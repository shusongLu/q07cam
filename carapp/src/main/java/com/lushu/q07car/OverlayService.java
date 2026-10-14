package com.lushu.q07car;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

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

    // 面板 / 摄像头窗口的位置大小改为从 Prefs 读取（可由 App 界面编辑）
    private int panelX, panelY, panelW, panelH;
    private int camX, camY, camW, camH;
    private boolean editMode;

    /** 编辑模式下的抓手尺寸 */
    private static final int HANDLE_DP = 44;
    private static final int BORDER_DP = 3;

    private static final String RTSP_LEFT = "rtsp://172.16.6.26:8888/lt";
    private static final String RTSP_RIGHT = "rtsp://172.16.6.26:8888/rt";

    public static volatile boolean running = false;
    /** 服务活着才继续跑轮询线程（否则 stopService/startService 会泄漏线程）。 */
    private volatile boolean alive = false;

    private WindowManager wm;
    private TextView tvMain;
    private SoaClient soa;
    private Handler handler;

    // 数据
    private volatile float kwPdu, pduVolt, pduCur, hvVolt, socPct, rangeKm, totalKm, speed;
    private volatile int gear = -1, gearInvalid = -1, powerShift = -1;
    private volatile int[] reev = new int[7];
    private volatile long lastLogAt = 0;
    /** 上次渲染的文本，没变就不 setText（减少重绘） */
    private volatile String lastText = "";

    /**
     * 给 App 页面用的实时数据快照，每项是 "名称|值"。
     * 由数据轮询线程写入，主线程读 —— 都是 volatile 数组引用，安全。
     */
    public static volatile String[] liveRows = new String[0];
    /** 数据是否有效（SOA 是否连上） */
    public static volatile boolean liveOk = false;

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

    // ---------------- 摄像头（每侧一个窗口） ----------------
    // 预连：开机就把某侧（或双侧）的 RTSP 拉起来常驻，窗口放在屏幕外，
    // 打灯时只把窗口"移进来"，省掉 RTSP 握手 → 基本零延迟。
    // 代价：一直占解码器/带宽；若 RTSP 服务只允许单会话，会挡住另一侧。
    public static final String PRE_NONE = "none";
    public static final String PRE_LEFT = "left";
    public static final String PRE_RIGHT = "right";
    public static final String PRE_BOTH = "both";

    private static final int SIDE_LEFT = 0;
    private static final int SIDE_RIGHT = 1;
    private static final int OFFSCREEN_X = -3000;

    private static class CamSide {
        View view;              // 容器（含 SurfaceView，编辑模式下带边框和缩放抓手）
        Q07RtspPlayer player;
        Surface surface;
        String url;
        boolean onScreen;
        boolean started;
    }

    private final CamSide[] sides = new CamSide[]{new CamSide(), new CamSide()};

    /** 预连掩码：bit0=左, bit1=右。 */
    private volatile int preMask = 0;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundInternal();

        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        handler = new Handler(Looper.getMainLooper());

        loadScale();
        loadPrefs();

        tvMain = new TextView(this);
        tvMain.setTextColor(0xFFE6EDF3);
        tvMain.setSingleLine(false);
        tvMain.setBackgroundColor(0xE60A0E12);
        tvMain.setPadding(44, 26, 44, 26);
        // 字号按面板高度算（不用 autoSize：每帧自动测量会把主线程拖死）
        tvMain.setTextSize(Math.max(10f, Math.min(40f, panelH / 19f)));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                panelW, panelH,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | (editMode ? 0 : WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = panelX;
        lp.y = panelY;
        if (editMode) {
            tvMain.setBackground(borderDrawable(0xCC00E5FF));
            tvMain.setOnTouchListener(new DragTouch(0));
        }
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

        // 预连配置
        setupPreconnect();

        // 转向灯单独用高频线程读（只读 1 个属性，~20ms），把发现延迟从 300ms 压到 ~60ms
        alive = true;
        new Thread(new Runnable() {
            @Override public void run() {
                while (alive) {
                    pollTurn();
                    try { Thread.sleep(60L); } catch (InterruptedException e) { return; }
                }
            }
        }, "q07-turn").start();

        // 数据轮询也放后台：一次读 10 个属性约 200ms，跑在主线程会堵住界面节拍
        new Thread(new Runnable() {
            @Override public void run() {
                while (alive) {
                    poll();
                    try { Thread.sleep(400L); } catch (InterruptedException e) { return; }
                }
            }
        }, "q07-poll").start();

        // 界面节拍：只做摄像头窗口切换（很轻），100ms 保证打灯响应
        handler.post(new Runnable() {
            @Override public void run() {
                updateCamera();
                handler.postDelayed(this, 100L);
            }
        });

        // 数据面板：400ms 一次，且文本没变就不 setText（避免无谓重绘）
        handler.post(new Runnable() {
            @Override public void run() {
                render();
                handler.postDelayed(this, 400L);
            }
        });
        running = true;
        Log.i(TAG, "service started");
    }

    @Override
    public void onDestroy() {
        running = false;
        alive = false;
        for (int i = 0; i < 2; i++) {
            stopPlayer(i);
            CamSide s = sides[i];
            if (s.view != null && wm != null) {
                try { wm.removeView(s.view); } catch (Throwable ignored) {}
                s.view = null;
            }
        }
        try { if (tvMain != null) wm.removeView(tvMain); } catch (Throwable ignored) {}
        if (handler != null) handler.removeCallbacksAndMessages(null);
        Log.i(TAG, "service stopped");
        super.onDestroy();
    }

    private void loadPrefs() {
        String p = Prefs.getStr(this, Prefs.KEY_PRECONNECT, PRE_NONE);
        preMask = 0;
        if (PRE_LEFT.equals(p) || PRE_BOTH.equals(p)) preMask |= 1;
        if (PRE_RIGHT.equals(p) || PRE_BOTH.equals(p)) preMask |= 2;

        panelX = Prefs.getInt(this, Prefs.KEY_PANEL_X, Prefs.DEF_PANEL_X);
        panelY = Prefs.getInt(this, Prefs.KEY_PANEL_Y, Prefs.DEF_PANEL_Y);
        panelW = Prefs.getInt(this, Prefs.KEY_PANEL_W, Prefs.DEF_PANEL_W);
        panelH = Prefs.getInt(this, Prefs.KEY_PANEL_H, Prefs.DEF_PANEL_H);
        camX = Prefs.getInt(this, Prefs.KEY_CAM_X, Prefs.DEF_CAM_X);
        camY = Prefs.getInt(this, Prefs.KEY_CAM_Y, Prefs.DEF_CAM_Y);
        camW = Prefs.getInt(this, Prefs.KEY_CAM_W, Prefs.DEF_CAM_W);
        camH = Prefs.getInt(this, Prefs.KEY_CAM_H, Prefs.DEF_CAM_H);
        editMode = Prefs.getBool(this, Prefs.KEY_EDIT, false);

        Log.i(TAG, "[cam] 预连=" + p + "(mask=" + preMask + ")  面板=" + panelX + "," + panelY
                + " " + panelW + "x" + panelH + "  摄像头=" + camX + "," + camY + " " + camW + "x" + camH
                + "  编辑模式=" + editMode);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 编辑模式下的高亮边框。 */
    private GradientDrawable borderDrawable(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x00000000);
        d.setStroke(dp(BORDER_DP), color);
        d.setCornerRadius(dp(6));
        return d;
    }

    private void savePanelLayout() {
        Prefs.putInt(this, Prefs.KEY_PANEL_X, panelX);
        Prefs.putInt(this, Prefs.KEY_PANEL_Y, panelY);
        Prefs.putInt(this, Prefs.KEY_PANEL_W, panelW);
        Prefs.putInt(this, Prefs.KEY_PANEL_H, panelH);
    }

    private void saveCamLayout() {
        Prefs.putInt(this, Prefs.KEY_CAM_X, camX);
        Prefs.putInt(this, Prefs.KEY_CAM_Y, camY);
        Prefs.putInt(this, Prefs.KEY_CAM_W, camW);
        Prefs.putInt(this, Prefs.KEY_CAM_H, camH);
    }

    /** 拖动：整块窗口跟着手指走。 */
    private class DragTouch implements View.OnTouchListener {
        private final int which;   // 0=面板 1=摄像头
        private float downX, downY;
        private int startX, startY;
        private boolean moved;

        DragTouch(int which) { this.which = which; }

        @Override public boolean onTouch(View v, MotionEvent e) {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) v.getLayoutParams();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    lp.x = startX + (int) (e.getRawX() - downX);
                    lp.y = startY + (int) (e.getRawY() - downY);
                    try { wm.updateViewLayout(v, lp); } catch (Throwable ignored) {}
                    moved = true;
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (moved) {
                        if (which == 0) { panelX = lp.x; panelY = lp.y; savePanelLayout(); }
                        else { camX = lp.x; camY = lp.y; saveCamLayout(); }
                    }
                    return true;
                default:
                    return false;
            }
        }
    }

    /** 右下角缩放：拖边框改大小。 */
    private class ResizeTouch implements View.OnTouchListener {
        private float downX, downY;
        private int startW, startH;

        @Override public boolean onTouch(View v, MotionEvent e) {
            View box = (View) v.getParent();
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) box.getLayoutParams();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startW = lp.width;
                    startH = lp.height;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int w = Math.max(dp(160), startW + (int) (e.getRawX() - downX));
                    int h = Math.max(dp(90), startH + (int) (e.getRawY() - downY));
                    lp.width = w;
                    lp.height = h;
                    try { wm.updateViewLayout(box, lp); } catch (Throwable ignored) {}
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    camW = lp.width;
                    camH = lp.height;
                    saveCamLayout();
                    Log.i(TAG, "[cam] 尺寸 -> " + camW + "x" + camH);
                    return true;
                default:
                    return false;
            }
        }
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
            liveOk = true;
            publishLive();
        } catch (Throwable ignored) {
        }
    }

    /** 把当前数据打包给 App 页面显示。 */
    private void publishLive() {
        liveRows = new String[]{
                "实时功率|" + String.format(Locale.US, "%.1f kW", kwPdu),
                "高压电压|" + String.format(Locale.US, "%.0f V", hvVolt),
                "母线电压|" + String.format(Locale.US, "%.1f V", pduVolt),
                "电池电流|" + String.format(Locale.US, "%.1f A", pduCur),
                "电量|" + String.format(Locale.US, "%.0f %%", socPct),
                "剩余里程|" + String.format(Locale.US, "%.0f km", rangeKm),
                "总里程|" + String.format(Locale.US, "%.0f km", totalKm),
                "挡位|" + gearName(gear, gearInvalid),
                "车速|" + String.format(Locale.US, "%.1f km/h", speed),
                "电源|" + powerShiftName(powerShift),
                "转向灯|" + turnName(turnState()),
                "增程器|" + reev[0] + " / " + reev[1] + " / " + reev[2] + " / " + reev[6],
        };
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

        if (tvMain != null && !sb.toString().equals(lastText)) {
            lastText = sb.toString();
            tvMain.setText(sb);
        }

        long now = System.currentTimeMillis();
        if (now - lastLogAt > 10000L) {
            lastLogAt = now;
            Log.i(TAG, String.format(Locale.US,
                    "kw=%.1f pduV=%.1f pduI=%.1f hv=%.0f soc=%.0f range=%.0f odo=%.0f gear=%s spd=%.1f turn=%s",
                    kwPdu, pduVolt, pduCur, hvVolt, socPct, rangeKm, totalKm,
                    gearName(gear, gearInvalid), speed, turnName(turnState())));
            for (int i = 0; i < 2; i++) {
                CamSide cs = sides[i];
                if (cs.player != null) {
                    Log.i(TAG, String.format(Locale.US, "[cam] %s frames=%d 最后一包 %dms 前 窗上=%s 预连=%s",
                            i == SIDE_LEFT ? "左" : "右", cs.player.frames(),
                            now - cs.player.lastRecv(), cs.onScreen, (preMask & (1 << i)) != 0));
                }
            }
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

    private static String urlOf(int side) {
        return side == SIDE_LEFT ? RTSP_LEFT : RTSP_RIGHT;
    }

    private void updateCamera() {
        if (wm == null) return;
        int turn = turnState();
        int want = (turn == 1) ? SIDE_LEFT : (turn == 2) ? SIDE_RIGHT : -1;

        if (want < 0) {
            for (int i = 0; i < 2; i++) {
                if ((preMask & (1 << i)) != 0) moveSide(i, false);
                else stopSide(i);
            }
            return;
        }

        int other = 1 - want;

        // 关键顺序：先把要显示的那一侧 PLAY 起来（服务端会把唯一的发送口转给它），
        // 再去收另一侧。反过来做会因为 TEARDOWN/重连的时序问题两边都收不到流。
        if ((preMask & (1 << want)) != 0 || sides[want].view != null) {
            ensureSide(want, (preMask & (1 << want)) != 0);
            if (sides[want].player == null) startSide(want);
            else resumeSide(want);
            moveSide(want, true);
        } else {
            sides[want].url = urlOf(want);
            ensureSide(want, false);
            startSide(want);           // surface 就绪后会自行起播
            curWant = want;
        }

        // 再收掉另一侧（窗口层面；流已经被抢过来了）
        if ((preMask & (1 << other)) != 0) moveSide(other, false);
        else stopSide(other);
    }

    /** 当前希望显示的那一侧（-1 表示无）。 */
    private volatile int curWant = -1;

    /** 已经有 player 的一侧：re-PLAY 抢回发送口，不重连。 */
    private void resumeSide(final int idx) {
        final CamSide s = sides[idx];
        if (s.player == null || s.onScreen) return;
        camExec.execute(new Runnable() {
            @Override public void run() {
                if (s.player.resume()) {
                    s.started = true;
                    return;
                }
                // 会话可能已失效 -> 重连
                try { s.player.stop(); } catch (Throwable ignored) {}
                s.player = null;
                s.started = false;
                s.url = urlOf(idx);
                startSide(idx);
            }
        });
    }

    private final Runnable startTask = new Runnable() {
        @Override public void run() {
            int turn = turnState();
            int want = (turn == 1) ? SIDE_LEFT : (turn == 2) ? SIDE_RIGHT : -1;
            if (want < 0) return;
            ensureSide(want, (preMask & (1 << want)) != 0);
            startSide(want);
            moveSide(want, true);
        }
    };

    /** 建窗口（预连的放屏幕外，非预连的直接放可见位置）。 */
    private void ensureSide(final int idx, boolean pre) {
        final CamSide s = sides[idx];
        if (s.view != null) return;
        try {
            SurfaceView sv = new SurfaceView(this);
            sv.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override public void surfaceCreated(SurfaceHolder h) {
                    sides[idx].surface = h.getSurface();
                    startSide(idx);
                }
                @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }
                @Override public void surfaceDestroyed(SurfaceHolder h) {
                    stopPlayer(idx);
                    sides[idx].surface = null;
                }
            });

            // 容器：编辑模式下显示边框 + 右下角缩放抓手
            FrameLayout box = new FrameLayout(this);
            box.addView(sv, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

            if (editMode) {
                box.setBackground(borderDrawable(0xCCFFC400));
                box.setOnTouchListener(new DragTouch(1));
                View handle = new View(this);
                GradientDrawable hd = new GradientDrawable();
                hd.setColor(0xCCFFC400);
                hd.setCornerRadius(dp(4));
                handle.setBackground(hd);
                FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                        dp(HANDLE_DP), dp(HANDLE_DP), Gravity.END | Gravity.BOTTOM);
                box.addView(handle, hp);
                handle.setOnTouchListener(new ResizeTouch());
            }

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    camW, camH,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | (editMode ? 0 : WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.OPAQUE);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = pre ? OFFSCREEN_X : camX;
            lp.y = camY;
            wm.addView(box, lp);
            s.view = box;
            s.url = urlOf(idx);
            s.onScreen = !pre;
            Log.i(TAG, "[cam] 建窗口 " + (idx == SIDE_LEFT ? "左" : "右") + (pre ? "（预连，离屏）" : "")
                    + " " + camW + "x" + camH + " @" + lp.x + "," + lp.y);
        } catch (Throwable t) {
            Log.e(TAG, "[cam] ensureSide 失败: " + t);
        }
    }

    private void moveSide(int idx, boolean onScreen) {
        CamSide s = sides[idx];
        if (s.view == null || wm == null || s.onScreen == onScreen) return;
        try {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) s.view.getLayoutParams();
            lp.x = onScreen ? camX : OFFSCREEN_X;
            lp.y = camY;
            wm.updateViewLayout(s.view, lp);
            s.onScreen = onScreen;
            Log.i(TAG, "[cam] " + (onScreen ? "显示 " : "收起 ") + (idx == SIDE_LEFT ? "左" : "右"));
        } catch (Throwable t) {
            Log.e(TAG, "[cam] moveSide 失败: " + t);
        }
    }

    private void startSide(final int idx) {
        final CamSide s = sides[idx];
        if (s.started) return;
        if (s.url == null) return;
        if (s.view == null) ensureSide(idx, (preMask & (1 << idx)) != 0);
        if (s.player == null) s.player = new Q07RtspPlayer(OverlayService.this, s.url);
        final Q07RtspPlayer p = s.player;
        camExec.execute(new Runnable() {
            @Override public void run() {
                try {
                    Surface surf = s.surface;
                    if (surf == null) return;          // surface 还没好，等 surfaceCreated 再调
                    // 服务端只有一把发送口：刚 TEARDOWN 完要留一点时间
                    long wait = 150 - (System.currentTimeMillis() - lastStopAt);
                    if (wait > 0) Thread.sleep(wait);
                    p.start(surf);
                    s.started = true;
                    long ms = System.currentTimeMillis() - turnSignalAt;
                    Log.i(TAG, "[cam] " + s.url + " 起播   信号->起播 " + ms + "ms");
                } catch (Throwable t) {
                    Log.e(TAG, "[cam] startSide: " + t);
                }
            }
        });
    }

    /** 单线程串行执行摄像头操作，避免"停/起"竞态。 */
    private final java.util.concurrent.ExecutorService camExec =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile long lastStopAt = 0;

    private void stopPlayer(int idx) {
        final CamSide s = sides[idx];
        if (s.player != null) {
            final Q07RtspPlayer p = s.player;
            s.player = null;
            s.started = false;
            lastStopAt = System.currentTimeMillis();
            camExec.execute(new Runnable() {
                @Override public void run() {
                    // TEARDOWN 只发不等（等响应会卡 5 秒）
                    try { p.stop(); } catch (Throwable ignored) {}
                    Log.i(TAG, "[cam] stopped " + (idx == SIDE_LEFT ? "左" : "右"));
                }
            });
        }
    }

    /** 非预连侧：连窗口一起收掉（释放 RTSP 会话）。 */
    private void stopSide(int idx) {
        CamSide s = sides[idx];
        if (s.view == null) { stopPlayer(idx); return; }
        if ((preMask & (1 << idx)) != 0) { moveSide(idx, false); return; }
        stopPlayer(idx);
        try { wm.removeView(s.view); } catch (Throwable ignored) {}
        s.view = null;
        s.surface = null;
        s.onScreen = false;
        Log.i(TAG, "[cam] 收起 " + (idx == SIDE_LEFT ? "左" : "右"));
    }

    /** 预连侧：开机就把流拉起来（窗口在屏外）。 */
    private void setupPreconnect() {
        if ((preMask & 1) != 0) { ensureSide(SIDE_LEFT, true); }
        if ((preMask & 2) != 0) { ensureSide(SIDE_RIGHT, true); }
        if (preMask != 0) {
            Log.i(TAG, "[cam] 预连掩码 = " + preMask + "（sides 建好后会自动拉流）");
        }
    }
}
