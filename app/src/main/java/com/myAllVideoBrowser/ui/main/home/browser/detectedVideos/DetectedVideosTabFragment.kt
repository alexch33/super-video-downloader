package com.myAllVideoBrowser.ui.main.home.browser.detectedVideos

import com.myAllVideoBrowser.ui.component.adapter.VideoInfoAdapter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.addCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.databinding.FragmentDetectedVideosTabBinding
import com.myAllVideoBrowser.ui.component.adapter.DownloadTabListener
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.ui.main.progress.WrapContentLinearLayoutManager
import com.myAllVideoBrowser.util.AppUtil
import javax.inject.Inject

class DetectedVideosTabFragment : BaseFragment() {
    var detectedVideosTabViewModel: VideoDetectionTabViewModel? = null
    var candidateFormatListener: DownloadTabListener? = null

    @Inject
    lateinit var mainActivity: MainActivity

    @Inject
    lateinit var appUtil: AppUtil

    private lateinit var binding: FragmentDetectedVideosTabBinding

    private lateinit var layoutMngr: WrapContentLinearLayoutManager

    companion object {
        fun newInstance() = DetectedVideosTabFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        if (detectedVideosTabViewModel == null || candidateFormatListener == null) {
            Toast.makeText(context, "Something went wrong, try again.", Toast.LENGTH_SHORT).show()
            parentFragmentManager.popBackStack()
            return View(context)
        }

        val adapter = detectedVideosTabViewModel?.let {
            candidateFormatListener?.let { it1 ->
                VideoInfoAdapter(
                    it.detectedVideosList.get()?.toList() ?: emptyList(),
                    it,
                    it1,
                    appUtil,
                )
            }
        }

        layoutMngr = WrapContentLinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)

        binding = FragmentDetectedVideosTabBinding.inflate(inflater, container, false).apply {
            val url = detectedVideosTabViewModel?.webTabModel?.getTabTextInput()?.get()
            title.text = if (!url.isNullOrEmpty()) {
                getString(R.string.found_videos_from, url).split("?").firstOrNull()
            } else {
                getString(R.string.title_video)
            }
            
            viewModel = detectedVideosTabViewModel
            videoInfoList.layoutManager = layoutMngr
            videoInfoList.isNestedScrollingEnabled = true
            videoInfoList.adapter = adapter
            dialogListener = candidateFormatListener

            ViewCompat.setOnApplyWindowInsetsListener(this.root) { v, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
                this.bottomBar.minimumHeight = systemBars.bottom + this.bottomBar.height
                insets
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            parentFragmentManager.popBackStack()
        }

        return binding.root
    }
}
