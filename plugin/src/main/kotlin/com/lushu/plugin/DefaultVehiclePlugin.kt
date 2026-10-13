package com.lushu.plugin

import android.os.Handler
import android.os.Looper
import com.lushu.q07battery.core.PLUGIN_INTERFACE_VERSION
import com.lushu.q07battery.core.PluginHost
import com.lushu.q07battery.core.TurnSignal
import com.lushu.q07battery.core.VehiclePlugin
import com.lushu.q07battery.core.VehicleSignals
import kotlin.math.abs
import kotlin.random.Random

/**
 * 默认插件（当前为模拟实现，用于验证热更闭环）。
 *
 * 真正的信号接入（广播 / AIDL / CarPropertyManager）以后替换本文件即可：
 * 改这里 → 重新打包 plugin.dex → adb push → App 重载，**不用重装 App**。
 *
 * 改动 [AMPLITUDE] 可以明显看出热更是否生效。
 */
class DefaultVehiclePlugin : VehiclePlugin {

    override fun version(): String = "mock-1.0 (amplitude=$AMPLITUDE)"

    override fun interfaceVersion(): Int = PLUGIN_INTERFACE_VERSION

    private var host: PluginHost? = null
    private val handler = Handler(Looper.getMainLooper())

    private var soc = 62f
    private var current = 20f
    private var tick = 0L

    private val runnable = object : Runnable {
        override fun run() {
            tick++

            current += (Random.nextFloat() * 24f - 12f) * AMPLITUDE
            if (tick % 90L == 0L) {
                current = if (Random.nextBoolean()) -60f else 70f
            }
            current = current.coerceIn(-160f * AMPLITUDE, 200f * AMPLITUDE)

            val engineRunning = (tick % 400L) in 120L..260L
            val rpm = if (engineRunning) 1200 + Random.nextInt(2200) else 0

            soc = (soc - abs(current) * 0.00002f).coerceIn(0f, 100f)

            val turn = when ((tick / 80L) % 3L) {
                1L -> TurnSignal.LEFT
                2L -> TurnSignal.RIGHT
                else -> TurnSignal.OFF
            }

            val voltage = 350f + (soc - 50f) * 0.4f
            host?.emit(
                VehicleSignals(
                    batteryVoltageV = voltage,
                    batteryCurrentA = current,
                    batteryPowerKw = voltage * current / 1000f,
                    engineRpm = rpm,
                    engineRunning = engineRunning,
                    turnSignal = turn,
                    speedKph = (abs(current) * 0.6f).coerceIn(0f, 160f),
                    socPercent = soc,
                    timestampMs = System.currentTimeMillis(),
                ),
            )
            handler.postDelayed(this, 100L)
        }
    }

    override fun start(host: PluginHost) {
        this.host = host
        host.log("DefaultVehiclePlugin.start() version=${version()}")
        host.status("插件运行中（模拟数据）")
        handler.post(runnable)
    }

    override fun stop() {
        handler.removeCallbacks(runnable)
        host?.log("DefaultVehiclePlugin.stop()")
        host = null
    }

    private companion object {
        /** 幅度系数：改这个值可直观验证热更是否生效。 */
        const val AMPLITUDE = 1.0f
    }
}
