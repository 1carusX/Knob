package cn.icarus.knob.api

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * 插件页面通用 UI 构建工具（纯代码构建 View，避免资源 ID 冲突）。
 * 壳与插件共用一份（放 api 包，两工程内容保持一致）。深色样式，跟壳的界面
 * 同一套颜色（壳的 res/values/colors.xml，设计稿 docs/设计/壳界面/README.md），
 * 尺寸都按 dp 算。
 *
 * 插件运行时用到的是壳里这份（DexClassLoader 父加载器优先），所以改这里的
 * 方法要换壳才生效；const 颜色会在编译时内联进插件，两边值要一致。
 *
 * 用法：
 *   Ui.vStack(ctx).apply {
 *       addView(Ui.title(ctx, "空调温度"))
 *       addView(Ui.text(ctx, "当前 22°C"))
 *       addView(Ui.primaryBtn(ctx, "确认") { onCommand("ac.temperature", ...) })
 *   }
 */
object Ui {

    const val COLOR_BG = "#12161B"       // 页面底色（= 壳插件区的底色）
    const val COLOR_CARD = "#0F1216"     // 卡片
    const val COLOR_SURFACE = "#1A1F26"  // 次要按钮
    const val COLOR_BORDER = "#2B313A"
    const val COLOR_DIVIDER = "#1F242B"
    const val COLOR_TEXT = "#F2F4F7"
    const val COLOR_BODY = "#C9CED6"
    const val COLOR_HINT = "#8A93A0"
    const val COLOR_PRIMARY = "#2563EB"  // 主按钮
    const val COLOR_ACCENT = "#A9C4F5"   // 强调文字
    const val COLOR_OK = "#34D399"
    const val COLOR_WARN = "#F7C948"
    const val COLOR_ERROR = "#FF7A82"

    private const val RIPPLE = 0x26FFFFFF

    private fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).roundToInt()

    // ==================== 布局 ====================

    /** 垂直布局（内边距 24dp） */
    fun vStack(context: Context): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(context, 24)
            setPadding(p, p, p, p)
        }

    /** 水平布局（垂直居中） */
    fun hStack(context: Context): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

    /** 圆角卡片（垂直布局，比页面略深一点的底 + 细边） */
    fun card(context: Context, bg: String = COLOR_CARD): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12))
            background = roundedBg(context, bg, 12, COLOR_DIVIDER)
        }

    // ==================== 文本 ====================

    fun text(context: Context, s: String, size: Float = 15f, color: String = COLOR_TEXT, bold: Boolean = false): TextView =
        TextView(context).apply {
            text = s
            textSize = size
            setTextColor(Color.parseColor(color))
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setLineSpacing(0f, 1.2f)
        }

    /** 页面标题 */
    fun title(context: Context, s: String, size: Float = 22f): TextView = text(context, s, size, COLOR_TEXT, bold = true)

    /** 小节标题 / 说明 */
    fun label(context: Context, s: String, size: Float = 14f): TextView = text(context, s, size, COLOR_BODY)

    /** 提示（灰色小字） */
    fun hint(context: Context, s: String, size: Float = 13f): TextView = text(context, s, size, COLOR_HINT)

    // ==================== 按钮 ====================

    /** 次要按钮：深灰底、细边，至少 48dp 高 */
    fun btn(context: Context, s: String, onClick: (View) -> Unit): Button =
        button(context, s, COLOR_SURFACE, COLOR_BORDER, COLOR_TEXT, onClick)

    /** 主按钮（确认、提交、申请这类一页里最要紧的那个）：蓝底白字 */
    fun primaryBtn(context: Context, s: String, onClick: (View) -> Unit): Button =
        button(context, s, COLOR_PRIMARY, null, "#FFFFFF", onClick)

    private fun button(context: Context, s: String, bg: String, stroke: String?, fg: String, onClick: (View) -> Unit): Button =
        Button(context).apply {
            text = s
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.parseColor(fg))
            background = RippleDrawable(ColorStateList.valueOf(RIPPLE), roundedBg(context, bg, 10, stroke), null)
            stateListAnimator = null // 去掉默认按钮的阴影浮起
            minHeight = dp(context, 48)
            minimumHeight = dp(context, 48)
            setPadding(dp(context, 18), 0, dp(context, 18), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { val m = dp(context, 4); setMargins(m, m, m, m) }
            setOnClickListener(onClick)
        }

    // ==================== 分隔线 / 间距 ====================

    fun divider(context: Context): View =
        View(context).apply {
            setBackgroundColor(Color.parseColor(COLOR_DIVIDER))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 1))
        }

    /** 空占位（dp） */
    fun space(context: Context, width: Int = 0, height: Int = 8): View =
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, width), dp(context, height))
        }

    // ==================== 辅助 ====================

    /** 圆角背景（radius 是 dp），stroke 非空时加 1dp 边 */
    fun roundedBg(context: Context, color: String, radius: Int, stroke: String? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, radius).toFloat()
            setColor(Color.parseColor(color))
            if (stroke != null) setStroke(dp(context, 1), Color.parseColor(stroke))
        }
}
