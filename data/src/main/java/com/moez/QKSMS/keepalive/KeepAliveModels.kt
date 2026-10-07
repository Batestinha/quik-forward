/* Copyright (C) 2026, GPL-3.0-or-later */
package dev.octoshrimpy.quik.keepalive

import java.util.concurrent.TimeUnit

data class KeepAliveRule(
    val simKey: String,
    val subscriptionId: Int,
    val label: String,
    val enabled: Boolean = false,
    val days: Int = 120,
    val marginDays: Int = 7,
    val body: String = "SIM keep-alive",
    val destinations: List<String> = emptyList(),
    val lastActivityAt: Long = 0,
    val lastActivitySource: String = "No recorded activity",
    val revision: Long = 0,
    val destinationIndex: Int = 0,
    val failedPasses: Int = 0,
    val retryAt: Long = 0,
    val pendingAttempt: String? = null,
    val status: String = "Disabled",
    val callStatus: String = "Call history not checked"
)

data class KeepAliveAttempt(
    val id: String,
    val simKey: String,
    val subscriptionId: Int,
    val destination: String,
    val body: String,
    val createdAt: Long,
    val revision: Long,
    val providerUri: String? = null,
    val state: String = PREPARED,
    val result: Int? = null,
    val completedAt: Long = 0
) {
    companion object {
        const val PREPARED = "prepared"
        const val DISPATCHED = "dispatched"
        const val SENT = "sent"
        const val FAILED = "failed"
        const val UNKNOWN = "unknown"
        const val CANCELLED = "cancelled"
    }
}

data class SimIdentity(val key: String, val subscriptionId: Int, val slot: Int, val label: String, val number: String = "")
data class SimActivity(val simKey: String, val timestamp: Long, val source: String)
data class CallRecord(val id: Long, val date: Long, val duration: Long, val component: String, val account: String)
data class ActivitySnapshot(val events: List<SimActivity>, val callStatus: String)

/** Pure policy, shared by workers and the settings preview. */
object KeepAlivePolicy {
    val RESULT_TIMEOUT = TimeUnit.MINUTES.toMillis(10)

    // Remove visual formatting only. Never turn letters, extensions or service codes into a different number.
    fun destinations(text: String): List<String> = text.lines().map {
        it.trim().replace(Regex("[\\s().-]"), "")
    }.filter(String::isNotBlank)

    fun validationError(rule: KeepAliveRule): String? = when {
        rule.days !in 1..3650 -> "Enter an inactivity limit between 1 and 3650 days."
        rule.marginDays !in 0 until rule.days -> "The margin must be smaller than the inactivity limit."
        rule.destinations.isEmpty() -> "Add at least one destination phone number."
        rule.destinations.any { !it.matches(Regex("\\+?[0-9]{3,20}")) } -> "Enter valid phone numbers, one per line."
        rule.destinations.distinct().size != rule.destinations.size -> "Remove duplicate destinations."
        rule.body.isBlank() -> "Enter the keep-alive message."
        else -> null
    }

    fun dueAt(rule: KeepAliveRule): Long = if (rule.retryAt > 0) rule.retryAt else
        if (rule.lastActivityAt <= 0) 0 else rule.lastActivityAt + TimeUnit.DAYS.toMillis((rule.days - rule.marginDays).toLong())

    fun canSend(rule: KeepAliveRule, revision: Long, now: Long, force: Boolean = false): Boolean =
        rule.enabled && rule.revision == revision && rule.pendingAttempt == null &&
            !rule.status.startsWith("Paused:") && (force || dueAt(rule) <= now)

    fun activity(rule: KeepAliveRule, event: SimActivity): KeepAliveRule =
        if (event.simKey != rule.simKey) rule else activity(rule, event.timestamp, event.source)

    fun activity(rule: KeepAliveRule, timestamp: Long, source: String): KeepAliveRule =
        if (timestamp <= rule.lastActivityAt) rule else rule.copy(
            lastActivityAt = timestamp, lastActivitySource = source, revision = rule.revision + 1,
            retryAt = 0, destinationIndex = 0, failedPasses = 0,
            status = "Timer reset by $source"
        )

    fun failed(rule: KeepAliveRule, attempt: KeepAliveAttempt, now: Long, serviceFailure: Boolean): KeepAliveRule {
        val cleared = rule.copy(pendingAttempt = null)
        if (!rule.enabled || rule.revision != attempt.revision || rule.lastActivityAt > attempt.createdAt) return cleared
        if (!serviceFailure && rule.destinationIndex + 1 < rule.destinations.size) {
            return cleared.copy(destinationIndex = rule.destinationIndex + 1,
                retryAt = now + TimeUnit.MINUTES.toMillis(1), status = "Send failed; trying the next destination")
        }
        val hours = when (rule.failedPasses) { 0 -> 1L; 1 -> 6L; else -> 24L }
        return cleared.copy(destinationIndex = if (serviceFailure) rule.destinationIndex else 0,
            failedPasses = rule.failedPasses + 1, retryAt = now + TimeUnit.HOURS.toMillis(hours),
            status = "Send failed; retry scheduled")
    }
}
