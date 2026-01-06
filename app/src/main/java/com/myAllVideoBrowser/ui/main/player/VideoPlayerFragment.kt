package com.myAllVideoBrowser.ui.main.player

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.myAllVideoBrowser.databinding.FragmentPlayerBinding
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.util.AppUtil
import com.myAllVideoBrowser.util.ContextUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.net.CookieHandler
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URI
import javax.inject.Inject


@UnstableApi
class VideoPlayerFragment : BaseFragment() {

    companion object {
        var DEFAULT_COOKIE_MANAGER: java.net.CookieManager? = null

        const val VIDEO_URL = "video_url"
        const val VIDEO_HEADERS = "video_headers"
        const val VIDEO_NAME = "video_name"

        // Media3 constants
        const val SHOW_BUFFERING_ALWAYS = PlayerView.SHOW_BUFFERING_ALWAYS
        const val EXTENSION_RENDERER_MODE_PREFER =
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
    }

    init {
        DEFAULT_COOKIE_MANAGER = java.net.CookieManager()
        DEFAULT_COOKIE_MANAGER?.setCookiePolicy(CookiePolicy.ACCEPT_ALL)
    }

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    @Inject
    lateinit var appUtil: AppUtil

    private lateinit var videoPlayerViewModel: VideoPlayerViewModel
    private lateinit var dataBinding: FragmentPlayerBinding
    private var isStretched = false
    private var mediaSource: androidx.media3.exoplayer.source.MediaSource? = null

    // Service connection
    private var mediaPlaybackService: MediaPlaybackService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            android.util.Log.d("VideoPlayerFragment", "Service connected")
            val binder = service as MediaPlaybackService.MediaPlaybackBinder
            mediaPlaybackService = binder.getService()
            serviceBound = true
            videoPlayerViewModel.setServiceRunning(true)
            // Set player to view and add listener
            dataBinding.videoView.player = mediaPlaybackService?.getPlayer()
            mediaPlaybackService?.getPlayer()?.addListener(playerListener)
            // Set up media session callback to update UI
            setupMediaSessionCallback()
            // Set media source if ready and not already set
            if (mediaPlaybackService?.getPlayer()?.currentMediaItem == null) {
                mediaSource?.let { source ->
                    android.util.Log.d("VideoPlayerFragment", "Setting media source and playing")
                    mediaPlaybackService?.setMediaSource(source)
                    mediaPlaybackService?.play()
                }
            } else {
                android.util.Log.d(
                    "VideoPlayerFragment",
                    "Media source already set, resuming playback"
                )
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            android.util.Log.d("VideoPlayerFragment", "Service disconnected")
            serviceBound = false
            videoPlayerViewModel.setServiceRunning(false)
            dataBinding.videoView.player = null
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                dataBinding.loadingBar.visibility = View.GONE
            } else {
                dataBinding.loadingBar.visibility = View.VISIBLE
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (videoPlayerViewModel.videoUrl.get().toString().startsWith("http")) {
                AlertDialog.Builder(requireContext())
                    .setTitle("Download Only")
                    .setMessage("This video supports only download.")
                    .setPositiveButton("OK") { dialog, _ ->
                        dialog.dismiss()
                    }
                    .show()
            }

            Toast.makeText(ContextUtils.getApplicationContext(), error.message, Toast.LENGTH_LONG)
                .show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        videoPlayerViewModel =
            ViewModelProvider(this, viewModelFactory)[VideoPlayerViewModel::class.java]
        arguments?.getString(VIDEO_HEADERS)?.let { rawHeaders ->
            val headers =
                Json.parseToJsonElement(rawHeaders).jsonObject.toMap()
                    .map { it.key to it.value.toString() }
                    .toMap()
            videoPlayerViewModel.videoHeaders.set(headers)
        }
        arguments?.getString(VIDEO_NAME)?.let { videoPlayerViewModel.videoName.set(it) }

        val iUrl = Uri.parse(arguments?.getString(VIDEO_URL))

        if (iUrl != null) {
            videoPlayerViewModel.videoUrl.set(iUrl)
        }

        val url = videoPlayerViewModel.videoUrl.get() ?: Uri.EMPTY
        val headers = videoPlayerViewModel.videoHeaders.get() ?: emptyMap()

        try {
            val mediaItem: MediaItem = MediaItem.fromUri(url)
            val mediaFactory = createMediaFactory(headers, url.toString().startsWith("http"))

            val cookiesStrArr = headers["Cookie"]?.split(";")
            if (!cookiesStrArr.isNullOrEmpty()) {
                for (cookiePair in cookiesStrArr) {
                    val tmp = cookiePair.split("=")
                    val key = tmp.firstOrNull()
                    val value = tmp.lastOrNull()

                    if (key != null && value != null) {
                        DEFAULT_COOKIE_MANAGER?.cookieStore?.add(
                            URI(url.toString()),
                            HttpCookie(key, value)
                        )
                    }
                }
            }

            mediaSource = mediaFactory.createMediaSource(mediaItem)
            android.util.Log.d("VideoPlayerFragment", "Media source created successfully")
        } catch (e: Exception) {
            android.util.Log.e(
                "VideoPlayerFragment",
                "Error creating media source: ${e.message}",
                e
            )
            Toast.makeText(context, "Error loading video: ${e.message}", Toast.LENGTH_LONG).show()
            activity?.finish()
            return dataBinding.root
        }

        dataBinding = FragmentPlayerBinding.inflate(inflater, container, false).apply {
            val currentBinding = this

            currentBinding.viewModel = videoPlayerViewModel
            currentBinding.toolbar.setNavigationOnClickListener(navigationIconClickListener)
            currentBinding.videoView.setShowBuffering(SHOW_BUFFERING_ALWAYS)
            currentBinding.videoView.setFullscreenButtonClickListener {
                toggleStretchMode()
            }
        }

        return dataBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (CookieHandler.getDefault() != DEFAULT_COOKIE_MANAGER) {
            CookieHandler.setDefault(DEFAULT_COOKIE_MANAGER)
        }

        handleBackPressed()
        handlePlayerEvents()
        videoPlayerViewModel.start()
        getActivity(context)?.let { appUtil.hideSystemUI(it.window, dataBinding.root) }

        // Bind to the media playback service
        bindToService()
    }

