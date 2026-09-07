package cn.icarus.knob.plugin

import android.content.Context
import android.net.Uri
import android.util.Log
import cn.icarus.knob.api.KnobPlugin
import dalvik.system.DexClassLoader
import java.io.File

/**
 * 插件加载器：用 DexClassLoader 加载私有目录里的 plugin.dex。
 *
 * parent 用壳的 classLoader → 插件代码跑在壳进程（壳 UID），继承车机厂商
 * 签名权限，能调 BYDAUTO。插件只放 App 私有目录 filesDir/knob/plugin.dex，
 * 不读写外部存储（规避分区存储限制，也无需存储权限）。
 *
 * ⚠️ 坑（W^X，Android 10+/targetSdk≥29 命中，车机 Android 12 同样）：ART 拒绝
 * 加载"本进程可写"的 dex，否则抛 "Writable dex file ... is not allowed"。
 * 因此 dex 落盘后必须先 setReadOnly() 再加载；覆盖更新前必须先 delete()
 * （只读文件无法直接覆盖写）。
 */
object PluginLoader {

    private const val TAG = "PluginLoader"

    const val PLUGIN_FILE = "plugin.dex"
    // 私有目录下的存放子目录
    private const val PLUGIN_DIR = "knob"

    @Volatile
    var current: KnobPlugin? = null
        private set

    /** 插件在私有目录中的目标文件（filesDir/knob/plugin.dex） */
    fun pluginFile(context: Context): File =
        File(File(context.filesDir, PLUGIN_DIR), PLUGIN_FILE)

    /** 每次进程启动自动调用：无插件文件或加载失败返回 null */
    fun load(context: Context): KnobPlugin? {
        val dexFile = pluginFile(context)
        if (!dexFile.exists() || !dexFile.isFile) {
            Log.w(TAG, "私有目录无插件文件: $dexFile")
            return null
        }
        return loadDex(context, dexFile)
    }

    /**
     * 把用户经文件选择器选中的 dex 拷进私有目录并立即加载。
     * @return true=拷贝并加载成功；成功后覆盖旧插件，下次启动自动加载新版本
     */
    fun importDex(context: Context, uri: Uri): Boolean {
        val target = pluginFile(context)
        return try {
            target.parentFile?.mkdirs()
            // ⚠️ 旧插件是只读的（W^X），不先删就无法覆盖写
            target.delete()
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                Log.e(TAG, "无法打开所选文件流: $uri")
                return false
            }
            Log.i(TAG, "✅ 已拷贝插件到私有目录: ${target.absolutePath}")
            val plugin = loadDex(context, target)
            if (plugin == null) {
                Log.e(TAG, "插件拷贝成功但加载失败，删除损坏文件")
                target.delete()
            }
            plugin != null
        } catch (e: Throwable) {
            Log.e(TAG, "❌ 导入插件失败: ${e.javaClass.simpleName} - ${e.message}")
            target.delete()
            false
        }
    }

    private fun loadDex(context: Context, dexFile: File): KnobPlugin? {
        return try {
            // ⚠️ W^X：加载前必须置为只读，否则 ART 直接拒绝（见类注释）
            if (!dexFile.setReadOnly()) {
                Log.w(TAG, "⚠️ 无法把插件文件置为只读，加载可能被系统拒绝: $dexFile")
            }
            // 优化目录用 App 私有可写目录；parent 用壳的 classLoader（继承壳类 + 系统类）
            val optimizedDir = File(context.cacheDir, "plugin_opt").apply { mkdirs() }
            val classLoader = DexClassLoader(
                dexFile.absolutePath,
                optimizedDir.absolutePath,
                null,
                context.classLoader
            )
            val pluginClass = classLoader.loadClass("cn.icarus.knob.plugin.MainPlugin")
            val plugin = pluginClass.getDeclaredConstructor().newInstance() as KnobPlugin
            current = plugin
            Log.i(TAG, "✅ 插件加载成功: $dexFile")
            plugin
        } catch (e: Throwable) {
            Log.e(TAG, "❌ 插件加载失败: ${e.javaClass.simpleName} - ${e.message}")
            null
        }
    }

    /** 卸载插件 */
    fun unload() {
        current?.onDestroy()
        current = null
    }
}
