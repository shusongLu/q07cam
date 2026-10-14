package q07overlay;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.TextView;

import com.incall.camedia.CaMediaPlayer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Q07 实时数据悬浮窗（不安装、不改 /system 版）。
 *
 * 数据来源：厂商 SOA HIDL —— android.hardware.automotive.soabridge.V1_0.ISoaBridgeNative
 *           （车机 /system/app/SoaBridgeTransit 里的 Java 类，dex 挂到 CLASSPATH 即可）
 *
 * id 全部逆向自厂商自己的适配层 com.incall.soabridgeadapter.*ClientEmbedded，
 * 字段下标抄自 com.changan.evs.appservice 的 com.incall.apps.common.manager.SoaBridgeManager。
 * 详见 recon/soa-signal-map.md。
 *
 * 转向灯：SOA 通知走 logcat（Basc_ExtrLiInfoTips.Ntf_TurnLiInfo），用"保持窗口"消抖。
 *
 * 注意：app_process 下窗口尺寸不会随内容重算，所以用固定尺寸 + 单个 TextView
 *      （多子视图时后加的几行不参与绘制）。
 */
public class Main {

    // ---------------------------------------------------------------- 信号 id
    private static final int ID_VEH_SPD      = 0x00418006; // 车速             int[]
    private static final int ID_GEAR_POSN    = 0x00418037; // 挡位 [0]=挡位 [1]=无效
    private static final int ID_POWER_SHIFT  = 0x00408004; // 电源挡位 0OFF 1ACC 2ON 3START
    private static final int ID_PT_LAMP      = 0x00419788; // 仪表文字显示      [25]=SOC 电量%
    private static final int ID_BCU_OPER_PRM = 0x00419307; // BCU 电池控制单元  [12]=高压电压 V
    private static final int ID_PDU_OPER_PRM = 0x00419309; // PDU 高压配电单元  [8][13]=母线电压 [0]=电流
    private static final int ID_RESI_MILG    = 0x00412055; // 剩余里程
    private static final int ID_TOTAL_MILG   = 0x00501000; // 总里程
    private static final int ID_REEV_OPER    = 0x00413072; // 增程器运行参数
    private static final int ID_EGY_CNSE     = 0x00412050; // 整车能耗信息

    /** PDU[0] 的量纲（电流系数）：默认 0.01 A，可用 /data/local/tmp/q07scale.txt 覆盖，免重编。 */
    private static final float PDU_CURRENT_SCALE_DEFAULT = 0.01f;
    private static volatile float PDU_CURRENT_SCALE = PDU_CURRENT_SCALE_DEFAULT;

    /** 车速原始值是 0.1 km/h（文档量程 0~460 km/h → 原始 0~4600）。 */
    private static final float SPEED_SCALE = 0.1f;

    /** 转向灯"保持"窗口：闪烁间隔约 720ms，2.5s 内没再收到就认为已关。 */
    private static final long TURN_HOLD_MS = 2500L;

    // ---------------------------------------------------------------- 侧摄像头（RTSP + 厂商 FFmpeg）
    /** 车机自带的侧视 RTSP 流（H.265）。 */
    private static final String RTSP_LEFT = "rtsp://172.16.6.26:8888/lt";
    private static final String RTSP_RIGHT = "rtsp://172.16.6.26:8888/rt";
    /** 摄像头窗口：贴在文字面板正下方。 */
    private static final int CAM_W = 720;
    private static final int CAM_H = 405;
    private static final int CAM_X = 470;
    private static final int CAM_Y = 745;

    private static TextView tvMain;

    private static volatile float kwPdu, pduVolt, pduCur, hvVolt, socPct, rangeKm, totalKm, speed;
    private static volatile float[] reev = new float[7];
    private static volatile int[] egyCnse = new int[5];
    private static volatile int gear = -1, gearInvalid = -1, powerShift = -1, pduRaw0 = -1, pduRaw22 = -1, pduRaw8 = -1;

    private static volatile long lastLeftTurnAt = 0L, lastRightTurnAt = 0L, lastLogAt = 0L;

    // ---------------------------------------------------------------- 摄像头状态
    private static WindowManager wmRef;
    private static Context ctxRef;
    private static SurfaceView camView;
    private static CaMediaPlayer camPlayer;
    private static Surface camSurface;
    private static String camUrl;
    private static long lastCamLogAt = 0L;

