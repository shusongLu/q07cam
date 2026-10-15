package com.lushu.q07car;

import android.content.Context;
import android.util.Log;

import com.incall.apps.casdkmanager.CaSdkManager;

/**
 * 长安官方 SDK（CaSdkManager）的接入试验。
 *
 * 只做一次性探测并把结果打到日志，不参与主流程 —— 目的是回答三个问题：
 *   1. 我们这个 App（正规安装、自有签名）能不能 bind 上车机的 com.incall.apps.sdkservice
 *   2. init() 要求 ca_dev_client_id / ca_dev_client_secret（先在 manifest 里放占位值）
 *   3. 车机上的 sdkservice 认不认那些能力名
 *
 * 能力名/服务名来自 CaSdkManager 字节码里的字符串常量（该 SDK 客户端是个通用 RPC 壳）。
 */
public class CaSdkTest {

    private static final String TAG = "Q07CaSdk";
    private static volatile boolean started = false;

    /**
     * SDK 会把"接口白名单"缓存在自己的 SharedPreferences（文件 "SDASDK"，键 "api_access_list"）。
     * 有缓存就走 use local api list（**不联网**），没有才去长安云端拉 ——
     * 而云端那条在开发者版上用的是占位域名 https://huappDomain，必然失败。
     *
     * 这份 prefs 就在我们 App 自己的数据目录里，所以可以直接写。
     * 内容 = 车机实际支持的 module × service 全组合（尽量宽松，别把自己拦了）。
     */
    private static final String[] MODULES = {
            "scene_brain_module", "virtual_vehicle", "vehicle_control",
            "event_module", "net_setting", "audio_module", "phone_service", "phone_setting_service",
            "business_forward", "gesture_module", "sub_state_module",
            "maintenance_info", "message_box_moudle", "wearth_module", "wisdom_id", "schedule_remind",
            "network_authorise", "music_light_component", "media_component", "cloud_service",
            "vehicle_cloud", "interactive_light", "app_manager_module", "easter_egg_module",
            "assist_service", "personalized_module", "app_module"
    };

    private static final String[] SERVICES = {
            "config_service", "vehicle_service", "vehicle_drive_service", "drive_service",
            "energy_recycler_service", "air_conditioner_service", "chair_service", "window_service",
            "door_service", "lamp_service", "charge_service", "extr_service", "wiper_service",
            "epb_service", "mileage_service", "environment_service", "car_body_hardware_service",
            "braking_system_service", "steering_service", "suspension_control_service",
            "aided_driving_service", "geek_space_service",
            "business_forward_service", "arrange_scene_service", "enjoy_co_driver_service",
            "vehicle_drive_scene_service", "vehicle_preset_scene_service",
            "maintenance_info_service", "meter_service", "event_service", "weather_module",
            "local_switch_service", "system_service", "net_authorise_service",
            "phone_service", "phone_setting_service", "music_rhythm_service",
            "app_manager_service", "app_store_service"
    };

    private static void seedLocalApiList(Context ctx) {
        try {
            // 先看看 App 数据目录里到底有哪些 prefs 文件（SDK 真正用的名字）
            try {
                java.io.File sp = new java.io.File(ctx.getDataDir(), "shared_prefs");
                String[] names = sp.list();
                Log.i(TAG, "[sdk] shared_prefs 目录: "
                        + (names == null ? "null" : java.util.Arrays.toString(names)));
            } catch (Throwable t) {
                Log.w(TAG, "[sdk] 列 shared_prefs 失败: " + t);
            }

            org.json.JSONArray arr = new org.json.JSONArray();
            for (String mod : MODULES) {
                for (String svc : SERVICES) {
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("moduleName", mod);
                    o.put("serviceName", svc);
                    arr.put(o);
                }
            }
            org.json.JSONObject data = new org.json.JSONObject();
            data.put("serviceList", arr);
            org.json.JSONObject root = new org.json.JSONObject();
            root.put("code", 200);
            root.put("msg", "local");
            root.put("data", data);
            // 必须用 commit() 同步写：apply() 是异步的，SDK 紧接着就读，会读到空值
            boolean okWrite = ctx.getSharedPreferences("SDASDK", Context.MODE_PRIVATE)
                    .edit().putString("api_access_list", root.toString()).commit();
            String back = ctx.getSharedPreferences("SDASDK", Context.MODE_PRIVATE)
                    .getString("api_access_list", "");
            Log.i(TAG, "[sdk] 已写入本地接口白名单: " + MODULES.length + " module × "
                    + SERVICES.length + " service = " + (MODULES.length * SERVICES.length)
                    + " 条  commit=" + okWrite + "  读回长度=" + (back == null ? -1 : back.length()));
        } catch (Throwable t) {
            Log.w(TAG, "[sdk] 写本地白名单失败: " + t);
        }
    }

