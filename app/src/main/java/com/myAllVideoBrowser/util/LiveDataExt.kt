package com.myAllVideoBrowser.util

import androidx.lifecycle.LiveData

fun LiveData<Boolean>?.get(): Boolean = this?.value ?: false
fun LiveData<Int>?.get(): Int = this?.value ?: 0
fun <T> LiveData<T>?.get(): T? = this?.value
