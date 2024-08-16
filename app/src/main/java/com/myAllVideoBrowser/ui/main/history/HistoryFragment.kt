package com.myAllVideoBrowser.ui.main.history

import androidx.lifecycle.ViewModelProvider
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.LinearLayoutManager
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.databinding.FragmentHistoryBinding
import com.myAllVideoBrowser.ui.component.adapter.HistoryAdapter
import com.myAllVideoBrowser.ui.component.adapter.HistoryListener
import com.myAllVideoBrowser.ui.component.adapter.HistorySearchAdapter
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.ui.main.home.MainViewModel
import com.myAllVideoBrowser.ui.main.home.browser.BrowserViewModel
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTab
import com.myAllVideoBrowser.ui.main.progress.WrapContentLinearLayoutManager
import com.myAllVideoBrowser.util.AppLogger
import javax.inject.Inject

class HistoryFragment : BaseFragment() {

    companion object {
        fun newInstance() = HistoryFragment()
    }

    private lateinit var mainViewModel: MainViewModel

    private lateinit var historyModel: HistoryViewModel

    private lateinit var dataBinding: FragmentHistoryBinding

    private lateinit var historyAdapter: HistoryAdapter

    private lateinit var searchHistoryAdapter: HistorySearchAdapter

    @Inject
    lateinit var mainActivity: MainActivity

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        mainViewModel = mainActivity.mainViewModel

        historyModel =
            ViewModelProvider(this, viewModelFactory)[HistoryViewModel::class.java]

        historyAdapter = HistoryAdapter(emptyList(), historyListener)
        searchHistoryAdapter = HistorySearchAdapter(emptyList(), searchHistoryListener)
        val color = getThemeBackgroundColor()

        dataBinding = FragmentHistoryBinding.inflate(inflater, container, false).apply {
            this.historyContainer.setBackgroundColor(color)
            val historyManagerLayout =
                WrapContentLinearLayoutManager(context, LinearLayoutManager.VERTICAL, true)
            historyManagerLayout.stackFromEnd = true

            val searchHistoryManagerLayout =
                WrapContentLinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)

            this.viewModel = historyModel
            this.historyList.layoutManager = historyManagerLayout
            this.historyList.adapter = historyAdapter

            this.historySearchList.layoutManager = searchHistoryManagerLayout
            this.historySearchList.adapter = searchHistoryAdapter

            this.searchHistoryView.editText.addTextChangedListener(searchTextChangeListener)
            this.clearButton.setOnClickListener {
                historyModel.clearHistory()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            parentFragmentManager.popBackStack()
        }
        return dataBinding.root
    }

    private val historyListener = object : HistoryListener {
        override fun onHistoryOpenClicked(view: View, id: String) {
            AppLogger.d("onHistoryOpenClicked: $id")
            historyModel.historyItems.get()?.find {
                it.id == id
            }.let {
                it?.let { item ->
                    parentFragmentManager.popBackStack()
                    BrowserViewModel.instance?.openPageEvent?.value =
                        WebTab(item.url, item.title, item.faviconBitmap())
                }
            }
        }

        override fun onHistoryDeleteClicked(view: View, id: String) {
        }

        override fun onMenuClicked(view: View, id: String) {
            showPopupMenu(view, id)
        }

        override fun onAllHistoryDeleteClicked() {
        }
    }

    private val searchHistoryListener = object : HistoryListener {
        override fun onHistoryOpenClicked(view: View, id: String) {
            AppLogger.d("SEARCH: onHistoryOpenClicked  $id")

            // TODO duplicate code from historyListener
            historyModel.historyItems.get()?.find {
                it.id == id
            }.let {
                it?.let { item ->
                    parentFragmentManager.popBackStack()
                    BrowserViewModel.instance?.openPageEvent?.value =
                        WebTab(item.url, item.title, item.faviconBitmap())
                }
            }
        }

        override fun onHistoryDeleteClicked(view: View, id: String) {
        }

        override fun onMenuClicked(view: View, id: String) {

        }

        override fun onAllHistoryDeleteClicked() {
        }

    }

    private val searchTextChangeListener = object : TextWatcher {
        override fun beforeTextChanged(
            s: CharSequence?,
            start: Int,
            count: Int,
            after: Int
        ) {

        }

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            historyModel.queryHistory(s.toString())
        }

        override fun afterTextChanged(s: Editable?) {

        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        historyModel.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        historyModel.stop()
    }

    private fun showPopupMenu(view: View, historyId: String) {
        val popupMenu = PopupMenu(view.context, view)
        popupMenu.menuInflater.inflate(R.menu.menu_history, popupMenu.menu)
        popupMenu.setForceShowIcon(true)
        popupMenu.show()

        popupMenu.setOnMenuItemClickListener { arg0 ->
            when (arg0.itemId) {
                R.id.item_remove -> {
                    historyModel.historyItems.get()?.find { it.id == historyId }
                        ?.let { historyModel.deleteHistory(it) }
                    true
                }

                else -> false
            }
        }
    }
}