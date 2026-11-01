package cn.icarus.knob.plugin

import android.content.Context
import android.hardware.bydauto.ac.BYDAutoAcDevice
import android.hardware.bydauto.setting.BYDAutoSettingDevice
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import cn.icarus.knob.api.KnobHost
import cn.icarus.knob.api.KnobPlugin
import cn.icarus.knob.plugin.page.SelfTestPage
import cn.icarus.knob.plugin.page.buildAcPage
import cn.icarus.knob.plugin.page.buildHomePage
import cn.icarus.knob.plugin.page.buildOtaPage
import cn.icarus.knob.plugin.page.buildSeatPage
import cn.icarus.knob.plugin.page.buildStartupPage
import kotlin.math.roundToInt

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
 *
 * 这里只留停靠点状态机（当前停靠点、车辆状态、旋钮/触屏输入、同步给旋钮）、
 * 持久化和页面路由，其余各管一块、各自一个文件：
 *  - CarCommands.kt   ：onCommand/onQuery 的命令清单
 *  - CarActions.kt    ：停靠点的车控读写（空调、座椅）
 *  - CarPermissions.kt：车控运行时权限
 *  - StartupCheck.kt  ：启动自检
 *  - KnobOta.kt       ：旋钮固件自动升级
 *  - DriveFeed.kt     ：旋钮驾驶页的数据源
 *  - page/            ：触屏页面，一个页面一个文件（见 page/PageParts.kt 开头）
 * 别的文件要读写的成员是 internal。
 */
class MainPlugin : KnobPlugin {

    companion object {
        // SIMULATE_CAR_CONTROL 不在这里定义——两份构建变体（build.gradle.kts
        // 的 main/simulate 两个 source set）各自带一份 BuildFlags.kt，
        // 编译期就把值定死，一次构建同时出 plugin.dex（false，真实车控）
        // 和 plugin-simulate.dex（true，本地模拟，不碰真车，验证按键/
        // GATT/插件路由这条链路用），不用手动改这一个值来回切换重新编译。

        internal const val PREFS_NAME = "knob_plugin_state"
        private const val KEY_PAGE = "page"
        private const val KEY_AC_MAIN = "ac_main"
        private const val KEY_AC_DEPUTY = "ac_deputy"
        private const val KEY_SEAT_LEVEL_DRIVER = "seat_level_driver"
        private const val KEY_SEAT_LEVEL_PASSENGER = "seat_level_passenger"
        private const val KEY_SEAT_LEVEL_REAR_LEFT = "seat_level_rear_left"
        private const val KEY_SEAT_LEVEL_REAR_RIGHT = "seat_level_rear_right"
        private const val KEY_CURRENT_SEAT_INDEX = "current_seat_index"
        private const val KEY_AC_SPLIT_SHOWING_MAIN = "ac_split_showing_main"

        // 座椅 5 档：-2/-1=通风2/1档，0=关闭，1/2=加热1/2档。负数=通风、
        // 正数=加热，这样触屏 ±/knob 屏幕的"居中对称"圆弧
        // （见 firmware/src/ui/page_seat.cpp）都能直接
        // 用这一个有符号数表示，不用另外维护"档位+方向"两个字段——反编译
        // 一款同类竞品 App 确认了它家协议也是同样的"符号选属性、数值选
        // 档位"思路，只是 0 单独作为两个属性共用的"关闭"，不占用它家的
        // ±1（它家 OFF 是 ±1，这里统一成 0）。
        internal const val SEAT_LEVEL_MIN = -2
        internal const val SEAT_LEVEL_MAX = 2

        // 旋钮固件单击触发的键码（固件把单击/双击反过来分配了，见
        // firmware/src/ble.h 的说明），不对应真实 Android 按键，数值跟
        // 那边的 KEYCODE_CYCLE_SUB 对齐。
        private const val KEYCODE_CYCLE_SUB = 67
    }

