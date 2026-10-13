package com.phoneak.dialer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Shared observable call state used by the built-in Phone AK in-call screen. */
object CallRepository {
    var activeCall by mutableStateOf<Call?>(null)
        private set
    var callState by mutableIntStateOf(Call.STATE_NEW)
        private set
    var phoneNumber by mutableStateOf("")
        private set
    var muted by mutableStateOf(false)
        private set
    var audioRoute by mutableIntStateOf(CallAudioState.ROUTE_EARPIECE)
        private set
    var hasCall by mutableStateOf(false)
        private set
    var service: PhoneAKInCallService? = null
        internal set

    internal fun prepareOutgoing(number: String) {
        phoneNumber = number
        callState = Call.STATE_CONNECTING
        hasCall = true
        activeCall = null
    }

    internal fun clearPendingOutgoing() {
        if (activeCall == null && callState == Call.STATE_CONNECTING) {
            callState = Call.STATE_DISCONNECTED
            hasCall = false
        }
    }

    internal fun publish(calls: List<Call>, audioState: CallAudioState? = null) {
        val liveCalls = calls.filter { it.state != Call.STATE_DISCONNECTED && it.state != Call.STATE_DISCONNECTING }
        val selected = liveCalls.firstOrNull { it.state == Call.STATE_ACTIVE }
            ?: liveCalls.firstOrNull { it.state == Call.STATE_RINGING }
            ?: liveCalls.firstOrNull { it.state == Call.STATE_DIALING || it.state == Call.STATE_CONNECTING }
            ?: liveCalls.firstOrNull()
        activeCall = selected
        hasCall = selected != null
        callState = selected?.state ?: if (calls.isEmpty()) Call.STATE_DISCONNECTED else calls.last().state
        phoneNumber = selected?.details?.handle?.schemeSpecificPart.orEmpty()
        if (audioState != null) {
            muted = audioState.isMuted
            audioRoute = audioState.route
        }
    }

    fun answer() { activeCall?.answer(VideoProfile.STATE_AUDIO_ONLY) }
    fun reject() { activeCall?.reject(false, null) }
    fun hangUp() { activeCall?.disconnect() }
    fun toggleHold() {
        activeCall?.let { call ->
            if (call.state == Call.STATE_HOLDING) call.unhold() else if (call.state == Call.STATE_ACTIVE) call.hold()
        }
    }
    fun toggleMute() {
        val next = !muted
        service?.setMuted(next)
        muted = next
    }
    fun toggleSpeaker() {
        val next = if (audioRoute == CallAudioState.ROUTE_SPEAKER) CallAudioState.ROUTE_EARPIECE else CallAudioState.ROUTE_SPEAKER
        service?.setAudioRoute(next)
        audioRoute = next
    }
    fun sendDtmf(digit: Char) {
        val call = activeCall ?: return
        call.playDtmfTone(digit)
        Handler(Looper.getMainLooper()).postDelayed({ runCatching { call.stopDtmfTone() } }, 140L)
    }
}

class PhoneAKInCallService : InCallService() {
    private val trackedCalls = linkedSetOf<Call>()
    private val callbacks = mutableMapOf<Call, Call.Callback>()

    override fun onCreate() {
        super.onCreate()
        CallRepository.service = this
        createCallNotificationChannel()
    }

