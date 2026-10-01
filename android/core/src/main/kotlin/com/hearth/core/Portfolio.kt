package com.hearth.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val NET_WORTH_TREND_POINTS = 6

@Serializable
data class MemberPortfolio(
    @SerialName("member_id") val memberId: String,
    @SerialName("value_minor") val valueMinor: Long,
    @SerialName("invested_minor") val investedMinor: Long,
    @SerialName("gain_bps") val gainBps: Long?,
)

@Serializable
data class Allocation(
    @SerialName("asset_class") val assetClass: String,
    @SerialName("value_minor") val valueMinor: Long,
    val pct: Long,
)

@Serializable
data class GoalProgress(
    @SerialName("goal_id") val goalId: String,
    val name: String,
    @SerialName("target_minor") val targetMinor: Long,
    @SerialName("saved_minor") val savedMinor: Long,
    val pct: Long,
    @SerialName("target_month") val targetMonth: String,
)

@Serializable
data class MonthValue(val month: String, @SerialName("value_minor") val valueMinor: Long)

@Serializable
data class PortfolioSummary(
    @SerialName("total_value_minor") val totalValueMinor: Long,
    @SerialName("invested_minor") val investedMinor: Long,
    @SerialName("gain_minor") val gainMinor: Long,
    @SerialName("gain_bps") val gainBps: Long?,
    val members: List<MemberPortfolio>,
    val allocation: List<Allocation>,
    @SerialName("liabilities_minor") val liabilitiesMinor: Long,
    @SerialName("net_worth_minor") val netWorthMinor: Long,
    val goals: List<GoalProgress>,
    @SerialName("networth_trend") val networthTrend: List<MonthValue>,
)

fun summarizePortfolio(
    holdings: List<Holding>,
    liabilities: List<Liability>,
    goals: List<Goal>,
    snapshots: List<NetWorthSnapshot>,
): PortfolioSummary {
    val live = holdings.filter { !it.deleted }
    val total = live.sumOf { it.valueMinor }
    val invested = live.sumOf { it.investedMinor }
    val members = live.groupBy { it.memberId }
        .map { (m, hs) ->
            val value = hs.sumOf { it.valueMinor }
            val inv = hs.sumOf { it.investedMinor }
            MemberPortfolio(m, value, inv, changeBps(value, inv))
        }
        .sortedWith(compareByDescending<MemberPortfolio> { it.valueMinor }.thenBy { it.memberId })
    val allocation = live.groupBy { it.assetClass }
        .map { (cls, hs) -> hs.sumOf { it.valueMinor }.let { v -> Allocation(cls.wire, v, percent(v, total)) } }
        .sortedWith(compareByDescending<Allocation> { it.valueMinor }.thenBy { it.assetClass })
    val owed = liabilities.filter { !it.deleted }.sumOf { it.outstandingMinor }
    return PortfolioSummary(
        totalValueMinor = total,
        investedMinor = invested,
        gainMinor = total - invested,
        gainBps = changeBps(total, invested),
        members = members,
        allocation = allocation,
        liabilitiesMinor = owed,
        netWorthMinor = total - owed,
        goals = goals.filter { !it.deleted }
            .map { GoalProgress(it.id, it.name, it.targetMinor, it.savedMinor, percent(it.savedMinor, it.targetMinor), it.targetMonth) }
            .sortedBy { it.name },
        networthTrend = snapshots.filter { !it.deleted }
            .map { MonthValue(it.month, it.valueMinor) }
            .sortedBy { it.month }
            .takeLast(NET_WORTH_TREND_POINTS),
    )
}
