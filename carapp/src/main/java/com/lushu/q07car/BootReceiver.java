package com.lushu.q07car;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * 开机自启。
 *
 * 注意：Android 8+ 在 BOOT_COMPLETED 里不能再 startService（后台启动限制），
 * 必须用 startForegroundService，否则会静默失败 —— 这是"开机自启没效果"的原因。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "Q07Car";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (!Prefs.getBool(context, Prefs.KEY_BOOT, true)) {
            Log.i(TAG, "boot(" + action + "): 已关闭开机自启");
            return;
        }
        // 亮屏/解锁这类广播会频繁到达，服务已在跑就不必重复拉
        if (OverlayService.running) {
            Log.i(TAG, "boot(" + action + "): 服务已在运行");
            return;
        }
        try {
            Intent i = new Intent(context, OverlayService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
            Log.i(TAG, "boot(" + action + "): 已拉起 OverlayService");
        } catch (Throwable t) {
            Log.e(TAG, "boot 启动失败: " + t);
        }
    }
}
