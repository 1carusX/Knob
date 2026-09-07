package cn.icarus.knob.plugin

import android.content.Context
import android.graphics.Color
import android.hardware.bydauto.ac.BYDAutoAcDevice
import android.hardware.bydauto.setting.BYDAutoSettingDevice
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import cn.icarus.knob.api.KnobHost
import cn.icarus.knob.api.KnobPlugin
import cn.icarus.knob.api.Ui

/**
 * 插件入口。实现 KnobPlugin，持有车控引擎 + 自检引擎。
 * 车控/业务逻辑全部在本插件内，壳只负责加载和 UI。
 *
 * 按功能域路由：
 *  - onCommand：控制域（写操作）
 *  - onQuery  ：查询域（读操作）
 *  - onEvent  ：事件域（外部事件）
 *  - onPush   ：推送域（壳→插件主动推数据）
 * 新增功能只需在对应域内加一条 when 分支，接口无需改动。
 */
class MainPlugin : KnobPlugin {

    companion object {
        // SIMULATE_CAR_CONTROL 不在这里定义——两份构建变体（build.gradle.kts
        // 的 main/simulate 两个 source set）各自带一份 BuildFlags.kt，
        // 编译期就把值定死，一次构建同时出 plugin.dex（false，真实车控）
        // 和 plugin-simulate.dex（true，本地模拟，不碰真车，验证按键/
        // GATT/插件路由这条链路用），不用手动改这一个值来回切换重新编译。

        private const val PREFS_NAME = "knob_plugin_state"
        private const val KEY_PAGE = "page"
        private const val KEY_AC_MAIN = "ac_main"
        private const val KEY_AC_DEPUTY = "ac_deputy"
        private const val KEY_SEAT_LEVEL = "seat_level"

        // 座椅 5 档：-2/-1=通风2/1档，0=关闭，1/2=加热1/2档。负数=通风、
        // 正数=加热，这样触屏 ±/knob 屏幕的"居中对称"圆弧
        // （见 firmware/src/main.cpp 的 LV_ARC_MODE_SYMMETRICAL）都能直接
        // 用这一个有符号数表示，不用另外维护"档位+方向"两个字段。
        // CarControl 里还没有座椅加热的真实 HAL 接口，这几个档位目前只在
        // 插件本地模拟，没有对应的真实车控常量可以复用。
        private const val SEAT_LEVEL_MIN = -2
        private const val SEAT_LEVEL_MAX = 2
    }

    /**
     * 车辆状态：插件内唯一数据源，触屏页面和旋钮同步都读写这一份，不再有
     * 各自独立的状态。SIMULATE_CAR_CONTROL=true 时纯本地值；=false 时
     * 由 refreshAcTemperatures() 从 CarControl 真实读取覆盖（座椅暂时没有
     * 对应的真实读取接口，始终是本地值）。
     *
     * 分控开关不再单独缓存——它不是一个可以随便读/信的状态（没有监听
     * 接口，getAcTemperatureControlMode 也可能因为语音/按键早就跟这里
     * 缓存的不一样），改成完全由"当前停靠在哪个停靠点"决定：停靠在
     * ac_link 就代表要联动、ac_main/ac_deputy 就代表要分控，提交时无条件
     * 断言一次，见 commitAc()。acMainTemp 兼作联动模式下显示/编辑的温度
     * （分控关闭时主驾副驾理论上是同一个值）。
     */
    private data class VehicleState(
        var acMainTemp: Int = 22,
        var acDeputyTemp: Int = 22,
        var seatLevel: Int = 0,
    )

    private val tag = "KnobPlugin"

    private var carControl: CarControl? = null
    private var selfTest: PluginSelfTest? = null
    private var appContext: Context? = null
    private var host: KnobHost? = null

    private var pageContainer: ViewGroup? = null
    private val vehicleState = VehicleState()

    // 当前停靠点 id，持久化 + 同步给 knob 屏幕。这一份状态是旋钮和触屏
    // 共用的同一套状态机——物理旋钮的转/单击/长按和触屏的按钮只是同一套
    // 状态机的两种输入方式，不是各自独立的两份逻辑。knobCyclePages 是
    // 单击（物理 Tab / 触屏"下一个"按钮）能循环到的停靠点，首页不算在
    // 内——旋钮场景下没有"回菜单"的需求，首页只是触屏进入时的一个入口，
    // 靠页面里的按钮跳转，旋钮单击不会经过它。
    private var currentPageId: String = "ac_link"
    private val knobCyclePages = listOf("ac_link", "ac_main", "ac_deputy", "seat")

    private fun isAcStop(pageId: String) = pageId == "ac_link" || pageId == "ac_main" || pageId == "ac_deputy"

