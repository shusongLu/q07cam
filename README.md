# q07battery — 长安启源 Q07 车机实时功率显示

在 Q07 原厂车机上显示**动力电池实时功率**、**发动机转速**，并逐步做多屏（副驾/HUD）与转向灯联动。

---

## 一、车机硬约束（先看这个）

| 约束 | 影响 |
|---|---|
| 可开**无线 ADB** | 能 logcat / dumpsys / 拉系统 App（侦察全靠它） |
| ADB **禁止安装 APK**（CAKJ 校验） | 有**白名单包名空位**可绕（见 `recon/install-bypass.md`）|
| 有开发者账号 → **官网分发** | 装包慢 → 所以做了**热更**，规避反复上传 |
| **不需要 root** | 普通 App 即可读 SOA / 播侧摄像头（已实测）|

可行性（**均已实车验证**）：

| 功能 | 可行性 | 说明 |
|---|---|---|
| 中控显示功率 / 转速 / 挡位 | ✅ | 走厂商 SOA（`recon/soa-signal-map.md`），普通 App 就能读 |
| 悬浮窗（浮在普通界面之上） | ✅ | `TYPE_APPLICATION_OVERLAY` + `appops set ... SYSTEM_ALERT_WINDOW allow` |
| 打转向灯显示**侧摄像头** | ✅ | 厂商侧视是 **H.265 RTSP**（`172.16.6.26:8888/lt|rt`），用厂商 `libffmpeg.so` 播 |
| 开机自启 / 常驻 | ✅ | 前台服务 + `BOOT_COMPLETED` |
| 悬浮到 **HUD** | ❌ | HUD 多为私有投屏 |
| 副驾屏启动**别的** App | ❌ | 需要系统权限 |

---

## 二、架构：**壳 + 可热更插件 dex**

```
┌──────────────────── app（壳，只装一次）────────────────────┐
│ Manifest：MainActivity / OverlayService（组件必须一次性声明好）│
│ PluginRuntime：加载 plugin.dex，分发信号给 UI / 悬浮窗        │
│ UI / 悬浮窗 / 副屏 Presentation（壳负责显示）                 │
└───────────────────────────┬─────────────────────────────────┘
                            │ 依赖 core 接口
┌──────────────────── core（纯接口，打进壳）──────────────────┐
│ VehiclePlugin / PluginHost / VehicleSignals / 版本常量        │
└───────────────────────────┬─────────────────────────────────┘
                            │ compileOnly（运行期由壳提供）
┌──────────────── plugin（热更 dex，随时替换）─────────────────┐
│ DefaultVehiclePlugin：注册广播 / 解析 / 算功率 / emit 数据    │
└──────────────────────────────────────────────────────────────┘
```

**关键点**

- 插件加载优先顺序：`getExternalFilesDir()/plugin.dex`（adb push）→ `filesDir/plugin.dex`（将来远程下载）
- 插件**只依赖 core 接口**，运行时通过父类加载器拿到 core / Android / Kotlin 类
- 接口带 `PLUGIN_INTERFACE_VERSION`，不匹配就拒绝加载，避免崩溃
- **能热更**：插件里的全部逻辑
- **不能热更**：manifest 组件、权限、壳代码、`core` 接口

---

## 三、目录结构

```
q07battery/
├── core/          纯 Kotlin 接口模块（打进壳）
├── app/           Android 壳
│   └── java/com/lushu/q07battery/
│       ├── PluginRuntime.kt        插件加载/重载/信号分发
│       ├── MainActivity.kt         中控界面 + 屏幕枚举 + 调试入口
│       ├── OverlayService.kt       悬浮窗（功率 + 转向占位面板）
│       └── ui/DashboardPresentation.kt  副屏仪表
├── plugin/        可热更逻辑（构建成 plugin.dex）
├── tools/
│   └── build-plugin-dex.ps1        构建插件 dex（可 -Push 直接推车机）
└── recon/         车机侦察（adbrecon.ps1 / livewatch.ps1 / PLAYBOOK.md）
```

