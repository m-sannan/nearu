package app.nearu.finder.logic

enum class ProximityState {
    SEARCHING,
    FRIEND_DETECTED,
    GETTING_CLOSER,
    GETTING_FARTHER,
    VERY_CLOSE,
    HOLDING,
    SIGNAL_LOST,
}

enum class Trend {
    UNKNOWN,
    CLOSER,
    FARTHER,
    STABLE,
}

data class Sample(val tMillis: Long, val rssi: Int)

data class ProximityReading(
    val state: ProximityState,
    val smoothed: Int?,
    val raw: Int?,
    val trend: Trend,
    val unstable: Boolean,
    val lastHeardMillisAgo: Long?,
    val packetCount: Int,
) {
    companion object {
        fun searching() = ProximityReading(
            state = ProximityState.SEARCHING,
            smoothed = null,
            raw = null,
            trend = Trend.UNKNOWN,
            unstable = false,
            lastHeardMillisAgo = null,
            packetCount = 0,
        )
    }
}

/**
 * Median of recent RSSI samples, plus a look-back trend.
 * RSSI is never converted to metres or a direction.
 * A short gap keeps the last number. A long gap clears it.
 */
class SignalProcessor(
    val medianWindow: Int = 5,
    val liveMs: Long = 2500,
    val staleMs: Long = 8000,
    val smoothingHorizonMs: Long = 6000,
    val veryCloseRssi: Int = -58,
    val veryCloseStrongRssi: Int = -42,
    val veryCloseMaxSpread: Int = 12,
    val veryCloseMinPackets: Int = 2,
    val veryClosePacketWindowMs: Long = 4000,
    val trendLookbackMs: Long = 2000,
    val trendBucketMs: Long = 10000,
    val trendDelta: Int = 4,
    val minPacketsToDetect: Int = 2,
    val unstableSpread: Int = 12,
    val historyLimit: Int = 80,
) {
    private val samples = ArrayDeque<Sample>()

    fun reset() {
        samples.clear()
    }

    fun add(sample: Sample) {
        samples.addLast(sample)
        while (samples.size > historyLimit) samples.removeFirst()
    }

    fun recent(): List<Sample> = samples.toList()

    fun evaluate(now: Long): ProximityReading {
        val newest = samples.lastOrNull() ?: return ProximityReading.searching()
        val heardAgo = now - newest.tMillis
        if (samples.size < minPacketsToDetect) {
            return if (heardAgo > staleMs) {
                ProximityReading.searching().copy(lastHeardMillisAgo = heardAgo, packetCount = samples.size)
            } else {
                ProximityReading.searching().copy(packetCount = samples.size)
            }
        }
        if (heardAgo > staleMs) {
            return ProximityReading(
                state = ProximityState.SIGNAL_LOST,
                smoothed = null,
                raw = null,
                trend = Trend.UNKNOWN,
                unstable = false,
                lastHeardMillisAgo = heardAgo,
                packetCount = samples.size,
            )
        }

        val recentWindow = samples
            .filter { now - it.tMillis in 0..smoothingHorizonMs }
            .takeLast(medianWindow)
        val window = recentWindow.ifEmpty { samples.takeLast(medianWindow) }
        val smoothed = median(window.map { it.rssi })
        val spread = window.maxOf { it.rssi } - window.minOf { it.rssi }
        val unstable = window.size >= 3 && spread > unstableSpread
        if (heardAgo > liveMs) {
            return ProximityReading(
                state = ProximityState.HOLDING,
                smoothed = smoothed,
                raw = newest.rssi,
                trend = Trend.STABLE,
                unstable = unstable,
                lastHeardMillisAgo = heardAgo,
                packetCount = samples.size,
            )
        }

        val older = samples.filter {
            val age = now - it.tMillis
            age in trendLookbackMs until (trendLookbackMs + trendBucketMs)
        }
        val trend = if (older.size >= 2) {
            val baseline = median(older.map { it.rssi })
            val diff = smoothed - baseline
            when {
                diff >= trendDelta -> Trend.CLOSER
                diff <= -trendDelta -> Trend.FARTHER
                else -> Trend.STABLE
            }
        } else {
            Trend.UNKNOWN
        }
        val recentCount = samples.count { now - it.tMillis in 0..veryClosePacketWindowMs }
        val steadyClose = smoothed >= veryCloseRssi &&
            spread <= veryCloseMaxSpread &&
            recentCount >= veryCloseMinPackets &&
            window.size >= veryCloseMinPackets
        val strongClose = smoothed >= veryCloseStrongRssi && window.size >= veryCloseMinPackets
        val state = when {
            steadyClose || strongClose -> ProximityState.VERY_CLOSE
            trend == Trend.CLOSER -> ProximityState.GETTING_CLOSER
            trend == Trend.FARTHER -> ProximityState.GETTING_FARTHER
            else -> ProximityState.FRIEND_DETECTED
        }
        return ProximityReading(
            state = state,
            smoothed = smoothed,
            raw = newest.rssi,
            trend = trend,
            unstable = unstable,
            lastHeardMillisAgo = heardAgo,
            packetCount = samples.size,
        )
    }

    private fun median(values: List<Int>): Int {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}

fun ProximityState.label(): String = when (this) {
    ProximityState.SEARCHING -> "Searching"
    ProximityState.FRIEND_DETECTED -> "Friend detected"
    ProximityState.GETTING_CLOSER -> "Getting closer"
    ProximityState.GETTING_FARTHER -> "Getting farther"
    ProximityState.VERY_CLOSE -> "Very close"
    ProximityState.HOLDING -> "Holding last reading"
    ProximityState.SIGNAL_LOST -> "Signal lost"
}
