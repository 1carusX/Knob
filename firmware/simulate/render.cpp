// 在电脑上用真正的固件界面代码渲染各状态截图，不用烧板子。
//
// 直接把 src/main.cpp 整个 #include 进来（它的 static 状态变量和函数在这里
// 都能直接用），把它依赖的几层换成假实现：Arduino（假时钟）、屏幕驱动
// （LVGL 画到内存里的整屏缓冲）、旋钮（转动/按键由这里设定）、BLE（回调
// 由这里主动调，模拟手机推数据、配对、OTA）。LVGL 本身、lv_conf.h、字体
// 都跟固件用的是同一份，所以画出来就是屏幕上的样子（RGB565 色深、4bpp
// 字体都一样），只是外面多套了一个圆形遮罩，表示圆屏看得见的范围。
//
// 用法见同目录 CMakeLists.txt 开头：cmake -B build && cmake --build build -j（输出到 out/），
// -DSCALE=1 出原始 240×240。

#include "../src/main.cpp"

#include <stdarg.h>
#include <stdlib.h>
#include <zlib.h>
#include <string>
#include <vector>

// ==================== Arduino 替身 ====================

static uint32_t g_now_ms = 1000;
static bool g_boot_pressed = false;

uint32_t millis() { return g_now_ms; }
void delay(uint32_t ms) { g_now_ms += ms; }
int digitalRead(uint8_t pin) { return (pin == BOOT_BTN_PIN && g_boot_pressed) ? LOW : HIGH; }
void pinMode(uint8_t, uint8_t) {}

SimSerial Serial;
SimEsp ESP;

// 固件的串口日志默认不打，SIM_VERBOSE=1 时打到 stderr 方便对照
int SimSerial::printf(const char *fmt, ...) {
    if (!getenv("SIM_VERBOSE")) return 0;
    va_list ap;
    va_start(ap, fmt);
    int n = vfprintf(stderr, fmt, ap);
    va_end(ap);
    return n;
}
void SimSerial::println(const char *s) {
    if (getenv("SIM_VERBOSE")) fprintf(stderr, "%s\n", s);
}
void SimEsp::restart() {}

// ==================== 屏幕：LVGL 画进内存 ====================

// 跟 display.cpp 一样走 PARTIAL 模式 + 40 行缓冲，flush 时拼回整屏。颜色
// 格式用不交换字节序的 RGB565（真机是 RGB565_SWAPPED，只差字节序，颜色
// 量化一样）。
static uint16_t g_frame[CONFIG_SCREEN_HOR_RES * CONFIG_SCREEN_VER_RES];
alignas(4) static uint8_t g_draw_buf[CONFIG_SCREEN_HOR_RES * 40 * 2];
static lv_display_t *g_disp;

static void sim_flush_cb(lv_display_t *disp, const lv_area_t *area, uint8_t *px_map) {
    const uint16_t *src = (const uint16_t *)px_map;
    int32_t w = area->x2 - area->x1 + 1;
    for (int32_t y = area->y1; y <= area->y2; y++) {
        memcpy(&g_frame[y * CONFIG_SCREEN_HOR_RES + area->x1], src, w * 2);
        src += w;
    }
    lv_display_flush_ready(disp);
}

void display_init() {
    lv_init();
    lv_tick_set_cb(millis);
    g_disp = lv_display_create(CONFIG_SCREEN_HOR_RES, CONFIG_SCREEN_VER_RES);
    lv_display_set_color_format(g_disp, LV_COLOR_FORMAT_RGB565);
    lv_display_set_buffers(g_disp, g_draw_buf, NULL, sizeof(g_draw_buf), LV_DISPLAY_RENDER_MODE_PARTIAL);
    lv_display_set_flush_cb(g_disp, sim_flush_cb);
}
void display_task_handler() { lv_timer_handler(); }
void display_set_brightness(uint8_t) {}

// ==================== 旋钮 ====================