    /**
     * 车辆状态：插件内唯一数据源，触屏页面和旋钮同步都读写这一份，不再有
     * 各自独立的状态。SIMULATE_CAR_CONTROL=true 时纯本地值；=false 时
     * 由 CarActions.refreshAcTemperatures() 从 CarControl 真实读取覆盖（座椅暂时没有
     * 对应的真实读取接口，始终是本地值）。
     *
     * 分控开关不再单独缓存——它不是一个可以随便读/信的状态（没有监听
     * 接口，getAcTemperatureControlMode 也可能因为语音/按键早就跟这里
     * 缓存的不一样），改成完全由"当前停靠在哪个停靠点"决定：停靠在
     * ac_link 就代表要联动、ac_split 就代表要分控，提交时无条件
     * 断言一次，见 CarActions.commitAc()。acMainTemp 兼作联动模式下显示/编辑的温度
     * （分控关闭时主驾副驾理论上是同一个值）。
     */
    internal data class VehicleState(
        var acMainTemp: Int = 22,
        var acDeputyTemp: Int = 22,
        var seatLevelDriver: Int = 0,
        var seatLevelPassenger: Int = 0,
        var seatLevelRearLeft: Int = 0,
        var seatLevelRearRight: Int = 0,
    )

    /**
     * "座椅"这一个停靠点内部要在 4 个座位间切换（旋钮单击/触屏按钮，见
     * [cycleSeat]），转动/长按永远只作用于 [currentSeatIndex] 指向的这一个——
     * 不用再把"主驾座椅""副驾座椅"…拆成 4 个独立停靠点。座位编号见
     * CarControl 类注释：1=主驾/2=副驾/3=后左/4=后右。
     */
    internal data class SeatSlot(val id: Int, val label: String)
    internal val seatSlots = listOf(
        SeatSlot(BYDAutoSettingDevice.DRIVER_SEAT, "主驾"),
        SeatSlot(BYDAutoSettingDevice.PASSENGER_SEAT, "副驾"),
        SeatSlot(CarControl.REAR_LEFT_SEAT, "左后"),
        SeatSlot(CarControl.REAR_RIGHT_SEAT, "右后"),
    )
    internal var currentSeatIndex = 0
        private set

    internal fun seatLevelFor(seatId: Int): Int = when (seatId) {
        BYDAutoSettingDevice.DRIVER_SEAT -> vehicleState.seatLevelDriver
        BYDAutoSettingDevice.PASSENGER_SEAT -> vehicleState.seatLevelPassenger
        CarControl.REAR_LEFT_SEAT -> vehicleState.seatLevelRearLeft
        CarControl.REAR_RIGHT_SEAT -> vehicleState.seatLevelRearRight
        else -> 0
    }

    internal fun setSeatLevelFor(seatId: Int, level: Int) {
        when (seatId) {
            BYDAutoSettingDevice.DRIVER_SEAT -> vehicleState.seatLevelDriver = level
            BYDAutoSettingDevice.PASSENGER_SEAT -> vehicleState.seatLevelPassenger = level
            CarControl.REAR_LEFT_SEAT -> vehicleState.seatLevelRearLeft = level
            CarControl.REAR_RIGHT_SEAT -> vehicleState.seatLevelRearRight = level
        }
    }

    /** 切到下一个座位（主驾→副驾→左后→右后循环），旋钮单击和触屏按钮共用同一个入口。 */
    internal fun cycleSeat() {
        currentSeatIndex = (currentSeatIndex + 1) % seatSlots.size
        host?.log("🪑 切换座位 -> ${seatSlots[currentSeatIndex].label}")
        persistState()
        refreshCurrentPage()
    }

    // 空调分控（ac_split）也是一个复合停靠点：主驾/副驾共用同一个停靠点，
    // 单击/触屏按钮在两者间切换（跟座椅同一个思路），不需要各自占一个
    // 停靠点。true=当前在编辑主驾，false=副驾。
    internal var acSplitShowingMain = true
        private set

    internal fun cycleAcSplitSide() {
        acSplitShowingMain = !acSplitShowingMain
        host?.log("❄️ 分控切换 -> ${if (acSplitShowingMain) "主驾" else "副驾"}")
        persistState()
        refreshCurrentPage()
    }

    private val tag = "KnobPlugin"

    internal var carControl: CarControl? = null
        private set
    internal var selfTest: PluginSelfTest? = null
        private set
    internal var bleManager: BleManager? = null
        private set
    internal var appContext: Context? = null
        private set
    internal var host: KnobHost? = null
        private set

