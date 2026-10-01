package com.hearth.core

import kotlinx.serialization.Serializable

@Serializable
data class KeywordRule(val category: String, val keywords: List<String>)

/** Default categories and keyword rules (contracts/categories.json, bundled as a resource). */
@Serializable
data class CategoryConfig(val categories: List<String>, val rules: List<KeywordRule>) {
    companion object {
        fun parse(json: String): CategoryConfig = HearthJson.decodeFromString(serializer(), json)

        fun default(): CategoryConfig {
            val stream = requireNotNull(CategoryConfig::class.java.getResourceAsStream("/categories.json")) {
                "categories.json missing from the classpath"
            }
            return stream.bufferedReader().use { parse(it.readText()) }
        }
    }
}

private val NON_ALNUM = Regex("[^A-Z0-9]+")

/** Normalized merchant key: uppercase, each non-alphanumeric run → one space, trimmed. */
fun merchantKey(merchant: String): String = merchant.uppercase().replace(NON_ALNUM, " ").trim()

/**
 * Category for a merchant. Family-learned rules (merchant key → category, from `category_rule`
 * records) beat the defaults; credits are always "Income"; `null` means "ask the user".
 */
class Categorizer(private val config: CategoryConfig, private val learned: Map<String, String>) {
    val categories: List<String> get() = config.categories

    fun categorize(merchant: String, direction: Direction): String? {
        if (direction == Direction.CREDIT) return INCOME
        val key = merchantKey(merchant)
        learned[key]?.let { return it }
        val padded = " $key"
        return config.rules.firstOrNull { rule -> rule.keywords.any { padded.contains(" $it") } }?.category
    }

    fun withLearned(rules: List<CategoryRule>): Categorizer =
        Categorizer(config, learned + rules.filter { !it.deleted }.associate { it.merchantKey to it.category })

    companion object {
        const val INCOME = "Income"
    }
}
