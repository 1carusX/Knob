# AGENTS.md — 给 AI 编码代理 / 工具的项目说明书

> 改动本仓库前先读本文件 + `docs/INDEX.md`。关键决策记录在 `docs/项目策略备忘录.md`，架构依据在 `docs/壳-DEX热加载架构设计.md`。

## 1. 项目是什么

车机「蓝牙旋钮配件」：硬件（ESP32-S3 旋钮 + 圆形屏）通过自定义 BLE GATT 通道双向通信——上报转/点/长按事件，接收车机推来的显示数据；车机端 App 负责按键→动作映射并执行车控（空调、座椅通风等），同时把车况回显给旋钮屏幕。车机端是 **壳（只签一次）+ DEX 热加载插件（可反复更新）** 架构。

目标车机实测环境：**Android 12（API 32）**。

## 2. 已验证的硬约束（改动设计前必须遵守）

| 约束 | 依据 |
|------|------|
| 无 root、无 adb；装 App 只能 U 盘 | 项目前提 |
| `BYDAUTO_AC_GET/SET`、`BYDAUTO_SETTING_GET/SET` 为 **signature 级**（protectionLevel=2），普通签名 App 拿不到 | `docs/验证结论.md` |
| `BYDAUTO_AC_COMMON`、`BYDAUTO_SETTING_COMMON` 为 **dangerous 级**，可运行时申请并授予 ✅ | 同上 |
| 直接 HAL 车控被 signature 级权限挡死（`[setIntArray] permission deny` / `[getInt] permission deny`） | 同上 |
| **不存在额外的包名白名单机制**：权限门槛就是签名本身，拿到车机厂商签发的签名即可获得对应权限 | `docs/验证结论.md` 关键推论 |
| 车控唯一路线：**用车机厂商签名签壳**（壳进程 UID 继承权限，DEX 继承）。签名已通过官方开发者认证流程申请到位 | `docs/验证结论.md`、`docs/项目历程与架构.md` |
| 旋钮↔手机是**自定义 BLE GATT**，不是标准 BLE HID：最初计划用 HID 键盘 + 无障碍服务捕获按键，实测目标车机未暴露无障碍设置入口、这条路走不通，已弃用；无障碍服务在本项目中已完全移除 | `docs/验证结论.md`、`app/.../ble/BleManager.kt` |
| 插件 DEX **不能打包 Android 资源、不能声明 Manifest 组件**（Activity/Service/Receiver） | `docs/壳-DEX热加载架构设计.md` |
| 插件代码跑在壳进程（壳 UID），`api/` 下的契约类必须同时存在于壳与插件的编译 classpath | 同上 |
| 只用车机厂商官方 SDK 声明的接口；未声明但反射确认存在的方法（如座椅加热）通过反射调用 | `plugin/.../CarControl.kt` |

## 3. 架构与文件地图

### 壳 `app/`（Android 工程，applicationId `cn.icarus.knob`）

| 文件 | 职责 |
|------|------|
| `app/src/main/java/cn/icarus/knob/api/KnobPlugin.kt` | 壳↔DEX 契约接口（四功能域，见下） |
| `app/src/main/java/cn/icarus/knob/api/KnobHost.kt` | 插件反向调壳的能力：`log` / `pushToKnob` |
| `app/src/main/java/cn/icarus/knob/api/Ui.kt` | 纯代码构建 View 的工具（插件无资源，只能走代码 UI） |
| `app/src/main/java/cn/icarus/knob/plugin/PluginLoader.kt` | 从私有目录 `filesDir/knob/plugin.dex` 加载；`importDex()` 把用户选中的 dex 拷进私有目录 |
| `app/src/main/java/cn/icarus/knob/host/KnobHostImpl.kt` | `KnobHost` 实现：日志转 LogSink；`pushToKnob` 原样把 Map 交给 `BleManager.write()` 编码下发 |
| `app/src/main/java/cn/icarus/knob/ble/BleManager.kt` | 手机↔ESP32 旋钮唯一的 BLE 通道：自定义 GATT（按键事件 NOTIFY + 显示同步 WRITE），断线重连轮询，收到按键通知后直接转发给插件 |
| `app/src/main/java/cn/icarus/knob/BootReceiver.kt` | 监听 `BOOT_COMPLETED`/`QUICKBOOT_POWERON`，仅记日志确认车机走的是哪条开机路径 |
| `app/src/main/java/cn/icarus/knob/KnobActivity.kt` | 主界面：左列日志 + 按钮（选择插件/清空日志/保存日志/权限申请）；右列 `pluginContainer` 经 `ui.bind` 交给插件，`onDestroy` 发 `ui.unbind`；`dispatchKeyEvent` 把系统按键（如返回键）转发给插件 |
| `app/src/main/java/cn/icarus/knob/KnobApp.kt` | Application：进程启动时启动 `BleManager` 重连轮询，并**同步**加载插件、调用 `init`（必然早于 Activity.onCreate，`ui.bind` 才能命中） |
| `app/src/main/java/cn/icarus/knob/util/LogSink.kt` / `CrashHandler.kt` | 日志（车机无 adb，App 内 TextView 即唯一观测手段）与崩溃兜底 |

