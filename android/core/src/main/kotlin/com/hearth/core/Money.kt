package com.hearth.core

import kotlin.math.abs

/** `a / b` rounded half away from zero. [b] must be positive. */
fun roundDiv(a: Long, b: Long): Long {
    require(b > 0) { "roundDiv divisor must be positive" }
    val ba = a.toBigInteger()
    val bb = b.toBigInteger()
    val half = bb.shiftRight(1)
    val q = if (a >= 0) (ba + half) / bb else -((-ba + half) / bb)
    return q.toLong()
}

/** Integer percent of [part] in [whole]; 0 when [whole] is 0. */
fun percent(part: Long, whole: Long): Long =
    if (whole <= 0) 0 else roundDivBig(part.toBigInteger() * 100.toBigInteger(), whole.toBigInteger())

/** Change from [prev] to [cur] in basis points; null when there is no baseline. */
fun changeBps(cur: Long, prev: Long): Long? =
    if (prev <= 0) null else roundDivBig((cur.toBigInteger() - prev.toBigInteger()) * 10_000.toBigInteger(), prev.toBigInteger())

internal fun roundDivBig(a: java.math.BigInteger, b: java.math.BigInteger): Long {
    val half = b.shiftRight(1)
    val q = if (a.signum() >= 0) (a + half) / b else -((-a + half) / b)
    return q.toLong()
}

/** "₹18,42,000": whole rupees with Indian digit grouping. Paise are rounded half away from zero. */
fun formatInr(minor: Long): String {
    val rupees = roundDiv(abs(minor), 100).toString()
    val grouped = if (rupees.length <= 3) {
        rupees
    } else {
        val head = rupees.dropLast(3)
        val pairs = head.reversed().chunked(2).joinToString(",").reversed()
        "$pairs,${rupees.takeLast(3)}"
    }
    return (if (minor < 0) "-₹" else "₹") + grouped
}

/** Basis points as a signed one-decimal percentage: -420 → "-4.2%". */
fun formatBps(bps: Long?): String {
    if (bps == null) return "—"
    val tenths = roundDiv(abs(bps), 10)
    val sign = when {
        bps > 0 -> "+"
        bps < 0 -> "-"
        else -> ""
    }
    return "$sign${tenths / 10}.${tenths % 10}%"
}

/** "just now", "2 min ago", "3h ago", "2d ago" or "never". */
fun syncedAgo(lastMs: Long?, nowMs: Long): String {
    if (lastMs == null) return "never"
    val s = (nowMs - lastMs).coerceAtLeast(0) / 1000
    return when {
        s < 60 -> "just now"
        s < 3_600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3_600}h ago"
        else -> "${s / 86_400}d ago"
    }
}

private val TYPED_AMOUNT = Regex("^(?:₹|rs\\.?|inr)?\\s*([0-9][0-9,]*)(?:\\.([0-9]{1,2}))?$", RegexOption.IGNORE_CASE)

/** Parse what a person types ("1,20,000", "649.5", "₹2,150.00") into paise; null if invalid. */
fun parseInrToMinor(text: String): Long? {
    val m = TYPED_AMOUNT.matchEntire(text.trim()) ?: return null
    val rupees = m.groupValues[1].replace(",", "").toLongOrNull() ?: return null
    val paise = m.groupValues[2].padEnd(2, '0').toLong()
    if (rupees > Long.MAX_VALUE / 100 - 1) return null
    return rupees * 100 + paise
}
