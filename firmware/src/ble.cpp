#include "ble.h"

#include <Arduino.h>
#include <NimBLEDevice.h>
#include <NimBLEHIDDevice.h>
#include <Update.h>
#include <esp_mac.h>
#include <mbedtls/sha256.h>
#include <string.h>

#include "config.h"
#include "fw_version_gen.h"

// 自定义服务下三个特征值：
//   kKeyEventCharUUID   NOTIFY        knob -> 手机，旋钮动作事件
//   kDisplayCharUUID    WRITE         手机 -> knob，同步显示数据（通用键值对）给屏幕
//   kOtaCharUUID        WRITE+NOTIFY  手机 -> knob 推新固件，knob 用通知回报结果（协议见下方 OTA 一节）
static const char *kServiceUUID = "29afa70d-2312-4f04-9b36-fe9298284756";
static const char *kKeyEventCharUUID = "182f303d-ea98-4ee4-839e-5599aaf3f29d";
static const char *kDisplayCharUUID = "5f774e59-5079-4e69-8e5d-de0ee1220d3f";
static const char *kOtaCharUUID = "468889d4-9176-4aa2-9a9e-cd7dd3ab1ae0";

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

// ==================== 固件 OTA ====================
//
// 手机往 kOtaCharUUID 写的每一包第 1 个字节是操作码，多字节整数一律小端：
//   0x01 BEGIN  + size(4) + sha256(32)  准备接收 size 字节的固件
//   0x02 DATA   + offset(4) + 数据       offset 必须正好等于已收字节数，不接受乱序/重传
//   0x03 END                             收齐了：校验 SHA-256，通过才切换启动分区
//   0x04 ABORT                           放弃，当前固件不受影响
// 用带应答的写（WRITE_TYPE_DEFAULT）：手机收到上一包的写回执才发下一包，
// 回执在 onWrite() 返回之后才发，onWrite 里把数据写进 flash 就天然起到
// 流控作用，不用另做窗口/确认。
//
// knob 用同一个特征值的通知回报结果：status(1) + 已收字节数(4)。只在
// BEGIN 成功、END 成功、任何一步出错时发——DATA 正常写入不发通知（写
// 回执已经说明收到了），手机收到错误通知就停止发送。
enum : uint8_t {
    OTA_OP_BEGIN = 0x01,
    OTA_OP_DATA = 0x02,
    OTA_OP_END = 0x03,
    OTA_OP_ABORT = 0x04,
};
enum : uint8_t {
    OTA_STATUS_READY = 0x00,        // BEGIN 成功，可以开始发 DATA
    OTA_STATUS_DONE = 0x01,         // END 校验通过、已切换启动分区，马上重启
    OTA_STATUS_ERR_BEGIN = 0x80,    // Update.begin() 失败（比如固件比分区还大）
    OTA_STATUS_ERR_SEQUENCE = 0x81, // 没 BEGIN 就发 DATA/END，或者 offset 对不上
    OTA_STATUS_ERR_WRITE = 0x82,    // 写 flash 失败
    OTA_STATUS_ERR_VERIFY = 0x83,   // 收齐了，但 SHA-256 / 字节数对不上
    OTA_STATUS_ERR_END = 0x84,      // Update.end() 失败（固件镜像格式不对等）
    OTA_STATUS_ERR_FORMAT = 0x85,   // 包太短 / 操作码不认识
};

static NimBLECharacteristic *s_ota_char = nullptr;
static void (*s_ota_cb)(OtaEvent event, uint32_t received, uint32_t total) = nullptr;
// 收到 BEGIN 之后这么久没再收到数据就自己放弃：手机那边出错没来得及发 ABORT
// 时（比如插件被重新导入），连接还在、不会触发断线放弃，屏幕会一直停在升级画面
static const uint32_t OTA_IDLE_TIMEOUT_MS = 20000;

