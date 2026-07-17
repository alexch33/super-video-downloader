package com.myAllVideoBrowser.util

import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.use
import java.io.ByteArrayOutputStream


class FaviconUtils {
    companion object {
        fun bitmapToBytes(bitmap: Bitmap?): ByteArray? {
            if (bitmap == null) return null
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
            return stream.toByteArray()
        }

        suspend fun getEncodedFaviconFromUrl(okHttpClient: OkHttpClient, url: String): ByteArray? {
            delay(0)
            return fetchFaviconBytes(okHttpClient, url)
        }

        private fun fetchFaviconBytes(okHttpClient: OkHttpClient, url: String): ByteArray? {
            val potentialUrls = listOf(
                "https://${Uri.parse(url).host}/favicon.ico",
                "https://${
                    Uri.parse(url).host?.replaceFirst(
                        "www.",
                        ""
                    )
                }/favicon.ico", // Without "www."
            )

            for (reqUrl in potentialUrls) {
                val safeUrl = reqUrl.toHttpUrlOrNull() ?: continue

                val request = Request.Builder().url(safeUrl).build()
                try {
                    val response = okHttpClient.newCall(request).execute()

                    if (response.isSuccessful) {
                        val bytes = response.body.bytes()
                        if (bytes.isNotEmpty()) {
                            return bytes
                        }
                    }
                    response.close()
                } catch (e: Exception) {
                    // Ignore and try next URL
                }
            }

            return null
        }
    }
}
