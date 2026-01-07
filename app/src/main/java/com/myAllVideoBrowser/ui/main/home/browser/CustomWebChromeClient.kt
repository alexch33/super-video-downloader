package com.myAllVideoBrowser.ui.main.home.browser

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Message
import android.view.View
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.databinding.FragmentWebTabBinding
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTab
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTabViewModel
import com.myAllVideoBrowser.ui.main.settings.SettingsViewModel
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.AppUtil
import com.myAllVideoBrowser.util.SingleLiveEvent

class CustomWebChromeClient(
    private val tabViewModel: WebTabViewModel,
    private val settingsViewModel: SettingsViewModel,
    private val updateTabEvent: SingleLiveEvent<WebTab>,
    private val pageTabProvider: PageTabProvider,
    private val dataBinding: FragmentWebTabBinding,
    private val appUtil: AppUtil,
    private val mainActivity: MainActivity
) : WebChromeClient() {
    override fun onPermissionRequest(request: PermissionRequest?) {
        if (request == null) {
            super.onPermissionRequest(null)
            return
        }

        val isDrmRequest =
            request.resources.any { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }

        if (isDrmRequest) {
            if (settingsViewModel.isDrmEnabled.get()) {
                AppLogger.d("DRM: Granting permission based on existing setting.")
                request.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
                return
            } else {
                MaterialAlertDialogBuilder(mainActivity)
                    .setTitle(R.string.drm_permission_title)
                    .setMessage(R.string.drm_permission_message)
                    .setPositiveButton(R.string.allow) { _, _ ->
                        AppLogger.d("DRM: User granted permission via dialog.")
                        request.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
                        settingsViewModel.isDrmEnabled.set(true)
                    }
                    .setNegativeButton(R.string.block) { _, _ ->
                        AppLogger.d("DRM: User denied permission via dialog.")
                        request.deny()
                    }
                    .show()
                return
            }
        }

        AppLogger.d("Permissions: Denying non-DRM request for resources: ${request.resources.joinToString()}")
        request.deny()
    }

    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?
    ): Boolean {
        if (!isUserGesture || view == null || resultMsg == null) {
            return false
        }
        val hitTestResult = view.hitTestResult
        val url = hitTestResult.extra

        // Check if the click was on a valid link URL.
        if (hitTestResult.type != WebView.HitTestResult.SRC_ANCHOR_TYPE || url.isNullOrBlank()) {
            return false
        }
        AppLogger.d("ON_CREATE_WINDOW: URL from HitTestResult: $url")

        if (!url.startsWith("http")) {
            AppLogger.d("ON_CREATE_WINDOW: Blocking ad or non-http scheme: $url")
            return false // Blocked
        }

        val transport = resultMsg.obj as WebView.WebViewTransport
        val newWebView = WebView(view.context)
        transport.webView = newWebView

        tabViewModel.openPageEvent.value =
            WebTab(
                webview = newWebView,
                resultMsg = resultMsg,
                url = url,
                title = "Loading...",
                iconBytes = null
            )

        return true
    }

    override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
        val pageTab = pageTabProvider.getPageTab(tabViewModel.thisTabIndex.get())

        val headers = pageTab.getHeaders() ?: emptyMap()
        val updateTab = WebTab(
            pageTab.getUrl(),
            pageTab.getTitle(),
            icon ?: pageTab.getFavicon(),
            headers,
            view,
            id = pageTab.id
        )
        updateTabEvent.value = updateTab
    }

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        tabViewModel.setProgress(newProgress)
        if (newProgress == 100) {
            tabViewModel.isShowProgress.set(false)
        } else {
            tabViewModel.isShowProgress.set(true)
        }
    }

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        super.onShowCustomView(view, callback)
        (mainActivity).requestedOrientation =
            ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        dataBinding.webviewContainer.visibility = View.GONE
        dataBinding.customView.rootView.findViewById<View>(R.id.bottom_bar).visibility =
            View.GONE
        (mainActivity).window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        dataBinding.customView.addView(view)
        appUtil.hideSystemUI(mainActivity.window, dataBinding.customView)
        dataBinding.customView.visibility = View.VISIBLE
        dataBinding.containerBrowser.visibility =
            View.GONE
    }

    override fun onHideCustomView() {
        super.onHideCustomView()
        dataBinding.customView.removeAllViews()
        dataBinding.webviewContainer.visibility = View.VISIBLE
        dataBinding.customView.visibility = View.GONE
        (mainActivity).window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        dataBinding.customView.rootView.findViewById<View>(R.id.bottom_bar).visibility =
            View.VISIBLE
        dataBinding.containerBrowser.visibility =
            View.VISIBLE
        mainActivity.requestedOrientation = if (settingsViewModel.isLockPortrait.get()) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        appUtil.showSystemUI(mainActivity.window, dataBinding.customView)
    }
}
