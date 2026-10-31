package cn.icarus.knob.plugin

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.hardware.IBYDAutoDevice
import android.hardware.bydauto.ac.AbsBYDAutoAcListener
import android.hardware.bydauto.ac.BYDAutoAcDevice
import android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice
import android.hardware.bydauto.sensor.AbsBYDAutoSensorListener
import android.hardware.bydauto.sensor.BYDAutoSensorDevice
import android.hardware.bydauto.setting.BYDAutoSettingDevice
import android.hardware.bydauto.speed.AbsBYDAutoSpeedListener
import android.hardware.bydauto.speed.BYDAutoSpeedDevice
import cn.icarus.knob.api.KnobHost
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.reflect.Method

/**
 * 车控引擎（从壳的 BydHal 迁移到插件）。
 * 日志通过 logger 回调发给壳显示。
 *
 * 座椅通风/空调均为车机厂商公开 HAL 接口（签名版可调用）。
 * 座椅: 1=主驾, 2=副驾, 3=后排左, 4=后排右
 * 通风档位: OFF=1, LOW=2, HIGH=3
 */
class CarControl(private val logger: KnobHost) {

    companion object {
        // 官方 jar（BYDAutoSettingDevice）只定义了 DRIVER_SEAT/PASSENGER_SEAT
        // 两个座位常量，没有后排的——反编译一款同类竞品 App 确认了完整
        // 编号：1=主驾,2=副驾,3=后左,4=后右，这里补两个命名常量，调用方
        // 不用写裸数字。
        const val REAR_LEFT_SEAT = 3
        const val REAR_RIGHT_SEAT = 4

        // 后排座椅加热/通风的 hasFeature 探测字符串，同样来自反编译——公开
        // 常量只有主驾/副驾 4 个（FEATURE_DRIVER_SEAT_HEATING 等），没有后排的。
        const val FEATURE_REAR_LEFT_SEAT_HEATING = "RearLeftSeatHeating"
        const val FEATURE_REAR_LEFT_SEAT_VENTILATING = "RearLeftSeatVentilating"
        const val FEATURE_REAR_RIGHT_SEAT_HEATING = "RearRightSeatHeating"
        const val FEATURE_REAR_RIGHT_SEAT_VENTILATING = "RearRightSeatVentilating"

        // 现有功能用到的 dangerous 级权限（*_COMMON）。壳 manifest 声明了全部
        // BYDAUTO 权限，插件不自动申请：用户在启动自检页 / 自检页上点「申请」
        // 按钮才申请（按 PERM_GROUPS 逐个功能列）；车控操作前只用
        // MainPlugin.hasPermissions 查一下，缺了就不执行。新功能用到新设备就在
        // 这里加一个、在 PERM_GROUPS 加一行，并在功能入口调 hasPermissions。
        // 签名级 GET/SET 装车即授，不用申请。
        const val PERM_AC = "android.permission.BYDAUTO_AC_COMMON"             // 空调温度/分控
        const val PERM_SETTING = "android.permission.BYDAUTO_SETTING_COMMON"   // 座椅加热/通风
        const val PERM_SPEED = "android.permission.BYDAUTO_SPEED_COMMON"       // 车速/油门/制动（目前只有自检在用）
        const val PERM_BODYWORK = "android.permission.BYDAUTO_BODYWORK_COMMON" // 车架号/车型（目前只有自检在用）

        /**
         * 现有功能各自要的整套权限，启动自检和自检页按这个表逐行展示授权状态：
         * common 是能运行时申请的那个，signature 是签名级的 GET/SET（申请不了，
         * 只能看）。车速、车身只读，所以只列 GET。required = 车控功能真的在用，
         * 启动自检要它全部授权、设备能初始化才放行（连旋钮、进车控）；车速、
         * 车身目前只有自检在用，缺了只提示不拦。新功能加了 PERM_* 也在这里加一行。
         */
        val PERM_GROUPS = listOf(
            PermGroup("空调（温度/分控）", PERM_AC, listOf(
                "android.permission.BYDAUTO_AC_GET", "android.permission.BYDAUTO_AC_SET"), required = true),
            PermGroup("座椅（加热/通风）", PERM_SETTING, listOf(
                "android.permission.BYDAUTO_SETTING_GET", "android.permission.BYDAUTO_SETTING_SET"), required = true),
            PermGroup("车速/油门/制动", PERM_SPEED, listOf("android.permission.BYDAUTO_SPEED_GET"), required = false),
            PermGroup("车架号/车型", PERM_BODYWORK, listOf("android.permission.BYDAUTO_BODYWORK_GET"), required = false),
        )
    }

    class PermGroup(val label: String, val common: String, val signature: List<String>, val required: Boolean)

    private var settingDevice: BYDAutoSettingDevice? = null
    private var acDevice: BYDAutoAcDevice? = null
    private var sensorDevice: BYDAutoSensorDevice? = null
    private var speedDevice: BYDAutoSpeedDevice? = null
    private var speedListener: AbsBYDAutoSpeedListener? = null
    private var bodyworkDevice: BYDAutoBodyworkDevice? = null

    // 外部（MainPlugin）想在这些值被语音/按键等车机自身渠道改了之后
    // 实时收到通知，就设这两个回调——CarControl 自己只管注册/解析监听器，
    // 不知道 vehicleState/UI 这些插件内部状态，所以用回调往外传，不直接
    // 依赖 MainPlugin。回调是在 AIDL 监听器的回调线程上触发的，不是主
    // 线程，MainPlugin 那边要自己切回主线程才能碰 View/knob 同步。
    var onTemperatureChangedExternal: ((area: Int, value: Int) -> Unit)? = null
    var onLightIntensityChangedExternal: ((level: Int) -> Unit)? = null

