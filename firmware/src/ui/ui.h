#pragma once

// 圆屏界面对外（main.cpp）的入口。画什么全看 state.h 里的状态：main.cpp 改完
// 状态调 redraw()，按 s_screen_state 等选一个画面重画。
//
// 文件分工（ui/ 目录）：
//   ui.cpp           控件创建、摆文字的小工具、redraw() 分发到各画面
//   dial.cpp         环/刻度/图标所在的绘制层：画图小工具、转圈、长按进度环
//   page_status.cpp  系统画面：配对码、未配对、等待连接、配对/连接成功、等待数据、固件升级
//   page_ac.cpp      空调联动 / 分控
//   page_seat.cpp    座椅
//   page_drive.cpp   驾驶页（车速/油门/刹车）
// 各画面之间共用的声明在 ui_internal.h。
//
// ⚠️ LVGL 不是线程安全的，下面这些只能在 loop() 线程上调（BLE 回调里只改
// 状态、置重绘标志，见 main.cpp 的 s_needs_redraw）。

#include <lvgl.h>
#include "theme.h"

// 创建所有控件，只在 setup() 里调用一次——具体显示什么、摆在哪交给
// redraw()，这里不写死初始文案和位置。
void build_ui();

// 按当前状态重画整个画面（不碰长按进度环，见 set_overlay）。
void redraw();

// 长按提交（琥珀）/ BOOT 清配对（红）的整圈进度环，叠加在当前画面最外圈，
// percent = -1 收起。跟画面分开存：redraw() 不碰它，所以按住期间不管手机
// 回执触发几次 redraw()，进度环都不会被冲掉。
void set_overlay(int percent, lv_color_t color);

// 等待画面的转圈样式。firmware/simulate 渲染两种对比时会改 s_spinner_style，
// 再把 s_spinner_on 置 false 让下一次 redraw() 从头开始转。
enum SpinnerStyle {
    SPINNER_CLASSIC,    // Material 经典款（Advance）：5.4 秒 4 次头追尾，弧长 20°~270° 快速伸缩
    SPINNER_EXPRESSIVE, // Material 3 Expressive（Retreat）：6 秒慢慢伸缩 10%~87% 圈，每 1.5 秒加速转 90°
};
extern SpinnerStyle s_spinner_style;
extern bool s_spinner_on;
