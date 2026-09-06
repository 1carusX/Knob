package cn.icarus.knob

/**
 * 一个"需要手动运行时申请"的权限按钮描述——通用模型，本身不含任何平台相关
 * 字符串。具体平台要列哪些权限、图标/文案是什么，由各 flavor 的 PlatformConfig
 * 决定（generic 变体返回空列表，因此产物与代码里都不出现平台专属权限信息）。
 */
data class RuntimePerm(val perm: String, val icon: String, val label: String)
