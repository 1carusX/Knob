#include "ble.h"

#include <Arduino.h>
#include <NimBLEDevice.h>
#include <NimBLEHIDDevice.h>
#include <esp_mac.h>
#include <string.h>

// 自定义服务下两个特征值：
//   kKeyEventCharUUID   NOTIFY  knob -> 手机，旋钮动作事件
//   kDisplayCharUUID    WRITE   手机 -> knob，同步显示数据（通用键值对）给屏幕
static const char *kServiceUUID = "29afa70d-2312-4f04-9b36-fe9298284756";
static const char *kKeyEventCharUUID = "182f303d-ea98-4ee4-839e-5599aaf3f29d";
static const char *kDisplayCharUUID = "5f774e59-5079-4e69-8e5d-de0ee1220d3f";

// 标准 HID Report Map（键盘 boot descriptor），仅用于让下面挂的那个标准
// HID 服务（0x1812）看起来是个结构完整的 HID 设备——真正的按键数据完全
// 走上面的自定义服务，这份 report map 描述的 Input Report 永远不会被
// notify()，纯粹是"摆设"，见 ble_init() 里的说明。
static const uint8_t kHidReportMap[] = {
    0x05, 0x01, // Usage Page (Generic Desktop Ctrls)
    0x09, 0x06, // Usage (Keyboard)
    0xA1, 0x01, // Collection (Application)
    0x85, 0x01, //   Report Id (1)
    0x05, 0x07, //   Usage Page (Kbrd/Keypad)
    0x19, 0xE0, //   Usage Minimum (0xE0)
    0x29, 0xE7, //   Usage Maximum (0xE7)
    0x15, 0x00, //   Logical Minimum (0)
    0x25, 0x01, //   Logical Maximum (1)
    0x75, 0x01, //   Report Size (1)
    0x95, 0x08, //   Report Count (8)
    0x81, 0x02, //   Input (Data,Var,Abs)
    0x95, 0x01, //   Report Count (1)
    0x75, 0x08, //   Report Size (8)
    0x81, 0x01, //   Input (Const,Array,Abs)
    0x95, 0x05, //   Report Count (5)
    0x75, 0x01, //   Report Size (1)
    0x05, 0x08, //   Usage Page (LEDs)
    0x19, 0x01, //   Usage Minimum (Num Lock)
    0x29, 0x05, //   Usage Maximum (Kana)
    0x91, 0x02, //   Output (Data,Var,Abs)
    0x95, 0x01, //   Report Count (1)
    0x75, 0x03, //   Report Size (3)
    0x91, 0x01, //   Output (Const,Array,Abs)
    0x95, 0x06, //   Report Count (6)
    0x75, 0x08, //   Report Size (8)
    0x15, 0x00, //   Logical Minimum (0)
    0x25, 0x65, //   Logical Maximum (101)
    0x05, 0x07, //   Usage Page (Kbrd/Keypad)
    0x19, 0x00, //   Usage Minimum (0x00)
    0x29, 0x65, //   Usage Maximum (0x65)
    0x81, 0x00, //   Input (Data,Array,Abs)
    0xC0,       // End Collection
};

static NimBLECharacteristic *s_key_event_char = nullptr;
static void (*s_display_update_cb)(const DisplayData &data) = nullptr;

// 复用同一块缓冲区解析每次收到的显示数据，不用堆分配。
static DisplayData s_display_data;

static volatile bool s_connected = false;
static volatile bool s_authenticated = false;

static void (*s_passkey_cb)(uint32_t passkey) = nullptr;
static void (*s_paired_cb)() = nullptr;
static void (*s_disconnected_cb)() = nullptr;

// 广播用的设备名，ble_init() 里算出来存这儿——main.cpp 未连接时要在
// 屏幕上提示"去手机蓝牙设置里找这个名字"，需要在 ble_init() 之后随时
// 能读到，不能只是 ble_init() 内部一个局部变量。
static char s_device_name[16] = "";

// 多块板子刷同一份固件时，蓝牙名字不能都叫一样——名字只是给人看的、
// 蓝牙协议本身认设备靠地址和密钥，不会真的连错，但人在手机配对列表里
// 认名字，一堆同名设备很容易手滑点错。这里在名字后面拼上芯片蓝牙 MAC
// 地址的后 2 字节，让每块板子广播出来的名字都不一样。
static void build_device_name() {
    uint8_t mac[6];
    esp_read_mac(mac, ESP_MAC_BT); // 出厂烧录的蓝牙 MAC，不依赖 BLE 栈已初始化
    snprintf(s_device_name, sizeof(s_device_name), "Knob-%02X%02X", mac[4], mac[5]);
}

