// 绘制层：刻度环、座椅分段弧、各种进度环、座椅分页标记……所有"画出来"而不是
// 文字的东西，都在这一个全屏透明对象的绘制回调里按当前画面现画（见
// dial_draw_cb），不拼一堆 lv_arc/lv_obj。各画面的环由它们自己的绘制函数画
// （reset_screen 时指定），这里只管通用的部分：画图小工具、转圈、图标和长按
// 进度环。

#include "ui_internal.h"
#include <math.h>

static lv_obj_t *s_dial;

// 下面这几个只在 loop() 线程上读写（redraw() 和绘制回调都在那里跑），
// 不用像 BLE 回调改的那些状态一样标 volatile。
static DialDrawFn s_draw = nullptr; // 当前画面的环怎么画，nullptr = 不画

static DialIcon s_icon = ICON_NONE;
static lv_color_t s_icon_color;
static int32_t s_icon_cy = 0;   // 图标中心的屏幕 y 坐标
static int32_t s_icon_size = 0; // 图标边长（对应设计稿 24×24 的那个框）

// 等待连接 / 等待数据时的转圈，也在这里画（见 draw_spinner）。不用
// LVGL 自带的 lv_spinner：它一圈 1.5 秒、结尾和下一圈开头对不上，循环处
// 会跳一下。这里照 Material 的做法：一个大周期里整体匀速旋转 + 弧长按
// 缓动曲线伸缩，周期结束时头尾正好回到整数圈，循环无缝。
SpinnerStyle s_spinner_style = SPINNER_EXPRESSIVE; // 不是 const：firmware/simulate 渲染两种对比时会改它
bool s_spinner_on = false;
static bool s_spinner_was_on = false; // 上一次 redraw 之前转圈是不是在显示（在就接着转，不从头来）
static uint32_t s_spinner_start_ms = 0;

// 长按提交 / BOOT 清配对的进度环（见 ui.h 的 set_overlay），-1 = 不显示。
static int s_overlay_percent = -1;
static lv_color_t s_overlay_color;

// ---------- 画图小工具 ----------

void polar(float r, float deg, int32_t *x, int32_t *y) {
    float a = deg * 0.017453293f;
    *x = DIAL_CX + (int32_t)lroundf(r * cosf(a));
    *y = DIAL_CY + (int32_t)lroundf(r * sinf(a));
}

void draw_tick(lv_layer_t *layer, float r0, float r1, float deg, lv_color_t color, int32_t width, lv_opa_t opa) {
    lv_draw_line_dsc_t d;
    lv_draw_line_dsc_init(&d);
    int32_t x, y;
    polar(r0, deg, &x, &y);
    d.p1.x = x;
    d.p1.y = y;
    polar(r1, deg, &x, &y);
    d.p2.x = x;
    d.p2.y = y;
    d.color = color;
    d.width = width;
    d.opa = opa;
    d.round_start = 1;
    d.round_end = 1;
    lv_draw_line(layer, &d);
}

void draw_dot(lv_layer_t *layer, int32_t cx, int32_t cy, int32_t diameter, lv_color_t color, lv_opa_t opa,
              int32_t border) {
    lv_draw_rect_dsc_t d;
    lv_draw_rect_dsc_init(&d);
    d.radius = LV_RADIUS_CIRCLE;
    if (border > 0) {
        d.bg_opa = LV_OPA_TRANSP;
        d.border_width = border;
        d.border_color = color;
        d.border_opa = opa;
    } else {
        d.bg_color = color;
        d.bg_opa = opa;
    }
    lv_area_t a = {cx - diameter / 2, cy - diameter / 2, cx - diameter / 2 + diameter - 1, cy - diameter / 2 + diameter - 1};
    lv_draw_rect(layer, &d, &a);
}

static int32_t norm_deg(int32_t a) {
    a %= 360;
    return a < 0 ? a + 360 : a;
}

void draw_ring(lv_layer_t *layer, float r, int32_t width, int32_t a0, int32_t a1, lv_color_t color, bool rounded,
               lv_opa_t opa) {
    lv_draw_arc_dsc_t d;
    lv_draw_arc_dsc_init(&d);
    d.center.x = DIAL_CX;
    d.center.y = DIAL_CY;
    d.radius = (uint16_t)lroundf(r + width / 2.0f);
    d.width = width;
    if (a1 - a0 >= 360) {
        d.start_angle = 0;
        d.end_angle = 360;
    } else {
        d.start_angle = norm_deg(a0);
        d.end_angle = norm_deg(a1);
    }
    d.color = color;
    d.opa = opa;
    d.rounded = rounded;
    lv_draw_arc(layer, &d);
}

