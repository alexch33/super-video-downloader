package com.myAllVideoBrowser.geckoview_example

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.IntentSender
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.text.InputType
import android.util.Log
import android.util.LruCache
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.UiThread
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import com.myAllVideoBrowser.BuildConfig
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.databinding.GeckoviewFragmentBinding
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.ui.main.home.browser.BrowserViewModel
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTab
import com.myAllVideoBrowser.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.BasicSelectionActionDelegate
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.Image
import org.mozilla.geckoview.MediaSession
import org.mozilla.geckoview.OrientationController
import org.mozilla.geckoview.ProfilerController
import org.mozilla.geckoview.SlowScriptResponse
import org.mozilla.geckoview.TranslationsController
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import org.mozilla.geckoview.WebNotification
import org.mozilla.geckoview.WebNotificationDelegate
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.lang.ref.WeakReference
import java.util.ArrayList
import java.util.Collections
import java.util.HashMap
import java.util.HashSet
import java.util.LinkedList
import java.util.Locale
import java.util.regex.Pattern
import javax.inject.Inject

interface WebExtensionDelegate {
    fun toggleBrowserActionPopup(force: Boolean): GeckoSession? = null
    fun onActionButton(button: ActionButton?) {}
    fun getSession(session: GeckoSession?): TabSession? = null
    fun getCurrentSession(): TabSession? = null
    fun closeTab(session: TabSession?) {}
    fun updateTab(session: TabSession?, details: WebExtension.UpdateTabDetails?) {}
    fun openNewTab(details: WebExtension.CreateTabDetails?): TabSession? = null
}

interface WebRequestInterceptor {
    fun onRequestIntercepted(url: WebRequest)
}

class WebExtensionManager(
    private val mRuntime: GeckoRuntime,
    private val mTabManager: TabSessionManager
) : WebExtension.MessageDelegate,
    WebExtension.ActionDelegate,
    WebExtension.SessionTabDelegate,
    WebExtension.TabDelegate,
    WebExtensionController.PromptDelegate,
    WebExtensionController.DebuggerDelegate,
    TabSessionManager.TabObserver {

    private var webRequestListener: WebRequestInterceptor? = null

    fun setWebRequestListener(listener: WebRequestInterceptor?) {
        this.webRequestListener = listener
    }

    var extension: WebExtension? = null
    private val mBitmapCache = LruCache<Image, Bitmap>(5)
    private var mDefaultAction: WebExtension.Action? = null
    private var mExtensionDelegate: WeakReference<WebExtensionDelegate>? = null

    @SuppressLint("MissingPermission")
    @UiThread
    override fun onInstallPromptRequest(
        extension: WebExtension,
        permissions: Array<out String>,
        origins: Array<out String>,
        technicalAndInteractionData: Array<out String>
    ): GeckoResult<WebExtension.PermissionPromptResponse>? {
        return GeckoResult.fromValue(
            WebExtension.PermissionPromptResponse(
                true, // isPermissionsGranted
                true,
                false,
            )
        )
    }

    @SuppressLint("MissingPermission")
    override fun onUpdatePrompt(
        currentlyInstalled: WebExtension,
        permissions: Array<out String>,
        origins: Array<out String>,
        technicalAndInteractionData: Array<out String>
    ): GeckoResult<AllowOrDeny>? {
        return GeckoResult.allow()
    }

    @SuppressLint("MissingPermission")
    override fun onOptionalPrompt(
        extension: WebExtension,
        permissions: Array<out String>,
        origins: Array<out String>,
        technicalAndInteractionData: Array<out String>
    ): GeckoResult<AllowOrDeny>? {
        return GeckoResult.allow()
    }

    override fun onExtensionListUpdated() {
        refreshExtensionList()
    }

    private fun onAction(
        extension: WebExtension,
        session: GeckoSession?,
        action: WebExtension.Action,
    ) {
        val delegate = mExtensionDelegate?.get() ?: return

        val resolved: WebExtension.Action

        if (session == null) {
            mDefaultAction = action
            resolved = actionFor(delegate.getCurrentSession())!!
        } else {
            if (delegate.getSession(session) == null) {
                return
            }
            delegate.getSession(session)?.action = action
            val currentSession = delegate.getCurrentSession()
            if (currentSession != null) {
                if (currentSession != session) {
                    return
                }
            }
            resolved = action.withDefault(mDefaultAction!!)
        }

        updateAction(resolved)
    }

    override fun onNewTab(
        source: WebExtension,
        details: WebExtension.CreateTabDetails,
    ): GeckoResult<GeckoSession>? {
        val delegate = mExtensionDelegate?.get() ?: return GeckoResult.fromValue(null)
        return GeckoResult.fromValue(delegate.openNewTab(details))
    }

    override fun onCloseTab(
        extension: WebExtension?,
        session: GeckoSession,
    ): GeckoResult<AllowOrDeny?> {
        val delegate = mExtensionDelegate?.get() ?: return GeckoResult.deny()
        val tabSession = mTabManager.getSession(session)
        if (tabSession != null) {
            delegate.closeTab(tabSession)
        }
        return GeckoResult.allow()
    }

    override fun onUpdateTab(
        extension: WebExtension,
        session: GeckoSession,
        updateDetails: WebExtension.UpdateTabDetails,
    ): GeckoResult<AllowOrDeny?> {
        val delegate = mExtensionDelegate?.get() ?: return GeckoResult.deny()
        val tabSession = mTabManager.getSession(session)
        if (tabSession != null) {
            delegate.updateTab(tabSession, updateDetails)
        }
        return GeckoResult.allow()
    }

    override fun onPageAction(
        extension: WebExtension,
        session: GeckoSession?,
        action: WebExtension.Action,
    ) {
        onAction(extension, session, action)
    }

    override fun onBrowserAction(
        extension: WebExtension,
        session: GeckoSession?,
        action: WebExtension.Action
    ) {
        onAction(extension, session, action)
    }

    private fun togglePopup(force: Boolean): GeckoResult<GeckoSession>? {
        val extensionDelegate = mExtensionDelegate?.get() ?: return null
        val session = extensionDelegate.toggleBrowserActionPopup(false)
        return if (session == null) null else GeckoResult.fromValue(session)
    }

    override fun onTogglePopup(
        extension: WebExtension,
        action: WebExtension.Action,
    ): GeckoResult<GeckoSession>? {
        return togglePopup(false)
    }

    override fun onOpenPopup(
        extension: WebExtension,
        action: WebExtension.Action,
    ): GeckoResult<GeckoSession>? {
        return togglePopup(true)
    }

    private fun actionFor(session: TabSession?): WebExtension.Action? {
        return if (session?.action == null) {
            mDefaultAction
        } else {
            session.action!!.withDefault(mDefaultAction!!)
        }
    }

    private fun updateAction(resolved: WebExtension.Action?) {
        val extensionDelegate = mExtensionDelegate?.get() ?: return

        if (resolved?.enabled == null || resolved.enabled != true) {
            extensionDelegate.onActionButton(null)
            return
        }

        resolved.icon?.let { icon ->
            mBitmapCache.get(icon)?.let { bitmap ->
                extensionDelegate.onActionButton(
                    ActionButton(
                        bitmap,
                        resolved.badgeText,
                        resolved.badgeTextColor ?: 0,
                        resolved.badgeBackgroundColor ?: 0
                    )
                )
            } ?: run {
                icon.getBitmap(100).accept { bitmap ->
                    mBitmapCache.put(icon, bitmap)
                    extensionDelegate.onActionButton(
                        ActionButton(
                            bitmap,
                            resolved.badgeText,
                            resolved.badgeTextColor ?: 0,
                            resolved.badgeBackgroundColor ?: 0
                        )
                    )
                }
            }
        } ?: run {
            extensionDelegate.onActionButton(null)
        }
    }

    fun onClicked(session: TabSession?) {
        val action = actionFor(session)
        action?.click()
    }

    fun setExtensionDelegate(delegate: WebExtensionDelegate) {
        mExtensionDelegate = WeakReference(delegate)
    }

    override fun onCurrentSession(session: TabSession?) {
        if (mDefaultAction == null) {
            return
        }

        if (session?.action != null) {
            updateAction(session.action!!.withDefault(mDefaultAction!!))
        } else {
            updateAction(mDefaultAction)
        }
    }

    fun unregisterExtension(): GeckoResult<Void>? {
        if (extension == null) {
            return GeckoResult.fromValue(null)
        }

        mTabManager.unregisterWebExtension()

        return mRuntime.webExtensionController
            .uninstall(extension!!)
            .accept {
                extension = null
                mDefaultAction = null
                updateAction(null)
            }
    }

    fun updateExtension(): GeckoResult<WebExtension>? {
        if (extension == null) {
            return GeckoResult.fromValue(null)
        }

        return mRuntime.webExtensionController
            .update(extension!!)
            .map { newExtension ->
                registerExtension(newExtension!!)
                newExtension
            }
    }

    fun registerExtension(extension: WebExtension) {
        extension.setActionDelegate(this)
        extension.setTabDelegate(this)
        extension.setMessageDelegate(this, "browser")
        mTabManager.setWebExtensionDelegates(extension, this, this, this)
        this.extension = extension
    }

    private fun refreshExtensionList() {
        mRuntime.webExtensionController
            .list()
            .accept { extensions ->
                if (extensions != null) {
                    for (extension in extensions) {
                        registerExtension(extension)
                    }
                }
            }
    }

    override fun onConnect(
        port: WebExtension.Port
    ) {
        AppLogger.d("Connected to extension: ${extension?.id}     $port")
    }

    override fun onMessage(
        messageId: String, // Corresponds to the namespace, e.g., "browser"
        message: Any, // The data sent from JS, usually a JSONObject
        sender: WebExtension.MessageSender
    ): GeckoResult<Any>? {
        AppLogger.d("Message from Extension: ID=$messageId, Data=$message")

        if (message is JSONObject) {
            val messageType = message.optString("type")

            if (messageType == "REQUEST_INTERCEPTED") {
                val payload = message.optJSONObject("payload") ?: return null
                val url = payload.optString("url")

                // The requestHeaders are a JSONArray of JSONObjects
                val headersArray = payload.optJSONArray("requestHeaders")

                if (url.isNotEmpty() && headersArray != null) {
                    val requestBuilder = WebRequest.Builder(url)

                    for (i in 0 until headersArray.length()) {
                        val headerObject = headersArray.optJSONObject(i)
                        val headerName = headerObject?.optString("name")
                        val headerValue = headerObject?.optString("value")
                        if (headerName != null && headerValue != null) {
                            requestBuilder.addHeader(headerName, headerValue)
                        }
                    }

                    webRequestListener?.onRequestIntercepted(requestBuilder.build())
                }
            }
        }
        return null
    }

    init {
        refreshExtensionList()
    }
}

