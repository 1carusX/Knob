#pragma once

// 圆屏界面的配色和字体，规格见 docs/设计/旋钮圆屏UI/README.md。

#include <lvgl.h>

// 字体都用 lv_font_conv 1.5.3 生成（4bpp、不压缩、不勾 --lcd——lv_conf.h
// 没开 LV_USE_FONT_SUBPX），只嵌入上屏用到的字符，生成参数见各文件开头的
// Opts，字号对应设计稿的规格。
//
// 中文：Noto Sans SC Medium，13/16/20 三个字号共用同一份字表。⚠️ 新增上屏
// 中文必须把字补进三份的 --symbols 重新生成，缺的字直接显示不出来。字表
// 外的字符（"..." 之类）靠 --lv-fallback lv_font_montserrat_18 显示。
LV_FONT_DECLARE(font_zh_13); // 提示（长按确认…）、分控数字下的主驾/副驾
LV_FONT_DECLARE(font_zh_16); // 标题、未配对、座椅的加热/通风
LV_FONT_DECLARE(font_zh_20); // 状态画面的主文字（等待连接、配对成功…）
LV_FONT_DECLARE(font_zh_28); // 座椅页的座位名，只有"主驾副左后右"（字表外回退到 font_zh_20）
LV_FONT_DECLARE(font_zh_44); // 只有一个"关"字：座椅 0 档放在大数字的位置
// 数字：Barlow Semi Condensed Medium（半窄体，分控两个数字才放得下）。
LV_FONT_DECLARE(font_num_92); // 大数字：温度、座椅档位、升级百分比，"0123456789-"
LV_FONT_DECLARE(font_num_80); // 分控正在编辑的那一侧
LV_FONT_DECLARE(font_num_44); // 分控参考侧、配对码
LV_FONT_DECLARE(font_num_34); // 未配对时的设备名，符号集 "Knob-0123456789ABCDEF"（见 ble.cpp 的 build_device_name）
LV_FONT_DECLARE(font_num_28); // 大数字后面的单位，只有 "%°"
LV_FONT_DECLARE(font_num_22); // 驾驶页两头的油门/刹车百分比，"0123456789"（等宽数字，同 92/80/44）
LV_FONT_DECLARE(font_num_13); // 驾驶页百分比后面的 "%"，只有这一个字符

// 用函数而不是全局 const 变量构造颜色——LV_COLOR_MAKE 在文件作用域展开出来
// 能不能当全局初始值用，这份代码库里没有先例，干脆每次用的时候现算，开销
// 可以忽略。
static inline lv_color_t color_bg() { return LV_COLOR_MAKE(0x0A, 0x0C, 0x0F); }
static inline lv_color_t color_text() { return LV_COLOR_MAKE(0xF2, 0xF4, 0xF7); }     // 主文字、当前值亮点
static inline lv_color_t color_text2() { return LV_COLOR_MAKE(0x8A, 0x93, 0xA0); }    // 标题、参考数字、次要文字
static inline lv_color_t color_text3() { return LV_COLOR_MAKE(0x4A, 0x51, 0x5C); }    // 未选中的座位、虚点
static inline lv_color_t color_tick_off() { return LV_COLOR_MAKE(0x2B, 0x31, 0x3A); } // 还没走到的刻度
static inline lv_color_t color_track() { return LV_COLOR_MAKE(0x23, 0x28, 0x30); }    // 进度环/座椅分段的底色
// 琥珀色只表示"还没提交"：转动过、还没长按确认的数字，以及长按提交进度环
static inline lv_color_t color_pending() { return LV_COLOR_MAKE(0xF7, 0xC9, 0x48); }
static inline lv_color_t color_ok() { return LV_COLOR_MAKE(0x34, 0xD3, 0x99); } // 连接成功、固件升级
static inline lv_color_t color_blue() { return LV_COLOR_MAKE(0x3B, 0x82, 0xF6); }
static inline lv_color_t color_cyan() { return LV_COLOR_MAKE(0x22, 0xD3, 0xEE); }
static inline lv_color_t color_coral() { return LV_COLOR_MAKE(0xFF, 0x7A, 0x45); }
static inline lv_color_t color_red() { return LV_COLOR_MAKE(0xF0, 0x41, 0x4D); }
