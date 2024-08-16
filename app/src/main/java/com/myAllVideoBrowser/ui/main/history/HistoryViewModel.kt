package com.myAllVideoBrowser.ui.main.history

import androidx.databinding.ObservableField
import androidx.lifecycle.viewModelScope
import com.myAllVideoBrowser.data.local.room.entity.HistoryItem
import com.myAllVideoBrowser.data.repository.HistoryRepository
import com.myAllVideoBrowser.ui.main.base.BaseViewModel
import com.myAllVideoBrowser.util.scheduler.BaseSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import javax.inject.Inject

class HistoryViewModel @Inject constructor(
    private val historyRepository: HistoryRepository,
    private val baseSchedulers: BaseSchedulers
) :
    BaseViewModel() {

    var historyItems = ObservableField<List<HistoryItem>>(emptyList())

    var searchHistoryItems = ObservableField<List<HistoryItem>>(emptyList())

    val searchQuery = ObservableField("")

    val isLoadingHistory = ObservableField(true)

    val executorSingleHistory = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private val disposableContainer = CompositeDisposable()

    private val historyExecutor = Executors.newFixedThreadPool(1).asCoroutineDispatcher()

    override fun start() {
        fetchAllHistory()
    }

    override fun stop() {
        disposableContainer.clear()
    }

    private fun fetchAllHistory() {
        isLoadingHistory.set(true)

        disposableContainer.clear()
        disposableContainer.add(
            historyRepository.getAllHistory().subscribeOn(baseSchedulers.computation)
                .observeOn(baseSchedulers.computation).subscribe {
                    historyItems.set(it)
                    isLoadingHistory.set(false)
                })
    }

    fun saveHistory(historyItem: HistoryItem) {
        viewModelScope.launch(historyExecutor) {
            try {
                historyRepository.saveHistory(historyItem)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    fun deleteHistory(historyItem: HistoryItem) {
        viewModelScope.launch(historyExecutor) {
            try {
                val newItems = historyItems.get()?.filter { it.id != historyItem.id }
                historyItems.set(newItems)
                historyRepository.deleteHistory(historyItem)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    fun queryHistory(query: String) {
        if (query.isEmpty()) {
            searchHistoryItems.set(emptyList())
        }
        if (query.isNotEmpty()) {
            val filtered = historyItems.get()
                ?.filter { it.url.contains(query) || it.title?.contains(query) ?: false }
            searchHistoryItems.set(filtered ?: emptyList())
        }
    }

    fun clearHistory() {
        viewModelScope.launch(historyExecutor) {
            isLoadingHistory.set(true)
            historyRepository.deleteAllHistory()
            historyItems.set(emptyList())
            searchHistoryItems.set(emptyList())
            isLoadingHistory.set(false)
        }
    }
}