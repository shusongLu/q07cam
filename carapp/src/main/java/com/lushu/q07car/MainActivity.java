package com.lushu.q07car;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * 启动界面：显示运行状态、申请悬浮窗权限、启动/停止悬浮窗服务。
 * 底部还有一段 SOA 自检，方便现场排查。
 */
public class MainActivity extends Activity {

    private static final String TAG = "Q07Car";

    private TextView status;
    private TextView diag;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(24);
        root.setPadding(p, p, p, p);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Q07 实时数据");
        title.setTextSize(24f);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(15f);
        status.setPadding(0, dp(12), 0, dp(12));
        root.addView(status);

        root.addView(button("启动悬浮窗", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startService(new Intent(MainActivity.this, OverlayService.class));
                refresh();
            }
        }));

        root.addView(button("停止悬浮窗", new View.OnClickListener() {
            @Override public void onClick(View v) {
                stopService(new Intent(MainActivity.this, OverlayService.class));
                refresh();
            }
        }));

        root.addView(button("授予「显示在其他应用上层」", new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Throwable t) {
                    Log.e(TAG, "打开悬浮窗设置页失败: " + t);
                }
            }
        }));

        diag = new TextView(this);
        diag.setTextSize(13f);
        diag.setPadding(0, dp(16), 0, 0);
        root.addView(diag);

        setContentView(scroll);

        refresh();
        new Thread(new Runnable() {
            @Override public void run() { runDiagnostics(); }
        }, "q07-diag").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void refresh() {
        boolean overlay = false;
        try { overlay = Settings.canDrawOverlays(this); } catch (Throwable ignored) {}
        status.setText("悬浮窗服务: " + (OverlayService.running ? "运行中" : "已停止")
                + "\n悬浮窗权限: " + (overlay ? "已授予" : "未授予（必须授予，否则面板不显示）")
                + "\n包名: " + getPackageName());
    }

    // ---------------------------------------------------------------- SOA 自检

    private void diag(String s) {
        Log.i(TAG, s);
        final String line = s;
        ui.post(new Runnable() {
            @Override public void run() { diag.append(line + "\n"); }
        });
    }

    private void runDiagnostics() {
        Object[][] want = {
                {0x00418037, 0, "挡位"},
                {0x00418006, 0, "车速(0.1km/h)"},
                {0x00419788, 25, "电量%"},
                {0x00419307, 12, "高压电压"},
                {0x00412055, 0, "剩余里程"},
                {0x00501000, 0, "总里程"},
                {0x00415244, 0, "左转向"},
                {0x00415244, 1, "右转向"},
        };
        try {
            SoaClient soa = new SoaClient();
            diag("=== SOA 自检 ===");
            if (!soa.init(this)) { diag("SOA 初始化失败"); return; }
            for (Object[] w : want) {
                int id = (Integer) w[0];
                int idx = (Integer) w[1];
                int[] a = soa.read(id);
                String v = (a == null) ? "null" : (idx < a.length ? String.valueOf(a[idx]) : Arrays.toString(a));
                diag(String.format("  0x%08X %-14s = %s", id, w[2], v));
            }
            diag("=== 完成 ===");
        } catch (Throwable t) {
            diag("自检异常: " + t);
        }
    }
}