// 通用键值对解码：壳（BleManager）编码时完全不理解字段含义，格式是
// 一连串 entry，每条：
//   byte  keyLen
//   bytes key (ASCII, keyLen 字节)
//   byte  type  0=int16 / 1=string / 2=bool
//   值本身：int16 是 2 字节小端；string 是 1 字节长度 + UTF-8 内容
//           （超过 MAX_STR_LEN 会被壳裁掉）；bool 是 1 字节 0/1
// 遇到不认识的 type 就没法知道占几个字节，直接停止解析——后面的 entry
// 宁可丢掉也不能瞎猜着继续读，会把后面的数据全解析错。
static void parse_display_data(const uint8_t *buf, size_t len, DisplayData &out) {
    out.count = 0;
    size_t pos = 0;
    while (pos < len && out.count < DisplayData::MAX_ENTRIES) {
        if (pos + 2 > len) break; // 至少要有 keyLen(1) + type(1)
        uint8_t keyLen = buf[pos++];
        if (pos + keyLen > len) break;
        DisplayData::Entry &e = out.entries[out.count];
        size_t copyKeyLen = keyLen > DisplayData::MAX_KEY_LEN ? DisplayData::MAX_KEY_LEN : keyLen;
        memcpy(e.key, buf + pos, copyKeyLen);
        e.key[copyKeyLen] = '\0';
        pos += keyLen;

        if (pos + 1 > len) break;
        e.type = buf[pos++];

        if (e.type == 0) { // int16
            if (pos + 2 > len) break;
            e.intValue = (int16_t)(buf[pos] | (buf[pos + 1] << 8)); // 小端
            pos += 2;
        } else if (e.type == 1) { // string
            if (pos + 1 > len) break;
            uint8_t strLen = buf[pos++];
            if (pos + strLen > len) break;
            size_t copyStrLen = strLen > DisplayData::MAX_STR_LEN ? DisplayData::MAX_STR_LEN : strLen;
            memcpy(e.strValue, buf + pos, copyStrLen);
            e.strValue[copyStrLen] = '\0';
            pos += strLen;
        } else if (e.type == 2) { // bool
            if (pos + 1 > len) break;
            e.boolValue = buf[pos++] != 0;
        } else {
            break; // 未知类型，无法安全跳过，停止解析
        }
        out.count++;
    }
}

int16_t DisplayData::getInt(const char *key, int16_t defaultValue) const {
    for (int i = 0; i < count; i++) {
        if (entries[i].type == 0 && strcmp(entries[i].key, key) == 0) return entries[i].intValue;
    }
    return defaultValue;
}

const char *DisplayData::getString(const char *key, const char *defaultValue) const {
    for (int i = 0; i < count; i++) {
        if (entries[i].type == 1 && strcmp(entries[i].key, key) == 0) return entries[i].strValue;
    }
    return defaultValue;
}

bool DisplayData::getBool(const char *key, bool defaultValue) const {
    for (int i = 0; i < count; i++) {
        if (entries[i].type == 2 && strcmp(entries[i].key, key) == 0) return entries[i].boolValue;
    }
    return defaultValue;
}

bool DisplayData::has(const char *key) const {
    for (int i = 0; i < count; i++) {
        if (strcmp(entries[i].key, key) == 0) return true;
    }
    return false;
}

// 手机往 kDisplayCharUUID 写数据时触发：解析成通用键值对，转发给
// main.cpp 通过 ble_on_display_update() 注册的回调。
class DisplayUpdateCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic *characteristic, NimBLEConnInfo &connInfo) override {
        NimBLEAttValue value = characteristic->getValue();
        parse_display_data(value.data(), value.length(), s_display_data);

        Serial.printf("[BLE] display update: %d fields\n", s_display_data.count);
        for (int i = 0; i < s_display_data.count; i++) {
            const DisplayData::Entry &e = s_display_data.entries[i];
            if (e.type == 0) {
                Serial.printf("  %s=%d\n", e.key, e.intValue);
            } else if (e.type == 1) {
                Serial.printf("  %s=\"%s\"\n", e.key, e.strValue);
            } else {
                Serial.printf("  %s=%s\n", e.key, e.boolValue ? "true" : "false");
            }
        }

        if (s_display_update_cb != nullptr) {
            s_display_update_cb(s_display_data);
        }
    }
};
static DisplayUpdateCallbacks s_display_update_callbacks;

