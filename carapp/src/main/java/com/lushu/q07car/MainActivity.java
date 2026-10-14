package com.lushu.q07car;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
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
 * 配置界面：顶部 Tab 切换（数据 / 悬浮窗 / 摄像头 / 设置）。
 */
public class MainActivity extends Activity {

    private static final String TAG = "Q07Car";

    private static final String THEME_DARK = "dark";
    private static final String THEME_LIGHT = "light";
    private static final String THEME_SYSTEM = "system";

    private int cBg, cCard, cBorder, cText, cSub, cAccent, cOff, cRipple, cTabBg;

    private TextView status;
    private TextView diag;
    private LinearLayout liveBox;
    private TextView liveHint;
    private String liveKey = "";
    private final Handler ui = new Handler(Looper.getMainLooper());

    private boolean darkNow;

    // Tab
    private static final String[] TABS = {"数据", "悬浮窗", "摄像头", "设置"};
    private final LinearLayout[] pages = new LinearLayout[TABS.length];
    private final TextView[] tabViews = new TextView[TABS.length];
    private int curTab = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        darkNow = resolveDark();
        setTheme(darkNow
                ? android.R.style.Theme_Material_NoActionBar
                : android.R.style.Theme_Material_Light_NoActionBar);
        applyPalette();
        super.onCreate(savedInstanceState);

        LinearLayout rootAll = new LinearLayout(this);
        rootAll.setOrientation(LinearLayout.VERTICAL);
        rootAll.setBackgroundColor(cBg);

