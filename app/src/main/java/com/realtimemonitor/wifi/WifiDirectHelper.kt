package com.realtimemonitor.wifi

import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.NetworkInterface as JavaNetworkInterface

/**
 * Helper to create a Wi-Fi Direct (P2P) group when no Wi-Fi network is available.
 * This device becomes the Group Owner (GO) with a fixed IP (typically 192.168.49.1).
 * Viewer device connects to this phone's Wi-Fi Direct network (using the shown password), then opens the stream URL.
 */
class WifiDirectHelper(private val context: Context) {

    private val manager: WifiP2pManager? by lazy {
        context.applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }
    private val channel: WifiP2pManager.Channel? by lazy {
        manager?.initialize(context.applicationContext, Looper.getMainLooper()) { }
    }

    private var groupCreatedCallback: ((Boolean, String?, String?) -> Unit)? = null

    /**
     * Default GO address on Android Wi-Fi Direct (p2p0 interface).
     */
    fun getDefaultGoAddress(): String = P2P_GO_IP

    /**
     * Create a P2P group (this device = Group Owner). Callback receives success, GO IP, and passphrase (for client to connect).
     */
    fun createGroup(callback: (Boolean, String?, String?) -> Unit) {
        if (manager == null || channel == null) {
            callback(false, null, null)
            return
        }
        groupCreatedCallback = callback
        val config = buildGroupConfig()
        val ch = channel ?: run {
            callback(false, null, null)
            return
        }
        manager?.createGroup(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Handler(Looper.getMainLooper()).postDelayed({
                    fetchGroupInfoAndNotify(ch, config)
                }, GROUP_FORMATION_DELAY_MS)
            }
            override fun onFailure(reason: Int) {
                groupCreatedCallback?.invoke(false, null, null)
                groupCreatedCallback = null
            }
        })
    }

    private fun buildGroupConfig(): WifiP2pConfig {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiP2pConfig.Builder().apply {
                setNetworkName(P2P_NETWORK_NAME)
                setPassphrase(P2P_PASSPHRASE)
            }.build()
        } else {
            @Suppress("DEPRECATION")
            WifiP2pConfig().apply {
                groupOwnerIntent = WifiP2pConfig.GROUP_OWNER_INTENT_MAX
            }
        }
    }

    private fun fetchGroupInfoAndNotify(ch: WifiP2pManager.Channel, configWithPassphrase: WifiP2pConfig?) {
        manager?.requestGroupInfo(ch) { group: WifiP2pGroup? ->
            val ip = getP2pGroupOwnerAddress()
            val passphrase = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && configWithPassphrase != null -> P2P_PASSPHRASE
                group?.passphrase?.isNotEmpty() == true -> group.passphrase
                else -> null
            }
            groupCreatedCallback?.invoke(true, ip ?: P2P_GO_IP, passphrase)
            groupCreatedCallback = null
        } ?: run {
            val ip = getP2pGroupOwnerAddress()
            val passphrase = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && configWithPassphrase != null) P2P_PASSPHRASE else null
            groupCreatedCallback?.invoke(true, ip ?: P2P_GO_IP, passphrase)
            groupCreatedCallback = null
        }
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
            val ifaces = JavaNetworkInterface.getNetworkInterfaces() ?: return P2P_GO_IP
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
        /** Network name and passphrase shown to user so client can connect to Wi‑Fi Direct. */
        /** Must start with DIRECT-xy per platform requirement (e.g. Android 12). */
        const val P2P_NETWORK_NAME = "DIRECT-xy-RealtimeMonitor"
        const val P2P_PASSPHRASE = "realtime"
    }
}