    public static void runOnce(final Context ctx) {
        if (started) { return; }
        started = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    seedLocalApiList(ctx);

                    CaSdkManager m = CaSdkManager.getInstance();
                    Log.i(TAG, "[sdk] getInstance() ok，准备 init()");
                    m.init(ctx, true);
                    Log.i(TAG, "[sdk] init() 返回，haveInit=" + m.haveInit());
                    Thread.sleep(2500);
                    boolean ok = m.haveInit();
                    Log.i(TAG, "[sdk] 2.5 秒后 haveInit=" + ok);

                    if (!ok) {
                        Log.w(TAG, "[sdk] 还是没绑上 —— 看 SDASDK-SdkManagerImpl / SDASDK-APIManager 的日志");
                        return;
                    }

                    // ---- 用从车机日志里扒到的真实 模块/服务/方法 三元组 ----
                    // 1) 车辆数据：virtual_vehicle / vehicle_service / getVirtualVehicle {"pkeys":[...]}
                    probe(m, "virtual_vehicle", "vehicle_service", "getVirtualVehicle", null); // 空参先看默认
                    probeKeys(m, new String[]{                        // 已知有效，用来验证链路
                            "vehicle.drive.flTireState", "vehicle.drive.frTireState",
                            "vehicle.drive.lrTireState", "vehicle.drive.rrTireState"});
                    probeKeys(m, new String[]{                        // 空数组：看能不能全量返回
                    });
                    probeKeys(m, new String[]{                        // 猜几个我们要的键
                            "vehicle.drive.gearLevel", "vehicle.drive.vehicleSpeed",
                            "vehicle.drive.speed", "vehicle.drive.powerShift",
                            "vehicle.energy.soc", "vehicle.energyRecycle.tripEnergyConsume"});
                    // 2) 应用列表（跟安装/自启那两块痛点相关）
                    probe(m, "app_manager_module", "app_manager_service", "get_app_list", null);
                    // 3) 系统配置
                    probe(m, "net_setting", "system_service", "get_system_configuration_parameter", null);
                } catch (Throwable t) {
                    Log.e(TAG, "[sdk] 异常: " + t, t);
                }
            }
        }, "ca-sdk").start();
    }

    /** 用 pkeys 查车辆数据（车机自己的调用就是这么发的）。 */
    private static void probeKeys(CaSdkManager m, String[] keys) {
        java.util.Map<String, Object> p = new java.util.HashMap<>();
        p.put("pkeys", new java.util.ArrayList<>(java.util.Arrays.asList(keys)));
        probe(m, "virtual_vehicle", "vehicle_service", "getVirtualVehicle", p);
    }

    private static void probe(CaSdkManager m, String cap, String svc, String method,
                              java.util.Map<String, Object> params) {
        try {
            Object r = m.callServiceSync(cap, svc, method,
                    params != null ? params : new java.util.HashMap<String, Object>());
            Log.i(TAG, "[sdk] " + cap + "/" + svc + "/" + method + " -> " + r);
        } catch (Throwable t) {
            Log.w(TAG, "[sdk] " + cap + "/" + svc + "/" + method + " 失败: " + t);
        }
    }
}
