package app.nearu.finder.logic

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

object SessionCodes {
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun generate(random: SecureRandom = SecureRandom()): String {
        return buildString(8) {
            repeat(8) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }
    }

    fun normalize(raw: String): String {
        return raw.uppercase().filter { it in ALPHABET }.take(8)
    }

    fun format(code: String): String {
        val normalized = normalize(code)
        return if (normalized.length == 8) {
            normalized.substring(0, 4) + "-" + normalized.substring(4)
        } else {
            normalized
        }
    }

    fun isComplete(raw: String): Boolean = normalize(raw).length == 8

    fun qrPayload(code: String): String = "nearu:${normalize(code)}"

    fun fromScan(raw: String): String? {
        val text = raw.trim()
        val body = if (text.startsWith("nearu:", ignoreCase = true)) text.substring(6) else text
        val code = normalize(body)
        return code.takeIf { it.length == 8 }
    }
}

object SessionIdentity {
    fun uuidForCode(code: String): UUID {
        val normalized = SessionCodes.normalize(code)
        require(normalized.length == 8) { "Session code must be 8 characters" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("nearu-session-v1:$normalized".toByteArray(Charsets.UTF_8))
        val bytes = digest.copyOfRange(0, 16)
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x50).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
        var most = 0L
        var least = 0L
        for (index in 0..7) {
            most = (most shl 8) or (bytes[index].toLong() and 0xFF)
        }
        for (index in 8..15) {
            least = (least shl 8) or (bytes[index].toLong() and 0xFF)
        }
        return UUID(most, least)
    }
}
