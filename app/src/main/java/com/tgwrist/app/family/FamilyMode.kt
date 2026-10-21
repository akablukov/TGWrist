package com.tgwrist.app.family

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.edit
import com.tgwrist.app.runtime.CALL_STATE_INCOMING
import com.tgwrist.app.runtime.CALL_STATE_NONE
import com.tgwrist.app.runtime.TgCallManager
import com.tgwrist.app.runtime.TgClient
import org.drinkless.tdlib.TdApi

/**
 * "Семейный режим" — функции детских часов поверх Telegram:
 *
 *  1. Автоответ: входящий звонок от доверенного пользователя принимается сам
 *     через [autoAnswerDelaySec] секунд, если ребёнок не ответил (громкая связь).
 *  2. Обратный вызов: доверенный пользователь присылает сообщение-команду
 *     ([callbackKeyword], по умолчанию "/callme") — часы сами звонят ему.
 *
 * Доверенные пользователи — список Telegram user id. Настраивается через
 * [FamilyModeReceiver] (adb) или программно через [setTrustedUserIds].
 */
object FamilyMode {
    private const val TAG = "FamilyMode"
    private const val PREF = "FamilyMode"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TRUSTED = "trusted_ids"
    private const val KEY_AUTO_ANSWER_SEC = "auto_answer_sec"
    private const val KEY_KEYWORD = "callback_keyword"

    /** Команды старше этого (в секундах) игнорируем — защита от повтора при синхронизации истории */
    private const val COMMAND_MAX_AGE_SEC = 180L

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var pendingAutoAnswer: Runnable? = null
    private var lastHandledMessageId = 0L
    private var lastCallbackAtMs = 0L

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) = prefs.edit { putBoolean(KEY_ENABLED, v) }

    var autoAnswerDelaySec: Int
        get() = prefs.getInt(KEY_AUTO_ANSWER_SEC, 12)
        set(v) = prefs.edit { putInt(KEY_AUTO_ANSWER_SEC, v) }

    var callbackKeyword: String
        get() = prefs.getString(KEY_KEYWORD, "/callme") ?: "/callme"
        set(v) = prefs.edit { putString(KEY_KEYWORD, v) }

    val trustedUserIds: Set<Long>
        get() = prefs.getString(KEY_TRUSTED, "")!!
            .split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()

    fun setTrustedUserIds(ids: Collection<Long>) {
        prefs.edit { putString(KEY_TRUSTED, ids.joinToString(",")) }
    }

    fun isTrusted(userId: Long): Boolean = userId != 0L && userId in trustedUserIds

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

        // Обратный вызов: новое сообщение от доверенного пользователя с командой
        TgClient.subscribe(TdApi.UpdateNewMessage::class.java) { update ->
            handleMessage(update.message)
        }

        // Если входящий звонок пропал (сбросили / ответили руками) — отменяем автоответ
        TgClient.subscribe(TdApi.UpdateCall::class.java) { update ->
            val s = update.call.state
            if (s is TdApi.CallStateReady || s is TdApi.CallStateHangingUp ||
                s is TdApi.CallStateDiscarded || s is TdApi.CallStateError
            ) {
                cancelAutoAnswer()
            }
        }
        Log.d(TAG, "init: enabled=$enabled trusted=${trustedUserIds} delay=${autoAnswerDelaySec}s keyword='$callbackKeyword'")
    }

    fun describe(): String =
        "enabled=$enabled trusted=$trustedUserIds autoAnswerSec=$autoAnswerDelaySec keyword='$callbackKeyword'"

    // ---------------------------------------------------------------- автоответ

    /** Вызывается из TgCallManager при переходе в CALL_STATE_INCOMING */
    fun onIncomingCall(peerUserId: Long) {
        if (!enabled || !isTrusted(peerUserId)) return
        val delay = autoAnswerDelaySec
        if (delay <= 0) return
        cancelAutoAnswer()
        Log.d(TAG, "Trusted incoming call from $peerUserId — auto-answer in ${delay}s")
        val r = Runnable {
            pendingAutoAnswer = null
            val st = TgCallManager.uiState.value
            if (st.callState == CALL_STATE_INCOMING && st.peerUserId == peerUserId) {
                Log.d(TAG, "Auto-answering call from $peerUserId")
                // Поднять экран звонка: CallForegroundService (microphone) можно
                // стартовать только когда приложение видимо.
                bringCallPageToFront()
                TgCallManager.setMute(false)
                TgCallManager.acceptCall()
            }
        }
        pendingAutoAnswer = r
        handler.postDelayed(r, delay * 1000L)
    }

    private fun bringCallPageToFront() {
        try {
            val ctx = com.tgwrist.app.TGWrist.context
            val intent = android.content.Intent(ctx, com.tgwrist.app.MainActivity::class.java).apply {
                putExtra("openCallPage", true)
                addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "bringCallPageToFront failed: ${e.message}")
        }
    }

    fun cancelAutoAnswer() {
        pendingAutoAnswer?.let { handler.removeCallbacks(it) }
        pendingAutoAnswer = null
    }

    // ------------------------------------------------------------ обратный вызов

    /** Сообщение из UpdateNewMessage (онлайн) */
    fun handleMessage(message: TdApi.Message) {
        if (message.isOutgoing) return
        val sender = (message.senderId as? TdApi.MessageSenderUser)?.userId ?: return
        val text = (message.content as? TdApi.MessageText)?.text?.text ?: return
        handleCommand(sender, text, message.date.toLong(), message.id)
    }

    /** Содержимое push-уведомления (офлайн-путь через ProcessPushNotification) */
    fun handlePushContent(senderId: TdApi.MessageSender?, content: TdApi.PushMessageContent?, dateSec: Long, messageId: Long) {
        val sender = (senderId as? TdApi.MessageSenderUser)?.userId ?: return
        val text = (content as? TdApi.PushMessageContentText)?.text ?: return
        handleCommand(sender, text, dateSec, messageId)
    }

    private fun handleCommand(senderUserId: Long, text: String, dateSec: Long, messageId: Long) {
        if (!enabled || !isTrusted(senderUserId)) return
        if (!text.trim().equals(callbackKeyword, ignoreCase = true)) return

        val nowSec = System.currentTimeMillis() / 1000
        if (dateSec > 0 && nowSec - dateSec > COMMAND_MAX_AGE_SEC) {
            Log.d(TAG, "Callback command too old (${nowSec - dateSec}s), ignoring")
            return
        }
        if (messageId != 0L && messageId == lastHandledMessageId) return
        if (System.currentTimeMillis() - lastCallbackAtMs < 15_000) return
        lastHandledMessageId = messageId
        lastCallbackAtMs = System.currentTimeMillis()

        if (TgCallManager.uiState.value.callState != CALL_STATE_NONE) {
            Log.d(TAG, "Callback requested but a call is already in progress")
            return
        }
        Log.d(TAG, "Callback command from $senderUserId — calling back")
        handler.post {
            // Через сервис входящего поднимаем полноэкранный экран (fullScreenIntent —
            // единственный надёжный способ показать Activity из фона), затем звоним.
            try {
                val ctx = com.tgwrist.app.TGWrist.context
                com.tgwrist.app.IncomingCallNotificationService.start(
                    ctx,
                    ctx.getString(com.tgwrist.app.R.string.Call),
                    callbackKeyword,
                )
            } catch (e: Exception) {
                Log.w(TAG, "IncomingCallNotificationService.start failed: ${e.message}")
            }
            bringCallPageToFront()
            handler.postDelayed({
                TgCallManager.setMute(false)
                TgCallManager.createCall(senderUserId)
            }, 1200L)
        }
    }
}
