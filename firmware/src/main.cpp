// Knob 固件。
// 屏幕显示"当前正在编辑的一个停靠点"（联动/分控-主驾/分控-副驾/座椅），
// 手机每次切停靠点/提交后回执都会推一份新的过来当起点；转动旋钮只改
// 本地这份副本、立刻重绘，不再每转一档就等手机一次——避免了转动和屏幕
// 更新之间的蓝牙往返延迟。单击（CycleSub）在"复合停靠点"内部切换子项
// （比如座椅切主驾/副驾/左后/右后，空调分控切主驾/副驾），双击（Tab）
// 才切到下一个停靠点，长按（Enter）把编辑到的最终值带给手机去真正应用
// （对应插件那边只有 Enter 会调用车控接口，转动期间不会触发大量真实车
// 控调用）。单击/双击特意反过来分配：手慢了没跟上双击判定窗口，最坏
// 情况是被误判成一次单击（原地切子项，还在同一个功能里），而不是误判
// 成切到下一个完全不相关的停靠点——后者代价明显更高，不该让更容易失手
// 的手势（双击）承担。固件无条件识别单击/双击、不知道当前停靠点是不是
// 复合停靠点——要不要响应交给手机插件根据 currentPageId 决定，见
// encoder_take_double_click 的注释。
//
// 屏幕同时还要同步配对/连接状态（未连接、正在配对、刚配对成功、
// 断开重连），不是一直都在显示手机推来的数据——见 state.h 的 ScreenState。
// 停靠点画面 DRIVE_IDLE_MS 没有操作会切到驾驶页（车速/油门/刹车），第一次
// 操作回原来的停靠点，见 update_drive()。
//
// 这个文件只管状态：BLE 回调、旋钮/BOOT 键输入、配对提示/重启这些定时的事，
// 改完状态调 redraw()。各画面怎么画在 ui/ 目录（每个画面一个文件，见 ui/ui.h）。
// 方案见 docs/旋钮-最小硬件调试方案.md、docs/蓝牙控制配件-方案.md。

#include <Arduino.h>
#include <string.h>
#include "config.h"
#include "display.h"
#include "encoder.h"
#include "ble.h"
#include "state.h"
#include "ui/ui.h"
#include "fw_version_gen.h"

// 屏幕显示的状态，说明见 state.h。
volatile ScreenState s_screen_state = SCREEN_DISCONNECTED;
volatile int32_t s_pending_passkey = -1;
volatile bool s_paired_via_confirm = false;
volatile int s_ota_percent = 0;
volatile bool s_ota_done = false;
char s_title[DisplayData::MAX_STR_LEN + 1] = "";
volatile DisplayLayout s_layout = LAYOUT_NONE;
volatile int16_t s_value = 0;
volatile int16_t s_synced_value = 0;
volatile int16_t s_secondary_value = 0;
volatile int16_t s_min = 0;
volatile int16_t s_max = 0;
volatile bool s_have_display_data = false;
volatile int16_t s_sub_count = 0;
volatile int16_t s_sub_index = 0;
volatile int16_t s_drive_speed = 0;
volatile int16_t s_drive_throttle = 0;
volatile int16_t s_drive_brake = 0;
volatile bool s_drive_have_speed = false;
bool s_drive_showing = false;
bool s_commit_holding = false;

// LVGL 不是线程安全的（官方文档明确说明，多线程/多任务并发调用 lv_ 函数
// 是未定义行为）——之前 on_paired() 这些回调直接在里面调 redraw()，
// 而 redraw() 最终会调 lv_label_set_text/lv_arc_set_value 等 LVGL 函数，
// 这些回调又是在 NimBLE 后台任务线程上跑的，跟 loop() 里的
// lv_timer_handler() 并发访问 LVGL 内部状态，会导致重绘偶尔"悄悄失效"
// （表现为：串口日志显示配对/收到数据都成功了，但屏幕没跟着变）。
// 现在改成回调只置这个标志位，真正的 redraw() 调用挪到 loop() 里做——
// 跟 lv_timer_handler() 在同一个线程，就没有这个问题了。
static volatile bool s_needs_redraw = false;

constexpr uint32_t PAIRED_TOAST_MS = 1000;
static volatile uint32_t s_toast_until_ms = 0;

