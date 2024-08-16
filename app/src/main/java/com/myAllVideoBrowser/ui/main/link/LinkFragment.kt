package com.myAllVideoBrowser.ui.main.link

import android.content.ClipDescription.MIMETYPE_TEXT_PLAIN
import android.content.ClipboardManager
import android.content.Context.CLIPBOARD_SERVICE
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.myAllVideoBrowser.DLApplication
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.VideFormatEntityList
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.databinding.FragmentLinkBinding
import com.myAllVideoBrowser.ui.component.dialog.DownloadDialogListener
import com.myAllVideoBrowser.ui.component.dialog.showDownloadVideoDialog
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.ui.main.home.MainViewModel
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.AppUtil
import com.myAllVideoBrowser.util.FileNameCleaner
import javax.inject.Inject


interface IDownloadInfoLinkListener : View.OnClickListener {
    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.pasteButton -> {
                onPasteLinkInfoFromBuffer()
            }

            R.id.downloadByLinkButton -> {
                onFetchDownloadInfo()
            }
        }
    }

    fun onFetchDownloadInfo()

    fun onPasteLinkInfoFromBuffer()
}

class LinkFragment : BaseFragment(), IDownloadInfoLinkListener {

    companion object {
        fun newInstance() = LinkFragment()
    }

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    @Inject
    lateinit var mainActivity: MainActivity

    @Inject
    lateinit var appUtil: AppUtil

    private lateinit var mainViewModel: MainViewModel

    private lateinit var dataBinding: FragmentLinkBinding

    private lateinit var linkViewModel: DownloadLinkViewModel

    private lateinit var clipboard: ClipboardManager

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        mainViewModel = mainActivity.mainViewModel
        linkViewModel =
            ViewModelProvider(this, viewModelFactory)[DownloadLinkViewModel::class.java]

        clipboard = context?.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager

        dataBinding = FragmentLinkBinding.inflate(inflater, container, false).apply {
            this.viewModel = linkViewModel
            this.downloadByLinkButton.setOnClickListener(this@LinkFragment)
            this.pasteButton.setOnClickListener(this@LinkFragment)
        }

        return dataBinding.root
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        linkViewModel.start()

        linkViewModel.showDownloadDialogEvent.observe(viewLifecycleOwner) { videoInfo ->
            // TODO DUPLICATED CODE
            activity?.runOnUiThread {
                showDownloadVideoDialog(
                    requireActivity(),
                    object : DownloadDialogListener {
                        override fun onCancel(dialog: BottomSheetDialog?) {
                            dialog?.dismiss()
                        }

                        override fun onPreviewVideo(
                            videoInfo: VideoInfo,
                            dialog: BottomSheetDialog?,
                            format: String,
                            isForce: Boolean
                        ) {
                            AppLogger.d(
                                "onPreviewVideo: ${videoInfo.formats}  $format $isForce"
                            )
                        }

                        override fun onDownloadVideo(
                            videoInfo: VideoInfo,
                            dialog: BottomSheetDialog?,
                            format: String,
                            videoTitle: String
                        ) {
                            if (videoInfo.isRegularDownload) {
                                mainViewModel.downloadVideoEvent.value = videoInfo
                            } else {
                                val info = videoInfo.copy(
                                    title = FileNameCleaner.cleanFileName(videoTitle),
                                    formats = VideFormatEntityList(videoInfo.formats.formats.filter {
                                        it.format?.contains(
                                            format
                                        ) ?: false
                                    })
                                )

                                mainViewModel.downloadVideoEvent.value = info
                            }
                        }

                        override fun onSelectFormat(videoInfo: VideoInfo, format: String) {
                            val currentFormat = mainActivity.mainViewModel.selectedFormatTitle
                            mainActivity.mainViewModel.selectedFormatTitle.set(currentFormat.get()
                                ?.let { Pair(format, it.second) })
                        }
                    }, mainActivity, videoInfo, appUtil
                )
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        linkViewModel.stop()
    }

    override fun onFetchDownloadInfo() {
        linkViewModel.fetchDownloadInfo(dataBinding.textField.text.toString())
    }

    override fun onPasteLinkInfoFromBuffer() {
        var pasteData = ""

        // If it does contain data, decide if you can handle the data.
        if (!clipboard.hasPrimaryClip()) {
        } else if (!clipboard.primaryClipDescription!!.hasMimeType(
                MIMETYPE_TEXT_PLAIN
            )
        ) {
            // since the clipboard has data but it is not plain text
        } else {
            //since the clipboard contains plain text.
            val item = clipboard.primaryClip!!.getItemAt(0)

            // Gets the clipboard as text.
            pasteData = item.text.toString()

            dataBinding.textField.setText(pasteData)
        }
    }
}