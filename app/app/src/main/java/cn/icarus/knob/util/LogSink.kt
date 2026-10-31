package cn.icarus.knob.util

import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局日志器：无 adb 环境下的唯一观测手段——日志直接显示在 App 内的日志面板里
 * （车机无 adb，见 docs/验证结论.md）。
 * 线程安全（任意线程 append，回调主线程通知界面）；保留最近 MAX_LINES 条防内存膨胀。
 *
 * 每条日志有级别，界面据此分颜色、筛"警告和错误"、数没看过的错误。不指定时按
 * 内容里的符号猜（❌ 错误 / ⚠ 警告 / ✅ 成功），插件经 KnobHost.log 打的日志都走这条；
 * 猜不对的地方（比如启动时的权限清单，❌ 只是"没授权"这个状态）由调用方明确传级别。
 *
 * ⚠️ 防泄漏：用 WeakReference 持有界面（Listener 就是 KnobActivity）——单例若强持有
 * Activity，Activity 重建/退出后整棵 View 树无法被 GC。
 */
object LogSink {
    private const val MAX_LINES = 500

    enum class Level { INFO, OK, WARN, ERROR, SECTION }

    /** time 是 HH:mm:ss.SSS；SECTION 的 text 是分段标题；seq 从 1 起递增，界面靠它只追加新来的 */
    class Entry(val seq: Long, val time: String, val text: String, val level: Level)

    /** 日志变了（主线程上回调；短时间内连续 append 合并成一次） */
    fun interface Listener {
        fun onLogChanged()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var listenerRef: WeakReference<Listener> = WeakReference(null)

    // 以下四个都在 lines 的锁里读写
    private val lines = ArrayDeque<Entry>()
    private var nextSeq = 1L
    private var unseenErrors = 0
    private var notifyPending = false

    // 日志面板现在是不是看得见（看得见时新来的错误不算"没看过"）。由当前注册的
    // 界面通过 setPanelVisible 设置，界面注销时自动变回 false。
    @Volatile private var panelVisible = false

    private val notifyRunnable = Runnable {
        synchronized(lines) { notifyPending = false }
        listenerRef.get()?.onLogChanged()
    }

    fun setListener(listener: Listener) {
        listenerRef = WeakReference(listener)
        scheduleNotify()
    }

    /**
     * 注销界面：只有当前注册的就是它时才清掉。界面重建时新旧 Activity 的
     * onCreate/onDestroy 先后不保证，旧的销毁晚了不能把新的也注销掉。
     */
    fun clearListener(listener: Listener) {
        if (listenerRef.get() !== listener) return
        listenerRef = WeakReference(null)
        panelVisible = false
    }

    /** 日志面板看不看得见（面板开着且界面在前台）。不是当前注册的界面设的不算。 */
    fun setPanelVisible(owner: Listener, visible: Boolean) {
        if (listenerRef.get() === owner) panelVisible = visible
    }

    /** 当前全部日志（旧的在前）的拷贝 */
    fun snapshot(): List<Entry> = synchronized(lines) { ArrayList(lines) }

    /** 面板收起期间来的错误条数，打开面板时 [markSeen] 清零 */
    fun unseenErrors(): Int = synchronized(lines) { unseenErrors }

    fun markSeen() {
        synchronized(lines) { unseenErrors = 0 }
        scheduleNotify()
    }

    fun clear() {
        synchronized(lines) {
            lines.clear()
            unseenErrors = 0
        }
        scheduleNotify()
    }

    /** 追加一行日志；level 不传就按内容里的符号猜（见 levelOf） */
    fun append(msg: String, level: Level = levelOf(msg)) = add(time(), msg, level)

    /** 分段标题 */
    fun section(title: String) = add(time(), title, Level.SECTION)

    /** 保存日志用的纯文本：全部条目，不管界面上筛了什么 */
    fun export(): String = snapshot().joinToString("\n") {
        if (it.level == Level.SECTION) "${it.time}  ========== ${it.text} ==========" else "${it.time}  ${it.text}"
    }

    private fun add(time: String, text: String, level: Level) {
        synchronized(lines) {
            val entry = Entry(nextSeq++, time, text, level)
            lines.addLast(entry)
            while (lines.size > MAX_LINES) lines.removeFirst()
            if (entry.level == Level.ERROR && !panelVisible) unseenErrors++
        }
        scheduleNotify()
    }

    private fun scheduleNotify() {
        synchronized(lines) {
            if (notifyPending) return
            notifyPending = true
        }
        // 稍微等一下再通知：启动、自检时一下子来几十条，合成一次重绘
        mainHandler.postDelayed(notifyRunnable, 50)
    }

    private fun levelOf(msg: String): Level = when {
        "❌" in msg -> Level.ERROR
        "⚠" in msg -> Level.WARN // "⚠️" 是 "⚠" 加变体选择符，也包含在内
        "✅" in msg -> Level.OK
        else -> Level.INFO
    }

    private fun time(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
}
