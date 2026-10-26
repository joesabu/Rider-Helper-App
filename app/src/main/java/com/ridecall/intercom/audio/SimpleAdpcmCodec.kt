package com.ridecall.intercom.audio

/**
 * IMA/DVI4 ADPCM codec, 4 bits/sample (4:1 compression vs 16-bit PCM), ported from the
 * classic public-domain reference implementation (Jack Jansen, CWI, 1992). Pure Kotlin,
 * no native/NDK dependency, which keeps classic-Bluetooth SPP voice comfortably within
 * its practical ~100-230kbps throughput ceiling (ADPCM gives ~32kbps per direction vs
 * 128kbps for raw 8kHz/16-bit PCM).
 *
 * Each instance carries its own predictor/step-index state and must be used for exactly
 * one direction of exactly one continuous stream (one instance to encode outgoing audio,
 * a separate instance to decode incoming audio) — mixing directions on one instance would
 * corrupt both streams' state.
 */
class SimpleAdpcmCodec {

    private var valprev = 0
    private var index = 0

    /** Encodes an even-length PCM frame into payload.size == pcm.size / 2 bytes. */
    fun encode(pcm: ShortArray): ByteArray {
        val out = ByteArray(pcm.size / 2)
        var step = STEP_SIZE_TABLE[index]
        var valpred = valprev
        var outputBuffer = 0
        var bufferStep = true
        var outIdx = 0

        for (sampleShort in pcm) {
            val sample = sampleShort.toInt()
            var diff = sample - valpred
            var sign = 0
            if (diff < 0) {
                sign = 8
                diff = -diff
            }

            var delta = 0
            var vpdiff = step shr 3

            if (diff >= step) {
                delta = 4
                diff -= step
                vpdiff += step
            }
            step = step shr 1
            if (diff >= step) {
                delta = delta or 2
                diff -= step
                vpdiff += step
            }
            step = step shr 1
            if (diff >= step) {
                delta = delta or 1
                vpdiff += step
            }

            valpred = if (sign != 0) valpred - vpdiff else valpred + vpdiff
            valpred = valpred.coerceIn(-32768, 32767)

            delta = delta or sign
            index = (index + INDEX_TABLE[delta]).coerceIn(0, 88)
            step = STEP_SIZE_TABLE[index]

            if (bufferStep) {
                outputBuffer = (delta shl 4) and 0xF0
            } else {
                out[outIdx++] = ((delta and 0x0F) or outputBuffer).toByte()
            }
            bufferStep = !bufferStep
        }

        valprev = valpred
        return out
    }

    /** Decodes payload bytes back into bytes.size * 2 PCM samples. */
    fun decode(bytes: ByteArray): ShortArray {
        val out = ShortArray(bytes.size * 2)
        var step = STEP_SIZE_TABLE[index]
        var valpred = valprev
        var outIdx = 0

        for (b in bytes) {
            val byteVal = b.toInt() and 0xFF

            for (nibbleIdx in 0 until 2) {
                val delta = if (nibbleIdx == 0) (byteVal shr 4) and 0x0F else byteVal and 0x0F

                index = (index + INDEX_TABLE[delta]).coerceIn(0, 88)

                val sign = delta and 8
                val magnitude = delta and 7

                var vpdiff = step shr 3
                if (magnitude and 4 != 0) vpdiff += step
                step = step shr 1
                if (magnitude and 2 != 0) vpdiff += step
                step = step shr 1
                if (magnitude and 1 != 0) vpdiff += step

                valpred = if (sign != 0) valpred - vpdiff else valpred + vpdiff
                valpred = valpred.coerceIn(-32768, 32767)

                step = STEP_SIZE_TABLE[index]
                out[outIdx++] = valpred.toShort()
            }
        }

        valprev = valpred
        return out
    }

    companion object {
        private val STEP_SIZE_TABLE = intArrayOf(
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31,
            34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143,
            157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658,
            724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024,
            3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
            15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
        )
        private val INDEX_TABLE = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)
    }
}
