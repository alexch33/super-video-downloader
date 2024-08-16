package com.myAllVideoBrowser.data.local.model

data class Proxy(
    val id: String = "",
    val host: String = "",
    val port: String = "",
    val user: String = "",
    val password: String = "",
    val valid: Boolean = false,
    val lastVerify: String = "",
    val countryCode: String = "",
    val cityName: String = "",
    val createdAt: String = ""
) {
    companion object {
        fun noProxy(): Proxy {
            return Proxy()
        }

        fun fromServerMap(tmp: Map<*, *>): Proxy {
            return Proxy(
                id = tmp["id"].toString(),
                host = tmp["proxy_address"].toString().replace("null", "").trim(),
                port = tmp["port"].toString().replace(".0", "").trim(),
                user = tmp["username"].toString(),
                password = tmp["password"].toString(),
                valid = tmp["valid"].toString() == "true",
                lastVerify = tmp["last_verification"].toString(),
                countryCode = tmp["country_code"].toString(),
                cityName = tmp["city_name"].toString(),
                createdAt = tmp["created_at"].toString(),
            )
        }

        fun fromMap(tmp: Map<*, *>) : Proxy {
            return Proxy(
                id = tmp["id"].toString(),
                host = tmp["host"].toString().replace("null", "").trim(),
                port = tmp["port"].toString().replace(".0", "").replace("null", "").trim(),
                user = tmp["user"].toString().replace("null", "").trim(),
                password = tmp["password"].toString().replace("null", "").trim(),
                valid = tmp["valid"].toString() == "true",
                lastVerify = tmp["lastVerification"].toString(),
                countryCode = tmp["countryCode"].toString(),
                cityName = tmp["cityName"].toString(),
                createdAt = tmp["createdAt"].toString(),
            )
        }
    }

    fun toMap(): Map<String, String> {
        val proxyMap = mutableMapOf<String, String>()

        proxyMap["id"] = id
        proxyMap["host"] = host
        proxyMap["port"] = port
        proxyMap["user"] = user
        proxyMap["password"] = password
        proxyMap["countryCode"] = countryCode
        proxyMap["valid"] = valid.toString()
        proxyMap["lastVerify"] = lastVerify
        proxyMap["cityName"] = cityName
        proxyMap["createdAt"] = createdAt

        return proxyMap
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Proxy

        if (port != other.port) return false
        if (host != other.host) return false

        return true
    }

    override fun hashCode(): Int {
        var result = host.hashCode()
        result = 31 * result + port.hashCode()
        return result
    }

}