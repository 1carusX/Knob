// Knob 固件。
// 屏幕显示"当前正在编辑的一个停靠点"（联动/分控-主驾/分控-副驾/座椅），
// 手机每次切停靠点/提交后回执都会推一份新的过来当起点；转动旋钮只改
// 本地这份副本、立刻重绘，不再每转一档就等手机一次——避免了转动和屏幕
// 更新之间的蓝牙往返延迟。单击（Tab）切到下一个停靠点，长按（Enter）
// 才把编辑到的最终值带给手机去真正应用（对应插件那边只有 Enter 会调用
// 车控接口，转动期间不会触发大量真实车控调用）。双击（CycleSub）用来
// 在"复合停靠点"内部切换子项（比如座椅切主驾/副驾/左后/右后，空调分控
// 切主驾/副驾），固件这边无条件识别双击、不知道当前停靠点是不是复合
// 停靠点——要不要响应交给手机插件根据 currentPageId 决定，见
// encoder_take_double_click 的注释。
//
// 屏幕同时还要同步配对/连接状态（未连接、正在配对、刚配对成功、
// 断开重连），不是一直都在显示手机推来的数据——见下面的 ScreenState。
// 方案见 docs/旋钮-最小硬件调试方案.md、docs/蓝牙控制配件-方案.md。

#include <Arduino.h>
#include <string.h>
#include "config.h"
#include "display.h"
#include "encoder.h"
#include "ble.h"

// 中文字体（font_zh_36.c，lvgl 官方字体转换器生成，只嵌入了固件实际
// 用到的汉字，NotoSerifCJKsc-Regular 生成）。原来还有一份 font_zh_18
// 给小标题用，这次标题改成两倍大直接复用这一份 36px 的，18px 那份就
// 删了。这份字符集最初是给旧版"空调温度/主驾/副驾/首页"这套标题文案
// 定的，这次重新生成时把"联/动/副"也加进去了——旧字符集里没有这三个
// 字，"联动"/"副驾"这两个新版标题字符串之前会因为缺字直接显示不出来，
// 是这次才发现并顺手修的。
LV_FONT_DECLARE(font_zh_36);
// 数字专用大字号，JetBrains Mono（用户自己生成，符号集是 "0123456789-"，
// 不含中文，不用像素放大变换，原生尺寸渲染）。单数字布局（ac_link/seat）
// 和双数字布局的大数字都用 Extra Bold 96px；双数字布局里那个小一点的
// 参考数字用 Bold 48px。
LV_FONT_DECLARE(font_num_extra_bold_96);
LV_FONT_DECLARE(font_num_bold_48);

// 后面需要更新的 LVGL 控件都存成全局变量，这样 build_ui()
// （只创建一次）和 redraw()（数值变化时更新）才都能访问到。
static lv_obj_t *s_label_name;   // 小标题（联动/主驾/副驾/座椅档位名/配对提示…）
static lv_obj_t *s_label_value;  // 单数字布局（ac_link/seat）用的大号数字
static lv_obj_t *s_label_left;   // 双数字布局用：左边，固定对应主驾
static lv_obj_t *s_label_right;  // 双数字布局用：右边，固定对应副驾
static lv_obj_t *s_arc;

// 复合停靠点（比如座椅切 4 个座位、空调分控切主驾/副驾）用的底部子页
// 指示点——n 个点代表有 n 个子页，选中的那个白色，其它灰色。数量按
// MainPlugin 目前的实际子页数（座椅 4 个）留够上限；不是复合停靠点时
// （sub_count<=1）全部隐藏，见 update_sub_dots()。
constexpr int MAX_SUB_DOTS = 4;
static lv_obj_t *s_sub_dots[MAX_SUB_DOTS];