// 固件升级完成后什么时候重启。重启不在 OTA 回调里直接做：留一点时间让
// "完成"通知发给手机、屏幕显示出"正在重启"，到点由 loop() 调 ESP.restart()。
// 0 = 没有待执行的重启。
static volatile uint32_t s_restart_at_ms = 0;
constexpr uint32_t OTA_RESTART_DELAY_MS = 1500;

static DisplayLayout parse_layout(const char *s) {
    if (strcmp(s, "ac_link") == 0) return LAYOUT_AC_LINK;
    if (strcmp(s, "ac_split_main") == 0) return LAYOUT_AC_SPLIT_MAIN;
    if (strcmp(s, "ac_split_deputy") == 0) return LAYOUT_AC_SPLIT_DEPUTY;
    if (strcmp(s, "seat") == 0) return LAYOUT_SEAT;
    return LAYOUT_NONE;
}

// 驾驶页不是停靠点，是停靠点画面闲置 DRIVE_IDLE_MS 之后的待机画面：插件里的
// 当前停靠点不变，旋钮自己切过去，第一次操作（转动/按下）切回来，这一下只
// 唤醒、不执行。从没收到过车速就不切驾驶页（只能看到 "--"）。
constexpr uint32_t DRIVE_IDLE_MS = 10000;
static volatile bool s_drive_changed = false; // 收到新的驾驶数据，驾驶页正显示着的话 loop() 重画

// 下面几个只在 loop() 线程上读写。
static uint32_t s_last_input_ms = 0;     // 上一次旋钮操作（转动/按着）的时间，闲置计时从这里算
// 在驾驶页按下按键唤醒时置 true：这一下按键（单击/双击/长按，含双击的第二下）
// 都只用来唤醒，事件全部丢掉、不画长按进度环。等旋钮模块彻底空闲
// （encoder_is_idle()：松手、没有等双击窗口的单击、事件都取走了）才清掉。
static bool s_swallow_button = false;

// 配对成功提示还没消失之前，手机如果推了新数据，先缓存在这里，等提示
// 消失后再应用——不能提示还没显示完就被数据覆盖掉。多次收到就直接
// 覆盖，只保留最新的一份。
static volatile bool s_display_pending = false;
static char s_pending_title[DisplayData::MAX_STR_LEN + 1] = "";
static volatile DisplayLayout s_pending_layout = LAYOUT_NONE;
static volatile int16_t s_pending_value = 0;
static volatile int16_t s_pending_secondary = 0;
static volatile int16_t s_pending_min = 0;
static volatile int16_t s_pending_max = 0;
static volatile int16_t s_pending_sub_count = 0;
static volatile int16_t s_pending_sub_index = 0;

// 正在配对，手机弹窗和这里应该显示同一个 6 位数——见 ble.h 里
// ble_on_passkey 的注释，只有真正走一遍新配对流程才会触发。
// 这几个回调都跑在 NimBLE 后台任务线程上，只允许改状态变量，不能直接
// 调 redraw()（LVGL 不是线程安全的，见 s_needs_redraw 的注释）——置个
// 标志位，交给 loop() 在自己的线程上真正重绘。
static void on_passkey(uint32_t passkey) {
    s_pending_passkey = (int32_t)passkey;
    s_paired_via_confirm = true; // 这个连接周期真的走了配对码确认
    s_screen_state = SCREEN_DISCONNECTED;
    s_needs_redraw = true;
}

// 加密连接建立成功——不管是刚配对完还是用已保存的密钥重新连接，都会
// 触发这个。切到"配对成功/连接成功"提示（哪个文案见 s_paired_via_confirm），
// 1 秒后 loop() 里会自动收尾。
static void on_paired() {
    s_pending_passkey = -1; // 配对完成了，不用再显示配对码
    s_screen_state = SCREEN_PAIRED_TOAST;
    s_toast_until_ms = millis() + PAIRED_TOAST_MS;
    s_needs_redraw = true;
}

