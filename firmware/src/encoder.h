#pragma once

#include <stdint.h>

// 把 EC11 的 A/B 引脚配置成中断，按键引脚配置成输入。
// 在 setup() 里调用一次。
void encoder_init();

// 返回自上次调用以来旋钮转过的档位数
// （正数=顺时针，负数=逆时针），读完自动清零。
// 每次 loop() 调用一次。
int16_t encoder_get_diff();

// 做按键消抖和长按计时。每次 loop() 都要调用——
// 下面两个取事件的函数只有靠它才能被正确更新。
void encoder_poll_button();

// 每发生一次"短按"（按住时长小于 ENC_COMMIT_PROGRESS_START_MS 就松手）
// 就返回一次 true。取到之后标志位会被清掉，所以每次点击只会被上报一次。
// ⚠️ 按住时长落在 [ENC_COMMIT_PROGRESS_START_MS, ENC_LONG_PRESS_MS) 之间
// 松手（已经进入长按的视觉反馈区间但没坚持到底）不算短按，两个 take
// 函数都不会返回 true，安静放弃这次操作——不会退回成一次点击。
bool encoder_take_click();

// 和 encoder_take_click() 类似，但对应按住超过
// ENC_LONG_PRESS_MS 的长按。
bool encoder_take_long_press();

// 每发生一次"双击"（两次短按松手间隔在 ENC_DOUBLE_CLICK_WINDOW_MS 内）
// 就返回一次 true，取到之后标志位清掉。这个判定是无条件开启的：任何一次
// 短按松手后都会先等这个窗口过去、确认没有第二下才补报单击（见
// encoder_take_click()），所以单击本身也会多出最多这个窗口的延迟，是
// 全局的固定代价，不分页面。要不要响应双击（比如没有子项可切的页面）
// 由上层（手机插件）根据当前停靠点决定，固件这边不区分、也不知道。
bool encoder_take_double_click();

// 直接读一次按键当前是不是按着（不经过消抖/长短按判定）。给开机时
// "按住按键几秒钟触发清除配对信息"这种一次性检测用——那时候只关心
// "这一刻按没按着"，不需要 encoder_poll_button() 那一整套状态机。
bool encoder_is_button_pressed();
