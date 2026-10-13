package com.phoneak.dialer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class InCallActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val finishIfEnded = Runnable { if (!isFinishing) finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render()
    }

    private fun render() {
        val initialNumber = intent.getStringExtra(EXTRA_NUMBER).orEmpty()
        setContent { PhoneAKCallScreen(initialNumber = initialNumber, onClose = { finish() }) }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(finishIfEnded)
    }

    override fun onPause() {
        super.onPause()
        if (CallRepository.callState == Call.STATE_DISCONNECTED && !isFinishing) handler.postDelayed(finishIfEnded, 900L)
    }

    override fun onDestroy() {
        handler.removeCallbacks(finishIfEnded)
        super.onDestroy()
    }

    companion object { const val EXTRA_NUMBER = "phoneak.call.number" }
}

@Composable
private fun PhoneAKCallScreen(initialNumber: String, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dark = isSystemInDarkTheme()
    val green = Color(0xFF1F9D61)
    val colors = if (dark) darkColorScheme(primary = Color(0xFF55D99A), background = Color(0xFF101110), surface = Color(0xFF191B19))
                 else lightColorScheme(primary = green, background = Color(0xFFF7F7F8), surface = Color.White)
    val call = CallRepository.activeCall
    val state = CallRepository.callState
    val number = CallRepository.phoneNumber.ifBlank { initialNumber.ifBlank { "Unknown number" } }
    var showKeypad by remember { mutableStateOf(false) }
    var elapsedSeconds by remember { mutableStateOf(0) }
    val startedAt = remember(call) { System.currentTimeMillis() }

    LaunchedEffect(call, state) {
        if (state == Call.STATE_ACTIVE) {
            while (CallRepository.callState == Call.STATE_ACTIVE || CallRepository.callState == Call.STATE_HOLDING) {
                kotlinx.coroutines.delay(1000L)
                elapsedSeconds = ((System.currentTimeMillis() - startedAt) / 1000L).toInt()
            }
        }
    }

    MaterialTheme(colorScheme = colors) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Phone AK", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(38.dp))
                Box(Modifier.size(104.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    Text(number.firstOrNull()?.toString()?.uppercase() ?: "?", fontSize = 40.sp, fontWeight = FontWeight.Light)
                }
                Spacer(Modifier.height(22.dp))
                Text(number, fontSize = 25.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2)
                Spacer(Modifier.height(10.dp))
                Text(callStateLabel(state), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state == Call.STATE_ACTIVE || state == Call.STATE_HOLDING) {
                    Spacer(Modifier.height(6.dp))
                    Text(formatDuration(elapsedSeconds), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(Modifier.weight(1f))
                if (state == Call.STATE_RINGING) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        BigCallButton(label = "Decline", color = Color(0xFFE34C4C), icon = { Icon(Icons.Default.CallEnd, null) }, onClick = { CallRepository.reject(); onClose() })
                        BigCallButton(label = "Answer", color = green, icon = { Icon(Icons.Default.CallEnd, null, modifier = Modifier.size(28.dp).rotate(135f)) }, onClick = { CallRepository.answer() })
                    }
                } else if (state != Call.STATE_DISCONNECTED && state != Call.STATE_DISCONNECTING) {
                    if (showKeypad) {
                        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")
                        keys.chunked(3).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                row.forEach { key ->
                                    Box(Modifier.weight(1f).padding(vertical = 4.dp).height(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                                        androidx.compose.material3.TextButton(onClick = { CallRepository.sendDtmf(key.first()) }, modifier = Modifier.fillMaxSize()) { Text(key, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface) }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        SmallCallButton(label = if (CallRepository.muted) "Unmute" else "Mute", active = CallRepository.muted, onClick = { CallRepository.toggleMute() }, icon = { Icon(if (CallRepository.muted) Icons.Default.MicOff else Icons.Default.Mic, null) })
                        SmallCallButton(label = if (CallRepository.audioRoute == android.telecom.CallAudioState.ROUTE_SPEAKER) "Speaker on" else "Speaker", active = CallRepository.audioRoute == android.telecom.CallAudioState.ROUTE_SPEAKER, onClick = { CallRepository.toggleSpeaker() }, icon = { Icon(Icons.Default.VolumeUp, null) })
                        SmallCallButton(label = if (showKeypad) "Hide keypad" else "Keypad", active = showKeypad, onClick = { showKeypad = !showKeypad }, icon = { Icon(Icons.Default.Dialpad, null) })
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        SmallCallButton(label = if (state == Call.STATE_HOLDING) "Resume" else "Hold", active = state == Call.STATE_HOLDING, onClick = { CallRepository.toggleHold() }, icon = { Icon(Icons.Default.Pause, null) })
                        SmallCallButton(label = "Add call", active = false, onClick = {
                            if (CallRepository.callState == Call.STATE_ACTIVE) CallRepository.activeCall?.hold()
                            val dialIntent = Intent(context, MainActivity::class.java).apply {
                                action = Intent.ACTION_DIAL
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            }
                            runCatching { context.startActivity(dialIntent) }
                        }, icon = { Icon(Icons.Default.PersonAdd, null) })
                    }
                    Spacer(Modifier.height(28.dp))
                    BigCallButton(label = "End call", color = Color(0xFFE34C4C), icon = { Icon(Icons.Default.CallEnd, null) }, onClick = { CallRepository.hangUp() })
                } else {
                    Text("Call ended", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(18.dp))
                    Button(onClick = onClose) { Text("Done") }
                }
                Spacer(Modifier.height(26.dp))
            }
        }
    }
}

@Composable
private fun BigCallButton(label: String, color: Color, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(72.dp),
            colors = ButtonDefaults.filledIconButtonColors(containerColor = color, contentColor = Color.White)
        ) { icon() }
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SmallCallButton(label: String, active: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(58.dp),
            colors = ButtonDefaults.filledIconButtonColors(
                containerColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                contentColor = if (active) Color.White else MaterialTheme.colorScheme.onSurface
            )
        ) { icon() }
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 2)
    }
}

private fun callStateLabel(state: Int): String = when (state) {
    Call.STATE_NEW, Call.STATE_CONNECTING -> "Connecting…"
    Call.STATE_DIALING -> "Calling…"
    Call.STATE_RINGING -> "Incoming call"
    Call.STATE_ACTIVE -> "Connected"
    Call.STATE_HOLDING -> "On hold"
    Call.STATE_DISCONNECTING -> "Ending call…"
    Call.STATE_DISCONNECTED -> "Call ended"
    Call.STATE_SELECT_PHONE_ACCOUNT -> "Choose SIM / phone account"
    else -> "Phone call"
}

private fun formatDuration(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, secs) else String.format("%02d:%02d", minutes, secs)
}