// 断开连接——不管之前是已经用了一会儿还是配对到一半就断了，都切回
// "未连接"。之前显示的数据可能已经过期（比如手机端车况变了），清掉
// s_have_display_data，避免重连、提示消失后一时半会儿没收到新数据，
// 却还在显示断线前的旧值。s_paired_via_confirm 也一起清零，下一个
// 连接周期重新判断是配对还是重连。
static void on_disconnected() {
    s_pending_passkey = -1;
    s_paired_via_confirm = false;
    // 升级完成、等着重启时手机先断开了：继续显示"正在重启"，不切回未连接
    if (!s_ota_done) s_screen_state = SCREEN_DISCONNECTED;
    s_have_display_data = false;
    s_display_pending = false;
    s_drive_have_speed = false; // 重连后插件会重推一份
    s_needs_redraw = true;
}

// 固件升级事件（见 ble_on_ota）。跟上面几个回调一样跑在 NimBLE 线程上
// （超时放弃的 FAILED 例外，来自 loop() 里的 ble_ota_check_timeout），
// 只改状态、置重绘标志。进度只在百分比变了才重绘，不是每收一包都重绘。
// 失败时：还连着就回到正常画面（数据还在），断线导致的失败随后会有
// on_disconnected() 把画面切到"未连接"。
static void on_ota(OtaEvent event, uint32_t received, uint32_t total) {
    int percent = total > 0 ? (int)((uint64_t)received * 100 / total) : 0;
    switch (event) {
        case OTA_EVENT_STARTED:
            s_ota_percent = 0;
            s_ota_done = false;
            s_screen_state = SCREEN_OTA;
            s_needs_redraw = true;
            break;
        case OTA_EVENT_PROGRESS:
            if (percent != s_ota_percent) {
                s_ota_percent = percent;
                s_needs_redraw = true;
            }
            break;
        case OTA_EVENT_DONE:
            s_ota_percent = 100;
            s_ota_done = true;
            s_restart_at_ms = millis() + OTA_RESTART_DELAY_MS;
            s_needs_redraw = true;
            break;
        case OTA_EVENT_FAILED:
            s_screen_state = SCREEN_NORMAL;
            s_needs_redraw = true;
            break;
    }
}

// 手机同步了新的显示数据时调用（见 ble_on_display_update）——切停靠点、
// 长按确认后的回执，都会走到这里。这里认的 "layout"/"title"/
// "value"/"value_main"/"value_deputy"/"min"/"max" 几个 key 是跟插件那边
// 约定好的，壳不参与这部分——以后要加新字段/新 layout，只需要同时改
// 插件发什么 key、这里认哪些 key，不用碰壳。data 指向的缓冲区只在本次
// 回调期间有效，getString() 拿到的指针也是，用完这次回调就失效，所以
// 这里立刻复制，不能只存指针。
static void on_display_update(const DisplayData &data) {
    // 亮度更新是独立的一条消息，跟"当前停靠点显示什么值"完全无关（光照
    // 传感器等级一变就推一次，不会带 title/layout/value 这些）——先处理，
    // 不走下面页面数据那一套（不用等配对提示消失、不影响 s_screen_state）。
    // display_set_brightness() 只是写一下 PWM 占空比，不碰 LVGL，这个
    // 回调所在的 NimBLE 线程上直接调没问题，不用像 redraw() 那样等
    // loop() 里再执行。
    if (data.has("brightness")) {
        display_set_brightness((uint8_t)data.getInt("brightness"));
        if (!data.has("title")) return; // 纯亮度推送，没有页面数据，到此为止
    }

    // 驾驶数据也是独立的一条消息（"throttle"/"brake" 0~100，读得到车速才带
    // "speed"，单位 km/h），只存最新一份，驾驶页正显示着才重画（见 loop()）。
    if (data.has("throttle")) {
        s_drive_have_speed = data.has("speed");
        s_drive_speed = data.getInt("speed");
        s_drive_throttle = data.getInt("throttle");
        s_drive_brake = data.getInt("brake");
        s_drive_changed = true;
        if (!data.has("title")) return;
    }

    // 升级期间插件会暂停显示同步；万一还是来了页面数据，也不能把升级
    // 画面切掉（亮度上面已经处理过，不受影响）。
    if (s_screen_state == SCREEN_OTA) return;

    DisplayLayout layout = parse_layout(data.getString("layout", "ac_link"));
    const char *title = data.getString("title");
    int16_t min = data.getInt("min");
    int16_t max = data.getInt("max");
    // 复合停靠点（座椅切座位、空调分控切主副驾）才会带这两个字段，不是
    // 复合停靠点时插件不发，默认值 0 表示"不用画指示点"，见 ui/page_seat.cpp。
    int16_t sub_count = data.getInt("sub_count", 0);
    int16_t sub_index = data.getInt("sub_index", 0);
    // "正在编辑的数字"在双数字布局下是 value_main 还是 value_deputy，
    // 由 layout 决定；另一侧存进 secondary，只读展示，转动不碰它。
    int16_t value;
    int16_t secondary;
    if (layout == LAYOUT_AC_SPLIT_MAIN) {
        value = data.getInt("value_main");
        secondary = data.getInt("value_deputy");
    } else if (layout == LAYOUT_AC_SPLIT_DEPUTY) {
        value = data.getInt("value_deputy");
        secondary = data.getInt("value_main");
    } else {
        value = data.getInt("value");
        secondary = 0;
    }

    if (s_screen_state == SCREEN_PAIRED_TOAST) {
        // "配对成功"提示还没显示完，先缓存，等 loop() 里提示到时收尾时
        // 再应用——不能提示还没看清就被数据覆盖掉。
        strncpy(s_pending_title, title, sizeof(s_pending_title) - 1);
        s_pending_title[sizeof(s_pending_title) - 1] = '\0';
        s_pending_layout = layout;
        s_pending_value = value;
        s_pending_secondary = secondary;
        s_pending_min = min;
        s_pending_max = max;
        s_pending_sub_count = sub_count;
        s_pending_sub_index = sub_index;
        s_display_pending = true;
        return;
    }

    strncpy(s_title, title, sizeof(s_title) - 1);
    s_title[sizeof(s_title) - 1] = '\0';
    s_layout = layout;
    s_value = value;
    s_synced_value = value; // 新起点，此刻还没被转动过，算"干净"
    s_secondary_value = secondary;
    s_min = min;
    s_max = max;
    s_sub_count = sub_count;
    s_sub_index = sub_index;
    s_have_display_data = true;
    s_screen_state = SCREEN_NORMAL;
    // 这个回调（跟上面几个一样）跑在 NimBLE 后台任务线程上，真正的
    // redraw() 调用得留给 loop()，不能在这里直接调，见 s_needs_redraw
    // 的注释。
    s_needs_redraw = true;
}

