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
// 断开重连），不是一直都在显示手机推来的数据——见下面的 ScreenState。
// 方案见 docs/旋钮-最小硬件调试方案.md、docs/蓝牙控制配件-方案.md。

#include <Arduino.h>
#include <math.h>
#include <string.h>
#include "config.h"
#include "display.h"
#include "encoder.h"
#include "ble.h"
#include "fw_version_gen.h"

// 字体都用 lv_font_conv 1.5.3 生成（4bpp、不压缩、不勾 --lcd——lv_conf.h
// 没开 LV_USE_FONT_SUBPX），只嵌入上屏用到的字符，生成参数见各文件开头的
// Opts，字号对应设计稿 docs/设计/旋钮圆屏UI/README.md 的规格。
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

// 后面需要更新的 LVGL 控件都存成全局变量，这样 build_ui()
// （只创建一次）和 redraw()（数值变化时更新）才都能访问到。
static lv_obj_t *s_label_name;   // 标题（联动/主驾/座位名/配对码…），状态画面里也放次要文字
static lv_obj_t *s_label_value;  // 中间的主文字：单数字布局的大数字、升级百分比、状态画面的中文/设备名
static lv_obj_t *s_label_left;   // 双数字布局用：左边，固定对应主驾；配对码画面放前 3 位
static lv_obj_t *s_label_right;  // 双数字布局用：右边，固定对应副驾；配对码画面放后 3 位
static lv_obj_t *s_label_sub_left;  // 分控：左边数字下面的"主驾"
static lv_obj_t *s_label_sub_right; // 分控：右边数字下面的"副驾"
static lv_obj_t *s_label_hint;   // 底部提示（长按确认/继续按住/请勿断开…），也放座椅的加热/通风
static lv_obj_t *s_unit;         // 大数字后面的单位："°" 或 "%"

// 刻度环、座椅分段弧、各种进度环、座椅分页标记……所有"画出来"而不是文字的东西，
// 都在这一个全屏透明对象的绘制回调里按当前状态现画（见 dial_draw_cb），
// 不再拼一堆 lv_arc/lv_obj。redraw_*() 只负责选 s_dial_mode、摆文字。
// 视觉规格见 docs/设计/旋钮圆屏UI/README.md，下面的半径/角度都按那里的数值。
static lv_obj_t *s_dial;

enum DialMode {
    DIAL_NONE,
    DIAL_TEMP,    // 联动：270° 刻度环 + 当前值亮点；转动过还没提交时多一个空心点标原值
    DIAL_SPLIT,   // 分控：左右两个半环，左主驾、右副驾，左右镜像、温度越高越靠上
    DIAL_SEAT,    // 座椅：4 段弧（左通风、右加热，正上方是关）+ 底部一排分页标记
    DIAL_DOTTED,  // 未配对：一圈虚点
    DIAL_PASSKEY, // 配对码：淡蓝整圈 + 顶部一小段亮蓝
    DIAL_OK,      // 配对/连接成功：绿色整圈 + 对勾背后一团淡绿
    DIAL_OTA,     // 固件升级：整圈轨道 + 绿色进度 + 头部白点
};
// 下面这几个只在 loop() 线程上读写（redraw_*() 和绘制回调都在那里跑），
// 不用像 BLE 回调改的那些状态一样标 volatile。
static DialMode s_dial_mode = DIAL_NONE;

// 状态画面的线条图标，跟环一起在绘制回调里画（照设计稿的 24×24 线条
// 图标按比例放大，比字体里的实心图标更细、更统一）。
enum DialIcon { ICON_NONE, ICON_BLUETOOTH, ICON_CHECK };
static DialIcon s_icon = ICON_NONE;
static lv_color_t s_icon_color;
static int32_t s_icon_cy = 0;   // 图标中心的屏幕 y 坐标
static int32_t s_icon_size = 0; // 图标边长（对应设计稿 24×24 的那个框）

// 分控两组数字中间那道竖线的屏幕 x 坐标（随两边数字宽度变，由 redraw_split 算）
static int32_t s_split_divider_x = 0;

