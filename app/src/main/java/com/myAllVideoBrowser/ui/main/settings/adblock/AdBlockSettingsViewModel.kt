package com.myAllVideoBrowser.ui.main.settings.adblock

import androidx.databinding.ObservableArrayList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myAllVideoBrowser.data.local.room.entity.AdBlockList
import com.myAllVideoBrowser.data.repository.AdBlockRepository
import com.myAllVideoBrowser.util.SingleLiveEvent
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

class AdBlockSettingsViewModel @Inject constructor(
    private val repository: AdBlockRepository
) : ViewModel() {

    init {
        viewModelScope.launch {
            repository.checkAndPrepopulateDefaults()
            repository.activeDownloads.collect { ids ->
                downloadingIds.clear()
                downloadingIds.addAll(ids)
            }
        }
    }

    val adBlockLists: StateFlow<List<AdBlockList>> = repository.getAllLists()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val downloadingIds = ObservableArrayList<Int>()

    val showAddDialogEvent = SingleLiveEvent<Unit?>()
    val errorEvent = SingleLiveEvent<String>()

    fun onAddListClicked() {
        showAddDialogEvent.call()
    }

    fun addCustomList(name: String, url: String) {
        if (name.isBlank() || url.isBlank()) {
            errorEvent.value = "Name and URL cannot be empty"
            return
        }
        viewModelScope.launch {
            repository.addCustomList(name, url)
        }
    }

    fun toggleList(list: AdBlockList) {
        if (!list.isDownloaded && list.url != null && !list.isDownloadFailed) {
            downloadList(list)
            return
        }
        viewModelScope.launch {
            repository.toggleList(list)
        }
    }

    fun deleteList(list: AdBlockList) {
        viewModelScope.launch {
            repository.deleteList(list)
        }
    }

    fun downloadList(list: AdBlockList) {
        if (downloadingIds.contains(list.id)) return
        
        viewModelScope.launch {
            downloadingIds.add(list.id)
            val success = repository.downloadList(list)
            downloadingIds.remove(list.id)
            if (!success) {
                errorEvent.value = "Failed to download list from ${list.url}"
            }
        }
    }

    fun isDownloading(id: Int): Boolean {
        return downloadingIds.contains(id)
    }
}
