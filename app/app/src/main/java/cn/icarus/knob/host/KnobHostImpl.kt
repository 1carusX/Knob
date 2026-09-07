package cn.icarus.knob.host

import cn.icarus.knob.api.KnobHost
import cn.icarus.knob.ble.BleManager
import cn.icarus.knob.util.LogSink

/**
 * 壳实现 [KnobHost]（传给插件的反向通道）：
 * log() 转发到 LogSink 显示；pushToKnob() 把插件要发往 knob 屏显/指令的 Map
 * 原样交给 BleManager 编码转发。
 *
 * ⚠️ 壳完全不理解字段含义：插件传什么 key/值就发什么，字段意义由插件与固件
 * 两端约定——以后插件加/改显示字段，这一层不用跟着改。
 */
object KnobHostImpl : KnobHost {

    override fun log(message: String) {
        LogSink.append("[插件] $message")
    }

    override fun pushToKnob(data: Map<String, Any>) {
        val text = data.map { "${it.key}=${it.value}" }.joinToString(", ")
        val sent = BleManager.write(data)
        LogSink.append("[插件→knob] pushToKnob: $text -> ${if (sent) "已发送" else "发送失败（GATT 未就绪）"}")
    }
}
