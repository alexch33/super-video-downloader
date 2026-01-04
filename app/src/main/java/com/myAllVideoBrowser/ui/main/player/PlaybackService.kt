package com.myAllVideoBrowser.ui.main.player

import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.myAllVideoBrowser.util.AppLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.net.CookieHandler
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URI
import androidx.core.net.toUri
import androidx.media3.session.DefaultMediaNotificationProvider
import com.myAllVideoBrowser.R

@UnstableApi
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    companion object {
        var isServiceRunning = false
        private const val CHANNEL_ID = "playback_channel"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isServiceRunning = true
        intent?.let { handleIntent(it) }

        return super.onStartCommand(intent, flags, startId)
    }

    private fun handleIntent(intent: Intent) {
        val player = mediaSession?.player ?: return
        val videoUrl = intent.getStringExtra("video_url")
        val videoHeaders = intent.getStringExtra("video_headers")

        if (videoUrl.isNullOrEmpty()) {
            AppLogger.w("PlaybackService: onStartCommand received null or empty URL.")
            return
        }

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setKeepPostFor302Redirects(true)

        val cookieManager = java.net.CookieManager().apply {
            setCookiePolicy(CookiePolicy.ACCEPT_ALL)
        }

        if (!videoHeaders.isNullOrEmpty()) {
            AppLogger.d("PlaybackService: Processing headers.")
            val headersMap = Json.parseToJsonElement(videoHeaders).jsonObject
                .mapValues { it.value.toString().removeSurrounding("\"") }

            dataSourceFactory.setDefaultRequestProperties(headersMap)

            headersMap["Cookie"]?.split(";")?.forEach { cookiePair ->
                val parts = cookiePair.trim().split("=", limit = 2)
                if (parts.size == 2) {
                    try {
                        cookieManager.cookieStore.add(URI(videoUrl), HttpCookie(parts[0], parts[1]))
                    } catch (e: Exception) {
                        AppLogger.e("PlaybackService: Failed to add cookie ${e.message}")
                    }
                }
            }
        }
        CookieHandler.setDefault(cookieManager)

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        val mediaItem = MediaItem.fromUri(videoUrl.toUri())
        val source = mediaSourceFactory.createMediaSource(mediaItem)
        (player as ExoPlayer).setMediaSource(source)

        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = true
    }


    override fun onCreate() {
        super.onCreate()
        AppLogger.d("PlaybackService: onCreate")

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(CHANNEL_ID)
            .setChannelName(R.string.playback_channel_name)
            .build()

        notificationProvider.setSmallIcon(R.drawable.media3_notification_small_icon)
        setMediaNotificationProvider(notificationProvider)


        val player = ExoPlayer.Builder(this).build()
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player!!
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        isServiceRunning = false
    }

    override fun onDestroy() {
        AppLogger.d("PlaybackService: onDestroy")
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
