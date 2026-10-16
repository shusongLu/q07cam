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

systemui 的两个 cleaner 都排除了。剩下最可能的是车机的
**「睡眠呵护」/「超级省电模式」** —— 车机设置里存在这些文案（见 `recon/autostart-wake.md` 引用）：

```
睡眠呵护 / 添加睡眠呵护应用
超级省电模式 / 开启了超级省电，应用关闭
添加到白名单
```

注意「**开启了超级省电，应用关闭**」—— 这条路**很可能无视前台服务**，直接关应用 ✗。

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