    // AC/Sensor 监听器的回调不在主线程上（AIDL 回调线程），这两个处理
    // 函数里要碰 View（refreshCurrentPage）和触发 knob 同步，必须先切回
    // 主线程，不然直接崩 CalledFromWrongThreadException。
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun init(context: Context, host: KnobHost) {
        Log.i(tag, "MainPlugin.init 调用")
        appContext = context
        this.host = host
        carControl = CarControl(host).also {
            it.onTemperatureChangedExternal = ::handleExternalTemperatureChange
            it.onLightIntensityChangedExternal = ::handleExternalLightIntensityChange
        }
        selfTest = PluginSelfTest(carControl!!, host)
        host.log("✅ 插件初始化完成（车控引擎已就绪）")
        restoreState()
        syncToKnob()
    }

    /**
     * 语音/按键等车机自身渠道改了温度——AC 监听器（onTemperatureChanged）
     * 触发的实时同步，跟触屏 ±/OK 走的是同一份 vehicleState、同一条
     * refreshCurrentPage() 路径，只是触发源不同。MAIN_DEPUTY 和 MAIN 都
     * 归到 acMainTemp（联动模式下这俩理论上是同一个值，ac_link 停靠点
     * 显示/编辑的就是 acMainTemp）。后排/车外温度不跟踪（UI 上没有对应
     * 的地方显示），忽略。
     */
    private fun handleExternalTemperatureChange(area: Int, value: Int) {
        mainHandler.post {
            val changed = when (area) {
                BYDAutoAcDevice.AC_TEMPERATURE_MAIN_DEPUTY, BYDAutoAcDevice.AC_TEMPERATURE_MAIN -> { vehicleState.acMainTemp = value; true }
                BYDAutoAcDevice.AC_TEMPERATURE_DEPUTY -> { vehicleState.acDeputyTemp = value; true }
                else -> false
            }
            if (!changed) return@post
            persistState()
            refreshCurrentPage()
        }
    }

    /**
     * 光照传感器等级变化（1 最亮~5 最暗）→ 换算成 knob 背光亮度百分比推
     * 过去。这个映射是拍的，没有实车调过具体数值，先能用，觉得不合适
     * 随时改这几个数字。
     */
    private fun brightnessForLightLevel(level: Int): Int = when (level) {
        1 -> 100 // >230 Lux，环境最亮
        2 -> 80
        3 -> 60
        4 -> 40
        5 -> 20  // <80 Lux，环境最暗，留一点亮度，不完全黑
        else -> 70
    }

    private fun handleExternalLightIntensityChange(level: Int) {
        val percent = brightnessForLightLevel(level)
        mainHandler.post {
            host?.pushToKnob(mapOf("brightness" to percent))
        }
    }

    /** 统一结果 Map：success 是否成功 / code 返回码 / value 结果 / message 说明。null 的 code/value 不放入。 */
    private fun resultMap(success: Boolean, code: Any?, value: Any? = null, message: String = ""): Map<String, Any> {
        val m = mutableMapOf<String, Any>("success" to success, "message" to message)
        if (code != null) m["code"] = code
        if (value != null) m["value"] = value
        return m
    }

    /** 把返回码 Int? 包装成统一结果 Map（command 用） */
    private fun codeResult(code: Int?, okMsg: String, failMsg: String): Map<String, Any> =
        if (code != null) resultMap(true, code, message = okMsg)
        else resultMap(false, null, message = failMsg)

