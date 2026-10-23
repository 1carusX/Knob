package cn.icarus.knob.plugin

import android.content.Context
import cn.icarus.knob.api.KnobHost
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * 插件包里带的旋钮固件（见 plugin/build.gradle.kts 的 pluginPackage<板子> 任务）。
 *
 * 插件包是个 zip：壳导入时原样存成 filesDir/knob/plugin.dex，ART 按文件头
 * 认出 zip 照样加载里面的 classes.dex；但文件名以 .dex 结尾，类加载器不会
 * 把它当 zip 查资源（getResourceAsStream 拿不到），所以这里直接按路径用
 * ZipFile 打开它读 firmware/ 下的两个文件。导入的是旧式纯 .dex 时打不开
 * zip，就当这个插件没带固件。
 */
class FirmwareBundle private constructor(
    val version: String,
    val board: String,
    /** 固件本体，已按 manifest 校验过大小和 SHA-256 */
    val bytes: ByteArray,
    val sha256: ByteArray,
) {
    companion object {
        // ⚠️ 必须跟壳 PluginLoader 的 PLUGIN_DIR / PLUGIN_FILE 一致（壳私有常量，
        // 插件够不到，只能两边各写一份）
        private const val PLUGIN_DIR = "knob"
        private const val PLUGIN_FILE = "plugin.dex"
        private const val MANIFEST_ENTRY = "firmware/manifest.json"
        private const val FIRMWARE_ENTRY = "firmware/firmware.bin"

        /** 壳导入后存下来的插件包文件（每次导入都会删掉重拷，修改时间随之变化）。 */
        fun pluginFile(context: Context): File = File(File(context.filesDir, PLUGIN_DIR), PLUGIN_FILE)

        /** 读出并校验插件包里的固件；没带、格式不对或校验失败都返回 null，原因写进日志。 */
        fun load(context: Context, host: KnobHost): FirmwareBundle? {
            val file = pluginFile(context)
            val zip = try {
                ZipFile(file)
            } catch (e: Exception) {
                host.log("📦 插件包里没有固件（导入的是纯 .dex），不会给旋钮升级")
                return null
            }
            return zip.use {
                try {
                    val manifestEntry = it.getEntry(MANIFEST_ENTRY)
                    val firmwareEntry = it.getEntry(FIRMWARE_ENTRY)
                    if (manifestEntry == null || firmwareEntry == null) {
                        host.log("📦 插件包里缺 $MANIFEST_ENTRY 或 $FIRMWARE_ENTRY，不会给旋钮升级")
                        return null
                    }
                    val manifest = JSONObject(it.getInputStream(manifestEntry).readBytes().toString(Charsets.UTF_8))
                    val bytes = it.getInputStream(firmwareEntry).readBytes()
                    val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
                    val expectedSha = manifest.getString("sha256").lowercase()
                    if (bytes.size != manifest.getInt("size") || sha256.toHex() != expectedSha) {
                        host.log("📦 ❌ 插件包里的固件跟 manifest 对不上（大小/SHA-256），包可能损坏，不会给旋钮升级")
                        return null
                    }
                    FirmwareBundle(manifest.getString("version"), manifest.getString("board"), bytes, sha256).also { b ->
                        host.log("📦 插件包带固件 ${b.version}（${b.board}），${b.bytes.size} 字节")
                    }
                } catch (e: Exception) {
                    host.log("📦 ❌ 读取插件包里的固件失败：${e.javaClass.simpleName} ${e.message}")
                    null
                }
            }
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
