/* Copyright (C) 2026, GPL-3.0-or-later */
package dev.octoshrimpy.quik.forwarding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardingRuleEvaluatorTest {
    private val evaluator = ForwardingRuleEvaluator { first, second ->
        first.filter(Char::isDigit).takeLast(10) == second.filter(Char::isDigit).takeLast(10)
    }

    @Test
    fun denyAlwaysWins() {
        val rules = listOf(
            addressRule(RuleAction.ALLOW, "+1 555 123 4567"),
            ForwardingRule(
                action = RuleAction.DENY,
                matcher = RuleMatcher.TEXT_LITERAL,
                value = "secret"
            )
        )

        assertFalse(eligible(ForwardingConfig(allowListOnly = true), rules, "this is secret"))
    }

    @Test
    fun allGroupRecipientsAndTextMustPassInAllowOnlyMode() {
        val rules = listOf(
            addressRule(RuleAction.ALLOW, "+1 555 123 4567"),
            addressRule(RuleAction.ALLOW, "+1 555 987 6543"),
            ForwardingRule(
                action = RuleAction.ALLOW,
                matcher = RuleMatcher.TEXT_REGEX,
                value = "invoice\\s+#\\d+"
            )
        )
        val config = ForwardingConfig(allowListOnly = true)

        assertTrue(eligible(config, rules, "Invoice #42"))
        assertFalse(eligible(config, rules, "Status update"))
        assertFalse(
            evaluator.isEligible(
                config,
                rules,
                ForwardingDirection.OUTGOING,
                ForwardingMessageKind.SMS,
                listOf("+1 555 123 4567", "+1 555 000 0000"),
                "Invoice #42"
            )
        )
    }

    @Test
    fun allowOnlyRequiresAtLeastOneApplicableAllowRule() {
        val incomingOnly = addressRule(RuleAction.ALLOW, "+1 555 123 4567").copy(
            incoming = true,
            outgoing = false
        )
        assertFalse(
            evaluator.isEligible(
                ForwardingConfig(allowListOnly = true),
                listOf(incomingOnly),
                ForwardingDirection.OUTGOING,
                ForwardingMessageKind.SMS,
                listOf("+1 555 123 4567"),
                "hello"
            )
        )
    }

    @Test
    fun allExceptDeniedAcceptsWhenNoDenyMatches() {
        val rule = ForwardingRule(
            action = RuleAction.DENY,
            matcher = RuleMatcher.TEXT_LITERAL,
            value = "blocked",
            mms = false
        )
        assertTrue(eligible(ForwardingConfig(), listOf(rule), "ordinary message"))
    }

    @Test
    fun regexValidationUsesRe2() {
        assertTrue(
            evaluator.validate(
                ForwardingRule(
                    action = RuleAction.ALLOW,
                    matcher = RuleMatcher.TEXT_REGEX,
                    value = "("
                )
            )?.startsWith("Invalid safe regular expression") == true
        )
        assertNull(
            evaluator.validate(
                ForwardingRule(
                    action = RuleAction.ALLOW,
                    matcher = RuleMatcher.TEXT_REGEX,
                    value = "invoice\\s+#\\d+"
                )
            )
        )
    }

    private fun eligible(
        config: ForwardingConfig,
        rules: List<ForwardingRule>,
        text: String
    ) = evaluator.isEligible(
        config,
        rules,
        ForwardingDirection.OUTGOING,
        ForwardingMessageKind.SMS,
        listOf("+1 555 123 4567", "+1 555 987 6543"),
        text
    )

    private fun addressRule(action: RuleAction, address: String) = ForwardingRule(
        action = action,
        matcher = RuleMatcher.ADDRESS_EXACT,
        value = address
    )
}
