package com.myAllVideoBrowser.ui.main.home

import android.graphics.Bitmap
import androidx.databinding.ObservableBoolean
import androidx.databinding.ObservableField
import androidx.lifecycle.viewModelScope
import com.myAllVideoBrowser.data.local.room.entity.PageInfo
//import com.allVideoDownloaderXmaster.OpenForTesting
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

//@OpenForTesting
class MainViewModel @Inject constructor(
    private val topPagesRepository: TopPagesRepository,
) : BaseViewModel() {

    var browserServicesProvider: BrowserServicesProvider? = null

    val openedUrl = ObservableField<String?>()

    val openedText = ObservableField<String?>()

    val isBrowserCurrent = ObservableBoolean(false)

    val currentItem = ObservableField<Int>()

    val offScreenPageLimit = ObservableField(4)

    // pair - format:url
    val selectedFormatTitle = ObservableField<Pair<String, String>?>()

    val currentOriginal = ObservableField<String>()

    val downloadVideoEvent = SingleLiveEvent<VideoInfo>()

    val openDownloadedVideoEvent = SingleLiveEvent<String>()

    val openNavDrawerEvent = SingleLiveEvent<Unit?>()

    var bookmarksList: ObservableField<MutableList<PageInfo>> = ObservableField(mutableListOf())

    private val executorSingle = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private val executorMoverSingle = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

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
            val faviconBytes = FaviconUtils.bitmapToBytes(favicon)
            val newBookmark = PageInfo(
                link = url, order = bookmarks.size, name = name, favicon = faviconBytes
            )
            bookmarks.add(newBookmark)
            bookmarks = bookmarks.mapIndexed { index, pageInfo ->
                pageInfo.order = index
                pageInfo
            }.toMutableList()
            bookmarksList.set(bookmarks)
            topPagesRepository.replaceBookmarksWith(bookmarks)
        }
    }

    private fun updateTopPages() {
        viewModelScope.launch(executorSingle) {
            val pages = try {
                topPagesRepository.getTopPages()
            } catch (e: Throwable) {
                e.printStackTrace()
                null
            }

            if (!pages.isNullOrEmpty()) {
                bookmarksList.set(pages.toMutableList())
            }

            try {
                topPagesRepository.updateLocalStorageFavicons().collect { pageInfo ->
                    val bookmrks = bookmarksList.get()
                    val index = bookmrks?.indexOfFirst { it.link == pageInfo.link }
                    if (index != null && index != -1) {
                        bookmrks[index] = pageInfo
                        bookmarksList.set(bookmrks.toMutableList())
                    } else {
                        bookmarksList.set((bookmarksList.get()?.plus(pageInfo))?.toMutableList())
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
            bookmarksList.set(bookmarks.toMutableList())
        }
    }
}
