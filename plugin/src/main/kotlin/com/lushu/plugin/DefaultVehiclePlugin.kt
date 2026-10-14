package com.lushu.plugin

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import com.lushu.q07battery.core.Gear
import com.lushu.q07battery.core.PLUGIN_INTERFACE_VERSION
import com.lushu.q07battery.core.PluginHost
import com.lushu.q07battery.core.TurnSignal
import com.lushu.q07battery.core.VehiclePlugin
import com.lushu.q07battery.core.VehicleSignals
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 真车插件（AAOS CarPropertyManager）。
 *
 * 数据来源是车机标准车辆属性（详见 core/VehicleSignals.kt 的注释）。
 * 只读，不写任何属性 —— 不改车、不改系统。
 *
 * 运行说明：Car.createCar() 会 bindService 并阻塞等待，**必须在工作线程调用**，
 * 不能占用主线程；采样结果通过 host.emit() 交回壳（壳负责切回主线程刷新 UI）。
 *
 * 本车权限情况：
 *  - CAR_ENERGY (dangerous)     ← 需运行时申请/授权：功率、电量、续航、油量
 *  - CAR_SPEED  (dangerous)     ← 需运行时申请/授权：车速
 *  - CAR_POWERTRAIN (normal)    ← 安装即授予：挡位、点火
 *  - CAR_ENGINE_DETAILED / CAR_EXTERIOR_LIGHTS (signature|privileged) ← 无法获取：转速、转向灯
 */
class DefaultVehiclePlugin : VehiclePlugin {

    override fun version(): String = "car-1.0"

    override fun interfaceVersion(): Int = PLUGIN_INTERFACE_VERSION

    private var host: PluginHost? = null
    private var executor: ExecutorService? = null
    private var car: Car? = null
    private val running = AtomicBoolean(false)

    override fun start(host: PluginHost) {
        this.host = host
        if (!running.compareAndSet(false, true)) return
        host.log("DefaultVehiclePlugin.start() version=${version()}")
        host.status("连接车辆服务…")
        val ex = Executors.newSingleThreadExecutor { r ->
            Thread(r, "q07-car-poll").apply { isDaemon = true }
        }
        executor = ex
        ex.execute { pollLoop(host) }
    }

    override fun stop() {
        running.set(false)
        executor?.shutdownNow()
        executor = null
        runCatching { car?.disconnect() }
        car = null
        host?.log("DefaultVehiclePlugin.stop()")
        host = null
    }

    // ---------- 采样循环（工作线程） ----------

    private fun pollLoop(host: PluginHost) {
        val ctx = host.appContext
        val pm: CarPropertyManager
        try {
            val c = Car.createCar(ctx)
            car = c
            val mgr = c.getCarManager(Car.PROPERTY_SERVICE)
            pm = mgr as? CarPropertyManager
                ?: run {
                    host.status("拿不到 CarPropertyManager")
                    return
                }
            host.status("已连接车辆服务，读取中")
        } catch (t: Throwable) {
            // 非车机设备（如手机）会走到这里：android.car 不存在 / 没有 car_service
            host.status("连接车辆服务失败：${t.javaClass.simpleName}")
            host.log("createCar failed: $t")
            return
        }

        var lastError = ""
        while (running.get()) {
            try {
                val s = sample(pm)
                host.emit(s)
                lastError = ""
            } catch (t: Throwable) {
                val msg = "${t.javaClass.simpleName}: ${t.message}"
                if (msg != lastError) {
                    lastError = msg
                    host.status("读取失败：$msg")
                }
            }
            try {
                Thread.sleep(POLL_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun sample(pm: CarPropertyManager): VehicleSignals {
        val powerRawW = readFloat(pm, VehiclePropertyIds.EV_BATTERY_INSTANTANEOUS_CHARGE_RATE)
        val levelRaw = readFloat(pm, VehiclePropertyIds.EV_BATTERY_LEVEL)
        val rangeRaw = readFloat(pm, VehiclePropertyIds.RANGE_REMAINING)
        val fuelRaw = readFloat(pm, VehiclePropertyIds.FUEL_LEVEL)
        val speedRaw = readFloat(pm, VehiclePropertyIds.PERF_VEHICLE_SPEED)
        val gearRaw = readInt(pm, VehiclePropertyIds.GEAR_SELECTION)

        return VehicleSignals(
            batteryPowerKw = powerRawW * POWER_W_TO_KW * POWER_SIGN,
            socPercent = levelRaw / LEVEL_DIVISOR,
            rangeKm = rangeRaw / RANGE_DIVISOR,
            fuelPercent = fuelRaw / FUEL_DIVISOR,
            speedKph = speedRaw * SPEED_FACTOR,
            gear = Gear.of(gearRaw),
            engineRpm = 0,
            engineRunning = false,
            turnSignal = TurnSignal.OFF,
            timestampMs = System.currentTimeMillis(),
            rawDebug = "lvl=$levelRaw rng=$rangeRaw fuel=$fuelRaw pwr=$powerRawW spd=$speedRaw",
        )
    }

    private fun readFloat(pm: CarPropertyManager, id: Int, def: Float = 0f): Float =
        runCatching { pm.getFloatProperty(id, 0) }.getOrDefault(def)

    private fun readInt(pm: CarPropertyManager, id: Int, def: Int = -1): Int =
        runCatching { pm.getIntProperty(id, 0) }.getOrDefault(def)

    private companion object {
        /** 采样周期 (ms)。CONTINUOUS 属性 VHAL 采样率上限 10Hz，100ms 足够。 */
        const val POLL_MS = 100L

        // ---------- 标定系数（实车对比后微调）----------
        /** EV_BATTERY_INSTANTANEOUS_CHARGE_RATE：AOSP 定义为 W。 */
        const val POWER_W_TO_KW = 0.001f
        /** 功率正负号：+1 保持原值；若实车显示与预期相反，改成 -1。 */
        const val POWER_SIGN = 1f
        /** EV_BATTERY_LEVEL 原始值 → %。 */
        const val LEVEL_DIVISOR = 1000f
        /** RANGE_REMAINING 原始值（AOSP 定义为 m）→ km。 */
        const val RANGE_DIVISOR = 1000f
        /** FUEL_LEVEL 原始值 → %。 */
        const val FUEL_DIVISOR = 1000f
        /** PERF_VEHICLE_SPEED → km/h（AOSP 为 m/s 时用 3.6）。 */
        const val SPEED_FACTOR = 1f
    }
}
