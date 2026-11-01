// 圆屏界面的骨架：创建控件、摆文字的小工具，以及 redraw() 按屏幕状态分发到
// 各画面（page_*.cpp）。文件分工见 ui.h。

#include "ui_internal.h"
#include <stdio.h>

// 后面需要更新的文字控件都存成全局变量，这样 build_ui()（只创建一次）
// 和各画面的 redraw_*()（数值变化时更新）才都能访问到。
lv_obj_t *s_label_name;
lv_obj_t *s_label_value;
lv_obj_t *s_label_left;
lv_obj_t *s_label_right;
lv_obj_t *s_label_sub_left;
lv_obj_t *s_label_sub_right;
lv_obj_t *s_label_hint;
lv_obj_t *s_unit;

void build_ui() {
    lv_obj_t *scr = lv_screen_active(); // 这个 demo 只有这一个页面
    lv_obj_set_style_bg_color(scr, color_bg(), 0);

    // 全屏透明的绘制层，先建它，后面建的文字才会盖在它上面。
    dial_create(scr);

    s_label_name = lv_label_create(scr);
    s_label_value = lv_label_create(scr);
    s_label_left = lv_label_create(scr);
    s_label_right = lv_label_create(scr);
    s_label_sub_left = lv_label_create(scr);
    s_label_sub_right = lv_label_create(scr);
    s_label_hint = lv_label_create(scr);
    s_unit = lv_label_create(scr);
    lv_obj_set_style_text_font(s_unit, &font_num_28, 0);
    lv_obj_set_style_text_color(s_unit, color_text2(), 0);

    reset_screen(nullptr);
}

// ---------- 摆文字 ----------

void reset_screen(DialDrawFn draw) {
    lv_obj_t *const all[] = {s_label_name, s_label_value, s_label_left, s_label_right, s_label_sub_left,
                             s_label_sub_right, s_label_hint, s_unit};
    for (lv_obj_t *o : all) lv_obj_add_flag(o, LV_OBJ_FLAG_HIDDEN);
    dial_reset(draw);
}

static void style_label(lv_obj_t *label, const lv_font_t *font, lv_color_t color, int32_t letter_space) {
    lv_obj_set_style_text_font(label, font, 0);
    lv_obj_set_style_text_color(label, color, 0);
    lv_obj_set_style_text_letter_space(label, letter_space, 0);
    lv_obj_remove_flag(label, LV_OBJ_FLAG_HIDDEN);
}

void place_text(lv_obj_t *label, const char *text, const lv_font_t *font, lv_color_t color, int32_t letter_space,
                int32_t dx, int32_t cy) {
    lv_label_set_text(label, text);
    style_label(label, font, color, letter_space);
    lv_obj_align(label, LV_ALIGN_TOP_MID, dx, cy - lv_font_get_line_height(font) / 2);
}

void place_digits(lv_obj_t *label, const char *text, const lv_font_t *font, lv_color_t color, int32_t letter_space,
                  int32_t dx, int32_t base) {
    lv_label_set_text(label, text);
    style_label(label, font, color, letter_space);
    lv_obj_align(label, LV_ALIGN_TOP_MID, dx, base - (lv_font_get_line_height(font) - font->base_line));
}

void place_title(const char *text) { place_text(s_label_name, text, &font_zh_16, color_text2(), 3, 0, TITLE_CY); }

void place_hint(const char *text, lv_color_t color, int32_t cy) {
    place_text(s_label_hint, text, &font_zh_13, color, 2, 0, cy);
}

// 居中的是数字本身——视觉重心在数字上，单位只是挂在旁边，不参与居中。
void place_big_number(int value, lv_color_t color, const char *unit) {
    char buf[16];
    snprintf(buf, sizeof(buf), "%d", value);
    place_digits(s_label_value, buf, &font_num_92, color, -1, 0, NUMBER_BASE);
    if (unit) {
        lv_label_set_text(s_unit, unit);
        lv_obj_remove_flag(s_unit, LV_OBJ_FLAG_HIDDEN);
        lv_obj_update_layout(s_label_value);
        lv_obj_align_to(s_unit, s_label_value, LV_ALIGN_OUT_RIGHT_TOP, 2, 2);
    }
}

void place_commit_hint(bool dirty) {
    if (s_commit_holding) {
        place_hint("继续按住", color_pending(), HINT_CY);
    } else if (dirty) {
        place_hint("长按确认", color_pending(), HINT_CY);
    }
}

// ---------- 按状态分发 ----------

static void redraw_normal() {
    if (s_drive_showing && s_have_display_data) {
        redraw_drive();
        return;
    }
    if (!s_have_display_data) {
        redraw_waiting_data();
        return;
    }

    // 转动过、还没长按提交（跟手机上次同步的起点不一样）就是琥珀色，
    // 静止时是白色。
    bool dirty = (s_value != s_synced_value);

    switch (s_layout) {
        case LAYOUT_AC_SPLIT_MAIN:
        case LAYOUT_AC_SPLIT_DEPUTY:
            redraw_ac_split(dirty);
            break;
        case LAYOUT_AC_LINK:
            redraw_ac_link(dirty);
            break;
        case LAYOUT_SEAT:
            redraw_seat(dirty);
            break;
        case LAYOUT_NONE:
            reset_screen(nullptr);
            place_text(s_label_name, s_title, &font_zh_20, color_text(), 3, 0, DIAL_CY);
            break;
    }
}

void redraw() {
    switch (s_screen_state) {
        case SCREEN_DISCONNECTED: redraw_disconnected(); break;
        case SCREEN_PAIRED_TOAST: redraw_paired_toast(); break;
        case SCREEN_NORMAL: redraw_normal(); break;
        case SCREEN_OTA: redraw_ota(); break;
    }
}
