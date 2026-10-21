// 独立屏幕测试固件：只验证 GC9A01 本身能不能点亮、方向/颜色对不对，
// 顺带验证背光 PWM 调光这条链路（呼吸灯效果，亮度应该平滑忽明忽暗）。
// 不依赖 LVGL、不依赖编码器——接线只按 docs/旋钮-最小硬件调试方案.md
// 第 2.1 节接屏幕那几根线即可。
// 编译烧录：pio run -e test_display_pcb（开发板用 test_display_devkit）-t upload -t monitor

#include <Arduino.h>
#include <Arduino_GFX_Library.h>
#include "config.h"

static Arduino_GC9A01 *screen;

// 依次填充几种纯色再画一行字：颜色对了 = SPI 接线和驱动没问题，
// 文字方向正常（不是倒的/镜像的）= rotation 参数跟屏幕的物理装配方向一致。
static const uint16_t COLORS[] = {RGB565_RED, RGB565_GREEN, RGB565_BLUE, RGB565_WHITE, RGB565_BLACK};
static const int COLOR_COUNT = sizeof(COLORS) / sizeof(COLORS[0]);
static int s_color_idx = 0;
static uint32_t s_last_color_change_ms = 0;
constexpr uint32_t COLOR_INTERVAL_MS = 1000;

// 背光循环调亮度（呼吸灯：0~255 来回渐变），验证 PWM 调光这条链路——
// 亮度应该平滑忽明忽暗，不能瞬间跳变或者完全没反应（没反应说明背光
// 那根线接的引脚不支持 PWM，或者接线本身有问题）。固件正式版
// （display.cpp）目前还是简单拉高背光，没有走 PWM，这里单独验证。
constexpr int BL_PWM_FREQ_HZ = 5000;
constexpr int BL_PWM_RESOLUTION_BITS = 8; // 占空比 0~255
static int s_brightness = 255;
static int s_brightness_step = -5;
static uint32_t s_last_brightness_step_ms = 0;
constexpr uint32_t BRIGHTNESS_STEP_INTERVAL_MS = 20;

void setup() {
    Serial.begin(115200);
    delay(200);
    Serial.println("[TestDisplay] boot");

    Arduino_DataBus *bus = new Arduino_HWSPI(TFT_DC, TFT_CS, TFT_SCLK, TFT_MOSI, TFT_MISO);
    screen = new Arduino_GC9A01(bus, TFT_RST, /*rotation=*/0, /*IPS=*/true);
    screen->begin();

    // Arduino-ESP32 3.x 的新 ledc API，直接按引脚操作，不用像旧版那样
    // 先 ledcSetup(channel,...) 再 ledcAttachPin(pin, channel)。
    // TFT_BLK 为 -1 表示背光没接到主控（见 config.h），跳过调光测试。
    if (TFT_BLK >= 0) {
        ledcAttach(TFT_BLK, BL_PWM_FREQ_HZ, BL_PWM_RESOLUTION_BITS);
        ledcWrite(TFT_BLK, s_brightness);
    }

    Serial.println("[TestDisplay] screen init done, cycling colors + brightness");
}

static void updateColor() {
    uint32_t now = millis();
    if (now - s_last_color_change_ms < COLOR_INTERVAL_MS) return;
    s_last_color_change_ms = now;

    uint16_t bg = COLORS[s_color_idx];
    screen->fillScreen(bg);
    screen->setCursor(40, 110);
    screen->setTextColor(bg == RGB565_BLACK ? RGB565_WHITE : RGB565_BLACK);
    screen->setTextSize(2);
    screen->print("HELLO");
    Serial.printf("[TestDisplay] fill color #%d\n", s_color_idx);

    s_color_idx = (s_color_idx + 1) % COLOR_COUNT;
}

static void updateBrightness() {
    if (TFT_BLK < 0) return;
    uint32_t now = millis();
    if (now - s_last_brightness_step_ms < BRIGHTNESS_STEP_INTERVAL_MS) return;
    s_last_brightness_step_ms = now;

    s_brightness += s_brightness_step;
    if (s_brightness <= 0) {
        s_brightness = 0;
        s_brightness_step = 5;
        Serial.println("[TestDisplay] brightness = 0 (最暗)");
    } else if (s_brightness >= 255) {
        s_brightness = 255;
        s_brightness_step = -5;
        Serial.println("[TestDisplay] brightness = 255 (最亮)");
    }
    ledcWrite(TFT_BLK, s_brightness);
}

void loop() {
    updateColor();
    updateBrightness();
    delay(5);
}
