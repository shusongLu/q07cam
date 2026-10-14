package com.lushu.soatest;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * 验证：普通安装的 App 能不能读厂商 SOA。
 *
 * 1) 反射 android.os.HwBinder.getService(...) 看 hwservicemanager 是否放行
 * 2) 把 assets/soa4.dex（厂商 HIDL Java 类）解出来，用 DexClassLoader 加载，
 *    走正规链路 ISoaBridgeNative.getService() -> SoaBridgeNativeClient.getProperty(id)
 */
public class MainActivity extends Activity {

    private static final String TAG = "SOATEST";
    private TextView out;

    /** 想读的几个 id（来自 recon/soa-signal-map.md）：{id, 下标, 名字}。 */
    private static final Object[][] WANT = {
            {0x00418037, 0, "挡位 GearPosnInfo"},
            {0x00418006, 0, "车速 VehSpd"},
            {0x00419788, 25, "电量 PtLamp[25]"},
            {0x00419307, 12, "高压电压 BCU[12]"},
            {0x00412055, 0, "剩余里程 ResiMilg"},
            {0x00501000, 0, "总里程 TotalMilg"},
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView sv = new ScrollView(this);
        out = new TextView(this);
        out.setTextSize(16f);
        out.setPadding(32, 32, 32, 32);
        sv.addView(out);
        setContentView(sv);

        new Thread(new Runnable() {
            @Override public void run() { runTest(); }
        }, "soatest").start();
    }

    private void log(final String s) {
        Log.i(TAG, s);
        runOnUiThread(new Runnable() {
            @Override public void run() { out.append(s + "\n"); }
        });
    }

    private void runTest() {
        log("=== SOA 可达性测试 ===");
        log("包名: " + getPackageName() + "   uid=" + android.os.Process.myUid());

        // ---- 1) hwservicemanager 能不能拿到 binder ----
        try {
            Class<?> hw = Class.forName("android.os.HwBinder");
            Method m = null;
            for (Method mm : hw.getMethods()) {
                if (mm.getName().equals("getService") && mm.getParameterTypes().length == 3) { m = mm; break; }
            }
            if (m == null) {
                log("[1] HwBinder.getService(3 参) 没找到");
            } else {
                Object b = m.invoke(null, "android.hardware.automotive.soabridge", "ISoaBridgeNative", "default");
                log("[1] HwBinder.getService -> " + (b == null ? "null（被拒/不存在）" : b));
            }
        } catch (Throwable t) {
            log("[1] HwBinder 反射失败: " + t);
        }

        // ---- 2) 厂商链路 ----
        ClassLoader loader;
        try {
            StringBuilder paths = new StringBuilder();
            String[] names = getAssets().list("");
            int cnt = 0;
            for (String n : names) {
                if (!n.endsWith(".dex")) continue;
                File dex = new File(getFilesDir(), n);
                if (!dex.exists() || dex.length() == 0) {
                    InputStream in = getAssets().open(n);
                    OutputStream fo = new FileOutputStream(dex);
                    byte[] buf = new byte[1 << 16];
                    int r;
                    while ((r = in.read(buf)) > 0) fo.write(buf, 0, r);
                    fo.close();
                    in.close();
                }
                if (paths.length() > 0) paths.append(File.pathSeparator);
                paths.append(dex.getAbsolutePath());
                cnt++;
            }
            log("[2] 释放 " + cnt + " 个厂商 dex");
            loader = new dalvik.system.DexClassLoader(
                    paths.toString(), getCodeCacheDir().getAbsolutePath(), null, getClassLoader());
            log("[2] DexClassLoader ok");
        } catch (Throwable t) {
            log("[2] 加载 dex 失败: " + t);
            return;
        }

        Object soaClient = null;
        try {
            Class<?> iface = Class.forName("android.hardware.automotive.soabridge.V1_0.ISoaBridgeNative", false, loader);
            log("[3] ISoaBridgeNative 类已加载");
            Object svc = iface.getMethod("getService").invoke(null);
            log("[3] ISoaBridgeNative.getService() -> " + (svc == null ? "null" : svc));
            if (svc == null) { log("[3] 拿不到 SOA 服务，停止"); return; }

            Class<?> clientCls = Class.forName(
                    "com.incall.soabridge.impl.SoaBridgeServiceManagement.SoaBridgeNativeClient", false, loader);
            soaClient = clientCls.getConstructor(iface).newInstance(svc);
            log("[4] SoaBridgeNativeClient 构造成功");
        } catch (Throwable t) {
            log("[3/4] 失败: " + t);
            return;
        }

        try {
            Class<?> clientCls = soaClient.getClass();
            Method getProperty = clientCls.getMethod("getProperty", int.class);
            Class<?> propCls = Class.forName("com.incall.soabridge.bean.SoaClientProperty", false, loader);
            Method getValue = propCls.getMethod("getPropertyValue");

            log("=== 读值 ===");
            for (Object[] w : WANT) {
                int id = (Integer) w[0];
                int idx = (Integer) w[1];
                String name = (String) w[2];
                try {
                    Object p = getProperty.invoke(soaClient, id);
                    if (p == null) { log(String.format("  0x%08X %-18s = null", id, name)); continue; }
                    Object v = getValue.invoke(p);
                    String s;
                    if (v instanceof int[]) s = Arrays.toString((int[]) v);
                    else if (v instanceof Object[]) s = Arrays.deepToString((Object[]) v);
                    else s = String.valueOf(v);
                    if (idx > 0 && v instanceof Object[]) {
                        Object[] a = (Object[]) v;
                        s = (idx < a.length) ? String.valueOf(a[idx]) : ("len=" + a.length);
                    }
                    log(String.format("  0x%08X %-18s = %s", id, name, s));
                } catch (Throwable t) {
                    log(String.format("  0x%08X %-18s = ERR %s", id, name, t));
                }
            }
            log("=== 完成 ===");
        } catch (Throwable t) {
            log("[5] 读值失败: " + t);
        }
    }
}
