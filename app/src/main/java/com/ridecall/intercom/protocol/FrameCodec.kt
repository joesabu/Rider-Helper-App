package com.ridecall.intercom.protocol

/**
 * RFCOMM is a raw byte stream with no message boundaries, so every frame on the wire
 * is a fixed size: 1 byte type + 2 byte sequence number + 80 bytes of ADPCM payload.
 */
object FrameCodec {
    const val TYPE_AUDIO: Byte = 0xA1.toByte()
    const val TYPE_CONTROL: Byte = 0xC1.toByte()
    const val CONTROL_HANGUP: Byte = 0x01
    const val PAYLOAD_SIZE = 80
    const val FRAME_SIZE = 1 + 2 + PAYLOAD_SIZE

    fun wrap(type: Byte, seq: Int, payload: ByteArray): ByteArray {
        require(payload.size == PAYLOAD_SIZE) { "payload must be $PAYLOAD_SIZE bytes" }
        val frame = ByteArray(FRAME_SIZE)
        frame[0] = type
        frame[1] = ((seq ushr 8) and 0xFF).toByte()
        frame[2] = (seq and 0xFF).toByte()
        System.arraycopy(payload, 0, frame, 3, PAYLOAD_SIZE)
        return frame
    }

    data class Parsed(val type: Byte, val seq: Int, val payload: ByteArray)

    fun parse(frame: ByteArray): Parsed {
        require(frame.size == FRAME_SIZE) { "frame must be $FRAME_SIZE bytes" }
        val type = frame[0]
        val seq = ((frame[1].toInt() and 0xFF) shl 8) or (frame[2].toInt() and 0xFF)
        val payload = frame.copyOfRange(3, FRAME_SIZE)
        return Parsed(type, seq, payload)
    }
}
