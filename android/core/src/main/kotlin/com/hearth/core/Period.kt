package com.hearth.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Reporting range (contracts/periods.json). Weeks start on Monday. */
enum class Range(val wire: String) {
    DAY("day"),
    WEEK("week"),
    MONTH("month"),
    YEAR("year"),
    ;

    fun period(anchor: LocalDate): Period {
        val start = when (this) {
            DAY -> anchor
            WEEK -> anchor.minusDays((anchor.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
            MONTH -> anchor.withDayOfMonth(1)
            YEAR -> anchor.withDayOfYear(1)
        }
        return Period(start, advance(start, 1))
    }

    fun previous(anchor: LocalDate): Period {
        val current = period(anchor)
        return Period(advance(current.start, -1), current.start)
    }

    private fun advance(start: LocalDate, n: Long): LocalDate = when (this) {
        DAY -> start.plusDays(n)
        WEEK -> start.plusWeeks(n)
        MONTH -> start.plusMonths(n)
        YEAR -> start.plusYears(n)
    }

    companion object {
        fun parse(s: String): Range = entries.firstOrNull { it.wire == s }
            ?: throw IllegalArgumentException("unknown range '$s' (day|week|month|year)")
    }
}

/** Local dates, [end] exclusive. */
data class Period(val start: LocalDate, val end: LocalDate) {
    fun startMs(tzOffsetMinutes: Int): Long = localToMs(start.atStartOfDay(), tzOffsetMinutes)
    fun endMs(tzOffsetMinutes: Int): Long = localToMs(end.atStartOfDay(), tzOffsetMinutes)
    fun containsMs(ms: Long, tzOffsetMinutes: Int): Boolean = ms >= startMs(tzOffsetMinutes) && ms < endMs(tzOffsetMinutes)
}

private fun offset(tzOffsetMinutes: Int): ZoneOffset = ZoneOffset.ofTotalSeconds(tzOffsetMinutes * 60)

/** Epoch millis of a family-local wall time. */
fun localToMs(local: LocalDateTime, tzOffsetMinutes: Int): Long =
    local.toInstant(offset(tzOffsetMinutes)).toEpochMilli()

/** Family-local calendar date of an instant. */
fun localDate(ms: Long, tzOffsetMinutes: Int): LocalDate =
    Instant.ofEpochMilli(ms).atOffset(offset(tzOffsetMinutes)).toLocalDate()
