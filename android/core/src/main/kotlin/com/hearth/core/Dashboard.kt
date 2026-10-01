package com.hearth.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Everything the screens need; [nowMs] and [tzOffsetMinutes] define "today". */
data class DashboardInput(
    val ledger: Ledger,
    val me: String,
    val members: Map<String, String>,
    val tzOffsetMinutes: Int,
    val nowMs: Long,
) {
    val today: LocalDate get() = localDate(nowMs, tzOffsetMinutes)
}

data class CategoryRow(val name: String, val amountLabel: String, val pct: Int) {
    val pctLabel: String get() = "$pct%"
}

data class ReviewItem(val transactionId: String, val label: String)

data class TxnRow(
    val transactionId: String,
    val memberInitial: String,
    val merchant: String,
    val dateLabel: String,
    val categoryLabel: String,
    val amountLabel: String,
)

data class HomeState(
    val greeting: String,
    val kicker: String,
    val totalLabel: String,
    val deltaLabel: String,
    val needsReview: ReviewItem?,
    val alerts: List<String>,
    val topCategories: List<CategoryRow>,
    val recent: List<TxnRow>,
)

data class MemberRow(val name: String, val amountLabel: String, val topCategory: String)

data class ReportsState(val totalLabel: String, val kicker: String, val categories: List<CategoryRow>, val members: List<MemberRow>)

data class TrendBar(val label: String, val heightPct: Int, val current: Boolean)

data class BudgetRow(val name: String, val amountLabel: String, val capLabel: String, val pct: Int, val over: Boolean)

data class MerchantRow(val name: String, val amountLabel: String)

data class AnalyticsState(
    val trend: List<TrendBar>,
    val budgets: List<BudgetRow>,
    val topMerchants: List<MerchantRow>,
    val alerts: List<String>,
)

data class PortfolioMemberRow(val name: String, val valueLabel: String, val gainLabel: String)

data class AllocationRow(val name: String, val pct: Int)

data class GoalRow(val name: String, val pct: Int, val savedLabel: String, val targetLabel: String, val etaLabel: String)

data class PortfolioState(
    val totalLabel: String,
    val gainLabel: String,
    val members: List<PortfolioMemberRow>,
    val allocation: List<AllocationRow>,
    val netWorthLabel: String,
    val netWorthTrend: List<Int>,
    val goals: List<GoalRow>,
)

data class BillRow(val name: String, val dueLabel: String, val tag: String, val amountLabel: String)

data class SyncRow(val initial: String, val name: String, val status: String, val online: Boolean)

