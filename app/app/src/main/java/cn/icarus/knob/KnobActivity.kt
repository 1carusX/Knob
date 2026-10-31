package cn.icarus.knob

import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
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
import kotlin.math.roundToInt

/**
 * 壳主界面：顶栏（插件 / 蓝牙权限状态、导入插件、日志开关）+ 插件区 + 日志面板。
 * 车控逻辑已全部移入插件。设计稿见 docs/设计/壳界面/README.md。
 *
 * 两份布局按 Knob 窗口宽度选（layout-w960dp 宽、layout 窄，车机分屏时窗口
 * 只有半屏宽，见布局文件开头）：宽布局日志是右侧可收起的面板，窄布局日志是
 * 底部一条 + 拉开的抽屉。两份控件 id 一致，只在一边有的（btnLog、logStrip、
 * logScrim）在 ViewBinding 里可空，下面用 isWide 区分。进出分屏窗口大小一变，
 * Activity 会重建：插件靠 ui.unbind/ui.bind 跟着重绑，日志面板开合状态存起来恢复。
 *
 * 壳只申请蓝牙权限（顶栏蓝牙胶囊没授权时可点）。车控等平台权限由插件自己在页面里
 * 画按钮、借本 Activity 发起申请；系统把结果回调到本 Activity，壳原样转给插件
 * （onEvent("perm.result")）。这样插件要申请新权限只需 manifest 已声明，不用改壳。
 * 本文件不含任何平台专属权限字符串。
 */
class KnobActivity : AppCompatActivity(), LogSink.Listener {

    private lateinit var binding: ActivityKnobBinding

    companion object {
        // 壳自己只申请蓝牙权限，用这一个码；其余请求码都是插件发起的，结果转给插件
        private const val REQUEST_BLUETOOTH_PERM = 2000

        private const val PREFS = "shell_ui"
        private const val KEY_LOG_OPEN = "log_open"           // 宽布局日志面板开着没有（跨启动记住）
        private const val STATE_DRAWER_OPEN = "log_drawer_open" // 窄布局抽屉（只在重建时恢复）
        private const val STATE_ISSUES_ONLY = "log_issues_only"
    }

    // 宽布局才有顶栏的日志开关按钮
    private val isWide: Boolean get() = binding.btnLog != null

    // 宽布局：右侧面板开着；窄布局：抽屉拉开了
    private var logOpen = false
    // 日志只看警告和错误
    private var issuesOnly = false
    // 新日志来了自动滚到最新；往上翻看旧日志时暂停（见 setupLogPanel）
    private var autoScroll = true

    // 日志面板里现在画着哪些条目（按 seq）、各占多少字符，新日志来了只追加新的、
    // 从开头删掉被挤出去的，不用每次把几百行整段重画。面板收起或换筛选时清空，
    // 下次整段重画。
    private val renderedSeqs = ArrayDeque<Long>()
    private val renderedLengths = ArrayDeque<Int>()
    private var needFullRender = true

    // 选择插件包 / plugin.dex（SAF，无需存储权限）
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

        if (savedInstanceState == null) {
            LogSink.section("Knob 启动")
            logSystemInfo()
            logPermissionStatus()
            logPluginStatus()
        }
        // 进出分屏会重建 Activity，每次都记一笔窗口多大，好知道系统实际给了多少地方
        logWindowInfo()

        setupTopBar()
        setupEmptyState()
        setupLogPanel(savedInstanceState)
        refreshStatusChips()
        LogSink.setListener(this)

