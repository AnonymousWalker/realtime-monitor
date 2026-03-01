package com.realtimemonitor.wifi

import android.content.Context
import android.net.NetworkInterface
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Helper to create a Wi-Fi Direct (P2P) group when no Wi-Fi network is available.
 * This device becomes the Group Owner (GO) with a fixed IP (typically 192.168.49.1).
 * Viewer device connects to this phone's Wi-Fi Direct network, then opens the stream URL.
 */
class WifiDirectHelper(private val context: Context) {

    private val manager: WifiP2pManager? by lazy {
        context.applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }
    private val channel: WifiP2pManager.Channel? by lazy {
        manager?.initialize(context.applicationContext, Looper.getMainLooper()) { }
    }

    private var groupCreatedCallback: ((Boolean, String?) -> Unit)? = null

    /**
     * Default GO address on Android Wi-Fi Direct (p2p0 interface).
     */
    fun getDefaultGoAddress(): String = P2P_GO_IP

    /**
     * Create a P2P group (this device = Group Owner). Callback receives success and GO IP.
     */
    fun createGroup(callback: (Boolean, String?) -> Unit) {
        if (manager == null || channel == null) {
            callback(false, null)
            return
        }
        groupCreatedCallback = callback
        manager?.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Handler(Looper.getMainLooper()).postDelayed({
                    val ip = getP2pGroupOwnerAddress()
                    groupCreatedCallback?.invoke(true, ip ?: P2P_GO_IP)
                    groupCreatedCallback = null
                }, GROUP_FORMATION_DELAY_MS)
            }
            override fun onFailure(reason: Int) {
                groupCreatedCallback?.invoke(false, null)
                groupCreatedCallback = null
            }
        })
    }

    /**
     * Remove the P2P group. Call when stopping stream that used P2P.
     */
    fun removeGroup(callback: ((Boolean) -> Unit)? = null) {
        if (manager == null || channel == null) {
            callback?.invoke(false)
            return
        }
        manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                callback?.invoke(true)
            }
            override fun onFailure(reason: Int) {
                callback?.invoke(false)
            }
        })
    }

    /**
     * Get this device's IP on the P2P interface when it is the Group Owner.
     * Typically 192.168.49.1; falls back to that if interface lookup fails.
     */
    private fun getP2pGroupOwnerAddress(): String? {
        return try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return P2P_GO_IP
            val p2p = ifaces.toList().firstOrNull { it.name.equals("p2p0", ignoreCase = true) } ?: return P2P_GO_IP
            val addr = p2p.inetAddresses.toList().firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            addr?.hostAddress ?: P2P_GO_IP
        } catch (e: Exception) {
            P2P_GO_IP
        }
    }

    fun release() {}

    companion object {
        private const val P2P_GO_IP = "192.168.49.1"
        private const val GROUP_FORMATION_DELAY_MS = 1500L
    }
}
