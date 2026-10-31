package cn.icarus.knob.plugin

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import android.view.KeyEvent
import cn.icarus.knob.api.KnobHost
import java.util.UUID

/**
 * 手机 ⇄ ESP32 knob 的唯一 BLE 通道（GATT client）。归插件所有——认哪个
 * 设备（UUID/名字前缀）、怎么编解码，都是插件+固件这一对的私有约定，壳
 * 不用管，也不需要跟着重签。代价：这个类的生命周期绑定插件实例，重新
 * 导入 plugin.dex 会连带断开当前的 BLE 连接（见 [shutdown]，由
 * PluginLoader 在换实例前调用）。
 *
 * 壳只提供 log() 这一个能力（转发到壳的日志窗口，车机无 adb，这是唯一
 * 观测手段），其余都不经过壳：按键事件、连接状态变化、扫描候选发现，
 * 都通过下面几个回调属性直接交给持有者（MainPlugin）处理。
 *
 * ⚠️ 坑（历史）：早期用 BLE HID 键盘 + 无障碍服务截系统按键，车机定制系统
 * 没有无障碍设置入口（跳转崩溃），弃用，改 GATT 直传插件。
 * ⚠️ 坑：这台车机蓝牙栈不按标准 AOSP 行为来（重连不发 ACTION_ACL_CONNECTED
 * 广播、autoConnect=true 会触发额外配对），故重连靠定时轮询 + 直连。
 * ⚠️ 不自动 createBond()：被动扫描到候选只发现、不自动配对（会突然弹系统
 * 配对框，用户没操作的情况下体验很打断），真正配对交给 [pairCandidate]。
 */
class BleManager(context: Context, private val host: KnobHost) {

    private val appContext: Context = context.applicationContext

    // ==================== 回调：直接交给持有者（MainPlugin），不经过壳 ====================

    /** 收到一次按键（旋钮转/点/长按解码后的结果），返回是否消费。 */
    var onKeyEvent: ((code: Int, action: Int, value: Int?) -> Boolean)? = null

    /** GATT 连接状态变化（含首次连上、断线重连、断开）。 */
    var onConnectionStateChanged: ((connected: Boolean, name: String?, mac: String?) -> Unit)? = null

    /** 被动扫描发现候选设备，等待用户在界面上确认配对（见 [pairCandidate]）。 */
    var onCandidateFound: ((name: String) -> Unit)? = null

    /**
     * 连上后读到的固件版本号和板子名称（设备信息服务 0x2A26/0x2A27），在
     * onConnectionStateChanged(connected=true) 之前回调。读不到（旧固件没有
     * 这两个特征值，或者读失败）对应参数就是 null。otaSupported = 旋钮有
     * 没有 OTA 特征值（没有就只能用 USB 刷）。
     */
    var onFirmwareInfo: ((version: String?, board: String?, otaSupported: Boolean) -> Unit)? = null

    /** OTA 进度：已发送并被旋钮确认的字节数 / 总字节数。 */
    var onOtaProgress: ((sent: Int, total: Int) -> Unit)? = null

    /** OTA 结束：成功（旋钮已校验并切换启动分区，马上重启）或失败（附原因）。 */
    var onOtaResult: ((success: Boolean, message: String) -> Unit)? = null

    private var gatt: BluetoothGatt? = null
    private var displayCharacteristic: BluetoothGattCharacteristic? = null
    private var keyEventCharacteristic: BluetoothGattCharacteristic? = null
    private var otaCharacteristic: BluetoothGattCharacteristic? = null

    // 协商到的 ATT MTU，决定 OTA 每包能带多少数据（见 otaChunkSize）
    @Volatile
    private var negotiatedMtu = 23

    /**
     * 正在进行的一次 OTA，null = 没在升级。升级期间独占 GATT 写通道，显示
     * 同步全部丢弃（见 [write]）。只在主线程碰——GATT 回调都先 post 回主线程。
     */
    private class OtaSession(val data: ByteArray, val sha256: ByteArray) {
        var beginSent = false   // BEGIN 发出去了（开始前可能要等一个在飞的显示写完成）
        var ready = false       // 旋钮回了 READY 通知
        var started = false     // 进入发数据阶段（READY 和 BEGIN 的写回执都到了）
        // 正在等写回执的那个操作码，null = 没有写在飞。⚠️ 回执必须按它来认，
        // 不能按 started 之类的阶段推断：固件在处理 BEGIN 的回调里就发了 READY
        // 通知，通知会比 BEGIN 的写回执先到，按阶段推断会把迟到的 BEGIN 回执
        // 当成第一包 DATA 的回执，第一包还在飞就发第二包（实测 writeCharacteristic 被拒）
        var inFlightOp: Byte? = null
        var offset = 0          // 已被旋钮确认的字节数
        var inFlightLen = 0     // 正在飞的那一包 DATA 的数据长度，写回执回来才计入 offset
        var lastPercent = -1
    }
    private var ota: OtaSession? = null
    private val otaTimeout = Runnable { failOta("超过 ${OTA_TIMEOUT_MS / 1000} 秒没有回应", sendAbort = true) }

    // 同一条 GATT 连接同一时刻只能有一个写操作在飞（前一个的 onCharacteristicWrite
    // 没回来之前发下一个会被系统直接拒绝）。syncToKnob() 可能被多个独立触发源
    // 挨得很近地连续调用（比如联动模式一次改温度会给主驾/副驾各触发一次监听
    // 回调），所以这里不能"来一个发一个"，得排队。因为每次 write() 发的都是
    // 当前完整状态快照（不是增量），排队没意义的中间状态直接覆盖丢弃即可，只
    // 保留最新一份。但"最新一份"要按消息种类分开算（write 的 slot 参数）：停靠点
    // 同步、亮度、驾驶数据是互不相干的三种消息，共用一个排队位的话，频繁的
    // 驾驶数据会把还没发出去的停靠点同步顶掉。每种各留最新一份，按先来后到发。
    // 这两个字段只在主线程碰（onCharacteristicWrite 回调发生在蓝牙 Binder 线程，
    // 见下方，会先 post 回主线程再改这两个字段）。
    private var writeInFlight = false
    private val pendingWrites = LinkedHashMap<String, ByteArray>()

