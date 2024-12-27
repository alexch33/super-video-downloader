package com.myAllVideoBrowser.ui.main.proxies

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.addCallback
import com.myAllVideoBrowser.data.local.model.Proxy
import com.myAllVideoBrowser.databinding.FragmentProxiesBinding
import com.myAllVideoBrowser.ui.component.adapter.ProxiesListener
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.util.proxy_utils.CustomProxyController
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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        proxiesViewModel = mainActivity.proxiesViewModel

        dataBinding = FragmentProxiesBinding.inflate(inflater, container, false).apply {
            this.saveProxyButton.setOnClickListener {
                val host = this.hostEditText.text.toString()
                val port = this.portEditText.text.toString().toIntOrNull()
                val user = this.loginEditText.text.toString()
                val password = this.passwordEditText.text.toString()

                if (isValidHost(host) && isValidPort(port.toString())) {
                    val newProxy = Proxy(
                        id = host.hashCode().toString(),
                        host = host,
                        port = port.toString(),
                        user = user,
                        password = password
                    )
                    setProxy(newProxy)
                } else {
                    Toast.makeText(
                        this@ProxiesFragment.context, "Invalid host or port", Toast.LENGTH_SHORT
                    ).show()
                }
            }
            this.listener = proxiesListener
            this.viewModel = proxiesViewModel

            val color = getThemeBackgroundColor()
            this.proxiesContainer.setBackgroundColor(color)
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            parentFragmentManager.popBackStack()
        }

        return dataBinding.root
    }

    private val proxiesListener = object : ProxiesListener {
        override fun onProxyClicked(view: View, proxy: Proxy) {
            setProxy(proxy)
        }

        override fun onProxyToggle(isChecked: Boolean) {
            if (isChecked) {
                proxiesViewModel.turnOnProxy()
            } else {
                proxiesViewModel.turnOffProxy()
            }
        }
    }

    private fun setProxy(proxy: Proxy) {
        proxiesViewModel.setUserProxy(proxy)
        proxiesViewModel.setProxy(proxy)
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