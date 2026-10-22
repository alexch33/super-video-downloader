package com.myAllVideoBrowser.ui.main.home.browser.detectedVideos

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.databinding.ObservableList
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.myAllVideoBrowser.databinding.FragmentDetectionLogsBinding
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.DetectionLogger

class DetectionLogsFragment : Fragment() {

    private var _binding: FragmentDetectionLogsBinding? = null
    private val binding get() = _binding!!
    private var adapter: LogsAdapter? = null

    private class SafeLinearLayoutManager(context: Context) : LinearLayoutManager(context) {
        override fun onLayoutChildren(
            recycler: RecyclerView.Recycler?,
            state: RecyclerView.State?
        ) {
            try {
                super.onLayoutChildren(recycler, state)
            } catch (e: IndexOutOfBoundsException) {
                AppLogger.e("IndexOutOfBoundsException: ${e.message.toString()}")
            } catch (e: Exception) {
                AppLogger.e(e.message.toString())
            }
        }

        override fun supportsPredictiveItemAnimations(): Boolean = false
    }

    private val logsListener =
        object : ObservableList.OnListChangedCallback<ObservableList<String>>() {
            override fun onChanged(sender: ObservableList<String>?) = safeNotify()
            override fun onItemRangeChanged(sender: ObservableList<String>?, p: Int, c: Int) =
                safeNotify()

            override fun onItemRangeInserted(sender: ObservableList<String>?, p: Int, c: Int) =
                safeNotify(shouldScroll = true)

            override fun onItemRangeMoved(sender: ObservableList<String>?, f: Int, t: Int, c: Int) =
                safeNotify()

            override fun onItemRangeRemoved(sender: ObservableList<String>?, p: Int, c: Int) =
                safeNotify()

            private fun safeNotify(shouldScroll: Boolean = false) {
                val currentBinding = _binding ?: return

                if (!isAdded || view == null) {
                    return
                }

                val lifecycle = try {
                    viewLifecycleOwner.lifecycle
                } catch (_: Exception) {
                    null
                }
                if (lifecycle == null || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    return
                }

                val updateAction = {
                    try {
                        val currentAdapter = adapter
                        if (_binding != null && currentAdapter != null) {
                            currentAdapter.notifyDataSetChanged()

                            if (shouldScroll && currentAdapter.itemCount > 0) {
                                binding.rvLogs.scrollToPosition(currentAdapter.itemCount - 1)
                            }
                        }
                    } catch (_: Exception) {

                    }
                }

                if (currentBinding.rvLogs.isComputingLayout) {
                    currentBinding.rvLogs.post {
                        if (_binding != null && isAdded) {
                            updateAction()
                        }
                    }
                } else {
                    updateAction()
                }
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDetectionLogsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isAdded) {
                    parentFragmentManager.popBackStack()
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        binding.toolbar.setNavigationOnClickListener {
            backCallback.handleOnBackPressed()
        }

        val context = context ?: return
        val newAdapter = LogsAdapter(DetectionLogger.logs)
        adapter = newAdapter

        binding.rvLogs.apply {
            layoutManager = SafeLinearLayoutManager(context)
            this.adapter = newAdapter
            itemAnimator = null
            setHasFixedSize(true)
        }
        
        binding.btnClear.setOnClickListener {
            DetectionLogger.clear()
        }
        
        DetectionLogger.logs.addOnListChangedCallback(logsListener)

        if (newAdapter.itemCount > 0) {
            binding.rvLogs.scrollToPosition(newAdapter.itemCount - 1)
        }
    }

    override fun onDestroyView() {
        DetectionLogger.logs.removeOnListChangedCallback(logsListener)
        _binding = null
        adapter = null
        super.onDestroyView()
    }

    private class LogsAdapter(private val logs: List<String>) :
        RecyclerView.Adapter<LogsAdapter.ViewHolder>() {
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textView: TextView = view as TextView
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val tv = TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(32, 16, 32, 16)
                textSize = 14f
            }
            return ViewHolder(tv)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            try {
                if (position >= 0 && position < logs.size) {
                    holder.textView.text = logs[position]
                }
            } catch (_: Exception) {
                holder.textView.text = ""
            }
        }

        override fun getItemCount(): Int = try {
            logs.size
        } catch (_: Exception) {
            0
        }
    }

    companion object {
        fun newInstance() = DetectionLogsFragment()
    }
}
