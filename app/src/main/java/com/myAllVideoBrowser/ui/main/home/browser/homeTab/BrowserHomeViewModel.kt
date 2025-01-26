package com.myAllVideoBrowser.ui.main.home.browser.homeTab

import androidx.databinding.ObservableBoolean
import androidx.databinding.ObservableField
import androidx.lifecycle.viewModelScope
import com.myAllVideoBrowser.data.local.model.Suggestion
import com.myAllVideoBrowser.ui.main.base.BaseViewModel
import com.myAllVideoBrowser.util.SuggestionsUtils
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import com.myAllVideoBrowser.util.scheduler.BaseSchedulers
import io.reactivex.rxjava3.core.BackpressureStrategy
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.subjects.PublishSubject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class BrowserHomeViewModel @Inject constructor(
    private val okHttpClient: OkHttpProxyClient,
    private val baseSchedulers: BaseSchedulers,
) :
    BaseViewModel() {
    val isSearchInputFocused = ObservableBoolean(false)
    val searchTextInput = ObservableField("")
    val listSuggestions: ObservableField<MutableList<Suggestion>> = ObservableField(mutableListOf())

    lateinit var homePublishSubject: PublishSubject<String>

    private var suggestionJob: Job? = null

    override fun start() {
        homePublishSubject = PublishSubject.create()
    }

    override fun stop() {

    }

    fun changeSearchFocus(isFocus: Boolean) {
        this.isSearchInputFocused.set(isFocus)
    }

    fun showSuggestions() {
        if (suggestionJob != null && suggestionJob?.isActive == true) {
            suggestionJob?.cancel()
        }
        suggestionJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(this.coroutineContext) {
                    val list = getListSuggestions().blockingFirst()
                    if (list.size > 50) {
                        listSuggestions.set(list.subList(0, 50).toMutableList())
                    } else {
                        listSuggestions.set(list.toMutableList())
                    }
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    private fun getListSuggestions(): Flowable<List<Suggestion>> {
        return Flowable.combineLatest(
            homePublishSubject.debounce(300, TimeUnit.MILLISECONDS)
                .toFlowable(BackpressureStrategy.LATEST), SuggestionsUtils.getSuggestions(
                okHttpClient.getProxyOkHttpClient(), searchTextInput.get() ?: ""
            )
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