    // 车速/油门/制动的变化回调——官方文档说这几项的读取和监听都不需要
    // 权限，先注册上试；回调跟上面几个一样在 AIDL 线程上触发，MainPlugin
    // 要自己切回主线程。目前还没人订阅，只有自检注册监听器打日志验证。
    var onSpeedChangedExternal: ((speedKmh: Double) -> Unit)? = null
    var onAccelerateDeepnessChangedExternal: ((percent: Int) -> Unit)? = null
    var onBrakeDeepnessChangedExternal: ((percent: Int) -> Unit)? = null

    fun log(msg: String) = logger.log(msg)

    /** 打日志时把整数值的十进制和十六进制都打出来，方便排查"数值看着像溢出/哨兵值"这种问题
     *（比如 Int.MIN_VALUE 十进制是一长串不好一眼认出来，十六进制 0x80000000 就很明显）。 */
    private fun logIntValue(label: String, value: Int?) {
        if (value == null) {
            log("  $label = null")
        } else {
            log("  $label = $value (0x${value.toUInt().toString(16)})")
        }
    }

    private fun enableDevice(context: Context, device: IBYDAutoDevice, logName: String) {
        try {
            val mgrClass = Class.forName("android.hardware.bydauto.BYDAutoDeviceManager")
            val mgrGetInstance = mgrClass.getMethod("getInstance", Context::class.java)
            val mgr = mgrGetInstance.invoke(null, context) ?: run {
                log("⚠️ BYDAutoDeviceManager.getInstance 返回 null")
                return
            }
            try {
                mgrClass.getMethod("enableDevice", IBYDAutoDevice::class.java)
                    .invoke(mgr, device)
                log("✅ 已启用 $logName")
            } catch (e: Throwable) {
                try {
                    mgrClass.getMethod("enableDevice", IBYDAutoDevice::class.java, IntArray::class.java)
                        .invoke(mgr, device, intArrayOf())
                    log("✅ 已启用 $logName (带 feature 重载)")
                } catch (e2: Throwable) {
                    log("⚠️ 启用 $logName 失败: ${e2.message}")
                }
            }
        } catch (e: Throwable) {
            log("⚠️ enableDevice($logName) 异常: ${e.message}")
        }
    }

    private fun findMethod(device: Any, name: String, vararg params: Class<*>): Method {
        return try {
            device.javaClass.getMethod(name, *params)
        } catch (e: NoSuchMethodException) {
            device.javaClass.getDeclaredMethod(name, *params).also { it.isAccessible = true }
        }
    }

    private fun exceptionDetail(e: Throwable): String {
        val sb = StringBuilder()
        var current: Throwable? = e
        var depth = 0
        while (current != null && depth < 5) {
            sb.append("cause[$depth] ${current.javaClass.simpleName}: ${current.message}\n")
            val sw = StringWriter()
            current.printStackTrace(PrintWriter(sw))
            sb.append(sw.toString().split("\n").take(6).joinToString("\n"))
            sb.append("\n")
            current = current.cause
            depth++
        }
        return sb.toString().trim()
    }

    // ==================== 座椅通风 ====================

    fun initSettingDevice(context: Context): Boolean {
        return try {
            settingDevice = BYDAutoSettingDevice.getInstance(context)
            if (settingDevice == null) {
                log("❌ BYDAutoSettingDevice.getInstance 返回 null")
                return false
            }
            log("✅ 获取 BYDAutoSettingDevice 实例成功")
            enableDevice(context, settingDevice!!, "Setting 设备")
            try {
                val r = settingDevice!!.hasFeature("DriverSeatVentilating")
                log("主驾座椅通风 feature = $r (1=支持)")
            } catch (e: Throwable) {
                log("⚠️ hasFeature 查询失败: ${e.message}")
            }
            true
        } catch (e: Throwable) {
            log("❌ 初始化 Setting 设备失败: ${e.javaClass.simpleName} - ${e.message}")
            false
        }
    }

    /** 座椅通风，seat 传 BYDAutoSettingDevice.DRIVER_SEAT/PASSENGER_SEAT/CarControl.REAR_LEFT_SEAT/REAR_RIGHT_SEAT。 */
    fun setSeatVentilating(context: Context, seat: Int, state: Int): Int? {
        if (settingDevice == null && !initSettingDevice(context)) return null
        return try {
            val method = findMethod(settingDevice!!, "setSeatVentilatingState", Int::class.java, Int::class.java)
            val result = method.invoke(settingDevice, seat, state) as? Int
            log("▶ 座椅(seat=$seat)通风 → 档位=$state，返回码=$result (0=成功)")
            result
        } catch (e: Throwable) {
            log("❌ setSeatVentilatingState 调用失败:\n${exceptionDetail(e)}")
            null
        }
    }

    fun getSeatVentilating(context: Context, seat: Int): Int? {
        if (settingDevice == null && !initSettingDevice(context)) return null
        return try {
            val method = findMethod(settingDevice!!, "getSeatVentilatingState", Int::class.java)
            val result = method.invoke(settingDevice, seat) as? Int
            log("◀ 座椅(seat=$seat)通风状态 (1=关,2=低,3=高)")
            logIntValue("result", result)
            result
        } catch (e: Throwable) {
            log("⚠️ 读取座椅通风状态失败: ${e.message}")
            null
        }
    }

    /** 主驾座椅通风的便捷包装——onCommand/onQuery 里 "seat.ventilating" 这条老接口在用，保留不动。 */
    fun setDriverSeatVentilating(context: Context, state: Int): Int? =
        setSeatVentilating(context, BYDAutoSettingDevice.DRIVER_SEAT, state)

    fun getDriverSeatVentilating(context: Context): Int? =
        getSeatVentilating(context, BYDAutoSettingDevice.DRIVER_SEAT)

