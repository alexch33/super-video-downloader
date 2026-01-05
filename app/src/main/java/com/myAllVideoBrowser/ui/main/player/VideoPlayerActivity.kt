package com.myAllVideoBrowser.ui.main.player

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.Window
import androidx.core.content.ContextCompat
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.ui.main.base.BaseActivity

class VideoPlayerActivity : BaseActivity() {

    @SuppressLint("UnsafeOptInUsageError")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)

        setContentView(R.layout.activity_player)

        if (savedInstanceState == null) {
            startPlaybackService()

            supportFragmentManager.beginTransaction()
                .replace(R.id.player_content_frame, VideoPlayerFragment().apply {
                    arguments = intent.extras
                })
                .commit()
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun startPlaybackService() {
        val serviceIntent = Intent(this, PlaybackService::class.java).apply {
            if (intent.extras != null) {
                putExtras(intent.extras!!)
            }
        }
        try {
            ContextCompat.startForegroundService(this, serviceIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
