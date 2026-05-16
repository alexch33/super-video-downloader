package com.myAllVideoBrowser.util.proxy_utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.model.Proxy
import com.myAllVideoBrowser.ui.main.proxies.ProxiesViewModel
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.SharedPrefHelper
import com.myAllVideoBrowser.util.proxy_utils.proxy_manager.ProxyHop
import com.myAllVideoBrowser.util.proxy_utils.proxy_manager.ProxyManager
import kotlinx.coroutines.delay

class ProxyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    lateinit var sharedPrefHelper: SharedPrefHelper

    companion object {
        const val WORK_NAME = "ProxyWorker"
        const val NOTIFICATION_ID = 101
        const val CHANNEL_ID = "ProxyWorkerChannel"
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo()
    }

    override suspend fun doWork(): Result {
        AppLogger.i("ProxyWorker starting...")

        if (!::sharedPrefHelper.isInitialized) {
            AppLogger.e("ProxyWorker: sharedPrefHelper not initialized")
            return Result.failure()
        }

        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            AppLogger.e("Failed to set foreground for ProxyWorker: ${e.message}")
        }

        val useProxy = sharedPrefHelper.getIsProxyOn()
        val useDns = sharedPrefHelper.getIsDohOn()

        if (!useProxy && !useDns) {
            AppLogger.i("Proxy and DNS are off, stopping worker.")
            ProxyManager.stopLocalProxy()
            return Result.success()
        }

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
            AppLogger.i("Proxy successfully started by Worker.")
            try {
                while (!isStopped) {
                    delay(10000)
                    if (!ProxyManager.isProxyRunning()) {
                        AppLogger.w("Proxy process died, attempting to restart...")
                        return Result.retry()
                    }
                }
            } catch (e: Exception) {
                AppLogger.i("ProxyWorker loop interrupted: ${e.message}")
            } finally {
                ProxyManager.stopLocalProxy()
            }
        } else {
            AppLogger.e("Failed to start proxy from Worker.")
            return Result.failure()
        }

        return if (isStopped) Result.retry() else Result.success()
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val notification = createNotification()
        // Android 14+ (API 34+)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10 to 13 (API 29-33)
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            // Older than Android 10
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.proxy_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    applicationContext.getString(R.string.proxy_service_channel_description)
            }

            val manager =
                applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(serviceChannel)
        }

        val proxyString = applicationContext.getString(R.string.proxy)
        val dnsString = applicationContext.getString(R.string.secure_dns)
        val additionalInfo = applicationContext.getString(R.string.can_be_turned_off)

        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(proxyString)
            .setContentText("$proxyString / $dnsString : $additionalInfo")
            .setSmallIcon(R.drawable.domino_mask_24px)
            .setOngoing(true)
            .build()
    }
}
