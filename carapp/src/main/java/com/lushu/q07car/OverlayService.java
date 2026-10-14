package com.lushu.q07car;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.TextView;

import com.incall.camedia.CaMediaPlayer;

import java.util.Locale;

/**
 * 悬浮窗前台服务：车辆数据面板 + 转向灯联动的侧摄像头。
 *
 * 全部走厂商 SOA（id 见 recon/soa-signal-map.md），
 * 不需要 root、不需要 logcat 权限、不需要任何 android.car 权限。
 */
public class OverlayService extends Service {

    private static final String TAG = "Q07Overlay";
    private static final String CH_ID = "q07";

    // ---------------- SOA 信号 id ----------------
    private static final int ID_VEH_SPD      = 0x00418006; // 车速            0.1 km/h
    private static final int ID_GEAR_POSN    = 0x00418037; // 挡位 [0]=挡位 [1]=无效
    private static final int ID_POWER_SHIFT  = 0x00408004; // 电源挡位
    private static final int ID_PT_LAMP      = 0x00419788; // 仪表文字显示      [25]=电量%
    private static final int ID_BCU_OPER_PRM = 0x00419307; // BCU               [12]=高压电压 V
    private static final int ID_PDU_OPER_PRM = 0x00419309; // PDU               [8]=母线电压 [0]=电流
    private static final int ID_RESI_MILG    = 0x00412055; // 剩余里程 km
    private static final int ID_TOTAL_MILG   = 0x00501000; // 总里程
    private static final int ID_REEV_OPER    = 0x00413072; // 增程器运行参数
    private static final int ID_TURN_LI      = 0x00415244; // 转向灯 [0]=左 [1]=右

    /** PDU[0] 量纲：×0.01 A（可用 /data/local/tmp/q07scale.txt 覆盖）。 */
    private static volatile float PDU_CURRENT_SCALE = 0.01f;
    private static final float SPEED_SCALE = 0.1f;

    /** 转向灯保持窗口（去闪烁）。 */
    private static final long TURN_HOLD_MS = 2500L;

    // 面板
    private static final int PANEL_W = 1040, PANEL_H = 560, PANEL_X = 470, PANEL_Y = 170;
    // 摄像头
    private static final int CAM_W = 720, CAM_H = 405, CAM_X = 470, CAM_Y = 745;
    private static final String RTSP_LEFT = "rtsp://172.16.6.26:8888/lt";
    private static final String RTSP_RIGHT = "rtsp://172.16.6.26:8888/rt";
    /** 换边时的间隔：等旧 RTSP 会话释放（厂商 AVM 也是这么做的）。 */
    private static final long SWITCH_DELAY_MS = 450L;

    public static volatile boolean running = false;

    private WindowManager wm;
    private TextView tvMain;
    private SoaClient soa;
    private Handler handler;

    // 数据
    private volatile float kwPdu, pduVolt, pduCur, hvVolt, socPct, rangeKm, totalKm, speed;
    private volatile int gear = -1, gearInvalid = -1, powerShift = -1;
    private volatile int[] reev = new int[7];
    private volatile long lastLogAt = 0;

    /**
     * 当前转向状态：0 关 / 1 左 / 2 右 / 3 双闪。
     * 只有在"同一次读数里左右同时亮"才判定双闪（并要求连续两次，防抖动），
     * 否则以最近一次单独亮起的那一侧为准 —— 这样右->左快速切换能立刻生效。
     */
    private volatile int curTurn = 0;
    private volatile long curTurnAt = 0;
    private volatile int bothStreak = 0;
    /** 收到转向信号的时间，用来量"信号 -> 出画面"的延迟。 */
    private volatile long turnSignalAt = 0;

    // 摄像头
    private SurfaceView camView;
    private CaMediaPlayer camPlayer;
    private Surface camSurface;
    private String camUrl;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundInternal();

        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        handler = new Handler(Looper.getMainLooper());

        loadScale();

        tvMain = new TextView(this);
        tvMain.setTextSize(30f);
        tvMain.setTextColor(0xFFE6EDF3);
        tvMain.setSingleLine(false);
        tvMain.setBackgroundColor(0xE60A0E12);
        tvMain.setPadding(44, 26, 44, 26);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                PANEL_W, PANEL_H,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = PANEL_X;
        lp.y = PANEL_Y;
        try {
            wm.addView(tvMain, lp);
        } catch (Throwable t) {
            Log.e(TAG, "面板窗口失败（多半没有『显示在其他应用上层』权限）: " + t);
        }

        new Thread(new Runnable() {
            @Override public void run() {
                soa = new SoaClient();
                boolean ok = soa.init(OverlayService.this);
                Log.i(TAG, "SOA init = " + ok);
            }
        }, "q07-soa").start();

        // 转向灯单独用高频线程读（只读 1 个属性，~20ms），把发现延迟从 300ms 压到 ~60ms
        new Thread(new Runnable() {
            @Override public void run() {
                while (true) {
                    pollTurn();
                    try { Thread.sleep(60L); } catch (InterruptedException e) { return; }
                }
            }
        }, "q07-turn").start();

