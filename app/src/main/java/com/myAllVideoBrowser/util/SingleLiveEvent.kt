package com.myAllVideoBrowser.util

import androidx.lifecycle.MutableLiveData

class SingleLiveEvent<T> : MutableLiveData<T>() {
    /**
     * Used for cases where T is Void, to make calls cleaner.
     * Use postValue to ensure thread safety even if called from background.
     */
    fun call() {
        postValue(null)
    }
}
