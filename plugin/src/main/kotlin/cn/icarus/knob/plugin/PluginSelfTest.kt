package cn.icarus.knob.plugin

import android.content.Context
import android.hardware.bydauto.ac.BYDAutoAcDevice
import android.hardware.bydauto.setting.BYDAutoSettingDevice
import cn.icarus.knob.api.KnobHost

/**
 * 一键全量自检（从壳的 SelfTest 迁移到插件）。
 * 接收 CarControl 实例执行车控，日志通过 logger 回调。
 */
class PluginSelfTest(
    private val control: CarControl,
    private val logger: KnobHost
) {

    fun runAll(context: Context) {
        val sb = StringBuilder()
        fun out(msg: String) {
            sb.append(msg).append("\n")
            logger.log(msg)
        }

        out("🚀 一键全量自检开始")
        var pass = 0
        var fail = 0

        fun run(name: String, action: () -> String) {
            try {
                val result = action()
                out("[自检] ✅ $name : 返回码=$result")
                pass++
            } catch (e: Throwable) {
                out("[自检] ❌ $name : ${e.javaClass.simpleName} - ${e.message}")
                fail++
            }
            try { Thread.sleep(300) } catch (ignored: InterruptedException) {}
        }

        // 权限
        run("权限检查") {
            val missing = control.checkBydPermissions(context)
            if (missing.isNotEmpty()) throw IllegalStateException("缺 ${missing.size} 个权限: ${missing.joinToString(", ") { it.removePrefix("android.permission.") }}")
            "全部已授权"
        }
        // 列出车机上定义的全部 BYDAUTO 权限和保护级别，确认车速/车身等设备要
        // 哪个权限、能不能运行时申请。一个都没找到说明探测方法本身不灵，算失败。
        run("列出车机 BYDAUTO 权限") {
            val n = control.probeBydPermissions(context)
            if (n == 0) throw IllegalStateException("一个 BYDAUTO 权限都没找到")
            "$n 个"
        }
        // 初始化
        run("初始化 Setting 设备") { if (!control.initSettingDevice(context)) throw RuntimeException("失败"); "ok" }
        run("初始化 AC 设备") { if (!control.initAcDevice(context)) throw RuntimeException("失败"); "ok" }

        // 车速/油门/制动探测（官方文档说只读+监听不需要权限，试试看）。只读不写，
        // 注册监听器后踩油门/刹车会在日志里出现 🔔 行。初始化失败或一项都没
        // 读到时 probeSpeed 会抛异常，这里如实计为失败。
        run("探测车速/油门/制动") { control.probeSpeed(context) }
        // 车架号/车型探测（文档说车身设备需要 bodywork 权限，签名后续处理；
        // 这里先列出真机上有哪些接口，并把权限异常打清楚）。同样失败会抛异常。
        run("探测车架号/车型") { control.probeBodywork(context) }

        // 座椅通风
        run("座椅通风-低档") { control.setDriverSeatVentilating(context, BYDAutoSettingDevice.SEAT_VENTILATING_LOW)?.toString() ?: throw RuntimeException("null") }
        run("座椅通风-高档") { control.setDriverSeatVentilating(context, BYDAutoSettingDevice.SEAT_VENTILATING_HIGH)?.toString() ?: throw RuntimeException("null") }
        run("座椅通风-关闭") { control.setDriverSeatVentilating(context, BYDAutoSettingDevice.SEAT_VENTILATING_OFF)?.toString() ?: throw RuntimeException("null") }
        run("读座椅通风状态") { control.getDriverSeatVentilating(context)?.toString() ?: throw RuntimeException("null") }

        // 空调温度
        val temps = listOf(BYDAutoAcDevice.AC_TEMP_IN_CELSIUS_MIN, 22, BYDAutoAcDevice.AC_TEMP_IN_CELSIUS_MAX)
        temps.forEach { t ->
            run("空调主驾 ${t}°C") { control.setAcTemperature(context, BYDAutoAcDevice.AC_TEMPERATURE_MAIN, t)?.toString() ?: throw RuntimeException("null") }
        }
        run("空调副驾 22°C") { control.setAcTemperature(context, BYDAutoAcDevice.AC_TEMPERATURE_DEPUTY, 22)?.toString() ?: throw RuntimeException("null") }
        run("空调后排 22°C") { control.setAcTemperature(context, BYDAutoAcDevice.AC_TEMPERATURE_REAR, 22)?.toString() ?: throw RuntimeException("null") }
        run("空调主副联动 22°C") { control.setAcTemperature(context, BYDAutoAcDevice.AC_TEMPERATURE_MAIN_DEPUTY, 22)?.toString() ?: throw RuntimeException("null") }
        listOf(
            BYDAutoAcDevice.AC_TEMPERATURE_MAIN to "主驾",
            BYDAutoAcDevice.AC_TEMPERATURE_DEPUTY to "副驾",
            BYDAutoAcDevice.AC_TEMPERATURE_REAR to "后排"
        ).forEach { (area, label) ->
            run("读空调$label 温度") { control.getAcTemperature(context, area)?.toString() ?: throw RuntimeException("null") }
        }

        // 分控
        run("分控-开启") { control.setAcTemperatureControlMode(context, true)?.toString() ?: throw RuntimeException("null") }
        run("分控-关闭") { control.setAcTemperatureControlMode(context, false)?.toString() ?: throw RuntimeException("null") }

        // 空调更多功能
        run("空调开关-开启") { control.setAcPower(context, true)?.toString() ?: throw RuntimeException("null") }
        run("空调风量-3档") { control.setAcWindLevel(context, 3)?.toString() ?: throw RuntimeException("null") }
        run("空调风量-5档") { control.setAcWindLevel(context, 5)?.toString() ?: throw RuntimeException("null") }
        run("空调风向-吹脸(1)") { control.setAcWindMode(context, 1)?.toString() ?: throw RuntimeException("null") }
        run("空调风向-吹脚(3)") { control.setAcWindMode(context, 3)?.toString() ?: throw RuntimeException("null") }
        run("空调循环-内循环") { control.setAcCycleMode(context, true)?.toString() ?: throw RuntimeException("null") }
        run("空调循环-外循环") { control.setAcCycleMode(context, false)?.toString() ?: throw RuntimeException("null") }
        run("空调开关-关闭") { control.setAcPower(context, false)?.toString() ?: throw RuntimeException("null") }

        // 异常温度边界
        run("异常温度-16°C") { control.setAcTemperature(context, BYDAutoAcDevice.AC_TEMPERATURE_MAIN, 16)?.toString() ?: throw RuntimeException("null") }
        run("异常温度-34°C") { control.setAcTemperature(context, BYDAutoAcDevice.AC_TEMPERATURE_MAIN, 34)?.toString() ?: throw RuntimeException("null") }
        run("异常温度--5°C") { control.setAcTemperature(context, BYDAutoAcDevice.AC_TEMPERATURE_MAIN, -5)?.toString() ?: throw RuntimeException("null") }
        run("异常温度-99°C") { control.setAcTemperature(context, BYDAutoAcDevice.AC_TEMPERATURE_MAIN, 99)?.toString() ?: throw RuntimeException("null") }

        out("🏁 自检完成：通过 $pass 项，失败 $fail 项")
        _lastResult = sb.toString()
    }

    private var _lastResult: String = ""

    fun lastResult(): String = _lastResult
}
