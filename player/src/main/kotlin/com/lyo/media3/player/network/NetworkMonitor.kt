package com.lyo.media3.player.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build

/**
 * 网络可用性监听：网络恢复后回调 onAvailable
 * （docs/android-media3-nvue-player-refactor-plan.md § 直播：网络恢复后重新播放）。
 *
 * 设计：
 * - 只关心"从无到有"的恢复事件，不重复触发。
 * - 注册 ConnectivityManager.NetworkCallback，在主线程回调。
 * - start/stop 幂等。
 */
class NetworkMonitor(
    context: Context,
    private val onAvailable: () -> Unit,
) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var lastHadNetwork = true
    private var started = false

    fun start() {
        if (started) return
        started = true
        if (cm == null) return
        // 先记录当前是否有网络，避免一开始有网就误触发 onAvailable
        lastHadNetwork = isCurrentlyAvailable()

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!lastHadNetwork) {
                    lastHadNetwork = true
                    onAvailable()
                }
            }

            override fun onLost(network: Network) {
                lastHadNetwork = false
            }
        }
        callback = cb
        try {
            cm.registerNetworkCallback(request, cb)
        } catch (_: Throwable) {
            // 某些 ROM 注册可能失败：不阻塞播放，至少允许无网络恢复重试
            started = false
        }
    }

    fun stop() {
        if (!started) return
        started = false
        val c = callback ?: return
        callback = null
        try {
            cm?.unregisterNetworkCallback(c)
        } catch (_: Throwable) {}
    }

    private fun isCurrentlyAvailable(): Boolean {
        if (cm == null) return true
        return try {
            val active = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(active) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        } catch (_: Throwable) {
            true
        }
    }
}
