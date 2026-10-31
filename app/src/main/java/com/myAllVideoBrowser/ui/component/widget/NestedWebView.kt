package com.myAllVideoBrowser.ui.component.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.webkit.WebView
import androidx.core.view.NestedScrollingChild3
import androidx.core.view.NestedScrollingChildHelper
import androidx.core.view.ViewCompat

class NestedWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.webViewStyle
) : WebView(context, attrs, defStyleAttr), NestedScrollingChild3 {

    private val childHelper = NestedScrollingChildHelper(this)
    private var lastY = 0
    private val scrollOffset = IntArray(2)
    private val scrollConsumed = IntArray(2)
    private var nestedOffsetY = 0

    init {
        isNestedScrollingEnabled = true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isNestedScrollingEnabled) {
            return super.onTouchEvent(event)
        }

        val action = event.actionMasked

        if (action == MotionEvent.ACTION_DOWN) {
            nestedOffsetY = 0
        }

        val eventY = event.y.toInt()
        val motionEvent = MotionEvent.obtain(event)
        motionEvent.offsetLocation(0f, nestedOffsetY.toFloat())

        try {
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    lastY = eventY
                    startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_TOUCH)
                    return super.onTouchEvent(motionEvent)
                }

                MotionEvent.ACTION_MOVE -> {
                    var deltaY = lastY - eventY

                    if (dispatchNestedPreScroll(
                            0,
                            deltaY,
                            scrollConsumed,
                            scrollOffset,
                            ViewCompat.TYPE_TOUCH
                        )
                    ) {
                        deltaY -= scrollConsumed[1]
                        motionEvent.offsetLocation(0f, scrollOffset[1].toFloat())
                        nestedOffsetY += scrollOffset[1]
                    }

                    lastY = eventY - scrollOffset[1]

                    val oldScrollY = scrollY
                    val internalCanScrollVertical =
                        canScrollVertically(1) || canScrollVertically(-1)

                    val returnValue = super.onTouchEvent(motionEvent)

                    if (internalCanScrollVertical) {
                        val scrolledBy = scrollY - oldScrollY
                        val unconsumedY = deltaY - scrolledBy

                        if (dispatchNestedScroll(
                                0,
                                scrolledBy,
                                0,
                                unconsumedY,
                                scrollOffset,
                                ViewCompat.TYPE_TOUCH
                            )
                        ) {
                            lastY -= scrollOffset[1]
                            motionEvent.offsetLocation(0f, scrollOffset[1].toFloat())
                            nestedOffsetY += scrollOffset[1]
                        }
                    }
                    return returnValue
                }

                MotionEvent.ACTION_UP -> {
                    performClick()
                    stopNestedScroll(ViewCompat.TYPE_TOUCH)
                    return super.onTouchEvent(motionEvent)
                }

                MotionEvent.ACTION_CANCEL -> {
                    stopNestedScroll(ViewCompat.TYPE_TOUCH)
                    return super.onTouchEvent(motionEvent)
                }

                else -> {
                    return super.onTouchEvent(motionEvent)
                }
            }
        } catch (_: Exception) {
            return super.onTouchEvent(event)
        } finally {
            motionEvent.recycle()
        }
    }

    override fun dispatchNestedScroll(
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        offsetInWindow: IntArray?,
        type: Int,
        consumed: IntArray
    ) {
        childHelper.dispatchNestedScroll(
            dxConsumed,
            dyConsumed,
            dxUnconsumed,
            dyUnconsumed,
            offsetInWindow,
            type,
            consumed
        )
    }

    override fun startNestedScroll(axes: Int, type: Int): Boolean {
        return childHelper.startNestedScroll(axes, type)
    }

    override fun stopNestedScroll(type: Int) {
        childHelper.stopNestedScroll(type)
    }

    override fun hasNestedScrollingParent(type: Int): Boolean {
        return childHelper.hasNestedScrollingParent(type)
    }

    override fun dispatchNestedScroll(
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        offsetInWindow: IntArray?,
        type: Int
    ): Boolean {
        return childHelper.dispatchNestedScroll(
            dxConsumed,
            dyConsumed,
            dxUnconsumed,
            dyUnconsumed,
            offsetInWindow,
            type
        )
    }

    override fun dispatchNestedPreScroll(
        dx: Int,
        dy: Int,
        consumed: IntArray?,
        offsetInWindow: IntArray?,
        type: Int
    ): Boolean {
        return childHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow, type)
    }

    override fun setNestedScrollingEnabled(enabled: Boolean) {
        childHelper.isNestedScrollingEnabled = enabled
    }

    override fun isNestedScrollingEnabled(): Boolean {
        return childHelper.isNestedScrollingEnabled
    }

    override fun startNestedScroll(axes: Int): Boolean {
        return startNestedScroll(axes, ViewCompat.TYPE_TOUCH)
    }

    override fun stopNestedScroll() {
        stopNestedScroll(ViewCompat.TYPE_TOUCH)
    }

    override fun hasNestedScrollingParent(): Boolean {
        return hasNestedScrollingParent(ViewCompat.TYPE_TOUCH)
    }

    override fun dispatchNestedScroll(
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        offsetInWindow: IntArray?
    ): Boolean {
        return dispatchNestedScroll(
            dxConsumed,
            dyConsumed,
            dxUnconsumed,
            dyUnconsumed,
            offsetInWindow,
            ViewCompat.TYPE_TOUCH
        )
    }

    override fun dispatchNestedPreScroll(
        dx: Int,
        dy: Int,
        consumed: IntArray?,
        offsetInWindow: IntArray?
    ): Boolean {
        return dispatchNestedPreScroll(
            dx,
            dy,
            consumed,
            offsetInWindow,
            ViewCompat.TYPE_TOUCH
        )
    }
}
