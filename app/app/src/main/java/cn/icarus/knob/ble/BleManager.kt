package cn.icarus.knob.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import cn.icarus.knob.plugin.PluginLoader
import cn.icarus.knob.util.LogSink
import java.util.UUID

/**
 * 手机 ⇄ ESP32 knob 的唯一 BLE 通道（GATT client）。
 *
 * 壳只做字节搬运、不理解字段含义：旋钮按键经自定义特征值 NOTIFY 上报，显示
 * 数据经另一个 WRITE 特征值下发（编码见 [write]/[encodeDisplayData]，格式与
 * firmware/src/ble.cpp 对齐）；字段意义由插件与固件两端约定，加字段只改两端。
 *
 * ⚠️ 坑（历史）：早期用 BLE HID 键盘 + 无障碍服务截系统按键，车机定制系统
 * 没有无障碍设置入口（跳转崩溃），弃用，改 GATT 直传插件。
 * ⚠️ 坑：这台车机蓝牙栈不按标准 AOSP 行为来（重连不发 ACTION_ACL_CONNECTED
 * 广播、autoConnect=true 会触发额外配对），故重连靠定时轮询 + 直连。
 */
object BleManager {
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

    private var gatt: BluetoothGatt? = null
    private var displayCharacteristic: BluetoothGattCharacteristic? = null
    private var keyEventCharacteristic: BluetoothGattCharacteristic? = null

    // 车机无 adb，日志必须进 App 内滚动窗口才看得见；Log.x() 只是习惯
    private fun logBoth(msg: String, isWarning: Boolean = false) {
        if (isWarning) Log.w(TAG, msg) else Log.i(TAG, msg)
        LogSink.append("[BleManager] $msg")
    }

    /**
     * API 31+ 的 BLUETOOTH_CONNECT 是运行时权限，没拿到时碰蓝牙 API 会直接抛
     * SecurityException 崩掉——所有会碰蓝牙的入口统一先过这道检查。
     */
    fun hasBluetoothPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true // API < 31 无需运行时申请
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** GATT 是否已连接（不保证特征值已发现完） */
    fun isConnected(): Boolean = gatt != null

    private const val RECONNECT_POLL_INTERVAL_MS = 5_000L
    private val pollHandler = Handler(Looper.getMainLooper())
    private var pollingStarted = false

