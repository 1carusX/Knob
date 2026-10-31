#include "encoder.h"
#include "config.h"
#include <Arduino.h>

// --- 旋转方向：正交解码（整步状态机） ---
//
// EC11 内部有 A、B 两路开关，机械结构上错开了一点，所以转动旋钮时
// 它们不会同时触发。靠它们"谁先变"就能判断方向。
//
// 最早用的是纯查表累加（每次电平跳变都按 -1/0/+1 计一票，攒够 4 的
// 倍数才除出一步，不够的余数留到下次），实测换向的第一下经常没反应：
// EC11 停留的机械档位和电气触点真正闭合的位置之间有点空程
// （backlash），换向瞬间第一下往往凑不够 4 次跳变，会被"除以 4 向零
// 截断"直接吃掉，要转第二下才会跟前一次的余数一起报出来，表现成
// "丢了一格"。
//
// 换成半步状态机（Ben Buxton 那版）试过之后发现变成一跳两格——半步
// 状态机把电平 00 和 11 都当成合法的静止起点，而这颗 EC11 实测是标准
// 的"整步"编码器（一档 4 次跳变，只在 00 这一个状态真正停留），走一
// 档会经过两段半步、各报一次，所以翻倍了。
//
// 这里换成 Buxton 同一套思路里对应"整步"编码器的版本：同样是状态机、
// 只有跳变序列真的按合法顺序走完一整圈、回到静止状态 R_START 才确认
// 一步，不是简单累加跳变次数再除——换向时哪怕空程更明显，状态机也
// 只会在中间状态之间来回摆动、安全地退回 R_START，不会把没走完的
// 那部分平白攒成误报，也不会像最早那版"除以 4 截断"一样把它吞掉。
// 每个物理档位还是只报 1 步，手感灵敏度跟最早的版本一致。
enum : uint8_t {
    R_START = 0x0,
    R_CW_FINAL = 0x1,
    R_CW_BEGIN = 0x2,
    R_CW_NEXT = 0x3,
    R_CCW_BEGIN = 0x4,
    R_CCW_FINAL = 0x5,
    R_CCW_NEXT = 0x6,
};
enum : uint8_t {
    DIR_CW = 0x10,
    DIR_CCW = 0x20,
    DIR_MASK = 0x30,
};

// 下标 [当前状态（去掉方向标志位）][这次读到的 2 位 A/B 电平]，查出来
// 的新状态只有在真正转完一整圈回到 R_START 的那一下才会带上
// DIR_CW/DIR_CCW 标志，用来驱动 s_enc_accum。
static const uint8_t FULL_STEP_TABLE[7][4] = {
    /* R_START     */ {R_START,     R_CW_BEGIN,  R_CCW_BEGIN, R_START},
    /* R_CW_FINAL  */ {R_CW_NEXT,   R_START,     R_CW_FINAL,  R_START | DIR_CW},
    /* R_CW_BEGIN  */ {R_CW_NEXT,   R_CW_BEGIN,  R_START,     R_START},
    /* R_CW_NEXT   */ {R_CW_NEXT,   R_CW_BEGIN,  R_CW_FINAL,  R_START},
    /* R_CCW_BEGIN */ {R_CCW_NEXT,  R_START,     R_CCW_BEGIN, R_START},
    /* R_CCW_FINAL */ {R_CCW_NEXT,  R_CCW_FINAL, R_START,     R_START | DIR_CCW},
    /* R_CCW_NEXT  */ {R_CCW_NEXT,  R_CCW_FINAL, R_CCW_BEGIN, R_START},
};

static volatile uint8_t s_state = R_START;
static volatile int32_t s_enc_accum = 0; // 已确认的档位数，方向状态机直接累加，不用再除

// 这个函数会在 A 或 B 每次电平变化时触发，运行在中断优先级——
// 要写得尽量短，绝对不能在里面调用 Serial.print 这类耗时的操作。
void IRAM_ATTR encoder_isr() {
    uint8_t a = digitalRead(ENC_PIN_A);
    uint8_t b = digitalRead(ENC_PIN_B);
    uint8_t pinstate = (a << 1) | b;
    s_state = FULL_STEP_TABLE[s_state & 0x0F][pinstate];
    uint8_t dir = s_state & DIR_MASK;
    if (dir == DIR_CW) {
        s_enc_accum++;
    } else if (dir == DIR_CCW) {
        s_enc_accum--;
    }
}

void encoder_init() {
    pinMode(ENC_PIN_A, INPUT_PULLUP);
    pinMode(ENC_PIN_B, INPUT_PULLUP);
    pinMode(ENC_PIN_SW, INPUT_PULLUP);
    // CHANGE = 上升沿、下降沿都触发，正好是上面查表算法需要的。
    attachInterrupt(digitalPinToInterrupt(ENC_PIN_A), encoder_isr, CHANGE);
    attachInterrupt(digitalPinToInterrupt(ENC_PIN_B), encoder_isr, CHANGE);
}

int16_t encoder_get_diff() {
    // 状态机已经在 ISR 里按"确认完成一步"才累加，s_enc_accum 本身
    // 就是档位数，这里直接算差值，不用再除。
    static int32_t last_reported = 0;
    int32_t accum = s_enc_accum; // volatile 读取；函数执行过程中 ISR 可能改它，没关系
    int16_t steps = (int16_t)(accum - last_reported);
    last_reported = accum;
    return steps;
}

