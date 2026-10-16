package com.lushu.q07car;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
    private static final int ID_PDU_OPER_PRM = 0x00419309; // PDU  [8][13]=母线电压(0.1V) [15]=电流(A) [0]=温度(0.1℃,非电流!)
    private static final int ID_RESI_MILG    = 0x00412055; // 剩余里程 km
    private static final int ID_TOTAL_MILG   = 0x00501000; // 总里程
    private static final int ID_REEV_OPER    = 0x00413072; // 增程器运行参数
    private static final int ID_ENG_THML     = 0x0041317B; // 发动机热管理 [8]=实际转速rpm [4]=目标转速 ★实车标定
    private static final int ID_VCU_STS      = 0x00419310; // VCU状态 [0]=2充电/3放电 [21]=9充电/0放电 ★实车标定
    private static final int ID_TURN_LI      = 0x00415244; // 转向灯 [0]=左 [1]=右

    /** PDU[0] 量纲：×0.01 A（可用 /data/local/tmp/q07scale.txt 覆盖）。 */
    private static volatile float PDU_CURRENT_SCALE = 1.0f;   // [15] 是整数安培，系数 1
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
    /** 画面镜像模式（none/h/v/hv）。 */
    private String mirrorLeft = Prefs.MIRROR_H;
    private String mirrorRight = Prefs.MIRROR_H;

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

    /**
     * 闭锁（车机是关背光，不是 Android 关屏）→ **彻底关闭本服务**：
     * 释放摄像头（RTSP + 解码器）、卸掉悬浮窗、撤销前台服务通知、停掉所有线程与定时器。
     * 解锁由 `Q07A11yService`（无障碍服务，系统会一直绑定着）收到窗口事件后重新拉起 ——
     * 所以闭锁期间我们不留任何轮询/通知/窗口，只留那个空闲的无障碍组件。
     */
    private volatile boolean closing = false;
    private BroadcastReceiver screenReceiver;

    /** 车机的"关屏"是关背光（见 recon/lock-kill-mechanism.md），不走 Android 电源状态，
     *  所以 mWakefulness/Display State 一直是 ON、ACTION_SCREEN_OFF 也永远不发。
     *  这个 sysfs 是 **世界可读** 的（-rw-r--r--），是唯一可靠的关屏信号。 */
    private static final String BL_POWER_PATH = "/sys/class/backlight/panel0/bl_power";

    /** 背光是否已关（0 = FB_BLANK_UNBLANK 亮，其余 = 灭）。读不到就当亮着，别误睡。 */
    private boolean backlightOff() {
        return blRaw() != 0;
    }

    /** 读背光原始值；-1 = 读不到。 */
    private int blRaw() {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(BL_POWER_PATH));
            String l = r.readLine();
            r.close();
            if (l == null) return -1;
            return Integer.parseInt(l.trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    private void shutdownForLock() {
        if (closing) return;
        closing = true;
        Log.i(TAG, "[life] 闭锁（背光灭）→ 关闭服务：释放摄像头 / 卸窗口 / 撤前台通知");
        // 关键：把界面也收掉。否则解锁时 Android 会恢复"上锁前最前面的界面"，把我们弹出来。
        // 收掉之后解锁只会看到桌面，而我们自己在后台被无障碍服务安静地拉起来。
        try { MainActivity.finishIfOpen(); } catch (Throwable ignored) {}
        stopSelf();     // onDestroy 会释放摄像头、卸窗口、停线程、反注册接收器
    }

    private WindowManager wm;
    private TextView tvMain;
    private SoaClient soa;
    private Handler handler;

    // 数据
    private volatile float kwPdu, pduVolt, pduCur, hvVolt, socPct, rangeKm, totalKm, speed;
    /** 电池包电压（BCU[0]/10）—— 与车机显示值精确一致。 */
    private volatile float packVolt;
    /** 充电/放电功率是否可信。当前恒为 false：电流拿不到，见 poll() 里的说明。 */
    private volatile boolean powerKnown = false;
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

    /** 转向灯灭后摄像头窗口再保留多久（毫秒）。0 = 立即关。 */
    private volatile int camOffDelayMs = 0;
    /** cameraTurn() 的保持状态：信号消失后继续返回它，直到延迟用完。 */
    private volatile int holdTurn = 0;
    private volatile long holdTurnAt = 0;
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
    /**
     * 预连那一路的"隐藏"方式：**不是移出屏幕，而是把窗口透明度调到几乎为 0**。
     *
     * 为什么不用 OFFSCREEN_X：画面现在是 TextureView 渲染的，窗口完全在屏幕外时
     * 应用侧就不再绘制它 → SurfaceTexture 的缓冲队列没人取帧 → 解码器被队列堵住、
     * 停止取输入 → 重新显示时要等下一个关键帧，肉眼就是"卡一秒"。
     * 留在原位用 alpha 隐藏，TextureView 一直在绘制、解码器不掉链子，显示即出画。
     */
    private static final float HIDDEN_ALPHA = 0.004f;
    /**
     * 隐藏那一路的渲染尺寸。目的只是"让它一直有人取帧"，好让解码器不掉链子；
     * 画面大小无所谓 —— 所以缩到 64×36，GPU 合成成本几乎归零
     * （隐藏在 1390×782 全尺寸时，光 RenderThread 就吃掉 7% 单核）。
     * 显示时再恢复成摄像头窗口的实际大小。
     */
    private static final int HIDDEN_W = 64, HIDDEN_H = 36;

    private static class CamSide {
        View view;              // 容器（含 SurfaceView，编辑模式下带边框和缩放抓手）
        Q07RtspPlayer player;
        Surface surface;
        String url;
        boolean onScreen;
        boolean started;
        /** 想要显示、但还没显形（在等第一帧）。 */
        boolean wantShow;
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
        Prefs.migrate(this);
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

        // 关屏休眠 / 亮屏唤醒。我们能收到这些广播，正是因为没被车机 force-stop。
        try {
            IntentFilter sf = new IntentFilter();
            sf.addAction(Intent.ACTION_SCREEN_OFF);
            sf.addAction(Intent.ACTION_SCREEN_ON);
            sf.addAction(Intent.ACTION_USER_PRESENT);
            screenReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    String a = i == null ? null : i.getAction();
                    if (Intent.ACTION_SCREEN_OFF.equals(a)) shutdownForLock();
                }
            };
            registerReceiver(screenReceiver, sf);
        } catch (Throwable t) {
            Log.w(TAG, "[sleep] 注册屏幕广播失败: " + t);
        }

        // 转向灯单独用高频线程读（只读 1 个属性，~20ms），把发现延迟从 300ms 压到 ~60ms
        alive = true;
        new Thread(new Runnable() {
            @Override public void run() {
                while (alive) {
                    if (backlightOff()) { shutdownForLock(); return; }   // 闭锁=背光灭 → 关掉自己
                    boolean need = needTurn();
                    if (need) pollTurn();
                    // 60ms → 90ms：打灯发现延迟 +30ms 基本无感，但少了 1/3 的 HIDL 往返
                    try { Thread.sleep(need ? 90L : 500L); } catch (InterruptedException e) { return; }
                }
            }
        }, "q07-turn").start();

        // 数据轮询降为兜底（推送在跑时不靠它）；2 秒一轮足够
        new Thread(new Runnable() {
            @Override public void run() {
                while (alive) {
                    if (backlightOff()) { shutdownForLock(); return; }   // 闭锁=背光灭 → 关掉自己
                    poll();
                    try { Thread.sleep(2000L); } catch (InterruptedException e) { return; }
                }
            }
        }, "q07-poll").start();

        // 界面节拍：只做摄像头窗口切换（很轻），100ms 保证打灯响应
        handler.post(new Runnable() {
            @Override public void run() {
                if (closing) return;
                updateCamera();
                handler.postDelayed(this, 100L);
            }
        });

        // 看门狗：每秒体检一次（SOA 自愈 / 摄像头重连）
        handler.post(new Runnable() {
            @Override public void run() {
                if (closing) return;
                healthCheck();
                handler.postDelayed(this, 1000L);
            }
        });

        // 数据面板：400ms 一次，且文本没变就不 setText（避免无谓重绘）
        handler.post(new Runnable() {
            @Override public void run() {
                if (closing) return;
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
        try { if (screenReceiver != null) unregisterReceiver(screenReceiver); } catch (Throwable ignored) {}
        screenReceiver = null;
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
        mirrorLeft = Prefs.getStr(this, Prefs.KEY_MIRROR_LEFT, Prefs.MIRROR_H);
        mirrorRight = Prefs.getStr(this, Prefs.KEY_MIRROR_RIGHT, Prefs.MIRROR_H);
        camOffDelayMs = Prefs.getInt(this, Prefs.KEY_CAM_OFF_DELAY, Prefs.DEF_CAM_OFF_DELAY);

        Log.i(TAG, "[cam] 预连=" + p + "(mask=" + preMask + ")  面板=" + panelX + "," + panelY
                + " " + panelW + "x" + panelH + "  摄像头=" + camX + "," + camY + " " + camW + "x" + camH
                + "  编辑模式=" + editMode + "  灭灯延迟=" + camOffDelayMs + "ms");
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
            if ((a = soa.read(ID_BCU_OPER_PRM)) != null && a.length > 12) {
                hvVolt = a[12];
                // ★ 与车机显示值精确一致的电压：BCU[0] ÷ 10
                //   （实车对照：车机 387.5 V ↔ BCU[0]=3875，三次采样全部相等）
                packVolt = a[0] / 10f;
                any = true;
            }
            if ((a = soa.read(ID_PT_LAMP)) != null && a.length > 25) { socPct = a[25]; any = true; }
            if ((a = soa.read(ID_VCU_STS)) != null && a.length > 21) {
                // 实车标定（快充 31.6kW/388.7V/80.3A 现场）：a[1]=2 表示充电中。
                // 原注释写 [0]=2 属于差一位（[0] 实测恒为 1），保留旧判据做兜底。
                charging = (a[1] == 2) || (a[0] == 2) || (a[21] == 9);
                any = true;
            }
            // 充放电功率：车机显示的是「电池包电压 × 电池包电流」（反编译能量 App 的
            // chargePower() 确认：power = voltage * current / 1000），但我们只能拿到电压。
            // 那个"电池包电流"不在这条 SOA 总线上 —— 厂商 SOA 客户端全表里没有任何
            // 充电电流取值器（verify：SoaBridgeTransit 的 SoaBridgeNativeClient +
            // SoaBridge*ClientManager 全部取值器），车机自己的值是绕 CA SDK 从 QNX
            // 仪表（event_module/meter_service）取的，而 CA SDK 被 authority 白名单挡住。
            // 详见 recon/power-calibration.md。宁可不显示，也不显示错的数。
            powerKnown = false;
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
                "实时功率|" + (powerKnown ? String.format(Locale.US, "%.2f kW", kwPdu) : "—（需厂商授权）"),
                "充放电|" + (charging ? "充电中（发电/接桩）" : "放电中"),
                "高压电压|" + String.format(Locale.US, "%.0f V", hvVolt),
                "电池电压|" + String.format(Locale.US, "%.1f V", packVolt),
                "电池电流|—",
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

    /**
     * 摄像头该显示哪一侧（含"灭灯延迟"）。
     *
     * 转向灯灭后，按配置再保留 camOffDelayMs 毫秒才收窗口（默认 0 = 立即收）。
     * 注意 turnState() 自身已经带一层"闪烁去抖"的保持窗口，这里是它之后**额外**的延迟。
     */
    private int cameraTurn() {
        int t = showCamera ? turnState() : 0;
        long now = System.currentTimeMillis();
        if (t != 0) {
            holdTurn = t;
            holdTurnAt = now;
            return t;
        }
        if (camOffDelayMs > 0 && holdTurn != 0 && now - holdTurnAt < camOffDelayMs) {
            return holdTurn;      // 信号已消失，但延迟还没走完
        }
        holdTurn = 0;
        return 0;
    }

    // ---------------------------------------------------------------- 渲染

    private void render() {
        if (!showPanel && auxView == null) { return; }   // 没人看就不必拼字符串
        SpannableStringBuilder sb = new SpannableStringBuilder();
        append(sb, powerKnown ? String.format(Locale.US, "%.2f kW", kwPdu) : "—",
                2.6f, charging ? 0xFF6EC6FF : 0xFF8AE234, true);
        append(sb, String.format(Locale.US, "\n%s   ·   电池 %.1fV   ·   高压 %.0fV",
                charging ? "充电中" : "放电中", packVolt, hvVolt),
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
                    "kw=%s packV=%.1f hv=%.0f soc=%.0f range=%.0f odo=%.0f gear=%s spd=%.1f turn=%s chg=%s bl=%d closing=%s [推送累计 %d]",
                    powerKnown ? String.format(Locale.US, "%.2f", kwPdu) : "n/a",
                    packVolt, hvVolt, socPct, rangeKm, totalKm,
                    gearName(gear, gearInvalid), speed, turnName(turnState()),
                    charging ? "是" : "否", blRaw(), closing ? "是" : "否", pushCount));
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
                if (a.length > 15) {
                    pduVolt = a[8] / 10f;
                    pduCur = a[15] * PDU_CURRENT_SCALE;   // 电流在 [15]，不是 [0]
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

        // 2) 摄像头：任一侧（含预连隐藏的那侧）5 秒收不到包就重连。
        //    隐藏那侧掉流时用户看不出来，等打灯才开始重连就是"卡一下"，所以要提前修好。
        if (showCamera) {
            for (int i = 0; i < 2; i++) {
                CamSide s = sides[i];
                if (s.player == null || s.view == null) { continue; }
                long idle = now - s.player.lastRecv();
                if (s.player.lastRecv() > 0 && idle > 5000 && now - lastCamRetryAt > 8000) {
                    lastCamRetryAt = now;
                    Log.w(TAG, "[wd] 摄像头 " + (i == SIDE_LEFT ? "左" : "右") + " 已 " + idle
                            + "ms 无数据（" + (s.onScreen ? "显示中" : "隐藏中") + "），重连");
                    stopPlayer(i);
                    startSide(i);
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
        int turn = cameraTurn();          // 含"灭灯延迟"，默认 0 = 立即收
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
            armReveal(want);           // 有画面了再显形，避免黑一下
        } else {
            sides[want].url = urlOf(want);
            ensureSide(want, false);
            startSide(want);           // surface 就绪后会自行起播
            armReveal(want);           // 同上：等第一帧
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
            armReveal(want);           // 等第一帧再显形
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

            int fullH = Math.max(dp(112), (int) (camW / CAM_ASPECT + 0.5f));
            // 正常使用：一律先按"隐藏态"建窗（64x36 + 近全透明），
            // 等解码器真的画出第一帧再 moveSide(idx,true) 显形 —— 否则新起的流会先黑一下。
            // 编辑模式保持原样（整窗可见，方便拖/缩放）。
            boolean hideFirst = !editMode;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    hideFirst ? HIDDEN_W : camW,
                    hideFirst ? HIDDEN_H : fullH,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | (editMode ? 0 : WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.OPAQUE);
            lp.gravity = Gravity.TOP | Gravity.START;
            if (editMode) {
                lp.x = pre ? OFFSCREEN_X : camX;   // 编辑模式：预连侧移出屏幕，避免两个窗口叠在一起不好拖
                lp.y = camY;
            } else {
                lp.x = camX;                       // 正常使用：留在原位，靠透明度隐藏
                lp.y = camY;
                lp.alpha = HIDDEN_ALPHA;
            }
            wm.addView(box, lp);
            s.view = box;
            s.url = urlOf(idx);
            s.onScreen = editMode && !pre;         // 编辑模式沿用原状态；正常使用一律先隐藏
            Log.i(TAG, "[cam] 建窗口 " + (idx == SIDE_LEFT ? "左" : "右")
                    + (pre ? (editMode ? "（预连，离屏）" : "（预连，透明隐藏）") : "")
                    + " 初始=" + lp.width + "x" + lp.height + " alpha=" + lp.alpha
                    + " 目标=" + camW + "x" + camH + " @" + lp.x + "," + lp.y);
        } catch (Throwable t) {
            Log.e(TAG, "[cam] ensureSide 失败: " + t);
        }
    }

    private void moveSide(int idx, boolean onScreen) {
        CamSide s = sides[idx];
        s.wantShow = onScreen;
        // 先按"是否在显示"决定这一侧要不要把解码结果画到 Surface 上（隐藏侧只解码不渲染）
        if (s.player != null) {
            if (!onScreen) { s.player.setOnFirstFrame(null); }   // 不再等显形
            s.player.setRender(onScreen);
        }
        if (s.view == null || wm == null || s.onScreen == onScreen) return;
        try {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) s.view.getLayoutParams();
            if (editMode) {
                // 编辑模式：预连侧移出屏幕（避免两窗重叠），但解码可能因此停摆 —— 只影响编辑时
                lp.x = onScreen ? camX : OFFSCREEN_X;
                lp.y = camY;
                lp.alpha = 1f;
                lp.width = camW;
                lp.height = Math.max(dp(112), (int) (camW / CAM_ASPECT + 0.5f));
            } else {
                // 正常使用：位置一直在摄像头该在的地方，只切透明度和尺寸 ——
                // 隐藏时缩到 64×36：既保证 TextureView 仍在绘制（解码器不会被队列堵住），
                // 又几乎不占 GPU 合成。
                lp.x = camX;
                lp.y = camY;
                lp.alpha = onScreen ? 1f : HIDDEN_ALPHA;
                lp.width = onScreen ? camW : HIDDEN_W;
                lp.height = onScreen ? Math.max(dp(112), (int) (camW / CAM_ASPECT + 0.5f)) : HIDDEN_H;
            }
            wm.updateViewLayout(s.view, lp);
            s.onScreen = onScreen;
            long f = (s.player != null) ? s.player.frames() : -1;
            Log.i(TAG, "[cam] " + (onScreen ? "显示 " : "隐藏 ")
                    + (idx == SIDE_LEFT ? "左" : "右") + "  frames=" + f);
        } catch (Throwable t) {
            Log.e(TAG, "[cam] moveSide 失败: " + t);
        }
    }

    /**
     * 想显示某一侧，但**先不显形**：窗口留在隐藏态、打开渲染，
     * 等解码器真的画出第一帧再显形 —— 避免"弹出来先黑一下"。
     *
     * 已经出过画面的（之前显示过、隐藏后 TextureView 还留着最后一帧）直接显形，不必等。
     */
    private void armReveal(final int idx) {
        final CamSide s = sides[idx];
        s.wantShow = true;
        final Q07RtspPlayer p = s.player;
        if (p == null) { return; }          // 播放器还没建 —— startSide 里建好后会再调一次

        p.setRender(true);                  // 往（仍然隐藏的）窗口出画
        if (p.hasRendered()) {              // 隐藏态下已经有过画面，直接显形
            p.setOnFirstFrame(null);
            moveSide(idx, true);
            return;
        }
        p.setOnFirstFrame(new Runnable() {
            @Override public void run() {
                handler.post(new Runnable() {
                    @Override public void run() {
                        CamSide cs = sides[idx];
                        if (cs.player != p) return;    // 中途换过播放器，忽略
                        if (!cs.wantShow) return;      // 期间又不要显示了，忽略
                        p.setOnFirstFrame(null);
                        moveSide(idx, true);           // 有画面了，显形
                    }
                });
            }
        });
    }

    private void startSide(final int idx) {
        final CamSide s = sides[idx];
        if (s.started) return;
        if (s.url == null) return;
        if (s.view == null) ensureSide(idx, (preMask & (1 << idx)) != 0);
        if (s.player == null) s.player = new Q07RtspPlayer(OverlayService.this, s.url);
        final Q07RtspPlayer p = s.player;
        p.setRender(s.onScreen);   // 预连（隐藏）的那一侧只解码、不渲染
        if (s.wantShow) { armReveal(idx); }   // 想要显示：等第一帧真的画出来再显形
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
     * 直接 setTransform 一个"以视图中心翻转"的矩阵即可。
     *
     * mode: none / h（水平，像后视镜）/ v（垂直）/ hv（水平+垂直 = 180°）
     */
    private void applyMirror(int idx, TextureView tv) {
        String mode = (idx == SIDE_LEFT) ? mirrorLeft : mirrorRight;
        if (mode == null) mode = Prefs.MIRROR_NONE;
        boolean hz = mode.contains("h");
        boolean vt = mode.contains("v");
        int w = tv.getWidth(), h = tv.getHeight();
        if ((!hz && !vt) || w <= 0 || h <= 0) {
            tv.setTransform(null);
            return;
        }
        Matrix m = new Matrix();
        m.setScale(hz ? -1f : 1f, vt ? -1f : 1f, w / 2f, h / 2f);   // 以视图中心翻转
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
