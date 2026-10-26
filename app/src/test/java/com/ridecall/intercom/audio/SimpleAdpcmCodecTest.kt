package com.ridecall.intercom.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class SimpleAdpcmCodecTest {

    @Test
    fun `round trip preserves frame size and roughly reconstructs a tone`() {
        val sampleRate = 8000
        val samples = ShortArray(160) { i ->
            (8000 * sin(2.0 * PI * 440.0 * i / sampleRate)).toInt().toShort()
        }

        val encoder = SimpleAdpcmCodec()
        val encoded = encoder.encode(samples)
        assertEquals(80, encoded.size)

        val decoder = SimpleAdpcmCodec()
        val decoded = decoder.decode(encoded)
        assertEquals(samples.size, decoded.size)

        var totalError = 0.0
        for (i in samples.indices) {
            totalError += abs(samples[i] - decoded[i])
        }
        val avgError = totalError / samples.size
        assertTrue("average reconstruction error too high: $avgError", avgError < 1500)
    }

    @Test
    fun `state persists across consecutive frames`() {
        val encoder = SimpleAdpcmCodec()
        val decoder = SimpleAdpcmCodec()
        val frame1 = ShortArray(160) { 1000 }
        val frame2 = ShortArray(160) { 1000 }

        val encoded1 = encoder.encode(frame1)
        val encoded2 = encoder.encode(frame2)
        val decoded1 = decoder.decode(encoded1)
        val decoded2 = decoder.decode(encoded2)

        val error1 = abs(frame1.last() - decoded1.last())
        val error2 = abs(frame2.last() - decoded2.last())
        assertTrue(error2 <= error1 + 50)
    }
}
