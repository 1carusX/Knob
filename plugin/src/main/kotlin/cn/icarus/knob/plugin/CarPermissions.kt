package cn.icarus.knob.plugin

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.util.Log

/**
 * 车控的运行时权限：只在用户点申请按钮时申请。
 *
 * 壳 manifest 声明全部权限，插件只在用户点启动自检页 / 自检页上的「申请」按钮
 * 时申请（[requestByButton]），不一次性全申请，也不在用功能时弹窗：车控操作前
 * 只用 [has] 查，缺了就不执行、记一笔。状态只在主线程读写。
 */
internal class CarPermissions(private val plugin: MainPlugin) {

    companion object {
        // 插件借壳 Activity 申请权限用的请求码，跟壳自己的蓝牙请求码（2000）
        // 错开，壳靠请求码分辨要不要转给插件。
        const val REQUEST_CODE = 1000
        private const val TAG = "KnobPlugin"
    }

    // 正在等系统权限弹窗结果的那个按钮的回调（刷新页面 / 重新检查）。系统同一时间
    // 只处理一组申请，弹窗没关之前再申请会被直接取消，所以只留一份，弹窗期间又点
    // 了别的申请按钮就用最新的覆盖。
    private var pendingRetry: ((Boolean) -> Unit)? = null

    // 被拒且系统不再弹窗的权限（用户勾了不再询问），自检页据此不再给申请按钮。
    // 只有申请过一次才知道，所以进程重启后清空，再点申请会重新探出来。
    val permanentlyDenied = mutableSetOf<String>()

    /**
     * 车控调用前的检查：权限都在返回 true；缺了只记一笔、返回 false，调用方这次
     * 什么都不做——不弹窗申请。权限一律由用户在启动自检页 / 自检页上点「申请」
     * 按钮申请（requestByButton），调温、调座椅这类操作不会顺带弹窗。
     * 模拟模式不碰真车，不需要权限。
     */
    fun has(perms: List<String>, what: String): Boolean {
        if (SIMULATE_CAR_CONTROL) return true
        val ctx = plugin.appContext ?: return false
        val missing = perms.filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return true
        plugin.host?.log("⚠️ $what 没执行：缺 ${missing.joinToString("、") { it.removePrefix("android.permission.") }}，到自检页点「申请」按钮授权")
        return false
    }

    /**
     * 申请权限，只给启动自检页 / 自检页上的「申请」按钮用：权限都在返回 true；缺了
     * 就借壳 Activity 弹窗申请、返回 false，结果回来调 onResult(全部授权了没有)。
     * 壳 manifest 没声明的申请了也是直接被拒，这种情况只打日志。模拟模式不需要权限。
     *
     * 插件不能声明 Activity，但 ui.bind 交来的容器就挂在 KnobActivity 上，
     * 从它的 context 一层层剥 ContextWrapper 就能拿到；结果由系统回调给
     * KnobActivity，壳再用 onEvent("perm.result") 转回来（见 [handleResult]）。
     * ⚠️ 只能从用户点申请按钮调过来：车控操作不申请（见 has），页面刷新、
     * ui.bind 这类自动路径更不能申请——这台车机上 onResume 会被高频反复调用，自动
     * 申请会死循环（KnobActivity 里有同样的说明）。
     */
    fun requestByButton(perms: List<String>, what: String, onResult: (Boolean) -> Unit): Boolean {
        if (SIMULATE_CAR_CONTROL) return true
        val ctx = plugin.appContext ?: return false
        val missing = perms.filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return true
        val names = missing.joinToString("、") { it.removePrefix("android.permission.") }
        val undeclared = missing - declared(ctx)
        if (undeclared.isNotEmpty()) {
            plugin.host?.log("❌ $what 需要的 ${undeclared.joinToString("、") { it.removePrefix("android.permission.") }} 壳没声明，申请不了")
            return false
        }
        if (pendingRetry != null) {
            plugin.host?.log("⏳ 权限弹窗还没处理完，先处理完再申请 $what")
            pendingRetry = onResult
            return false
        }
        val activity = hostActivity() ?: run {
            plugin.host?.log("❌ 申请 $what 的 $names 权限要在 Knob 界面上点")
            return false
        }
        plugin.host?.log("👉 $what 需要 $names 权限，申请中")
        pendingRetry = onResult
        activity.requestPermissions(missing.toTypedArray(), REQUEST_CODE)
        return false
    }

    /** 现有功能要的全部权限里没授权的（COMMON 和签名级都算），模拟模式算全有。 */
    fun missing(ctx: Context): Set<String> {
        if (SIMULATE_CAR_CONTROL) return emptySet()
        return CarControl.PERM_GROUPS.flatMap { listOf(it.common) + it.signature }
            .filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            .toSet()
    }

    /** 壳 manifest 声明的全部权限（壳不重装就不会变，但查一次很便宜，不缓存）。 */
    fun declared(ctx: Context): Set<String> = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.toSet().orEmpty()
    } catch (e: Throwable) {
        Log.w(TAG, "读壳声明的权限失败: ${e.message}")
        emptySet()
    }

    private fun hostActivity(): Activity? {
        var c: Context? = plugin.pageContainer?.context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    /**
     * 申请结果（壳经 onEvent("perm.result") 转回来）。结果为空说明弹窗被系统取消，
     * 按没授权算。被拒且系统不再给"说明理由"的机会，说明用户勾了不再询问（或被
     * 策略禁止），再操作也不会弹窗，只能去系统设置里手动授权。
     */
    fun handleResult(perms: List<String>, results: List<Int>) {
        val activity = hostActivity()
        perms.forEachIndexed { i, perm ->
            val name = perm.removePrefix("android.permission.")
            when {
                results.getOrNull(i) == PackageManager.PERMISSION_GRANTED -> {
                    permanentlyDenied -= perm
                    plugin.host?.log("✅ $name 已授权")
                }
                activity != null && !activity.shouldShowRequestPermissionRationale(perm) -> {
                    permanentlyDenied += perm
                    plugin.host?.log("❌ $name 未授权：系统不再弹窗，请到系统设置里手动授权")
                }
                else -> plugin.host?.log("❌ $name 未授权")
            }
        }
        val allGranted = perms.isNotEmpty() && results.size == perms.size &&
            results.all { it == PackageManager.PERMISSION_GRANTED }
        val retry = pendingRetry
        pendingRetry = null
        retry?.invoke(allGranted)
    }
}
