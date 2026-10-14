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
 * 取值来源（AAOS CarPropertyManager）：
 *  - batteryPowerKw ← EV_BATTERY_INSTANTANEOUS_CHARGE_RATE（原始单位 W）
 *  - socPercent     ← EV_BATTERY_LEVEL
 *  - rangeKm        ← RANGE_REMAINING（原始单位 m）
 *  - fuelPercent    ← FUEL_LEVEL
 *  - speedKph       ← PERF_VEHICLE_SPEED
 *  - gear           ← GEAR_SELECTION
 *
 * 以下两项本车被提到 privileged 级别，普通 App 读不到，暂时恒为 0/OFF：
 *  - engineRpm / engineRunning ← ENGINE_RPM（需 CAR_ENGINE_DETAILED）
 *  - turnSignal                ← TURN_SIGNAL_STATE（需 CAR_EXTERIOR_LIGHTS）
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
    /** 发动机转速 (rpm)，0 = 未启动（本车暂不可读）。 */
    val engineRpm: Int,
    /** 发动机是否运行。 */
    val engineRunning: Boolean,
    /** 转向灯（本车暂不可读）。 */
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
