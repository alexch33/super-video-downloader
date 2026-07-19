package com.myAllVideoBrowser.util

import android.net.Uri
import androidx.core.net.toUri

class FaviconUtils {
    companion object {
        fun getFaviconUrl(url: String): String {
            val uri = url.toUri()
            val host = uri.host ?: return ""
            return "https://$host/favicon.ico"
        }
    }
}