    /**
     * 座椅加热：跟座椅通风一样是隐藏方法（桩包 jar 没声明 get/setSeatHeatingState，
     * 反射调）。seat 直接传 BYDAutoSettingDevice.DRIVER_SEAT/PASSENGER_SEAT 等——
     * 反编译一款同类竞品应用确认支持全部 4 座（1=主驾,2=副驾,3=后左,4=后右），
     * state 用 SEAT_HEATING_OFF/LOW/HIGH（1/2/3，同座椅通风取值）。
     * ⚠️ 还反射到一个"1"后缀重载（setSeatHeatingState1/getSeatHeatingState1），
     * 对方声明了但没人调用，用途不明，不接。
     */
    fun setSeatHeating(context: Context, seat: Int, state: Int): Int? {
        if (settingDevice == null && !initSettingDevice(context)) return null
        return try {
            val method = findMethod(settingDevice!!, "setSeatHeatingState", Int::class.java, Int::class.java)
            val result = method.invoke(settingDevice, seat, state) as? Int
            log("▶ 座椅(seat=$seat)加热 → 档位=$state，返回码=$result (0=成功)")
            result
        } catch (e: Throwable) {
            log("❌ setSeatHeatingState 调用失败:\n${exceptionDetail(e)}")
            null
        }
    }

    fun getSeatHeating(context: Context, seat: Int): Int? {
        if (settingDevice == null && !initSettingDevice(context)) return null
        return try {
            val method = findMethod(settingDevice!!, "getSeatHeatingState", Int::class.java)
            val result = method.invoke(settingDevice, seat) as? Int
            log("◀ 座椅(seat=$seat)加热状态 (1=关,2=低,3=高)")
            logIntValue("result", result)
            result
        } catch (e: Throwable) {
            log("⚠️ 读取座椅加热状态失败: ${e.message}")
            null
        }
    }

    /**
     * 反射列举 BYDAutoSettingDevice 上的全部方法（含未公开的），不按名字筛选——
     * 官方常量里已经有 FEATURE_DRIVER_SEAT_HEATING 等 4 个座椅加热/通风的
     * feature 常量，猜测应该有配套的隐藏方法，但方法名不一定跟 Seat/Heat/Vent
     * 这几个词对得上，干脆全部打出来自己看。只打印方法签名，不调用。
     */
    fun probeSeatFeatureMethods(context: Context) {
        if (settingDevice == null && !initSettingDevice(context)) return
        val cls = settingDevice!!.javaClass
        log("🔍 反射扫描 ${cls.name} 上的全部方法（只看不调）：")
        val methods = (cls.declaredMethods.toList() + cls.methods.toList())
            .distinctBy { m -> "${m.name}(${m.parameterTypes.joinToString(",") { it.name }})" }
            .sortedBy { it.name }
        methods.forEach { m ->
            val mod = java.lang.reflect.Modifier.toString(m.modifiers)
            val params = m.parameterTypes.joinToString(", ") { it.simpleName }
            log("  [$mod] ${m.name}($params): ${m.returnType.simpleName}")
        }
    }

    /**
     * 用官方 hasFeature(String) + BYDAutoSettingDevice.FEATURE_DRIVER_SEAT_HEATING
     * 等 4 个官方常量查一下座椅加热/通风在这台车机上是否配置为支持
     * （1=支持，0=不支持），确认是车机配置问题还是接口本身的问题。
     */
    fun checkSeatFeatures(context: Context) {
        if (settingDevice == null && !initSettingDevice(context)) return
        log("🔍 座椅加热/通风 feature 配置检查：")
        val features = listOf(
            "主驾加热" to BYDAutoSettingDevice.FEATURE_DRIVER_SEAT_HEATING,
            "主驾通风" to BYDAutoSettingDevice.FEATURE_DRIVER_SEAT_VENTILATING,
            "副驾加热" to BYDAutoSettingDevice.FEATURE_PASSENGER_SEAT_HEATING,
            "副驾通风" to BYDAutoSettingDevice.FEATURE_PASSENGER_SEAT_VENTILATING,
            "后左加热" to FEATURE_REAR_LEFT_SEAT_HEATING,
            "后左通风" to FEATURE_REAR_LEFT_SEAT_VENTILATING,
            "后右加热" to FEATURE_REAR_RIGHT_SEAT_HEATING,
            "后右通风" to FEATURE_REAR_RIGHT_SEAT_VENTILATING,
        )
        features.forEach { (label, feature) ->
            try {
                val r = settingDevice!!.hasFeature(feature)
                log("  $label ($feature) = $r (1=支持,0=不支持)")
            } catch (e: Throwable) {
                log("  ⚠️ $label ($feature) 查询失败: ${e.message}")
            }
        }
    }

    // ==================== 空调 ====================

