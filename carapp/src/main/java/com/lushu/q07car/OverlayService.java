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
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.view.TextureView;
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
    private static final int ID_ENG_THML     = 0x0041317B; // 发动机热管理 [8]=实际转速rpm [4]=目标转速 ★实车标定
    private static final int ID_VCU_STS      = 0x00419310; // VCU状态 [0]=2充电/3放电 [21]=9充电/0放电 ★实车标定
    private static final int ID_TURN_LI      = 0x00415244; // 转向灯 [0]=左 [1]=右

    /** PDU[0] 量纲：×0.01 A（可用 /data/local/tmp/q07scale.txt 覆盖）。 */
    private static volatile float PDU_CURRENT_SCALE = 0.01f;
    private static final float SPEED_SCALE = 0.1f;

    /** 转向灯保持窗口（去闪烁）：自适应，取实测闪烁周期的 1.7 倍，钳制在 [900, 2500]ms。 */
    private static final long TURN_HOLD_MS = 2500L;
    private static final long TURN_HOLD_MIN_MS = 900L;
    /** 实测的闪烁周期（两次"亮"之间的间隔），初值按 800ms 估。 */
    private volatile long flashInterval = 800L;
    private volatile long lastOnEdgeAt = 0L;
    private volatile boolean prevL = false, prevR = false;

    // 面板 / 摄像头窗口的位置大小改为从 Prefs 读取（可由 App 界面编辑）
    private int panelX, panelY, panelW, panelH;
    private int camX, camY, camW, camH;
    private boolean editMode;
    private boolean showPanel = true;
    private boolean showCamera = true;
    /** 画面镜像（左右各自）。 */
    private boolean mirrorLeft = true;
    private boolean mirrorRight = true;

    /** 编辑模式下的抓手尺寸 */
    private static final int HANDLE_DP = 44;
    private static final int BORDER_DP = 3;
    /** 侧摄像头画面是 1920×1080，窗口锁 16:9，缩放时不拉伸。 */
    private static final float CAM_ASPECT = 16f / 9f;

    private static final String RTSP_LEFT = "rtsp://172.16.6.26:8888/lt";
    private static final String RTSP_RIGHT = "rtsp://172.16.6.26:8888/rt";

    public static volatile boolean running = false;
    /** 主界面是否在前台（由 MainActivity 通知）—— 用于"没人看就省资源"。 */
    public static volatile boolean uiForeground = false;

    public static void setUiForeground(boolean f) {
        uiForeground = f;
    }
    /** 服务活着才继续跑轮询线程（否则 stopService/startService 会泄漏线程）。 */
    private volatile boolean alive = false;

    private WindowManager wm;
    private TextView tvMain;
    private SoaClient soa;
    private Handler handler;

    // 数据
    private volatile float kwPdu, pduVolt, pduCur, hvVolt, socPct, rangeKm, totalKm, speed;
    private volatile int gear = -1, gearInvalid = -1, powerShift = -1;
    /** 发动机(增程器)转速 rpm：0x0041317B[8]（实测：熄火 0 / 低档 990 / 中档 1090 / 高档 1350） */
    private volatile int rpm = 0;
    /** 目标转速 rpm：0x0041317B[4]（熄火瞬间清零，随充电档位阶梯变化） */
    private volatile int rpmTarget = 0;
    /** 电池是否在充电：0x00419310 [0]==2 或 [21]==9（充电）。两次实车实验一致。 */
    private volatile boolean charging = false;
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

    /** 预连掩码：bit0=左, bit1=右。默认双路（新服务端支持多客户端）。 */
    private volatile int preMask = 0;

    public static final String PRE_DEFAULT = PRE_BOTH;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    /** START_STICKY：被系统回收后尽量自动重建。 */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        serviceStartAt = System.currentTimeMillis();
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
            if (showPanel) wm.addView(tvMain, lp);
        } catch (Throwable t) {
            Log.e(TAG, "面板窗口失败（多半没有『显示在其他应用上层』权限）: " + t);
        }

        new Thread(new Runnable() {
            @Override public void run() {
                soa = new SoaClient();
                boolean ok = soa.init(OverlayService.this);
                Log.i(TAG, "SOA init = " + ok);
                // 实测：客户端侧 subscribeProperty 不推送（HAL 的通知只发给
                // "服务端按名字订阅"的系统应用，日志里的 SoaBridge_Basc_*_Client 就是）。
                // 因此维持轮询；enablePush()/subscribe() 代码保留，将来走 server 路径再用。
                // if (ok) startPush();
            }
        }, "q07-soa").start();

        // 预连配置
        setupPreconnect();
        // 副驾屏面板
        setupAuxDisplay();

        // 转向灯单独用高频线程读（只读 1 个属性，~20ms），把发现延迟从 300ms 压到 ~60ms
        alive = true;
        new Thread(new Runnable() {
            @Override public void run() {
                while (alive) {
                    boolean need = needTurn();
                    if (need) pollTurn();
                    try { Thread.sleep(need ? 60L : 500L); } catch (InterruptedException e) { return; }
                }
            }
        }, "q07-turn").start();

        // 数据轮询降为兜底（推送在跑时不靠它）；2 秒一轮足够
        new Thread(new Runnable() {
            @Override public void run() {
                while (alive) {
                    poll();
                    try { Thread.sleep(2000L); } catch (InterruptedException e) { return; }
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

        // 看门狗：每秒体检一次（SOA 自愈 / 摄像头重连）
        handler.post(new Runnable() {
            @Override public void run() {
                healthCheck();
                handler.postDelayed(this, 1000L);
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
        showPanel = Prefs.getBool(this, Prefs.KEY_SHOW_PANEL, true);
        showCamera = Prefs.getBool(this, Prefs.KEY_SHOW_CAMERA, true);
        mirrorLeft = Prefs.getBool(this, Prefs.KEY_MIRROR_LEFT, true);
        mirrorRight = Prefs.getBool(this, Prefs.KEY_MIRROR_RIGHT, true);

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
                    int w = Math.max(dp(200), startW + (int) (e.getRawX() - downX));
                    // 锁 16:9：只按宽度算高度，画面不拉伸
                    int h = Math.max(dp(112), (int) (w / CAM_ASPECT + 0.5f));
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

    // ---------------------------------------------------------------- 省资源

    /**
     * 需要「完整车辆数据」吗 —— 数据面板 / 主界面 / 副驾面板任一在显示才需要。
     * 都不在时（用户把面板关了、也没打开 App），每 2 秒读十几个 SOA 属性纯属浪费。
     */
    private boolean needFullData() {
        return showPanel || uiForeground || auxView != null;
    }

    /** 需要转向灯吗 —— 面板要显示转向状态，或摄像头要跟着打灯切换。 */
    private boolean needTurn() {
        return needFullData() || showCamera;
    }

    private void poll() {
        if (soa == null) return;
        if (!needFullData()) {
            // 低功耗：不读 SOA，但保持健康时间戳 —— 否则看门狗会误判「12 秒没数据」去重建连接
            lastPollOkAt = System.currentTimeMillis();
            return;
        }
        try {
            int[] a;
            boolean any = false;
            if ((a = soa.read(ID_GEAR_POSN)) != null && a.length >= 2) { gear = a[0]; gearInvalid = a[1]; any = true; }
            if ((a = soa.read(ID_VEH_SPD)) != null && a.length >= 1) { speed = a[0] * SPEED_SCALE; any = true; }
            if ((a = soa.read(ID_POWER_SHIFT)) != null && a.length >= 1) { powerShift = a[0]; any = true; }
            if ((a = soa.read(ID_RESI_MILG)) != null && a.length >= 1) { rangeKm = a[0]; any = true; }
            if ((a = soa.read(ID_TOTAL_MILG)) != null && a.length >= 1) { totalKm = a[0]; any = true; }
            if ((a = soa.read(ID_BCU_OPER_PRM)) != null && a.length > 12) { hvVolt = a[12]; any = true; }
            if ((a = soa.read(ID_PT_LAMP)) != null && a.length > 25) { socPct = a[25]; any = true; }
            if ((a = soa.read(ID_VCU_STS)) != null && a.length > 21) {
                charging = (a[0] == 2 || a[21] == 9);
                any = true;
            }
            if ((a = soa.read(ID_PDU_OPER_PRM)) != null && a.length > 13) {
                pduVolt = a[8] / 10f;
                pduCur = a[0] * PDU_CURRENT_SCALE;
                float kw = pduVolt * pduCur / 1000f;
                kwPdu = charging ? -kw : kw;   // 正 = 放电，负 = 充电
                any = true;
            }
            if ((a = soa.read(ID_REEV_OPER)) != null) {
                for (int i = 0; i < reev.length && i < a.length; i++) reev[i] = a[i];
                any = true;
            }
            if ((a = soa.read(ID_ENG_THML)) != null && a.length > 8) {
                rpm = a[8];        // 实际转速
                rpmTarget = a[4];  // 目标转速
                any = true;
            }
            // 必须"真的读到东西"才算健康 —— 否则读不到时也会刷新时间戳，看门狗就失效了
            if (any) {
                liveOk = true;
                lastPollOkAt = System.currentTimeMillis();
                publishLive();
            } else {
                liveOk = false;
            }
        } catch (Throwable ignored) {
        }
    }

    /** 把当前数据打包给 App 页面显示。 */
    private void publishLive() {
        liveRows = new String[]{
                "实时功率|" + String.format(Locale.US, "%.2f kW", kwPdu),
                "充放电|" + (charging ? "充电中（发电/接桩）" : "放电中"),
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
                "发动机转速|" + rpm + " rpm",
                "目标转速|" + rpmTarget + " rpm",
                "增程器|" + reev[0] + " / " + reev[1] + " / " + reev[2] + " / " + reev[6],
        };
    }

    /** 只读转向灯，供高频线程调用（推送在跑时直接跳过）。 */
    private void pollTurn() {
        if (soa == null) return;
        // 推送活着就不轮询；10 秒没收到推送才退回轮询
        if (lastPushAt > 0 && System.currentTimeMillis() - lastPushAt < 10000) return;
        try {
            applyTurn(soa.read(ID_TURN_LI));
        } catch (Throwable ignored) {
        }
    }

    /** 0 关 1 左 2 右 3 双闪（保持窗口按实测闪烁周期自适应，灯灭后尽快收起） */
    private int turnState() {
        long hold = Math.max(TURN_HOLD_MIN_MS, Math.min(TURN_HOLD_MS, (long) (flashInterval * 1.7)));
        return (System.currentTimeMillis() - curTurnAt < hold) ? curTurn : 0;
    }

    // ---------------------------------------------------------------- 渲染

    private void render() {
        if (!showPanel && auxView == null) { return; }   // 没人看就不必拼字符串
        SpannableStringBuilder sb = new SpannableStringBuilder();
        append(sb, String.format(Locale.US, "%.2f kW", kwPdu), 2.6f,
                charging ? 0xFF6EC6FF : 0xFF8AE234, true);
        append(sb, String.format(Locale.US, "\n%s   ·   PDU %.1fV × %.1fA   ·   高压 %.0fV",
                charging ? "充电中" : "放电中", pduVolt, pduCur, hvVolt),
                0.8f, charging ? 0xFF6EC6FF : 0xFF9ACD32, false);
        append(sb, String.format(Locale.US, "\n电量 %.0f%%   ·   续航 %.0f km   ·   总里程 %.0f km",
                socPct, rangeKm, totalKm), 1f, 0xFFE6EDF3, false);
        append(sb, String.format(Locale.US, "\n挡位 %s   ·   %.1f km/h   ·   电源 %s   ·   转向 %s",
                gearName(gear, gearInvalid), speed, powerShiftName(powerShift), turnName(turnState())),
                1f, 0xFFE6EDF3, false);
        append(sb, String.format(Locale.US, "\n转速 %d rpm（目标 %d）   ·   %s",
                rpm, rpmTarget, rpm > 0 ? "增程器运行" : "增程器停止"), 1f, 0xFFFCE94F, false);
        append(sb, String.format(Locale.US, "\nREEV=%d/%d/%d/%d", reev[0], reev[1], reev[2], reev[6]),
                0.52f, 0x99FFFFFF, false);

        if (tvMain != null && !sb.toString().equals(lastText)) {
            lastText = sb.toString();
            tvMain.setText(sb);
            if (auxView != null) auxView.setText(sb);
        }

        long now = System.currentTimeMillis();
        if (now - lastLogAt > 10000L) {
            lastLogAt = now;
            Log.i(TAG, String.format(Locale.US,
                    "kw=%.2f pduV=%.1f pduI=%.1f hv=%.0f soc=%.0f range=%.0f odo=%.0f gear=%s spd=%.1f turn=%s  [推送累计 %d]",
                    kwPdu, pduVolt, pduCur, hvVolt, socPct, rangeKm, totalKm,
                    gearName(gear, gearInvalid), speed, turnName(turnState()), pushCount));
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

    // ---------------------------------------------------------------- 推送（订阅）

    private volatile long lastPushAt = 0;
    private volatile long pushCount = 0;

    private static final int[] SUBSCRIBE_IDS = {
            ID_GEAR_POSN, ID_VEH_SPD, ID_POWER_SHIFT, ID_RESI_MILG, ID_TOTAL_MILG,
            ID_BCU_OPER_PRM, ID_PT_LAMP, ID_PDU_OPER_PRM, ID_REEV_OPER, ID_TURN_LI,
    };

    /** 打开推送并订阅我们关心的属性（走厂商的 PropertyHalListener 回调）。 */
    private void startPush() {
        if (soa == null) return;
        boolean ok = soa.enablePush(new SoaClient.OnProp() {
            @Override public void onProp(int id, int[] value) {
                try { applyProp(id, value); } catch (Throwable ignored) {}
            }
        });
        if (!ok) return;
        for (int id : SUBSCRIBE_IDS) soa.subscribe(id);
        Log.i(TAG, "已订阅 " + SUBSCRIBE_IDS.length + " 个属性（有变化即时回调，轮询降为兜底）");
    }

    /** 推送回来的值：按 id 落到对应字段。 */
    private void applyProp(int id, int[] a) {
        if (a == null) return;
        long now = System.currentTimeMillis();
        lastPushAt = now;
        lastPollOkAt = now;      // 推送活着也算健康
        liveOk = true;
        pushCount++;
        switch (id) {
            case ID_GEAR_POSN:
                if (a.length >= 2) { gear = a[0]; gearInvalid = a[1]; }
                break;
            case ID_VEH_SPD:
                if (a.length >= 1) speed = a[0] * SPEED_SCALE;
                break;
            case ID_POWER_SHIFT:
                if (a.length >= 1) powerShift = a[0];
                break;
            case ID_RESI_MILG:
                if (a.length >= 1) rangeKm = a[0];
                break;
            case ID_TOTAL_MILG:
                if (a.length >= 1) totalKm = a[0];
                break;
            case ID_BCU_OPER_PRM:
                if (a.length > 12) hvVolt = a[12];
                break;
            case ID_PT_LAMP:
                if (a.length > 25) socPct = a[25];
                break;
            case ID_PDU_OPER_PRM:
                if (a.length > 13) {
                    pduVolt = a[8] / 10f;
                    pduCur = a[0] * PDU_CURRENT_SCALE;
                    kwPdu = pduVolt * pduCur / 1000f;
                }
                break;
            case ID_REEV_OPER:
                for (int i = 0; i < reev.length && i < a.length; i++) reev[i] = a[i];
                break;
            case ID_TURN_LI:
                applyTurn(a);
                break;
            default:
                break;
        }
        publishLive();
    }

    /** 转向灯状态机（轮询与推送共用）。 */
    private void applyTurn(int[] a) {
        if (a == null || a.length < 2) return;
        long now = System.currentTimeMillis();
        boolean l = a[0] != 0;
        boolean r = a[1] != 0;

        // 记录"亮"的上升沿间隔，用于估计闪烁周期（自适应保持窗口）
        if ((l && !prevL) || (r && !prevR)) {
            if (lastOnEdgeAt > 0) {
                long d = now - lastOnEdgeAt;
                if (d > 200 && d < 4000) flashInterval = d;
            }
            lastOnEdgeAt = now;
        }
        prevL = l;
        prevR = r;

        int before = curTurn;
        if (l && r) {
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
        if (curTurn != before && (curTurn == 1 || curTurn == 2)) {
            turnSignalAt = now;
            Log.i(TAG, "[cam] 收到转向信号: " + turnName(curTurn) + (lastPushAt > 0 ? "（推送）" : ""));
        }
    }

    // ---------------------------------------------------------------- 自愈看门狗

    private volatile long lastPollOkAt = 0;
    private volatile long lastSoaRetryAt = 0;
    private volatile long lastCamRetryAt = 0;
    private volatile long serviceStartAt = 0;

    /**
     * 车机会休眠/被系统回收，这之后：
     *  - SOA 连接可能已经死了 -> 数据卡住不动
     *  - RTSP 会话可能被断开  -> 画面黑掉但窗口还在
     * 这里定时体检并自愈。
     */
    private void healthCheck() {
        long now = System.currentTimeMillis();

        // 1) SOA：超过 12 秒没有新数据就重建连接
        boolean soaStale = (soa == null)
                || (lastPollOkAt == 0
                        ? (now - serviceStartAt > 15000)
                        : (now - lastPollOkAt > 12000));
        if (soaStale && now - lastSoaRetryAt > 15000) {
            lastSoaRetryAt = now;
            Log.w(TAG, "[wd] SOA 无数据，重建连接（上次成功 " + (lastPollOkAt == 0 ? "从未" : (now - lastPollOkAt) + "ms 前") + "）");
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        SoaClient c = new SoaClient();
                        if (c.init(OverlayService.this)) {
                            soa = c;
                            lastPollOkAt = System.currentTimeMillis();
                            liveOk = true;
                            Log.i(TAG, "[wd] SOA 重建成功");
                        } else {
                            Log.w(TAG, "[wd] SOA 重建失败，稍后再试");
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "[wd] SOA 重建异常: " + t);
                    }
                }
            }, "q07-soa-retry").start();
        }

        // 2) 摄像头：正在显示的那一侧 5 秒收不到包就重连
        if (showCamera) {
            int want = wantedSide();
            if (want >= 0) {
                CamSide s = sides[want];
                if (s.player != null && s.onScreen) {
                    long idle = now - s.player.lastRecv();
                    if (s.player.lastRecv() > 0 && idle > 5000 && now - lastCamRetryAt > 8000) {
                        lastCamRetryAt = now;
                        Log.w(TAG, "[wd] 摄像头 " + (want == SIDE_LEFT ? "左" : "右")
                                + " 已 " + idle + "ms 无数据，重连");
                        stopPlayer(want);
                        startSide(want);
                    }
                }
            }
        }
    }

    private int wantedSide() {
        int turn = showCamera ? turnState() : 0;
        return (turn == 1) ? SIDE_LEFT : (turn == 2) ? SIDE_RIGHT : -1;
    }

    // ---------------------------------------------------------------- 摄像头

    private static String urlOf(int side) {
        return side == SIDE_LEFT ? RTSP_LEFT : RTSP_RIGHT;
    }

    private void updateCamera() {
        if (wm == null) return;
        // 摄像头功能关掉 → 连预连一起收掉，释放 RTSP 会话 + 解码器
        if (!showCamera) {
            if (camBuilt) releaseCameras();
            return;
        }
        if (!camBuilt) setupPreconnect();
        int turn = showCamera ? turnState() : 0;
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

    /** 已经有 player 的一侧：只要流还活着就**只移窗口**（零延迟、无黑屏）；
     *  流真的断了才 re-PLAY 抢回发送口。 */
    private void resumeSide(final int idx) {
        final CamSide s = sides[idx];
        if (s.player == null || s.onScreen) return;
        final long idle = System.currentTimeMillis() - s.player.lastRecv();
        if (s.player.lastRecv() > 0 && idle < 2000) return;   // 一直在收流，直接显示即可
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
            final TextureView sv = new TextureView(this);
            sv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                    sides[idx].surface = new Surface(st);
                    applyMirror(idx, sv);
                    startSide(idx);
                }
                @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                    applyMirror(idx, sv);
                }
                @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                    final Surface old = sides[idx].surface;
                    sides[idx].surface = null;
                    stopPlayer(idx);                     // 异步停流
                    if (old != null) {
                        camExec.execute(new Runnable() {  // 停完再释放 surface
                            @Override public void run() {
                                try { old.release(); } catch (Throwable ignored) {}
                            }
                        });
                    }
                    return true;
                }
                @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
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
                    camW, Math.max(dp(112), (int) (camW / CAM_ASPECT + 0.5f)),
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
        if (!showCamera) { camBuilt = false; return; }
        if ((preMask & 1) != 0) { ensureSide(SIDE_LEFT, true); }
        if ((preMask & 2) != 0) { ensureSide(SIDE_RIGHT, true); }
        camBuilt = true;
        if (preMask != 0) {
            Log.i(TAG, "[cam] 预连掩码 = " + preMask + "（sides 建好后会自动拉流）");
        }
    }

    // ---------------------------------------------------------------- 摄像头：镜像 / 释放

    /** 摄像头窗口是否已建（用于「不需要就释放、需要就重建」）。 */
    private volatile boolean camBuilt = false;

    /**
     * 应用镜像。改成 TextureView 就是为了这个：
     * SurfaceView 是系统合成层，拿不到可变换的矩阵；TextureView 是应用侧合成，
     * 直接 setTransform 一个以中轴翻转的矩阵即可（setScale(-1, 1) 就是水平镜像）。
     */
    private void applyMirror(int idx, TextureView tv) {
        boolean mirror = (idx == SIDE_LEFT) ? mirrorLeft : mirrorRight;
        int w = tv.getWidth(), h = tv.getHeight();
        if (!mirror || w <= 0 || h <= 0) {
            tv.setTransform(null);
            return;
        }
        Matrix m = new Matrix();
        m.setScale(-1f, 1f, w / 2f, h / 2f);   // 以中轴翻转
        tv.setTransform(m);
    }

    /** 彻底释放摄像头（含预连窗口）：RTSP 会话 + H.265 解码器全部回收。 */
    private void releaseCameras() {
        camBuilt = false;
        for (int i = 0; i < 2; i++) {
            stopPlayer(i);
            CamSide s = sides[i];
            if (s.view != null && wm != null) {
                try { wm.removeView(s.view); } catch (Throwable ignored) {}
            }
            s.view = null;
            s.surface = null;
            s.onScreen = false;
            s.started = false;
        }
        Log.i(TAG, "[cam] 已释放摄像头资源（含预连）");
    }

    // ---------------------------------------------------------------- 副驾屏

    private WindowManager auxWm;
    private TextView auxView;

    /** 在指定屏幕上再开一份数据面板（副驾屏）。 */
    private void setupAuxDisplay() {
        int id = Prefs.getInt(this, Prefs.KEY_AUX_DISPLAY, -1);
        if (id < 0) return;
        try {
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
            if (dm == null) return;
            android.view.Display target = null;
            for (android.view.Display d : dm.getDisplays()) {
                if (d.getDisplayId() == id) target = d;
            }
            if (target == null) {
                Log.w(TAG, "[aux] 找不到 display " + id);
                return;
            }
            Context dc = createDisplayContext(target);
            auxWm = (WindowManager) dc.getSystemService(Context.WINDOW_SERVICE);
            auxView = new TextView(dc);
            auxView.setTextColor(0xFFE6EDF3);
            auxView.setSingleLine(false);
            auxView.setBackgroundColor(0xE60A0E12);
            auxView.setPadding(44, 26, 44, 26);
            auxView.setTextSize(Math.max(10f, Math.min(40f, panelH / 19f)));

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    panelW, panelH,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = panelX;
            lp.y = panelY;
            auxWm.addView(auxView, lp);
            Log.i(TAG, "[aux] 已在 display " + id + " 打开面板");
        } catch (Throwable t) {
            Log.e(TAG, "[aux] 打开失败（该屏可能是私有的）: " + t);
        }
    }
}
