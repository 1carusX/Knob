// 驾驶页（车速/油门/刹车，只读）。不是停靠点，是停靠点画面闲置一会儿之后的
// 待机画面，什么时候进出见 main.cpp 的 update_drive()。

#include "ui_internal.h"
#include <stdio.h>

// 跟温度环一样 270°、缺口朝下，从正上方 270° 分开——往右顺时针是
// 油门、往左逆时针是刹车，各 0~100% 占 135°，走到缺口两侧。每 5% 一根刻度、
// 整 25% 长一点；踩下去的部分着色，当前值处一个同色亮点，0% 不画亮点。
// 正上方一根 0% 刻线（同座椅"关"那根）。没有车速数据时环全灰。
static float pedal_angle(int pct, bool throttle) { return throttle ? 270.0f + pct * 1.35f : 270.0f - pct * 1.35f; }

static void draw_pedal_half(lv_layer_t *layer, int pct, bool throttle, lv_color_t color) {
    if (pct < 0) pct = 0;
    if (pct > 100) pct = 100;
    for (int v = 5; v <= 100; v += 5) {
        bool major = v % 25 == 0;
        lv_color_t c = (pct > 0 && v <= pct) ? color : color_tick_off();
        draw_tick(layer, major ? 100 : 104, 113, pedal_angle(v, throttle), c, major ? 3 : 2);
    }
    if (pct <= 0) return;
    int32_t x, y;
    polar(DIAL_MARKER_R, pedal_angle(pct, throttle), &x, &y);
    draw_dot(layer, x, y, 18, color, LV_OPA_30); // 光晕
    draw_dot(layer, x, y, 9, color_text());
}

static void draw_drive_dial(lv_layer_t *layer) {
    bool have = s_drive_have_speed;
    draw_pedal_half(layer, have ? s_drive_brake : 0, false, color_red());
    draw_pedal_half(layer, have ? s_drive_throttle : 0, true, color_ok());
    draw_tick(layer, 99, 117, 270, color_text2(), 2);
}

// 中间只有车速（不带单位），环的两头是刹车（左下）/ 油门（右下）的
// 百分比，数字 22px + "%" 13px 共用基线、整体居中在 r 98、118° / 62° 处，
// 正好在缺口两侧。踩着时用各自的颜色，0% 变暗。没有车速数据时显示 "--"、
// 两头读数不显示。
constexpr int32_t DRIVE_NUMBER_BASE = 157; // 车速 92px 数字的基线（数字竖直居中在圆心）
constexpr int32_t PEDAL_BASE_DY = 9;       // 两头读数的基线 = 读数中心 y + 这个值

static void place_pedal(lv_obj_t *num, lv_obj_t *pct_sign, int pct, float deg, lv_color_t color) {
    if (pct < 0) pct = 0;
    if (pct > 100) pct = 100;
    int32_t cx, cy;
    polar(98, deg, &cx, &cy);
    lv_color_t c = pct > 0 ? color : color_text3();
    char buf[8];
    snprintf(buf, sizeof(buf), "%d", pct);
    place_digits(num, buf, &font_num_22, c, 0, 0, cy + PEDAL_BASE_DY);
    place_digits(pct_sign, "%", &font_num_13, c, 0, 0, cy + PEDAL_BASE_DY);
    // [数字][%] 作为一组，整体水平居中在 cx
    lv_obj_update_layout(num);
    int32_t wn = lv_obj_get_width(num), wp = lv_obj_get_width(pct_sign);
    int32_t left = cx - DIAL_CX - (wn + wp) / 2;
    lv_obj_set_x(num, left + wn / 2);
    lv_obj_set_x(pct_sign, left + wn + wp / 2);
}

void redraw_drive() {
    reset_screen(draw_drive_dial);
    if (!s_drive_have_speed) {
        place_digits(s_label_value, "--", &font_num_92, color_text3(), 17, 0, DRIVE_NUMBER_BASE);
        return;
    }
    char buf[8];
    snprintf(buf, sizeof(buf), "%d", s_drive_speed);
    place_digits(s_label_value, buf, &font_num_92, color_text(), -1, 0, DRIVE_NUMBER_BASE);
    place_pedal(s_label_left, s_label_sub_left, s_drive_brake, 118, color_red());
    place_pedal(s_label_right, s_label_sub_right, s_drive_throttle, 62, color_ok());
}