// 屏幕当前处在哪个阶段。除了"显示手机推来的数据"这个主状态，还要
// 覆盖配对码/配对成功提示/未连接这几种状态，所以单独做一个简单状态机，
// 不是直接把这些揉进 s_title/s_value 里、靠字符串规则区分。
enum ScreenState {
    SCREEN_DISCONNECTED, // 未连接：显示配对码（正在配对时）/ 设备名+"未配对"（从没配对过）/ "等待连接"（配过、暂时断连）
    SCREEN_PAIRED_TOAST, // 刚配对/连接成功，显示提示 1 秒
    SCREEN_NORMAL,       // 显示手机同步过来的数据
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

// 蓝 -> 青 -> 橙 -> 红，四段色标均匀分布在 [min,max] 上——车机空调/暖通
// 面板最常见的冷暖配色（蓝/青读"冷"，橙/红读"热"，单调过渡，不会像
// 之前那版"浅蓝->深蓝"先变深再跳到暖色那样显得中间有一段莫名其妙的
// "变暗"）。数值取自 Material Design 的标准色板（Blue 500 / Cyan 300 /
// Orange 400 / Red 600），饱和度和明度搭配过，认知度高、观感专业。
// 两个布局共用同一套渐变：
//   空调温度（min>=0，比如 17~33）：低温蓝/青、高温橙/红。
//   座椅（min<0，比如 -2~2，0=关闭居中）：通风那一侧落在蓝/青这两段，
//   加热那一侧落在橙/红这两段，"关闭"正好卡在青到橙的过渡区——但那
//   时候指示条长度是 0（见 redraw 的对称模式），这段颜色根本不会被
//   画出来，不影响观感。
static lv_color_t gradient_color(int16_t value, int16_t vmin, int16_t vmax) {
    static const lv_color_t stops[4] = {
        LV_COLOR_MAKE(0x21, 0x96, 0xF3), // 蓝（Material Blue 500）
        LV_COLOR_MAKE(0x4D, 0xD0, 0xE1), // 青（Material Cyan 300）
        LV_COLOR_MAKE(0xFF, 0xA7, 0x26), // 橙（Material Orange 400）
        LV_COLOR_MAKE(0xE5, 0x39, 0x35), // 红（Material Red 600）
    };
    if (vmax <= vmin) return stops[0];
    float t = (float)(value - vmin) / (float)(vmax - vmin); // 0..1
    if (t < 0.0f) t = 0.0f;
    if (t > 1.0f) t = 1.0f;
    float seg = t * 3.0f; // 落在四段里的哪一段：0..3
    int idx = (int)seg;
    if (idx > 2) idx = 2;
    float frac = seg - idx;
    // lv_color_mix(c1, c2, mix)：mix=0 时是纯 c2，mix=255 时是纯 c1。
    // frac=0 时要纯 stops[idx]，frac=1 时要纯 stops[idx+1]。
    return lv_color_mix(stops[idx + 1], stops[idx], (uint8_t)(frac * 255));
}

// "空心/实心"效果——font_num_extra_bold_96/font_num_bold_48 都是转换好
// 的位图字体，没有现成的描边/镂空样式可用（LVGL label 本身也没有
// outline 这种 style），
// 真要做矢量描边得额外生成一版带描边的字体资源。这里先用同一套字体，
// 靠颜色/透明度模拟同样的语义：脏（转动过、还没提交）用全白高亮；干净
// （跟手机上次同步的值一致）用较暗的灰白。以后要做成真正的镂空描边，
// 得单独出一版描边字体再换这里的实现，视觉上先用这个凑合但不影响
// 判断"这个数字改没改"这件事本身。
// 用函数而不是全局 const 变量构造这几个颜色——LV_COLOR_MAKE 这个宏
// 在文件作用域（不在函数体内）展开出来的表达式能不能当全局初始值用，
// 这份代码库里没有先例（其它地方都是在函数体内构造颜色），干脆用函数
// 调用规避这个不确定性，每次用的时候现算，反正就是几个字段赋值，
// 开销可以忽略。
static lv_color_t color_solid() { return LV_COLOR_MAKE(0xFF, 0xFF, 0xFF); }
static lv_color_t color_hollow() { return LV_COLOR_MAKE(0x66, 0x66, 0x66); }
static lv_color_t color_reference() { return LV_COLOR_MAKE(0x88, 0x88, 0x88); } // 双数字布局里"另一侧、不可编辑"的固定颜色

// 按 s_sub_count/s_sub_index 摆放/显示底部指示点，选中的白色、其它灰色；
// s_sub_count<=1（不是复合停靠点，或者只有一个子项没什么好切的）时
// 全部隐藏。点的总宽度随 count 变化，每次都重新居中，不是固定位置。
static void update_sub_dots() {
    int count = s_sub_count;
    if (count > MAX_SUB_DOTS) count = MAX_SUB_DOTS; // 保护性截断，理论上不会发生
    if (count <= 1) {
        for (int i = 0; i < MAX_SUB_DOTS; i++) lv_obj_add_flag(s_sub_dots[i], LV_OBJ_FLAG_HIDDEN);
        return;
    }
    constexpr int32_t DOT_SIZE = 10;
    constexpr int32_t DOT_PITCH = 20; // 点心到点心的间距（大小 + 间隔）
    int32_t total_width = (count - 1) * DOT_PITCH + DOT_SIZE;
    int32_t start_x = -total_width / 2 + DOT_SIZE / 2;
    for (int i = 0; i < count; i++) {
        lv_obj_t *dot = s_sub_dots[i];
        lv_obj_remove_flag(dot, LV_OBJ_FLAG_HIDDEN);
        lv_obj_align(dot, LV_ALIGN_BOTTOM_MID, start_x + i * DOT_PITCH, -14);
        lv_obj_set_style_bg_color(dot, i == s_sub_index ? color_solid() : color_hollow(), 0);
    }
    for (int i = count; i < MAX_SUB_DOTS; i++) lv_obj_add_flag(s_sub_dots[i], LV_OBJ_FLAG_HIDDEN);
}

// 三个屏幕状态各自的重绘逻辑，统一由 redraw() 按 s_screen_state 分发。
// 数值圆弧只有在真正显示手机数据时才有意义，其它状态一律隐藏，屏幕
// 中间只剩两行文字。

static void reset_label_value_style() {
    lv_obj_set_style_text_font(s_label_value, &font_zh_36, 0);
}

static void redraw_disconnected() {
    lv_obj_add_flag(s_arc, LV_OBJ_FLAG_HIDDEN);
    lv_obj_add_flag(s_label_left, LV_OBJ_FLAG_HIDDEN);
    lv_obj_add_flag(s_label_right, LV_OBJ_FLAG_HIDDEN);
    for (int i = 0; i < MAX_SUB_DOTS; i++) lv_obj_add_flag(s_sub_dots[i], LV_OBJ_FLAG_HIDDEN);
    lv_obj_remove_flag(s_label_value, LV_OBJ_FLAG_HIDDEN);
    reset_label_value_style(); // 这几个状态显示中文/设备名，不用大号数字字体
    if (s_pending_passkey >= 0) {
        lv_label_set_text(s_label_name, "配对码");
        char buf[8];
        snprintf(buf, sizeof(buf), "%06u", (unsigned)s_pending_passkey);
        lv_label_set_text(s_label_value, buf);
    } else if (!ble_has_any_bond()) {
        // 从没跟任何设备配对成功过（NimBLE 自己 flash 里的配对记录数为
        // 0，本地就能查，不用猜）——这种情况才是真的需要用户去手机蓝牙
        // 设置里配对，显示广播名字（放大号 s_label_value）方便用户找，
        // 同时配好几个 knob 时尤其有用：只有真正没配过的那个会显示
        // 名字，已经配好的不会。
        lv_label_set_text(s_label_name, "未配对");
        lv_label_set_text(s_label_value, ble_get_device_name());
    } else {
        // 已经跟某个设备配对过，现在只是暂时没连上（杀 App、暂时超出
        // 范围……）——配对信息（LTK）早就还在，不需要用户做任何事，
        // 等它自动重连就行，不需要再显示名字（不是要用户去找它配对）。
        // 这种情况下"等待连接"就是唯一要传达的信息，放大号 s_label_value。
        lv_label_set_text(s_label_name, "");
        lv_label_set_text(s_label_value, "等待连接");
    }
    lv_obj_set_style_text_color(s_label_value, color_solid(), 0);
}

static void redraw_paired_toast() {
    lv_obj_add_flag(s_arc, LV_OBJ_FLAG_HIDDEN);
    lv_obj_add_flag(s_label_left, LV_OBJ_FLAG_HIDDEN);
    lv_obj_add_flag(s_label_right, LV_OBJ_FLAG_HIDDEN);
    for (int i = 0; i < MAX_SUB_DOTS; i++) lv_obj_add_flag(s_sub_dots[i], LV_OBJ_FLAG_HIDDEN);
    lv_obj_remove_flag(s_label_value, LV_OBJ_FLAG_HIDDEN);
    reset_label_value_style();
    // 真的走过配对码确认才是"配对成功"；已配对设备用保存的密钥重新
    // 连接（比如手机 App 被杀后台又重启）没有这一步，应该说"连接成功"，
    // 不然会让人误以为每次连接都要重新配对。放大号的 s_label_value。
    lv_label_set_text(s_label_name, "");
    lv_label_set_text(s_label_value, s_paired_via_confirm ? "配对成功" : "连接成功");
    lv_obj_set_style_text_color(s_label_value, color_solid(), 0);
}

static void redraw_normal() {
    if (!s_have_display_data) {
        // 已经连上了，但手机还没推过任何数据（比如刚配对完，提示消失
        // 时手机还没来得及同步）——不能显示上一次残留的 title/value，
        // 那可能是断线之前的旧值，容易让人误以为是当前状态。
        lv_obj_add_flag(s_arc, LV_OBJ_FLAG_HIDDEN);
        lv_obj_add_flag(s_label_left, LV_OBJ_FLAG_HIDDEN);
        lv_obj_add_flag(s_label_right, LV_OBJ_FLAG_HIDDEN);
        for (int i = 0; i < MAX_SUB_DOTS; i++) lv_obj_add_flag(s_sub_dots[i], LV_OBJ_FLAG_HIDDEN);
        lv_obj_remove_flag(s_label_value, LV_OBJ_FLAG_HIDDEN);
        reset_label_value_style();
        lv_label_set_text(s_label_name, "已连接");
        lv_label_set_text(s_label_value, "等待数据...");
        lv_obj_set_style_text_color(s_label_value, color_solid(), 0);
        return;
    }

    lv_obj_remove_flag(s_arc, LV_OBJ_FLAG_HIDDEN);
    lv_label_set_text(s_label_name, s_title);
    update_sub_dots();

    // BOOT 长按清除配对那个红色/长按提交那个琥珀色进度环（见
    // draw_reset_progress/draw_commit_progress）会把 s_arc 的背景角度/
    // 起始角度/模式改成画满一整圈，这几行改回正常显示数值时的样子
    // （留 90 度缺口的参考弧）——不然长按过一次之后，正常页面的圆弧就
    // 会一直保持成整圈，回不去了。跟 build_ui() 里的初始值保持一致。
    lv_arc_set_rotation(s_arc, 135);
    lv_arc_set_bg_angles(s_arc, 0, 270);
    if (s_max > s_min) {
        lv_arc_set_range(s_arc, s_min, s_max);
        // min<0（比如座椅 -2~2，0=关闭居中）用 LVGL 内置的对称模式：
        // 指示条从取值范围的中点（这里正好是 0）往两侧长，负值往一侧、
        // 正值往另一侧——不用自己算角度，LVGL 按 range 中点自动处理。
        // 其它情况（比如空调温度 17~33，没有"居中关闭"这个概念）还是
        // 普通模式，从一端往另一端长。这个参考弧只反映"当前编辑值在
        // 范围里的大致位置"，不再随浏览/调整状态变粗变亮。
        lv_arc_set_mode(s_arc, s_min < 0 ? LV_ARC_MODE_SYMMETRICAL : LV_ARC_MODE_NORMAL);
        lv_arc_set_value(s_arc, s_value);
        lv_obj_set_style_arc_color(s_arc, gradient_color(s_value, s_min, s_max), LV_PART_INDICATOR);
    }

    bool dirty = (s_value != s_synced_value);

    if (s_layout == LAYOUT_AC_SPLIT_MAIN || s_layout == LAYOUT_AC_SPLIT_DEPUTY) {
        // 双数字布局：左边固定是主驾、右边固定是副驾（跟真实驾驶位左右
        // 对应，不随哪个是"大的"而互换位置）。哪个是大号可编辑数字、
        // 哪个是小号参考数字，由 layout 决定；可编辑那个跟着 dirty 变
        // 空心/实心，参考那个全程固定颜色。
        lv_obj_add_flag(s_label_value, LV_OBJ_FLAG_HIDDEN);
        lv_obj_remove_flag(s_label_left, LV_OBJ_FLAG_HIDDEN);
        lv_obj_remove_flag(s_label_right, LV_OBJ_FLAG_HIDDEN);

        char main_buf[16];
        char deputy_buf[16];
        bool main_is_primary = (s_layout == LAYOUT_AC_SPLIT_MAIN);
        snprintf(main_buf, sizeof(main_buf), "%d", main_is_primary ? s_value : s_secondary_value);
        snprintf(deputy_buf, sizeof(deputy_buf), "%d", main_is_primary ? s_secondary_value : s_value);
        lv_label_set_text(s_label_left, main_buf);
        lv_label_set_text(s_label_right, deputy_buf);

        // 双数字挤在同一行左右两边，可编辑那个用 Extra Bold 96px
        // （font_num_extra_bold_96，跟单数字布局那个大字号是同一个字体），
        // 参考那个用 Bold 48px（font_num_bold_48）。
        lv_obj_t *primary_label = main_is_primary ? s_label_left : s_label_right;
        lv_obj_t *secondary_label = main_is_primary ? s_label_right : s_label_left;
        lv_obj_set_style_text_font(primary_label, &font_num_extra_bold_96, 0);
        lv_obj_set_style_text_color(primary_label, dirty ? color_solid() : color_hollow(), 0);
        lv_obj_set_style_text_font(secondary_label, &font_num_bold_48, 0);
        lv_obj_set_style_text_color(secondary_label, color_reference(), 0);

        // 位置不能像单数字布局那样固定死——大字(96px)两位数整体约 115px
        // 宽，小字(48px)约 58px（两个字体自己的 adv_w 字段量出来的），
        // 主驾/副驾谁大谁小每次都可能不一样，固定两个对称锚点摆不下
        // （算过：两个都要躲开 s_arc 的圆弧、中间还要留够间隔，加起来
        // 比屏幕能给的宽度还宽）。改成每次按"这一帧到底谁是大字"重新
        // 算位置：把"大字 + 间隔 + 小字"当一个整体块，大字在前（主驾大）
        // 还是小字在前（副驾大）由 main_is_primary 决定，主驾永远还是
        // 排在副驾左边，整个块再往右偏 DUAL_SHIFT_RIGHT——大字这时候
        // 经常会盖过屏幕正中间那条线，是故意允许的，不强求"各自留在
        // 自己那一半"。
        // 靠右那一侧的外边缘到中心的距离固定是 half_block + DUAL_SHIFT_RIGHT
        // （不管这一侧是大字还是小字，代入化简后都是这个值）。之前
        // DUAL_GAP=15 时算出来是 104，真机反馈还是会盖到 s_arc 的进度
        // 环——s_arc 的绘制半径是 110 没错，但那是圆弧线条本身的半径，
        // 线条还有宽度（LVGL 默认弧宽），环的内边缘其实在不到 110 的
        // 地方，104 早就伸到线条底下去了。把 DUAL_GAP 收到 6，算法不变，
        // 右边缘变成 90(半宽和)+3(间隔的一半)+10(偏移)=正好 100，比之前
        // 让开了 4px，如果还是盖到，再往下调 DUAL_GAP 或者 DUAL_SHIFT_RIGHT，
        // 这两个改哪个都行，改完两侧的间隔和位置会跟着重新算，不用管
        // 其它地方。
        constexpr int32_t DUAL_BIG_HALF_W = 58;   // 96px 两位数整体半宽
        constexpr int32_t DUAL_SMALL_HALF_W = 29; // 48px 两位数整体半宽
        constexpr int32_t DUAL_GAP = 2;           // 大字/小字之间留的最小间隔
        constexpr int32_t DUAL_SHIFT_RIGHT = 4;  // 整体往右偏一点
        constexpr int32_t half_block = DUAL_BIG_HALF_W + DUAL_GAP / 2 + DUAL_SMALL_HALF_W;
        int32_t primary_x, secondary_x;
        if (main_is_primary) {
            // 大字（主驾）在前/靠左，小字（副驾）在后/靠右
            primary_x = -half_block + DUAL_BIG_HALF_W + DUAL_SHIFT_RIGHT;
            secondary_x = half_block - DUAL_SMALL_HALF_W + DUAL_SHIFT_RIGHT;
        } else {
            // 小字（主驾）在前/靠左，大字（副驾）在后/靠右
            secondary_x = -half_block + DUAL_SMALL_HALF_W + DUAL_SHIFT_RIGHT;
            primary_x = half_block - DUAL_BIG_HALF_W + DUAL_SHIFT_RIGHT;
        }
        lv_obj_align(primary_label, LV_ALIGN_CENTER, primary_x, 8);
        lv_obj_align(secondary_label, LV_ALIGN_CENTER, secondary_x, 8);
    } else {
        // 单数字布局（ac_link/seat）：只有 s_label_value 一个数字，
        // 空心/实心直接对应它有没有被转动过。就它一个数字、没有旁边
        // 的东西要避让，用 Extra Bold 96px 原生数字字体
        // （font_num_extra_bold_96）尽量把中间填满——原生尺寸，不用
        // 像素放大变换，边缘不会糊/有锯齿。
        lv_obj_add_flag(s_label_left, LV_OBJ_FLAG_HIDDEN);
        lv_obj_add_flag(s_label_right, LV_OBJ_FLAG_HIDDEN);
        lv_obj_remove_flag(s_label_value, LV_OBJ_FLAG_HIDDEN);
        lv_obj_set_style_text_font(s_label_value, &font_num_extra_bold_96, 0);

        char buf[16];
        snprintf(buf, sizeof(buf), "%d", s_value);
        lv_label_set_text(s_label_value, buf);
        lv_obj_set_style_text_color(s_label_value, dirty ? color_solid() : color_hollow(), 0);
    }
}

static void redraw() {
    switch (s_screen_state) {
        case SCREEN_DISCONNECTED: redraw_disconnected(); break;
        case SCREEN_PAIRED_TOAST: redraw_paired_toast(); break;
        case SCREEN_NORMAL: redraw_normal(); break;
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
    s_screen_state = SCREEN_DISCONNECTED;
    s_have_display_data = false;
    s_display_pending = false;
    s_needs_redraw = true;
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

    DisplayLayout layout = parse_layout(data.getString("layout", "ac_link"));
    const char *title = data.getString("title");
    int16_t min = data.getInt("min");
    int16_t max = data.getInt("max");
    // 复合停靠点（座椅切座位、空调分控切主副驾）才会带这两个字段，不是
    // 复合停靠点时插件不发，默认值 0 表示"不用画指示点"，见 update_sub_dots()。
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

// 创建所有控件，只在 setup() 里调用一次——具体显示什么文字交给 redraw()，
// 这里不写死初始文案。
static void build_ui() {
    lv_obj_t *scr = lv_screen_active(); // 这个 demo 只有这一个页面
    lv_obj_set_style_bg_color(scr, lv_color_black(), 0);

    // 屏幕边缘的一圈圆弧，用来表示当前值在整个范围里的比例，也复用来
    // 画长按提交/BOOT 清配对的进度环。只在 SCREEN_NORMAL 且已经收到过
    // 数据时显示正常参考弧，其它状态由 redraw() 隐藏。
    s_arc = lv_arc_create(scr);
    lv_obj_set_size(s_arc, 220, 220);
    lv_arc_set_rotation(s_arc, 135);   // 起始角度，让缺口留在下方
    lv_arc_set_bg_angles(s_arc, 0, 270); // 留 90 度缺口，而不是画一整圈
    lv_arc_set_range(s_arc, 0, 100);
    lv_obj_remove_style(s_arc, NULL, LV_PART_KNOB); // 隐藏默认的可拖拽手柄，这里只是展示用，不需要能拖
    lv_obj_remove_flag(s_arc, LV_OBJ_FLAG_CLICKABLE); // 禁止点击/触摸拖动它
    lv_obj_set_style_arc_color(s_arc, lv_palette_main(LV_PALETTE_BLUE), LV_PART_INDICATOR);
    lv_obj_center(s_arc);

    // 标题用 font_zh_36（比原来的 font_zh_18 大一倍，圆屏放得下），挪得
    // 靠上一点，给下面的大数字留出空间。
    s_label_name = lv_label_create(scr);
    lv_obj_set_style_text_font(s_label_name, &font_zh_36, 0);
    lv_obj_set_style_text_color(s_label_name, lv_color_white(), 0);
    lv_obj_align(s_label_name, LV_ALIGN_CENTER, 0, -58); // 比原来往下挪了半个字（36px 字体，半个字约 18px）

    // 单数字布局用（ac_link/seat/配对提示/连接状态这些也复用它）。字体/
    // 放大变换在 redraw() 里按状态动态设置（大号数字 vs 中文状态文字），
    // 这里只给一个初始值。
    s_label_value = lv_label_create(scr);
    lv_obj_set_style_text_font(s_label_value, &font_zh_36, 0);
    lv_obj_align(s_label_value, LV_ALIGN_CENTER, 0, 12);

    // 双数字布局用：s_label_left 固定放主驾内容、s_label_right 固定放
    // 副驾内容（主驾在左、副驾在右，跟真实驾驶位对应，不互换）。字体/
    // 颜色/位置都是在 redraw_normal() 里按"这一帧谁是大字"动态算的
    // （主驾/副驾谁大谁小不一样，两个数字宽度差得多，固定死一个位置
    // 摆不下，具体算法见 redraw_normal() 里的注释），这里只给个初始
    // 隐藏状态用的占位值，实际显示前都会被重新对齐一次。
    s_label_left = lv_label_create(scr);
    lv_obj_align(s_label_left, LV_ALIGN_CENTER, -38, 8);
    lv_obj_add_flag(s_label_left, LV_OBJ_FLAG_HIDDEN);

    s_label_right = lv_label_create(scr);
    lv_obj_align(s_label_right, LV_ALIGN_CENTER, 38, 8);
    lv_obj_add_flag(s_label_right, LV_OBJ_FLAG_HIDDEN);

    // 底部子页指示点：固定造够 MAX_SUB_DOTS 个，具体这一帧显示几个、
    // 摆在哪由 update_sub_dots() 按 s_sub_count 动态决定，这里先全部隐藏、
    // 位置随便给一个占位值。s_arc 底部留了 90 度缺口（见上面的创建代码），
    // 这一排点摆在这个缺口里，不会被圆弧盖住。
    for (int i = 0; i < MAX_SUB_DOTS; i++) {
        lv_obj_t *dot = lv_obj_create(scr);
        lv_obj_set_size(dot, 10, 10);
        lv_obj_set_style_radius(dot, LV_RADIUS_CIRCLE, 0);
        lv_obj_set_style_border_width(dot, 0, 0);
        lv_obj_remove_flag(dot, LV_OBJ_FLAG_SCROLLABLE);
        lv_obj_remove_flag(dot, LV_OBJ_FLAG_CLICKABLE);
        lv_obj_add_flag(dot, LV_OBJ_FLAG_HIDDEN);
        s_sub_dots[i] = dot;
    }
}

// 长按 BOOT 键触发清除配对期间，借用 s_arc 画一圈红色进度环（绕屏幕
// 一整圈，跟正常显示数值时那个留了缺口的弧不是一回事，这里 360 度
// 全画满）——percent 走到 100 正好是长按到点的那一刻。
static void draw_reset_progress(int percent) {
    lv_obj_remove_flag(s_arc, LV_OBJ_FLAG_HIDDEN);
    lv_arc_set_bg_angles(s_arc, 0, 360);
    lv_arc_set_rotation(s_arc, 270); // 从正上方开始，顺时针画满一圈
    lv_arc_set_mode(s_arc, LV_ARC_MODE_NORMAL);
    lv_arc_set_range(s_arc, 0, 100);
    lv_arc_set_value(s_arc, percent);
    lv_obj_set_style_arc_color(s_arc, lv_palette_main(LV_PALETTE_RED), LV_PART_INDICATOR);
}

// 长按 Enter 提交时，借用 s_arc 画一圈琥珀色进度环——跟上面
// draw_reset_progress 是同一个套路（画法完全一样，只是颜色换成琥珀，
// 区分"这一下会真的写到车上"和"这一下会清空配对信息"两种长按）。
// percent 走到 100 正好是长按到点、松手会真正提交的那一刻。
static void draw_commit_progress(int percent) {
    lv_obj_remove_flag(s_arc, LV_OBJ_FLAG_HIDDEN);
    lv_arc_set_bg_angles(s_arc, 0, 360);
    lv_arc_set_rotation(s_arc, 270);
    lv_arc_set_mode(s_arc, LV_ARC_MODE_NORMAL);
    lv_arc_set_range(s_arc, 0, 100);
    lv_arc_set_value(s_arc, percent);
    lv_obj_set_style_arc_color(s_arc, LV_COLOR_MAKE(0xFF, 0xB3, 0x00), LV_PART_INDICATOR); // 琥珀色
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

    bool pressed = digitalRead(BOOT_BTN_PIN) == LOW;
    if (!pressed) {
        if (showing_progress) {
            // 进度环画到一半松手——放弃，把 s_arc 恢复成当前真实状态
            // 该显示的样子（redraw() 会按 s_screen_state 重新配置它）。
            showing_progress = false;
            redraw();
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
        draw_reset_progress(percent > 100 ? 100 : percent);
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
// 这个函数必须是 loop() 里最后一个碰 s_arc 的调用（在 display_task_handler()
// 之前）：长按到点之后如果继续按着不松手，手机那边的回执（提交完
// syncToKnob() 推一份新数据回来）有概率在同一次按住期间通过
// s_needs_redraw 触发 redraw_normal()，把 s_arc 从进度环样式改回正常
// 参考弧样式——如果这个函数在那之前调用，进度环就会先被盖掉、下一帧才
// 重新画出来，观感上是"进度条消失了一下又出现"。放在最后，同一帧里
// 不管前面谁改了 s_arc，只要按键还按着，最终显示到物理屏幕上的永远是
// 进度环这一版，不会有中间状态被真的刷出来。
static void check_enter_commit_progress() {
    static uint32_t press_start_ms = 0;
    static bool showing_progress = false;

    bool pressed = encoder_is_button_pressed();
    if (!pressed) {
        if (showing_progress) {
            showing_progress = false;
            redraw(); // 松手（不管有没有到点）恢复成当前该显示的样子
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
    showing_progress = true;
    uint32_t fill_elapsed = held_ms - ENC_COMMIT_PROGRESS_START_MS;
    uint32_t fill_span = ENC_LONG_PRESS_MS - ENC_COMMIT_PROGRESS_START_MS;
    int percent = (int)((uint64_t)fill_elapsed * 100 / fill_span);
    draw_commit_progress(percent > 100 ? 100 : percent);
}

void setup() {
    Serial.begin(115200);
    Serial.println("[Knob] boot");

    pinMode(BOOT_BTN_PIN, INPUT_PULLUP);
    display_init();
    encoder_init();
    build_ui();
    ble_on_display_update(on_display_update);
    ble_on_passkey(on_passkey);
    ble_on_paired(on_paired);
    ble_on_disconnected(on_disconnected);
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
    if (encoder_take_click() && s_screen_state == SCREEN_NORMAL) {
        Serial.println("[BTN] click -> TAB (下一个停靠点)");
        ble_send_key(KEYCODE_TAB);
    }

    if (encoder_take_double_click() && s_screen_state == SCREEN_NORMAL) {
        Serial.println("[BTN] double-click -> CYCLE_SUB (座椅子项切换)");
        ble_send_key(KEYCODE_CYCLE_SUB);
    }

    if (encoder_take_long_press() && s_screen_state == SCREEN_NORMAL && s_have_display_data) {
        // 长按 = 确认：把本地编辑到的最终值带给手机，插件收到后才会
        // 真正调用车控接口——转动过程中不会触发任何车控调用，只有这
        // 一下会。
        Serial.printf("[BTN] long-press -> ENTER, value=%d\n", s_value);
        ble_send_key_with_value(KEYCODE_ENTER, s_value);
    }

    // 必须是这一帧里最后一个碰 s_arc 的调用，见函数自己的注释——按住
    // 不放时才能保证进度环不会被同一帧里其它 redraw() 调用（比如手机
    // 提交回执触发的那次）盖掉又重画，观感上一直稳稳按住。
    check_enter_commit_progress();

    display_task_handler(); // 让 LVGL 把刚才的改动画出来
    delay(5);
}
