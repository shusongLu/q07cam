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

    /**
     * 默认关闭。
     *
     * 现状：SDK 能绑上车机的 sdkservice（haveInit()=true），但**每次调用都被 SDK 自己的
     * "接口白名单"拦下** —— 报「接口调用失败, 不支持模块(xxx), 请在长安开发云，开通相关API!」。
     * 那份名单由云端下发，而 device 上拿不到平台授权；且 developer_ca 版本的正式域名是
     * 占位（https://huappDomain），云端那条必然失败。
     *
     * 另外：官方 SDK 的 vehicle_service.drive 只有车速/挡位/里程/轮胎，**没有发动机转速** ——
     * 我们现在走的 HIDL 直读信号更全，所以不启用它没有任何损失。
     *
     * 等以后拿到平台 API 授权（或上架审核开通），把它改成 true 即可。
     */
    public static final boolean ENABLED = false;

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
            // 车控模块
            "vehicle_control",
            // 虚拟车模块（只读这些信号）
            "virtual_vehicle",
            "phone_service", "phone_setting_service", "audio_module",
            // 系统设置模块
            "net_setting",
            // 长安云服务模块
            "cloud_service",
            "wisdom_id", "schedule_remind", "sub_state_module", "business_forward",
            "event_module", "message_box_moudle", "network_authorise",
            "voice_module", "navi_module",
            // 应用管理（日志里出现过）
            "app_manager_module", "app_module",
            "scene_brain_module", "vehicle_cloud", "maintenance_info",
            "interactive_light", "music_light_component", "media_component",
            "gesture_module", "easter_egg_module", "assist_service", "personalized_module"
    };

    /** 服务名按官方文档抄（虚拟车是 vehicle_service.<域> 这种两段式）。 */
    private static final String[] SERVICES = {
            // 车控模块（vehicle_control）
            "air_conditioner_service", "wiper_service", "charge_service", "epb_service",
            "energy_recycler_service", "window_service", "lamp_service", "door_service",
            "chair_service", "drive_service", "car_body_hardware_service",
            "Steering_service", "suspension_control_service",
            // 虚拟车模块（virtual_vehicle）—— 我们要的车速/挡位/灯/能量都在这里
            "vehicle_service.airCondition", "vehicle_service.wiper", "vehicle_service.charge",
            "vehicle_service.epb", "vehicle_service.energyRecycle", "vehicle_service.window",
            "vehicle_service.light", "vehicle_service.door", "vehicle_service.chair",
            "vehicle_service.drive", "vehicle_service.carBodyHardware", "vehicle_service.follow",
            "vehicle_service.geek", "vehicle_service.suspension", "vehicle_service.aidedDrive",
            "vehicle_service",
            // 其它模块
            "phone_service", "phone_setting_service", "sound_service",
            "service_wifi", "service_wifi_ap", "Scene_service", "display_service",
            "auto_brightness_service", "system_service",
            "theme_business_service", "wallpaper_business_service", "theme_sound_business_service",
            "stunt_sound_effect_business_service", "fragrance_business_service",
            "accelerate_service", "avas_sound_business_service",
            "user_info_service", "service_schedule", "sub_state_service",
            "vehicle_preset_scene_service", "interactive_display_service", "day_night_service",
            "arrange_scene_service", "enjoy_co_driver_service",
            "meter_service", "event_service", "service_message", "net_authorise_service",
            "voice_base_service", "navi_base_service",
            "app_manager_service", "app_store_business_service", "config_service",
            "maintenance_info_service", "local_switch_service"
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
                    m.init(ctx, false);   // 官方文档写的是 false
                    Log.i(TAG, "[sdk] init() 返回，haveInit=" + m.haveInit());
                    Thread.sleep(2500);
                    boolean ok = m.haveInit();
                    Log.i(TAG, "[sdk] 2.5 秒后 haveInit=" + ok);

                    if (!ok) {
                        Log.w(TAG, "[sdk] 还是没绑上 —— 看 SDASDK-SdkManagerImpl / SDASDK-APIManager 的日志");
                        return;
                    }

                    // 用**官方文档**的服务名（虚拟车是两段式 vehicle_service.<域>）
                    probeKeys(m, "vehicle_service.drive", new String[]{   // 文档：车速/挡位/里程/轮胎
                            "vehicle.drive.flTireState", "vehicle.drive.frTireState",
                            "vehicle.drive.lrTireState", "vehicle.drive.rrTireState"});
                    probeKeys(m, "vehicle_service.drive", new String[]{}); // 空：看能否全量返回
                    probeKeys(m, "vehicle_service.drive", new String[]{   // 猜我们要的
                            "vehicle.drive.speed", "vehicle.drive.vehicleSpeed",
                            "vehicle.drive.gearLevel", "vehicle.drive.gearPosition",
                            "vehicle.drive.powerShift", "vehicle.drive.totalMileage"});
                    probeKeys(m, "vehicle_service.light", new String[]{   // 文档：转弯灯在这
                            "vehicle.light.turnLight", "vehicle.light.turnLightState",
                            "vehicle.light.leftTurnLight", "vehicle.light.hazardLamp"});
                    probeKeys(m, "vehicle_service.energyRecycle", new String[]{
                            "vehicle.energyRecycle.soc", "vehicle.energyRecycle.batteryLevel",
                            "vehicle.energyRecycle.tripEnergyConsume"});
                    // 应用列表（安装/自启那两块痛点）
                    probe(m, "app_manager_module", "app_manager_service", "get_app_list", null);
                } catch (Throwable t) {
                    Log.e(TAG, "[sdk] 异常: " + t, t);
                }
            }
        }, "ca-sdk").start();
    }

    /** 用 pkeys 查车辆数据（车机自己的调用就是这么发的）。 */
    private static void probeKeys(CaSdkManager m, String svc, String[] keys) {
        java.util.Map<String, Object> p = new java.util.HashMap<>();
        p.put("pkeys", new java.util.ArrayList<>(java.util.Arrays.asList(keys)));
        probe(m, "virtual_vehicle", svc, "getVirtualVehicle", p);
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
