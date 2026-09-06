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

    /** 需界面提供"运行时申请"按钮的 dangerous 权限（签名级 GET/SET 装车即授，无需申请） */
    val runtimePerms: List<RuntimePerm> = listOf(
        RuntimePerm("android.permission.BYDAUTO_AC_COMMON", "🌡️", "AC_COMMON"),
        RuntimePerm("android.permission.BYDAUTO_SETTING_COMMON", "⚙️", "SETTING_COMMON")
    )

    /** 状态展示（只读检查）用的全部 BYD 权限；空 = 该平台无 BYDAUTO 权限体系 */
    val statusPermissions: List<String> = listOf(
        "android.permission.BYDAUTO_SETTING_GET",
        "android.permission.BYDAUTO_SETTING_SET",
        "android.permission.BYDAUTO_SETTING_COMMON",
        "android.permission.BYDAUTO_AC_GET",
        "android.permission.BYDAUTO_AC_SET",
        "android.permission.BYDAUTO_AC_COMMON"
    )
}
