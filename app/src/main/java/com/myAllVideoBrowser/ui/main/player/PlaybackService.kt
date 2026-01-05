package com.myAllVideoBrowser.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.common.util.UnstableApi
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.ui.main.player.VideoPlayerActivity
import com.myAllVideoBrowser.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

@UnstableApi
class MediaPlaybackService : MediaBrowserServiceCompat() {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val NOTIFICATION_CHANNEL_ID = "media_playback_channel"
        const val NOTIFICATION_CHANNEL_NAME = "Media Playback"
        const val ROOT_ID = "media_root_id"
        const val ACTION_PLAY_PAUSE = "com.myAllVideoBrowser.ACTION_PLAY_PAUSE"
        const val ACTION_STOP = "com.myAllVideoBrowser.ACTION_STOP"
    }

    inner class MediaPlaybackBinder : Binder() {
        fun getService(): MediaPlaybackService = this@MediaPlaybackService
    }

    private val binder = MediaPlaybackBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var stateBuilder: PlaybackStateCompat.Builder
    private lateinit var notificationManager: NotificationManager
    private lateinit var audioManager: AudioManager
    private lateinit var exoPlayer: ExoPlayer

    private var isForegroundService = false
    private var currentMediaItem: MediaItem? = null

    override fun onCreate() {
        super.onCreate()
        AppLogger.d("MediaPlaybackService onCreate")

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Create notification channel for Android O+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        }

        // Initialize MediaSession
        mediaSession = MediaSessionCompat(baseContext, "MediaPlaybackService").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(mediaSessionCallback)
            isActive = true
        }

        stateBuilder = PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE)

        // Initialize ExoPlayer
        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this))
            .build()
            .apply {
                addListener(playerListener)
                playWhenReady = false
            }

        sessionToken = mediaSession.sessionToken
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.d("MediaPlaybackService onDestroy")
        notificationManager.cancel(NOTIFICATION_ID)
        mediaSession.isActive = false
        mediaSession.release()
        exoPlayer.removeListener(playerListener)
        exoPlayer.release()
        serviceScope.cancel()
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot? {
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        // Return empty list for now - we don't need media browsing for video playback
        result.sendResult(mutableListOf())
    }

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> {
                val wasPlaying = exoPlayer.isPlaying
                if (wasPlaying) {
                    pause()
                } else {
                    play()
                }
                // Update notification immediately with the new state
                if (isForegroundService) {
                    val newIsPlaying = !wasPlaying
                    val notification = createNotification(newIsPlaying)
                    notificationManager.notify(NOTIFICATION_ID, notification)
                }
            }
            ACTION_STOP -> {
                stop()
                stopForegroundService()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            AppLogger.d("MediaSession onPlay")
            play()
        }

        override fun onPause() {
            AppLogger.d("MediaSession onPause")
            pause()
        }

        override fun onStop() {
            AppLogger.d("MediaSession onStop")
            stop()
            stopForegroundService()
        }

        override fun onSeekTo(pos: Long) {
            AppLogger.d("MediaSession onSeekTo: $pos")
            seekTo(pos)
        }
    }

    private fun updatePlaybackState(state: Int) {
        val position = if (exoPlayer.currentPosition >= 0) exoPlayer.currentPosition else PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN
        stateBuilder.setState(state, position, 1.0f)
        mediaSession.setPlaybackState(stateBuilder.build())

        if (state == PlaybackStateCompat.STATE_PLAYING || state == PlaybackStateCompat.STATE_PAUSED) {
            startForegroundService()
        } else if (state == PlaybackStateCompat.STATE_STOPPED) {
            stopForegroundService()
        }

        // Update notification if foreground
        if (isForegroundService) {
            val notification = createNotification()
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun startForegroundService() {
        if (!isForegroundService) {
            val notification = createNotification()
            startForeground(NOTIFICATION_ID, notification)
            isForegroundService = true
        }
    }

    private fun stopForegroundService() {
        if (isForegroundService) {
            stopForeground(false)
            isForegroundService = false
        }
    }

    private fun createNotification(overrideIsPlaying: Boolean? = null): Notification {
        val playbackState = mediaSession.controller?.playbackState?.state ?: PlaybackStateCompat.STATE_STOPPED
        val isPlaying = overrideIsPlaying ?: (playbackState == PlaybackStateCompat.STATE_PLAYING)

        val playPauseIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        val playPauseText = if (isPlaying) "Pause" else "Play"

        val playPauseIntent = Intent(this, MediaPlaybackService::class.java).apply {
            action = ACTION_PLAY_PAUSE
        }
        val playPausePendingIntent = PendingIntent.getService(
            this,
            0,
            playPauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseAction = NotificationCompat.Action(
            playPauseIcon,
            playPauseText,
            playPausePendingIntent
        )

        val stopIntent = Intent(this, MediaPlaybackService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopAction = NotificationCompat.Action(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Stop",
            stopPendingIntent
        )

        val contentText = if (isPlaying) "Playing video" else "Paused"

        val intent = Intent(this, VideoPlayerActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Video Player")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_video_24dp)
            .setContentIntent(pendingIntent)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1))
            .addAction(playPauseAction)
            .addAction(stopAction)
            .setOngoing(true)
            .build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Media playback controls"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    if (exoPlayer.playWhenReady) {
                        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                    } else {
                        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                    }
                }
                Player.STATE_ENDED -> {
                    updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
                }
                Player.STATE_IDLE -> {
                    updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
                }
                Player.STATE_BUFFERING -> {
                    updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING)
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            AppLogger.e("ExoPlayer error: ${error.message}")
            updatePlaybackState(PlaybackStateCompat.STATE_ERROR)
        }
    }

    // Public methods for controlling playback
    fun play() {
        exoPlayer.playWhenReady = true
        exoPlayer.play()
    }

    fun pause() {
        exoPlayer.pause()
    }

    fun stop() {
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        currentMediaItem = null
    }

    fun seekTo(position: Long) {
        exoPlayer.seekTo(position)
    }

    fun setMediaItem(mediaItem: MediaItem) {
        currentMediaItem = mediaItem
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
    }

    fun getCurrentPosition(): Long {
        return exoPlayer.currentPosition
    }

    fun getDuration(): Long {
        return exoPlayer.duration
    }

    fun isPlaying(): Boolean {
        return exoPlayer.isPlaying
    }

    fun getPlayer(): ExoPlayer {
        return exoPlayer
    }

    @UnstableApi
    fun setMediaSource(source: MediaSource) {
        try {
            AppLogger.d("Setting media source in service")
            exoPlayer.setMediaSource(source)
            exoPlayer.prepare()
            AppLogger.d("Media source prepared successfully")
        } catch (e: Exception) {
            AppLogger.e("Error setting media source: ${e.message}")
            updatePlaybackState(PlaybackStateCompat.STATE_ERROR)
        }
    }
}