// 开发板自带的 BOOT 键（GPIO0）：设备正常运行期间长按
// BOOT_BTN_CLEAR_BONDS_MS，清空 ESP32 自己保存的配对记录——主要是
// 给调试用，模拟"从没配对过"的状态（正常使用不会用到这个）。开机
// 瞬间不检测，避免跟"按住 BOOT 进下载模式"这个约定冲突，见 config.h
// 里 BOOT_BTN_PIN 的注释。
//
// 手感：按住前 BOOT_BTN_PROGRESS_START_MS 没有任何视觉反馈（避免误碰
// 一下就吓一跳）；过了这个点开始在屏幕上画红色进度环，绕完一圈正好是
// BOOT_BTN_CLEAR_BONDS_MS，到点即触发清除；进度画到一半松手就放弃，
// 画面恢复成松手前该显示的样子，不会真的清除。
static void check_boot_button_reset() {
    static uint32_t press_start_ms = 0;
    static bool triggered = false;
    static bool showing_progress = false;

    if (s_screen_state == SCREEN_OTA) return; // 升级中不响应，也别把进度环画到升级画面上

    bool pressed = digitalRead(BOOT_BTN_PIN) == LOW;
    if (!pressed) {
        if (showing_progress) {
            // 进度环画到一半松手——放弃，收起进度环，底下的画面一直没变过
            showing_progress = false;
            set_overlay(-1, color_red());
        }
        press_start_ms = 0;
        triggered = false;
        return;
    }
    if (press_start_ms == 0) {
        press_start_ms = millis();
        return;
    }
    if (triggered) {
        return; // 已经触发过一次，松开之前不重复触发
    }

    uint32_t held_ms = millis() - press_start_ms;
    if (held_ms >= BOOT_BTN_CLEAR_BONDS_MS) {
        triggered = true;
        showing_progress = false;
        set_overlay(-1, color_red());
        Serial.println("[Knob] BOOT 长按触发：清除配对信息");
        ble_clear_all_bonds();
        // 立刻按"未连接"重绘一次——如果这时候本来就没连接，redraw()
        // 会重新查一次 ble_has_any_bond()（现在是 0 了），正确显示
        // "未配对"；如果这时候还连着，画面要等真正断连才会更新，这里
        // 不强行打断当前连接。
        s_screen_state = SCREEN_DISCONNECTED;
        redraw();
    } else if (held_ms >= BOOT_BTN_PROGRESS_START_MS) {
        showing_progress = true;
        uint32_t fill_elapsed = held_ms - BOOT_BTN_PROGRESS_START_MS;
        uint32_t fill_span = BOOT_BTN_CLEAR_BONDS_MS - BOOT_BTN_PROGRESS_START_MS;
        int percent = (int)((uint64_t)fill_elapsed * 100 / fill_span);
        set_overlay(percent > 100 ? 100 : percent, color_red());
    }
}

