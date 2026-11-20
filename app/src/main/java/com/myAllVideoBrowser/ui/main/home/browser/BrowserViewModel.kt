package com.myAllVideoBrowser.ui.main.home.browser

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.ui.main.base.BaseViewModel
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTab
import com.myAllVideoBrowser.util.SingleLiveEvent
import com.myAllVideoBrowser.ui.main.settings.SettingsViewModel
import javax.inject.Inject

class BrowserViewModel @Inject constructor() : BaseViewModel() {

    companion object {
        const val SEARCH_URL = "https://duckduckgo.com/?t=ffab&q=%s"

        var instance: BrowserViewModel? = null
    }

    var settingsModel: SettingsViewModel? = null

    val openPageEvent = SingleLiveEvent<WebTab>()

    val closePageEvent = SingleLiveEvent<WebTab>()

    val selectWebTabEvent = SingleLiveEvent<WebTab>()

    val updateWebTabEvent = SingleLiveEvent<WebTab>()

    private val _workerM3u8MpdEvent = MutableLiveData<DownloadButtonState>()
    val workerM3u8MpdEvent: LiveData<DownloadButtonState> = _workerM3u8MpdEvent

    private val _workerMP4Event = MutableLiveData<DownloadButtonState>()
    val workerMP4Event: LiveData<DownloadButtonState> = _workerMP4Event

    private val _tabs = MutableLiveData(listOf(WebTab.HOME_TAB))
    val tabs: LiveData<List<WebTab>> = _tabs

    private val _currentTab = MutableLiveData(HOME_TAB_INDEX)
    val currentTab: LiveData<Int> = _currentTab

    fun setM3U8Event(state: DownloadButtonState?) {
        if (state == null) {
            return
        }
        _workerM3u8MpdEvent.postValue(state)
    }

    fun setMP4Event(state: DownloadButtonState?) {
        if (state == null) {
            return
        }
        _workerMP4Event.postValue(state)
    }

    fun setTabs(tabsList: List<WebTab>) {
        if (_tabs.value != tabsList) {
            _tabs.value = tabsList
        }
    }

    fun setCurrentTab(index: Int) {
        if (_currentTab.value != index) {
            _currentTab.value = index
        }
    }

    override fun start() {
        instance = this
    }

    override fun stop() {
        instance = null
    }
}

abstract class DownloadButtonState

class DownloadButtonStateLoading : DownloadButtonState()

class DownloadButtonStateCanDownload(val info: VideoInfo?) : DownloadButtonState()
class DownloadButtonStateCanNotDownload : DownloadButtonState()
