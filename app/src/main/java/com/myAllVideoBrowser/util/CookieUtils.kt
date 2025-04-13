package com.myAllVideoBrowser.util

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import com.google.common.net.InternetDomainName
import com.yausername.youtubedl_android.YoutubeDLRequest
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Request
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date


object CookieUtils {
    val chromeDefaultPathApi29More =
        "${ContextUtils.getApplicationContext().filesDir.parentFile}/app_webview/Default/"
    val chromeDefaultPathApi28Less =
        "${ContextUtils.getApplicationContext().filesDir.parentFile}/app_webview/"

    fun webRequestToHttpWithCookies(request: WebResourceRequest): Request? {
        val url = request.url.toString()

        val tmpHeaders = request.requestHeaders
        tmpHeaders["Cookie"] = try {
            CookieManager.getInstance().getCookie(url) ?: ""
        } catch (e: Throwable) {
            ""
        }
        val verReq = try {
            Request.Builder().headers(tmpHeaders.toHeaders()).url(url).build()
        } catch (e: Throwable) {
            null
        }

        return verReq
    }

    fun addCookiesToRequest(
        url: String,
        request: YoutubeDLRequest,
        additionalUrl: String? = null
    ): File {
        // TODO: May be should remove this If
        if (Build.VERSION.SDK_INT > 32) {
            val cookieFile =
                File(chromeDefaultPathApi29More)
            if (cookieFile.exists() && !cookieFile.isFile) {
                request.addOption("--cookies-from-browser", "chrome:${cookieFile.path}")
            }

            return cookieFile
        }

        val cookieFile = createTmpCookieFile(url.hashCode().toString())
        var cookies = readCookiesForUrlFromDb(url)

        if (additionalUrl != null && cookies.split("\n").size <= 3) {
            cookies = readCookiesForUrlFromDb(additionalUrl)
        }
        if (cookieFile.exists() && cookieFile.isFile) {
            cookieFile.writeText(cookies)
            request.addOption("--cookies", cookieFile.path)
        }

        return cookieFile
    }

    fun getCookiesForUrlNetScape(url: String, additionalUrl: String? = null): String {
        var cookies = readCookiesForUrlFromDb(url)

        if (additionalUrl != null && cookies.split("\n").size <= 3) {
            cookies = readCookiesForUrlFromDb(additionalUrl)
        }
        return cookies
    }

    fun getFinalRedirectURL(url: URL, headers: Map<String, String>): Pair<URL, Headers>? {
        val currentHeaders = headers.toMutableMap()

        try {
            val con = url.openConnection() as HttpURLConnection
            con.instanceFollowRedirects = false
            for (header in currentHeaders) con.setRequestProperty(header.key, header.value)
            try {
                con.connect()
            } catch (_: Throwable) {

            }
            val resCode = con.responseCode
            if (resCode == HttpURLConnection.HTTP_SEE_OTHER || resCode == HttpURLConnection.HTTP_MOVED_PERM || resCode == HttpURLConnection.HTTP_MOVED_TEMP) {
                var location = con.getHeaderField("Location")

                val origin = con.getHeaderField("Access-Control-Allow-Origin")

                if (location.startsWith("/")) {
                    location = if (location.startsWith("//")) {
                        url.protocol + "://" + location.replace("//", "")
                    } else {
                        url.protocol + "://" + url.host + location
                    }
                }
                if (origin != null) {
                    currentHeaders["Referer"] = origin
                }
                return getFinalRedirectURL(URL(location), currentHeaders)
            }
        } catch (_: Exception) {

        }

        return Pair(url, currentHeaders.toHeaders())
    }

    private fun readCookiesForUrlFromDb(url: String): String {
        val cookiesDbFile = if (Build.VERSION.SDK_INT > 28) {
            File("${chromeDefaultPathApi29More}/Cookies")
        } else {
            File("${chromeDefaultPathApi28Less}/Cookies")
        }

        val chrome = ChromeBrowser()
        val cookies = chrome.getCookiesNetscapeForDomain(Uri.parse(url).host, cookiesDbFile)

        return cookies.trim()
    }

    private fun createTmpCookieFile(name: String): File {
        val file = File("${ContextUtils.getApplicationContext().cacheDir}/$name")
        if (file.exists()) {
            file.delete()
        }
        file.createNewFile()

        return file
    }
}

