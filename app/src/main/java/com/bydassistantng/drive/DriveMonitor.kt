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
 * 30 minutes. One status reading every [DRIVING_INTERVAL_MS] while moving and every [PARKED_INTERVAL_MS] otherwise —
 * both inside the car helper's own idle time, so it is never restarted for this — and none while a conversation is
 * going on. The samples stay in memory ([DriveLog]): nothing is stored or sent anywhere.
 */
object DriveMonitor {
    private const val TAG = "DriveMonitor"
    const val DRIVING_INTERVAL_MS = 30_000L
    const val PARKED_INTERVAL_MS = 90_000L

    // After the car wakes, the greeting and the first command have the car to themselves for a moment.
    private const val FIRST_DELAY_MS = 45_000L

    val log = DriveLog()
    @Volatile private var job: Job? = null

    /**
     * Starts watching (once). [enabled] is asked before every reading (the setting and vehicle control), [busy] says a
     * conversation is under way, and [read] returns the car's status text (see [com.bydassistantng.vehicle.CarStatus]).
     */
    fun start(scope: CoroutineScope, enabled: suspend () -> Boolean, busy: () -> Boolean, read: suspend () -> String?) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            AppLogger.log(TAG, "Watching the drive")
            delay(FIRST_DELAY_MS)
            var lastSpeed: Double? = null
            var failedInARow = 0
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
                        log.add(DriveSample(System.currentTimeMillis(), facts))
                        lastSpeed = facts.speedKmh
                        failedInARow = 0
                    } else if (++failedInARow == 3) {
                        AppLogger.log(TAG, "The car gave no status three times running — still trying")
                    }
                }
                delay(if ((lastSpeed ?: 0.0) >= DriveAnalysis.MOVING_KMH) DRIVING_INTERVAL_MS else PARKED_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
