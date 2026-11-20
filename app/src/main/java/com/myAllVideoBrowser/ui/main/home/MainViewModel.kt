package com.myAllVideoBrowser.ui.main.home

import android.graphics.Bitmap
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.myAllVideoBrowser.data.local.room.entity.PageInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.data.repository.TopPagesRepository
import com.myAllVideoBrowser.ui.main.base.BaseViewModel
import com.myAllVideoBrowser.ui.main.home.browser.BrowserServicesProvider
import com.myAllVideoBrowser.util.FaviconUtils
import com.myAllVideoBrowser.util.SingleLiveEvent
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import javax.inject.Inject

class MainViewModel @Inject constructor(
    private val topPagesRepository: TopPagesRepository,
) : BaseViewModel() {

    var browserServicesProvider: BrowserServicesProvider? = null

    private val _openedUrl = MutableLiveData<String?>()
    val openedUrl: LiveData<String?> = _openedUrl

    private val _openedText = MutableLiveData<String?>()
    val openedText: LiveData<String?> = _openedText

    private val _isBrowserCurrent = MutableLiveData(false)
    val isBrowserCurrent: LiveData<Boolean> = _isBrowserCurrent

    private val _currentItem = MutableLiveData<Int>()
    val currentItem: LiveData<Int> = _currentItem

    private val _isWebViewAvailable = MutableLiveData(true)
    val isWebViewAvailable: LiveData<Boolean> = _isWebViewAvailable

    private val _offScreenPageLimit = MutableLiveData(4)
    val offScreenPageLimit: LiveData<Int> = _offScreenPageLimit

    // pair - format:url
    private val _selectedFormatTitle = MutableLiveData<Pair<String, String>?>()
    val selectedFormatTitle: LiveData<Pair<String, String>?> = _selectedFormatTitle

    private val _currentOriginal = MutableLiveData<String?>()
    val currentOriginal: LiveData<String?> = _currentOriginal

    val downloadVideoEvent = SingleLiveEvent<VideoInfo>()

    val openDownloadedVideoEvent = SingleLiveEvent<String>()

    val openNavDrawerEvent = SingleLiveEvent<Unit?>()

    private val _bookmarksList = MutableLiveData<List<PageInfo>>(emptyList())
    val bookmarksList: LiveData<List<PageInfo>> = _bookmarksList

    private val executorSingle = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private val executorMoverSingle = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    fun setOpenedUrl(url: String?) {
        if (_openedUrl.value != url) {
            _openedUrl.value = url
        }
    }

    fun setOpenedText(text: String?) {
        if (_openedText.value != text) {
            _openedText.value = text
        }
    }

    fun setIsBrowserCurrent(isCurrent: Boolean) {
        if (_isBrowserCurrent.value != isCurrent) {
            _isBrowserCurrent.value = isCurrent
        }
    }

    fun setCurrentItem(item: Int?) {
        if (item != null && _currentItem.value != item) {
            _currentItem.value = item
        }
    }

    fun setIsWebViewAvailable(isAvailable: Boolean) {
        if (_isWebViewAvailable.value != isAvailable) {
            _isWebViewAvailable.value = isAvailable
        }
    }

    fun setOffScreenPageLimit(limit: Int) {
        if (_offScreenPageLimit.value != limit) {
            _offScreenPageLimit.value = limit
        }
    }

    fun setSelectedFormatTitle(title: Pair<String, String>?) {
        if (_selectedFormatTitle.value != title) {
            _selectedFormatTitle.value = title
        }
    }

    fun setCurrentOriginal(original: String?) {
        if (_currentOriginal.value != original) {
            _currentOriginal.value = original
        }
    }

    fun setBookmarksList(bookmarks: List<PageInfo>) {
        if (_bookmarksList.value != bookmarks) {
            _bookmarksList.value = bookmarks
        }
    }

    override fun start() {
        updateTopPages()
    }

    override fun stop() {
        executorSingle.cancel()
        executorMoverSingle.cancel()
    }

    fun bookmark(url: String, name: String, favicon: Bitmap?) {
        viewModelScope.launch(executorMoverSingle) {
            var bookmarks = topPagesRepository.getTopPages().toMutableList()
            val faviconUrl = FaviconUtils.getFaviconUrl(url)
            val newBookmark = PageInfo(
                link = url, order = bookmarks.size, name = name, favicon = faviconUrl
            )
            bookmarks.add(newBookmark)
            bookmarks = bookmarks.mapIndexed { index, pageInfo ->
                pageInfo.order = index
                pageInfo
            }.toMutableList()
            _bookmarksList.postValue(bookmarks)
            topPagesRepository.replaceBookmarksWith(bookmarks)
        }
    }

    fun loadBookmarks() {
        viewModelScope.launch(executorSingle) {
            val pages = try {
                topPagesRepository.getTopPages()
            } catch (e: Throwable) {
                e.printStackTrace()
                null
            }

            if (pages != null) {
                _bookmarksList.postValue(pages)
            } else {
                _bookmarksList.postValue(emptyList())
            }
        }
    }

    private fun updateTopPages() {
        loadBookmarks()
        viewModelScope.launch(executorSingle) {
            try {
                topPagesRepository.updateLocalStorageFavicons().collect { pageInfo ->
                    val bookmrks = _bookmarksList.value?.toMutableList()
                    val index = bookmrks?.indexOfFirst { it.link == pageInfo.link }
                    if (index != null && index != -1) {
                        bookmrks[index] = pageInfo
                        _bookmarksList.postValue(bookmrks)
                    } else if (bookmrks != null) {
                        bookmrks.add(pageInfo)
                        _bookmarksList.postValue(bookmrks)
                    }
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    fun updateBookmarks(bookmarks: List<PageInfo>) {
        viewModelScope.launch(executorMoverSingle) {
            val updatedBookMarks = bookmarks.mapIndexed { index, value ->
                value.order = index
                value
            }
            topPagesRepository.replaceBookmarksWith(updatedBookMarks)
            _bookmarksList.postValue(bookmarks)
        }
    }
}
