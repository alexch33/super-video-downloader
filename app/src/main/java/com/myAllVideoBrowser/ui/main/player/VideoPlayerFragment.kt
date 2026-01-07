package com.myAllVideoBrowser.ui.main.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
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
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView.SHOW_BUFFERING_ALWAYS
import com.myAllVideoBrowser.databinding.FragmentPlayerBinding
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.util.AppUtil
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject


@UnstableApi
class VideoPlayerFragment : BaseFragment() {

    companion object {
        const val VIDEO_URL = "video_url"
        const val VIDEO_HEADERS = "video_headers"
        const val VIDEO_NAME = "video_name"
    }

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    @Inject
    lateinit var appUtil: AppUtil

    @Inject
    lateinit var okHttpClient: OkHttpProxyClient

    private lateinit var player: ExoPlayer

    private lateinit var videoPlayerViewModel: VideoPlayerViewModel

    private lateinit var dataBinding: FragmentPlayerBinding
    private var isStretched = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        videoPlayerViewModel =
            ViewModelProvider(this, viewModelFactory)[VideoPlayerViewModel::class.java]
        arguments?.getString(VIDEO_HEADERS)?.let { rawHeaders ->
            try {
                val headers =
                    Json.parseToJsonElement(rawHeaders).jsonObject.mapValues { (_, value) ->
                        value.toString().removeSurrounding("\"")
                    }
                videoPlayerViewModel.videoHeaders.set(headers)
            } catch (e: Exception) {
                videoPlayerViewModel.videoHeaders.set(emptyMap())
            }
        }
        arguments?.getString(VIDEO_NAME)?.let { videoPlayerViewModel.videoName.set(it) }

        val iUrl = Uri.parse(arguments?.getString(VIDEO_URL))

        if (iUrl != null) {
            videoPlayerViewModel.videoUrl.set(iUrl)
        }

        val url = videoPlayerViewModel.videoUrl.get() ?: Uri.EMPTY
        // The "Cookie" header will be passed here, but OkHttp using CookieJar
        val headers = videoPlayerViewModel.videoHeaders.get() ?: emptyMap()

        val mediaFactory = createMediaFactory(headers, url.toString().startsWith("http"))

        player = ExoPlayer.Builder(requireContext())
            .setRenderersFactory(createRenderFactory())
            .setMediaSourceFactory(mediaFactory)
            .build()

        dataBinding = FragmentPlayerBinding.inflate(inflater, container, false).apply {
            val currentBinding = this

            currentBinding.viewModel = videoPlayerViewModel
            currentBinding.toolbar.setNavigationOnClickListener(navigationIconClickListener)
            currentBinding.videoView.player = player
            currentBinding.videoView.setShowBuffering(SHOW_BUFFERING_ALWAYS)
            currentBinding.videoView.setFullscreenButtonClickListener {
                toggleStretchMode()
            }

            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY && player.playWhenReady) {
                        currentBinding.loadingBar.visibility = View.GONE
                    } else if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                        currentBinding.loadingBar.visibility = View.GONE
                    } else {
                        currentBinding.loadingBar.visibility = View.VISIBLE
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (videoPlayerViewModel.videoUrl.get().toString().startsWith("http")) {
                        AlertDialog.Builder(requireContext())
                            .setTitle("Download Only")
                            .setMessage("This video supports only download.")
                            .setPositiveButton("OK") { dialog, _ ->
                                dialog.dismiss()
                                handleClose()
                            }
                            .show()
                    }
                    Toast.makeText(context, error.message, Toast.LENGTH_LONG).show()
                }
            })

            val mediaItem: MediaItem = MediaItem.fromUri(url)
            player.setMediaItem(mediaItem)
            player.prepare()
            player.playWhenReady = true
        }

        return dataBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
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
                    decoderInfos = ArrayList(decoderInfos)
                    decoderInfos.reverse()
                }
                decoderInfos
            }
    }

    private fun createMediaFactory(
        headers: Map<String, String>,
        isHttp: Boolean
    ): DefaultMediaSourceFactory {
        val dataSourceFactory: DataSource.Factory = if (isHttp) {
            OkHttpDataSource.Factory(okHttpClient.getProxyOkHttpClient())
                .setDefaultRequestProperties(headers)
        } else {
            DefaultDataSource.Factory(requireContext())
        }

        return DefaultMediaSourceFactory(requireContext()).setDataSourceFactory(dataSourceFactory)
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

    private fun toggleStretchMode() {
        isStretched = !isStretched

        if (isStretched) {
            dataBinding.videoView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            dataBinding.toolbar.visibility = View.GONE
        } else {
            dataBinding.videoView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            dataBinding.toolbar.visibility = View.VISIBLE
        }
    }
}