static int16_t g_enc_diff = 0;
static bool g_enc_pressed = false;

void encoder_init() {}
int16_t encoder_get_diff() {
    int16_t d = g_enc_diff;
    g_enc_diff = 0;
    return d;
}
void encoder_poll_button() {}
bool encoder_take_click() { return false; }
bool encoder_take_long_press() { return false; }
bool encoder_take_double_click() { return false; }
bool encoder_is_button_pressed() { return g_enc_pressed; }

// ==================== BLE ====================

static void (*g_on_display)(const DisplayData &) = nullptr;
static void (*g_on_passkey)(uint32_t) = nullptr;
static void (*g_on_paired)() = nullptr;
static void (*g_on_disconnected)() = nullptr;
static void (*g_on_ota)(OtaEvent, uint32_t, uint32_t) = nullptr;
static bool g_has_bond = false;

void ble_init() {}
bool ble_is_paired() { return true; }
void ble_send_key(int16_t) {}
void ble_send_key_with_value(int16_t, int16_t) {}
void ble_on_display_update(void (*cb)(const DisplayData &)) { g_on_display = cb; }
void ble_on_passkey(void (*cb)(uint32_t)) { g_on_passkey = cb; }
void ble_on_paired(void (*cb)()) { g_on_paired = cb; }
void ble_on_disconnected(void (*cb)()) { g_on_disconnected = cb; }
void ble_on_ota(void (*cb)(OtaEvent, uint32_t, uint32_t)) { g_on_ota = cb; }
const char *ble_get_device_name() { return "Knob-3F2A"; }
bool ble_has_any_bond() { return g_has_bond; }
void ble_clear_all_bonds() { g_has_bond = false; }
void ble_ota_check_timeout() {}

static const DisplayData::Entry *find_entry(const DisplayData &d, const char *key, int type) {
    for (int i = 0; i < d.count; i++) {
        if (strcmp(d.entries[i].key, key) == 0 && (type < 0 || d.entries[i].type == type)) return &d.entries[i];
    }
    return nullptr;
}
int16_t DisplayData::getInt(const char *key, int16_t def) const {
    const Entry *e = find_entry(*this, key, 0);
    return e ? e->intValue : def;
}
const char *DisplayData::getString(const char *key, const char *def) const {
    const Entry *e = find_entry(*this, key, 1);
    return e ? e->strValue : def;
}
bool DisplayData::getBool(const char *key, bool def) const {
    const Entry *e = find_entry(*this, key, 2);
    return e ? e->boolValue : def;
}
bool DisplayData::has(const char *key) const { return find_entry(*this, key, -1) != nullptr; }

// 拼一份"手机推来的显示数据"，字段跟插件 MainPlugin.syncToKnob() 发的一致
struct Push {
    DisplayData d;
    Push &i(const char *k, int16_t v) {
        DisplayData::Entry &e = d.entries[d.count++];
        snprintf(e.key, sizeof(e.key), "%s", k);
        e.type = 0;
        e.intValue = v;
        return *this;
    }
    Push &s(const char *k, const char *v) {
        DisplayData::Entry &e = d.entries[d.count++];
        snprintf(e.key, sizeof(e.key), "%s", k);
        e.type = 1;
        snprintf(e.strValue, sizeof(e.strValue), "%s", v);
        return *this;
    }
    void send() { g_on_display(d); }
};

// ==================== PNG / 动画 PNG 输出 ====================
//
// 只靠 zlib 自己拼 PNG 块，不依赖图像库。动画用 APNG（PNG 的动画扩展，浏览器、
// 系统看图器都直接播）：第一帧是普通 IDAT，后面每帧一个 fcTL（位置/时长）+ fdAT。

static void put_u32(std::vector<uint8_t> &v, uint32_t x) {
    v.push_back(x >> 24);
    v.push_back(x >> 16);
    v.push_back(x >> 8);
    v.push_back(x);
}

