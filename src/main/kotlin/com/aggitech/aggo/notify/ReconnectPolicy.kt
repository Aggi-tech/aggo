package com.aggitech.aggo.notify

import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Decides how long [AggoListener] waits before retrying a dropped `LISTEN` connection.
 *
 * Plugs directly into [kotlinx.coroutines.flow.Flow.retryWhen] — [nextDelay]
 * returning `null` means "give up", which [AggoListener] turns into a thrown
 * [AggoListenerException] so the failure surfaces to the collector.
 */
sealed interface ReconnectPolicy {

    /** Returns the delay before the next attempt (1-based), or `null` to give up. */
    fun nextDelay(attempt: Int): Duration?

    /** Doubles the delay on every attempt, capped at [maxDelay], up to [maxAttempts]. */
    data class ExponentialBackoff(
        val initialDelay: Duration = 200.milliseconds,
        val maxDelay: Duration = 30.seconds,
        val multiplier: Double = 2.0,
        val maxAttempts: Int = 10,
    ) : ReconnectPolicy {
        override fun nextDelay(attempt: Int): Duration? {
            if (attempt > maxAttempts) return null
            val ms = (initialDelay.inWholeMilliseconds * multiplier.pow(attempt - 1))
                .toLong()
                .coerceAtMost(maxDelay.inWholeMilliseconds)
            return ms.milliseconds
        }
    }

    /** Never retries — the first connection failure is terminal. */
    object NoRetry : ReconnectPolicy {
        override fun nextDelay(attempt: Int): Duration? = null
    }
}

/** Thrown when [ReconnectPolicy.nextDelay] gives up after repeated connection failures. */
class AggoListenerException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
