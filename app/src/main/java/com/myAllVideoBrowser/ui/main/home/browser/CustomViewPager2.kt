package com.myAllVideoBrowser.ui.main.home.browser

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

interface OnGoThroughListener {
    fun onRightGoThrough()
}

class CustomViewPager2(context: Context, attrs: AttributeSet?) : ViewGroup(context, attrs),
    OnGoThroughListener {
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
            viewPager2.currentItem = value
        }

    var offscreenPageLimit: Int
        get() = viewPager2.offscreenPageLimit
        set(value) {
            viewPager2.offscreenPageLimit = value
        }

    var adapter: RecyclerView.Adapter<*>?
        get() = viewPager2.adapter
        set(value) {
            viewPager2.setAdapter(value)
        }

    var isUserInputEnabled: Boolean
        get() = viewPager2.isUserInputEnabled
        set(value) {
            viewPager2.isUserInputEnabled = value
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        viewPager2.measure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(viewPager2.measuredWidth, viewPager2.measuredHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        viewPager2.layout(l, t, r, b)
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