package com.lushu.q07battery.core

/** 转向灯状态。 */
enum class TurnSignal { OFF, LEFT, RIGHT, HAZARD }

/**
 * 挡位（对应 AAOS GEAR_SELECTION 的 VehicleGear 位值）。
 * 实车支持的挡位：P / N / R / D / 1..5（见 dumpsys car_service 的 Supported gears）。
 */
enum class Gear(val id: Int) {
    UNKNOWN(-1),
    NEUTRAL(1),
    REVERSE(2),
    PARK(4),
    DRIVE(8),
    FIRST(16),
    SECOND(32),
    THIRD(64),
    FOURTH(128),
    FIFTH(256),
    SIXTH(512),
    ;

    companion object {
        fun of(id: Int): Gear = entries.firstOrNull { it.id == id } ?: UNKNOWN

        /** 中文短标签，给 UI 用。 */
        fun label(g: Gear): String = when (g) {
            UNKNOWN -> "?"
            NEUTRAL -> "N"
            REVERSE -> "R"
            PARK -> "P"
            DRIVE -> "D"
            FIRST -> "1"
            SECOND -> "2"
            THIRD -> "3"
            FOURTH -> "4"
            FIFTH -> "5"
            SIXTH -> "6"
        }
    }
}

/**
 * 一次车辆信号采样。壳与插件之间的唯一数据契约。
 *
 * 取值来源：**厂商车辆 HAL**（`com.ca.car.proxy.hardware.property.CaCarPropertyManager`
 * → 服务 `ca_car_service_hal_property`）。不走 AOSP CarService，因此**不需要任何
 * `android.car.permission.*`**，也不要 root / 特权 / 厂商审批。
 *
 *  - batteryPowerKw ← EV_BATTERY_INSTANTANEOUS_CHARGE_RATE（0x1160030C，原始单位 W）
 *  - socPercent     ← EV_BATTERY_LEVEL（0x11600309）
 *  - rangeKm        ← RANGE_REMAINING（0x11600308，原始单位 m）
 *  - fuelPercent    ← FUEL_LEVEL（0x11600307）
 *  - speedKph       ← PERF_VEHICLE_SPEED（0x11600207）
 *  - gear           ← GEAR_SELECTION（0x11400400）
 *  - engineRpm      ← ENGINE_RPM（0x11600305）
 *  - turnSignal     ← TURN_SIGNAL_STATE（0x11400408）
 */
data class VehicleSignals(
    /** 动力电池瞬时功率 (kW)，正=放电/耗电，负=充电/回收。 */
    val batteryPowerKw: Float,
    /** 动力电池 SOC (%)。 */
    val socPercent: Float,
    /** 剩余续航 (km)。 */
    val rangeKm: Float,
    /** 油箱余量 (%)。 */
    val fuelPercent: Float,
    /** 车速 (km/h)。 */
    val speedKph: Float,
    /** 挡位。 */
    val gear: Gear,
    /** 发动机转速 (rpm)，0 = 未启动。 */
    val engineRpm: Int,
    /** 发动机是否运行。 */
    val engineRunning: Boolean,
    /** 转向灯。 */
    val turnSignal: TurnSignal,
    /** 采样时间戳 (ms)。 */
    val timestampMs: Long,
    /**
     * 标定用原始值（未经换算）：EV_BATTERY_LEVEL / RANGE_REMAINING / FUEL_LEVEL / 功率原始值。
     * 实车对比用；确认换算系数后可忽略。
     */
    val rawDebug: String,
) {
    companion object {
        val EMPTY = VehicleSignals(
            batteryPowerKw = 0f,
            socPercent = 0f,
            rangeKm = 0f,
            fuelPercent = 0f,
            speedKph = 0f,
            gear = Gear.UNKNOWN,
            engineRpm = 0,
            engineRunning = false,
            turnSignal = TurnSignal.OFF,
            timestampMs = 0L,
            rawDebug = "-",
        )
    }
}
