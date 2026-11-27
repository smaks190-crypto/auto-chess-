package dev.nox.local

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import org.json.JSONObject

/** Sampling is done on a worker and only while the settings screen is visible. */
internal class DeviceTelemetry(context: Context) {
    private val app = context.applicationContext
    private var lastCpu: Long? = null
    private var lastTime: Long? = null

    fun sample(): JSONObject {
        val now = SystemClock.elapsedRealtime()
        val cpu = Process.getElapsedCpuTime()
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val percent = lastCpu?.let { c ->
            lastTime?.let { t -> processCpuPercent(cpu - c, now - t, cores) }
        }
        lastCpu = cpu
        lastTime = now
        val memory = runCatching {
            ActivityManager.MemoryInfo().also { info ->
                checkNotNull(app.getSystemService(ActivityManager::class.java)).getMemoryInfo(info)
            }
        }.getOrNull()
        val total = memory?.totalMem?.takeIf { it > 0 }
        val available = memory?.availMem?.takeIf { it >= 0 && total != null && it <= total }
        val battery = runCatching {
            app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val charge = battery?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level in 0..scale && scale > 0) level * 100.0 / scale else null
        }
        val temperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 }
        val pss = runCatching { Debug.getPss().takeIf { it > 0 }?.times(1024L) }.getOrNull()
        val thermal = runCatching {
            app.getSystemService(PowerManager::class.java)?.currentThermalStatus
        }.getOrNull()
        return JSONObject()
            .put("type", "metrics")
            .put("sampledAt", now)
            .put("ramTotal", total ?: JSONObject.NULL)
            .put("ramAvailable", available ?: JSONObject.NULL)
            .put("processPss", pss ?: JSONObject.NULL)
            .put("cpuPercent", percent ?: JSONObject.NULL)
            .put("cpuCores", cores)
            .put("batteryPercent", charge ?: JSONObject.NULL)
            .put("batteryTemperature", temperature ?: JSONObject.NULL)
            .put("thermalStatus", thermal ?: JSONObject.NULL)
    }

    companion object {
        /** Percentage of the CPU capacity of the reported available cores, not total system load. */
        internal fun processCpuPercent(cpuDelta: Long, elapsedDelta: Long, cores: Int): Double? {
            if (cpuDelta < 0 || elapsedDelta !in 500L..10_000L || cores <= 0) return null
            val percent = cpuDelta.toDouble() / elapsedDelta / cores * 100.0
            return percent.takeIf { it.isFinite() && it in 0.0..100.0 }
        }
    }
}
