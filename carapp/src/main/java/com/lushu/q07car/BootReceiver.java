package com.lushu.q07car;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 开机自启：把悬浮窗服务拉起来。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            context.startService(new Intent(context, OverlayService.class));
            Log.i("Q07Car", "boot: 已拉起 OverlayService");
        } catch (Throwable t) {
            Log.e("Q07Car", "boot 启动失败: " + t);
        }
    }
}
