package cn.icarus.knob.plugin.page

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import cn.icarus.knob.api.Ui
import cn.icarus.knob.plugin.MainPlugin
import cn.icarus.knob.plugin.PluginSelfTest

/**
 * 自检页：一键全量自检（PluginSelfTest）的界面。叠在停靠点页面上面显示
 * （跟升级页一样不改 currentPageId，旋钮照常能用），点「返回」或系统返回键
 * 关掉。以下状态都只在主线程读写：后台线程逐项结果、跑完都 post 回主线程
 * 再改，所以不用加 volatile。
 */
internal class SelfTestPage(private val plugin: MainPlugin) {

    var isOpen = false
        private set
    private var running = false
    private val items = mutableListOf<PluginSelfTest.Item>()
    private var done = false
    // 开跑那一刻没授权的权限，跑完后对比现在的状态，提示"有新授权，重跑看效果"。
    private var missingAtStart: Set<String> = emptySet()

    // 正在显示的自检页里那几个要逐项更新的 View。跑的过程中每出一项只往
    // 列表末尾追加一行，不整页重绘——整页重绘会把滚动位置打回顶部，而且
    // refreshCurrentPage 每次都会给旋钮同步一次，几十项就是几十次 BLE 写。
    private var statusView: TextView? = null
    private var listView: LinearLayout? = null
    private var scrollView: ScrollView? = null

    fun open() {
        isOpen = true
        plugin.refreshCurrentPage()
    }

    fun close() {
        isOpen = false
        plugin.refreshCurrentPage()
    }

    /** 上面那几个 View 只对当前显示的这一页有效，换页、解绑时先清掉。 */
    fun detachViews() {
        statusView = null
        listView = null
        scrollView = null
    }

    /**
     * 跑一键全量自检。不先申请权限：没授权的项如实记失败，跑完页面上再列出
     * 缺的权限和申请按钮，由用户决定申请哪个。⚠️ 必须开后台线程：runAll 里有
     * 十几次 Thread.sleep(300) 和一串阻塞的设备调用，放主线程会卡住 UI 二三
     * 十秒直接 ANR。结果同时打进日志窗口（车机无 adb，日志是唯一观测手段）。
     */
    private fun start() {
        val ctx = plugin.appContext ?: return
        val st = plugin.selfTest ?: run { plugin.host?.log("❌ 自检未初始化"); return }
        if (running) { plugin.host?.log("⏳ 自检正在进行，请等它跑完"); return }
        running = true
        done = false
        items.clear()
        missingAtStart = plugin.permissions.missing(ctx)
        plugin.refreshCurrentPage()
        plugin.host?.log("🚀 开始自检（后台线程，约 20~30 秒）")
        Thread {
            try {
                st.runAll(ctx) { item -> plugin.mainHandler.post { append(item) } }
            } catch (e: Throwable) {
                plugin.host?.log("❌ 自检异常: ${e.javaClass.simpleName} - ${e.message}")
            } finally {
                plugin.mainHandler.post {
                    running = false
                    done = true
                    plugin.refreshCurrentPage()
                }
            }
        }.start()
    }

    private fun append(item: PluginSelfTest.Item) {
        items += item
        val ctx = plugin.appContext ?: return
        statusView?.text = "⏳ 正在自检… 已完成 ${items.size} 项"
        val list = listView ?: return
        list.addView(plugin.selfTestItemView(ctx, item))
        scrollView?.let { sv -> sv.post { sv.fullScroll(View.FOCUS_DOWN) } }
    }

    /**
     * 顶部状态和按钮，跑完后紧跟着权限区（缺哪个、能不能申请），
     * 最后是逐项结果（跑完后失败的排前面）。没跑过时只有说明和开始按钮。
     */
    fun build(): View {
        val ctx = plugin.appContext!!
        val list = Ui.vStack(ctx).apply { setPadding(0, 0, 0, 0) }
        val status = Ui.text(ctx, "", size = 16f, bold = true)
        val body = Ui.vStack(ctx).apply {
            setBackgroundColor(Color.parseColor(Ui.COLOR_BG))
            addView(Ui.hStack(ctx).apply {
                addView(Ui.btn(ctx, "← 返回") { close() })
                addView(Ui.space(ctx, 12, 0))
                addView(Ui.title(ctx, "🔧 车控自检"))
            })
            addView(Ui.space(ctx, 0, 8))
            addView(status)
            val passed = items.count { it.ok }
            val failed = items.size - passed
            status.text = when {
                running -> "⏳ 正在自检… 已完成 ${items.size} 项"
                done -> "🏁 自检完成：通过 $passed 项，失败 $failed 项"
                else -> "还没有自检结果"
            }
            if (!running) {
                if (!done) {
                    addView(Ui.hint(ctx, "会逐项实际调用车控：开关空调、改温度/风量/风向/循环、调座椅通风，" +
                        "再读车速、车架号等，约 20~30 秒。结果也会打进左边日志。"))
                }
                addView(Ui.space(ctx, 0, 8))
                addView(Ui.primaryBtn(ctx, if (done) "🔁 重新自检" else "▶️ 开始自检") { start() })
            }
            if (done) {
                if ((missingAtStart - plugin.permissions.missing(ctx)).isNotEmpty()) {
                    addView(Ui.text(ctx, "👉 自检之后有新授权，点「重新自检」看效果", size = 13f, color = Ui.COLOR_ACCENT))
                }
                addView(Ui.space(ctx, 0, 12))
                addView(plugin.buildPermissionSection(ctx) { plugin.refreshCurrentPage() })
            }
            if (running || done) {
                addView(Ui.space(ctx, 0, 12))
                addView(Ui.label(ctx, "逐项结果"))
                addView(Ui.divider(ctx))
                val shown = if (done) items.sortedBy { it.ok } else items
                shown.forEach { list.addView(plugin.selfTestItemView(ctx, it)) }
                addView(list)
            }
        }
        val scroll = ScrollView(ctx).apply {
            setBackgroundColor(Color.parseColor(Ui.COLOR_BG))
            addView(body)
        }
        statusView = status
        listView = list
        scrollView = scroll
        return scroll
    }
}

/** 自检结果的一行（自检页、启动自检页共用）。 */
internal fun MainPlugin.selfTestItemView(ctx: Context, item: PluginSelfTest.Item): View =
    Ui.text(ctx, "${if (item.ok) "✅" else "❌"} ${item.name}：${item.detail}",
        size = 13f, color = if (item.ok) Ui.COLOR_OK else Ui.COLOR_ERROR).apply {
        setPadding(0, dp(4), 0, dp(4))
    }