    // AC 的各种状态变化监听——只有 onTemperatureChanged 这一个有对应的外部
    // 回调（onTemperatureChangedExternal，MainPlugin 用来实时同步温度），
    // 其它回调目前插件内没有对应的状态/UI 可更新，先只打日志留痕，不往外
    // 传。注意：分控（AcTemperatureControlMode）变化没有专门的监听回调
    // （文档和反编译的 SDK 桩里都没有），这几个回调覆盖不到分控被语音/
    // 按键从外部改的情况，目前不处理。
    // 曾经尝试过覆写 IBYDAutoListener 唯一的 onDataChanged（猜测是所有
    // onXxxChanged 具名回调的通用分发源头，可能覆盖分控这种没有专门回调
    // 的字段），但真机加载插件时报 LinkageError：AbsBYDAutoAcListener 上
    // 这个方法实际是 final 的（编译期用的 bydauto-openapi.jar 桩包没标出
    // 这一点），框架就是故意锁死不让子类覆写，逼着用具名回调，这条路走
    // 不通，不要再加。
    // 不能像 acDevice/settingDevice/sensorDevice 那样直接写死类型，也不能
    // 用 "private val acListener = object : AbsBYDAutoAcListener() {...}"
    // 这种字段初始化——这个匿名类是真的继承自 AbsBYDAutoAcListener，字段
    // 初始化会在 CarControl 构造时就立刻发生，逼着 ART 在这一刻就去解析
    // AbsBYDAutoAcListener 这个类。simulate 构建跑在普通手机上（没有 BYD
    // 车机框架，这个类根本不存在），"导入插件"这一步就直接
    // "Failed resolution of AbsBYDAutoAcListener" 炸掉——比 setAcTemperature
    // 这种方法体内部才引用车控类型的地方严重得多，那些有 try/catch 兜底
    // （方法体内的引用是懒解析，调用那一刻才去找类，找不到能被
    // catch(Throwable) 接住），字段初始化/继承关系是 ART 验证类的时候就要
    // 解析的，兜不住。改成跟 acDevice 一样的懒加载：只在真正调
    // initAcDevice() 时才 new 这个匿名类，simulate 版本下 initAcDevice()
    // 根本不会被调用到，这个类就永远不会被 ART 摸到，自然不会报错。
    private var acListener: AbsBYDAutoAcListener? = null

    fun initAcDevice(context: Context): Boolean {
        return try {
            acDevice = BYDAutoAcDevice.getInstance(context)
            if (acDevice == null) {
                log("❌ BYDAutoAcDevice.getInstance 返回 null")
                return false
            }
            log("✅ 获取 BYDAutoAcDevice 实例成功")
            enableDevice(context, acDevice!!, "AC 设备")
            val listener = acListener ?: object : AbsBYDAutoAcListener() {
                override fun onTemperatureChanged(area: Int, value: Int) {
                    log("🔔 [AC监听] 温度变化 area=${areaLabel(area)} value=$value")
                    onTemperatureChangedExternal?.invoke(area, value)
                }
                override fun onAcStarted() = log("🔔 [AC监听] 空调开启")
                override fun onAcStoped() = log("🔔 [AC监听] 空调关闭")
                override fun onAcRearStarted() = log("🔔 [AC监听] 后排空调开启")
                override fun onAcRearStoped() = log("🔔 [AC监听] 后排空调关闭")
                override fun onAcCtrlModeChanged(mode: Int) = log("🔔 [AC监听] 控制方式(手动/自动)变化 mode=$mode")
                override fun onAcCycleModeChanged(mode: Int) = log("🔔 [AC监听] 循环方式变化 mode=$mode")
                override fun onAcVentilationStateChanged(state: Int) = log("🔔 [AC监听] 驻车通风变化 state=$state")
                override fun onAcDefrostStateChanged(area: Int, state: Int) = log("🔔 [AC监听] 除霜变化 area=$area state=$state")
                override fun onAcCompressorManualSignChanged(sign: Int) = log("🔔 [AC监听] 压缩机手动标志变化 sign=$sign")
                override fun onAcCompressorModeChanged(mode: Int) = log("🔔 [AC监听] 压缩机状态变化 mode=$mode")
                override fun onAcWindModeManualSignChanged(sign: Int) = log("🔔 [AC监听] 出风模式手动标志变化 sign=$sign")
                override fun onAcWindModeChanged(mode: Int) = log("🔔 [AC监听] 出风模式变化 mode=$mode")
                override fun onAcWindLevelManualSignChanged(sign: Int) = log("🔔 [AC监听] 风量手动标志变化 sign=$sign")
                override fun onAcWindLevelChanged(level: Int) = log("🔔 [AC监听] 风量变化 level=$level")
                override fun onTemperatureUnitChanged(unit: Int) = log("🔔 [AC监听] 温度单位变化 unit=$unit")
                override fun onAcWindModeShownStateChanged(state: Int) = log("🔔 [AC监听] 出风模式显示状态变化 state=$state")
            }.also { acListener = it }
            acDevice!!.registerListener(listener)
            log("✅ 已注册 AC 监听器")
            true
        } catch (e: Throwable) {
            log("❌ 初始化 AC 设备失败: ${e.javaClass.simpleName} - ${e.message}")
            false
        }
    }

    /** 插件销毁时清理，避免监听器继续挂在已经不用的插件实例上。 */
    fun unregisterListeners() {
        try { acListener?.let { acDevice?.unregisterListener(it) } } catch (e: Throwable) { log("⚠️ 取消 AC 监听器失败: ${e.message}") }
        try { sensorListener?.let { sensorDevice?.unregisterListener(it) } } catch (e: Throwable) { log("⚠️ 取消 Sensor 监听器失败: ${e.message}") }
    }

    fun setAcTemperature(context: Context, area: Int, value: Int): Int? {
        if (acDevice == null && !initAcDevice(context)) return null
        return try {
            val result = acDevice!!.setAcTemperature(area, value, BYDAutoAcDevice.AC_CTRL_SOURCE_UI_KEY, BYDAutoAcDevice.AC_TEMPERATURE_UNIT_OC)
            log("▶ 空调(${areaLabel(area)}) → 温度=${value}°C")
            logIntValue("返回码", result)
            result
        } catch (e: Throwable) {
            log("❌ setAcTemperature 调用失败:\n${exceptionDetail(e)}")
            null
        }
    }

