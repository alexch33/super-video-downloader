package com.myAllVideoBrowser.ui.main.home.browser

import android.view.Gravity
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ShareCompat
import androidx.databinding.Observable
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.lifecycleScope
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.ui.main.bookmarks.BookmarksFragment
import com.myAllVideoBrowser.ui.main.help.HelpFragment
import com.myAllVideoBrowser.ui.main.history.HistoryFragment
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.ui.main.proxies.ProxiesFragment
import com.myAllVideoBrowser.ui.main.settings.SettingsFragment
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.SharedPrefHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.core.view.get


abstract class BaseWebTabFragment : BaseFragment() {
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

    override fun onDestroyView() {
        popupMenu?.dismiss()
        popupMenu = null

        mainActivity.settingsViewModel.isDarkMode.removeOnPropertyChangedCallback(darkModeCallback)
        mainActivity.settingsViewModel.isAutoDarkMode.removeOnPropertyChangedCallback(autoDarkModeCallback)
        mainActivity.settingsViewModel.isDesktopMode.removeOnPropertyChangedCallback(desktopModeCallback)
        mainActivity.proxiesViewModel.isProxyOn.removeOnPropertyChangedCallback(proxyOnCallback)

        super.onDestroyView()
    }

    abstract fun shareWebLink()

    abstract fun bookmarkCurrentUrl()

    fun buildWebTabMenu(browserMenu: View, isHomeTab: Boolean) {
        if (popupMenu == null) {
            popupMenu =
                buildPopupMenu(browserMenu)
            val bookmarkMenuItem = popupMenu!!.menu[2]
            val shareMenuItem = popupMenu!!.menu[3]
            val desktopMenuItem = popupMenu!!.menu[4]
            val proxyItem = popupMenu!!.menu[7]
            val isProxyOn = mainActivity.proxiesViewModel.isProxyOn
            val isDarkModeItem = popupMenu!!.menu[8]
            val isDark = mainActivity.settingsViewModel.isDarkMode.get()
            isDarkModeItem.isChecked = isDark
            isDarkModeItem.isEnabled = !mainActivity.settingsViewModel.isAutoDarkMode.get()

            desktopMenuItem.isChecked = mainActivity.settingsViewModel.isDesktopMode.get() == true
            proxyItem.isChecked = isProxyOn.get() == true

            popupMenu!!.setForceShowIcon(true)

            mainActivity.settingsViewModel.isDarkMode.addOnPropertyChangedCallback(darkModeCallback)

            mainActivity.settingsViewModel.isAutoDarkMode.addOnPropertyChangedCallback(autoDarkModeCallback)

            mainActivity.settingsViewModel.isDesktopMode.addOnPropertyChangedCallback(desktopModeCallback)

            isProxyOn.addOnPropertyChangedCallback(proxyOnCallback)

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

                R.id.is_dark -> {
                    mainActivity.settingsViewModel.setIsDarkMode(!mainActivity.settingsViewModel.isDarkMode.get())
                    true
                }

                else -> false
            }
        }

        return popupMenu
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
