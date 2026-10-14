package com.lushu.q07car;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 厂商 SOA 访问封装。
 *
 * 厂商 HIDL Java 类（assets/soa*.dex，从 /system/app/SoaBridgeTransit 抽出）
 * 在运行时释放到 filesDir，用 DexClassLoader 加载，然后走厂商自己的链路：
 *
 *   ISoaBridgeNative.getService()
 *     -> new SoaBridgeServiceManagement.SoaBridgeNativeClient(iface)
 *       -> getProperty(id) -> SoaClientProperty.getPropertyValue()
 *
 * 实测普通 App（非 root、非 system）即可使用。
 */
public class SoaClient {

    private static final String TAG = "Q07Soa";

    private Object client;
    private Method mGetProperty;
    private Method mGetValue;
    private ClassLoader loader;

    public boolean init(Context ctx) {
        try {
            File dir = new File(ctx.getFilesDir(), "vendor");
            if (!dir.exists()) dir.mkdirs();

            List<String> paths = new ArrayList<>();
            String[] names = ctx.getAssets().list("");
            if (names != null) {
                for (String n : names) {
                    if (!n.endsWith(".dex")) continue;
                    File f = new File(dir, n);
                    if (!f.exists() || f.length() == 0) {
                        InputStream in = ctx.getAssets().open(n);
                        OutputStream out = new FileOutputStream(f);
                        byte[] buf = new byte[1 << 16];
                        int r;
                        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                        out.close();
                        in.close();
                    }
                    paths.add(f.getAbsolutePath());
                }
            }
            if (paths.isEmpty()) {
                Log.e(TAG, "assets 里没有厂商 dex");
                return false;
            }

            StringBuilder sb = new StringBuilder();
            for (String p : paths) {
                if (sb.length() > 0) sb.append(File.pathSeparator);
                sb.append(p);
            }

            loader = new dalvik.system.DexClassLoader(
                    sb.toString(), ctx.getCodeCacheDir().getAbsolutePath(), null, SoaClient.class.getClassLoader());

            Class<?> iface = Class.forName(
                    "android.hardware.automotive.soabridge.V1_0.ISoaBridgeNative", false, loader);
            Object svc = iface.getMethod("getService").invoke(null);
            if (svc == null) {
                Log.e(TAG, "ISoaBridgeNative.getService() 返回 null");
                return false;
            }

            Class<?> clientCls = Class.forName(
                    "com.incall.soabridge.impl.SoaBridgeServiceManagement.SoaBridgeNativeClient", false, loader);
            client = clientCls.getConstructor(iface).newInstance(svc);
            mGetProperty = clientCls.getMethod("getProperty", int.class);
            mGetValue = Class.forName("com.incall.soabridge.bean.SoaClientProperty", false, loader)
                    .getMethod("getPropertyValue");
            Log.i(TAG, "SOA 就绪，dex 数=" + paths.size());
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "init 失败: " + t, t);
            return false;
        }
    }

    /** 读一个属性；读不到返回 null。 */
    public int[] read(int id) {
        if (client == null) return null;
        try {
            Object prop = mGetProperty.invoke(client, id);
            if (prop == null) return null;
            Object v = mGetValue.invoke(prop);
            if (v == null) return null;
            if (v instanceof int[]) return (int[]) v;
            if (v instanceof Object[]) {
                Object[] a = (Object[]) v;
                int[] r = new int[a.length];
                for (int i = 0; i < a.length; i++) {
                    r[i] = a[i] instanceof Number ? ((Number) a[i]).intValue() : 0;
                }
                return r;
            }
            if (v instanceof Number) return new int[]{((Number) v).intValue()};
        } catch (Throwable ignored) {
        }
        return null;
    }

    public int readInt(int id, int index) {
        int[] a = read(id);
        return (a != null && index < a.length) ? a[index] : 0;
    }
}