    fun setAcTemperatureControlMode(context: Context, separate: Boolean): Int? {
        if (acDevice == null && !initAcDevice(context)) return null
        val mode = if (separate) BYDAutoAcDevice.AC_TEMPCTRL_SEPARATE_ON else BYDAutoAcDevice.AC_TEMPCTRL_SEPARATE_OFF
        return try {
            log("▶ 分控 setAcTemperatureControlMode(setSource=${BYDAutoAcDevice.AC_CTRL_SOURCE_UI_KEY}, mode=$mode) [${if (separate) "开启" else "关闭"}]")
            val result = acDevice!!.setAcTemperatureControlMode(BYDAutoAcDevice.AC_CTRL_SOURCE_UI_KEY, mode)
            logIntValue("返回码", result)
            result
        } catch (e: Throwable) {
            log("❌ setAcTemperatureControlMode 调用失败:\n${exceptionDetail(e)}")
            null
        }
    }

    fun getAcTemperatureControlMode(context: Context): Int? {
        if (acDevice == null && !initAcDevice(context)) return null
        return try {
            log("◀ 分控 getAcTemperatureControlMode() (${BYDAutoAcDevice.AC_TEMPCTRL_SEPARATE_OFF}=关,${BYDAutoAcDevice.AC_TEMPCTRL_SEPARATE_ON}=开)")
            val result = acDevice!!.getAcTemperatureControlMode()
            logIntValue("result", result)
            result
        } catch (e: Throwable) {
            log("⚠️ 读取分控模式失败: ${e.message}")
            null
        }
    }

    fun setAcPower(context: Context, on: Boolean): Int? {
        if (acDevice == null && !initAcDevice(context)) return null
        return try {
            val result = if (on) acDevice!!.start(BYDAutoAcDevice.AC_CTRL_SOURCE_UI_KEY)
                          else acDevice!!.stop(BYDAutoAcDevice.AC_CTRL_SOURCE_UI_KEY)
            log("▶ 空调${if (on) "开启" else "关闭"}，返回码=$result")
            result
        } catch (e: Throwable) {
            log("❌ 开关空调失败:\n${exceptionDetail(e)}")
            null
        }
    }

    fun setAcWindLevel(context: Context, level: Int): Int? {
        if (acDevice == null && !initAcDevice(context)) return null
        return try {
            val result = acDevice!!.setAcWindLevel(BYDAutoAcDevice.AC_CTRL_SOURCE_UI_KEY, level)
            log("▶ 空调风量=$level，返回码=$result")
            result
        } catch (e: Throwable) {
            log("❌ 设置风量失败:\n${exceptionDetail(e)}")
            null
        }
    }

    fun setAcWindMode(context: Context, mode: Int): Int? {
        if (acDevice == null && !initAcDevice(context)) return null
        return try {
            val result = acDevice!!.setAcWindMode(BYDAutoAcDevice.AC_CTRL_SOURCE_UI_KEY, mode)
            log("▶ 空调风向=$mode，返回码=$result")
            result
        } catch (e: Throwable) {
            log("❌ 设置风向失败:\n${exceptionDetail(e)}")
            null
        }
    }

    fun setAcCycleMode(context: Context, inner: Boolean): Int? {
        if (acDevice == null && !initAcDevice(context)) return null
        return try {
            val mode = if (inner) 1 else 0
            val result = acDevice!!.setAcCycleMode(BYDAutoAcDevice.AC_CTRL_SOURCE_UI_KEY, mode)
            log("▶ 空调循环=${if (inner) "内循环" else "外循环"}，返回码=$result")
            result
        } catch (e: Throwable) {
            log("❌ 设置循环失败:\n${exceptionDetail(e)}")
            null
        }
    }

    fun getAcTemperature(context: Context, area: Int): Int? {
        if (acDevice == null && !initAcDevice(context)) return null
        return try {
            val method = findMethod(acDevice!!, "getTemprature", Int::class.java)
            log("◀ 读取空调(${areaLabel(area)})温度 getTemprature(area=$area)")
            val result = method.invoke(acDevice, area) as? Int
            logIntValue("result", result)
            result
        } catch (e: Throwable) {
            log("⚠️ 读取空调温度失败: ${e.message}")
            null
        }
    }

    private fun areaLabel(area: Int): String = when (area) {
        BYDAutoAcDevice.AC_TEMPERATURE_MAIN_DEPUTY -> "主副联动"
        BYDAutoAcDevice.AC_TEMPERATURE_MAIN -> "主驾"
        BYDAutoAcDevice.AC_TEMPERATURE_DEPUTY -> "副驾"
        BYDAutoAcDevice.AC_TEMPERATURE_REAR -> "后排"
        else -> "区域$area"
    }

    // ==================== 环境光传感器 ====================

    // 跟 acListener 同样的懒加载理由——不能在字段初始化时就 new 一个继承
    // 自 AbsBYDAutoSensorListener 的匿名类，否则 simulate 版本在没有 BYD
    // 车机框架的手机上导入插件会立刻 "Failed resolution of
    // AbsBYDAutoSensorListener"。
    private var sensorListener: AbsBYDAutoSensorListener? = null

    fun initSensorDevice(context: Context): Boolean {
        return try {
            sensorDevice = BYDAutoSensorDevice.getInstance(context)
            if (sensorDevice == null) {
                log("❌ BYDAutoSensorDevice.getInstance 返回 null")
                return false
            }
            log("✅ 获取 BYDAutoSensorDevice 实例成功")
            enableDevice(context, sensorDevice!!, "Sensor 设备")
            val listener = sensorListener ?: object : AbsBYDAutoSensorListener() {
                override fun onLightIntensityChanged(value: Int) {
                    log("🔔 [Sensor监听] 光照等级变化 level=$value")
                    onLightIntensityChangedExternal?.invoke(value)
                }
            }.also { sensorListener = it }
            sensorDevice!!.registerListener(listener)
            log("✅ 已注册 Sensor 监听器")
            true
        } catch (e: Throwable) {
            log("❌ 初始化 Sensor 设备失败: ${e.javaClass.simpleName} - ${e.message}")
            false
        }
    }

