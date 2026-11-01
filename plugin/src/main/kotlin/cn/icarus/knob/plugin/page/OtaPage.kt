package cn.icarus.knob.plugin.page

import android.graphics.Color
import android.view.Gravity
import android.view.View
import cn.icarus.knob.api.Ui
import cn.icarus.knob.plugin.MainPlugin

/** 给旋钮升级固件时固定显示这一页，没有按钮——升级期间不能操作，也不该切走。 */
internal fun MainPlugin.buildOtaPage(): View {
    val ctx = appContext!!
    return Ui.vStack(ctx).apply {
        gravity = Gravity.CENTER
        setBackgroundColor(Color.parseColor(Ui.COLOR_BG))
        addView(Ui.title(ctx, "⬆️ 正在升级旋钮固件"))
        addView(Ui.space(ctx, 0, 12))
        addView(Ui.text(ctx, "${ota.percent ?: 0}%", size = 40f, bold = true))
        addView(Ui.space(ctx, 0, 12))
        addView(Ui.hint(ctx, "${ota.knobVersion ?: "未知"} → ${ota.bundle?.version ?: "未知"}"))
        addView(Ui.hint(ctx, "升级期间旋钮暂时不能用，完成后会自动重启"))
    }
}
