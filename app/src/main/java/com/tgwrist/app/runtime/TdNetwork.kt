package com.tgwrist.app.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import org.drinkless.tdlib.TdApi

/**
 * Сообщает TDLib о текущем типе сети (TdApi.SetNetworkType).
 *
 * Без этого TDLib не знает о смене Wi‑Fi → LTE и может часами висеть в
 * ConnectionStateConnecting на мёртвом сокете (наблюдалось на часах: вышли из
 * дома, Wi‑Fi выключился, TDLib «подключается» два часа, пока пользователь не
 * откроет приложение). Вызов SetNetworkType заставляет TDLib сбросить соединения
 * и переподключиться сразу.
 */
object TdNetwork {
    private const val TAG = "TdNetwork"
    private var registered = false

    fun currentType(context: Context): TdApi.NetworkType {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return TdApi.NetworkTypeOther()
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return TdApi.NetworkTypeNone()
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> TdApi.NetworkTypeWiFi()
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> TdApi.NetworkTypeMobile()
            else -> TdApi.NetworkTypeOther()
        }
    }

    /** Отправить TDLib актуальный тип сети (безопасно вызывать в любой момент). */
    fun push(context: Context) {
        val type = currentType(context)
        Log.d(TAG, "SetNetworkType ${type.javaClass.simpleName}")
        try {
            TgClient.send(TdApi.SetNetworkType(type)) { }
        } catch (e: Exception) {
            Log.w(TAG, "SetNetworkType failed: ${e.message}")
        }
    }

    /** Подписаться на смену сети и пробрасывать её в TDLib. */
    fun register(context: Context) {
        if (registered) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = push(context)
                override fun onLost(network: Network) = push(context)
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = push(context)
            })
            registered = true
        } catch (e: Exception) {
            Log.w(TAG, "registerDefaultNetworkCallback failed: ${e.message}")
        }
    }
}