        // ---------------- 头部 ----------------
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(18), dp(24), dp(18), dp(10));
        TextView title = new TextView(this);
        title.setText("Q07 实时数据");
        title.setTextSize(26f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(cText);
        head.addView(title);
        rootAll.addView(head);

        // ---------------- Tab 栏 ----------------
        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackground(round(cTabBg, cBorder, 14));
        LinearLayout.LayoutParams tblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tblp.leftMargin = dp(18);
        tblp.rightMargin = dp(18);
        tabBar.setLayoutParams(tblp);
        tabBar.setPadding(dp(4), dp(4), dp(4), dp(4));
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(TABS[i]);
            t.setTextSize(15f);
            t.setTypeface(null, Typeface.BOLD);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            t.setLayoutParams(lp);
            t.setPadding(0, dp(10), 0, dp(10));
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { switchTab(idx); }
            });
            tabBar.addView(t);
            tabViews[i] = t;
        }
        rootAll.addView(tabBar);

        // ---------------- 内容区 ----------------
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        rootAll.addView(content);

        for (int i = 0; i < TABS.length; i++) {
            ScrollView sc = new ScrollView(this);
            LinearLayout page = new LinearLayout(this);
            page.setOrientation(LinearLayout.VERTICAL);
            page.setPadding(dp(18), 0, dp(18), dp(28));
            sc.addView(page);
            sc.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
            content.addView(sc, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            sc.setTag(i);
            pages[i] = page;
            page.setTag(sc);
        }

        buildDataPage(pages[0]);
        buildOverlayPage(pages[1]);
        buildCameraPage(pages[2]);
        buildSettingsPage(pages[3]);

        setContentView(rootAll);
        switchTab(0);

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

    private void switchTab(int idx) {
        curTab = idx;
        for (int i = 0; i < TABS.length; i++) {
            View sc = (View) pages[i].getTag();
            sc.setVisibility(i == idx ? View.VISIBLE : View.GONE);
            boolean on = i == idx;
            tabViews[i].setTextColor(on ? Color.WHITE : cSub);
            tabViews[i].setBackground(on ? round(cAccent, 0, 11) : round(Color.TRANSPARENT, 0, 11));
        }
    }

    // ================================================================ 数据页

    private void buildDataPage(LinearLayout page) {
        LinearLayout live = card(page, "实时数据");
        liveHint = new TextView(this);
        liveHint.setTextSize(13f);
        liveHint.setTextColor(cSub);
        live.addView(liveHint);
        liveBox = new LinearLayout(this);
        liveBox.setOrientation(LinearLayout.VERTICAL);
        liveBox.setPadding(0, dp(6), 0, 0);
        live.addView(liveBox);

        LinearLayout dg = card(page, "原始数据自检");
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
    }

    // ================================================================ 悬浮窗页

    private void buildOverlayPage(LinearLayout page) {
        LinearLayout st = card(page, "运行状态");
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

        LinearLayout lay = card(page, "布局");
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
    }

    // ================================================================ 摄像头页

    private void buildCameraPage(LinearLayout page) {
        LinearLayout cam = card(page, "侧摄像头预连");
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
    }

    // ================================================================ 设置页

    private void buildSettingsPage(LinearLayout page) {
        // 外观
        LinearLayout theme = card(page, "外观");
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

        // 屏幕 / 副驾屏
        LinearLayout scr = card(page, "屏幕");
        note(scr, "点「在此屏打开」把这个界面投到那块屏上（车机副驾屏一般是第二块）。\n"
                + "若某块屏是私有的，普通 App 可能被拒绝 —— 车机上再验。");
        DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        Display[] displays = dm != null ? dm.getDisplays() : new Display[0];
        for (final Display d : displays) {
            String name = d.getName() + "  （id " + d.getDisplayId() + "）";
            boolean isDefault = d.getDisplayId() == Display.DEFAULT_DISPLAY;
            LinearLayout row = hrow(scr);
            TextView tv = new TextView(this);
            tv.setText(name + (isDefault ? "  [主屏]" : ""));
            tv.setTextSize(14f);
            tv.setTextColor(cText);
            addWide(row, tv, 1.4f);

            Button bOpen = mkButton("在此屏打开", false);
            bOpen.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { launchOnDisplay(d); }
            });
            addWide(row, bOpen, 1f);

            if (!isDefault) {
                boolean auxOn = Prefs.getInt(this, Prefs.KEY_AUX_DISPLAY, -1) == d.getDisplayId();
                Button bAux = mkButton(auxOn ? "悬浮窗 ✓" : "悬浮窗", auxOn);
                bAux.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        boolean on = Prefs.getInt(MainActivity.this, Prefs.KEY_AUX_DISPLAY, -1) != d.getDisplayId();
                        setAuxOverlay(d, on);
                    }
                });
                addWide(row, bAux, 1f);
            }
        }
        if (displays.length <= 1) {
            note(scr, "当前只检测到 1 块屏（平板上没接外接屏）。");
        }

        // 开机自启
        LinearLayout boot = card(page, "开机自启");
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

        // 关于
        LinearLayout about = card(page, "关于");
        String ver = "?";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
        }
        note(about, "包名  " + getPackageName()
                + "\n版本  " + ver
                + "\n说明  数据来自厂商 SOA；侧摄像头为 H.265 RTSP，自研播放器解码。");
    }

    private void launchOnDisplay(Display d) {
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            Bundle opts = ActivityOptions.makeBasic()
                    .setLaunchDisplayId(d.getDisplayId()).toBundle();
            startActivity(i, opts);
            Log.i(TAG, "已在 display " + d.getDisplayId() + " 打开");
        } catch (Throwable t) {
            Log.e(TAG, "在该屏打开失败: " + t);
            toast("该屏打不开：" + t.getClass().getSimpleName());
        }
    }

    /** 让悬浮窗面板也在该屏显示一份（副驾屏）。 */
    private void setAuxOverlay(Display d, boolean on) {
        Prefs.putInt(this, Prefs.KEY_AUX_DISPLAY, on ? d.getDisplayId() : -1);
        restartService();
        toast(on ? ("悬浮窗已设到 display " + d.getDisplayId()) : "副驾屏悬浮窗已关闭");
    }

    private void toast(final String s) {
        ui.post(new Runnable() {
            @Override public void run() {
                android.widget.Toast.makeText(MainActivity.this, s,
                        android.widget.Toast.LENGTH_LONG).show();
            }
        });
    }

    // ================================================================ 主题/样式

    private boolean resolveDark() {
        String t = Prefs.getStr(this, Prefs.KEY_THEME, THEME_SYSTEM);
        if (THEME_DARK.equals(t)) return true;
        if (THEME_LIGHT.equals(t)) return false;
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private void applyPalette() {
        if (darkNow) {
            cBg = 0xFF0B0E13; cCard = 0xFF151A21; cBorder = 0xFF232C38; cTabBg = 0xFF12171E;
            cText = 0xFFE8EDF3; cSub = 0xFF93A1B2; cAccent = 0xFF4C8DFF;
            cOff = 0xFF6B7684; cRipple = 0x33FFFFFF;
        } else {
            cBg = 0xFFF3F5F9; cCard = 0xFFFFFFFF; cBorder = 0xFFE3E8EF; cTabBg = 0xFFE9EDF4;
            cText = 0xFF111827; cSub = 0xFF6B7280; cAccent = 0xFF2563EB;
            cOff = 0xFF9CA3AF; cRipple = 0x22000000;
        }
    }

    private ColorStateList tint() {
        return new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{-android.R.attr.state_checked}},
                new int[]{cAccent, cOff});
    }

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
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(10), 0, 0);
        parent.addView(r);
        return r;
    }

    private Button mkButton(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14f);
        b.setTypeface(null, Typeface.BOLD);
        b.setTextColor(primary ? Color.WHITE : cAccent);
        b.setBackground(new RippleDrawable(
                ColorStateList.valueOf(cRipple),
                round(primary ? cAccent : Color.TRANSPARENT,
                        primary ? 0 : (darkNow ? 0x664C8DFF : 0x552563EB), 22),
                null));
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
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

    // ================================================================ 行为

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
        if (status != null) {
            status.setText("悬浮窗服务    " + (running ? "● 运行中" : "○ 已停止")
                    + "\n悬浮窗权限    " + (overlay ? "● 已授予" : "● 未授予（不授予面板不显示）")
                    + "\n编辑模式      " + (edit ? "● 开" : "○ 关")
                    + "\n包名          " + getPackageName());
        }
    }

    private void refreshLive() {
        if (liveBox == null) return;
        String[] rows = OverlayService.liveRows;
        boolean ok = OverlayService.liveOk;
        if (!ok || rows == null || rows.length == 0) {
            liveHint.setText("暂无数据 —— 悬浮窗服务未运行，或本机不是车机（读不到 SOA）。");
            if (liveBox.getChildCount() != 0) liveBox.removeAllViews();
            liveKey = "";
            return;
        }
        liveHint.setText("来自厂商 SOA（每 0.4 秒刷新）");
        StringBuilder key = new StringBuilder();
        for (String r : rows) key.append(r).append(';');
        if (key.toString().equals(liveKey)) return;
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

    // ================================================================ SOA 自检

    private void diag(final String s) {
        Log.i(TAG, s);
        ui.post(new Runnable() {
            @Override public void run() { if (diag != null) diag.append(s + "\n"); }
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
