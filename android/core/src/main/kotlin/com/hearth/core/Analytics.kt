package com.hearth.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.math.BigInteger
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val ANOMALY_FACTOR = 3L
private const val ANOMALY_MIN_BASELINE = 3
private const val ANOMALY_WINDOW_MS = 90L * 24 * 60 * 60 * 1000
private val MONTH_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

@Serializable
data class MonthTotal(val month: String, @SerialName("total_minor") val totalMinor: Long)

@Serializable
data class BudgetStatus(
    @SerialName("budget_id") val budgetId: String,
    val category: String,
    @SerialName("cap_minor") val capMinor: Long,
    @SerialName("spent_minor") val spentMinor: Long,
    @SerialName("used_pct") val usedPct: Long,
    val over: Boolean,
)

@Serializable
data class Anomaly(
    @SerialName("transaction_id") val transactionId: String,
    val category: String,
    @SerialName("amount_minor") val amountMinor: Long,
    @SerialName("ratio_pct") val ratioPct: Long,
)

/** Spend totals for the [months] calendar months ending with the anchor's month, oldest first. */
fun monthlyTrend(txns: List<Transaction>, scope: Scope, anchor: LocalDate, months: Int, tz: Int): List<MonthTotal> {
    val last = anchor.withDayOfMonth(1)
    return (months - 1 downTo 0).map { back ->
        val m = last.minusMonths(back.toLong())
        MonthTotal(m.format(MONTH_FORMAT), spendIn(txns, scope, Range.MONTH.period(m), tz).sumOf { it.amountMinor })
    }
}

private fun Budget.inScope(scope: Scope): Boolean = !deleted && when (scope) {
    Scope.Family -> memberId == null
    is Scope.Member -> memberId == scope.memberId
}

/** Monthly budgets of the scope against the anchor month's spend, sorted by category. */
fun budgetStatus(txns: List<Transaction>, budgets: List<Budget>, scope: Scope, anchor: LocalDate, tz: Int): List<BudgetStatus> {
    val monthSpend = spendIn(txns, scope, Range.MONTH.period(anchor), tz)
    return budgets.filter { it.inScope(scope) }
        .map { b ->
            val spent = monthSpend.filter { it.category == b.category }.sumOf { it.amountMinor }
            BudgetStatus(b.id, b.category, b.capMinor, spent, percent(spent, b.capMinor), spent > b.capMinor)
        }
        .sortedWith(compareBy<BudgetStatus> { it.category }.thenBy { it.budgetId })
}

/** Debits in the anchor month that are ≥ 3× the scope's usual spend in that category; newest first. */
fun anomalies(txns: List<Transaction>, scope: Scope, anchor: LocalDate, tz: Int): List<Anomaly> =
    spendIn(txns, scope, Range.MONTH.period(anchor), tz)
        .mapNotNull { t ->
            val category = t.category ?: return@mapNotNull null
            val baseline = txns.filter {
                it.isSpend && scope.includes(it) && it.category == category &&
                    it.occurredAtMs < t.occurredAtMs && it.occurredAtMs >= t.occurredAtMs - ANOMALY_WINDOW_MS
            }
            val count = baseline.size
            val sum = baseline.sumOf { it.amountMinor }
            if (count < ANOMALY_MIN_BASELINE || sum == 0L) return@mapNotNull null
            val big = t.amountMinor.toBigInteger() * count.toBigInteger()
            if (big < ANOMALY_FACTOR.toBigInteger() * sum.toBigInteger()) return@mapNotNull null
            val ratio = roundDivBig(big * BigInteger.valueOf(100), sum.toBigInteger())
            t to Anomaly(t.id, category, t.amountMinor, ratio)
        }
        .sortedWith(compareByDescending<Pair<Transaction, Anomaly>> { it.first.occurredAtMs }.thenBy { it.second.transactionId })
        .map { it.second }
