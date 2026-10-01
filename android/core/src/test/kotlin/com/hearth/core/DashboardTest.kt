package com.hearth.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Screen state for the design's Home / Reports / Analytics / Invest / Settings tabs. */
class DashboardTest {
    private val tz = 330
    private val now = localToMs(LocalDateTime.parse("2026-09-29T20:30"), tz)
    private val members = mapOf("m_you" to "You", "m_priya" to "Priya", "m_aarav" to "Aarav")

    private fun file(name: String): JsonObject =
        HearthJson.parseToJsonElement(File(System.getProperty("contracts.dir"), name).readText()).jsonObject

    private val input: DashboardInput by lazy {
        val d = file("dataset.json")
        val txns = d.getValue("transactions").jsonArray.map { el ->
            val o = el.jsonObject
            Transaction(
                id = o.getValue("id").jsonPrimitive.content,
                memberId = o.getValue("member_id").jsonPrimitive.content,
                amountMinor = o.getValue("amount_minor").jsonPrimitive.long,
                direction = HearthJson.decodeFromJsonElement(o.getValue("direction")),
                merchant = o.getValue("merchant").jsonPrimitive.content,
                category = o["category"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content,
                occurredAtMs = localToMs(LocalDateTime.parse(o.getValue("at").jsonPrimitive.content), tz),
                deleted = o["deleted"]?.jsonPrimitive?.boolean ?: false,
            )
        }
        val budgets = d.getValue("budgets").jsonArray.map { HearthJson.decodeFromJsonElement<Budget>(it) }
        val p = file("portfolio.json").getValue("input").jsonObject
        val ledger = Ledger(
            transactions = txns,
            budgets = budgets,
            holdings = p.getValue("holdings").jsonArray.map { HearthJson.decodeFromJsonElement(it) },
            liabilities = p.getValue("liabilities").jsonArray.map { HearthJson.decodeFromJsonElement(it) },
            goals = p.getValue("goals").jsonArray.map { HearthJson.decodeFromJsonElement(it) },
            snapshots = p.getValue("snapshots").jsonArray.map { HearthJson.decodeFromJsonElement(it) },
            bills = listOf(
                Bill("b1", "Home Loan EMI", 3_240_000, 3, BillKind.RECURRING),
                Bill("b2", "Electricity", 215_000, 30, BillKind.VARIABLE),
                Bill("b3", "Netflix", 64_900, 12, BillKind.AUTOPAY),
            ),
        )
        check(d.getValue("tz_offset_minutes").jsonPrimitive.int == tz)
        DashboardInput(ledger, me = "m_you", members = members, tzOffsetMinutes = tz, nowMs = now)
    }

    @Test
    fun homeFamilyMonth() {
        val home = Dashboard.home(input, Scope.Family, Range.MONTH)
        assertEquals("Good evening, You", home.greeting)
        assertEquals("This month · Family", home.kicker)
        assertEquals("₹10,359", home.totalLabel)
        assertEquals("+298.4%", home.deltaLabel)
        assertEquals(listOf("Shopping", "Groceries", "Dining", "Uncategorized"), home.topCategories.map { it.name })
        assertEquals("₹5,350", home.topCategories[0].amountLabel)
        assertEquals(52, home.topCategories[0].pct)
        val review = home.needsReview!!
        assertEquals("t5", review.transactionId)
        assertEquals("₹850 at PAYTM-XYZTRD4521 · Sep 26", review.label)
        val first = home.recent.first()
        assertEquals(TxnRow("t2", "P", "Zomato", "Sep 29", "Dining", "₹780"), first)
        assertTrue(home.recent.none { it.transactionId == "t12" }, "credits are not spend")
        assertTrue(home.recent.none { it.transactionId == "t13" }, "deleted transactions are hidden")
    }

    @Test
    fun homePersonalDayShowsOnlyMyTransactions() {
        val home = Dashboard.home(input, Scope.Member("m_you"), Range.DAY)
        assertEquals("Today · You", home.kicker)
        assertEquals("₹1,840", home.totalLabel)
        assertEquals(listOf("t1"), home.recent.map { it.transactionId })
        assertEquals("t5", home.needsReview?.transactionId, "uncategorized spend surfaces whatever the period")
        assertNull(Dashboard.home(input, Scope.Member("m_aarav"), Range.DAY).needsReview)
    }

    @Test
    fun reportsIncludeMemberBreakdownOnlyForFamily() {
        val fam = Dashboard.reports(input, Scope.Family, Range.MONTH)
        assertEquals(7, fam.categories.size)
        assertEquals("52%", fam.categories[0].pctLabel)
        assertEquals(listOf(MemberRow("You", "₹6,229", "Shopping"), MemberRow("Priya", "₹3,980", "Shopping"), MemberRow("Aarav", "₹150", "Dining")), fam.members)
        assertTrue(Dashboard.reports(input, Scope.Member("m_you"), Range.MONTH).members.isEmpty())
    }

    @Test
    fun analyticsTrendBudgetsAndMerchants() {
        val a = Dashboard.analytics(input, Scope.Family)
        assertEquals(listOf("Apr", "May", "Jun", "Jul", "Aug", "Sep"), a.trend.map { it.label })
        assertEquals(100, a.trend.last().heightPct)
        assertTrue(a.trend.last().current)
        assertEquals(25, a.trend[4].heightPct) // 2,600 of 10,359
        val groceries = a.budgets.first { it.name == "Groceries" }
        assertEquals(BudgetRow("Groceries", "₹1,840", "₹1,500", 100, true), groceries)
        assertEquals(BudgetRow("Dining", "₹930", "₹1,000", 93, false), a.budgets.first { it.name == "Dining" })
        assertEquals("Myntra", a.topMerchants.first().name)
    }

    @Test
    fun alertsDescribeUnusualSpendInPlainWords() {
        val txns = listOf(
            Transaction("a1", "m_priya", 50_000, Direction.DEBIT, "Myntra", "Shopping", now - 80L * 86_400_000),
            Transaction("a2", "m_you", 60_000, Direction.DEBIT, "Amazon", "Shopping", now - 50L * 86_400_000),
            Transaction("a3", "m_priya", 40_000, Direction.DEBIT, "Myntra", "Shopping", now - 20L * 86_400_000),
            Transaction("a4", "m_priya", 320_000, Direction.DEBIT, "Myntra", "Shopping", now - 86_400_000),
        )
        val alerts = Dashboard.alerts(input.copy(ledger = Ledger(transactions = txns)), Scope.Family)
        assertEquals(listOf("Priya's ₹3,200 Myntra purchase is about 6x the family's usual Shopping spend."), alerts)
    }

    @Test
    fun portfolioLabels() {
        val p = Dashboard.portfolio(input)
        assertEquals("₹17,05,000", p.totalLabel)
        assertEquals("₹3,05,000 (21.8%)", p.gainLabel)
        assertEquals(listOf("You", "Priya"), p.members.map { it.name })
        assertEquals("+24.4%", p.members[0].gainLabel)
        assertEquals(listOf("Equity", "Mutual Funds", "FD", "Gold"), p.allocation.map { it.name })
        assertEquals("₹5,05,000", p.netWorthLabel)
        assertEquals(6, p.netWorthTrend.size)
        val goa = p.goals.first { it.name == "Goa Vacation Fund" }
        assertEquals(GoalRow("Goa Vacation Fund", 61, "₹92,000", "₹1,50,000", "Dec 2026"), goa)
    }

    @Test
    fun billsShowNextDueDate() {
        val bills = Dashboard.bills(input)
        assertEquals(listOf("Electricity", "Home Loan EMI", "Netflix"), bills.map { it.name })
        assertEquals(BillRow("Electricity", "Due Sep 30", "Variable", "₹2,150"), bills[0])
        assertEquals("Due Oct 3", bills[1].dueLabel)
        assertEquals("Autopay", bills[2].tag)
    }

    @Test
    fun syncRowsUseRelativeTimes() {
        val status = SyncStatus(now, listOf(MemberSync("m_you", "You", now - 10_000), MemberSync("m_aarav", "Aarav", now - 3 * 3_600_000), MemberSync("m_x", "New", null)))
        val rows = Dashboard.syncRows(status, onlineWithinMs = 15 * 60_000)
        assertEquals(SyncRow("Y", "You", "Synced just now", true), rows[0])
        assertEquals(SyncRow("A", "Aarav", "Last synced 3h ago", false), rows[1])
        assertEquals(SyncRow("N", "New", "Never synced", false), rows[2])
        assertEquals("1 of 3 online", Dashboard.syncSummary(rows))
    }
}
