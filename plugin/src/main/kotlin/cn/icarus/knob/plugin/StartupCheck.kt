package cn.icarus.knob.plugin

import android.content.Context
import android.content.pm.PackageManager

/**
 * 启动自检：插件加载后先跑一遍，PASSED 之前页面固定显示启动自检页、不启动
 * BleManager、不响应车控按键。PASSED 是终态：本进程内通过一次就不再查，通过时
 * 调 [onPassed]（MainPlugin 在那里连旋钮、开始驾驶数据）。
 *
 * 只读，不申请权限、不动车（完整自检会开关空调、调座椅，不能每次开机都自动
 * 跑）。查 CarControl.PERM_GROUPS 里 required 的那几组：权限全部授权、对应设备
 * 能初始化，才算通过。车机开机时可能没有界面，检查本身不依赖界面，权限早就
 * 授过的话照样通过、照样连旋钮。
 *
 * 状态只在主线程读写（后台检查跑完 post 回主线程再改）。
 */
internal class StartupCheck(private val plugin: MainPlugin, private val onPassed: () -> Unit) {

    enum class State { CHECKING, PASSED, FAILED }

    var state = State.CHECKING
        private set
    val passed get() = state == State.PASSED

    // 上一次检查的逐项结果，启动自检页据此显示哪项没过
    var items: List<PluginSelfTest.Item> = emptyList()
        private set

    private var checking = false

    // 已经初始化成功的设备（按 PERM_* 记）。没通过要重查时跳过它们：再调一次
    // initAcDevice 会把同一个监听器又注册一遍，温度变化就会回调两次。
    // 只在检查线程里读写，checking 保证同一时间只有一个检查线程。
    private val deviceOk = mutableSetOf<String>()

    /** 开始（或重新）检查。设备初始化是阻塞的 binder 调用，放后台线程，免得拖慢壳的启动。 */
    fun run() {
        if (passed || checking) return
        if (SIMULATE_CAR_CONTROL) {
            plugin.host?.log("🧪（模拟）不碰真车，跳过启动自检")
            pass()
            return
        }
        val ctx = plugin.appContext ?: return
        val ctrl = plugin.carControl ?: return
        checking = true
        state = State.CHECKING
        plugin.refreshCurrentPage()
        plugin.host?.log("🔍 启动自检：检查车控权限和设备")
        Thread {
            val result = try {
                checkItems(ctx, ctrl)
            } catch (e: Throwable) {
                listOf(PluginSelfTest.Item("启动自检", false, "${e.javaClass.simpleName} - ${e.message}"))
            }
            plugin.mainHandler.post {
                checking = false
                items = result
                result.forEach { plugin.host?.log("[启动自检] ${if (it.ok) "✅" else "❌"} ${it.name}：${it.detail}") }
                if (result.all { it.ok }) pass() else {
                    state = State.FAILED
                    plugin.host?.log("❌ 启动自检没通过：先不连旋钮，按启动自检页上的提示补权限")
                    plugin.refreshCurrentPage()
                }
            }
        }.start()
    }

    /** 每个 required 功能两项：权限齐不齐，齐了再看设备能不能初始化（不齐就不去碰设备）。 */
    private fun checkItems(ctx: Context, ctrl: CarControl): List<PluginSelfTest.Item> {
        val devices: Map<String, Pair<String, () -> Boolean>> = mapOf(
            CarControl.PERM_AC to ("初始化 AC 设备" to { ctrl.initAcDevice(ctx) }),
            CarControl.PERM_SETTING to ("初始化 Setting 设备" to { ctrl.initSettingDevice(ctx) }),
        )
        val items = mutableListOf<PluginSelfTest.Item>()
        CarControl.PERM_GROUPS.filter { it.required }.forEach { g ->
            val missing = (listOf(g.common) + g.signature)
                .filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            items += if (missing.isEmpty()) PluginSelfTest.Item("${g.label}权限", true, "已授权")
            else PluginSelfTest.Item("${g.label}权限", false,
                "缺 ${missing.joinToString("、") { it.removePrefix("android.permission.") }}")
            val (name, initDevice) = devices[g.common] ?: return@forEach
            items += when {
                missing.isNotEmpty() -> PluginSelfTest.Item(name, false, "缺权限，没去初始化")
                g.common in deviceOk -> PluginSelfTest.Item(name, true, "ok（上次已初始化）")
                initDevice() -> { deviceOk += g.common; PluginSelfTest.Item(name, true, "ok") }
                else -> PluginSelfTest.Item(name, false, "失败，详见日志")
            }
        }
        return items
    }

    private fun pass() {
        state = State.PASSED
        plugin.host?.log("✅ 启动自检通过，开始连接旋钮")
        onPassed()
    }
}
