#pragma once

// ui/ 下各文件之间共用的声明（main.cpp 只用 ui.h）：屏幕上那几个文字控件、
// 摆文字和画图的小工具，以及各画面文件的入口。
//
// 每个画面文件（page_*.cpp）管自己的两件事：redraw_*() 摆文字，draw_*() 画
// 环和刻度。环/刻度/图标都不是控件，是一个全屏透明对象的绘制回调按当前状态
// 现画的（见 dial.cpp）：redraw_*() 开头调 reset_screen(draw_xxx) 指定这个画面
// 用哪个绘制函数，之后每次重绘都调它。视觉规格见 docs/设计/旋钮圆屏UI/README.md，
// 各文件里的半径/角度/坐标都按那里的数值。

#include <lvgl.h>
#include "config.h"
#include "../state.h"
#include "ui.h"

// ---------- 文字控件（ui.cpp 创建，各画面摆放） ----------

extern lv_obj_t *s_label_name;      // 标题（联动/主驾/座位名/配对码…），状态画面里也放次要文字
extern lv_obj_t *s_label_value;     // 中间的主文字：单数字布局的大数字、升级百分比、状态画面的中文/设备名
extern lv_obj_t *s_label_left;      // 双数字布局用：左边，固定对应主驾；配对码画面放前 3 位
extern lv_obj_t *s_label_right;     // 双数字布局用：右边，固定对应副驾；配对码画面放后 3 位
extern lv_obj_t *s_label_sub_left;  // 分控：左边数字下面的"主驾"
extern lv_obj_t *s_label_sub_right; // 分控：右边数字下面的"副驾"
extern lv_obj_t *s_label_hint;      // 底部提示（长按确认/继续按住/请勿断开…），也放座椅的加热/通风
extern lv_obj_t *s_unit;            // 大数字后面的单位："°" 或 "%"

// 下面的 y 坐标都是屏幕绝对坐标（0~240），直接对应设计稿：文字给的是
// 这一行的竖直中心，数字给的是基线（数字底边）——数字字号不一样时按
// 基线对齐才齐，而且字体换了也不用重新量。
constexpr int32_t TITLE_CY = 58;     // 标题
constexpr int32_t NUMBER_BASE = 155; // 大数字（92px）的基线
constexpr int32_t HINT_CY = 199;     // 底部提示

// ---------- ui.cpp：摆文字 ----------

// 画面在 dial 层上的绘制函数（画环/刻度），nullptr = 不画
typedef void (*DialDrawFn)(lv_layer_t *layer);

// 每个画面都从"全部隐藏"开始，只把自己要的控件打开、摆好位置——不用
// 记上一个画面留下了什么。draw 是这个画面的环/刻度怎么画。
void reset_screen(DialDrawFn draw);
// 一行文字：水平中心在 (屏幕中心 + dx)，竖直中心在屏幕 y = cy
void place_text(lv_obj_t *label, const char *text, const lv_font_t *font, lv_color_t color,
                int32_t letter_space, int32_t dx, int32_t cy);
// 数字：水平中心在 (屏幕中心 + dx)，基线在屏幕 y = base
void place_digits(lv_obj_t *label, const char *text, const lv_font_t *font, lv_color_t color,
                  int32_t letter_space, int32_t dx, int32_t base);
void place_title(const char *text);
void place_hint(const char *text, lv_color_t color, int32_t cy);
// 92px 大数字，unit 非空时右上角挂一个 28px 的单位（"°" 或 "%"）
void place_big_number(int value, lv_color_t color, const char *unit);
// 联动/分控的底部提示：按住时"继续按住"，转动过还没提交时"长按确认"
void place_commit_hint(bool dirty);

// ---------- dial.cpp：绘制层 ----------
// 坐标都是屏幕绝对坐标，角度 0° 指右、顺时针为正。

constexpr int32_t DIAL_CX = CONFIG_SCREEN_HOR_RES / 2;
constexpr int32_t DIAL_CY = CONFIG_SCREEN_VER_RES / 2;
constexpr float DIAL_MARKER_R = 93.0f; // 当前值亮点/原值空心点所在的半径（刻度环内侧）

// 创建全屏透明的绘制层，要在文字控件之前建（文字才会盖在它上面）
void dial_create(lv_obj_t *scr);
// 换画面：指定新的绘制函数，清掉图标，转圈先停（start_spinner 会接着转）
void dial_reset(DialDrawFn draw);

void polar(float r, float deg, int32_t *x, int32_t *y);
void draw_tick(lv_layer_t *layer, float r0, float r1, float deg, lv_color_t color, int32_t width,
               lv_opa_t opa = LV_OPA_COVER);
// 实心圆点（diameter 是直径）；border > 0 时画成空心圆圈
void draw_dot(lv_layer_t *layer, int32_t cx, int32_t cy, int32_t diameter, lv_color_t color,
              lv_opa_t opa = LV_OPA_COVER, int32_t border = 0);
// r 是线条中心的半径（LVGL 的 radius 是外沿，这里换算一下），从 a0 顺时针画到 a1
void draw_ring(lv_layer_t *layer, float r, int32_t width, int32_t a0, int32_t a1, lv_color_t color,
               bool rounded, lv_opa_t opa = LV_OPA_COVER);
// 从正上方顺时针的整圈进度（固件升级、长按提交、清配对都是这个画法）
void draw_progress_ring(lv_layer_t *layer, float r, int32_t width, int percent, lv_color_t color);

// 状态画面的线条图标，跟环一起在绘制回调里画（照设计稿的 24×24 线条
// 图标按比例放大，比字体里的实心图标更细、更统一）。
enum DialIcon { ICON_NONE, ICON_BLUETOOTH, ICON_CHECK };
// 图标中心在 (DIAL_CX, cy)，size 是图标边长（对应设计稿 24×24 的那个框）
void place_icon(DialIcon icon, lv_color_t color, int32_t size, int32_t cy);

// 显示转圈：之前没在转就从头开始，已经在转（比如等待画面又被 redraw 了一次）
// 就接着转，不让它跳回起点。
void start_spinner();

// ---------- 各画面 ----------

// page_status.cpp
void redraw_disconnected();
void redraw_paired_toast();
void redraw_waiting_data();
void redraw_ota();
// page_ac.cpp
void redraw_ac_link(bool dirty);
void redraw_ac_split(bool dirty);
// page_seat.cpp
void redraw_seat(bool dirty);
// page_drive.cpp
void redraw_drive();