// --- 按键：消抖 + 短按/长按判定 ---
//
// 机械开关按下/松开的瞬间会有几毫秒的"抖动"，如果直接读电平会
// 产生很多多余的跳变。这里的做法很简单：20ms 之内的状态变化一律
// 忽略（消抖），再靠 millis() 时间戳区分"短按"/"长按"/"放弃"三种结局。
//
// 按住时长落在哪个区间决定松手时的结局（ENC_COMMIT_PROGRESS_START_MS/
// ENC_LONG_PRESS_MS 见 config.h，跟屏幕上长按进度环的时间点是同一组
// 常量，视觉反馈和这里的判定天然对齐）：
//   [0, START)      纯粹的短按，松手 -> 单击事件
//   [START, LONG)   已经进入"准备长按"的视觉区间（进度环已经在画）但
//                   没坚持到底，松手 -> 什么事件都不发，安静放弃这次
//                   操作。不能退回成单击：用户看到进度环在走、以为自己
//                   在做"长按确认"，松手才发现被当成单击切走了停靠点，
//                   会觉得莫名其妙。
//   [LONG, ∞)       长按已经生效（多半在按住期间就提前判定过了）

static bool s_btn_pressed = false;     // 消抖之后的当前按键状态
static uint32_t s_press_start_ms = 0;  // 这次按下是什么时候开始的
static uint32_t s_last_change_ms = 0;  // 用于消抖计时
static bool s_long_fired = false;      // 这次按下是否已经触发过长按事件
static bool s_click_flag = false;      // "发生了一次短按"的一次性标志位
static bool s_long_flag = false;       // "发生了一次长按"的一次性标志位

// 双击判定：无条件开启，见 encoder.h 的 encoder_take_double_click() 注释。
static bool s_double_click_flag = false;   // "发生了一次双击"的一次性标志位
static bool s_click_pending = false;       // 等双击窗口过去再决定是不是补报单击
static uint32_t s_click_pending_since_ms = 0;
static uint32_t s_last_click_release_ms = 0; // 上一次"确认是单击"松手的时刻，0 表示还没有过

void encoder_poll_button() {
    bool pressed = (digitalRead(ENC_PIN_SW) == LOW); // 接线是按下时低电平（上拉）
    uint32_t now = millis();

    if (pressed != s_btn_pressed && (now - s_last_change_ms) > 20) { // 20ms 消抖窗口
        s_last_change_ms = now;
        s_btn_pressed = pressed;
        if (pressed) {
            s_press_start_ms = now;
            s_long_fired = false;
        } else if (!s_long_fired) {
            // 松手时兜底用真实按住时长再判一次：正常情况下长按会在下面
            // "还按着"那段轮询里提前标记掉，走不到这里；但如果按住期间
            // loop() 被别的耗时操作（LVGL 重绘、BLE 写入）卡住，没能轮询到
            // 那次判定，松手时 s_long_fired 仍是 false——不能只看这个标志
            // 位，得用松手时刻算出的真实时长再兜底判一次，否则一次卡顿
            // 就会把真实的长按误判成短按。
            uint32_t held_ms = now - s_press_start_ms;
            if (held_ms >= ENC_LONG_PRESS_MS) {
                s_long_flag = true;
            } else if (held_ms < ENC_COMMIT_PROGRESS_START_MS) {
                if (s_last_click_release_ms != 0 &&
                    (now - s_last_click_release_ms) <= ENC_DOUBLE_CLICK_WINDOW_MS) {
                    // 这是双击的第二下：撤销还在等窗口的那次单击（如果有），
                    // 只报双击，不会先补一次单击再报双击。
                    s_click_pending = false;
                    s_double_click_flag = true;
                    s_last_click_release_ms = 0; // 避免连续三击被当成两次双击
                } else {
                    // 先攒着，等双击窗口过去、确认没有第二下，再补报单击。
                    s_click_pending = true;
                    s_click_pending_since_ms = now;
                    s_last_click_release_ms = now;
                }
            }
            // else：held_ms 落在 [START, LONG) 之间——已经进入长按视觉
            // 反馈区间又中途松手，两个标志位都不置，安静放弃（见上面
            // 类注释）。
        }
    }

    // 还按着，而且已经超过长按阈值？
    if (s_btn_pressed && !s_long_fired && (now - s_press_start_ms) >= ENC_LONG_PRESS_MS) {
        s_long_fired = true;
        s_long_flag = true;
    }

    // 攒着的单击等够了双击窗口、还没等到第二下——确认真的只是单击，补报。
    if (s_click_pending && (now - s_click_pending_since_ms) > ENC_DOUBLE_CLICK_WINDOW_MS) {
        s_click_pending = false;
        s_click_flag = true;
    }
}

bool encoder_take_click() {
    if (s_click_flag) {
        s_click_flag = false;
        return true;
    }
    return false;
}

bool encoder_take_long_press() {
    if (s_long_flag) {
        s_long_flag = false;
        return true;
    }
    return false;
}

bool encoder_take_double_click() {
    if (s_double_click_flag) {
        s_double_click_flag = false;
        return true;
    }
    return false;
}

bool encoder_is_button_pressed() {
    return digitalRead(ENC_PIN_SW) == LOW; // 接线是按下时低电平（上拉），跟 encoder_poll_button() 一致
}

bool encoder_is_button_down() {
    return s_btn_pressed;
}

bool encoder_is_idle() {
    return !s_btn_pressed && !s_click_pending && !s_click_flag && !s_double_click_flag && !s_long_flag;
}
