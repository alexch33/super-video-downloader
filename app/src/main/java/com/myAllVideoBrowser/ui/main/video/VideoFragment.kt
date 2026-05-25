package com.myAllVideoBrowser.ui.main.video

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.core.view.get
import androidx.databinding.Observable
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

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

    private var backPressedCallback: OnBackPressedCallback? = null

    private val navCallback = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            if (videoAdapter.isSelectionMode) {
                exitSelectionMode()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        videoViewModel = ViewModelProvider(this, viewModelFactory)[VideoViewModel::class.java]
        videoAdapter = VideoAdapter(emptyList(), videoListener, fileUtil)

        dataBinding = FragmentVideoBinding.inflate(inflater, container, false).apply {
            val managerL =
                WrapContentLinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)

            this.viewModel = videoViewModel
            this.mainViewModel = mainActivity.mainViewModel
            this.rvVideo.layoutManager = managerL
            this.rvVideo.adapter = videoAdapter
            this.rvVideo.isClickable = true
            this.clContent.isClickable = true
            this.isSelectionMode = false
            this.isAllSelected = false
        }

        videoViewModel.shareEvent.observe(viewLifecycleOwner) { uri ->
            intentUtil.shareVideo(requireContext(), uri)
        }

        setupSelectionControls()
        setupNavigationObservers()

        return dataBinding.root
    }

    private fun setupNavigationObservers() {
        mainActivity.mainViewModel.isBrowserCurrent.addOnPropertyChangedCallback(navCallback)
        mainActivity.mainViewModel.currentItem.addOnPropertyChangedCallback(navCallback)
        mainActivity.mainViewModel.openNavDrawerEvent.observe(viewLifecycleOwner) {
            if (videoAdapter.isSelectionMode) {
                exitSelectionMode()
            }
        }
    }

    override fun onDestroyView() {
        mainActivity.mainViewModel.isBrowserCurrent.removeOnPropertyChangedCallback(navCallback)
        mainActivity.mainViewModel.currentItem.removeOnPropertyChangedCallback(navCallback)

        backPressedCallback?.remove()
        backPressedCallback = null

        disposable?.dispose()
        disposable = null

        dataBinding.let {
            it.cbSelectAll.setOnClickListener(null)
            it.btnDeleteSelected.setOnClickListener(null)
            it.rvVideo.setOnClickListener(null)
            it.clContent.setOnClickListener(null)
            it.root.setOnClickListener(null)
        }
        super.onDestroyView()
    }

    private fun setupSelectionControls() {
        dataBinding.cbSelectAll.setOnClickListener {
            if (dataBinding.cbSelectAll.isChecked) {
                videoAdapter.selectAll()
            } else {
                videoAdapter.deselectAll()
            }
            updateSelectionState()
        }

        dataBinding.btnDeleteSelected.setOnClickListener {
            showDeleteConfirmationDialog {
                val selectedIds = videoAdapter.selectedItems.toList()
                val videosToDelete =
                    videoViewModel.localVideos.get()?.filter { selectedIds.contains(it.id) }
                videosToDelete?.forEach { video ->
                    context?.let { videoViewModel.deleteVideo(it, video) }
                }
                exitSelectionMode()
            }
        }

        val exitSelectionClickListener = View.OnClickListener {
            if (videoAdapter.isSelectionMode) {
                exitSelectionMode()
            }
        }

        dataBinding.rvVideo.setOnClickListener(exitSelectionClickListener)
        dataBinding.clContent.setOnClickListener(exitSelectionClickListener)
        dataBinding.root.setOnClickListener(exitSelectionClickListener)

        backPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (videoAdapter.isSelectionMode) {
                    exitSelectionMode()
                } else {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            backPressedCallback!!
        )
    }

    private fun showDeleteConfirmationDialog(onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete)
            .setPositiveButton(R.string.delete) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.all_text_cancel, null)
            .show()
    }

    private fun exitSelectionMode() {
        videoAdapter.isSelectionMode = false
        videoAdapter.deselectAll()
        dataBinding.isSelectionMode = false
        dataBinding.isAllSelected = false
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
            if (videoAdapter.isSelectionMode) {
                exitSelectionMode()
            }
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
            if (videoAdapter.isSelectionMode) {
                videoAdapter.toggleSelection(localVideo.id)
                updateSelectionState()
            } else {
                startVideo(localVideo)
            }
        }

        override fun onMenuClicked(view: View, localVideo: LocalVideo) {
            showPopupMenu(view, localVideo)
        }

        override fun onItemLongClicked(localVideo: LocalVideo) {
            if (!videoAdapter.isSelectionMode) {
                videoAdapter.isSelectionMode = true
                videoAdapter.toggleSelection(localVideo.id)
                dataBinding.isSelectionMode = true
                updateSelectionState()
            }
        }

        override fun onItemSelected(localVideo: LocalVideo, isSelected: Boolean) {
            videoAdapter.toggleSelection(localVideo.id)
            updateSelectionState()
        }
    }

    private fun updateSelectionState() {
        val totalItems = videoViewModel.localVideos.get()?.size ?: 0
        val selectedCount = videoAdapter.selectedItems.size
        dataBinding.isAllSelected = totalItems > 0 && selectedCount == totalItems
    }

    private fun showPopupMenu(view: View, video: LocalVideo) {
        val popupMenu = PopupMenu(view.context, view)

        popupMenu.menuInflater.inflate(R.menu.menu_video, popupMenu.menu)
        popupMenu.setForceShowIcon(true)
        popupMenu.menu[5].isVisible = isVideoInHiddenFolderFolder(video)
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
                    showDeleteConfirmationDialog {
                        context?.let { videoViewModel.deleteVideo(it, video) }
                    }
                    true
                }

                R.id.item_share -> {
                    videoViewModel.shareEvent.value = video.uri
                    true
                }

                R.id.item_open_in_folder -> {
                    true
                }

                R.id.item_move_to_downloads -> {
                    val targetDir =
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val target = File(targetDir, video.name)

                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        videoViewModel.isLoading.set(true)
                        try {
                            val isSuccess =
                                fileUtil.moveMedia(requireContext(), video.uri, target.toUri())
                            withContext(Dispatchers.Main) {
                                val msg =
                                    if (isSuccess) R.string.media_move_success else R.string.media_move_error
                                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    requireContext(),
                                    R.string.media_move_error,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        } finally {
                            videoViewModel.isLoading.set(false)
                        }
                    }
                    true
                }

                else -> false
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun startVideo(localVideo: LocalVideo) {
        startActivity(
            Intent(
                requireContext(), VideoPlayerActivity::class.java
            ).apply {
                putExtra(VideoPlayerFragment.VIDEO_NAME, localVideo.name)
                putExtra(
                    VideoPlayerFragment.VIDEO_URL, localVideo.uri.toString()
                )
            })
    }

    private fun isVideoInHiddenFolderFolder(video: LocalVideo): Boolean {
        if (video.uri.scheme != "file") {
            return false
        }

        return try {
            val downloadsDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val videoParentDir = video.uri.toFile().parentFile
            videoParentDir != null && videoParentDir.absolutePath != downloadsDir.absolutePath
        } catch (e: Exception) {
            false
        }
    }


    private fun startVideoWith(localVideo: LocalVideo) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context?.let {
            val fileSupported = fileUtil.isFileApiSupportedByUri(it, localVideo.uri)
            if (fileSupported && localVideo.uri.scheme == "file") {
                try {
                    val videoUri = FileProvider.getUriForFile(
                        requireContext(),
                        requireContext().applicationContext.packageName + ".provider",
                        localVideo.uri.toFile()
                    )
                    intent.setDataAndType(videoUri, "video/mp4")
                } catch (e: Exception) {
                    intent.setDataAndType(localVideo.uri, "video/mp4")
                }
            } else {
                intent.setDataAndType(localVideo.uri, "video/mp4")
            }
        }

        context?.startActivity(intent)
    }
}
