package cn.icarus.knob.plugin.page

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import cn.icarus.knob.api.Ui
import cn.icarus.knob.plugin.MainPlugin

// 几个页面共用的零件。
//
// 页面一个文件（这个目录下的 *Page.kt），都写成 MainPlugin 的扩展函数：页面
// 只是"按插件当前状态画出来"，状态和业务逻辑（停靠点状态机、车控提交、BLE）
// 都留在 MainPlugin，页面直接读写它的 internal 成员。自检页自己有跑测试的
// 状态，所以是个类（见 SelfTestPage）。

/** 车控自检入口按钮——纯车控诊断，跟旋钮连没连无关，连接页/车控页都放一个。只打开页面，不直接开跑。 */
internal fun MainPlugin.selfTestButton(ctx: Context): View = Ui.btn(ctx, "🔧 自检") { selfTestPage.open() }

/** 车控页底部那一行旋钮固件信息，只在连着旋钮时显示。 */
internal fun MainPlugin.firmwareInfoHint(ctx: Context): View {
    val knob = "旋钮固件 ${ota.knobVersion ?: "未知（固件太旧）"}（${ota.knobBoard ?: "未知板子"}）"
    val bundled = ota.bundle?.let { "\n插件包内置 ${it.version}（${it.board}）" } ?: ""
    return Ui.hint(ctx, knob + bundled)
}

/** 车控停靠点页面（空调、座椅）底部共用的一排：下一个、返回蓝牙页 / 固件信息、自检。 */
internal fun MainPlugin.addStopFooter(page: LinearLayout, ctx: Context) = page.apply {
    addView(Ui.space(ctx, 0, 12))
    addView(Ui.btn(ctx, "🔀 下一个") { advanceStop() })
    // 只有真的没连 BLE、靠 forceShowCarControl 跳过来的情况下才需要
    // 这个按钮——已经连上 knob 时没有"手动回蓝牙页"的必要。
    if (!bleConnected) {
        addView(Ui.btn(ctx, "📶 返回蓝牙页") { backToBluetoothPage() })
    } else {
        addView(Ui.space(ctx, 0, 8))
        addView(firmwareInfoHint(ctx))
    }
    addView(Ui.space(ctx, 0, 8))
    addView(selfTestButton(ctx))
}

/** 车控页"返回蓝牙页"按钮：撤销首页的"跳过"（见 skipToCarControl），退回首页。 */
private fun MainPlugin.backToBluetoothPage() {
    host?.log("👈 用户从车控页手动返回蓝牙页")
    forceShowCarControl = false
    refreshCurrentPage()
}
