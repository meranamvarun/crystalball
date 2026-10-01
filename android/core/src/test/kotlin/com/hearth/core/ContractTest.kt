package com.hearth.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/** Runs the shared fixtures in /contracts against the Kotlin core. The Rust core runs the same files. */
class ContractTest {
    private fun contract(name: String): JsonObject {
        val dir = System.getProperty("contracts.dir") ?: fail("contracts.dir system property not set")
        return HearthJson.parseToJsonElement(File(dir, name).readText()).jsonObject
    }

    private val JsonElement.str: String get() = jsonPrimitive.content
    private fun JsonObject.arr(key: String): JsonArray = getValue(key).jsonArray
    private fun date(e: JsonElement): LocalDate = LocalDate.parse(e.str)

    private fun scope(case: JsonObject): Scope = when (case.getValue("scope").str) {
        "family" -> Scope.Family
        "me" -> Scope.Member(case.getValue("me").str)
        else -> fail("unknown scope")
    }

    private fun transactions(list: JsonArray, tz: Int): List<Transaction> = list.map { el ->
        val o = el.jsonObject
        Transaction(
            id = o.getValue("id").str,
            memberId = o.getValue("member_id").str,
            amountMinor = o.getValue("amount_minor").jsonPrimitive.long,
            direction = HearthJson.decodeFromJsonElement(o.getValue("direction")),
            merchant = o.getValue("merchant").str,
            category = o["category"]?.takeUnless { it is JsonNull }?.str,
            occurredAtMs = localToMs(LocalDateTime.parse(o.getValue("at").str), tz),
            deleted = o["deleted"]?.jsonPrimitive?.boolean ?: false,
        )
    }

    private data class Dataset(val txns: List<Transaction>, val budgets: List<Budget>, val tz: Int)

    private fun dataset(): Dataset {
        val d = contract("dataset.json")
        val tz = d.getValue("tz_offset_minutes").jsonPrimitive.int
        val budgets = d.arr("budgets").map { HearthJson.decodeFromJsonElement<Budget>(it) }
        return Dataset(transactions(d.arr("transactions"), tz), budgets, tz)
    }

    private fun assertContract(expected: JsonElement, actual: JsonElement, name: String) =
        assertEquals(expected, actual, "contract case '$name' failed")

    @Test
    fun contractHlcTick() {
        for (el in contract("hlc.json").arr("tick")) {
            val c = el.jsonObject
            val clock = HearthJson.decodeFromJsonElement<Hlc>(c.getValue("state"))
            val got = clock.tick(c.getValue("now_ms").jsonPrimitive.long)
            assertEquals(c.getValue("expect").str, got.toString(), "contract case '${c["name"]}' failed")
        }
    }

    @Test
    fun contractHlcRecv() {
        for (el in contract("hlc.json").arr("recv")) {
            val c = el.jsonObject
            val clock = HearthJson.decodeFromJsonElement<Hlc>(c.getValue("state"))
            val got = clock.recv(Hlc.parse(c.getValue("remote").str), c.getValue("now_ms").jsonPrimitive.long)
            assertEquals(c.getValue("expect").str, got.toString(), "contract case '${c["name"]}' failed")
        }
    }

    @Test
    fun contractHlcParse() {
        for (el in contract("hlc.json").arr("parse")) {
            val c = el.jsonObject
            val input = c.getValue("input").str
            val parsed = Hlc.parseOrNull(input)
            if (c.getValue("valid").jsonPrimitive.boolean) {
                val h = parsed ?: fail("contract case '$input' failed: expected valid")
                assertEquals(c.getValue("millis").jsonPrimitive.long, h.millis)
                assertEquals(c.getValue("counter").jsonPrimitive.int, h.counter)
                assertEquals(c.getValue("node").str, h.node)
                assertEquals(input, h.toString())
            } else {
                assertNull(parsed, "contract case '$input' failed: expected invalid")
            }
        }
    }

    @Test
    fun contractMerge() {
        for (el in contract("merge.json").arr("cases")) {
            val c = el.jsonObject
            val name = c.getValue("name").str
            val store = MemoryStore()
            store.merge(c.arr("local").map { HearthJson.decodeFromJsonElement<SyncRecord>(it) })
            val applied = store.merge(c.arr("incoming").map { HearthJson.decodeFromJsonElement<SyncRecord>(it) })
            assertEquals(c.arr("applied").map { it.str }, applied.map { it.key }, "contract case '$name' failed")
            assertContract(c.getValue("state"), HearthJson.encodeToJsonElement(store.records()), name)
        }
    }

