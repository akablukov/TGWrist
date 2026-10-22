package com.tgwrist.app.family

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.tgwrist.app.TGWrist
import com.tgwrist.app.runtime.TgClient
import org.drinkless.tdlib.TdApi
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ответ на команду /gps: отправляет доверенному пользователю геометку Telegram
 * (нативное сообщение-локация) и строку с точностью, источником и зарядом.
 *
 * Для работы из фона (push) нужен ACCESS_BACKGROUND_LOCATION — без него
 * getCurrentLocation из сервиса, стартовавшего из фона, возвращает null.
 */
object GeoReply {
    private const val TAG = "GeoReply"
    private const val TIMEOUT_MS = 40_000L

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    /** [onDone] вызывается, когда ответ сформирован и передан TDLib на отправку */
    fun send(toUserId: Long, onDone: () -> Unit = {}) {
        val ctx = TGWrist.context
        if (!hasPermission(ctx)) {
            sendText(toUserId, "📍 Нет разрешения на геолокацию на часах")
            onDone()
            return
        }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val done = AtomicBoolean(false)
        val cancel = CancellationSignal()

        fun deliver(loc: Location?, source: String) {
            if (!done.compareAndSet(false, true)) return
            cancel.cancel()
            try {
                if (loc == null) {
                    sendText(toUserId, "📍 Не удалось определить координаты (нет GPS/сети), заряд ${battery(ctx)}%")
                } else {
                    sendLocation(toUserId, loc)
                    val ageSec = (System.currentTimeMillis() - loc.time) / 1000
                    val txt = buildString {
                        append("📍 ±").append(loc.accuracy.toInt()).append(" м, ").append(source)
                        if (ageSec > 60) append(", данные ${ageSec / 60} мин назад")
                        append(", 🔋 ").append(battery(ctx)).append('%')
                        append("\nhttps://maps.google.com/?q=")
                            .append(String.format(java.util.Locale.US, "%.6f,%.6f", loc.latitude, loc.longitude))
                    }
                    sendText(toUserId, txt)
                }
            } finally {
                onDone()
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                    .filter { lm.isProviderEnabled(it) }
                if (providers.isEmpty()) {
                    deliver(lastKnown(lm), "последняя известная")
                    return
                }
                // Сначала быстрый ответ по сети (если есть), затем — точный GPS
                var best: Location? = null
                for (p in providers) {
                    lm.getCurrentLocation(p, cancel, executor) { loc ->
                        if (loc == null) {
                            Log.d(TAG, "getCurrentLocation($p) -> null")
                            return@getCurrentLocation
                        }
                        if (p == LocationManager.GPS_PROVIDER || loc.accuracy <= 50f) {
                            deliver(loc, if (p == LocationManager.GPS_PROVIDER) "GPS" else "сеть")
                        } else if (best == null || loc.accuracy < best!!.accuracy) {
                            best = loc
                        }
                    }
                }
                handler.postDelayed({
                    deliver(best ?: lastKnown(lm), if (best != null) "сеть" else "последняя известная")
                }, TIMEOUT_MS)
            } else {
                deliver(lastKnown(lm), "последняя известная")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission missing", e)
            deliver(null, "")
        } catch (e: Exception) {
            Log.w(TAG, "Location failed", e)
            deliver(lastKnown(lm), "последняя известная")
        }
    }

    private fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun lastKnown(lm: LocationManager): Location? = try {
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    } catch (_: Exception) { null }

    private fun battery(ctx: Context): Int =
        (ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

    private fun sendLocation(chatId: Long, loc: Location) {
        val content = TdApi.InputMessageLocation(
            TdApi.Location(loc.latitude, loc.longitude, loc.accuracy.toDouble()),
            0, 0, 0
        )
        TgClient.send(TdApi.SendMessage(chatId, null, null, null, null, content)) { r ->
            if (r is TdApi.Error) Log.w(TAG, "SendMessage(location) failed: $r")
            if (r is TdApi.Message) FamilyMode.trackSend(r.id)
        }
    }

    private fun sendText(chatId: Long, text: String) {
        val content = TdApi.InputMessageText().apply { this.text = TdApi.FormattedText(text, null) }
        TgClient.send(TdApi.SendMessage(chatId, null, null, null, null, content)) { r ->
            if (r is TdApi.Error) Log.w(TAG, "SendMessage(text) failed: $r")
            if (r is TdApi.Message) FamilyMode.trackSend(r.id)
        }
    }
}
