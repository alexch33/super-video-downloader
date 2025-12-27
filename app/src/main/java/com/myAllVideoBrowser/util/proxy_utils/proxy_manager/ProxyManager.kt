package com.myAllVideoBrowser.util.proxy_utils.proxy_manager

import android.util.Log
import com.myAllVideoBrowser.DLApplication.Companion.DEBUG_TAG
import com.myAllVideoBrowser.v2ray.V2Ray
import java.io.Serializable

/**
 * Represents a single proxy server in a chain.
 * Based on the provided template.
 */
data class ProxyHop(
    val type: String,
    val address: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null
) : Serializable

/**
 * Manages the Xray proxy lifecycle using the V2Ray JNI wrapper.
 * Full support for proxy chaining.
 */
object ProxyManager {
    private const val TAG = "$DEBUG_TAG ProxyManager"

    /**
     * Starts a local proxy that can chain through a series of other proxies.
     * This is the main function that implements the logic from your template.
     */
    fun startProxyChain(
        localPort: Int,
        localUser: String,
        localPass: String,
        hops: List<ProxyHop>,
        dnsUrl: String? = null
    ): Boolean {
        if (isProxyRunning()) {
            Log.w(TAG, "Proxy is already running. Stopping first.")
            stopLocalProxy()
        }

        val localProxyTag = "local_in"
        val directTag = "direct"

        // Build the outbounds section safely.
        val hopsJson = hops.mapIndexed { index, hop ->
            val tag = "hop_${index}"
            // The password can contain special JSON characters, so it must be escaped.
            val escapedPassword = hop.password?.replace("\"", "\\\"")
            val userPassJson = if (hop.username != null && escapedPassword != null) {
                """, "users": [ { "user": "${hop.username}", "pass": "$escapedPassword" } ] """
            } else ""

            // Xray's protocol name for SOCKS5 is just "socks".
            val protocolName = if (hop.type.equals("socks5", ignoreCase = true) || hop.type.equals(
                    "socks4",
                    ignoreCase = true
                )
            ) "socks" else hop.type

            """
            {
              "tag": "$tag",
              "protocol": "$protocolName",
              "settings": { "servers": [ { "address": "${hop.address}", "port": ${hop.port} $userPassJson } ] }
            }
            """
        }

        val freedomOutbound = """{ "tag": "$directTag", "protocol": "freedom", "settings": {} }"""

        // Join the hops and the final freedom outbound, ensuring no leading comma if hops are empty.
        val outboundsJson = (hopsJson + freedomOutbound).joinToString(",\n")

        // Determine the final tag for the routing rule.
        // If there are hops, the final tag is the last hop. Otherwise, it's 'direct'.
        val finalOutboundTag = if (hops.isNotEmpty()) "hop_${hops.size - 1}" else directTag

        // Build the "routing" JSON object
        val routingJson = """
          "routing": { "rules": [ { "type": "field", "inboundTag": ["$localProxyTag"], "outboundTag": "$finalOutboundTag" } ] }
        """.trimIndent()

        val dnsJson = if (dnsUrl != null) {
            when {
                // Handle DNS-over-HTTPS (e.g., https://dns.google/dns-query)
                dnsUrl.startsWith("https://") -> {
                    """, "dns": { "servers": [ { "address": "$dnsUrl", "protocol": "https" } ] }"""
                }
                // Handle DNS-over-TLS (e.g., dot://dns.google:853 or just dns.google)
                dnsUrl.startsWith("dot://") -> {
                    val dotAddress = dnsUrl.substringAfter("dot://")
                    """, "dns": { "servers": [ { "address": "$dotAddress", "protocol": "tls" } ] }"""
                }
                // Default case for plain domain or IP which can be used for DoT
                else -> {
                    """, "dns": { "servers": [ { "address": "$dnsUrl", "protocol": "tls" } ] }"""
                }
            }
        } else ""

        // Escape the local password just in case it contains special characters
        val escapedLocalPass = localPass.replace("\"", "\\\"")

        // Assemble the complete JSON configuration
        val xrayJsonConfig = """
        {
          "log": { "loglevel": "debug" },
          "inbounds": [
            {
              "tag": "$localProxyTag",
              "port": $localPort,
              "listen": "127.0.0.1",
              "protocol": "http",
              "settings": {
                "accounts": [ { "user": "$localUser", "pass": "$escapedLocalPass" } ],
                "allowTransparent": false
              }
            }
          ],
          "outbounds": [
            $outboundsJson
          ],
          $routingJson
          $dnsJson
        }
        """.trimIndent()

        val redactedConfig = xrayJsonConfig
            .replace(Regex(""""pass":\s*".*?""""), """"pass": "[REDACTED]"""")


        Log.d(TAG, "Starting Libv2ray with generated chain config: $redactedConfig")

        try {
            val result = V2Ray.XrayRun(xrayJsonConfig)
            if (result == 0L) {
                Log.i(TAG, "V2Ray proxy chain started successfully on port $localPort")
                return true
            } else {
                Log.e(TAG, "V2Ray.XrayRun returned a non-zero error code: $result")
                return false
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start V2Ray proxy chain", e)
            return false
        }
    }

    fun stopLocalProxy() {
        if (!isProxyRunning()) return
        try {
            V2Ray.XrayStop()
            Log.i(TAG, "V2Ray proxy stop command issued.")
        } catch (e: Throwable) {
            Log.e(TAG, "Error stopping V2Ray proxy", e)
        }
    }

    fun isProxyRunning(): Boolean {
        return try {
            V2Ray.XrayIsRunning() != 0L
        } catch (e: Throwable) {
            Log.w(TAG, "Could not check V2Ray status, assuming not running.", e)
            false
        }
    }
}
