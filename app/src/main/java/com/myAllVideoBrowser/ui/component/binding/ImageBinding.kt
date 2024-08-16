package com.myAllVideoBrowser.ui.component.binding

import androidx.databinding.BindingAdapter
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.ImageView
import com.bumptech.glide.Glide

object ImageBinding {

    @BindingAdapter("app:imageUrl")
    @JvmStatic
    fun ImageView.loadImage(url: String) {
        Glide.with(context).load(url).into(this)
    }

    @BindingAdapter("app:bitmap")
    @JvmStatic
    fun ImageView.setImageBitmap(bitmap: Bitmap?) {
        bitmap?.let { setImageBitmap(it) }
    }

    @BindingAdapter("android:src")
    @JvmStatic
    fun setImageUri(view: ImageView, imageUri: String?) {
        if (imageUri == null) {
            view.setImageURI(null)
        } else {
            view.setImageURI(Uri.parse(imageUri))
        }
    }

    @BindingAdapter("android:src")
    @JvmStatic
    fun setImageUri(view: ImageView, imageUri: Uri?) {
        view.setImageURI(imageUri)
    }

    @BindingAdapter("android:src")
    @JvmStatic
    fun setImageDrawable(view: ImageView, drawable: Drawable?) {
        view.setImageDrawable(drawable)
    }

    @BindingAdapter("android:src")
    @JvmStatic
    fun setImageResource(imageView: ImageView, resource: Int) {
        imageView.setImageResource(resource)
    }
}