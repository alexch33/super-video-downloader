package com.myAllVideoBrowser.ui.main.player

//import com.allVideoDownloaderXmaster.OpenForTesting

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView.SHOW_BUFFERING_ALWAYS
import com.myAllVideoBrowser.databinding.FragmentPlayerBinding
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.util.AppUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.net.CookieHandler
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URI
import javax.inject.Inject


@UnstableApi //@OpenForTesting
class VideoPlayerFragment : BaseFragment() {

    companion object {
        var DEFAULT_COOKIE_MANAGER: java.net.CookieManager? = null

        const val VIDEO_URL = "video_url"
        const val VIDEO_HEADERS = "video_headers"
        const val VIDEO_NAME = "video_name"
    }

    init {
        DEFAULT_COOKIE_MANAGER = java.net.CookieManager()
        DEFAULT_COOKIE_MANAGER?.setCookiePolicy(CookiePolicy.ACCEPT_ALL)
    }

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    @Inject
    lateinit var appUtil: AppUtil

    private lateinit var player: ExoPlayer

    private lateinit var videoPlayerViewModel: VideoPlayerViewModel

    private lateinit var dataBinding: FragmentPlayerBinding

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

        player = ExoPlayer.Builder(requireContext())
            .setRenderersFactory(createRenderFactory())
            .build()

        dataBinding = FragmentPlayerBinding.inflate(inflater, container, false).apply {
            val currentBinding = this

            currentBinding.viewModel = videoPlayerViewModel
            currentBinding.toolbar.setNavigationOnClickListener(navigationIconClickListener)
            currentBinding.videoView.player = player
            currentBinding.videoView.setShowBuffering(SHOW_BUFFERING_ALWAYS)
            currentBinding.videoView.setFullscreenButtonClickListener {
                if (it) {
                    this.toolbar.visibility = View.GONE
                } else {
                    this.toolbar.visibility = View.VISIBLE
                }
            }

            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == PlaybackState.STATE_PLAYING) {
                        currentBinding.loadingBar.visibility = View.GONE
                    } else {
                        currentBinding.loadingBar.visibility = View.VISIBLE
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (viewModel?.videoUrl?.get().toString().startsWith("http")) {
                        AlertDialog.Builder(requireContext())
                            .setTitle("Download Only")
                            .setMessage("This video supports only download.")
                            .setPositiveButton("OK") { dialog, _ ->
                                dialog.dismiss()
                            }
                            .show()
                    }
                    Toast.makeText(context, error.message, Toast.LENGTH_LONG).show()
                }
            })

            player.setMediaSource(mediaFactory.createMediaSource(mediaItem))
            player.prepare()
            player.playWhenReady = true
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

    override fun onDestroyView() {
        getActivity(context)?.let { appUtil.showSystemUI(it.window, dataBinding.root) }
        videoPlayerViewModel.stop()
        player.release()
        super.onDestroyView()
    }

    private val navigationIconClickListener = View.OnClickListener {
        handleClose()
    }

    private fun handlePlayerEvents() {
        videoPlayerViewModel.stopPlayerEvent.observe(viewLifecycleOwner) {
            player.stop()
        }
    }

    private fun createRenderFactory(): RenderersFactory {
        return DefaultRenderersFactory(requireContext().applicationContext)
            .setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)
            .setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                var decoderInfos =
                    MediaCodecSelector.DEFAULT
                        .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                if (MimeTypes.VIDEO_H264 == mimeType) {
                    // copy the list because MediaCodecSelector.DEFAULT returns an unmodifiable list
                    decoderInfos = ArrayList(decoderInfos)
                    decoderInfos.reverse()
                }
                decoderInfos
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
        activity?.finish()
    }
}