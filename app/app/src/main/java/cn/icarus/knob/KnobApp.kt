package cn.icarus.knob

import android.app.Application
import android.content.Context
import cn.icarus.knob.ble.BleManager
import cn.icarus.knob.host.KnobHostImpl
import cn.icarus.knob.plugin.PluginLoader
import cn.icarus.knob.util.LogSink

/**
 * Application：进程启动时同步加载插件 + 启动 BLE 重连轮询。
 *
 * ⚠️ 同步而非延迟：Application.onCreate 必然早于本进程任何 Activity.onCreate，
 * 同步加载才能保证 KnobActivity 创建时插件已就绪、ui.bind 能命中（dex 只有
 * 几十 KB，耗时忽略）。异常必须吞掉，否则会拖垮进程启动。
 *
 * ⚠️ 坑（历史）：进程以前由无障碍服务在开机时拉起；无障碍服务整体去掉后
 * （改自定义 BLE GATT 通道，见 BleManager）进程就没了自动拉起手段——现在靠
 * BootReceiver 接开机广播把进程拉起来，车机重启后如广播没发则需要手动点开
 * 一次 App 才会运行。
 */
class KnobApp : Application() {
    override fun onCreate() {
        super.onCreate()
        context = applicationContext
        // 常驻后台时 knob 断电重启也能自动把 GATT 通道接回去，不用等
        // Activity 回到前台，见 BleManager.startReconnectPolling
        BleManager.startReconnectPolling(this)
        try {
            val plugin = PluginLoader.load(this)
            plugin?.let {
                // 插件加载成功后 init（传入 host，插件日志转发到壳显示）
                it.init(applicationContext, KnobHostImpl)
                LogSink.append("✅ 插件已加载并初始化")
            } ?: LogSink.append("⚠️ 未加载插件，请在主界面点「选择插件」导入 plugin.dex")
        } catch (e: Throwable) {
            LogSink.append("❌ 插件加载异常: ${e.javaClass.simpleName} - ${e.message}")
        }
    }

    companion object {
        lateinit var context: Context
    }
}
