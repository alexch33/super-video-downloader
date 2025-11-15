package com.myAllVideoBrowser.ui.main.settings

import android.content.Context
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.databinding.Observable
import androidx.databinding.Observable.OnPropertyChangedCallback
import androidx.lifecycle.lifecycleScope
import com.myAllVideoBrowser.R
//import com.allVideoDownloaderXmaster.OpenForTesting
import com.myAllVideoBrowser.databinding.FragmentSettingsBinding
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.IntentUtil
import com.myAllVideoBrowser.util.SystemUtil
import kotlinx.coroutines.launch
import javax.inject.Inject

//@OpenForTesting
class SettingsFragment : BaseFragment() {

    companion object {
        fun newInstance() = SettingsFragment()
    }

    @Inject
    lateinit var fileUtil: FileUtil

    @Inject
    lateinit var intentUtil: IntentUtil

    @Inject
    lateinit var systemUtil: SystemUtil

    @Inject
    lateinit var mainActivity: MainActivity

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    private lateinit var dataBinding: FragmentSettingsBinding

    private lateinit var settingsViewModel: SettingsViewModel

    private var lastSavedRegularThreadsCount = -1

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        settingsViewModel = mainActivity.settingsViewModel

        val color = getThemeBackgroundColor()

        dataBinding = FragmentSettingsBinding.inflate(inflater, container, false).apply {
            this.settingsBackground.setBackgroundColor(color)
            this.viewModel = settingsViewModel
            this.isForceStreamDetection.setOnCheckedChangeListener { _, checked ->
                settingsViewModel.setForceStreamDetection(checked)
                if (checked) {
                    settingsViewModel.setRegularThreadsCount(1)
                }
            }
            this.isForceStreamDownloading.setOnCheckedChangeListener { _, checked ->
                settingsViewModel.setForceStreamDownloading(checked)
            }
            this.isAutoThemeCheckBox.setOnCheckedChangeListener { _, checked ->
                settingsViewModel.setIsAutoTheme(checked)
            }
            this.lockOrientationCheckBox.setOnCheckedChangeListener { _, checked ->
                settingsViewModel.setIsLockPortrait(checked)
            }
            this.showVideoAlertCheckBox.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    settingsViewModel.setShowVideoAlertOn()
                } else {
                    settingsViewModel.setShowVideoAlertOff()
                }
            }
            this.showVideoActionButtonCheckBox.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    settingsViewModel.setShowVideoActionButtonOn()
                } else {
                    settingsViewModel.setShowVideoActionButtonOff()
                }
            }
            this.isCheckEveryRequestOnMp4.setOnCheckedChangeListener { _, checked ->
                settingsViewModel.setIsCheckEveryRequestOnVideo(checked)
            }
            this.isCheckEveryRequestOnAudio.setOnCheckedChangeListener { _, checked ->
                settingsViewModel.setIsCheckOnAudio(checked)
            }
            this.findVideosByUrl.setOnCheckedChangeListener { _, checked ->
                settingsViewModel.setIsFindVideoByUrl(checked)
            }

            this.isCheckEveryRequestOnM3u8.setOnCheckedChangeListener { _, checked ->
                settingsViewModel.setIsCheckIfEveryUrlOnM3u8(checked)
            }

            this.seekBarM3u8.progress = settingsViewModel.m3u8ThreadsCount.get()
            this.seekBarM3u8.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    if (fromUser) {
                        settingsViewModel.setM3u8ThreadsCount(progress)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                }
            })
            lastSavedRegularThreadsCount = settingsViewModel.regularThreadsCount.get()
            this.seekBarRegular.progress = settingsViewModel.regularThreadsCount.get()
            this.seekBarRegular.setOnSeekBarChangeListener(object :
                SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    if (fromUser) {
                        val progressFixed = progress + 1
                        if (lastSavedRegularThreadsCount == 1 && progressFixed > 1) {
                            context?.let { showDownloadWarningDialog(it) }
                        }
                        lastSavedRegularThreadsCount = progressFixed

                        settingsViewModel.setRegularThreadsCount(progress)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                }
            })
            this.seekBarAdsTreshold.progress = settingsViewModel.videoDetectionTreshold.get()
            this.seekBarAdsTreshold.setOnSeekBarChangeListener(object :
                SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    if (fromUser) {
                        settingsViewModel.setVideoDetectionTreshold(progress)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                }
            })
            this.storageOptions.setOnCheckedChangeListener { _, checkedId ->
                when (checkedId) {
                    R.id.option_sd_card -> {
                        settingsViewModel.setDownloadsFolderSdCard()
                    }

                    R.id.option_hidden_folder -> {
                        settingsViewModel.setDownloadsFolderHidden()

                    }

                    R.id.option_sd_app_folder -> {
                        settingsViewModel.setDownloadsFolderHiddenSdCard()
                    }
                }
            }
            val type = settingsViewModel.storageType.get()
            when (type) {
                StorageType.SD -> {
                    this@apply.storageOptions.clearCheck()
                    this@apply.storageOptions.check(R.id.option_sd_card)
                }

                StorageType.HIDDEN -> {
                    this@apply.storageOptions.clearCheck()
                    this@apply.storageOptions.check(R.id.option_hidden_folder)
                }

                StorageType.HIDDEN_SD -> {
                    this@apply.storageOptions.clearCheck()
                    this@apply.storageOptions.check(R.id.option_sd_app_folder)
                }

                null -> {
                    this@apply.storageOptions.clearCheck()
                }
            }

            settingsViewModel.videoDetectionTreshold.addOnPropertyChangedCallback(object :
                OnPropertyChangedCallback() {
                override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
                    if (isAdded) {
                        lifecycleScope.launch {
                            this@apply.adsTresholdText.text = getString(
                                R.string.ads_detection_treshold, FileUtil.getFileSizeReadable(
                                    settingsViewModel.videoDetectionTreshold.get().toDouble()
                                )
                            )
                        }
                    }
                }
            })
            settingsViewModel.regularThreadsCount.addOnPropertyChangedCallback(object :
                OnPropertyChangedCallback() {
                override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
                    if (isAdded) {
                        lifecycleScope.launch {
                            this@apply.regularThreadsCountText.text =
                                getString(
                                    R.string.thread_count_for_regular_files,
                                    "${settingsViewModel.regularThreadsCount.get()}"
                                )
                        }
                    }
                }
            })
            settingsViewModel.m3u8ThreadsCount.addOnPropertyChangedCallback(object :
                OnPropertyChangedCallback() {
                override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
                    if (isAdded) {
                        lifecycleScope.launch {
                            this@apply.m3u8ThreadsCountText.text = getString(
                                R.string.thread_count_for_hls_and_mpd,
                                "${settingsViewModel.m3u8ThreadsCount.get() + 1}"
                            )
                        }
                    }
                }
            })
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            parentFragmentManager.popBackStack()
        }

        return dataBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settingsViewModel.start()
        handleUIEvents()
        ensureInitializedTexts()
    }

    override fun onDestroyView() {
        settingsViewModel.stop()
        super.onDestroyView()
    }

    private fun handleUIEvents() {
        settingsViewModel.apply {
            clearCookiesEvent.observe(viewLifecycleOwner, Observer {
                systemUtil.clearCookies(context)
            })

            openVideoFolderEvent.observe(viewLifecycleOwner, Observer {
                intentUtil.openVideoFolder(context, fileUtil.folderDir.path)
            })
        }
    }


    private fun showDownloadWarningDialog(context: Context) {
        AlertDialog.Builder(context)
            .setTitle("Download Warning")
            .setMessage("Some downloads may be corrupted in multi-thread downloading, if you experience some issues, switch back to single thread download!")
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun ensureInitializedTexts() {
        val m3u8ThreadCount = settingsViewModel.m3u8ThreadsCount.get()
        settingsViewModel.m3u8ThreadsCount.set(-1)
        settingsViewModel.m3u8ThreadsCount.set(m3u8ThreadCount)
        val regularThreadCount = settingsViewModel.regularThreadsCount.get()
        settingsViewModel.regularThreadsCount.set(-1)
        settingsViewModel.regularThreadsCount.set(regularThreadCount)
        val videoDetectionTreshold = settingsViewModel.videoDetectionTreshold.get()
        settingsViewModel.videoDetectionTreshold.set(-1)
        settingsViewModel.videoDetectionTreshold.set(videoDetectionTreshold)
    }
}