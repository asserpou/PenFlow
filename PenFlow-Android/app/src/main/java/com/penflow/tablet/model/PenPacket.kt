package com.penflow.tablet.model

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 24-Byte Binary Packet Specification for Ultra-Low-Latency USB Digitzer Stream:
 *
 * Offset 0..1:   UInt16  magic (0x5046 -> "PF")
 * Offset 2:      UInt8   packetType (0x01: Pointer, 0x02: Ping, 0x03: Config)
 * Offset 3:      UInt8   action (0: HOVER, 1: DOWN, 2: MOVE, 3: UP, 4: CANCEL)
 * Offset 4..7:   Float32 normX (0.00000f to 1.00000f)
 * Offset 8..11:  Float32 normY (0.00000f to 1.00000f)
 * Offset 12..15: Float32 pressure (0.0f = hover, 1.0f = max)
 * Offset 16..17: Int16   tiltX (-90 to +90 degrees)
 * Offset 18..19: Int16   tiltY (-90 to +90 degrees)
 * Offset 20:     UInt8   toolType (1: Stylus, 2: Eraser, 3: Finger)
 * Offset 21:     UInt8   buttonState (Bit 0 = Barrel/Primary, Bit 1 = Secondary)
 * Offset 22..23: UInt16  reserved / sequence counter
 */
object PacketConstants {
    const val PACKET_SIZE = 24
    const val MAGIC_HEADER: Short = 0x5046.toShort() // 'P', 'F'

    // Packet Types
    const val TYPE_POINTER: Byte = 0x01
    const val TYPE_PING: Byte = 0x02
    const val TYPE_CONFIG: Byte = 0x03

    // Pointer Actions
    const val ACTION_HOVER: Byte = 0
    const val ACTION_DOWN: Byte = 1
    const val ACTION_MOVE: Byte = 2
    const val ACTION_UP: Byte = 3
    const val ACTION_CANCEL: Byte = 4

    // Tool Types
    const val TOOL_STYLUS: Byte = 1
    const val TOOL_ERASER: Byte = 2
    const val TOOL_FINGER: Byte = 3

    // Button Bitflags
    const val BUTTON_NONE: Byte = 0
    const val BUTTON_BARREL_PRIMARY: Byte = 0x01
    const val BUTTON_SECONDARY: Byte = 0x02
    const val BUTTON_FLAG_FULLSCREEN: Byte = 0x04
}

/**
 * Serializes an event directly into a target byte array buffer without allocation.
 */
class PacketSerializer {
    private val byteBuffer: ByteBuffer = ByteBuffer.allocate(PacketConstants.PACKET_SIZE).apply {
        order(ByteOrder.LITTLE_ENDIAN)
    }

    private var sequenceNumber: Int = 0

    @Synchronized
    fun serializePointer(
        action: Byte,
        normX: Float,
        normY: Float,
        pressure: Float,
        tiltX: Short,
        tiltY: Short,
        toolType: Byte,
        buttonState: Byte,
        activeWidthMm: Float = 293.76f,
        activeHeightMm: Float = 183.60f,
        outBytes: ByteArray,
        outOffset: Int = 0
    ): Int {
        val seq = sequenceNumber++
        // Encode physical activeWidthMm (in 0.1mm units, bit 15 = 0) on even packets,
        // and physical activeHeightMm (in 0.1mm units, bit 15 = 1) on odd packets into bytes 22..23!
        val dimTenthsMm = if ((seq and 1) == 0) {
            (activeWidthMm * 10f).toInt().coerceIn(50, 32000)
        } else {
            ((activeHeightMm * 10f).toInt().coerceIn(50, 32000)) or 0x8000
        }

        byteBuffer.clear()
        byteBuffer.putShort(PacketConstants.MAGIC_HEADER)
        byteBuffer.put(PacketConstants.TYPE_POINTER)
        byteBuffer.put(action)
        byteBuffer.putFloat(normX)
        byteBuffer.putFloat(normY)
        byteBuffer.putFloat(pressure)
        byteBuffer.putShort(tiltX)
        byteBuffer.putShort(tiltY)
        byteBuffer.put(toolType)
        byteBuffer.put(buttonState)
        byteBuffer.putShort(dimTenthsMm.toShort())

        System.arraycopy(byteBuffer.array(), 0, outBytes, outOffset, PacketConstants.PACKET_SIZE)
        return PacketConstants.PACKET_SIZE
    }

    @Synchronized
    fun serializePing(timestampMs: Long, outBytes: ByteArray, outOffset: Int = 0): Int {
        byteBuffer.clear()
        byteBuffer.putShort(PacketConstants.MAGIC_HEADER)
        byteBuffer.put(PacketConstants.TYPE_PING)
        byteBuffer.put(0.toByte()) // action unused
        // Pack timestamp in normX & normY (or pressure)
        byteBuffer.putLong(timestampMs) // 8 bytes (normX + normY)
        byteBuffer.putFloat(0f)         // 4 bytes pressure
        byteBuffer.putShort(0)          // tiltX
        byteBuffer.putShort(0)          // tiltY
        byteBuffer.put(0.toByte())      // toolType
        byteBuffer.put(0.toByte())      // buttonState
        byteBuffer.putShort((sequenceNumber++).toShort())

        System.arraycopy(byteBuffer.array(), 0, outBytes, outOffset, PacketConstants.PACKET_SIZE)
        return PacketConstants.PACKET_SIZE
    }
}
