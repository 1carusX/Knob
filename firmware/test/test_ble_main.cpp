// 独立 BLE 测试固件：验证 ESP32-S3 能不能被手机/车机配对连接，配对
// 加密建立后按键事件通知和显示同步写入两条自定义特征值能不能正常工作。
// 不依赖屏幕、不依赖编码器。
// 编译烧录：pio run -e test_ble_pcb（开发板用 test_ble_devkit）-t upload -t monitor
//
// 用法：烧录后用 nRF Connect 连上 "Knob-XXXX"（后 4 位是该板子蓝牙 MAC
// 的后 2 字节，每块板子不一样），配对方式是 Passkey（数字比对）：ESP32
// 和手机会各自显示一个 6 位数，串口和手机弹窗上应该是同一个数字，确认
// 一致后在手机上点确认即可，不需要输入。
//
// 验证按键事件通知（29afa70d-... 服务下 182f303d-... 特征值）：在
// nRF Connect 里对这个特征值点"订阅"（Enable Notifications），连上后
// 每隔 2 秒交替发一次 Tab（单字段）和 Enter 带值（两字段），应该能在
// nRF Connect 里看到收到的数据交替变化：
//   01 00 3D 00                        （KEYCODE_TAB=61，只有 code 字段）
//   01 00 42 00 02 00 2A 00            （KEYCODE_ENTER=66, value=42）
// 每 4 字节一个字段：fieldId(1) + type(1)=0(int16) + value(2,小端)。
// fieldId=1=KEY_FIELD_CODE，fieldId=2=KEY_FIELD_VALUE。
// 串口也会打印发送日志。
//
// 顺带验证 ble_on_display_update()：找到同一服务下的可写特征值
// （5f774e59-...），写 9 字节 "05 76 61 6C 75 65 00 16 00"——这是通用
// 键值对编码里的一条 entry：keyLen=5, key="value"(76 61 6C 75 65),
// type=0(int16), 值=0x0016=22（小端）。串口应该打出
// "[BLE] display update: 1 fields" + "  value=22"，然后
// "[TestBLE] display update: 1 fields, value=22"。
// 完整格式（多个 entry、string/bool 类型）见 ble.h 里 DisplayData 的注释。

#include <Arduino.h>
#include "ble.h"

static void on_display_update(const DisplayData &data) {
    Serial.printf("[TestBLE] display update: %d fields, value=%d\n",
                  data.count, data.getInt("value"));
}

void setup() {
    Serial.begin(115200);
    delay(200);
    Serial.println("[TestBLE] boot");

    ble_on_display_update(on_display_update);
    ble_init();
}

void loop() {
    static bool was_paired = false;
    bool paired = ble_is_paired();
    if (paired != was_paired) {
        was_paired = paired;
        Serial.println(paired ? "[TestBLE] paired" : "[TestBLE] not paired");
    }

    static uint32_t last_send_ms = 0;
    static bool send_tab = true;
    if (paired && millis() - last_send_ms >= 2000) {
        last_send_ms = millis();
        if (send_tab) {
            Serial.println("[TestBLE] send TAB");
            ble_send_key(KEYCODE_TAB);
        } else {
            Serial.println("[TestBLE] send ENTER, value=42");
            ble_send_key_with_value(KEYCODE_ENTER, 42);
        }
        send_tab = !send_tab;
    }

    delay(20);
}
