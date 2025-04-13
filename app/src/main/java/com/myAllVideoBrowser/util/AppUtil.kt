package com.myAllVideoBrowser.util

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.View
import android.view.Window
import android.view.inputmethod.InputMethodManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
//import com.allVideoDownloaderXmaster.OpenForTesting
import javax.inject.Inject

//@OpenForTesting
class AppUtil @Inject constructor() {

    fun getSystemDefaultThemeIsDark(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
            return when (uiModeManager.nightMode) {
                UiModeManager.MODE_NIGHT_NO -> false
                else -> true
            }
        } else {
            AppLogger.d("Theme System-wide dark mode not supported on this Android version")
            return context.resources.configuration.uiMode == Configuration.UI_MODE_NIGHT_YES
        }
    }

    fun showSoftKeyboard(view: View) {
        if (view.requestFocus()) {
            val imm =
                view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun hideSoftKeyboard(view: View) {
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, InputMethodManager.HIDE_IMPLICIT_ONLY)
    }

    fun hideSystemUI(window: Window, container: View) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, container).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    fun showSystemUI(window: Window, container: View) {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, container).show(WindowInsetsCompat.Type.systemBars())
    }
}