    /**
     * 控制域：所有写操作/命令。
     * 命令约定：
     *   "init.all"          初始化 Setting + AC + Sensor 设备
     *   "seat.ventilating"  params: {state: Int(1=关,2=低,3=高)}
     *   "ac.temperature"    params: {area: Int(0-3), value: Int(17-33)}
     *   "ac.power"          params: {on: Boolean}
     *   "ac.windlevel"      params: {level: Int(0-7)}
     *   "ac.windmode"       params: {mode: Int}
     *   "ac.cycle"          params: {inner: Boolean}
     *   "ac.separate"       params: {separate: Boolean}
     */
    override fun onCommand(command: String, params: Map<String, Any>): Map<String, Any>? {
        val ctrl = carControl ?: return null
        val context = appContext ?: return null
        return try {
            when (command) {
                "init.all" -> {
                    ctrl.log("📦 初始化设备（Setting + AC + Sensor）")
                    val s = ctrl.initSettingDevice(context)
                    val a = ctrl.initAcDevice(context)
                    val se = ctrl.initSensorDevice(context)
                    // Sensor 的监听器只在光照等级变化时才会触发回调，初始化
                    // 完主动读一次当前等级、立刻推一次背光，不然要等第一次
                    // 光照变化才会有初始亮度，之前一直是固件默认的最大亮度。
                    if (se) {
                        ctrl.getLightIntensity(context)?.let { handleExternalLightIntensityChange(it) }
                    }
                    if (s && a && se) resultMap(true, 0, message = "初始化成功")
                    else resultMap(false, null, message = "初始化失败")
                }
                "seat.ventilating" -> {
                    val state = (params["state"] as? Number)?.toInt()
                        ?: return resultMap(false, null, message = "缺少 state")
                    codeResult(ctrl.setDriverSeatVentilating(context, state), "座椅通风执行完成", "座椅通风执行失败")
                }
                "ac.temperature" -> {
                    val area = (params["area"] as? Number)?.toInt() ?: BYDAutoAcDevice.AC_TEMPERATURE_MAIN
                    val value = (params["value"] as? Number)?.toInt()
                        ?: return resultMap(false, null, message = "缺少 value")
                    codeResult(ctrl.setAcTemperature(context, area, value), "设置温度执行完成", "设置温度失败")
                }
                "ac.power" ->
                    codeResult(ctrl.setAcPower(context, (params["on"] as? Boolean) ?: false), "空调开关执行完成", "空调开关失败")
                "ac.windlevel" -> {
                    val level = (params["level"] as? Number)?.toInt()
                        ?: return resultMap(false, null, message = "缺少 level")
                    codeResult(ctrl.setAcWindLevel(context, level), "设置风量执行完成", "设置风量失败")
                }
                "ac.windmode" -> {
                    val mode = (params["mode"] as? Number)?.toInt()
                        ?: return resultMap(false, null, message = "缺少 mode")
                    codeResult(ctrl.setAcWindMode(context, mode), "设置风向执行完成", "设置风向失败")
                }
                "ac.cycle" ->
                    codeResult(ctrl.setAcCycleMode(context, (params["inner"] as? Boolean) ?: false), "设置循环执行完成", "设置循环失败")
                "ac.separate" ->
                    codeResult(ctrl.setAcTemperatureControlMode(context, (params["separate"] as? Boolean) ?: false), "设置分控执行完成", "设置分控失败")
                else -> {
                    ctrl.log("⚠️ 未知命令: $command")
                    null
                }
            }
        } catch (e: Throwable) {
            ctrl.log("❌ onCommand($command) 异常: ${e.message}")
            resultMap(false, null, message = "异常: ${e.message}")
        }
    }

    /**
     * 查询域：所有读操作。
     * 查询约定：
     *   "perm.check"        检查 BYDAUTO 权限
     *   "selfTest"          一键全量自检（value=结果文本）
     *   "seat.ventilating"  读取主驾座椅通风状态（value=Int）
     *   "ac.temperature"    params: {area: Int} → 读取空调温度（value=Int）
     *   "seat.probeMethods" 反射列举 Setting 设备上的全部方法，只打日志不调用
     *   "seat.checkFeatures" 查座椅加热/通风 4 个 feature 是否配置为支持
     */
    override fun onQuery(query: String, params: Map<String, Any>): Map<String, Any>? {
        val ctrl = carControl ?: return null
        val context = appContext ?: return null
        return try {
            when (query) {
                "perm.check" -> {
                    ctrl.log("📋 检查 BYDAUTO 权限")
                    ctrl.checkBydPermissions(context)
                    resultMap(true, 0, message = "权限检查完成，详见日志")
                }
                "seat.probeMethods" -> {
                    ctrl.probeSeatFeatureMethods(context)
                    resultMap(true, 0, message = "扫描完成，详见日志")
                }
                "seat.checkFeatures" -> {
                    ctrl.checkSeatFeatures(context)
                    resultMap(true, 0, message = "检查完成，详见日志")
                }
                "selfTest" -> {
                    selfTest?.runAll(context)
                    resultMap(true, 0, value = selfTest?.lastResult() ?: "自检未初始化", message = "自检完成")
                }
                "seat.ventilating" -> {
                    val v = ctrl.getDriverSeatVentilating(context)
                    if (v != null) resultMap(true, 0, value = v, message = "读取成功")
                    else resultMap(false, null, message = "读取失败")
                }
                "ac.temperature" -> {
                    val area = (params["area"] as? Number)?.toInt() ?: BYDAutoAcDevice.AC_TEMPERATURE_MAIN
                    val v = ctrl.getAcTemperature(context, area)
                    if (v != null) resultMap(true, 0, value = v, message = "读取成功")
                    else resultMap(false, null, message = "读取失败")
                }
                else -> {
                    ctrl.log("⚠️ 未知查询: $query")
                    null
                }
            }
        } catch (e: Throwable) {
            ctrl.log("❌ onQuery($query) 异常: ${e.message}")
            resultMap(false, null, message = "异常: ${e.message}")
        }
    }

