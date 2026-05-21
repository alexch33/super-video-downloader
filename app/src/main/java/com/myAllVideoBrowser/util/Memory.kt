package com.myAllVideoBrowser.util

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.annotation.FloatRange

object Memory {
    const val MEMORY_CRITICAL_THRESHOLD = 0.9

    fun calcCacheSize(context: Context, @FloatRange(from = 0.01, to = 1.0) size: Float): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val largeHeap = context.applicationInfo.flags and ApplicationInfo.FLAG_LARGE_HEAP != 0
        val memoryClass = if (largeHeap) am.largeMemoryClass else am.memoryClass
        return (memoryClass * 1024L * 1024L * size).toLong()
    }

    fun isMemoryCritical(context: Context): Boolean {
        val am =
            context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return false

        // 1. Check System-wide Memory (for yt-dlp native process)
        val memoryInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memoryInfo)

        // If the OS has already flagged 'lowMemory', stop immediately.
        if (memoryInfo.lowMemory) return true

        // 2. Check Java Heap Usage
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()

        // Use the actual runtime max memory as the source of truth for the heap limit
        val maxHeapBytes = runtime.maxMemory()

        val isHeapCritical = if (maxHeapBytes > 0) {
            (usedMemory.toDouble() / maxHeapBytes.toDouble()) > MEMORY_CRITICAL_THRESHOLD
        } else {
            false
        }

        // 3. Check System RAM (Native space for yt-dlp)
        val availableRamMb = memoryInfo.availMem / (1024 * 1024)
        val isSystemRamCritical = availableRamMb < 64

        val heapRatio =
            if (maxHeapBytes > 0) usedMemory.toDouble() / maxHeapBytes.toDouble() else 0.0
        AppLogger.d(
            "isMemoryCritical: heap=$isHeapCritical (${
                String.format(
                    "%.2f",
                    heapRatio
                )
            }), system=$isSystemRamCritical (avail=${availableRamMb}MB)"
        )

        return isHeapCritical || isSystemRamCritical
    }
}
