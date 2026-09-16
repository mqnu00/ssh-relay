package dev.sshrelay.ssh

import kotlin.math.pow
import kotlin.random.Random

/**
 * Exponential backoff with jitter for reconnect attempts.
 *
 * Jitter matters here: without it every device that lost connectivity at the same
 * moment (e.g. a Wi-Fi drop) would hammer the SSH server in lockstep.
 */
data class BackoffPolicy(
    val initialDelayMs: Long = 1_000,
    val maxDelayMs: Long = 30_000,
    val factor: Double = 2.0,
    val jitterRatio: Double = 0.2,
) {
    /** [attempt] is 1-based. */
    fun delayFor(attempt: Int, random: Random = Random.Default): Long {
        val exponent = (attempt - 1).coerceAtLeast(0)
        val raw = (initialDelayMs.toDouble() * factor.pow(exponent)).toLong()
        val capped = raw.coerceIn(initialDelayMs, maxDelayMs)
        val jitter = (capped * jitterRatio).toLong()
        if (jitter <= 0L) return capped
        return (capped - jitter) + random.nextLong(0, 2 * jitter + 1)
    }
}
