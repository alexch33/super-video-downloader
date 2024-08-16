package com.myAllVideoBrowser.ui.component.dialog

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import androidx.databinding.DataBindingUtil
import androidx.databinding.ObservableField
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.databinding.DialogDownloadVideoBinding
import com.myAllVideoBrowser.databinding.DownloadCandidateItemBinding
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.util.AppUtil

var titleStore = TitleStore("")

fun showDownloadVideoDialog(
    activity: Activity,
    downloadDialogListener: DownloadDialogListener,
    mainActivity: MainActivity,
    candidates: VideoInfo,
    appUtil: AppUtil
) {
    val mainViewModel = mainActivity.mainViewModel
    val selectedFormat = mainViewModel.selectedFormatTitle

    titleStore.title = candidates.title

    val bottomSheetDialog = BottomSheetDialog(activity)
    val binding = DialogDownloadVideoBinding.inflate(activity.layoutInflater, null, false).apply {
        this.mainViewModel = mainViewModel
        this.videInfo = candidates

        if (mainViewModel.selectedFormatTitle.get() == null) {
            mainViewModel.selectedFormatTitle.set(
                Pair(
                    candidates.formats.formats.lastOrNull()?.format ?: "", titleStore.title
                )
            )
        }

        this.videoTitleEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                this.videoTitleEdit.clearFocus()
                titleStore.title = this.videoTitleEdit.text.toString()
                val sFormat = selectedFormat.get()?.first
                if (sFormat != null) {
                    mainViewModel.selectedFormatTitle.set(Pair(sFormat, titleStore.title))
                }
                appUtil.hideSoftKeyboard(this.videoTitleEdit)
                false
            } else false
        }

        this.videoTitleEdit.setText(candidates.title)

        this.videoTitleEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
            }

            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
                val sFormat = selectedFormat.get()?.first
                titleStore.title = p0.toString()
                if (sFormat != null) {
                    mainViewModel.selectedFormatTitle.set(Pair(sFormat, titleStore.title))
                }
            }

            override fun afterTextChanged(p0: Editable?) {
            }
        })

        this.dialogListener = downloadDialogListener
        this.dialog = bottomSheetDialog
        this.candidatesList.adapter = CandidatesListAdapter(
            activity.applicationContext, candidates, selectedFormat, downloadDialogListener
        )

        this.videoTitleRenameButton.setOnClickListener {
            this.videoTitleEdit.requestFocus()
            this.videoTitleEdit.setSelection(this.videoTitleEdit.text.toString().length)
            appUtil.showSoftKeyboard(this.videoTitleEdit)
        }
    }

    val contentView = binding.root
    contentView.setBackgroundColor(contentView.context.resources.getColor(android.R.color.transparent))

    bottomSheetDialog.setContentView(contentView)
    bottomSheetDialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    bottomSheetDialog.setOnDismissListener {
        mainViewModel.selectedFormatTitle.set(null)
    }
    bottomSheetDialog.setOnCancelListener {
        mainViewModel.selectedFormatTitle.set(null)
    }

    bottomSheetDialog.show()
}

class CandidatesListAdapter(
    context: Context,
    private val downloadCandidates: VideoInfo,
    private val selectedFormat: ObservableField<Pair<String, String>?>,
    private val downloadDialogListener: CandidateFormatListener,
) : ArrayAdapter<CandidatesListAdapter.CandidatesViewHolder>(
    context, R.layout.download_candidate_item
) {
    private var formats: List<VideoFormatEntity> = arrayListOf()

    init {
        val allFormats = downloadCandidates.formats.formats

        formats = getShortenFormats(allFormats).asReversed()
    }

    override fun getView(position: Int, view: View?, parent: ViewGroup): View {
        val binding = if (view == null) {
            val inflater = LayoutInflater.from(parent.context)
            DownloadCandidateItemBinding.inflate(inflater, parent, false)
        } else {
            DataBindingUtil.getBinding(view)
        }

        with(binding) {
            val candidate = formats[position].format ?: "error"

            this!!.listener = object : CandidateFormatListener {
                override fun onSelectFormat(videoInfo: VideoInfo, format: String) {
                    downloadDialogListener.onSelectFormat(videoInfo, format)
                    notifyDataSetChanged()
                }
            }
            this.videoInfo = downloadCandidates
            this.downloadCandidate = candidate
            this.isCandidateSelected = candidate == selectedFormat.get()?.first
            this.tvTitle.text = getShortOfFormat(candidate)

            this.executePendingBindings()
        }

        return binding!!.root
    }

    override fun getItemId(position: Int): Long {
        return try {
            val format = formats[position]
            val isSelected = format.format == selectedFormat.get()?.first
            val coef = if (isSelected) 1 else 0
            format.hashCode().toLong() + coef
        } catch (e: Exception) {
            0
        }
    }

    override fun getCount(): Int {
        return formats.size
    }

    class CandidatesViewHolder(val binding: DownloadCandidateItemBinding) :
        RecyclerView.ViewHolder(binding.root)

    fun setData(formats: List<VideoFormatEntity>) {
        this.formats = formats
        notifyDataSetChanged()
    }

    private fun makeVideoFormatHumanReadable(input: String): String {
        return input.replace(Regex("-\\w+"), "")
    }

    private fun getShortenFormats(allFormats: List<VideoFormatEntity>): List<VideoFormatEntity> {
        val formatsMap = mutableMapOf<String, VideoFormatEntity>()
        for (format in allFormats) {
            formatsMap[getShortOfFormat(format.format)] = format
        }

        formatsMap.remove("")

        formatsMap.toSortedMap()

        return formatsMap.toSortedMap().values.toList()
    }

    private fun getShortOfFormat(format: String?): String {
        val formattedFormat = makeVideoFormatHumanReadable(format ?: "error")
        if (formattedFormat != "error") {
            return if (formattedFormat.contains("x")) {
                "${formattedFormat.split("x").last().replace(Regex("\\D"), "")}P"
            } else if (!formattedFormat.contains("x") && !formattedFormat.contains("audio only")
                && formattedFormat.contains("-")
            ) {
                val leftSide = formattedFormat.split("-").first()
                leftSide.replace("p", "P").trim()
            } else if (formattedFormat.contains("audio only")) {
                ""
            } else {
                formattedFormat
            }
        }

        return "Error"
    }
}

interface DownloadVideoListener {
    fun onPreviewVideo(
        videoInfo: VideoInfo,
        dialog: BottomSheetDialog?,
        format: String,
        isForce: Boolean
    )

    fun onDownloadVideo(
        videoInfo: VideoInfo,
        dialog: BottomSheetDialog?,
        format: String,
        videoTitle: String
    )
}

interface DownloadTabVideoListener {
    fun onPreviewVideo(
        videoInfo: VideoInfo,
        format: String,
        isForce: Boolean
    )

    fun onDownloadVideo(
        videoInfo: VideoInfo,
        format: String,
        videoTitle: String
    )
}

interface DownloadDialogListener : DownloadVideoListener, CandidateFormatListener {
    fun onCancel(dialog: BottomSheetDialog?)
}

interface DownloadTabListener : DownloadTabVideoListener, CandidateFormatListener {
    fun onCancel()
}

interface CandidateFormatListener {
    fun onSelectFormat(videoInfo: VideoInfo, format: String)
}

class TitleStore(var title: String)