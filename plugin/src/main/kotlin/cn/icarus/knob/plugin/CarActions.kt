package cn.icarus.knob.plugin

import android.hardware.bydauto.ac.BYDAutoAcDevice
import android.hardware.bydauto.setting.BYDAutoSettingDevice

/**
 * 停靠点的车控读写：旋钮长按 / 触屏 OK 时把数值真正下发给车，进空调停靠点时
 * 读一次真实车况。模拟模式只打日志；真车模式缺权限就不执行（见 CarPermissions.has）。
 */
internal class CarActions(private val plugin: MainPlugin) {

    companion object {
        // 联动提交时"设完双区温度 → 关分控"之间的延时，比亚迪建议加延时
        // 但没给具体值，需实车验证调整。见 commitAc()。
        private const val AC_LINK_MODE_DELAY_MS = 500L
    }

    // 联动提交里"延时后关分控"那一步，还没执行时再次提交要先取消，
    // 否则它会晚到一步把新停靠点刚打开的分控又关掉。只在主线程读写。
    private var pendingAcLinkMode: Runnable? = null

    /**
     * 从车况刷新主驾/副驾温度（进空调类停靠点时调，停靠点内部调整不调）。
     * 只读 MAIN/DEPUTY 这两个 area——MAIN_DEPUTY 对 getTemprature 来说是
     * 无效参数（只对 setAcTemperature 的 type 有效，官方文档写明了，这也
     * 是之前那个"-21亿"读数的根因），不读它；联动停靠点显示/编辑的
     * acMainTemp 已经靠这里刷新。分控开关不读也不缓存，见 VehicleState
     * 的注释。
     */
    fun refreshAcTemperatures() {
        if (SIMULATE_CAR_CONTROL) {
            plugin.host?.log("🧪（模拟）跳过读取车况，沿用当前本地值")
            return
        }
        if (!plugin.permissions.has(listOf(CarControl.PERM_AC), "读空调温度")) return
        val ctrl = plugin.carControl ?: return
        val context = plugin.appContext ?: return
        ctrl.getAcTemperature(context, BYDAutoAcDevice.AC_TEMPERATURE_MAIN)?.let { plugin.vehicleState.acMainTemp = it }
        ctrl.getAcTemperature(context, BYDAutoAcDevice.AC_TEMPERATURE_DEPUTY)?.let { plugin.vehicleState.acDeputyTemp = it }
        plugin.persistState()
    }

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
    fun commitAc(separate: Boolean, area: Int, value: Int) {
        if (SIMULATE_CAR_CONTROL) {
            plugin.host?.log("🧪（模拟）setAcTemperatureControlMode separate=$separate; setAcTemperature area=$area value=$value")
            return
        }
        if (!plugin.permissions.has(listOf(CarControl.PERM_AC), "调空调温度")) return
        val ctrl = plugin.carControl ?: return
        val context = plugin.appContext ?: return
        pendingAcLinkMode?.let { plugin.mainHandler.removeCallbacks(it) }
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
        plugin.mainHandler.postDelayed(r, AC_LINK_MODE_DELAY_MS)
    }

    /**
     * 座椅 OK 键：真正下发座椅通风/加热。level 的符号选属性、数值选档位
     * （见 MainPlugin.SEAT_LEVEL_MIN/MAX 的注释）：>0 调 setSeatHeating，<0 调
     * setSeatVentilating，=0 两个属性的 OFF 都发一遍——0 本身没有记录
     * "关闭前是加热还是通风"，两个都发一遍能保证不管之前是哪个都被关掉，
     * 已经是 OFF 的那个再发一次 OFF 没有副作用。
     */
    fun commitSeatLevel(seatId: Int, level: Int) {
        if (SIMULATE_CAR_CONTROL) {
            plugin.host?.log("🧪（模拟）座椅(seat=$seatId) -> ${seatLevelLabel(level)}")
            return
        }
        if (!plugin.permissions.has(listOf(CarControl.PERM_SETTING), "调座椅")) return
        val ctrl = plugin.carControl ?: return
        val context = plugin.appContext ?: return
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
}

/** 座椅档位 -> 人能看懂的文字，UI 标签和日志都用这个，保证显示一致。 */
internal fun seatLevelLabel(level: Int): String = when {
    level <= -2 -> "通风2档"
    level == -1 -> "通风1档"
    level == 0 -> "关闭"
    level == 1 -> "加热1档"
    else -> "加热2档"
}