/** Pure presenter: turns the ledger into the labels and rows the Compose screens render. */
object Dashboard {
    private const val TOP_CATEGORIES = 4
    private const val RECENT = 20
    private val DAY_LABEL = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
    private val MONTH_YEAR = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)

    private fun rangeLabel(range: Range) = when (range) {
        Range.DAY -> "Today"
        Range.WEEK -> "This week"
        Range.MONTH -> "This month"
        Range.YEAR -> "This year"
    }

    private fun DashboardInput.scopeLabel(scope: Scope) = if (scope is Scope.Family) "Family" else "You"

    private fun DashboardInput.name(memberId: String) = members[memberId] ?: "Someone"

    private fun DashboardInput.dayLabel(ms: Long) = localDate(ms, tzOffsetMinutes).format(DAY_LABEL)

    private fun greeting(input: DashboardInput): String {
        val hour = java.time.Instant.ofEpochMilli(input.nowMs)
            .atOffset(java.time.ZoneOffset.ofTotalSeconds(input.tzOffsetMinutes * 60)).hour
        val part = when {
            hour < 12 -> "morning"
            hour < 17 -> "afternoon"
            else -> "evening"
        }
        return "Good $part, ${input.name(input.me)}"
    }

    private fun categoryRows(report: Report) =
        report.categories.map { CategoryRow(it.category ?: UNCATEGORIZED, formatInr(it.amountMinor), it.pct.toInt()) }

    fun home(input: DashboardInput, scope: Scope, range: Range): HomeState {
        val txns = input.ledger.transactions
        val report = spendReport(txns, scope, range, input.today, input.tzOffsetMinutes)
        val review = txns.filter { it.isSpend && scope.includes(it) && it.category == null }
            .maxByOrNull { it.occurredAtMs }
            ?.let { ReviewItem(it.id, "${formatInr(it.amountMinor)} at ${it.merchant} · ${input.dayLabel(it.occurredAtMs)}") }
        val recent = spendIn(txns, scope, range.period(input.today), input.tzOffsetMinutes)
            .sortedByDescending { it.occurredAtMs }
            .take(RECENT)
            .map {
                TxnRow(
                    transactionId = it.id,
                    memberInitial = input.name(it.memberId).take(1).uppercase(),
                    merchant = it.merchant,
                    dateLabel = input.dayLabel(it.occurredAtMs),
                    categoryLabel = it.category ?: "Needs category",
                    amountLabel = formatInr(it.amountMinor),
                )
            }
        return HomeState(
            greeting = greeting(input),
            kicker = "${rangeLabel(range)} · ${input.scopeLabel(scope)}",
            totalLabel = formatInr(report.totalMinor),
            deltaLabel = formatBps(report.deltaBps),
            needsReview = review,
            alerts = alerts(input, scope),
            topCategories = categoryRows(report).take(TOP_CATEGORIES),
            recent = recent,
        )
    }

    fun reports(input: DashboardInput, scope: Scope, range: Range): ReportsState {
        val report = spendReport(input.ledger.transactions, scope, range, input.today, input.tzOffsetMinutes)
        val members = if (scope is Scope.Family) {
            report.members.map { MemberRow(input.name(it.memberId), formatInr(it.amountMinor), it.topCategory ?: UNCATEGORIZED) }
        } else {
            emptyList()
        }
        return ReportsState(formatInr(report.totalMinor), "Total · ${rangeLabel(range)}", categoryRows(report), members)
    }

    fun analytics(input: DashboardInput, scope: Scope, months: Int = 6): AnalyticsState {
        val txns = input.ledger.transactions
        val trend = monthlyTrend(txns, scope, input.today, months, input.tzOffsetMinutes)
        val max = trend.maxOfOrNull { it.totalMinor } ?: 0L
        val bars = trend.mapIndexed { i, m ->
            TrendBar(
                label = YearMonth.parse(m.month).month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                heightPct = percent(m.totalMinor, max).toInt(),
                current = i == trend.lastIndex,
            )
        }
        val budgets = budgetStatus(txns, input.ledger.budgets, scope, input.today, input.tzOffsetMinutes).map {
            BudgetRow(it.category, formatInr(it.spentMinor), formatInr(it.capMinor), it.usedPct.toInt().coerceAtMost(100), it.over)
        }
        val merchants = spendReport(txns, scope, Range.MONTH, input.today, input.tzOffsetMinutes).topMerchants
            .map { MerchantRow(it.merchant, formatInr(it.amountMinor)) }
        return AnalyticsState(bars, budgets, merchants, alerts(input, scope))
    }

    /** Plain-language unusual-spend alerts for the current month. */
    fun alerts(input: DashboardInput, scope: Scope): List<String> {
        val byId = input.ledger.transactions.associateBy { it.id }
        return anomalies(input.ledger.transactions, scope, input.today, input.tzOffsetMinutes).mapNotNull { a ->
            val t = byId[a.transactionId] ?: return@mapNotNull null
            val times = roundDiv(a.ratioPct, 100)
            val amount = formatInr(a.amountMinor)
            if (scope is Scope.Family) {
                "${input.name(t.memberId)}'s $amount ${t.merchant} purchase is about ${times}x the family's usual ${a.category} spend."
            } else {
                "${t.merchant} purchase of $amount is about ${times}x your usual ${a.category} spend."
            }
        }
    }

    private fun assetName(wire: String) = when (wire) {
        "equity" -> "Equity"
        "mutual_fund" -> "Mutual Funds"
        "fd" -> "FD"
        "gold" -> "Gold"
        "cash" -> "Cash"
        else -> "Other"
    }

    fun portfolio(input: DashboardInput): PortfolioState {
        val l = input.ledger
        val s = summarizePortfolio(l.holdings, l.liabilities, l.goals, l.snapshots)
        val maxNw = s.networthTrend.maxOfOrNull { it.valueMinor } ?: 0L
        return PortfolioState(
            totalLabel = formatInr(s.totalValueMinor),
            gainLabel = "${formatInr(s.gainMinor)} (${formatBps(s.gainBps).removePrefix("+")})",
            members = s.members.map { PortfolioMemberRow(input.name(it.memberId), formatInr(it.valueMinor), formatBps(it.gainBps)) },
            allocation = s.allocation.map { AllocationRow(assetName(it.assetClass), it.pct.toInt()) },
            netWorthLabel = formatInr(s.netWorthMinor),
            netWorthTrend = s.networthTrend.map { percent(it.valueMinor, maxNw).toInt() },
            goals = s.goals.map {
                GoalRow(it.name, it.pct.toInt(), formatInr(it.savedMinor), formatInr(it.targetMinor), YearMonth.parse(it.targetMonth).atDay(1).format(MONTH_YEAR))
            },
        )
    }

    private fun nextDue(today: LocalDate, dueDay: Int): LocalDate {
        val thisMonth = YearMonth.from(today)
        val candidate = thisMonth.atDay(dueDay.coerceAtMost(thisMonth.lengthOfMonth()))
        if (!candidate.isBefore(today)) return candidate
        val next = thisMonth.plusMonths(1)
        return next.atDay(dueDay.coerceAtMost(next.lengthOfMonth()))
    }

    fun bills(input: DashboardInput): List<BillRow> =
        input.ledger.bills.filter { !it.deleted }
            .map { it to nextDue(input.today, it.dueDay) }
            .sortedWith(compareBy<Pair<Bill, LocalDate>> { it.second }.thenBy { it.first.name })
            .map { (b, due) ->
                val tag = b.kind.name.lowercase().replaceFirstChar { it.uppercase() }
                BillRow(b.name, "Due ${due.format(DAY_LABEL)}", tag, formatInr(b.amountMinor))
            }

    fun syncRows(status: SyncStatus, onlineWithinMs: Long): List<SyncRow> = status.members.map { m ->
        val last = m.lastSyncMs
        val online = last != null && status.nowMs - last <= onlineWithinMs
        val text = when {
            last == null -> "Never synced"
            online -> "Synced ${syncedAgo(last, status.nowMs)}"
            else -> "Last synced ${syncedAgo(last, status.nowMs)}"
        }
        SyncRow(m.name.take(1).uppercase(), m.name, text, online)
    }

    fun syncSummary(rows: List<SyncRow>): String = "${rows.count { it.online }} of ${rows.size} online"
}