    internal var pageContainer: ViewGroup? = null
        private set
    internal val vehicleState = VehicleState()

    // 当前停靠点 id，持久化 + 同步给 knob 屏幕。这一份状态是旋钮和触屏
    // 共用的同一套状态机——物理旋钮的转/单击/双击/长按和触屏的按钮只是
    // 同一套状态机的两种输入方式，不是各自独立的两份逻辑。knobCyclePages
    // 是双击（物理 Tab / 触屏"下一个"按钮）能循环到的停靠点，首页不算在
    // 内——旋钮场景下没有"回菜单"的需求，首页只是触屏进入时的一个入口，
    // 靠页面里的按钮跳转，旋钮双击不会经过它。
    internal var currentPageId: String = "ac_link"
        private set
    private val knobCyclePages = listOf("ac_link", "ac_split", "seat")

    // 有没有连上 knob 的 BLE 通道——没连上时不管 currentPageId 是什么，
    // 默认强制显示"home"这个连接页（见 refreshCurrentPage），逼用户先把
    // 蓝牙连上，不然转旋钮改的东西根本传不到 knob 上，改了也白改。但触屏
    // 场景下车控本身（CarControl/BYDAuto HAL）完全不依赖 BLE，用户可能
    // 就是想单纯用触屏调空调/座椅、压根不关心旋钮有没有连上——见
    // forceShowCarControl，允许手动越过这道门。
    internal var bleConnected = false
        private set

    // 用户在首页点了"跳过，直接进车控"之后置 true，绕开上面 bleConnected
    // 的强制门；点车控页里的"返回蓝牙页"或者 BLE 真的连上后置回 false。
    // 只影响 refreshCurrentPage() 该显示哪个页面，不影响 currentPageId
    // 本身停靠在哪——跟旋钮的停靠点状态机是分开的两件事。
    internal var forceShowCarControl = false

    // 后台被动扫描发现的候选设备名字，非 null 时首页显示"发现 XXX，点击
    // 配对"。壳只发现不自动配对（弹配对框太打断），要不要真的配对由用户
    // 在首页点按钮决定，见 page/HomePage.kt。
    internal var candidateName: String? = null

    // 各块独立的功能，各自一个文件：
    internal val permissions = CarPermissions(this) // 车控运行时权限（只在用户点按钮时申请）
    internal val carActions = CarActions(this)      // 停靠点的车控读写（空调、座椅）
    internal val ota = KnobOta(this)                // 旋钮固件自动升级
    internal val drive = DriveFeed(this)            // 旋钮驾驶页的数据源
    internal val startup = StartupCheck(this, ::onStartupPassed) // 启动自检，通过才连旋钮、进车控

    // 自检页（叠在停靠点页面上，自己管跑测试的状态）
    internal val selfTestPage = SelfTestPage(this)

    private fun isAcStop(pageId: String) = pageId == "ac_link" || pageId == "ac_split"

    /** 页面里零散的尺寸按 dp 算（Ui 自己的控件已经按 dp 了） */
    internal fun dp(v: Int): Int = ((appContext?.resources?.displayMetrics?.density ?: 1f) * v).roundToInt()

    // AC/Sensor 监听器的回调不在主线程上（AIDL 回调线程），这两个处理
    // 函数里要碰 View（refreshCurrentPage）和触发 knob 同步，必须先切回
    // 主线程，不然直接崩 CalledFromWrongThreadException。
    internal val mainHandler = Handler(Looper.getMainLooper())

