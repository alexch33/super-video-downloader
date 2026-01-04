package com.myAllVideoBrowser.util.proxy_utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.model.Proxy
import com.myAllVideoBrowser.ui.main.proxies.ProxiesViewModel
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.SharedPrefHelper
import com.myAllVideoBrowser.util.proxy_utils.proxy_manager.ProxyHop
import com.myAllVideoBrowser.util.proxy_utils.proxy_manager.ProxyManager
import dagger.android.AndroidInjection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

class ProxyService : Service() {

    @Inject
    lateinit var sharedPrefHelper: SharedPrefHelper

    private var proxyJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    companion object {
        @Volatile
        var isRunning = false
            private set

        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "ProxyServiceChannel"

        const val ACTION_START_OR_UPDATE = "com.myAllVideoBrowser.services.action.START_OR_UPDATE"
        const val ACTION_STOP = "com.myAllVideoBrowser.services.action.STOP"

        const val EXTRA_PROXY_HOPS = "extra_proxy_hops"
        const val EXTRA_DNS_URL = "extra_dns_url"
    }

    override fun onCreate() {
        AndroidInjection.inject(this)
        super.onCreate()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == null) {
            handleSystemStartOrRestart()
        } else {
            when (intent.action) {
                ACTION_START_OR_UPDATE -> handleStartOrUpdate(intent)
                ACTION_STOP -> {
                    AppLogger.i("Stopping proxy service via explicit stop action.")
                    stopSelf()
                }
            }
        }

        // Ensures the service is recreated after being killed by the system.
        return START_STICKY
    }

    /**
     * Handles the case where the service is started by the system or on app launch.
     * It reads its configuration directly from SharedPreferences to be self-sufficient.
     */
    private fun handleSystemStartOrRestart() {
        proxyJob?.cancel()
        proxyJob = serviceScope.launch {
            val useProxy = sharedPrefHelper.getIsProxyOn()
            val useDns = sharedPrefHelper.getIsDohOn()

            // If both features are off according to settings, the service shouldn't be running.
            if (!useProxy && !useDns) {
                AppLogger.i("Proxy and DNS are off, stopping restarted service.")
                stopSelf()
                return@launch
            }

            startForegroundWithCorrectType()
            AppLogger.i("ProxyService (re)started by system. Initializing from SharedPreferences.")

            val proxyHops = if (useProxy) {
                sharedPrefHelper.getUserProxyChain().filter { it != Proxy.noProxy() }.map { proxy ->
                    ProxyHop(
                        type = proxy.type.name.lowercase(),
                        address = proxy.host,
                        port = proxy.port.toInt(),
                        username = proxy.user.takeIf { it.isNotBlank() },
                        password = proxy.password.takeIf { it.isNotBlank() }
                    )
                }
            } else {
                emptyList()
            }

            val dnsUrl: String? = if (useDns) {
                val providerName = sharedPrefHelper.getSelectedDnsProvider()
                val provider =
                    ProxiesViewModel.SecureDnsProvider.entries.find { it.name == providerName }
                if (provider == ProxiesViewModel.SecureDnsProvider.CUSTOM) {
                    provider.getCleanUrl(sharedPrefHelper.getCustomDnsUrl())
                } else {
                    provider?.getCleanUrl()
                }
            } else {
                null
            }

            val localCreds = sharedPrefHelper.getGeneratedCreds()

            val success = ProxyManager.startProxyChain(
                localPort = 8888,
                localUser = localCreds.localUser,
                localPass = localCreds.localPassword,
                hops = proxyHops,
                dnsUrl = dnsUrl
            )

            if (success) {
                AppLogger.i("Proxy successfully restarted from background.")
            } else {
                AppLogger.e("Failed to restart proxy from background.")
            }
        }
    }

    /**
     * Handles an explicit command from the ViewModel to start or update the proxy.
     */
    private fun handleStartOrUpdate(intent: Intent) {
        startForegroundWithCorrectType()

        val proxyHops = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(EXTRA_PROXY_HOPS, ArrayList::class.java) as? List<ProxyHop>
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(EXTRA_PROXY_HOPS) as? List<ProxyHop>
        } ?: emptyList()
        val dnsUrl = intent.getStringExtra(EXTRA_DNS_URL)

        proxyJob?.cancel()
        proxyJob = serviceScope.launch {
            val localCreds = sharedPrefHelper.getGeneratedCreds()
            AppLogger.i("Starting or updating proxy chain via Intent.")

            val success = ProxyManager.startProxyChain(
                localPort = 8888,
                localUser = localCreds.localUser,
                localPass = localCreds.localPassword,
                hops = proxyHops,
                dnsUrl = dnsUrl
            )

            if (success) {
                AppLogger.i("Local Proxy process started/updated successfully.")
            } else {
                AppLogger.e("Failed to start/update Local Proxy process.")
            }
        }
    }

    /**
     * Helper function to start the foreground service with the correct type based on Android version.
     */
    private fun startForegroundWithCorrectType() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }


    override fun onDestroy() {
        AppLogger.i("Local Proxy Stopping....")
        ProxyManager.stopLocalProxy()
        AppLogger.i("Local Proxy Stooped.")
        proxyJob?.cancel()
        super.onDestroy()
        isRunning = false
        AppLogger.i("ProxyService destroyed.")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.proxy_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Channel for the background proxy service."
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Proxy Service")
            .setContentText("Local proxy is running...")
            .build()
    }
}
