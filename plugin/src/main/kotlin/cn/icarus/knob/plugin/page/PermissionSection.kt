package cn.icarus.knob.plugin.page

import android.content.Context
import android.content.pm.PackageManager
import android.view.View
import cn.icarus.knob.api.Ui
import cn.icarus.knob.plugin.CarControl
import cn.icarus.knob.plugin.MainPlugin
import cn.icarus.knob.plugin.SIMULATE_CAR_CONTROL

/**
 * 权限区（启动自检页、自检页共用）：现有功能一行一个（CarControl.PERM_GROUPS），
 * 列出 COMMON 和签名级 GET/SET 的授权状态。COMMON 没授权就给一个申请按钮——
 * 用户点了才申请，一次只申请这一行的那一个；签名级的申请不了，只说明原因。
 * 弹窗结果回来（授没授都算）调 onChanged：启动自检页借它重新检查，自检页只重绘。
 */
internal fun MainPlugin.buildPermissionSection(ctx: Context, onChanged: () -> Unit): View = Ui.vStack(ctx).apply {
    setPadding(0, 0, 0, 0)
    addView(Ui.label(ctx, "车控权限"))
    addView(Ui.divider(ctx))
    if (SIMULATE_CAR_CONTROL) {
        addView(Ui.hint(ctx, "模拟版插件不碰真车，不需要车控权限"))
        return@apply
    }
    val declared = permissions.declared(ctx)
    fun granted(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    fun short(p: String) = p.removePrefix("android.permission.BYDAUTO_")
    val missingNow = permissions.missing(ctx)
    if (missingNow.isEmpty()) {
        addView(Ui.hint(ctx, "✅ 现有功能要的权限都已授权"))
    }
    CarControl.PERM_GROUPS.forEach { g ->
        val perms = listOf(g.common) + g.signature
        val ok = perms.all { granted(it) }
        addView(Ui.card(ctx).apply {
            addView(Ui.text(ctx, "${if (ok) "✅" else "⚠️"} ${g.label}", bold = true))
            addView(Ui.hint(ctx, perms.joinToString("  ") { "${short(it)} ${if (granted(it)) "✅" else "❌"}" }))
            if (!g.required) addView(Ui.hint(ctx, "车控功能暂未用到，缺了不影响启动"))
            when {
                granted(g.common) -> {}
                g.common !in declared ->
                    addView(Ui.hint(ctx, "${short(g.common)} 壳没声明，申请不了，要换新版壳"))
                g.common in permissions.permanentlyDenied ->
                    addView(Ui.hint(ctx, "${short(g.common)} 已被拒绝且不再弹窗：请到系统设置 → 应用 → Knob → 权限里手动打开"))
                else -> addView(Ui.primaryBtn(ctx, "🔑 申请 ${short(g.common)}") {
                    if (permissions.requestByButton(listOf(g.common), g.label) { onChanged() }) onChanged()
                })
            }
            val sigMissing = g.signature.filterNot { granted(it) }
            if (sigMissing.isNotEmpty()) {
                val undeclared = sigMissing.filterNot { it in declared }
                addView(Ui.hint(ctx, if (undeclared.isNotEmpty())
                    "${undeclared.joinToString("、") { short(it) }} 壳没声明，要换新版壳"
                else
                    "${sigMissing.joinToString("、") { short(it) }} 是签名级，不能申请：壳要用车机厂商签名签过才有"))
            }
        })
        addView(Ui.space(ctx, 0, 8))
    }
}
