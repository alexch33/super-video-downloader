package com.myAllVideoBrowser.ui.main.settings.adblock

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.addCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.databinding.FragmentAdblockSettingsBinding
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.ui.main.home.MainActivity
import kotlinx.coroutines.launch
import javax.inject.Inject

class AdBlockSettingsFragment : BaseFragment() {

    companion object {
        fun newInstance() = AdBlockSettingsFragment()
    }

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    @Inject
    lateinit var mainActivity: MainActivity

    private lateinit var binding: FragmentAdblockSettingsBinding
    private lateinit var viewModel: AdBlockSettingsViewModel
    private lateinit var adapter: AdBlockListAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        viewModel = ViewModelProvider(this, viewModelFactory)[AdBlockSettingsViewModel::class.java]
        binding = FragmentAdblockSettingsBinding.inflate(inflater, container, false)
        binding.viewModel = viewModel
        binding.lifecycleOwner = viewLifecycleOwner

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupToolbar()
        observeViewModel()

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            parentFragmentManager.popBackStack()
        }
    }

    private fun setupRecyclerView() {
        adapter = AdBlockListAdapter(viewModel)
        binding.rvAdblockLists.layoutManager = LinearLayoutManager(context)
        binding.rvAdblockLists.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.adBlockLists.collect { lists ->
                    adapter.submitList(lists)
                }
            }
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }

    private fun observeViewModel() {
        viewModel.showAddDialogEvent.observe(viewLifecycleOwner) {
            showAddListDialog()
        }

        viewModel.errorEvent.observe(viewLifecycleOwner) { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAddListDialog() {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_add_adblock_list, null)
        val etName = dialogView.findViewById<EditText>(R.id.et_name)
        val etUrl = dialogView.findViewById<EditText>(R.id.et_url)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Add AdBlock List")
            .setView(dialogView)
            .setPositiveButton("Add") { _, _ ->
                val name = etName.text.toString()
                val url = etUrl.text.toString()
                viewModel.addCustomList(name, url)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