    /**
     * 光照（环境光）强度等级，1~5：1 最亮(>230Lux)，5 最暗(<80Lux)。
     * 注意常量名——bydauto-sensor.md 文档里写的是 LIGHT_ILLUM_LEVELx，
     * 反编译实际的 SDK 桩之后确认编译期常量其实叫
     * BYDAutoSensorDevice.LIGHT_INTENSITY_LEVELx，文档和真实 SDK 对不上，
     * 这里以反编译结果为准。
     */
    fun getLightIntensity(context: Context): Int? {
        if (sensorDevice == null && !initSensorDevice(context)) return null
        return try {
            log("◀ 光照等级 getLightIntensity()")
            val result = sensorDevice!!.getLightIntensity()
            logIntValue("result", result)
            result
        } catch (e: Throwable) {
            log("⚠️ 读取光照等级失败: ${e.message}")
            null
        }
    }

    // ==================== 车速 / 油门 / 制动（探测，可能不需要权限） ====================

    /**
     * 官方文档说车速、油门深度、制动深度的读取和监听都不需要权限。这里
     * getInstance + enableDevice + 注册监听器，全程 try/catch，任何一步
     * 抛异常都打日志但不影响后面——目的是在真车上确认到底能不能读到。
     */
    fun initSpeedDevice(context: Context): Boolean {
        return try {
            speedDevice = BYDAutoSpeedDevice.getInstance(context)
            if (speedDevice == null) {
                log("❌ BYDAutoSpeedDevice.getInstance 返回 null")
                return false
            }
            log("✅ 获取 BYDAutoSpeedDevice 实例成功")
            enableDevice(context, speedDevice!!, "Speed 设备")
            val listener = speedListener ?: object : AbsBYDAutoSpeedListener() {
                override fun onSpeedChanged(value: Double) {
                    log("🔔 [Speed监听] 车速变化 $value km/h")
                    onSpeedChangedExternal?.invoke(value)
                }
                override fun onAccelerateDeepnessChanged(value: Int) {
                    log("🔔 [Speed监听] 油门深度变化 $value%")
                    onAccelerateDeepnessChangedExternal?.invoke(value)
                }
                override fun onBrakeDeepnessChanged(value: Int) {
                    log("🔔 [Speed监听] 制动深度变化 $value%")
                    onBrakeDeepnessChangedExternal?.invoke(value)
                }
            }.also { speedListener = it }
            speedDevice!!.registerListener(listener)
            log("✅ 已注册 Speed 监听器（踩一下油门/刹车看有没有 🔔 日志）")
            true
        } catch (e: Throwable) {
            log("❌ 初始化 Speed 设备失败: ${e.javaClass.simpleName} - ${e.message}")
            false
        }
    }

    /**
     * 读一次当前车速、油门深度、制动深度，各自独立 try/catch，便于区分哪个能读哪个被拒。
     * 设备初始化失败、或者三项一个都没读到就抛异常——自检靠异常判定失败，
     * 以前这里把异常全吞了，真车上初始化就被拒，自检还照样报通过。
     */
    fun probeSpeed(context: Context): String {
        if (speedDevice == null && !initSpeedDevice(context)) throw IllegalStateException("初始化 Speed 设备失败，见上方")
        val dev = speedDevice ?: throw IllegalStateException("初始化 Speed 设备失败，见上方")
        var ok = 0
        try {
            log("◀ 车速 getCurrentSpeed() = ${dev.getCurrentSpeed()} km/h")
            ok++
        } catch (e: Throwable) {
            log("⚠️ 读车速失败: ${e.javaClass.simpleName} - ${e.message}")
        }
        try {
            log("◀ 油门深度 getAccelerateDeepness() = ${dev.getAccelerateDeepness()}%")
            ok++
        } catch (e: Throwable) {
            log("⚠️ 读油门深度失败: ${e.javaClass.simpleName} - ${e.message}")
        }
        try {
            log("◀ 制动深度 getBrakeDeepness() = ${dev.getBrakeDeepness()}%")
            ok++
        } catch (e: Throwable) {
            log("⚠️ 读制动深度失败: ${e.javaClass.simpleName} - ${e.message}")
        }
        if (ok == 0) throw IllegalStateException("车速/油门/制动一项都没读到，见上方")
        return "读到 $ok/3 项"
    }

    // ==================== 车身：车架号 / 车型（需要 bodywork 权限，先探测） ====================

    /**
     * 车身设备按文档需要 BYDAUTO_BODYWORK 权限（壳暂未声明、签名由用户后续
     * 处理），所以这里大概率会在 getInstance 或调用时抛权限异常——照样全程
     * try/catch，把真车上到底是哪一步、什么异常打清楚。
     */
    fun initBodyworkDevice(context: Context): Boolean {
        return try {
            bodyworkDevice = BYDAutoBodyworkDevice.getInstance(context)
            if (bodyworkDevice == null) {
                log("❌ BYDAutoBodyworkDevice.getInstance 返回 null")
                return false
            }
            log("✅ 获取 BYDAutoBodyworkDevice 实例成功")
            enableDevice(context, bodyworkDevice!!, "Bodywork 设备")
            true
        } catch (e: Throwable) {
            log("❌ 初始化 Bodywork 设备失败: ${e.javaClass.simpleName} - ${e.message}")
            false
        }
    }

