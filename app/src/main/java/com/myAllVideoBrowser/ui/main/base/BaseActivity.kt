package com.myAllVideoBrowser.ui.main.base

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import com.google.android.material.color.DynamicColors
import dagger.android.support.DaggerAppCompatActivity

abstract class BaseActivity : DaggerAppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
    }
}
