# q07battery — 长安启源 Q07 车机实时功率显示

在 Q07 原厂车机上显示**动力电池实时功率**、**发动机转速**，并逐步做多屏（副驾/HUD）与转向灯联动。

---

## 一、车机硬约束（先看这个）

| 约束 | 影响 |
|---|---|
| 可开**无线 ADB** | 能 logcat / dumpsys / 拉系统 App（侦察全靠它） |
| ADB **禁止安装 APK** | 本机不能 `adb install`；只能走官方渠道分发 |
| 有开发者账号 → **官网分发** | 装包慢 → 所以做了**热更**，规避反复上传 |
| **不能 root** | 只能按"普通三方 App"能力设计 |

可行性（侦察确认前）：

| 功能 | 可行性 | 说明 |
|---|---|---|
| 中控显示功率 / 转速 | ⚠️ 取决于数据源 | UI 已就绪，卡在"能不能读到信号" |
| 副驾屏显示**自己的**界面 | ⚠️ 可能 | 标准 `Presentation` |
| 悬浮窗（浮在普通界面之上） | ✅ 已实现 | 只需授予"显示在其他应用上层" |
| 悬浮到 **HUD** | ❌ | HUD 多为私有投屏 |
| 副驾屏启动**别的** App | ❌ | 需要系统权限 |
| 打转向灯显示**侧摄像头** | ❌ | AVM 画面走私有服务 |

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
