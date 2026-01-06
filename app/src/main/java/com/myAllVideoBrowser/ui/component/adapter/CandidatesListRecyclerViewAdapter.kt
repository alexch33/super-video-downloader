package com.myAllVideoBrowser.ui.component.adapter

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.databinding.ObservableField
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.databinding.DownloadCandidateItemBinding
import com.myAllVideoBrowser.util.FileUtil


interface DownloadVideoListener {
    fun onPreviewVideo(
        videoInfo: VideoInfo, dialog: BottomSheetDialog?, format: String, isForce: Boolean
    )

    fun onDownloadVideo(
        videoInfo: VideoInfo, dialog: BottomSheetDialog?, format: String, videoTitle: String
    )
}

interface DownloadTabVideoListener {
    fun onPreviewVideo(
        videoInfo: VideoInfo, format: String, isForce: Boolean
    )

    fun onDownloadVideo(
        videoInfo: VideoInfo, format: String, videoTitle: String
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

    fun onFormatUrlShare(videoInfo: VideoInfo, format: String): Boolean
}

class CandidatesListRecyclerViewAdapter(
    private val downloadCandidates: VideoInfo,
    private val selectedFormat: ObservableField<Map<String, String>>,
    private val downloadDialogListener: CandidateFormatListener
) : RecyclerView.Adapter<CandidatesListRecyclerViewAdapter.CandidatesViewHolder>() {

    private var formats: List<VideoFormatEntity> = arrayListOf()

    init {
        val allFormats = downloadCandidates.formats.formats
        formats = getShortenFormats(allFormats)
    }

    class CandidatesViewHolder(val binding: DownloadCandidateItemBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CandidatesViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = DownloadCandidateItemBinding.inflate(inflater, parent, false)
        return CandidatesViewHolder(binding)
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: CandidatesViewHolder, position: Int) {
        val formatEntity = formats[position]
        val candidate = formatEntity.format ?: "error"

        with(holder.binding) {
            val selected = selectedFormat.get()?.get(downloadCandidates.id)

            val color = MaterialColors.getColor(
                this.root.context, R.attr.colorSurfaceVariant, Color.YELLOW
            )
            this.cardItem.setCardBackgroundColor(color)

            this.videoInfo = downloadCandidates
            this.downloadCandidate = candidate
            this.isCandidateSelected = candidate == selected
            this.tvTitle.text = getShortOfFormat(candidate, downloadCandidates.isDetectedBySuperX)

            this.listener = object : CandidateFormatListener {
                override fun onSelectFormat(videoInfo: VideoInfo, format: String) {
                    val currentPosition = holder.bindingAdapterPosition
                    if (currentPosition != RecyclerView.NO_POSITION) {
                        downloadDialogListener.onSelectFormat(videoInfo, format)
                        notifyDataSetChanged()
                    }
                }

                override fun onFormatUrlShare(videoInfo: VideoInfo, format: String): Boolean {
                    val currentPosition = holder.bindingAdapterPosition
                    return if (currentPosition != RecyclerView.NO_POSITION) {
                        downloadDialogListener.onFormatUrlShare(videoInfo, format)
                    } else {
                        false
                    }
                }
            }

            val formatSize = if (formatEntity.fileSizeApproximate > 0) {
                FileUtil.getFileSizeReadable(formatEntity.fileSizeApproximate.toDouble())
            } else if (formatEntity.fileSize > 0) {
                FileUtil.getFileSizeReadable(formatEntity.fileSize.toDouble())
            } else {
                "Unknown"
            }
            val fileSizeLine = "File size: $formatSize"

            val durationLine = formatDuration(formatEntity.duration ?: 0)
            val details = listOf(
                "vcodec: ${formatEntity.vcodec ?: "unknown"}",
                "acodec: ${formatEntity.acodec ?: "unknown"}",
                fileSizeLine,
                formatEntity.formatNote,
                if (durationLine.isNotEmpty()) "Duration: $durationLine" else ""
            ).filter { it != null && it.isNotBlank() }.joinToString("\n")

            this.tvData.text = details

            this.executePendingBindings()
        }
    }

    override fun getItemCount(): Int = formats.size

    fun setData(formats: List<VideoFormatEntity>) {
        this.formats = formats
        notifyDataSetChanged()
    }

    private fun makeVideoFormatHumanReadable(input: String, isDetectedBySuperX: Boolean): String {
        val lowercasedInput = input.lowercase()
        return when {
            isDetectedBySuperX && (lowercasedInput.startsWith("mpd-") || lowercasedInput.startsWith(
                "hls-"
            )) -> {
                val parts = lowercasedInput.split('-')
                if (parts.size >= 2) {
                    val type = parts[0].uppercase() // "MPD" or "HLS"
                    val resolution = parts[1] // "1080p" or "audio"
                    if (resolution.contains("p")) {
                        "$type ${resolution.uppercase()}" // "MPD 1080P"
                    } else {
                        "$type Audio" // "HLS Audio"
                    }
                } else {
                    input
                }
            }

            else -> input.replace(Regex("-\\w+"), "")
        }
    }

    private fun getShortenFormats(allFormats: List<VideoFormatEntity>): List<VideoFormatEntity> {
        val formatsMap = mutableMapOf<String, VideoFormatEntity>()
        for (format in allFormats) {
            formatsMap[format.format.toString()] = format
        }

        formatsMap.remove("")

        formatsMap.toSortedMap()

        return formatsMap.toSortedMap().values.toList().sortedBy { it.formatNote }
    }

    private fun getShortOfFormat(format: String?, detectedBySuperX: Boolean): String {
        val formattedFormat = makeVideoFormatHumanReadable(format ?: "error", detectedBySuperX)
        if (formattedFormat != "error") {
            return if (formattedFormat.contains("x")) {
                "${parseHeight(formattedFormat)}P"
            } else if (!formattedFormat.contains("x") && !formattedFormat.contains("audio only") && formattedFormat.contains(
                    "-"
                )
            ) {
                val leftSide = formattedFormat.split("-").first()
                if (leftSide.lowercase().contains("hd") || leftSide.contains("sd")) {
                    return leftSide.trim()
                }
                val rightSide = formattedFormat.split("-").last()
                rightSide.replace("p", "P").trim()
            } else if (formattedFormat.contains("audio only")) {
                ""
            } else {
                formattedFormat
            }
        }

        return "Error"
    }

    private fun parseHeight(input: String): Int? {
        val regex = Regex("""\d+x(\d+)""")
        val matchResult = regex.find(input)

        return if (matchResult != null) {
            matchResult.groupValues[1].toIntOrNull()
        } else {
            null
        }
    }

    private fun formatDuration(milliseconds: Long): String {
        if (milliseconds <= 0) {
            return ""
        }

        val totalSeconds = milliseconds / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}