---

## 四、构建与热更

### 构建壳 APK

```powershell
cd C:\Users\lushu\source\repos\q07battery
.\gradlew.bat --offline :app:assembleDebug
# 产物: app\build\outputs\apk\debug\app-debug.apk
```

### 构建并推送插件（热更）

```powershell
# 只构建 dex
powershell -ExecutionPolicy Bypass -File .\tools\build-plugin-dex.ps1

# 构建 + push 到车机（无需重装 APK）
powershell -ExecutionPolicy Bypass -File .\tools\build-plugin-dex.ps1 -Push -Serial <IP:端口>
```

推送后在车机上点 **「重新加载插件（热更生效）」**（或重启 App）即可。

> **PowerShell 执行策略**：本机默认禁止运行 .ps1。两种办法：
> - 临时：`powershell -ExecutionPolicy Bypass -File <脚本>`
> - 永久：`Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`

### 已验证的热更闭环

真机（小米，无线 ADB）实测：改 `plugin` 里一个常量 → 重新打包 dex → `adb push` → 不重装 APK，界面立即显示新版本：

```
[来源: 外部目录(adb push) | 插件: mock-1.0 (amplitude=1.0)]
[来源: 外部目录(adb push) | 插件: mock-1.0 (amplitude=2.5)]   ← 热更后
```

---

## 五、离线可用性（已验证，车上无需外网）

```powershell
.\gradlew.bat --offline clean :app:assembleDebug   # 应 BUILD SUCCESSFUL
```

> **SDK 定位**：`app` 走 `local.properties`；`core`/`plugin` 编译期需要 `android.jar`，
> 定位顺序为 `ANDROID_HOME` → `ANDROID_SDK_ROOT` → 本仓库 `local.properties` 的 `sdk.dir`。
> 本机没设 `ANDROID_HOME`，只靠 `local.properties` 也能离线构建（无需手动 export）。

| 组件 | 位置 |
|---|---|
| JDK 21 | `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot` |
| Android SDK | platform 36 + **37**、build-tools 36/37、platform-tools |
| Gradle 9.8.1 | `C:\Users\lushu\tools\gradle-9.8.1` + wrapper 缓存（不会再下载） |
| AGP 9.4.1 / Kotlin 2.4.21 / 依赖 | `~\.gradle\caches` |
| REA + JADX 0.7.1 + Apktool 2.12.1 | `C:\Users\lushu\tools\rea-android` |

预缓存的额外库见 `C:\Users\lushu\tools\depcache`（androidx / material / coroutines，含实测通过的版本号）。
**Compose 未预缓存**。

---

## 六、上车侦察

```powershell
cd recon
powershell -ExecutionPolicy Bypass -File .\adbrecon.ps1 -Connect <IP:端口> -TurnSignal -PullApks
```

