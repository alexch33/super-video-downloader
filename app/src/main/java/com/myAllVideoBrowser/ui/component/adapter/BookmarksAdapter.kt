package com.myAllVideoBrowser.ui.component.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.content.res.AppCompatResources
import androidx.databinding.DataBindingUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.PageInfo
import com.myAllVideoBrowser.databinding.ItemBookmarkBinding
import com.myAllVideoBrowser.util.ContextUtils
import java.util.Collections

interface BookmarksListener {
    fun onBookmarkOpenClicked(view: View, bookmarkItem: PageInfo)

    fun onBookmarkMove(bookmarks: MutableList<PageInfo>)

    fun onBookmarkDelete(bookmarks: MutableList<PageInfo>, position: Int)
}

interface ItemTouchHelperAdapter {
    fun onItemMove(fromPosition: Int, toPosition: Int)

    fun onItemDismiss(position: Int)
}

class BookmarksAdapter(
    private var bookmarksItems: MutableList<PageInfo>,
    private val bookmarksListener: BookmarksListener
) : RecyclerView.Adapter<BookmarksAdapter.BookmarkViewHolder>(), ItemTouchHelperAdapter {
    class BookmarkViewHolder(val binding: ItemBookmarkBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(bookmarkItem: PageInfo, bookmarksListener: BookmarksListener) {
            with(binding) {
                this.bookmarkItem = bookmarkItem
                this.bookmarksListener = bookmarksListener

                if (bookmarkItem.faviconBitmap() == null) {
                    val bm = AppCompatResources.getDrawable(
                        ContextUtils.getApplicationContext(), R.drawable.ic_browser
                    )

                    this.favicon.setImageDrawable(bm)
                }

                executePendingBindings()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookmarkViewHolder {
        val binding = DataBindingUtil.inflate<ItemBookmarkBinding>(
            LayoutInflater.from(parent.context), R.layout.item_bookmark, parent, false
        )

        return BookmarkViewHolder(binding)
    }

    override fun getItemCount() = bookmarksItems.size

    override fun onBindViewHolder(holder: BookmarkViewHolder, position: Int) =
        holder.bind(bookmarksItems[position], bookmarksListener)

    fun setData(bookmarksItems: MutableList<PageInfo>) {
        this.bookmarksItems = bookmarksItems
        notifyDataSetChanged()
    }

    override fun onItemMove(fromPosition: Int, toPosition: Int) {
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                Collections.swap(bookmarksItems, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                Collections.swap(bookmarksItems, i, i - 1)
            }
        }
        notifyItemMoved(fromPosition, toPosition)
        bookmarksListener.onBookmarkMove(bookmarksItems)

    }

    override fun onItemDismiss(position: Int) {
        val bookmarksClone = bookmarksItems.toMutableList()
        bookmarksItems.removeAt(position)
        notifyItemRemoved(position)
        bookmarksListener.onBookmarkDelete(bookmarksClone, position)
    }
}

class ReorderableItemTouchHelperCallback(private val adapter: ItemTouchHelperAdapter) :
    ItemTouchHelper.Callback() {

    override fun isLongPressDragEnabled(): Boolean {
        return true
    }

    override fun isItemViewSwipeEnabled(): Boolean {
        return true
    }

    override fun getMovementFlags(
        recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
    ): Int {
        val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN
        val swipeFlags = ItemTouchHelper.START or ItemTouchHelper.END
        return makeMovementFlags(dragFlags, swipeFlags)
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        adapter.onItemMove(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
        return true
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        adapter.onItemDismiss(viewHolder.bindingAdapterPosition)
    }
}
