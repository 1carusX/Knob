#pragma once

#include <stdint.h>

// Android KeyEvent.KEYCODE_* 常量（不依赖 Android SDK，这几个数值是长期
// 稳定不变的 AOSP 框架常量，直接抄过来用）。DPAD 转动现在完全本地处理
// （见 main.cpp），不再通知手机，所以这里只剩 Tab（双击=切下一个停靠点）
// 和 Enter（长按=确认，带上最终值）两个真正会发出去的键。
constexpr int16_t KEYCODE_TAB = 61;
constexpr int16_t KEYCODE_ENTER = 66;

// 单击：在"复合停靠点"内部切换子项（比如座椅停靠点内部切主驾/副驾/
// 左后/右后、空调分控停靠点内部切主驾/副驾），跟上面两个不一样——不
// 对应任何真实会被系统按键分发的 Android 常量（旋钮早就不走 HID/无障碍
// 那条路了，上面两个沿用 AOSP 数值只是历史习惯），随便挑了一个没跟它们
// 撞的值。固件无条件识别单击并发出这个键码，不知道当前停靠点是不是
// 复合停靠点——要不要响应由手机插件根据 currentPageId 决定。
//
// ⚠️ 单击/双击对应的键码是反过来分配的（单击发这个 CycleSub，双击才发
// 上面的 Tab）：双击判定有窗口延迟，手慢了没跟上会被拆成一次单击，此时
// 代价应该是"原地切一次子项"（还在同一个功能里，无伤大雅），而不是
// "跳到下一个完全不相关的停靠点"（体验上突兀得多）。见 main.cpp 里
// loop() 对应的注释。
constexpr int16_t KEYCODE_CYCLE_SUB = 67;

// 初始化 BLE：建 GATT server、配置配对方式（数字比对）、加两个自定义
// 特征值（按键事件通知 + 显示同步可写），开始广播。在 setup() 里调用一次。
//
// 不走标准 BLE HID 键盘 profile——之前是 HID + 手机侧无障碍服务拦截系统
// 按键，但车机的定制系统没有暴露标准的无障碍设置入口（跳转会直接崩
// ActivityNotFoundException），这条路走不通。现在旋钮的每个动作都直接
// 通过自定义 GATT 特征值通知给手机，壳收到通知后直接转发给插件，不再
// 经过系统按键分发/无障碍服务这一层，也就不需要这个权限了。
void ble_init();

// 当前是不是已经跟主机配对并且加密连接建立完成——发事件之前应该先检查
// 这个，没配对时发了也没人收。
bool ble_is_paired();

// 按键事件通知的字段 ID——跟手机那边 BleManager.kt 里同名常量对齐（两边
// 各写一份、数值对齐，就跟 KEYCODE_* 这些常量的维护方式一样）。用数字
// ID 代替字段名文本，字节量比 DisplayData 那种"keyLen+key名"的编码小
// 得多，这条通道每次按键都要发，值得省这个字节。
constexpr uint8_t KEY_FIELD_CODE = 1;
constexpr uint8_t KEY_FIELD_VALUE = 2;

// 通知手机"发生了一次按键"，keycode 用上面 KEYCODE_* 常量。没配对时
// 调用不会有任何效果。
void ble_send_key(int16_t keycode);

// 跟 ble_send_key 一样，但额外带上一个数值——目前只有长按（确认）用，
// 把旋钮本地编辑到的最终值一起带给手机，不用手机在这之前每转一档就
// 跟着同步一遍。
void ble_send_key_with_value(int16_t keycode, int16_t value);

// 手机推来的显示数据，通用键值对——这一层（跟手机那边的插件对应）自己
// 按约定好的 key 名取值，壳（BleManager/KnobHostImpl）完全不理解这些
// key 的含义，只负责把 Map 编码成字节流搬过来，所以以后要加/改字段，
// 只需要同时改插件（发什么 key）和这里（认哪些 key），不用碰壳。
struct DisplayData {
    static const int MAX_ENTRIES = 8;
    static const int MAX_KEY_LEN = 15;
    static const int MAX_STR_LEN = 60;

    struct Entry {
        char key[MAX_KEY_LEN + 1];
        uint8_t type; // 0=int16, 1=string, 2=bool
        int16_t intValue;
        char strValue[MAX_STR_LEN + 1];
        bool boolValue;
    };

    Entry entries[MAX_ENTRIES];
    int count = 0;

    // 找不到对应 key（或类型不对）就返回 defaultValue，调用方不用先判断
    // 有没有这个字段。
    int16_t getInt(const char *key, int16_t defaultValue = 0) const;
    const char *getString(const char *key, const char *defaultValue = "") const;
    bool getBool(const char *key, bool defaultValue = false) const;