// 连接/配对生命周期。配对时自动确认自己这一侧（不等物理按键），真正的
// 人工核对靠人去比屏幕（见 ble_on_passkey，main.cpp 会显示这个码）和
// 串口打印的 6 位数是不是跟手机弹窗上的一样，没有削弱安全性，只是没有
// 强制要求人去核对。
class ServerCallbacks : public NimBLEServerCallbacks {
    void onConnect(NimBLEServer *server, NimBLEConnInfo &connInfo) override {
        s_connected = true;
        Serial.println("[BLE] host connected");

        // 主动请求短连接间隔 + 短 supervision timeout——默认值可能有
        // 十几二十秒，knob 断电重启后手机要等这个超时才会发现连接死了，
        // 之后才轮到重连逻辑，等待时间主要耗在这里，不是轮询间隔。
        // 参数单位：interval 是 1.25ms 单位（12=15ms，24=30ms），
        // latency=0（每个连接事件都应答），timeout 是 10ms 单位
        // （300=3000ms=3秒）——留了远超协议要求下限（2×30ms=60ms）的
        // 余量，不会因为正常的信号波动就被当成掉线。
        server->updateConnParams(connInfo.getConnHandle(), 12, 24, 0, 300);
    }

    void onDisconnect(NimBLEServer *server, NimBLEConnInfo &connInfo, int reason) override {
        s_connected = false;
        s_authenticated = false;
        Serial.printf("[BLE] host disconnected (reason=%d), restarting advertising\n", reason);
        NimBLEDevice::startAdvertising();
        if (s_disconnected_cb != nullptr) {
            s_disconnected_cb();
        }
    }

    void onAuthenticationComplete(NimBLEConnInfo &connInfo) override {
        s_authenticated = connInfo.isEncrypted();
        Serial.println(s_authenticated ? "[BLE] pairing succeeded" : "[BLE] pairing failed");
        if (s_authenticated && s_paired_cb != nullptr) {
            s_paired_cb();
        }
    }

    void onConfirmPassKey(NimBLEConnInfo &connInfo, uint32_t pass_key) override {
        Serial.printf("[BLE] passkey: %06u (should match what the phone shows)\n", pass_key);
        if (s_passkey_cb != nullptr) {
            s_passkey_cb(pass_key);
        }
        NimBLEDevice::injectConfirmPasskey(connInfo, true);
    }
};
static ServerCallbacks s_server_callbacks;

void ble_init() {
    build_device_name();
    Serial.printf("[BLE] initializing as \"%s\"...\n", s_device_name);

    NimBLEDevice::init(s_device_name);

    // Arduino-ESP32 3.x + NimBLE-Arduino 2.5.1 上 init() 之后 NimBLE host
    // 任务需要一点时间才真正就绪，太早配置安全参数/建 server 会失败。
    uint32_t t = millis();
    while (!NimBLEDevice::isInitialized()) {
        delay(10);
        if (millis() - t > 5000) {
            Serial.println("[BLE] ERROR: NimBLE failed to initialize after 5s");
            return;
        }
    }

    // Passkey（数字比对）配对：MITM + Secure Connections，DisplayYesNo
    // 触发数字比对而不是要求手机输入数字。
    NimBLEDevice::setSecurityAuth(BLE_SM_PAIR_AUTHREQ_BOND |
                                   BLE_SM_PAIR_AUTHREQ_MITM |
                                   BLE_SM_PAIR_AUTHREQ_SC);
    NimBLEDevice::setSecurityIOCap(BLE_HS_IO_DISPLAY_YESNO);
    NimBLEDevice::setSecurityInitKey(BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID);
    NimBLEDevice::setSecurityRespKey(BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID);

    NimBLEServer *server = NimBLEDevice::createServer();
    server->setCallbacks(&s_server_callbacks);

    NimBLEService *service = server->createService(kServiceUUID);

    // 两个特征值都带上 *_ENC，要求访问前链路必须是加密的——之前没加，
    // ATT 层从来没强制要求加密，导致杀 App 重启后手机重新 connectGatt()
    // 时，只要不碰会要求加密的操作，蓝牙栈完全没必要重新走一遍加密
    // 流程，onAuthenticationComplete() 就不会再触发，s_authenticated
    // 一直卡在 false，knob 屏幕卡在"等待配对"（其实链路是通的，只是
    // 没加密，knob 这边认为没配对成功）。加上这两个 flag 后，每次
    // 重连手机都得先重新建立加密才能读写这两个特征值，onAuthentication
    // Complete() 也就能保证每个连接周期都至少触发一次。
    s_key_event_char = service->createCharacteristic(
        kKeyEventCharUUID, NIMBLE_PROPERTY::NOTIFY | NIMBLE_PROPERTY::READ_ENC);
    // 标准 Characteristic User Description（0x2901）：私有 128 位 UUID 没有
    // 官方注册名字，nRF Connect 之类的工具只能显示 "Unknown"，挂这个描述符
    // 让它们能读出一个人类可读的标签，纯调试可读性用途，不影响协议本身。
    s_key_event_char->createDescriptor((uint16_t)0x2901, NIMBLE_PROPERTY::READ)->setValue("Knob Key Event");

    NimBLECharacteristic *displayChar = service->createCharacteristic(
        kDisplayCharUUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_ENC);
    displayChar->setCallbacks(&s_display_update_callbacks);
    displayChar->createDescriptor((uint16_t)0x2901, NIMBLE_PROPERTY::READ)->setValue("Knob Display Sync");

    // 车机的蓝牙设置界面会按"设备类型"过滤扫描结果，只展示手机类/HID类
    // 设备，我们这个纯自定义 GATT 外设会被过滤掉、压根不出现在列表里。
    // 这里额外挂一个标准 HID 服务（0x1812）+ 把广播的 Appearance 设成
    // Keyboard，纯粹是为了让车机认为这是个 HID 设备、愿意展示出来——
    // 上面那两个自定义特征值（按键 NOTIFY / 显示 WRITE）才是真正干活的
    // 通道，完全不受影响；这个 HID 服务里的 Input Report 不会被真的
    // notify()，只是挂在 GATT 表里充数用。
    NimBLEHIDDevice *hid = new NimBLEHIDDevice(server);
    hid->setHidInfo(0x00, 0x02); // country=0（未本地化），flags=NormallyConnectable
    hid->setReportMap(const_cast<uint8_t *>(kHidReportMap), sizeof(kHidReportMap));
    hid->getInputReport(1); // 只声明结构，不会被调用 notify()

    server->start();

    NimBLEAdvertising *adv = NimBLEDevice::getAdvertising();
    adv->addServiceUUID(kServiceUUID);
    adv->addServiceUUID(hid->getHidService()->getUUID());
    adv->setAppearance(HID_KEYBOARD);
    NimBLEAdvertisementData scanResponse;
    scanResponse.setName(s_device_name);
    adv->setScanResponseData(scanResponse);
    NimBLEDevice::startAdvertising();

    Serial.printf("[BLE] advertising as \"%s\"\n", s_device_name);
}

