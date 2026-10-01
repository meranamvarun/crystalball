package com.hearth.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

private fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

/**
 * Deterministic id for an SMS transaction so the same bank event seen on two family phones
 * (joint account) collapses into one record: bank reference when present, otherwise
 * amount + account + minute of receipt.
 */
fun smsTransactionId(p: ParsedSms, receivedAtMs: Long): String {
    val basis = if (p.reference != null) {
        "ref|${p.accountLast4}|${p.reference}"
    } else {
        "amt|${p.accountLast4}|${p.amountMinor}|${p.direction}|${receivedAtMs / 60_000}"
    }
    return "sms-" + sha256Hex(basis).take(24)
}

/**
 * Parse + categorize an incoming SMS into a transaction record stamped with the next HLC.
 * Returns null for messages that are not transactions. The raw [body] is not retained.
 */
fun ingestSms(
    sender: String,
    body: String,
    receivedAtMs: Long,
    memberId: String,
    categorizer: Categorizer,
    clock: Hlc,
): Pair<SyncRecord, Hlc>? {
    val parsed = SmsParser.parse(sender, body) ?: return null
    val txn = Transaction(
        memberId = memberId,
        amountMinor = parsed.amountMinor,
        direction = parsed.direction,
        merchant = parsed.merchant,
        category = categorizer.categorize(parsed.merchant, parsed.direction),
        occurredAtMs = receivedAtMs,
        source = "sms",
        accountLast4 = parsed.accountLast4,
    )
    val stamp = clock.tick(receivedAtMs)
    val record = SyncRecord(Entities.TRANSACTION, smsTransactionId(parsed, receivedAtMs), stamp.toString(), false, memberId, toPayload(txn))
    return record to stamp
}

/**
 * User picked a category for a transaction: returns the updated transaction record, a
 * `category_rule` record that teaches every family phone, and the advanced clock.
 */
fun recategorize(record: SyncRecord, category: String, nowMs: Long, clock: Hlc): Triple<SyncRecord, SyncRecord, Hlc> {
    val payload = requireNotNull(record.payload as? JsonObject) { "payload must be an object" }
    val first = clock.tick(nowMs)
    val updated = record.copy(
        hlc = first.toString(),
        payload = JsonObject(payload + ("category" to JsonPrimitive(category))),
    )
    val key = merchantKey(fromRecord<Transaction>(record).merchant)
    val second = first.tick(nowMs)
    val rule = SyncRecord(
        entity = Entities.CATEGORY_RULE,
        id = "rule-" + sha256Hex(key).take(16),
        hlc = second.toString(),
        deleted = false,
        author = record.author,
        payload = toPayload(CategoryRule(merchantKey = key, category = category)),
    )
    return Triple(updated, rule, second)
}

/** A tombstone for [record] (delete wins only if its HLC is newer). */
fun tombstone(record: SyncRecord, nowMs: Long, clock: Hlc): Pair<SyncRecord, Hlc> {
    val stamp = clock.tick(nowMs)
    return record.copy(hlc = stamp.toString(), deleted = true) to stamp
}
