package com.myAllVideoBrowser

import java.util.concurrent.Callable

fun waitUntil(commandName: String, check: Callable<Boolean>, waitTime: Long) {
    val startTime = System.currentTimeMillis()
    var lastError = RuntimeException(commandName)
    do {
        try {
            if (check.call()) break
        } catch (t: Throwable) {
            lastError = RuntimeException(commandName, t)
        }

        if (System.currentTimeMillis() - startTime > waitTime) {
            throw lastError
        }
        try {
            Thread.sleep(10)
        } catch (ignored: InterruptedException) {
        }
    } while (true)
}