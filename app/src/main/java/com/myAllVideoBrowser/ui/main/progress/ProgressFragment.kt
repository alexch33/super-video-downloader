package com.myAllVideoBrowser.ui.main.progress

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.Recycler
import com.google.android.material.color.MaterialColors
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.databinding.FragmentProgressBinding
import com.myAllVideoBrowser.ui.component.adapter.ProgressAdapter
import com.myAllVideoBrowser.ui.component.adapter.ProgressListener
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.ui.main.home.MainViewModel
import com.myAllVideoBrowser.util.AppLogger
import javax.inject.Inject

//@OpenForTesting
class ProgressFragment : BaseFragment() {

    companion object {
        fun newInstance() = ProgressFragment()
    }

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    @Inject
    lateinit var mainActivity: MainActivity

    private lateinit var progressViewModel: ProgressViewModel

    private lateinit var mainViewModel: MainViewModel

    private lateinit var dataBinding: FragmentProgressBinding

    private lateinit var progressAdapter: ProgressAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        mainViewModel = mainActivity.mainViewModel
        progressViewModel =
            ViewModelProvider(this, viewModelFactory)[ProgressViewModel::class.java]
        progressAdapter = ProgressAdapter(emptyList(), progressListener)

        val isDark = mainActivity.settingsViewModel.isDarkMode.get()
        val color = if (isDark) {
            MaterialColors.getColor(requireContext(), R.attr.editTextColor, Color.YELLOW)
        } else {
            null
        }

        dataBinding = FragmentProgressBinding.inflate(inflater, container, false).apply {
            val managerL =
                WrapContentLinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            this.mainViewModel = mainActivity.mainViewModel
            this.viewModel = progressViewModel
            this.rvProgress.layoutManager = managerL
            this.rvProgress.adapter = progressAdapter
            if (color != null) {
                this.ivEmptyIcon.setBackgroundColor(color)
            }
        }

        return dataBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        progressViewModel.start()
        handleDownloadVideoEvent()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        progressViewModel.stop()
    }

    private fun handleDownloadVideoEvent() {
        mainViewModel.downloadVideoEvent.observe(viewLifecycleOwner, Observer { videoInfo ->
            val currentOriginal = videoInfo.originalUrl
            mainViewModel.currentOriginal.set(currentOriginal)
            progressViewModel.downloadVideo(videoInfo)
        })
    }

    private val progressListener = object : ProgressListener {
        override fun onMenuClicked(view: View, downloadId: Long, isRegular: Boolean) {
            showPopupMenu(view, downloadId, isRegular)
        }
    }

    private fun showPopupMenu(view: View, downloadId: Long, isRegular: Boolean) {
        val myView = fixPopup(dataBinding.anchor, view)

        val menuCandidate = progressViewModel.progressInfos.get()?.find { it.downloadId == downloadId }

        val popupMenu = PopupMenu(myView.context, myView)
        popupMenu.menuInflater.inflate(R.menu.menu_progress, popupMenu.menu)

//        popupMenu.menu.getItem(3).isVisible = !isRegular

        popupMenu.menu.getItem(3).isVisible = menuCandidate?.isLive == true

        popupMenu.setForceShowIcon(true)
        popupMenu.show()

        popupMenu.setOnMenuItemClickListener { arg0 ->
            when (arg0.itemId) {
                R.id.item_cancel -> {
                    progressViewModel.cancelDownload(downloadId, true)
                    true
                }

                R.id.item_pause -> {
                    progressViewModel.pauseDownload(downloadId)
                    true
                }

                R.id.item_resume -> {
                    progressViewModel.resumeDownload(downloadId)
                    true
                }

                R.id.item_stop_save -> {
                    progressViewModel.stopAndSaveDownload(downloadId)
                    true
                }

                else -> false
            }
        }
    }
}

class WrapContentLinearLayoutManager : LinearLayoutManager {
    constructor(context: Context?) : super(context) {}
    constructor(context: Context?, orientation: Int, reverseLayout: Boolean) : super(
        context,
        orientation,
        reverseLayout
    ) {
    }

    constructor(
        context: Context?,
        attrs: AttributeSet?,
        defStyleAttr: Int,
        defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes) {
    }

    override fun onLayoutChildren(recycler: Recycler, state: RecyclerView.State) {
        try {
            super.onLayoutChildren(recycler, state)
        } catch (e: IndexOutOfBoundsException) {
            AppLogger.e("meet a IOOBE in RecyclerView")
        }
    }
}