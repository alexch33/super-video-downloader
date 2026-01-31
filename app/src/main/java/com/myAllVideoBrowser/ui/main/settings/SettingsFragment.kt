package com.myAllVideoBrowser.ui.main.settings

import android.annotation.SuppressLint
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

    private val tresholdCallback = object : Observable.OnPropertyChangedCallback() {
        @SuppressLint("SetTextI18n")
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            if (!isAdded) {
                return
            }
            val currentTreshold = settingsViewModel.videoDetectionTreshold.get()
            val readableSize = FileUtil.getFileSizeReadable(currentTreshold.toDouble())
            dataBinding.adsTresholdText.text =
                getString(R.string.ads_detection_treshold) + " $readableSize"
        }
    }

    private val storageTypeCallback = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            if (!isAdded) return
            val newCheckId = when (settingsViewModel.storageType.get()) {
                StorageType.SD -> R.id.option_sd_card
                StorageType.HIDDEN -> R.id.option_hidden_folder
                StorageType.HIDDEN_SD -> R.id.option_sd_app_folder
                else -> -1
            }
            if (newCheckId != -1 && dataBinding.storageOptions.checkedRadioButtonId != newCheckId) {
                dataBinding.storageOptions.check(newCheckId)
            }
        }
    }

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

        setupSeekBarListeners()
        setupRadioGroupListener()
        setupTextUpdateCallbacks()
        handleUIEvents()

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            parentFragmentManager.popBackStack()
        }

        settingsViewModel.start()
    }

    override fun onDestroyView() {
        settingsViewModel.stop()
        tresholdCallback.let {
            settingsViewModel.videoDetectionTreshold.removeOnPropertyChangedCallback(
                it
            )
        }
        storageTypeCallback.let { settingsViewModel.storageType.removeOnPropertyChangedCallback(it) }
        super.onDestroyView()
    }

    private fun setupSeekBarListeners() {
        dataBinding.seekBarRegular.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    settingsViewModel.setRegularThreadsCount(progress)

                    if (lastSavedRegularThreadsCount == 1 && progress > 1) {
                        context?.let { showDownloadWarningDialog(it) }
                    }
                    lastSavedRegularThreadsCount = progress
                }
            }

            override fun onStartTrackingTouch(p0: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let { settingsViewModel.setRegularThreadsCount(it.progress) }
            }
        })

        dataBinding.seekBarM3u8.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    settingsViewModel.setM3u8ThreadsCount(progress)
                }
            }

            override fun onStartTrackingTouch(p0: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let { settingsViewModel.setM3u8ThreadsCount(it.progress) }
            }
        })

        dataBinding.seekBarAdsTreshold.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    settingsViewModel.setVideoDetectionTreshold(progress)
                }
            }

            override fun onStartTrackingTouch(p0: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let { settingsViewModel.setVideoDetectionTreshold(it.progress) }
            }
        })
    }

    private fun setupRadioGroupListener() {
        storageTypeCallback.let { settingsViewModel.storageType.addOnPropertyChangedCallback(it) }
        storageTypeCallback.onPropertyChanged(null, 0)
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
        tresholdCallback.let {
            settingsViewModel.videoDetectionTreshold.addOnPropertyChangedCallback(
                it
            )
        }

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