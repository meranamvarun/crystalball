package com.hearth.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What Hearth keeps from a bank SMS. The raw text is never stored or synced. */
@Serializable
data class ParsedSms(
    @SerialName("amount_minor") val amountMinor: Long,
    val direction: Direction,
    val merchant: String,
    @SerialName("account_last4") val accountLast4: String?,
    val reference: String?,
)

/** On-device parser for Indian bank / UPI / card SMS (contracts/sms.json). */
object SmsParser {
    private const val UNKNOWN = "Unknown"
    private const val MAX_MERCHANT = 60

    private val ignore = Regex("(?i)\\b(otp|one time password|failed|declined|unsuccessful|requested|will be debited)\\b")
    private val accountMention = Regex("(?i)(\\ba/?c\\b|\\bacct\\b|\\baccount\\b|\\bcard\\b|\\bupi\\b|\\bvpa\\b)")
    private const val NUMBER = "([0-9][0-9,]*(?:\\.[0-9]{1,2})?)"
    private val amountPatterns = listOf(
        Regex("(?i)(?:\\brs\\.?|\\binr|₹)\\s*$NUMBER"),
        Regex("(?i)\\bdebited by\\s+$NUMBER"),
    )
    private val debitVerb = Regex("(?i)\\b(debited|spent|sent|paid|withdrawn|debit|purchase)\\b")
    private val creditVerb = Regex("(?i)\\b(credited|received|deposited|refund)\\b")
    private val last4 = Regex("(?i)(?:\\ba/?c|\\bacct|\\baccount|\\bcard)(?:\\s*no\\.?)?\\s*(?:ending\\s*)?[x*]*\\s*(\\d{4})\\b")
    private val reference = Regex("(?i)\\b(?:upi\\s*ref(?:\\s*no)?|ref\\s*no|refno|ref|utr)\\b[:.\\s#]*([0-9]{6,})")
    private val vpa = Regex("(?i)\\bto\\s+(?:vpa\\s+)?([a-z0-9._-]+)@[a-z0-9]+")
    private const val TERMINATOR =
        "(?=\\s+on\\b|\\s+ref|\\s+upi\\b|\\.\\s|\\.?\\s*avl\\b|\\s+using\\b|\\.?\\s*\\n|\\.?\\s*$)"
    private val rejectCandidate =
        Regex("(?i)^(a/?c\\b|ac\\b|acct|account|your\\b|rs\\b|rs\\.|inr\\b|₹|date\\b|card\\b|bank\\b|neft\\b|imps\\b|rtgs\\b)")
    private val debitKeywords = listOf("trf to", "towards", "at", "to", "for", "on")
    private val creditKeywords = listOf("from", "by")
    private val keywordPatterns = (debitKeywords + creditKeywords).distinct().associateWith { kw ->
        Regex("(?i)\\b${kw.replace(" ", "\\s+")}\\s+([^\\n]+?)$TERMINATOR")
    }

    fun parse(sender: String, body: String): ParsedSms? {
        if (sender.isBlank() || ignore.containsMatchIn(body) || !accountMention.containsMatchIn(body)) return null
        val amount = amountPatterns.mapNotNull { it.find(body) }.minByOrNull { it.range.first } ?: return null
        val direction = direction(body) ?: return null
        return ParsedSms(
            amountMinor = toMinor(amount.groupValues[1]),
            direction = direction,
            merchant = merchant(body, direction),
            accountLast4 = last4.find(body)?.groupValues?.get(1),
            reference = reference.find(body)?.groupValues?.get(1),
        )
    }

    private fun direction(body: String): Direction? {
        val debit = debitVerb.find(body)?.range?.first
        val credit = creditVerb.find(body)?.range?.first
        return when {
            debit == null && credit == null -> null
            credit == null -> Direction.DEBIT
            debit == null -> Direction.CREDIT
            debit <= credit -> Direction.DEBIT
            else -> Direction.CREDIT
        }
    }

    private fun toMinor(text: String): Long {
        val clean = text.replace(",", "")
        val rupees = clean.substringBefore('.')
        val paise = clean.substringAfter('.', "").padEnd(2, '0').take(2)
        return rupees.toLong() * 100 + paise.toLong()
    }

    private fun merchant(body: String, direction: Direction): String {
        if (direction == Direction.DEBIT) {
            vpa.find(body)?.let { return it.groupValues[1] }
        }
        val keywords = if (direction == Direction.DEBIT) debitKeywords else creditKeywords
        for (kw in keywords) {
            for (m in keywordPatterns.getValue(kw).findAll(body)) {
                val candidate = m.groupValues[1].trim().trimEnd('.', ',').trim()
                if (isPlausible(candidate, kw)) return candidate.take(MAX_MERCHANT)
            }
        }
        return UNKNOWN
    }

    private fun isPlausible(candidate: String, keyword: String): Boolean = when {
        candidate.isEmpty() || candidate.first().isDigit() -> false
        rejectCandidate.containsMatchIn(candidate) -> false
        // "on" is mostly followed by dates; only trust it for ALL-CAPS merchant names (card SMS).
        keyword == "on" -> candidate.none { it.isLowerCase() }
        else -> true
    }
}