void draw_progress_ring(lv_layer_t *layer, float r, int32_t width, int percent, lv_color_t color) {
    if (percent <= 0) return;
    if (percent > 100) percent = 100;
    draw_ring(layer, r, width, 270, 270 + percent * 360 / 100, color, true);
}

// ---------- 图标 ----------

void place_icon(DialIcon icon, lv_color_t color, int32_t size, int32_t cy) {
    s_icon = icon;
    s_icon_color = color;
    s_icon_size = size;
    s_icon_cy = cy;
}

// 设计稿的 24×24 线条图标（蓝牙符文 / 对勾），按 s_icon_size 放大，中心画在
// (DIAL_CX, s_icon_cy)。每段都是圆头线，拐角自然是圆的。
static void draw_icon(lv_layer_t *layer) {
    static const int8_t bluetooth[][2] = {{7, 7}, {17, 17}, {12, 22}, {12, 2}, {17, 7}, {7, 17}};
    static const int8_t check[][2] = {{20, 6}, {9, 17}, {4, 12}};
    const int8_t(*pts)[2];
    int n;
    float stroke;
    if (s_icon == ICON_BLUETOOTH) {
        pts = bluetooth;
        n = 6;
        stroke = 2.0f;
    } else if (s_icon == ICON_CHECK) {
        pts = check;
        n = 3;
        stroke = 2.4f;
    } else {
        return;
    }
    float k = s_icon_size / 24.0f;
    lv_draw_line_dsc_t d;
    lv_draw_line_dsc_init(&d);
    d.color = s_icon_color;
    d.width = (int32_t)lroundf(stroke * k);
    if (d.width < 1) d.width = 1;
    d.round_start = 1;
    d.round_end = 1;
    for (int i = 0; i + 1 < n; i++) {
        d.p1.x = DIAL_CX + (int32_t)lroundf((pts[i][0] - 12) * k);
        d.p1.y = s_icon_cy + (int32_t)lroundf((pts[i][1] - 12) * k);
        d.p2.x = DIAL_CX + (int32_t)lroundf((pts[i + 1][0] - 12) * k);
        d.p2.y = s_icon_cy + (int32_t)lroundf((pts[i + 1][1] - 12) * k);
        lv_draw_line(layer, &d);
    }
}

// ---------- 转圈（算法照搬 Material Components Android 的
// CircularIndeterminateAdvanceAnimatorDelegate / ...RetreatAnimatorDelegate） ----------

// cubic-bezier(0.4, 0, 0.2, 1)：Material 的 FastOutSlowIn（也是 standard 缓动）。
// 先按 x 二分反解贝塞尔参数 u，再算 y。
static float ease_fast_out_slow_in(float x) {
    if (x <= 0.0f) return 0.0f;
    if (x >= 1.0f) return 1.0f;
    float lo = 0.0f, hi = 1.0f, u = x;
    for (int i = 0; i < 16; i++) {
        u = (lo + hi) / 2;
        float bx = 3 * (1 - u) * (1 - u) * u * 0.4f + 3 * (1 - u) * u * u * 0.2f + u * u * u;
        if (bx < x) lo = u;
        else hi = u;
    }
    return 3 * (1 - u) * u * u + u * u * u;
}

// 时间 t 落在 [delay, delay + duration] 这一段里的进度，0..1
static float window(uint32_t t, uint32_t delay, uint32_t duration) {
    if (t <= delay) return 0.0f;
    if (t >= delay + duration) return 1.0f;
    return (float)(t - delay) / duration;
}