abstract class Cookie(
    var name: String,
    var encryptedValue: ByteArray,
    expires: Date,
    path: String,
    domain: String,
    secure: Boolean,
    httpOnly: Boolean,
    cookieStore: File
) {
    private var expires: Date
    var path: String
        protected set
    var domain: String
        protected set
    var isSecure: Boolean
        protected set
    var isHttpOnly: Boolean
        protected set
    var cookieStore: File
        protected set

    init {
        this.expires = expires
        this.path = path
        this.domain = domain
        isSecure = secure
        isHttpOnly = httpOnly
        this.cookieStore = cookieStore
    }

    fun getExpires(): Date {
        return expires
    }

    abstract val isDecrypted: Boolean
}

class DecryptedCookie(
    name: String,
    encryptedValue: ByteArray,
    val decryptedValue: String,
    expires: Date,
    path: String,
    domain: String,
    secure: Boolean,
    httpOnly: Boolean,
    cookieStore: File
) :
    Cookie(name, encryptedValue, expires, path, domain, secure, httpOnly, cookieStore) {

    override val isDecrypted: Boolean
        get() = true

    override fun toString(): String {
        return "Cookie [name=$name, value=$decryptedValue]"
    }
}

class EncryptedCookie(
    name: String,
    encryptedValue: ByteArray,
    expires: Date,
    path: String,
    domain: String,
    secure: Boolean,
    httpOnly: Boolean,
    cookieStore: File
) :
    Cookie(name, encryptedValue, expires, path, domain, secure, httpOnly, cookieStore) {
    override val isDecrypted: Boolean
        get() = false

    override fun toString(): String {
        return "Cookie [name=$name (encrypted)]"
    }
}

abstract class Browser {
    /**
     * A file that should be used to make a temporary copy of the browser's cookie store
     */
    protected var cookieStoreCopy =
        File("${ContextUtils.getApplicationContext().cacheDir}/cookies_${this.hashCode()}.db")

    val cookies: Set<Cookie>
        /**
         * Returns all cookies
         */
        get() {
            val cookies = HashSet<Cookie>()
            for (cookieStore in cookieStores) {
                cookies.addAll(processCookies(cookieStore, null)!!)
            }
            return cookies
        }

    /**
     * Returns cookies for a given domain
     */
    fun getCookiesForDomain(domain: String?): Set<Cookie> {
        val cookies = HashSet<Cookie>()
        for (cookieStore in cookieStores) {
            cookies.addAll(processCookies(cookieStore, domain)!!)
        }
        return cookies
    }

    protected abstract val cookieStores: Set<File?>

    /**
     * Processes all cookies in the cookie store for a given domain or all
     * domains if domainFilter is null
     *
     * @param cookieStore
     * @param domainFilter
     * @return
     */
    protected abstract fun processCookies(
        cookieStore: File?,
        domainFilter: String?
    ): Set<Cookie>?

    /**
     * Decrypts an encrypted cookie
     * @param encryptedCookie
     * @return
     */
    protected abstract fun decrypt(encryptedCookie: EncryptedCookie?): DecryptedCookie?
}

/**
 * An implementation of Chrome cookie decryption logic for Mac, Windows, and Linux installs
 *
 * References:
 * 1) http://n8henrie.com/2014/05/decrypt-chrome-cookies-with-python/
 * 2) https://github.com/markushuber/ssnoob
 *
 * @author Ben Holland
 */
class ChromeBrowser : Browser() {
    private var chromeKeyringPassword: String? = null
    override val cookieStores: Set<File?>
        /**
         * Returns a set of cookie store locations
         * @return
         */
        get() {
            val cookieStores = HashSet<File>()
            val cookiesDbFile = if (Build.VERSION.SDK_INT > 28) {
                File("${CookieUtils.chromeDefaultPathApi29More}/Cookies")
            } else {
                File("${CookieUtils.chromeDefaultPathApi28Less}/Cookies")
            }
            cookieStores.add(cookiesDbFile)

            return cookieStores
        }

    /**
     * Processes all cookies in the cookie store for a given domain or all
     * domains if domainFilter is null
     *
     * @param cookieStore
     * @param domainFilter
     * @return
     */

    fun getCookiesNetscapeForDomain(domain: String?, cookiesStore: File): String {
        val dm: String = domain?.let { InternetDomainName.from(it).topPrivateDomain().toString() }.toString()

        return processCookiesToNetscape(cookiesStore, dm)
    }

    protected override fun processCookies(
        cookieStore: File?,
        domainFilter: String?
    ): Set<Cookie> {
        return emptySet()
    }

