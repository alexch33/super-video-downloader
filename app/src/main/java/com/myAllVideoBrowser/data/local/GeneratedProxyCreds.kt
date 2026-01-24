package com.myAllVideoBrowser.data.local

import com.google.gson.Gson
import java.security.SecureRandom

data class GeneratedProxyCreds(val localUser: String, val localPassword: String) {

    fun toJson(): String {
        return Gson().toJson(this)
    }

    companion object {
        fun fromJson(json: String): GeneratedProxyCreds {
            return Gson().fromJson(json, GeneratedProxyCreds::class.java)
        }

        private const val USERNAME_LENGTH = 16
        private const val PASSWORD_LENGTH = 32
        private val USER_CHARSET = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        private val PASSWORD_CHARSET = USER_CHARSET

        fun generateProxyCredentials(): GeneratedProxyCreds {
            val user = generateRandomString(USERNAME_LENGTH, USER_CHARSET)
            val password = generateRandomString(PASSWORD_LENGTH, PASSWORD_CHARSET)
            return GeneratedProxyCreds(user, password)
        }

        private fun generateRandomString(length: Int, charset: List<Char>): String {
            val random = SecureRandom()
            return (1..length)
                .map { charset[random.nextInt(charset.size)] }
                .joinToString("")
        }
    }
}
