package cn.icarus.knob.plugin

import android.content.Context
import cn.icarus.knob.api.KnobHost

/**
 * 旋钮固件自动升级：连上旋钮读到版本后，跟插件包里带的固件（FirmwareBundle）
 * 比，需要就直接开始 OTA（BleManager 负责发送）。升级期间 [percent] 非 null，
 * MainPlugin.refreshCurrentPage 据此固定显示升级页。以下状态只在主线程读写。
 */
internal class KnobOta(private val plugin: MainPlugin) {

    companion object {
        // 自动 OTA 的目标版本和已尝试次数（防死循环，见 maybeStart）
        private const val KEY_OTA_TARGET = "ota_target"
        private const val KEY_OTA_ATTEMPTS = "ota_attempts"
        // 上次见到的插件包文件修改时间，用来认出"刚导入了插件包"（见 resetAttemptsIfNewPackage）
        private const val KEY_OTA_PACKAGE_STAMP = "ota_package_stamp"
        private const val MAX_OTA_ATTEMPTS = 2
    }

    // 插件包里带的固件，init 时读一次；null = 导入的是纯 .dex 或者包有问题，
    // 不会给旋钮升级。
    var bundle: FirmwareBundle? = null
        private set

    // 当前连着的旋钮上报的固件版本号和板子名称（BleManager.onFirmwareInfo）；
    // 断开就清空，下次连上重新读。null = 没连上或者读不到（旧固件）。
    var knobVersion: String? = null
        private set
    var knobBoard: String? = null
        private set

    // 正在给旋钮升级时的进度（0~100），null = 没在升级。非 null 时页面固定
    // 显示升级页，BleManager 也会丢弃显示同步。
    var percent: Int? = null
        private set

    /** 插件 init 时调：读出插件包里的固件。 */
    fun load(context: Context, host: KnobHost) {
        bundle = FirmwareBundle.load(context, host)
        resetAttemptsIfNewPackage(context)
    }

    /** 旋钮断开：版本信息作废，下次连上重新读。 */
    fun onKnobDisconnected() {
        knobVersion = null
        knobBoard = null
    }

    /**
     * BleManager.onFirmwareInfo 回调：记下旋钮的固件版本和板子名称，跟插件包
     * 里带的固件不一致就开始升级（见 maybeStart）。紧接着会有一次
     * onConnectionStateChanged(connected=true)，那里会重绘页面，这里不用自己刷新。
     */
    fun onFirmwareInfo(version: String?, board: String?, otaSupported: Boolean) {
        plugin.host?.log("🏷️ 旋钮固件: ${version ?: "未知（固件太旧）"}，板子: ${board ?: "未知"}")
        knobVersion = version
        knobBoard = board
        maybeStart(version, board, otaSupported)
    }

    /**
     * 手动导入插件包 = 用户想再试一次，把 [maybeStart] 的尝试次数清零。
     * init 分不出是刚导入还是开机自动加载，靠插件包文件的修改时间区分：壳每次
     * 导入都删掉旧文件重新拷，时间必然变；开机重新加载的还是同一个文件，不变。
     */
    private fun resetAttemptsIfNewPackage(context: Context) {
        val stamp = FirmwareBundle.pluginFile(context).lastModified()
        val prefs = context.getSharedPreferences(MainPlugin.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getLong(KEY_OTA_PACKAGE_STAMP, 0L) == stamp) return
        prefs.edit().putLong(KEY_OTA_PACKAGE_STAMP, stamp).remove(KEY_OTA_TARGET).remove(KEY_OTA_ATTEMPTS).apply()
    }

    /**
     * 连上旋钮、读到版本后决定要不要升级：插件包带了固件、板子对得上、版本
     * 不一样、旋钮支持 OTA，四条都满足才开始，不弹确认。
     *
     * 防死循环：要是新固件上报的版本跟包里写的不一样（比如版本号生成出了
     * 问题），每次连上都会再升一遍。所以按"目标版本"记尝试次数，同一个
     * 目标最多试 [MAX_OTA_ATTEMPTS] 次，旋钮版本终于对上了、或者重新导入了
     * 插件包（见 [resetAttemptsIfNewPackage]）才清零。
     */
    private fun maybeStart(version: String?, board: String?, otaSupported: Boolean) {
        val bundle = bundle ?: return
        val ctx = plugin.appContext ?: return
        val prefs = ctx.getSharedPreferences(MainPlugin.PREFS_NAME, Context.MODE_PRIVATE)
        when {
            version == bundle.version -> {
                plugin.host?.log("✅ 旋钮固件已是插件包里的版本，不用升级")
                prefs.edit().remove(KEY_OTA_TARGET).remove(KEY_OTA_ATTEMPTS).apply()
                return
            }
            board != bundle.board -> {
                plugin.host?.log("⚠️ 插件包里的固件是给 ${bundle.board} 编的，旋钮是 ${board ?: "未知板子"}，不升级")
                return
            }
            !otaSupported -> {
                plugin.host?.log("⚠️ 旋钮固件不支持 OTA，需要先用 USB 刷一次新固件")
                return
            }
        }
        val attempts = if (prefs.getString(KEY_OTA_TARGET, null) == bundle.version) prefs.getInt(KEY_OTA_ATTEMPTS, 0) else 0
        if (attempts >= MAX_OTA_ATTEMPTS) {
            plugin.host?.log("⚠️ 升级到 ${bundle.version} 已经试过 $attempts 次，旋钮版本仍是 $version，不再自动升级（检查固件版本号是否生成正确，或重新导入插件包）")
            return
        }
        prefs.edit().putString(KEY_OTA_TARGET, bundle.version).putInt(KEY_OTA_ATTEMPTS, attempts + 1).apply()
        plugin.host?.log("⬆️ 旋钮固件 $version → ${bundle.version}，开始升级（第 ${attempts + 1} 次）")
        if (plugin.bleManager?.startOta(bundle.bytes, bundle.sha256) == true) {
            percent = 0
            plugin.refreshCurrentPage()
        }
    }

    /** BleManager.onOtaProgress 回调：只在百分比变了时才会来，直接重绘。 */
    fun onProgress(sent: Int, total: Int) {
        percent = (sent.toLong() * 100 / total).toInt()
        plugin.refreshCurrentPage()
    }

    /**
     * BleManager.onOtaResult 回调。成功后旋钮马上重启、断开，BleManager 会自动
     * 重连，重连读到新版本时 maybeStart 确认版本一致并清掉尝试次数。
     */
    fun onResult(success: Boolean, message: String) {
        plugin.host?.log(if (success) "✅ 旋钮固件升级完成：$message" else "❌ 旋钮固件升级失败：$message")
        percent = null
        plugin.refreshCurrentPage()
    }
}
