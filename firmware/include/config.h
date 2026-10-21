#pragma once

// ============================================================
// 整个工程的引脚定义都放这一个文件里。
// 其他源文件都 include 这个头文件，不直接写死引脚号，
// 以后你接线方式变了，只改这一处就行。
// 接线表见 docs/旋钮-最小硬件调试方案.md 第 2.1 / 2.2 节
//
// 两块硬件引脚不同：自制 PCB（BOARD_PCB）和 DevKitC 开发板（BOARD_DEVKIT），
// 由 platformio.ini 里每个 env 的 build_flags 定义其中一个宏。两块板子
// 不一样的引脚放在下面的 #if 分支里，其余（时序参数、分辨率等）共用。
// ============================================================

#if defined(BOARD_PCB) == defined(BOARD_DEVKIT)
#error "必须且只能定义 BOARD_PCB / BOARD_DEVKIT 其中一个（见 platformio.ini 的 env）"
#endif

// --- GC9A01 圆形屏（ESP32-S3 的 SPI2 / FSPI）---
// SPI 大致可以理解成：CLK（时钟）同步节奏，MOSI 传数据（主控→屏幕），
// CS 相当于"我在跟你说话"的信号，DC 用来告诉屏幕当前传的字节
// 是"命令"还是"像素数据"。
#define TFT_SCLK 12   // SPI 时钟
#define TFT_MOSI 11   // SPI 数据（主控 -> 屏幕）
#define TFT_CS   10   // 片选，低电平有效
#define TFT_RST  9    // 硬件复位，低电平有效
#define TFT_MISO -1   // GC9A01 没有数据回传引脚，没什么可读的

#if defined(BOARD_PCB)
#define TFT_DC   46   // 数据/命令选择（GPIO46 是启动选择脚，但屏幕 DC 是高阻输入，
                      // 不会把它拉高，芯片内部下拉能保持住，不影响启动）
#define TFT_BLK  -1   // 自制板的屏幕插座只有 7 针，背光没接到主控（屏幕模块内部常亮），
                      // -1 = 没接，display.cpp 会跳过所有背光/调亮度操作
#define TFT_ROTATION 2  // 自制板上屏幕装配方向跟开发板差 180 度，转 2 档（2×90°）补回来
#else // BOARD_DEVKIT
#define TFT_DC   14   // 数据/命令选择
#define TFT_BLK  13   // 背光，PWM 调亮度
#define TFT_ROTATION 0
#endif
// TFT_ROTATION 取 0~3，每档 90 度，交给 GFX 库设屏幕控制器的扫描方向
// （硬件转，LVGL 不用管），要跟屏幕物理装配方向匹配画面才是正的。

#define CONFIG_SCREEN_HOR_RES 240  // 屏幕宽度（像素）
#define CONFIG_SCREEN_VER_RES 240  // 屏幕高度（像素）

// --- EC11 带按压的旋转编码器 ---
// A、B 是两路正交相位信号：转动旋钮时它们会错开一点先后触发，
// 靠"谁先变"就能判断转动方向（具体解码逻辑见 encoder.cpp）。
// 按键（SW）按下时接地（用内部上拉电阻）。
#if defined(BOARD_PCB)
#define ENC_PIN_A  47
#define ENC_PIN_B  48
// ⚠️ GPIO45 是启动选择脚：按芯片手册，复位瞬间被拉高会让芯片给 Flash/PSRAM
// 供 1.8V，而 WROOM-1 MCN16R8 的 Flash/PSRAM 都是 3.3V 的。自制板这根线上
// 焊了 10kΩ 外部上拉（R5），但照样正常启动——espefuse summary 确认
// （2026-10-20）模块出厂已烧 VDD_SPI_FORCE=1、VDD_SPI_TIEH=1，供电电压
// 由 eFuse 固定成 3.3V，不再看 GPIO45。这是官方保证的：WROOM-1 数据手册
// 第 8 章写明带 PSRAM 的模块都会这样烧 eFuse；不带 PSRAM 的型号（N4/N8/
// N16，没有 R 后缀）没烧，换成那种模块必须拆掉 R5。
#define ENC_PIN_SW 45
#else // BOARD_DEVKIT
#define ENC_PIN_A  4
#define ENC_PIN_B  5
#define ENC_PIN_SW 6
#endif

#define ENC_LONG_PRESS_MS 1500            // 按住多久算"长按"（触发真正的提交）
#define ENC_COMMIT_PROGRESS_START_MS 500  // 按住多久开始显示提交进度环（不是一按下就显示，避免单击也扫出一小段）
#define ENC_DOUBLE_CLICK_WINDOW_MS 300    // 双击判定窗口：两次单击松手间隔在这个时间内才算双击（无条件生效，单击也会因此多出最多这个时间的上报延迟）

// --- 开发板自带的 BOOT 键 ---
// 两块板子共用：自制 PCB 上同样有 BOOT 键（SW3 接 GPIO0 到地，R10 10kΩ
// 上拉到 3.3V），跟开发板的接法一样，不用单独分支。
// 接的是 GPIO0，同时也是"要不要进下载模式"的启动选脚——只有在
// 开机/复位那一瞬间被拉低才会进下载模式，设备已经正常跑起来之后
// 再按它就是个普通输入，不影响运行，所以只能在运行期间（不是开机
// 瞬间）拿它当调试用的按键，见 main.cpp 的"长按清除配对信息"。
#define BOOT_BTN_PIN 0
#define BOOT_BTN_CLEAR_BONDS_MS 5000     // 长按多久触发清除配对信息
#define BOOT_BTN_PROGRESS_START_MS 2000  // 按住多久开始显示红色进度环（不是一按下就显示）
