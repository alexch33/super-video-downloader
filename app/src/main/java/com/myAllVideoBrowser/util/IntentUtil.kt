package com.myAllVideoBrowser.util

//import com.allVideoDownloaderXmaster.OpenForTesting

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import com.myAllVideoBrowser.R
import java.io.File
import javax.inject.Inject

//@OpenForTesting
class IntentUtil @Inject constructor(private val fileUtil: FileUtil) {

    @Deprecated("This old method is deprecated")
    fun openVideoFolder(context: Context?, path: String) {
        context?.let {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val photoURI = FileProvider.getUriForFile(
                context,
                context.applicationContext.packageName + ".provider",
                File("${context.filesDir.path}/${FileUtil.FOLDER_NAME}")
            )

            intent.setDataAndType(photoURI, DocumentsContract.Document.MIME_TYPE_DIR)

            if (intent.resolveActivity(it.packageManager) != null) {
                it.startActivity(intent)
            } else {
                Toast.makeText(
                    it,
                    it.getString(R.string.settings_message_open_folder),
                    Toast.LENGTH_SHORT
                )
                    .show()
            }
        }
    }

    fun shareVideo(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "video/*"
        val fileSupported = fileUtil.isFileApiSupportedByUri(context, uri)
        if (fileSupported) {
            val fileUri = FileProvider.getUriForFile(
                context,
                context.applicationContext.packageName + ".provider",
                uri.toFile()
            )
            intent.setDataAndType(fileUri, "video/mp4")
            intent.clipData = ClipData.newRawUri("", fileUri)
            intent.putExtra(Intent.EXTRA_STREAM, fileUri)
        } else {
            intent.clipData = ClipData.newRawUri("", uri)
            intent.putExtra(Intent.EXTRA_STREAM, uri)
        }

        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val resInfoList: List<ResolveInfo> = context.packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY
        )
        for (resolveInfo in resInfoList) {
            val packageName = resolveInfo.activityInfo.packageName
            context.grantUriPermission(
                packageName,
                uri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        if (intent.resolveActivityInfo(context.packageManager, 0) != null) {
            context.startActivity(Intent.createChooser(intent, "Share via:"))
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.video_share_message),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}