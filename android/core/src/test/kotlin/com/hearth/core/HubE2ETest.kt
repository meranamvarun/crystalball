package com.hearth.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Cross-stack test: two Kotlin "phones" talk to a real hearth-server (Rust) process.
 * Runs only when HEARTH_HUB_URL is set — `python3 harness/e2e.py` starts a hub and sets it.
 */
class HubE2ETest {
    private val tz = 330
    private val anchor = LocalDate.parse("2026-09-29")
    private fun at(s: String) = localToMs(LocalDateTime.parse(s), tz)

    @Test
    fun twoPhonesShareOneLedgerThroughTheHub() {
        val url = System.getenv("HEARTH_HUB_URL").orEmpty()
        assumeTrue(url.isNotEmpty(), "HEARTH_HUB_URL not set; run python3 harness/e2e.py")
        val hub = HubClient(url)

        val you = hub.createFamily(CreateFamilyRequest("E2E Family", "You", "phone-a", tz))
        val priya = hub.join(JoinFamilyRequest(you.inviteCode, "Priya", "phone-b"))
        val phoneA = InMemoryLocalStore("phone-a")
        val phoneB = InMemoryLocalStore("phone-b")
        val categorizer = Categorizer(CategoryConfig.default(), emptyMap())

        fun ingest(store: InMemoryLocalStore, member: String, body: String, atMs: Long) {
            val (record, clock) = assertNotNull(ingestSms("VM-BANK", body, atMs, member, categorizer, store.clock))
            store.clock = clock
            store.writeLocal(record)
        }

        ingest(phoneA, you.memberId, "Sent Rs.649.00\nFrom HDFC Bank A/C *1234\nTo Netflix\nOn 25/09/26\nRef 627312345678", at("2026-09-25T08:00"))
        ingest(phoneA, you.memberId, "Rs 1,840.00 debited from A/c no. XX1234 on 29-09-26 at BIG BAZAAR. Avl Bal Rs 45,210.55", at("2026-09-29T10:00"))
        ingest(
            phoneA,
            you.memberId,
            "Dear UPI user A/C X4521 debited by 850.0 on date 26Sep26 trf to PAYTM-XYZTRD4521 Refno 626912345678.",
            at("2026-09-26T13:00"),
        )
        // Joint account: the same bank event lands on both phones.
        val joint = "INR 780.00 spent using ICICI Bank Card XX4521 on 29-Sep-26 on ZOMATO. Avl Limit: INR 1,23,456.78."
        ingest(phoneA, you.memberId, joint, at("2026-09-29T20:11"))
        ingest(phoneB, priya.memberId, joint, at("2026-09-29T20:11"))

        SyncEngine.syncOnce(phoneA, hub, you.token, nowMs = at("2026-09-29T21:00"))
        SyncEngine.syncOnce(phoneB, hub, priya.token, nowMs = at("2026-09-29T21:00"))
        assertEquals(4, Ledger.fromRecords(phoneB.records()).transactions.size, "joint-account SMS deduplicated")

        // Priya categorizes the unknown merchant; the rule reaches You's phone.
        val paytm = phoneB.records().first { fromRecord<Transaction>(it).merchant == "PAYTM-XYZTRD4521" }
        val (updated, rule, clock) = recategorize(paytm, "Groceries", at("2026-09-29T21:05"), phoneB.clock)
        phoneB.clock = clock
        phoneB.writeLocal(updated)
        phoneB.writeLocal(rule)
        SyncEngine.syncOnce(phoneB, hub, priya.token, nowMs = at("2026-09-29T21:06"))
        SyncEngine.syncOnce(phoneA, hub, you.token, nowMs = at("2026-09-29T21:07"))

        val ledgerA = Ledger.fromRecords(phoneA.records())
        assertEquals("Groceries", ledgerA.transactions.first { it.merchant == "PAYTM-XYZTRD4521" }.category)
        assertEquals(
            "Groceries",
            Categorizer(CategoryConfig.default(), emptyMap()).withLearned(ledgerA.categoryRules).categorize("PAYTM-XYZTRD4521", Direction.DEBIT),
        )

        // Kotlin (phone) and Rust (hub) compute the same family report from the same records.
        val local = HearthJson.encodeToJsonElement(spendReport(ledgerA.transactions, Scope.Family, Range.MONTH, anchor, tz)).jsonObject
        val remote = hub.report(you.token, scope = "family", range = "month", anchor = anchor.toString())
        val shared = local.keys
        assertEquals(local, JsonObject(remote.filterKeys { it in shared }), "Kotlin and Rust reports diverge")
        assertEquals(411_900L, local.getValue("total_minor").toString().toLong())

        val status = hub.status(you.token)
        assertEquals(setOf("You", "Priya"), status.members.map { it.name }.toSet())
    }
}
