package q07overlay;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * Q07 实时功率悬浮窗（不安装版）。
 *
 * 车辆信号：厂商 HAL —— com.ca.car.proxy.hardware.property.CaCarPropertyManager
 *           （车机 BOOTCLASSPATH 自带，无需任何权限、无需 root 权限声明）
 * 转向灯  ：AOSP CarProperty 拿不到（转向灯走厂商 SOA 通道），退而求其次从 logcat
 *           读 SOA 通知 Basc_ExtrLiInfoTips.Ntf_TurnLiInfo，并用"保持窗口"消除闪烁。
 *
 * 由 root 通过 app_process 启动（见 tools/run-overlay.ps1），不安装、不改 /system。
 */
public class Main {

    private static TextView tvPower;
    private static TextView tvRpm;
    private static TextView tvInfo1;
    private static TextView tvInfo2;
    private static TextView tvRaw;

    // ---------- 属性 ID（与厂商 defs.VehicleProperty 一致）----------
    private static final int ID_ENGINE_RPM = 0x11600305;
    private static final int ID_EV_CHARGE_RATE = 0x1160030C;
    private static final int ID_EV_BATTERY_LEVEL = 0x11600309;
    private static final int ID_RANGE_REMAINING = 0x11600308;
    private static final int ID_FUEL_LEVEL = 0x11600307;
    private static final int ID_PERF_SPEED = 0x11600207;
    private static final int ID_GEAR_SELECTION = 0x11400400;

    // ---------- 标定系数 ----------
    /** 实测：原始值 150000 ↔ 车上 50%，故 1% = 3000。 */
    private static final float SOC_DIVISOR = 3000f;
    /** RANGE_REMAINING 原始单位按 m（50000 → 50 km）。 */
    private static final float RANGE_DIVISOR = 1000f;
    private static final float FUEL_DIVISOR = 1000f;
    /** EV_BATTERY_INSTANTANEOUS_CHARGE_RATE 原始单位 W。 */
    private static final float POWER_W_TO_KW = 0.001f;

    /** 转向灯"保持"窗口：闪烁间隔约 720ms，2.5s 内没再收到就认为已关。 */
    private static final long TURN_HOLD_MS = 2500L;

    private static volatile long lastLeftTurnAt = 0L;
    private static volatile long lastRightTurnAt = 0L;

