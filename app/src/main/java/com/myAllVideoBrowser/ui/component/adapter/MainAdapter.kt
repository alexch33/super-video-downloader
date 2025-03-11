package com.myAllVideoBrowser.ui.component.adapter

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.myAllVideoBrowser.util.fragment.FragmentFactory

class MainAdapter(
    fm: FragmentManager,
    lifecycle: Lifecycle,
    private val fragmentFactory: FragmentFactory
) : FragmentStateAdapter(fm, lifecycle) {

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> fragmentFactory.createBrowserFragment()
            1 -> fragmentFactory.createProgressFragment()
            2 -> fragmentFactory.createVideoFragment()
            else -> fragmentFactory.createVideoFragment()
        }
    }

    override fun getItemCount(): Int {
        return 3
    }
}