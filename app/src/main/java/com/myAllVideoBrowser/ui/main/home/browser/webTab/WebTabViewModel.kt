package com.myAllVideoBrowser.ui.main.home.browser.webTab

import android.webkit.WebView
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.HistoryItem
import com.myAllVideoBrowser.data.repository.HistoryRepository
import com.myAllVideoBrowser.ui.main.base.BaseViewModel
import com.myAllVideoBrowser.util.CookieUtils
import com.myAllVideoBrowser.util.SingleLiveEvent
import com.myAllVideoBrowser.util.get
import com.myAllVideoBrowser.util.scheduler.BaseSchedulers
import io.reactivex.rxjava3.core.BackpressureStrategy
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.subjects.PublishSubject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class WebTabViewModel @Inject constructor(
    private val historyRepository: HistoryRepository,
    private val baseSchedulers: BaseSchedulers,
) : BaseViewModel() {
    private val _isTabInputFocused = MutableLiveData(false)
    val isTabInputFocused: LiveData<Boolean> = _isTabInputFocused
    val changeTabFocusEvent = SingleLiveEvent<Boolean>()

    private val _thisTabIndex = MutableLiveData(-1)
    val thisTabIndex: LiveData<Int> = _thisTabIndex

    private val _isDownloadDialogShown = MutableLiveData(false)
    val isDownloadDialogShown: LiveData<Boolean> = _isDownloadDialogShown

    lateinit var tabPublishSubject: PublishSubject<String>

    private val _listTabSuggestions = MutableLiveData(listOf<HistoryItem>())
    var listTabSuggestions: LiveData<List<HistoryItem>> = _listTabSuggestions

    private val _isShowProgress = MutableStateFlow(true)
    val isShowProgress: StateFlow<Boolean> = _isShowProgress

    private val _progress = MutableLiveData(0)
    val progress: LiveData<Int> = _progress

    private val _progressIcon = MutableLiveData(R.drawable.ic_refresh_24dp)
    val progressIcon: LiveData<Int> = _progressIcon

    private val _currentTitle = MutableLiveData("")
    val currentTitle: LiveData<String> = _currentTitle

    private val _userAgent = MutableLiveData("")
    val userAgent: LiveData<String> = _userAgent

    // This events from BrowserFragment
    lateinit var openPageEvent: SingleLiveEvent<WebTab>
    lateinit var closePageEvent: SingleLiveEvent<WebTab>

    val loadPageEvent = SingleLiveEvent<WebTab>()

    private val _tabUrl = MutableLiveData("")
    val tabUrlInput: LiveData<String> = _tabUrl

    private var tabSuggestionJob: Job? = null

    fun setUserAgent(userAgent: String) {
        _userAgent.value = userAgent
    }

    fun setIsShowProgress(isShow: Boolean) {
        _isShowProgress.value = isShow
    }

    fun setIsDownloadDialogShown(isShown: Boolean) {
        _isDownloadDialogShown.value = isShown
    }

    fun setIsTabInputFocused(isFocus: Boolean) {
        _isTabInputFocused.value = isFocus
    }

    fun setThisTabIndex(index: Int) {
        _thisTabIndex.value = index
    }

    fun setProgress(newProgress: Int) {
        _progress.value = newProgress
    }

    override fun start() {
        tabPublishSubject = PublishSubject.create()
        viewModelScope.launch {
            isShowProgress.collect { isShow ->
                when (isShow) {
                    true -> setProgressIcon(R.drawable.close_24px)
                    false -> setProgressIcon(R.drawable.ic_refresh_24dp)
                }
            }
        }
    }

    override fun stop() {
    }

    fun finishPage(url: String) {
        setTabTextInput(url, true)
        setIsShowProgress(false)
    }

    fun onStartPage(url: String, title: String?) {
        setTabTextInput(url)
        setIsShowProgress(true)
        setCurrentTitle(title ?: "no title")
        setTabUrl(url)
    }

    fun onUpdateVisitedHistory(url: String, title: String?, userAgent: String?) {
        if (url.startsWith("http")) {
            setTabTextInput(url)
            setIsShowProgress(true)
            setTabUrl(url)
        }
    }

    fun showTabSuggestions() {
        if (tabSuggestionJob != null && tabSuggestionJob?.isActive == true) {
            tabSuggestionJob?.cancel()
        }
        tabSuggestionJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val list = fetchListTabSuggestions().blockingFirst().reversed()
                if (list.size > 50) {
                    _listTabSuggestions.postValue(list.subList(0, 50))
                } else {
                    _listTabSuggestions.postValue(list)
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    private fun fetchListTabSuggestions(): Flowable<List<HistoryItem>> {
        return Flowable.combineLatest(
            tabPublishSubject.debounce(300, TimeUnit.MILLISECONDS)
                .toFlowable(BackpressureStrategy.LATEST), historyRepository.getAllHistory().take(1)
        ) { input, suggestions ->
            setTabUrl(input)

            val listSuggestions = suggestions.filter { historyItem ->
                historyItem.url.contains(
                    input
                )
            }
            listSuggestions.toList()
        }.take(1).observeOn(baseSchedulers.single)
            .subscribeOn(baseSchedulers.computation) // MAIN_TH
    }

    fun changeTabFocus(isFocus: Boolean) {
        this._isTabInputFocused.value = isFocus
        changeTabFocusEvent.value = isFocus
    }


    fun openPage(input: String) {
        if (input.isNotEmpty()) {
            changeTabFocus(false)
            openPageEvent.value = WebTabFactory.createWebTabFromInput(input)
        }
    }

    fun loadPage(input: String) {
        if (input.isNotEmpty()) {
            changeTabFocus(false)
            val tab = WebTabFactory.createWebTabFromInput(input)
            setTabTextInput(tab.getUrl())

            loadPageEvent.value = tab
        }
    }

    fun setTabTextInput(input: String?, isForce: Boolean = false) {
        if (input.isNullOrEmpty()) {
            return
        }

        if (!isTabInputFocused.get() || isForce) {
            setTabUrl(input)
        }
    }

    fun closeTab(webTab: WebTab) {
        closePageEvent.value = webTab
    }

    fun onPageReload(urlLoader: WebView?) {
        changeTabFocus(false)
        setIsShowProgress(true)

        urlLoader?.reload()
    }

    fun onPageStop(urlLoader: WebView?) {
        changeTabFocus(false)
        setIsShowProgress(false)
        urlLoader?.stopLoading()
    }

    fun onGoBack(webView: WebView) {
        changeTabFocus(false)
        setIsShowProgress(true)
        webView.goBack()
    }

    fun onGoForward(webView: WebView) {
        changeTabFocus(false)
        setIsShowProgress(true)
        webView.goForward()
    }

    private fun setProgressIcon(icon: Int) {
        _progressIcon.value = icon
    }

    private fun setTabUrl(url: String) {
        _tabUrl.postValue(CookieUtils.sanitizeUrlOrText(url))
    }

    private fun setCurrentTitle(title: String) {
        _currentTitle.value = CookieUtils.sanitizeUrlOrText(title)
    }
}
