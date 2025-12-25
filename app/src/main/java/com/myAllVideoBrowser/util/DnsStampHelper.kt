package com.myAllVideoBrowser.util

import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A helper class to decode DNS Stamp strings into structured objects.
 * DNS Stamps encode all the parameters required to connect to a secure DNS server
 * as a single string.
 *
 * This implementation supports Plain DNS, DNSCrypt, DNS-over-HTTPS (DoH), and DNS-over-TLS (DoT) stamps.
 *
 * See DNS Stamp specification for more details.
 */
object DnsStampHelper {

    /**
     * Data classes to hold the parsed DNS stamp information.
     * Each sealed subclass represents a different DNS protocol.
     */
    sealed class DnsStamp {
        /**
         * Converts the parsed stamp data into a human-readable URL-like string.
         */
        abstract override fun toString(): String

        /**
         * Plain DNS (unencrypted)
         * protocol: 0x00
         */
        data class Plain(
            val props: Long,
            val addr: String
        ) : DnsStamp() {
            override fun toString(): String {
                // Returns the IP address and port, e.g., "8.8.8.8:53"
                return addr
            }
        }

        /**
         * DNSCrypt
         * protocol: 0x01
         */
        data class DnsCrypt(
            val props: Long,
            val addr: String,
            val pk: ByteArray,
            val providerName: String
        ) : DnsStamp() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false
                other as DnsCrypt
                if (props != other.props) return false
                if (addr != other.addr) return false
                if (!pk.contentEquals(other.pk)) return false
                return providerName == other.providerName
            }

            override fun hashCode(): Int {
                var result = props.hashCode()
                result = 31 * result + addr.hashCode()
                result = 31 * result + pk.contentHashCode()
                result = 31 * result + providerName.hashCode()
                return result
            }

