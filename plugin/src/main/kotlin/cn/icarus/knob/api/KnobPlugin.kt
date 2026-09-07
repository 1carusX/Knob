package cn.icarus.knob.api

import android.content.Context

/**
 * 壳 ↔ 插件(DEX)通信契约。插件实现此接口，壳通过它驱动插件。
 *
 * ⚠️ 本文件必须同时存在于壳与插件工程，包名/类名/方法签名完全一致；
 * 改动需两侧同步，否则运行时签名不匹配。
 *
 * 按【功能域】分方法而非一功能一方法：新增功能只需在对应域内加一条路由
 * 分支，接口保持稳定 → 壳不用重签。
 *
 * 四个功能域：
 *  - onCommand：控制域（写操作）
 *  - onQuery  ：查询域（读操作）
 *  - onEvent  ：事件域（外部事件；UI 容器绑定/解绑也走这里）
 *  - onPush   ：推送域（壳在任意时刻主动推数据，不依赖某次调用）
 */
interface KnobPlugin {

    /** 插件初始化（context=壳 context，host=壳宿主回调） */
    fun init(context: Context, host: KnobHost)

    /**
     * 控制域：下发命令（写）。命令如 "init.all"、"seat.ventilating"、
     * "ac.temperature" 等，参数随命令约定。
     * @return 统一结果 Map；失败/未知命令返回 null
     *   成功: {"success":true, "code":0, "value":null, "message":"ok"}
     *   失败: {"success":false, "code":<错误码/null>, "value":null, "message":"说明"}
     */
    fun onCommand(command: String, params: Map<String, Any>): Map<String, Any>?

    /**
     * 查询域：读状态/信息（不改任何状态）。查询如 "perm.check"、"selfTest"、
     * "seat.ventilating"、"ac.temperature" 等。
     * @return 统一结果 Map（形状同 onCommand，查询结果放 value）；失败/未知查询返回 null
     */
    fun onQuery(query: String, params: Map<String, Any>): Map<String, Any>?

    /**
     * 事件域：外部事件。
     *  - "key"        旋钮/物理键/系统返回键的统一入口。
     *                 params: {code: Int, action: Int, value: Int?}
     *                 code=KeyEvent.KEYCODE_*，action=DOWN/UP。
     *                 ⚠️ value 只在旋钮长按（确认）时携带——旋钮转动只改自己
     *                 屏幕的本地状态、不再逐档通知手机，长按确认时才把最终值
     *                 发来，插件应直接用该值应用（写车控），不要在此之前按
     *                 增量调整。
     *                 ⚠️ 消费某键时按下与抬起要成对返回 true，只吃一半会让
     *                 系统按键状态错乱。
     *  - "ui.bind"    Activity 创建，壳把页面容器 {container: ViewGroup} 交给
     *                 插件，插件开始渲染并自管页面栈。
     *  - "ui.unbind"  Activity 销毁，插件必须断开对容器/View 树的持有。
     *                 ⚠️ Activity 反复创建销毁，不解绑会泄漏已销毁的 Activity，
     *                 且下次 bind 复用旧 View 时抛 "already has a parent"。
     * @return 是否消费了该事件
     */
    fun onEvent(event: String, params: Map<String, Any>): Boolean

    /**
     * 推送域：壳→插件的通用数据通道，任意时刻主动推（如 "state"、
     * "bluetooth"、"hardware"、"config"）。新增类型只需在插件内加一条
     * when(type) 分支，接口不变。
     */
    fun onPush(type: String, data: Map<String, Any>)

    /** 插件销毁 */
    fun onDestroy()
}
