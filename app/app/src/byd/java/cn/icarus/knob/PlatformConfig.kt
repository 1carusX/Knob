package cn.icarus.knob

/**
 * byd 变体（比亚迪车机）专属配置。generic 变体存在同包同名对象、字段全空，
 * 由 flavor 的 source set 在编译期决定用哪一份（与 plugin/ 的 BuildFlags 同套路）。
 *
 * KnobActivity 只认这里的列表：runtimePerms 非空 → 渲染权限申请按钮并挂点击
 * （按钮图标/文案/权限名全在这里，main 代码不含任何平台字符串）；
 * statusPermissions 非空 → 打印 "BYDAUTO 权限状态" 日志段。空列表即无对应 UI。
 */
object PlatformConfig {

    /**
     * 需界面提供"运行时申请"按钮的 dangerous 权限（签名级 GET/SET 装车即授，无需申请）。
     * 只放插件实际用到的设备：空调、座椅（Setting）、车速、车身。其余 *_COMMON
     * 已在 manifest 声明，插件将来用到时在这里加一个按钮即可。
     */
    val runtimePerms: List<RuntimePerm> = listOf(
        RuntimePerm("android.permission.BYDAUTO_AC_COMMON", "🌡️", "AC_COMMON"),
        RuntimePerm("android.permission.BYDAUTO_SETTING_COMMON", "⚙️", "SETTING_COMMON"),
        RuntimePerm("android.permission.BYDAUTO_SPEED_COMMON", "🏎️", "SPEED_COMMON"),
        RuntimePerm("android.permission.BYDAUTO_BODYWORK_COMMON", "🚙", "BODYWORK_COMMON")
    )

    /** 状态展示（只读检查）用的全部 BYD 权限，与 byd manifest 声明的一一对应；空 = 该平台无 BYDAUTO 权限体系 */
    val statusPermissions: List<String> = listOf(
        "android.permission.BYDAUTO_SETTING_GET",
        "android.permission.BYDAUTO_SETTING_SET",
        "android.permission.BYDAUTO_AC_GET",
        "android.permission.BYDAUTO_AC_SET",
        "android.permission.BYDAUTO_AC_COMMON",
        "android.permission.BYDAUTO_AUDIO_COMMON",
        "android.permission.BYDAUTO_BODYWORK_COMMON",
        "android.permission.BYDAUTO_CHARGING_COMMON",
        "android.permission.BYDAUTO_DOOR_LOCK_COMMON",
        "android.permission.BYDAUTO_ENERGY_COMMON",
        "android.permission.BYDAUTO_ENGINE_COMMON",
        "android.permission.BYDAUTO_GEARBOX_COMMON",
        "android.permission.BYDAUTO_INSTRUMENT_COMMON",
        "android.permission.BYDAUTO_LIGHT_COMMON",
        "android.permission.BYDAUTO_MULTIMEDIA_COMMON",
        "android.permission.BYDAUTO_PANORAMA_COMMON",
        "android.permission.BYDAUTO_PM2P5_COMMON",
        "android.permission.BYDAUTO_RADAR_COMMON",
        "android.permission.BYDAUTO_SAFETY_BELT_COMMON",
        "android.permission.BYDAUTO_SETTING_COMMON",
        "android.permission.BYDAUTO_SPEED_COMMON",
        "android.permission.BYDAUTO_STATISTIC_COMMON",
        "android.permission.BYDAUTO_TIME_COMMON",
        "android.permission.BYDAUTO_TYRE_COMMON"
    )
}
