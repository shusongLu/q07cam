package com.lushu.q07battery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.lushu.q07battery.core.TurnSignal
import com.lushu.q07battery.core.VehicleSignals

/**
 * 悬浮窗服务（壳）。数据来自热更插件，经 PluginRuntime 分发。
 *
 * 只能浮在普通窗口之上；浮到 HUD 需要厂商私有接口（另行确认）。
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var powerView: View? = null
    private var turnView: View? = null
    private var tvPower: TextView? = null
    private var tvRpm: TextView? = null
    private var tvSoc: TextView? = null

    private val onSignals: (VehicleSignals) -> Unit = { s -> handler.post { render(s) } }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startAsForeground()
        addPowerOverlay()
        addTurnOverlay()
        PluginRuntime.ensureStarted(this)
        PluginRuntime.addSignalListener(onSignals)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        PluginRuntime.removeSignalListener(onSignals)
        powerView?.let { runCatching { windowManager.removeView(it) } }
        turnView?.let { runCatching { windowManager.removeView(it) } }
        powerView = null
        turnView = null
        super.onDestroy()
    }

    private fun startAsForeground() {
        val channelId = "q07_overlay"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "实时功率悬浮窗", NotificationManager.IMPORTANCE_LOW),
        )
        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle("Q07 电池功率")
            .setContentText("正在显示实时悬浮窗")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun overlayParams(gravity: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            this.gravity = gravity
        }

    private fun addPowerOverlay() {
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_power, null)
        tvPower = view.findViewById(R.id.tvOverlayPower)
        tvRpm = view.findViewById(R.id.tvOverlayRpm)
        tvSoc = view.findViewById(R.id.tvOverlaySoc)
        windowManager.addView(view, overlayParams(Gravity.TOP or Gravity.START).apply { x = 48; y = 48 })
        powerView = view
    }

    private fun addTurnOverlay() {
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_turn, null)
        view.visibility = View.GONE
        windowManager.addView(view, overlayParams(Gravity.CENTER).apply { width = dp(420); height = dp(240) })
        turnView = view
    }

    private fun render(s: VehicleSignals) {
        tvPower?.text = "%.1f kW".format(s.batteryPowerKw)
        tvRpm?.text = if (s.engineRunning) "${s.engineRpm} rpm" else "EV"
        tvSoc?.text = "SOC %.0f%%  %.0f V  %.0f A".format(s.socPercent, s.batteryVoltageV, s.batteryCurrentA)
        turnView?.visibility = if (s.turnSignal == TurnSignal.OFF) View.GONE else View.VISIBLE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val NOTIFICATION_ID = 0x0707
    }
}
