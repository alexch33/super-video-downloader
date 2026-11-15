package com.myAllVideoBrowser.ui.main.player

import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.myAllVideoBrowser.ui.main.base.BaseViewModel
import com.myAllVideoBrowser.util.SingleLiveEvent
import javax.inject.Inject

class VideoPlayerViewModel @Inject constructor() : BaseViewModel() {

    private val _videoName = MutableLiveData("")
    val videoName: LiveData<String> = _videoName

    private val _videoUrl = MutableLiveData(Uri.EMPTY)
    val videoUrl: LiveData<Uri> = _videoUrl

    private val _videoHeaders = MutableLiveData<Map<String, String>>(emptyMap())
    val videoHeaders: LiveData<Map<String, String>> = _videoHeaders

    val stopPlayerEvent = SingleLiveEvent<Void?>()

    fun setVideoName(name: String) {
        _videoName.value = name
    }

    fun setVideoUrl(url: Uri) {
        _videoUrl.value = url
    }

    fun setVideoHeaders(headers: Map<String, String>) {
        _videoHeaders.value = headers
    }

    override fun start() {
    }

    override fun stop() {
        stopPlayerEvent.call()
    }
}
