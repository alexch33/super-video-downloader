package com.myAllVideoBrowser.ui.component.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View.MeasureSpec
import androidx.drawerlayout.widget.DrawerLayout

/**
 * A [DrawerLayout] that ignores non-EXACTLY measure specs to avoid crashes.
 * Some parent views like RecyclerView (used in ViewPager2) or ConstraintLayout 
 * might measure their children with AT_MOST or UNSPECIFIED during measurement passes,
 * which DrawerLayout doesn't support and throws an IllegalArgumentException.
 */
class SafeDrawerLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleInt: Int = 0
) : DrawerLayout(context, attrs, defStyleInt) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var newWidthMeasureSpec = widthMeasureSpec
        var newHeightMeasureSpec = heightMeasureSpec

        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)

        if (widthMode != MeasureSpec.EXACTLY) {
            newWidthMeasureSpec = MeasureSpec.makeMeasureSpec(
                MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.EXACTLY
            )
        }

        if (heightMode != MeasureSpec.EXACTLY) {
            newHeightMeasureSpec = MeasureSpec.makeMeasureSpec(
                MeasureSpec.getSize(heightMeasureSpec),
                MeasureSpec.EXACTLY
            )
        }

        super.onMeasure(newWidthMeasureSpec, newHeightMeasureSpec)
    }
}
