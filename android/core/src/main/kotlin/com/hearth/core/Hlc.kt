package com.hearth.core

import kotlinx.serialization.Serializable

private const val MAX_COUNTER = 9_999

/**
 * Hybrid logical clock. Immutable: [tick] and [recv] return the next clock state, which is also
 * the stamp to put on the record. String form `{millis:013}-{counter:04}-{node}` (contracts/hlc.json).
 */
@Serializable
data class Hlc(val millis: Long, val counter: Int, val node: String) {

    /** Stamp a local event at wall time [nowMs]. */
    fun tick(nowMs: Long): Hlc =
        if (nowMs > millis) copy(millis = nowMs, counter = 0) else bumped(millis, counter + 1)

    /** Merge a remote stamp observed at wall time [nowMs]. */
    fun recv(remote: Hlc, nowMs: Long): Hlc {
        val max = maxOf(millis, remote.millis, nowMs)
        if (nowMs > millis && nowMs > remote.millis) return copy(millis = nowMs, counter = 0)
        val next = when {
            millis == remote.millis -> maxOf(counter, remote.counter) + 1
            max == millis -> counter + 1
            else -> remote.counter + 1
        }
        return bumped(max, next)
    }

    private fun bumped(atMillis: Long, nextCounter: Int): Hlc =
        if (nextCounter > MAX_COUNTER) copy(millis = atMillis + 1, counter = 0) else copy(millis = atMillis, counter = nextCounter)

    override fun toString(): String = "%013d-%04d-%s".format(millis, counter, node)

    companion object {
        private val FORMAT = Regex("^(\\d{13})-(\\d{4})-(.+)$")

        fun parseOrNull(s: String): Hlc? {
            val m = FORMAT.matchEntire(s) ?: return null
            val (millis, counter, node) = m.destructured
            return Hlc(millis.toLong(), counter.toInt(), node)
        }

        fun parse(s: String): Hlc = requireNotNull(parseOrNull(s)) { "invalid HLC '$s'" }
    }
}
