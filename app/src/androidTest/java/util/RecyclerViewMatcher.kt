package util

import android.content.res.Resources
import androidx.annotation.IdRes
import androidx.recyclerview.widget.RecyclerView
import android.view.View
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.TypeSafeMatcher

class RecyclerViewMatcher(val id: Int) {

    fun atPosition(position: Int): Matcher<View> {
        return atPositionOnView(position, -1)
    }

    fun atPositionOnView(position: Int, targetViewId: Int): Matcher<View> {

        return object : TypeSafeMatcher<View>() {
            var resources: Resources? = null
            var childView: View? = null

            override fun describeTo(description: Description) {
                var idDescription = Integer.toString(id)
                if (this.resources != null) {
                    idDescription = try {
                        this.resources!!.getResourceName(id)
                    } catch (var4: Resources.NotFoundException) {
                        String.format("%s (resource name not found)", id)
                    }

                }

                description.appendText(
                    "RecyclerView with id: $idDescription at position: $position"
                )
            }

            public override fun matchesSafely(view: View): Boolean {

                this.resources = view.resources

                if (childView == null) {
                    val recyclerView = view.rootView.findViewById<View>(id) as RecyclerView
                    if (recyclerView != null && recyclerView.id == id) {
                        val viewHolder = recyclerView.findViewHolderForAdapterPosition(position)
                        if (viewHolder != null) {
                            childView = viewHolder.itemView
                        }
                    } else {
                        return false
                    }
                }

                return when {
                    targetViewId == -1 -> view === childView
                    childView == null -> false
                    else -> {
                        val targetView = childView!!.findViewById<View>(targetViewId)
                        view === targetView
                    }
                }
            }
        }
    }

    companion object {
        fun recyclerViewWithId(@IdRes id: Int): RecyclerViewMatcher {
            return RecyclerViewMatcher(id)
        }
    }
}
