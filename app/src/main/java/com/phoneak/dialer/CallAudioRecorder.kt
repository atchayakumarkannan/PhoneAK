package com.phoneak.dialer

import android.content.Context
import android.media.MediaRecorder
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Best-effort microphone recording only. Android's public SDK does not grant a
 * normal dialer direct access to both sides of a cellular call's audio stream.
 */
class CallAudioRecorder(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null

    var isRecording by mutableStateOf(false)
        private set
    var statusMessage by mutableStateOf("")
    var lastSavedPath by mutableStateOf("")
        private set

    fun start(): Boolean {
        if (isRecording) return true
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        val folder = File(baseDir, "PhoneAK/CallRecordings")
        if (!folder.exists() && !folder.mkdirs()) {
            statusMessage = "Couldn't create the recordings folder."
            return false
        }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(folder, "PhoneAK_$stamp.m4a")
        var recorder: MediaRecorder? = null
        return try {
            recorder = MediaRecorder()
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(96000)
            recorder.setAudioSamplingRate(44100)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()
            mediaRecorder = recorder
            outputFile = file
            isRecording = true
            statusMessage = "Recording microphone audio. Speakerphone may help, but the other side is not guaranteed."
            true
        } catch (e: Exception) {
            runCatching { recorder?.reset() }
            runCatching { recorder?.release() }
            runCatching { file.delete() }
            mediaRecorder = null
            outputFile = null
            isRecording = false
            statusMessage = "Recording couldn't start on this device during the call."
            false
        }
    }

    fun stop(): String? {
        if (!isRecording) return null
        val file = outputFile
        val recorder = mediaRecorder
        return try {
            recorder?.stop()
            file?.takeIf { it.exists() && it.length() > 0L }?.let {
                lastSavedPath = it.absolutePath
                statusMessage = "Recording saved in Phone AK's private app storage."
                it.absolutePath
            } ?: run {
                statusMessage = "No usable audio file was produced."
                null
            }
        } catch (e: Exception) {
            file?.delete()
            statusMessage = "Recording ended, but the audio file was invalid or empty."
            null
        } finally {
            runCatching { recorder?.reset() }
            runCatching { recorder?.release() }
            mediaRecorder = null
            outputFile = null
            isRecording = false
        }
    }
}
