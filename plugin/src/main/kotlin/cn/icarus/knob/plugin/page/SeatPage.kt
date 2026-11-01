package cn.icarus.knob.plugin.page

import android.graphics.Color
import android.view.Gravity
import android.view.View
import cn.icarus.knob.api.Ui
import cn.icarus.knob.plugin.MainPlugin
import cn.icarus.knob.plugin.seatLevelLabel

/** 座椅停靠点：4 个座位共用这一页，按钮切座位（跟旋钮单击同一个入口）。 */
internal fun MainPlugin.buildSeatPage(): View {
    val ctx = appContext!!
    return Ui.vStack(ctx).apply {
        gravity = Gravity.CENTER
        setBackgroundColor(Color.parseColor(Ui.COLOR_BG))
        addView(Ui.title(ctx, "🪑 座椅"))
        addView(Ui.space(ctx, 0, 12))
        addView(buildSeatRow())
        addStopFooter(this, ctx)
    }
}

/**
 * 座椅的"座位切换 － 档位 ＋ OK"，跟空调页的 buildTempRow 不共用是因为这里
 * 显示的是"通风2档"这种文字档位，不是"数值+单位"，格式完全不同。
 * "座位:xx" 按钮跟旋钮单击是同一个 cycleSeat() 入口，切完显示新座位的档位；
 * －/＋ 只改本地待提交档位，OK 才真正下发。
 */
private fun MainPlugin.buildSeatRow(): View {
    val ctx = appContext!!
    val slot = seatSlots[currentSeatIndex]
    val level = seatLevelFor(slot.id)
    return Ui.card(ctx).apply {
        addView(Ui.label(ctx, "座椅"))
        addView(Ui.hStack(ctx).apply {
            addView(Ui.btn(ctx, "座位:${slot.label}") { cycleSeat() })
            addView(Ui.btn(ctx, "－") {
                setSeatLevelFor(slot.id, (level - 1).coerceIn(MainPlugin.SEAT_LEVEL_MIN, MainPlugin.SEAT_LEVEL_MAX))
                persistState()
                refreshCurrentPage()
            })
            addView(Ui.text(ctx, seatLevelLabel(level), size = 24f, bold = true).apply { setPadding(dp(16), 0, dp(16), 0) })
            addView(Ui.btn(ctx, "＋") {
                setSeatLevelFor(slot.id, (level + 1).coerceIn(MainPlugin.SEAT_LEVEL_MIN, MainPlugin.SEAT_LEVEL_MAX))
                persistState()
                refreshCurrentPage()
            })
            addView(Ui.primaryBtn(ctx, "OK") { carActions.commitSeatLevel(slot.id, seatLevelFor(slot.id)) })
        })
    }
}
