/* Copyright (C) 2026, GPL-3.0-or-later */
package dev.octoshrimpy.quik.keepalive

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import dev.octoshrimpy.quik.compat.TelephonyCompat
import dev.octoshrimpy.quik.forwarding.ForwardingDirection
import dev.octoshrimpy.quik.forwarding.ForwardingScheduler
import dev.octoshrimpy.quik.manager.PermissionManager
import dev.octoshrimpy.quik.receiver.KeepAliveSentReceiver
import dev.octoshrimpy.quik.repository.MessageRepository
import dev.octoshrimpy.quik.repository.SyncRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeepAliveManager @Inject constructor(
    private val context: Context,
    val store: KeepAliveStore,
    val sims: KeepAliveSims,
    private val activitySource: KeepAliveActivitySource,
    private val permissions: PermissionManager,
    private val sync: SyncRepository,
    private val messages: MessageRepository,
    private val forwarding: ForwardingScheduler
) {
    @Synchronized fun preview(rule: KeepAliveRule): KeepAliveRule {
        if (!permissions.hasReadSms() || !permissions.hasPhone()) return rule
        val snapshot = activitySource.read(sims.discover().filter { it.key == rule.simKey })
        return snapshot.events.fold(rule, { current, event -> KeepAlivePolicy.activity(current, event) })
            .copy(callStatus = snapshot.callStatus)
    }

    @Synchronized fun save(input: KeepAliveRule) {
        require(KeepAlivePolicy.validationError(input) == null) { KeepAlivePolicy.validationError(input).orEmpty() }
        require(android.telephony.SmsMessage.calculateLength(input.body, false)[0] == 1) { "Keep the message within one SMS segment." }
        val old = store.rule(input.simKey)
        val current = old ?: preview(input)
        val updated = input.copy(lastActivityAt = current.lastActivityAt,
            lastActivitySource = current.lastActivitySource, callStatus = current.callStatus,
            revision = current.revision + 1, destinationIndex = 0, failedPasses = 0, retryAt = 0,
            pendingAttempt = current.pendingAttempt,
            status = if (input.enabled) "Scheduled" else "Disabled")
        store.save(updated)
        if (!updated.enabled) {
            KeepAliveScheduler.cancel(context, updated.simKey)
            clearNotification(updated.simKey + "send")
            clearNotification(updated.simKey + "calls")
        }
        KeepAliveScheduler.reconcile(context)
    }

    @Synchronized fun delete(key: String) {
        store.remove(key)
        KeepAliveScheduler.cancel(context, key)
        clearNotification(key + "send")
        clearNotification(key + "calls")
        KeepAliveScheduler.reconcile(context)
    }

    @Synchronized fun retryUnknown(key: String) {
        store.transaction {
            val rule = store.rule(key) ?: return@transaction
            val pending = rule.pendingAttempt?.let(store::attempt) ?: return@transaction
            check(pending.state == KeepAliveAttempt.UNKNOWN) { "A send is still in progress" }
            store.save(pending.copy(state = KeepAliveAttempt.CANCELLED))
            store.save(rule.copy(pendingAttempt = null, revision = rule.revision + 1, retryAt = System.currentTimeMillis(),
                status = "Retry requested"))
        }
        KeepAliveScheduler.reconcile(context)
    }

    @Synchronized fun recordSentMessage(messageId: Long, timestamp: Long) {
        val message = messages.getUnmanagedMessage(messageId) ?: return
        val sim = sims.discover().singleOrNull { it.subscriptionId == message.subId } ?: return
        store.rule(sim.key)?.let { store.save(KeepAlivePolicy.activity(it, timestamp, "sent ${message.type.uppercase()}")) }
    }

    @Synchronized fun refresh(schedule: Boolean = true) {
        val enabled = store.rules().filter { it.enabled }
        KeepAliveScheduler.periodic(context, enabled.isNotEmpty())
        if (enabled.isEmpty()) return
        if (!permissions.isDefaultSms() || !permissions.hasReadSms() || !permissions.hasPhone() || !permissions.hasSendSms()) {
            enabled.forEach { pause(it, "Paused: make QUIK the default SMS app and grant SMS/phone permissions") }
            return
        }
        val active = sims.discover()
        val selected = active.filter { sim -> enabled.any { it.simKey == sim.key } }
        val snapshot = if (selected.isEmpty()) ActivitySnapshot(emptyList(), "SIM unavailable") else
            activitySource.read(selected, enabled.minOf { it.lastActivityAt }.coerceAtLeast(0))
        enabled.forEach { previous ->
            val found = active.singleOrNull { it.key == previous.simKey }
            if (found == null) { pause(previous, "Paused: configured SIM is unavailable or its identity changed"); return@forEach }
            if (!found.key.startsWith("sim:")) {
                pause(previous, "Paused: SIM identity is unavailable; verify the default SMS role and phone permission")
                return@forEach
            }
            var rule = store.rule(previous.simKey) ?: return@forEach
            rule = rule.copy(subscriptionId = found.subscriptionId, label = found.label, callStatus = snapshot.callStatus)
            snapshot.events.filter { it.simKey == rule.simKey }.forEach {
                rule = KeepAlivePolicy.activity(rule, it)
            }
            if (rule.status.startsWith("Paused:")) rule = rule.copy(status = "Scheduled")
            store.save(rule)
            recoverPending(rule)
            if (snapshot.callStatus.contains("unavailable") || snapshot.callStatus.contains("incomplete"))
                notify(rule.simKey + "calls", rule.label, snapshot.callStatus)
            else clearNotification(rule.simKey + "calls")
        }
        if (schedule) scheduleAll()
    }

    private fun recoverPending(rule: KeepAliveRule) {
        val attempt = rule.pendingAttempt?.let(store::attempt) ?: return
        if (attempt.state == KeepAliveAttempt.PREPARED) {
            // The dispatch marker is persisted before calling SmsManager, so PREPARED is safe to abandon.
            attempt.providerUri?.let { uri -> updateMessage(uri, false, System.currentTimeMillis()) }
            store.transaction {
                store.save(attempt.copy(state = KeepAliveAttempt.CANCELLED))
                store.save(rule.copy(pendingAttempt = null))
            }
        } else if (attempt.state == KeepAliveAttempt.DISPATCHED || attempt.state == KeepAliveAttempt.UNKNOWN) {
            val box = attempt.providerUri?.let { uri -> context.contentResolver.query(Uri.parse(uri),
                arrayOf(Telephony.Sms.TYPE), null, null, null)?.use { if (it.moveToFirst()) it.getInt(0) else null } }
            when {
                box == Telephony.Sms.MESSAGE_TYPE_SENT -> result(attempt.id, Activity.RESULT_OK, attempt.createdAt)
                box == Telephony.Sms.MESSAGE_TYPE_FAILED -> result(attempt.id, SmsManager.RESULT_ERROR_GENERIC_FAILURE, System.currentTimeMillis())
                attempt.state == KeepAliveAttempt.UNKNOWN || System.currentTimeMillis() >= attempt.createdAt + KeepAlivePolicy.RESULT_TIMEOUT -> {
                    store.transaction {
                        store.save(attempt.copy(state = KeepAliveAttempt.UNKNOWN))
                        store.save(rule.copy(status = "Send outcome unknown; inspect the message before retrying"))
                    }
                    notify(rule.simKey + "send", rule.label, "Keep-alive send outcome unknown. Open settings to resolve it.")
                }
            }
        }
    }

    private fun scheduleAll() {
        store.rules().forEach { rule ->
            if (!rule.enabled || rule.status.startsWith("Paused:")) { KeepAliveScheduler.cancel(context, rule.simKey); return@forEach }
            val pending = rule.pendingAttempt?.let(store::attempt)
            if (pending?.state == KeepAliveAttempt.UNKNOWN) { KeepAliveScheduler.cancel(context, rule.simKey); return@forEach }
            val at = pending?.let { it.createdAt + KeepAlivePolicy.RESULT_TIMEOUT } ?: KeepAlivePolicy.dueAt(rule)
            KeepAliveScheduler.schedule(context, rule, at)
        }
    }

    @Suppress("DEPRECATION", "MissingPermission")
    @Synchronized fun sendDue(key: String, revision: Long, force: Boolean, stopped: () -> Boolean) {
        refresh(schedule = false)
        try {
            val rule = store.rule(key) ?: return
            val now = System.currentTimeMillis()
            if (stopped() || !KeepAlivePolicy.canSend(rule, revision, now, force)) return
            val sim = sims.discover().singleOrNull { it.key == key && it.subscriptionId == rule.subscriptionId } ?: return
            val error = KeepAlivePolicy.validationError(rule)
            if (error != null) { pause(rule, "Paused: $error"); return }
            val sms = SmsManager.getSmsManagerForSubscriptionId(sim.subscriptionId)
            if (sms.divideMessage(rule.body).size != 1) { pause(rule, "Paused: message exceeds one SMS segment"); return }
            var attempt = KeepAliveAttempt(UUID.randomUUID().toString(), key, sim.subscriptionId,
                rule.destinations[rule.destinationIndex.coerceIn(rule.destinations.indices)], rule.body, now, rule.revision)
            store.transaction { store.save(attempt); store.save(rule.copy(pendingAttempt = attempt.id, status = "Sending keep-alive SMS")) }
            try {
                val uri = checkNotNull(context.contentResolver.insert(Telephony.Sms.CONTENT_URI, ContentValues().apply {
                    put(Telephony.Sms.SUBSCRIPTION_ID, sim.subscriptionId)
                    put(Telephony.Sms.ADDRESS, attempt.destination)
                    put(Telephony.Sms.BODY, attempt.body)
                    put(Telephony.Sms.DATE, now)
                    put(Telephony.Sms.READ, 1)
                    put(Telephony.Sms.SEEN, 1)
                    put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
                    put(Telephony.Sms.THREAD_ID, TelephonyCompat.getOrCreateThreadId(context, listOf(attempt.destination)))
                })) { "Could not create message" }
                attempt = attempt.copy(providerUri = uri.toString())
                store.save(attempt)
                sync.syncMessage(uri)
                if (stopped() || sims.discover().none { it.key == key && it.subscriptionId == sim.subscriptionId }) {
                    result(attempt.id, SmsManager.RESULT_ERROR_NO_SERVICE, now)
                    return
                }
                val intent = Intent(context, KeepAliveSentReceiver::class.java).setData(Uri.parse("quik-keepalive://${attempt.id}"))
                    .putExtra("attempt", attempt.id)
                val sent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                store.save(attempt.copy(state = KeepAliveAttempt.DISPATCHED))
                sms.sendTextMessage(attempt.destination, null, attempt.body, sent, null)
            } catch (_: Exception) {
                val saved = store.attempt(attempt.id) ?: return
                if (saved.state == KeepAliveAttempt.DISPATCHED) {
                    // A transport exception may occur after submission. Do not automatically send again.
                    store.save(saved.copy(state = KeepAliveAttempt.UNKNOWN))
                    store.rule(key)?.let { store.save(it.copy(status = "Send outcome unknown; inspect the message before retrying")) }
                    notify(key + "send", rule.label, "Keep-alive send outcome unknown. Open settings to resolve it.")
                } else result(attempt.id, SmsManager.RESULT_ERROR_GENERIC_FAILURE, now)
            }
        } finally { scheduleAll() }
    }

    @Synchronized fun result(id: String, code: Int, timestamp: Long) {
        val attempt = store.attempt(id) ?: return
        if (attempt.state == KeepAliveAttempt.SENT || attempt.state == KeepAliveAttempt.FAILED) return
        val success = code == Activity.RESULT_OK
        attempt.providerUri?.let { updateMessage(it, success, timestamp) }
        store.transaction {
            store.save(attempt.copy(state = if (success) KeepAliveAttempt.SENT else KeepAliveAttempt.FAILED,
                result = code, completedAt = timestamp))
            store.rule(attempt.simKey)?.let { rule ->
                if (success) {
                    val reset = KeepAlivePolicy.activity(rule, timestamp, "keep-alive SMS")
                    store.save(reset.copy(pendingAttempt = if (rule.pendingAttempt == id) null else rule.pendingAttempt,
                        status = "Keep-alive SMS sent successfully"))
                } else if (rule.pendingAttempt == id) store.save(KeepAlivePolicy.failed(rule, attempt, timestamp,
                    code == SmsManager.RESULT_ERROR_NO_SERVICE || code == SmsManager.RESULT_ERROR_RADIO_OFF || code == SmsManager.RESULT_ERROR_LIMIT_EXCEEDED))
            }
        }
        if (success) clearNotification(attempt.simKey + "send") else {
            val retry = store.rule(attempt.simKey)?.let { it.enabled && it.retryAt > 0 } == true
            notify(attempt.simKey + "send", "SIM keep-alive", "SMS could not be sent (code $code). Check signal and credit. " +
                if (retry) "A retry is scheduled." else "No automatic retry is scheduled.")
        }
        scheduleAll()
    }

    private fun updateMessage(uri: String, success: Boolean, at: Long) {
        context.contentResolver.update(Uri.parse(uri), ContentValues().apply {
            put(Telephony.Sms.TYPE, if (success) Telephony.Sms.MESSAGE_TYPE_SENT else Telephony.Sms.MESSAGE_TYPE_FAILED)
            if (success) put(Telephony.Sms.DATE, at)
        }, null, null)
        val message = sync.syncMessage(Uri.parse(uri))
        if (success && message != null) forwarding.enqueue(message.id, ForwardingDirection.OUTGOING)
    }

    private fun pause(rule: KeepAliveRule, reason: String) {
        store.save(rule.copy(status = reason))
        KeepAliveScheduler.cancel(context, rule.simKey)
        notify(rule.simKey + "send", rule.label, reason)
    }

    private fun clearNotification(tag: String) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(tag, 7109)
    }

    private fun notify(tag: String, title: String, text: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("sim_keepalive", "SIM keep-alive", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent().setClassName(context.packageName, "dev.octoshrimpy.quik.feature.keepalive.KeepAliveSettingsActivity")
        val pending = PendingIntent.getActivity(context, 7109, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching { manager.notify(tag, 7109, NotificationCompat.Builder(context, "sim_keepalive")
            .setSmallIcon(android.R.drawable.stat_notify_error).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true).build()) }
    }
}
