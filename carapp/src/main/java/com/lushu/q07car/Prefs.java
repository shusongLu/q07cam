package com.lushu.q07car;

import android.content.Context;
import android.content.SharedPreferences;

/** 全部配置：预连方式、两个窗口的位置与大小、编辑模式、开机自启。 */
public class Prefs {

    private static final String NAME = "q07";

    // 预连：none | left | right | both
    public static final String KEY_PRECONNECT = "preconnect";
    // 窗口布局
    public static final String KEY_PANEL_X = "panel_x";
    public static final String KEY_PANEL_Y = "panel_y";
    public static final String KEY_PANEL_W = "panel_w";
    public static final String KEY_PANEL_H = "panel_h";
    public static final String KEY_CAM_X = "cam_x";
    public static final String KEY_CAM_Y = "cam_y";
    public static final String KEY_CAM_W = "cam_w";
    public static final String KEY_CAM_H = "cam_h";
    // 编辑模式（可拖动/缩放）
    public static final String KEY_EDIT = "edit";
    // 主题：dark | light | system
    public static final String KEY_THEME = "theme";
    // 副驾屏（DisplayManager 的 displayId，-1 = 关闭）
    public static final String KEY_AUX_DISPLAY = "aux_display";
    // 功能开关
    public static final String KEY_SHOW_PANEL = "show_panel";
    public static final String KEY_SHOW_CAMERA = "show_camera";
    // 摄像头画面镜像模式：none | h | v | hv（左右各自）
    public static final String KEY_MIRROR_LEFT = "mirror_left_mode";
    public static final String KEY_MIRROR_RIGHT = "mirror_right_mode";
    /** 转向灯灭后摄像头窗口再保留多久（毫秒，0 = 立即关）。 */
    public static final String KEY_CAM_OFF_DELAY = "cam_off_delay_ms";
    public static final int DEF_CAM_OFF_DELAY = 0;
    public static final String MIRROR_NONE = "none";
    public static final String MIRROR_H = "h";     // 水平镜像（像后视镜）
    public static final String MIRROR_V = "v";     // 垂直翻转
    public static final String MIRROR_HV = "hv";   // 水平 + 垂直（等于 180° 旋转）
    // 开机自启
    public static final String KEY_BOOT = "boot";

    // 默认布局
    public static final int DEF_PANEL_X = 470, DEF_PANEL_Y = 170, DEF_PANEL_W = 1040, DEF_PANEL_H = 560;
    public static final int DEF_CAM_X = 470, DEF_CAM_Y = 745, DEF_CAM_W = 960, DEF_CAM_H = 540;

    // 旧默认值（用于一次性迁移，见 migrate()）
    private static final int DEF_CAM_W_OLD = 720, DEF_CAM_H_OLD = 405;
    private static final String KEY_LAYOUT_V = "layout_v";
    private static final int LAYOUT_V = 2;

    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static int getInt(Context c, String key, int def) {
        return get(c).getInt(key, def);
    }

    public static void putInt(Context c, String key, int v) {
        get(c).edit().putInt(key, v).apply();
    }

    public static boolean getBool(Context c, String key, boolean def) {
        return get(c).getBoolean(key, def);
    }

    public static void putBool(Context c, String key, boolean v) {
        get(c).edit().putBoolean(key, v).apply();
    }

    public static String getStr(Context c, String key, String def) {
        return get(c).getString(key, def);
    }

    public static void putStr(Context c, String key, String v) {
        get(c).edit().putString(key, v).apply();
    }

    /**
     * 一次性布局迁移。
     *
     * 摄像头窗口旧的默认尺寸是 720x405，而拉到的流是 1920x1080 —— 清晰度只用掉一小半。
     * 放大窗口本身**不增加 CPU**（TextureView 每帧合成开销与窗口尺寸无关，已实测），
     * 所以把默认值提到 960x540。只在"用户没自己改过"（存的还是旧默认值）时动它。
     */
    public static void migrate(Context c) {
        SharedPreferences p = get(c);
        int v;
        try { v = p.getInt(KEY_LAYOUT_V, 0); } catch (Throwable t) { v = 0; }
        if (v >= LAYOUT_V) return;

        SharedPreferences.Editor e = p.edit();
        int w = p.getInt(KEY_CAM_W, DEF_CAM_W_OLD);
        int h = p.getInt(KEY_CAM_H, DEF_CAM_H_OLD);
        if (w == DEF_CAM_W_OLD && h == DEF_CAM_H_OLD) {
            e.putInt(KEY_CAM_W, DEF_CAM_W).putInt(KEY_CAM_H, DEF_CAM_H);
        }
        e.putInt(KEY_LAYOUT_V, LAYOUT_V).apply();
    }

    /** 位置/大小恢复默认。 */
    public static void resetLayout(Context c) {
        get(c).edit()
                .remove(KEY_PANEL_X).remove(KEY_PANEL_Y).remove(KEY_PANEL_W).remove(KEY_PANEL_H)
                .remove(KEY_CAM_X).remove(KEY_CAM_Y).remove(KEY_CAM_W).remove(KEY_CAM_H)
                .apply();
    }
}
