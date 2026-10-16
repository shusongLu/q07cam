# 闭锁关屏 → 被杀 的完整判定链（2026-10-17 逆 systemui）

## 现场证据（实车，用户物理闭锁 + 解锁）

```
02:09:36.483  ActivityTaskManager: Force removing ActivityRecord{...otaflash/MainActivity...}: app died, no saved state
02:09:56.228  ActivityManager: Start proc 8236:...otaflash for pre-top-activity      ← 用户手动点开（不是自动唤醒）
02:09:36.820  am_kill: [0,2577,com.shusong.appmanagf:acc,250, stop ... due to from pid 1450]
02:09:51.584  am_kill: [0,7468,com.incall.apps.weather,925, stop ... due to from pid 1450]
```

- **`from pid 1450` = com.android.systemui**（日志 tag 里就写着 `systemUI..._MemoryCleanerManager(1450)`）
- 连"天气"和用户自己的"应用管理"都被停 → **不是按包名名单停特定应用**
- **解锁后我们没有自动回来** ✗（是用户手动点开的）—— 问题确认

## 凶手一：`MemoryCleanerManager`（**不是它**）

```java
init():  mPackageNameArray = resources.getStringArray(R.array.ca_memory_clean_list);   // SystemUI 内资源数组

doClean():
  任务为空 → removeTask
  是主屏/副屏前台 → skip: mainScreen/coScreen Activity is top
  else if (空闲≥5s && **在 ca_memory_clean_list 里**) → removeTask + 记入 mRemovedActivitySet
  进程 importance>200 && ((在名单里 && 不在 safe) || 任务被移除) → forceStopPackage
```

→ **只有名单内的应用会被杀**。实测查过 SystemUI 的 `resources.arsc`：**我们的包名不在里面** ✓，
而且日志确实打了 `skip: mainScreen Activity is top de.dsa.ford.otaflash` ✓ → **它没杀我们**。

（附带：SystemUI 里那份名单还有清晰的拼写错误条目，如 `com.inall.apps.wisdomid`、`com.incall.apps.micrworld`，
可以据此确认那串就是名单本身。）

## 凶手二：`CAMemoryCleanerManager`（**也不是它**）

```java
doClean(Context):
  任务：mainScreen/coScreen 前台 → skip；在 DEFAULT_NO_CLEAN_UP_WHITE_LIST → skip:_WHITE_LIST；否则 removeTask
  进程：if (所有包都不在白名单) {
          for (pkg) if (**!isAppSystem(pkg) && importance > 200**) {
              CALog.d(TAG, "It will be killed, package name : " + pkg + ...);
              activityManager.forceStopPackage(pkg);          // ★
          }
        }
```

**`importance > 200` 是被杀的必要条件**。Android importance：**100=前台 / 125=前台服务 / 200=可见**；
230=可感知 / 300=服务 / 400=缓存。

→ 我们有前台服务：实测 `dumpsys activity services` 显示
`OverlayService: startForegroundCount=1, isForeground=true` → importance ≈ **125 ≤ 200** → **豁免** ✓
日志里也**没有** `It will be killed, package name : de.dsa.ford.otaflash` ✓ → **它也没杀我们**。

## 所以：还有第三个机制（未定）

systemui 的两个 cleaner 都排除了。第三个机制的特征：
- 调用方是 **systemui**（`am_kill ... due to from pid 1450`）
- **连无关应用都停**（天气、用户自己的应用管理）→ 不是"按特定名单停"，更像"**停所有非系统应用**"
- 且**连前台服务也没保住我们**（`CAMemoryCleanerManager` 只杀 importance>200 的，前台服务=125 本应豁免）

scenepower 侧的特征：
- 它管理的是**深度睡眠/关机/一键睡眠**：`DEEP_SLEEP_ENTRY/EXIT`、`SLEEP_IMMEDIATELY`、
  `SET_ONE_KEY_TO_SLEEP`、`EVENT_POWER_OFF_ENABLE`、`SOC_POWER_OFF_SLEEP_SIGN`
- 它有 **`tryKillProcess()`** 和 `killProcess` / `removeTask:`
- 它的白名单三处来源**全只读**：
  | 来源 | 状态 |
  |---|---|
  | `/system/etc/packageName.json` | **不存在**（/system 只读）|
  | `/system/etc/packageName.xml` | **不存在** |
  | APK 内 assets | **没有**（APK 只有 META-INF/manifest/dex/arsc）|
  | `WhiteListPath.whitePackages`（硬编码 String[]）| 代码里改不了 |

⚠️ 若那两处 system 文件不存在导致 `whitePackages` **为空**，那 scenepower 侧就是"**没有白名单 → 全杀**"，
与"天气和用户的 App 都被杀"吻合。

## ★ 唯一有操作空间的线索：换个包名

`recon/install-bypass.md` 记录 CAKJ 的安装许可名单只有 5 个名额、3 个已被系统应用占用：

