package com.lushu.q07battery

import android.content.Context
import android.util.Log
import com.lushu.q07battery.core.PLUGIN_ENTRY_CLASS
import com.lushu.q07battery.core.PLUGIN_FILE_NAME
import com.lushu.q07battery.core.PLUGIN_INTERFACE_VERSION
import com.lushu.q07battery.core.PluginHost
import com.lushu.q07battery.core.VehiclePlugin
import com.lushu.q07battery.core.VehicleSignals
import dalvik.system.DexClassLoader
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 插件（热更逻辑）运行时。单例，进程内共享一个插件实例。
 *
 * 插件加载优先级：
 *   1. getExternalFilesDir()/plugin.dex   ← `adb push`，现场调试首选
 *   2. filesDir/plugin.dex                ← 内部存储（将来远程下载写这里）
 *
 * 改逻辑 → 重新打包 plugin.dex → 覆盖上面任一路径 → 调 [reload] 即可，无需重装 APK。
 */
object PluginRuntime {

    private const val TAG = "PluginRuntime"

    private var plugin: VehiclePlugin? = null
    private var loader: DexClassLoader? = null

    private val signalListeners = CopyOnWriteArrayList<(VehicleSignals) -> Unit>()
    private val statusListeners = CopyOnWriteArrayList<(String) -> Unit>()

    @Volatile
    private var statusText: String = "未加载"

    @Volatile
    var source: String = "-"
        private set

    @Volatile
    var pluginVersion: String = "-"
        private set

    fun addSignalListener(listener: (VehicleSignals) -> Unit) {
        signalListeners += listener
    }

    fun removeSignalListener(listener: (VehicleSignals) -> Unit) {
        signalListeners -= listener
    }

    fun addStatusListener(listener: (String) -> Unit) {
        statusListeners += listener
        listener(describeStatus())
    }

    fun removeStatusListener(listener: (String) -> Unit) {
        statusListeners -= listener
    }

    fun describeStatus(): String = "$statusText　[来源: $source｜插件: $pluginVersion]"

    @Synchronized
    fun ensureStarted(context: Context) {
        if (plugin == null) reload(context)
    }

    /** 重新加载插件（换 dex 后调用即可生效）。 */
    @Synchronized
    fun reload(context: Context) {
        stopInternal()
        val appContext = context.applicationContext

        val located = locatePlugin(appContext)
        if (located == null) {
            source = "未找到 $PLUGIN_FILE_NAME"
            statusText = "插件缺失"
            publishStatus()
            return
        }
        val (file, from) = located

        try {
            val optDir = File(appContext.cacheDir, "plugin-opt").apply { mkdirs() }
            val classLoader = DexClassLoader(
                file.absolutePath,
                optDir.absolutePath,
                null,
                appContext.classLoader,
            )
            val clazz = Class.forName(PLUGIN_ENTRY_CLASS, true, classLoader)
            val instance = clazz.getDeclaredConstructor().newInstance() as VehiclePlugin

            val version = instance.interfaceVersion()
            if (version != PLUGIN_INTERFACE_VERSION) {
                source = from
                statusText = "接口版本不符：插件=$version，壳=$PLUGIN_INTERFACE_VERSION"
                publishStatus()
                return
            }

            loader = classLoader
            plugin = instance
            source = from
            pluginVersion = instance.version()
            instance.start(host(appContext))
            publishStatus()
        } catch (t: Throwable) {
            Log.e(TAG, "插件加载失败", t)
            source = from
            statusText = "加载失败：${t.javaClass.simpleName}: ${t.message}"
            plugin = null
            loader = null
            publishStatus()
        }
    }

    @Synchronized
    private fun stopInternal() {
        runCatching { plugin?.stop() }.onFailure { Log.w(TAG, "插件 stop 异常", it) }
        plugin = null
        loader = null
        pluginVersion = "-"
        statusText = "未加载"
    }

    private fun host(appContext: Context): PluginHost = object : PluginHost {
        override val appContext: Context get() = appContext

        override fun emit(signals: VehicleSignals) {
            signalListeners.forEach { it(signals) }
        }

        override fun status(text: String) {
            statusText = text
            publishStatus()
        }

        override fun log(message: String) {
            Log.i(TAG, "[plugin] $message")
        }
    }

    private fun publishStatus() {
        val text = describeStatus()
        statusListeners.forEach { it(text) }
    }

    /** 返回 (文件, 来源描述)。 */
    private fun locatePlugin(context: Context): Pair<File, String>? {
        context.getExternalFilesDir(null)?.let { dir ->
            val file = File(dir, PLUGIN_FILE_NAME)
            if (file.exists()) return file to "外部目录(adb push)"
        }
        val internalFile = File(context.filesDir, PLUGIN_FILE_NAME)
        if (internalFile.exists()) return internalFile to "内部 files"
        return null
    }
}