    /**
     * 事件域：外部事件。
     * 事件约定：
     *   "key"           params: {code: Int, action: Int}  壳转发的所有按键（含系统返回键）
     *   "ui.bind"       params: {container: ViewGroup}   壳交出页面容器，开始渲染
     *   "ui.unbind"     Activity 销毁，断开容器与 View 树的持有
     */
    override fun onEvent(event: String, params: Map<String, Any>): Boolean {
        return try {
            when (event) {
                "ui.bind" -> {
                    val container = params["container"] as? ViewGroup ?: return false
                    bindUi(container)
                    true
                }
                "ui.unbind" -> {
                    unbindUi()
                    true
                }
                "key" -> {
                    val code = (params["code"] as? Number)?.toInt() ?: return false
                    val action = (params["action"] as? Number)?.toInt() ?: return false
                    // value：只有旋钮长按（确认）才会带上，是旋钮本地编辑
                    // 到的最终值——旋钮转动时不再逐档通知手机，只有长按
                    // 才把结果发过来。
                    val value = (params["value"] as? Number)?.toInt()
                    handleKey(code, action, value)
                }
                else -> false
            }
        } catch (e: Throwable) {
            Log.e(tag, "onEvent($event) 异常: ${e.message}")
            false
        }
    }

    /**
     * 推送域：壳在任意时刻主动推数据给插件。
     * type 约定（新增类型只需加一条 when 分支）：
     *   "bluetooth"  data: {connected: Boolean, name: String?, mac: String?}  蓝牙连接状态
     *   "state"      data: {model: String, android: String, ...}              壳/设备状态
     *   "hardware"   data: {event: String, ...}                               硬件事件流（旋钮/按键原始值）
     *   "config"     data: {key: Any, ...}                                    配置项
     */
    override fun onPush(type: String, data: Map<String, Any>) {
        val ctrl = carControl
        try {
            when (type) {
                "bluetooth" -> {
                    val connected = data["connected"] as? Boolean ?: false
                    ctrl?.log("📡 蓝牙状态: ${if (connected) "已连接" else "断开"} " +
                        "name=${data["name"]} mac=${data["mac"]}")
                    Log.i(tag, "onPush bluetooth connected=$connected")
                    // 刚连上（含断线重连）时主动推一次当前状态给屏幕——不然
                    // 插件 init() 那次同步大概率因为当时 GATT 还没连上而白白
                    // 失败，用户不主动操作的话屏幕会一直停在出厂默认画面。
                    if (connected) syncToKnob()
                }
                "state" -> {
                    ctrl?.log("ℹ️ 壳状态: ${data.map { "${it.key}=${it.value}" }.joinToString(", ")}")
                    Log.i(tag, "onPush state done")
                }
                "hardware" -> {
                    ctrl?.log("🎛 硬件事件: $data")
                    Log.i(tag, "onPush hardware: $data")
                }
                "config" -> {
                    ctrl?.log("⚙️ 配置更新: $data")
                    Log.i(tag, "onPush config done")
                }
                else -> {
                    Log.i(tag, "onPush 未知类型: $type, data=$data")
                }
            }
        } catch (e: Throwable) {
            Log.e(tag, "onPush($type) 异常: ${e.message}")
        }
    }

    /**
     * 按键处理，返回是否消费。
     *
     * 旋钮映射：Tab=切下一个停靠点（ac_link/ac_main/ac_deputy/seat 循环），
     * Enter=确认（对应触屏页面上的 OK）。旋钮转动本身完全在固件本地处理、
     * 不再上报给手机——长按 Enter 时才把旋钮本地编辑到的最终值（value）
     * 一起带过来，直接拿去应用，不需要手机在转动过程中跟着增量调整。
     * 系统返回键不再有特殊处理——停靠点之间是平级切换，没有"返回上一个"
     * 这个概念，交给壳/系统默认处理。按下和抬起都要消费，只吃一半会让
     * 系统按键状态错乱。
     */
    private fun handleKey(code: Int, action: Int, value: Int?): Boolean {
        when (code) {
            KeyEvent.KEYCODE_TAB -> {
                if (action == KeyEvent.ACTION_UP) advanceStop()
                return true
            }
            KeyEvent.KEYCODE_ENTER -> {
                if (action == KeyEvent.ACTION_UP) applyPrimaryValue(value)
                return true
            }
        }
        Log.i(tag, "onEvent key code=$code action=$action（未处理）")
        return false
    }

    /**
     * 切到循环列表里的下一个停靠点——物理旋钮单击和触屏"下一个"按钮
     * 都调这一个函数，保证两条输入路径走的是同一套状态机。
     */
    private fun advanceStop() {
        val idx = knobCyclePages.indexOf(currentPageId)
        val next = knobCyclePages[if (idx == -1) 0 else (idx + 1) % knobCyclePages.size]
        host?.log("🔀 下一个停靠点 -> $next")
        switchToPage(next)
    }

