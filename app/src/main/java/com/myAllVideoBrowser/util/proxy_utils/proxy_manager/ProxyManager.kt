package com.myAllVideoBrowser.util.proxy_utils.proxy_manager

import android.util.Log
import com.myAllVideoBrowser.DLApplication.Companion.DEBUG_TAG
import libv2ray.CoreController
import libv2ray.CoreCallbackHandler
import java.io.Serializable

class XrayCallback : CoreCallbackHandler {
    companion object {
        private const val TAG = "$DEBUG_TAG XrayCallback"
    }

    override fun onEmitStatus(l: Long, s: String): Long {
        Log.d(TAG, "onEmitStatus: l=$l, s='$s'")
        return 0
    }

    override fun shutdown(): Long {
        Log.d(TAG, "shutdown() called from core.")
        return 0
    }

    override fun startup(): Long {
        Log.d(TAG, "startup() called from core.")
        return 0
    }
}

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
 * Manages the Xray proxy lifecycle using the libv2ray.CoreController API.
 * Now with full support for proxy chaining.
 */
object ProxyManager {
    private const val TAG = "$DEBUG_TAG ProxyManager"
    private var coreController: CoreController? = null

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
              "listen": "0.0.0.0",
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
            coreController = CoreController(XrayCallback())
            coreController?.startLoop(xrayJsonConfig)

            Log.i(TAG, "Libv2ray proxy chain started successfully on port $localPort")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Libv2ray proxy chain", e)
            coreController = null
            return false
        }
    }


    fun stopLocalProxy() {
        if (!isProxyRunning()) return
        try {
            coreController?.stopLoop()
            Log.i(TAG, "Libv2ray proxy stop command issued.")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Libv2ray proxy", e)
        } finally {
            coreController = null
        }
    }

    fun isProxyRunning(): Boolean {
        return coreController?.isRunning ?: false
    }
}
