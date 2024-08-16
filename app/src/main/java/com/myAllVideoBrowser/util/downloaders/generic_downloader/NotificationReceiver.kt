package com.myAllVideoBrowser.util.downloaders.generic_downloader

import android.Manifest
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat


class NotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_SEND_NOTIFICATION) {
            val notification = intent.getParcelableExtra<Notification>(EXTRA_NOTIFICATION)
            val id = intent.getIntExtra(EXTRA_ID, DEFAULT_ID)
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return
            }
            if (notification == null)
                Log.e(TAG, "EXTRA_NOTIFICATION ($EXTRA_NOTIFICATION) was not provided!")
            else
                NotificationManagerCompat.from(context).notify(id, notification)
        }
    }

    companion object {
        const val TAG = "NotificationReceiver"

        const val ACTION_SEND_NOTIFICATION = "intent.action.SEND_NOTIFICATION"

        const val EXTRA_NOTIFICATION = "notification_parcel"
        const val EXTRA_ID = "notification_id"
        const val DEFAULT_ID = 1010
    }

}
