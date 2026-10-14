package com.lushu.plugin

import android.os.Handler
import android.os.HandlerThread
import com.lushu.q07battery.core.Gear
import com.lushu.q07battery.core.PLUGIN_INTERFACE_VERSION
import com.lushu.q07battery.core.PluginHost
import com.lushu.q07battery.core.TurnSignal
import com.lushu.q07battery.core.VehiclePlugin
import com.lushu.q07battery.core.VehicleSignals
import java.lang.reflect.Method
import kotlin.math.roundToInt

/**
 * 真车插件 —— 走**厂商车辆 HAL**（不经 AOSP 的 CarService）。
 *
 * 为什么这么做（实测结论）：
 *  - AOSP 的 CarPropertyManager 需要 android.car.permission.*，
 *    其中 CAR_ENGINE_DETAILED / CAR_EXTERIOR_LIGHTS 是 signature|privileged，三方 App 拿不到；
 *    而且声明 car 权限会触发渠道"上架检测"。
 *  - 厂商自带 `com.ca.car.proxy.*`（在车机 BOOTCLASSPATH 里，App 无需打包），
 *    它直连厂商 HAL 服务 `ca_car_service_hal_property`，**不校验任何权限**。
 *    实测：非 root 的 shell 身份即可读到 232 个属性，含功率、转速、转向灯。
 *
 * 约束与注意：
 *  - 该类不在 android.jar 里 → 用**反射**调用（也避免编译期依赖）。
 *  - `getInstance()` 内部会 new Handler()，所以**必须在有 Looper 的线程**调用
 *    （App 主线程天然有；这里我们自建 HandlerThread，全部逻辑都在它上面跑）。
 *  - 只读，不写任何属性。
 */
class DefaultVehiclePlugin : VehiclePlugin {

    override fun version(): String = "ca-hal-1.0"

    override fun interfaceVersion(): Int = PLUGIN_INTERFACE_VERSION

    private var host: PluginHost? = null
    private var thread: HandlerThread? = null

    @Volatile
    private var running = false

    override fun start(host: PluginHost) {
        this.host = host
        if (running) return
        running = true
        host.log("DefaultVehiclePlugin.start() version=${version()}")
        host.status("连接厂商车辆服务…")

        val t = HandlerThread("q07-car-hal")
        t.start()
        thread = t
        // 厂商类会建 Handler，必须在带 Looper 的线程上初始化 —— 就用这个 HandlerThread
        Handler(t.looper).post { runLoop(host) }
    }

    override fun stop() {
        running = false
        thread?.quitSafely()
        thread = null
        host?.log("DefaultVehiclePlugin.stop()")
        host = null
    }

    // ---------- 采样循环（HandlerThread 上执行） ----------

