package com.ridecall.intercom.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor

/**
 * Wraps AudioRecord for 20ms-framed, 8kHz mono mic capture. Uses VOICE_COMMUNICATION
 * source so the platform's AEC/NoiseSuppressor effects can attach correctly (needed in
 * case the phone is used handsfree via its own speaker/mic rather than a headset).
 */
class AudioCapture {

    companion object {
        const val SAMPLE_RATE = 8000
        const val SAMPLES_PER_FRAME = 160 // 20ms @ 8kHz
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    @SuppressLint("MissingPermission")
    fun start() {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = (minBuffer * 2).coerceAtLeast(SAMPLES_PER_FRAME * 2 * 4)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord failed to initialize")
        }
        audioRecord = record

        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = AcousticEchoCanceler.create(record.audioSessionId)?.also { it.enabled = true }
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(record.audioSessionId)?.also { it.enabled = true }
        }

        record.startRecording()
    }

    /** Blocking read of exactly one 20ms frame (160 samples), or null if capture has stopped. */
    fun readFrame(): ShortArray? {
        val record = audioRecord ?: return null
        val frame = ShortArray(SAMPLES_PER_FRAME)
        var offset = 0
        while (offset < frame.size) {
            val read = record.read(frame, offset, frame.size - offset)
            if (read <= 0) return null
            offset += read
        }
        return frame
    }

    fun stop() {
        echoCanceler?.release()
        echoCanceler = null
        noiseSuppressor?.release()
        noiseSuppressor = null
        audioRecord?.let {
            try { it.stop() } catch (_: IllegalStateException) {}
            it.release()
        }
        audioRecord = null
    }
}
