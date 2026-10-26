package com.ridecall.intercom.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Wraps AudioTrack for 20ms-framed, 8kHz mono low-latency playback. Deliberately kept at
 * a small internal buffer — jitter absorption happens in JitterBuffer, not here, so added
 * latency stays tunable in one place.
 */
class AudioPlayback {

    companion object {
        const val SAMPLE_RATE = AudioCapture.SAMPLE_RATE
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioTrack: AudioTrack? = null

    fun start() {
        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG)
                    .setEncoding(AUDIO_FORMAT)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        audioTrack = track
        track.play()
    }

    fun writeFrame(frame: ShortArray) {
        audioTrack?.write(frame, 0, frame.size, AudioTrack.WRITE_BLOCKING)
    }

    fun stop() {
        audioTrack?.let {
            try { it.stop() } catch (_: IllegalStateException) {}
            it.release()
        }
        audioTrack = null
    }
}
