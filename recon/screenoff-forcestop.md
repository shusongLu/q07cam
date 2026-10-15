# 闭锁关屏 → 应用被系统 force-stop（"解锁不自启"的真正根因）

## 结论（2026-10-15 实车定位）

**不是自启动的问题，也不是「应用管家」的问题 —— 是车机自己在关屏时把第三方应用
`force-stop` 了。**

被 force-stop 后，Android 平台层面**彻底断了复活途径**：

| 手段 | 被 force-stop 后 |
|---|---|
| BOOT_COMPLETED / 任何隐式广播 | ✗ 不投递（stopped 包被跳过）|
| 清单接收器 SCREEN_ON / USER_PRESENT | ✗ |
| `START_STICKY` 服务重建 | ✗ |
| AlarmManager / JobScheduler | ✗ |
| **无障碍服务** | ✗ **系统会把它从启用列表里摘掉** |

**所以"解锁亮屏自动回来"用应用侧代码是做不到的** —— 必须让车机别 force-stop 我们。

## 现场证据

```
22:58:41.063  com.incall.scenepower: setBackLightEnable ... enable:2   ← 关屏
22:58:41.242  Killing 10493:de.dsa.ford.otaflash (adj 0): stop de.dsa.ford.otaflash due to from pid 1444
              1444 = com.android.systemui
22:58:42.023  Force stopping android appid=1000 user=0: from pid 1444
```

之后 `dumpsys package` → `stopped=true`；`dumpsys accessibility` → 我们的
`Q07A11yService` 从 **Enabled services** 消失，出现在 **Crashed services** 里：

```
Enabled services:{ speechassistant, sdalauncher, dvr }     ← 没有我们了
Crashed services:{{de.dsa.ford.otaflash/com.lushu.q07car.Q07A11yService}}
```

> 判据：**只有被 force-stop 的应用，系统才会把它的无障碍服务摘出启用列表。**

## 已排除的

- `am get-standby-bucket` = **10 (ACTIVE)** —— 不是被待机降级
- `dumpsys deviceidle whitelist` —— 已加白，仍被杀
- 「应用管家」—— 它自己也一样会被杀（`has died: prcp IMPF`），不是它干的

## 车机侧的开关（下一步）

系统组件：`com.incall.scenepower`（`/system/app/IncallScenePower`，SYSTEM+PERSISTENT），
白名单机制在 `com.incall.scenepower.constant.WhiteListPath` /
`SYSTEM_WHITE_LIST_JSON_PATH` / `SYSTEM_WHITE_LIST_XML_PATH`（指向 `/system/etc/packageName.json|xml`，
**该文件当前不存在**，且 `/system` 只读 → 应用侧改不了）。

车机设置（`com.incall.apps.vehiclesetting`）里存在这些文案：

```
睡眠呵护 / 添加睡眠呵护应用
睡眠呵护应用：设置摄像头权限 / 读取睡眠呵护权限状态 / 授权已过期
超级省电模式 / 开启了超级省电，应用关闭
添加到白名单
```

→ **在车机设置里找到「睡眠呵护」（或「省电」相关），把「Q07 实时数据」加进去**
（并授予其摄像头权限 —— 它正好和我们侧摄像头场景对应）。

## 代码里保留了什么

`Q07A11yService`（无障碍服务）**保留**：它在"应用没被 force-stop"的场景下依然有效
（例如以后上架商店版本、或车机策略放宽时）。但在当前这台车机上会被系统摘掉，别指望它。

`BootReceiver` 里新增的 `SCREEN_ON / USER_PRESENT / USER_UNLOCKED / DREAMING_STOPPED`
同理保留（应用活着时有用）。