    /**
     * Enter/OK：应用当前停靠点的主要数值。
     * @param knobValue 旋钮本地编辑到的最终值（长按时固件带过来的），
     *   写入 vehicleState 后再提交——旋钮转动期间手机完全不知道中间值，
     *   只在这一下才知道最终结果。knobValue 为 null（比如触屏 OK 按钮
     *   触发，本地值早就在 onChange 里改好了）时直接沿用 vehicleState
     *   里已有的值。
     */
    private fun applyPrimaryValue(knobValue: Int?) {
        when (currentPageId) {
            "ac_link" -> {
                if (knobValue != null) {
                    vehicleState.acMainTemp = knobValue.coerceIn(BYDAutoAcDevice.AC_TEMP_IN_CELSIUS_MIN, BYDAutoAcDevice.AC_TEMP_IN_CELSIUS_MAX)
                }
                persistState()
                commitAc(separate = false, area = BYDAutoAcDevice.AC_TEMPERATURE_MAIN_DEPUTY, value = vehicleState.acMainTemp)
                refreshCurrentPage()
            }
            "ac_main" -> {
                if (knobValue != null) {
                    vehicleState.acMainTemp = knobValue.coerceIn(BYDAutoAcDevice.AC_TEMP_IN_CELSIUS_MIN, BYDAutoAcDevice.AC_TEMP_IN_CELSIUS_MAX)
                }
                persistState()
                commitAc(separate = true, area = BYDAutoAcDevice.AC_TEMPERATURE_MAIN, value = vehicleState.acMainTemp)
                refreshCurrentPage()
            }
            "ac_deputy" -> {
                if (knobValue != null) {
                    vehicleState.acDeputyTemp = knobValue.coerceIn(BYDAutoAcDevice.AC_TEMP_IN_CELSIUS_MIN, BYDAutoAcDevice.AC_TEMP_IN_CELSIUS_MAX)
                }
                persistState()
                commitAc(separate = true, area = BYDAutoAcDevice.AC_TEMPERATURE_DEPUTY, value = vehicleState.acDeputyTemp)
                refreshCurrentPage()
            }
            "seat" -> {
                if (knobValue != null) {
                    vehicleState.seatLevel = knobValue.coerceIn(SEAT_LEVEL_MIN, SEAT_LEVEL_MAX)
                }
                persistState()
                commitSeatLevel(vehicleState.seatLevel)
                refreshCurrentPage()
            }
            else -> Unit
        }
    }

    /** 座椅档位 -> 人能看懂的文字，UI 标签和日志都用这个，保证显示一致。 */
    private fun seatLevelLabel(level: Int): String = when {
        level <= -2 -> "通风2档"
        level == -1 -> "通风1档"
        level == 0 -> "关闭"
        level == 1 -> "加热1档"
        else -> "加热2档"
    }

    /** 座椅 OK 键：真正下发座椅通风/加热。CarControl 还没有真实的座椅加热接口，先只做模拟。 */
    private fun commitSeatLevel(level: Int) {
        if (SIMULATE_CAR_CONTROL) {
            host?.log("🧪（模拟）座椅 -> ${seatLevelLabel(level)}")
            return
        }
        host?.log("⚠️ 座椅真实车控接口还没实现（CarControl 缺座椅加热的 HAL 封装），只做了本地模拟：${seatLevelLabel(level)}")
    }

    /**
     * 把当前停靠点同步给旋钮屏幕（走 host.pushToKnob -> BLE -> ESP32），
     * 作为固件本地编辑的起点。这里直接按当前停靠点拼一个 Map，不同停靠点
     * 带的字段不完全一样——不再用一个固定形状的结构体强行统一，以后加/
     * 改某个停靠点的字段只影响它自己这一条分支。"layout" 字段告诉固件
     * 该用哪种画法（不是靠"有没有某个字段"去反推），"value_main"/
     * "value_deputy" 这两个字段名固定指代主驾/副驾，不用无指向的
     * value/value2。此后转动旋钮只改固件本地的副本，不会再触发这个同步，
     * 直到长按确认把最终值带回来、或者切停靠点/重新进页面时再推一次新的
     * 起点。min<0（座椅）时固件会画成从中间向两侧扩展的圆弧，见
     * firmware/src/main.cpp。
     */
    private fun syncToKnob() {
        val data: Map<String, Any> = when (currentPageId) {
            "ac_link" -> mapOf(
                "layout" to "ac_link",
                "title" to "联动",
                "value" to vehicleState.acMainTemp,
                "min" to BYDAutoAcDevice.AC_TEMP_IN_CELSIUS_MIN,
                "max" to BYDAutoAcDevice.AC_TEMP_IN_CELSIUS_MAX,
            )
            "ac_main" -> mapOf(
                "layout" to "ac_split_main",
                "title" to "主驾",
                "value_main" to vehicleState.acMainTemp,
                "value_deputy" to vehicleState.acDeputyTemp,
                "min" to BYDAutoAcDevice.AC_TEMP_IN_CELSIUS_MIN,
                "max" to BYDAutoAcDevice.AC_TEMP_IN_CELSIUS_MAX,
            )
            "ac_deputy" -> mapOf(
                "layout" to "ac_split_deputy",
                "title" to "副驾",
                "value_main" to vehicleState.acMainTemp,
                "value_deputy" to vehicleState.acDeputyTemp,
                "min" to BYDAutoAcDevice.AC_TEMP_IN_CELSIUS_MIN,
                "max" to BYDAutoAcDevice.AC_TEMP_IN_CELSIUS_MAX,
            )
            "seat" -> mapOf(
                "layout" to "seat",
                "title" to seatLevelLabel(vehicleState.seatLevel),
                "value" to vehicleState.seatLevel,
                "min" to SEAT_LEVEL_MIN,
                "max" to SEAT_LEVEL_MAX,
            )
            else -> mapOf("layout" to "none", "title" to "首页", "value" to 0, "min" to 0, "max" to 0)
        }
        host?.pushToKnob(data)
    }

