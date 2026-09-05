package com.ji1ore.wifi_rig_ctrl.data

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log

/**
 * BT SCO audio loopback for BT CI-V mode.
 * Captures RX audio from IC-705 via Bluetooth SCO (appears as BT mic input)
 * and plays it through the phone speaker via AudioTrack.
 */
class CivBtAudio(private val context: Context) {

    private val TAG = "CivBtAudio"
    private val audioManager = context.getSystemService(AudioManager::class.java)

    @Volatile var isActive: Boolean = false
    var lastError: String = ""

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var loopThread: Thread? = null
    private var receiverRegistered = false

    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
            Log.i(TAG, "SCO state: $state")
            when (state) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> startLoopback()
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                    if (isActive) {
                        Log.w(TAG, "SCO disconnected unexpectedly")
                        stopLoopbackInternal()
                    }
                }
            }
        }
    }

    fun start() {
        if (isActive) return
        lastError = ""
        try {
            context.registerReceiver(scoReceiver,
                IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED))
            receiverRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "registerReceiver: ${e.message}")
        }
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true
        Log.i(TAG, "startBluetoothSco requested")
    }

    fun stop() {
        isActive = false
        stopLoopbackInternal()
        audioManager.isBluetoothScoOn = false
        audioManager.stopBluetoothSco()
        if (receiverRegistered) {
            try { context.unregisterReceiver(scoReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        Log.i(TAG, "stopped")
    }

    private fun stopLoopbackInternal() {
        loopThread?.interrupt()
        loopThread = null
        audioRecord?.apply { try { stop(); release() } catch (_: Exception) {} }
        audioRecord = null
        audioTrack?.apply { try { stop(); release() } catch (_: Exception) {} }
        audioTrack = null
    }

    @SuppressLint("MissingPermission")
    private fun startLoopback() {
        if (isActive) return
        val sampleRate = 8000
        val inBufSize = maxOf(
            AudioRecord.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            1024
        )
        val outBufSize = maxOf(
            AudioTrack.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT),
            1024
        )
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, inBufSize * 4
            )
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build())
                .setBufferSizeInBytes(outBufSize * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioRecord!!.startRecording()
            audioTrack!!.play()
            isActive = true
            Log.i(TAG, "loopback started sampleRate=$sampleRate")

            loopThread = Thread({
                val buf = ByteArray(inBufSize)
                while (isActive) {
                    val n = audioRecord?.read(buf, 0, buf.size) ?: break
                    if (n > 0) audioTrack?.write(buf, 0, n)
                }
            }, "CivBtAudio-loop").also { it.start() }

        } catch (e: Exception) {
            lastError = e.message ?: "unknown"
            Log.e(TAG, "startLoopback failed: $lastError")
            stopLoopbackInternal()
        }
    }
}
