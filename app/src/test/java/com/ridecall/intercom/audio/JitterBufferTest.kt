package com.ridecall.intercom.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JitterBufferTest {

    @Test
    fun `offer then poll returns frames in order`() {
        val buffer = JitterBuffer(targetDepthFrames = 5)
        val frame1 = shortArrayOf(1, 2, 3)
        val frame2 = shortArrayOf(4, 5, 6)
        buffer.offer(frame1)
        buffer.offer(frame2)

        assertEquals(frame1, buffer.poll(10))
        assertEquals(frame2, buffer.poll(10))
    }

    @Test
    fun `poll on empty buffer times out and returns null`() {
        val buffer = JitterBuffer(targetDepthFrames = 5)
        assertNull(buffer.poll(50))
    }

    @Test
    fun `overflow drops oldest frames to stay at target depth`() {
        val buffer = JitterBuffer(targetDepthFrames = 3)
        for (i in 0 until 6) {
            buffer.offer(shortArrayOf(i.toShort()))
        }
        assertTrue(buffer.size <= 3)
        val first = buffer.poll(10)
        assertTrue(first != null && first[0] >= 3)
    }
}