        // 插件已在 Application.onCreate 同步加载完，这里把页面容器交给它
        binding.pluginContainer.clipToOutline = true // 插件页面跟着面板的圆角裁
        bindPluginUi()
    }

    override fun onResume() {
        super.onResume()
        // 用户可能去系统设置里改了权限。⚠️ 这里只刷新显示，绝不发起申请：
        // 这台车机上 onResume 会被高频反复触发，自动申请会死循环。
        refreshStatusChips()
    }

    // 面板开着、而且界面在前台，新来的错误才算"看到了"。onStart/onStop 而不是
    // onResume/onPause：这台车机上 onResume 会被高频反复触发。
    override fun onStart() {
        super.onStart()
        LogSink.setPanelVisible(this, logOpen)
        if (logOpen) LogSink.markSeen()
    }

    override fun onStop() {
        LogSink.setPanelVisible(this, false)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!isWide) outState.putBoolean(STATE_DRAWER_OPEN, logOpen)
        outState.putBoolean(STATE_ISSUES_ONLY, issuesOnly)
    }

    // ==================== 顶栏 ====================

    private fun setupTopBar() {
        binding.btnImport.setOnClickListener { pickPlugin() }
        binding.btnLog?.setOnClickListener { setLogOpen(!logOpen) }
        binding.logStrip?.setOnClickListener { setLogOpen(true) }
        binding.logScrim?.setOnClickListener { setLogOpen(false) }
        // ⚠️ 坑：权限曾在 onResume() 自动发起，这台设备上 onResume 会被系统高频
        // 反复触发，无条件重复请求权限直接死循环——只在用户点蓝牙胶囊时申请。
        binding.btChip.setOnClickListener { requestBluetoothPermission() }
    }

    private fun pickPlugin() = pickPluginLauncher.launch(arrayOf("*/*"))

    /** 插件加载状态和蓝牙权限状态画到顶栏两个胶囊上 */
    private fun refreshStatusChips() {
        val loaded = PluginLoader.current != null
        binding.pluginDot.setBackgroundResource(if (loaded) R.drawable.dot_ok else R.drawable.dot_idle)
        binding.pluginChipText.text = when {
            !isWide -> "插件"
            !loaded -> "未导入插件"
            else -> {
                val file = PluginLoader.pluginFile(this)
                if (file.exists()) "插件已加载 · 导入于 " + SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(file.lastModified()))
                else "插件已加载"
            }
        }
        binding.pluginChip.contentDescription = if (loaded) "插件已加载" else "未导入插件"

        val bt = bluetoothGranted()
        binding.btChip.setBackgroundResource(if (bt) R.drawable.bg_chip else R.drawable.bg_chip_alert)
        binding.btDot.setBackgroundResource(if (bt) R.drawable.dot_ok else R.drawable.dot_error)
        binding.btChipText.text = when {
            !isWide -> "蓝牙"
            bt -> "蓝牙权限"
            else -> "蓝牙权限未授予"
        }
        binding.btChipText.setTextColor(getColor(if (bt) R.color.text else R.color.alert_text))
        binding.btGrant.visibility = if (bt) View.GONE else View.VISIBLE
        binding.btChip.isClickable = !bt
        binding.btChip.isFocusable = !bt
        binding.btChip.contentDescription = if (bt) "蓝牙权限已授予" else "蓝牙权限未授予，点此授权"
    }

    /** 蓝牙权限够不够：API 31+ 要 CONNECT+SCAN 都到位，更早系统靠 ACCESS_FINE_LOCATION */
    private fun bluetoothGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

    // ==================== 插件区的空状态 ====================

    private fun setupEmptyState() {
        val empty = binding.emptyState
        empty.btnImportBig.setOnClickListener { pickPlugin() }
        // 三步说明最宽 440dp，窄屏（手机）跟着插件区缩
        binding.pluginContainer.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val w = minOf(dp(440), v.width - dp(64))
            val lp = empty.emptySteps.layoutParams
            if (w > 0 && lp.width != w) {
                lp.width = w
                empty.emptySteps.post { empty.emptySteps.layoutParams = lp }
            }
        }
    }

    // ==================== 日志面板 ====================

    private fun setupLogPanel(savedInstanceState: Bundle?) {
        val p = binding.logPanel
        p.root.setBackgroundResource(if (isWide) R.drawable.bg_log_panel else R.drawable.bg_sheet)
        p.root.clipToOutline = true
        p.logHandle.visibility = if (isWide) View.GONE else View.VISIBLE
        p.btnCloseLog.visibility = if (isWide) View.GONE else View.VISIBLE

        p.btnCloseLog.setOnClickListener { setLogOpen(false) }
        p.btnSaveLog.setOnClickListener { saveLog() }
        p.btnClearLog.setOnClickListener { LogSink.clear() }
        p.btnAutoScroll.setOnClickListener {
            if (autoScroll) setAutoScroll(false) else scrollToLatest()
        }
        p.btnBackToLatest.setOnClickListener { scrollToLatest() }
        p.filterAll.setOnClickListener { setIssuesOnly(false) }
        p.filterIssues.setOnClickListener { setIssuesOnly(true) }

        // 用户往上翻（离底部超过一点）就暂停自动滚动，翻回底部再恢复。程序自己
        // 滚到底也会触发这里，那时本来就在底部，结果一致。
        p.logScroll.setOnScrollChangeListener { v, _, scrollY, _, _ ->
            val child = (v as android.widget.ScrollView).getChildAt(0) ?: return@setOnScrollChangeListener
            val atBottom = scrollY + v.height - v.paddingTop - v.paddingBottom >= child.height - dp(24)
            if (atBottom != autoScroll) setAutoScroll(atBottom)
        }

        issuesOnly = savedInstanceState?.getBoolean(STATE_ISSUES_ONLY) ?: false
        logOpen = if (isWide) {
            getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_LOG_OPEN, true)
        } else {
            savedInstanceState?.getBoolean(STATE_DRAWER_OPEN) ?: false
        }
        setAutoScroll(true)
        updateFilterButtons()
        applyLogOpen()
    }

    private fun setLogOpen(open: Boolean) {
        logOpen = open
        if (isWide) getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_LOG_OPEN, open).apply()
        applyLogOpen()
    }

    private fun applyLogOpen() {
        val panel = binding.logPanel.root
        panel.visibility = if (logOpen) View.VISIBLE else View.GONE
        binding.logScrim?.visibility = if (logOpen) View.VISIBLE else View.GONE
        if (!isWide && logOpen) {
            // 抽屉占窗口高度六成左右，上面还能看到一点插件区
            binding.root.post {
                val h = (binding.root.height * 0.6f).roundToInt().coerceAtLeast(dp(280))
                panel.layoutParams = panel.layoutParams.apply { height = h }
            }
        }
        binding.btnLog?.let { btn ->
            btn.setBackgroundResource(if (logOpen) R.drawable.bg_btn_toggle_on else R.drawable.bg_btn_secondary)
            val c = getColor(if (logOpen) R.color.toggle_on_text else R.color.text)
            binding.btnLogIcon?.setColorFilter(c)
            binding.btnLogText?.setTextColor(c)
            btn.contentDescription = if (logOpen) "收起日志" else "展开日志"
        }
        val started = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        LogSink.setPanelVisible(this, logOpen && started)
        if (logOpen && started) LogSink.markSeen()
        if (!logOpen) needFullRender = true // 收起期间不维护面板内容，下次打开整段重画
        renderLog()
        if (logOpen && autoScroll) scrollToLatest()
    }

    private fun setIssuesOnly(only: Boolean) {
        issuesOnly = only
        needFullRender = true
        updateFilterButtons()
        renderLog()
        scrollToLatest()
    }

    private fun updateFilterButtons() {
        val p = binding.logPanel
        p.filterAll.setBackgroundResource(if (issuesOnly) R.drawable.bg_segment_off else R.drawable.bg_segment_on)
        p.filterIssues.setBackgroundResource(if (issuesOnly) R.drawable.bg_segment_on else R.drawable.bg_segment_off)
        p.filterAll.setTextColor(getColor(if (issuesOnly) R.color.text_secondary else R.color.text))
        p.filterIssues.setTextColor(getColor(if (issuesOnly) R.color.text else R.color.text_secondary))
    }

    private fun setAutoScroll(on: Boolean) {
        autoScroll = on
        val p = binding.logPanel
        p.btnAutoScroll.setBackgroundResource(if (on) R.drawable.bg_icon_btn_on else R.drawable.bg_icon_btn)
        p.btnAutoScroll.setColorFilter(getColor(if (on) R.color.toggle_on_text else R.color.text_body))
        p.btnAutoScroll.contentDescription = if (on) "自动滚到最新：开" else "自动滚到最新：关"
        p.btnBackToLatest.visibility = if (on) View.GONE else View.VISIBLE
    }

    private fun scrollToLatest() {
        setAutoScroll(true)
        val sv = binding.logPanel.logScroll
        sv.post {
            val child = sv.getChildAt(0) ?: return@post
            sv.scrollTo(0, (child.height - (sv.height - sv.paddingTop - sv.paddingBottom)).coerceAtLeast(0))
        }
    }

    override fun onLogChanged() = renderLog()

    /** 按当前日志刷新：面板内容（开着才画）、条数、顶栏 / 底部条的最新一条、未读错误角标 */
    private fun renderLog() {
        val all = LogSink.snapshot()
        val issues = all.count { it.level == LogSink.Level.WARN || it.level == LogSink.Level.ERROR }
        val p = binding.logPanel
        p.logCount.text = "${all.count { it.level != LogSink.Level.SECTION }} 条"
        p.filterIssues.text = if (issues > 0) "警告和错误 · $issues" else "警告和错误"
        if (logOpen) {
            val shown = if (issuesOnly) all.filter { it.level == LogSink.Level.WARN || it.level == LogSink.Level.ERROR } else all
            if (updateLogText(p.tvLog, shown) && autoScroll) scrollToLatest()
        }

        // 最新一条：宽布局只在面板收起时显示在顶栏，窄布局底部条一直显示
        val latest = all.lastOrNull { it.level != LogSink.Level.SECTION }
        val ticker = binding.logTicker
        if (latest == null) {
            ticker.text = ""
            if (isWide) ticker.visibility = View.GONE
        } else {
            ticker.text = logLine(latest)
            if (isWide) ticker.visibility = if (logOpen) View.GONE else View.VISIBLE
        }

        val unseen = LogSink.unseenErrors()
        binding.logBadge.visibility = if (unseen > 0 && !logOpen) View.VISIBLE else View.GONE
        binding.logBadge.text = if (unseen > 99) "99+" else unseen.toString()
    }

    /**
     * 把面板里的日志文本更新成 shown：能增量就增量——从开头删掉已经不在 shown 里的
     * （超过条数上限被挤掉、或者被清空），末尾追加 seq 比已画的都大的。筛选变了、
     * 面板刚打开时（needFullRender）整段重画。返回文本有没有变。
     */
    private fun updateLogText(tv: TextView, shown: List<LogSink.Entry>): Boolean {
        if (needFullRender) {
            renderedSeqs.clear()
            renderedLengths.clear()
            val sb = SpannableStringBuilder()
            appendEntries(sb, shown)
            tv.setText(sb, TextView.BufferType.EDITABLE)
            needFullRender = false
            return true
        }
        val text = tv.editableText ?: run { needFullRender = true; return updateLogText(tv, shown) }
        var changed = false

        // 开头被挤掉的：已画的、但 seq 比 shown 里最旧的还小（shown 为空就是全清了）
        val oldest = shown.firstOrNull()?.seq ?: Long.MAX_VALUE
        var dropCount = 0
        var dropChars = 0
        while (renderedSeqs.isNotEmpty() && renderedSeqs.first() < oldest) {
            renderedSeqs.removeFirst()
            dropChars += renderedLengths.removeFirst()
            dropCount++
        }
        if (dropCount > 0) {
            // 每条后面跟一个换行（最后一条除外）；全删光了就直接清空
            if (renderedSeqs.isEmpty()) text.clear() else text.delete(0, dropChars + dropCount)
            changed = true
        }

        val newest = renderedSeqs.lastOrNull() ?: Long.MIN_VALUE
        val fresh = shown.filter { it.seq > newest }
        if (fresh.isNotEmpty()) {
            appendEntries(text, fresh)
            changed = true
        }
        return changed
    }

    /** 逐条追加到 out（条与条之间一个换行），并记下每条的 seq 和字符数 */
    private fun appendEntries(out: Editable, entries: List<LogSink.Entry>) {
        entries.forEach { e ->
            if (out.isNotEmpty()) out.append('\n')
            val start = out.length
            appendEntry(out, e)
            renderedSeqs.addLast(e.seq)
            renderedLengths.addLast(out.length - start)
        }
    }

    /** 时间灰色（只到秒），正文按级别上色；分段标题单独一行灰色 */
    private fun appendEntry(out: Editable, e: LogSink.Entry) {
        val secondary = getColor(R.color.text_secondary)
        if (e.level == LogSink.Level.SECTION) {
            appendColored(out, "—— ${e.text} ——", secondary)
            return
        }
        appendColored(out, e.time.take(8), secondary)
        out.append("  ")
        appendColored(out, e.text, getColor(when (e.level) {
            LogSink.Level.OK -> R.color.ok
            LogSink.Level.WARN -> R.color.warn
            LogSink.Level.ERROR -> R.color.error_text
            else -> R.color.text_body
        }))
    }

    private fun appendColored(out: Editable, s: String, color: Int) {
        val start = out.length
        out.append(s)
        out.setSpan(ForegroundColorSpan(color), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** 顶栏 / 底部日志条上的单独一条 */
    private fun logLine(e: LogSink.Entry): CharSequence = SpannableStringBuilder().also { appendEntry(it, e) }

    // ==================== 权限 ====================

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH_PERM) {
            // API 31+ 这里一次申请 CONNECT+SCAN 两个权限，逐个检查而不是只看 grantResults[0]
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            LogSink.append(if (allGranted) "✅ 蓝牙连接/扫描权限已授权" else "❌ 蓝牙权限未完全授权，自动连接/配对可能不可用")
            // 不在这里主动触发连接——插件自己的 BleManager 有 5s 重连轮询，
            // 权限刚授权的话最多等一个轮询周期就会自己接上，不需要壳插手。
        } else {
            // 不是壳发起的，就是插件借本 Activity 发起的：原样转给插件，日志和
            // 按钮状态由插件自己处理。插件没加载（或不认这个事件）时只记一笔。
            val handled = PluginLoader.current?.onEvent("perm.result", mapOf(
                "requestCode" to requestCode,
                "permissions" to permissions.toList(),
                "grantResults" to grantResults.toList()
            )) ?: false
            if (!handled) LogSink.append("⚠️ 权限申请结果无人处理（requestCode=$requestCode）：${permissions.joinToString()}")
        }
        refreshStatusChips()
    }

    /**
     * BLUETOOTH_CONNECT/BLUETOOTH_SCAN 是 API 31+ 的运行时权限，一起申请
     * （插件自己的 BleManager 找不到已知/已配对设备时会自己扫描+配对，两个
     * 都要，但只有 Activity 能发起权限申请，插件够不到，得由壳代劳）；
     * 更早系统连接靠 manifest 里 maxSdkVersion=30 的旧 BLUETOOTH/BLUETOOTH_ADMIN
     * （安装时自动授予），扫描则要 ACCESS_FINE_LOCATION。由顶栏蓝牙胶囊触发。
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
            refreshStatusChips()
        } else {
            LogSink.append("请求权限：${needed.joinToString()}")
            requestPermissions(needed.toTypedArray(), REQUEST_BLUETOOTH_PERM)
        }
    }

    // ==================== 插件 ====================

    /** 若插件已加载，把插件区容器交给插件渲染（它会清掉空状态占位） */
    private fun bindPluginUi() {
        PluginLoader.current?.onEvent("ui.bind", mapOf("container" to binding.pluginContainer))
    }

    /**
     * Activity 销毁时通知插件解绑容器。插件实例是进程级单例，活得比 Activity 久，
     * 不解绑会让插件一直持有已销毁的 View 树。注意这里不能 unload 插件。
     */
    override fun onDestroy() {
        PluginLoader.current?.onEvent("ui.unbind", emptyMap())
        LogSink.clearListener(this)
        super.onDestroy()
    }

    /**
     * 系统投递的按键（车机物理键、系统返回键）转给插件，同样走 onEvent("key")。
     * 旋钮本身的转/点/长按不经过这里，是插件自己的 BleManager 收到 BLE 通知后
     * 直接处理的（BleManager 现在归插件所有，见 plugin/BleManager.kt）。
     * 窄布局日志抽屉拉开时，返回键先用来收起抽屉（按下和抬起都吃掉）。
     *
     * ⚠️ 为什么用 dispatchKeyEvent 而非 onKeyDown：它在 View 树分发之前拿到事件，
     * 不会被获得焦点的按钮抢走 DPAD/ENTER。
     * 插件不消费则交回系统默认处理（返回键默认行为 = 关闭页面）。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!isWide && logOpen && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) setLogOpen(false)
            return true
        }
        val consumed = PluginLoader.current?.onEvent("key", mapOf(
            "code" to event.keyCode,
            "action" to event.action
        )) ?: false
        return consumed || super.dispatchKeyEvent(event)
    }

    /** 把选中的插件包 / dex 拷进私有目录并加载 */
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
                LogSink.append("❌ 插件导入/加载失败，请重新选择有效的插件包")
                Toast.makeText(this, "插件加载失败，请重新选择", Toast.LENGTH_LONG).show()
            }
        } catch (e: Throwable) {
            LogSink.append("❌ 导入异常: ${e.javaClass.simpleName} - ${e.message}")
        }
        refreshStatusChips()
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

    /** 窗口大小、密度、是不是分屏、用的哪份布局（宽 >= 960dp） */
    private fun logWindowInfo() {
        val c = resources.configuration
        val density = resources.displayMetrics.density
        LogSink.append("窗口 ${c.screenWidthDp}×${c.screenHeightDp}dp，密度 $density，" +
            "分屏=${if (isInMultiWindowMode) "是" else "否"}，布局=${if (isWide) "宽" else "窄"}")
    }

    /**
     * 平台专属权限状态；列表为空（generic）则整段跳过。先一行汇总，再逐个列明细。
     * 明细里的"未授权"只是状态，不算错误：壳把各设备的 GET/SET 都按命名规律声明了，
     * 车机上很多根本不存在，每次启动都会有几十个未授权——按错误算的话，未读错误
     * 角标一开机就是一大串，"警告和错误"里也全是它们。功能真缺权限时插件的启动
     * 自检会报错。
     */
    private fun logPermissionStatus() {
        val perms = PlatformConfig.statusPermissions
        if (perms.isEmpty()) return
        val pm = packageManager
        val granted = perms.associateWith { perm ->
            try {
                pm.checkPermission(perm, packageName) == PackageManager.PERMISSION_GRANTED
            } catch (e: Throwable) { false }
        }
        LogSink.section("平台权限状态")
        LogSink.append("平台权限 ${perms.size} 个：已授权 ${granted.count { it.value }}，未授权 ${granted.count { !it.value }}", LogSink.Level.INFO)
        granted.forEach { (perm, ok) ->
            val short = perm.removePrefix("android.permission.")
            LogSink.append(if (ok) "✅ $short 已授权" else "❌ $short 未授权", LogSink.Level.INFO)
        }
    }

    private fun logPluginStatus() {
        val p = PluginLoader.current
        LogSink.section("插件状态")
        if (p != null) {
            LogSink.append("✅ 插件已加载")
        } else {
            LogSink.append("⚠️ 插件未加载，请点「导入插件」选择插件包")
        }
    }

    // ==================== 保存日志 ====================

    private fun saveLog() {
        val content = LogSink.export().trim()
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