// 等待连接 / 等待数据时的转圈，也在 s_dial 里画（见 draw_spinner）。不用
// LVGL 自带的 lv_spinner：它一圈 1.5 秒、结尾和下一圈开头对不上，循环处
// 会跳一下。这里照 Material 的做法：一个大周期里整体匀速旋转 + 弧长按
// 缓动曲线伸缩，周期结束时头尾正好回到整数圈，循环无缝。
enum SpinnerStyle {
    SPINNER_CLASSIC,    // Material 经典款（Advance）：5.4 秒 4 次头追尾，弧长 20°~270° 快速伸缩
    SPINNER_EXPRESSIVE, // Material 3 Expressive（Retreat）：6 秒慢慢伸缩 10%~87% 圈，每 1.5 秒加速转 90°
};
static SpinnerStyle s_spinner_style = SPINNER_EXPRESSIVE; // 不是 const：firmware/simulate 渲染两种对比时会改它
static bool s_spinner_on = false;
static bool s_spinner_was_on = false; // 上一次 redraw 之前转圈是不是在显示（在就接着转，不从头来）
static uint32_t s_spinner_start_ms = 0;

// 长按提交（琥珀）/ BOOT 清配对（红）的整圈进度环，叠加在当前画面最外圈。
// 跟 s_dial_mode 分开存：redraw() 只改 s_dial_mode、不碰这两个，所以按住
// 期间不管手机回执触发几次 redraw()，进度环都不会被冲掉。-1 = 不显示。
static int s_overlay_percent = -1;
static lv_color_t s_overlay_color;

// 屏幕当前处在哪个阶段。除了"显示手机推来的数据"这个主状态，还要
// 覆盖配对码/配对成功提示/未连接这几种状态，所以单独做一个简单状态机，
// 不是直接把这些揉进 s_title/s_value 里、靠字符串规则区分。
enum ScreenState {
    SCREEN_DISCONNECTED, // 未连接：显示配对码（正在配对时）/ 设备名+"未配对"（从没配对过）/ "等待连接"（配过、暂时断连）
    SCREEN_PAIRED_TOAST, // 刚配对/连接成功，显示提示 1 秒
    SCREEN_NORMAL,       // 显示手机同步过来的数据
    SCREEN_OTA,          // 固件升级中：进度环 + 百分比，不响应旋钮操作，完成后重启
};
// 除了字符缓冲区（下面单独说明），这个状态机相关的变量都要标
// volatile：NimBLE 的连接/配对回调（on_passkey/on_paired/on_disconnected/
// on_display_update）跑在 NimBLE 自己的后台任务线程上，不是 loop() 那个
// 线程，不标 volatile 的话 loop() 读到的可能是编译器缓存的旧值，看起来
// 就像"状态明明该变了、屏幕却没反应"。
static volatile ScreenState s_screen_state = SCREEN_DISCONNECTED;

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

// 固件升级进度（0~100）和"升级完成、什么时候重启"。重启不在 OTA 回调里
// 直接做：留一点时间让"完成"通知发给手机、屏幕显示出"正在重启"，到点
// 由 loop() 调 ESP.restart()。0 = 没有待执行的重启。
static volatile int s_ota_percent = 0;
static volatile bool s_ota_done = false;
static volatile uint32_t s_restart_at_ms = 0;
constexpr uint32_t OTA_RESTART_DELAY_MS = 1500;

// 正在配对时，手机和固件两边应该显示同一个 6 位确认码；没有正在配对
// 时是 -1。已配对过的设备重连不会再走一遍配对流程，也就不会再有这个码
// （见 ble_on_passkey 的注释）。
static volatile int32_t s_pending_passkey = -1;

// 这次连接有没有真的走过配对码确认——onConfirmPassKey 触发过才是真配对，
// 已配对设备直接用保存的密钥重连不会走这一步。on_paired() 用这个字段
// 区分该显示"配对成功"还是"连接成功"，on_disconnected() 里清零，
// 保证每次连接周期都是从头判断，不会用上一轮的结果。
static volatile bool s_paired_via_confirm = false;