        // 界面节拍：只做渲染 + 摄像头窗口切换（不读 SOA，很快）
        handler.post(new Runnable() {
            @Override public void run() {
                render();
                updateCamera();
                handler.postDelayed(this, 100L);
            }
        });

        // 数据节拍：SOA 读取较慢（10 个属性约 200ms），单独放 400ms
        handler.post(new Runnable() {
            @Override public void run() {
                poll();
                handler.postDelayed(this, 400L);
            }
        });
        running = true;
        Log.i(TAG, "service started");
    }

    @Override
    public void onDestroy() {
        running = false;
        hideCam();
        try { if (tvMain != null) wm.removeView(tvMain); } catch (Throwable ignored) {}
        if (handler != null) handler.removeCallbacksAndMessages(null);
        Log.i(TAG, "service stopped");
        super.onDestroy();
    }

    private void startForegroundInternal() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CH_ID) == null) {
            NotificationChannel ch = new NotificationChannel(CH_ID, "Q07 实时数据",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Notification n = new Notification.Builder(this, CH_ID)
                .setContentTitle("Q07 实时数据")
                .setContentText("悬浮窗运行中")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .build();
        startForeground(1, n);
    }

    private void loadScale() {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader("/data/local/tmp/q07scale.txt"));
            String l = r.readLine();
            r.close();
            if (l != null && l.trim().length() > 0) PDU_CURRENT_SCALE = Float.parseFloat(l.trim());
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 数据

    private void poll() {
        if (soa == null) return;
        try {
            int[] a;
            if ((a = soa.read(ID_GEAR_POSN)) != null && a.length >= 2) { gear = a[0]; gearInvalid = a[1]; }
            if ((a = soa.read(ID_VEH_SPD)) != null && a.length >= 1) speed = a[0] * SPEED_SCALE;
            if ((a = soa.read(ID_POWER_SHIFT)) != null && a.length >= 1) powerShift = a[0];
            if ((a = soa.read(ID_RESI_MILG)) != null && a.length >= 1) rangeKm = a[0];
            if ((a = soa.read(ID_TOTAL_MILG)) != null && a.length >= 1) totalKm = a[0];
            if ((a = soa.read(ID_BCU_OPER_PRM)) != null && a.length > 12) hvVolt = a[12];
            if ((a = soa.read(ID_PT_LAMP)) != null && a.length > 25) socPct = a[25];
            if ((a = soa.read(ID_PDU_OPER_PRM)) != null && a.length > 13) {
                pduVolt = a[8] / 10f;
                pduCur = a[0] * PDU_CURRENT_SCALE;
                kwPdu = pduVolt * pduCur / 1000f;
            }
            if ((a = soa.read(ID_REEV_OPER)) != null) {
                for (int i = 0; i < reev.length && i < a.length; i++) reev[i] = a[i];
            }
        } catch (Throwable ignored) {
        }
    }

    /** 只读转向灯，供高频线程调用。 */
    private void pollTurn() {
        if (soa == null) return;
        try {
            int[] a = soa.read(ID_TURN_LI);
            if (a == null || a.length < 2) return;
            long now = System.currentTimeMillis();
            boolean l = a[0] != 0;
            boolean r = a[1] != 0;
            int before = curTurn;
            if (l && r) {
                // 左右同时亮：连读两次才认双闪（切换瞬间可能扫到一次）
                if (++bothStreak >= 2) { curTurn = 3; curTurnAt = now; }
            } else if (l) {
                bothStreak = 0;
                curTurn = 1;
                curTurnAt = now;
            } else if (r) {
                bothStreak = 0;
                curTurn = 2;
                curTurnAt = now;
            }
            // 左右都不亮：不动 curTurn，靠保持窗口维持（转向灯是闪烁的）
            if (curTurn != before && (curTurn == 1 || curTurn == 2)) {
                turnSignalAt = now;
                Log.i(TAG, "[cam] 收到转向信号: " + turnName(curTurn));
            }
        } catch (Throwable ignored) {
        }
    }

    /** 0 关 1 左 2 右 3 双闪（保持窗口消除闪烁） */
    private int turnState() {
        return (System.currentTimeMillis() - curTurnAt < TURN_HOLD_MS) ? curTurn : 0;
    }

    // ---------------------------------------------------------------- 渲染

    private void render() {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        append(sb, String.format(Locale.US, "%.1f kW", kwPdu), 2.6f, 0xFF8AE234, true);
        append(sb, String.format(Locale.US, "\nPDU %.1fV × %.1fA（系数待标定）   ·   高压 %.0fV",
                pduVolt, pduCur, hvVolt), 0.8f, 0xFF9ACD32, false);
        append(sb, String.format(Locale.US, "\n电量 %.0f%%   ·   续航 %.0f km   ·   总里程 %.0f km",
                socPct, rangeKm, totalKm), 1f, 0xFFE6EDF3, false);
        append(sb, String.format(Locale.US, "\n挡位 %s   ·   %.1f km/h   ·   电源 %s   ·   转向 %s",
                gearName(gear, gearInvalid), speed, powerShiftName(powerShift), turnName(turnState())),
                1f, 0xFFE6EDF3, false);
        append(sb, String.format(Locale.US, "\nREEV=%d/%d/%d/%d", reev[0], reev[1], reev[2], reev[6]),
                0.52f, 0x99FFFFFF, false);

        if (tvMain != null) tvMain.setText(sb);

        long now = System.currentTimeMillis();
        if (now - lastLogAt > 10000L) {
            lastLogAt = now;
            Log.i(TAG, String.format(Locale.US,
                    "kw=%.1f pduV=%.1f pduI=%.1f hv=%.0f soc=%.0f range=%.0f odo=%.0f gear=%s spd=%.1f turn=%s",
                    kwPdu, pduVolt, pduCur, hvVolt, socPct, rangeKm, totalKm,
                    gearName(gear, gearInvalid), speed, turnName(turnState())));
        }
    }

    private void append(SpannableStringBuilder sb, String text, float scale, int color, boolean bold) {
        int start = sb.length();
        sb.append(text);
        int end = sb.length();
        if (scale != 1f) sb.setSpan(new RelativeSizeSpan(scale), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) sb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

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

    private static String turnName(int t) {
        switch (t) {
            case 1: return "◀ 左";
            case 2: return "右 ▶";
            case 3: return "双闪";
            default: return "关";
        }
    }

    // ---------------------------------------------------------------- 摄像头

    private void updateCamera() {
        if (wm == null) return;
        int turn = turnState();
        // 双闪（3）不弹侧摄像头 —— 两边都不是"转向意图"
        String want = (turn == 1) ? RTSP_LEFT : (turn == 2) ? RTSP_RIGHT : null;

        if (want == null) {
            if (camView != null) hideCam();
            return;
        }
        if (camView == null) {
            showCam(want);
        } else if (!want.equals(camUrl)) {
            switchStream(want);
        }
    }

    /**
     * 换边：厂商自己的 AVM 在左右切换时也是"先关、等一个间隔、再开"
     * （ViewAssistConstant.SWITCH_COMMON_INTERVAL），否则 RTSP 会话还没释放，
     * 新流接不上就是黑屏。这里照做，并且复用同一个 SurfaceView，不重建窗口。
     */
    private void switchStream(String url) {
        camUrl = url;
        handler.removeCallbacks(startTask);
        stopPlayback();
        Log.i(TAG, "[cam] switch -> " + url);
        handler.postDelayed(startTask, SWITCH_DELAY_MS);
    }

    private final Runnable startTask = new Runnable() {
        @Override public void run() {
            if (camSurface != null && camUrl != null) startPlayback(camUrl);
        }
    };

    private void showCam(String url) {
        try {
            SurfaceView sv = new SurfaceView(this);
            sv.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override public void surfaceCreated(SurfaceHolder h) {
                    camSurface = h.getSurface();
                    // surface 已就绪，立即喂流（首次打开不需要额外等待）
                    handler.removeCallbacks(startTask);
                    startTask.run();
                }
                @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }
                @Override public void surfaceDestroyed(SurfaceHolder h) {
                    handler.removeCallbacks(startTask);
                    stopPlayback();
                    camSurface = null;
                }
            });
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    CAM_W, CAM_H,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.OPAQUE);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = CAM_X;
            lp.y = CAM_Y;
            wm.addView(sv, lp);
            camView = sv;
            camUrl = url;
            Log.i(TAG, "[cam] show " + url);
        } catch (Throwable t) {
            Log.e(TAG, "[cam] showCam 失败: " + t);
        }
    }

    private void hideCam() {
        handler.removeCallbacks(startTask);
        stopPlayback();
        if (camView != null && wm != null) {
            try { wm.removeView(camView); } catch (Throwable ignored) {}
        }
        camView = null;
        camSurface = null;
        if (camUrl != null) Log.i(TAG, "[cam] hide");
        camUrl = null;
    }

    private void startPlayback(String url) {
        if (camPlayer != null || camSurface == null || url == null) return;
        try {
            CaMediaPlayer p = new CaMediaPlayer();
            int r1 = p.setDataSource(url);
            int r2 = p.setSurface(camSurface);
            int r3 = p.play(camSurface);
            camPlayer = p;
            long ms = System.currentTimeMillis() - turnSignalAt;
            Log.i(TAG, "[cam] " + url + " setDataSource=" + r1 + " setSurface=" + r2 + " play=" + r3
                    + "   信号->开始播放 " + ms + "ms");
        } catch (Throwable t) {
            Log.e(TAG, "[cam] startPlayback 失败: " + t);
            camPlayer = null;
        }
    }

    private void stopPlayback() {
        if (camPlayer != null) {
            try { camPlayer.close(); } catch (Throwable ignored) {}
            try { camPlayer.release(); } catch (Throwable ignored) {}
            camPlayer = null;
        }
    }
}
