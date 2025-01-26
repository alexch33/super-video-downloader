package com.myAllVideoBrowser.ui.main.home.browser.webTab

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.app.ShareCompat
import androidx.databinding.Observable
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.HistoryItem
import com.myAllVideoBrowser.data.local.room.entity.VideFormatEntityList
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.databinding.FragmentWebTabBinding
import com.myAllVideoBrowser.ui.component.adapter.SuggestionTabListener
import com.myAllVideoBrowser.ui.component.adapter.TabSuggestionAdapter
import com.myAllVideoBrowser.ui.component.dialog.DownloadTabListener
import com.myAllVideoBrowser.ui.main.home.browser.BaseWebTabFragment
import com.myAllVideoBrowser.ui.main.home.browser.BrowserFragment
import com.myAllVideoBrowser.ui.main.home.browser.BrowserListener
import com.myAllVideoBrowser.ui.main.home.browser.CurrentTabIndexProvider
import com.myAllVideoBrowser.ui.main.home.browser.CustomWebChromeClient
import com.myAllVideoBrowser.ui.main.home.browser.CustomWebViewClient
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonStateCanDownload
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonStateCanNotDownload
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonStateLoading
import com.myAllVideoBrowser.ui.main.home.browser.HOME_TAB_INDEX
import com.myAllVideoBrowser.ui.main.home.browser.HistoryProvider
import com.myAllVideoBrowser.ui.main.home.browser.PageTabProvider
import com.myAllVideoBrowser.ui.main.home.browser.TAB_INDEX_KEY
import com.myAllVideoBrowser.ui.main.home.browser.TabManagerProvider
import com.myAllVideoBrowser.ui.main.home.browser.WorkerEventProvider
import com.myAllVideoBrowser.ui.main.home.browser.detectedVideos.DetectedVideosTabFragment
import com.myAllVideoBrowser.ui.main.home.browser.detectedVideos.VideoDetectionTabViewModel
import com.myAllVideoBrowser.ui.main.player.VideoPlayerActivity
import com.myAllVideoBrowser.ui.main.player.VideoPlayerFragment
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.AppUtil
import com.myAllVideoBrowser.util.FileNameCleaner
import com.myAllVideoBrowser.util.proxy_utils.CustomProxyController
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

class WebTabFragment : BaseWebTabFragment() {

    companion object {
        fun newInstance() = WebTabFragment()
    }

    private lateinit var suggestionAdapter: TabSuggestionAdapter

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    @Inject
    lateinit var appUtil: AppUtil

    @Inject
    lateinit var proxyController: CustomProxyController

    @Inject
    lateinit var okHttpProxyClient: OkHttpProxyClient

    private lateinit var dataBinding: FragmentWebTabBinding

    private lateinit var tabManagerProvider: TabManagerProvider

    private lateinit var pageTabProvider: PageTabProvider

    private lateinit var historyProvider: HistoryProvider

    private lateinit var workerEventProvider: WorkerEventProvider

    private lateinit var currentTabIndexProvider: CurrentTabIndexProvider

    private lateinit var tabViewModel: WebTabViewModel

    private lateinit var videoDetectionTabViewModel: VideoDetectionTabViewModel

    private lateinit var webTab: WebTab

    private var videoToast: Toast? = null

    private var canGoCounter = 0

    private val backPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            handleOnBackPress()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val thisTabIndex = requireArguments().getInt(TAB_INDEX_KEY)

        tabManagerProvider = mainActivity.mainViewModel.browserServicesProvider!!
        pageTabProvider = mainActivity.mainViewModel.browserServicesProvider!!
        historyProvider = mainActivity.mainViewModel.browserServicesProvider!!
        workerEventProvider = mainActivity.mainViewModel.browserServicesProvider!!
        currentTabIndexProvider = mainActivity.mainViewModel.browserServicesProvider!!

        tabViewModel = ViewModelProvider(this, viewModelFactory)[WebTabViewModel::class]
        videoDetectionTabViewModel =
            ViewModelProvider(this, viewModelFactory)[VideoDetectionTabViewModel::class]
        videoDetectionTabViewModel.settingsModel = mainActivity.settingsViewModel
        videoDetectionTabViewModel.webTabModel = tabViewModel

