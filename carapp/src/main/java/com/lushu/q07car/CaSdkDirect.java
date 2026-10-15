package com.lushu.q07car;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;

import com.incall.apps.sdkservice.ICaSdkService;
import com.incall.apps.sdkservice.aidl.bean.SdkDataBean;

/**
 * 直接 bind 车机上的 com.incall.apps.sdkservice，绕开官方 SDK 客户端的"接口白名单"。
 *
 * 背景：CaSdkManager 能把服务绑上（haveInit()=true），但每次 callServiceSync 都被它自己
 * 的 APIManager 拦下（"不支持模块，请在长安开发云开通相关API"）。那份名单在**客户端**，
 * 服务端不查。所以自己拿 AIDL 桩直连即可 —— AIDL 由官方 AAR 提供：
 *   ICaSdkService.callServiceSync(module, service, method, paramsJson, extra) -> SdkDataBean
 *
 * 服务地址从车机返回的配置里读到：
 *   action = com.incall.app.start.CaSdkService   package = com.incall.apps.sdkservice
 */
public class CaSdkDirect {

    /**
     * 默认关闭。
     *
     * 实测结论（2026-10-16）：自己 bind + AIDL 直连**完全打通**了 ——
     *   [direct] bindService -> true
     *   [direct] 已连接: ComponentInfo{com.incall.apps.sdkservice/...CaSdkService}
     *   [direct] 调用 -> {"code":-1001,"codeMsg":"app未授权, 请联系SDASdkService添加白名单"}
     *
     * 也就是说：卡点不在客户端拦截（那个已经绕过去了），而在**服务端按包名白名单授权** ——
     * 日志里能成功调用的只有 com.incall.primateservice、com.incall.SoaBridgeTransit 这类
     * 厂商自家应用。「请联系 SDASdkService 添加白名单」= 只有厂商能加。
     *
     * 加上云端还要"申请 API 列表"，两道门都不对外开放 —— 所以这条路暂时封死。
     * 我们的 HIDL 直读方案信号更全（转速这种官方 SDK 根本没有），不受影响。
     *
     * 将来若能拿到厂商白名单 / 平台授权，改成 true 即可启用。
     */
    public static final boolean ENABLED = false;

    private static final String TAG = "Q07CaSdk";
    private static volatile ICaSdkService svc;
    private static volatile boolean started = false;

    public static void runOnce(Context ctx) {
        if (started) { return; }
        started = true;
        if (!ENABLED) { return; }
        try {
            Intent it = new Intent("com.incall.app.start.CaSdkService");
            it.setPackage("com.incall.apps.sdkservice");
            boolean ok = ctx.getApplicationContext().bindService(it, conn, Context.BIND_AUTO_CREATE);
            Log.i(TAG, "[direct] bindService -> " + ok);
        } catch (Throwable t) {
            Log.e(TAG, "[direct] bindService 异常: " + t, t);
        }
    }

    private static final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            Log.i(TAG, "[direct] 已连接: " + n);
            try {
                svc = ICaSdkService.Stub.asInterface(b);
                Log.i(TAG, "[direct] 接口拿到 = " + (svc != null));
                probes();
            } catch (Throwable t) {
                Log.e(TAG, "[direct] asInterface 失败: " + t, t);
            }
        }
        @Override public void onServiceDisconnected(ComponentName n) {
            Log.w(TAG, "[direct] 断开: " + n);
            svc = null;
        }
    };

    private static void probes() {
        ICaSdkService s = svc;
        if (s == null) { Log.w(TAG, "[direct] 服务未就绪"); return; }

        // 等一下官方 SDK 那边 init 完成（它的 get_config_info 是能过的），再试直连
        try { Thread.sleep(8000); } catch (InterruptedException e) { return; }

        // AIDL 里有个 setTestEnv —— 官方 SDK 大概率会调它来"开测试环境"，
        // 我们直连少了这一步就被判 "app未授权, 请联系SDASdkService添加白名单"
        for (boolean v : new boolean[]{true, false}) {
            try {
                s.setTestEnv(v);
                Log.i(TAG, "[direct] setTestEnv(" + v + ") 调用成功");
            } catch (Throwable t) {
                Log.w(TAG, "[direct] setTestEnv(" + v + ") 失败: " + t);
            }
        }
        // 先试一个最小的调用，看授权状态变没变
        call("app_module", "config_service", "get_config_info", "");

        // 车机自己的调用长这样：S1[virtual_vehicle, vehicle_service, getVirtualVehicle, {pkeys:[...]}]
        // 官方文档的服务名是两段式：vehicle_service.drive / .light / .energyRecycle
        call("virtual_vehicle", "vehicle_service.drive", "getVirtualVehicle",
                "{\"pkeys\":[\"vehicle.drive.flTireState\",\"vehicle.drive.frTireState\"]}");
        call("virtual_vehicle", "vehicle_service.drive", "getVirtualVehicle", "{\"pkeys\":[]}");
        call("virtual_vehicle", "vehicle_service.light", "getVirtualVehicle",
                "{\"pkeys\":[\"vehicle.light.turnLight\"]}");
        call("virtual_vehicle", "vehicle_service.energyRecycle", "getVirtualVehicle",
                "{\"pkeys\":[\"vehicle.energyRecycle.tripEnergyConsume\"]}");
        call("app_manager_module", "app_manager_service", "get_app_list", "");
    }

    private static void call(String module, String service, String method, String paramsJson) {
        ICaSdkService s = svc;
        if (s == null) { Log.w(TAG, "[direct] 服务未就绪"); return; }
        // 第 5 个参数含义未知：空串 / 我们包名 / null 都试
        String[] tries = { "", "de.dsa.ford.otaflash", null };
        String last = null;
        for (String fifth : tries) {
            try {
                SdkDataBean bean = s.callServiceSync(module, service, method, paramsJson, fifth);
                String out = (bean == null) ? "null"
                        : ("data=" + bean.getStringData() + "  obj=" + bean.getDataObject());
                Log.i(TAG, "[direct] " + module + "/" + service + "/" + method
                        + "  fifth=" + (fifth == null ? "null" : "\"" + fifth + "\"")
                        + "  -> " + out);
                if (bean != null) {
                    String d = bean.getStringData();
                    if (d != null && !d.contains("-1001")) { return; }   // 真的拿到数据了
                    last = d;
                }
            } catch (Throwable t) {
                Log.w(TAG, "[direct] " + module + "/" + service + "/" + method
                        + "  fifth=" + (fifth == null ? "null" : "\"" + fifth + "\"")
                        + " 失败: " + t);
            }
        }
        if (last != null) { Log.w(TAG, "[direct] " + module + "/" + service + " 全部被拒: " + last); }
    }
}