### 插件 `plugin/`（纯 JVM + Kotlin，产物 `plugin/output/plugin.dex`）

| 文件 | 职责 |
|------|------|
| `src/main/kotlin/cn/icarus/knob/plugin/MainPlugin.kt` | `KnobPlugin` 实现：四功能域路由 + 自管页面栈 |
| `src/main/kotlin/cn/icarus/knob/plugin/CarControl.kt` | 车控引擎：BYDAUTO 设备初始化 / 空调（温度·开关·风量·风向·循环·分控）/ 座椅通风·加热 / 环境光传感器读取 |
| `src/main/kotlin/cn/icarus/knob/plugin/PluginSelfTest.kt` | 一键全量自检 |
| `src/main/kotlin/cn/icarus/knob/api/` | 与壳 `api/` 逐字节相同的三个契约文件（KnobPlugin / KnobHost / Ui） |
| `src/main/flags-real/…/BuildFlags.kt`、`src/main/flags-simulate/…/BuildFlags.kt` | 同路径两份文件，只有 `SIMULATE_CAR_CONTROL` 值不同，分别参与 `main`/`simulate` source set 编译（见 §6） |

> 新增车控功能 = 只改插件（`CarControl.kt` 加方法 + `MainPlugin.kt` 加一条 `when` 分支）+ 重新 `pluginDex`，**壳不用动、不用重签**。

### KnobPlugin 契约（四功能域）

接口按【功能域】分方法而非一功能一方法，新增功能只加路由分支，接口本身不变 → 壳无需重签：

```kotlin
fun init(context: Context, host: KnobHost)
fun onCommand(command: String, params: Map<String, Any>): Map<String, Any>?  // 写：init.all / seat.ventilating / ac.*
fun onQuery(query: String, params: Map<String, Any>): Map<String, Any>?      // 读：perm.check / selfTest / seat.ventilating / ac.temperature
fun onEvent(event: String, params: Map<String, Any>): Boolean                // 事件：key / ui.bind / ui.unbind
fun onPush(type: String, data: Map<String, Any>)                             // 壳主动推：state / bluetooth / hardware / config
fun onDestroy()
```

`onCommand` / `onQuery` 统一返回 `{"success":Boolean, "code":Int?, "value":Any?, "message":String}`；未知命令返回 `null`。

**按键统一走 `onEvent("key", {"code": Int, "action": Int, "value": Int?})`**，但有两条不同的入口：旋钮本身的转/点/长按经 `BleManager` 解码自定义 BLE GATT 的按键通知特征值，由 `BleManager.dispatchKeyToPlugin` 直接调插件，不经过 Activity；车机物理键、系统返回键则走 `KnobActivity.dispatchKeyEvent` 这个入口。两条路径最终都落到同一个 `onEvent("key", ...)` 调用上，没有 `back.pressed` 之类的专用事件。`value` 字段只有旋钮长按确认时才带（旋钮转动只改自身屏幕的本地状态，不逐档上报），其余按键没有这个字段。`KnobActivity` 用 `dispatchKeyEvent` 而非 `onKeyDown` 是因为它在 View 树分发之前拿到事件，不会被获得焦点的按钮抢走 DPAD/ENTER。插件消费某个键时**按下与抬起要成对返回 true**，只吃一半会让系统按键状态错乱（返回键的出栈逻辑见 `MainPlugin.handleKey`）。

**UI 绑定也走事件域**，接口不为它单开方法：
- `onEvent("ui.bind", {"container": ViewGroup})` —— `KnobActivity.onCreate` 把右半边容器交给插件，插件开始渲染并自管页面栈。
- `onEvent("ui.unbind", {})` —— `KnobActivity.onDestroy` 通知插件断开对该容器/View 树的持有。

⚠️ **壳进程常驻、Activity 反复创建销毁**，所以 `ui.unbind` 是必须的：插件（静态持有）若继续引用旧容器，会泄漏已销毁的 Activity，且下次 `ui.bind` 复用旧 View 时抛 `already has a parent`。Activity 销毁**只解绑 UI，绝不能 `PluginLoader.unload()`**（那会连插件状态和外设连接一起丢掉）。

## 4. 构建命令

```bash
# 壳（产物 app/app/build/outputs/apk/release/app-release-unsigned.apk）
cd app && ./gradlew assembleRelease

# 插件（真实车控）：pluginJar → d8 → plugin/output/plugin.dex（依赖本机 ANDROID_HOME；SDK 路径见 plugin/build.gradle.kts 的 fallback）
cd plugin && ./gradlew pluginDex

# 插件（本地模拟，不碰真实车控接口）：同样流程 → plugin/output/plugin-simulate.dex
cd plugin && ./gradlew pluginDexSimulate
```

