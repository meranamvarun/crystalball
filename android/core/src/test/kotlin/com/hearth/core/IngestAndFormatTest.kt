package com.hearth.core

import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IngestTest {
    private val categorizer = Categorizer(CategoryConfig.default(), emptyMap())
    private val body = "Sent Rs.649.00\nFrom HDFC Bank A/C *1234\nTo Netflix\nOn 25/09/26\nRef 627312345678"

    @Test
    fun smsBecomesCategorizedTransactionRecord() {
        val clock = Hlc(0, 0, "phone-a")
        val result = ingestSms("VM-HDFCBK", body, receivedAtMs = 1_790_692_200_000, memberId = "m_you", categorizer, clock)
        assertNotNull(result)
        val (record, nextClock) = result
        assertEquals("transaction", record.entity)
        assertEquals("1790692200000-0000-phone-a", record.hlc)
        assertEquals(nextClock.toString(), record.hlc)
        val txn = fromRecord<Transaction>(record)
        assertEquals(64_900L, txn.amountMinor)
        assertEquals("Entertainment", txn.category)
        assertEquals("sms", txn.source)
        assertEquals("1234", txn.accountLast4)
        assertEquals(1_790_692_200_000, record.payload.jsonObjectOrNull()?.get("occurred_at_ms")?.jsonPrimitive?.long)
    }

    @Test
    fun rawSmsBodyNeverEntersThePayload() {
        val (record, _) = ingestSms("VM-HDFCBK", body, 1_790_692_200_000, "m_you", categorizer, Hlc(0, 0, "a"))!!
        assertFalse(record.payload.toString().contains("Not You"))
        assertFalse(record.payload.toString().contains("HDFC Bank A/C"))
    }

    @Test
    fun sameBankEventOnTwoPhonesGetsTheSameId() {
        val a = ingestSms("VM-HDFCBK", body, 1_790_692_200_000, "m_you", categorizer, Hlc(0, 0, "a"))!!.first
        val b = ingestSms("VM-HDFCBK", body, 1_790_692_201_500, "m_priya", categorizer, Hlc(0, 0, "b"))!!.first
        assertEquals(a.id, b.id)
        val other = ingestSms("VM-HDFCBK", body.replace("627312345678", "627312345679"), 1_790_692_200_000, "m_you", categorizer, Hlc(0, 0, "a"))!!.first
        assertNotEquals(a.id, other.id)
    }

    @Test
    fun nonTransactionSmsIsIgnored() {
        assertNull(ingestSms("VM-OTP", "123456 is your OTP. Do not share.", 1, "m", categorizer, Hlc(0, 0, "a")))
    }

    @Test
    fun recategorizeBumpsHlcAndTeachesFamilyRule() {
        val (record, clock) = ingestSms("AD-SBIUPI", "Dear UPI user A/C X4521 debited by 850.0 on date 26Sep26 trf to PAYTM-XYZTRD4521 Refno 626912345678.", 1_000, "m_you", categorizer, Hlc(0, 0, "a"))!!
        assertNull(fromRecord<Transaction>(record).category)
        val (updated, rule, after) = recategorize(record, "Groceries", nowMs = 500, clock = clock)
        assertTrue(updated.hlc > record.hlc, "edit must win LWW against the original")
        assertEquals("Groceries", fromRecord<Transaction>(updated).category)
        assertEquals("category_rule", rule.entity)
        assertEquals("Groceries", fromRecord<CategoryRule>(rule).category)
        assertEquals("PAYTM XYZTRD4521", fromRecord<CategoryRule>(rule).merchantKey)
        assertTrue(rule.hlc > updated.hlc)
        assertEquals(after.toString(), rule.hlc)
    }
}

class FormatTest {
    @Test
    fun indianRupeeGrouping() {
        assertEquals("₹0", formatInr(0))
        assertEquals("₹850", formatInr(85_000))
        assertEquals("₹43,700", formatInr(4_370_000))
        assertEquals("₹1,18,400", formatInr(11_840_000))
        assertEquals("₹18,42,000", formatInr(184_200_000))
        assertEquals("₹1,00,00,000", formatInr(1_000_000_000))
        assertEquals("-₹2,150", formatInr(-215_000))
        assertEquals("₹650", formatInr(64_950)) // rounds half away from zero to whole rupees
    }

    @Test
    fun deltaLabel() {
        assertEquals("-4.2%", formatBps(-420))
        assertEquals("+13.2%", formatBps(1_320))
        assertEquals("0.0%", formatBps(0))
        assertEquals("—", formatBps(null))
    }

    @Test
    fun relativeSyncTime() {
        val now = 1_000_000_000L
        assertEquals("never", syncedAgo(null, now))
        assertEquals("just now", syncedAgo(now - 30_000, now))
        assertEquals("2 min ago", syncedAgo(now - 120_000, now))
        assertEquals("3h ago", syncedAgo(now - 3 * 3_600_000, now))
        assertEquals("2d ago", syncedAgo(now - 2 * 86_400_000, now))
    }
}
