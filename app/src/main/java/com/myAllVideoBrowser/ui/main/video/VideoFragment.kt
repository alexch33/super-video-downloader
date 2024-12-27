package com.myAllVideoBrowser.ui.main.video

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.color.MaterialColors
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.model.LocalVideo
import com.myAllVideoBrowser.databinding.FragmentVideoBinding
import com.myAllVideoBrowser.ui.component.adapter.VideoAdapter
import com.myAllVideoBrowser.ui.component.adapter.VideoListener
import com.myAllVideoBrowser.ui.component.dialog.showRenameVideoDialog
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.ui.main.player.VideoPlayerActivity
import com.myAllVideoBrowser.ui.main.player.VideoPlayerFragment
import com.myAllVideoBrowser.ui.main.progress.WrapContentLinearLayoutManager
import com.myAllVideoBrowser.ui.main.video.VideoViewModel.Companion.FILE_EXIST_ERROR_CODE
import com.myAllVideoBrowser.util.AppUtil
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.IntentUtil
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

//@OpenForTesting
class VideoFragment : BaseFragment() {

    companion object {
        fun newInstance() = VideoFragment()
    }

    private var disposable: Disposable? = null

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    @Inject
    lateinit var intentUtil: IntentUtil

    @Inject
    lateinit var fileUtil: FileUtil

    @Inject
    lateinit var appUtil: AppUtil

    @Inject
    lateinit var mainActivity: MainActivity

    private lateinit var dataBinding: FragmentVideoBinding

    private lateinit var videoViewModel: VideoViewModel

    private lateinit var videoAdapter: VideoAdapter

    private var counter = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        videoViewModel = ViewModelProvider(this, viewModelFactory)[VideoViewModel::class.java]
        videoAdapter = VideoAdapter(emptyList(), videoListener, fileUtil)

        val isDark = mainActivity.settingsViewModel.isDarkMode.get()
        val color = if (isDark) {
            MaterialColors.getColor(requireContext(), R.attr.editTextColor, Color.YELLOW)
        } else {
            null
        }

        dataBinding = FragmentVideoBinding.inflate(inflater, container, false).apply {
            val managerL =
                WrapContentLinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)

            this.viewModel = videoViewModel
            this.mainViewModel = mainActivity.mainViewModel
            this.rvVideo.layoutManager = managerL
            this.rvVideo.adapter = videoAdapter
            if (color != null) {
                this.ivEmptyIcon.setBackgroundColor(color)
            }
        }

        videoViewModel.shareEvent.observe(viewLifecycleOwner) { uri ->
            intentUtil.shareVideo(requireContext(), uri)
        }

        return dataBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        videoViewModel.start()
        handleUIEvents()
        handleIfStartedFromNotification()
    }

    private fun handleUIEvents() {
        videoViewModel.apply {
            renameErrorEvent.observe(viewLifecycleOwner) { errorCode ->
                val errorMessage =
                    if (errorCode == FILE_EXIST_ERROR_CODE) R.string.video_rename_exist else R.string.video_rename_invalid
                activity?.runOnUiThread {
                    Toast.makeText(context, context?.getString(errorMessage), Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
    }

    private fun handleIfStartedFromNotification() {
        mainActivity.mainViewModel.openDownloadedVideoEvent.observe(viewLifecycleOwner) { downloadFilename ->
            disposable?.dispose()
            disposable = null
            disposable =
                videoViewModel.findVideoByName(downloadFilename).subscribeOn(Schedulers.io())
                    .observeOn(Schedulers.single()).subscribe { video ->
                        startVideo(video)
                    }
        }
    }

    private val videoListener = object : VideoListener {
        override fun onItemClicked(localVideo: LocalVideo) {
            startVideo(localVideo)
        }

        override fun onMenuClicked(view: View, localVideo: LocalVideo) {
            showPopupMenu(view, localVideo)
        }
    }

    private fun showPopupMenu(view: View, video: LocalVideo) {
        val myView = fixPopup(dataBinding.anchor, view)

        val popupMenu = PopupMenu(myView.context, myView)
        popupMenu.menuInflater.inflate(R.menu.menu_video, popupMenu.menu)
        popupMenu.setForceShowIcon(true)
        popupMenu.show()

        popupMenu.setOnMenuItemClickListener { arg0 ->
            when (arg0.itemId) {
                R.id.item_rename -> {
                    showRenameVideoDialog(
                        view.context, appUtil, video.name
                    ) { v ->
                        with(v as EditText) {
                            val newName = v.text.toString().trim()
                            videoViewModel.renameVideo(
                                v.context, video.uri, File(newName).nameWithoutExtension + ".mp4"
                            )
                        }
                    }
                    true
                }

                R.id.item_open_with -> {
                    startVideoWith(video)
                    true
                }

                R.id.item_delete -> {
                    context?.let { videoViewModel.deleteVideo(it, video) }
                    true
                }

                R.id.item_share -> {
                    videoViewModel.shareEvent.value = video.uri
                    true
                }

                R.id.item_open_in_folder -> {
//                    file.parent?.let { intentUtil.openVideoFolder(view.context, it) }
                    true
                }

                else -> false
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun startVideo(localVideo: LocalVideo) {
        startActivity(Intent(
            requireContext(), VideoPlayerActivity::class.java
        ).apply {
            putExtra(VideoPlayerFragment.VIDEO_NAME, localVideo.name)
            putExtra(
                VideoPlayerFragment.VIDEO_URL, localVideo.uri.toString()
            )
        })
    }

    private fun startVideoWith(localVideo: LocalVideo) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context?.let {
            val fileSupported = fileUtil.isFileApiSupportedByUri(it, localVideo.uri)
            if (fileSupported) {
                val videoUri = FileProvider.getUriForFile(
                    requireContext(),
                    requireContext().applicationContext.packageName + ".provider",
                    localVideo.uri.toFile()
                )
                intent.setDataAndType(videoUri, "video/mp4")
            } else {
                intent.setDataAndType(localVideo.uri, "video/mp4")
            }
        }

        context?.startActivity(intent)
    }
}