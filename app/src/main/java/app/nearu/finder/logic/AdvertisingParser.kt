package app.nearu.finder.logic

import java.util.UUID

/**
 * Reads BLE advertising bytes. Android's parsed service-UUID list is unreliable
 * unless a hardware scan filter was set, so NearU matches the session itself.
 */
object AdvertisingParser {
    /** Prototype company id. Not a Bluetooth SIG registration. Used only as a per-launch nonce. */
    const val MANUFACTURER_ID: Int = 0x4E52
    const val MAGIC: Byte = 0x01

    data class Parsed(
        val serviceUuids: List<UUID>,
        val manufacturerPayloads: Map<Int, ByteArray>,
    )

    fun parse(bytes: ByteArray): Parsed {
        val uuids = mutableListOf<UUID>()
        val payloads = mutableMapOf<Int, ByteArray>()
        var index = 0
        while (index < bytes.size) {
            val length = bytes[index].toInt() and 0xFF
            if (length == 0) break
            if (index + length >= bytes.size) break
            val type = bytes[index + 1].toInt() and 0xFF
            val start = index + 2
            val end = index + 1 + length
            when (type) {
                0x06, 0x07 -> {
                    var cursor = start
                    while (cursor + 16 <= end) {
                        uuids.add(readUuidLe(bytes, cursor))
                        cursor += 16
                    }
                }
                0xFF -> {
                    if (end - start >= 2) {
                        val company = (bytes[start].toInt() and 0xFF) or
                            ((bytes[start + 1].toInt() and 0xFF) shl 8)
                        payloads[company] = bytes.copyOfRange(start + 2, end)
                    }
                }
            }
            index += length + 1
        }
        return Parsed(uuids, payloads)
    }

    fun readUuidLe(bytes: ByteArray, offset: Int): UUID {
        var most = 0L
        var least = 0L
        for (index in 0 until 8) {
            most = (most shl 8) or (bytes[offset + 15 - index].toLong() and 0xFF)
        }
        for (index in 0 until 8) {
            least = (least shl 8) or (bytes[offset + 7 - index].toLong() and 0xFF)
        }
        return UUID(most, least)
    }

    fun isOwnNonce(payload: ByteArray?, nonce: Byte): Boolean {
        return payload != null &&
            payload.size >= 2 &&
            payload[0] == MAGIC &&
            payload[1] == nonce
    }
}