    // ==================== 状态持久化（进程重启后恢复页面 + 车辆状态） ====================

    private fun persistState() {
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_PAGE, currentPageId)
            .putInt(KEY_AC_MAIN, vehicleState.acMainTemp)
            .putInt(KEY_AC_DEPUTY, vehicleState.acDeputyTemp)
            .putInt(KEY_SEAT_LEVEL, vehicleState.seatLevel)
            .apply()
    }

    private fun restoreState() {
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentPageId = prefs.getString(KEY_PAGE, "ac_link") ?: "ac_link"
        // 之前的版本用的是 "car"/"seat" 两个页面 id，这台测试机上很可能
        // 还残留着旧值——不是这次改动要处理的"不可能场景"，是几乎必然
        // 会在自己手上碰到的情况，兜底回默认停靠点，不然 pages[currentPageId]
        // 会取不到构建函数，屏幕直接空白。
        if (currentPageId !in pages) currentPageId = "ac_link"
        vehicleState.acMainTemp = prefs.getInt(KEY_AC_MAIN, 22)
        vehicleState.acDeputyTemp = prefs.getInt(KEY_AC_DEPUTY, 22)
        vehicleState.seatLevel = prefs.getInt(KEY_SEAT_LEVEL, 0)
    }

    // ==================== 页面渲染（纯代码构建 View，避免资源 ID 冲突） ====================

    /** 页面注册表：pageId → 构建函数。新增页面只需在此加一行，并写对应的构建函数。 */
    private val pages: Map<String, () -> View> = mapOf(
        "home" to { buildHomePage() },
        "ac_link" to { buildAcPage() },
        "ac_main" to { buildAcPage() },
        "ac_deputy" to { buildAcPage() },
        "seat" to { buildSeatPage() },
    )

    /** 壳交出页面容器，插件开始渲染当前页面（Activity 重建也会重新调用，直接按当前状态重绘）。 */
    private fun bindUi(container: ViewGroup) {
        pageContainer = container
        Log.i(tag, "ui.bind: 页面容器已就绪，当前页面=$currentPageId")
        if (isAcStop(currentPageId)) refreshAcTemperatures()
        refreshCurrentPage()
    }

    /** Activity 销毁，断开容器持有——插件是进程级单例，不能强持有已销毁的 Activity。 */
    private fun unbindUi() {
        pageContainer?.removeAllViews()
        pageContainer = null
        Log.i(tag, "ui.unbind: 页面容器已解绑")
    }

    /**
     * 真正切换到另一个停靠点（跟同停靠点内数值调整的 refreshCurrentPage
     * 区分开）：更新 currentPageId、持久化、如果切到空调类停靠点顺带刷新
     * 一次真实车况，最后交给 refreshCurrentPage() 重绘并同步给 knob。
     * 不管是旋钮 Tab/触屏"下一个"按钮触发的，还是首页菜单直接跳转触发
     * 的，都走这一个函数，保证所有输入路径下 knob 收到的通知是一致的。
     */
    private fun switchToPage(pageId: String) {
        if (!pages.containsKey(pageId)) {
            Log.w(tag, "未知页面: $pageId")
            return
        }
        currentPageId = pageId
        if (isAcStop(pageId)) refreshAcTemperatures()
        persistState()
        refreshCurrentPage()
    }

    /** 用当前状态重绘 currentPageId 对应的页面，并同步给 knob——数值调整（不切页）时用这个。 */
    private fun refreshCurrentPage() {
        val container = pageContainer ?: return
        val builder = pages[currentPageId] ?: return
        container.removeAllViews()
        container.addView(builder(), ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        syncToKnob()
    }

    /** 首页：跳转到空调（联动）页 / 座椅页（纯菜单，不接旋钮 Tab 循环） */
    private fun buildHomePage(): View {
        val ctx = appContext!!
        return Ui.vStack(ctx).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            addView(Ui.title(ctx, "🎛 插件首页"))
            addView(Ui.hint(ctx, "旋钮单击在联动/主驾/副驾/座椅 4 个停靠点间循环切换\n首页只能触屏进入"))
            addView(Ui.space(ctx, 0, 12))
            addView(Ui.btn(ctx, "❄️ 去空调页") { switchToPage("ac_link") })
            addView(Ui.btn(ctx, "🪑 去座椅页") { switchToPage("seat") })
        }
    }

    /**
     * 从车况刷新主驾/副驾温度（进空调类停靠点时调，停靠点内部调整不调）。
     * 只读 MAIN/DEPUTY 这两个 area——MAIN_DEPUTY 对 getTemprature 来说是
     * 无效参数（只对 setAcTemperature 的 type 有效，官方文档写明了，这也
     * 是之前那个"-21亿"读数的根因），不读它；联动停靠点显示/编辑的
     * acMainTemp 已经靠这里刷新。分控开关不读也不缓存，见 VehicleState
     * 的注释。
     */
    private fun refreshAcTemperatures() {
        if (SIMULATE_CAR_CONTROL) {
            host?.log("🧪（模拟）跳过读取车况，沿用当前本地值")
            return
        }
        val ctrl = carControl ?: return
        val context = appContext ?: return
        ctrl.getAcTemperature(context, BYDAutoAcDevice.AC_TEMPERATURE_MAIN)?.let { vehicleState.acMainTemp = it }
        ctrl.getAcTemperature(context, BYDAutoAcDevice.AC_TEMPERATURE_DEPUTY)?.let { vehicleState.acDeputyTemp = it }
        persistState()
    }

    /**
     * 空调三个停靠点共用一个构建函数，按 currentPageId 决定画法——跟旋钮
     * 屏幕的 layout 分发是同一个思路：ac_link 只有联动一组温度框；
     * ac_main/ac_deputy 各自的主要那组可编辑，另一侧只读展示做参考，
     * 不提供 ± /OK。
     */
    private fun buildAcPage(): View {
        val ctx = appContext!!
        val title = when (currentPageId) {
            "ac_main" -> "❄️ 空调温度（分控·主驾）"
            "ac_deputy" -> "❄️ 空调温度（分控·副驾）"
            else -> "❄️ 空调温度（联动）"
        }
        return Ui.vStack(ctx).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#E3F2FD"))
            addView(Ui.title(ctx, title))
            addView(Ui.space(ctx, 0, 12))
            when (currentPageId) {
                "ac_link" -> addView(buildTempRow(
                    label = "联动温度",
                    value = vehicleState.acMainTemp,
                    onChange = { vehicleState.acMainTemp = it; persistState(); refreshCurrentPage() },
                    onOk = {
                        commitAc(separate = false, area = BYDAutoAcDevice.AC_TEMPERATURE_MAIN_DEPUTY, value = vehicleState.acMainTemp)
                        refreshCurrentPage()
                    }
                ))
                "ac_main" -> {
                    addView(buildTempRow(
                        label = "主驾温度",
                        value = vehicleState.acMainTemp,
                        onChange = { vehicleState.acMainTemp = it; persistState(); refreshCurrentPage() },
                        onOk = {
                            commitAc(separate = true, area = BYDAutoAcDevice.AC_TEMPERATURE_MAIN, value = vehicleState.acMainTemp)
                            refreshCurrentPage()
                        }
                    ))
                    addView(Ui.space(ctx, 0, 8))
                    addView(Ui.label(ctx, "副驾温度 ${vehicleState.acDeputyTemp}°C（参考，去副驾停靠点调整）"))
                }
                "ac_deputy" -> {
                    addView(buildTempRow(
                        label = "副驾温度",
                        value = vehicleState.acDeputyTemp,
                        onChange = { vehicleState.acDeputyTemp = it; persistState(); refreshCurrentPage() },
                        onOk = {
                            commitAc(separate = true, area = BYDAutoAcDevice.AC_TEMPERATURE_DEPUTY, value = vehicleState.acDeputyTemp)
                            refreshCurrentPage()
                        }
                    ))
                    addView(Ui.space(ctx, 0, 8))
                    addView(Ui.label(ctx, "主驾温度 ${vehicleState.acMainTemp}°C（参考，去主驾停靠点调整）"))
                }
            }
            addView(Ui.space(ctx, 0, 12))
            addView(Ui.btn(ctx, "🔀 下一个") { advanceStop() })
            addView(Ui.btn(ctx, "← 返回首页") { switchToPage("home") })
        }
    }

    /** 一组"标签 － 数值 ＋ OK"。－/＋ 只改本地待提交值，OK 才真正下发。 */
    private fun buildTempRow(label: String, value: Int, onChange: (Int) -> Unit, onOk: () -> Unit): View {
        val ctx = appContext!!
        return Ui.card(ctx).apply {
            addView(Ui.label(ctx, label))
            addView(Ui.hStack(ctx).apply {
                addView(Ui.btn(ctx, "－") {
                    onChange((value - 1).coerceIn(BYDAutoAcDevice.AC_TEMP_IN_CELSIUS_MIN, BYDAutoAcDevice.AC_TEMP_IN_CELSIUS_MAX))
                })
                addView(Ui.text(ctx, "${value}°C", size = 18f, bold = true))
                addView(Ui.btn(ctx, "＋") {
                    onChange((value + 1).coerceIn(BYDAutoAcDevice.AC_TEMP_IN_CELSIUS_MIN, BYDAutoAcDevice.AC_TEMP_IN_CELSIUS_MAX))
                })
                addView(Ui.btn(ctx, "OK") { onOk() })
            })
        }
    }

    /**
     * 空调停靠点的提交：先无条件断言这个停靠点要求的分控状态，再下发
     * 温度——不比较本地缓存是不是已经是这个状态（分控没有监听接口，缓存
     * 可能因为语音/按键早就跟车况不一致了），每次提交都强制断言一次最
     * 安全。顺序不能反：分控没真正切过去之前先写 area=MAIN/DEPUTY 的
     * 温度大概率会被联动模式吞掉或不生效。
     */
    private fun commitAc(separate: Boolean, area: Int, value: Int) {
        if (SIMULATE_CAR_CONTROL) {
            host?.log("🧪（模拟）setAcTemperatureControlMode separate=$separate; setAcTemperature area=$area value=$value")
            return
        }
        val ctrl = carControl ?: return
        val context = appContext ?: return
        ctrl.setAcTemperatureControlMode(context, separate)
        ctrl.setAcTemperature(context, area, value)
    }

    /** 座椅页：下一个停靠点循环回联动页 / 首页菜单（占位页面，还没接真实座椅加热状态）。 */
    private fun buildSeatPage(): View {
        val ctx = appContext!!
        return Ui.vStack(ctx).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#E8F5E9"))
            addView(Ui.title(ctx, "🪑 座椅"))
            addView(Ui.space(ctx, 0, 12))
            addView(buildSeatRow())
            addView(Ui.space(ctx, 0, 12))
            // 临时调试按钮：座椅加热/通风隐藏接口探测，结果只打日志，不调用
            // 任何真实车控方法——排查完可以删掉。
            addView(Ui.btn(ctx, "🔍 扫描 Setting 设备方法") {
                carControl?.probeSeatFeatureMethods(appContext!!)
            })
            addView(Ui.btn(ctx, "🔍 查座椅加热/通风 Feature") {
                carControl?.checkSeatFeatures(appContext!!)
            })
            addView(Ui.space(ctx, 0, 8))
            // 反射扫描 + 反编译一款同类竞品应用都确认了 setSeatHeatingState(seat, state)/
            // getSeatHeatingState(seat) 这套接口真实存在，state 官方常量是
            // SEAT_HEATING_OFF/LOW/HIGH=1/2/3——这几个按钮就是拿这三个值
            // 实际调一下，看物理座椅有没有反应，确认参数含义对不对。
            // 排查完可以删掉。
            addView(Ui.btn(ctx, "🔥 主驾加热-关(1)") {
                carControl?.setSeatHeating(appContext!!, BYDAutoSettingDevice.DRIVER_SEAT, 1)
            })
            addView(Ui.btn(ctx, "🔥 主驾加热-低(2)") {
                carControl?.setSeatHeating(appContext!!, BYDAutoSettingDevice.DRIVER_SEAT, 2)
            })
            addView(Ui.btn(ctx, "🔥 主驾加热-高(3)") {
                carControl?.setSeatHeating(appContext!!, BYDAutoSettingDevice.DRIVER_SEAT, 3)
            })
            addView(Ui.btn(ctx, "🔍 读主驾加热状态") {
                carControl?.getSeatHeating(appContext!!, BYDAutoSettingDevice.DRIVER_SEAT)
            })
            addView(Ui.space(ctx, 0, 12))
            addView(Ui.btn(ctx, "🔀 下一个") { advanceStop() })
            addView(Ui.btn(ctx, "← 返回首页") { switchToPage("home") })
        }
    }

    /**
     * 座椅的"标签 － 档位 ＋ OK"，跟 buildTempRow 不共用是因为这里显示的
     * 是"通风2档"这种文字档位，不是"数值+单位"，格式完全不同。
     * －/＋ 只改本地待提交档位，OK 才真正下发。
     */
    private fun buildSeatRow(): View {
        val ctx = appContext!!
        val level = vehicleState.seatLevel
        return Ui.card(ctx).apply {
            addView(Ui.label(ctx, "座椅"))
            addView(Ui.hStack(ctx).apply {
                addView(Ui.btn(ctx, "－") {
                    vehicleState.seatLevel = (level - 1).coerceIn(SEAT_LEVEL_MIN, SEAT_LEVEL_MAX)
                    persistState()
                    refreshCurrentPage()
                })
                addView(Ui.text(ctx, seatLevelLabel(level), size = 18f, bold = true))
                addView(Ui.btn(ctx, "＋") {
                    vehicleState.seatLevel = (level + 1).coerceIn(SEAT_LEVEL_MIN, SEAT_LEVEL_MAX)
                    persistState()
                    refreshCurrentPage()
                })
                addView(Ui.btn(ctx, "OK") { commitSeatLevel(vehicleState.seatLevel) })
            })
        }
    }

    override fun onDestroy() {
        Log.i(tag, "MainPlugin.onDestroy")
        carControl?.unregisterListeners()
    }
}