    /**
     * 探测车架号和车型。VIN 优先用反射调 getRealAutoVIN()（官方 jar 里没有
     * 这个方法、是竞品反编译发现的隐藏接口），没有或失败再退回 jar 里声明
     * 的 getAutoVIN()。车型同理：先 jar 里的 getAutoModelName()，再反射试
     * getAutoType()。返回的整数用反射扫 AUTO_MODEL_* / AUTO_TYPE_* 常量
     * 反查名字（覆盖全部近百个常量，不用手抄）。
     * 跟 probeSpeed 一样：初始化失败、或车架号和车型都没读到就抛异常，
     * 否则返回读到了什么，给自检当结果显示。
     */
    fun probeBodywork(context: Context): String {
        if (bodyworkDevice == null && !initBodyworkDevice(context)) throw IllegalStateException("初始化 Bodywork 设备失败，见上方")
        val dev = bodyworkDevice ?: throw IllegalStateException("初始化 Bodywork 设备失败，见上方")

        // 先把真车上这个类实际有哪些方法列出来——官方 jar 只是编译桩，真机
        // 的类可能多出 getRealAutoVIN/getAutoType 这类隐藏方法。
        listInstanceMethods(dev, "BYDAutoBodyworkDevice")

        // --- 车架号 VIN ---
        var vin: String? = callStringByReflection(dev, "getRealAutoVIN")
        if (vin != null) {
            log("◀ 车架号 getRealAutoVIN() = $vin")
        } else {
            try {
                vin = dev.getAutoVIN()
                log("◀ 车架号 getAutoVIN() = $vin")
            } catch (e: Throwable) {
                log("⚠️ 读车架号失败: ${e.javaClass.simpleName} - ${e.message}")
            }
        }

        // --- 车型 ---
        var modelLabel: String? = null
        try {
            val model = dev.getAutoModelName()
            modelLabel = "$model (${constName("AUTO_MODEL_", model) ?: "未知代码"})"
            log("◀ 车型 getAutoModelName() = $modelLabel")
        } catch (e: Throwable) {
            log("⚠️ 读车型 getAutoModelName() 失败: ${e.javaClass.simpleName} - ${e.message}")
        }
        val autoType = callIntByReflection(dev, "getAutoType")
        if (autoType != null) {
            log("◀ 车辆代码 getAutoType() = $autoType (${constName("AUTO_TYPE_", autoType) ?: "未知代码"})")
        } else {
            log("ℹ️ 没有 getAutoType() 方法（或调用失败），跳过")
        }

        if (vin == null && modelLabel == null) throw IllegalStateException("车架号和车型都没读到，见上方")
        return listOfNotNull(vin?.let { "车架号=$it" }, modelLabel?.let { "车型=$it" }).joinToString("，")
    }

    /** 反射调一个无参返回 String 的方法；方法不存在或抛异常都返回 null（异常会打日志）。 */
    private fun callStringByReflection(device: Any, name: String): String? {
        return try {
            val m = device.javaClass.getMethod(name)
            m.invoke(device) as? String
        } catch (e: NoSuchMethodException) {
            null
        } catch (e: Throwable) {
            log("⚠️ 反射调 $name() 失败: ${e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName} - ${e.cause?.message ?: e.message}")
            null
        }
    }

    /** 反射调一个无参返回 int 的方法；方法不存在返回 null，调用抛异常也返回 null（打日志）。 */
    private fun callIntByReflection(device: Any, name: String): Int? {
        return try {
            val m = device.javaClass.getMethod(name)
            (m.invoke(device) as? Number)?.toInt()
        } catch (e: NoSuchMethodException) {
            null
        } catch (e: Throwable) {
            log("⚠️ 反射调 $name() 失败: ${e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName} - ${e.cause?.message ?: e.message}")
            null
        }
    }

    /** 在 BYDAutoBodyworkDevice 的静态 int 常量里，按前缀找出等于 value 的那个常量名（反查车型代码）。 */
    private fun constName(prefix: String, value: Int): String? {
        return try {
            BYDAutoBodyworkDevice::class.java.fields
                .firstOrNull { it.name.startsWith(prefix) && it.type == Int::class.javaPrimitiveType && it.getInt(null) == value }
                ?.name
        } catch (e: Throwable) {
            null
        }
    }

    /** 把某个设备实例的所有 public 方法名列出来（排重排序），用来发现官方 jar 没声明的隐藏接口。 */
    private fun listInstanceMethods(device: Any, label: String) {
        try {
            val names = device.javaClass.methods
                .filter { it.declaringClass.name.startsWith("android.hardware.bydauto") }
                .map { "${it.name}(${it.parameterTypes.joinToString(",") { p -> p.simpleName }})" }
                .distinct().sorted()
            log("🔎 $label 实际方法（${names.size}）: ${names.joinToString(", ")}")
        } catch (e: Throwable) {
            log("⚠️ 列 $label 方法失败: ${e.message}")
        }
    }

    /** 逐个检查壳声明的 BYDAUTO 权限，返回没授权的那些（空列表 = 全部已授权）。 */
    fun checkBydPermissions(context: Context): List<String> {
        val perms = listOf(
            "android.permission.BYDAUTO_SETTING_GET",
            "android.permission.BYDAUTO_SETTING_SET",
            "android.permission.BYDAUTO_SETTING_COMMON",
            "android.permission.BYDAUTO_AC_GET",
            "android.permission.BYDAUTO_AC_SET",
            "android.permission.BYDAUTO_AC_COMMON",
            // 车速、车身设备实车初始化时被拒，probeBydPermissions 查到这两个
            // 都是 dangerous 级，在自检页上点按钮运行时申请（PERM_SPEED/PERM_BODYWORK）。
            "android.permission.BYDAUTO_SPEED_COMMON",
            "android.permission.BYDAUTO_BODYWORK_COMMON"
            // Sensor 不需要单独申请权限，开发文档里写明了，不在这里列。
        )
        val pm = context.packageManager
        return perms.filter { perm ->
            val granted = try {
                pm.checkPermission(perm, context.packageName) == PackageManager.PERMISSION_GRANTED
            } catch (e: Throwable) { false }
            log(if (granted) "✅ $perm 已授权" else "❌ $perm 未授权")
            !granted
        }
    }

    // ==================== 车机定义的全部 BYDAUTO 权限（探测，只读） ====================