// 当前停靠点用哪种画法——手机推来的 "layout" 字段直接决定，不是靠
// "有没有某个字段"去反推。ac_link/seat 是单数字布局（共用 s_label_value）；
// ac_split_main/ac_split_deputy 是双数字布局（共用 s_label_left/right，
// 哪边大、哪边可编辑由 layout 决定，位置固定：左=主驾，右=副驾）。
enum DisplayLayout {
    LAYOUT_AC_LINK,
    LAYOUT_AC_SPLIT_MAIN,
    LAYOUT_AC_SPLIT_DEPUTY,
    LAYOUT_SEAT,
    LAYOUT_NONE,
};

static DisplayLayout parse_layout(const char *s) {
    if (strcmp(s, "ac_link") == 0) return LAYOUT_AC_LINK;
    if (strcmp(s, "ac_split_main") == 0) return LAYOUT_AC_SPLIT_MAIN;
    if (strcmp(s, "ac_split_deputy") == 0) return LAYOUT_AC_SPLIT_DEPUTY;
    if (strcmp(s, "seat") == 0) return LAYOUT_SEAT;
    return LAYOUT_NONE;
}

// 当前正在编辑的这一路数值——手机每次同步（切停靠点/长按确认后的
// 回执）会覆盖这几个字段；转动旋钮只改 s_value 这一个字段（不管当前是
// 单数字还是双数字布局，"正在被编辑的那个数字"永远是 s_value，双数字
// 布局下另一侧的参考数字放在 s_secondary_value，转动不会碰它），不碰
// title/layout/min/max，也不会发消息给手机。s_title 这个字符缓冲区不标
// volatile——strncpy 系操作跟 volatile 指针配合很别扭，这里的一致性靠
// s_screen_state/s_display_pending 的读写顺序保证（BLE 回调写完 title
// 才会翻转对应的标志位，loop() 先看到标志位翻转才会去读 title），够用，
// 不需要上更重的同步机制。
static char s_title[DisplayData::MAX_STR_LEN + 1] = "";
static volatile DisplayLayout s_layout = LAYOUT_NONE;
static volatile int16_t s_value = 0;           // 正在编辑的数字，转动直接改这个
static volatile int16_t s_synced_value = 0;    // s_value 上一次跟手机同步时的起点，用来判断有没有被转动过（脏/干净，决定空心/实心）
static volatile int16_t s_secondary_value = 0; // 双数字布局下另一侧的参考数字，只读，转动不碰它
static volatile int16_t s_min = 0;
static volatile int16_t s_max = 0;
static volatile bool s_have_display_data = false; // 有没有收到过手机推来的真实数据

// 底部子页指示点用：sub_count<=1 表示当前停靠点不是复合停靠点，一个
// 点都不画；否则画 sub_count 个点，sub_index（0-based）那个是白色。
static volatile int16_t s_sub_count = 0;
static volatile int16_t s_sub_index = 0;

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

// 配色（见 docs/设计/旋钮圆屏UI/README.md 的规格）。用函数而不是全局 const
// 变量构造颜色——LV_COLOR_MAKE 在文件作用域展开出来能不能当全局初始值用，
// 这份代码库里没有先例，干脆每次用的时候现算，开销可以忽略。
static lv_color_t color_bg() { return LV_COLOR_MAKE(0x0A, 0x0C, 0x0F); }
static lv_color_t color_text() { return LV_COLOR_MAKE(0xF2, 0xF4, 0xF7); }     // 主文字、当前值亮点
static lv_color_t color_text2() { return LV_COLOR_MAKE(0x8A, 0x93, 0xA0); }    // 标题、参考数字、次要文字
static lv_color_t color_text3() { return LV_COLOR_MAKE(0x4A, 0x51, 0x5C); }    // 未选中的座位、虚点
static lv_color_t color_tick_off() { return LV_COLOR_MAKE(0x2B, 0x31, 0x3A); } // 还没走到的刻度
static lv_color_t color_track() { return LV_COLOR_MAKE(0x23, 0x28, 0x30); }    // 进度环/座椅分段的底色
// 琥珀色只表示"还没提交"：转动过、还没长按确认的数字，以及长按提交进度环
static lv_color_t color_pending() { return LV_COLOR_MAKE(0xF7, 0xC9, 0x48); }
static lv_color_t color_ok() { return LV_COLOR_MAKE(0x34, 0xD3, 0x99); } // 连接成功、固件升级
static lv_color_t color_blue() { return LV_COLOR_MAKE(0x3B, 0x82, 0xF6); }
static lv_color_t color_cyan() { return LV_COLOR_MAKE(0x22, 0xD3, 0xEE); }
static lv_color_t color_coral() { return LV_COLOR_MAKE(0xFF, 0x7A, 0x45); }
static lv_color_t color_red() { return LV_COLOR_MAKE(0xF0, 0x41, 0x4D); }

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