    private fun processCookiesToNetscape(cookieStore: File?, domainFilter: String?): String {
        val netscapeCookieFile = StringBuilder()
        netscapeCookieFile.appendLine("# Netscape HTTP Cookie File")
        netscapeCookieFile.appendLine("# https://curl.haxx.se/rfc/cookie_spec.html")
        netscapeCookieFile.appendLine("# This is a generated file! Do not edit.\n")

        cookieStore?.takeIf { it.exists() }?.let { file ->
            val cookieStoreCopy = File.createTempFile("cookieStoreCopy", ".db")
            file.copyTo(cookieStoreCopy, overwrite = true)

            try {
                SQLiteDatabase.openDatabase(cookieStoreCopy.absolutePath, null, 0).use { db ->
                    val cursor = db.rawQuery(
                        "SELECT * FROM cookies ${if (domainFilter.isNullOrEmpty()) "" else "WHERE host_key LIKE '%$domainFilter%'"}",
                        null
                    )
                    cursor.use {
                        while (it.moveToNext()) {
                            val cookieData = extractCookieData(it)
                            netscapeCookieFile.appendLine(formatCookieLine(cookieData))
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.d(e.toString())
                e.printStackTrace()
            } finally {
                cookieStoreCopy.delete()
            }
        }

        return netscapeCookieFile.toString()
    }

    private fun extractCookieData(cursor: Cursor): Map<String, Any?> {
        val cookieData = mutableMapOf<String, Any?>()
        for (i in 0 until cursor.columnCount) {
            val columnName = cursor.getColumnName(i)
            val columnValue = when (cursor.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(i)
                Cursor.FIELD_TYPE_FLOAT -> cursor.getFloat(i)
                Cursor.FIELD_TYPE_STRING -> cursor.getString(i)
                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                else -> cursor.getString(i)
            }
            cookieData[columnName] = columnValue
        }
        return cookieData
    }

    private fun formatCookieLine(cookieData: Map<String, Any?>): String {
        val name = cookieData["name"] as String
        val encryptedBytes = cookieData["encrypted_value"] as? ByteArray ?: byteArrayOf()
        val value = (cookieData["value"] as? String).orEmpty()
        val path = cookieData["path"] as String
        val domain = cookieData["host_key"] as String
        val secure = (cookieData["is_secure"] as? Long ?: cookieData["secure"] as? Long ?: 0L) == 1L
        val httpOnly =
            (cookieData["is_httponly"] as? Long ?: cookieData["httponly"] as? Long ?: 0L) == 1L
        val expires = (cookieData["expires_utc"] as? Long)?.let { chromeTime(it) } ?: 0L

        val httpOnlyString = if (httpOnly) "#HttpOnly_" else ""
        val isSubdomainString = if (domain.startsWith(".")) "TRUE" else "FALSE"
        val isSecureString = if (secure) "TRUE" else "FALSE"
        val expiresFormatted = if (expires == 0L) "0" else expires
        val valueFormatted =
            if (encryptedBytes.isNotEmpty() && value.isEmpty()) String(encryptedBytes) else value

        return "$httpOnlyString${domain}\t${isSubdomainString}\t${path}\t${isSecureString}\t${expiresFormatted}\t${name}\t${valueFormatted}"
    }

    /**
     * Decrypts an encrypted cookie
     * @param encryptedCookie
     * @return
     */
    protected override fun decrypt(encryptedCookie: EncryptedCookie?): DecryptedCookie? {
       return null
    }

    companion object {
        const val CHROMEEPOCHSTART = 11644473600000L

        class CursorStorage() {
            private val nameValueParams = mutableMapOf<String, Any?>()
            private val nameTypeParams = mutableMapOf<String, Int>()

            fun addParams(name: String, type: Int, value: Any?) {
                nameValueParams[name] = value
                nameTypeParams[name] = type
            }

            fun getNameType(name: String): Int {
                Cursor.FIELD_TYPE_BLOB
                return nameTypeParams[name] ?: -1
            }

            fun getNameValue(name: String): Any? {
                return nameValueParams[name]
            }
        }
    }


    // webkit timestamps use Jan 1, 1601 as epoch start, UNIX timestamps
    // start at Jan 1, 1970. this constant represents the difference
    // in milliseconds.
    private fun chromeTime(t: Long): Long {
        // wekbit timestamps are in microseconds, hence t/1000
        return t / 1000 - CHROMEEPOCHSTART
    }
}