    private fun getActivity(context: Context?): Activity? {
        if (context == null) {
            return null
        } else if (context is ContextWrapper) {
            return if (context is Activity) {
                context
            } else {
                getActivity(context.baseContext)
            }
        }
        return null
    }

    override fun onStart() {
        super.onStart()
        bindToService()
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            requireContext().unbindService(serviceConnection)
            serviceBound = false
        }
    }

    override fun onDestroyView() {
        // Detach player from view to prevent surface issues
        dataBinding.videoView.player = null
        getActivity(context)?.let { appUtil.showSystemUI(it.window, dataBinding.root) }
        videoPlayerViewModel.stop()
        super.onDestroyView()
    }

    private val navigationIconClickListener = View.OnClickListener {
        handleClose()
    }

    private fun handlePlayerEvents() {
        videoPlayerViewModel.stopPlayerEvent.observe(viewLifecycleOwner) {
            mediaPlaybackService?.stop()
        }
    }


    private fun createMediaFactory(
        headers: Map<String, String>,
        isUrl: Boolean
    ): DefaultMediaSourceFactory {
        if (isUrl) {
            if (headers.isEmpty()) {
                val factory = DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setKeepPostFor302Redirects(true)
                return DefaultMediaSourceFactory(requireContext()).setDataSourceFactory(
                    factory
                )
            }
            val fixedHeaders = headers.toMutableMap()

            val dataSourceFactory: DataSource.Factory = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setKeepPostFor302Redirects(true)
                .setUserAgent(headers["User-Agent"])
                .setDefaultRequestProperties(fixedHeaders)

            return DefaultMediaSourceFactory(requireContext()).setDataSourceFactory(
                dataSourceFactory
            )
        } else {
            val dataSourceFactory = DefaultDataSource.Factory(requireContext())

            return DefaultMediaSourceFactory(requireContext()).setDataSourceFactory(
                dataSourceFactory
            )
        }

    }

    private fun handleBackPressed() {
        this.view?.isFocusableInTouchMode = true
        this.view?.requestFocus()
        this.view?.setOnKeyListener { _, keyCode, _ ->
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                handleClose()
                true
            } else false
        }
    }

    private fun handleClose() {
        videoPlayerViewModel.stop()
        mediaPlaybackService?.stop()
        activity?.finish()
    }

    private fun toggleStretchMode() {
        isStretched = !isStretched

        if (isStretched) {
            // Apply stretch based on orientation
            val orientation = resources.configuration.orientation
            if (orientation == Configuration.ORIENTATION_PORTRAIT) {
                // Stretch to fill vertically in portrait
                dataBinding.videoView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            } else {
                // Stretch to fill horizontally in landscape
                dataBinding.videoView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
            // Hide toolbar in stretched mode
            dataBinding.toolbar.visibility = View.GONE
        } else {
            // Return to original size
            dataBinding.videoView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            // Show toolbar
            dataBinding.toolbar.visibility = View.VISIBLE
        }
    }

    private fun bindToService() {
        if (!serviceBound) {
            android.util.Log.d("VideoPlayerFragment", "Starting and binding to service")
            val intent = Intent(requireContext(), MediaPlaybackService::class.java)
            requireContext().startService(intent)
            requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun setupMediaSessionCallback() {
        // Media session is handled in the service, no additional setup needed here
    }
}