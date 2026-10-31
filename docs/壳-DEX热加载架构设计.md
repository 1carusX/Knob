# 壳 + DEX 热加载架构设计方案

## 目标
**减少车机厂商签名次数**：壳只签名一次，后续所有代码更新都通过下发 DEX 实现，无需重新签名。

## 核心原理
- 壳 App 用车机厂商签名，进程获得 BYDAUTO 权限（signature 级）
- 外部 DEX 通过 `DexClassLoader` 加载，代码运行在**壳进程**（壳的 UID）
- 权限是**进程级**（按 UID），DEX 继承壳的全部权限 → 能调 BYDAUTO
- 改 DEX 不影响壳的 APK 签名 → 无需重新签名

## 架构图

```mermaid
flowchart TD
    SHELL["壳 App（cn.icarus.knob，车机厂商签名一次）<br/>· KnobApp（加载器）：启动时读取私有目录<br/>filesDir/knob/plugin.dex → DexClassLoader 加载<br/>→ 实例化插件入口 → 调 init()<br/>· 内置框架：蓝牙 BLE、日志、UI 壳、权限<br/>· api/：KnobPlugin / KnobHost / Ui（壳↔DEX 契约）"]
    PLUGIN["plugin.dex（可反复更新，无需重签）<br/>· 实现 KnobPlugin 接口<br/>· 全部车控逻辑（BYDAUTO 调用）<br/>· 全部业务逻辑、测试序列、蓝牙协议处理"]
    SHELL -- "DexClassLoader（继承壳 ClassLoader）" --> PLUGIN
```

## 职责划分

### 壳 App（固定，签名一次）
- **启动加载器**：发现并加载 plugin.dex
- **KnobPlugin 接口**：定义插件契约
- **基础设施**：蓝牙 BLE、日志、UI 壳、权限申请、CrashHandler
- **尽量薄**：不含具体车控/业务逻辑（都放 DEX）

### 插件 DEX（可更新）
- 实现 `KnobPlugin` 接口
- **全部车控逻辑**：空调、座椅、分控等（调 BYDAUTO）
- **全部业务逻辑**：蓝牙协议解析、按键映射、测试序列
- 可反复修改、编译、下发

## KnobPlugin 接口设计（四功能域）

按【功能域】分方法，而不是一个功能一个方法。新增功能只需在插件内加一条路由分支，接口本身保持稳定 → 壳不用动、不用重签：

```kotlin
interface KnobPlugin {
    fun init(context: Context, host: KnobHost)

    // 控制域（写）：init.all / seat.ventilating / ac.temperature / ac.power / ac.windlevel ...
    fun onCommand(command: String, params: Map<String, Any>): Map<String, Any>?

    // 查询域（读）：perm.check / selfTest / seat.ventilating / ac.temperature
    fun onQuery(query: String, params: Map<String, Any>): Map<String, Any>?

    // 事件域：key（所有按键，含返回键）/ ui.bind / ui.unbind
    fun onEvent(event: String, params: Map<String, Any>): Boolean

    // 推送域：壳任意时刻主动推数据 —— state / bluetooth / hardware / config
    fun onPush(type: String, data: Map<String, Any>)

    fun onDestroy()
}
```

`onCommand` / `onQuery` 统一返回 `{"success":Boolean, "code":Int?, "value":Any?, "message":String}`，未知命令/查询返回 `null`。

**按键统一走 `onEvent("key", {"code", "action"})`**：蓝牙 HID 旋钮/按键、物理键、系统返回键都由 `KnobActivity.dispatchKeyEvent` 一个入口转发，不设 `back.pressed` 这类专用事件。插件消费某键时按下与抬起须成对返回 true。

**UI 容器的绑定也走事件域**，不为它单开接口方法：`onEvent("ui.bind", {"container": ViewGroup})` 交出右半边容器，插件开始渲染并自管页面栈；`onEvent("ui.unbind", {})` 收回。

壳进程常驻而 Activity 反复创建销毁，所以 `ui.unbind` 是必须的：插件被 `PluginLoader` 静态持有，若继续引用旧容器会泄漏已销毁的 Activity 及整棵 View 树，且下次 `ui.bind` 复用旧 View 时抛 `already has a parent`。解绑里 `removeAllViews()` 同时解决这两点。Activity 销毁**只解绑 UI，绝不能 `unload()` 插件**。

配套的 `KnobHost` 是反向通道（插件→壳）：`log(message)` 打日志、`pushToKnob(data)` 请求壳通过 BLE 下发到旋钮屏。