// 旋钮自己的按键（EC11 press）长按提交进度环——跟 check_boot_button_reset()
// 是同一个"按住画进度环"的套路，但这里查的是 encoder_is_button_pressed()
// 这个原始读数，不经过 encoder_poll_button() 那一套消抖/长短按判定状态
// 机，两者互不干扰、各查各的：encoder_poll_button() 继续负责
// "有没有发生一次点击/长按"这个边沿事件（用来真正触发 Tab/Enter），
// 这里只负责"按住的这段时间该不该画进度环"这个纯视觉反馈，不消费/不
// 影响那套状态机。
//
// 跟 BOOT 键那个红色进度环一样，按住前 ENC_COMMIT_PROGRESS_START_MS
// 没有任何视觉反馈，过了这个点才开始画——不然单击（哪怕只按几十毫秒）
// 也会闪一下进度环，不好看。ENC_LONG_PRESS_MS 到点触发真正的提交，
// 进度条正好在这一刻画满。
//
// 长按到点之后如果继续按着不松手，手机那边的回执（提交完 syncToKnob()
// 推一份新数据回来）会在同一次按住期间触发 redraw()——进度环跟画面分开
// 存（见 ui/ui.h 的 set_overlay）、redraw() 不碰它，所以回执重绘只换底下的数字，
// 进度环一直稳稳留在最外圈。
static void check_enter_commit_progress() {
    static uint32_t press_start_ms = 0;
    static bool showing_progress = false;

    bool pressed = encoder_is_button_pressed();
    if (!pressed) {
        if (showing_progress) {
            // 松手（不管有没有到点）收起进度环，提示换回"长按确认"
            showing_progress = false;
            s_commit_holding = false;
            set_overlay(-1, color_pending());
            redraw();
        }
        press_start_ms = 0;
        return;
    }
    if (s_screen_state != SCREEN_NORMAL || !s_have_display_data || s_drive_showing || s_swallow_button) {
        // 没有可编辑内容（未连接/配对提示/还没收到过数据）时不显示，
        // 这些状态下长按也不会真正触发提交。驾驶页上、以及按下去唤醒的
        // 这一下按住不放时也不显示——这一下只唤醒，长按会被丢掉。
        return;
    }
    if (press_start_ms == 0) {
        press_start_ms = millis();
        return;
    }
    uint32_t held_ms = millis() - press_start_ms;
    if (held_ms < ENC_COMMIT_PROGRESS_START_MS) {
        return; // 还没到"开始显示"的点，继续按着但屏幕上什么都不画
    }
    if (!showing_progress) {
        // 进度环刚出现：提示从"长按确认"换成"继续按住"，只在这一下重绘
        showing_progress = true;
        s_commit_holding = true;
        redraw();
    }
    uint32_t fill_elapsed = held_ms - ENC_COMMIT_PROGRESS_START_MS;
    uint32_t fill_span = ENC_LONG_PRESS_MS - ENC_COMMIT_PROGRESS_START_MS;
    int percent = (int)((uint64_t)fill_elapsed * 100 / fill_span);
    set_overlay(percent > 100 ? 100 : percent, color_pending());
}

// 停靠点画面闲置够久 → 驾驶页。还没提交的改动（琥珀色）直接丢掉：它本来就
// 没发给手机，车上什么都没变；回来时显示的是手机上次同步的值。
static void enter_drive() {
    s_drive_showing = true;
    s_value = s_synced_value;
    Serial.println("[DRIVE] idle -> drive page");
    redraw();
}

