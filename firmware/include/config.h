#pragma once

// ============================================================
// 整个工程的引脚定义都放这一个文件里。
// 其他源文件都 include 这个头文件，不直接写死引脚号，
// 以后你接线方式变了，只改这一处就行。
// 接线表见 docs/旋钮-最小硬件调试方案.md 第 2.1 / 2.2 节
// ============================================================

// --- GC9A01 圆形屏（ESP32-S3 的 SPI2 / FSPI）---
// SPI 大致可以理解成：CLK（时钟）同步节奏，MOSI 传数据（主控→屏幕），
// CS 相当于"我在跟你说话"的信号，DC 用来告诉屏幕当前传的字节
// 是"命令"还是"像素数据"。
#define TFT_SCLK 12   // SPI 时钟
#define TFT_MOSI 11   // SPI 数据（主控 -> 屏幕）
#define TFT_CS   10   // 片选，低电平有效
#define TFT_DC   14   // 数据/命令选择
#define TFT_RST  9    // 硬件复位，低电平有效
#define TFT_BLK  13   // 背光，拉高点亮（也可以接 PWM 调亮度）
#define TFT_MISO -1   // GC9A01 没有数据回传引脚，没什么可读的

#define CONFIG_SCREEN_HOR_RES 240  // 屏幕宽度（像素）
#define CONFIG_SCREEN_VER_RES 240  // 屏幕高度（像素）

// --- EC11 带按压的旋转编码器 ---
// A、B 是两路正交相位信号：转动旋钮时它们会错开一点先后触发，
// 靠"谁先变"就能判断转动方向（具体解码逻辑见 encoder.cpp）。
#define ENC_PIN_A  4
#define ENC_PIN_B  5
#define ENC_PIN_SW 6   // 按键，按下时接地（用内部上拉电阻）

#define ENC_LONG_PRESS_MS 1500            // 按住多久算"长按"（触发真正的提交）
#define ENC_COMMIT_PROGRESS_START_MS 500  // 按住多久开始显示提交进度环（不是一按下就显示，避免单击也扫出一小段）
#define ENC_DOUBLE_CLICK_WINDOW_MS 300    // 双击判定窗口：两次单击松手间隔在这个时间内才算双击（无条件生效，单击也会因此多出最多这个时间的上报延迟）

// --- 开发板自带的 BOOT 键 ---
// 接的是 GPIO0，同时也是"要不要进下载模式"的启动选脚——只有在
// 开机/复位那一瞬间被拉低才会进下载模式，设备已经正常跑起来之后
// 再按它就是个普通输入，不影响运行，所以只能在运行期间（不是开机
// 瞬间）拿它当调试用的按键，见 main.cpp 的"长按清除配对信息"。
#define BOOT_BTN_PIN 0
#define BOOT_BTN_CLEAR_BONDS_MS 5000     // 长按多久触发清除配对信息
#define BOOT_BTN_PROGRESS_START_MS 2000  // 按住多久开始显示红色进度环（不是一按下就显示）