    // 连接建立后依次读设备信息服务里的这几个特征值（GATT 同一时刻只能有
    // 一个操作在飞，只能一个读完再读下一个），读完才通知"已连接"——那会
    // 触发 syncToKnob() 写显示特征值，提前通知会跟读操作撞车。只在 GATT
    // 回调里碰，回调是串行的，不用加锁。
    private val infoReadQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private val infoReadResults = mutableMapOf<UUID, String?>()

    // 这次连接是不是已经清过一次 GATT 缓存——见 onServicesDiscovered，每次
    // 连接最多清一次，清完还缺就说明固件真的没有，不能无限重试。
    private var gattCacheRefreshed = false

    // 这次连接是不是已经发起过服务发现。⚠️ 实测有的手机（小米 Android 16）
    // 会把 onMtuChanged 回调两次，每次都 discoverServices() 的话，两次发现
    // 结果会各自走一遍订阅/读版本/通知已连接，互相撞车。
    private var discoveryStarted = false

    private fun logBoth(msg: String, isWarning: Boolean = false) {
        if (isWarning) Log.w(TAG, msg) else Log.i(TAG, msg)
        host.log("[BLE] $msg")
    }

    /**
     * API 31+ 的 BLUETOOTH_CONNECT 是运行时权限，没拿到时碰蓝牙 API 会直接抛
     * SecurityException 崩掉——所有会碰蓝牙的入口统一先过这道检查。权限本身
     * 由壳的「申请蓝牙权限」按钮申请（Activity-only API，插件够不到），这里
     * 只读状态。
     */
    private fun hasBluetoothPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true // API < 31 无需运行时申请
        return appContext.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * 扫描（BLUETOOTH_SCAN，API 31+）跟连接（BLUETOOTH_CONNECT）是两个独立的运行时权限。
     * API < 31 靠 ACCESS_FINE_LOCATION 兜底（旧版 BLE 扫描的前提条件）。
     */
    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            appContext.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isConnected(): Boolean = gatt != null

    private val pollHandler = Handler(Looper.getMainLooper())
    private var pollingStarted = false