static void png_chunk(FILE *f, const char *type, const std::vector<uint8_t> &data) {
    std::vector<uint8_t> len;
    put_u32(len, data.size());
    fwrite(len.data(), 1, 4, f);
    fwrite(type, 1, 4, f);
    if (!data.empty()) fwrite(data.data(), 1, data.size(), f);
    uLong crc = crc32(0, (const Bytef *)type, 4);
    if (!data.empty()) crc = crc32(crc, data.data(), data.size());
    std::vector<uint8_t> c;
    put_u32(c, crc);
    fwrite(c.data(), 1, 4, f);
}

// 当前整屏缓冲按 scale 倍最近邻放大（保留像素颗粒感）成 RGBA，圆屏外的角落透明
static std::vector<uint8_t> frame_rgba(int scale) {
    const int W = CONFIG_SCREEN_HOR_RES, H = CONFIG_SCREEN_VER_RES;
    const int OW = W * scale, OH = H * scale;
    const double cx = W / 2.0, cy = H / 2.0, r = W / 2.0;
    std::vector<uint8_t> px;
    px.reserve((size_t)OW * OH * 4);
    for (int oy = 0; oy < OH; oy++) {
        for (int ox = 0; ox < OW; ox++) {
            uint16_t p = g_frame[(oy / scale) * W + ox / scale];
            uint8_t r5 = p >> 11, g6 = (p >> 5) & 0x3F, b5 = p & 0x1F;
            px.push_back((r5 << 3) | (r5 >> 2));
            px.push_back((g6 << 2) | (g6 >> 4));
            px.push_back((b5 << 3) | (b5 >> 2));
            double dx = (ox + 0.5) / scale - cx, dy = (oy + 0.5) / scale - cy;
            px.push_back(dx * dx + dy * dy <= r * r ? 255 : 0);
        }
    }
    return px;
}

// RGBA 图（宽 stride 像素）里 (x, y, w, h) 这一块压成 IDAT/fdAT 的数据
static std::vector<uint8_t> deflate_rect(const std::vector<uint8_t> &px, int stride, int x, int y, int w, int h) {
    std::vector<uint8_t> raw;
    raw.reserve((size_t)h * (w * 4 + 1));
    for (int row = y; row < y + h; row++) {
        raw.push_back(0); // filter: none
        const uint8_t *p = &px[((size_t)row * stride + x) * 4];
        raw.insert(raw.end(), p, p + w * 4);
    }
    uLongf zlen = compressBound(raw.size());
    std::vector<uint8_t> z(zlen);
    compress2(z.data(), &zlen, raw.data(), raw.size(), 9);
    z.resize(zlen);
    return z;
}

