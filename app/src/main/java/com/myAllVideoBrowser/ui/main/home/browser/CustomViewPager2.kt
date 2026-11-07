package com.myAllVideoBrowser.ui.main.home.browser

import android.content.Context
import android.os.Parcelable
import android.util.AttributeSet
import android.util.SparseArray
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.myAllVideoBrowser.util.AppLogger

interface OnGoThroughListener {
    fun onRightGoThrough()
}

class CustomViewPager2 @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr), OnGoThroughListener {

    private val viewPager2 = ViewPager2(context, attrs)
    private var startX = 0f
    private var swipeThreshold = 300
    private var onGoThroughListener: OnGoThroughListener? = null

    init {
        addView(viewPager2)
    }

    fun setOnGoThroughListener(listener: OnGoThroughListener) {
        onGoThroughListener = listener
    }

    fun setSwipeThreshold(threshold: Int) {
        swipeThreshold = threshold
    }

    var currentItem: Int
        get() = viewPager2.currentItem
        set(value) {
            try {
                viewPager2.currentItem = value
            } catch (e: Exception) {
                AppLogger.e("Error setting viewPager2 currentItem: ${e.message}")
            }
        }

    var offscreenPageLimit: Int
        get() = viewPager2.offscreenPageLimit
        set(value) {
            try {
                viewPager2.offscreenPageLimit = value
            } catch (e: Exception) {
                AppLogger.e("Error setting viewPager2 offscreenPageLimit: ${e.message}")
            }
        }

    var adapter: RecyclerView.Adapter<*>?
        get() = viewPager2.adapter
        set(value) {
            try {
                viewPager2.adapter = value
            } catch (e: IllegalStateException) {
                AppLogger.e("Caught IllegalStateException during viewPager2.setAdapter: ${e.message}")
                clearPendingAdapterState()
                try {
                    viewPager2.adapter = value
                } catch (e2: Exception) {
                    AppLogger.e("Retry setting viewPager2 adapter failed: ${e2.message}")
                }
            } catch (e: Exception) {
                AppLogger.e("Caught Exception during viewPager2.setAdapter: ${e.message}")
                clearPendingAdapterState()
                try {
                    viewPager2.adapter = value
                } catch (e2: Exception) {
                    AppLogger.e("Retry setting viewPager2 adapter failed: ${e2.message}")
                }
            }
        }

    var isUserInputEnabled: Boolean
        get() = viewPager2.isUserInputEnabled
        set(value) {
            viewPager2.isUserInputEnabled = value
        }

    override fun dispatchRestoreInstanceState(container: SparseArray<Parcelable>?) {
        try {
            super.dispatchRestoreInstanceState(container)
        } catch (e: IllegalStateException) {
            AppLogger.e("Caught IllegalStateException during CustomViewPager2 dispatchRestoreInstanceState: ${e.message}")
            clearPendingAdapterState()
        } catch (e: Exception) {
            AppLogger.e("Caught Exception during CustomViewPager2 dispatchRestoreInstanceState: ${e.message}")
            clearPendingAdapterState()
        }
    }

    private fun clearPendingAdapterState() {
        try {
            val field = ViewPager2::class.java.getDeclaredField("mPendingAdapterState")
            field.isAccessible = true
            field.set(viewPager2, null)
        } catch (t: Throwable) {
            AppLogger.e("Failed to clear mPendingAdapterState: ${t.message}")
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        viewPager2.measure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(viewPager2.measuredWidth, viewPager2.measuredHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        viewPager2.layout(0, 0, r - l, b - t)
    }

    override fun getChildAt(index: Int): View {
        return viewPager2.getChildAt(index)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        if (viewPager2.onInterceptTouchEvent(ev)) {
            return true
        }

        when (ev?.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
            }

            MotionEvent.ACTION_MOVE -> {
                val diffX = ev.x - startX
                if (diffX < -swipeThreshold) {
                    if (viewPager2.currentItem == (viewPager2.adapter?.itemCount ?: 0) - 1) {
                        this.onRightGoThrough()
                        return true
                    }
                }
            }
        }
        return false
    }

    fun registerOnPageChangeCallback(callBack: ViewPager2.OnPageChangeCallback) {
        viewPager2.registerOnPageChangeCallback(callBack)
    }

    fun unregisterOnPageChangeCallback(callBack: ViewPager2.OnPageChangeCallback) {
        viewPager2.unregisterOnPageChangeCallback(callBack)
    }

    override fun onRightGoThrough() {
        onGoThroughListener?.onRightGoThrough()
    }
}
