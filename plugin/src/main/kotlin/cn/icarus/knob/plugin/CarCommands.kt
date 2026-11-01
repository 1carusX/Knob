package cn.icarus.knob.plugin

import android.hardware.bydauto.ac.BYDAutoAcDevice

// KnobPlugin 的控制域（onCommand）和查询域（onQuery）：壳按命令名直接调车控。
// 新增车控功能 = CarControl 加方法 + 这里加一条 when 分支。
// 返回统一结果 Map（见 resultMap）；未知命令返回 null。

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
internal fun MainPlugin.runCommand(command: String, params: Map<String, Any>): Map<String, Any>? {
    val ctrl = carControl ?: return null
    val context = appContext ?: return null
    // 车控命令没权限就不执行（不弹窗申请，见 CarPermissions.has）
    val needed = when {
        command.startsWith("ac.") -> listOf(CarControl.PERM_AC)
        command.startsWith("seat.") -> listOf(CarControl.PERM_SETTING)
        else -> emptyList()
    }
    if (needed.isNotEmpty() && !permissions.has(needed, "onCommand($command)")) {
        return resultMap(false, null, message = "缺权限，没执行")
    }
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
internal fun MainPlugin.runQuery(query: String, params: Map<String, Any>): Map<String, Any>? {
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
                if (!permissions.has(listOf(CarControl.PERM_SETTING), "读座椅通风")) return resultMap(false, null, message = "缺权限，没执行")
                val v = ctrl.getDriverSeatVentilating(context)
                if (v != null) resultMap(true, 0, value = v, message = "读取成功")
                else resultMap(false, null, message = "读取失败")
            }
            "ac.temperature" -> {
                if (!permissions.has(listOf(CarControl.PERM_AC), "读空调温度")) return resultMap(false, null, message = "缺权限，没执行")
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
