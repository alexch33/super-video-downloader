package com.myAllVideoBrowser.ui.main.home.browser.homeTab

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.myAllVideoBrowser.data.local.model.Suggestion
import com.myAllVideoBrowser.ui.main.base.BaseViewModel
import com.myAllVideoBrowser.util.SuggestionsUtils
import com.myAllVideoBrowser.util.get
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import com.myAllVideoBrowser.util.scheduler.BaseSchedulers
import io.reactivex.rxjava3.core.BackpressureStrategy
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.subjects.PublishSubject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class BrowserHomeViewModel @Inject constructor(
    private val okHttpClient: OkHttpProxyClient,
    private val baseSchedulers: BaseSchedulers,
) :
    BaseViewModel() {
    private val _isSearchInputFocused = MutableLiveData(false)
    val isSearchInputFocused: LiveData<Boolean> = _isSearchInputFocused

    private val _searchTextInput = MutableLiveData("")
    val searchTextInput: LiveData<String> = _searchTextInput

    private val _listSuggestions = MutableLiveData<List<Suggestion>>(emptyList())
    val listSuggestions: LiveData<List<Suggestion>> = _listSuggestions

    lateinit var homePublishSubject: PublishSubject<String>

    private var suggestionJob: Job? = null

    override fun start() {
        homePublishSubject = PublishSubject.create()
    }

    override fun stop() {

    }

    fun setSearchTextInput(input: String) {
        _searchTextInput.value = input
    }

    fun changeSearchFocus(isFocus: Boolean) {
        _isSearchInputFocused.value = isFocus
    }

    fun showSuggestions() {
        if (suggestionJob != null && suggestionJob?.isActive == true) {
            suggestionJob?.cancel()
        }
        suggestionJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val list = fetchListSuggestions().blockingFirst()
                if (list.size > 50) {
                    _listSuggestions.postValue(list.subList(0, 50).toMutableList())
                } else {
                    _listSuggestions.postValue(list.toMutableList())
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    private fun fetchListSuggestions(): Flowable<List<Suggestion>> {
        return Flowable.combineLatest(
            homePublishSubject.debounce(300, TimeUnit.MILLISECONDS)
                .toFlowable(BackpressureStrategy.LATEST), SuggestionsUtils.getSuggestions(
                okHttpClient.getProxyOkHttpClient(), searchTextInput.get() ?: ""
            ).toFlowable(BackpressureStrategy.LATEST)
        ) { _, suggestions ->
            val listSuggestions = mutableListOf<Suggestion>()
            listSuggestions.addAll(suggestions)
            listSuggestions.toList()
        }.onErrorReturn {
            emptyList()
        }.take(1).observeOn(baseSchedulers.single)
            .subscribeOn(baseSchedulers.computation)
    }
}