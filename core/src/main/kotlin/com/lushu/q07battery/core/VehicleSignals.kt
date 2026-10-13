package com.lushu.q07battery.core

/** 转向灯状态。 */
enum class TurnSignal { OFF, LEFT, RIGHT, HAZARD }

/**
 * 一次车辆信号采样。壳与插件之间的唯一数据契约。
 * 未知字段填 0 / false。
 */
data class VehicleSignals(
    /** 动力电池母线电压 (V) */
    val batteryVoltageV: Float,
    /** 动力电池母线电流 (A)，正=放电，负=充电/回收 */
    val batteryCurrentA: Float,
    /** 动力电池功率 (kW) */
    val batteryPowerKw: Float,
    /** 发动机转速 (rpm)，0 = 未启动 */
    val engineRpm: Int,
    /** 发动机是否运行 */
    val engineRunning: Boolean,
    /** 转向灯 */
    val turnSignal: TurnSignal,
    /** 车速 (km/h) */
    val speedKph: Float,
    /** SOC (%) */
    val socPercent: Float,
    /** 采样时间戳 (ms) */
    val timestampMs: Long,
)
