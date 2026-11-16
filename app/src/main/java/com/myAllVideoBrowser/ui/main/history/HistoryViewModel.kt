package com.myAllVideoBrowser.ui.main.history

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.myAllVideoBrowser.data.local.room.entity.HistoryItem
import com.myAllVideoBrowser.data.repository.HistoryRepository
import com.myAllVideoBrowser.ui.main.base.BaseViewModel
import com.myAllVideoBrowser.util.scheduler.BaseSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import javax.inject.Inject

class HistoryViewModel @Inject constructor(
    private val historyRepository: HistoryRepository,
) :
    BaseViewModel() {

    private val _historyItems = MutableLiveData<List<HistoryItem>>(emptyList())
    val historyItems: LiveData<List<HistoryItem>> = _historyItems

    private val _searchHistoryItems = MutableLiveData<List<HistoryItem>>(emptyList())
    val searchHistoryItems: LiveData<List<HistoryItem>> = _searchHistoryItems

    private val _searchQuery = MutableLiveData("")
    val searchQuery: LiveData<String> = _searchQuery

    private val _isLoadingHistory = MutableLiveData(true)
    val isLoadingHistory: LiveData<Boolean> = _isLoadingHistory

    val executorSingleHistory = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private val historyExecutor = Executors.newFixedThreadPool(1).asCoroutineDispatcher()

    private val additionalExecutor = Executors.newFixedThreadPool(1).asCoroutineDispatcher()

    override fun start() {
        fetchAllHistory()
    }

    override fun stop() {
    }

    private fun fetchAllHistory() {
        _isLoadingHistory.value = true

        viewModelScope.launch(additionalExecutor) {
            val history = historyRepository.getAllHistory().blockingFirst()
            _historyItems.postValue(history)
            _isLoadingHistory.postValue(false)
        }
    }

    fun loadHistory() {
        fetchAllHistory()
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
                val currentItems = _historyItems.value ?: emptyList()
                val newItems = currentItems.filter { it.id != historyItem.id }
                _historyItems.postValue(newItems)
                historyRepository.deleteHistory(historyItem)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    fun queryHistory(query: String) {
        _searchQuery.value = query
        if (query.isEmpty()) {
            _searchHistoryItems.value = emptyList()
        }
        if (query.isNotEmpty()) {
            val filtered = _historyItems.value
                ?.filter { it.url.contains(query) || it.title?.contains(query) ?: false }
            _searchHistoryItems.value = filtered ?: emptyList()
        }
    }

    fun clearHistory() {
        viewModelScope.launch(historyExecutor) {
            withContext(Dispatchers.Main) {
                _isLoadingHistory.value = true
            }
            historyRepository.deleteAllHistory()
            withContext(Dispatchers.Main) {
                _historyItems.value = emptyList()
                _searchHistoryItems.value = emptyList()
                _isLoadingHistory.value = false
            }
        }
    }
}