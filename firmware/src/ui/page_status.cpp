// 系统画面：未连接（配对码 / 未配对 / 等待连接）、配对/连接成功提示、
// 已连接但还没收到数据、固件升级。这些画面都不受旋钮操作影响。

#include "ui_internal.h"
#include <stdio.h>

// ---------- 环 ----------

// 未配对：一圈虚点
static void draw_dotted(lv_layer_t *layer) {
    int32_t x, y;
    for (int deg = 0; deg < 360; deg += 5) {
        polar(112, deg, &x, &y);
        draw_dot(layer, x, y, 3, color_text3());
    }
}

// 配对码：淡蓝整圈 + 顶部一小段亮蓝
static void draw_passkey_ring(lv_layer_t *layer) {
    draw_ring(layer, 112, 2, 0, 360, color_blue(), false, LV_OPA_40);
    draw_ring(layer, 112, 3, 250, 290, color_blue(), true);
}

// 配对/连接成功：绿色整圈 + 对勾背后一团淡绿
constexpr int32_t OK_ICON_CY = 98; // 对勾的中心
static void draw_ok_ring(lv_layer_t *layer) {
    draw_ring(layer, 110, 3, 0, 360, color_ok(), false);
    draw_dot(layer, DIAL_CX, OK_ICON_CY, 52, color_ok(), 36); // 对勾背后，不透明度约 14%
}

// 固件升级：整圈轨道 + 绿色进度 + 头部白点
static void draw_ota_ring(lv_layer_t *layer) {
    draw_ring(layer, 110, 5, 0, 360, color_track(), false);
    draw_progress_ring(layer, 110, 5, s_ota_percent, color_ok());
    if (s_ota_percent > 0) {
        int32_t x, y;
        polar(110, 270 + s_ota_percent * 360 / 100, &x, &y);
        draw_dot(layer, x, y, 11, color_text());
    }
}

// ---------- 画面 ----------

void redraw_disconnected() {
    if (s_pending_passkey >= 0) {
        // 正在配对：6 位码拆成 3+3 两段方便跟手机核对（数字字体里没有空格
        // 字形，所以用左右两个标签，中间留 14px）。
        reset_screen(draw_passkey_ring);
        place_text(s_label_name, "配对码", &font_zh_16, color_text2(), 3, 0, 70);
        char buf[8];
        snprintf(buf, sizeof(buf), "%06u", (unsigned)s_pending_passkey);
        place_digits(s_label_right, buf + 3, &font_num_44, color_text(), 3, 0, 134);
        buf[3] = '\0';
        place_digits(s_label_left, buf, &font_num_44, color_text(), 3, 0, 134);
        lv_obj_update_layout(s_label_left);
        int32_t half_gap = 7;
        lv_obj_set_x(s_label_left, -(lv_obj_get_width(s_label_left) / 2 + half_gap));
        lv_obj_set_x(s_label_right, lv_obj_get_width(s_label_right) / 2 + half_gap);
        place_hint("请在手机上确认", color_text2(), 167);
    } else if (!ble_has_any_bond()) {
        // 从没跟任何设备配对成功过（NimBLE 自己 flash 里的配对记录数为
        // 0，本地就能查，不用猜）——这种情况才是真的需要用户去配对，显示
        // 广播名字方便用户找，同时配好几个 knob 时尤其有用：只有真正没配过
        // 的那个会显示名字，已经配好的不会。
        reset_screen(draw_dotted);
        place_icon(ICON_BLUETOOTH, color_blue(), 26, 65);
        place_digits(s_label_value, ble_get_device_name(), &font_num_34, color_text(), 0, 0, 129);
        place_text(s_label_name, "未配对", &font_zh_16, color_text2(), 3, 0, 154);
    } else {
        // 已经跟某个设备配对过，现在只是暂时没连上（杀 App、暂时超出
        // 范围……）——配对信息（LTK）早就还在，不需要用户做任何事，等它
        // 自动重连就行，"等待连接"就是唯一要传达的信息。
        reset_screen(nullptr);
        start_spinner();
        place_icon(ICON_BLUETOOTH, color_text2(), 40, 90);
        place_text(s_label_value, "等待连接", &font_zh_20, color_text(), 3, 0, 142);
    }
}

void redraw_paired_toast() {
    // 真的走过配对码确认才是"配对成功"；已配对设备用保存的密钥重新
    // 连接（比如手机 App 被杀后台又重启）没有这一步，应该说"连接成功"，
    // 不然会让人误以为每次连接都要重新配对。
    reset_screen(draw_ok_ring);
    place_icon(ICON_CHECK, color_ok(), 30, OK_ICON_CY);
    place_text(s_label_value, s_paired_via_confirm ? "配对成功" : "连接成功", &font_zh_20, color_text(), 3, 0, 150);
}

// 已经连上了，但手机还没推过任何数据（比如刚配对完，提示消失时手机还没来得及
// 同步）——不能显示上一次残留的 title/value，那可能是断线之前的旧值，容易让人
// 误以为是当前状态。
void redraw_waiting_data() {
    reset_screen(nullptr);
    start_spinner();
    place_text(s_label_name, "已连接", &font_zh_16, color_text2(), 3, 0, 100);
    place_text(s_label_value, "等待数据...", &font_zh_20, color_text(), 3, 0, 132);
}

// 固件升级中：整圈进度环 + 中间大字百分比，完成后显示"正在重启"。
void redraw_ota() {
    reset_screen(draw_ota_ring);
    place_title("固件升级");
    if (s_ota_done) {
        place_text(s_label_value, "正在重启", &font_zh_20, color_text(), 3, 0, DIAL_CY);
    } else {
        place_big_number(s_ota_percent, color_text(), "%");
        place_hint("请勿断开", color_text2(), 187);
    }
}
