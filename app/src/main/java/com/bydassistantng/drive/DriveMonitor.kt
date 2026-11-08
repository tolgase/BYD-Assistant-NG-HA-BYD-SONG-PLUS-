package com.bydassistantng.drive

import com.bydassistantng.util.AppLogger
import com.bydassistantng.vehicle.StatusFacts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Song PLUS edition: watches the drive while the car is on, so "how am I driving?" can be answered for the last 15 or
 * 30 minutes, the assistant knows how long the ride has been (its manners, see [RideStyle]) and who is on board, and a
 * short tip can be spoken when the drive clearly calls for one (see [DriveTips]).
 *
 * One status reading every [DRIVING_INTERVAL_MS] while moving and every [PARKED_INTERVAL_MS] otherwise — both inside the
 * car helper's own idle time, so it is never restarted for this — and none while a conversation is going on. The samples
 * stay in memory ([DriveLog]): nothing is stored or sent anywhere.
 */
object DriveMonitor {
    private const val TAG = "DriveMonitor"
    const val DRIVING_INTERVAL_MS = 30_000L
    const val PARKED_INTERVAL_MS = 90_000L

    // After the car wakes, the greeting and the first command have the car to themselves for a moment.
    private const val FIRST_DELAY_MS = 45_000L

    // A tip is looked for this often while driving (it is said far less often, see [DriveTips]).
    private const val TIP_CHECK_MS = 5 * 60_000L

    val log = DriveLog()
    val ride = RideTracker()

    /** The last sample taken (who is on board, among others). */
    @Volatile var latest: DriveSample? = null
        private set

    private val lastSaid = HashMap<TipKind, Long>()
    @Volatile private var job: Job? = null

    /**
     * Starts watching (once). [enabled] is asked before every reading (the setting and vehicle control), [busy] says a
     * conversation is under way, and [read] returns the car's status text (see [com.bydassistantng.vehicle.CarStatus]).
     * [tipsEnabled] and [speak] are for the spoken tips: [speak] says an automatic message and returns whether it started.
     */
    fun start(
        scope: CoroutineScope,
        enabled: suspend () -> Boolean,
        busy: () -> Boolean,
        read: suspend () -> String?,
        tipsEnabled: suspend () -> Boolean = { false },
        speak: suspend (DriveTip) -> Boolean = { false },
    ) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            AppLogger.log(TAG, "Watching the drive")
            delay(FIRST_DELAY_MS)
            var lastSpeed: Double? = null
            var failedInARow = 0
            var lastTipCheck = 0L
            while (isActive) {
                if (enabled() && !busy()) {
                    val facts = try {
                        StatusFacts.parse(read())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        StatusFacts()
                    }
                    if (facts.hasAny) {
                        val sample = DriveSample(System.currentTimeMillis(), facts)
                        log.add(sample)
                        ride.update(sample.atMs, facts.speedKmh)
                        latest = sample
                        lastSpeed = facts.speedKmh
                        failedInARow = 0
                    } else if (++failedInARow == 3) {
                        AppLogger.log(TAG, "The car gave no status three times running — still trying")
                    }
                    val now = System.currentTimeMillis()
                    val moving = (lastSpeed ?: 0.0) >= DriveAnalysis.MOVING_KMH
                    if (moving && now - lastTipCheck >= TIP_CHECK_MS) {
                        lastTipCheck = now
                        maybeTip(now, tipsEnabled, busy, speak)
                    }
                }
                delay(if ((lastSpeed ?: 0.0) >= DriveAnalysis.MOVING_KMH) DRIVING_INTERVAL_MS else PARKED_INTERVAL_MS)
            }
        }
    }

    private suspend fun maybeTip(now: Long, tipsEnabled: suspend () -> Boolean, busy: () -> Boolean, speak: suspend (DriveTip) -> Boolean) {
        if (!tipsEnabled() || busy()) return
        val report = DriveAnalysis.analyse(log.snapshot(), 15, now)
        val tip = synchronized(lastSaid) { DriveTips.pick(report, ride.minutes(now), lastSaid.toMap(), now) } ?: return
        AppLogger.log(TAG, "Drive tip due: ${tip.kind} (${tip.facts})")
        val said = try {
            speak(tip)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log(TAG, "The drive tip could not be said: $e")
            false
        }
        // Only a tip that was said counts: one that could not start (no connection) is looked at again later.
        if (said) synchronized(lastSaid) { lastSaid[tip.kind] = now }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
