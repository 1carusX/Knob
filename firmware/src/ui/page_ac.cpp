// 空调两个停靠点：联动（一个温度，270° 刻度环）和分控（主驾/副驾两个温度，
// 左右两个半环）。

#include "ui_internal.h"
#include <stdio.h>

// 蓝 -> 青 -> 珊瑚 -> 红，四段色标均匀分布在 0..1 上：蓝/青读"冷"，珊瑚/红
// 读"热"，单调过渡。空调温度低温蓝青、高温珊瑚红；刻度环上每根刻度按它
// 自己代表的温度取色，所以走过的那一段本身就是一条冷暖渐变。
static lv_color_t gradient_color(float value, float vmin, float vmax) {
    const lv_color_t stops[4] = {color_blue(), color_cyan(), color_coral(), color_red()};
    if (vmax <= vmin) return stops[0];
    float t = (value - vmin) / (vmax - vmin); // 0..1
    if (t < 0.0f) t = 0.0f;
    if (t > 1.0f) t = 1.0f;
    float seg = t * 3.0f; // 落在四段里的哪一段：0..3
    int idx = (int)seg;
    if (idx > 2) idx = 2;
    float frac = seg - idx;
    // lv_color_mix(c1, c2, mix)：mix=0 时是纯 c2，mix=255 时是纯 c1。
    return lv_color_mix(stops[idx + 1], stops[idx], (uint8_t)(frac * 255));
}

// ---------- 联动 ----------

// 135° 起顺时针扫 270°（缺口朝下）。范围不大（空调 17~33）时每 0.5 度
// 一根刻度、整数度的长一点；走过的刻度按自己的温度取冷暖色，没走到的暗灰。
static float temp_angle(float v) { return 135.0f + (v - s_min) * 270.0f / (s_max - s_min); }

static void draw_temp_dial(lv_layer_t *layer) {
    if (s_max <= s_min) return;
    int per_unit = (s_max - s_min) <= 40 ? 2 : 1;
    int n = (s_max - s_min) * per_unit;
    for (int i = 0; i <= n; i++) {
        float v = s_min + (float)i / per_unit;
        bool major = (i % per_unit) == 0;
        lv_color_t c = v <= s_value ? gradient_color(v, s_min, s_max) : color_tick_off();
        draw_tick(layer, major ? 101 : 106, 113, temp_angle(v), c, major ? 3 : 2);
    }
    int32_t x, y;
    if (s_value != s_synced_value) {
        // 转动过还没提交：原值（手机上次同步的起点）留一个空心点
        polar(DIAL_MARKER_R, temp_angle(s_synced_value), &x, &y);
        draw_dot(layer, x, y, 9, color_text2(), LV_OPA_COVER, 2);
    }
    polar(DIAL_MARKER_R, temp_angle(s_value), &x, &y);
    draw_dot(layer, x, y, 18, gradient_color(s_value, s_min, s_max), LV_OPA_30); // 光晕
    draw_dot(layer, x, y, 9, color_text());
}

void redraw_ac_link(bool dirty) {
    reset_screen(draw_temp_dial);
    place_title(s_title);
    place_big_number(s_value, dirty ? color_pending() : color_text(), "°");
    place_commit_hint(dirty);
}

// ---------- 分控 ----------

// 分控两组数字中间那道竖线的屏幕 x 坐标（随两边数字宽度变，由 redraw_ac_split 算）
static int32_t s_split_divider_x = 0;

// 左半环 135°→260° 是主驾、右半环 45°→-80° 是副驾，左右镜像，两边
// 都是越热越靠上。右半环升温是逆时针走，所以编辑副驾时旋钮也反过来拧
// （逆时针升温，见 main.cpp 的 loop() 里处理 encoder_get_diff() 的地方），亮点
// 始终跟着手转的方向走。
// 编辑侧跟联动一样画内侧亮点（和没提交时的空心原值点），参考侧（不在编辑
// 的那一侧）刻度整体压暗、只画一个小灰点。
static float half_angle(float v, bool left) {
    float t = (v - s_min) / (float)(s_max - s_min);
    return left ? 135.0f + t * 125.0f : 45.0f - t * 125.0f;
}

