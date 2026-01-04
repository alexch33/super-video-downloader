package com.myAllVideoBrowser.ui.main.player

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import com.myAllVideoBrowser.databinding.FragmentPlayerBinding
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.util.AppUtil
import com.google.common.util.concurrent.MoreExecutors
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

    private var mediaController: MediaController? = null
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

        val rawHeaders = arguments?.getString(VIDEO_HEADERS)
        if (rawHeaders != null) {
            val headersMap = Json.parseToJsonElement(rawHeaders).jsonObject
                .mapValues { it.value.toString().removeSurrounding("\"") }
            videoPlayerViewModel.videoHeaders.set(headersMap)
        }
        arguments?.getString(VIDEO_NAME)?.let { videoPlayerViewModel.videoName.set(it) }
        arguments?.getString(VIDEO_URL)?.toUri()?.let { videoPlayerViewModel.videoUrl.set(it) }

        dataBinding = FragmentPlayerBinding.inflate(inflater, container, false).apply {
            viewModel = videoPlayerViewModel
            toolbar.setNavigationOnClickListener(navigationIconClickListener)
            videoView.setFullscreenButtonClickListener { toggleStretchMode() }
        }
        startPlaybackService()

        return dataBinding.root
    }

    private fun startPlaybackService() {
        val serviceIntent = Intent(requireContext(), PlaybackService::class.java).apply {
            putExtra(VIDEO_URL, videoPlayerViewModel.videoUrl.get().toString())
            putExtra(VIDEO_HEADERS, arguments?.getString(VIDEO_HEADERS))
        }
        ContextCompat.startForegroundService(requireContext(), serviceIntent)
    }

    override fun onStart() {
        super.onStart()
        initializeController()
    }

    private fun initializeController() {
        val sessionToken = SessionToken(
            requireContext(),
            ComponentName(requireContext(), PlaybackService::class.java)
        )
        val controllerFuture = MediaController.Builder(requireContext(), sessionToken).buildAsync()

        controllerFuture.addListener({
            mediaController = controllerFuture.get()
            dataBinding.videoView.player = mediaController
            mediaController?.addListener(playerListener)
        }, MoreExecutors.directExecutor())
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
                dataBinding.loadingBar.visibility = View.GONE
            } else {
                dataBinding.loadingBar.visibility = View.VISIBLE
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Toast.makeText(context, "Playback Error: ${error.message}", Toast.LENGTH_LONG).show()
            activity?.finish()
        }
    }

    override fun onStop() {
        super.onStop()
        mediaController?.release()
        mediaController = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        videoPlayerViewModel.stop()
        mediaController?.removeListener(playerListener)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        handleBackPressed()
        videoPlayerViewModel.start()
        getActivity(context)?.let { appUtil.hideSystemUI(it.window, dataBinding.root) }
    }

    private fun getActivity(context: Context?): Activity? {
        if (context == null) {
            return null
        } else if (context is ContextWrapper) {
            return context as? Activity ?: getActivity(context.baseContext)
        }
        return null
    }

    private val navigationIconClickListener = View.OnClickListener {
        handleClose()
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
        mediaController?.stop()
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