## 加载流程

插件只存放在 App 私有目录，**不读写外部存储**（规避 Android 11+ 分区存储限制，也无需存储权限）。

```
首次导入（KnobActivity 点顶栏「导入插件」）
  → SAF 文件选择器返回 content Uri
  → PluginLoader.importDex()
      → delete() 旧文件（旧文件是只读的，不删无法覆盖）
      → 拷贝到 filesDir/knob/plugin.dex
  → 立即 loadDex()（失败则删除损坏文件）

每次进程启动（KnobApp.onCreate，同步执行，不再延迟）
  → PluginLoader.load()：读 filesDir/knob/plugin.dex
  → setReadOnly()：W^X 要求，可写的 dex 会被 ART 拒绝
  → DexClassLoader(dexPath, cacheDir/plugin_opt, null, 壳ClassLoader)
  → cl.loadClass("cn.icarus.knob.plugin.MainPlugin")
  → 实例化，强转 KnobPlugin
  → plugin.init(context, KnobHostImpl)
  ↓ Application.onCreate 必然早于本进程任何 Activity.onCreate
KnobActivity.onCreate
  → plugin.onEvent("ui.bind", {"container": pluginContainer})   // 插件开始渲染自己的页面
KnobActivity.onDestroy
  → plugin.onEvent("ui.unbind", {})    // 只解绑 UI，不 unload 插件
```

**进程由谁拉起**：车机开机时系统会绑定已启用的 `KnobAccessibilityService`，进程随之启动并跑完上面的加载流程，
不需要 `RECEIVE_BOOT_COMPLETED`。同一个绑定也让进程常驻 —— 退出 Activity 不销毁进程，BLE 等外设连接可长期挂在壳里。
（待实测：车机定制系统开机后是否自动恢复无障碍服务的启用状态。）

## 分发方式
- 编译出 `plugin/output/plugin.dex` → 拷到 U 盘 → 车机上用壳的「导入插件」导入
- 改逻辑 → 重新 `pluginDex` → 重新导入覆盖 → 生效（壳不用重装、不用重签）

## 插件 DEX 的编译
- 用 Android Studio 建一个 **Android Library 模块**（或纯 Java/Kotlin 工程）
- 依赖：壳提供的 KnobPlugin 接口 jar、车机厂商官方 jar、Android SDK
- 输出：DEX 文件（用 d8 把 classes.jar 转成 classes.dex）

## 关键约束（务必遵守）

0. **W^X（Android 10+，targetSdk≥29）**：ART 拒绝加载「本进程可写」的 dex，抛 `SecurityException: Writable dex file '...' is not allowed`。
   → 插件文件落盘后必须 `setReadOnly()` 再加载；覆盖更新前必须先 `delete()`（只读文件无法直接覆盖写）。
   2026-08-28 实机复现并修复于 `PluginLoader`。车机是 Android 12，同样命中。
1. **DEX 不能打包 Android 资源**（res/）→ 业务逻辑避免用资源，用代码创建 UI 或用壳提供的资源
2. **DEX 不能声明 Android 组件**（Activity/Service/Receiver 的 Manifest）→ 只放普通类（车控逻辑是普通类，没问题）
3. **DEX 用到的第三方库**要一起打进 DEX 并处理 ClassLoader
4. **`api/` 下的契约类必须同时存在于壳和 DEX 的编译 classpath**（DEX 引用壳的接口，运行时由壳提供）。当前做法是两个工程各存一份内容完全相同的源码，改动时**必须两边同步**

## 待验证风险
- **包名白名单**（⚠️ 推断，未验证）：若车机服务端除签名外还校验包名白名单，则 `cn.icarus.knob` 即便拿到车机厂商签名也可能被拒。这是本架构最大的前提性风险，签名版装车时优先验证。
- 车机是否检测/限制动态加载 DEX（需签名版实测）
- 类加载冲突（DEX 里避免用壳已有的类）
- ~~部分系统对动态加载的 DEX 有路径限制~~（已规避：dex 与优化目录都在 App 私有目录）

## 后续路线
- 阶段1：搭好壳 + 加载器 + KnobPlugin 接口，DEX 先放一个测试插件验证能加载 ✅
- 阶段2：把车控逻辑迁移进 DEX ✅
- 阶段3：U 盘分发 + 导入加载验证（等签名版装车）
- 阶段4：把蓝牙通路也整合进来

> 实时状态见 `ROADMAP.md`。
