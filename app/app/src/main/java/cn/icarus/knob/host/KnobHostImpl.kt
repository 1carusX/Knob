package cn.icarus.knob.host

import cn.icarus.knob.api.KnobHost
import cn.icarus.knob.util.LogSink

/**
 * 壳实现 [KnobHost]：只剩日志这一件事——log() 转发到 LogSink 显示。
 * BLE 连接已经整体移进插件自己的 BleManager，壳不再插手。
 */
object KnobHostImpl : KnobHost {
    override fun log(message: String) {
        LogSink.append("[插件] $message")
    }
}
