package com.bydassistantng.drive

import com.bydassistantng.vehicle.StatusFacts

/** One look at the car while it is on: wall-clock ms and what the car reported. */
data class DriveSample(val atMs: Long, val facts: StatusFacts)

/**
 * The last [keepMs] of samples, oldest first, in memory only: a drive is what happened since the car woke, and nothing
 * of it is stored or sent anywhere except into the driver's own question.
 */
class DriveLog(private val keepMs: Long = KEEP_MS) {
    private val samples = ArrayDeque<DriveSample>()

    @Synchronized
    fun add(sample: DriveSample) {
        // A clock that went back (a time sync on wake) would make every duration wrong: start afresh rather than mix them.
        if (samples.isNotEmpty() && sample.atMs < samples.last().atMs) samples.clear()
        samples.addLast(sample)
        while (samples.isNotEmpty() && sample.atMs - samples.first().atMs > keepMs) samples.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<DriveSample> = samples.toList()

    @Synchronized
    fun clear() = samples.clear()

    companion object {
        const val KEEP_MS = 50 * 60_000L
    }
}

/**
 * What the last few minutes of driving looked like, worked out from the samples. Every figure is measured or derived from
 * what the car reported; one that cannot be worked out is null. Rounded for speaking.
 */
data class DriveReport(
    val windowMinutes: Int,
    /** How much of the window the samples actually span (the car may have woken more recently). */
    val coveredMinutes: Double,
    val samples: Int,
    val distanceKm: Double?,
    /** "odometer" when measured by the car, "speed" when added up from the speed samples. */
    val distanceFrom: String?,
    val avgMovingSpeedKmh: Double?,
    val maxSpeedKmh: Double?,
    /** Share of the time standing still (0..1). */
    val stoppedShare: Double?,
    val stops: Int?,
    /** Average change of speed between samples, km/h per minute: high = stop-and-go or hard acceleration and braking. */
    val speedChangePerMin: Double?,
    val batteryUsedPercent: Double?,
    val electricRangeUsedKm: Double?,
    val fuelRangeUsedKm: Double?,
    /** Range used per km actually driven: 1.0 is what the car predicted; above it the drive used more than estimated. */
    val rangeUsedPerKm: Double?,
    val batteryPercentPer10Km: Double?,
    /** Share of the time the A/C was on (0..1). */
    val acOnShare: Double?,
    val insideCelsius: Double?,
    val outsideCelsius: Double?,
    /** False when there is too little to judge (a few minutes at most): the model is told so instead of reading noise. */
    val enoughData: Boolean,
)

/** Song PLUS edition: turns the drive samples into a report the model can coach from. Pure, unit-tested. */
object DriveAnalysis {
    const val MOVING_KMH = 3.0
    const val MIN_COVERED_MINUTES = 3.0
    const val MIN_SAMPLES = 3

    // Two samples further apart than this (the car slept, the app restarted) are not joined into one stretch of driving.
    private const val MAX_GAP_MS = 4 * 60_000L

    fun analyse(all: List<DriveSample>, windowMinutes: Int, nowMs: Long): DriveReport {
        val from = nowMs - windowMinutes * 60_000L
        val s = all.filter { it.atMs in from..nowMs }.sortedBy { it.atMs }
        val covered = if (s.size < 2) 0.0 else (s.last().atMs - s.first().atMs) / 60_000.0
        val pairs = s.zipWithNext().filter { (a, b) -> b.atMs - a.atMs in 1..MAX_GAP_MS }

        val speeds = s.mapNotNull { it.facts.speedKmh }
        val moving = speeds.filter { it >= MOVING_KMH }

        val odoFirst = s.firstNotNullOfOrNull { it.facts.odometerKm }
        val odoLast = s.lastOrNull { it.facts.odometerKm != null }?.facts?.odometerKm
        val odoDistance = if (odoFirst != null && odoLast != null && odoLast >= odoFirst && odoLast - odoFirst < 500) odoLast - odoFirst else null
        val speedDistance = pairs.mapNotNull { (a, b) ->
            val va = a.facts.speedKmh
            val vb = b.facts.speedKmh
            if (va == null || vb == null) null else (va + vb) / 2.0 * (b.atMs - a.atMs) / 3_600_000.0
        }.takeIf { it.isNotEmpty() }?.sum()
        // The odometer counts whole kilometres on many cars: on a short stretch the speed samples say more.
        val (distance, distanceFrom) = when {
            odoDistance != null && odoDistance >= 2.0 -> odoDistance to "odometer"
            speedDistance != null -> speedDistance to "speed"
            odoDistance != null -> odoDistance to "odometer"
            else -> null to null
        }

        val stops = if (speeds.size < 2) null else speeds.zipWithNext().count { (a, b) -> a >= MOVING_KMH && b < MOVING_KMH }
        val speedChange = pairs.mapNotNull { (a, b) ->
            val va = a.facts.speedKmh
            val vb = b.facts.speedKmh
            if (va == null || vb == null) null else Math.abs(vb - va) / ((b.atMs - a.atMs) / 60_000.0)
        }.takeIf { it.isNotEmpty() }?.average()

        fun used(pick: (StatusFacts) -> Double?): Double? {
            val first = s.firstNotNullOfOrNull { pick(it.facts) } ?: return null
            val last = s.lastOrNull { pick(it.facts) != null }?.let { pick(it.facts) } ?: return null
            return first - last
        }
        val battery = used { it.batteryPercent }
        val electric = used { it.electricRangeKm }
        val fuel = used { it.fuelRangeKm }
        val drove = distance?.takeIf { it >= 1.0 }
        val rangeUsed = if (drove == null || (electric == null && fuel == null)) null else ((electric ?: 0.0) + (fuel ?: 0.0)) / drove
        val acKnown = s.mapNotNull { it.facts.acOn }

        return DriveReport(
            windowMinutes = windowMinutes,
            coveredMinutes = round1(covered),
            samples = s.size,
            distanceKm = distance?.let(::round1),
            distanceFrom = distanceFrom,
            avgMovingSpeedKmh = moving.takeIf { it.isNotEmpty() }?.average()?.let(::round1),
            maxSpeedKmh = speeds.maxOrNull()?.let(::round1),
            stoppedShare = speeds.takeIf { it.isNotEmpty() }?.let { round2(it.count { v -> v < MOVING_KMH }.toDouble() / it.size) },
            stops = stops,
            speedChangePerMin = speedChange?.let(::round1),
            batteryUsedPercent = battery?.let(::round1),
            electricRangeUsedKm = electric?.let(::round1),
            fuelRangeUsedKm = fuel?.let(::round1),
            rangeUsedPerKm = rangeUsed?.let(::round2),
            batteryPercentPer10Km = if (drove == null || battery == null) null else round1(battery / drove * 10.0),
            acOnShare = acKnown.takeIf { it.isNotEmpty() }?.let { round2(it.count { on -> on }.toDouble() / it.size) },
            insideCelsius = s.lastOrNull { it.facts.insideCelsius != null }?.facts?.insideCelsius,
            outsideCelsius = s.lastOrNull { it.facts.outsideCelsius != null }?.facts?.outsideCelsius,
            enoughData = covered >= MIN_COVERED_MINUTES && s.size >= MIN_SAMPLES,
        )
    }

    private fun round1(v: Double) = Math.round(v * 10.0) / 10.0
    private fun round2(v: Double) = Math.round(v * 100.0) / 100.0
}
