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
    private Class<?> propCls;
    private Class<?> clientCls;
    private boolean pushEnabled;

    /** 推送回调：属性 id + 新值。 */
    public interface OnProp {
        void onProp(int id, int[] value);
    }

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

            Class<?> clientCls2 = Class.forName(
                    "com.incall.soabridge.impl.SoaBridgeServiceManagement.SoaBridgeNativeClient", false, loader);
            client = clientCls2.getConstructor(iface).newInstance(svc);
            clientCls = clientCls2;
            mGetProperty = clientCls2.getMethod("getProperty", int.class);
            propCls = Class.forName("com.incall.soabridge.bean.SoaClientProperty", false, loader);
            mGetValue = propCls.getMethod("getPropertyValue");
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

    // ---------------------------------------------------------------- 订阅（推送）

    /**
     * 打开推送：注册 PropertyHalListener（用 Proxy 实现，方法签名不写死），
     * 之后 subscribe(id) 的属性有变化时服务端会回调，不必轮询。
     */
    public boolean enablePush(final OnProp cb) {
        try {
            Class<?> listenerCls = Class.forName(
                    "com.incall.soabridge.impl.SoaBridgeServiceManagement.SoaBridgeNativeClient$PropertyHalListener",
                    false, loader);
            StringBuilder sig = new StringBuilder();
            for (java.lang.reflect.Method m : listenerCls.getMethods()) {
                sig.append(m.getName()).append('(');
                for (Class<?> p : m.getParameterTypes()) sig.append(p.getSimpleName()).append(',');
                sig.append(") ");
            }
            Log.i(TAG, "监听器方法: " + sig);

            Object listener = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{listenerCls},
                    new java.lang.reflect.InvocationHandler() {
                        private int n = 0;
                        @Override public Object invoke(Object proxy, Method method, Object[] args) {
                            try {
                                if (n < 20) {
                                    n++;
                                    StringBuilder sb = new StringBuilder("回调 " + method.getName() + "(");
                                    if (args != null) {
                                        for (Object a : args) {
                                            sb.append(a == null ? "null" : a.getClass().getSimpleName()).append(',');
                                        }
                                    }
                                    sb.append(')');
                                    Log.i(TAG, sb.toString());
                                }
                                if (args != null) {
                                    for (Object a : args) dispatch(a, cb);
                                }
                            } catch (Throwable ignored) {
                            }
                            return null;
                        }
                    });
            clientCls.getMethod("setListener", listenerCls).invoke(client, listener);
            pushEnabled = true;
            Log.i(TAG, "推送已开启（PropertyHalListener 已注册）");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "开启推送失败，继续用轮询: " + t);
            return false;
        }
    }

    public boolean subscribe(int id) {
        if (!pushEnabled || client == null) return false;
        try {
            clientCls.getMethod("subscribeProperty", int.class).invoke(client, id);
            Log.i(TAG, "订阅 0x" + Integer.toHexString(id).toUpperCase());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "订阅 0x" + Integer.toHexString(id) + " 失败: " + t);
            return false;
        }
    }

    public void unsubscribe(int id) {
        if (!pushEnabled || client == null) return;
        try { clientCls.getMethod("unSubscribeProperty", int.class).invoke(client, id); } catch (Throwable ignored) {}
    }

    /** HAL 回调的入参可能是 SoaClientProperty，也可能是它的 List —— 都拆开处理。 */
    private void dispatch(Object o, OnProp cb) {
        if (o == null || cb == null) return;
        if (o instanceof java.util.List) {
            for (Object e : (java.util.List<?>) o) dispatch(e, cb);
            return;
        }
        try {
            if (propCls != null && propCls.isInstance(o)) {
                int id = (Integer) propCls.getMethod("getPropertyId").invoke(o);
                int[] v = toInts(mGetValue.invoke(o));
                if (v != null) cb.onProp(id, v);
            }
        } catch (Throwable ignored) {
        }
    }

    private static int[] toInts(Object v) {
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
        return null;
    }
}
