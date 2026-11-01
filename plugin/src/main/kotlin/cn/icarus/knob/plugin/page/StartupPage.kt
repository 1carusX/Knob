package cn.icarus.knob.plugin.page

import android.graphics.Color
import android.view.View
import android.widget.ScrollView
import cn.icarus.knob.api.Ui
import cn.icarus.knob.plugin.MainPlugin
import cn.icarus.knob.plugin.StartupCheck

/**
 * 启动自检没通过（或还在查）时固定显示这一页：哪项没过、缺哪些权限、申请按钮。
 * 检查本身在 StartupCheck。
 */
internal fun MainPlugin.buildStartupPage(): View {
    val ctx = appContext!!
    val body = Ui.vStack(ctx).apply {
        setBackgroundColor(Color.parseColor(Ui.COLOR_BG))
        addView(Ui.title(ctx, "🔍 启动自检"))
        addView(Ui.space(ctx, 0, 8))
        if (startup.state == StartupCheck.State.CHECKING) {
            addView(Ui.text(ctx, "⏳ 正在检查车控权限和设备…", size = 16f, bold = true))
            return@apply
        }
        addView(Ui.text(ctx, "❌ 自检没通过", size = 16f, bold = true, color = Ui.COLOR_ERROR))
        addView(Ui.hint(ctx, "通过后才会连接旋钮、进入车控。授权后会自动重新检查。"))
        addView(Ui.space(ctx, 0, 8))
        startup.items.sortedBy { it.ok }.forEach { addView(selfTestItemView(ctx, it)) }
        addView(Ui.space(ctx, 0, 12))
        addView(buildPermissionSection(ctx) { startup.run() })
        addView(Ui.hStack(ctx).apply {
            addView(Ui.btn(ctx, "🔁 重新检查") { startup.run() })
            addView(Ui.space(ctx, 12, 0))
            addView(selfTestButton(ctx))
        })
    }
    return ScrollView(ctx).apply {
        setBackgroundColor(Color.parseColor(Ui.COLOR_BG))
        addView(body)
    }
}
