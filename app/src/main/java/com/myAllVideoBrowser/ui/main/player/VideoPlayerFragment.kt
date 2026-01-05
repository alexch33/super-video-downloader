package com.myAllVideoBrowser.ui.main.player

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.addCallback
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import com.google.common.util.concurrent.ListenableFuture
import com.myAllVideoBrowser.databinding.FragmentPlayerBinding
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.util.AppUtil
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

    private val videoPlayerViewModel: VideoPlayerViewModel by viewModels { viewModelFactory }

    private var _dataBinding: FragmentPlayerBinding? = null
    private val dataBinding get() = _dataBinding!!

    private var mediaControllerFuture: ListenableFuture<MediaController>? = null
    private val mediaController: MediaController?
        get() = if (mediaControllerFuture?.isDone == true) mediaControllerFuture?.get() else null

    private var isStretched = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _dataBinding = FragmentPlayerBinding.inflate(inflater, container, false)
        dataBinding.viewModel = videoPlayerViewModel
        return dataBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupViewModel()
        setupUI()
        handleBackPressed()

        videoPlayerViewModel.start()
        getActivity(context)?.let { appUtil.hideSystemUI(it.window, dataBinding.root) }
    }

    private fun setupViewModel() {
        arguments?.let { args ->
            val rawHeaders = args.getString(VIDEO_HEADERS)
            if (rawHeaders != null) {
                runCatching {
                    val headersMap = Json.parseToJsonElement(rawHeaders).jsonObject
                        .mapValues { it.value.toString().removeSurrounding("\"") }
                    videoPlayerViewModel.videoHeaders.set(headersMap)
                }
            }
            args.getString(VIDEO_NAME)?.let { videoPlayerViewModel.videoName.set(it) }
            args.getString(VIDEO_URL)?.toUri()?.let { videoPlayerViewModel.videoUrl.set(it) }
        }
    }

    private fun setupUI() {
        dataBinding.toolbar.setNavigationOnClickListener { handleClose() }
        dataBinding.videoView.setFullscreenButtonClickListener { toggleStretchMode() }
    }

    override fun onStart() {
        super.onStart()
        initializeController()
    }

    override fun onStop() {
        super.onStop()
        releaseController()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        dataBinding.videoView.player = null
        _dataBinding = null
    }

    private fun initializeController() {
        val sessionToken = SessionToken(
            requireContext(),
            ComponentName(requireContext(), PlaybackService::class.java)
        )
        mediaControllerFuture = MediaController.Builder(requireContext(), sessionToken).buildAsync()

        mediaControllerFuture?.addListener({
            val controller = this.mediaController ?: return@addListener
            dataBinding.videoView.player = controller
            controller.addListener(playerListener)
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun releaseController() {
        mediaControllerFuture?.let {
            MediaController.releaseFuture(it)
        }
        mediaControllerFuture = null
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val isLoading =
                playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE
            dataBinding.loadingBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        }

        override fun onPlayerError(error: PlaybackException) {
            val errorMessage = "Playback Error: ${error.message ?: "Unknown error"}"
            Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
            activity?.finish()
        }
    }

    private fun handleBackPressed() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            handleClose()
        }
    }

    private fun handleClose() {
        mediaController?.stop()
        mediaController?.clearMediaItems()
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

    private fun getActivity(context: Context?): Activity? {
        var currentContext = context
        while (currentContext is ContextWrapper) {
            if (currentContext is Activity) {
                return currentContext
            }
            currentContext = currentContext.baseContext
        }
        return null
    }
}
