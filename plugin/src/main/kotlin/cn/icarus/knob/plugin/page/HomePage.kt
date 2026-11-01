package cn.icarus.knob.plugin.page

import android.graphics.Color
import android.view.Gravity
import android.view.View
import cn.icarus.knob.api.Ui
import cn.icarus.knob.plugin.MainPlugin

/**
 * "首页" = 没连上 knob 时强制显示的连接页（见 refreshCurrentPage 的
 * bleConnected 判断），不是可以随便导航过来的菜单。BleManager 后台每
 * 5s 被动扫描一次（不会自动弹配对框，见 BleManager.scanForCandidate），
 * 扫到候选就回调 handleBleCandidateFound，这里才显示出候选名字和
 * 「确认配对」按钮——真正的 createBond() 只在用户点了这个按钮之后才
 * 发生。连上之后 handleBleConnectionChanged 会自动把页面切回原来停靠
 * 的那个停靠点，不需要这里手动跳转。
 */
internal fun MainPlugin.buildHomePage(): View {
    val ctx = appContext!!
    val name = candidateName
    return Ui.vStack(ctx).apply {
        gravity = Gravity.CENTER
        setBackgroundColor(Color.parseColor(Ui.COLOR_BG))
        addView(Ui.title(ctx, "🔌 未连接旋钮"))
        if (name != null) {
            addView(Ui.hint(ctx, "发现设备：$name"))
            addView(Ui.space(ctx, 0, 12))
            addView(Ui.primaryBtn(ctx, "🔗 确认配对") {
                host?.log("👉 用户确认配对 $name")
                // 乐观清空：配对是否成功由后续连接状态变化 / 重新扫描
                // 决定，这里先退回"扫描中"文案，避免配对失败后按钮还
                // 留在原地、再点一次却已经没有候选可配对。
                candidateName = null
                bleManager?.pairCandidate()
                refreshCurrentPage()
            })
        } else {
            addView(Ui.hint(ctx, "正在扫描附近的蓝牙旋钮…"))
        }
        addView(Ui.space(ctx, 0, 12))
        addView(Ui.btn(ctx, "⏭️ 跳过，直接进车控") { skipToCarControl() })
        addView(Ui.space(ctx, 0, 8))
        addView(selfTestButton(ctx))
    }
}

/**
 * 首页"跳过"按钮：不管 BLE 连没连上，都直接进 currentPageId 停靠的
 * 车控页面——CarControl/BYDAuto HAL 本身不依赖 BLE，触屏场景下用户
 * 可能压根不关心旋钮连没连。只影响页面显示，不会假装 knob 已连接，
 * refreshCurrentPage() 仍然靠真实的 bleConnected 决定要不要 syncToKnob()。
 * 车控页上的"返回蓝牙页"按钮撤销它（见 PageParts.kt 的 backToBluetoothPage）。
 */
private fun MainPlugin.skipToCarControl() {
    host?.log("⏭️ 用户跳过蓝牙连接，直接进入车控页面")
    forceShowCarControl = true
    refreshCurrentPage()
}