```
com.changan.acscenter           ← 未安装（空位）★
com.incall.apps.intelligenthmi  ← 已装
com.incall.apps.softmanager     ← 已装
com.incall.submasterservice     ← 已装
de.dsa.ford.otaflash            ← 我们正在用（另一个空位）
```

而 **`com.changan.acscenter` 这个名字也出现在 scenepower 的字符串里**（`pm path` 实测未安装）。

→ **把 applicationId 改成 `com.changan.acscenter`**：
- CAKJ 安装校验 ✓（在许可名单且是空位）
- 若它确实在 scenepower/systemui 的豁免名单里 → **锁车不再被杀** ✓
- 可逆（改回去即可）

**这是一个便宜的、可逆的实测**，值得先试。若无效，则说明这台车是"**闭锁时停掉所有非系统应用**"的既定策略，
应用侧无解（只能靠厂商白名单 / OTA / 商店版）。

## ✅ 结论：换包名 `com.changan.acscenter` 生效（2026-10-17 实车 A/B 验证）

同车、同一晚、同样的闭锁+解锁测试，只改包名：

| | 包名 | 闭锁后结果 |
|---|---|---|
| **A** | `de.dsa.ford.otaflash` | **被杀** ✗：`stopped=true`、无障碍服务被摘、进程 pid 变了、用户手动点开才回来 |
| **B** | `com.changan.acscenter` | **存活** ✅：**同一个 pid**（11573）、`stopped=false`、无障碍还在、`am_kill` 里**没有我们** |

同时期别的应用照样被杀（`com.shusong.appmanagf`、`com.netease.cloudmusic.iot`、`android.process.acore` …），
而我们没事 —— 说明**车机的闭锁清理确实有一份"包名豁免名单"**，
`com.changan.acscenter` 在名单里，`de.dsa.ford.otaflash` 不在。

**这正是之前反复找不到答案的原因**：名单是**按包名**判定的，而名单本身在只读分区/硬编码里，
所以"改代码让它别被杀"是死路 —— **但"换一个已经在名单里的包名"是通的** ✅

### 为什么这个包名能两全

| 条件 | 说明 |
|---|---|
| CAKJ 安装校验 | `com.changan.acscenter` 是许可名单的**空位** → `adb install` 照常放行 ✅ |
| 车机豁免名单 | 该名字出现在 scenepower 的字符串表里，且实测确实豁免 ✅ |
| 不冲突 | 实测 `pm path com.changan.acscenter` 为空（该厂商应用未预装）✅ |

### 换名后的注意

- 包名变了 → **窗口布局等偏好是新装的默认值**，需要在编辑模式里重新调一次位置/大小
- 无障碍与悬浮窗权限要重新授一次（`settings put secure enabled_accessibility_services` + `appops set ... SYSTEM_ALERT_WINDOW allow`）
- 回滚：把 `build.gradle.kts` 的 `applicationId` 改回 `de.dsa.ford.otaflash` 重装即可

### 副作用：第三方工具会把我们当"系统应用"

包名变成 `com.changan.*` 后，按包名前缀分类的工具会**把我们归到厂商/系统应用**里 ✗ ——
实测「应用管家」（`com.shusong.appmanagf`）的"用户应用"列表里**看不到我们**（旧名 `de.` 开头时可见）。
要在里面操作，需要打开它的「显示系统应用 / 全部应用」之类的开关。

**但 `WRITE_SECURE_SETTINGS` 不需要应用管家** —— 它是 `signature|privileged|**development**` 级别，
带 `development` 就可以用 adb 直接授：

```bash
adb shell pm grant com.changan.acscenter android.permission.WRITE_SECURE_SETTINGS
# 验证
adb shell dumpsys package com.changan.acscenter | grep WRITE_SECURE_SETTINGS   # granted=true
```

（重装后需要重新授一次。）

## ⚠️ 一条硬规矩（本次踩坑）

**禁止用 adb 发 `keyevent 223`(SLEEP)** —— 车机关屏时会连 Wi-Fi/ADB 一起断，
之后就再也发不出 WAKEUP，只能整车重启。要测关屏必须**物理操作**并由人唤醒。

## 下一步

1. **在车机设置里找「睡眠呵护」/「超级省电模式」**：
   - 看它是不是开着；开着的话，能不能把「Q07 实时数据」加进去（文案里就有「添加到白名单」）
   - 关屏类测试由用户物理操作
2. 若设置里没有可加的入口 → 逆 **`com.incall.apps.vehiclesetting`**（睡眠呵护的 UI 在这），
   看它把名单写到哪（Settings 键 / scenepower 的 API / 某个文件）
3. 顺带：`com.incall.scenepower` 的 `WhiteListPath` 有 `SYSTEM_WHITE_LIST_JSON_PATH`/`_XML_PATH`
   → `/system/etc/packageName.json|xml`（**当前都不存在**，且 /system 只读）
