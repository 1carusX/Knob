# Knob — 车机蓝牙旋钮配件

> 目标车机的「蓝牙物理旋钮配件」DIY 项目。
>
> **硬件端**：ESP32-S3 + 圆形屏 + 旋转编码器，通过自定义 BLE GATT 服务上报"转/点/长按"事件，同时接收车况显示数据。
> **车机端**：Android App（壳 + DEX 热加载插件），解析旋钮事件并翻译成车控动作（空调 / 座椅通风加热），同时把车况回显给旋钮屏幕。

## 核心思路

```mermaid
flowchart LR
    HW["硬件配件<br/>ESP32-S3 + 编码器 + 圆形屏"]
    subgraph CAR["车机"]
        SHELL["壳 App<br/>车机厂商签名，只签一次"]
        DEX["plugin.dex<br/>全部车控/业务逻辑，可热更新"]
        SHELL -- DexClassLoader --> DEX
    end
    HW <-- "自定义 BLE GATT<br/>双向：事件 / 回显" --> SHELL
```

- **硬件 = 旋钮**，不知道也不关心自己在控制什么，只管上报事件、显示手机推来的数据。
- **App = 大脑**，负责事件→动作映射，执行车控，并把结果回显给旋钮屏幕。
- **壳只签名一次**，后续所有代码更新通过下发 `plugin.dex` 完成，避免反复"上传→签名→装车"。

（最初计划走标准 BLE HID 键盘 + 无障碍服务捕获按键，实测目标车机没有暴露无障碍服务开关入口、这条路走不通，现已替换为上面这套自定义 GATT 方案，详见 `docs/项目历程与架构.md`。）

## 目录结构

```
├── app/                 # 壳 App（Android 工程，cn.icarus.knob）
│   ├── 使用与验证指南.md  # App 用法 / 实车验证步骤说明书
│   └── app/src/main/java/cn/icarus/knob/
│       ├── api/          # 壳↔DEX 契约：KnobPlugin / KnobHost / Ui
│       ├── plugin/       # PluginLoader：发现并加载 plugin.dex
│       ├── ble/          # BleManager：自定义 BLE GATT 连接管理与编解码
│       ├── host/         # KnobHostImpl：壳向插件提供的能力
│       ├── util/         # 日志（LogSink）/ CrashHandler
│       └── KnobActivity / KnobApp / BootReceiver
├── plugin/               # 插件工程（编译产出 output/plugin.dex，可反复更新）
│   └── src/main/kotlin/cn/icarus/knob/
│       ├── api/               # 与壳完全相同的契约副本（改动须两边同步）
│       └── plugin/
│           ├── MainPlugin.kt      # KnobPlugin 接口实现（入口 + 交互状态机）
│           ├── CarControl.kt      # 车控引擎（BYDAUTO 调用）
│           └── PluginSelfTest.kt  # 一键全量自检
├── firmware/             # 旋钮固件（PlatformIO，ESP32-S3+GC9A01+EC11，含自定义 BLE GATT 通信）
├── store/                # 应用商店素材（图标 + 预览图）
└── docs/                 # 全部分析知识 / 方案
    ├── INDEX.md          # ← 文档索引（先读这个）
    ├── 项目历程与架构.md   # 完整发展历程 + 当前架构/协议/交互设计
    ├── 验证结论.md        # 实车验证事实清单（持续更新）
    └── ROADMAP.md        # 路线图与当前状态
```

## 快速开始

```bash
# 壳 App → app/app/build/outputs/apk/release/app-release-unsigned.apk
cd app && ./gradlew assembleRelease

# 插件 → plugin/output/plugin.dex（SDK 版本见根目录 gradle/libs.versions.toml，SDK 路径自动探测）
cd plugin && ./gradlew pluginDex
```

装车流程：U 盘安装壳 APK（需车机厂商签名版）→ 打开壳点「📥 选择插件」，用文件选择器选中 `plugin.dex`（自动拷进 App 私有目录，之后每次启动自动加载）→ 右半边出现插件页面即成功。详见 `app/使用与验证指南.md`。

## 关键事实（已验证）

- 无 root、无 adb，只能 U 盘装 APK。
- `BYDAUTO_AC_GET/SET`、`BYDAUTO_SETTING_GET/SET` 是 **signature 级权限**（protectionLevel=2），普通签名 App 车机不授予 → 直接 HAL 车控被挡死（实测，见 `docs/验证结论.md`）。
- 能直接控车的应用都持有车机厂商签发的签名，且不存在额外的包名白名单——拿到签名即获得对应权限。本项目已通过官方开发者认证流程申请到车机厂商签名，只用官方 SDK 声明的接口（未声明的方法通过反射调用，见 `docs/项目历程与架构.md`）。
- 无障碍服务方案已弃用：最初计划用无障碍服务捕获蓝牙 HID 按键，实测目标车机没有暴露无障碍服务开关入口，无法启用，已替换为自定义 BLE GATT 双向通道，无障碍服务在本项目中不再使用。

## 文档导航

所有知识文档的索引见 [`docs/INDEX.md`](docs/INDEX.md)；工具/代理请先读 [`AGENTS.md`](AGENTS.md)。