// 转圈开始 ms 毫秒时，弧的尾部/头部角度（0° 指右、顺时针，可以超过 360）
static void spinner_arc(uint32_t ms, float *tail, float *head) {
    if (s_spinner_style == SPINNER_CLASSIC) {
        // 5.4 秒一个大周期，整体匀速转 1520°；里面 4 个小周期，头部先用
        // 667ms 往前冲 250°（变长），尾部再用 667ms 追上 250°（变短）。
        // 一个大周期头尾都多走 1520 + 4×250 = 2520° = 7 圈，首尾无缝。
        uint32_t t = ms % 5400;
        float rot = 1520.0f * t / 5400;
        *head = rot;
        *tail = rot - 20;
        for (int k = 0; k < 4; k++) {
            *head += ease_fast_out_slow_in(window(t, 1350 * k, 667)) * 250;
            *tail += ease_fast_out_slow_in(window(t, 1350 * k + 667, 667)) * 250;
        }
    } else {
        // 6 秒一个大周期，整体匀速转 1080°，另外每 1.5 秒用 500ms 加速多转
        // 90°（一共 4 次，合计正好 4 圈）；弧长前 3 秒从 0.1 圈慢慢长到
        // 0.87 圈，后 3 秒缩回去。
        uint32_t t = ms % 6000;
        float rot = 1080.0f * t / 6000;
        for (int k = 0; k < 4; k++) rot += ease_fast_out_slow_in(window(t, 1500 * k, 500)) * 90;
        float grow = ease_fast_out_slow_in(window(t, 0, 3000)) - ease_fast_out_slow_in(window(t, 3000, 3000));
        *tail = rot;
        *head = rot + (0.10f + 0.77f * grow) * 360;
    }
}

// 蓝色的弧 + 暗色轨道，轨道在弧的两头各留一道缝（Material 3 的画法），
// 弧和轨道不粘在一起，伸缩看得更清楚。整体转 -90°，让 0 时刻从 12 点钟开始。
static void draw_spinner(lv_layer_t *layer) {
    float tail, head;
    spinner_arc(lv_tick_get() - s_spinner_start_ms, &tail, &head);
    int32_t a0 = (int32_t)lroundf(tail) - 90;
    int32_t a1 = (int32_t)lroundf(head) - 90;
    constexpr int32_t GAP_DEG = 6; // 线宽 3 的圆头占掉一点，留出约 6px 的缝
    if (a1 - a0 < 360 - 2 * GAP_DEG) draw_ring(layer, 110, 3, a1 + GAP_DEG, a0 + 360 - GAP_DEG, color_track(), true);
    draw_ring(layer, 110, 3, a0, a1, color_blue(), true);
}

void start_spinner() {
    s_spinner_on = true;
    if (!s_spinner_was_on) s_spinner_start_ms = lv_tick_get();
}

// 转圈每一帧都在变：按刷新节奏让 s_dial 重画（只在转圈显示的时候）
static void spinner_tick_cb(lv_timer_t *) {
    if (s_spinner_on) lv_obj_invalidate(s_dial);
}

// ---------- 绘制回调 ----------

static void dial_draw_cb(lv_event_t *e) {
    lv_layer_t *layer = lv_event_get_layer(e);
    if (s_draw) s_draw(layer);
    if (s_spinner_on) draw_spinner(layer);
    draw_icon(layer);
    if (s_overlay_percent >= 0) draw_progress_ring(layer, 117, 4, s_overlay_percent, s_overlay_color);
}

void dial_create(lv_obj_t *scr) {
    s_dial = lv_obj_create(scr);
    lv_obj_remove_style_all(s_dial);
    lv_obj_set_size(s_dial, CONFIG_SCREEN_HOR_RES, CONFIG_SCREEN_VER_RES);
    lv_obj_center(s_dial);
    lv_obj_remove_flag(s_dial, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_add_event_cb(s_dial, dial_draw_cb, LV_EVENT_DRAW_MAIN, NULL);

    // 转圈要按刷新节奏一直重画（见 spinner_tick_cb）
    lv_timer_create(spinner_tick_cb, LV_DEF_REFR_PERIOD, NULL);
}

void dial_reset(DialDrawFn draw) {
    s_spinner_was_on = s_spinner_on;
    s_spinner_on = false;
    s_draw = draw;
    s_icon = ICON_NONE;
    lv_obj_invalidate(s_dial);
}

void set_overlay(int percent, lv_color_t color) {
    if (percent == s_overlay_percent && (percent < 0 || lv_color_eq(color, s_overlay_color))) return;
    s_overlay_percent = percent;
    s_overlay_color = color;
    lv_obj_invalidate(s_dial);
}
