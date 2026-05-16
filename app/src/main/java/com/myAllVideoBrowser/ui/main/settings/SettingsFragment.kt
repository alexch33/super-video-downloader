package com.myAllVideoBrowser.ui.main.settings

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.databinding.Observable
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
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

    private val regularThreadsListener = Slider.OnChangeListener { _, value, fromUser ->
        if (fromUser) {
            val progress = value.toInt()
            settingsViewModel.setRegularThreadsCount(progress)

            if (lastSavedRegularThreadsCount == 1 && progress > 1) {
                context?.let { showDownloadWarningDialog(it) }
            }
            lastSavedRegularThreadsCount = progress
        }
    }

    private val m3u8ThreadsListener = Slider.OnChangeListener { _, value, fromUser ->
        if (fromUser) {
            settingsViewModel.setM3u8ThreadsCount(value.toInt())
        }
    }

    private val adsTresholdListener = Slider.OnChangeListener { _, value, fromUser ->
        if (fromUser) {
            settingsViewModel.setVideoDetectionTreshold(value.toInt())
        }
    }

    private val tresholdCallback = object : Observable.OnPropertyChangedCallback() {
        @SuppressLint("SetTextI18n")
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            if (!isAdded) {
                return
            }
            val currentTreshold = settingsViewModel.videoDetectionTreshold.get()
            val readableSize = FileUtil.getFileSizeReadable(currentTreshold.toDouble())
            val readebleText = getString(R.string.ads_detection_treshold) + " $readableSize"
            dataBinding.adsTresholdText.text = readebleText
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

        dataBinding.seekBarAdsTreshold.setLabelFormatter { value: Float ->
            val readableSize = FileUtil.getFileSizeReadable(value.toDouble())
            return@setLabelFormatter readableSize
        }

        ViewCompat.setOnApplyWindowInsetsListener(dataBinding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)

            dataBinding.bottomBar.setPadding(0, 0, 0, systemBars.bottom)
            insets
        }

        return dataBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSliderListeners()
        setupTextUpdateCallbacks()
        handleUIEvents()

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            parentFragmentManager.popBackStack()
        }

        settingsViewModel.start()

        lastSavedRegularThreadsCount = settingsViewModel.regularThreadsCount.get()
        dataBinding.seekBarRegular.value = lastSavedRegularThreadsCount.coerceIn(1, 16).toFloat()
        dataBinding.seekBarM3u8.value =
            settingsViewModel.m3u8ThreadsCount.get().coerceIn(1, 16).toFloat()
        dataBinding.seekBarAdsTreshold.value =
            settingsViewModel.videoDetectionTreshold.get().toFloat().coerceIn(0f, 52428800f)
    }

    override fun onDestroyView() {
        settingsViewModel.stop()
        settingsViewModel.videoDetectionTreshold.removeOnPropertyChangedCallback(tresholdCallback)

        dataBinding.seekBarRegular.removeOnChangeListener(regularThreadsListener)
        dataBinding.seekBarM3u8.removeOnChangeListener(m3u8ThreadsListener)
        dataBinding.seekBarAdsTreshold.removeOnChangeListener(adsTresholdListener)

        super.onDestroyView()
    }

    private fun setupSliderListeners() {
        dataBinding.seekBarRegular.addOnChangeListener(regularThreadsListener)
        dataBinding.seekBarM3u8.addOnChangeListener(m3u8ThreadsListener)
        dataBinding.seekBarAdsTreshold.addOnChangeListener(adsTresholdListener)
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
        settingsViewModel.videoDetectionTreshold.addOnPropertyChangedCallback(tresholdCallback)
        tresholdCallback.onPropertyChanged(null, 0)
    }

    private fun showDownloadWarningDialog(context: Context) {
        MaterialAlertDialogBuilder(context)
            .setTitle("Download Warning")
            .setMessage("Some downloads may be corrupted in multi-thread downloading, if you experience some issues, switch back to single thread download!")
            .setPositiveButton(R.string.ok) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
}
