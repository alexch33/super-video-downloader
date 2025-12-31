package com.myAllVideoBrowser.v2ray

import android.util.Log

/**
 * This object is the JNI wrapper for the Go library `libgojni.so`.
 * It provides a 100% reproducible way to interact with the underlying Go/Xray core.
 */
object V2Ray {

    private const val TAG = "V2RayJNI"

    // This block loads the native library once, when this V2Ray object is first used.
    // "gojni" corresponds to the filename "libgojni.so".
    init {
        try {
            System.loadLibrary("gojni")
            Log.i(TAG, "Successfully loaded 'libgojni.so' native library.")
        } catch (e: UnsatisfiedLinkError) {
            // This error means the .so file was not found in the APK.
            // This is a critical failure.
            Log.e(TAG, "CRITICAL: Failed to load native library 'libgojni.so'.", e)
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