        tabViewModel.openPageEvent = tabManagerProvider.getOpenTabEvent()
        tabViewModel.closePageEvent = tabManagerProvider.getCloseTabEvent()
        tabViewModel.thisTabIndex.set(thisTabIndex)

        webTab = pageTabProvider.getPageTab(thisTabIndex)

        AppLogger.d("onCreate Webview::::::::: ${webTab.getUrl()} $savedInstanceState")
        suggestionAdapter =
            TabSuggestionAdapter(requireContext(), mutableListOf(), suggestionListener)

        recreateWebView(savedInstanceState)

        dataBinding = FragmentWebTabBinding.inflate(inflater, container, false).apply {
            buildWebTabMenu(this.browserMenuButton, false)

            viewModel = tabViewModel
            browserMenuListener = tabListener
            settingsViewModel = mainActivity.settingsViewModel
            videoTabVModel = videoDetectionTabViewModel

            etSearch.setAdapter(suggestionAdapter)
            etSearch.addTextChangedListener(onInputTabChangeListener)
            this.etSearch.imeOptions = EditorInfo.IME_ACTION_DONE
            this.etSearch.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    this.etSearch.clearFocus()
                    viewModel?.viewModelScope?.launch {
                        delay(400)
                        tabViewModel.loadPage((this@apply.etSearch as EditText).text.toString())
                    }
                    false
                } else false
            }

            ivCloseTab.clipToOutline = true
            ivGoForward.clipToOutline = true
            ivGoBack.clipToOutline = true
            ivCloseRefresh.clipToOutline = true

            Glide.with(this@WebTabFragment).asGif().load(R.drawable.loading_floating)
                .into(loadingWavy)
            loadingWavy.clipToOutline = true

            configureWebView(this)
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner, backPressedCallback
        )

        addChangeRouteCallBack()

        tabViewModel.userAgent.set(
            webTab.getWebView()?.settings?.userAgentString
                ?: BrowserFragment.MOBILE_USER_AGENT
        )

        val message = webTab.getMessage()
        if (message != null) {
            message.sendToTarget()
            webTab.flushMessage()
        } else {
            tabViewModel.loadPage(webTab.getUrl())
        }

        return dataBinding.root
    }

    override fun shareWebLink() {
        val link = webTab.getWebView()?.url
        if (link != null) {
            shareLink(link)
        }
    }

    override fun bookmarkCurrentUrl() {
        val webview = webTab.getWebView()
        val url = webview?.url
        val favicon = webview?.favicon
        val name = webview?.title

        if (url == null) {
            return
        }

        mainActivity.mainViewModel.bookmark(
            url,
            name ?: Uri.parse(url).host.toString(),
            favicon
        )
    }

    override fun setIsDesktop(isDesktop: Boolean) {
        super.setIsDesktop(isDesktop)
        setUserAgentIsDesktop(isDesktop)
        webTab.getWebView()?.reload()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        if (!outState.isEmpty) {
            webTab.getWebView()?.saveState(outState)
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        if (savedInstanceState != null && !savedInstanceState.isEmpty) {
            webTab.getWebView()?.restoreState(savedInstanceState)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        handleIndexChangeEvent()
        handleLoadPageEvent()
        handleChangeTabFocusEvent()
        handleWorkerEvent()
        handleOpenDetectedVideos()
        handleVideoPushed()
        tabViewModel.start()
        videoDetectionTabViewModel.start()
    }

    override fun onPause() {
        AppLogger.d("onPause Webview::::::::: ${webTab.getUrl()}")
        super.onPause()
        onWebViewPause()
        backPressedCallback.remove()
    }

    override fun onResume() {
        AppLogger.d("onResume Webview::::::::: ${webTab.getUrl()}")
        super.onResume()
        onWebViewResume()

        activity?.onBackPressedDispatcher?.addCallback(
            viewLifecycleOwner, backPressedCallback
        )
    }

    override fun onDestroy() {
        AppLogger.d("onDestroy Webview::::::::: ${webTab.getUrl()}")
        super.onDestroy()
        webTab.getWebView()?.let { destroyWebView(it) }
        tabViewModel.stop()
        webTab.setWebView(null)
        videoDetectionTabViewModel.stop()
        tabManagerProvider.getTabsListChangeEvent()
            .removeOnPropertyChangedCallback(tabsListChangeListener)
    }

    private fun handleOpenDetectedVideos() {
        videoDetectionTabViewModel.showDetectedVideosEvent.observe(viewLifecycleOwner) {
            navigateToDownloads()
        }
    }

    private fun handleVideoPushed() {
        videoDetectionTabViewModel.videoPushedEvent.observe(viewLifecycleOwner) {
            onVideoPushed()
        }
    }

    private fun onVideoPushed() {
        showToastVideoFound()

        val isDownloadsVisible = isDetectedVideosTabFragmentVisible()
        val isCond = !tabViewModel.isDownloadDialogShown.get() && !isDownloadsVisible
        if (context != null && mainActivity.settingsViewModel.getVideoAlertState()
                .get() && isCond
        ) {
            lifecycleScope.launch(Dispatchers.Main) {
                showAlertVideoFound()
            }
        }
    }

    private fun onVideoPreviewPropagate(
        videoInfo: VideoInfo, format: String, isForce: Boolean
    ) {
        AppLogger.d(
            "onPreviewVideo: ${videoInfo.formats}  $format"
        )
        // start your activity by passing the intent
        startActivity(
            Intent(
                requireContext(), VideoPlayerActivity::class.java
            ).apply {
                // you can add values(if any) to pass to the next class or avoid using `.apply`
                val currFormat = videoInfo.formats.formats.filter {
                    it.format?.contains(
                        format
                    ) ?: false
                }

                putExtra(VideoPlayerFragment.VIDEO_NAME, videoInfo.title)
                if (currFormat.isNotEmpty()) {
                    val headers = currFormat.first().httpHeaders?.let {
                        JSONObject(
                            currFormat.first().httpHeaders ?: emptyMap<String, String>()
                        ).toString()
                    } ?: "{}"

                    putExtra(
                        VideoPlayerFragment.VIDEO_URL, currFormat.first().url
                    )
                    val headersFinal = if (isForce) "{}" else headers
                    putExtra(
                        VideoPlayerFragment.VIDEO_HEADERS, headersFinal
                    )
                }
            })
    }

    private fun onVideoDownloadPropagate(
        videoInfo: VideoInfo, videoTitle: String, format: String
    ) {
        val info = videoInfo.copy(
            title = FileNameCleaner.cleanFileName(videoTitle),
            formats = VideFormatEntityList(videoInfo.formats.formats.filter {
                it.format?.contains(
                    format
                ) ?: false
            })
        )

        mainActivity.mainViewModel.downloadVideoEvent.value = info

        context?.let {
            Toast.makeText(
                it, it.getString(R.string.download_started), Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun recreateWebView(savedInstanceState: Bundle?) {
        if (webTab.getMessage() == null || webTab.getWebView() == null) {
            webTab.setWebView(WebView(requireContext()))
        }

        if (savedInstanceState != null) {
            webTab.getWebView()?.restoreState(savedInstanceState)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(fragmentWebTabBinding: FragmentWebTabBinding) {
        val currentWebView = this.webTab.getWebView()

        val webViewClient = CustomWebViewClient(
            tabViewModel,
            mainActivity.settingsViewModel,
            videoDetectionTabViewModel,
            historyProvider.getHistoryVModel(),
            okHttpProxyClient,
            tabManagerProvider.getUpdateTabEvent(),
            pageTabProvider,
            proxyController,
        )

        val chromeClient = CustomWebChromeClient(
            tabViewModel,
            mainActivity.settingsViewModel,
            tabManagerProvider.getUpdateTabEvent(),
            pageTabProvider,
            fragmentWebTabBinding,
            appUtil,
            mainActivity
        )

        currentWebView?.webChromeClient = chromeClient
        currentWebView?.webViewClient = webViewClient

        val webSettings = webTab.getWebView()?.settings
        val webView = webTab.getWebView()

        webView?.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView?.isScrollbarFadingEnabled = true

        // TODO: turn on third-party from settings
//        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webSettings?.apply {
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportZoom(true)
            setSupportMultipleWindows(true)
            setGeolocationEnabled(false)
            allowContentAccess = true
            allowFileAccess = true
            offscreenPreRaster = false
            displayZoomControls = false
            builtInZoomControls = true
            loadWithOverviewMode = true
            layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
            useWideViewPort = true
            domStorageEnabled = true
            javaScriptEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            if (mainActivity.settingsViewModel.isDesktopMode.get()) {
                userAgentString = BrowserFragment.DESKTOP_USER_AGENT
            }
        }
        fragmentWebTabBinding.webviewContainer.addView(
            webTab.getWebView(),
            LinearLayout.LayoutParams(-1, -1)
        )
    }

    private val onInputTabChangeListener = object : TextWatcher {
        override fun afterTextChanged(s: Editable) {
            val input = s.toString()

            tabViewModel.showTabSuggestions()
            tabViewModel.tabPublishSubject.onNext(input)
        }

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
        }

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        }
    }

    private val suggestionListener = object : SuggestionTabListener {
        override fun onItemClicked(suggestion: HistoryItem) {
            tabViewModel.loadPage(suggestion.url)
        }
    }

    private fun handleChangeTabFocusEvent() {
        var value = -1
        tabViewModel.changeTabFocusEvent.observe(viewLifecycleOwner) { isFocus ->
            isFocus.let {
                if (it) {
                    val oldValue = value
                    val start = dataBinding.etSearch.selectionStart
                    val end = dataBinding.etSearch.selectionEnd
                    value = (start + end) / 2
                    if (oldValue == value) {
                        dataBinding.etSearch.selectAll()

                    }
                    tabViewModel.isTabInputFocused.set(true)
                    appUtil.showSoftKeyboard(dataBinding.etSearch)
                } else {
                    tabViewModel.isTabInputFocused.set(false)
                    appUtil.hideSoftKeyboard(
                        dataBinding.etSearch
                    )
                }
            }
        }
    }

    private fun handleLoadPageEvent() {
        tabViewModel.loadPageEvent.observe(viewLifecycleOwner) { tab ->
            if (tab.getUrl().startsWith("http")) {
                webTab.getWebView()?.stopLoading()
                webTab.getWebView()?.loadUrl(tab.getUrl())
            }
        }
    }

    private fun handleWorkerEvent() {
        workerEventProvider.getWorkerM3u8MpdEvent().observe(viewLifecycleOwner) { state ->
            if (state is DownloadButtonStateCanDownload && state.info?.id?.isNotEmpty() == true) {
                videoDetectionTabViewModel.pushNewVideoInfoToAll(state.info)
                val loadings = videoDetectionTabViewModel.m3u8LoadingList.get()
                loadings?.remove("m3u8")
                videoDetectionTabViewModel.m3u8LoadingList.set(loadings?.toMutableSet())
            }
            if (state is DownloadButtonStateLoading) {
                val loadings = videoDetectionTabViewModel.m3u8LoadingList.get()
                loadings?.add("m3u8")
                videoDetectionTabViewModel.m3u8LoadingList.set(loadings?.toMutableSet())
                videoDetectionTabViewModel.setButtonState(DownloadButtonStateLoading())
            }
            if (state is DownloadButtonStateCanNotDownload) {
                val loadings = videoDetectionTabViewModel.m3u8LoadingList.get()
                loadings?.remove("m3u8")
                videoDetectionTabViewModel.m3u8LoadingList.set(loadings?.toMutableSet())
                videoDetectionTabViewModel.setButtonState(DownloadButtonStateCanNotDownload())
            }
        }

        workerEventProvider.getWorkerMP4Event().observe(viewLifecycleOwner) { state ->
            if (state is DownloadButtonStateCanDownload && state.info?.id?.isNotEmpty() == true) {
                AppLogger.d("Worker MP4 event CanDownload: ${state.info}")
                videoDetectionTabViewModel.pushNewVideoInfoToAll(state.info)
            } else {
                AppLogger.d("Worker MP4 event state: $state")
            }
        }
    }

    private fun handleIndexChangeEvent() {
        tabManagerProvider.getTabsListChangeEvent()
            .addOnPropertyChangedCallback(tabsListChangeListener)
    }

    private val tabsListChangeListener = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            val tabs = tabManagerProvider.getTabsListChangeEvent().get()
            val webTab = tabs?.find { it.id == webTab.id }
            val index = tabs?.indexOf(webTab)
            if (index != null && index in tabs.indices) {
                tabViewModel.thisTabIndex.set(index)
            }
        }
    }

    private fun onWebViewPause() {
        webTab.getWebView()?.onPause()
    }

    private fun onWebViewResume() {
        webTab.getWebView()?.onResume()
    }

    private val tabListener = object : BrowserListener {
        override fun onBrowserMenuClicked() {
            showPopupMenu()
        }

        override fun onBrowserReloadClicked() {
            var url = webTab.getWebView()?.url
            var urlWasChange = false

            if (url?.contains("m.facebook") == true) {
                url = url.replace("m.facebook", "www.facebook")
                urlWasChange = true
                val isDesktop = mainActivity.settingsViewModel.isDesktopMode.get()
                if (!isDesktop) {
                    mainActivity.settingsViewModel.setIsDesktopMode(true)
                }
            }

            val userAgent =
                webTab.getWebView()?.settings?.userAgentString ?: tabViewModel.userAgent.get()
                ?: BrowserFragment.MOBILE_USER_AGENT
            if (url != null) {
                videoDetectionTabViewModel.viewModelScope.launch(videoDetectionTabViewModel.executorReload) {
                    videoDetectionTabViewModel.onStartPage(url, userAgent)
                }

                if (url.contains("www.facebook") && urlWasChange) {
                    tabViewModel.openPage(url)
                    tabViewModel.closeTab(webTab)
                } else {
                    tabViewModel.onPageReload(webTab.getWebView())
                }
            }
        }


        override fun onTabCloseClicked() {
            tabViewModel.closeTab(webTab)
            videoDetectionTabViewModel.cancelAllCheckJobs()
        }

        override fun onBrowserStopClicked() {
            tabViewModel.onPageStop(webTab.getWebView())
        }

        override fun onBrowserBackClicked() {
            val webView = webTab.getWebView()
            val canGoBack = webView?.canGoBack()
            if (canGoBack == true) {
                webView.goBack()
                tabViewModel.onGoBack(webView)
                videoDetectionTabViewModel.cancelAllCheckJobs()
            }

            if (canGoBack == false) {
                if (canGoCounter >= 1) {
                    canGoCounter = 0
                    mainActivity.mainViewModel.openNavDrawerEvent.call()
                } else {
                    canGoCounter++
                }
            }
        }

        override fun onBrowserForwardClicked() {
            val webView = webTab.getWebView()
            val canGoForward = webView?.canGoForward()
            if (canGoForward == true) {
                webView.goForward()
                tabViewModel.onGoForward(webView)
                videoDetectionTabViewModel.cancelAllCheckJobs()
            }
        }
    }

    private fun getWebViewClientCompat(webView: WebView?): CustomWebViewClient? {
        return try {
            val getWebViewClientMethod = WebView::class.java.getMethod("getWebViewClient")
            val client = getWebViewClientMethod.invoke(webView) as? CustomWebViewClient
            client
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun showAlertVideoFound() {
        if (!tabViewModel.isDownloadDialogShown.get()) {
            tabViewModel.isDownloadDialogShown.set(true)
            val client = getWebViewClientCompat(webTab.getWebView())

            client?.videoAlert =
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.video_found)
            client?.videoAlert?.setOnDismissListener {
                client.videoAlert = null
            }
            client?.videoAlert?.setMessage(R.string.whatshould)?.setPositiveButton(
                R.string.view
            ) { dialog, _ ->
                navigateToDownloads()
                tabViewModel.isDownloadDialogShown.set(false)
                dialog.dismiss()
            }?.setNeutralButton(R.string.dontshow) { dialog, _ ->
                mainActivity.settingsViewModel.setShowVideoAlertOff()
                tabViewModel.isDownloadDialogShown.set(false)
                dialog.dismiss()
            }?.setNegativeButton(R.string.all_text_cancel) { dialog, _ ->
                tabViewModel.isDownloadDialogShown.set(false)
                dialog.dismiss()
            }?.show()
        }
    }

    private fun handleOnBackPress() {
        val isBrowserRoute = mainActivity.mainViewModel.currentItem.get() == 0
        val isCurrentTabSelected =
            currentTabIndexProvider.getCurrentTabIndex().get() == requireArguments().getInt(
                TAB_INDEX_KEY
            )
        val isStateResumed = viewLifecycleOwner.lifecycle.currentState == Lifecycle.State.RESUMED

        if (isStateResumed && isBrowserRoute && isCurrentTabSelected && isVisible) {
            webTab.getWebView()?.goBack()
        }
    }

    private fun setUserAgentIsDesktop(isDesktop: Boolean) {
        val settings = webTab.getWebView()?.settings
        if (isDesktop) {
            settings?.userAgentString = BrowserFragment.DESKTOP_USER_AGENT
        } else {
            settings?.userAgentString = null
        }
    }

    private fun addChangeRouteCallBack() {
        mainActivity.mainViewModel.currentItem.removeOnPropertyChangedCallback(changeRouteCallBack)
        mainActivity.mainViewModel.currentItem.addOnPropertyChangedCallback(changeRouteCallBack)
    }

    private val changeRouteCallBack = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            val indexRoute = mainActivity.mainViewModel.currentItem.get()
            val currentTabIndexSelected = currentTabIndexProvider.getCurrentTabIndex().get()
            val isCurrentTabSelected =
                currentTabIndexSelected == requireArguments().getInt(TAB_INDEX_KEY)
            val isBrowserRoute = indexRoute == 0
            val isNotHomeTabSelected = currentTabIndexSelected != HOME_TAB_INDEX
            val isVisible = this@WebTabFragment.isVisible
            if (isBrowserRoute && isNotHomeTabSelected && isCurrentTabSelected && isVisible) {
                activity?.onBackPressedDispatcher?.addCallback(
                    viewLifecycleOwner, backPressedCallback
                )
            } else {
                backPressedCallback.remove()
            }
        }
    }

    private fun showToastVideoFound() {
        val context = context

        if (context != null) {
            Handler(Looper.getMainLooper()).postDelayed({
                videoToast?.cancel()
                videoToast = Toast.makeText(
                    context, context.getString(R.string.video_found), Toast.LENGTH_SHORT
                )
                videoToast?.show()
            }, 1)
        }
    }

    private fun destroyWebView(webView: WebView) {
        val webViewContainer: ViewGroup = webView.parent as ViewGroup
        webViewContainer.removeView(webView)
        webView.destroy()
        webTab.setWebView(null)
    }

    private fun navigateToDownloads() {
        try {
            val currentFragment = this
            val activityFragmentContainer =
                currentFragment.activity?.findViewById<FragmentContainerView>(R.id.fragment_container_view)
            activityFragmentContainer?.let {
                val transaction =
                    currentFragment.requireActivity().supportFragmentManager.beginTransaction()
                val fragment = DetectedVideosTabFragment.newInstance()
                fragment.detectedVideosTabViewModel = videoDetectionTabViewModel
                fragment.candidateFormatListener = downloadListener
                transaction.add(it.id, fragment, "DOWNLOADS_TAB")
                transaction.addToBackStack("DOWNLOADS_TAB")
                transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                transaction.commit()
            }
        } catch (e: ClassCastException) {
            AppLogger.e("Can't get the fragment manager with this")
        }
    }

    private fun isDetectedVideosTabFragmentVisible(): Boolean {
        val fragmentManager = requireActivity().supportFragmentManager
        val fragment =
            fragmentManager.findFragmentByTag("DOWNLOADS_TAB") as? DetectedVideosTabFragment
        return fragment != null && fragment.isAdded && fragment.isVisible && fragment.isResumed
    }

    private val downloadListener = object : DownloadTabListener {
        override fun onCancel() {
            mainActivity.supportFragmentManager.popBackStack()
        }

        override fun onPreviewVideo(
            videoInfo: VideoInfo, format: String, isForce: Boolean
        ) {
            onVideoPreviewPropagate(videoInfo, format, isForce)
        }

        override fun onDownloadVideo(
            videoInfo: VideoInfo, format: String, videoTitle: String
        ) {
            onVideoDownloadPropagate(videoInfo, videoTitle, format)
        }

        override fun onSelectFormat(videoInfo: VideoInfo, format: String) {
            val formats =
                videoDetectionTabViewModel.selectedFormats.get()?.toMutableMap() ?: mutableMapOf()
            formats[videoInfo.id] = format
            videoDetectionTabViewModel.selectedFormats.set(formats)
        }

        override fun onFormatUrlShare(videoInfo: VideoInfo, format: String): Boolean {
            val foundFormat = videoInfo.formats.formats.find { thisFormat ->
                thisFormat.format?.contains(format) == true
            }
            if (foundFormat == null) {
                return false
            }

            ShareCompat.IntentBuilder(mainActivity).setType("text/plain")
                .setChooserTitle("Share Link")
                .setText(foundFormat.url).startChooser()
            return true
        }
    }
}
