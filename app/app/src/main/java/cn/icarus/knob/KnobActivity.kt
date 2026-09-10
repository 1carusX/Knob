package cn.icarus.knob

import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import cn.icarus.knob.databinding.ActivityKnobBinding
import cn.icarus.knob.host.KnobHostImpl
import cn.icarus.knob.plugin.PluginLoader
import cn.icarus.knob.util.CrashHandler
import cn.icarus.knob.util.LogSink
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 壳主界面：插件页面容器 + 日志/按钮调试面板（信息全进日志，不单独占 UI）。
 * 车控逻辑已全部移入插件。
 */
class KnobActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKnobBinding

    private val REQUEST_AC_COMMON_PERM = 2001
    private val REQUEST_BLUETOOTH_PERM = 2002
    private val REQUEST_SETTING_COMMON_PERM = 2003

    // 选择 plugin.dex（SAF，无需存储权限）
    private val pickPluginLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) {
                LogSink.append("⚠️ 未选择插件文件")
                return@registerForActivityResult
            }
            importPlugin(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKnobBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 旋钮操作无触摸事件，不加这个会正常走到系统自动息屏/锁屏
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 崩溃时导出 crash_*.txt 到 Downloads（无 adb 环境的兜底观测）
        CrashHandler.install()

        LogSink.bind(binding.tvLog)
        LogSink.section("Knob 启动")

        logSystemInfo()
        logPermissionStatus()
        logPluginStatus()

        binding.btnPickPlugin.setOnClickListener {
            pickPluginLauncher.launch(arrayOf("*/*"))
        }
        binding.btnClearLog.setOnClickListener { LogSink.clear() }
        binding.btnSaveLog.setOnClickListener { saveLog() }

        // ⚠️ 坑：权限曾在 onResume() 自动发起，这台设备上 onResume 会被系统高频
        // 反复触发，无条件重复请求权限直接死循环——改成三个按钮手动触发，时机
        // 由用户决定，不会有意外弹窗。
        binding.btnReqAcCommon.setOnClickListener { requestSinglePermission("android.permission.BYDAUTO_AC_COMMON", REQUEST_AC_COMMON_PERM) }
        binding.btnReqSettingCommon.setOnClickListener { requestSinglePermission("android.permission.BYDAUTO_SETTING_COMMON", REQUEST_SETTING_COMMON_PERM) }
        binding.btnReqBluetooth.setOnClickListener { requestBluetoothPermission() }
        refreshPermButtonStates()

        // 插件已在 Application.onCreate 同步加载完，这里把页面容器交给它
        bindPluginUi()
    }

    /** 若插件已加载，把右半边容器交给插件渲染 */
    private fun bindPluginUi() {
        PluginLoader.current?.onEvent("ui.bind", mapOf("container" to binding.pluginContainer))
    }

    /**
     * Activity 销毁时通知插件解绑容器。插件实例是进程级单例，活得比 Activity 久，
     * 不解绑会让插件一直持有已销毁的 View 树。注意这里不能 unload 插件。
     */
    override fun onDestroy() {
        PluginLoader.current?.onEvent("ui.unbind", emptyMap())
        super.onDestroy()
    }

    /**
     * 系统投递的按键（车机物理键、系统返回键）转给插件，同样走 onEvent("key")。
     * 旋钮本身的转/点/长按不经过这里，是插件自己的 BleManager 收到 BLE 通知后
     * 直接处理的（BleManager 现在归插件所有，见 plugin/BleManager.kt）。
     *
     * ⚠️ 为什么用 dispatchKeyEvent 而非 onKeyDown：它在 View 树分发之前拿到事件，
     * 不会被获得焦点的按钮抢走 DPAD/ENTER。
     * 插件不消费则交回系统默认处理（返回键默认行为 = 关闭页面）。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val consumed = PluginLoader.current?.onEvent("key", mapOf(
            "code" to event.keyCode,
            "action" to event.action
        )) ?: false
        return consumed || super.dispatchKeyEvent(event)
    }

    /** 把选中的 dex 拷进私有目录并加载 */
    private fun importPlugin(uri: Uri) {
        LogSink.section("导入插件")
        try {
            val ok = PluginLoader.importDex(this, uri)
            if (ok) {
                PluginLoader.current?.init(applicationContext, KnobHostImpl)
                LogSink.append("✅ 插件导入并加载成功")
                Toast.makeText(this, "插件已导入并加载", Toast.LENGTH_SHORT).show()
                bindPluginUi()
                // 示例：壳主动推设备状态给插件（state / bluetooth / hardware / config）
                pushShellData()
            } else {
                LogSink.append("❌ 插件导入/加载失败，请重新选择有效的 plugin.dex")
                Toast.makeText(this, "插件加载失败，请重新选择", Toast.LENGTH_LONG).show()
            }
        } catch (e: Throwable) {
            LogSink.append("❌ 导入异常: ${e.javaClass.simpleName} - ${e.message}")
        }
    }

    /** 插件刚导入完，把设备信息推给它。蓝牙连接状态不在这里推——插件自己的 BleManager 全权管理。 */
    private fun pushShellData() {
        val p = PluginLoader.current ?: return
        p.onPush("state", mapOf(
            "model" to Build.MODEL,
            "manufacturer" to Build.MANUFACTURER,
            "android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        ))
    }

    // ==================== 信息直接打进日志 ====================

    private fun logSystemInfo() {
        LogSink.section("系统信息")
        LogSink.append("厂商: ${Build.MANUFACTURER}")
        LogSink.append("型号: ${Build.MODEL}")
        LogSink.append("品牌: ${Build.BRAND}")
        LogSink.append("设备: ${Build.DEVICE}")
        LogSink.append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        LogSink.append("指纹: ${Build.FINGERPRINT}")
    }

    private fun logPermissionStatus() {
        val perms = listOf(
            "android.permission.BYDAUTO_AC_COMMON",
            "android.permission.BYDAUTO_AC_GET",
            "android.permission.BYDAUTO_AC_SET",
            "android.permission.BYDAUTO_SETTING_COMMON",
            "android.permission.BYDAUTO_SETTING_GET",
            "android.permission.BYDAUTO_SETTING_SET"
        )
        val pm = packageManager
        LogSink.section("BYDAUTO 权限状态")
        perms.forEach { perm ->
            val granted = try {
                pm.checkPermission(perm, packageName) == PackageManager.PERMISSION_GRANTED
            } catch (e: Throwable) { false }
            val short = perm.removePrefix("android.permission.")
            LogSink.append(if (granted) "✅ $short 已授权" else "❌ $short 未授权")
        }
    }

    private fun logPluginStatus() {
        val p = PluginLoader.current
        LogSink.section("插件状态")
        if (p != null) {
            LogSink.append("✅ 插件已加载")
        } else {
            LogSink.append("⚠️ 插件未加载，请点「选择插件」导入 plugin.dex")
        }
    }

    // ==================== 保存日志 ====================

    private fun saveLog() {
        val content = binding.tvLog.text.toString().trim()
        if (content.isEmpty()) {
            Toast.makeText(this, "日志为空，无内容可保存", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(Date())
            val fileName = "log_$timestamp.txt"

            // API 29+ 走 MediaStore（无需存储权限）；更早用公共 Downloads 目录
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri == null) {
                    Toast.makeText(this, "保存失败：无法创建文件", Toast.LENGTH_SHORT).show()
                    return
                }
                contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                    ?: run {
                        Toast.makeText(this, "保存失败：无法写入", Toast.LENGTH_SHORT).show()
                        return
                    }
                Toast.makeText(this, "已保存到 Downloads/$fileName", Toast.LENGTH_LONG).show()
            } else {
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloads.exists()) downloads.mkdirs()
                val file = File(downloads, fileName)
                file.writeText(content)
                Toast.makeText(this, "已保存到 ${file.absolutePath}", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ==================== BYDAUTO 运行时权限申请 ====================

    /** 申请单个 dangerous 级运行时权限，按钮点击时调用 */
    private fun requestSinglePermission(perm: String, requestCode: Int) {
        val shortName = perm.removePrefix("android.permission.")
        LogSink.section("申请权限：$shortName")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            LogSink.append("⚠️ 当前系统无需运行时申请（API < 23）")
            return
        }
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) {
            LogSink.append("✅ 已经是授权状态")
            refreshPermButtonStates()
            return
        }
        requestPermissions(arrayOf(perm), requestCode)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQUEST_AC_COMMON_PERM -> {
                LogSink.append(if (granted) "✅ BYDAUTO_AC_COMMON 已授权" else "❌ BYDAUTO_AC_COMMON 未授权")
            }
            REQUEST_SETTING_COMMON_PERM -> {
                LogSink.append(if (granted) "✅ BYDAUTO_SETTING_COMMON 已授权" else "❌ BYDAUTO_SETTING_COMMON 未授权")
            }
            REQUEST_BLUETOOTH_PERM -> {
                // API 31+ 这里一次申请 CONNECT+SCAN 两个权限，逐个检查而不是只看 grantResults[0]
                val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                LogSink.append(if (allGranted) "✅ 蓝牙连接/扫描权限已授权" else "❌ 蓝牙权限未完全授权，自动连接/配对可能不可用")
                // 不在这里主动触发连接——插件自己的 BleManager 有 5s 重连轮询，
                // 权限刚授权的话最多等一个轮询周期就会自己接上，不需要壳插手。
            }
        }
        refreshPermButtonStates()
    }

    // ==================== 权限按钮的红/绿状态 ====================

    /**
     * 按实际权限状态刷新三个权限按钮：未授权=红可点，已授权=绿不可点
     * （授权后无"撤销"操作，不可点即表达"已完成"）。风格与「选择插件」一致。
     */
    private fun refreshPermButtonStates() {
        val acGranted = checkSelfPermission("android.permission.BYDAUTO_AC_COMMON") == PackageManager.PERMISSION_GRANTED
        updatePermButtonState(binding.btnReqAcCommon, binding.tvReqAcCommonLabel, acGranted)

        val settingGranted = checkSelfPermission("android.permission.BYDAUTO_SETTING_COMMON") == PackageManager.PERMISSION_GRANTED
        updatePermButtonState(binding.btnReqSettingCommon, binding.tvReqSettingCommonLabel, settingGranted)

        val bleGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
        updatePermButtonState(binding.btnReqBluetooth, binding.tvReqBluetoothLabel, bleGranted)
    }

    private fun updatePermButtonState(container: LinearLayout, label: TextView, granted: Boolean) {
        container.setBackgroundResource(if (granted) R.drawable.bg_btn_green else R.drawable.bg_btn_red)
        label.setTextColor(Color.parseColor(if (granted) "#2E7D32" else "#C62828"))
        container.isClickable = !granted
        container.isFocusable = !granted
        container.foreground = if (granted) null else selectableItemForeground()
    }

    /** ⚠️ 每次取一份新的 ?attr/selectableItemBackground drawable——不同 View 不能共用同一实例（内部状态会互相打架） */
    private fun selectableItemForeground(): Drawable? {
        val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        val drawable = ta.getDrawable(0)
        ta.recycle()
        return drawable
    }

    // ==================== 蓝牙权限申请 ====================

    /**
     * BLUETOOTH_CONNECT/BLUETOOTH_SCAN 是 API 31+ 的运行时权限，一起申请
     * （插件自己的 BleManager 找不到已知/已配对设备时会自己扫描+配对，两个
     * 都要，但只有 Activity 能发起权限申请，插件够不到，得由壳代劳）；
     * 更早系统连接靠 manifest 里 maxSdkVersion=30 的旧 BLUETOOTH/BLUETOOTH_ADMIN
     * （安装时自动授予），扫描则要 ACCESS_FINE_LOCATION。由「申请蓝牙权限」按钮触发。
     * 申请到手之后不用管后续连接——插件的重连轮询会自己接上。
     */
    private fun requestBluetoothPermission() {
        LogSink.section("蓝牙权限申请")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                LogSink.append("当前系统无需申请 BLUETOOTH_CONNECT（API < 31），定位权限已就绪")
            } else {
                LogSink.append("请求 ACCESS_FINE_LOCATION 权限（本系统 BLE 扫描的前提）")
                requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_BLUETOOTH_PERM)
            }
            return
        }
        val needed = listOf(
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_SCAN
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) {
            LogSink.append("✅ 蓝牙连接/扫描权限已授权")
            refreshPermButtonStates()
        } else {
            LogSink.append("请求权限：${needed.joinToString()}")
            requestPermissions(needed.toTypedArray(), REQUEST_BLUETOOTH_PERM)
        }
    }
}