产出在 `recon\recon-out\<时间戳>\`，先看 `SUMMARY.md`。
重点是 **`17-broadcasts.txt`**（广播历史）和 **`11-car_service.txt`**（AAOS 属性）。
详细步骤 / 判定树 / 排错见 **`recon\PLAYBOOK.md`**。

---

## 七、踩过的坑（省得重复踩）

1. **AGP 9 内置 Kotlin**：Android 模块**不能**再应用 `org.jetbrains.kotlin.android`，否则直接构建失败。纯 JVM 模块（core/plugin）仍用 `org.jetbrains.kotlin.jvm`。
2. **Gradle wrapper 会重新下载**：改过 `distributionUrl` 后会重新拉 150MB。已把发行包填进 `~\.gradle\wrapper\dists`（含 `.ok` 标记），不会再下。
3. **PowerShell 脚本要 UTF-8 BOM**：无 BOM 时 5.1 按 ANSI 解析中文，会把字符串截断导致语法错误。
4. **执行策略**：默认禁止跑 .ps1（见上）。
5. `Display.Mode` 没有 `width/height`，要用 `physicalWidth/physicalHeight`。
6. 无线 ADB：`adb mdns services` 看真实端口——**配对端口 ≠ 连接端口**。
7. **`core`/`plugin` 也要 Android SDK**：这两块编译期需要 `android.jar`，定位顺序为
   `ANDROID_HOME` → `ANDROID_SDK_ROOT` → 本仓库 `local.properties` 的 `sdk.dir`；
   只设 `local.properties` 即可离线构建。三处都缺会报「找不到 Android SDK」。

---

## 八、下一步

1. **车连上** → `adbrecon.ps1` 侦察 → 判定信号来源（广播 / AIDL / AAOS CarPropertyManager）
2. 按结果改 **plugin**（不用动壳、不用重装），`build-plugin-dex.ps1 -Push` 验证
3. 数据通了再逐步做多屏与转向联动

---

## 九、实车侦察结论（已在 Q07 上跑通）

### 车机身份

| 项 | 值 |
|---|---|
| 系统 | Android 11 (API 30)，`user` build |
| 平台 | SiEngine **SE1000**（`SiEngine/se1000_car/se1000:11/RD2A.211001.002/27`）|
| 车辆架构 | **AAOS**：`car_service`、厂商车辆属性 HAL `com.ca.car.proxy.hardware.property.ICarProperty` |
| SELinux / 调试 | **Permissive**；`ro.debuggable=1` → `adb root` 可用（但**产品不需要 root**）|
| 屏幕 | port 0 = 中控 2560×1600；port 2 = 副驾 1920×1080（两块真屏）；port 3 (800×480) / port 10 (243×200) 全黑（疑似 HUD / 预留口）|

> 注：`screencap -d` 用的是**物理口编号**，不是 `dumpsys display` 的逻辑 ID。

### 车辆属性（CarPropertyManager，VHAL 共暴露 82 个）

| 功能 | 属性 | ID | 所需权限 | 保护级别 | 普通 App |
|---|---|---|---|---|---|
| **实时功率** | `EV_BATTERY_INSTANTANEOUS_CHARGE_RATE` | `0x1160030C` | `CAR_ENERGY` | **dangerous** | ✅ |
| 电量 / 续航 / 油量 | `EV_BATTERY_LEVEL` / `RANGE_REMAINING` / `FUEL_LEVEL` | `0x11600309 / 0x11600308 / 0x11600307` | `CAR_ENERGY` | dangerous | ✅ |
| 车速 | `PERF_VEHICLE_SPEED` | `0x11600207` | `CAR_SPEED` | dangerous | ✅ |
| 挡位 / 点火 / 手刹 | `GEAR_SELECTION` / `IGNITION_STATE` / `PARKING_BRAKE_ON` | `0x11400400 / 0x11400409 / 0x11200402` | `CAR_POWERTRAIN` | **normal** | ✅ |
| **发动机转速** | `ENGINE_RPM` | `0x11600305` | `CAR_ENGINE_DETAILED` | signature\|privileged | ❌ |
| **转向灯** | `TURN_SIGNAL_STATE` | `0x11400408` | `CAR_EXTERIOR_LIGHTS` | signature\|privileged | ❌ |

- `ICarProperty.getReadPermission(id)` 可查任意属性所需权限（探针见下）。
- 厂商的车辆数据服务（`hmiservice` / `sdkservice` / `SoaBridgeTransit`）全部 `sharedUserId="android.uid.system"`，**不对外开放**；三方接入靠 SDA 开发者平台（`open.sda.changan.com.cn`，其"虚拟车模块"为仿真数据，需审核）。

### 安装限制（重要）

`adb install` / `pm install` / 分段安装 / 冒充市场安装器 **全部失败**：

```
INSTALL_FAILED_INVALID_APK: CAKJ failed to verify APK result
```

- 日志 tag `Leosin-APK`，来自 **system_server**；字符串 `CAKJ` 位于 `/system/framework/services.jar`
- 即厂商在**框架层**加了安装校验钩子 —— 不是可 `pm disable` 的 App，也没有对应设置项
- 手动放 `/data/app` 也不行：PMS 开机扫描会把 `packages.xml` 里没有的目录当孤儿删除

**结论：装壳必须走官方 / 内测分发渠道；壳装好后插件照常热更。**

### 单位待标定

`EV_BATTERY_LEVEL` / `RANGE_REMAINING` / `FUEL_LEVEL` 厂商未提供 min/max（null），
原始值（驻车实测 150000 / 50000 / 15000）需实车对比标定。
系数集中在 `plugin/src/main/kotlin/com/lushu/plugin/DefaultVehiclePlugin.kt` 的 `companion object`，
界面底部会显示原始值，便于一眼对照修正。

---

## 十、实时数据实测结论（全部实车验证）

### 数据通道对照

| 通道 | 权限 | 实时？ | 结论 |
|---|---|---|---|
| **厂商 HAL** `com.ca.car.proxy.CaCarPropertyManager` | **零权限** | ✅ **订阅后实时** | **主力通道** |
| AOSP `android.car.CarPropertyManager` | 需 `android.car.permission.*`（部分特权级）| — | 弃用 |
| 厂商 SOA（`SoaBridgeTransit`）| signature 级权限，客户端锁在系统应用里 | ✅ | 三方拿不到 |
| logcat 里的 SOA 通知 | 只读 | ✅ | **转向灯只能走这里** |

### 两个必踩的坑

1. **`getProperty` 返回的是陈旧缓存，不是实时值。**
   实测：车机电量已 49%，`getProperty` 仍返回 `150000`（=50%），10 秒 6 次读数一字不变。
   **实时值必须 `registerServiceListener(propId, rate, listener)` 订阅。**
2. **订阅回调经 Handler 投递 —— 宿主进程必须跑 `Looper.loop()`。**
   否则事件全部积压在消息队列里，表现为"订阅成功、却一个事件都收不到"。
   （`app_process` 里最容易漏这一步。）

### 转向灯（不在 CarProperty 体系里）

走厂商 SOA，只能从 logcat 读通知：
```
NTF propid:0x415245 name:Basc_ExtrLiInfoTips.Ntf_TurnLiInfo  Value:0 1
                                                                    ↑左 ↑右