    // ⚠️ 曾想用 ACTION_ACL_CONNECTED 广播驱动重连，实测这台车机不发该广播
    // （系统蓝牙设置显示已连接，广播却收不到），不值得继续折腾，改定时轮询。
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isConnected()) init(appContext)
            pollHandler.postDelayed(this, RECONNECT_POLL_INTERVAL_MS)
        }
    }

    private lateinit var appContext: Context

    /** 启动重连轮询，进程生命周期内调一次（KnobApp.onCreate）。首次立即执行，不等一个轮询周期 */
    fun startReconnectPolling(context: Context) {
        if (pollingStarted) return
        pollingStarted = true
        appContext = context.applicationContext
        pollHandler.post(pollRunnable)
    }

    /**
     * 找已配对的 knob（名字以 "Knob-" 开头）并发起 GATT 连接。
     * 已连接/正在连接时重复调用直接跳过；因"当时还没配对"而失败可放心再调重试。
     */
    @SuppressLint("MissingPermission")
    fun init(context: Context) {
        if (!hasBluetoothPermission(context)) {
            logBoth("init() 中止：还没拿到 BLUETOOTH_CONNECT 权限", isWarning = true)
            return
        }
        if (gatt != null) {
            logBoth("init() 跳过：已经连接/正在连接中")
            return
        }
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val knob = adapter?.bondedDevices?.firstOrNull { it.name?.startsWith(DEVICE_NAME_PREFIX) == true }
        if (knob == null) {
            logBoth("未找到已配对的 Knob 设备（名字以 \"$DEVICE_NAME_PREFIX\" 开头），先去蓝牙设置配对，配对后可以重新触发一次 init()", isWarning = true)
            return
        }
        logBoth("找到已配对设备 ${knob.name}（bondState=${knob.bondState}），发起 GATT 连接")
        // ⚠️ 用 autoConnect=false 直连：autoConnect=true 在这台车机上会触发一次
        // 独立的全新配对（弹跟系统配对不一样的 6 位数），不复用已有连接/绑定。
        gatt = knob.connectGatt(context, /* autoConnect = */ false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // 提示系统走低延迟连接（配合固件短连接间隔）；然后先协商大 MTU
                    // 再发现服务——默认 ATT MTU 23 字节装不下带中文的显示 payload。
                    // onMtuChanged() 里才真正 discoverServices()，失败则退回默认 MTU。
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    logBoth("GATT 已连接，请求更大 MTU")
                    g.requestMtu(185)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    // autoConnect=false 断开不会自动重连：关掉 GATT、清状态，
                    // 下次 init() 才会重连。断线即刻重试一次（knob 多半已在
                    // 重新广播），失败再由轮询兜底。
                    logBoth("GATT 断开（status=$status），已停止", isWarning = true)
                    g.close()
                    gatt = null
                    displayCharacteristic = null
                    keyEventCharacteristic = null
                    notifyPluginBluetoothState(connected = false)
                    init(appContext)
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
            // （waitingForCccd=true）就不能就地通知插件"已连接"——那会触发插件的
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
                notifyPluginBluetoothState(connected = true, device = g.device)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            logBoth(
                if (status == BluetoothGatt.GATT_SUCCESS) "按键事件通知订阅完成" else "按键事件通知订阅失败 status=$status",
                isWarning = status != BluetoothGatt.GATT_SUCCESS
            )
            // CCCD 写真正完成，此时通知插件"已连接"（触发 syncToKnob 写显示）才不会撞车
            if (displayCharacteristic != null) {
                notifyPluginBluetoothState(connected = true, device = g.device)
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
    // 插件的按键处理会碰 View（切页面 removeAllViews/addView），不切主线程会抛
    // CalledFromWrongThreadException 且被 onEvent 的 try/catch 吞掉，表现为
    // "插件未处理"+页面清空但没画上新内容（残留空白）。
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
     * 按下+抬起都转发（与当年无障碍转发系统按键的语义一致，只吃一半会让插件
     * 内部按键状态错乱）；插件不处理时没有壳兜底——不再走系统按键分发。
     */
    private fun handleKeyEventNotification(value: ByteArray) {
        val fields = decodeIntFields(value)
        val code = fields[FIELD_CODE] ?: run {
            logBoth("按键通知缺 code 字段（${value.size} 字节）", isWarning = true)
            return
        }
        val knobValue = fields[FIELD_VALUE]
        logBoth("收到按键事件 code=$code value=$knobValue")
        mainHandler.post { dispatchKeyToPlugin(code, knobValue) }
    }

    private fun dispatchKeyToPlugin(code: Int, value: Int?) {
        val params = { action: Int ->
            if (value != null) mapOf("code" to code, "action" to action, "value" to value)
            else mapOf("code" to code, "action" to action)
        }
        PluginLoader.current?.onEvent("key", params(KeyEvent.ACTION_DOWN))
        val consumed = PluginLoader.current?.onEvent("key", params(KeyEvent.ACTION_UP)) ?: false
        logBoth(if (consumed) "  → 插件已处理" else "  → 插件未处理")
    }

    /**
     * 通知插件蓝牙连接状态（onPush("bluetooth")）。与按键通知一样切回主线程再
     * 转发——onPush 目前不碰 View，但统一走这条路更保险，新增回调不用再想一遍
     * 线程安全。
     */
    @SuppressLint("MissingPermission")
    private fun notifyPluginBluetoothState(connected: Boolean, device: BluetoothDevice? = null) {
        val data = if (connected && device != null) {
            mapOf("connected" to true, "name" to (device.name ?: ""), "mac" to device.address)
        } else {
            mapOf("connected" to connected)
        }
        mainHandler.post { PluginLoader.current?.onPush("bluetooth", data) }
    }

    /**
     * 把插件给的 Map 编码成字节流写给 knob——只认值类型（Int/String/Boolean），
     * 不认 key 名。@return 是否成功发出写请求（异步，不代表对方已收到；固件侧
     * 以串口日志为准）。
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
        // 未完成的写撞车（如刚连接时的 CCCD 订阅写）。原无此日志，排查时只能看到
        // KnobHostImpl 笼统的"GATT 未就绪"，补一条方便定位。
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
}
