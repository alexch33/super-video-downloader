package com.myAllVideoBrowser.ui.main.player

import android.net.Uri
import androidx.databinding.ObservableField
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.myAllVideoBrowser.data.repository.PlaybackStateRepository
import com.myAllVideoBrowser.ui.main.base.BaseViewModel
import com.myAllVideoBrowser.util.SingleLiveEvent
import kotlinx.coroutines.launch
import javax.inject.Inject

class VideoPlayerViewModel @Inject constructor(
    private val playbackStateRepository: PlaybackStateRepository
) : BaseViewModel() {

    val videoName = ObservableField("")
    val videoUrl = ObservableField(Uri.EMPTY)
    val videoHeaders = ObservableField(emptyMap<String, String>())

    val stopPlayerEvent = SingleLiveEvent<Void?>()
    val playPauseEvent = SingleLiveEvent<Boolean>()
    val seekToEvent = SingleLiveEvent<Long>()

    val playbackState: LiveData<PlaybackStateRepository.PlaybackState> = playbackStateRepository.playbackState
    val isServiceRunning: LiveData<Boolean> = playbackStateRepository.isServiceRunning

    override fun start() {
        viewModelScope.launch {
            // Load current playback state
            playbackStateRepository.getCurrentState().let { state ->
                if (state.videoUrl != null) {
                    videoUrl.set(state.videoUrl)
                    videoName.set(state.title)
                    seekToEvent.value = state.position
                    playPauseEvent.value = state.isPlaying
                }
            }
        }
    }

    override fun stop() {
        stopPlayerEvent.call()
        viewModelScope.launch {
            playbackStateRepository.updatePlaybackState(isPlaying = false)
        }
    }

    fun playVideo(url: Uri, title: String, headers: Map<String, String> = emptyMap()) {
        videoUrl.set(url)
        videoName.set(title)
        videoHeaders.set(headers)

        viewModelScope.launch {
            playbackStateRepository.updatePlaybackState(
                videoUrl = url,
                position = 0,
                isPlaying = true,
                title = title
            )
        }

        playPauseEvent.value = true
    }

    fun pauseVideo() {
        playPauseEvent.value = false
        viewModelScope.launch {
            playbackStateRepository.updatePlaybackState(isPlaying = false)
        }
    }

    fun resumeVideo() {
        playPauseEvent.value = true
        viewModelScope.launch {
            playbackStateRepository.updatePlaybackState(isPlaying = true)
        }
    }

    fun seekTo(position: Long) {
        seekToEvent.value = position
        viewModelScope.launch {
            playbackStateRepository.updatePlaybackState(position = position)
        }
    }

    fun setServiceRunning(running: Boolean) {
        playbackStateRepository.setServiceRunning(running)
    }
}