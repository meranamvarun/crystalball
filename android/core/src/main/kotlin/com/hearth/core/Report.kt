package com.hearth.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

const val UNCATEGORIZED = "Uncategorized"
private const val TOP_MERCHANTS = 5

/** Whose spending a view covers: the whole family or one member ("You"). */
sealed interface Scope {
    fun includes(t: Transaction): Boolean

    data object Family : Scope {
        override fun includes(t: Transaction) = true
    }

    data class Member(val memberId: String) : Scope {
        override fun includes(t: Transaction) = t.memberId == memberId
    }
}

@Serializable
data class CategoryTotal(val category: String?, @SerialName("amount_minor") val amountMinor: Long, val pct: Long)

@Serializable
data class MemberTotal(
    @SerialName("member_id") val memberId: String,
    @SerialName("amount_minor") val amountMinor: Long,
    @SerialName("top_category") val topCategory: String?,
)

@Serializable
data class MerchantTotal(val merchant: String, @SerialName("amount_minor") val amountMinor: Long)

@Serializable
data class Report(
    @SerialName("total_minor") val totalMinor: Long,
    @SerialName("prev_total_minor") val prevTotalMinor: Long,
    @SerialName("delta_bps") val deltaBps: Long?,
    val categories: List<CategoryTotal>,
    val members: List<MemberTotal>,
    @SerialName("top_merchants") val topMerchants: List<MerchantTotal>,
    @SerialName("needs_review") val needsReview: Int,
)

/** Spend (non-deleted debits) of [scope] inside [period]. */
fun spendIn(txns: List<Transaction>, scope: Scope, period: Period, tz: Int): List<Transaction> {
    val start = period.startMs(tz)
    val end = period.endMs(tz)
    return txns.filter { it.isSpend && scope.includes(it) && it.occurredAtMs >= start && it.occurredAtMs < end }
}

private val categoryOrder: Comparator<Pair<String?, Long>> =
    compareByDescending<Pair<String?, Long>> { it.second }.thenBy { it.first ?: UNCATEGORIZED }

/** Sum per category, amount desc then category name (null sorts as "Uncategorized"). */
fun byCategory(txns: List<Transaction>): List<Pair<String?, Long>> =
    txns.groupBy { it.category }
        .map { (category, ts) -> category to ts.sumOf { it.amountMinor } }
        .sortedWith(categoryOrder)

fun spendReport(txns: List<Transaction>, scope: Scope, range: Range, anchor: LocalDate, tz: Int): Report {
    val current = spendIn(txns, scope, range.period(anchor), tz)
    val prevTotal = spendIn(txns, scope, range.previous(anchor), tz).sumOf { it.amountMinor }
    val total = current.sumOf { it.amountMinor }
    val members = current.groupBy { it.memberId }
        .map { (member, ts) -> MemberTotal(member, ts.sumOf { it.amountMinor }, byCategory(ts).firstOrNull()?.first) }
        .sortedWith(compareByDescending<MemberTotal> { it.amountMinor }.thenBy { it.memberId })
    val merchants = current.groupBy { it.merchant }
        .map { (m, ts) -> MerchantTotal(m, ts.sumOf { it.amountMinor }) }
        .sortedWith(compareByDescending<MerchantTotal> { it.amountMinor }.thenBy { it.merchant })
        .take(TOP_MERCHANTS)
    return Report(
        totalMinor = total,
        prevTotalMinor = prevTotal,
        deltaBps = changeBps(total, prevTotal),
        categories = byCategory(current).map { (c, a) -> CategoryTotal(c, a, percent(a, total)) },
        members = members,
        topMerchants = merchants,
        needsReview = current.count { it.category == null },
    )
}
