package com.myAllVideoBrowser.ui.main.home.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ShareCompat
import androidx.databinding.Observable
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myAllVideoBrowser.BuildConfig
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.ui.main.bookmarks.BookmarksFragment
import com.myAllVideoBrowser.ui.main.help.HelpFragment
import com.myAllVideoBrowser.ui.main.history.HistoryFragment
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.ui.main.proxies.ProxiesFragment
import com.myAllVideoBrowser.ui.main.settings.SettingsFragment
import com.myAllVideoBrowser.ui.main.settings.adblock.AdBlockSettingsFragment
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.SharedPrefHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import javax.inject.Inject
import com.myAllVideoBrowser.util.proxy_utils.CustomProxyController
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import com.myAllVideoBrowser.util.proxy_utils.proxy_manager.ProxyManager
import androidx.core.net.toUri


abstract class BaseWebTabFragment : BaseFragment() {
    @Inject
    lateinit var customProxyController: CustomProxyController

    @Inject
    lateinit var okHttpProxyClient: OkHttpProxyClient

    @Inject
    lateinit var mainActivity: MainActivity

    @Inject
    lateinit var sharedPrefHelper: SharedPrefHelper

    private var popupMenu: PopupMenu? = null

    private val darkModeCallback = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            if (!isAdded) {
                return
            }
            lifecycleScope.launch(Dispatchers.Main) {
                popupMenu?.menu?.findItem(R.id.is_dark)?.isChecked =
                    mainActivity.settingsViewModel.isDarkMode.get()
            }
        }
    }

    private val autoDarkModeCallback = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            if (!isAdded) {
                return
            }
            lifecycleScope.launch(Dispatchers.Main) {
                popupMenu?.menu?.findItem(R.id.is_dark)?.isEnabled =
                    !mainActivity.settingsViewModel.isAutoDarkMode.get()
            }
        }
    }

    private val desktopModeCallback = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            if (!isAdded) {
                return
            }
            lifecycleScope.launch(Dispatchers.Main) {
                popupMenu?.menu?.findItem(R.id.desktop_mode)?.isChecked =
                    mainActivity.settingsViewModel.isDesktopMode.get()
            }
        }
    }

    private val proxyOnCallback = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            if (!isAdded) {
                return
            }
            val isProxyOn = mainActivity.proxiesViewModel.isProxyOn.get() == true
            lifecycleScope.launch(Dispatchers.Main) {
                popupMenu?.menu?.findItem(R.id.proxies)?.isChecked = isProxyOn
            }
            sharedPrefHelper.setIsProxyOn(isProxyOn)
        }
    }

    private val adBlockOnCallback = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            if (!isAdded) {
                return
            }
            lifecycleScope.launch(Dispatchers.Main) {
                val isOn = mainActivity.settingsViewModel.isAdBlockOn.get() == true
                popupMenu?.menu?.findItem(R.id.adblock_toggle)?.isChecked = isOn
                mainActivity.settingsViewModel.setIsAdBlockOn(isOn)
            }
        }
    }

    override fun onDestroyView() {
        popupMenu?.dismiss()
        popupMenu = null

        mainActivity.settingsViewModel.isDarkMode.removeOnPropertyChangedCallback(darkModeCallback)
        mainActivity.settingsViewModel.isAutoDarkMode.removeOnPropertyChangedCallback(
            autoDarkModeCallback
        )
        mainActivity.settingsViewModel.isDesktopMode.removeOnPropertyChangedCallback(
            desktopModeCallback
        )
        mainActivity.proxiesViewModel.isProxyOn.removeOnPropertyChangedCallback(proxyOnCallback)
        mainActivity.settingsViewModel.isAdBlockOn.removeOnPropertyChangedCallback(adBlockOnCallback)

        super.onDestroyView()
    }

    abstract fun shareWebLink()

    abstract fun bookmarkCurrentUrl()

    fun buildWebTabMenu(browserMenu: View, isHomeTab: Boolean) {
        if (popupMenu == null) {
            popupMenu = buildPopupMenu(browserMenu)

            val menu = popupMenu!!.menu

            menu.findItem(R.id.bookmark).isVisible = !isHomeTab
            menu.findItem(R.id.share_link).isVisible = !isHomeTab

            menu.findItem(R.id.adblock_toggle).isChecked =
                mainActivity.settingsViewModel.isAdBlockOn.get()
            menu.findItem(R.id.desktop_mode).isChecked =
                mainActivity.settingsViewModel.isDesktopMode.get()
            menu.findItem(R.id.proxies).isChecked =
                mainActivity.proxiesViewModel.isProxyOn.get() == true

            val isDarkModeItem = menu.findItem(R.id.is_dark)
            isDarkModeItem.isChecked = mainActivity.settingsViewModel.isDarkMode.get()
            isDarkModeItem.isEnabled = !mainActivity.settingsViewModel.isAutoDarkMode.get()

            popupMenu!!.setForceShowIcon(true)

            mainActivity.settingsViewModel.isDarkMode.addOnPropertyChangedCallback(darkModeCallback)
            mainActivity.settingsViewModel.isAutoDarkMode.addOnPropertyChangedCallback(
                autoDarkModeCallback
            )
            mainActivity.settingsViewModel.isDesktopMode.addOnPropertyChangedCallback(
                desktopModeCallback
            )
            mainActivity.proxiesViewModel.isProxyOn.addOnPropertyChangedCallback(proxyOnCallback)
            mainActivity.settingsViewModel.isAdBlockOn.addOnPropertyChangedCallback(
                adBlockOnCallback
            )
        }
    }

    fun showPopupMenu() {
        popupMenu?.show()
    }

    open fun setIsDesktop(isDesktop: Boolean) {
        mainActivity.settingsViewModel.setIsDesktopMode(isDesktop)

        val text = if (isDesktop) {
            requireContext().getString(R.string.desktop_mode_on)
        } else {
            requireContext().getString(R.string.desktop_mode_off)
        }

        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
    }

    private fun buildPopupMenu(view: View): PopupMenu {
        val popupMenu = PopupMenu(requireContext(), view)

        popupMenu.gravity = Gravity.END
        popupMenu.menuInflater.inflate(R.menu.menu_browser, popupMenu.menu)

        popupMenu.menu.findItem(R.id.proxies).isEnabled = ProxyManager.isProxySupported()
        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.share_link -> {
                    shareWebLink()
                    true
                }

                R.id.history_screen_menu_item -> {
                    navigateToHistory()
                    true
                }

                R.id.bookmarks_list -> {
                    navigateToBookMarks()
                    true
                }

                R.id.bookmark -> {
                    bookmarkCurrentUrl()
                    true
                }

                R.id.adblock_toggle -> {
                    menuItem.isChecked = !menuItem.isChecked
                    mainActivity.settingsViewModel.setIsAdBlockOn(menuItem.isChecked)
                    false
                }

                R.id.adblock_settings -> {
                    navigateToAdBlockSettings()
                    true
                }

                R.id.desktop_mode -> {
                    menuItem.isChecked = !menuItem.isChecked
                    setIsDesktop(menuItem.isChecked)
                    false
                }

                R.id.settings -> {
                    navigateToSettings()
                    true
                }

                R.id.help -> {
                    navigateToHelp()
                    true
                }

                R.id.about -> {
                    showAboutDialog()
                    true
                }

                R.id.proxies -> {
                    navigateToProxies()
                    true
                }

                R.id.is_dark -> {
                    mainActivity.settingsViewModel.setIsDarkMode(!mainActivity.settingsViewModel.isDarkMode.get())
                    true
                }

                else -> false
            }
        }

        return popupMenu
    }

    private fun showAboutDialog() {
        val currentVersion = BuildConfig.VERSION_NAME
        val developerUrl = "https://github.com/alexch33"
        val latestReleaseUrl = "https://github.com/alexch33/super-video-downloader/releases/latest"
        val btcAddress = "bc1q97xgwurjf2p5at9kzm96fkxymf3rh6gfmfq8fj"
        val currentVersionString = requireContext().getString(R.string.currentVersion)
        val latestVersionString = requireContext().getString(R.string.latestVersion)
        val checkingUpdatesString = requireContext().getString(R.string.checkingForUpdates)
        val checkingFailedString = requireContext().getString(R.string.checkingForUpdatesFailed)
        val developerString = requireContext().getString(R.string.developer)
        val copyBtcString = requireContext().getString(R.string.copyBTC)
        val btcAddressString = requireContext().getString(R.string.btcAddress)
        val btcCopiedString = requireContext().getString(R.string.btcCopied)
        val projectSupportString = requireContext().getString(R.string.supportString)


        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.about)
            .setMessage("$currentVersionString: $currentVersion\n$checkingUpdatesString")
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(developerString) { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, developerUrl.toUri())
                startActivity(intent)
            }
            .setNegativeButton(copyBtcString) { _, _ ->
                val clipboard =
                    requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText(btcAddressString, btcAddress)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(
                    requireContext(),
                    btcCopiedString,
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = okHttpProxyClient.getProxyOkHttpClient()
                val request = Request.Builder()
                    .url(latestReleaseUrl)
                    .build()

                client.newCall(request).execute().use { response ->
                    val redirectedUrl = response.request.url.toString()
                    val latestVersion = redirectedUrl.substringAfterLast("/")

                    withContext(Dispatchers.Main) {
                        dialog.setMessage("$currentVersionString: $currentVersion\n$latestVersionString: $latestVersion\n\n$developerString: $developerUrl\n\n$projectSupportString: $btcAddress")
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    dialog.setMessage("$currentVersionString: $currentVersion\n$checkingFailedString.\n\n$developerString: $developerUrl\n\n$projectSupportString: $btcAddress")
                }
            }
        }
    }

    private fun navigateToHistory() {
        try {
            val currentFragment = this
            val activityFragmentContainer =
                currentFragment.activity?.findViewById<FragmentContainerView>(R.id.fragment_container_view)
            activityFragmentContainer?.let {
                val transaction =
                    currentFragment.requireActivity().supportFragmentManager.beginTransaction()
                transaction.add(it.id, HistoryFragment.newInstance())
                transaction.addToBackStack("history")
                transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                transaction.commit()
            }
        } catch (e: ClassCastException) {
            AppLogger.d("Can't get the fragment manager with this")
        }
    }

    fun shareLink(url: String?) {
        ShareCompat.IntentBuilder(mainActivity).setType("text/plain").setChooserTitle("Share Link")
            .setText(url).startChooser()
    }

    private fun navigateToSettings() {
        try {
            val currentFragment = this
            val activityFragmentContainer =
                currentFragment.activity?.findViewById<FragmentContainerView>(R.id.fragment_container_view)
            activityFragmentContainer?.let {
                val transaction =
                    currentFragment.requireActivity().supportFragmentManager.beginTransaction()
                transaction.add(it.id, SettingsFragment.newInstance())
                transaction.addToBackStack("settings")
                transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                transaction.commit()
            }
        } catch (e: ClassCastException) {
            AppLogger.d("Can't get the fragment manager with this")
        }
    }

    private fun navigateToAdBlockSettings() {
        try {
            val currentFragment = this
            val activityFragmentContainer =
                currentFragment.activity?.findViewById<FragmentContainerView>(R.id.fragment_container_view)
            activityFragmentContainer?.let {
                val transaction =
                    currentFragment.requireActivity().supportFragmentManager.beginTransaction()
                transaction.add(it.id, AdBlockSettingsFragment.newInstance())
                transaction.addToBackStack("adblock_settings")
                transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                transaction.commit()
            }
        } catch (e: ClassCastException) {
            AppLogger.d("Can't get the fragment manager with this")
        }
    }

    private fun navigateToProxies() {
        try {
            val currentFragment = this
            val activityFragmentContainer =
                currentFragment.activity?.findViewById<FragmentContainerView>(R.id.fragment_container_view)
            activityFragmentContainer?.let {
                val transaction =
                    currentFragment.requireActivity().supportFragmentManager.beginTransaction()
                transaction.add(it.id, ProxiesFragment.newInstance())
                transaction.addToBackStack("proxies")
                transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                transaction.commit()
            }
        } catch (e: ClassCastException) {
            AppLogger.d("Can't get the fragment manager with this")
        }
    }

    fun navigateToHelp() {
        try {
            val currentFragment = this
            val activityFragmentContainer =
                currentFragment.activity?.findViewById<FragmentContainerView>(R.id.fragment_container_view)
            activityFragmentContainer?.let {
                val transaction =
                    currentFragment.requireActivity().supportFragmentManager.beginTransaction()
                transaction.add(it.id, HelpFragment.newInstance())
                transaction.addToBackStack("help")
                transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                transaction.commit()
            }
        } catch (e: ClassCastException) {
            AppLogger.d("Can't get the fragment manager with this")
        }
    }

    private fun navigateToBookMarks() {
        try {
            val currentFragment = this
            val activityFragmentContainer =
                currentFragment.activity?.findViewById<FragmentContainerView>(R.id.fragment_container_view)
            activityFragmentContainer?.let {
                val transaction =
                    currentFragment.requireActivity().supportFragmentManager.beginTransaction()
                transaction.add(it.id, BookmarksFragment.newInstance())
                transaction.addToBackStack("bookmarks")
                transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                transaction.commit()
            }
        } catch (e: ClassCastException) {
            AppLogger.d("Can't get the fragment manager with this")
        }
    }
}