const char *ble_get_device_name() {
    return s_device_name;
}

bool ble_has_any_bond() {
    return NimBLEDevice::getNumBonds() > 0;
}

void ble_clear_all_bonds() {
    // 配对时我们让手机分发了 IRK（setSecurityInitKey/RespKey 里的
    // BLE_SM_PAIR_KEY_DIST_ID），NimBLE 删配对记录时要连带把 IRK 从
    // "地址解析列表"里也摘掉，这个操作在广播中是不允许做的——
    // ble_gap_unpair() 一看 ble_gap_adv_active()==true 就直接返回
    // BLE_HS_EBUSY，删除失败。而 knob 只要没连接就一直在广播（见
    // ServerCallbacks::onDisconnect 和 ble_init() 末尾），所以之前
    // 不管什么时候按都会失败。这里先停广播、删完再重新广播。
    NimBLEDevice::stopAdvertising();
    bool ok = NimBLEDevice::deleteAllBonds();
    NimBLEDevice::startAdvertising();
    Serial.printf("[BLE] clear all bonds: %s\n", ok ? "ok" : "failed");
}

bool ble_is_paired() {
    return s_authenticated;
}

// 把一个 int16 字段编码成 "fieldId(1) + type(1)=0 + value(2,小端)"，写进
// buf，返回写入的字节数——跟 DisplayData 用的是同一套 type 约定（0=int16），
// 只是用数字 ID 代替字段名文本，按键事件这条通道字节量更小。
static size_t encode_int16_field(uint8_t *buf, uint8_t fieldId, int16_t value) {
    buf[0] = fieldId;
    buf[1] = 0; // type = int16
    buf[2] = (uint8_t)(value & 0xFF);
    buf[3] = (uint8_t)((value >> 8) & 0xFF);
    return 4;
}

void ble_send_key(int16_t keycode) {
    if (!ble_is_paired() || s_key_event_char == nullptr) {
        return;
    }
    uint8_t payload[4]; // 一个字段
    size_t pos = encode_int16_field(payload, KEY_FIELD_CODE, keycode);
    s_key_event_char->notify(payload, pos);
}

void ble_send_key_with_value(int16_t keycode, int16_t value) {
    if (!ble_is_paired() || s_key_event_char == nullptr) {
        return;
    }
    uint8_t payload[8]; // 两个字段，各 4 字节
    size_t pos = 0;
    pos += encode_int16_field(payload + pos, KEY_FIELD_CODE, keycode);
    pos += encode_int16_field(payload + pos, KEY_FIELD_VALUE, value);
    s_key_event_char->notify(payload, pos);
}

void ble_on_display_update(void (*callback)(const DisplayData &data)) {
    s_display_update_cb = callback;
}

void ble_on_passkey(void (*callback)(uint32_t passkey)) {
    s_passkey_cb = callback;
}

void ble_on_paired(void (*callback)()) {
    s_paired_cb = callback;
}

void ble_on_disconnected(void (*callback)()) {
    s_disconnected_cb = callback;
}