    @Test
    fun contractPeriods() {
        for (el in contract("periods.json").arr("cases")) {
            val c = el.jsonObject
            val range = Range.parse(c.getValue("range").str)
            val anchor = date(c.getValue("anchor"))
            val label = "${c["range"]} ${c["anchor"]}"
            assertEquals(Period(date(c.getValue("start")), date(c.getValue("end"))), range.period(anchor), "contract case '$label' failed")
            assertEquals(
                Period(date(c.getValue("prev_start")), date(c.getValue("prev_end"))),
                range.previous(anchor),
                "contract case '$label' failed",
            )
        }
    }

    @Test
    fun contractReports() {
        val (txns, _, tz) = dataset()
        for (el in contract("reports.json").arr("cases")) {
            val c = el.jsonObject
            val report = spendReport(txns, scope(c), Range.parse(c.getValue("range").str), date(c.getValue("anchor")), tz)
            assertContract(c.getValue("expect"), HearthJson.encodeToJsonElement(report), c.getValue("name").str)
        }
    }

    @Test
    fun contractAnalyticsTrend() {
        val (txns, _, tz) = dataset()
        for (el in contract("analytics.json").arr("trend")) {
            val c = el.jsonObject
            val got = monthlyTrend(txns, scope(c), date(c.getValue("anchor")), c.getValue("months").jsonPrimitive.int, tz)
            assertContract(c.getValue("expect"), HearthJson.encodeToJsonElement(got), "trend ${c["anchor"]}")
        }
    }

    @Test
    fun contractAnalyticsBudgets() {
        val (txns, budgets, tz) = dataset()
        for (el in contract("analytics.json").arr("budgets")) {
            val c = el.jsonObject
            val got = budgetStatus(txns, budgets, scope(c), date(c.getValue("anchor")), tz)
            assertContract(c.getValue("expect"), HearthJson.encodeToJsonElement(got), "budgets ${c["scope"]}")
        }
    }

    @Test
    fun contractAnalyticsAnomalies() {
        val section = contract("analytics.json").getValue("anomalies").jsonObject
        val tz = section.getValue("tz_offset_minutes").jsonPrimitive.int
        val txns = transactions(section.arr("transactions"), tz)
        for (el in section.arr("cases")) {
            val c = el.jsonObject
            val got = anomalies(txns, scope(c), date(c.getValue("anchor")), tz)
            assertContract(c.getValue("expect"), HearthJson.encodeToJsonElement(got), "anomalies ${c["scope"]} ${c["anchor"]}")
        }
    }

    @Test
    fun contractPortfolio() {
        val doc = contract("portfolio.json")
        val input = doc.getValue("input").jsonObject
        val got = summarizePortfolio(
            holdings = input.arr("holdings").map { HearthJson.decodeFromJsonElement<Holding>(it) },
            liabilities = input.arr("liabilities").map { HearthJson.decodeFromJsonElement<Liability>(it) },
            goals = input.arr("goals").map { HearthJson.decodeFromJsonElement<Goal>(it) },
            snapshots = input.arr("snapshots").map { HearthJson.decodeFromJsonElement<NetWorthSnapshot>(it) },
        )
        assertContract(doc.getValue("expect"), HearthJson.encodeToJsonElement(got), "portfolio")
    }

    @Test
    fun contractSmsParsing() {
        for (el in contract("sms.json").arr("cases")) {
            val c = el.jsonObject
            val parsed = SmsParser.parse(c.getValue("sender").str, c.getValue("body").str)
            val actual = parsed?.let { HearthJson.encodeToJsonElement(it) } ?: JsonNull
            assertContract(c.getValue("expect"), actual, c.getValue("name").str)
        }
    }

    @Test
    fun contractCategorize() {
        val doc = contract("categorize.json")
        val learned = doc.getValue("learned").jsonObject.mapValues { it.value.str }
        val categorizer = Categorizer(CategoryConfig.default(), learned)
        for (el in doc.arr("cases")) {
            val c = el.jsonObject
            val merchant = c.getValue("merchant").str
            assertEquals(c.getValue("key").str, merchantKey(merchant), "contract case 'key $merchant' failed")
            val direction = HearthJson.decodeFromJsonElement<Direction>(c.getValue("direction"))
            val expected = c["expect"]?.takeUnless { it is JsonNull }?.str
            assertEquals(expected, categorizer.categorize(merchant, direction), "contract case '$merchant' failed")
        }
    }
}
