package com.myAllVideoBrowser.ui.main.home.browser

//import com.allVideoDownloaderXmaster.OpenForTesting

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.*
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.widget.Toast
import androidx.activity.addCallback
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat.getSystemService
import androidx.core.view.GravityCompat
import androidx.databinding.Observable
import androidx.databinding.ObservableField
import androidx.databinding.ObservableInt
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.remote.service.YoutubedlHelper
import com.myAllVideoBrowser.databinding.FragmentBrowserBinding
import com.myAllVideoBrowser.ui.component.adapter.WebTabsAdapter
import com.myAllVideoBrowser.ui.component.adapter.WebTabsListener
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.ui.main.history.HistoryViewModel
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.ui.main.home.MainViewModel
import com.myAllVideoBrowser.ui.main.home.browser.detectedVideos.VideoDetectionAlgVModel
import com.myAllVideoBrowser.ui.main.home.browser.homeTab.BrowserHomeFragment
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTab
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTabFragment
import com.myAllVideoBrowser.ui.main.progress.WrapContentLinearLayoutManager
import com.myAllVideoBrowser.ui.main.settings.SettingsViewModel
import com.myAllVideoBrowser.util.*
import com.myAllVideoBrowser.util.proxy_utils.CustomProxyController
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import io.reactivex.rxjava3.disposables.CompositeDisposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject


interface BrowserServicesProvider : TabManagerProvider, PageTabProvider, HistoryProvider,
    WorkerEventProvider, CurrentTabIndexProvider

interface TabManagerProvider {
    fun getOpenTabEvent(): SingleLiveEvent<WebTab>

    fun getCloseTabEvent(): SingleLiveEvent<WebTab>

    fun getUpdateTabEvent(): SingleLiveEvent<WebTab>

    fun getTabsListChangeEvent(): ObservableField<List<WebTab>>
}

interface PageTabProvider {
    fun getPageTab(position: Int): WebTab
}

interface HistoryProvider {
    fun getHistoryVModel(): HistoryViewModel
}

interface WorkerEventProvider {
    fun getWorkerM3u8MpdEvent(): MutableLiveData<DownloadButtonState>
}

interface CurrentTabIndexProvider {
    fun getCurrentTabIndex(): ObservableInt
}

interface BrowserListener {
    fun onBrowserMenuClicked()

    fun onBrowserReloadClicked()

    fun onTabCloseClicked()

    fun onBrowserStopClicked()

    fun onBrowserBackClicked()

    fun onBrowserForwardClicked()
}

const val HOME_TAB_INDEX = 0

const val TAB_INDEX_KEY = "TAB_INDEX_KEY"

//@OpenForTesting
class BrowserFragment : BaseFragment(), BrowserServicesProvider {

    companion object {
        fun newInstance() = BrowserFragment()
        var DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36"

        // TODO different agents for different androids
        var MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/92.0.4515.131 Mobile Safari/537.36"
    }

    private lateinit var tabsAdapter: TabsFragmentStateAdapter

    private lateinit var drawerAdapter: WebTabsAdapter

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    @Inject
    lateinit var mainActivity: MainActivity

    @Inject
    lateinit var appUtil: AppUtil

    @Inject
    lateinit var proxyController: CustomProxyController

    @Inject
    lateinit var sharedPrefHelper: SharedPrefHelper

    @Inject
    lateinit var okHttpProxyClient: OkHttpProxyClient

    @Inject
    lateinit var youtubedlHelper: YoutubedlHelper

    @VisibleForTesting
    internal lateinit var dataBinding: FragmentBrowserBinding

    private lateinit var browserViewModel: BrowserViewModel

    private lateinit var mainViewModel: MainViewModel

    private lateinit var historyModel: HistoryViewModel

    private lateinit var settingsModel: SettingsViewModel

    private lateinit var videoDetectionModel: VideoDetectionAlgVModel

    private val compositeDisposable = CompositeDisposable()

    private var backPressedOnce = false

