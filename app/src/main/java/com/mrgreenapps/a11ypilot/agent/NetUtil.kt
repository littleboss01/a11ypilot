package com.mrgreenapps.a11ypilot.agent

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.NetworkCapabilities
import java.net.Inet4Address

object NetUtil {
    /** Returns the device's Wi-Fi IPv4 (or any non-loopback IPv4 if Wi-Fi specifically not found). */
    fun activeIpv4(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nets = cm.allNetworks
        var fallback: String? = null
        for (n in nets) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            val link = cm.getLinkProperties(n) ?: continue
            val addr = link.linkAddresses
                .firstOrNull { (it as? LinkAddress)?.address is Inet4Address }
                ?.address?.hostAddress
                ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return addr
            if (fallback == null) fallback = addr
        }
        return fallback
    }

    fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
