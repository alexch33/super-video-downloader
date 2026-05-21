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

    fun isMemoryCritical(): Boolean {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()

        return usedMemory.toDouble() / maxMemory.toDouble() > MEMORY_CRITICAL_THRESHOLD
    }
}