// 驾驶页上第一次操作 → 回到原来的停靠点（插件那边的当前停靠点一直没变）。
static void leave_drive(bool by_button) {
    s_drive_showing = false;
    if (by_button) s_swallow_button = true;
    Serial.println("[DRIVE] wake -> back to stop");
    redraw();
}

// 每次 loop()：处理驾驶页的进出。返回 true = 这次的转动量要丢掉（唤醒用掉了）。
static bool update_drive(int16_t diff, bool btn_down) {
    uint32_t now = millis();
    if (s_screen_state != SCREEN_NORMAL || !s_have_display_data) {
        // 系统画面（未连接/配对/升级）不受影响；回到正常画面时从头计时
        s_drive_showing = false;
        s_last_input_ms = now;
        return false;
    }
    if (s_drive_showing) {
        if (diff != 0 || btn_down) {
            s_last_input_ms = now;
            leave_drive(btn_down);
            return true;
        }
        if (s_drive_changed) {
            s_drive_changed = false;
            redraw();
        }
        return false;
    }
    s_drive_changed = false; // 不在驾驶页，存着就行，不用重画
    if (diff != 0 || btn_down || s_commit_holding) {
        s_last_input_ms = now;
    } else if (s_drive_have_speed && (uint32_t)(now - s_last_input_ms) >= DRIVE_IDLE_MS) {
        enter_drive();
    }
    return false;
}

// 唤醒的那一下按键彻底结束（旋钮模块空闲：松手、单击不再等双击窗口、事件
// 都取走了）才结束吞事件。放在取完按键事件之后调：这一下产生的单击要等双击
// 窗口过了才报出来，那时 encoder_is_idle() 还是 false，先被丢掉、下一轮才清。
static void update_swallow() {
    if (s_swallow_button && encoder_is_idle()) s_swallow_button = false;
}

void setup() {
    Serial.begin(115200);
    Serial.printf("[Knob] boot, firmware %s (%s)\n", FW_VERSION, BOARD_NAME);

    pinMode(BOOT_BTN_PIN, INPUT_PULLUP);
    display_init();
    encoder_init();
    build_ui();
    ble_on_display_update(on_display_update);
    ble_on_passkey(on_passkey);
    ble_on_paired(on_paired);
    ble_on_disconnected(on_disconnected);
    ble_on_ota(on_ota);
    // ble_init() 里才会算出广播名字（ble_get_device_name() 之后才有效），
    // 所以 redraw() 得放在它后面，不然刚开机那一下会显示空名字。
    ble_init();
    redraw(); // 刚开机、还没连上手机的初始画面

    Serial.println("[Knob] ready: rotate = local edit, click = next stop, long-press = confirm");
}