    public static void main(String[] args) {
        try {
            Looper.prepareMainLooper();
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("systemMain").invoke(null);
            Context base = (Context) at.getMethod("getSystemContext").invoke(thread);
            Context ctx = new android.view.ContextThemeWrapper(base, android.R.style.Theme_DeviceDefault);

            tvMain = new TextView(ctx);
            tvMain.setTextSize(30f);
            tvMain.setTextColor(0xFFE6EDF3);
            tvMain.setSingleLine(false);
            tvMain.setBackgroundColor(0xE60A0E12);
            tvMain.setPadding(44, 26, 44, 26);

            WindowManager wm = (WindowManager) base.getSystemService(Context.WINDOW_SERVICE);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    1040, 560,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            // 中控首页左侧是"卡片栏"，放那儿会被自家卡片挡；挪到右边空白区
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = 470;
            lp.y = 170;
            wm.addView(tvMain, lp);
            wmRef = wm;
            ctxRef = ctx;
            System.out.println("[ovl] window added");

            if (!initSoa()) {
                tvMain.setText("SOA 未连接（检查 CLASSPATH 是否含 sdk*.dex / soa*.dex）");
            }
            loadScale();

            final Handler main = new Handler(Looper.getMainLooper());
            main.postDelayed(new Runnable() {
                @Override public void run() {
                    poll();
                    render();
                    updateCamera();
                    main.postDelayed(this, 300L);
                }
            }, 100L);

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

    // ---------------------------------------------------------------- SOA

    /** 允许通过 /data/local/tmp/q07scale.txt（一行浮点数）改电流系数，免重编。 */
    private static void loadScale() {
        try {
            BufferedReader r = new BufferedReader(new java.io.FileReader("/data/local/tmp/q07scale.txt"));
            String l = r.readLine();
            r.close();
            if (l != null && l.trim().length() > 0) {
                PDU_CURRENT_SCALE = Float.parseFloat(l.trim());
                System.out.println("[ovl] scale from file = " + PDU_CURRENT_SCALE);
            }
        } catch (Throwable ignored) {
            System.out.println("[ovl] scale = default " + PDU_CURRENT_SCALE);
        }
    }

    private static Object soaClient;
    private static Method mGetProperty, mGetValue;

    private static boolean initSoa() {
        try {
            Class<?> iface = Class.forName("android.hardware.automotive.soabridge.V1_0.ISoaBridgeNative");
            Object svc = iface.getMethod("getService").invoke(null);
            Class<?> clientCls = Class.forName(
                    "com.incall.soabridge.impl.SoaBridgeServiceManagement.SoaBridgeNativeClient");
            soaClient = clientCls.getConstructor(iface).newInstance(svc);
            mGetProperty = clientCls.getMethod("getProperty", int.class);
            mGetValue = Class.forName("com.incall.soabridge.bean.SoaClientProperty")
                    .getMethod("getPropertyValue");
            System.out.println("[ovl] SOA connected: " + soaClient);
            return true;
        } catch (Throwable t) {
            System.out.println("[ovl] SOA init failed: " + t);
            return false;
        }
    }

    /** 读一个属性并转成 int[]；读不到返回 null。 */
    private static int[] read(int id) {
        try {
            Object prop = mGetProperty.invoke(soaClient, id);
            if (prop == null) return null;
            Object v = mGetValue.invoke(prop);
            if (v == null) return null;
            if (v instanceof int[]) return (int[]) v;
            if (v instanceof Object[]) {
                Object[] a = (Object[]) v;
                int[] r = new int[a.length];
                for (int i = 0; i < a.length; i++) r[i] = a[i] instanceof Number ? ((Number) a[i]).intValue() : 0;
                return r;
            }
            if (v instanceof Number) return new int[]{((Number) v).intValue()};
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void poll() {
        try {
            int[] a;
            if ((a = read(ID_GEAR_POSN)) != null && a.length >= 2) { gear = a[0]; gearInvalid = a[1]; }
            if ((a = read(ID_VEH_SPD)) != null && a.length >= 1) { speed = a[0] * SPEED_SCALE; }
            if ((a = read(ID_POWER_SHIFT)) != null && a.length >= 1) { powerShift = a[0]; }
            if ((a = read(ID_RESI_MILG)) != null && a.length >= 1) { rangeKm = a[0]; }
            if ((a = read(ID_TOTAL_MILG)) != null && a.length >= 1) { totalKm = a[0]; }
            if ((a = read(ID_BCU_OPER_PRM)) != null && a.length > 12) { hvVolt = a[12]; }
            if ((a = read(ID_PT_LAMP)) != null && a.length > 25) { socPct = a[25]; }
            if ((a = read(ID_PDU_OPER_PRM)) != null && a.length > 22) {
                pduRaw0 = a[0]; pduRaw8 = a[8]; pduRaw22 = a[22];
                pduVolt = a[8] / 10f;
                pduCur = a[0] * PDU_CURRENT_SCALE;
                kwPdu = pduVolt > 0 ? pduVolt * pduCur / 1000f : 0f;
            }
            if ((a = read(ID_REEV_OPER)) != null) {
                for (int i = 0; i < reev.length && i < a.length; i++) reev[i] = a[i];
            }
            if ((a = read(ID_EGY_CNSE)) != null) {
                for (int i = 0; i < egyCnse.length && i < a.length; i++) egyCnse[i] = a[i];
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 渲染

    private static void render() {
        final int turn = turnState();  // 0 关 1 左 2 右 3 双闪

        SpannableStringBuilder sb = new SpannableStringBuilder();
        append(sb, String.format(Locale.US, "%.1f kW", kwPdu),
                2.6f, 0xFF8AE234, true);
        append(sb, String.format(Locale.US, "\nPDU %.1fV × %.1fA（系数待标定）   ·   高压 %.0fV",
                pduVolt, pduCur, hvVolt), 0.8f, 0xFF9ACD32, false);
        append(sb, String.format(Locale.US, "\n电量 %.0f%%   ·   续航 %.0f km   ·   总里程 %.0f km",
                socPct, rangeKm, totalKm), 1f, 0xFFE6EDF3, false);
        append(sb, String.format(Locale.US, "\n挡位 %s   ·   %.1f km/h   ·   电源 %s   ·   转向 %s",
                gearName(gear, gearInvalid), speed, powerShiftName(powerShift), turnName(turn)),
                1f, 0xFFE6EDF3, false);
        append(sb, String.format(Locale.US,
                "\nPDU[0]=%d [8]=%d [22]=%d  ·  BCU[12]=%.0fV  ·  PtLamp[25]=%.0f\nREEV=%d/%d/%d/%d  ·  EGY=%d/%d/%d/%d/%d",
                pduRaw0, pduRaw8, pduRaw22, hvVolt, socPct,
                (int) reev[0], (int) reev[1], (int) reev[2], (int) reev[6],
                egyCnse[0], egyCnse[1], egyCnse[2], egyCnse[3], egyCnse[4]), 0.52f, 0x99FFFFFF, false);

        tvMain.setText(sb);

        long now = System.currentTimeMillis();
        if (now - lastLogAt > 2000L) {
            lastLogAt = now;
            System.out.println(String.format(Locale.US,
                    "[data] kw=%.1f pduV=%.1f pduI=%.1f hv=%.0f soc=%.0f range=%.0f odo=%.0f gear=%s spd=%.0f pwr=%s turn=%s reev=%d/%d",
                    kwPdu, pduVolt, pduCur, hvVolt, socPct, rangeKm, totalKm,
                    gearName(gear, gearInvalid), speed, powerShiftName(powerShift), turnName(turn),
                    (int) reev[0], (int) reev[1]));
        }
    }

    private static void append(SpannableStringBuilder sb, String text, float scale, int color, boolean bold) {
        int start = sb.length();
        sb.append(text);
        int end = sb.length();
        if (scale != 1f) sb.setSpan(new RelativeSizeSpan(scale), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) sb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** 厂商常量：GEAR_P=1, GEAR_R=2, GEAR_N=3, GEAR_D=4。 */
    private static String gearName(int g, int invalid) {
        if (invalid != 0) return "?";
        switch (g) {
            case 1: return "P";
            case 2: return "R";
            case 3: return "N";
            case 4: return "D";
            default: return String.valueOf(g);
        }
    }

    private static String powerShiftName(int p) {
        switch (p) {
            case 0: return "OFF";
            case 1: return "ACC";
            case 2: return "ON";
            case 3: return "START";
            default: return "--";
        }
    }

    // ---------------------------------------------------------------- 侧摄像头

    /**
     * 转向灯亮 -> 弹出对应侧摄像头画面；灭 -> 关掉。
     * 用 2.5s 保持窗口避免闪烁引起反复开关。
     */
    private static void updateCamera() {
        int turn = turnState();
        String want = (turn == 1) ? RTSP_LEFT : (turn == 2 || turn == 3) ? RTSP_RIGHT : null;

        if (want == null) {
            if (camUrl != null || camView != null) hideCam();
            return;
        }
        if (want.equals(camUrl) && camView != null) return;
        hideCam();
        showCam(want);
    }

    private static void showCam(String url) {
        try {
            SurfaceView sv = new SurfaceView(ctxRef);
            sv.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override public void surfaceCreated(SurfaceHolder h) {
                    camSurface = h.getSurface();
                    startPlayback();
                }
                @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {
                }
                @Override public void surfaceDestroyed(SurfaceHolder h) {
                    stopPlayback();
                    camSurface = null;
                }
            });

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    CAM_W, CAM_H,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.OPAQUE);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = CAM_X;
            lp.y = CAM_Y;
            wmRef.addView(sv, lp);
            camView = sv;
            camUrl = url;
            System.out.println("[cam] show " + url);
        } catch (Throwable t) {
            System.out.println("[cam] showCam failed: " + t);
        }
    }

    private static void hideCam() {
        stopPlayback();
        if (camView != null && wmRef != null) {
            try { wmRef.removeView(camView); } catch (Throwable ignored) {}
        }
        camView = null;
        camSurface = null;
        if (camUrl != null) System.out.println("[cam] hide");
        camUrl = null;
    }

    private static void startPlayback() {
        if (camPlayer != null || camSurface == null || camUrl == null) return;
        try {
            CaMediaPlayer p = new CaMediaPlayer();
            int r1 = p.setDataSource(camUrl);
            int r2 = p.setSurface(camSurface);
            int r3 = p.play(camSurface);
            camPlayer = p;
            System.out.println("[cam] " + camUrl + " setDataSource=" + r1 + " setSurface=" + r2 + " play=" + r3);
        } catch (Throwable t) {
            System.out.println("[cam] startPlayback failed: " + t);
            camPlayer = null;
        }
    }

    private static void stopPlayback() {
        if (camPlayer != null) {
            try { camPlayer.close(); } catch (Throwable ignored) {}
            try { camPlayer.release(); } catch (Throwable ignored) {}
            camPlayer = null;
        }
    }

    // ---------------------------------------------------------------- 转向灯（logcat）

    private static void watchTurnSignal() {
        Process p = null;
        BufferedReader r = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"logcat", "-v", "brief"});
            r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            System.out.println("[ovl] logcat watcher started");
            String line;
            while ((line = r.readLine()) != null) {
                if (line.indexOf("Ntf_TurnLiInfo") < 0) continue;
                int valAt = line.indexOf("Value:");
                if (valAt < 0) continue;
                String[] parts = line.substring(valAt + 6).trim().split("\\s+");
                if (parts.length < 2) continue;
                long now = System.currentTimeMillis();
                if ("1".equals(parts[0])) lastLeftTurnAt = now;
                if ("1".equals(parts[1])) lastRightTurnAt = now;
            }
        } catch (Throwable t) {
            System.out.println("[ovl] logcat watcher failed: " + t);
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) {}
            try { if (p != null) p.destroy(); } catch (Throwable ignored) {}
        }
    }

    private static int turnState() {
        long now = System.currentTimeMillis();
        boolean left = now - lastLeftTurnAt < TURN_HOLD_MS;
        boolean right = now - lastRightTurnAt < TURN_HOLD_MS;
        if (left && right) return 3;
        if (left) return 1;
        if (right) return 2;
        return 0;
    }

    private static String turnName(int t) {
        switch (t) {
            case 1: return "◀ 左";
            case 2: return "右 ▶";
            case 3: return "双闪";
            default: return "关";
        }
    }
}