    // 只看这次推送里有没有这个字段（不管类型），不关心值是多少。用来
    // 区分"这次推送是不是带了某个可选字段"——比如亮度推送可能不带
    // title，不能直接拿 getString("title","") 是不是空串来判断（正常
    // 页面数据的 title 理论上也可能是空串）。
    bool has(const char *key) const;
};

// 注册"手机同步了显示数据"回调：手机往自定义特征值写数据时触发。
// data 指向的是内部一块复用的缓冲区，只在回调期间有效，getString()
// 返回的指针也是——需要长期保留的话必须自己复制一份，不能只存指针。
void ble_on_display_update(void (*callback)(const DisplayData &data));

// ==================== 配对/连接状态回调（main.cpp 用来同步屏幕显示） ====================

// 正在配对时，手机和固件两边应该显示同一个 6 位确认码，注册这个回调
// 把这个码显示在屏幕上，方便用户核对屏幕上和手机上是不是同一个数字。
// 只有真正走一次新配对流程时才会触发；已配对过的设备重连不会再走
// 这一步（直接用之前保存的密钥重新加密，见 ble_on_paired）。
void ble_on_passkey(void (*callback)(uint32_t passkey));

// 加密连接建立成功时触发一次——不管这次是刚刚走完配对流程，还是用
// 已保存的密钥重新连接（重连不会再触发 ble_on_passkey），main.cpp
// 用它切到"配对成功"提示。
void ble_on_paired(void (*callback)());

// 断开连接时触发（不管之前有没有配对成功过），main.cpp 用它把屏幕
// 切回"未连接"状态。
void ble_on_disconnected(void (*callback)());

// 这块 knob 广播出去的蓝牙名字（"Knob-XXXX" 格式，XXXX 是芯片蓝牙 MAC
// 后 2 字节，见 ble.cpp 的 build_device_name）。ble_init() 调用之后才
// 有效——main.cpp 在"从没配对过"这种状态下用它提示"去手机蓝牙设置里
// 找这个名字配对"（同时配好几个 knob 时，只有这个提示才能分清楚具体
// 是哪一个还没配对）。
const char *ble_get_device_name();

// 这块 knob 有没有跟任何设备配对成功过——这是存在 ESP32 自己 flash 里
// 的本地状态（NimBLE 的 bond store），不需要问手机就能查，主要用来
// 区分"从没配对过"（要提示用户去配对）和"配对过、只是暂时断连"（等
// 它自动重连就行，不用管），main.cpp 靠这个决定未连接时该显示什么。
bool ble_has_any_bond();

// 清空 ESP32 自己保存的所有配对记录（不影响手机那边——手机的配对
// 记录得去手机蓝牙设置里单独删）。主要是给开发调试用：手机端解绑
// 之后 ESP32 这边并不会跟着清掉记录（两边各自独立存储，没有自动
// 同步机制），如果想真的测试"从没配对过"这条路径，得手动调这个——
// 见 main.cpp 里长按 BOOT 键触发的逻辑。
void ble_clear_all_bonds();

// ==================== 固件 OTA（手机经 BLE 推新固件过来） ====================
//
// 协议在 ble.cpp 的 OTA 特征值上（kOtaCharUUID，WRITE+WRITE_ENC+NOTIFY），
// 收到的数据直接写进另一个 app 分区，校验通过才切换启动分区——中途断线
// 或者校验失败都不影响当前在跑的固件。main.cpp 只负责显示进度和到点重启。

enum OtaEvent : uint8_t {
    OTA_EVENT_STARTED,  // 收到 BEGIN，开始接收（received=0）
    OTA_EVENT_PROGRESS, // 每收到一块数据触发一次
    OTA_EVENT_DONE,     // 校验通过、已切换启动分区，等着重启
    OTA_EVENT_FAILED,   // 中止：手机发了 ABORT、校验失败、写 flash 失败或者断线
};

// 注册 OTA 事件回调。跟其它回调一样跑在 NimBLE 后台任务线程上，只能改
// 状态变量，不能直接碰 LVGL。收到 OTA_EVENT_DONE 后由调用方负责重启
// （留一点时间让"完成"通知发出去、屏幕显示出来）。
void ble_on_ota(void (*callback)(OtaEvent event, uint32_t received, uint32_t total));

// 在 loop() 里反复调用：升级开始后太久没收到数据就放弃（触发 OTA_EVENT_FAILED），
// 免得手机那边出错又没发 ABORT 时屏幕一直停在升级画面。
void ble_ota_check_timeout();
