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
        private const val KEY_SEAT_LEVEL_DRIVER = "seat_level_driver"
        private const val KEY_SEAT_LEVEL_PASSENGER = "seat_level_passenger"
        private const val KEY_SEAT_LEVEL_REAR_LEFT = "seat_level_rear_left"
        private const val KEY_SEAT_LEVEL_REAR_RIGHT = "seat_level_rear_right"
        private const val KEY_CURRENT_SEAT_INDEX = "current_seat_index"
        private const val KEY_AC_SPLIT_SHOWING_MAIN = "ac_split_showing_main"
        // 自动 OTA 的目标版本和已尝试次数（防死循环，见 maybeStartOta）
        private const val KEY_OTA_TARGET = "ota_target"
        private const val KEY_OTA_ATTEMPTS = "ota_attempts"
        // 上次见到的插件包文件修改时间，用来认出"刚导入了插件包"（见 resetOtaAttemptsIfNewPackage）
        private const val KEY_OTA_PACKAGE_STAMP = "ota_package_stamp"
        private const val MAX_OTA_ATTEMPTS = 2

        // 座椅 5 档：-2/-1=通风2/1档，0=关闭，1/2=加热1/2档。负数=通风、
        // 正数=加热，这样触屏 ±/knob 屏幕的"居中对称"圆弧
        // （见 firmware/src/main.cpp 的 LV_ARC_MODE_SYMMETRICAL）都能直接
        // 用这一个有符号数表示，不用另外维护"档位+方向"两个字段——反编译
        // 一款同类竞品 App 确认了它家协议也是同样的"符号选属性、数值选
        // 档位"思路，只是 0 单独作为两个属性共用的"关闭"，不占用它家的
        // ±1（它家 OFF 是 ±1，这里统一成 0）。
        private const val SEAT_LEVEL_MIN = -2
        private const val SEAT_LEVEL_MAX = 2

        // 联动提交时"设完双区温度 → 关分控"之间的延时，比亚迪建议加延时
        // 但没给具体值，需实车验证调整。见 commitAc()。
        private const val AC_LINK_MODE_DELAY_MS = 500L

        // 旋钮固件单击触发的键码（固件把单击/双击反过来分配了，见
        // firmware/src/ble.h 的说明），不对应真实 Android 按键，数值跟
        // 那边的 KEYCODE_CYCLE_SUB 对齐。
        private const val KEYCODE_CYCLE_SUB = 67
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
     * ac_link 就代表要联动、ac_split 就代表要分控，提交时无条件
     * 断言一次，见 commitAc()。acMainTemp 兼作联动模式下显示/编辑的温度
     * （分控关闭时主驾副驾理论上是同一个值）。
     */
    private data class VehicleState(
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
    private data class SeatSlot(val id: Int, val label: String)
    private val seatSlots = listOf(
        SeatSlot(BYDAutoSettingDevice.DRIVER_SEAT, "主驾"),
        SeatSlot(BYDAutoSettingDevice.PASSENGER_SEAT, "副驾"),
        SeatSlot(CarControl.REAR_LEFT_SEAT, "左后"),
        SeatSlot(CarControl.REAR_RIGHT_SEAT, "右后"),
    )
    private var currentSeatIndex = 0

    private fun seatLevelFor(seatId: Int): Int = when (seatId) {
        BYDAutoSettingDevice.DRIVER_SEAT -> vehicleState.seatLevelDriver
        BYDAutoSettingDevice.PASSENGER_SEAT -> vehicleState.seatLevelPassenger
        CarControl.REAR_LEFT_SEAT -> vehicleState.seatLevelRearLeft
        CarControl.REAR_RIGHT_SEAT -> vehicleState.seatLevelRearRight
        else -> 0
    }

    private fun setSeatLevelFor(seatId: Int, level: Int) {
        when (seatId) {
            BYDAutoSettingDevice.DRIVER_SEAT -> vehicleState.seatLevelDriver = level
            BYDAutoSettingDevice.PASSENGER_SEAT -> vehicleState.seatLevelPassenger = level
            CarControl.REAR_LEFT_SEAT -> vehicleState.seatLevelRearLeft = level
            CarControl.REAR_RIGHT_SEAT -> vehicleState.seatLevelRearRight = level
        }
    }

    /** 切到下一个座位（主驾→副驾→左后→右后循环），旋钮单击和触屏按钮共用同一个入口。 */
    private fun cycleSeat() {
        currentSeatIndex = (currentSeatIndex + 1) % seatSlots.size
        host?.log("🪑 切换座位 -> ${seatSlots[currentSeatIndex].label}")
        persistState()
        refreshCurrentPage()
    }

    // 空调分控（ac_split）也是一个复合停靠点：主驾/副驾共用同一个停靠点，
    // 单击/触屏按钮在两者间切换（跟座椅同一个思路），不需要各自占一个
    // 停靠点。true=当前在编辑主驾，false=副驾。
    private var acSplitShowingMain = true

    private fun cycleAcSplitSide() {
        acSplitShowingMain = !acSplitShowingMain
        host?.log("❄️ 分控切换 -> ${if (acSplitShowingMain) "主驾" else "副驾"}")
        persistState()
        refreshCurrentPage()
    }

    private val tag = "KnobPlugin"

    private var carControl: CarControl? = null
    private var selfTest: PluginSelfTest? = null
    private var bleManager: BleManager? = null
    private var appContext: Context? = null
    private var host: KnobHost? = null

    private var pageContainer: ViewGroup? = null
    private val vehicleState = VehicleState()

    // 当前停靠点 id，持久化 + 同步给 knob 屏幕。这一份状态是旋钮和触屏
    // 共用的同一套状态机——物理旋钮的转/单击/双击/长按和触屏的按钮只是
    // 同一套状态机的两种输入方式，不是各自独立的两份逻辑。knobCyclePages
    // 是双击（物理 Tab / 触屏"下一个"按钮）能循环到的停靠点，首页不算在
    // 内——旋钮场景下没有"回菜单"的需求，首页只是触屏进入时的一个入口，
    // 靠页面里的按钮跳转，旋钮双击不会经过它。
    private var currentPageId: String = "ac_link"
    private val knobCyclePages = listOf("ac_link", "ac_split", "seat")

    // 有没有连上 knob 的 BLE 通道——没连上时不管 currentPageId 是什么，
    // 默认强制显示"home"这个连接页（见 refreshCurrentPage），逼用户先把
    // 蓝牙连上，不然转旋钮改的东西根本传不到 knob 上，改了也白改。但触屏
    // 场景下车控本身（CarControl/BYDAuto HAL）完全不依赖 BLE，用户可能
    // 就是想单纯用触屏调空调/座椅、压根不关心旋钮有没有连上——见
    // forceShowCarControl，允许手动越过这道门。
    private var bleConnected = false

    // 用户在首页点了"跳过，直接进车控"之后置 true，绕开上面 bleConnected
    // 的强制门；点车控页里的"返回蓝牙页"或者 BLE 真的连上后置回 false。
    // 只影响 refreshCurrentPage() 该显示哪个页面，不影响 currentPageId
    // 本身停靠在哪——跟旋钮的停靠点状态机是分开的两件事。
    private var forceShowCarControl = false

    // 后台被动扫描发现的候选设备名字，非 null 时首页显示"发现 XXX，点击
    // 配对"。壳只发现不自动配对（弹配对框太打断），要不要真的配对由用户
    // 在首页点按钮决定，见 buildHomePage()。
    private var candidateName: String? = null

    // 当前连着的旋钮上报的固件版本号和板子名称（BleManager.onFirmwareInfo）；
    // 断开就清空，下次连上重新读。null = 没连上或者读不到（旧固件）。
    private var knobFirmwareVersion: String? = null
    private var knobBoard: String? = null

    // 插件包里带的固件（见 FirmwareBundle），init 时读一次；null = 导入的是纯
    // .dex 或者包有问题，不会给旋钮升级。
    private var firmwareBundle: FirmwareBundle? = null

    // 正在给旋钮升级时的进度（0~100），null = 没在升级。非 null 时页面固定
    // 显示升级页（见 refreshCurrentPage），BleManager 也会丢弃显示同步。
    private var otaPercent: Int? = null

    private fun isAcStop(pageId: String) = pageId == "ac_link" || pageId == "ac_split"

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
        bleManager = BleManager(context, host).also {
            it.onKeyEvent = ::handleKey
            it.onConnectionStateChanged = ::handleBleConnectionChanged
            it.onCandidateFound = ::handleBleCandidateFound
            it.onFirmwareInfo = ::handleKnobFirmwareInfo
            it.onOtaProgress = ::handleOtaProgress
            it.onOtaResult = ::handleOtaResult
        }
        host.log("✅ 插件初始化完成（车控引擎已就绪）")
        firmwareBundle = FirmwareBundle.load(context, host)
        resetOtaAttemptsIfNewPackage(context)
        restoreState()
        syncToKnob()
        // BLE 连接现在整个归插件自己管（见 BleManager），壳不再插手认哪个
        // 设备、什么时候连——插件加载完就该自己发起。
        bleManager?.start()
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
            bleManager?.write(mapOf("brightness" to percent))
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
        when (code) {
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
        if (!connected) {
            knobFirmwareVersion = null
            knobBoard = null
        }
        // 真的连上了，forceShowCarControl 这个手动越门标记就没意义了——
        // 清掉避免下次断线时凭空继续生效，逼用户重新决定要不要再跳过。
        if (connected) forceShowCarControl = false
        refreshCurrentPage()
    }

    /**
     * BleManager.onFirmwareInfo 回调：记下旋钮的固件版本和板子名称，跟插件包
     * 里带的固件不一致就开始升级（见 maybeStartOta）。紧接着会有一次
     * onConnectionStateChanged(connected=true)，那里会重绘页面，这里不用自己刷新。
     */
    private fun handleKnobFirmwareInfo(version: String?, board: String?, otaSupported: Boolean) {
        host?.log("🏷️ 旋钮固件: ${version ?: "未知（固件太旧）"}，板子: ${board ?: "未知"}")
        knobFirmwareVersion = version
        knobBoard = board
        maybeStartOta(version, board, otaSupported)
    }

    /**
     * 手动导入插件包 = 用户想再试一次，把 [maybeStartOta] 的尝试次数清零。
     * init 分不出是刚导入还是开机自动加载，靠插件包文件的修改时间区分：壳每次
     * 导入都删掉旧文件重新拷，时间必然变；开机重新加载的还是同一个文件，不变。
     */
    private fun resetOtaAttemptsIfNewPackage(context: Context) {
        val stamp = FirmwareBundle.pluginFile(context).lastModified()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getLong(KEY_OTA_PACKAGE_STAMP, 0L) == stamp) return
        prefs.edit().putLong(KEY_OTA_PACKAGE_STAMP, stamp).remove(KEY_OTA_TARGET).remove(KEY_OTA_ATTEMPTS).apply()
    }

    /**
     * 连上旋钮、读到版本后决定要不要升级：插件包带了固件、板子对得上、版本
     * 不一样、旋钮支持 OTA，四条都满足才开始，不弹确认。
     *
     * 防死循环：要是新固件上报的版本跟包里写的不一样（比如版本号生成出了
     * 问题），每次连上都会再升一遍。所以按"目标版本"记尝试次数，同一个
     * 目标最多试 [MAX_OTA_ATTEMPTS] 次，旋钮版本终于对上了、或者重新导入了
     * 插件包（见 [resetOtaAttemptsIfNewPackage]）才清零。
     */
    private fun maybeStartOta(version: String?, board: String?, otaSupported: Boolean) {
        val bundle = firmwareBundle ?: return
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        when {
            version == bundle.version -> {
                host?.log("✅ 旋钮固件已是插件包里的版本，不用升级")
                prefs.edit().remove(KEY_OTA_TARGET).remove(KEY_OTA_ATTEMPTS).apply()
                return
            }
            board != bundle.board -> {
                host?.log("⚠️ 插件包里的固件是给 ${bundle.board} 编的，旋钮是 ${board ?: "未知板子"}，不升级")
                return
            }
            !otaSupported -> {
                host?.log("⚠️ 旋钮固件不支持 OTA，需要先用 USB 刷一次新固件")
                return
            }
        }
        val attempts = if (prefs.getString(KEY_OTA_TARGET, null) == bundle.version) prefs.getInt(KEY_OTA_ATTEMPTS, 0) else 0
        if (attempts >= MAX_OTA_ATTEMPTS) {
            host?.log("⚠️ 升级到 ${bundle.version} 已经试过 $attempts 次，旋钮版本仍是 $version，不再自动升级（检查固件版本号是否生成正确，或重新导入插件包）")
            return
        }
        prefs.edit().putString(KEY_OTA_TARGET, bundle.version).putInt(KEY_OTA_ATTEMPTS, attempts + 1).apply()
        host?.log("⬆️ 旋钮固件 $version → ${bundle.version}，开始升级（第 ${attempts + 1} 次）")
        if (bleManager?.startOta(bundle.bytes, bundle.sha256) == true) {
            otaPercent = 0
            refreshCurrentPage()
        }
    }

    /** BleManager.onOtaProgress 回调：只在百分比变了时才会来，直接重绘。 */
    private fun handleOtaProgress(sent: Int, total: Int) {
        otaPercent = (sent.toLong() * 100 / total).toInt()
        refreshCurrentPage()
    }

    /**
     * BleManager.onOtaResult 回调。成功后旋钮马上重启、断开，BleManager 会自动
     * 重连，重连读到新版本时 maybeStartOta 确认版本一致并清掉尝试次数。
     */
    private fun handleOtaResult(success: Boolean, message: String) {
        host?.log(if (success) "✅ 旋钮固件升级完成：$message" else "❌ 旋钮固件升级失败：$message")
        otaPercent = null
        refreshCurrentPage()
    }

    /** 车控页底部那一行旋钮固件信息，只在连着旋钮时显示。 */
    private fun firmwareInfoHint(ctx: Context): View {
        val knob = "旋钮固件 ${knobFirmwareVersion ?: "未知（固件太旧）"}（${knobBoard ?: "未知板子"}）"
        val bundled = firmwareBundle?.let { "\n插件包内置 ${it.version}（${it.board}）" } ?: ""
        return Ui.hint(ctx, knob + bundled)
    }

    /** 升级中固定显示这一页，没有按钮——升级期间不能操作，也不该切走。 */
    private fun buildOtaPage(): View {
        val ctx = appContext!!
        return Ui.vStack(ctx).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            addView(Ui.title(ctx, "⬆️ 正在升级旋钮固件"))
            addView(Ui.space(ctx, 0, 12))
            addView(Ui.text(ctx, "${otaPercent ?: 0}%", size = 40f, bold = true))
            addView(Ui.space(ctx, 0, 12))
            addView(Ui.hint(ctx, "${knobFirmwareVersion ?: "未知"} → ${firmwareBundle?.version ?: "未知"}"))
            addView(Ui.hint(ctx, "升级期间旋钮暂时不能用，完成后会自动重启"))
        }
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
            "ac_split" -> {
                if (acSplitShowingMain) {
                    if (knobValue != null) {
                        vehicleState.acMainTemp = knobValue.coerceIn(BYDAutoAcDevice.AC_TEMP_IN_CELSIUS_MIN, BYDAutoAcDevice.AC_TEMP_IN_CELSIUS_MAX)
                    }
                    persistState()
                    commitAc(separate = true, area = BYDAutoAcDevice.AC_TEMPERATURE_MAIN, value = vehicleState.acMainTemp)
                } else {
                    if (knobValue != null) {
                        vehicleState.acDeputyTemp = knobValue.coerceIn(BYDAutoAcDevice.AC_TEMP_IN_CELSIUS_MIN, BYDAutoAcDevice.AC_TEMP_IN_CELSIUS_MAX)
                    }
                    persistState()
                    commitAc(separate = true, area = BYDAutoAcDevice.AC_TEMPERATURE_DEPUTY, value = vehicleState.acDeputyTemp)
                }
                refreshCurrentPage()
            }
            "seat" -> {
                val seatId = seatSlots[currentSeatIndex].id
                if (knobValue != null) {
                    setSeatLevelFor(seatId, knobValue.coerceIn(SEAT_LEVEL_MIN, SEAT_LEVEL_MAX))
                }
                persistState()
                commitSeatLevel(seatId, seatLevelFor(seatId))
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

    /**
     * 座椅 OK 键：真正下发座椅通风/加热。level 的符号选属性、数值选档位
     * （见 SEAT_LEVEL_MIN/MAX 的注释）：>0 调 setSeatHeating，<0 调
     * setSeatVentilating，=0 两个属性的 OFF 都发一遍——0 本身没有记录
     * "关闭前是加热还是通风"，两个都发一遍能保证不管之前是哪个都被关掉，
     * 已经是 OFF 的那个再发一次 OFF 没有副作用。
     */
    private fun commitSeatLevel(seatId: Int, level: Int) {
        if (SIMULATE_CAR_CONTROL) {
            host?.log("🧪（模拟）座椅(seat=$seatId) -> ${seatLevelLabel(level)}")
            return
        }
        val ctrl = carControl ?: return
        val context = appContext ?: return
        when {
            level > 0 -> {
                val state = if (level >= 2) BYDAutoSettingDevice.SEAT_HEATING_HIGH else BYDAutoSettingDevice.SEAT_HEATING_LOW
                ctrl.setSeatHeating(context, seatId, state)
            }
            level < 0 -> {
                val state = if (level <= -2) BYDAutoSettingDevice.SEAT_VENTILATING_HIGH else BYDAutoSettingDevice.SEAT_VENTILATING_LOW
                ctrl.setSeatVentilating(context, seatId, state)
            }
            else -> {
                ctrl.setSeatHeating(context, seatId, BYDAutoSettingDevice.SEAT_HEATING_OFF)
                ctrl.setSeatVentilating(context, seatId, BYDAutoSettingDevice.SEAT_VENTILATING_OFF)
            }
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
     * firmware/src/main.cpp。"sub_count"/"sub_index" 只有复合停靠点
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
                    // 表达（gradient_color 在负值这一侧是蓝/青，正值是橙/红，
                    // 见 firmware/src/main.cpp），不用汉字重复一遍。
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

    private fun persistState() {
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

    /** 页面注册表：pageId → 构建函数。新增页面只需在此加一行，并写对应的构建函数。 */
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

    /**
     * 用当前状态重绘该显示的页面，并同步给 knob——数值调整（不切页）时用这个。
     * 没连上 knob 时默认无视 currentPageId、强制显示"home"连接页（见
     * bleConnected），除非用户在首页点了"跳过"（forceShowCarControl，见
     * skipToCarControl）；这是唯一一处把"该显示哪个页面"和"currentPageId"
     * 分开决定的地方，其余地方（advanceStop/switchToPage）都只管业务意义上
     * 停靠在哪个停靠点，不用关心蓝牙有没有连上。没连上时依然不会
     * syncToKnob()——没有 knob 可同步。
     */
    private fun refreshCurrentPage() {
        val container = pageContainer ?: return
        val pageId = if (bleConnected || forceShowCarControl) currentPageId else "home"
        val builder: () -> View = if (otaPercent != null) ::buildOtaPage else pages[pageId] ?: return
        container.removeAllViews()
        container.addView(builder(), ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        if (bleConnected) syncToKnob()
    }

    // 自检正在跑（防重复点）。只在主线程读写这个标志（按钮点击 + 结束时
    // post 回主线程清除），后台线程不碰，所以不用加 volatile。
    private var selfTestRunning = false

    /**
     * 跑一键全量自检。⚠️ 必须开后台线程：runAll 里有十几次 Thread.sleep(300)
     * 和一串阻塞的设备调用，放主线程会卡住 UI 二三十秒直接 ANR。结果全程
     * 打进日志窗口（车机无 adb，日志是唯一观测手段）。
     */
    private fun runSelfTest() {
        val ctx = appContext ?: return
        val st = selfTest ?: run { host?.log("❌ 自检未初始化"); return }
        if (selfTestRunning) { host?.log("⏳ 自检正在进行，请等它跑完"); return }
        selfTestRunning = true
        host?.log("🚀 开始自检（后台线程，约 20~30 秒，看日志）")
        Thread {
            try {
                st.runAll(ctx)
            } catch (e: Throwable) {
                host?.log("❌ 自检异常: ${e.javaClass.simpleName} - ${e.message}")
            } finally {
                mainHandler.post { selfTestRunning = false }
            }
        }.start()
    }

    /** 车控自检按钮——纯车控诊断，跟旋钮连没连无关，连接页/车控页都放一个。 */
    private fun selfTestButton(ctx: Context): View = Ui.btn(ctx, "🔧 自检") { runSelfTest() }

    /**
     * "首页" = 没连上 knob 时强制显示的连接页（见 refreshCurrentPage 的
     * bleConnected 判断），不是可以随便导航过来的菜单。BleManager 后台每
     * 5s 被动扫描一次（不会自动弹配对框，见 BleManager.scanForCandidate），
     * 扫到候选就回调 handleBleCandidateFound，这里才显示出候选名字和
     * 「确认配对」按钮——真正的 createBond() 只在用户点了这个按钮之后才
     * 发生。连上之后 handleBleConnectionChanged 会自动把页面切回原来停靠
     * 的那个停靠点，不需要这里手动跳转。
     */
    private fun buildHomePage(): View {
        val ctx = appContext!!
        val name = candidateName
        return Ui.vStack(ctx).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            addView(Ui.title(ctx, "🔌 未连接旋钮"))
            if (name != null) {
                addView(Ui.hint(ctx, "发现设备：$name"))
                addView(Ui.space(ctx, 0, 12))
                addView(Ui.btn(ctx, "🔗 确认配对") {
                    host?.log("👉 用户确认配对 $name")
                    // 乐观清空：配对是否成功由后续连接状态变化 / 重新扫描
                    // 决定，这里先退回"扫描中"文案，避免配对失败后按钮还
                    // 留在原地、再点一次却已经没有候选可配对。
                    candidateName = null
                    bleManager?.pairCandidate()
                    refreshCurrentPage()
                })
            } else {
                addView(Ui.hint(ctx, "正在扫描附近的蓝牙旋钮…"))
            }
            addView(Ui.space(ctx, 0, 12))
            addView(Ui.btn(ctx, "⏭️ 跳过，直接进车控") { skipToCarControl() })
            addView(Ui.space(ctx, 0, 8))
            addView(selfTestButton(ctx))
        }
    }

    /**
     * 首页"跳过"按钮：不管 BLE 连没连上，都直接进 currentPageId 停靠的
     * 车控页面——CarControl/BYDAuto HAL 本身不依赖 BLE，触屏场景下用户
     * 可能压根不关心旋钮连没连。只影响页面显示，不会假装 knob 已连接，
     * refreshCurrentPage() 仍然靠真实的 bleConnected 决定要不要 syncToKnob()。
     */
    private fun skipToCarControl() {
        host?.log("⏭️ 用户跳过蓝牙连接，直接进入车控页面")
        forceShowCarControl = true
        refreshCurrentPage()
    }

    /** 车控页"返回蓝牙页"按钮的另一半：撤销上面的跳过，退回首页。 */
    private fun backToBluetoothPage() {
        host?.log("👈 用户从车控页手动返回蓝牙页")
        forceShowCarControl = false
        refreshCurrentPage()
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
     * 空调两个停靠点共用一个构建函数，按 currentPageId 决定画法——跟旋钮
     * 屏幕的 layout 分发是同一个思路：ac_link 只有联动一组温度框；
     * ac_split 是分控，主驾/副驾共用这一个停靠点，[acSplitShowingMain]
     * 决定当前哪一侧可编辑，"切换" 按钮跟旋钮单击是同一个 cycleAcSplitSide()
     * 入口，另一侧只读展示做参考，不提供 ±/OK。
     */
    private fun buildAcPage(): View {
        val ctx = appContext!!
        val title = when {
            currentPageId == "ac_split" && acSplitShowingMain -> "❄️ 空调温度（分控·主驾）"
            currentPageId == "ac_split" -> "❄️ 空调温度（分控·副驾）"
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
                "ac_split" -> {
                    addView(Ui.btn(ctx, if (acSplitShowingMain) "切到副驾" else "切到主驾") { cycleAcSplitSide() })
                    addView(Ui.space(ctx, 0, 8))
                    if (acSplitShowingMain) {
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
                        addView(Ui.label(ctx, "副驾温度 ${vehicleState.acDeputyTemp}°C（参考，点按钮切到副驾调整）"))
                    } else {
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
                        addView(Ui.label(ctx, "主驾温度 ${vehicleState.acMainTemp}°C（参考，点按钮切到主驾调整）"))
                    }
                }
            }
            addView(Ui.space(ctx, 0, 12))
            addView(Ui.btn(ctx, "🔀 下一个") { advanceStop() })
            // 只有真的没连 BLE、靠 forceShowCarControl 跳过来的情况下才需要
            // 这个按钮——已经连上 knob 时没有"手动回蓝牙页"的必要。
            if (!bleConnected) {
                addView(Ui.btn(ctx, "📶 返回蓝牙页") { backToBluetoothPage() })
            } else {
                addView(Ui.space(ctx, 0, 8))
                addView(firmwareInfoHint(ctx))
            }
            addView(Ui.space(ctx, 0, 8))
            addView(selfTestButton(ctx))
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

    // 联动提交里"延时后关分控"那一步，还没执行时再次提交要先取消，
    // 否则它会晚到一步把新停靠点刚打开的分控又关掉。
    private var pendingAcLinkMode: Runnable? = null

    /**
     * 空调停靠点的提交。分控没有监听回调，所以每次提交都无条件断言一次
     * 这个停靠点要求的分控状态，不比较本地缓存。
     *
     * 车机 setAcTemperature 会把分控重新打开（比亚迪确认是预期行为），
     * 所以两条路顺序不同：
     * - 分控：先开分控，再写 MAIN/DEPUTY 温度——调温本来就会保持分控开。
     * - 联动：按比亚迪建议，先写 MAIN_DEPUTY 双区温度，延时
     *   AC_LINK_MODE_DELAY_MS 后再关分控，否则关分控会被调温顶回去。
     */
    private fun commitAc(separate: Boolean, area: Int, value: Int) {
        if (SIMULATE_CAR_CONTROL) {
            host?.log("🧪（模拟）setAcTemperatureControlMode separate=$separate; setAcTemperature area=$area value=$value")
            return
        }
        val ctrl = carControl ?: return
        val context = appContext ?: return
        pendingAcLinkMode?.let { mainHandler.removeCallbacks(it) }
        pendingAcLinkMode = null
        if (separate) {
            ctrl.setAcTemperatureControlMode(context, true)
            ctrl.setAcTemperature(context, area, value)
            return
        }
        ctrl.setAcTemperature(context, area, value)
        val r = Runnable {
            pendingAcLinkMode = null
            ctrl.setAcTemperatureControlMode(context, false)
            ctrl.getAcTemperatureControlMode(context)
        }
        pendingAcLinkMode = r
        mainHandler.postDelayed(r, AC_LINK_MODE_DELAY_MS)
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
            addView(Ui.btn(ctx, "🔀 下一个") { advanceStop() })
            if (!bleConnected) {
                addView(Ui.btn(ctx, "📶 返回蓝牙页") { backToBluetoothPage() })
            } else {
                addView(Ui.space(ctx, 0, 8))
                addView(firmwareInfoHint(ctx))
            }
            addView(Ui.space(ctx, 0, 8))
            addView(selfTestButton(ctx))
        }
    }

    /**
     * 座椅的"座位切换 － 档位 ＋ OK"，跟 buildTempRow 不共用是因为这里显示的
     * 是"通风2档"这种文字档位，不是"数值+单位"，格式完全不同。
     * "座位:xx" 按钮跟旋钮单击是同一个 cycleSeat() 入口，切完显示新座位的档位；
     * －/＋ 只改本地待提交档位，OK 才真正下发。
     */
    private fun buildSeatRow(): View {
        val ctx = appContext!!
        val slot = seatSlots[currentSeatIndex]
        val level = seatLevelFor(slot.id)
        return Ui.card(ctx).apply {
            addView(Ui.label(ctx, "座椅"))
            addView(Ui.hStack(ctx).apply {
                addView(Ui.btn(ctx, "座位:${slot.label}") { cycleSeat() })
                addView(Ui.btn(ctx, "－") {
                    setSeatLevelFor(slot.id, (level - 1).coerceIn(SEAT_LEVEL_MIN, SEAT_LEVEL_MAX))
                    persistState()
                    refreshCurrentPage()
                })
                addView(Ui.text(ctx, seatLevelLabel(level), size = 18f, bold = true))
                addView(Ui.btn(ctx, "＋") {
                    setSeatLevelFor(slot.id, (level + 1).coerceIn(SEAT_LEVEL_MIN, SEAT_LEVEL_MAX))
                    persistState()
                    refreshCurrentPage()
                })
                addView(Ui.btn(ctx, "OK") { commitSeatLevel(slot.id, seatLevelFor(slot.id)) })
            })
        }
    }

    override fun onDestroy() {
        Log.i(tag, "MainPlugin.onDestroy")
        carControl?.unregisterListeners()
        bleManager?.shutdown()
    }
}
