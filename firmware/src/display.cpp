#include "display.h"
#include "config.h"

// LVGL 从来不会直接往屏幕上画，它先画到这块内存缓冲区里，
// 画完一块区域后再交给下面的 disp_flush_cb() —— 整个工程里
// 只有这个函数真正跟硬件打交道。40 行缓冲在动画流畅度和内存
// 占用（240*40*2 字节，RGB565 每像素 2 字节）之间是个还不错的折中。
static uint8_t buf1[CONFIG_SCREEN_HOR_RES * 40 * 2];
static SCREEN_CLASS *screen;

// 背光 PWM 参数，跟 test_display_main.cpp 里验证过的一致。
constexpr int BL_PWM_FREQ_HZ = 5000;
constexpr int BL_PWM_RESOLUTION_BITS = 8; // 占空比 0~255

// LVGL 每画完一块矩形区域的像素就会调这个回调。area 是这块矩形的
// 位置和大小，px_map 是像素数据——display_init() 里把颜色格式设成了
// RGB565_SWAPPED（大端字节序），跟 Arduino_GFX 的 draw16bitBeRGBBitmap
// 要的字节序一致，直接转过去用就行，不用自己再转一次字节序。
static void disp_flush_cb(lv_display_t *disp, const lv_area_t *area, uint8_t *px_map) {
    uint32_t w = area->x2 - area->x1 + 1;
    uint32_t h = area->y2 - area->y1 + 1;
    screen->draw16bitBeRGBBitmap(area->x1, area->y1, (uint16_t *)px_map, w, h);
    lv_display_flush_ready(disp);
}

void display_init() {
    // 1) 先把物理屏幕通过 SPI 点亮。
    Arduino_DataBus *bus = new Arduino_HWSPI(TFT_DC, TFT_CS, TFT_SCLK, TFT_MOSI, TFT_MISO);
    // rotation 按板子取（见 config.h 的 TFT_ROTATION），要跟屏幕的物理
    // 装配方向匹配才能让画面正着显示。
    screen = new SCREEN_CLASS(bus, TFT_RST, TFT_ROTATION, /*IPS=*/true);
    screen->begin();
    screen->fillScreen(RGB565_BLACK);

    // 背光走 PWM（Arduino-ESP32 3.x 按引脚操作的新 ledc API），不是简单
    // 拉高——手机端插件会根据车机光照传感器算出目标亮度推过来（见
    // display_set_brightness），默认先给满亮度。
    // TFT_BLK 为 -1 表示背光没接到主控（见 config.h），整段跳过。
    if (TFT_BLK >= 0) {
        ledcAttach(TFT_BLK, BL_PWM_FREQ_HZ, BL_PWM_RESOLUTION_BITS);
        display_set_brightness(100);
    }

    // 2) 初始化 LVGL，并告诉它怎么找到刚才点亮的这块屏幕。
    lv_init();
    // 之前 v8 时代靠 lv_conf.h 里的 LV_TICK_CUSTOM 配置生效；v9 把它
    // 改成了运行时注册回调，millis() 的签名（uint32_t(*)(void)）正好
    // 跟 lv_tick_get_cb_t 对上，直接传函数指针就行，不用包一层。
    lv_tick_set_cb(millis);
    lv_display_t *disp = lv_display_create(CONFIG_SCREEN_HOR_RES, CONFIG_SCREEN_VER_RES);
    // 之前 v8 时代靠 lv_conf.h 里的 LV_COLOR_16_SWAP=1 全局生效；v9 把
    // 这个字节序改成了按每个 display 单独设置的颜色格式，效果一样。
    lv_display_set_color_format(disp, LV_COLOR_FORMAT_RGB565_SWAPPED);
    lv_display_set_buffers(disp, buf1, NULL, sizeof(buf1), LV_DISPLAY_RENDER_MODE_PARTIAL);
    lv_display_set_flush_cb(disp, disp_flush_cb);
}

void display_task_handler() {
    // 让 LVGL 处理定时器/动画，并把变化的像素刷到屏幕上。
    lv_timer_handler();
}

void display_set_brightness(uint8_t percent) {
    if (TFT_BLK < 0) return; // 背光没接到主控，调不了亮度
    if (percent > 100) percent = 100;
    uint8_t duty = (uint8_t)((uint16_t)percent * 255 / 100);
    ledcWrite(TFT_BLK, duty);
}
