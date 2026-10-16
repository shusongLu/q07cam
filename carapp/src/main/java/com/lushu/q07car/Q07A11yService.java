package com.lushu.q07car;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * 常驻「无障碍服务」—— 只用来**保活 + 解锁亮屏时唤醒**。
 *
 * 背景：车机在闭锁关屏后会清掉后台进程，而 Android 8+ 的隐式广播
 * （SCREEN_ON / USER_PRESENT / USER_UNLOCKED…）**不会投递给"没在运行"的应用**
 * 的清单接收器，所以单靠 BroadcastReceiver 接不住"解锁亮屏"。
 *
 * 无障碍服务是**系统绑定**的：进程被杀后系统会主动把它拉回来，
 * 并且任何窗口变化（解锁后回到桌面）都会回调 onAccessibilityEvent。
 * 国产车机上这是最稳的常驻点（「应用管家」也是这么活的）。
 *
 * 本服务**不读取窗口内容**（canRetrieveWindowContent=false），只做唤醒。
 */
public class Q07A11yService extends AccessibilityService {

    private static final String TAG = "Q07Car";
    /** 3 秒内不重复踢，避免事件风暴。 */
    private static final long KICK_GAP_MS = 3000L;
    private long lastKick = 0L;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "无障碍服务已连接");
        kick("connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) { return; }
        kick(AccessibilityEvent.eventTypeToString(event.getEventType()));
    }

    @Override
    public void onInterrupt() {
    }

    /** 车机闭锁=关背光（不走 Android 电源状态）。闭锁期间别把服务拉起来，否则会"起来→关掉"反复循环。 */
    private static final String BL_POWER_PATH = "/sys/class/backlight/panel0/bl_power";

    private boolean backlightOff() {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(BL_POWER_PATH));
            String l = r.readLine();
            r.close();
            return l != null && Integer.parseInt(l.trim()) != 0;
        } catch (Throwable t) {
            return false;      // 读不到就当亮着
        }
    }

    /** 保证悬浮窗服务在跑。 */
    private void kick(String why) {
        if (OverlayService.running) { return; }
        if (backlightOff()) { return; }        // ★ 闭锁中：不拉起来（起来也会立刻关掉）
        long now = System.currentTimeMillis();
        if (now - lastKick < KICK_GAP_MS) { return; }
        lastKick = now;
        try {
            Intent i = new Intent(this, OverlayService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
            Log.i(TAG, "无障碍(" + why + "): 已拉起 OverlayService");
        } catch (Throwable t) {
            Log.e(TAG, "无障碍拉起失败: " + t);
        }
    }
}
