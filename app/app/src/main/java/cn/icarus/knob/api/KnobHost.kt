package cn.icarus.knob.api

/**
 * 壳宿主回调：壳实现并传给插件，插件通过它反向调用壳。
 *
 * ⚠️ 必须同时存在于壳与插件工程（包名/签名一致），改动需两侧同步。
 *
 * 只剩日志这一个能力——BLE 连接（扫描/配对/GATT 读写）已经整体移进插件
 * 自己的 BleManager（见 plugin/.../plugin/BleManager.kt），不再需要经壳
 * 转发：认哪个设备、怎么编解码，都是插件+固件这一对的私有约定，壳没有
 * 理由插手，也不该因为这些细节的变动被迫跟着重签。
 */
interface KnobHost {
    /** 插件打日志，转发给壳的日志窗口显示（车机无 adb，这是唯一观测手段） */
    fun log(message: String)
}
