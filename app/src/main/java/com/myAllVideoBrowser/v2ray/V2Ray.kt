package com.myAllVideoBrowser.v2ray

import android.content.Context
import android.util.Log
import com.getkeepsafe.relinker.ReLinker

/**
 * This object is the JNI wrapper for the Go library `libgojni.so`.
 * It provides a 100% reproducible way to interact with the underlying Go/Xray core.
 */
object V2Ray {

    private const val TAG = "V2RayJNI"

    /**
     * Initializes the native library using ReLinker for better compatibility on older devices.
     * This should be called early in the application lifecycle (e.g., in Application.onCreate).
     */
    fun init(context: Context) {
        try {
            ReLinker.loadLibrary(context, "gojni")
            Log.i(TAG, "Successfully loaded 'libgojni' native library using ReLinker.")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load 'libgojni' using ReLinker: ${t.message}")
            try {
                System.loadLibrary("gojni")
                Log.i(TAG, "Successfully loaded 'libgojni' native library using fallback.")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "CRITICAL: Failed to load native library 'libgojni'.", e)
            }
        }
    }

    // --- Native Function Declarations ---
    // These declarations MUST match the 'export' names in your builder.go file.

    /**
     * Corresponds to: //export XrayRun
     * Starts the Xray core with the given JSON configuration.
     * @param config The full Xray JSON configuration as a String.
     * @return 0 on success, non-zero on failure.
     */
    @JvmStatic
    external fun XrayRun(config: String): Long

    /**
     * Corresponds to: //export XrayStop
     * Stops the running Xray core.
     * @return 0 on success.
     */
    @JvmStatic
    external fun XrayStop(): Long

    /**
     * Corresponds to: //export XrayIsRunning
     * Checks if the Xray core is currently active.
     * @return A non-zero value (true) if running, 0 (false) if not.
     */
    @JvmStatic
    external fun XrayIsRunning(): Long

    /**
     * Corresponds to: //export XrayMeasure
     * A utility function to measure something, like connection delay.
     * @param config The Xray JSON configuration.
     * @param url The URL to test against.
     * @return A measurement value, like latency in milliseconds.
     */
    @JvmStatic
    external fun XrayMeasure(config: String, url: String): Long
}
