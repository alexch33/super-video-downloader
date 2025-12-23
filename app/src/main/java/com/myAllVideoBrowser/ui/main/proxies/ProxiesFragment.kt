package com.myAllVideoBrowser.ui.main.proxies

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.addCallback
import androidx.lifecycle.lifecycleScope
import com.myAllVideoBrowser.data.local.model.Proxy
import com.myAllVideoBrowser.data.local.model.ProxyType
import com.myAllVideoBrowser.databinding.FragmentProxiesBinding
import com.myAllVideoBrowser.ui.component.adapter.ProxiesAdapter
import com.myAllVideoBrowser.ui.component.adapter.ProxiesListener
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.util.proxy_utils.CustomProxyController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

class ProxiesFragment : BaseFragment() {

    companion object {
        fun newInstance() = ProxiesFragment()
    }

    @Inject
    lateinit var proxyController: CustomProxyController

    @Inject
    lateinit var mainActivity: MainActivity

    private lateinit var dataBinding: FragmentProxiesBinding

    private lateinit var proxiesViewModel: ProxiesViewModel

    private lateinit var proxiesAdapter: ProxiesAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        proxiesViewModel = mainActivity.proxiesViewModel

        proxiesAdapter = ProxiesAdapter(mutableListOf(), proxiesListener)

        dataBinding = FragmentProxiesBinding.inflate(inflater, container, false).apply {
            this.viewModel = proxiesViewModel
            this.listener = proxiesListener
            this.proxiesRecyclerView.adapter = proxiesAdapter
            proxiesAdapter.setData(proxiesViewModel.proxiesList.get()?.toList() ?: emptyList())

            this.addProxyButton.setOnClickListener {
                val host = this.hostEditText.text.toString()
                val port = this.portEditText.text.toString()
                val user = this.loginEditText.text.toString()
                val password = this.passwordEditText.text.toString()

                val selectedType = if (this.httpRadioButton.isChecked) {
                    ProxyType.HTTP
                } else {
                    ProxyType.SOCKS5
                }

                if (isValidHost(host) && isValidPort(port)) {
                    val newProxy = Proxy(
                        id = System.currentTimeMillis().toString(),
                        host = host,
                        port = port,
                        user = user,
                        password = password,
                        type = selectedType
                    )

                    proxiesViewModel.addProxy(newProxy)

                    this.hostEditText.text?.clear()
                    this.portEditText.text?.clear()
                    this.loginEditText.text?.clear()
                    this.passwordEditText.text?.clear()
                    this.httpRadioButton.isChecked = true

                } else {
                    Toast.makeText(
                        this@ProxiesFragment.context, "Invalid host or port", Toast.LENGTH_SHORT
                    ).show()
                }
            }

            val color = getThemeBackgroundColor()
            this.proxiesContainer.setBackgroundColor(color)
        }

        proxiesViewModel.proxiesList.addOnPropertyChangedCallback(object :
            androidx.databinding.Observable.OnPropertyChangedCallback() {
            override fun onPropertyChanged(
                sender: androidx.databinding.Observable?,
                propertyId: Int
            ) {
                proxiesViewModel.proxiesList.get()?.let {
                    lifecycleScope.launch(Dispatchers.Main) {
                        proxiesAdapter.setData(it)
                    }
                }
            }
        })

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            parentFragmentManager.popBackStack()
        }

        return dataBinding.root
    }

    private val proxiesListener = object : ProxiesListener {
        override fun onProxyRemoveClicked(proxy: Proxy) {
            proxiesViewModel.removeProxy(proxy)
        }

        override fun onProxyToggle(isChecked: Boolean) {
            if (isChecked) {
                proxiesViewModel.turnOnProxy()
            } else {
                proxiesViewModel.turnOffProxy()
            }
        }
    }

    private fun isValidPort(port: String): Boolean {
        return try {
            val portNumber = port.toInt()
            portNumber in 1..65535
        } catch (e: NumberFormatException) {
            false
        }
    }

    private fun isValidHost(host: String): Boolean {
        return host.isNotEmpty()
    }
}