    /**
     * 列出车机上定义的全部 BYDAUTO_* 权限和保护级别，用来确认车速/车身等设备
     * 到底要哪个权限、是 dangerous（运行时申请即可）还是 signature（只能靠签名）。
     * 只读，不申请任何东西。
     *
     * Android 没有"列出全部权限"的 API，三路合起来按名字去重：
     * 1. 包声明的权限：android 包 + getInstalledPackages 能看到的包。壳没有
     *    QUERY_ALL_PACKAGES，Android 11+ 只看得到一部分包，可见包个数打进日志。
     * 2. 按权限组查：每个权限组 + null（不属于任何组的）。
     * 3. 按命名规律猜：设备名 × GET/SET/COMMON 逐个 getPermissionInfo，
     *    这一路不受包可见性限制。设备名来自 docs/项目历程与架构.md 第七节的
     *    HAL 设备清单，加几个常见别名。
     * 每一路单独 try/catch，一路出错不影响其它路。返回找到的权限个数。
     */
    fun probeBydPermissions(context: Context): Int {
        val pm = context.packageManager
        val found = sortedMapOf<String, PermissionInfo>()
        fun collect(infos: Array<PermissionInfo>?): Int {
            var n = 0
            infos?.forEach { p ->
                if (p.name?.contains("BYDAUTO") == true) {
                    found.putIfAbsent(p.name, p)
                    n++
                }
            }
            return n
        }

        // 1. 包声明的权限
        var fromPackages = 0
        var visiblePackages = 0
        try {
            fromPackages += collect(pm.getPackageInfo("android", PackageManager.GET_PERMISSIONS).permissions)
        } catch (e: Throwable) {
            log("⚠️ 读 android 包声明的权限失败: ${e.javaClass.simpleName} - ${e.message}")
        }
        try {
            val pkgs = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            visiblePackages = pkgs.size
            pkgs.forEach { fromPackages += collect(it.permissions) }
        } catch (e: Throwable) {
            log("⚠️ 遍历已安装包声明的权限失败: ${e.javaClass.simpleName} - ${e.message}")
        }

        // 2. 按权限组查
        var fromGroups = 0
        try {
            val groups = pm.getAllPermissionGroups(0).map { it.name } + listOf<String?>(null)
            groups.forEach { g ->
                try {
                    fromGroups += collect(pm.queryPermissionsByGroup(g, 0).toTypedArray())
                } catch (e: Throwable) {
                    log("⚠️ 查权限组 $g 失败: ${e.javaClass.simpleName} - ${e.message}")
                }
            }
        } catch (e: Throwable) {
            log("⚠️ 列权限组失败: ${e.javaClass.simpleName} - ${e.message}")
        }

        // 3. 按命名规律猜
        var fromGuess = 0
        try {
            val devices = listOf(
                "AC", "BODYWORK", "AUDIO", "LIGHT", "ENERGY", "INSTRUMENT", "PM2P5", "PM25",
                "CHARGING", "GEARBOX", "ENGINE", "SPEED", "STATISTIC", "TYRE", "SETTING", "TIME",
                "RADAR", "PANORAMA", "DOORLOCK", "SENSOR", "SAFETYBELT", "MULTIMEDIA", "ADAS",
                "VERSION", "OTA", "POWER"
            )
            devices.forEach { d ->
                listOf("GET", "SET", "COMMON").forEach { s ->
                    try {
                        val p = pm.getPermissionInfo("android.permission.BYDAUTO_${d}_$s", 0)
                        found.putIfAbsent(p.name, p)
                        fromGuess++
                    } catch (e: PackageManager.NameNotFoundException) {
                        // 没有这个权限，静默跳过
                    }
                }
            }
        } catch (e: Throwable) {
            log("⚠️ 按名猜权限失败: ${e.javaClass.simpleName} - ${e.message}")
        }

        log("🔎 车机定义的 BYDAUTO 权限共 ${found.size} 个（可见包 $visiblePackages 个；来源：包声明 $fromPackages、权限组 $fromGroups、按名猜中 $fromGuess）")
        found.forEach { (name, p) ->
            val granted = try {
                pm.checkPermission(name, context.packageName) == PackageManager.PERMISSION_GRANTED
            } catch (e: Throwable) { false }
            @Suppress("DEPRECATION")
            val level = protectionName(p.protectionLevel)
            log("  ${if (granted) "✅" else "⛔"} ${name.removePrefix("android.permission.")} 级别=$level 定义包=${p.packageName}")
        }
        return found.size
    }

    /** 把 PermissionInfo.protectionLevel 翻译成可读名字，比如 signature|privileged(18)。 */
    @Suppress("DEPRECATION")
    private fun protectionName(level: Int): String {
        val base = when (level and PermissionInfo.PROTECTION_MASK_BASE) {
            0 -> "normal"
            1 -> "dangerous"
            2 -> "signature"
            3 -> "signatureOrSystem"
            4 -> "internal"
            else -> "base0x${(level and PermissionInfo.PROTECTION_MASK_BASE).toString(16)}"
        }
        val flagNames = listOf(
            0x10 to "privileged", 0x20 to "development", 0x40 to "appop", 0x80 to "pre23",
            0x100 to "installer", 0x200 to "verifier", 0x400 to "preinstalled", 0x800 to "setup",
            0x1000 to "instant", 0x2000 to "runtimeOnly"
        )
        var rest = level and PermissionInfo.PROTECTION_MASK_FLAGS
        val parts = mutableListOf(base)
        flagNames.forEach { (bit, name) ->
            if (rest and bit != 0) {
                parts += name
                rest = rest and bit.inv()
            }
        }
        if (rest != 0) parts += "0x${rest.toString(16)}"
        return "${parts.joinToString("|")}($level)"
    }
}
