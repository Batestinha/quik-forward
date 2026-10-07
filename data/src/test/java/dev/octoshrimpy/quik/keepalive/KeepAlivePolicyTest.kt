/* Copyright (C) 2026, GPL-3.0-or-later */
package dev.octoshrimpy.quik.keepalive

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class KeepAlivePolicyTest {
    private val start = 1700000000000L
    private val rule = KeepAliveRule("sim:a", 1, "SIM A", enabled = true,
        destinations = listOf("+351910000001", "+351910000002"), lastActivityAt = start)
    private fun days(value: Long) = TimeUnit.DAYS.toMillis(value)
    private fun attempt(r: KeepAliveRule = rule) = KeepAliveAttempt("attempt", r.simKey, r.subscriptionId,
        r.destinations[r.destinationIndex], r.body, start + days(113), r.revision)

    @Test fun uzoSendsSevenDaysBeforeThe120DayLimit() {
        assertFalse(KeepAlivePolicy.canSend(rule, 0, start + days(113) - 1))
        assertTrue(KeepAlivePolicy.canSend(rule, 0, start + days(113)))
        assertEquals(start + days(113), KeepAlivePolicy.dueAt(rule))
    }

    @Test fun onlyTheSimThatWasUsedHasItsDeadlineMoved() {
        val event = SimActivity("sim:b", start + days(50), "connected outgoing call")
        assertEquals(rule, KeepAlivePolicy.activity(rule, event))
        val b = rule.copy(simKey = "sim:b", subscriptionId = 2)
        assertEquals(start + days(163), KeepAlivePolicy.dueAt(KeepAlivePolicy.activity(b, event)))
    }

    @Test fun replayingOldHistoryNeverExtendsTheTimer() {
        val updated = KeepAlivePolicy.activity(rule, start + days(3), "sent SMS")
        assertEquals(updated, KeepAlivePolicy.activity(updated, start + days(3), "sent SMS"))
        assertEquals(updated, KeepAlivePolicy.activity(updated, start, "connected outgoing call"))
    }

    @Test fun newActivityInvalidatesAnAlreadyQueuedSendAndRetries() {
        val retried = rule.copy(destinationIndex = 1, failedPasses = 3, retryAt = start + days(113))
        val updated = KeepAlivePolicy.activity(retried, start + days(112), "sent MMS")
        assertFalse(KeepAlivePolicy.canSend(updated, rule.revision, start + days(113)))
        assertEquals(0, updated.failedPasses)
        assertEquals(0, updated.destinationIndex)
        assertEquals(0L, updated.retryAt)
        assertEquals(start + days(225), KeepAlivePolicy.dueAt(updated))
    }

    @Test fun unknownHistoryIsDueNowButOnlyAfterExplicitEnable() {
        val fresh = rule.copy(lastActivityAt = 0)
        assertTrue(KeepAlivePolicy.canSend(fresh, 0, start))
        assertFalse(KeepAlivePolicy.canSend(fresh.copy(enabled = false), 0, start))
    }

    @Test fun evenManualSendingCannotDuplicateAPendingAttemptOrBypassPause() {
        assertFalse(KeepAlivePolicy.canSend(rule.copy(pendingAttempt = "attempt"), 0, start + days(200), true))
        assertFalse(KeepAlivePolicy.canSend(rule.copy(status = "Paused: SIM unavailable"), 0, start + days(200), true))
        assertFalse(KeepAlivePolicy.canSend(rule.copy(enabled = false), 0, start + days(200), true))
    }

    @Test fun definiteFailureAdvancesToTheNextNumberWithoutResettingActivity() {
        val now = start + days(113)
        val next = KeepAlivePolicy.failed(rule.copy(pendingAttempt = "attempt"), attempt(), now, false)
        assertEquals(1, next.destinationIndex)
        assertEquals(start, next.lastActivityAt)
        assertNull(next.pendingAttempt)
        assertEquals(now + TimeUnit.MINUTES.toMillis(1), next.retryAt)
    }

    @Test fun noServiceKeepsTheSameDestinationAndBacksOff() {
        val next = KeepAlivePolicy.failed(rule.copy(pendingAttempt = "attempt"), attempt(), start + days(113), true)
        assertEquals(0, next.destinationIndex)
        assertEquals(1, next.failedPasses)
        assertEquals(start + days(113) + TimeUnit.HOURS.toMillis(1), next.retryAt)
    }

    @Test fun exhaustedFallbackPassesBackOffAtOneSixAnd24Hours() {
        val now = start + days(113)
        var current = rule
        listOf(1L, 6L, 24L, 24L).forEach { hours ->
            current = current.copy(destinationIndex = 1, pendingAttempt = "attempt")
            current = KeepAlivePolicy.failed(current, attempt(current), now, false)
            assertEquals(now + TimeUnit.HOURS.toMillis(hours), current.retryAt)
            assertEquals(0, current.destinationIndex)
        }
    }

    @Test fun aLateFailureCannotRestartFallbacksAfterOrdinaryActivityOrDisable() {
        val original = rule.copy(pendingAttempt = "attempt")
        val updated = KeepAlivePolicy.activity(original, start + days(114), "connected outgoing call")
        val handled = KeepAlivePolicy.failed(updated, attempt(), start + days(115), false)
        assertEquals(0L, handled.retryAt)
        assertEquals(0, handled.destinationIndex)
        assertNull(handled.pendingAttempt)
        assertEquals(0L, KeepAlivePolicy.failed(original.copy(enabled = false), attempt(), start + days(113), false).retryAt)
    }

    @Test fun pendingAttemptAndRuleRoundTripWithoutLosingDefaultsOrDispatchState() {
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val rules = moshi.adapter(KeepAliveRule::class.java)
        val attempts = moshi.adapter(KeepAliveAttempt::class.java)
        val pending = rule.copy(pendingAttempt = "attempt", retryAt = start)
        val dispatched = attempt().copy(state = KeepAliveAttempt.DISPATCHED, providerUri = "content://sms/123")
        assertEquals(pending, rules.fromJson(rules.toJson(pending)))
        assertEquals(dispatched, attempts.fromJson(attempts.toJson(dispatched)))
    }

    @Test fun invalidIntervalsOrDestinationsCannotBeEnabled() {
        assertNull(KeepAlivePolicy.validationError(rule))
        assertNotNull(KeepAlivePolicy.validationError(rule.copy(marginDays = 120)))
        assertNotNull(KeepAlivePolicy.validationError(rule.copy(days = 0)))
        assertNotNull(KeepAlivePolicy.validationError(rule.copy(destinations = emptyList())))
        assertNotNull(KeepAlivePolicy.validationError(rule.copy(destinations = listOf("*123#"))))
        assertNotNull(KeepAlivePolicy.validationError(rule.copy(body = "")))
    }

    @Test fun numberFormattingNeverConvertsLettersOrServiceCodesIntoADialableNumber() {
        assertEquals(listOf("+351910000001", "910000002"), KeepAlivePolicy.destinations("+351 (910) 000-001\n\n910.000.002"))
        listOf("1-800-FLOWERS", "*123#", "910000001 ext 2", "910000001,2").forEach {
            assertNotNull(KeepAlivePolicy.validationError(rule.copy(destinations = KeepAlivePolicy.destinations(it))))
        }
    }

    @Test fun shizukuParserAcceptsOnlyTheFixedMetadataProjection() {
        val row = "Row: 0 _id=42, date=1700000000000, duration=15, subscription_component_name=com.android.phone/.Service, subscription_id=opaque-sim-account"
        assertEquals(CallRecord(42, start, 15, "com.android.phone/.Service", "opaque-sim-account"), KeepAliveShizuku.parseRow(row))
        assertNull(KeepAliveShizuku.parseRow("java.lang.SecurityException: permission denied"))
    }
}
