# 深蓝桌面 App（com.deepalhome.launcher v26.0930）逆向要点

样本：`C:\Users\lushu\Desktop\v26.0930.apk.1`（14.4 MB，5991 类，targetSdk 34）

## 一、它是 Launcher —— 这决定了它的"唤醒"

清单里有 **`android.intent.category.HOME`** ✓ → 它是**桌面替代**。

这带来两个天然优势：
1. **系统不会杀 home 应用** ✓
2. **解锁/上电后，系统本来就把 home 拉到前台** ✓ → 它**不需要"唤醒自己"** ✓✓

所以"上车自己出来"对它是免费的 —— 对普通 App 不是。

## 二、它的 HUD 实现：**就是一块 Display**

### 关键类

```
com.deepalhome.launcher.hud.HudDisplayHelper
    findProjectionTriggerDisplay(Display[])      ← 从显示列表里挑
    matchesResolution(Display, Function2)
    resolveHudDisplay(Context, Function1)
    resolveHudDisplayInternal(Context, boolean)
    safeName(Display)

com.deepalhome.launcher.hud.HudDisplayResolution
    HUD_RESOLUTIONS : List<Pair<Int,Int>>        ← 硬编码的 HUD 分辨率清单
    isHud(int w, int h)                          ← 按分辨率认 HUD（长宽可互换）

com.deepalhome.launcher.hud.HudDisplayProbe      ← 探测（activeProbe/timeout/pendingCallbacks）
com.deepalhome.launcher.hud.HudDisplayReconciler
com.deepalhome.launcher.hud.HudFrame / HudFrameBridge
com.deepalhome.launcher.hudmapui.*               ← HUD 上真正画的东西（lane bar / TMC / 红绿灯图标）
```

`isHud` 的实现：

```java
public static boolean isHud(int w, int h) {
    for (Pair p : HUD_RESOLUTIONS) {
        int a = p.first, b = p.second;
        if ((w == a && h == b) || (w == b && h == a)) return true;
    }
    return false;
}
```

→ **靠分辨率认 HUD**，不是靠名字/ID ✓。

### 还有一条 VirtualDisplay 路径

字符串里有：

```
VirtualDisplay created, displayId=
hudDisplayId= / triggerDisplayId= / clusterDisplayId
setLaunchDisplayId
HUD/HUD     HUD/HUDVideo     HUD/HUDBrightness
HUD/HUDTheme  HUD/HUDHeight  HUD/HUDSteerpan  HUD/HUDDisplayMode
```

- 它会在找不到现成 HUD 时**创建 VirtualDisplay**（清单申请了 `ADD_TRUSTED_DISPLAY`，`signature` 级）
- `HUD/*` 这一组是厂商侧的 HUD 设置键（亮度/主题/高度/转向视角/视频）

### 对我们 App 意味着什么

- **如果 Q07 暴露的是一块真实 Display**（HUD 或仪表），那么**不需要什么特殊权限**：
  用 `WindowManager.createDisplayContext(display)` + `TYPE_APPLICATION_OVERLAY` 就能往上画 ✓
  （SYSTEM_ALERT_WINDOW 是全局的，对所有 display 生效）
- 如果只能走 **VirtualDisplay + 镜像到 HUD**，那需要 `ADD_TRUSTED_DISPLAY`（签名级）✗ —— 普通 App 拿不到

**上车待验证**：`dumpsys display` 里有没有一块"小/扁"的 display（HUD 典型是 480×240、800×480 之类），
以及它的名字/分辨率 —— 有的话就能直接试。

## 三、它的"常驻/自愈"：无障碍守护 + 写设置

```
service com.deepalhome.launcher.accessibility.AccessibilityDaemonService
    getEnabledServicesString()
    canonicalizeServiceId(String)
    doDaemon(String)                              ← 主逻辑
    restoreServicesSequentially$restoreNext(...)  ← 逐个写回去
    startForegroundSafely()                       ← 前台服务
    字段: contentObserver / installedServiceIdMap / screenOnReceiver / screenOnCheckRunnable

receiver com.deepalhome.launcher.accessibility.AccessibilityBootReceiver
service  com.deepalhome.launcher.accessibility.AutoNaviAccessibilityService
```

`doDaemon` 的逻辑（反编译整理）：

```java
doDaemon(String enabledServices) {
    if (!config.accessibilityDaemonEnabled) { stopSelf(); return; }
    List<String> wanted = config.daemonAccessibilityServices;   // 用户配置要守护的服务
    if (wanted.isEmpty()) { stopSelf(); return; }

    Set<String> current = split(enabledServices, ":").map(trim).filter(nonEmpty)
                            .map(canonicalizeServiceId).toSet();

    List<String> missing = wanted.stream()
        .map(canonicalizeServiceId)                 // 归一化成 "包名/类名"
        .filter(c -> installedServiceIdMap.containsKey(c))
        .filter(c -> !current.contains(c))          // 现在不在启用列表里
        .toList();

    if (!missing.isEmpty()) {
        restoreServicesSequentially$restoreNext(0, ..., missing, ref(enabledServices));
    }
}
```

→ **只要系统把它从 `enabled_accessibility_services` 里摘掉，它就立刻用 `WRITE_SECURE_SETTINGS` 写回去** ✓✓
触发点：`ContentObserver`（设置变化）＋ `screenOnCheckRunnable`（亮屏复查）＋ 开机接收器。

## 四、可直接借给我们的两件事

| 借用点 | 价值 | 难度 |
|---|---|---|
| **无障碍自愈守护** | 系统摘掉我们就写回去（我们有 `WRITE_SECURE_SETTINGS` ✓）→ 解锁唤醒变成铁打的 | 小 |
| **HUD = Display** | 若 Q07 有真实 HUD display，可直接把面板/画面画到 HUD | 待上车确认 |

**不借**：当 Launcher（我们的定位不是替换桌面）。

## 五、上车后的检查清单

1. `dumpsys display | grep -E 'DisplayDeviceInfo|mDisplayId'` → 列出所有 display 及分辨率
   - 找"小/扁"的那块 → 大概率是 HUD；`clusterDisplayId` 可能是仪表
2. `dumpsys car_service | grep -i hud` 或 `getprop | grep -i hud`
3. 若找到 HUD display：写个小实验（`createDisplayContext` + overlay）看能不能出画
4. 顺带验证 Deepal 的 `HUD/HUDVideo` 键是不是 `Settings.Global` 里的（`settings list global | grep HUD`）
