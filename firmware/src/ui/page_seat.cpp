// 座椅停靠点：4 个座位共用一个画面（单击切座位），档位 -2..2，负数通风、
// 正数加热、0 关。

#include "ui_internal.h"

// 座椅档位的颜色跟 4 段弧一一对应：-2 蓝、-1 青（通风），+1 珊瑚、+2 红（加热）
static lv_color_t seat_level_color(int16_t level) {
    if (level <= -2) return color_blue();
    if (level == -1) return color_cyan();
    if (level == 1) return color_coral();
    if (level >= 2) return color_red();
    return color_text2(); // 0 = 关
}

// -2/-1 在左（通风），+1/+2 在右（加热），正上方 270° 一根刻线表示"关"；
// 从 0 往当前档位那一侧填色。底部一排分页标记，顺序跟插件 seatSlots 一致
// （主驾→副驾→左后→右后）：当前那个是 16px 宽的白色胶囊，其余是 6px 灰点。
// 是哪个座位主要靠顶部的大号座位名，标记只表示"一共几个、轮到第几个"。
// 不是复合停靠点时插件不发 sub_count，默认 0，不画标记。
static void draw_seat_dial(lv_layer_t *layer) {
    static const struct {
        int8_t level;
        int16_t a0, a1;
    } segs[] = {{-2, 137, 197}, {-1, 203, 264}, {1, 276, 337}, {2, 343, 403}};
    for (const auto &s : segs) {
        bool filled = (s_value < 0 && s.level < 0 && s.level >= s_value) || (s_value > 0 && s.level > 0 && s.level <= s_value);
        draw_ring(layer, 108, 9, s.a0, s.a1, filled ? seat_level_color(s.level) : color_track(), false);
    }
    draw_tick(layer, 99, 117, 270, color_text3(), 2);

    int count = s_sub_count > 6 ? 6 : s_sub_count;
    if (count <= 1) return;
    constexpr int32_t DOT = 6, ACTIVE_W = 16, GAP = 6, CY = 199;
    int32_t x = DIAL_CX - ((count - 1) * (DOT + GAP) + ACTIVE_W) / 2;
    for (int i = 0; i < count; i++) {
        bool active = (i == s_sub_index);
        int32_t w = active ? ACTIVE_W : DOT;
        lv_draw_rect_dsc_t d;
        lv_draw_rect_dsc_init(&d);
        d.radius = LV_RADIUS_CIRCLE;
        d.bg_color = active ? color_text() : color_text3();
        lv_area_t a = {x, CY - DOT / 2, x + w - 1, CY - DOT / 2 + DOT - 1};
        lv_draw_rect(layer, &d, &a);
        x += w + GAP;
    }
}

// 中间是档位大小，下面一行写明加热/通风（颜色跟亮起的那段弧一致）；
// 0 档只写一个"关"。底部是分页标记，放不下提示文字，没提交只靠
// 数字变琥珀色表示。座位名比别的页标题大一号、用白色——四个座位
// 共用一个页面，转到哪个座位全靠它认。
void redraw_seat(bool dirty) {
    reset_screen(draw_seat_dial);
    place_text(s_label_name, s_title, &font_zh_28, color_text(), 3, 0, TITLE_CY);
    if (s_value == 0) {
        place_text(s_label_value, "关", &font_zh_44, dirty ? color_pending() : color_text2(), 0, 0, 120);
    } else {
        lv_color_t c = seat_level_color(s_value);
        place_big_number(s_value < 0 ? -s_value : s_value, dirty ? color_pending() : c, nullptr);
        place_text(s_label_hint, s_value > 0 ? "加热" : "通风", &font_zh_16, c, 3, 0, 168);
    }
}