static void draw_half_ring(lv_layer_t *layer, bool left, int16_t value, bool editing) {
    for (int v = s_min; v <= s_max; v++) {
        bool on = v <= value;
        lv_color_t c = on ? gradient_color(v, s_min, s_max) : color_tick_off();
        draw_tick(layer, 102, 113, half_angle(v, left), c, 3, (editing || !on) ? LV_OPA_COVER : LV_OPA_40);
    }
    int32_t x, y;
    if (!editing) {
        polar(DIAL_MARKER_R, half_angle(value, left), &x, &y);
        draw_dot(layer, x, y, 7, color_text3());
        return;
    }
    if (s_value != s_synced_value) {
        polar(DIAL_MARKER_R, half_angle(s_synced_value, left), &x, &y);
        draw_dot(layer, x, y, 9, color_text2(), LV_OPA_COVER, 2);
    }
    polar(DIAL_MARKER_R, half_angle(value, left), &x, &y);
    draw_dot(layer, x, y, 18, gradient_color(value, s_min, s_max), LV_OPA_30); // 光晕
    draw_dot(layer, x, y, 9, color_text());
}

static void draw_split_dial(lv_layer_t *layer) {
    if (s_max > s_min) {
        bool main_editing = (s_layout == LAYOUT_AC_SPLIT_MAIN);
        draw_half_ring(layer, true, main_editing ? s_value : s_secondary_value, main_editing);
        draw_half_ring(layer, false, main_editing ? s_secondary_value : s_value, !main_editing);
    }
    lv_draw_line_dsc_t d;
    lv_draw_line_dsc_init(&d);
    d.color = color_track();
    d.width = 1;
    d.p1.x = d.p2.x = s_split_divider_x;
    d.p1.y = 101;
    d.p2.y = 165;
    lv_draw_line(layer, &d);
}

// 双数字布局（ac_split_main/ac_split_deputy）：左边固定是主驾、右边固定是
// 副驾（跟真实驾驶位左右对应，不随哪个是"大的"而互换位置）。正在编辑
// 的那一侧 80px、跟着转动变琥珀色，另一侧 44px 灰色只读；两个数字按基线
// 对齐，下面各有一个小标签，中间一道竖线。
void redraw_ac_split(bool dirty) {
    reset_screen(draw_split_dial);
    place_title("分控");

    bool main_is_primary = (s_layout == LAYOUT_AC_SPLIT_MAIN);
    lv_color_t edit_color = dirty ? color_pending() : color_text();
    char main_buf[16];
    char deputy_buf[16];
    snprintf(main_buf, sizeof(main_buf), "%d", main_is_primary ? s_value : s_secondary_value);
    snprintf(deputy_buf, sizeof(deputy_buf), "%d", main_is_primary ? s_secondary_value : s_value);
    constexpr int32_t SPLIT_BASE = 145;  // 两个数字共用的基线
    constexpr int32_t SPLIT_LABEL_CY = 163;
    if (main_is_primary) {
        place_digits(s_label_left, main_buf, &font_num_80, edit_color, -1, 0, SPLIT_BASE);
        place_digits(s_label_right, deputy_buf, &font_num_44, color_text2(), 0, 0, SPLIT_BASE);
    } else {
        place_digits(s_label_left, main_buf, &font_num_44, color_text2(), 0, 0, SPLIT_BASE);
        place_digits(s_label_right, deputy_buf, &font_num_80, edit_color, -1, 0, SPLIT_BASE);
    }
    place_text(s_label_sub_left, "主驾", &font_zh_13, main_is_primary ? color_text() : color_text2(), 2, 0, SPLIT_LABEL_CY);
    place_text(s_label_sub_right, "副驾", &font_zh_13, main_is_primary ? color_text2() : color_text(), 2, 0, SPLIT_LABEL_CY);

    // 两边宽度每次都可能不一样（谁大谁小、几位数），按实际宽度现算：
    // [左列][16][竖线][16][右列] 整体居中，每列宽 = 数字和标签里宽的那个。
    lv_obj_update_layout(s_label_left);
    int32_t wl = LV_MAX(lv_obj_get_width(s_label_left), lv_obj_get_width(s_label_sub_left));
    int32_t wr = LV_MAX(lv_obj_get_width(s_label_right), lv_obj_get_width(s_label_sub_right));
    constexpr int32_t GAP = 16;
    int32_t total = wl + GAP + 1 + GAP + wr;
    int32_t left_cx = -total / 2 + wl / 2;
    int32_t right_cx = total / 2 - wr / 2;
    lv_obj_set_x(s_label_left, left_cx);
    lv_obj_set_x(s_label_sub_left, left_cx);
    lv_obj_set_x(s_label_right, right_cx);
    lv_obj_set_x(s_label_sub_right, right_cx);
    s_split_divider_x = DIAL_CX - total / 2 + wl + GAP;

    place_commit_hint(dirty);
}