    public static void main(String[] args) {
        try {
            Looper.prepareMainLooper();
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("systemMain").invoke(null);
            Context base = (Context) at.getMethod("getSystemContext").invoke(thread);
            // system 的 Context 没有主题，直接建 TextView 会在初始化默认字体时崩，套个主题
            Context ctx = new android.view.ContextThemeWrapper(base, android.R.style.Theme_DeviceDefault);

            final Handler main = new Handler(Looper.getMainLooper());

            tvPower = label(ctx, 64f, 0xFF8AE234);
            tvRpm = label(ctx, 34f, 0xFFFCE94F);
            tvInfo1 = label(ctx, 24f, 0xFFB9C2CC);
            tvInfo2 = label(ctx, 24f, 0xFFB9C2CC);
            tvRaw = label(ctx, 14f, 0x66FFFFFF);

            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(0xCC0A0E12);
            root.setPadding(48, 32, 48, 32);
            root.addView(tvPower);
            root.addView(tvRpm);
            root.addView(tvInfo1);
            root.addView(tvInfo2);
            root.addView(tvRaw);

            WindowManager wm = (WindowManager) base.getSystemService(Context.WINDOW_SERVICE);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = 60;
            lp.y = 60;
            wm.addView(root, lp);
            System.out.println("[ovl] window added");

            Thread poller = new Thread(new Runnable() {
                @Override public void run() { pollVehicle(main); }
            }, "q07-poll");
            poller.setDaemon(true);
            poller.start();

            Thread turn = new Thread(new Runnable() {
                @Override public void run() { watchTurnSignal(); }
            }, "q07-turn");
            turn.setDaemon(true);
            turn.start();

            System.out.println("[ovl] entering loop");
            Looper.loop();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static TextView label(Context ctx, float sp, int color) {
        TextView tv = new TextView(ctx);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setSingleLine(true);
        return tv;
    }

    // ---------- 车辆信号轮询 ----------

    private static void pollVehicle(Handler main) {
        Object mgr;
        Method getProperty;
        Method getValue;
        try {
            Class<?> mgrCls = Class.forName("com.ca.car.proxy.hardware.property.CaCarPropertyManager");
            mgr = mgrCls.getMethod("getInstance").invoke(null);
            getProperty = mgrCls.getMethod("getProperty", int.class, int.class);
            getValue = Class.forName("com.ca.car.proxy.hardware.CarPropertyValue").getMethod("getValue");
            System.out.println("[ovl] vendor HAL ready");
        } catch (Throwable t) {
            System.out.println("[ovl] vendor HAL init failed: " + t);
            main.post(new Runnable() {
                @Override public void run() { tvInfo1.setText("车辆服务连接失败"); }
            });
            return;
        }

        while (true) {
            try {
                final float powerW = read(getProperty, getValue, mgr, ID_EV_CHARGE_RATE);
                final float level = read(getProperty, getValue, mgr, ID_EV_BATTERY_LEVEL);
                final float range = read(getProperty, getValue, mgr, ID_RANGE_REMAINING);
                final float fuel = read(getProperty, getValue, mgr, ID_FUEL_LEVEL);
                final float speed = read(getProperty, getValue, mgr, ID_PERF_SPEED);
                final int gear = (int) read(getProperty, getValue, mgr, ID_GEAR_SELECTION);
                final float rpm = read(getProperty, getValue, mgr, ID_ENGINE_RPM);
                final String turn = turnText();

                main.post(new Runnable() {
                    @Override
                    public void run() {
                        tvPower.setText(String.format("%.1f kW", powerW * POWER_W_TO_KW));
                        tvRpm.setText(rpm > 0f
                                ? String.format("%d rpm", (int) rpm)
                                : "EV（增程器未启动）");
                        tvInfo1.setText(String.format("电量 %.0f%%    续航 %.0f km    油 %.0f%%",
                                level / SOC_DIVISOR, range / RANGE_DIVISOR, fuel / FUEL_DIVISOR));
                        tvInfo2.setText(String.format("车速 %.0f km/h    挡位 %s    转向 %s",
                                speed, gearName(gear), turn));
                        tvRaw.setText(String.format("lvl=%.0f rng=%.0f fuel=%.0f pwr=%.0f spd=%.0f rpm=%.0f gear=%d turn=%s",
                                level, range, fuel, powerW, speed, rpm, gear, turn));
                    }
                });
            } catch (Throwable t) {
                System.out.println("[ovl] poll error: " + t);
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private static float read(Method getProperty, Method getValue, Object mgr, int id) {
        try {
            Object v = getProperty.invoke(mgr, id, 0);
            if (v == null) return 0f;
            Object value = getValue.invoke(v);
            return (value instanceof Number) ? ((Number) value).floatValue() : 0f;
        } catch (Throwable t) {
            return 0f;
        }
    }

    // ---------- 转向灯（厂商 SOA 通知，从 logcat 读） ----------

    private static void watchTurnSignal() {
        Process p = null;
        BufferedReader r = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"logcat", "-v", "brief"});
            r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            System.out.println("[ovl] logcat watcher started");
            String line;
            while ((line = r.readLine()) != null) {
                int nameAt = line.indexOf("Ntf_TurnLiInfo");
                if (nameAt < 0) continue;
                int valAt = line.indexOf("Value:");
                if (valAt < 0) continue;
                String[] parts = line.substring(valAt + 6).trim().split("\\s+");
                if (parts.length < 2) continue;
                boolean left = "1".equals(parts[0]);
                boolean right = "1".equals(parts[1]);
                long now = System.currentTimeMillis();
                if (left) lastLeftTurnAt = now;
                if (right) lastRightTurnAt = now;
            }
        } catch (Throwable t) {
            System.out.println("[ovl] logcat watcher failed: " + t);
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) {}
            try { if (p != null) p.destroy(); } catch (Throwable ignored) {}
        }
    }

    private static String turnText() {
        long now = System.currentTimeMillis();
        boolean left = now - lastLeftTurnAt < TURN_HOLD_MS;
        boolean right = now - lastRightTurnAt < TURN_HOLD_MS;
        if (left && right) return "双闪";
        if (left) return "左";
        if (right) return "右";
        return "关";
    }

    private static String gearName(int g) {
        switch (g) {
            case 1: return "N";
            case 2: return "R";
            case 4: return "P";
            case 8: return "D";
            case 16: return "1";
            case 32: return "2";
            case 64: return "3";
            case 128: return "4";
            case 256: return "5";
            default: return String.valueOf(g);
        }
    }
}
