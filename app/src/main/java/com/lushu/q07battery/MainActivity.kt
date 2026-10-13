package com.lushu.q07battery

import android.app.Activity
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import android.view.Surface
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.lushu.q07battery.core.TurnSignal
import com.lushu.q07battery.core.VehicleSignals
import com.lushu.q07battery.ui.DashboardPresentation

/**
 * 中控主界面（壳）。
 * 业务逻辑在热更插件里，这里只负责显示 + 提供调试入口。
 */
class MainActivity : Activity() {

    private var presentation: DashboardPresentation? = null

    private lateinit var tvPlugin: TextView
    private lateinit var tvPower: TextView
    private lateinit var tvRpm: TextView
    private lateinit var tvSoc: TextView
    private lateinit var tvTurn: TextView
    private lateinit var tvDisplays: TextView

    private val onSignals: (VehicleSignals) -> Unit = { render(it) }
    private val onStatus: (String) -> Unit = { text -> tvPlugin.text = text }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvPlugin = findViewById(R.id.tvPlugin)
        tvPower = findViewById(R.id.tvPower)
        tvRpm = findViewById(R.id.tvRpm)
        tvSoc = findViewById(R.id.tvSoc)
        tvTurn = findViewById(R.id.tvTurn)
        tvDisplays = findViewById(R.id.tvDisplays)

        findViewById<Button>(R.id.btnReloadPlugin).setOnClickListener {
            PluginRuntime.reload(this)
            toast("已重新加载插件")
        }
        findViewById<Button>(R.id.btnRefreshDisplays).setOnClickListener { refreshDisplays() }
        findViewById<Button>(R.id.btnPresentation).setOnClickListener { showOnSecondaryDisplay() }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener { startOverlay() }

        refreshDisplays()
        PluginRuntime.ensureStarted(this)

        if (intent?.getBooleanExtra(EXTRA_START_OVERLAY, false) == true) {
            startOverlay()
        }
    }

    override fun onResume() {
        super.onResume()
        PluginRuntime.addSignalListener(onSignals)
        PluginRuntime.addStatusListener(onStatus)
    }

    override fun onPause() {
        super.onPause()
        PluginRuntime.removeSignalListener(onSignals)
        PluginRuntime.removeStatusListener(onStatus)
    }

    override fun onDestroy() {
        presentation?.dismiss()
        presentation = null
        super.onDestroy()
    }

    // ---------- 渲染 ----------

    private fun render(s: VehicleSignals) {
        tvPower.text = "%.1f kW".format(s.batteryPowerKw)
        tvRpm.text = if (s.engineRunning) "${s.engineRpm} rpm" else "EV（发动机未启动）"
        tvSoc.text = "SOC %.0f%% · %.0f V · %.0f A · %.0f km/h"
            .format(s.socPercent, s.batteryVoltageV, s.batteryCurrentA, s.speedKph)
        tvTurn.text = when (s.turnSignal) {
            TurnSignal.OFF -> "转向灯: 关"
            TurnSignal.LEFT -> "转向灯: 左（应显示左侧影像）"
            TurnSignal.RIGHT -> "转向灯: 右（应显示右侧影像）"
            TurnSignal.HAZARD -> "转向灯: 双闪"
        }
        presentation?.render(s)
    }

    // ---------- 多屏 ----------

    private fun displays(): List<Display> =
        getSystemService(DisplayManager::class.java).displays.toList()

    private fun refreshDisplays() {
        val sb = StringBuilder()
        displays().forEach { d ->
            val mode = d.mode
            val size = mode?.let {
                "${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate.toInt()}Hz"
            } ?: "?"
            val flags = buildList {
                if (d.displayId == Display.DEFAULT_DISPLAY) add("默认")
                if (d.flags and Display.FLAG_PRESENTATION != 0) add("PRESENTATION")
                if (d.flags and Display.FLAG_PRIVATE != 0) add("PRIVATE")
                if (d.flags and Display.FLAG_SECURE != 0) add("SECURE")
            }
            sb.appendLine(
                "id=${d.displayId}  ${d.name}  $size  rotation=${rotationName(d.rotation)}  [${flags.joinToString(",")}]",
            )
        }
        tvDisplays.text = sb.toString().ifBlank { "(没有枚举到 Display)" }
    }

    private fun rotationName(r: Int): String = when (r) {
        Surface.ROTATION_0 -> "0"
        Surface.ROTATION_90 -> "90"
        Surface.ROTATION_180 -> "180"
        Surface.ROTATION_270 -> "270"
        else -> "?"
    }

    private fun showOnSecondaryDisplay() {
        val candidates = displays().filter { it.displayId != Display.DEFAULT_DISPLAY }
        val target = candidates.firstOrNull { it.flags and Display.FLAG_PRESENTATION != 0 }
            ?: candidates.firstOrNull()
        if (target == null) {
            toast("没有第二块屏幕")
            return
        }
        presentation?.dismiss()
        presentation = try {
            DashboardPresentation(this, target).also { it.show() }
        } catch (t: Throwable) {
            toast("投屏到「${target.name}」失败: ${t.javaClass.simpleName}")
            null
        }
        if (presentation != null) toast("已投到: ${target.name}")
    }

    // ---------- 悬浮窗 ----------

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            toast("请先授予「显示在其他应用上层」权限")
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
            return
        }
        startService(Intent(this, OverlayService::class.java))
        toast("悬浮窗已开启")
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private companion object {
        /** `am start -n <pkg>/.MainActivity --ez start_overlay true` */
        const val EXTRA_START_OVERLAY = "start_overlay"
    }
}
