package com.myAllVideoBrowser.ui.main.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.franmontiel.persistentcookiejar.PersistentCookieJar
import com.franmontiel.persistentcookiejar.cache.SetCookieCache
import com.franmontiel.persistentcookiejar.persistence.SharedPrefsCookiePersistor
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.util.AppLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.Json.Default.encodeToString
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

@kotlinx.serialization.Serializable
data class PlaybackStateSnapshot(
    val url: String,
    val headers: String?,
    val positionMs: Long,
    val playWhenReady: Boolean
)

@UnstableApi
class PlaybackService : MediaSessionService() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "playback_channel"
        const val NOTIFICATION_ID = 999
        const val HEADERS_CUSTOM_KEY = "raw_headers"
    }

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    private val cookieJar by lazy {
        PersistentCookieJar(
            SetCookieCache(),
            SharedPrefsCookiePersistor(applicationContext)
        )
    }

    // TODO: inject proxy client
    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .build()
    }

    private val prefs by lazy {
        getSharedPreferences("playback_state", MODE_PRIVATE)
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildInitialNotification())

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(NOTIFICATION_ID)
            .setChannelId(NOTIFICATION_CHANNEL_ID)
            .build()
        setMediaNotificationProvider(notificationProvider)

        val renderersFactory = DefaultRenderersFactory(this).apply {
            setEnableAudioOutputPlaybackParameters(true)
        }

        val player = ExoPlayer.Builder(this, renderersFactory).build().apply {
            configureAudio(this)
            setWakeMode(C.WAKE_MODE_NETWORK)
            observePlayer(this)
        }

        this.player = player
        this.mediaSession = MediaSession.Builder(this, player).build()
    }


    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        super.onUpdateNotification(session, false)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        player?.let {
            if (it.playWhenReady) {
                it.stop()
            }
        }
        stopSelf()
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        player = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.hasExtra(VideoPlayerFragment.VIDEO_URL) == true) {
            handlePlaybackIntent(intent)
        } else {
            restorePlaybackIfNeeded()
        }
        return START_STICKY
    }

    private fun handlePlaybackIntent(intent: Intent) {
        val player = this.player ?: return
        val url = intent.getStringExtra(VideoPlayerFragment.VIDEO_URL) ?: return
        val headers = intent.getStringExtra(VideoPlayerFragment.VIDEO_HEADERS)

        val mediaItem = buildMediaItem(url, headers)
        val mediaSource = if (url.startsWith("file://")) {
            buildFileMediaSource(mediaItem)
        } else {
            buildHttpMediaSource(mediaItem, headers)
        }

        player.setMediaSource(mediaSource)
        player.prepare()
        player.play()
    }

    private fun buildMediaItem(url: String, headers: String?): MediaItem {
        val customData = Bundle().apply {
            putString(HEADERS_CUSTOM_KEY, headers)
        }
        return MediaItem.Builder()
            .setUri(url)
            .setCustomCacheKey(url)
            .setTag(customData)
            .build()
    }

    private fun buildFileMediaSource(mediaItem: MediaItem) =
        ProgressiveMediaSource.Factory(
            DefaultDataSource.Factory(applicationContext)
        ).createMediaSource(mediaItem)

    private fun buildHttpMediaSource(mediaItem: MediaItem, rawHeaders: String?) =
        ProgressiveMediaSource.Factory(
            buildHttpDataSource(mediaItem.requestMetadata.mediaUri.toString(), rawHeaders)
        ).createMediaSource(mediaItem)

    private fun buildHttpDataSource(
        videoUrl: String,
        rawHeaders: String?
    ): OkHttpDataSource.Factory {
        cookieJar.clear()
        val factory = OkHttpDataSource.Factory(okHttpClient)
        rawHeaders?.let {
            try {
                val headers = Json.parseToJsonElement(it)
                    .jsonObject
                    .mapValues { v -> v.value.toString().removeSurrounding("\"") }
                    .toMutableMap()

                headers.remove("Cookie")?.split(";")?.forEach { cookieString ->
                    okhttp3.Cookie.parse(videoUrl.toHttpUrl(), cookieString)?.let { cookie ->
                        cookieJar.saveFromResponse(videoUrl.toHttpUrl(), listOf(cookie))
                    }
                }
                if (headers.isNotEmpty()) {
                    factory.setDefaultRequestProperties(headers)
                }
            } catch (e: Exception) {
                AppLogger.e("Failed to parse headers: $rawHeaders, ${e.message}")
            }
        }
        return factory
    }

    private fun observePlayer(player: ExoPlayer) {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) savePlaybackState()
                if (state == Player.STATE_ENDED) prefs.edit { clear() }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (player.playbackState == Player.STATE_READY) savePlaybackState()
            }
        })
    }

    private fun savePlaybackState() {
        val player = this.player ?: return
        val item = player.currentMediaItem ?: return
        val position = player.currentPosition
        if (position <= 0) return

        val customData = item.localConfiguration?.tag as? Bundle
        val headers = customData?.getString(HEADERS_CUSTOM_KEY)

        prefs.edit {
            putString(
                "snapshot",
                encodeToString<PlaybackStateSnapshot>(
                    PlaybackStateSnapshot(
                        url = item.localConfiguration?.uri.toString(),
                        headers = headers,
                        positionMs = position,
                        playWhenReady = player.playWhenReady
                    )
                )
            )
        }
    }

    private fun restorePlaybackIfNeeded() {
        val player = this.player ?: return
        val json = prefs.getString("snapshot", null) ?: return
        val snapshot: PlaybackStateSnapshot
        try {
            snapshot = Json.decodeFromString(json)
        } catch (e: Exception) {
            prefs.edit { clear() }
            return
        }

        val mediaItem = buildMediaItem(snapshot.url, snapshot.headers)
        val mediaSource = if (snapshot.url.startsWith("file://")) {
            buildFileMediaSource(mediaItem)
        } else {
            buildHttpMediaSource(mediaItem, snapshot.headers)
        }

        player.setMediaSource(mediaSource)
        player.prepare()
        player.seekTo(snapshot.positionMs)
        player.playWhenReady = snapshot.playWhenReady
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.playback_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildInitialNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.media3_notification_small_icon)
            .setContentTitle(getString(R.string.playback_channel_name))
            .setContentText("Initializing...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun configureAudio(player: ExoPlayer) {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        player.setAudioAttributes(audioAttributes, true)
        player.setHandleAudioBecomingNoisy(true)
    }
}
