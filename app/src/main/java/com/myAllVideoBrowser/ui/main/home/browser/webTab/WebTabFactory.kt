package com.myAllVideoBrowser.ui.main.home.browser.webTab

import android.util.Patterns
import com.myAllVideoBrowser.ui.main.home.browser.BrowserViewModel

class WebTabFactory {
    companion object {
        fun createWebTabFromInput(input: String): WebTab {
            if (input.isNotEmpty()) {
                return if (input.startsWith("http://") || input.startsWith("https://")) {
                    WebTab(input, null, null, emptyMap())
                } else if (Patterns.WEB_URL.matcher(input).matches()) {
                    WebTab("https://$input", null, null, emptyMap())
                } else {
                    WebTab(
                        String.format(BrowserViewModel.SEARCH_URL, input),
                        null,
                        null,
                        emptyMap())
                }
            }

            return WebTab.HOME_TAB
        }
    }
}
