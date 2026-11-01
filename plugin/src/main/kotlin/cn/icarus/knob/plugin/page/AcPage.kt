package cn.icarus.knob.plugin.page

import android.graphics.Color
import android.hardware.bydauto.ac.BYDAutoAcDevice
import android.view.Gravity
import android.view.View
import cn.icarus.knob.api.Ui
import cn.icarus.knob.plugin.MainPlugin

/**
 * 空调两个停靠点共用一个构建函数，按 currentPageId 决定画法——跟旋钮
 * 屏幕的 layout 分发是同一个思路：ac_link 只有联动一组温度框；
 * ac_split 是分控，主驾/副驾共用这一个停靠点，acSplitShowingMain
 * 决定当前哪一侧可编辑，"切换" 按钮跟旋钮单击是同一个 cycleAcSplitSide()
 * 入口，另一侧只读展示做参考，不提供 ±/OK。
 */
internal fun MainPlugin.buildAcPage(): View {
    val ctx = appContext!!
    val title = when {
        currentPageId == "ac_split" && acSplitShowingMain -> "❄️ 空调温度（分控·主驾）"
        currentPageId == "ac_split" -> "❄️ 空调温度（分控·副驾）"
        else -> "❄️ 空调温度（联动）"
    }
    return Ui.vStack(ctx).apply {
        gravity = Gravity.CENTER
        setBackgroundColor(Color.parseColor(Ui.COLOR_BG))
        addView(Ui.title(ctx, title))
        addView(Ui.space(ctx, 0, 12))
        when (currentPageId) {
            "ac_link" -> addView(buildTempRow(
                label = "联动温度",
                value = vehicleState.acMainTemp,
                onChange = { vehicleState.acMainTemp = it; persistState(); refreshCurrentPage() },
                onOk = {
                    carActions.commitAc(separate = false, area = BYDAutoAcDevice.AC_TEMPERATURE_MAIN_DEPUTY, value = vehicleState.acMainTemp)
                    refreshCurrentPage()
                }
            ))
            "ac_split" -> {
                addView(Ui.btn(ctx, if (acSplitShowingMain) "切到副驾" else "切到主驾") { cycleAcSplitSide() })
                addView(Ui.space(ctx, 0, 8))
                if (acSplitShowingMain) {
                    addView(buildTempRow(
                        label = "主驾温度",
                        value = vehicleState.acMainTemp,
                        onChange = { vehicleState.acMainTemp = it; persistState(); refreshCurrentPage() },
                        onOk = {
                            carActions.commitAc(separate = true, area = BYDAutoAcDevice.AC_TEMPERATURE_MAIN, value = vehicleState.acMainTemp)
                            refreshCurrentPage()
                        }
                    ))
                    addView(Ui.space(ctx, 0, 8))
                    addView(Ui.label(ctx, "副驾温度 ${vehicleState.acDeputyTemp}°C（参考，点按钮切到副驾调整）"))
                } else {
                    addView(buildTempRow(
                        label = "副驾温度",
                        value = vehicleState.acDeputyTemp,
                        onChange = { vehicleState.acDeputyTemp = it; persistState(); refreshCurrentPage() },
                        onOk = {
                            carActions.commitAc(separate = true, area = BYDAutoAcDevice.AC_TEMPERATURE_DEPUTY, value = vehicleState.acDeputyTemp)
                            refreshCurrentPage()
                        }
                    ))
                    addView(Ui.space(ctx, 0, 8))
                    addView(Ui.label(ctx, "主驾温度 ${vehicleState.acMainTemp}°C（参考，点按钮切到主驾调整）"))
                }
            }
        }
        addStopFooter(this, ctx)
    }
}

/** 一组"标签 － 数值 ＋ OK"。－/＋ 只改本地待提交值，OK 才真正下发。 */
private fun MainPlugin.buildTempRow(label: String, value: Int, onChange: (Int) -> Unit, onOk: () -> Unit): View {
    val ctx = appContext!!
    return Ui.card(ctx).apply {
        addView(Ui.label(ctx, label))
        addView(Ui.hStack(ctx).apply {
            addView(Ui.btn(ctx, "－") {
                onChange((value - 1).coerceIn(BYDAutoAcDevice.AC_TEMP_IN_CELSIUS_MIN, BYDAutoAcDevice.AC_TEMP_IN_CELSIUS_MAX))
            })
            addView(Ui.text(ctx, "${value}°C", size = 32f, bold = true).apply { setPadding(dp(16), 0, dp(16), 0) })
            addView(Ui.btn(ctx, "＋") {
                onChange((value + 1).coerceIn(BYDAutoAcDevice.AC_TEMP_IN_CELSIUS_MIN, BYDAutoAcDevice.AC_TEMP_IN_CELSIUS_MAX))
            })
            addView(Ui.primaryBtn(ctx, "OK") { onOk() })
        })
    }
}
