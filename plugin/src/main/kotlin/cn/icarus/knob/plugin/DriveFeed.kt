package cn.icarus.knob.plugin

import android.content.pm.PackageManager
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 旋钮驾驶页的数据源（车速/油门/刹车）。
 *
 * 旋钮停靠点画面闲置一会儿会自己切到驾驶页，第一次操作再切回来——什么时候
 * 显示全由固件决定（firmware/src/main.cpp 的 update_drive），插件的
 * currentPageId 不变。这里只管把最新的驾驶数据单独推过去（slot "drive"，不跟
 * 停靠点同步抢排队位）。读不到车速（实车缺 SPEED_GET、设备初始化失败）就一条
 * 都不推，旋钮也就不会切到驾驶页。以下状态只在主线程读写。
 */
internal class DriveFeed(private val plugin: MainPlugin) {

    companion object {
        // 驾驶数据推给旋钮的最短间隔（最多 5 次/秒），见 schedulePush
        private const val PUSH_INTERVAL_MS = 200L
    }

    private var started = false
    private var haveData = false
    private var speed: Int? = null
    private var throttle = 0
    private var brake = 0
    private var lastPushed: Map<String, Any>? = null
    private var lastPushMs = 0L
    private var pushScheduled = false
    private var missingPermLogged = false

    /**
     * 启动自检通过后、以及权限申请有结果后调：车速设备的权限齐了就初始化、读一次
     * 起点，之后靠监听回调更新。车速/车身不在启动自检的必需项里，缺权限只记一笔。
     */
    fun start() {
        if (started || !plugin.startup.passed) return
        if (SIMULATE_CAR_CONTROL) {
            started = true
            plugin.host?.log("🧪（模拟）旋钮驾驶页用假的车速/油门/刹车数据")
            plugin.mainHandler.post(simulateTick)
            return
        }
        val ctx = plugin.appContext ?: return
        val ctrl = plugin.carControl ?: return
        val group = CarControl.PERM_GROUPS.first { it.common == CarControl.PERM_SPEED }
        val missing = (listOf(group.common) + group.signature)
            .filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            if (!missingPermLogged) {
                missingPermLogged = true
                plugin.host?.log("ℹ️ 旋钮驾驶页没有数据：缺 ${missing.joinToString("、") { it.removePrefix("android.permission.") }}")
            }
            return
        }
        started = true
        Thread {
            val snap = try { ctrl.startDriveData(ctx) } catch (e: Throwable) { null }
            plugin.mainHandler.post {
                if (snap == null) {
                    started = false // 下次权限有变化再试
                    plugin.host?.log("❌ 车速设备初始化失败，旋钮驾驶页没有数据（详见上方）")
                    return@post
                }
                plugin.host?.log("🚗 驾驶数据已接上：车速 ${snap.speedKmh} km/h、油门 ${snap.accelerate}%、刹车 ${snap.brake}%")
                snap.speedKmh?.let { onSpeed(it) }
                snap.accelerate?.let { onPedal(throttle = true, it) }
                snap.brake?.let { onPedal(throttle = false, it) }
            }
        }.start()
    }

    /** 车速变化（主线程调）。 */
    fun onSpeed(kmh: Double) {
        // SDK 范围 0~282；超出范围（读失败时的哨兵值之类）按读不到算
        speed = if (kmh.isNaN() || kmh < 0 || kmh > 400) null else kmh.roundToInt()
        haveData = true
        schedulePush()
    }

    /** 油门/刹车深度变化（主线程调）。 */
    fun onPedal(throttle: Boolean, percent: Int) {
        if (percent !in 0..100) return // 失败返回码（SPEED_COMMAND_* 都是很大的负数），不当真
        if (throttle) this.throttle = percent else brake = percent
        haveData = true
        schedulePush()
    }

    /** 限频：最多每 PUSH_INTERVAL_MS 推一次，中间的变化合并成最新一份。 */
    private fun schedulePush() {
        if (pushScheduled) return
        pushScheduled = true
        val wait = PUSH_INTERVAL_MS - (SystemClock.uptimeMillis() - lastPushMs)
        plugin.mainHandler.postDelayed(pushRunnable, wait.coerceAtLeast(0))
    }

    private val pushRunnable = Runnable {
        pushScheduled = false
        push(force = false)
    }

    /** 数值没变就不推（停车不动时没有流量）；刚连上旋钮时 force 补一份（旋钮断线时清掉了驾驶数据）。 */
    fun push(force: Boolean) {
        if (!plugin.bleConnected || !haveData) return
        val data = buildMap<String, Any> {
            speed?.let { put("speed", it) }
            put("throttle", throttle)
            put("brake", brake)
        }
        if (!force && data == lastPushed) return
        lastPushed = data
        lastPushMs = SystemClock.uptimeMillis()
        plugin.bleManager?.write(data, slot = "drive")
    }

    /** 插件销毁时撤掉还没跑的定时任务。 */
    fun stop() {
        plugin.mainHandler.removeCallbacks(simulateTick)
        plugin.mainHandler.removeCallbacks(pushRunnable)
    }

    // 模拟版没有真车速：8 秒一个周期，车速在 10~110 之间起伏，加速时踩油门、减速时踩刹车。
    private var simulatePhase = 0.0
    private val simulateTick = object : Runnable {
        override fun run() {
            simulatePhase += 2 * PI * PUSH_INTERVAL_MS / 8000
            val trend = cos(simulatePhase)
            speed = (60 + 50 * sin(simulatePhase)).roundToInt()
            throttle = if (trend > 0) (trend * 60).roundToInt() else 0
            brake = if (trend < 0) (-trend * 70).roundToInt() else 0
            haveData = true
            schedulePush()
            plugin.mainHandler.postDelayed(this, PUSH_INTERVAL_MS)
        }
    }
}