// 只在 NimBLE 任务里碰（onWrite / onDisconnect 都在这个任务上串行执行）。
static bool s_ota_active = false;
static uint32_t s_ota_size = 0;
static uint32_t s_ota_received = 0;
static volatile uint32_t s_ota_last_ms = 0; // 最近一次收到 BEGIN/DATA 的时间，见 ble_ota_check_timeout
static uint8_t s_ota_expected_sha[32];
static mbedtls_sha256_context s_ota_sha;

static uint32_t read_u32_le(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static void ota_notify(uint8_t status) {
    if (s_ota_char == nullptr) return;
    uint8_t payload[5] = {
        status,
        (uint8_t)(s_ota_received & 0xFF),
        (uint8_t)((s_ota_received >> 8) & 0xFF),
        (uint8_t)((s_ota_received >> 16) & 0xFF),
        (uint8_t)((s_ota_received >> 24) & 0xFF),
    };
    s_ota_char->notify(payload, sizeof(payload));
}

// 放弃当前这次 OTA（没在进行就什么都不做）：丢掉已写进另一个分区的内容，
// 当前在跑的固件和启动分区都不动。
static void ota_abort(const char *reason) {
    if (!s_ota_active) return;
    Update.abort();
    mbedtls_sha256_free(&s_ota_sha);
    s_ota_active = false;
    Serial.printf("[OTA] aborted at %u/%u: %s\n", (unsigned)s_ota_received, (unsigned)s_ota_size, reason);
    if (s_ota_cb != nullptr) s_ota_cb(OTA_EVENT_FAILED, s_ota_received, s_ota_size);
}

static void ota_fail(uint8_t status, const char *reason) {
    ota_abort(reason);
    ota_notify(status);
}

static void ota_handle_begin(const uint8_t *buf, size_t len) {
    if (len != 1 + 4 + 32) {
        ota_notify(OTA_STATUS_ERR_FORMAT);
        return;
    }
    ota_abort("restarted by a new BEGIN"); // 上一次没收完又来了新的 BEGIN，从头来
    s_ota_size = read_u32_le(buf + 1);
    s_ota_received = 0;
    memcpy(s_ota_expected_sha, buf + 5, sizeof(s_ota_expected_sha));
    if (s_ota_size == 0 || !Update.begin(s_ota_size, U_FLASH)) {
        Serial.printf("[OTA] begin(%u) failed: %s\n", (unsigned)s_ota_size, Update.errorString());
        ota_notify(OTA_STATUS_ERR_BEGIN);
        return;
    }
    mbedtls_sha256_init(&s_ota_sha);
    mbedtls_sha256_starts(&s_ota_sha, 0); // 0 = SHA-256（不是 SHA-224）
    s_ota_last_ms = millis();
    s_ota_active = true;
    Serial.printf("[OTA] begin, %u bytes\n", (unsigned)s_ota_size);
    if (s_ota_cb != nullptr) s_ota_cb(OTA_EVENT_STARTED, 0, s_ota_size);
    ota_notify(OTA_STATUS_READY);
}

static void ota_handle_data(const uint8_t *buf, size_t len) {
    if (!s_ota_active || len < 1 + 4) {
        ota_fail(OTA_STATUS_ERR_SEQUENCE, "DATA without BEGIN");
        return;
    }
    uint32_t offset = read_u32_le(buf + 1);
    const uint8_t *data = buf + 5;
    size_t data_len = len - 5;
    if (offset != s_ota_received || s_ota_received + data_len > s_ota_size) {
        ota_fail(OTA_STATUS_ERR_SEQUENCE, "unexpected offset / too much data");
        return;
    }
    // Update::write 的参数不是 const，但不会改数据
    if (Update.write(const_cast<uint8_t *>(data), data_len) != data_len) {
        ota_fail(OTA_STATUS_ERR_WRITE, Update.errorString());
        return;
    }
    mbedtls_sha256_update(&s_ota_sha, data, data_len);
    s_ota_received += data_len;
    s_ota_last_ms = millis();
    if (s_ota_cb != nullptr) s_ota_cb(OTA_EVENT_PROGRESS, s_ota_received, s_ota_size);
}

static void ota_handle_end() {
    if (!s_ota_active) {
        ota_notify(OTA_STATUS_ERR_SEQUENCE);
        return;
    }
    uint8_t actual_sha[32];
    mbedtls_sha256_finish(&s_ota_sha, actual_sha);
    if (s_ota_received != s_ota_size || memcmp(actual_sha, s_ota_expected_sha, sizeof(actual_sha)) != 0) {
        ota_fail(OTA_STATUS_ERR_VERIFY, "size or SHA-256 mismatch");
        return;
    }
    // end() 会再校验一遍固件镜像本身，通过才把启动分区切到新固件
    if (!Update.end()) {
        ota_fail(OTA_STATUS_ERR_END, Update.errorString());
        return;
    }
    mbedtls_sha256_free(&s_ota_sha);
    s_ota_active = false;
    Serial.printf("[OTA] done, %u bytes verified, boot partition switched\n", (unsigned)s_ota_received);
    ota_notify(OTA_STATUS_DONE);
    if (s_ota_cb != nullptr) s_ota_cb(OTA_EVENT_DONE, s_ota_received, s_ota_size);
}

class OtaCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic *characteristic, NimBLEConnInfo &connInfo) override {
        NimBLEAttValue value = characteristic->getValue();
        const uint8_t *buf = value.data();
        size_t len = value.length();
        if (len < 1) {
            ota_notify(OTA_STATUS_ERR_FORMAT);
            return;
        }
        switch (buf[0]) {
            case OTA_OP_BEGIN: ota_handle_begin(buf, len); break;
            case OTA_OP_DATA: ota_handle_data(buf, len); break;
            case OTA_OP_END: ota_handle_end(); break;
            case OTA_OP_ABORT: ota_abort("aborted by phone"); break;
            default: ota_fail(OTA_STATUS_ERR_FORMAT, "unknown opcode"); break;
        }
    }
};
static OtaCallbacks s_ota_callbacks;

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
        ota_abort("disconnected"); // 收到一半断线：已写的内容作废，当前固件不受影响
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

    // OTA 每包能带多少数据取决于 MTU，NimBLE 默认上限只有 255，提到 BLE
    // 允许的最大值 517（实际值取手机和这里的较小者，见插件 requestMtu）。
    NimBLEDevice::setMTU(517);

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

    s_ota_char = service->createCharacteristic(
        kOtaCharUUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_ENC | NIMBLE_PROPERTY::NOTIFY);
    s_ota_char->setCallbacks(&s_ota_callbacks);
    s_ota_char->createDescriptor((uint16_t)0x2901, NIMBLE_PROPERTY::READ)->setValue("Knob Firmware OTA");

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

    // 固件版本号和板子名称挂在 HID 自带的设备信息服务（0x180A）里，用
    // 标准的"固件版本"（0x2A26）/"硬件版本"（0x2A27）特征值，nRF Connect
    // 之类的工具也能直接认出来。插件连上后读这两个值：显示版本，以后
    // OTA 也靠它判断要不要升级。跟上面两个自定义特征值一样要求加密。
    NimBLEService *deviceInfo = hid->getDeviceInfoService();
    deviceInfo->createCharacteristic((uint16_t)0x2A26, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::READ_ENC)
        ->setValue(FW_VERSION);
    deviceInfo->createCharacteristic((uint16_t)0x2A27, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::READ_ENC)
        ->setValue(BOARD_NAME);

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

void ble_ota_check_timeout() {
    // ⚠️ 跑在 loop() 线程，ota_abort 平时跑在 NimBLE 线程。只在已经 20 秒
    // 没收到任何数据时才会走到这里，此时 NimBLE 线程不会同时在写 flash；
    // 万一放弃之后手机又发来 DATA，会因为 s_ota_active=false 收到 ERR_SEQUENCE。
    if (s_ota_active && millis() - s_ota_last_ms > OTA_IDLE_TIMEOUT_MS) {
        ota_abort("no data for too long");
    }
}

void ble_on_ota(void (*callback)(OtaEvent event, uint32_t received, uint32_t total)) {
    s_ota_cb = callback;
}