- 两个工程均为 Kotlin + JVM target 17，SDK 34（`minSdk 26`），Kotlin 2.1.10。
- 插件编译依赖 `plugin/libs/bydauto-openapi.jar`（车机厂商官方车控 SDK，`compileOnly`，运行时由车机系统提供）与 `android.jar`；jar 已入库，克隆后无需额外放置。
- 工程未配 `signingConfig`，`assembleRelease` 只产出 **unsigned** APK；装车前需用车机厂商签名单独签。
- 命令行构建需先设 `JAVA_HOME`（本机无系统级 JDK，可用 Gradle 已 provision 的 `~/.gradle/jdks/jetbrains_s_r_o_-21-amd64-linux.2`）；Android Studio 内构建会自带 JBR。
- `local.properties`、`build/`、`.gradle/` 不入库（见 `.gitignore`）；克隆后 Android Studio 会自动重建 `local.properties`。

## 5. 插件分发与加载

**不走外部存储路径**（避免 Android 11+ 的分区存储限制，也不需要存储权限）：

1. 编译出 `plugin/output/plugin.dex`，拷到 U 盘/车机任意可见位置。
2. 壳里点「📥 选择插件」→ SAF 文件选择器选中 dex。
3. `PluginLoader.importDex()` 先删旧文件（旧的是只读的，不删无法覆盖）→ 拷进私有目录 `filesDir/knob/plugin.dex` → `setReadOnly()`（W^X 要求）→ `DexClassLoader` 加载（parent = 壳 classLoader）、实例化 `cn.icarus.knob.plugin.MainPlugin`、调 `init`，随后 Activity 发 `ui.bind` 交出容器。
4. 之后每次启动由 `KnobApp` 自动从私有目录加载，无需再选。更新插件 = 重复步骤 1-2 覆盖。

### 进程生命周期

- 开机自启改用广播接收器：`BootReceiver` 静态注册 `BOOT_COMPLETED` 与 `QUICKBOOT_POWERON`（部分定制系统的非标准开机 action）。`onReceive()` 本身只记一条日志，进程能被系统投递广播前必须先拉起——这是 Android 标准广播分发机制，不需要用户手动打开任何开关（这点跟以前依赖的无障碍服务不同，无障碍服务需要用户在系统设置里手动启用才生效）。
- ⚠️ Android 系统会扣住"从未被用户主动启动过 / 已被强制停止"的 App 的隐式广播（含 `BOOT_COMPLETED`），直到用户手动打开一次——`KnobApp.kt` 注释里"车机重启后需要手动点开一次 App 才会运行"说的就是这条系统限制，不是 bug；手动打开过一次之后，`BootReceiver` 在后续开机时才能正常生效。
- 进程不靠特殊绑定常驻：退出 Activity 不会销毁进程（标准 Android 行为），BLE 连接由 `BleManager` 的断线重连轮询维持，可以挂在壳里长期存活。

## 6. 调试与验证流程

插件有两个编译变体，靠 source set 切换一个常量、同一次 Gradle 构建各出一份 dex，不用手改值来回切换重编译（见 `plugin/build.gradle.kts` 的 `pluginDex`/`pluginDexSimulate`，常量定义在 `flags-real`/`flags-simulate` 两份同路径的 `BuildFlags.kt` 里）：

1. **`plugin-simulate.dex`**（`SIMULATE_CAR_CONTROL = true`）：不碰真实 BYDAUTO 接口，先在无签名机器上验证按键路由、BLE GATT 链路、插件页面栈这条完整链路。
2. **`plugin.dex`**（`SIMULATE_CAR_CONTROL = false`）：接真实车控接口，需要车机厂商签名版壳才能拿到 signature 级权限，装车联调用。

壳只需要签名一次（已通过官方开发者认证流程拿到），两个插件变体之后都通过「选择插件」重复导入切换，不用重新签名、重新装车。日志窗口（`LogSink`）是唯一观测手段，所有操作/返回码都要打进去。

## 7. 约定

- 壳保持薄：**业务逻辑一律放插件**，不要往壳里加车控代码。
- 改 `api/` 下任何文件，**必须同步改另一份**（壳 `app/.../api/` 与插件 `plugin/.../api/` 内容需完全一致），否则运行时签名不匹配。
- 插件内避免使用壳已有的类名（类加载冲突）；不引用壳私有资源，UI 一律用 `Ui.kt` 纯代码构建。
- 所有操作/返回码要进 `LogSink` 日志（车机无 adb，日志即唯一观测手段；返回码含义见 `app/使用与验证指南.md`）。
- 实车/第三方资料得出的结论，写进 `docs/验证结论.md`（或 `docs/项目历程与架构.md`），不要只留在别处。

## 8. 已知问题 / 待办

| 问题 | 位置 | 说明 |
|------|------|------|
| `api/` 三个类被打进 dex | `plugin/build.gradle.kts` 的 `pluginJar` | 双亲委派下运行时实际用壳的类，暂不致命；若将来把 parent 改成 null 会 `ClassCastException` |
| `WRITE_SECURE_SETTINGS` 拿不到 | `AndroidManifest.xml` | signature 级权限，探测阶段残留，当前代码未用 |
