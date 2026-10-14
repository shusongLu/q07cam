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
    // 开机自启
    public static final String KEY_BOOT = "boot";

    // 默认布局
    public static final int DEF_PANEL_X = 470, DEF_PANEL_Y = 170, DEF_PANEL_W = 1040, DEF_PANEL_H = 560;
    public static final int DEF_CAM_X = 470, DEF_CAM_Y = 745, DEF_CAM_W = 720, DEF_CAM_H = 405;

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

    /** 位置/大小恢复默认。 */
    public static void resetLayout(Context c) {
        get(c).edit()
                .remove(KEY_PANEL_X).remove(KEY_PANEL_Y).remove(KEY_PANEL_W).remove(KEY_PANEL_H)
                .remove(KEY_CAM_X).remove(KEY_CAM_Y).remove(KEY_CAM_W).remove(KEY_CAM_H)
                .apply();
    }
}
