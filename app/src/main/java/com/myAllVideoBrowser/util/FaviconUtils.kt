package com.myAllVideoBrowser.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.use
import java.io.ByteArrayOutputStream


class FaviconUtils {
    companion object {
        fun bitmapToBytes(bitmap: Bitmap?): ByteArray {
            val stream = ByteArrayOutputStream()
            bitmap?.compress(Bitmap.CompressFormat.PNG, 90, stream)

            return stream.toByteArray()
        }

        suspend fun getEncodedFaviconFromUrl(okHttpClient: OkHttpClient, url: String): Bitmap? {
            delay(0)
            return fetchFavicon(okHttpClient, url)
        }

        private fun fetchFavicon(okHttpClient: OkHttpClient, url: String): Bitmap? {
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
                val request = Request.Builder().url(reqUrl).build()
                val response = okHttpClient.newCall(request).execute()

                if (response.isSuccessful) {
                    response.body.byteStream().use { stream ->
                        return BitmapFactory.decodeStream(stream)
                    }
                }
                response.close()
            }

            return null
        }
    }
}
