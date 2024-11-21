package com.myAllVideoBrowser.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.use
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream


class FaviconUtils {
    companion object {
        fun bitmapToBytes(bitmap: Bitmap?): ByteArray {
            val stream = ByteArrayOutputStream()
            bitmap?.compress(Bitmap.CompressFormat.PNG, 90, stream)

            return stream.toByteArray()
        }

        suspend fun getEncodedFaviconFromUrl(
            okHttpClient: OkHttpClient,
            url: String
        ): Bitmap? {
            val reqUrl = "https://${Uri.parse(url).host}/favicon.ico"

            var request = Request.Builder().url(reqUrl).build()
            var response = okHttpClient.newCall(request).execute()

            if (response.code == 404) {
                request = Request.Builder().url(reqUrl.replaceFirst("www.", "").trim()).build()
                response = okHttpClient.newCall(request).execute()
            }

            if (response.code == 404) {
                request = Request.Builder().url(reqUrl.replaceFirst("/favicon.ico", "")).build()
                response = okHttpClient.newCall(request).execute()

                val htmlBodyRaw = response.body.toString()

                val htmlBody = Jsoup.parse(htmlBodyRaw)
                response.body.close()
                val el = htmlBody.select("link[rel~='icon']")
                if (el.isNotEmpty()) {
                    val href = el.first()?.attr("href")
                    if (href != null) {
                        request = Request.Builder().url(href.trim()).build()
                        response = okHttpClient.newCall(request).execute()
                    } else {
                        request = Request.Builder().url(reqUrl).build()
                        response = okHttpClient.newCall(request).execute()
                    }
                }
            }

            if (response.code == 404) {
                request =
                    Request.Builder().url("https://www.google.com/s2/favicons?domain=$url")
                        .build()
                response.body.close()
                response = okHttpClient.newCall(request).execute()
            }

            if (response.code == 200) {

                val source = response.body.use {
                    it.source().readByteString()
                }

                var bodyBytes: Bitmap? = null
                try {
                    bodyBytes = BitmapFactory.decodeStream(source.toByteArray().inputStream())
                } catch (e: Throwable) {
                    AppLogger.d(
                        "BitmapFactory.decodeStream(source?.toByteArray()?.inputStream()) ERROR: ${e.message}"
                    )
                }

                return bodyBytes
            }

            return null
        }
    }
}