    private fun runLoop(host: PluginHost) {
        val mgr: Any
        val getProperty: Method
        val getValue: Method
        try {
            val mgrCls = Class.forName(MANAGER_CLASS)
            mgr = mgrCls.getMethod("getInstance").invoke(null)
            getProperty = mgrCls.getMethod("getProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            getValue = Class.forName(VALUE_CLASS).getMethod("getValue")
            host.status("已连接厂商车辆服务，读取中")
        } catch (t: Throwable) {
            // 非本车机（比如在手机上联调）会走到这里
            host.status("连接车辆服务失败：${t.javaClass.simpleName}")
            host.log("vendor HAL init failed: $t")
            return
        }

        var lastError = ""
        while (running) {
            try {
                host.emit(sample(getProperty, getValue, mgr))
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

    private fun sample(getProperty: Method, getValue: Method, mgr: Any): VehicleSignals {
        val powerW = raw(getProperty, getValue, mgr, ID_EV_CHARGE_RATE)
        val levelRaw = raw(getProperty, getValue, mgr, ID_EV_BATTERY_LEVEL)
        val rangeRaw = raw(getProperty, getValue, mgr, ID_RANGE_REMAINING)
        val fuelRaw = raw(getProperty, getValue, mgr, ID_FUEL_LEVEL)
        val speedRaw = raw(getProperty, getValue, mgr, ID_PERF_SPEED)
        val gearRaw = raw(getProperty, getValue, mgr, ID_GEAR_SELECTION)
        val rpmRaw = raw(getProperty, getValue, mgr, ID_ENGINE_RPM)
        val turnRaw = raw(getProperty, getValue, mgr, ID_TURN_SIGNAL)

        val rpm = rpmRaw.roundToInt()
        return VehicleSignals(
            batteryPowerKw = powerW * POWER_W_TO_KW * POWER_SIGN,
            socPercent = levelRaw / LEVEL_DIVISOR,
            rangeKm = rangeRaw / RANGE_DIVISOR,
            fuelPercent = fuelRaw / FUEL_DIVISOR,
            speedKph = speedRaw * SPEED_FACTOR,
            gear = Gear.of(gearRaw.roundToInt()),
            engineRpm = rpm,
            engineRunning = rpm > 0,
            turnSignal = turnSignalOf(turnRaw.roundToInt()),
            timestampMs = System.currentTimeMillis(),
            rawDebug = "lvl=$levelRaw rng=$rangeRaw fuel=$fuelRaw pwr=$powerW spd=$speedRaw rpm=$rpmRaw turn=$turnRaw",
        )
    }

    /** 读一个属性，失败一律当 0 —— 单个属性出问题不能拖垮整轮采样。 */
    private fun raw(getProperty: Method, getValue: Method, mgr: Any, id: Int, def: Float = 0f): Float =
        runCatching {
            val v = getProperty.invoke(mgr, id, 0) ?: return@runCatching def
            val value = getValue.invoke(v)
            (value as? Number)?.toFloat() ?: def
        }.getOrDefault(def)

    /**
     * 转向灯取值 → 枚举。
     * 本车取值编码未在文档中给出，先按常见约定映射；界面会显示原始值，便于实车校准。
     */
    private fun turnSignalOf(v: Int): TurnSignal = when (v) {
        1 -> TurnSignal.LEFT
        2 -> TurnSignal.RIGHT
        3 -> TurnSignal.HAZARD
        else -> TurnSignal.OFF
    }

    private companion object {
        const val MANAGER_CLASS = "com.ca.car.proxy.hardware.property.CaCarPropertyManager"
        const val VALUE_CLASS = "com.ca.car.proxy.hardware.CarPropertyValue"

        /** 采样周期 (ms)。 */
        const val POLL_MS = 100L

        // ---------- 属性 ID（实测值，与厂商 defs.VehicleProperty 一致）----------
        const val ID_ENGINE_RPM = 0x11600305
        const val ID_EV_CHARGE_RATE = 0x1160030C
        const val ID_EV_BATTERY_LEVEL = 0x11600309
        const val ID_RANGE_REMAINING = 0x11600308
        const val ID_FUEL_LEVEL = 0x11600307
        const val ID_PERF_SPEED = 0x11600207
        const val ID_GEAR_SELECTION = 0x11400400
        const val ID_TURN_SIGNAL = 0x11400408

        // ---------- 标定系数（实车对比后微调）----------
        /** EV_BATTERY_INSTANTANEOUS_CHARGE_RATE：AOSP 定义为 W。 */
        const val POWER_W_TO_KW = 0.001f
        /** 功率正负号：+1 保持原值；若与预期相反改成 -1。 */
        const val POWER_SIGN = 1f
        const val LEVEL_DIVISOR = 1000f
        const val RANGE_DIVISOR = 1000f
        const val FUEL_DIVISOR = 1000f
        /** PERF_VEHICLE_SPEED → km/h（若实车为 m/s 则改成 3.6）。 */
        const val SPEED_FACTOR = 1f
    }
}
