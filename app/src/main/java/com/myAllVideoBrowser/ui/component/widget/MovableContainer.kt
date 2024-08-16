package com.myAllVideoBrowser.ui.component.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import kotlin.math.abs


class MovableContainer @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ConstraintLayout(context, attrs), View.OnTouchListener {
    companion object {
        private const val CLICK_DRAG_TOLERANCE =
            10f // Often, there will be a slight, unintentional, drag when the user taps the FAB, so we need to account for this.
    }

    init {
        init()
    }


    private var downRawX = 0f
    private var downRawY = 0f
    private var dX = 0f
    private var dY = 0f

    private fun init() {
        setOnTouchListener(this)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null) {
            return onTouch(this, ev)
        }

        return super.onInterceptTouchEvent(null)
    }

    override fun onTouch(view: View, motionEvent: MotionEvent): Boolean {
        val layoutParams = view.layoutParams as MarginLayoutParams
        val action = motionEvent.action
        return if (action == MotionEvent.ACTION_DOWN) {
            downRawX = motionEvent.rawX
            downRawY = motionEvent.rawY
            dX = view.x - downRawX
            dY = view.y - downRawY
            false // Consumed but sent to nested touches
        } else if (action == MotionEvent.ACTION_MOVE) {
            val viewWidth = view.width
            val viewHeight = view.height
            val viewParent = view.parent as View
            val parentWidth = viewParent.width
            val parentHeight = viewParent.height
            var newX: Float = motionEvent.rawX + dX
            newX = layoutParams.leftMargin.toFloat()
                .coerceAtLeast(newX) // Don't allow the FAB past the left hand side of the parent
            newX = (parentWidth - viewWidth - layoutParams.rightMargin).toFloat()
                .coerceAtMost(newX) // Don't allow the FAB past the right hand side of the parent
            var newY: Float = motionEvent.rawY + dY
            newY = layoutParams.topMargin.toFloat()
                .coerceAtLeast(newY) // Don't allow the FAB past the top of the parent
            newY = (parentHeight - viewHeight - layoutParams.bottomMargin).toFloat()
                .coerceAtMost(newY) // Don't allow the FAB past the bottom of the parent
            view.animate()
                .x(newX)
                .y(newY)
                .setDuration(0)
                .start()
            false // Consumed but sent to nested touches
        } else if (action == MotionEvent.ACTION_UP) {
            val upRawX = motionEvent.rawX
            val upRawY = motionEvent.rawY
            val upDX: Float = upRawX - downRawX
            val upDY: Float = upRawY - downRawY
            if (abs(upDX) < Companion.CLICK_DRAG_TOLERANCE && abs(upDY) < Companion.CLICK_DRAG_TOLERANCE) { // A click
                performClick()
            } else { // A drag
                true // Consumed
            }
        } else {
            super.onTouchEvent(motionEvent)
        }
    }
}