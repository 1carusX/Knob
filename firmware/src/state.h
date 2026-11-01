#pragma once

// 屏幕要显示的状态：main.cpp 定义并维护（BLE 回调、旋钮输入改它们），
// ui/ 下的各个画面只读它们来决定画什么。
//
// 标 volatile 的那些会被 NimBLE 的连接/配对/数据回调（on_passkey/on_paired/
// on_disconnected/on_display_update）改，这些回调跑在 NimBLE 自己的后台任务
// 线程上，不是 loop() 那个线程，不标 volatile 的话 loop() 读到的可能是编译器
// 缓存的旧值，看起来就像"状态明明该变了、屏幕却没反应"。没标的只在 loop()
// 线程上读写（重绘和绘制回调也都在那里跑）。

#include <stdint.h>
#include "ble.h"

// 屏幕当前处在哪个阶段。除了"显示手机推来的数据"这个主状态，还要
// 覆盖配对码/配对成功提示/未连接这几种状态，所以单独做一个简单状态机，
// 不是直接把这些揉进 s_title/s_value 里、靠字符串规则区分。
enum ScreenState {
    SCREEN_DISCONNECTED, // 未连接：显示配对码（正在配对时）/ 设备名+"未配对"（从没配对过）/ "等待连接"（配过、暂时断连）
    SCREEN_PAIRED_TOAST, // 刚配对/连接成功，显示提示 1 秒
    SCREEN_NORMAL,       // 显示手机同步过来的数据
    SCREEN_OTA,          // 固件升级中：进度环 + 百分比，不响应旋钮操作，完成后重启
};
extern volatile ScreenState s_screen_state;

// 正在配对时，手机和固件两边应该显示同一个 6 位确认码；没有正在配对
// 时是 -1。已配对过的设备重连不会再走一遍配对流程，也就不会再有这个码
// （见 ble_on_passkey 的注释）。
extern volatile int32_t s_pending_passkey;

// 这次连接有没有真的走过配对码确认——onConfirmPassKey 触发过才是真配对，
// 已配对设备直接用保存的密钥重连不会走这一步。用来区分该显示"配对成功"
// 还是"连接成功"，断开时清零，保证每次连接周期都是从头判断，不会用上
// 一轮的结果。
extern volatile bool s_paired_via_confirm;

// 固件升级进度（0~100），升级完成（等着重启）时 s_ota_done = true。
extern volatile int s_ota_percent;
extern volatile bool s_ota_done;

// 当前停靠点用哪种画法——手机推来的 "layout" 字段直接决定，不是靠
// "有没有某个字段"去反推。ac_link/seat 是单数字布局（共用 s_label_value）；
// ac_split_main/ac_split_deputy 是双数字布局（共用 s_label_left/right，
// 哪边大、哪边可编辑由 layout 决定，位置固定：左=主驾，右=副驾）。
enum DisplayLayout {
    LAYOUT_AC_LINK,
    LAYOUT_AC_SPLIT_MAIN,
    LAYOUT_AC_SPLIT_DEPUTY,
    LAYOUT_SEAT,
    LAYOUT_NONE,
};

// 当前正在编辑的这一路数值——手机每次同步（切停靠点/长按确认后的
// 回执）会覆盖这几个字段；转动旋钮只改 s_value 这一个字段（不管当前是
// 单数字还是双数字布局，"正在被编辑的那个数字"永远是 s_value，双数字
// 布局下另一侧的参考数字放在 s_secondary_value，转动不会碰它），不碰
// title/layout/min/max，也不会发消息给手机。s_title 这个字符缓冲区不标
// volatile——strncpy 系操作跟 volatile 指针配合很别扭，这里的一致性靠
// s_screen_state/s_display_pending 的读写顺序保证（BLE 回调写完 title
// 才会翻转对应的标志位，loop() 先看到标志位翻转才会去读 title），够用，
// 不需要上更重的同步机制。
extern char s_title[DisplayData::MAX_STR_LEN + 1];
extern volatile DisplayLayout s_layout;
extern volatile int16_t s_value;           // 正在编辑的数字，转动直接改这个
extern volatile int16_t s_synced_value;    // s_value 上一次跟手机同步时的起点，用来判断有没有被转动过（脏/干净，决定空心/实心）
extern volatile int16_t s_secondary_value; // 双数字布局下另一侧的参考数字，只读，转动不碰它
extern volatile int16_t s_min;
extern volatile int16_t s_max;
extern volatile bool s_have_display_data; // 有没有收到过手机推来的真实数据

// 底部子页指示点用：sub_count<=1 表示当前停靠点不是复合停靠点，一个
// 点都不画；否则画 sub_count 个点，sub_index（0-based）那个是白色。
extern volatile int16_t s_sub_count;
extern volatile int16_t s_sub_index;

// 驾驶页（车速/油门/刹车，只读）。驾驶数据是插件单独推的一种消息（见
// on_display_update），跟当前停靠点无关，不管显示哪页都存最新一份。
// s_drive_have_speed = 最新一条带了车速（插件读不到车速时不带）。
extern volatile int16_t s_drive_speed;
extern volatile int16_t s_drive_throttle;
extern volatile int16_t s_drive_brake;
extern volatile bool s_drive_have_speed;

// 现在显示的是驾驶页（而不是停靠点）。什么时候进出见 main.cpp 的 update_drive()。
extern bool s_drive_showing;

// 长按提交按住期间（进度环在画）为 true，联动/分控的底部提示从"长按确认"
// 换成"继续按住"。只由 main.cpp 的 check_enter_commit_progress() 改。
extern bool s_commit_holding;
