// 独立编码器测试固件：只验证 EC11 本身转动方向、消抖、短按/长按
// 是否正常。不依赖屏幕、不依赖 LVGL——接线只按
// docs/旋钮-最小硬件调试方案.md 第 2.2 节接编码器那三根信号线即可。
// 编译烧录：pio run -e test_encoder_pcb（V2 用 test_encoder_pcb_v2，开发板用 test_encoder_devkit）-t upload -t monitor
//
// 复用了 encoder.cpp/encoder.h（跟正式 demo 是同一份驱动代码），
// 这里只是把结果打到串口，而不是接去驱动屏幕 UI。

#include <Arduino.h>
#include "encoder.h"

void setup() {
    Serial.begin(115200);
    delay(200);
    Serial.println("[TestEncoder] boot");

    encoder_init();

    Serial.println("[TestEncoder] ready: rotate / click / long-press and watch the log");
}

void loop() {
    encoder_poll_button();

    int16_t diff = encoder_get_diff();
    if (diff != 0) {
        Serial.printf("[TestEncoder] rotate diff = %d\n", diff);
    }

    if (encoder_take_click()) {
        Serial.println("[TestEncoder] click");
    }

    if (encoder_take_long_press()) {
        Serial.println("[TestEncoder] long-press");
    }

    delay(5);
}
