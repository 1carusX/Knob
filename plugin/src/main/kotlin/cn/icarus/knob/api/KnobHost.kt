package cn.icarus.knob.api

/**
 * 壳宿主回调：壳实现并传给插件，插件通过它反向调用壳。
 *
 * ⚠️ 必须同时存在于壳与插件工程（包名/签名一致），改动需两侧同步。
 */
interface KnobHost {
    /** 插件打日志，转发给壳的日志窗口显示 */
    fun log(message: String)

    /**
     * 插件请求壳把数据推送到 knob（屏显/指令），经 BLE 下发（见 BleManager）。
     * @param data 键值数据——字段意义由插件与固件约定，壳不理解、原样搬运
     */
    fun pushToKnob(data: Map<String, Any>)
}