    // ⚠️ 曾想用 ACTION_ACL_CONNECTED 广播驱动重连，实测这台车机不发该广播
    // （系统蓝牙设置显示已连接，广播却收不到），不值得继续折腾，改定时轮询。
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isConnected()) tryConnect()
            pollHandler.postDelayed(this, RECONNECT_POLL_INTERVAL_MS)
        }
    }

    /** 插件初始化完成后调一次即可（幂等）：立即尝试一次连接，之后每 5s 重试。 */
    fun start() {
        if (pollingStarted) return
        pollingStarted = true
        pollHandler.post(pollRunnable)
    }

    /**
     * 插件销毁/被替换时调用（见 PluginLoader.loadDex）：停轮询、关 GATT、
     * 注销广播接收器。BLE 连接绑定插件实例的生命周期，这里不清理的话会
     * 挂着引用旧插件 classLoader 的类，是内存泄漏。
     */
    @SuppressLint("MissingPermission")
    fun shutdown() {
        pollHandler.removeCallbacksAndMessages(null)
        gatt?.close()
        gatt = null
        if (bondReceiverRegistered) {
            runCatching { appContext.unregisterReceiver(bondStateReceiver) }
            bondReceiverRegistered = false
        }
    }

    // 记住上一次连上的 knob MAC（SharedPreferences），下次直接连它，不用每次
    // 都重新扫描——车机蓝牙设置界面搜不到纯 BLE 自定义外设（见 W1a 分析），
    // 首次配对必须靠 App 自己扫，但后续没必要每次都重扫一遍。
    private fun getSavedMac(): String? =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_KNOB_MAC, null)

    private fun saveMac(mac: String) {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_KNOB_MAC, mac).apply()
    }

    /**
     * 找 knob 并发起 GATT 连接，按下面优先级：
     * 1. 上次记住的 MAC（最常见路径，直连，不用扫描）
     * 2. 用户在系统设置里手动配对过的设备（兼容旧路径）
     * 3. 都没有：被动扫描（见 scanForCandidate）——车机蓝牙设置界面的"搜索"
     *    不枚举纯 BLE 自定义外设，绕开它自己扫，但只发现、不自动
     *    createBond()：自动弹系统配对框对用户来说太突兀，扫到后交给
     *    [pairCandidate] 由用户在界面上确认后才真正配对。
     * 已连接/正在连接时重复调用直接跳过；因一时半会没连上而失败可放心再调重试。
     */
    @SuppressLint("MissingPermission")
    private fun tryConnect() {
        if (!hasBluetoothPermission()) {
            logBoth("tryConnect() 中止：还没拿到 BLUETOOTH_CONNECT 权限", isWarning = true)
            return
        }
        if (gatt != null) {
            logBoth("tryConnect() 跳过：已经连接/正在连接中")
            return
        }
        val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) {
            logBoth("拿不到 BluetoothAdapter", isWarning = true)
            return
        }

        val savedMac = getSavedMac()
        val known = savedMac?.let { runCatching { adapter.getRemoteDevice(it) }.getOrNull() }
        if (known != null) {
            connectOrBond(known)
            return
        }

        val bonded = adapter.bondedDevices?.firstOrNull { it.name?.startsWith(DEVICE_NAME_PREFIX) == true }
        if (bonded != null) {
            saveMac(bonded.address)
            connectOrBond(bonded)
            return
        }

        if (!hasScanPermission()) {
            logBoth("没有已知/已配对的 knob，且缺扫描权限，无法自动扫描（先在壳里申请一次蓝牙权限）", isWarning = true)
            return
        }
        scanForCandidate()
    }

    // createBond() 发出去、结果还没回来（用户可能正盯着系统配对框在犹豫）
    // 这段时间内不要再被动扫描——不然会拿同一个设备重新刷一次候选通知，
    // 徒增噪音。由 bondStateReceiver 收到最终结果时清掉。
    @Volatile
    private var pairing = false

    /** bondState 已是 BONDED 直接连；否则发起 createBond()，结果由 [bondStateReceiver] 接手。 */
    @SuppressLint("MissingPermission")
    private fun connectOrBond(device: BluetoothDevice) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            logBoth("找到设备 ${device.name}（已配对），发起 GATT 连接")
            // ⚠️ 用 autoConnect=false 直连：autoConnect=true 在这台车机上会触发
            // 一次独立的全新配对（弹跟系统配对不一样的 6 位数），不复用已有绑定。
            gatt = device.connectGatt(appContext, /* autoConnect = */ false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            return
        }
        logBoth("设备 ${device.name ?: device.address} 尚未配对，发起 createBond()（车机应会弹出配对确认框）")
        pairing = true
        registerBondReceiver()
        if (!device.createBond()) {
            pairing = false
            logBoth("createBond() 调用失败", isWarning = true)
        }
    }

    private var bondReceiverRegistered = false

    /** createBond() 是异步的，配对结果（含用户在车机上点了确认/取消）只能靠这个广播拿到。 */
    private val bondStateReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return
            if (device.name?.startsWith(DEVICE_NAME_PREFIX) != true) return
            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
                BluetoothDevice.BOND_BONDED -> {
                    pairing = false
                    logBoth("配对成功：${device.name}，发起 GATT 连接")
                    saveMac(device.address)
                    gatt = device.connectGatt(appContext, /* autoConnect = */ false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                }
                BluetoothDevice.BOND_NONE -> {
                    pairing = false
                    logBoth("配对失败或被取消：${device.name}", isWarning = true)
                }
            }
        }
    }

    private fun registerBondReceiver() {
        if (bondReceiverRegistered) return
        bondReceiverRegistered = true
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        // API 33+ 要求上下文注册的 receiver 显式声明导出与否；ACTION_BOND_STATE_CHANGED
        // 是系统 protected broadcast，用 NOT_EXPORTED 即可（不需要接收别的 App 发的同名广播）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(bondStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(bondStateReceiver, filter)
        }
    }

    @Volatile
    private var scanning = false

    // 被动扫描到、等用户在界面上确认配对的候选设备——扫到就停（见
    // scanForCandidate 里的判重），不用重复发现同一个；用户点确认
    // （pairCandidate）或者它自己连上/换了个新设备才清掉。
    @Volatile
    private var candidateDevice: BluetoothDevice? = null

    /**
     * 被动扫描找 knob：每次由外部（目前是 [start] 的 5s 轮询）触发一次，
     * 扫到就记下候选、报给持有者在界面上显示，绝不自动 createBond()——
     * 车机在用户没操作的情况下突然弹配对确认框，体验很打断。真正的配对
     * 由用户在界面上看到候选后点确认，触发 [pairCandidate]。已经有一个
     * 候选在等确认时不用再扫。
     */
    @SuppressLint("MissingPermission")
    private fun scanForCandidate(durationMs: Long = 4_000L) {
        if (scanning || candidateDevice != null || pairing) return
        val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            logBoth("拿不到 BluetoothLeScanner，无法扫描", isWarning = true)
            return
        }
        scanning = true
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        lateinit var callback: ScanCallback
        val timeoutRunnable = Runnable {
            scanner.stopScan(callback)
            scanning = false
        }
        callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                scanner.stopScan(this)
                mainHandler.removeCallbacks(timeoutRunnable)
                scanning = false
                val device = result.device
                candidateDevice = device
                val name = device.name ?: device.address
                logBoth("发现候选设备 $name（mac=${device.address}），等待用户在界面上确认配对")
                mainHandler.post { onCandidateFound?.invoke(name) }
            }

            override fun onScanFailed(errorCode: Int) {
                scanning = false
                logBoth("扫描失败 errorCode=$errorCode", isWarning = true)
            }
        }
        scanner.startScan(filters, settings, callback)
        mainHandler.postDelayed(timeoutRunnable, durationMs)
    }

    /**
     * 用户在界面上看到扫描到的候选设备后点了确认——只有这个时机才真正
     * 发起配对（可能弹系统配对框）。没有候选（比如用户还没扫到就点了、
     * 或者候选已经处理过）时安全地什么都不做。
     */
    fun pairCandidate() {
        val device = candidateDevice
        if (device == null) {
            logBoth("pairCandidate() 被调用，但当前没有等待确认的候选设备", isWarning = true)
            return
        }
        candidateDevice = null
        connectOrBond(device)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    candidateDevice = null // 连都连上了，不需要再等界面确认配对
                    gattCacheRefreshed = false
                    discoveryStarted = false
                    // 提示系统走低延迟连接（配合固件短连接间隔）；然后先协商大 MTU
                    // 再发现服务——默认 ATT MTU 23 字节装不下带中文的显示 payload，
                    // OTA 每包的数据量也取决于它。517 是 BLE 允许的最大值，实际
                    // 结果取手机和固件（NimBLEDevice::setMTU）两边的较小者。
                    // onMtuChanged() 里才真正 discoverServices()，失败则退回默认 MTU。
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    logBoth("GATT 已连接，请求更大 MTU")
                    g.requestMtu(517)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    // autoConnect=false 断开不会自动重连：关掉 GATT、清状态，
                    // 下次 tryConnect() 才会重连。断线即刻重试一次（knob 多半已在
                    // 重新广播），失败再由轮询兜底。
                    logBoth("GATT 断开（status=$status），已停止", isWarning = true)
                    g.close()
                    gatt = null
                    displayCharacteristic = null
                    keyEventCharacteristic = null
                    otaCharacteristic = null
                    writeInFlight = false
                    pendingWrites.clear()
                    infoReadQueue.clear()
                    infoReadResults.clear()
                    // 升级成功后旋钮会自己重启断开，那时 ota 已经清空，不算失败
                    mainHandler.post { if (ota != null) failOta("连接断开", sendAbort = false) }
                    notifyConnectionState(connected = false)
                    tryConnect()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (discoveryStarted) {
                logBoth("MTU 协商结果=$mtu (status=$status)，重复回调，忽略")
                return
            }
            discoveryStarted = true
            if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu = mtu
            logBoth("MTU 协商结果=$mtu (status=$status)，开始发现服务")
            g.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            logBoth("发现服务(status=$status)：" + g.services.joinToString { svc ->
                "${shortUuid(svc.uuid)}[${svc.characteristics.joinToString(" ") { shortUuid(it.uuid) }}]"
            })
            // ⚠️ Android 会缓存已配对设备的服务列表，固件新增了特征值（固件版本号、
            // OTA……OTA 之后 GATT 结构随时可能变）也照样用旧缓存，看起来就像固件
            // 没有这个特征值。插件跟固件是一起打包发布的，插件用到的特征值就是
            // 这一版固件该有的，缺任何一个都清一次这个设备的 GATT 缓存、重新
            // 发现；每次连接最多一次，清完还缺就是旋钮上确实是旧固件。
            val missing = REQUIRED_CHARACTERISTICS.filter { (svc, chr) -> g.getService(svc)?.getCharacteristic(chr) == null }
            if (missing.isNotEmpty() && !gattCacheRefreshed) {
                gattCacheRefreshed = true
                if (refreshGattCache(g)) {
                    logBoth("服务列表里缺 ${missing.joinToString { shortUuid(it.second) }}，可能是系统缓存了旧固件的服务列表，已清缓存，重新发现服务")
                    mainHandler.postDelayed({ if (gatt === g) g.discoverServices() }, GATT_REFRESH_DELAY_MS)
                    return
                }
            }
            val service = g.getService(SERVICE_UUID)
            if (service == null) {
                logBoth("没找到自定义服务（UUID 对不上，固件版本可能太旧）", isWarning = true)
                return
            }

            displayCharacteristic = service.getCharacteristic(DISPLAY_CHARACTERISTIC_UUID)
            logBoth(if (displayCharacteristic != null) "显示同步特征值已就绪" else "没找到显示同步特征值", isWarning = displayCharacteristic == null)
            otaCharacteristic = service.getCharacteristic(OTA_CHARACTERISTIC_UUID)

            val keyChar = service.getCharacteristic(KEY_EVENT_CHARACTERISTIC_UUID)
            // ⚠️ GATT 同一时刻只允许一个写操作在飞：CCCD 订阅写真发出去了
            // （waitingForCccd=true）就不能就地通知"已连接"——那会触发插件的
            // syncToKnob() 写显示特征值，跟这个写撞车被拒。要等 onDescriptorWrite()
            // 里真正完成后再通知。
            val waitingForCccd = if (keyChar == null) {
                logBoth("没找到按键事件特征值", isWarning = true)
                false
            } else {
                keyEventCharacteristic = keyChar
                subscribeNotifications(g, keyChar, "按键事件")
            }
            if (!waitingForCccd) startFirmwareInfoRead(g)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val isKeyEvent = descriptor.characteristic.uuid == KEY_EVENT_CHARACTERISTIC_UUID
            val label = if (isKeyEvent) "按键事件" else "OTA"
            logBoth(
                if (status == BluetoothGatt.GATT_SUCCESS) "${label}通知订阅完成" else "${label}通知订阅失败 status=$status",
                isWarning = status != BluetoothGatt.GATT_SUCCESS
            )
            // 一个 CCCD 写完才能写下一个：按键事件订阅完接着订阅 OTA 通知（固件
            // 支持的话），都完成再读固件版本，读完才通知"已连接"（见 startFirmwareInfoRead）
            val otaChar = otaCharacteristic
            if (isKeyEvent && otaChar != null && subscribeNotifications(g, otaChar, "OTA")) return
            startFirmwareInfoRead(g)
        }

        // API 33+ 路径
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            handleFirmwareInfoRead(g, characteristic, value, status)
        }

        // API < 33 路径——33+ 已由上面处理（上面那个没调 super，系统不会再转调这里）
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            handleFirmwareInfoRead(g, characteristic, characteristic.value ?: ByteArray(0), status)
        }

        // 显示同步特征值写完成的回执——收到这个才能发下一个排队的写，见 [pendingWrites]。
        // 跑在蓝牙 Binder 线程，先 post 回主线程再碰 writeInFlight/pendingWrites。
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid == OTA_CHARACTERISTIC_UUID) {
                mainHandler.post { onOtaWriteComplete(status) }
                return
            }
            if (characteristic.uuid != DISPLAY_CHARACTERISTIC_UUID) return
            mainHandler.post {
                writeInFlight = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    logBoth("显示同步写入失败 status=$status", isWarning = true)
                }
                // 有一次 OTA 在等这个显示写完成才能发 BEGIN（见 startOta）
                val session = ota
                if (session != null && !session.beginSent) {
                    pendingWrites.clear()
                    sendOtaBegin(session)
                    return@post
                }
                val slot = pendingWrites.keys.firstOrNull() ?: return@post
                issueWrite(pendingWrites.remove(slot)!!)
            }
        }

        // API 33+ 路径
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == KEY_EVENT_CHARACTERISTIC_UUID) handleKeyEventNotification(value)
            if (characteristic.uuid == OTA_CHARACTERISTIC_UUID) mainHandler.post { onOtaNotification(value) }
        }

        // API < 33 路径——33+ 已由上面处理，这里直接跳过，避免同一通知处理两遍
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            if (characteristic.uuid == KEY_EVENT_CHARACTERISTIC_UUID) handleKeyEventNotification(characteristic.value)
            if (characteristic.uuid == OTA_CHARACTERISTIC_UUID) {
                val value = characteristic.value.copyOf() // 回调返回后这个数组可能被复用，post 之前先拷一份
                mainHandler.post { onOtaNotification(value) }
            }
        }
    }

    /**
     * 订阅某个特征值的通知（按键事件 / OTA 结果）：本地开通知回调 + 写 CCCD
     * 通知外设"开始推送"，两步缺一不可。label 只用于日志。
     * @return CCCD 写是否真的发出去了——true 时调用方须等 onDescriptorWrite()
     *   才算完成；false（没找到特征值/描述符或写失败）不会有回调，不能傻等。
     */
    @SuppressLint("MissingPermission")
    private fun subscribeNotifications(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, label: String): Boolean {
        if (!g.setCharacteristicNotification(characteristic, true)) {
            logBoth("setCharacteristicNotification 失败，${label}通知收不到", isWarning = true)
            return false
        }
        val cccd = characteristic.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            logBoth("${label}特征值没有 CCCD 描述符，收不到通知", isWarning = true)
            return false
        }
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
        logBoth(if (ok) "已发起${label}通知订阅" else "订阅${label}通知失败", isWarning = !ok)
        return ok
    }

    /**
     * 清掉系统对这个设备缓存的服务列表。`BluetoothGatt.refresh()` 是隐藏 API，
     * 只能反射调用；拿不到（被系统限制）就返回 false，按原来的缓存继续走。
     */
    private fun refreshGattCache(g: BluetoothGatt): Boolean = try {
        g.javaClass.getMethod("refresh").invoke(g) as? Boolean ?: false
    } catch (e: Throwable) {
        logBoth("清 GATT 缓存失败：${e.javaClass.simpleName} ${e.message}", isWarning = true)
        false
    }

    /** 标准 16 位 UUID 只显示 4 位十六进制（如 180a），自定义 128 位 UUID 显示前 8 位，日志里好认。 */
    private fun shortUuid(uuid: UUID): String {
        val str = uuid.toString()
        return if (str.endsWith("-0000-1000-8000-00805f9b34fb") && str.startsWith("0000")) str.substring(4, 8) else str.substring(0, 8)
    }

    /**
     * 订阅完按键通知后调用：依次读设备信息服务里的固件版本、板子名称，全部
     * 读完（或者没有可读的）再交给 [finishFirmwareInfoRead] 通知持有者。
     */
    @SuppressLint("MissingPermission")
    private fun startFirmwareInfoRead(g: BluetoothGatt) {
        infoReadQueue.clear()
        infoReadResults.clear()
        val deviceInfo = g.getService(DEVICE_INFO_SERVICE_UUID)
        for (uuid in listOf(FIRMWARE_REVISION_UUID, HARDWARE_REVISION_UUID)) {
            deviceInfo?.getCharacteristic(uuid)?.let { infoReadQueue.addLast(it) }
        }
        if (infoReadQueue.isEmpty()) {
            logBoth("旋钮没有提供固件版本信息（固件太旧，需要用 USB 刷一次新固件）", isWarning = true)
        }
        readNextFirmwareInfo(g)
    }

    @SuppressLint("MissingPermission")
    private fun readNextFirmwareInfo(g: BluetoothGatt) {
        while (true) {
            val char = infoReadQueue.removeFirstOrNull() ?: break
            if (g.readCharacteristic(char)) return // 等 onCharacteristicRead 回来再读下一个
            logBoth("读取 ${char.uuid} 发起失败", isWarning = true)
            infoReadResults[char.uuid] = null
        }
        finishFirmwareInfoRead(g)
    }

    private fun handleFirmwareInfoRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
        if (characteristic.uuid != FIRMWARE_REVISION_UUID && characteristic.uuid != HARDWARE_REVISION_UUID) return
        infoReadResults[characteristic.uuid] = if (status == BluetoothGatt.GATT_SUCCESS) {
            value.toString(Charsets.UTF_8).trim()
        } else {
            logBoth("读取 ${characteristic.uuid} 失败 status=$status", isWarning = true)
            null
        }
        readNextFirmwareInfo(g)
    }

    private fun finishFirmwareInfoRead(g: BluetoothGatt) {
        val version = infoReadResults[FIRMWARE_REVISION_UUID]
        val board = infoReadResults[HARDWARE_REVISION_UUID]
        val otaSupported = otaCharacteristic != null
        logBoth("旋钮固件版本=${version ?: "未知"} 板子=${board ?: "未知"} 支持OTA=$otaSupported")
        mainHandler.post { onFirmwareInfo?.invoke(version, board, otaSupported) }
        if (displayCharacteristic != null) {
            notifyConnectionState(connected = true, device = g.device)
        }
    }

    // ⚠️ GATT 回调（含 onCharacteristicChanged）跑在蓝牙 Binder 线程而非主线程：
    // 持有者处理按键会碰 View（切页面 removeAllViews/addView），不切主线程会抛
    // CalledFromWrongThreadException，表现为回调里静默出错。
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 通用解码：一串 "fieldId(1) + type(1) + value" 字段，目前只认 type=0(int16)。
     * 遇到未知类型不知道占几字节，直接停止解析——后面的字段宁可丢掉也不能
     * 瞎猜着继续读。顺序无关、缺/多字段都不影响已解出的部分（与固件
     * parse_display_data 同一思路，只是 key 用数字 ID 代替字符串）。
     */
    private fun decodeIntFields(value: ByteArray): Map<Int, Int> {
        val result = mutableMapOf<Int, Int>()
        var pos = 0
        while (pos + 4 <= value.size) {
            val fieldId = value[pos].toInt() and 0xFF
            val type = value[pos + 1].toInt() and 0xFF
            if (type != 0) break
            val low = value[pos + 2].toInt() and 0xFF
            val high = value[pos + 3].toInt() and 0xFF
            // 固件编码的是有符号 int16（小端）：先拼 16 位再转 Short 做符号扩展，
            // 直接 (high shl 8) or low 会把负数（如座椅档位 -2 = 0xFFFE）解成 65534
            result[fieldId] = ((high shl 8) or low).toShort().toInt()
            pos += 4
        }
        return result
    }

    /**
     * 收到一次按键通知：FIELD_CODE 必有，FIELD_VALUE 只有长按（确认）才带。
     * 按下+抬起都转发（跟系统按键分发的语义一致，只吃一半会让状态错乱）；
     * 持有者不处理时没有兜底——不走系统按键分发这一层。
     */
    private fun handleKeyEventNotification(value: ByteArray) {
        val fields = decodeIntFields(value)
        val code = fields[FIELD_CODE] ?: run {
            logBoth("按键通知缺 code 字段（${value.size} 字节）", isWarning = true)
            return
        }
        val knobValue = fields[FIELD_VALUE]
        logBoth("收到按键事件 code=$code value=$knobValue")
        mainHandler.post { dispatchKey(code, knobValue) }
    }

    private fun dispatchKey(code: Int, value: Int?) {
        onKeyEvent?.invoke(code, KeyEvent.ACTION_DOWN, value)
        val consumed = onKeyEvent?.invoke(code, KeyEvent.ACTION_UP, value) ?: false
        logBoth(if (consumed) "  → 已处理" else "  → 未处理")
    }

    /**
     * 通知连接状态变化。跟按键通知一样切回主线程再转发——onConnectionStateChanged
     * 目前的实现会碰 View（切换首页/停靠点），必须在主线程。
     */
    private fun notifyConnectionState(connected: Boolean, device: BluetoothDevice? = null) {
        val name = if (connected) device?.name else null
        val mac = if (connected) device?.address else null
        mainHandler.post { onConnectionStateChanged?.invoke(connected, name, mac) }
    }

    /**
     * 把 Map 编码成字节流写给 knob——只认值类型（Int/String/Boolean），不认
     * key 名，字段意义由插件与固件约定。slot 是消息种类（停靠点同步 / 亮度 /
     * 驾驶数据……），排队时同种只留最新一份，不同种互不覆盖，见 [pendingWrites]。
     * @return 是否成功接受了这次写（可能排队等前一个写完成，不代表对方已收到；
     * 固件侧以串口日志为准）。
     */
    fun write(data: Map<String, Any>, slot: String = "page"): Boolean {
        // 升级期间 OTA 独占写通道，显示同步直接丢弃（升级完旋钮会重启，重连后插件会重新同步一次）
        if (ota != null) return false
        if (gatt == null) {
            logBoth("write() 失败：GATT 还没连上", isWarning = true)
            return false
        }
        if (displayCharacteristic == null) {
            logBoth("write() 失败：显示同步特征值还没发现（服务发现可能还没完成）", isWarning = true)
            return false
        }
        val payload = encodeDisplayData(data)
        if (writeInFlight) {
            // 上一个写还没收到回执，这次的内容是完整状态快照、不是增量，
            // 直接覆盖掉同一种还没发出去的排队内容即可。先删再放，排到队尾。
            pendingWrites.remove(slot)
            pendingWrites[slot] = payload
            return true
        }
        return issueWrite(payload)
    }

    /** 真正发起一次写，调用方需确保 gatt/displayCharacteristic 都已就绪且当前没有写在飞。 */
    @SuppressLint("MissingPermission")
    private fun issueWrite(payload: ByteArray): Boolean {
        val g = gatt ?: return false
        val char = displayCharacteristic ?: return false
        writeInFlight = true
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(char, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            char.value = payload
            @Suppress("DEPRECATION")
            g.writeCharacteristic(char)
        }
        if (!ok) {
            writeInFlight = false
            logBoth("write() 失败：writeCharacteristic 发起失败", isWarning = true)
        }
        return ok
    }

    /**
     * 通用键值对编码——不理解字段含义，只按值的 Kotlin 类型编码，固件按同格式解码
     * （firmware/src/ble.cpp 的 parse_display_data）。每条 entry：
     *   keyLen(1) + key(ASCII) + type(1：0=int16 / 1=string / 2=bool) + 值
     *   int16=2 字节小端；string=1 字节长度(≤60)+UTF-8；bool=1 字节 0/1
     * 不支持的值的类型直接跳过并告警，不中断其余字段。
     */
    private fun encodeDisplayData(data: Map<String, Any>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for ((key, value) in data) {
            val keyBytes = key.toByteArray(Charsets.US_ASCII).let { if (it.size > 255) it.copyOf(255) else it }
            when (value) {
                is Int -> {
                    out.write(keyBytes.size)
                    out.write(keyBytes)
                    out.write(0)
                    out.write(value and 0xFF)
                    out.write((value shr 8) and 0xFF)
                }
                is String -> {
                    val strBytes = value.toByteArray(Charsets.UTF_8).let { if (it.size > 60) it.copyOf(60) else it }
                    out.write(keyBytes.size)
                    out.write(keyBytes)
                    out.write(1)
                    out.write(strBytes.size)
                    out.write(strBytes)
                }
                is Boolean -> {
                    out.write(keyBytes.size)
                    out.write(keyBytes)
                    out.write(2)
                    out.write(if (value) 1 else 0)
                }
                else -> logBoth("encodeDisplayData 跳过不支持的字段类型：$key=$value (${value::class.simpleName})", isWarning = true)
            }
        }
        return out.toByteArray()
    }

    // ==================== 固件 OTA（协议见 firmware/src/ble.cpp 的 OTA 一节） ====================

    /**
     * 开始给旋钮推新固件。必须在主线程调用，且已连上、旋钮支持 OTA。
     * 有显示写在飞时先不发 BEGIN，等它的写回执回来再发（见 onCharacteristicWrite）。
     * @return 是否真的开始了（已经在升级 / 没连上 / 旋钮不支持时返回 false）
     */
    fun startOta(firmware: ByteArray, sha256: ByteArray): Boolean {
        if (ota != null) return false
        if (gatt == null || otaCharacteristic == null) {
            logBoth("startOta() 失败：没连上，或者旋钮固件不支持 OTA", isWarning = true)
            return false
        }
        val session = OtaSession(firmware, sha256)
        ota = session
        pendingWrites.clear()
        logBoth("开始 OTA：${firmware.size} 字节，MTU=$negotiatedMtu，每包 ${otaChunkSize()} 字节")
        armOtaTimeout() // 等在飞的显示写完成也算在超时里，免得它的回执永远不来时卡住
        if (!writeInFlight) sendOtaBegin(session)
        return true
    }

    // 每包 = 5 字节 OTA 头 + 数据，受两个上限约束：一次写最多 MTU - 3 字节（ATT 头），
    // 固件 NimBLE 特征值最长 512 字节（BLE_ATT_ATTR_MAX_LEN）。MTU 517 时卡在后者，每包 507 字节数据。
    private fun otaChunkSize(): Int = (minOf(negotiatedMtu - 3, OTA_MAX_WRITE_LEN) - 5).coerceAtLeast(1)

    private fun sendOtaBegin(session: OtaSession) {
        session.beginSent = true
        val payload = ByteArray(1 + 4 + 32)
        payload[0] = OTA_OP_BEGIN
        putIntLe(payload, 1, session.data.size)
        session.sha256.copyInto(payload, 5)
        writeOta(payload)
    }

    private fun sendNextOtaChunk(session: OtaSession) {
        val len = minOf(otaChunkSize(), session.data.size - session.offset)
        val payload = ByteArray(1 + 4 + len)
        payload[0] = OTA_OP_DATA
        putIntLe(payload, 1, session.offset)
        session.data.copyInto(payload, 5, session.offset, session.offset + len)
        session.inFlightLen = len
        writeOta(payload)
    }

    /** 每一次 OTA 写回执：DATA 写完才算对方收下了这一包，接着发下一包或者 END。 */
    private fun onOtaWriteComplete(status: Int) {
        val session = ota ?: return
        val op = session.inFlightOp
        session.inFlightOp = null
        if (status != BluetoothGatt.GATT_SUCCESS) {
            failOta("写入被拒绝 status=$status", sendAbort = true)
            return
        }
        armOtaTimeout()
        when (op) {
            OTA_OP_BEGIN -> if (session.ready) startOtaData(session) // READY 先到了，在等这个回执
            OTA_OP_DATA -> onOtaChunkAcked(session)
            else -> {} // END 的结果等通知
        }
    }

    // READY 通知和 BEGIN 写回执谁后到谁调这里，两个都到了才开始发数据
    private fun startOtaData(session: OtaSession) {
        if (session.started) return
        session.started = true
        logBoth("旋钮已准备好接收固件")
        sendNextOtaChunk(session)
    }

    private fun onOtaChunkAcked(session: OtaSession) {
        session.offset += session.inFlightLen
        session.inFlightLen = 0
        val percent = (session.offset.toLong() * 100 / session.data.size).toInt()
        if (percent != session.lastPercent) {
            session.lastPercent = percent
            onOtaProgress?.invoke(session.offset, session.data.size)
        }
        if (session.offset < session.data.size) {
            sendNextOtaChunk(session)
        } else {
            logBoth("固件数据发送完毕，请旋钮校验")
            writeOta(byteArrayOf(OTA_OP_END))
        }
    }

    /** 旋钮的 OTA 通知：status(1) + 已收字节数(4)。 */
    private fun onOtaNotification(value: ByteArray) {
        val session = ota ?: return
        if (value.isEmpty()) return
        val status = value[0].toInt() and 0xFF
        val received = if (value.size >= 5) getIntLe(value, 1) else -1
        when (status) {
            OTA_STATUS_READY -> {
                session.ready = true
                armOtaTimeout()
                if (session.inFlightOp == null) startOtaData(session) // BEGIN 的回执已经先到了
            }
            OTA_STATUS_DONE -> {
                pollHandler.removeCallbacks(otaTimeout)
                ota = null
                logBoth("✅ 旋钮校验通过，已切换到新固件，正在重启")
                onOtaResult?.invoke(true, "旋钮已校验新固件，正在重启")
            }
            // 出错时旋钮已经自己放弃了这次升级，不用再发 ABORT
            else -> failOta("旋钮报错：${otaStatusName(status)}（已收 $received 字节）", sendAbort = false)
        }
    }

    private fun failOta(reason: String, sendAbort: Boolean) {
        val session = ota ?: return
        pollHandler.removeCallbacks(otaTimeout)
        ota = null
        logBoth("❌ OTA 失败：$reason（已发送 ${session.offset}/${session.data.size} 字节），旋钮仍是原来的固件", isWarning = true)
        // 还有写在飞时发不出去，旋钮那边 20 秒收不到数据会自己放弃（见固件 ble_ota_check_timeout）
        if (sendAbort && session.inFlightOp == null) writeOta(byteArrayOf(OTA_OP_ABORT))
        onOtaResult?.invoke(false, reason)
    }

    private fun armOtaTimeout() {
        pollHandler.removeCallbacks(otaTimeout)
        pollHandler.postDelayed(otaTimeout, OTA_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun writeOta(payload: ByteArray) {
        val g = gatt
        val char = otaCharacteristic
        if (g == null || char == null) {
            if (ota != null) failOta("连接已断开", sendAbort = false)
            return
        }
        armOtaTimeout()
        ota?.inFlightOp = payload[0]
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(char, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            char.value = payload
            @Suppress("DEPRECATION")
            g.writeCharacteristic(char)
        }
        if (!ok) {
            ota?.inFlightOp = null
            if (ota != null) failOta("writeCharacteristic 发起失败", sendAbort = false)
        }
    }

    private fun otaStatusName(status: Int): String = when (status) {
        OTA_STATUS_ERR_BEGIN -> "无法开始（固件太大或分区不对）"
        OTA_STATUS_ERR_SEQUENCE -> "数据顺序不对"
        OTA_STATUS_ERR_WRITE -> "写 flash 失败"
        OTA_STATUS_ERR_VERIFY -> "校验失败（大小或 SHA-256 不符）"
        OTA_STATUS_ERR_END -> "固件镜像无效"
        OTA_STATUS_ERR_FORMAT -> "数据格式不对"
        else -> "未知错误 0x%02x".format(status)
    }

    private fun putIntLe(buf: ByteArray, pos: Int, v: Int) {
        for (i in 0 until 4) buf[pos + i] = (v ushr (8 * i)).toByte()
    }

    private fun getIntLe(buf: ByteArray, pos: Int): Int =
        (0 until 4).fold(0) { acc, i -> acc or ((buf[pos + i].toInt() and 0xFF) shl (8 * i)) }

    companion object {
        private const val TAG = "BleManager"

        // 与固件 build_device_name() 命名一致；下方 UUID/字段 ID 也与 firmware/src/ble.cpp|ble.h 对齐
        private const val DEVICE_NAME_PREFIX = "Knob-"
        private val SERVICE_UUID = UUID.fromString("29afa70d-2312-4f04-9b36-fe9298284756")
        private val KEY_EVENT_CHARACTERISTIC_UUID = UUID.fromString("182f303d-ea98-4ee4-839e-5599aaf3f29d")
        private val DISPLAY_CHARACTERISTIC_UUID = UUID.fromString("5f774e59-5079-4e69-8e5d-de0ee1220d3f")
        private val OTA_CHARACTERISTIC_UUID = UUID.fromString("468889d4-9176-4aa2-9a9e-cd7dd3ab1ae0")

        // OTA 操作码 / 状态码，与 firmware/src/ble.cpp 的 OTA_OP_* / OTA_STATUS_* 对齐
        private const val OTA_OP_BEGIN: Byte = 0x01
        private const val OTA_OP_DATA: Byte = 0x02
        private const val OTA_OP_END: Byte = 0x03
        private const val OTA_OP_ABORT: Byte = 0x04
        private const val OTA_STATUS_READY = 0x00
        private const val OTA_STATUS_DONE = 0x01
        private const val OTA_STATUS_ERR_BEGIN = 0x80
        private const val OTA_STATUS_ERR_SEQUENCE = 0x81
        private const val OTA_STATUS_ERR_WRITE = 0x82
        private const val OTA_STATUS_ERR_VERIFY = 0x83
        private const val OTA_STATUS_ERR_END = 0x84
        private const val OTA_STATUS_ERR_FORMAT = 0x85
        // 旋钮写 flash 时擦除扇区会慢一点，单包回执远到不了这个值；超时说明链路卡死了
        private const val OTA_TIMEOUT_MS = 15_000L
        private const val OTA_MAX_WRITE_LEN = 512
        // 标准 CCCD 描述符：订阅 NOTIFY 靠写它
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        // 标准设备信息服务及其中的固件版本 / 硬件版本特征值，固件挂在 HID 自带的 0x180A 服务里
        private val DEVICE_INFO_SERVICE_UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        private val FIRMWARE_REVISION_UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
        private val HARDWARE_REVISION_UUID = UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb")

        // 插件用到的全部特征值（服务 UUID 到特征值 UUID；⚠️ 必须写在上面这些 UUID 之后，companion 按顺序初始化），发现服务后缺任何一个都先清一次 GATT 缓存
        private val REQUIRED_CHARACTERISTICS = listOf(
            SERVICE_UUID to KEY_EVENT_CHARACTERISTIC_UUID,
            SERVICE_UUID to DISPLAY_CHARACTERISTIC_UUID,
            SERVICE_UUID to OTA_CHARACTERISTIC_UUID,
            DEVICE_INFO_SERVICE_UUID to FIRMWARE_REVISION_UUID,
            DEVICE_INFO_SERVICE_UUID to HARDWARE_REVISION_UUID,
        )

        // 按键事件字段 ID，与 firmware/src/ble.h 的 KEY_FIELD_CODE/KEY_FIELD_VALUE 数值对齐
        private const val FIELD_CODE = 1
        private const val FIELD_VALUE = 2

        private const val RECONNECT_POLL_INTERVAL_MS = 5_000L
        // 清完 GATT 缓存后等一会儿再重新发现服务，立即发现有可能还是拿到旧结果
        private const val GATT_REFRESH_DELAY_MS = 500L
        private const val PREFS_NAME = "ble_manager"
        private const val KEY_KNOB_MAC = "knob_mac"
    }
}
