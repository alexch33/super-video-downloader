package com.myAllVideoBrowser.ui.main.settings

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.databinding.Observable
import androidx.lifecycle.ViewModelProvider
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.databinding.FragmentSettingsBinding
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.IntentUtil
import com.myAllVideoBrowser.util.SystemUtil
import javax.inject.Inject

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
        dataBinding = FragmentSettingsBinding.inflate(inflater, container, false)
        dataBinding.viewModel = settingsViewModel
        dataBinding.lifecycleOwner = viewLifecycleOwner

        return dataBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSwitchListeners()
        setupSeekBarListeners()
        setupRadioGroupListener()
        setupTextUpdateCallbacks()
        handleUIEvents()

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            parentFragmentManager.popBackStack()
        }

        // Load initial values
        settingsViewModel.start()
    }

    override fun onDestroyView() {
        settingsViewModel.stop()
        super.onDestroyView()
    }

    private fun setupSwitchListeners() {
        dataBinding.apply {
            isAutoThemeCheckBox.setOnCheckedChangeListener { _, isChecked ->
                settingsViewModel.setIsAutoTheme(isChecked)
            }
            lockOrientationCheckBox.setOnCheckedChangeListener { _, isChecked ->
                settingsViewModel.setIsLockPortrait(isChecked)
            }
            showVideoAlertCheckBox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) settingsViewModel.setShowVideoAlertOn() else settingsViewModel.setShowVideoAlertOff()
            }
            showVideoActionButtonCheckBox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) settingsViewModel.setShowVideoActionButtonOn() else settingsViewModel.setShowVideoActionButtonOff()
            }
            findVideosByUrl.setOnCheckedChangeListener { _, isChecked ->
                settingsViewModel.setIsFindVideoByUrl(isChecked)
            }
            isCheckEveryRequestOnMp4.setOnCheckedChangeListener { _, isChecked ->
                settingsViewModel.setIsCheckEveryRequestOnVideo(isChecked)
            }
            isCheckEveryRequestOnM3u8.setOnCheckedChangeListener { _, isChecked ->
                settingsViewModel.setIsCheckIfEveryUrlOnM3u8(isChecked)
            }
            isCheckEveryRequestOnAudio.setOnCheckedChangeListener { _, isChecked ->
                settingsViewModel.setIsCheckOnAudio(isChecked)
            }
            isForceStreamDetection.setOnCheckedChangeListener { _, isChecked ->
                settingsViewModel.setForceStreamDetection(isChecked)
                if (isChecked) {
                    settingsViewModel.setRegularThreadsCount(1)
                }
            }
            // Add listener for the new switch
            isUseLegacyM3u8Detection.setOnCheckedChangeListener { _, isChecked ->
                settingsViewModel.setUseLegacyM3u8Detection(isChecked)
            }
            isForceStreamDownloading.setOnCheckedChangeListener { _, isChecked ->
                settingsViewModel.setForceStreamDownloading(isChecked)
            }
            isAlwaysRemuxRegularDownloads.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    settingsViewModel.setIsRemuxOnlyLiveRegularDownloads(true)
                }
                settingsViewModel.setIsRemuxOnlyRegularDownloads(isChecked)
            }
            isRemuxOnlyLiveRegularDownloads.setOnCheckedChangeListener { _, isChecked ->
                if (!isChecked) {
                    settingsViewModel.setIsRemuxOnlyRegularDownloads(false)
                }
                settingsViewModel.setIsRemuxOnlyLiveRegularDownloads(isChecked)
            }
            isInterruptIntreceptedResources.setOnCheckedChangeListener { _, isChecked ->
                settingsViewModel.setIsInterruptInterceptedResources(isChecked)
            }
        }
    }

    private fun setupSeekBarListeners() {
        dataBinding.seekBarRegular.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    if (lastSavedRegularThreadsCount == 1 && progress > 1) {
                        context?.let { showDownloadWarningDialog(it) }
                    }
                    lastSavedRegularThreadsCount = progress
                    settingsViewModel.setRegularThreadsCount(progress)
                }
            }

            override fun onStartTrackingTouch(p0: SeekBar?) {}
            override fun onStopTrackingTouch(p0: SeekBar?) {}
        })

        dataBinding.seekBarM3u8.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    settingsViewModel.setM3u8ThreadsCount(progress)
                }
            }

            override fun onStartTrackingTouch(p0: SeekBar?) {}
            override fun onStopTrackingTouch(p0: SeekBar?) {}
        })

        dataBinding.seekBarAdsTreshold.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    settingsViewModel.setVideoDetectionTreshold(progress)
                }
            }

            override fun onStartTrackingTouch(p0: SeekBar?) {}
            override fun onStopTrackingTouch(p0: SeekBar?) {}
        })
    }

    private fun setupRadioGroupListener() {
        val storageTypeCallback = object : Observable.OnPropertyChangedCallback() {
            override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
                val newCheckId = when (settingsViewModel.storageType.get()) {
                    StorageType.SD -> R.id.option_sd_card
                    StorageType.HIDDEN -> R.id.option_hidden_folder
                    StorageType.HIDDEN_SD -> R.id.option_sd_app_folder
                    null -> -1 // Should not happen, but good to handle
                }
                if (newCheckId != -1 && dataBinding.storageOptions.checkedRadioButtonId != newCheckId) {
                    dataBinding.storageOptions.check(newCheckId)
                }
            }
        }

        // Add the callback to listen for changes
        settingsViewModel.storageType.addOnPropertyChangedCallback(storageTypeCallback)

        storageTypeCallback.onPropertyChanged(null, 0)

        dataBinding.storageOptions.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.option_sd_card -> settingsViewModel.setDownloadsFolderSdCard()
                R.id.option_hidden_folder -> settingsViewModel.setDownloadsFolderHidden()
                R.id.option_sd_app_folder -> settingsViewModel.setDownloadsFolderHiddenSdCard()
            }
        }
    }

    private fun handleUIEvents() {
        settingsViewModel.clearCookiesEvent.observe(viewLifecycleOwner) {
            systemUtil.clearCookies(context)
        }
        settingsViewModel.openVideoFolderEvent.observe(viewLifecycleOwner) {
            intentUtil.openVideoFolder(context, fileUtil.folderDir.path)
        }
    }

    private fun setupTextUpdateCallbacks() {
        val tresholdCallback = object : Observable.OnPropertyChangedCallback() {
            override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
                val readableSize = FileUtil.getFileSizeReadable(
                    settingsViewModel.videoDetectionTreshold.get().toDouble()
                )
                dataBinding.adsTresholdText.text =
                    getString(R.string.ads_detection_treshold, readableSize)
            }
        }
        settingsViewModel.videoDetectionTreshold.addOnPropertyChangedCallback(tresholdCallback)

        // Initialize text at start
        tresholdCallback.onPropertyChanged(null, 0)
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
}