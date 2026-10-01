package com.hearth.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

object Entities {
    const val TRANSACTION = "transaction"
    const val BUDGET = "budget"
    const val BILL = "bill"
    const val HOLDING = "holding"
    const val LIABILITY = "liability"
    const val GOAL = "goal"
    const val NETWORTH_SNAPSHOT = "networth_snapshot"
    const val CATEGORY_RULE = "category_rule"
}

@Serializable
enum class Direction {
    @SerialName("debit") DEBIT,
    @SerialName("credit") CREDIT,
}

@Serializable
data class Transaction(
    val id: String = "",
    @SerialName("member_id") val memberId: String,
    @SerialName("amount_minor") val amountMinor: Long,
    val direction: Direction,
    val merchant: String,
    val category: String?,
    @SerialName("occurred_at_ms") val occurredAtMs: Long,
    val deleted: Boolean = false,
    val source: String = "manual",
    @SerialName("account_last4") val accountLast4: String? = null,
    val note: String? = null,
) {
    val isSpend: Boolean get() = !deleted && direction == Direction.DEBIT
}

/** Monthly budget; [memberId] null = family budget. */
@Serializable
data class Budget(
    val id: String = "",
    val category: String,
    @SerialName("cap_minor") val capMinor: Long,
    @SerialName("member_id") val memberId: String? = null,
    val deleted: Boolean = false,
)

@Serializable
enum class BillKind {
    @SerialName("recurring") RECURRING,
    @SerialName("variable") VARIABLE,
    @SerialName("autopay") AUTOPAY,
}

@Serializable
data class Bill(
    val id: String = "",
    val name: String,
    @SerialName("amount_minor") val amountMinor: Long,
    @SerialName("due_day") val dueDay: Int,
    val kind: BillKind,
    val deleted: Boolean = false,
)

@Serializable
enum class AssetClass(val wire: String) {
    @SerialName("equity") EQUITY("equity"),
    @SerialName("mutual_fund") MUTUAL_FUND("mutual_fund"),
    @SerialName("fd") FD("fd"),
    @SerialName("gold") GOLD("gold"),
    @SerialName("cash") CASH("cash"),
    @SerialName("other") OTHER("other"),
}

@Serializable
data class Holding(
    val id: String = "",
    @SerialName("member_id") val memberId: String,
    val name: String,
    @SerialName("asset_class") val assetClass: AssetClass,
    @SerialName("invested_minor") val investedMinor: Long,
    @SerialName("value_minor") val valueMinor: Long,
    val deleted: Boolean = false,
)

@Serializable
data class Liability(
    val id: String = "",
    @SerialName("member_id") val memberId: String,
    val name: String,
    @SerialName("outstanding_minor") val outstandingMinor: Long,
    val deleted: Boolean = false,
)

@Serializable
data class Goal(
    val id: String = "",
    val name: String,
    @SerialName("target_minor") val targetMinor: Long,
    @SerialName("saved_minor") val savedMinor: Long,
    @SerialName("target_month") val targetMonth: String,
    val deleted: Boolean = false,
)

@Serializable
data class NetWorthSnapshot(
    val id: String = "",
    val month: String,
    @SerialName("value_minor") val valueMinor: Long,
    val deleted: Boolean = false,
)

@Serializable
data class CategoryRule(
    val id: String = "",
    @SerialName("merchant_key") val merchantKey: String,
    val category: String,
    val deleted: Boolean = false,
)

/** Decode a record's payload into a typed entity, injecting `id` and `deleted` from the record. */
inline fun <reified T> fromRecord(rec: SyncRecord): T {
    val obj = requireNotNull(rec.payload.jsonObjectOrNull()) { "payload must be a JSON object" }
    val merged = JsonObject(obj + mapOf("id" to JsonPrimitive(rec.id), "deleted" to JsonPrimitive(rec.deleted)))
    return HearthJson.decodeFromJsonElement(merged)
}

/** Encode an entity as a sync payload (`id` and `deleted` live on the record, not the payload). */
inline fun <reified T> toPayload(value: T): JsonObject =
    JsonObject(HearthJson.encodeToJsonElement(value).jsonObject - setOf("id", "deleted"))

/** All live entities of a family decoded from sync records; tombstones and bad rows are skipped. */
data class Ledger(
    val transactions: List<Transaction> = emptyList(),
    val budgets: List<Budget> = emptyList(),
    val bills: List<Bill> = emptyList(),
    val holdings: List<Holding> = emptyList(),
    val liabilities: List<Liability> = emptyList(),
    val goals: List<Goal> = emptyList(),
    val snapshots: List<NetWorthSnapshot> = emptyList(),
    val categoryRules: List<CategoryRule> = emptyList(),
) {
    companion object {
        fun fromRecords(records: Iterable<SyncRecord>): Ledger {
            val live = records.filter { !it.deleted }
            fun <T> decode(entity: String, f: (SyncRecord) -> T): List<T> =
                live.filter { it.entity == entity }.mapNotNull { runCatching { f(it) }.getOrNull() }
            return Ledger(
                transactions = decode(Entities.TRANSACTION) { fromRecord<Transaction>(it) },
                budgets = decode(Entities.BUDGET) { fromRecord<Budget>(it) },
                bills = decode(Entities.BILL) { fromRecord<Bill>(it) },
                holdings = decode(Entities.HOLDING) { fromRecord<Holding>(it) },
                liabilities = decode(Entities.LIABILITY) { fromRecord<Liability>(it) },
                goals = decode(Entities.GOAL) { fromRecord<Goal>(it) },
                snapshots = decode(Entities.NETWORTH_SNAPSHOT) { fromRecord<NetWorthSnapshot>(it) },
                categoryRules = decode(Entities.CATEGORY_RULE) { fromRecord<CategoryRule>(it) },
            )
        }
    }
}