void loop() {
    // encoder_poll_button() 每次 loop() 都要跑，哪怕这次循环没有
    // 别的事情发生——它是唯一负责计算"按了多久"的地方。
    encoder_poll_button();
    check_boot_button_reset();

    // BLE 回调（跑在 NimBLE 后台任务线程上）置了这个标志位就说明状态
    // 变了、需要重绘——真正调 redraw()（LVGL 函数）只能在这个线程上做，
    // 见 s_needs_redraw 的注释。
    if (s_needs_redraw) {
        s_needs_redraw = false;
        redraw();
    }

    ble_ota_check_timeout(); // 升级卡住时放弃、屏幕切回正常画面（经 on_ota 的 FAILED）

    // 固件升级完成，"正在重启"显示一会儿后重启进新固件（见 on_ota）
    if (s_restart_at_ms != 0 && (int32_t)(millis() - s_restart_at_ms) >= 0) {
        Serial.println("[OTA] restarting into the new firmware");
        Serial.flush();
        ESP.restart();
    }

    // "配对成功"提示到时间了：如果提示期间手机推过新数据，直接应用；
    // 没有的话就先显示"已连接，等待数据"，等 on_display_update() 来了
    // 再刷新（走上面 SCREEN_NORMAL 分支）。
    if (s_screen_state == SCREEN_PAIRED_TOAST && millis() >= s_toast_until_ms) {
        if (s_display_pending) {
            strncpy(s_title, s_pending_title, sizeof(s_title) - 1);
            s_title[sizeof(s_title) - 1] = '\0';
            s_layout = s_pending_layout;
            s_value = s_pending_value;
            s_synced_value = s_pending_value;
            s_secondary_value = s_pending_secondary;
            s_min = s_pending_min;
            s_max = s_pending_max;
            s_sub_count = s_pending_sub_count;
            s_sub_index = s_pending_sub_index;
            s_have_display_data = true;
            s_display_pending = false;
        }
        s_screen_state = SCREEN_NORMAL;
        redraw();
    }

    // 驾驶页的进出（闲置计时、第一次操作唤醒）。唤醒用掉的转动量不再拿去改数值。
    int16_t diff = encoder_get_diff();
    // 用消抖后的状态：按键抖动、干扰不会把驾驶页误唤醒（再顺带吞掉一次真单击）
    bool btn_down = encoder_is_button_down();
    if (update_drive(diff, btn_down)) diff = 0;

    // 旋钮转动只在真正显示手机数据时才有意义（未连接/配对提示阶段
    // 没有可编辑的值，s_min/s_max 也可能是上一次连接时的残留）。
    if (diff != 0 && s_screen_state == SCREEN_NORMAL && s_have_display_data) {
        // 只改本地这份编辑副本、立刻重绘——不通知手机，转动过程中不会
        // 有蓝牙往返，也就没有转动和屏幕更新之间的延迟。s_min/s_max
        // 是手机上次同步时给的范围，本地直接夹住，不需要手机来回确认。
        // s_synced_value 不动，重绘时靠 s_value 是不是还等于
        // 它来判断这个数字该显示空心（没转过）还是实心（转过、待提交）。
        // 副驾半环是镜像的（越热越靠上 = 逆时针），旋钮也反过来：逆时针拧升温，
        // 亮点跟着手的方向走。见 ui/page_ac.cpp 的 half_angle()。
        if (s_layout == LAYOUT_AC_SPLIT_DEPUTY) diff = -diff;
        s_value = (int16_t)(s_value + diff);
        if (s_max > s_min) {
            if (s_value < s_min) s_value = s_min;
            if (s_value > s_max) s_value = s_max;
        }
        redraw();
        Serial.printf("[ENC] local value=%d\n", s_value);
    }

    // Tab/Enter 同样只在正常显示阶段才有意义——配对提示这 1 秒钟不接受
    // 交互，避免提示还没看清就被打断。ble_is_paired() 已经在
    // ble_send_key* 内部检查过一次，这里的 s_screen_state 检查是额外
    // 的交互层面限制，不是安全检查。
    //
    // 单击/双击对应的键码特意反过来分配（单击=CycleSub、双击=Tab），见
    // 文件顶部的说明：双击判定窗口没跟上时最坏也就是原地切一次子项，
    // 不会被误判成跳到完全不相关的下一个停靠点。
    //
    // 按键事件每次都要取走（不取会留到下次），但在驾驶页唤醒的那一下按键里
    // 发生的全部丢掉：这一下只唤醒，不切子项、不跳停靠点、不提交。
    bool click = encoder_take_click();
    bool double_click = encoder_take_double_click();
    bool long_press = encoder_take_long_press();
    if (s_swallow_button) {
        if (click || double_click || long_press) Serial.println("[BTN] swallowed (wake from drive page)");
        click = double_click = long_press = false;
    }
    update_swallow();

    if (click && s_screen_state == SCREEN_NORMAL) {
        Serial.println("[BTN] click -> CYCLE_SUB (座椅/分控子项切换)");
        ble_send_key(KEYCODE_CYCLE_SUB);
    }

    if (double_click && s_screen_state == SCREEN_NORMAL) {
        Serial.println("[BTN] double-click -> TAB (下一个停靠点)");
        ble_send_key(KEYCODE_TAB);
    }

    if (long_press && s_screen_state == SCREEN_NORMAL && s_have_display_data) {
        // 长按 = 确认：把本地编辑到的最终值带给手机，插件收到后才会
        // 真正调用车控接口——转动过程中不会触发任何车控调用，只有这
        // 一下会。
        Serial.printf("[BTN] long-press -> ENTER, value=%d\n", s_value);
        ble_send_key_with_value(KEYCODE_ENTER, s_value);
    }

    // 长按提交进度环（叠加层，redraw() 不会冲掉它，见函数自己的注释）
    check_enter_commit_progress();

    display_task_handler(); // 让 LVGL 把刚才的改动画出来
    delay(5);
}
