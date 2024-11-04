package com.myAllVideoBrowser.ui.main.home

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Window
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.databinding.DataBindingUtil
import androidx.databinding.Observable
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.repository.AdBlockHostsRepository
import com.myAllVideoBrowser.databinding.ActivityMainBinding
import com.myAllVideoBrowser.ui.component.adapter.MainAdapter
import com.myAllVideoBrowser.ui.main.base.BaseActivity
import com.myAllVideoBrowser.ui.main.proxies.ProxiesViewModel
import com.myAllVideoBrowser.ui.main.settings.SettingsViewModel
import com.myAllVideoBrowser.util.AdsInitializerHelper
import com.myAllVideoBrowser.util.SharedPrefHelper
import com.myAllVideoBrowser.util.downloaders.youtubedl_downloader.YoutubeDlDownloaderWorker
import com.myAllVideoBrowser.util.fragment.FragmentFactory
import com.myAllVideoBrowser.util.scheduler.BaseSchedulers
import javax.inject.Inject

//@OpenForTesting
class MainActivity : BaseActivity() {

    @Inject
    lateinit var fragmentFactory: FragmentFactory

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    @Inject
    lateinit var baseSchedulers: BaseSchedulers

    @Inject
    lateinit var adBlockHostsRepository: AdBlockHostsRepository

    @Inject
    lateinit var sharedPrefHelper: SharedPrefHelper

    lateinit var mainViewModel: MainViewModel

    lateinit var proxiesViewModel: ProxiesViewModel

    lateinit var settingsViewModel: SettingsViewModel

    private lateinit var dataBinding: ActivityMainBinding

    private lateinit var mainAdapter: MainAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()

        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)

        dataBinding = DataBindingUtil.setContentView(this, R.layout.activity_main)

        AdsInitializerHelper.initializeAdBlocker(
            adBlockHostsRepository,
            sharedPrefHelper,
            lifecycleScope
        )

        mainViewModel = ViewModelProvider(this, viewModelFactory)[MainViewModel::class.java]
        proxiesViewModel = ViewModelProvider(this, viewModelFactory)[ProxiesViewModel::class.java]
        settingsViewModel = ViewModelProvider(this, viewModelFactory)[SettingsViewModel::class.java]

        mainAdapter = MainAdapter(supportFragmentManager, lifecycle, fragmentFactory)

        dataBinding.viewPager.isUserInputEnabled = false
        dataBinding.viewPager.adapter = mainAdapter
        dataBinding.viewPager.registerOnPageChangeCallback(onPageChangeListener)
        dataBinding.bottomBar.setOnItemSelectedListener { menuItem ->
            val isBrowser = mainViewModel.currentItem.get() == 0
            var goingToBrowser = false
            when (menuItem.itemId) {
                R.id.tab_browser -> {
                    mainViewModel.currentItem.set(0)
                    goingToBrowser = true
                }

                R.id.tab_progress -> mainViewModel.currentItem.set(1)
                R.id.tab_video -> mainViewModel.currentItem.set(2)
                else -> mainViewModel.currentItem.set(3)
            }

            if (isBrowser && goingToBrowser && mainViewModel.isBrowserCurrent.get()) {
                mainViewModel.openNavDrawerEvent.call()
            }
            return@setOnItemSelectedListener true
        }
        dataBinding.viewModel = mainViewModel

        grantPermissions()
        proxiesViewModel.start()
        settingsViewModel.start()
        mainViewModel.start()

        if (intent.action == Intent.ACTION_VIEW) {
            val videoUrl = intent.dataString
            if (videoUrl != null) {
                mainViewModel.openedUrl.set(videoUrl)
            }
        }

        if (intent.action == Intent.ACTION_SEND) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (sharedText != null) {
                mainViewModel.openedText.set(sharedText)
            }
        }

        handleScreenOrientationSettingChange()
        handleScreenOrientationSettingsInit()

        onNewIntent(intent)
    }

    @SuppressLint("MissingSuperCall")
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra(
                YoutubeDlDownloaderWorker.IS_FINISHED_DOWNLOAD_ACTION_KEY,
                false
            ) == true
        ) {
            if (intent.getBooleanExtra(
                    YoutubeDlDownloaderWorker.IS_FINISHED_DOWNLOAD_ACTION_ERROR_KEY,
                    false
                )
            ) {
                dataBinding.viewPager.currentItem = 1
            } else {
                dataBinding.viewPager.currentItem = 2
            }

            if (intent.hasExtra(YoutubeDlDownloaderWorker.DOWNLOAD_FILENAME_KEY)) {
                val downloadFileName =
                    intent.getStringExtra(YoutubeDlDownloaderWorker.DOWNLOAD_FILENAME_KEY)
                        .toString()

                Handler(Looper.getMainLooper()).postDelayed({
                    mainViewModel.openDownloadedVideoEvent.value = downloadFileName
                }, 1000)
            }
        } else {
            if (intent?.hasExtra(YoutubeDlDownloaderWorker.IS_FINISHED_DOWNLOAD_ACTION_KEY) == true) {
                dataBinding.viewPager.currentItem = 1
            } else {
                dataBinding.viewPager.currentItem = 0
            }
        }
    }

    private fun grantPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.POST_NOTIFICATIONS
                    ),
                    0
                )
            }
        }
    }

    private val onPageChangeListener = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageScrollStateChanged(p0: Int) {
        }

        override fun onPageScrolled(p0: Int, p1: Float, p2: Int) {
        }

        override fun onPageSelected(postion: Int) {
            if (postion == 0) {
                // Если без этого, дровер отркрываетс когда не надо
                Handler(Looper.getMainLooper()).postDelayed({
                    mainViewModel.isBrowserCurrent.set(true)
                }, 1000)
            } else {
                mainViewModel.isBrowserCurrent.set(false)
            }

            val childrenCount = dataBinding.fragmentContainerView.childCount
            if (childrenCount > 0) {
                supportFragmentManager.popBackStack()
            }
            if (postion > 0) {
                dataBinding.viewPager.isUserInputEnabled = true
            } else {
                dataBinding.viewPager.isUserInputEnabled = false
            }

            mainViewModel.currentItem.set(postion)
        }
    }

    override fun onDestroy() {
        mainViewModel.stop()
        super.onDestroy()
    }

    private fun handleScreenOrientationSettingsInit() {
        // INIT
        requestedOrientation = if (settingsViewModel.isLockPortrait.get()) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun handleScreenOrientationSettingChange() {
        // CHANGES HANDLING
        settingsViewModel.isLockPortrait.addOnPropertyChangedCallback(object :
            Observable.OnPropertyChangedCallback() {
            override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
                val isLock = settingsViewModel.isLockPortrait.get()

                requestedOrientation = if (isLock) {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        })
    }
}
