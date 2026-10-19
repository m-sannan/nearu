package app.nearu.finder.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class NearULogicTest {
    @Test
    fun sessionCodeIgnoresCaseSpacesAndAmbiguousCharacters() {
        assertEquals("ABCD2345", SessionCodes.normalize(" abcd-2345 "))
        assertEquals("ABCD", SessionCodes.normalize("A0B1CDOI"))
        assertEquals("ABCD-2345", SessionCodes.format("abcd2345"))
        assertTrue(SessionCodes.isComplete("abcd2345"))
        assertFalse(SessionCodes.isComplete("abcd"))
        assertEquals("ABCD2345", SessionCodes.fromScan("nearu:ABCD-2345"))
        assertEquals("ABCD2345", SessionCodes.fromScan("abcd2345"))
        assertEquals(null, SessionCodes.fromScan("nearu:short"))
        assertEquals("nearu:ABCD2345", SessionCodes.qrPayload("abcd-2345"))
    }

    @Test
    fun sameCodeAlwaysProducesTheSameUuid() {
        val first = SessionIdentity.uuidForCode("ABCD-2345")
        val second = SessionIdentity.uuidForCode("abcd2345")
        assertEquals(first, second)
        assertNotEquals(first, SessionIdentity.uuidForCode("ABCD2346"))
    }

    @Test
    fun parserReadsSessionUuidAndNonce() {
        val uuid = UUID.fromString("12345678-9abc-def0-1234-56789abcdef0")
        val parsed = AdvertisingParser.parse(packet(uuid, nonce = 7))
        assertEquals(listOf(uuid), parsed.serviceUuids)
        val payload = parsed.manufacturerPayloads.getValue(AdvertisingParser.MANUFACTURER_ID)
        assertTrue(AdvertisingParser.isOwnNonce(payload, 7.toByte()))
        assertFalse(AdvertisingParser.isOwnNonce(payload, 8.toByte()))
    }

    @Test
    fun parserIgnoresTruncatedAndEmptyPackets() {
        assertTrue(AdvertisingParser.parse(byteArrayOf()).serviceUuids.isEmpty())
        assertTrue(AdvertisingParser.parse(byteArrayOf(0x02, 0x01, 0x06)).serviceUuids.isEmpty())
        assertTrue(AdvertisingParser.parse(byteArrayOf(0x05, 0x07, 0x01, 0x02)).serviceUuids.isEmpty())
    }

    @Test
    fun noSamplesIsSearchingWithNoNumber() {
        val reading = SignalProcessor().evaluate(1_000)
        assertEquals(ProximityState.SEARCHING, reading.state)
        assertNull(reading.smoothed)
        assertNull(reading.raw)
    }

    @Test
    fun twoFreshPacketsDetectTheFriend() {
        val processor = SignalProcessor()
        processor.add(Sample(10_000, -70))
        processor.add(Sample(10_100, -69))
        val reading = processor.evaluate(10_100)
        assertEquals(ProximityState.FRIEND_DETECTED, reading.state)
        assertEquals(-69, reading.smoothed)
        assertEquals(-69, reading.raw)
    }

    @Test
    fun onePacketThenSilenceIsNotSignalLost() {
        val processor = SignalProcessor()
        processor.add(Sample(0, -60))
        val reading = processor.evaluate(3_000)
        assertEquals(ProximityState.SEARCHING, reading.state)
        assertNull(reading.smoothed)
        assertNull(reading.raw)
    }

    @Test
    fun staleReadingIsNotShownAsLive() {
        val processor = SignalProcessor()
        processor.add(Sample(0, -55))
        processor.add(Sample(100, -55))
        val reading = processor.evaluate(9_000)
        assertEquals(ProximityState.SIGNAL_LOST, reading.state)
        assertNull(reading.smoothed)
        assertNull(reading.raw)
        assertTrue((reading.lastHeardMillisAgo ?: 0) > 8_000)
    }

    @Test
    fun shortGapKeepsTheLastReading() {
        val processor = SignalProcessor()
        processor.add(Sample(0, -55))
        processor.add(Sample(100, -54))
        val reading = processor.evaluate(4_000)
        assertEquals(ProximityState.HOLDING, reading.state)
        assertEquals(-54, reading.smoothed)
        assertEquals(-54, reading.raw)
    }

    @Test
    fun strongJumpySignalIsVeryClose() {
        val processor = SignalProcessor()
        listOf(-48, -30, -44, -28).forEachIndexed { index, rssi ->
            processor.add(Sample(9_000L + index * 200, rssi))
        }
        val reading = processor.evaluate(9_600)
        assertEquals(ProximityState.VERY_CLOSE, reading.state)
    }

    @Test
    fun sustainedRiseIsGettingCloser() {
        val processor = SignalProcessor()
        listOf(7_000L to -80, 7_200L to -80, 7_400L to -79, 7_600L to -80).forEach {
            processor.add(Sample(it.first, it.second))
        }
        listOf(9_600L to -66, 9_700L to -66, 9_800L to -65, 9_900L to -66, 10_000L to -66).forEach {
            processor.add(Sample(it.first, it.second))
        }
        val reading = processor.evaluate(10_000)
        assertEquals(Trend.CLOSER, reading.trend)
        assertEquals(ProximityState.GETTING_CLOSER, reading.state)
    }

    @Test
    fun sustainedDropIsGettingFarther() {
        val processor = SignalProcessor()
        listOf(7_000L to -70, 7_200L to -70, 7_400L to -71).forEach {
            processor.add(Sample(it.first, it.second))
        }
        listOf(9_600L to -90, 9_700L to -89, 9_800L to -90, 9_900L to -91, 10_000L to -90).forEach {
            processor.add(Sample(it.first, it.second))
        }
        val reading = processor.evaluate(10_000)
        assertEquals(Trend.FARTHER, reading.trend)
        assertEquals(ProximityState.GETTING_FARTHER, reading.state)
    }

    @Test
    fun stationaryNoiseDoesNotFlipTheTrend() {
        val processor = SignalProcessor()
        var t = 7_000L
        repeat(4) {
            processor.add(Sample(t, if (it % 2 == 0) -70 else -72))
            t += 200
        }
        t = 9_600L
        repeat(5) {
            processor.add(Sample(t, if (it % 2 == 0) -71 else -73))
            t += 100
        }
        val reading = processor.evaluate(10_000)
        assertEquals(Trend.STABLE, reading.trend)
        assertEquals(ProximityState.FRIEND_DETECTED, reading.state)
    }

    @Test
    fun oneSpikeIsNotVeryClose() {
        val processor = SignalProcessor()
        listOf(-80, -80, -80, -80, -40).forEachIndexed { index, rssi ->
            processor.add(Sample(10_000L + index * 50, rssi))
        }
        val reading = processor.evaluate(10_200)
        assertNotEquals(ProximityState.VERY_CLOSE, reading.state)
    }

    @Test
    fun stableStrongSignalIsVeryClose() {
        val processor = SignalProcessor()
        listOf(-55, -56, -54, -55, -57).forEachIndexed { index, rssi ->
            processor.add(Sample(9_000L + index * 250, rssi))
        }
        val reading = processor.evaluate(10_000)
        assertEquals(ProximityState.VERY_CLOSE, reading.state)
        assertEquals(-55, reading.smoothed)
    }

    @Test
    fun resetClearsALostSession() {
        val processor = SignalProcessor()
        processor.add(Sample(0, -60))
        processor.add(Sample(100, -60))
        processor.reset()
        val reading = processor.evaluate(5_000)
        assertEquals(ProximityState.SEARCHING, reading.state)
        assertNull(reading.smoothed)
    }

    private fun packet(uuid: UUID, nonce: Int): ByteArray {
        val uuidBytes = uuidToLe(uuid)
        return byteArrayOf(0x02, 0x01, 0x06, 0x11, 0x07) +
            uuidBytes +
            byteArrayOf(
                0x05,
                0xFF.toByte(),
                (AdvertisingParser.MANUFACTURER_ID and 0xFF).toByte(),
                ((AdvertisingParser.MANUFACTURER_ID shr 8) and 0xFF).toByte(),
                AdvertisingParser.MAGIC,
                nonce.toByte(),
            )
    }

    private fun uuidToLe(uuid: UUID): ByteArray {
        val out = ByteArray(16)
        val most = uuid.mostSignificantBits
        val least = uuid.leastSignificantBits
        for (index in 0 until 8) {
            out[15 - index] = ((most ushr ((7 - index) * 8)) and 0xFF).toByte()
        }
        for (index in 0 until 8) {
            out[7 - index] = ((least ushr ((7 - index) * 8)) and 0xFF).toByte()
        }
        return out
    }
}