            override fun toString(): String {
                // Returns a representation like "dnscrypt://p1.freedns.zone/8.8.8.8:53"
                return "dnscrypt://$providerName/$addr"
            }
        }

        /**
         * DNS-over-HTTPS (DoH)
         * protocol: 0x02
         */
        data class Doh(
            val props: Long,
            val addr: String,
            val hashes: List<ByteArray>,
            val hostname: String,
            val path: String,
            val bootstrapIps: List<String>
        ) : DnsStamp() {
            override fun toString(): String {
                // Returns a standard DoH URL, e.g., "https://dns.adguard-dns.com/dns-query"
                val port = if (hostname.contains(":")) "" else ":443"
                return "https://$hostname$port$path"
            }
        }

        /**
         * DNS-over-TLS (DoT)
         * protocol: 0x03
         */
        data class Dot(
            val props: Long,
            val addr: String,
            val hashes: List<ByteArray>,
            val hostname: String,
            val bootstrapIps: List<String>
        ) : DnsStamp() {
            override fun toString(): String {
                // Returns a DoT URL, e.g., "dot://dns.google:853"
                val port = if (hostname.contains(":")) "" else ":853"
                return "dot://$hostname$port"
            }
        }


        /**
         * Represents a stamp with a protocol that is not handled by this decoder.
         */
        data class Unhandled(val protocol: Int) : DnsStamp() {
            override fun toString(): String {
                return "unhandled_protocol_$protocol"
            }
        }
    }

    /**
     * Decodes a DNS Stamp string into a structured [DnsStamp] object.
     *
     * @param stampString The DNS stamp URL (e.g., "sdns://...").
     * @return A [DnsStamp] subclass representing the parsed data, or null if parsing fails.
     */
    fun decodeDnsStamp(stampString: String): DnsStamp? {
        if (!stampString.startsWith("sdns://")) {
            return null
        }

        val base64Part = stampString.substringAfter("sdns://").trim()
        val decodedBytes = try {
            // Use URL_SAFE flag for base64url decoding, with NO_WRAP and NO_PADDING
            Base64.decode(base64Part, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            return null // Invalid Base64
        }

        val buffer = ByteBuffer.wrap(decodedBytes).order(ByteOrder.LITTLE_ENDIAN)

        if (buffer.remaining() < 1) return null

        val protocol = buffer.get().toInt() and 0xFF

        return try {
            when (protocol) {
                0x00 -> parsePlainDns(buffer)
                0x01 -> parseDnsCrypt(buffer)
                0x02 -> parseDoh(buffer)
                0x03 -> parseDot(buffer)
                else -> DnsStamp.Unhandled(protocol)
            }
        } catch (_: Exception) {
            null // Buffer underflow or other parsing error
        }
    }

    private fun parsePlainDns(buffer: ByteBuffer): DnsStamp.Plain? {
        if (buffer.remaining() < 8) return null
        val props = buffer.long
        val addr = parseLp(buffer) ?: return null
        return DnsStamp.Plain(props, addr)
    }

    private fun parseDnsCrypt(buffer: ByteBuffer): DnsStamp.DnsCrypt? {
        if (buffer.remaining() < 8) return null
        val props = buffer.long
        val addr = parseLp(buffer) ?: return null
        val pk = parseLpBytes(buffer) ?: return null
        val providerName = parseLp(buffer) ?: return null
        return DnsStamp.DnsCrypt(props, addr, pk, providerName)
    }

    private fun parseDoh(buffer: ByteBuffer): DnsStamp.Doh? {
        if (buffer.remaining() < 8) return null
        val props = buffer.long
        val addr = parseLp(buffer) ?: "" // Address can be an empty string for DoH
        val hashes = parseVlpBytes(buffer)
        val hostname = parseLp(buffer) ?: return null
        val path = parseLp(buffer) ?: return null
        val bootstrapIps = if (buffer.hasRemaining()) parseVlp(buffer) else emptyList()
        return DnsStamp.Doh(props, addr, hashes, hostname, path, bootstrapIps)
    }

    private fun parseDot(buffer: ByteBuffer): DnsStamp.Dot? {
        if (buffer.remaining() < 8) return null
        val props = buffer.long
        val addr = parseLp(buffer) ?: "" // Address can be an empty string for DoT
        val hashes = parseVlpBytes(buffer)
        val hostname = parseLp(buffer) ?: return null
        val bootstrapIps = if (buffer.hasRemaining()) parseVlp(buffer) else emptyList()
        return DnsStamp.Dot(props, addr, hashes, hostname, bootstrapIps)
    }

    // Helper functions for parsing length-prefixed data

    /**
     * Parses a length-prefixed (LP) string.
     */
    private fun parseLp(buffer: ByteBuffer): String? {
        val bytes = parseLpBytes(buffer) ?: return null
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * Parses length-prefixed (LP) bytes.
     * Format: len(x) || x
     */
    private fun parseLpBytes(buffer: ByteBuffer): ByteArray? {
        if (buffer.remaining() < 1) return null
        val len = buffer.get().toInt() and 0xFF
        if (buffer.remaining() < len) return null
        val bytes = ByteArray(len)
        buffer.get(bytes)
        return bytes
    }

    /**
     * Parses a variable-length-prefixed (VLP) set of strings.
     */
    private fun parseVlp(buffer: ByteBuffer): List<String> {
        return parseVlpBytes(buffer).map { String(it, Charsets.UTF_8) }
    }

    /**
     * Parses a variable-length-prefixed (VLP) set of byte arrays.
     * Format: vlen(x1) || x1 || vlen(x2) || x2 ...
     */
    private fun parseVlpBytes(buffer: ByteBuffer): List<ByteArray> {
        val list = mutableListOf<ByteArray>()
        while (buffer.hasRemaining()) {
            val lenByte = buffer.get().toInt() and 0xFF
            val len = lenByte and 0x7F // Lower 7 bits are the length
            if (buffer.remaining() < len) break // Malformed or truncated
            val bytes = ByteArray(len)
            buffer.get(bytes)
            list.add(bytes)
            // If high bit is not set, this is the last item in the set
            if ((lenByte and 0x80) == 0) {
                break
            }
        }
        return list
    }
}
