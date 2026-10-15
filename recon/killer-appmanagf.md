# 「应用管家」是"杀我们应用"的凶手（2026-10-15 实车定位）

## 结论

**开机自启本身完全正常。** 应用是被一个第三方"管家"应用 **force-stop** 掉的：

> `com.shusong.appmanagf`（「应用管家」v1.8.3，装在 `/data/app`，用户自行安装）

它把我们 kill 掉后，包被置为 `stopped=true` → 之后**收不到任何广播**（包括 BOOT_COMPLETED）
→ 锁车/解锁后就再也回不来。

## 现场证据（logcat）

```
22:43:31.315  am_proc_start  [.., de.dsa.ford.otaflash, broadcast, {...BootReceiver}]   ← 开机广播到达，服务起来了 ✓
22:43:32.156  Foreground service started: .../OverlayService                            ✓
22:44:44      （我们的 q07-turn 线程还在正常读 SOA）                                     ✓
22:46:14.059  notification_cancel_all [com.shusong.appmanagf]                           ← 管家开始动作
22:46:14.649  am_proc_bound  com.shusong.appmanagf:acc
22:46:14.800  WIN DEATH: Window{... de.dsa.ford.otaflash} ×3                            ← 我们 0.7 秒后死亡 ✗
22:46:15.317  am_proc_start  com.shusong.appmanagf {rikka.shizuku.ShizukuProvider}      ← Shizuku（shell 级权限）
```

之后：`dumpsys package de.dsa.ford.otaflash` → **`stopped=true`**（force-stop 的典型后果）。

日志里**没有** FATAL / ANR / SIGSEGV / Killing —— 不是我们崩的，是被外部强停的 ✗。

## 「应用管家」为什么权力这么大

从它自己的 dex 里抠出的能力（类名 + 文案）：

| 能力 | 证据 |
|---|---|
| **Shizuku（shell 权限）** | `rikka.shizuku.ShizukuProvider` |
| **强制停止应用** | `forceStop` / `am force-stop` / `ActivityManager` |
| 无障碍服务常驻 | `Accessibility_boot_task`、`AccessibilityKeepAliveDelay` |
| 通知监听（可清通知） | `settings secure` 里 `enabled_notification_listeners=com.shusong.appmanagf/...` |
| **自启动管理** | `AutoTaskStartAutoStartTask`、`BootStartTask_*`、`BootTaskService` |
| **白名单** | `ActivityCleanMemoryWhitelist`、`ActivityOuShangWhitelist`、「应用白名单管理」 |
| **保活** | `getKeepalive`/`setKeepalive`、「保活措施：不显示在最近任务 / 创建通知栏服务 / 忽略电池优化」 |
| 内存清理 | 「内存清理」「放心清理」「按HOME键后…」 |
| 待机/冻结 | 「强制应用进入待机」「解除应用待机状态」「冻结应用」 |

## 怎么解

**在白名单里加上我们的应用**（必须在它界面里操作，`/data/data/com.shusong.appmanagf` 读不到）：

1. 打开「应用管家」→ **自启动管理** → 勾选 **Q07 实时数据**（`de.dsa.ford.otaflash`）
2. 打开「应用管家」→ **应用白名单管理** → 加入 **Q07 实时数据**
3. 若它有 **保活措施**，一并打开（忽略电池优化 / 创建通知栏服务）

或者：不再使用「应用管家」（停用/卸载）。

## 教训

**排查"开机自启失效"时，不能只看 `stopped` 标志本身**——
`stopped=true` 既可能是自己 `am force-stop` 留下的，
也可能是**第三方管家/清理类应用强停**留下的。
要看 logcat 的时间线：`notification_cancel_all` + 进程死亡时间对齐，就能抓到真凶。