    private val serviceWorkerClient = object : ServiceWorkerClient() {
        override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            if (url.contains(".m3u8") || url.contains(".mpd") || url.contains(".txt")) {
                lifecycleScope.launch(Dispatchers.Main) {
                    val okRequest = CookieUtils.webRequestToHttpWithCookies(request)
                    if (okRequest != null) {
                        videoDetectionModel.verifyLinkStatus(okRequest)
                    }
                }
            }

            return super.shouldInterceptRequest(request)
        }
    }

    inner class TabsFragmentStateAdapter(private var webTabsRoutes: List<WebTab>) :
        FragmentStateAdapter(this) {
        fun setRoutes(newRoutes: List<WebTab>) {
            webTabsRoutes = newRoutes

            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = webTabsRoutes.size

        override fun getItemId(position: Int): Long {
            return webTabsRoutes[position].id.hashCode().toLong()
        }

        override fun containsItem(itemId: Long): Boolean {
            val webTab = webTabsRoutes.find { it.id.hashCode().toLong() == itemId }
            return webTab != null
        }

        override fun createFragment(position: Int): Fragment {
            if (position == HOME_TAB_INDEX) {
                return createHomeTabFragment()
            }

            return createTabFragment(position)
        }
    }

    private fun createHomeTabFragment(): Fragment {
        return BrowserHomeFragment.newInstance()
    }

    override fun getOpenTabEvent(): SingleLiveEvent<WebTab> {
        return browserViewModel.openPageEvent
    }

    override fun getCloseTabEvent(): SingleLiveEvent<WebTab> {
        return browserViewModel.closePageEvent
    }

    override fun getUpdateTabEvent(): SingleLiveEvent<WebTab> {
        return browserViewModel.updateWebTabEvent
    }

    override fun getTabsListChangeEvent(): ObservableField<List<WebTab>> {
        return browserViewModel.tabs
    }

    override fun getPageTab(position: Int): WebTab {
        val list = browserViewModel.tabs.get() ?: listOf(WebTab.HOME_TAB)
        if (position in list.indices) {
            return list[position]
        }
        return WebTab("error", "error")
    }

    private fun createTabFragment(index: Int): Fragment {
        val fragment = WebTabFragment.newInstance().apply {
            val args = Bundle().apply {
                putInt(TAB_INDEX_KEY, index)
            }
            arguments = args
        }

        return fragment
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val swController = ServiceWorkerController.getInstance()
        swController.setServiceWorkerClient(serviceWorkerClient)
        swController.serviceWorkerWebSettings.allowContentAccess = true

        mainViewModel = mainActivity.mainViewModel
        browserViewModel = ViewModelProvider(this, viewModelFactory)[BrowserViewModel::class.java]
        historyModel = ViewModelProvider(this, viewModelFactory)[HistoryViewModel::class.java]
        videoDetectionModel =
            ViewModelProvider(this, viewModelFactory)[VideoDetectionAlgVModel::class.java]

        videoDetectionModel.settingsModel = mainActivity.settingsViewModel
        browserViewModel.settingsModel = mainActivity.settingsViewModel
        settingsModel = mainActivity.settingsViewModel

        mainActivity.mainViewModel.browserServicesProvider = this

        tabsAdapter = TabsFragmentStateAdapter(emptyList())

        drawerAdapter = WebTabsAdapter(emptyList(), tabsListener)

        val webTabsManagerLayout =
            WrapContentLinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
        webTabsManagerLayout.reverseLayout = true

        val color = getThemeBackgroundColor()

        dataBinding = FragmentBrowserBinding.inflate(inflater, container, false).apply {
            this.viewPager.adapter = tabsAdapter
            this.viewPager.setSwipeThreshold(500)
            this.viewPager.setOnGoThroughListener(onGoThroughListener)
            this.viewPager.isUserInputEnabled = false
            this.tabsList.layoutManager = webTabsManagerLayout
            this.tabsList.adapter = drawerAdapter
            this.drawerLayoutContent.setBackgroundColor(color)

            this.viewModel = browserViewModel
        }
//        mainActivity.setNativeMainContainer(dataBinding.mainNativeAdContainer)

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            onBackPressed()
        }

        videoDetectionModel.downloadButtonState.addOnPropertyChangedCallback(object :
            Observable.OnPropertyChangedCallback() {
            override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
                lifecycleScope.launch(Dispatchers.Main) {
                    browserViewModel.workerM3u8MpdEvent.value =
                        videoDetectionModel.downloadButtonState.get()
                }
            }
        })

        return dataBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        browserViewModel.start()
        handlePressWebTabEvent()
        handleOpenTabEvent()
        handleCloseWebTabEventEvent()
        handleOpenNavDrawerEvent()
        handleUpdateWebTabEventEvent()
        checkIsPowerSaveMode()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        browserViewModel.stop()
        videoDetectionModel.stop()
        compositeDisposable.clear()
    }

    override fun getHistoryVModel(): HistoryViewModel {
        return this.historyModel
    }

    override fun getWorkerM3u8MpdEvent(): MutableLiveData<DownloadButtonState> {
        return browserViewModel.workerM3u8MpdEvent
    }

    override fun getCurrentTabIndex(): ObservableInt {
        return browserViewModel.currentTab
    }

    private val tabsListener = object : WebTabsListener {
        override fun onCloseTabClicked(webTab: WebTab) {

            browserViewModel.closePageEvent.value = webTab
        }

        override fun onSelectTabClicked(webTab: WebTab) {
            browserViewModel.selectWebTabEvent.value = webTab
        }
    }

    private fun handlePressWebTabEvent() {
        browserViewModel.selectWebTabEvent.observe(viewLifecycleOwner) { webTab ->
            val index = browserViewModel.tabs.get()?.indexOf(webTab) ?: 0
            browserViewModel.currentTab.set(index.coerceAtLeast(0))
        }
    }

    // TODO: Show dialog with variants: "Open in New Tab", "Load in Current Tab", "Block", "Don't show again"
    private fun handleOpenTabEvent() {
        browserViewModel.openPageEvent.observe(viewLifecycleOwner) { webTab ->
            val newList = browserViewModel.tabs.get()?.plus(webTab) ?: emptyList()
            browserViewModel.tabs.set(newList)
            val index = newList.indexOf(webTab)
            browserViewModel.currentTab.set(index.coerceAtLeast(0))
        }
    }

    private fun handleCloseWebTabEventEvent() {
        browserViewModel.closePageEvent.observe(viewLifecycleOwner) { webTab ->
            val tabs =
                browserViewModel.tabs.get()?.toMutableList() ?: mutableListOf(WebTab.HOME_TAB)
            val tabToClose = tabs.find { it.id == webTab.id }
            val index = tabs.indexOf(tabToClose)
            if (index in tabs.indices && index != HOME_TAB_INDEX) {
                tabs.removeAt(index)
            }

            if (browserViewModel.currentTab.get() == index) {
                val newIndex = (index - 1).coerceAtLeast(0)
                browserViewModel.currentTab.set(newIndex)
            }

            browserViewModel.tabs.set(tabs)
        }
    }

    private fun handleUpdateWebTabEventEvent() {
        browserViewModel.updateWebTabEvent.observe(viewLifecycleOwner) { webTab ->
            val tabs = browserViewModel.tabs.get()?.toMutableList()
            val tabToUpdate = tabs?.find { it.id == webTab.id }
            val updateIndex = tabs?.indexOf(tabToUpdate)

            if (updateIndex != null && updateIndex in tabs.indices) {
                tabs[updateIndex] = webTab
            }

            browserViewModel.tabs.set(tabs ?: emptyList())
        }
    }

    private fun handleOpenNavDrawerEvent() {
        mainViewModel.openNavDrawerEvent.observe(viewLifecycleOwner) {
            val isOpened = dataBinding.drawerLayout.isDrawerOpen(GravityCompat.START)
            if (isOpened) {
                dataBinding.drawerLayout.close()
            } else {
                dataBinding.drawerLayout.open()
            }
        }
    }

    private fun checkIsPowerSaveMode() {
        val context = this.requireContext()
        val pwManager = getSystemService(context, PowerManager::class.java)
        if (pwManager?.isPowerSaveMode == true) {
            MaterialAlertDialogBuilder(context).setTitle(R.string.warning)
                .setMessage(R.string.powerSave).setPositiveButton(
                    R.string.ok
                ) { dialog, _ ->
                    dialog.dismiss()
                }.show()
        }
    }

    private fun onBackPressed() {
        val rootPagerIndex = mainActivity.mainViewModel.currentItem.get() ?: 0
        if (rootPagerIndex > 0) {
            mainActivity.mainViewModel.currentItem.set(HOME_TAB_INDEX)
        }
        if (rootPagerIndex == HOME_TAB_INDEX) {
            if (backPressedOnce) {
                requireActivity().finish()
                return
            }

            backPressedOnce = true
            Toast.makeText(requireContext(), "Press Back Again to exit", Toast.LENGTH_SHORT).show()

            Handler(Looper.getMainLooper()).postDelayed({
                backPressedOnce = false
            }, 2000)
        }
    }

    private val onGoThroughListener = object : OnGoThroughListener {
        override fun onRightGoThrough() {
            val currentTabIndex = browserViewModel.currentTab.get()
            if (currentTabIndex == 0) {
                mainViewModel.currentItem.set((mainViewModel.currentItem.get() ?: 0) + 1)
            }
        }
    }
}
