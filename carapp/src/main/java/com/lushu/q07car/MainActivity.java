package com.lushu.q07car;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Arrays;

/**
 * 配置界面：主题、实时数据、悬浮窗开关、布局编辑、侧摄像头预连、开机自启、自检。
 */
public class MainActivity extends Activity {

    private static final String TAG = "Q07Car";

    private static final String THEME_DARK = "dark";
    private static final String THEME_LIGHT = "light";
    private static final String THEME_SYSTEM = "system";

    // 调色板（按主题切换）
    private int cBg, cCard, cBorder, cText, cSub, cAccent, cOk, cOff, cRipple;

    private TextView status;
    private TextView diag;
    private LinearLayout liveBox;
    private TextView liveHint;
    private String liveKey = "";
    private final Handler ui = new Handler(Looper.getMainLooper());

    private boolean darkNow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 主题必须在 setContentView 之前定
        darkNow = resolveDark();
        setTheme(darkNow
                ? android.R.style.Theme_Material_NoActionBar
                : android.R.style.Theme_Material_Light_NoActionBar);
        applyPalette();

        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(cBg);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(18);
        root.setPadding(p, dp(28), p, dp(28));
        scroll.addView(root);

        // ---------------- 头部 ----------------
        TextView title = new TextView(this);
        title.setText("Q07 实时数据");
        title.setTextSize(28f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(cText);
        root.addView(title);

        TextView subTitle = new TextView(this);
        subTitle.setText("长安启源 Q07 · 悬浮窗控制台");
        subTitle.setTextSize(14f);
        subTitle.setTextColor(cSub);
        subTitle.setPadding(0, dp(4), 0, dp(4));
        root.addView(subTitle);

        // ---------------- 实时数据 ----------------
        LinearLayout live = card(root, "实时数据");
        liveHint = new TextView(this);
        liveHint.setTextSize(13f);
        liveHint.setTextColor(cSub);
        live.addView(liveHint);
        liveBox = new LinearLayout(this);
        liveBox.setOrientation(LinearLayout.VERTICAL);
        liveBox.setPadding(0, dp(6), 0, 0);
        live.addView(liveBox);

        // ---------------- 运行状态 ----------------
        LinearLayout st = card(root, "运行状态");
        status = new TextView(this);
        status.setTextSize(15f);
        status.setTextColor(cText);
        status.setLineSpacing(dp(4), 1f);
        st.addView(status);

        LinearLayout btnRow = hrow(st);
        Button bStart = mkButton("启动", true);
        bStart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startServiceCompat(); refresh(); }
        });
        addWide(btnRow, bStart, 1f);

        Button bStop = mkButton("停止", false);
        bStop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                stopService(new Intent(MainActivity.this, OverlayService.class));
                refresh();
            }
        });
        addWide(btnRow, bStop, 1f);

        Button bPerm = mkButton("授权", false);
        bPerm.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                } catch (Throwable t) {
                    Log.e(TAG, "打开悬浮窗设置页失败: " + t);
                }
            }
        });
        addWide(btnRow, bPerm, 1f);

        // ---------------- 外观 ----------------
        LinearLayout theme = card(root, "外观");
        final String[] tKeys = {THEME_SYSTEM, THEME_DARK, THEME_LIGHT};
        final String[] tLabels = {"跟随系统", "深色", "浅色"};
        String curTheme = Prefs.getStr(this, Prefs.KEY_THEME, THEME_SYSTEM);
        final RadioGroup tg = new RadioGroup(this);
        tg.setOrientation(RadioGroup.HORIZONTAL);
        for (int i = 0; i < tKeys.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(tLabels[i]);
            rb.setTextSize(15f);
            rb.setTextColor(cText);
            rb.setButtonTintList(tint());
            rb.setId(i);
            if (tKeys[i].equals(curTheme)) rb.setChecked(true);
            tg.addView(rb);
        }
        theme.addView(tg);
        theme.addView(ghost("应用主题", new View.OnClickListener() {
            @Override public void onClick(View v) {
                int id = tg.getCheckedRadioButtonId();
                String key = (id >= 0 && id < tKeys.length) ? tKeys[id] : THEME_SYSTEM;
                Prefs.putStr(MainActivity.this, Prefs.KEY_THEME, key);
                recreate();
            }
        }));

        // ---------------- 布局 ----------------
        LinearLayout lay = card(root, "布局");
        note(lay, "打开编辑后：拖动面板 / 摄像头窗口可移动；拖摄像头右下角方块可缩放。\n"
                + "关掉后窗口恢复「穿透」，不再挡触摸。位置和大小会自动保存。");

        Switch editSw = new Switch(this);
        styleSwitch(editSw, "编辑布局（可拖动 / 缩放）");
        editSw.setChecked(Prefs.getBool(this, Prefs.KEY_EDIT, false));
        editSw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                Prefs.putBool(MainActivity.this, Prefs.KEY_EDIT, checked);
                restartService();
            }
        });
        lay.addView(editSw);

        lay.addView(ghost("恢复默认位置和大小", new View.OnClickListener() {
            @Override public void onClick(View v) {
                Prefs.resetLayout(MainActivity.this);
                restartService();
            }
        }));

        // ---------------- 摄像头 ----------------
        LinearLayout cam = card(root, "侧摄像头预连");
        note(cam, "预连 = 开机就把该侧 RTSP 拉起来常驻，打灯只把窗口移进来 → 基本零延迟。\n"
                + "注意：服务端只有一路发送口，预连哪侧会把另一侧挤掉（切过去会慢一点）。");

        final String[] keys = {OverlayService.PRE_NONE, OverlayService.PRE_LEFT,
                OverlayService.PRE_RIGHT, OverlayService.PRE_BOTH};
        final String[] labels = {"不预连（切换要重连）", "预连左侧", "预连右侧",
                "双路预连（服务端只支持一路，多半无效）"};
        String cur = Prefs.getStr(this, Prefs.KEY_PRECONNECT, OverlayService.PRE_NONE);

        final RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        for (int i = 0; i < keys.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(labels[i]);
            rb.setTextSize(15f);
            rb.setTextColor(cText);
            rb.setButtonTintList(tint());
            rb.setId(i);
            if (keys[i].equals(cur)) rb.setChecked(true);
            group.addView(rb);
        }
        cam.addView(group);

        cam.addView(primary("应用并重启悬浮窗", new View.OnClickListener() {
            @Override public void onClick(View v) {
                int id = group.getCheckedRadioButtonId();
                String key = (id >= 0 && id < keys.length) ? keys[id] : OverlayService.PRE_NONE;
                Prefs.putStr(MainActivity.this, Prefs.KEY_PRECONNECT, key);
                restartService();
            }
        }));

        // ---------------- 开机自启 ----------------
        LinearLayout boot = card(root, "开机自启");
        Switch bootSw = new Switch(this);
        styleSwitch(bootSw, "开机后自动启动悬浮窗");
        bootSw.setChecked(Prefs.getBool(this, Prefs.KEY_BOOT, true));
        bootSw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                Prefs.putBool(MainActivity.this, Prefs.KEY_BOOT, checked);
            }
        });
        boot.addView(bootSw);
        note(boot, "Android 8+ 在开机广播里必须用 startForegroundService，用 startService 会被静默拒绝 —— 已修。");

        // ---------------- 自检 ----------------
        LinearLayout dg = card(root, "原始数据自检");
        note(dg, "直接按属性 id 读一次，用来确认 SOA 通了、以及各下标含义。");
        dg.addView(ghost("读取一次", new View.OnClickListener() {
            @Override public void onClick(View v) {
                diag.setText("读取中…");
                new Thread(new Runnable() {
                    @Override public void run() { runDiagnostics(); }
                }, "q07-diag").start();
            }
        }));
        diag = new TextView(this);
        diag.setTextSize(12f);
        diag.setTextColor(cSub);
        diag.setTypeface(Typeface.MONOSPACE);
        diag.setPadding(0, dp(10), 0, 0);
        diag.setLineSpacing(dp(3), 1f);
        dg.addView(diag);

        setContentView(scroll);

        // 实时数据刷新
        ui.post(new Runnable() {
            @Override public void run() {
                refreshLive();
                refresh();
                ui.postDelayed(this, 500L);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    // ---------------------------------------------------------------- 主题

    private boolean resolveDark() {
        String t = Prefs.getStr(this, Prefs.KEY_THEME, THEME_SYSTEM);
        if (THEME_DARK.equals(t)) return true;
        if (THEME_LIGHT.equals(t)) return false;
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private void applyPalette() {
        if (darkNow) {
            cBg = 0xFF0B0E13; cCard = 0xFF151A21; cBorder = 0xFF232C38;
            cText = 0xFFE8EDF3; cSub = 0xFF93A1B2; cAccent = 0xFF4C8DFF;
            cOk = 0xFF34D399; cOff = 0xFF6B7684; cRipple = 0x33FFFFFF;
        } else {
            cBg = 0xFFF3F5F9; cCard = 0xFFFFFFFF; cBorder = 0xFFE3E8EF;
            cText = 0xFF111827; cSub = 0xFF6B7280; cAccent = 0xFF2563EB;
            cOk = 0xFF059669; cOff = 0xFF9CA3AF; cRipple = 0x22000000;
        }
    }

    private ColorStateList tint() {
        return new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{-android.R.attr.state_checked}},
                new int[]{cAccent, cOff});
    }

    // ---------------------------------------------------------------- 样式工具

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable round(int fill, int stroke, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private LinearLayout card(LinearLayout parent, String titleText) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(cCard, cBorder, 16));
        int pad = dp(16);
        box.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        box.setLayoutParams(lp);

        TextView t = new TextView(this);
        t.setText(titleText);
        t.setTextSize(17f);
        t.setTypeface(null, Typeface.BOLD);
        t.setTextColor(cText);
        box.addView(t);

        parent.addView(box);
        return box;
    }

    private void note(LinearLayout parent, String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(13f);
        tv.setTextColor(cSub);
        tv.setLineSpacing(dp(3), 1f);
        tv.setPadding(0, dp(8), 0, dp(8));
        parent.addView(tv);
    }

    private void styleSwitch(Switch sw, String text) {
        sw.setText(text);
        sw.setTextSize(15f);
        sw.setTextColor(cText);
        sw.setPadding(0, dp(8), 0, dp(8));
        sw.setThumbTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{cAccent, cOff}));
        sw.setTrackTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{darkNow ? 0x664C8DFF : 0x552563EB, darkNow ? 0x33000000 : 0x22000000}));
    }

    private LinearLayout hrow(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(12), 0, 0);
        parent.addView(r);
        return r;
    }

    private Button mkButton(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15f);
        b.setTypeface(null, Typeface.BOLD);
        b.setTextColor(primary ? Color.WHITE : cAccent);
        b.setBackground(new RippleDrawable(
                ColorStateList.valueOf(cRipple),
                round(primary ? cAccent : Color.TRANSPARENT,
                        primary ? 0 : (darkNow ? 0x664C8DFF : 0x552563EB), 22),
                null));
        b.setPadding(dp(18), dp(10), dp(18), dp(10));
        b.setStateListAnimator(null);
        return b;
    }

    private Button primary(String text, View.OnClickListener l) {
        Button b = mkButton(text, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private Button ghost(String text, View.OnClickListener l) {
        Button b = mkButton(text, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private void addWide(LinearLayout row, View v, float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        lp.rightMargin = dp(8);
        v.setLayoutParams(lp);
        row.addView(v);
    }

    // ---------------------------------------------------------------- 行为

    private void startServiceCompat() {
        Intent i = new Intent(this, OverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
    }

    private void restartService() {
        stopService(new Intent(this, OverlayService.class));
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                try { startServiceCompat(); } catch (Throwable t) { Log.e(TAG, "重启服务失败: " + t); }
                ui.postDelayed(new Runnable() {
                    @Override public void run() { refresh(); }
                }, 1500L);
            }
        }, 700L);
    }

    private void refresh() {
        boolean overlay = false;
        try { overlay = Settings.canDrawOverlays(this); } catch (Throwable ignored) {}
        boolean running = OverlayService.running;
        boolean edit = Prefs.getBool(this, Prefs.KEY_EDIT, false);
        status.setText("悬浮窗服务    " + (running ? "● 运行中" : "○ 已停止")
                + "\n悬浮窗权限    " + (overlay ? "● 已授予" : "● 未授予（不授予面板不显示）")
                + "\n编辑模式      " + (edit ? "● 开" : "○ 关")
                + "\n包名          " + getPackageName());
    }

    /** 把服务里的实时数据快照画成两列表格。 */
    private void refreshLive() {
        String[] rows = OverlayService.liveRows;
        boolean ok = OverlayService.liveOk;
        if (!ok || rows == null || rows.length == 0) {
            liveHint.setText("暂无数据 —— 悬浮窗服务未运行，或本机不是车机（读不到 SOA）。");
            liveBox.removeAllViews();
            liveKey = "";
            return;
        }
        liveHint.setText("来自厂商 SOA（每 0.4 秒刷新）");
        StringBuilder key = new StringBuilder();
        for (String r : rows) key.append(r).append(';');
        if (key.toString().equals(liveKey)) return;   // 没变就不重建视图
        liveKey = key.toString();

        liveBox.removeAllViews();
        for (String r : rows) {
            int i = r.indexOf('|');
            if (i < 0) continue;
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setPadding(0, dp(5), 0, dp(5));

            TextView k = new TextView(this);
            k.setText(r.substring(0, i));
            k.setTextSize(14f);
            k.setTextColor(cSub);
            k.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            line.addView(k);

            TextView v = new TextView(this);
            v.setText(r.substring(i + 1));
            v.setTextSize(15f);
            v.setTypeface(null, Typeface.BOLD);
            v.setTextColor(cAccent);
            line.addView(v);

            liveBox.addView(line);
        }
    }

    // ---------------------------------------------------------------- SOA 自检

    private void diag(final String s) {
        Log.i(TAG, s);
        ui.post(new Runnable() {
            @Override public void run() { diag.append(s + "\n"); }
        });
    }

    private void runDiagnostics() {
        Object[][] want = {
                {0x00418037, 0, "挡位"},
                {0x00418006, 0, "车速"},
                {0x00419788, 25, "电量%"},
                {0x00419307, 12, "高压电压"},
                {0x00412055, 0, "剩余里程"},
                {0x00501000, 0, "总里程"},
                {0x00415244, 0, "左转向"},
                {0x00415244, 1, "右转向"},
        };
        try {
            SoaClient soa = new SoaClient();
            if (!soa.init(this)) { diag("SOA 初始化失败（本机不是车机，正常）"); return; }
            for (Object[] w : want) {
                int id = (Integer) w[0];
                int idx = (Integer) w[1];
                int[] a = soa.read(id);
                String v = (a == null) ? "null"
                        : (idx < a.length ? String.valueOf(a[idx]) : Arrays.toString(a));
                diag(String.format("0x%08X %-10s = %s", id, w[2], v));
            }
        } catch (Throwable t) {
            diag("自检异常: " + t);
        }
    }
}