    override fun onDestroy() {
        callbacks.forEach { (call, callback) -> runCatching { call.unregisterCallback(callback) } }
        callbacks.clear()
        trackedCalls.clear()
        if (CallRepository.service === this) CallRepository.service = null
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        trackedCalls.add(call)
        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                publishAndNotify()
            }
            override fun onDetailsChanged(call: Call, details: Call.Details) {
                publishAndNotify()
            }
            override fun onChildrenChanged(call: Call, children: MutableList<Call>) {
                publishAndNotify()
            }
        }
        callbacks[call] = callback
        call.registerCallback(callback)
        publishAndNotify()
        if (call.state == Call.STATE_RINGING) postIncomingNotification(call)
        else postOngoingNotification(call)
    }

    override fun onCallRemoved(call: Call) {
        callbacks.remove(call)?.let { runCatching { call.unregisterCallback(it) } }
        trackedCalls.remove(call)
        publishAndNotify()
        if (trackedCalls.none { it.state != Call.STATE_DISCONNECTED && it.state != Call.STATE_DISCONNECTING }) {
            getSystemService(NotificationManager::class.java).cancel(CALL_NOTIFICATION_ID)
        }
        super.onCallRemoved(call)
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        CallRepository.publish(trackedCalls.toList(), audioState)
    }

    private fun publishAndNotify() {
        CallRepository.publish(trackedCalls.toList())
        val current = CallRepository.activeCall
        if (current != null) {
            if (current.state == Call.STATE_RINGING) postIncomingNotification(current)
            else postOngoingNotification(current)
        }
    }

    private fun createCallNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CALL_CHANNEL_ID, "Phone calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming and ongoing Phone AK calls"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun callScreenIntent(number: String): PendingIntent {
        val intent = Intent(this, InCallActivity::class.java).apply {
            putExtra(InCallActivity.EXTRA_NUMBER, number)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(this, 301, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, CallActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun postIncomingNotification(call: Call) {
        val number = call.details.handle?.schemeSpecificPart.orEmpty().ifBlank { "Incoming call" }
        val screen = callScreenIntent(number)
        val notification = Notification.Builder(this, CALL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(number)
            .setContentText("Incoming call · Phone AK")
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(screen)
            .setFullScreenIntent(screen, true)
            .addAction(Notification.Action.Builder(null, "Decline", actionIntent(CallActionReceiver.ACTION_REJECT, 302)).build())
            .addAction(Notification.Action.Builder(null, "Answer", actionIntent(CallActionReceiver.ACTION_ANSWER, 303)).build())
            .build()
        getSystemService(NotificationManager::class.java).notify(CALL_NOTIFICATION_ID, notification)
    }

    private fun postOngoingNotification(call: Call) {
        if (call.state == Call.STATE_DISCONNECTED || call.state == Call.STATE_DISCONNECTING) return
        val number = call.details.handle?.schemeSpecificPart.orEmpty().ifBlank { "Phone call" }
        val stateText = when (call.state) {
            Call.STATE_DIALING, Call.STATE_CONNECTING -> "Calling…"
            Call.STATE_ACTIVE -> "Call in progress"
            Call.STATE_HOLDING -> "Call on hold"
            Call.STATE_RINGING -> "Incoming call"
            else -> "Phone AK call"
        }
        val notification = Notification.Builder(this, CALL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_outgoing)
            .setContentTitle(number)
            .setContentText("$stateText · Tap to return to Phone AK")
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(callScreenIntent(number))
            .addAction(Notification.Action.Builder(null, "End call", actionIntent(CallActionReceiver.ACTION_HANG_UP, 304)).build())
            .build()
        getSystemService(NotificationManager::class.java).notify(CALL_NOTIFICATION_ID, notification)
    }

    companion object {
        const val CALL_CHANNEL_ID = "phone_ak_calls"
        const val CALL_NOTIFICATION_ID = 7104
    }
}

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ANSWER -> CallRepository.answer()
            ACTION_REJECT -> CallRepository.reject()
            ACTION_HANG_UP -> CallRepository.hangUp()
        }
        if (intent.action == ACTION_ANSWER || intent.action == ACTION_REJECT) {
            val number = CallRepository.phoneNumber
            val screen = Intent(context, InCallActivity::class.java).apply {
                putExtra(InCallActivity.EXTRA_NUMBER, number)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            runCatching { context.startActivity(screen) }
        }
    }
    companion object {
        const val ACTION_ANSWER = "com.phoneak.dialer.action.ANSWER"
        const val ACTION_REJECT = "com.phoneak.dialer.action.REJECT"
        const val ACTION_HANG_UP = "com.phoneak.dialer.action.HANG_UP"
    }
}

fun placePhoneAKCall(context: Context, rawNumber: String): Boolean {
    val number = rawNumber.trim()
    if (number.isBlank()) return false
    CallRepository.prepareOutgoing(number)
    return runCatching {
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
        val extras = Bundle()
        telecom.placeCall(Uri.fromParts("tel", number, null), extras)
        val screen = Intent(context, InCallActivity::class.java).apply {
            putExtra(InCallActivity.EXTRA_NUMBER, number)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(screen)
        true
    }.getOrElse {
        CallRepository.clearPendingOutgoing()
        false
    }
}