// 座椅档位的颜色跟 4 段弧一一对应：-2 蓝、-1 青（通风），+1 珊瑚、+2 红（加热）
static lv_color_t seat_level_color(int16_t level) {
    if (level <= -2) return color_blue();
    if (level == -1) return color_cyan();
    if (level == 1) return color_coral();
    if (level >= 2) return color_red();
    return color_text2(); // 0 = 关
}

// ---------- 绘制小工具：坐标都是屏幕绝对坐标，角度 0° 指右、顺时针为正 ----------

constexpr int32_t DIAL_CX = CONFIG_SCREEN_HOR_RES / 2;
constexpr int32_t DIAL_CY = CONFIG_SCREEN_VER_RES / 2;
constexpr float DIAL_MARKER_R = 93.0f; // 当前值亮点/原值空心点所在的半径（刻度环内侧）

static void polar(float r, float deg, int32_t *x, int32_t *y) {
    float a = deg * 0.017453293f;
    *x = DIAL_CX + (int32_t)lroundf(r * cosf(a));
    *y = DIAL_CY + (int32_t)lroundf(r * sinf(a));
}

static void draw_tick(lv_layer_t *layer, float r0, float r1, float deg, lv_color_t color, int32_t width,
                      lv_opa_t opa = LV_OPA_COVER) {
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

// 实心圆点（diameter 是直径）；border > 0 时画成空心圆圈
static void draw_dot(lv_layer_t *layer, int32_t cx, int32_t cy, int32_t diameter, lv_color_t color,
                     lv_opa_t opa = LV_OPA_COVER, int32_t border = 0) {
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

// r 是线条中心的半径（LVGL 的 radius 是外沿，这里换算一下），从 a0 顺时针画到 a1
static void draw_ring(lv_layer_t *layer, float r, int32_t width, int32_t a0, int32_t a1, lv_color_t color,
                      bool rounded, lv_opa_t opa = LV_OPA_COVER) {
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

// 从正上方顺时针的整圈进度（固件升级、长按提交、清配对都是这个画法）
static void draw_progress_ring(lv_layer_t *layer, float r, int32_t width, int percent, lv_color_t color) {
    if (percent <= 0) return;
    if (percent > 100) percent = 100;
    draw_ring(layer, r, width, 270, 270 + percent * 360 / 100, color, true);
}

// ---------- 各画面的环 ----------

// 联动：135° 起顺时针扫 270°（缺口朝下）。范围不大（空调 17~33）时每 0.5 度
// 一根刻度、整数度的长一点；走过的刻度按自己的温度取冷暖色，没走到的暗灰。
static float temp_angle(float v) { return 135.0f + (v - s_min) * 270.0f / (s_max - s_min); }

static void draw_temp_dial(lv_layer_t *layer) {
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

// 分控：左半环 135°→260° 是主驾、右半环 45°→-80° 是副驾，左右镜像，两边
// 都是越热越靠上。右半环升温是逆时针走，所以编辑副驾时旋钮也反过来拧
// （逆时针升温，见 loop() 里处理 encoder_get_diff() 的地方），亮点始终跟着
// 手转的方向走。
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
    bool main_editing = (s_layout == LAYOUT_AC_SPLIT_MAIN);
    draw_half_ring(layer, true, main_editing ? s_value : s_secondary_value, main_editing);
    draw_half_ring(layer, false, main_editing ? s_secondary_value : s_value, !main_editing);
}

// 座椅：-2/-1 在左（通风），+1/+2 在右（加热），正上方 270° 一根刻线表示"关"；
// 从 0 往当前档位那一侧填色。底部一排分页标记，顺序跟插件 seatSlots 一致
// （主驾→副驾→左后→右后）：当前那个是 16px 宽的白色胶囊，其余是 6px 灰点。
// 是哪个座位主要靠顶部的大号座位名，标记只表示"一共几个、轮到第几个"。
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

// 显示转圈：之前没在转就从头开始，已经在转（比如等待画面又被 redraw 了一次）
// 就接着转，不让它跳回起点。
static void start_spinner() {
    s_spinner_on = true;
    if (!s_spinner_was_on) s_spinner_start_ms = lv_tick_get();
}

// 转圈每一帧都在变：按刷新节奏让 s_dial 重画（只在转圈显示的时候）
static void spinner_tick_cb(lv_timer_t *) {
    if (s_spinner_on) lv_obj_invalidate(s_dial);
}

static void dial_draw_cb(lv_event_t *e) {
    lv_layer_t *layer = lv_event_get_layer(e);
    bool ranged = s_max > s_min;
    int32_t x, y;
    switch (s_dial_mode) {
        case DIAL_NONE:
            break;
        case DIAL_TEMP:
            if (ranged) draw_temp_dial(layer);
            break;
        case DIAL_SPLIT: {
            if (ranged) draw_split_dial(layer);
            lv_draw_line_dsc_t d;
            lv_draw_line_dsc_init(&d);
            d.color = color_track();
            d.width = 1;
            d.p1.x = d.p2.x = s_split_divider_x;
            d.p1.y = 101;
            d.p2.y = 165;
            lv_draw_line(layer, &d);
            break;
        }
        case DIAL_SEAT:
            draw_seat_dial(layer);
            break;
        case DIAL_DOTTED:
            for (int deg = 0; deg < 360; deg += 5) {
                polar(112, deg, &x, &y);
                draw_dot(layer, x, y, 3, color_text3());
            }
            break;
        case DIAL_PASSKEY:
            draw_ring(layer, 112, 2, 0, 360, color_blue(), false, LV_OPA_40);
            draw_ring(layer, 112, 3, 250, 290, color_blue(), true);
            break;
        case DIAL_OK:
            draw_ring(layer, 110, 3, 0, 360, color_ok(), false);
            draw_dot(layer, DIAL_CX, s_icon_cy, 52, color_ok(), 36); // 对勾背后，不透明度约 14%
            break;
        case DIAL_OTA:
            draw_ring(layer, 110, 5, 0, 360, color_track(), false);
            draw_progress_ring(layer, 110, 5, s_ota_percent, color_ok());
            if (s_ota_percent > 0) {
                polar(110, 270 + s_ota_percent * 360 / 100, &x, &y);
                draw_dot(layer, x, y, 11, color_text());
            }
            break;
    }
    if (s_spinner_on) draw_spinner(layer);
    draw_icon(layer);
    if (s_overlay_percent >= 0) draw_progress_ring(layer, 117, 4, s_overlay_percent, s_overlay_color);
}

// 几个屏幕状态各自的重绘逻辑，统一由 redraw() 按 s_screen_state 分发。
// 每个画面都从"全部隐藏"开始，只把自己要的控件打开、摆好位置——不用
// 记上一个画面留下了什么。环/刻度/图标这些由 s_dial 的绘制回调按
// s_dial_mode/s_icon 画。
//
// 下面的 y 坐标都是屏幕绝对坐标（0~240），直接对应设计稿：文字给的是
// 这一行的竖直中心，数字给的是基线（数字底边）——数字字号不一样时按
// 基线对齐才齐，而且字体换了也不用重新量。
constexpr int32_t TITLE_CY = 58;     // 标题
constexpr int32_t NUMBER_BASE = 155; // 大数字（92px）的基线
constexpr int32_t HINT_CY = 199;     // 底部提示

// 长按提交按住期间（进度环在画）为 true，联动/分控的底部提示从"长按确认"
// 换成"继续按住"。只由 check_enter_commit_progress() 改，跟它一样跑在
// loop() 线程上。
static bool s_commit_holding = false;

static void reset_screen(DialMode mode) {
    lv_obj_t *const all[] = {s_label_name, s_label_value, s_label_left, s_label_right, s_label_sub_left,
                             s_label_sub_right, s_label_hint, s_unit};
    for (lv_obj_t *o : all) lv_obj_add_flag(o, LV_OBJ_FLAG_HIDDEN);
    s_spinner_was_on = s_spinner_on;
    s_spinner_on = false;
    s_dial_mode = mode;
    s_icon = ICON_NONE;
    lv_obj_invalidate(s_dial);
}

static void style_label(lv_obj_t *label, const lv_font_t *font, lv_color_t color, int32_t letter_space) {
    lv_obj_set_style_text_font(label, font, 0);
    lv_obj_set_style_text_color(label, color, 0);
    lv_obj_set_style_text_letter_space(label, letter_space, 0);
    lv_obj_remove_flag(label, LV_OBJ_FLAG_HIDDEN);
}

// 一行文字：水平中心在 (屏幕中心 + dx)，竖直中心在屏幕 y = cy
static void place_text(lv_obj_t *label, const char *text, const lv_font_t *font, lv_color_t color,
                       int32_t letter_space, int32_t dx, int32_t cy) {
    lv_label_set_text(label, text);
    style_label(label, font, color, letter_space);
    lv_obj_align(label, LV_ALIGN_TOP_MID, dx, cy - lv_font_get_line_height(font) / 2);
}

// 数字：水平中心在 (屏幕中心 + dx)，基线在屏幕 y = base
static void place_digits(lv_obj_t *label, const char *text, const lv_font_t *font, lv_color_t color,
                         int32_t letter_space, int32_t dx, int32_t base) {
    lv_label_set_text(label, text);
    style_label(label, font, color, letter_space);
    lv_obj_align(label, LV_ALIGN_TOP_MID, dx, base - (lv_font_get_line_height(font) - font->base_line));
}

static void place_title(const char *text) { place_text(s_label_name, text, &font_zh_16, color_text2(), 3, 0, TITLE_CY); }

static void place_hint(const char *text, lv_color_t color, int32_t cy) {
    place_text(s_label_hint, text, &font_zh_13, color, 2, 0, cy);
}

static void place_icon(DialIcon icon, lv_color_t color, int32_t size, int32_t cy) {
    s_icon = icon;
    s_icon_color = color;
    s_icon_size = size;
    s_icon_cy = cy;
}

// 92px 大数字，unit 非空时右上角挂一个 28px 的单位（"°" 或 "%"）。居中的
// 是数字本身——视觉重心在数字上，单位只是挂在旁边，不参与居中。
static void place_big_number(int value, lv_color_t color, const char *unit) {
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

static void redraw_disconnected() {
    if (s_pending_passkey >= 0) {
        // 正在配对：6 位码拆成 3+3 两段方便跟手机核对（数字字体里没有空格
        // 字形，所以用左右两个标签，中间留 14px）。
        reset_screen(DIAL_PASSKEY);
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
        reset_screen(DIAL_DOTTED);
        place_icon(ICON_BLUETOOTH, color_blue(), 26, 65);
        place_digits(s_label_value, ble_get_device_name(), &font_num_34, color_text(), 0, 0, 129);
        place_text(s_label_name, "未配对", &font_zh_16, color_text2(), 3, 0, 154);
    } else {
        // 已经跟某个设备配对过，现在只是暂时没连上（杀 App、暂时超出
        // 范围……）——配对信息（LTK）早就还在，不需要用户做任何事，等它
        // 自动重连就行，"等待连接"就是唯一要传达的信息。
        reset_screen(DIAL_NONE);
        start_spinner();
        place_icon(ICON_BLUETOOTH, color_text2(), 40, 90);
        place_text(s_label_value, "等待连接", &font_zh_20, color_text(), 3, 0, 142);
    }
}

static void redraw_paired_toast() {
    // 真的走过配对码确认才是"配对成功"；已配对设备用保存的密钥重新
    // 连接（比如手机 App 被杀后台又重启）没有这一步，应该说"连接成功"，
    // 不然会让人误以为每次连接都要重新配对。
    reset_screen(DIAL_OK);
    place_icon(ICON_CHECK, color_ok(), 30, 98);
    place_text(s_label_value, s_paired_via_confirm ? "配对成功" : "连接成功", &font_zh_20, color_text(), 3, 0, 150);
}

// 双数字布局（ac_split_main/ac_split_deputy）：左边固定是主驾、右边固定是
// 副驾（跟真实驾驶位左右对应，不随哪个是"大的"而互换位置）。正在编辑
// 的那一侧 80px、跟着转动变琥珀色，另一侧 44px 灰色只读；两个数字按基线
// 对齐，下面各有一个小标签，中间一道竖线。
static void redraw_split(bool dirty) {
    reset_screen(DIAL_SPLIT);
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

    if (s_commit_holding) {
        place_hint("继续按住", color_pending(), HINT_CY);
    } else if (dirty) {
        place_hint("长按确认", color_pending(), HINT_CY);
    }
}

static void redraw_normal() {
    if (!s_have_display_data) {
        // 已经连上了，但手机还没推过任何数据（比如刚配对完，提示消失
        // 时手机还没来得及同步）——不能显示上一次残留的 title/value，
        // 那可能是断线之前的旧值，容易让人误以为是当前状态。
        reset_screen(DIAL_NONE);
        start_spinner();
        place_text(s_label_name, "已连接", &font_zh_16, color_text2(), 3, 0, 100);
        place_text(s_label_value, "等待数据...", &font_zh_20, color_text(), 3, 0, 132);
        return;
    }

    // 转动过、还没长按提交（跟手机上次同步的起点不一样）就是琥珀色，
    // 静止时是白色。
    bool dirty = (s_value != s_synced_value);

    switch (s_layout) {
        case LAYOUT_AC_SPLIT_MAIN:
        case LAYOUT_AC_SPLIT_DEPUTY:
            redraw_split(dirty);
            break;
        case LAYOUT_AC_LINK:
            reset_screen(DIAL_TEMP);
            place_title(s_title);
            place_big_number(s_value, dirty ? color_pending() : color_text(), "°");
            if (s_commit_holding) {
                place_hint("继续按住", color_pending(), HINT_CY);
            } else if (dirty) {
                place_hint("长按确认", color_pending(), HINT_CY);
            }
            break;
        case LAYOUT_SEAT: {
            // 中间是档位大小，下面一行写明加热/通风（颜色跟亮起的那段弧一致）；
            // 0 档只写一个"关"。底部是分页标记，放不下提示文字，没提交只靠
            // 数字变琥珀色表示。座位名比别的页标题大一号、用白色——四个座位
            // 共用一个页面，转到哪个座位全靠它认。
            reset_screen(DIAL_SEAT);
            place_text(s_label_name, s_title, &font_zh_28, color_text(), 3, 0, TITLE_CY);
            if (s_value == 0) {
                place_text(s_label_value, "关", &font_zh_44, dirty ? color_pending() : color_text2(), 0, 0, 120);
            } else {
                lv_color_t c = seat_level_color(s_value);
                place_big_number(s_value < 0 ? -s_value : s_value, dirty ? color_pending() : c, nullptr);
                place_text(s_label_hint, s_value > 0 ? "加热" : "通风", &font_zh_16, c, 3, 0, 168);
            }
            break;
        }
        case LAYOUT_NONE:
            reset_screen(DIAL_NONE);
            place_text(s_label_name, s_title, &font_zh_20, color_text(), 3, 0, DIAL_CY);
            break;
    }
}

// 固件升级中：整圈进度环 + 中间大字百分比，完成后显示"正在重启"。
static void redraw_ota() {
    reset_screen(DIAL_OTA);
    place_title("固件升级");
    if (s_ota_done) {
        place_text(s_label_value, "正在重启", &font_zh_20, color_text(), 3, 0, DIAL_CY);
    } else {
        place_big_number(s_ota_percent, color_text(), "%");
        place_hint("请勿断开", color_text2(), 187);
    }
}

static void redraw() {
    switch (s_screen_state) {
        case SCREEN_DISCONNECTED: redraw_disconnected(); break;
        case SCREEN_PAIRED_TOAST: redraw_paired_toast(); break;
        case SCREEN_NORMAL: redraw_normal(); break;
        case SCREEN_OTA: redraw_ota(); break;
    }
}

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

    // 升级期间插件会暂停显示同步；万一还是来了页面数据，也不能把升级
    // 画面切掉（亮度上面已经处理过，不受影响）。
    if (s_screen_state == SCREEN_OTA) return;

    DisplayLayout layout = parse_layout(data.getString("layout", "ac_link"));
    const char *title = data.getString("title");
    int16_t min = data.getInt("min");
    int16_t max = data.getInt("max");
    // 复合停靠点（座椅切座位、空调分控切主副驾）才会带这两个字段，不是
    // 复合停靠点时插件不发，默认值 0 表示"不用画指示点"，见 draw_seat_dial()。
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

// 创建所有控件，只在 setup() 里调用一次——具体显示什么、摆在哪交给
// redraw_*()，这里不写死初始文案和位置。
static void build_ui() {
    lv_obj_t *scr = lv_screen_active(); // 这个 demo 只有这一个页面
    lv_obj_set_style_bg_color(scr, color_bg(), 0);

    // 全屏透明的绘制层，先建它，后面建的文字才会盖在它上面。
    s_dial = lv_obj_create(scr);
    lv_obj_remove_style_all(s_dial);
    lv_obj_set_size(s_dial, CONFIG_SCREEN_HOR_RES, CONFIG_SCREEN_VER_RES);
    lv_obj_center(s_dial);
    lv_obj_remove_flag(s_dial, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_add_event_cb(s_dial, dial_draw_cb, LV_EVENT_DRAW_MAIN, NULL);

    // 转圈要按刷新节奏一直重画（见 spinner_tick_cb）
    lv_timer_create(spinner_tick_cb, LV_DEF_REFR_PERIOD, NULL);

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

    reset_screen(DIAL_NONE);
}

// 长按提交 / BOOT 清配对的进度环（见 s_overlay_percent），percent = -1 收起。
static void set_overlay(int percent, lv_color_t color) {
    if (percent == s_overlay_percent && (percent < 0 || lv_color_eq(color, s_overlay_color))) return;
    s_overlay_percent = percent;
    s_overlay_color = color;
    lv_obj_invalidate(s_dial);
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
// 推一份新数据回来）会在同一次按住期间触发 redraw_normal()——进度环存在
// s_overlay_percent 里、redraw() 不碰它，所以回执重绘只换底下的数字，
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
    if (s_screen_state != SCREEN_NORMAL || !s_have_display_data) {
        // 没有可编辑内容（未连接/配对提示/还没收到过数据）时不显示，
        // 这些状态下长按也不会真正触发提交。
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

    // 旋钮转动只在真正显示手机数据时才有意义（未连接/配对提示阶段
    // 没有可编辑的值，s_min/s_max 也可能是上一次连接时的残留）。
    int16_t diff = encoder_get_diff();
    if (diff != 0 && s_screen_state == SCREEN_NORMAL && s_have_display_data) {
        // 只改本地这份编辑副本、立刻重绘——不通知手机，转动过程中不会
        // 有蓝牙往返，也就没有转动和屏幕更新之间的延迟。s_min/s_max
        // 是手机上次同步时给的范围，本地直接夹住，不需要手机来回确认。
        // s_synced_value 不动，redraw_normal() 靠 s_value 是不是还等于
        // 它来判断这个数字该显示空心（没转过）还是实心（转过、待提交）。
        // 副驾半环是镜像的（越热越靠上 = 逆时针），旋钮也反过来：逆时针拧升温，
        // 亮点跟着手的方向走。见 half_angle()。
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
    if (encoder_take_click() && s_screen_state == SCREEN_NORMAL) {
        Serial.println("[BTN] click -> CYCLE_SUB (座椅/分控子项切换)");
        ble_send_key(KEYCODE_CYCLE_SUB);
    }

    if (encoder_take_double_click() && s_screen_state == SCREEN_NORMAL) {
        Serial.println("[BTN] double-click -> TAB (下一个停靠点)");
        ble_send_key(KEYCODE_TAB);
    }

    if (encoder_take_long_press() && s_screen_state == SCREEN_NORMAL && s_have_display_data) {
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
