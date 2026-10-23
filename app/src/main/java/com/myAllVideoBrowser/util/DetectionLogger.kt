package com.myAllVideoBrowser.util

import android.os.Handler
import android.os.Looper
import androidx.databinding.ObservableArrayList
import java.text.SimpleDateFormat
import java.util.*

object DetectionLogger {
    private const val MAX_LOGS = 30
    val logs = ObservableArrayList<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val mainHandler = Handler(Looper.getMainLooper())

    @Synchronized
    fun log(type: String, state: String, url: String, message: String = "") {
        val time = dateFormat.format(Date())
        val logEntry = "$time:$type:$state:$url${if (message.isNotEmpty()) " ($message)" else ""}"
        
        mainHandler.post {
            if (logs.size >= MAX_LOGS) {
                logs.removeAt(0)
            }
            logs.add(logEntry)
        }
        AppLogger.d("DetectionLogger: $logEntry")
    }

    fun clear() {
        mainHandler.post {
            logs.clear()
        }
    }
}
