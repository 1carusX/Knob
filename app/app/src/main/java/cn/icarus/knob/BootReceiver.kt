package cn.icarus.knob

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.icarus.knob.util.LogSink

/**
 * 开机自启。以前靠无障碍服务绑定把进程拉起来，现改用开机广播：
 * BOOT_COMPLETED 是标准 action；QUICKBOOT_POWERON 是部分国产 ROM/车机"快速
 * 开机"用的非标准 action，不一定会发标准 BOOT_COMPLETED（参考一款同类第三方
 * 车控 App 反编译结果，两个都注册）——两个都注册覆盖车机实际走的路径。
 *
 * onReceive() 本身无需做事：系统投递广播前必须先拉起进程并跑完
 * Application.onCreate（BLE 重连轮询与插件加载那时已生效），这里只记一条日志
 * 确认到底是哪个广播拉起的进程，方便日后排查开机路径。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LogSink.append("[开机自启] 收到广播 action=${intent.action}，进程已拉起")
    }
}
