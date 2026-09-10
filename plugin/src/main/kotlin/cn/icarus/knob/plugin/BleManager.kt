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

    private var gatt: BluetoothGatt? = null
    private var displayCharacteristic: BluetoothGattCharacteristic? = null
    private var keyEventCharacteristic: BluetoothGattCharacteristic? = null

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
                    // 提示系统走低延迟连接（配合固件短连接间隔）；然后先协商大 MTU
                    // 再发现服务——默认 ATT MTU 23 字节装不下带中文的显示 payload。
                    // onMtuChanged() 里才真正 discoverServices()，失败则退回默认 MTU。
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    logBoth("GATT 已连接，请求更大 MTU")
                    g.requestMtu(185)
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
                    notifyConnectionState(connected = false)
                    tryConnect()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            logBoth("MTU 协商结果=$mtu (status=$status)，开始发现服务")
            g.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE_UUID)
            if (service == null) {
                logBoth("没找到自定义服务（UUID 对不上，固件版本可能太旧）", isWarning = true)
                return
            }

            displayCharacteristic = service.getCharacteristic(DISPLAY_CHARACTERISTIC_UUID)
            logBoth(if (displayCharacteristic != null) "显示同步特征值已就绪" else "没找到显示同步特征值", isWarning = displayCharacteristic == null)

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
                subscribeKeyEvents(g, keyChar)
            }
            if (!waitingForCccd && displayCharacteristic != null) {
                notifyConnectionState(connected = true, device = g.device)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            logBoth(
                if (status == BluetoothGatt.GATT_SUCCESS) "按键事件通知订阅完成" else "按键事件通知订阅失败 status=$status",
                isWarning = status != BluetoothGatt.GATT_SUCCESS
            )
            // CCCD 写真正完成，此时通知"已连接"（触发 syncToKnob 写显示）才不会撞车
            if (displayCharacteristic != null) {
                notifyConnectionState(connected = true, device = g.device)
            }
        }

        // API 33+ 路径
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == KEY_EVENT_CHARACTERISTIC_UUID) handleKeyEventNotification(value)
        }

        // API < 33 路径——33+ 已由上面处理，这里直接跳过，避免同一通知处理两遍
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            if (characteristic.uuid == KEY_EVENT_CHARACTERISTIC_UUID) handleKeyEventNotification(characteristic.value)
        }
    }

    /**
     * 订阅按键事件：本地开通知回调 + 写 CCCD 通知外设"开始推送"，两步缺一不可。
     * @return CCCD 写是否真的发出去了——true 时调用方须等 onDescriptorWrite()
     *   才算完成；false（没找到特征值/描述符或写失败）不会有回调，不能傻等。
     */
    @SuppressLint("MissingPermission")
    private fun subscribeKeyEvents(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        if (!g.setCharacteristicNotification(characteristic, true)) {
            logBoth("setCharacteristicNotification 失败，按键事件收不到", isWarning = true)
            return false
        }
        val cccd = characteristic.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            logBoth("按键事件特征值没有 CCCD 描述符，收不到通知", isWarning = true)
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
        logBoth(if (ok) "已发起按键事件通知订阅" else "订阅按键事件通知失败", isWarning = !ok)
        return ok
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
     * key 名，字段意义由插件与固件约定。@return 是否成功发出写请求（异步，
     * 不代表对方已收到；固件侧以串口日志为准）。
     */
    @SuppressLint("MissingPermission")
    fun write(data: Map<String, Any>): Boolean {
        val g = gatt ?: run {
            logBoth("write() 失败：GATT 还没连上", isWarning = true)
            return false
        }
        val char = displayCharacteristic ?: run {
            logBoth("write() 失败：显示同步特征值还没发现（服务发现可能还没完成）", isWarning = true)
            return false
        }
        val payload = encodeDisplayData(data)
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(char, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            char.value = payload
            @Suppress("DEPRECATION")
            g.writeCharacteristic(char)
        }
        // ⚠️ 特征值都在却仍被拒，通常是"同一时刻只能一个 GATT 写在飞"，跟另一个
        // 未完成的写撞车（如刚连接时的 CCCD 订阅写），补一条日志方便定位。
        if (!ok) {
            logBoth("write() 失败：writeCharacteristic 请求被拒绝（可能跟另一个 GATT 操作撞车了）", isWarning = true)
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

    companion object {
        private const val TAG = "BleManager"

        // 与固件 build_device_name() 命名一致；下方 UUID/字段 ID 也与 firmware/src/ble.cpp|ble.h 对齐
        private const val DEVICE_NAME_PREFIX = "Knob-"
        private val SERVICE_UUID = UUID.fromString("29afa70d-2312-4f04-9b36-fe9298284756")
        private val KEY_EVENT_CHARACTERISTIC_UUID = UUID.fromString("182f303d-ea98-4ee4-839e-5599aaf3f29d")
        private val DISPLAY_CHARACTERISTIC_UUID = UUID.fromString("5f774e59-5079-4e69-8e5d-de0ee1220d3f")
        // 标准 CCCD 描述符：订阅 NOTIFY 靠写它
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // 按键事件字段 ID，与 firmware/src/ble.h 的 KEY_FIELD_CODE/KEY_FIELD_VALUE 数值对齐
        private const val FIELD_CODE = 1
        private const val FIELD_VALUE = 2

        private const val RECONNECT_POLL_INTERVAL_MS = 5_000L
        private const val PREFS_NAME = "ble_manager"
        private const val KEY_KNOB_MAC = "knob_mac"
    }
}