open class GeckoViewFragment : BaseFragment(),
    WebExtensionDelegate,
    SharedPreferences.OnSharedPreferenceChangeListener, ToolbarLayout.TabListener,
    WebRequestInterceptor {

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    @Inject
    lateinit var mainActivity: MainActivity;

    internal lateinit var binding: GeckoviewFragmentBinding

    companion object {
        private const val ARG_INITIAL_URL = "initial_url"
        private const val LOGTAG = "GeckoViewFragment"
        private const val FULL_ACCESSIBILITY_TREE_EXTRA = "full_accessibility_tree"
        private const val SEARCH_URI_BASE = "https://www.google.com/search?q="
        private const val ACTION_SHUTDOWN = "org.mozilla.geckoview_example.SHUTDOWN"
        private const val CHANNEL_ID = "GeckoViewExample"
        private const val REQUEST_FILE_PICKER = 1
        private const val REQUEST_PERMISSIONS = 2
        private const val REQUEST_WRITE_EXTERNAL_STORAGE = 3

        private var sGeckoRuntime: GeckoRuntime? = null
        private var sExtensionManager: WebExtensionManager? = null

        fun newInstance(initialUrl: String?): GeckoViewFragment {
            val fragment = GeckoViewFragment()
            if (initialUrl != null) {
                val args = Bundle().apply {
                    putString(ARG_INITIAL_URL, initialUrl)
                }
                fragment.arguments = args
            }

            return fragment
        }

    }

    // This property will hold the URL from the arguments
    private var initialUrl: String? = null

    private lateinit var mTabSessionManager: TabSessionManager
    private lateinit var mGeckoView: GeckoView
    private var mFullAccessibilityTree = false
    private var mUsePrivateBrowsing = false
    private var mCollapsed = false
    private var mKillProcessOnDestroy = false
    private var mDesktopMode = false

    private var mPopupSession: TabSession? = null
    private var mPopupView: View? = null
    private var mTrackingProtectionPermission: ContentPermission? =
        null

    private var mShowNotificationsRejected = false
    private val mAcceptedPersistentStorage = ArrayList<String>()

    private lateinit var mToolbarView: ToolbarLayout
    private var mCurrentUri: String? = null
    private var mCanGoBack = false
    private var mCanGoForward = false
    private var mFullScreen = false
    private var mExpectedTranslate = false
    private var mTranslateRestore = false

    private var mDetectedLanguage: String? = null

    private val mNotificationIDMap = HashMap<String, Int>()
    private var mLastID = 100

    private lateinit var mProgressView: ProgressBar

    private var mPendingDownloads = LinkedList<WebResponse>()

    private var mNextActivityResultCode = 10
    private val mPendingActivityResult = HashMap<Int, GeckoResult<Intent>>()

    private val mCommitListener = object : LocationView.CommitListener {
        override fun onCommit(text: String) {
            when {
                text.startsWith("data:") -> mTabSessionManager.currentSession?.loadUri(text)
                (text.contains(".") || text.contains(":")) && !text.contains(" ") ->
                    mTabSessionManager.currentSession?.loadUri(text)

                else -> mTabSessionManager.currentSession?.loadUri(SEARCH_URI_BASE + text)
            }
            mGeckoView.requestFocus()
        }
    }

    private val SETTINGS = ArrayList<Setting<*>>()

    private abstract inner class Setting<T>(
        private val mKey: Int,
        private val mDefaultKey: Int?,
        private val mReloadCurrentSession: Boolean,
    ) {
        protected var mValue: T? = null

        init {
            SETTINGS.add(this)
        }

        fun onPrefChange(pref: SharedPreferences) {
            val defaultValue = getDefaultValue(mDefaultKey!!, resources)
            val key = resources.getString(this.mKey)
            val value = getValue(key, defaultValue, pref)
            if (value()?.equals(value) != false) {
                setValue(value)
            }
        }

        private fun setValue(newValue: T) {
            mValue = newValue
            for (session in mTabSessionManager.sessions) {
                setValue(session.settings, value())
            }
            sGeckoRuntime?.let { runtime ->
                setValue(runtime.settings, value())
                sExtensionManager?.let {
                    setValue(runtime.webExtensionController, value())
                }
            }

            val current = mTabSessionManager.currentSession
            if (mReloadCurrentSession && current != null) {
                current.reload()
            }
        }

        fun value(): T = mValue ?: getDefaultValue(mDefaultKey!!, resources)

        protected abstract fun getDefaultValue(key: Int, res: Resources): T
        protected abstract fun getValue(
            key: String,
            defaultValue: T,
            preferences: SharedPreferences
        ): T

        protected open fun setValue(settings: GeckoSessionSettings, value: T) {}
        protected open fun setValue(settings: GeckoRuntimeSettings, value: T) {}
        protected open fun setValue(controller: WebExtensionController, value: T) {}
    }

    private open inner class StringSetting(
        key: Int,
        defaultValueKey: Int,
        reloadCurrentSession: Boolean = false
    ) :
        Setting<String>(key, defaultValueKey, reloadCurrentSession) {
        constructor(key: Int, defaultValueKey: Int) : this(key, defaultValueKey, false)

        override fun getDefaultValue(key: Int, res: Resources): String = res.getString(key)
        override fun getValue(
            key: String,
            defaultValue: String,
            preferences: SharedPreferences
        ): String =
            preferences.getString(key, defaultValue) ?: defaultValue
    }

    private open inner class BooleanSetting(
        key: Int,
        defaultValueKey: Int,
        reloadCurrentSession: Boolean = false
    ) :
        Setting<Boolean>(key, defaultValueKey, reloadCurrentSession) {
        constructor(key: Int, defaultValueKey: Int) : this(key, defaultValueKey, false)

        override fun getDefaultValue(key: Int, res: Resources): Boolean = res.getBoolean(key)
        override fun getValue(
            key: String,
            defaultValue: Boolean,
            preferences: SharedPreferences
        ): Boolean =
            preferences.getBoolean(key, defaultValue)
    }

    private open inner class IntSetting(
        key: Int,
        defaultValueKey: Int,
        reloadCurrentSession: Boolean = false
    ) :
        Setting<Int>(key, defaultValueKey, reloadCurrentSession) {
        constructor(key: Int, defaultValueKey: Int) : this(key, defaultValueKey, false)

        override fun getDefaultValue(key: Int, res: Resources): Int = res.getInteger(key)
        override fun getValue(key: String, defaultValue: Int, preferences: SharedPreferences): Int =
            preferences.getString(key, defaultValue.toString())?.toInt() ?: defaultValue
    }

    private val mDisplayMode =
        object : IntSetting(R.string.key_display_mode, R.integer.display_mode_default) {
            override fun setValue(settings: GeckoSessionSettings, value: Int) {
                settings.setDisplayMode(value)
            }
        }

    private val mPreferredColorScheme = object : IntSetting(
        R.string.key_preferred_color_scheme,
        R.integer.preferred_color_scheme_default,
        true
    ) {
        override fun setValue(settings: GeckoRuntimeSettings, value: Int) {
            settings.setPreferredColorScheme(value)
        }
    }

    private val mUserAgent = object : StringSetting(
        R.string.key_user_agent_override,
        R.string.user_agent_override_default,
        true
    ) {
        override fun setValue(settings: GeckoSessionSettings, value: String) {
            settings.setUserAgentOverride(if (value.isEmpty()) null else value)
        }
    }

    private val mRemoteDebugging =
        object : BooleanSetting(R.string.key_remote_debugging, R.bool.remote_debugging_default) {
            override fun setValue(settings: GeckoRuntimeSettings, value: Boolean) {
                settings.setRemoteDebuggingEnabled(value)
            }
        }

    private val mJavascriptEnabled = object : BooleanSetting(
        R.string.key_javascript_enabled,
        R.bool.javascript_enabled_default,
        true
    ) {
        override fun setValue(settings: GeckoRuntimeSettings, value: Boolean) {
            settings.setJavaScriptEnabled(value)
        }
    }

    private val mGlobalPrivacyControlEnabled = object : BooleanSetting(
        R.string.key_global_privacy_control_enabled,
        R.bool.global_privacy_control_enabled_default,
        true
    ) {
        override fun setValue(settings: GeckoRuntimeSettings, value: Boolean) {
            settings.setGlobalPrivacyControl(value)
        }
    }

    private val mEtbPrivateModeEnabled = object : BooleanSetting(
        R.string.key_etb_private_mode_enabled,
        R.bool.etb_private_mode_enabled_default,
        true
    ) {
        override fun setValue(settings: GeckoRuntimeSettings, value: Boolean) {
            settings.contentBlocking.setEmailTrackerBlockingPrivateBrowsing(value)
        }
    }

    private val mExtensionsProcessEnabled = object : BooleanSetting(
        R.string.key_extensions_process_enabled,
        R.bool.extensions_process_enabled_default,
        true
    ) {
        override fun setValue(settings: GeckoRuntimeSettings, value: Boolean) {
            settings.setExtensionsProcessEnabled(value)
        }
    }

    private val mTrackingProtection = object : BooleanSetting(
        R.string.key_tracking_protection,
        R.bool.tracking_protection_default
    ) {
        override fun setValue(settings: GeckoRuntimeSettings, value: Boolean) {
            mTabSessionManager.setUseTrackingProtection(value)
            settings.contentBlocking.setStrictSocialTrackingProtection(value)
        }
    }

    private val mEnhancedTrackingProtection = object : StringSetting(
        R.string.key_enhanced_tracking_protection,
        R.string.enhanced_tracking_protection_default,
    ) {
        override fun setValue(settings: GeckoRuntimeSettings, value: String) {
            val etpLevel = when (value) {
                "disabled" -> ContentBlocking.EtpLevel.NONE
                "standard" -> ContentBlocking.EtpLevel.DEFAULT
                "strict" -> ContentBlocking.EtpLevel.STRICT
                else -> throw RuntimeException("Invalid ETP level: $value")
            }
            settings.contentBlocking.setEnhancedTrackingProtectionLevel(etpLevel)
        }
    }

    private val mCookieBannerHandling = object : StringSetting(
        R.string.key_cookie_banner_handling,
        R.string.cookie_banner_handling_default,
    ) {
        override fun setValue(settings: GeckoRuntimeSettings, value: String) {
            val cbMode = when (value) {
                "disabled" -> ContentBlocking.CookieBannerMode.COOKIE_BANNER_MODE_DISABLED
                "reject_all" -> ContentBlocking.CookieBannerMode.COOKIE_BANNER_MODE_REJECT
                "reject_accept_all" -> ContentBlocking.CookieBannerMode.COOKIE_BANNER_MODE_REJECT_OR_ACCEPT
                else -> throw RuntimeException("Invalid Cookie Banner Handling mode: $value")
            }
            settings.contentBlocking.setCookieBannerMode(cbMode)
        }
    }

    private val mCookieBannerHandlingPrivateMode = object : StringSetting(
        R.string.key_cookie_banner_handling_pb,
        R.string.cookie_banner_handling_pb_default,
    ) {
        override fun setValue(settings: GeckoRuntimeSettings, value: String) {
            val cbPrivateMode = when (value) {
                "disabled" -> ContentBlocking.CookieBannerMode.COOKIE_BANNER_MODE_DISABLED
                "reject_all" -> ContentBlocking.CookieBannerMode.COOKIE_BANNER_MODE_REJECT
                "reject_accept_all" -> ContentBlocking.CookieBannerMode.COOKIE_BANNER_MODE_REJECT_OR_ACCEPT
                else -> throw RuntimeException("Invalid Cookie Banner Handling private mode: $value")
            }
            settings.contentBlocking.setCookieBannerModePrivateBrowsing(cbPrivateMode)
        }
    }

    private val mDynamicFirstPartyIsolation =
        object : BooleanSetting(R.string.key_dfpi, R.bool.dfpi_default) {
            override fun setValue(settings: GeckoRuntimeSettings, value: Boolean) {
                if (value) {
                    settings.contentBlocking.setCookieBehavior(
                        ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS
                    )
                }
            }
        }

    private val mAllowAutoplay =
        BooleanSetting(R.string.key_autoplay, R.bool.autoplay_default, true)

    private val mAllowExtensionsInPrivateBrowsing = object : BooleanSetting(
        R.string.key_allow_extensions_in_private_browsing,
        R.bool.allow_extensions_in_private_browsing_default,
    ) {
        override fun setValue(controller: WebExtensionController, value: Boolean) {
            sExtensionManager?.extension?.let { extension ->
                controller.setAllowedInPrivateBrowsing(extension, value)
            }
        }
    }

    private fun onPreferencesChange(preferences: SharedPreferences) {
        for (setting in SETTINGS) {
            setting.onPrefChange(preferences)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)

        // Retrieve the initialUrl from the arguments
        arguments?.let {
            initialUrl = it.getString(ARG_INITIAL_URL)
        }


        createNotificationChannel()

        val notification = activity?.intent?.getParcelableExtra<WebNotification>("onClick")
        notification?.let {
            activity?.intent?.removeExtra("onClick")
            it.click()
        }

        Log.i(LOGTAG, "zerdatime ${SystemClock.elapsedRealtime()} - application start")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        binding = GeckoviewFragmentBinding.inflate(inflater, container, false)

        binding.lifecycleOwner = viewLifecycleOwner

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val browserViewModel =
            ViewModelProvider(mainActivity, viewModelFactory)[BrowserViewModel::class.java]
        AppLogger.d("browserViewModel tabs in geckoview ${browserViewModel.tabs.get()}")

        mGeckoView = view.findViewById(R.id.gecko_view)
        mGeckoView.setActivityContextDelegate(ExampleActivityDelegate())
        mTabSessionManager = TabSessionManager()
        mTabSessionManager.setTabsObserver(object : TabSessionManager.TabsObserver {
            override fun onOpenTab(session: TabSession?) {
                AppLogger.d("OPEN TAB >>> $session")
//                val tabs = browserViewModel.tabs.get()?.toMutableList()
//                tabs?.add(
//                    WebTab(
//                        url = session?.uri.toString(),
//                        title = session?.title.toString(),
//                        iconBytes = null,
//                        headers = emptyMap(),
//                        webview = null,
//                        resultMsg = null,
//                        id = session.toString()
//                    )
//                )

//                browserViewModel.tabs.set(tabs)
            }

            override fun onCloseTab(session: TabSession?) {
//                val tabs = browserViewModel.tabs.get()?.toMutableList()
//                val found = tabs?.find { tab -> tab.id == session.toString() }
//                if (found != null) {
//                    tabs.remove(found)
//                }
//                browserViewModel.tabs.set(tabs)
                AppLogger.d("CLOSE TAB >>> $session")
            }

            override fun onSelectTab(session: TabSession?) {
//                val found =
//                    browserViewModel.tabs.get()?.find { tab -> tab.id == session.toString() }
//                val foundIndex = browserViewModel.tabs.get()?.indexOf(found)
//                if (foundIndex != null && foundIndex > 0) {
//                    browserViewModel.currentTab.set(foundIndex)
//                }
                AppLogger.d("SELECT TAB >>> $session")
            }
        })

        val preferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        preferences.registerOnSharedPreferenceChangeListener(this)
        onPreferencesChange(preferences)
        val toolbar = view.findViewById<Toolbar>(R.id.gecko_toolbar)

        mToolbarView = ToolbarLayout(requireContext(), mTabSessionManager)
        mToolbarView.id = R.id.toolbar_layout // This ID is for your custom view
        mToolbarView.setTabListener(this)

        toolbar.addView(
            mToolbarView,
            Toolbar.LayoutParams(
                Toolbar.LayoutParams.MATCH_PARENT,
                Toolbar.LayoutParams.MATCH_PARENT
            )
        )

        toolbar.inflateMenu(R.menu.actions)
        toolbar.setOnMenuItemClickListener { menuItem ->
            onOptionsItemSelected(menuItem)
        }

        mFullAccessibilityTree =
            activity?.intent?.getBooleanExtra(FULL_ACCESSIBILITY_TREE_EXTRA, false) ?: false
        mProgressView = view.findViewById(R.id.page_progress)

        if (sGeckoRuntime == null) {
            val runtimeSettingsBuilder = GeckoRuntimeSettings.Builder()

            if (BuildConfig.DEBUG) {
                runtimeSettingsBuilder.arguments(arrayOf("-purcecaches"))
            }

            activity?.intent?.extras?.let { extras ->
                runtimeSettingsBuilder.extras(extras)
            }

            runtimeSettingsBuilder
                .remoteDebuggingEnabled(mRemoteDebugging.value())
                .consoleOutput(true)
                .contentBlocking(
                    ContentBlocking.Settings.Builder()
                        .antiTracking(ContentBlocking.AntiTracking.DEFAULT or ContentBlocking.AntiTracking.STP)
                        .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
                        .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS)
                        .cookieBehaviorPrivateMode(ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS)
                        .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.DEFAULT)
                        .emailTrackerBlockingPrivateMode(mEtbPrivateModeEnabled.value())
                        .build()
                )
                .crashHandler(ExampleCrashHandler::class.java)
                .preferredColorScheme(mPreferredColorScheme.value())
                .javaScriptEnabled(mJavascriptEnabled.value())
                .extensionsProcessEnabled(mExtensionsProcessEnabled.value())
                .globalPrivacyControlEnabled(mGlobalPrivacyControlEnabled.value())
                .aboutConfigEnabled(true)

            sGeckoRuntime = GeckoRuntime.create(requireContext(), runtimeSettingsBuilder.build())

            sExtensionManager = WebExtensionManager(sGeckoRuntime!!, mTabSessionManager)
            mTabSessionManager.setTabObserver(sExtensionManager!!)
            sExtensionManager?.setWebRequestListener(this)
            sGeckoRuntime!!.webExtensionController.setDebuggerDelegate(sExtensionManager!!)
            sGeckoRuntime!!.setAutocompleteStorageDelegate(ExampleAutocompleteStorageDelegate())
            sGeckoRuntime!!.orientationController.setDelegate(ExampleOrientationDelegate())
            sGeckoRuntime!!.setServiceWorkerDelegate(object : GeckoRuntime.ServiceWorkerDelegate {
                override fun onOpenWindow(url: String): GeckoResult<GeckoSession?> {
                    return mNavigationDelegate.onNewSession(
                        mTabSessionManager.currentSession,
                        url
                    )!!
                }
            })

            sGeckoRuntime!!.setWebNotificationDelegate(
                object : WebNotificationDelegate {
                    private val notificationManager =
                        requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                    override fun onShowNotification(notification: WebNotification) {
                        val clickIntent =
                            Intent(requireContext(), requireActivity().javaClass)
                        clickIntent.putExtra("onClick", notification)
                        val dismissIntent = PendingIntent.getActivity(
                            requireContext(), mLastID, clickIntent,
                            PendingIntent.FLAG_IMMUTABLE,
                        )

                        val builder = NotificationCompat.Builder(requireContext(), CHANNEL_ID)
                            .setContentTitle(notification.title)
                            .setContentText(notification.text)
                            .setSmallIcon(R.drawable.ic_status_logo)
                            .setContentIntent(dismissIntent)
                            .setAutoCancel(true)

                        mNotificationIDMap[notification.tag] = mLastID

                        notification.imageUrl?.let { imageUrl ->
                            if (imageUrl.isNotEmpty()) {
                                val executor = GeckoWebExecutor(sGeckoRuntime!!)
                                val response = executor.fetch(
                                    WebRequest.Builder(imageUrl)
                                        .addHeader("Accept", "image")
                                        .build(),
                                )
                                response.accept { value ->
                                    val bitmap = BitmapFactory.decodeStream(value?.body)
                                    bitmap?.let { builder.setLargeIcon(it) }
                                    notificationManager.notify(mLastID++, builder.build())
                                }
                            } else {
                                notificationManager.notify(mLastID++, builder.build())
                            }
                        }
                    }

                    override fun onCloseNotification(notification: WebNotification) {
                        mNotificationIDMap[notification.tag]?.let { id ->
                            notificationManager.cancel(id)
                            mNotificationIDMap.remove(notification.tag)
                        }
                    }
                },
            )

            sGeckoRuntime!!.setDelegate {
                mKillProcessOnDestroy = true
                activity?.finish()
            }

            sGeckoRuntime!!.setActivityDelegate { pendingIntent ->
                val result = GeckoResult<Intent>()
                try {
                    val code = mNextActivityResultCode++
                    mPendingActivityResult[code] = result
                    startIntentSenderForResult(
                        pendingIntent.intentSender, code, null, 0, 0, 0, null,
                    )
                } catch (e: IntentSender.SendIntentException) {
                    result.completeExceptionally(e)
                }
                result
            }
        }

        sExtensionManager?.setExtensionDelegate(this)

        installAddonFromAsset()

        if (savedInstanceState == null) {
            val session = activity?.intent?.getParcelableExtra<TabSession>("session")
            session?.let {
                connectSession(it)

                if (!it.isOpen) {
                    it.open(sGeckoRuntime!!)
                }

                mFullAccessibilityTree = it.settings.fullAccessibilityTree
                mTabSessionManager.addSession(it)
                it.open(sGeckoRuntime!!)
                setGeckoViewSession(it)
            } ?: run {
                val newSession = createSession()
                newSession?.open(sGeckoRuntime!!)

                // Use the initialUrl here
                initialUrl?.let { url ->
                    newSession.loadUri(url)
                }

                mTabSessionManager.currentSession = newSession
                mGeckoView.setSession(newSession)
                sGeckoRuntime!!.webExtensionController.setTabActive(newSession, true)
            }
            loadFromIntent(activity?.intent)
        }

        mToolbarView.locationView.setCommitListener(mCommitListener)
        mToolbarView.updateTabCount()
    }

    private fun openSettingsActivity() {
        val intent = Intent(requireContext(), SettingsActivity::class.java)
        startActivity(intent)
    }

    override fun getSession(session: GeckoSession?): TabSession? {
        return mTabSessionManager.getSession(session)
    }

    override fun getCurrentSession(): TabSession? {
        return mTabSessionManager.currentSession
    }

    override fun onActionButton(button: ActionButton?) {
        mToolbarView.setBrowserActionButton(button)
    }

    override fun toggleBrowserActionPopup(force: Boolean): GeckoSession? {
        if (mPopupSession == null) {
            openPopupSession()
        }

        val params = mPopupView?.layoutParams
        val shouldShow = force || (params?.width ?: 0) == 0
        setViewVisibility(mPopupView, shouldShow)

        return if (shouldShow) mPopupSession else null
    }

    private fun setViewVisibility(view: View?, visible: Boolean) {
        if (view == null) return

        val params = view.layoutParams
        if (visible) {
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            params.height = 0
            params.width = 0
        }
        view.layoutParams = params
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        sharedPreferences?.let { onPreferencesChange(it) }
    }

    private inner class PopupSessionContentDelegate : GeckoSession.ContentDelegate {
        override fun onCloseRequest(session: GeckoSession) {
            setViewVisibility(mPopupView, false)
            mPopupSession?.close()
            mPopupSession = null
            mPopupView = null
        }
    }

    private fun openPopupSession() {
        val inflater =
            requireContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        mPopupView = inflater.inflate(R.layout.browser_action_popup, null)
        val geckoView = mPopupView!!.findViewById<GeckoView>(R.id.gecko_view_popup)
        geckoView.setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
        mPopupSession = TabSession()
        mPopupSession?.setContentDelegate(PopupSessionContentDelegate())
        mPopupSession?.open(sGeckoRuntime!!)
        geckoView.setSession(mPopupSession!!)

        mPopupView?.setOnFocusChangeListener { view, hasFocus ->
            if (!hasFocus) {
                val params = mPopupView?.layoutParams
                params?.height = 0
                params?.width = 0
                mPopupView?.layoutParams = params
            }
        }

        val params = RelativeLayout.LayoutParams(0, 0)
        params.addRule(RelativeLayout.ABOVE, R.id.toolbar)
        mPopupView?.layoutParams = params
        mPopupView?.isFocusable = true
        (view?.findViewById<ViewGroup>(R.id.main))?.addView(mPopupView)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.app_name)
            val description = getString(R.string.gecko_label)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance)
            channel.description = description
            val notificationManager =
                requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createSession(cookieStoreId: String? = null): TabSession {
        val settingsBuilder = GeckoSessionSettings.Builder()
        settingsBuilder
            .usePrivateMode(mUsePrivateBrowsing)
            .fullAccessibilityTree(mFullAccessibilityTree)
            .userAgentOverride(mUserAgent.value())
            .viewportMode(
                if (mDesktopMode)
                    GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                else
                    GeckoSessionSettings.VIEWPORT_MODE_MOBILE
            )
            .userAgentMode(
                if (mDesktopMode)
                    GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                else
                    GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            )
            .useTrackingProtection(mTrackingProtection.value())
            .displayMode(mDisplayMode.value())

        cookieStoreId?.let {
            settingsBuilder.contextId(it)
        }

        val session = mTabSessionManager.newSession(settingsBuilder.build())
        connectSession(session)
        return session
    }

    private val mNavigationDelegate = ExampleNavigationDelegate()

    private fun connectSession(session: GeckoSession) {
        session.setContentDelegate(ExampleContentDelegate())
        session.setHistoryDelegate(ExampleHistoryDelegate())
        val cb = ExampleContentBlockingDelegate()
        session.setContentBlockingDelegate(cb)
        session.setProgressDelegate(ExampleProgressDelegate(cb))
        session.setNavigationDelegate(mNavigationDelegate)

        val prompt = BasicGeckoViewPrompt(requireActivity())
        prompt.filePickerRequestCode = REQUEST_FILE_PICKER
        session.setPromptDelegate(prompt)

        val permission = ExamplePermissionDelegate()
        permission.androidPermissionRequestCode = REQUEST_PERMISSIONS
        session.setPermissionDelegate(permission)

        session.setMediaDelegate(ExampleMediaDelegate(requireActivity()))

        session.setMediaSessionDelegate(ExampleMediaSessionDelegate(requireActivity()))

        session.setTranslationsSessionDelegate(ExampleTranslationsSessionDelegate())

        session.setSelectionActionDelegate(BasicSelectionActionDelegate(requireActivity()))

        sExtensionManager?.extension?.let { extension ->
            val sessionController = session.webExtensionController
            sessionController.setActionDelegate(extension, sExtensionManager!!)
            sessionController.setTabDelegate(extension, sExtensionManager!!)
            sessionController.setMessageDelegate(extension, sExtensionManager!!, "browser")
        }

        updateDesktopMode(session)
    }

    private fun recreateSession(session: TabSession? = null) {
        session?.let { mTabSessionManager.closeSession(it) }

        val newSession = createSession()
        newSession.open(sGeckoRuntime!!)
        mTabSessionManager.currentSession = newSession
        mGeckoView.setSession(newSession)
        sGeckoRuntime!!.webExtensionController.setTabActive(newSession, true)
        mCurrentUri?.let { newSession.loadUri(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Save fragment state if needed
    }

    private fun updateDesktopMode(session: GeckoSession) {
        session.settings.setViewportMode(
            if (mDesktopMode)
                GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
            else
                GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        )
        session.settings.setUserAgentMode(
            if (mDesktopMode)
                GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
            else
                GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        )
    }

    fun onBackPressed(): Boolean {
        val session = mTabSessionManager.currentSession
        if (mFullScreen && session != null) {
            session.exitFullScreen()
            return true
        }

        if (mCanGoBack && session != null) {
            session.goBack()
            return true
        }

        return false
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.actions, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        menu.findItem(R.id.action_pb).isChecked = mUsePrivateBrowsing
        menu.findItem(R.id.collapse).isChecked = mCollapsed
        menu.findItem(R.id.desktop_mode).isChecked = mDesktopMode
        menu.findItem(R.id.action_tpe).isChecked =
            mTrackingProtectionPermission?.value == ContentPermission.VALUE_ALLOW
        menu.findItem(R.id.action_forward).isEnabled = mCanGoForward

        val hasSession = mTabSessionManager.currentSession != null
        menu.findItem(R.id.action_reload).isEnabled = hasSession
        menu.findItem(R.id.action_forward).isEnabled = hasSession
        menu.findItem(R.id.action_close_tab).isEnabled = hasSession
        menu.findItem(R.id.action_tpe).isEnabled =
            hasSession && mTrackingProtectionPermission != null
        menu.findItem(R.id.action_pb).isEnabled = hasSession
        menu.findItem(R.id.desktop_mode).isEnabled = hasSession
        menu.findItem(R.id.translate).isVisible = mExpectedTranslate
        menu.findItem(R.id.translate_restore).isVisible = mTranslateRestore
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val session = mTabSessionManager.currentSession
        when (item.itemId) {
            R.id.action_reload -> session?.reload()
            R.id.action_forward -> session?.goForward()
            R.id.action_tpe -> {
                mTrackingProtectionPermission?.let { permission ->
                    sGeckoRuntime?.storageController?.setPermission(
                        permission,
                        if (permission.value == ContentPermission.VALUE_ALLOW)
                            ContentPermission.VALUE_DENY
                        else
                            ContentPermission.VALUE_ALLOW
                    )
                    session?.reload()
                }
            }

            R.id.desktop_mode -> {
                mDesktopMode = !mDesktopMode
                session?.let { updateDesktopMode(it) }
                session?.reload()
            }

            R.id.action_pb -> {
                mUsePrivateBrowsing = !mUsePrivateBrowsing
                recreateSession(session)
            }

            R.id.collapse -> {
                mCollapsed = !mCollapsed
                setViewVisibility(mGeckoView, !mCollapsed)
            }

            R.id.install_addon -> installAddon()
            R.id.update_addon -> updateAddon()
            R.id.settings -> openSettingsActivity()
            R.id.action_new_tab -> createNewTab()
            R.id.action_close_tab -> closeTab(session)
            R.id.save_pdf -> savePdf(session)
            R.id.print_page -> printPage(session)
            R.id.translate -> translate(session)
            R.id.translate_restore -> translateRestore(session)
            R.id.translate_manage -> translateManage()
            R.id.webcompat_info -> webCompatInfo(session)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun installAddonFromAsset() {
        val controller = sGeckoRuntime?.webExtensionController ?: return

        // Extensions from assets are "built-in" and don't need user prompts for installation.
        // The URI format "resource://android/assets/" points to the assets directory of the APK.
        val uri = "resource://android/assets/web-extension/"

        sExtensionManager?.unregisterExtension()?.then {
            controller.ensureBuiltIn(uri, "supervideodownloader@example.com")
        }?.then { extension ->
            sGeckoRuntime!!.webExtensionController.setAllowedInPrivateBrowsing(
                extension!!, mAllowExtensionsInPrivateBrowsing.value()
            )
        }?.accept { extension ->
            AppLogger.e("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
            AppLogger.d("REGISTRING EXTENSION: $extension")
            AppLogger.e("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
            sExtensionManager?.registerExtension(extension!!)
        }?.exceptionally<Void?> { throwable ->
            AppLogger.e("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
            AppLogger.e("Failed to install addon from asset: $throwable")
            AppLogger.e("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
            null // Return null to end the exceptional path
        }
    }

    private fun installAddon() {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(R.string.install_addon)

        val input = EditText(requireContext())
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.setHint(R.string.install_addon_hint)
        builder.setView(input)

        builder.setPositiveButton(R.string.install) { dialog, which ->
            val uri = input.text.toString()

            setViewVisibility(mPopupView, false)
            mPopupView = null
            mPopupSession = null

            sExtensionManager?.unregisterExtension()?.then { unused ->
                val controller = sGeckoRuntime?.webExtensionController
                controller?.setPromptDelegate(sExtensionManager)
                controller?.install(uri, null)
            }?.then({ extension ->
                sGeckoRuntime!!.webExtensionController.setAllowedInPrivateBrowsing(
                    extension!!, mAllowExtensionsInPrivateBrowsing.value()
                )
            })?.accept { extension ->
                sExtensionManager?.registerExtension(extension!!)
            }
        }

        builder.setNegativeButton(R.string.cancel) { dialog, which -> }

        builder.show()
    }

    private fun updateAddon() {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(R.string.update_addon)

        sExtensionManager?.updateExtension()?.accept(
            { extension ->
                builder.setMessage(if (extension != null) "Success" else "No addon to update")
                builder.show()
            },
            { exception ->
                builder.setMessage("Failed: $exception")
                builder.show()
            },
        )
    }

    private fun createNewTab() {
        val startTime = ProfilerController.getProfilerTime()
        val newSession = createSession()
        newSession.open(sGeckoRuntime!!)
        setGeckoViewSession(newSession)
        mToolbarView.updateTabCount()
        ProfilerController.addMarker("Create new tab", startTime)
    }

    @SuppressLint("WrongThread")
    @UiThread
    private fun savePdf(session: GeckoSession?) {
        session?.saveAsPdf()?.accept { pdfStream ->
            try {
                val response = WebResponse.Builder("")
                    .body(pdfStream!!)
                    .addHeader("Content-Type", "application/pdf")
                    .addHeader("Content-Disposition", "attachment; filename=PDFDownload.pdf")
                    .build()
                session.contentDelegate?.onExternalResponse(session, response)
            } catch (e: Exception) {
                Log.d(LOGTAG, e.message ?: "")
            }
        }
    }

    private fun printPage(session: GeckoSession?) {
        session?.didPrintPageContent()
    }

    private fun translate(session: GeckoSession?) {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(R.string.translate)
        val fromSelect = Spinner(requireContext())
        val toSelect = Spinner(requireContext())

        TranslationsController.RuntimeTranslation.listSupportedLanguages()
            .then<TranslationsController.RuntimeTranslation.TranslationSupport?> { supportedLanguages ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    Collections.reverse(supportedLanguages?.fromLanguages)
                }
                val fromData = ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_spinner_item,
                    supportedLanguages?.fromLanguages ?: emptyList(),
                )
                fromSelect.adapter = fromData
                val index =
                    fromData.getPosition(TranslationsController.Language(mDetectedLanguage!!, null))
                fromSelect.setSelection(index)

                val toData = ArrayAdapter(
                    requireContext(), // lint
                    android.R.layout.simple_spinner_item,
                    supportedLanguages?.toLanguages ?: emptyList(),
                )
                toSelect.adapter = toData

                TranslationsController.RuntimeTranslation.preferredLanguages()
                    .then<MutableList<String>> { preferredList ->
                        Log.d(LOGTAG, "Preferred Translation Languages: $preferredList")
                        for (i in preferredList?.indices?.reversed() ?: emptyList()) {
                            val langIndex = toData.getPosition(
                                TranslationsController.Language(preferredList?.get(i) ?: "en", null)
                            ) // lint
                            val displayLanguage = toData.getItem(langIndex)
                            toData.remove(displayLanguage)
                            toData.insert(displayLanguage, 0)
                            if (i == 0) {
                                toSelect.setSelection(0)
                            }
                        }
                        null
                    }
                null
            }

        builder.setView(
            translateLayout(
                fromSelect,
                R.string.translate_language_from_hint,
                toSelect,
                R.string.translate_language_to_hint,
                -1,
            ),
        )

        builder.setPositiveButton(R.string.translate_action) { dialog, which ->
            val fromLang = fromSelect.selectedItem as TranslationsController.Language
            val toLang = toSelect.selectedItem as TranslationsController.Language
            session?.sessionTranslation?.translate(fromLang.code, toLang.code, null)
            mTranslateRestore = true
        }

        builder.setNegativeButton(R.string.cancel) { dialog, which -> }
        builder.show()
    }

    private fun translateRestore(session: GeckoSession?) {
        session?.sessionTranslation?.restoreOriginalPage()
            ?.then<Void?> {
                mTranslateRestore = false
                null
            }
    }

    private fun translateManage() {
        val languageSelect = Spinner(requireContext())
        val operationSelect = Spinner(requireContext())

        val operationChoices = listOf(
            TranslationsController.RuntimeTranslation.DELETE.toString(),
            TranslationsController.RuntimeTranslation.DOWNLOAD.toString(),
        )
        val operationData = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            operationChoices,
        )
        operationSelect.adapter = operationData

        val currentStates = TranslationsController.RuntimeTranslation.listModelDownloadStates()
        currentStates.then<List<TranslationsController.RuntimeTranslation.LanguageModel>> { models ->
            val languages = ArrayList<TranslationsController.Language>()
            languages.add(TranslationsController.Language("all", "All Models"))
            for (model in models ?: emptyList()) {
                Log.i(LOGTAG, "Translate Model State: $model")
                model.language?.let { languages.add(it) }
            }
            val languageData = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_item,
                languages,
            )
            languageSelect.adapter = languageData
            null
        }

        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(R.string.translate_manage)
        builder.setView(
            translateLayout(
                languageSelect,
                R.string.translate_manage_languages,
                operationSelect,
                R.string.translate_manage_operations,
                R.string.translate_display_hint,
            ),
        )

        builder.setPositiveButton(R.string.translate_manage_action) { dialog, which ->
            val selectedLanguage = languageSelect.selectedItem as TranslationsController.Language
            val operation = operationSelect.selectedItem as String

            var operationLevel = TranslationsController.RuntimeTranslation.LANGUAGE
            if (selectedLanguage.code == "all") {
                operationLevel = TranslationsController.RuntimeTranslation.ALL
            }

            val options = TranslationsController.RuntimeTranslation.ModelManagementOptions.Builder()
                .languageToManage(selectedLanguage.code)
                .operation(operation)
                .operationLevel(operationLevel)
                .build()

            val requestOperation =
                TranslationsController.RuntimeTranslation.manageLanguageModel(options)
            requestOperation.then<Void?> {
                val reportChanges =
                    TranslationsController.RuntimeTranslation.listModelDownloadStates()
                reportChanges.then<List<TranslationsController.RuntimeTranslation.LanguageModel>> { models ->
                    for (model in models ?: emptyList()) {
                        Log.i(LOGTAG, "Translate Model State: $model")
                    }
                    null
                }
                null
            }
        }

        builder.setNegativeButton(R.string.cancel) { dialog, which -> }
        builder.show()
    }

    private fun translateLayout(
        spinnerA: Spinner,
        labelA: Int,
        spinnerB: Spinner,
        labelB: Int,
        labelInfo: Int,
    ): RelativeLayout {
        val fromLangLabel = TextView(requireContext())
        fromLangLabel.setText(labelA)
        val from = LinearLayout(requireContext())
        from.id = View.generateViewId()
        from.addView(fromLangLabel)
        from.addView(spinnerA)
        val fromParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT,
        )
        fromParams.setMargins(30, 0, 0, 0)

        val toLangLabel = TextView(requireContext())
        toLangLabel.setText(labelB)
        val to = LinearLayout(requireContext())
        to.id = View.generateViewId()
        to.addView(toLangLabel)
        to.addView(spinnerB)
        val toParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT,
        )
        toParams.setMargins(30, 0, 0, 0)
        toParams.addRule(RelativeLayout.BELOW, from.id)

        val layout = RelativeLayout(requireContext())
        layout.addView(from, fromParams)
        layout.addView(to, toParams)

        if (labelInfo != -1) {
            val info = TextView(requireContext())
            val infoParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT,
            )
            infoParams.setMargins(30, 0, 0, 0)
            infoParams.addRule(RelativeLayout.BELOW, to.id)
            info.setText(labelInfo)
            layout.addView(info, infoParams)
        }

        return layout
    }

    override fun closeTab(session: TabSession?) {
        session?.let { mTabSessionManager.closeSession(it) }
        val tabSession = mTabSessionManager.currentSession
        setGeckoViewSession(tabSession)
        tabSession?.reload()
        mToolbarView.updateTabCount()
    }

    override fun updateTab(session: TabSession?, details: WebExtension.UpdateTabDetails?) {
        if (details?.active == true) {
            switchToSession(session, false)
        }
    }

    override fun onBrowserActionClick() {
        sExtensionManager?.onClicked(mTabSessionManager.currentSession)
    }

    fun switchToSession(session: TabSession?, activateTab: Boolean) {
        val currentSession = mTabSessionManager.currentSession
        if (session != currentSession) {
            setGeckoViewSession(session, activateTab)
            mCurrentUri = session?.uri
            session?.let {
                if (!it.isOpen) {
                    it.open(sGeckoRuntime!!)
                    mCurrentUri?.let { uri -> it.loadUri(uri) }
                }
                mToolbarView.locationView.setText(mCurrentUri)
            }
        }
    }

    override fun switchToTab(index: Int) {
        val nextSession = mTabSessionManager.getSession(index)
        switchToSession(nextSession, true)
    }

    private fun setGeckoViewSession(session: TabSession?, activateTab: Boolean = true) {
        val controller = sGeckoRuntime?.webExtensionController
        val previousSession = mGeckoView.session
        previousSession?.let { controller?.setTabActive(it, false) }

        val hasSession = session != null
        val view = mToolbarView.locationView
        view.isEnabled = hasSession

        if (hasSession) {
            mGeckoView.setSession(session)
            if (activateTab) {
                controller?.setTabActive(session, true)
            }
            mTabSessionManager.currentSession = session
        } else {
            mGeckoView.coverUntilFirstPaint(Color.WHITE)
            view.setText("")
        }
    }

    override fun onDestroy() {
        if (mKillProcessOnDestroy) {
            Process.killProcess(Process.myPid())
        }

        super.onDestroy()
    }

    fun onNewIntent(intent: Intent?) {
        intent ?: return

        if (ACTION_SHUTDOWN == intent.action) {
            mKillProcessOnDestroy = true
            sGeckoRuntime?.shutdown()
            activity?.finish()
            return
        }

        intent.getParcelableExtra<WebNotification>("onClick")?.let { notification ->
            intent.removeExtra("onClick")
            notification.click()
        }

        if (intent.data != null) {
            loadFromIntent(intent)
        }
    }

    private fun loadFromIntent(intent: Intent?) {
        intent?.data?.let { uri ->
            mTabSessionManager.currentSession?.load(
                GeckoSession.Loader()
                    .uri(uri.toString())
                    .flags(GeckoSession.LOAD_FLAGS_EXTERNAL),
            )!!
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQUEST_FILE_PICKER -> {
                val prompt =
                    mTabSessionManager.currentSession?.promptDelegate as? BasicGeckoViewPrompt
                prompt?.onFileCallbackResult(resultCode, data)
            }

            else -> {
                if (mPendingActivityResult.containsKey(requestCode)) {
                    val result = mPendingActivityResult.remove(requestCode)
                    if (resultCode == Activity.RESULT_OK) {
                        result?.complete(data)
                    } else {
                        result?.completeExceptionally(RuntimeException("Unknown error"))
                    }
                } else {
                    super.onActivityResult(requestCode, resultCode, data)
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            REQUEST_PERMISSIONS -> {
                val permission =
                    mTabSessionManager.currentSession?.permissionDelegate as? ExamplePermissionDelegate
                permission?.onRequestPermissionsResult(permissions as Array<String>, grantResults)
            }

            REQUEST_WRITE_EXTERNAL_STORAGE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    continueDownloads()
                }
            }

            else -> super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    private fun continueDownloads() {
        val downloads = mPendingDownloads
        mPendingDownloads = LinkedList()

        for (response in downloads) {
            downloadFile(response)
        }
    }

    private fun sanitizeMimeType(mimeType: String?): String? {
        return mimeType?.let {
            if (it.contains(";")) {
                it.split(";")[0].trim()
            } else {
                it.trim()
            }
        }
    }

    private fun downloadFile(response: WebResponse) {
        if (response.body == null) return

        val filename = getFileName(response)
        Log.i(LOGTAG, "FileName:$filename")

        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
            val contentResolver = requireContext().contentResolver
            var mime = sanitizeMimeType(response.headers["Content-Type"])
            if (mime.isNullOrEmpty()) mime = "*/*"

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val fileUri = contentResolver.insert(collection, contentValues)

            fileUri ?: run {
                Toast.makeText(
                    requireContext(),
                    "Unable to access downloads directory",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val inputStream = response.body
                    if (inputStream != null) {
                        contentResolver.openOutputStream(fileUri)?.use { out ->
                            val buffer = ByteArray(1024)
                            var len: Int
                            while (inputStream.read(buffer).also { len = it } != -1) {
                                out.write(buffer, 0, len)
                            }
                        }
                    }

                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    contentResolver.update(fileUri, contentValues, null, null)

                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), "Downloading $filename", Toast.LENGTH_LONG)
                            .show()
                    }
                } catch (e: Exception) {
                    Log.i(LOGTAG, e.stackTraceToString())
                }
            }
        } else {
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                mPendingDownloads.add(response)
                ActivityCompat.requestPermissions(
                    requireActivity(),
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    REQUEST_WRITE_EXTERNAL_STORAGE,
                )
            }
        }
    }

    private fun getFileName(response: WebResponse): String {
        var contentDispositionHeader = response.headers["content-disposition"]
            ?: response.headers.getOrDefault("Content-Disposition", "default filename=GVDownload")

        val pattern = Pattern.compile("(filename=\"?)(.+)(\"?)")
        val matcher = pattern.matcher(contentDispositionHeader)

        return if (matcher.find()) {
            matcher.group(2).replace("\\s".toRegex(), "%20").replace("\"", "")
        } else {
            "GVEdownload"
        }
    }

    private fun isForeground(): Boolean {
        val appProcessInfo = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(appProcessInfo)
        return appProcessInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                appProcessInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    private var mErrorTemplate: String? = null

    private fun createErrorPage(error: String): String? {
        if (mErrorTemplate == null) {
            var stream: InputStream? = null
            var reader: BufferedReader? = null
            try {
                stream = resources.assets.open("error.html")
                reader = BufferedReader(InputStreamReader(stream))
                mErrorTemplate = reader.readText()
            } catch (e: IOException) {
                Log.d(LOGTAG, "Failed to open error page template", e)
                return null
            } finally {
                stream?.close()
                reader?.close()
            }
        }
        return mErrorTemplate?.replace("${DeprecationLevel.ERROR}", error)
    }

    private inner class ExampleHistoryDelegate : GeckoSession.HistoryDelegate {
        private val mVisitedURLs = HashSet<String>()

        override fun onVisited(
            session: GeckoSession,
            url: String,
            lastVisitedURL: String?,
            flags: Int,
        ): GeckoResult<Boolean> {
            Log.i(LOGTAG, "Visited URL: $url")
            mVisitedURLs.add(url)
            return GeckoResult.fromValue(true)
        }

        override fun getVisited(
            session: GeckoSession,
            urls: Array<String>
        ): GeckoResult<BooleanArray> {
            val visited = BooleanArray(urls.size) { i -> mVisitedURLs.contains(urls[i]) }
            return GeckoResult.fromValue(visited)
        }

        override fun onHistoryStateChange(
            session: GeckoSession,
            state: GeckoSession.HistoryDelegate.HistoryList
        ) {
            Log.i(LOGTAG, "History state updated")
        }
    }

    private inner class ExampleAutocompleteStorageDelegate : Autocomplete.StorageDelegate {
        private val mStorage = HashMap<String, Autocomplete.LoginEntry>()

        override fun onLoginFetch(): GeckoResult<Array<Autocomplete.LoginEntry>> {
            return GeckoResult.fromValue(mStorage.values.toTypedArray())
        }

        override fun onLoginSave(login: Autocomplete.LoginEntry) {
            mStorage[login.guid.toString()] = login
        }
    }

    private inner class ExampleOrientationDelegate : OrientationController.OrientationDelegate {
        override fun onOrientationLock(aOrientation: Int): GeckoResult<AllowOrDeny> {
            activity?.requestedOrientation = aOrientation
            return GeckoResult.allow()
        }

        override fun onOrientationUnlock() {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private inner class ExampleContentDelegate : GeckoSession.ContentDelegate {
        override fun onTitleChange(session: GeckoSession, title: String?) {
            Log.i(LOGTAG, "Content title changed to $title")
            val tabSession = mTabSessionManager.getSession(session)
            tabSession?.title = title
        }

        override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
            mFullScreen = fullScreen
            activity?.let {
                if (fullScreen) {
                    it.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                } else {
                    it.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                }
            }
        }

        override fun onFocusRequest(session: GeckoSession) {
            Log.i(LOGTAG, "Content requesting focus")
        }

        override fun onCloseRequest(session: GeckoSession) {
            val currentSession = mTabSessionManager.currentSession
            if (session == currentSession) {
                closeTab(currentSession)
            }
        }

        override fun onContextMenu(
            session: GeckoSession,
            screenX: Int,
            screenY: Int,
            element: GeckoSession.ContentDelegate.ContextElement
        ) {
            Log.i(
                LOGTAG,
                "onContextMenu screenX=$screenX screenY=$screenY type=${element.type} " +
                        "linkUri=${element.linkUri} title=${element.title} alt=${element.altText} " +
                        "srcUri=${element.srcUri}"
            )
        }

        override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
            downloadFile(response)
        }

        override fun onCrash(session: GeckoSession) {
            Log.e(LOGTAG, "Crashed, reopening session")
            session.open(sGeckoRuntime!!)
        }

        override fun onKill(session: GeckoSession) {
            val tabSession = mTabSessionManager.getSession(session) ?: return

            if (tabSession != mTabSessionManager.currentSession) {
                Log.e(LOGTAG, "Background session killed")
                return
            }

            if (isForeground()) {
                throw IllegalStateException("Foreground content process unexpectedly killed by OS!")
            }

            Log.e(LOGTAG, "Current session killed, reopening")
            tabSession.open(sGeckoRuntime!!)
            tabSession.loadUri(tabSession.uri)
        }

        override fun onFirstComposite(session: GeckoSession) {
            Log.d(LOGTAG, "onFirstComposite")
        }

        override fun onWebAppManifest(session: GeckoSession, manifest: JSONObject) {
            Log.d(LOGTAG, "onWebAppManifest: $manifest")
        }

        private var activeAlert = false

        override fun onSlowScript(
            geckoSession: GeckoSession,
            scriptFileName: String
        ): GeckoResult<SlowScriptResponse>? {
            val prompt = mTabSessionManager.currentSession?.promptDelegate as? BasicGeckoViewPrompt
            return prompt?.let {
                val result = GeckoResult<SlowScriptResponse>()
                if (!activeAlert) {
                    activeAlert = true
                    it.onSlowScriptPrompt(geckoSession, getString(R.string.slow_script), result)
                }
                result.then { value ->
                    activeAlert = false
                    GeckoResult.fromValue(value)
                }
            }
        }

        override fun onMetaViewportFitChange(session: GeckoSession, viewportFit: String) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

            activity?.window?.attributes?.let { layoutParams ->
                layoutParams.layoutInDisplayCutoutMode = when (viewportFit) {
                    "cover" -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    "contain" -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
                    else -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
                activity?.window?.attributes = layoutParams
            }
        }

        override fun onShowDynamicToolbar(session: GeckoSession) {
            view?.findViewById<View>(R.id.toolbar)?.let { toolbar ->
                toolbar.translationY = 0f
                mGeckoView.setVerticalClipping(0)
            }
        }

        override fun onHideDynamicToolbar(session: GeckoSession) {
            view?.findViewById<View>(R.id.toolbar)?.let { toolbar ->
                toolbar.translationY = toolbar.height.toFloat()
            }
        }

        override fun onCookieBannerDetected(session: GeckoSession) {
            Log.d("BELL", "A cookie banner was detected on this website")
        }

        override fun onCookieBannerHandled(session: GeckoSession) {
            Log.d("BELL", "A cookie banner was handled on this website")
        }
    }

    private inner class ExampleProgressDelegate(private val mCb: ExampleContentBlockingDelegate) :
        GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            Log.i(LOGTAG, "Starting to load page at $url")
            Log.i(LOGTAG, "zerdatime ${SystemClock.elapsedRealtime()} - page load start")
            mCb.clearCounters()
            mExpectedTranslate = false
            mTranslateRestore = false
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            Log.i(LOGTAG, "Stopping page load ${if (success) "successfully" else "unsuccessfully"}")
            Log.i(LOGTAG, "zerdatime ${SystemClock.elapsedRealtime()} - page load stop")
            mCb.logCounters()
        }

        override fun onProgressChange(session: GeckoSession, progress: Int) {
            Log.i(LOGTAG, "onProgressChange $progress")
            mProgressView.progress = progress
            mProgressView.visibility =
                if (progress > 0 && progress < 100) View.VISIBLE else View.GONE
        }

        override fun onSecurityChange(
            session: GeckoSession,
            securityInfo: GeckoSession.ProgressDelegate.SecurityInformation
        ) {
            Log.i(LOGTAG, "Security status changed to ${securityInfo.securityMode}")
        }

        override fun onSessionStateChange(session: GeckoSession, state: GeckoSession.SessionState) {
            Log.i(LOGTAG, "New Session state: $state")
        }
    }

    private inner class ExamplePermissionDelegate : GeckoSession.PermissionDelegate {
        var androidPermissionRequestCode = 1
        private var mCallback: GeckoSession.PermissionDelegate.Callback? = null

        inner class ExampleNotificationCallback(private val mCallback: GeckoSession.PermissionDelegate.Callback) :
            GeckoSession.PermissionDelegate.Callback {
            override fun reject() {
                mShowNotificationsRejected = true
                mCallback.reject()
            }

            override fun grant() {
                mShowNotificationsRejected = false
                mCallback.grant()
            }
        }

        inner class ExamplePersistentStorageCallback(
            private val mCallback: GeckoSession.PermissionDelegate.Callback,
            private val mUri: String
        ) : GeckoSession.PermissionDelegate.Callback {
            override fun reject() {
                mCallback.reject()
            }

            override fun grant() {
                mAcceptedPersistentStorage.add(mUri)
                mCallback.grant()
            }
        }

        fun onRequestPermissionsResult(permissions: Array<out String>, grantResults: IntArray) {
            mCallback?.let { cb ->
                mCallback = null
                for (result in grantResults) {
                    if (result != PackageManager.PERMISSION_GRANTED) {
                        cb.reject()
                        return
                    }
                }
                cb.grant()
            }
        }

        override fun onAndroidPermissionsRequest(
            session: GeckoSession,
            permissions: Array<String>?,
            callback: GeckoSession.PermissionDelegate.Callback
        ) {
            if (Build.VERSION.SDK_INT >= 23) {
                mCallback = callback
                ActivityCompat.requestPermissions(
                    requireActivity(),
                    permissions!!,
                    androidPermissionRequestCode
                )
            } else {
                callback.grant()
            }
        }

        override fun onContentPermissionRequest(
            session: GeckoSession,
            perm: ContentPermission
        ): GeckoResult<Int> {
            val resId = when (perm.permission) {
                GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION -> R.string.request_geolocation
                GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> R.string.request_notification
                GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> R.string.request_storage
                GeckoSession.PermissionDelegate.PERMISSION_XR -> R.string.request_xr
                GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE, GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE -> {
                    return if (!mAllowAutoplay.value()) {
                        GeckoResult.fromValue(ContentPermission.VALUE_DENY)
                    } else {
                        GeckoResult.fromValue(ContentPermission.VALUE_ALLOW)
                    }
                }

                GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> R.string.request_media_key_system_access
                GeckoSession.PermissionDelegate.PERMISSION_STORAGE_ACCESS -> R.string.request_storage_access
                else -> return GeckoResult.fromValue(ContentPermission.VALUE_DENY)
            }

            val title = getString(resId, Uri.parse(perm.uri).authority)
            val prompt = mTabSessionManager.currentSession?.promptDelegate as? BasicGeckoViewPrompt
            return prompt?.onPermissionPrompt(session, title, perm)
                ?: GeckoResult.fromValue(ContentPermission.VALUE_DENY)
        }

        private fun normalizeMediaName(sources: Array<GeckoSession.PermissionDelegate.MediaSource>?): Array<String>? {
            sources ?: return null
            return Array(sources.size) { i ->
                when (sources[i].source) {
                    GeckoSession.PermissionDelegate.MediaSource.SOURCE_CAMERA -> {
                        if (sources[i].name?.lowercase(Locale.ROOT)?.contains("front") == true) {
                            getString(R.string.media_front_camera)
                        } else {
                            getString(R.string.media_back_camera)
                        }
                    }

                    GeckoSession.PermissionDelegate.MediaSource.SOURCE_MICROPHONE -> getString(R.string.media_microphone)
                    else -> {
                        if (sources[i].name?.isNotEmpty() == true) {
                            sources[i].name
                        } else {
                            getString(R.string.media_other)
                        }
                    }
                } ?: ""
            }
        }

        override fun onMediaPermissionRequest(
            session: GeckoSession,
            uri: String,
            video: Array<GeckoSession.PermissionDelegate.MediaSource>?,
            audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
            callback: GeckoSession.PermissionDelegate.MediaCallback,
        ) {
            if ((audio != null && ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED) ||
                (video != null && ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED)
            ) {
                callback.reject()
                return
            }

            val host = Uri.parse(uri).authority
            val title = when {
                audio == null -> getString(R.string.request_video, host)
                video == null -> getString(R.string.request_audio, host)
                else -> getString(R.string.request_media, host)
            }

            val videoNames = normalizeMediaName(video)
            val audioNames = normalizeMediaName(audio)

            val prompt = mTabSessionManager.currentSession?.promptDelegate as? BasicGeckoViewPrompt
            prompt?.onMediaPrompt(session, title, video, audio, videoNames, audioNames, callback)
                ?: callback.reject()
        }
    }

    private fun getTrackingProtectionPermission(perms: List<ContentPermission>): ContentPermission? {
        return perms.firstOrNull { it.permission == GeckoSession.PermissionDelegate.PERMISSION_TRACKING }
    }

    fun webCompatInfo(session: GeckoSession?) {
        session?.getWebCompatInfo()?.map { getWebCompatInfo ->
            Log.d(LOGTAG, "Received web compat info.")
            getWebCompatInfo?.let {
                JSONObject().apply {
                    put("reason", "Reason")
                    put("description", "Description")
                    put("endpointUrl", "https://webcompat.com/issues/new")
                    put("reportUrl", "https://www.mozilla.org/en-US/firefox/")

                    val reporterConfig = JSONObject().apply {
                        put("src", "android-components-reporter")
                        put("utm_campaign", "report-site-issue-button")
                        put("utm_source", "android-components-reporter")
                    }

                    put("reporterConfig", reporterConfig)
                    put("webcompatInfo", getWebCompatInfo)
                    session.sendMoreWebCompatInfo(this)
                }
            }
            getWebCompatInfo
        }
    }

    override fun onRequestIntercepted(url: WebRequest) {
        AppLogger.d("onRequestIntercepted:   ${url.uri}   ${url.headers}")

    }

    override fun onDestroyView() {
        super.onDestroyView()
        sExtensionManager?.setWebRequestListener(null)
    }

    private inner class ExampleNavigationDelegate : GeckoSession.NavigationDelegate {
        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            perms: List<ContentPermission>,
            hasUserGesture: Boolean,
        ) {
            mToolbarView.locationView.setText(url)
            val tabSession = mTabSessionManager.getSession(session)
            if (url != null) {
                tabSession?.onLocationChange(url)
            }
            mTrackingProtectionPermission = getTrackingProtectionPermission(perms)
            mCurrentUri = url
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            mCanGoBack = canGoBack
        }

        override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
            mCanGoForward = canGoForward
        }

        override fun onLoadRequest(
            session: GeckoSession,
            request: GeckoSession.NavigationDelegate.LoadRequest
        ): GeckoResult<AllowOrDeny> {
            AppLogger.d(
                "onLoadRequest=${request.uri} triggerUri=${request.triggerUri} " +
                        "where=${request.target} isRedirect=${request.isRedirect} " +
                        "isDirectNavigation=${request.isDirectNavigation}"
            ) // Lint
            return GeckoResult.allow()
        }

        override fun onSubframeLoadRequest(
            session: GeckoSession,
            request: GeckoSession.NavigationDelegate.LoadRequest
        ): GeckoResult<AllowOrDeny> {
            AppLogger.d(
                "onSubframeLoadRequest=${request.uri} triggerUri=${request.triggerUri} " +
                        "isRedirect=${request.isRedirect} isDirectNavigation=${request.isDirectNavigation}"
            ) // lint
            return GeckoResult.allow()
        }

        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession?> {
            val newSession = createSession()
            mToolbarView.updateTabCount() // lint
            setGeckoViewSession(newSession)
            return GeckoResult.fromValue(newSession)
        }

        private fun categoryToString(category: Int): String {
            return when (category) {
                WebRequestError.ERROR_CATEGORY_UNKNOWN -> "ERROR_CATEGORY_UNKNOWN"
                WebRequestError.ERROR_CATEGORY_SECURITY -> "ERROR_CATEGORY_SECURITY"
                WebRequestError.ERROR_CATEGORY_NETWORK -> "ERROR_CATEGORY_NETWORK"
                WebRequestError.ERROR_CATEGORY_CONTENT -> "ERROR_CATEGORY_CONTENT"
                WebRequestError.ERROR_CATEGORY_URI -> "ERROR_CATEGORY_URI"
                WebRequestError.ERROR_CATEGORY_PROXY -> "ERROR_CATEGORY_PROXY"
                WebRequestError.ERROR_CATEGORY_SAFEBROWSING -> "ERROR_CATEGORY_SAFEBROWSING"
                else -> "UNKNOWN"
            }
        }

        private fun errorToString(error: Int): String {
            return when (error) {
                WebRequestError.ERROR_UNKNOWN -> "ERROR_UNKNOWN"
                WebRequestError.ERROR_SECURITY_SSL -> "ERROR_SECURITY_SSL"
                WebRequestError.ERROR_SECURITY_BAD_CERT -> "ERROR_SECURITY_BAD_CERT"
                WebRequestError.ERROR_NET_RESET -> "ERROR_NET_RESET"
                WebRequestError.ERROR_NET_INTERRUPT -> "ERROR_NET_INTERRUPT"
                WebRequestError.ERROR_NET_TIMEOUT -> "ERROR_NET_TIMEOUT"
                WebRequestError.ERROR_CONNECTION_REFUSED -> "ERROR_CONNECTION_REFUSED"
                WebRequestError.ERROR_UNKNOWN_PROTOCOL -> "ERROR_UNKNOWN_PROTOCOL"
                WebRequestError.ERROR_UNKNOWN_HOST -> "ERROR_UNKNOWN_HOST"
                WebRequestError.ERROR_UNKNOWN_SOCKET_TYPE -> "ERROR_UNKNOWN_SOCKET_TYPE"
                WebRequestError.ERROR_UNKNOWN_PROXY_HOST -> "ERROR_UNKNOWN_PROXY_HOST"
                WebRequestError.ERROR_MALFORMED_URI -> "ERROR_MALFORMED_URI"
                WebRequestError.ERROR_REDIRECT_LOOP -> "ERROR_REDIRECT_LOOP"
                WebRequestError.ERROR_SAFEBROWSING_PHISHING_URI -> "ERROR_SAFEBROWSING_PHISHING_URI"
                WebRequestError.ERROR_SAFEBROWSING_MALWARE_URI -> "ERROR_SAFEBROWSING_MALWARE_URI"
                WebRequestError.ERROR_SAFEBROWSING_UNWANTED_URI -> "ERROR_SAFEBROWSING_UNWANTED_URI"
                WebRequestError.ERROR_SAFEBROWSING_HARMFUL_URI -> "ERROR_SAFEBROWSING_HARMFUL_URI"
                WebRequestError.ERROR_CONTENT_CRASHED -> "ERROR_CONTENT_CRASHED"
                WebRequestError.ERROR_OFFLINE -> "ERROR_OFFLINE"
                WebRequestError.ERROR_PORT_BLOCKED -> "ERROR_PORT_BLOCKED"
                WebRequestError.ERROR_PROXY_CONNECTION_REFUSED -> "ERROR_PROXY_CONNECTION_REFUSED"
                WebRequestError.ERROR_FILE_NOT_FOUND -> "ERROR_FILE_NOT_FOUND"
                WebRequestError.ERROR_FILE_ACCESS_DENIED -> "ERROR_FILE_ACCESS_DENIED"
                WebRequestError.ERROR_INVALID_CONTENT_ENCODING -> "ERROR_INVALID_CONTENT_ENCODING"
                WebRequestError.ERROR_UNSAFE_CONTENT_TYPE -> "ERROR_UNSAFE_CONTENT_TYPE"
                WebRequestError.ERROR_CORRUPTED_CONTENT -> "ERROR_CORRUPTED_CONTENT"
                WebRequestError.ERROR_HTTPS_ONLY -> "ERROR_HTTPS_ONLY"
                WebRequestError.ERROR_BAD_HSTS_CERT -> "ERROR_BAD_HSTS_CERT"
                else -> "UNKNOWN"
            }
        }

        private fun createErrorPage(category: Int, error: Int): String? {
            if (mErrorTemplate == null) {
                try {
                    resources.assets.open("error.html").use { stream ->
                        BufferedReader(InputStreamReader(stream)).use { reader ->
                            mErrorTemplate = reader.readText()
                        }
                    }
                } catch (e: IOException) {
                    Log.d(LOGTAG, "Failed to open error page template", e)
                    return null
                }
            }
            return "Errorrr"
        }

        override fun onLoadError(
            session: GeckoSession,
            uri: String?,
            error: WebRequestError
        ): GeckoResult<String> {
            Log.d(LOGTAG, "onLoadError=$uri error category=${error.category} error=${error.code}")
            return GeckoResult.fromValue(
                "data:text/html,${
                    createErrorPage(
                        error.category,
                        error.code
                    )
                }"
            )
        }
    }

    private inner class ExampleContentBlockingDelegate : ContentBlocking.Delegate {
        private var mBlockedAds = 0
        private var mBlockedAnalytics = 0
        private var mBlockedSocial = 0
        private var mBlockedContent = 0
        private var mBlockedTest = 0
        private var mBlockedStp = 0

        fun clearCounters() {
            mBlockedAds = 0
            mBlockedAnalytics = 0
            mBlockedSocial = 0
            mBlockedContent = 0
            mBlockedTest = 0
            mBlockedStp = 0
        }

        fun logCounters() {
            Log.d(
                LOGTAG, // lint
                "Trackers blocked: $mBlockedAds ads, $mBlockedAnalytics analytics, " +
                        "$mBlockedSocial social, $mBlockedContent content, $mBlockedTest test, $mBlockedStp stp"
            )
        }

        override fun onContentBlocked(session: GeckoSession, event: ContentBlocking.BlockEvent) {
            Log.d(
                LOGTAG,
                "onContentBlocked AT: ${event.antiTrackingCategory} " +
                        "SB: ${event.safeBrowsingCategory} CB: ${event.cookieBehaviorCategory} " +
                        "URI: ${event.uri}"
            )
            if (event.antiTrackingCategory and ContentBlocking.AntiTracking.TEST != 0) mBlockedTest++
            if (event.antiTrackingCategory and ContentBlocking.AntiTracking.AD != 0) mBlockedAds++
            if (event.antiTrackingCategory and ContentBlocking.AntiTracking.ANALYTIC != 0) mBlockedAnalytics++
            if (event.antiTrackingCategory and ContentBlocking.AntiTracking.SOCIAL != 0) mBlockedSocial++
            if (event.antiTrackingCategory and ContentBlocking.AntiTracking.CONTENT != 0) mBlockedContent++
            if (event.antiTrackingCategory and ContentBlocking.AntiTracking.STP != 0) mBlockedStp++
        }

        override fun onContentLoaded(session: GeckoSession, event: ContentBlocking.BlockEvent) {
            Log.d(
                LOGTAG, // lint
                "onContentLoaded AT: ${event.antiTrackingCategory} " +
                        "SB: ${event.safeBrowsingCategory} CB: ${event.cookieBehaviorCategory} " +
                        "URI: ${event.uri}"
            )
        }
    }

    private inner class ExampleMediaDelegate(private val mActivity: Activity) :
        GeckoSession.MediaDelegate {
        private var mLastNotificationId = 100
        private var mNotificationId: Int? = null

        override fun onRecordingStatusChanged(
            session: GeckoSession,
            devices: Array<GeckoSession.MediaDelegate.RecordingDevice>
        ) {
            var message: String
            var icon: Int
            val notificationManager = NotificationManagerCompat.from(mActivity)
            var camera: GeckoSession.MediaDelegate.RecordingDevice? = null
            var microphone: GeckoSession.MediaDelegate.RecordingDevice? = null

            for (device in devices) {
                when (device.type) {
                    GeckoSession.MediaDelegate.RecordingDevice.Type.CAMERA -> camera = device
                    GeckoSession.MediaDelegate.RecordingDevice.Type.MICROPHONE -> microphone =
                        device
                }
            }

            when {
                camera != null && microphone != null -> {
                    Log.d(
                        LOGTAG,
                        "ExampleDeviceDelegate:onRecordingDeviceEvent display alert_mic_camera"
                    )
                    message = getString(R.string.device_sharing_camera_and_mic)
                    icon = R.drawable.alert_mic_camera
                }

                camera != null -> {
                    Log.d(
                        LOGTAG,
                        "ExampleDeviceDelegate:onRecordingDeviceEvent display alert_camera"
                    )
                    message = getString(R.string.device_sharing_camera)
                    icon = R.drawable.alert_camera
                }

                microphone != null -> {
                    Log.d(LOGTAG, "ExampleDeviceDelegate:onRecordingDeviceEvent display alert_mic")
                    message = getString(R.string.device_sharing_microphone)
                    icon = R.drawable.alert_mic
                }

                else -> {
                    Log.d(
                        LOGTAG,
                        "ExampleDeviceDelegate:onRecordingDeviceEvent dismiss any notifications"
                    )
                    mNotificationId?.let { notificationManager.cancel(it) }
                    mNotificationId = null
                    return
                }
            }

            if (mNotificationId == null) {
                mNotificationId = ++mLastNotificationId
            }

            val intent = Intent(mActivity, requireActivity().javaClass)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            val pendingIntent = PendingIntent.getActivity(
                mActivity.applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(mActivity.applicationContext, CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)

            notificationManager.notify(mNotificationId!!, builder.build())
        }
    }

    private inner class ExampleTranslationsSessionDelegate :
        TranslationsController.SessionTranslation.Delegate {
        override fun onOfferTranslate(session: GeckoSession) {
            Log.i(LOGTAG, "onOfferTranslate")
        }

        override fun onExpectedTranslate(session: GeckoSession) {
            Log.i(LOGTAG, "onExpectedTranslate")
            mExpectedTranslate = true
        }

        override fun onTranslationStateChange(
            session: GeckoSession,
            translationState: TranslationsController.SessionTranslation.TranslationState?
        ) {
            Log.i(LOGTAG, "onTranslationStateChange")
            translationState?.detectedLanguages?.let {
                mDetectedLanguage = it.docLangTag
            }
        }
    }

    private inner class ExampleMediaSessionDelegate(private val mActivity: Activity) :
        MediaSession.Delegate {
        override fun onFullscreen(
            session: GeckoSession,
            mediaSession: MediaSession,
            enabled: Boolean, // lint
            meta: MediaSession.ElementMetadata?
        ) {
            Log.d(LOGTAG, "onFullscreen: Metadata=${meta?.toString() ?: "null"}")
            if (!enabled) {
                mActivity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER
                return
            }
            meta ?: return

            mActivity.requestedOrientation = if (meta.width > meta.height) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
        }
    }

    private inner class ExampleActivityDelegate : GeckoView.ActivityContextDelegate {
        override fun getActivityContext(): Context {
            return requireContext()
        }
    }
}