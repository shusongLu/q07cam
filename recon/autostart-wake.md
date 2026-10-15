# 解锁亮屏自启（闭锁关屏被杀后自动回来）

## 问题

车机在**闭锁关屏后会清掉后台进程**（厂商电源策略，`am get-standby-bucket` 显示 ACTIVE、
`dumpsys deviceidle whitelist` 里也有，仍然被杀）。而 **Android 8+ 的隐式广播
（`SCREEN_ON` / `USER_PRESENT` / `USER_UNLOCKED`）不会投递给"没在运行"的应用**的清单接收器，
所以单靠 `BroadcastReceiver` **接不住"解锁亮屏"** ✗ ——
表现为：锁车解锁后悬浮窗不再出现，必须手点图标。

## 解法：常驻「无障碍服务」

新增 `Q07A11yService extends AccessibilityService`（`res/xml/q07_accessibility.xml` 配置）。

- 无障碍服务是**系统绑定**的：进程被杀，系统会主动重新绑定 → 我们的进程被拉回来 ✓
- 绑定/任何窗口事件（解锁回到桌面）→ 回调 → 拉起 `OverlayService` ✓
- 配置里 **`canRetrieveWindowContent="false"`** —— 不读窗口内容，只做唤醒
- 事件类型只订阅 `TYPE_WINDOW_STATE_CHANGED | TYPE_WINDOWS_CHANGED | TYPE_WINDOW_CONTENT_CHANGED`

```xml
<service android:name=".Q07A11yService"
         android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter><action android:name="android.accessibilityservice.AccessibilityService"/></intent-filter>
    <meta-data android:name="android.accessibilityservice" android:resource="@xml/q07_accessibility"/>
</service>
```

## 启用方式

**方式 A：系统设置**（用户做一次，永久生效）
设置 → 无障碍 → 「Q07 实时数据」 → 开启

**方式 B：adb 直接写（保留原有服务，只追加）**

```bash
# 先读现有的
adb shell settings get secure enabled_accessibility_services
# 追加（注意用冒号分隔，别覆盖别人的）
adb shell settings put secure enabled_accessibility_services \
  "<原有>::de.dsa.ford.otaflash/com.lushu.q07car.Q07A11yService"
adb shell settings put secure accessibility_enabled 1
# 校验
adb shell dumpsys accessibility | grep -A2 "Enabled services"
```

校验成功的标志（`dumpsys accessibility`）：

```
Bound services:{..., Service[label=Q07 实时数据, eventTypes=[TYPE_WINDOW_STATE_CHANGED, ...]]}
Enabled services:{{...}, {de.dsa.ford.otaflash/com.lushu.q07car.Q07A11yService}, ...}
```

## 附带改动

- `BootReceiver` 增加 `SCREEN_ON` / `SCREEN_OFF` / `USER_PRESENT` / `USER_UNLOCKED` /
  `DREAMING_STOPPED`（应用还活着时能收到，作为补充）
- `BootReceiver` 里加 `if (OverlayService.running) return;` —— 亮屏类广播很频繁，避免重复拉
- 新建 `res/values/strings.xml`（`app_name` / 无障碍描述）

## 注意

- **`stopped=true` 的应用仍然收不到任何广播**，无障碍服务也会停 —— 
  所以千万别对目标应用执行 `adb shell am force-stop`（见 `install-bypass.md` 附录）。
- 无障碍属于敏感权限，界面上会显示在"无障碍"列表里；本服务不读取窗口内容。
