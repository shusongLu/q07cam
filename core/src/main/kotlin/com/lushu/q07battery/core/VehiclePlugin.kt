package com.lushu.q07battery.core

import android.content.Context

/**
 * 插件接口版本。
 * 壳与插件必须一致，否则拒绝加载（避免接口不匹配导致崩溃）。
 * 只要不改这个接口，插件就能无限次热更，无需重装 App。
 */
const val PLUGIN_INTERFACE_VERSION: Int = 1

/** 插件入口类的全限定名（壳按此名字反射实例化）。 */
const val PLUGIN_ENTRY_CLASS: String = "com.lushu.plugin.DefaultVehiclePlugin"

/** 插件文件名（放在 App 外部目录，可 adb push；或内部 files）。 */
const val PLUGIN_FILE_NAME: String = "plugin.dex"

/**
 * 壳提供给插件的能力。
 * 插件不直接碰 UI —— 只把数据 emit 给壳，壳负责显示。
 */
interface PluginHost {
    /** Application Context，用于注册广播 / 绑定服务 / 读系统服务。 */
    val appContext: Context

    /** 把一次采样交给壳显示（主线程回调）。 */
    fun emit(signals: VehicleSignals)

    /** 报告运行状态，壳会显示，便于现场排错。 */
    fun status(text: String)

    /** 写日志。 */
    fun log(message: String)
}

/**
 * 可热更的业务逻辑。
 *
 * 实现体在 plugin.dex 里，通过 DexClassLoader 加载；
 * 壳只依赖本接口，因此插件可以随时替换。
 */
interface VehiclePlugin {
    /** 插件自身版本（显示用）。 */
    fun version(): String

    /** 必须返回 [PLUGIN_INTERFACE_VERSION]。 */
    fun interfaceVersion(): Int

    /** 开始工作：注册监听、绑定服务、启动采集。 */
    fun start(host: PluginHost)

    /** 停止并释放资源。 */
    fun stop()
}