    override fun init(context: Context, host: KnobHost) {
        Log.i(tag, "MainPlugin.init 调用")
        appContext = context
        this.host = host
        carControl = CarControl(host).also {
            it.onTemperatureChangedExternal = ::handleExternalTemperatureChange
            it.onLightIntensityChangedExternal = ::handleExternalLightIntensityChange
            it.onSpeedChangedExternal = { v -> mainHandler.post { drive.onSpeed(v) } }
            it.onAccelerateDeepnessChangedExternal = { v -> mainHandler.post { drive.onPedal(throttle = true, v) } }
            it.onBrakeDeepnessChangedExternal = { v -> mainHandler.post { drive.onPedal(throttle = false, v) } }
        }
        selfTest = PluginSelfTest(carControl!!, host)
        bleManager = BleManager(context, host).also {
            it.onKeyEvent = ::handleKey
            it.onConnectionStateChanged = ::handleBleConnectionChanged
            it.onCandidateFound = ::handleBleCandidateFound
            it.onFirmwareInfo = ota::onFirmwareInfo
            it.onOtaProgress = ota::onProgress
            it.onOtaResult = ota::onResult
        }
        host.log("✅ 插件初始化完成（车控引擎已就绪）")
        ota.load(context, host)
        restoreState()
        syncToKnob()
        // 先过启动自检，通过了才启动 BleManager 连旋钮（见 StartupCheck）。
        startup.run()
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

    internal fun handleExternalLightIntensityChange(level: Int) {
        val percent = brightnessForLightLevel(level)
        mainHandler.post {
            bleManager?.write(mapOf("brightness" to percent), slot = "brightness")
        }
    }

    /** 控制域：所有写操作/命令，命令清单见 CarCommands.kt。 */
    override fun onCommand(command: String, params: Map<String, Any>): Map<String, Any>? = runCommand(command, params)

    /** 查询域：所有读操作，查询清单见 CarCommands.kt。 */
    override fun onQuery(query: String, params: Map<String, Any>): Map<String, Any>? = runQuery(query, params)

    /**
     * 事件域：外部事件。
     * 事件约定：
     *   "key"           params: {code: Int, action: Int}  壳转发的所有按键（含系统返回键）
     *   "ui.bind"       params: {container: ViewGroup}   壳交出页面容器，开始渲染
     *   "ui.unbind"     Activity 销毁，断开容器与 View 树的持有
     *   "perm.result"   params: {requestCode: Int, permissions: List<String>, grantResults: List<Int>}
     *                   插件借壳的 Activity 发起的权限申请结果，壳原样转回来（见 CarPermissions.requestByButton）
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
                "perm.result" -> {
                    val requestCode = (params["requestCode"] as? Number)?.toInt() ?: return false
                    if (requestCode != CarPermissions.REQUEST_CODE) return false
                    val perms = (params["permissions"] as? List<*>)?.filterIsInstance<String>().orEmpty()
                    val results = (params["grantResults"] as? List<*>)?.filterIsInstance<Number>().orEmpty()
                    permissions.handleResult(perms, results.map { it.toInt() })
                    drive.start() // 可能刚授了车速权限
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
     * 推送域：壳在任意时刻主动推数据给插件。BLE 连接状态/扫描候选不再
     * 走这里——那是插件自己的 BleManager 直接回调（见 init() 里的
     * handleBleConnectionChanged/handleBleCandidateFound），跟壳无关。
     * type 约定（新增类型只需加一条 when 分支）：
     *   "state"        data: {model: String, android: String, ...}             壳/设备状态
     *   "hardware"     data: {event: String, ...}                              硬件事件流（旋钮/按键原始值）
     *   "config"       data: {key: Any, ...}                                   配置项
     */
    override fun onPush(type: String, data: Map<String, Any>) {
        val ctrl = carControl
        try {
            when (type) {
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
     * 旋钮映射：Tab（双击触发）=切下一个停靠点（ac_link/ac_split/seat 循环），
     * Enter（长按触发）=确认（对应触屏页面上的 OK）。旋钮转动本身完全在
     * 固件本地处理、不再上报给手机——长按 Enter 时才把旋钮本地编辑到的
     * 最终值（value）一起带过来，直接拿去应用，不需要手机在转动过程中
     * 跟着增量调整。单击（CycleSub）用来在"复合停靠点"内部切子项——单击/
     * 双击是固件那边特意反过来分配的（双击判定有窗口延迟，手慢了没跟上
     * 会被拆成单击，代价应该是"原地切一次子项"而不是"跳到下一个不相关
     * 的停靠点"，见 firmware/src/main.cpp 的说明）。固件无条件识别单击，
     * 不知道当前是不是复合停靠点，这里按 currentPageId 决定要不要响应，
     * 没有子项可切的停靠点收到这个键码直接忽略。
     * 系统返回键不再有特殊处理——停靠点之间是平级切换，没有"返回上一个"
     * 这个概念，交给壳/系统默认处理。按下和抬起都要消费，只吃一半会让
     * 系统按键状态错乱。
     */
    private fun handleKey(code: Int, action: Int, value: Int?): Boolean {
        // 启动自检没通过时车控按键一律不处理（旋钮这时也还没连），交给系统，
        // 车机的确认键就能去点启动自检页上的按钮。
        val carKey = code == KeyEvent.KEYCODE_TAB || code == KeyEvent.KEYCODE_ENTER || code == KEYCODE_CYCLE_SUB
        if (carKey && !startup.passed) return false
        when (code) {
            // 唯一要处理返回键的情况：自检页开着时关掉它（叠在停靠点上的页面，
            // 不是停靠点之间的切换）。其余时候照旧不处理，交给壳/系统。
            KeyEvent.KEYCODE_BACK -> if (selfTestPage.isOpen) {
                if (action == KeyEvent.ACTION_UP) selfTestPage.close()
                return true
            }
            KeyEvent.KEYCODE_TAB -> {
                if (action == KeyEvent.ACTION_UP) advanceStop()
                return true
            }
            KeyEvent.KEYCODE_ENTER -> {
                if (action == KeyEvent.ACTION_UP) applyPrimaryValue(value)
                return true
            }
            KEYCODE_CYCLE_SUB -> {
                if (action == KeyEvent.ACTION_UP) {
                    when (currentPageId) {
                        "seat" -> cycleSeat()
                        "ac_split" -> cycleAcSplitSide()
                        // 没有子项可切的停靠点——安静忽略，不是错误。
                    }
                }
                return true
            }
        }
        Log.i(tag, "onEvent key code=$code action=$action（未处理）")
        return false
    }

    /**
     * BleManager.onConnectionStateChanged 回调：断开就切到"扫描并连接
     * 蓝牙"这个首页；重新连上（含断线重连）就切回原来停靠的页面，并借
     * refreshCurrentPage() 顺带同步一次当前状态给屏幕——不然用户不主动
     * 操作的话，屏幕会一直停在出厂默认画面。
     */
    private fun handleBleConnectionChanged(connected: Boolean, name: String?, mac: String?) {
        carControl?.log("📡 蓝牙状态: ${if (connected) "已连接" else "断开"} name=$name mac=$mac")
        bleConnected = connected
        if (!connected) ota.onKnobDisconnected()
        // 真的连上了，forceShowCarControl 这个手动越门标记就没意义了——
        // 清掉避免下次断线时凭空继续生效，逼用户重新决定要不要再跳过。
        if (connected) forceShowCarControl = false
        refreshCurrentPage()
        // 旋钮断线时清掉了驾驶数据，连上补一份（数值没变也推）
        if (connected) drive.push(force = true)
    }

    /** BleManager.onCandidateFound 回调：让首页显示出这个候选，等用户点确认配对。 */
    private fun handleBleCandidateFound(name: String) {
        carControl?.log("🔍 发现候选设备: $name")
        candidateName = name
        refreshCurrentPage()
    }

    /**
     * 切到循环列表里的下一个停靠点——物理旋钮双击和触屏"下一个"按钮
     * 都调这一个函数，保证两条输入路径走的是同一套状态机。
     */
    internal fun advanceStop() {
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
                carActions.commitAc(separate = false, area = BYDAutoAcDevice.AC_TEMPERATURE_MAIN_DEPUTY, value = vehicleState.acMainTemp)
                refreshCurrentPage()
            }
            "ac_split" -> {
                if (acSplitShowingMain) {
                    if (knobValue != null) {
                        vehicleState.acMainTemp = knobValue.coerceIn(BYDAutoAcDevice.AC_TEMP_IN_CELSIUS_MIN, BYDAutoAcDevice.AC_TEMP_IN_CELSIUS_MAX)
                    }
                    persistState()
                    carActions.commitAc(separate = true, area = BYDAutoAcDevice.AC_TEMPERATURE_MAIN, value = vehicleState.acMainTemp)
                } else {
                    if (knobValue != null) {
                        vehicleState.acDeputyTemp = knobValue.coerceIn(BYDAutoAcDevice.AC_TEMP_IN_CELSIUS_MIN, BYDAutoAcDevice.AC_TEMP_IN_CELSIUS_MAX)
                    }
                    persistState()
                    carActions.commitAc(separate = true, area = BYDAutoAcDevice.AC_TEMPERATURE_DEPUTY, value = vehicleState.acDeputyTemp)
                }
                refreshCurrentPage()
            }
            "seat" -> {
                val seatId = seatSlots[currentSeatIndex].id
                if (knobValue != null) {
                    setSeatLevelFor(seatId, knobValue.coerceIn(SEAT_LEVEL_MIN, SEAT_LEVEL_MAX))
                }
                persistState()
                carActions.commitSeatLevel(seatId, seatLevelFor(seatId))
                refreshCurrentPage()
            }
            else -> Unit
        }
    }

    /**
     * 把当前停靠点同步给旋钮屏幕（走 bleManager.write -> BLE -> ESP32），
     * 作为固件本地编辑的起点。这里直接按当前停靠点拼一个 Map，不同停靠点
     * 带的字段不完全一样——不再用一个固定形状的结构体强行统一，以后加/
     * 改某个停靠点的字段只影响它自己这一条分支。"layout" 字段告诉固件
     * 该用哪种画法（不是靠"有没有某个字段"去反推），"value_main"/
     * "value_deputy" 这两个字段名固定指代主驾/副驾，不用无指向的
     * value/value2。此后转动旋钮只改固件本地的副本，不会再触发这个同步，
     * 直到长按确认把最终值带回来、或者切停靠点/重新进页面时再推一次新的
     * 起点。min<0（座椅）时固件会画成从中间向两侧扩展的圆弧，见
     * firmware/src/ui/page_seat.cpp。"sub_count"/"sub_index" 只有复合停靠点
     * （座椅/空调分控）才带——固件靠这两个字段在屏幕底部画"第 m/n 个"
     * 指示点，不是复合停靠点不发这两个字段，固件默认当 0 处理（不画）。
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
            "ac_split" -> mapOf(
                "layout" to if (acSplitShowingMain) "ac_split_main" else "ac_split_deputy",
                "title" to if (acSplitShowingMain) "主驾" else "副驾",
                "value_main" to vehicleState.acMainTemp,
                "value_deputy" to vehicleState.acDeputyTemp,
                "min" to BYDAutoAcDevice.AC_TEMP_IN_CELSIUS_MIN,
                "max" to BYDAutoAcDevice.AC_TEMP_IN_CELSIUS_MAX,
                "sub_count" to 2,
                "sub_index" to if (acSplitShowingMain) 0 else 1,
            )
            "seat" -> {
                val slot = seatSlots[currentSeatIndex]
                val level = seatLevelFor(slot.id)
                mapOf(
                    // 标题只留座位名——加热/通风状态交给数字本身 + 圆弧颜色
                    // 表达（通风一侧蓝/青，加热一侧珊瑚/红，见
                    // firmware/src/ui/page_seat.cpp），不用汉字重复一遍。
                    "layout" to "seat",
                    "title" to slot.label,
                    "value" to level,
                    "min" to SEAT_LEVEL_MIN,
                    "max" to SEAT_LEVEL_MAX,
                    "sub_count" to seatSlots.size,
                    "sub_index" to currentSeatIndex,
                )
            }
            else -> mapOf("layout" to "none", "title" to "首页", "value" to 0, "min" to 0, "max" to 0)
        }
        bleManager?.write(data)
    }

    // ==================== 状态持久化（进程重启后恢复页面 + 车辆状态） ====================

    internal fun persistState() {
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_PAGE, currentPageId)
            .putInt(KEY_AC_MAIN, vehicleState.acMainTemp)
            .putInt(KEY_AC_DEPUTY, vehicleState.acDeputyTemp)
            .putInt(KEY_SEAT_LEVEL_DRIVER, vehicleState.seatLevelDriver)
            .putInt(KEY_SEAT_LEVEL_PASSENGER, vehicleState.seatLevelPassenger)
            .putInt(KEY_SEAT_LEVEL_REAR_LEFT, vehicleState.seatLevelRearLeft)
            .putInt(KEY_SEAT_LEVEL_REAR_RIGHT, vehicleState.seatLevelRearRight)
            .putInt(KEY_CURRENT_SEAT_INDEX, currentSeatIndex)
            .putBoolean(KEY_AC_SPLIT_SHOWING_MAIN, acSplitShowingMain)
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
        vehicleState.seatLevelDriver = prefs.getInt(KEY_SEAT_LEVEL_DRIVER, 0)
        vehicleState.seatLevelPassenger = prefs.getInt(KEY_SEAT_LEVEL_PASSENGER, 0)
        vehicleState.seatLevelRearLeft = prefs.getInt(KEY_SEAT_LEVEL_REAR_LEFT, 0)
        vehicleState.seatLevelRearRight = prefs.getInt(KEY_SEAT_LEVEL_REAR_RIGHT, 0)
        currentSeatIndex = prefs.getInt(KEY_CURRENT_SEAT_INDEX, 0).coerceIn(0, seatSlots.size - 1)
        acSplitShowingMain = prefs.getBoolean(KEY_AC_SPLIT_SHOWING_MAIN, true)
    }

    // ==================== 页面渲染（纯代码构建 View，避免资源 ID 冲突） ====================

    /** 页面注册表：pageId → 构建函数。新增页面在 page/ 下加一个文件写构建函数，再在这里加一行。 */
    private val pages: Map<String, () -> View> = mapOf(
        "home" to { buildHomePage() },
        "ac_link" to { buildAcPage() },
        "ac_split" to { buildAcPage() },
        "seat" to { buildSeatPage() },
    )

    /** 壳交出页面容器，插件开始渲染当前页面（Activity 重建也会重新调用，直接按当前状态重绘）。 */
    private fun bindUi(container: ViewGroup) {
        pageContainer = container
        Log.i(tag, "ui.bind: 页面容器已就绪，当前页面=$currentPageId")
        if (startup.passed && isAcStop(currentPageId)) carActions.refreshAcTemperatures()
        refreshCurrentPage()
    }

    /** Activity 销毁，断开容器持有——插件是进程级单例，不能强持有已销毁的 Activity。 */
    private fun unbindUi() {
        pageContainer?.removeAllViews()
        pageContainer = null
        selfTestPage.detachViews()
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
        if (isAcStop(pageId)) carActions.refreshAcTemperatures()
        persistState()
        refreshCurrentPage()
    }

    /**
     * 用当前状态重绘该显示的页面，并同步给 knob——数值调整（不切页）时用这个。
     * 没连上 knob 时默认无视 currentPageId、强制显示"home"连接页（见
     * bleConnected），除非用户在首页点了"跳过"（forceShowCarControl，见
     * page/HomePage.kt 的 skipToCarControl）；这是唯一一处把"该显示哪个页面"和"currentPageId"
     * 分开决定的地方，其余地方（advanceStop/switchToPage）都只管业务意义上
     * 停靠在哪个停靠点，不用关心蓝牙有没有连上。没连上时依然不会
     * syncToKnob()——没有 knob 可同步。
     */
    internal fun refreshCurrentPage() {
        val container = pageContainer ?: return
        val pageId = if (bleConnected || forceShowCarControl) currentPageId else "home"
        val builder: () -> View = when {
            ota.percent != null -> this::buildOtaPage
            selfTestPage.isOpen -> selfTestPage::build
            !startup.passed -> this::buildStartupPage
            else -> pages[pageId] ?: return
        }
        // 自检页逐项追加用的 View 引用只对当前这一页有效，换页先清掉。
        selfTestPage.detachViews()
        container.removeAllViews()
        container.addView(builder(), ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        if (bleConnected) syncToKnob()
    }

    /** 启动自检通过（见 StartupCheck）：连旋钮、接驾驶数据、读一次车况。 */
    private fun onStartupPassed() {
        // BLE 连接整个归插件自己管（见 BleManager），壳不插手认哪个设备、
        // 什么时候连——启动自检一通过就自己发起。
        bleManager?.start()
        drive.start()
        if (isAcStop(currentPageId)) carActions.refreshAcTemperatures()
        refreshCurrentPage()
    }

    override fun onDestroy() {
        Log.i(tag, "MainPlugin.onDestroy")
        carControl?.unregisterListeners()
        drive.stop()
        bleManager?.shutdown()
    }
}
