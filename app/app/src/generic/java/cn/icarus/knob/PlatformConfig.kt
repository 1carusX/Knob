package cn.icarus.knob

/**
 * generic 变体（通用手机/非比亚迪设备）专属配置：无任何平台专属权限体系。
 * 列表为空 → KnobActivity 不打印平台权限状态日志；
 * 产物与代码中都不出现平台相关权限字符串。
 */
object PlatformConfig {

    /** 无平台专属权限可查 */
    val statusPermissions: List<String> = emptyList()
}
