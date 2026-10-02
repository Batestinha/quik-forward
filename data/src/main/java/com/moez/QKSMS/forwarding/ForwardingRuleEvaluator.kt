/* Copyright (C) 2026, GPL-3.0-or-later */
package dev.octoshrimpy.quik.forwarding

import com.google.re2j.Pattern

class ForwardingRuleEvaluator(
    private val addressEquals: (String, String) -> Boolean = { first, second ->
        first.trim().equals(second.trim(), ignoreCase = true)
    }
) {

    fun isEligible(
        config: ForwardingConfig,
        rules: List<ForwardingRule>,
        direction: ForwardingDirection,
        kind: ForwardingMessageKind,
        participants: List<String>,
        text: String
    ): Boolean {
        val applicable = rules.filter { rule -> rule.appliesTo(direction, kind) }

        // A deny rule always wins, regardless of the selected policy.
        if (applicable.any { it.action == RuleAction.DENY && matches(it, participants, text) }) {
            return false
        }

        if (!config.allowListOnly) return true

        val allows = applicable.filter { it.action == RuleAction.ALLOW }
        val addressRules = allows.filter { it.matcher == RuleMatcher.ADDRESS_EXACT }
        val textRules = allows.filter { it.matcher != RuleMatcher.ADDRESS_EXACT }
        if (addressRules.isEmpty() && textRules.isEmpty()) return false

        // Every member of an outgoing group must be explicitly allowed when address rules exist.
        val addressesPass = addressRules.isEmpty() || participants.isNotEmpty() &&
            participants.all { address ->
                addressRules.any { rule -> matches(rule, listOf(address), text) }
            }
        val textPass = textRules.isEmpty() || textRules.any { rule -> matches(rule, participants, text) }
        return addressesPass && textPass
    }

    fun validate(rule: ForwardingRule): String? = when {
        rule.value.isBlank() -> "Rule value cannot be empty"
        rule.matcher == RuleMatcher.TEXT_REGEX -> try {
            compile(rule)
            null
        } catch (error: RuntimeException) {
            "Invalid safe regular expression: ${error.message ?: "syntax error"}"
        }
        else -> null
    }

    private fun ForwardingRule.appliesTo(
        direction: ForwardingDirection,
        kind: ForwardingMessageKind
    ): Boolean =
        (direction == ForwardingDirection.INCOMING && incoming ||
            direction == ForwardingDirection.OUTGOING && outgoing) &&
            (kind == ForwardingMessageKind.SMS && sms || kind == ForwardingMessageKind.MMS && mms)

    private fun matches(
        rule: ForwardingRule,
        participants: List<String>,
        text: String
    ): Boolean = when (rule.matcher) {
        RuleMatcher.ADDRESS_EXACT -> participants.any { address -> addressEquals(address, rule.value) }
        RuleMatcher.TEXT_LITERAL -> text.contains(rule.value, ignoreCase = !rule.caseSensitive)
        RuleMatcher.TEXT_REGEX -> compile(rule).matcher(text).find()
    }

    private fun compile(rule: ForwardingRule): Pattern = Pattern.compile(
        if (rule.caseSensitive) rule.value else "(?i)${rule.value}"
    )
}