static FILE *open_png(const std::string &path, int w, int h) {
    FILE *f = fopen(path.c_str(), "wb");
    if (!f) {
        perror(path.c_str());
        exit(1);
    }
    static const uint8_t sig[8] = {0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    fwrite(sig, 1, 8, f);
    std::vector<uint8_t> ihdr;
    put_u32(ihdr, w);
    put_u32(ihdr, h);
    ihdr.insert(ihdr.end(), {8, 6, 0, 0, 0}); // 8bit RGBA
    png_chunk(f, "IHDR", ihdr);
    return f;
}

static void write_png(const std::string &path, int scale) {
    const int OW = CONFIG_SCREEN_HOR_RES * scale, OH = CONFIG_SCREEN_VER_RES * scale;
    FILE *f = open_png(path, OW, OH);
    png_chunk(f, "IDAT", deflate_rect(frame_rgba(scale), OW, 0, 0, OW, OH));
    png_chunk(f, "IEND", {});
    fclose(f);
}

// 动画 PNG：每帧只存跟上一帧不一样的那块矩形（blend_op = SOURCE 直接覆盖），
// 跟上一帧完全一样就只把上一帧的时长加长，文件小很多。
struct Apng {
    struct Frame {
        int x, y, w, h;
        uint32_t ms;
        std::vector<uint8_t> z;
    };
    int ow, oh;
    std::vector<uint8_t> prev;
    std::vector<Frame> frames;

    explicit Apng(int scale) : ow(CONFIG_SCREEN_HOR_RES * scale), oh(CONFIG_SCREEN_VER_RES * scale) {}

    void add(std::vector<uint8_t> px, uint32_t ms) {
        if (frames.empty()) {
            frames.push_back({0, 0, ow, oh, ms, deflate_rect(px, ow, 0, 0, ow, oh)});
            prev = std::move(px);
            return;
        }
        int x0 = ow, y0 = oh, x1 = -1, y1 = -1;
        for (int y = 0; y < oh; y++) {
            const uint32_t *a = (const uint32_t *)&px[(size_t)y * ow * 4];
            const uint32_t *b = (const uint32_t *)&prev[(size_t)y * ow * 4];
            for (int x = 0; x < ow; x++) {
                if (a[x] == b[x]) continue;
                x0 = LV_MIN(x0, x);
                x1 = LV_MAX(x1, x);
                y0 = LV_MIN(y0, y);
                y1 = LV_MAX(y1, y);
            }
        }
        if (x1 < 0) {
            frames.back().ms += ms;
            return;
        }
        frames.push_back({x0, y0, x1 - x0 + 1, y1 - y0 + 1, ms, deflate_rect(px, ow, x0, y0, x1 - x0 + 1, y1 - y0 + 1)});
        prev = std::move(px);
    }

    void write(const std::string &path) const {
        FILE *f = open_png(path, ow, oh);
        std::vector<uint8_t> actl;
        put_u32(actl, frames.size());
        put_u32(actl, 0); // 无限循环
        png_chunk(f, "acTL", actl);
        uint32_t seq = 0;
        for (size_t i = 0; i < frames.size(); i++) {
            const Frame &fr = frames[i];
            std::vector<uint8_t> fctl;
            put_u32(fctl, seq++);
            put_u32(fctl, fr.w);
            put_u32(fctl, fr.h);
            put_u32(fctl, fr.x);
            put_u32(fctl, fr.y);
            fctl.insert(fctl.end(), {(uint8_t)(fr.ms >> 8), (uint8_t)fr.ms, 0x03, 0xE8}); // 时长 ms/1000 秒
            fctl.insert(fctl.end(), {0, 0}); // dispose_op = NONE，blend_op = SOURCE
            png_chunk(f, "fcTL", fctl);
            if (i == 0) {
                png_chunk(f, "IDAT", fr.z);
            } else {
                std::vector<uint8_t> fdat;
                put_u32(fdat, seq++);
                fdat.insert(fdat.end(), fr.z.begin(), fr.z.end());
                png_chunk(f, "fdAT", fdat);
            }
        }
        png_chunk(f, "IEND", {});
        fclose(f);
    }
};

// ==================== 场景 ====================

static std::string g_out_dir;
static int g_scale = 2;
static int g_shot = 0;

// 跑一次固件的 loop()（里面会处理 s_needs_redraw、旋钮、长按进度环等）
static void step() { loop(); }

static void snap(const char *name) {
    lv_obj_invalidate(lv_screen_active());
    lv_refr_now(g_disp);
    char file[256];
    snprintf(file, sizeof(file), "%s/%02d-%s.png", g_out_dir.c_str(), ++g_shot, name);
    write_png(file, g_scale);
    printf("%s\n", file);
}

static void push_ac_link(int16_t value) {
    Push().s("layout", "ac_link").s("title", "联动").i("value", value).i("min", 17).i("max", 33).send();
}
static void push_ac_split(bool main_side, int16_t main_v, int16_t deputy_v) {
    Push()
        .s("layout", main_side ? "ac_split_main" : "ac_split_deputy")
        .s("title", main_side ? "主驾" : "副驾")
        .i("value_main", main_v)
        .i("value_deputy", deputy_v)
        .i("min", 17)
        .i("max", 33)
        .i("sub_count", 2)
        .i("sub_index", main_side ? 0 : 1)
        .send();
}
static void push_seat(const char *title, int16_t index, int16_t level) {
    Push().s("layout", "seat").s("title", title).i("value", level).i("min", -2).i("max", 2).i("sub_count", 4).i("sub_index", index).send();
}

// ==================== 动画 ====================
//
// 按真机的刷新节奏推进假时钟：每帧 FRAME_MS（= lv_conf.h 的 LV_DEF_REFR_PERIOD），
// 中间照常反复跑固件的 loop()（每次 delay(5)），LVGL 的动画、长按计时都
// 跟真机一样按时间走。每段录完写成一个动画 PNG：<out>/<name>.png。

constexpr uint32_t FRAME_MS = LV_DEF_REFR_PERIOD;

static void advance(uint32_t ms) {
    uint32_t end = g_now_ms + ms;
    while ((int32_t)(g_now_ms - end) < 0) loop();
}

struct Recorder {
    std::string path;
    uint32_t frame_ms;
    Apng apng;
    // frame_ms 能整除一个循环周期时，录满一个周期的动画 PNG 首尾正好接上
    explicit Recorder(const char *name, uint32_t frame_ms = FRAME_MS)
        : path(g_out_dir + "/" + name + ".png"), frame_ms(frame_ms), apng(g_scale) {}
    ~Recorder() {
        apng.write(path);
        uint32_t total = 0;
        for (const auto &f : apng.frames) total += f.ms;
        printf("%s  %u 帧 / %.1f 秒\n", path.c_str(), (unsigned)(total / frame_ms), total / 1000.0);
    }
    void frame() {
        lv_obj_invalidate(lv_screen_active());
        lv_refr_now(g_disp);
        apng.add(frame_rgba(g_scale), frame_ms);
    }
    // 按真实时间录 ms 毫秒
    void run(uint32_t ms) {
        for (uint32_t t = 0; t < ms; t += frame_ms) {
            advance(frame_ms);
            frame();
        }
    }
};

static void render_anims() {
    // 先走到"已配对、有数据"的正常状态，各段动画从这里切出去
    g_on_passkey(482913);
    g_on_paired();
    g_has_bond = true;
    advance(PAIRED_TOAST_MS + 100);


    push_ac_link(22);
    advance(50);

    {   // 长按提交：先转 3 档，按住（前 0.5 秒没反馈，之后 1 秒画满）。画满那一刻
        // 真机会把值发给手机，手机执行完推回新数据（这里模拟这次回执），数字
        // 变回白色；再按一会儿后松手。
        Recorder r("commit");
        g_enc_diff = 3;
        r.run(500);
        g_enc_pressed = true;
        r.run(ENC_LONG_PRESS_MS);
        push_ac_link(25);
        r.run(300);
        g_enc_pressed = false;
        r.run(600);
    }

    // 断开、等待重连：两种转圈各录正好一个大周期（每帧 30ms 能整除周期，
    // 动画 PNG 循环播放时首尾无缝，跟真机一样）
    g_on_disconnected();
    advance(50);
    for (SpinnerStyle style : {SPINNER_CLASSIC, SPINNER_EXPRESSIVE}) {
        bool classic = style == SPINNER_CLASSIC;
        s_spinner_style = style;
        s_spinner_on = false; // 让下一次 redraw 从头开始转
        redraw();
        Recorder r(classic ? "spinner-classic" : "spinner-expressive", 30);
        r.run(classic ? 5400 : 6000);
    }

    g_on_paired();
    advance(PAIRED_TOAST_MS + 100);
    push_ac_link(22);
    advance(50);

    {   // 固件升级：真机约 80 秒，这里每帧 +1%（约快 25 倍），完成后"正在重启"停 1 秒
        Recorder r("ota");
        g_on_ota(OTA_EVENT_STARTED, 0, 100);
        r.run(300);
        for (int p = 1; p <= 100; p++) {
            g_on_ota(OTA_EVENT_PROGRESS, p, 100);
            r.run(FRAME_MS);
        }
        g_on_ota(OTA_EVENT_DONE, 100, 100);
        r.run(1000);
        s_ota_done = false;
        s_restart_at_ms = 0;
        s_screen_state = SCREEN_NORMAL;
        s_needs_redraw = true;
        advance(50);
    }

    {   // 长按 BOOT 清配对：按住 2 秒后开始画，再 3 秒画满、清掉配对记录，画面
        // 立刻切到"未配对"
        Recorder r("boot-reset");
        g_boot_pressed = true;
        r.run(BOOT_BTN_CLEAR_BONDS_MS + 100);
        g_boot_pressed = false;
        r.run(1000);
    }
}

int main(int argc, char **argv) {
    g_out_dir = argc > 1 ? argv[1] : "out";
    g_scale = argc > 2 ? atoi(argv[2]) : 2;
    if (g_scale < 1) g_scale = 1;

    setup(); // 固件自己的 setup()：build_ui() + 注册回调 + 第一次 redraw()

    if (argc > 3 && strcmp(argv[3], "anim") == 0) {
        render_anims();
        return 0;
    }

    // --- 连接流程 ---
    step();
    snap("unpaired"); // 从没配对过：设备名 + 未配对

    g_on_passkey(482913);
    step();
    snap("passkey");

    g_on_paired();
    g_has_bond = true;
    step();
    snap("paired-toast"); // 走过配对码 → 配对成功

    delay(PAIRED_TOAST_MS + 100);
    step();
    snap("connected-no-data"); // 提示结束、手机还没推数据

    // --- 数据画面 ---
    push_ac_link(22);
    step();
    snap("link-idle");

    g_enc_diff = 3; // 转 3 档，本地编辑、还没提交
    step();
    snap("link-editing");

    // 长按提交进度环：按住到 ENC_COMMIT_PROGRESS_START_MS 之后才开始画，取一半
    g_enc_pressed = true;
    step();
    delay(ENC_COMMIT_PROGRESS_START_MS + (ENC_LONG_PRESS_MS - ENC_COMMIT_PROGRESS_START_MS) / 2);
    step();
    snap("link-commit");
    g_enc_pressed = false;
    step();

    push_ac_split(true, 22, 24);
    step();
    snap("split-main");

    push_ac_split(false, 22, 24);
    step();
    snap("split-deputy");

    push_seat("主驾", 0, 2);
    step();
    snap("seat-heat");

    push_seat("左后", 2, -1);
    step();
    snap("seat-vent");

    push_seat("副驾", 1, 0);
    step();
    snap("seat-off");

    // --- 断线重连 ---
    g_on_disconnected();
    step();
    snap("waiting"); // 配对过、暂时断开

    g_on_paired();
    step();
    snap("connect-toast"); // 没走配对码 → 连接成功
    delay(PAIRED_TOAST_MS + 100);
    step();
    push_ac_link(22);
    step();

    // --- 固件升级 ---
    g_on_ota(OTA_EVENT_STARTED, 0, 1000);
    g_on_ota(OTA_EVENT_PROGRESS, 640, 1000);
    step();
    snap("ota");

    g_on_ota(OTA_EVENT_DONE, 1000, 1000);
    step();
    snap("ota-restarting");
    // 不真的重启：清掉升级状态回到正常画面
    s_ota_done = false;
    s_restart_at_ms = 0;
    s_screen_state = SCREEN_NORMAL;
    s_needs_redraw = true;
    step();

    // --- BOOT 长按清配对的红色进度环，取一半 ---
    g_boot_pressed = true;
    step();
    delay(BOOT_BTN_PROGRESS_START_MS + (BOOT_BTN_CLEAR_BONDS_MS - BOOT_BTN_PROGRESS_START_MS) / 2);
    step();
    snap("boot-reset");
    g_boot_pressed = false;
    step();

    return 0;
}
