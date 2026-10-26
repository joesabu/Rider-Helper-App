package com.ridecall.intercom.audio

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Smooths Bluetooth timing jitter between the network-reader thread (producer, decodes
 * incoming frames as they arrive) and the playback thread (consumer, paced by AudioTrack).
 * Holds decoded PCM frames at a fixed target depth: offer() drops the oldest frame before
 * adding a new one once at target depth (favors catching back up to live over completeness),
 * poll() returns null on timeout so the caller can substitute silence instead of blocking.
 */
class JitterBuffer(private val targetDepthFrames: Int = TARGET_DEPTH_FRAMES) {

    private val queue = ArrayBlockingQueue<ShortArray>(MAX_DEPTH_FRAMES)

    fun offer(frame: ShortArray) {
        while (queue.size >= targetDepthFrames) {
            queue.poll()
        }
        queue.offer(frame)
    }

    fun poll(timeoutMs: Long): ShortArray? {
        return queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun clear() {
        queue.clear()
    }

    val size: Int get() = queue.size

    companion object {
        const val TARGET_DEPTH_FRAMES = 5
        const val MAX_DEPTH_FRAMES = 10
    }
}
