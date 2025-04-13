package com.myAllVideoBrowser.ui.main.home.browser

import android.view.Gravity
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ShareCompat
import androidx.databinding.Observable
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.lifecycleScope
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.ui.main.bookmarks.BookmarksFragment
import com.myAllVideoBrowser.ui.main.help.HelpFragment
import com.myAllVideoBrowser.ui.main.history.HistoryFragment
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.ui.main.proxies.ProxiesFragment
import com.myAllVideoBrowser.ui.main.settings.SettingsFragment
import com.myAllVideoBrowser.util.AdsInitializerHelper
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.SharedPrefHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject


abstract class BaseWebTabFragment : BaseFragment() {
    @Inject
    lateinit var mainActivity: MainActivity

    @Inject
    lateinit var sharedPrefHelper: SharedPrefHelper

    private var popupMenu: PopupMenu? = null

    abstract fun shareWebLink()

    abstract fun bookmarkCurrentUrl()

    fun buildWebTabMenu(browserMenu: View, isHomeTab: Boolean) {
        if (popupMenu == null) {
            popupMenu =
                buildPopupMenu(browserMenu)
            val bookmarkMenuItem = popupMenu!!.menu.getItem(2)
            val shareMenuItem = popupMenu!!.menu.getItem(3)
            val desktopMenuItem = popupMenu!!.menu.getItem(4)
            val proxyItem = popupMenu!!.menu.getItem(7)
            val isProxyOn = mainActivity.proxiesViewModel.isProxyOn

            val isAdblockMenuItem = popupMenu!!.menu.getItem(8)

            val isDarkModeItem = popupMenu!!.menu.getItem(9)
            val isDark = mainActivity.settingsViewModel.isDarkMode.get()
            isDarkModeItem.isChecked = isDark
            isDarkModeItem.isEnabled = !mainActivity.settingsViewModel.isAutoDarkMode.get()

            val isAdBlocker = mainActivity.settingsViewModel.isAdBlocker

            desktopMenuItem.isChecked = mainActivity.settingsViewModel.isDesktopMode.get() == true
            proxyItem.isChecked = isProxyOn.get() == true

            isAdblockMenuItem.isChecked = isAdBlocker.get() == true

            popupMenu!!.setForceShowIcon(true)

            mainActivity.settingsViewModel.isDarkMode.addOnPropertyChangedCallback(object :
                Observable.OnPropertyChangedCallback() {
                override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        isDarkModeItem.isChecked = mainActivity.settingsViewModel.isDarkMode.get()
                    }
                }
            })

            mainActivity.settingsViewModel.isAutoDarkMode.addOnPropertyChangedCallback(object :
                Observable.OnPropertyChangedCallback() {
                override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        isDarkModeItem.isEnabled =
                            !mainActivity.settingsViewModel.isAutoDarkMode.get()
                    }
                }
            })

            mainActivity.settingsViewModel.isDesktopMode.addOnPropertyChangedCallback(object :
                Observable.OnPropertyChangedCallback() {
                override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        desktopMenuItem.isChecked =
                            mainActivity.settingsViewModel.isDesktopMode.get() == true
                    }
                }
            })

            isProxyOn.addOnPropertyChangedCallback(object : Observable.OnPropertyChangedCallback() {
                override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
                    proxyItem.isChecked = isProxyOn.get() == true
                    sharedPrefHelper.setIsProxyOn(isProxyOn.get() == true)
                }
            })

            isAdBlocker.addOnPropertyChangedCallback(object :
                Observable.OnPropertyChangedCallback() {
                override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        isAdblockMenuItem.isChecked = isAdBlocker.get() == true
                    }
                }
            })

            shareMenuItem.isVisible = !isHomeTab
            bookmarkMenuItem.isVisible = !isHomeTab
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
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val popupMenu = PopupMenu(requireContext(), view)

        popupMenu.gravity = Gravity.END
        popupMenu.menuInflater.inflate(R.menu.menu_browser, popupMenu.menu)

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

                R.id.proxies -> {
                    navigateToProxies()
                    true
                }

                R.id.ad_blocker -> {
                    val isAdBlockerOn = !menuItem.isChecked
                    menuItem.isChecked = isAdBlockerOn

                    mainActivity.settingsViewModel.setIsAdBlockerOn(isAdBlockerOn)

                    if (isAdBlockerOn) {
                        initializeAdBlocker()
                    }
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

    private fun initializeAdBlocker() {
        AdsInitializerHelper.initializeAdBlocker(
            mainActivity.adBlockHostsRepository,
            mainActivity.sharedPrefHelper,
            lifecycle.coroutineScope
        )
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