```
值会随闪烁在 `0 0` / `0 1` 之间交替，故用 **2.5 秒保持窗口**消除闪烁后再显示。

### 标定值（实测）

| 项 | 原始值 | 换算 |
|---|---|---|
| 电量 | 150000 | ÷3000 → 50% |
| 续航 | 50000 | ÷1000 → 50 km |
| 油量 | 15000 | ÷1000 → 15% |
| 功率 | `EV_BATTERY_INSTANTANEOUS_CHARGE_RATE` | ×0.001 → kW |

### 不安装版悬浮窗（临时方案）

因 CAKJ 封死 adb 安装、渠道又要数日审核，另做了一条**不安装**的路：

```powershell
# 构建 + 推送（可选）
powershell -ExecutionPolicy Bypass -File .\tools\build-overlay.ps1 -Push -Serial <IP:端口>
# 启动 / 停止（需要 adb root）
powershell -ExecutionPolicy Bypass -File .\tools\run-overlay.ps1 -Serial <IP:端口>
powershell -ExecutionPolicy Bypass -File .\tools\run-overlay.ps1 -Serial <IP:端口> -Stop
```

- 源码：`tools/overlay/Main.java`；以 root 用 `app_process` 拉起 `TYPE_APPLICATION_OVERLAY` 悬浮窗
- **不安装、不改 `/system`**；车机重启后需重新执行一次
- 显示：功率 / 转速 / 车速 / 电量 / 续航 / 挡位 / 转向灯

