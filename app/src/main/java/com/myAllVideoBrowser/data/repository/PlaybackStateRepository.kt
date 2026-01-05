package com.myAllVideoBrowser.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.myAllVideoBrowser.di.qualifier.ApplicationContext
import com.myAllVideoBrowser.util.AppLogger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackStateRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        const val PREFS_NAME = "playback_state_prefs"
        const val KEY_VIDEO_URL = "video_url"
        const val KEY_VIDEO_POSITION = "video_position"
        const val KEY_IS_PLAYING = "is_playing"
        const val KEY_VIDEO_TITLE = "video_title"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _playbackState = MutableLiveData<PlaybackState>()
    val playbackState: LiveData<PlaybackState> = _playbackState

    private val _isServiceRunning = MutableLiveData<Boolean>(false)
    val isServiceRunning: LiveData<Boolean> = _isServiceRunning

    init {
        loadSavedState()
    }

    fun updatePlaybackState(
        videoUrl: Uri? = null,
        position: Long = 0,
        isPlaying: Boolean = false,
        title: String? = null
    ) {
        val currentState = _playbackState.value ?: PlaybackState()

        val newState = currentState.copy(
            videoUrl = videoUrl ?: currentState.videoUrl,
            position = position,
            isPlaying = isPlaying,
            title = title ?: currentState.title
        )

        _playbackState.value = newState
        saveState(newState)
        AppLogger.d("PlaybackState updated: $newState")
    }

    fun setServiceRunning(running: Boolean) {
        _isServiceRunning.value = running
    }

    fun getCurrentState(): PlaybackState {
        return _playbackState.value ?: PlaybackState()
    }

    private fun saveState(state: PlaybackState) {
        prefs.edit().apply {
            putString(KEY_VIDEO_URL, state.videoUrl?.toString())
            putLong(KEY_VIDEO_POSITION, state.position)
            putBoolean(KEY_IS_PLAYING, state.isPlaying)
            putString(KEY_VIDEO_TITLE, state.title)
            apply()
        }
    }

    private fun loadSavedState() {
        val videoUrl = prefs.getString(KEY_VIDEO_URL, null)?.let { Uri.parse(it) }
        val position = prefs.getLong(KEY_VIDEO_POSITION, 0)
        val isPlaying = prefs.getBoolean(KEY_IS_PLAYING, false)
        val title = prefs.getString(KEY_VIDEO_TITLE, "") ?: ""

        val savedState = PlaybackState(
            videoUrl = videoUrl,
            position = position,
            isPlaying = isPlaying,
            title = title
        )

        _playbackState.value = savedState
        AppLogger.d("Loaded saved playback state: $savedState")
    }

    fun clearState() {
        prefs.edit().clear().apply()
        _playbackState.value = PlaybackState()
        AppLogger.d("Playback state cleared")
    }

    data class PlaybackState(
        val videoUrl: Uri? = null,
        val position: Long = 0,
        val isPlaying: Boolean = false,
        val title: String = ""
    )
}