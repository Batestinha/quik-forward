/* Copyright (C) 2026, GPL-3.0-or-later */
package dev.octoshrimpy.quik.keepalive

import android.content.Context
import androidx.work.*
import dev.octoshrimpy.quik.worker.KeepAliveWorker
import java.util.concurrent.TimeUnit

object KeepAliveScheduler {
    private fun work(context: Context) = WorkManager.getInstance(context.applicationContext)
    private fun name(key: String) = "quik-keepalive-$key"

    fun reconcile(context: Context, delaySeconds: Long = 0) = work(context).enqueueUniqueWork(
        "quik-keepalive-reconcile", ExistingWorkPolicy.APPEND_OR_REPLACE,
        OneTimeWorkRequestBuilder<KeepAliveWorker>().setInitialDelay(delaySeconds, TimeUnit.SECONDS).build())

    fun periodic(context: Context, enabled: Boolean) {
        if (!enabled) { work(context).cancelUniqueWork("quik-keepalive-periodic"); return }
        work(context).enqueueUniquePeriodicWork("quik-keepalive-periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<KeepAliveWorker>(6, TimeUnit.HOURS).build())
    }

    fun schedule(context: Context, rule: KeepAliveRule, at: Long) {
        val deadline = "at:$at:${rule.revision}"
        val existing = work(context).getWorkInfosForUniqueWork(name(rule.simKey)).get(5, TimeUnit.SECONDS)
        if (existing.any { !it.state.isFinished && deadline in it.tags }) return
        work(context).enqueueUniqueWork(name(rule.simKey), ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<KeepAliveWorker>()
                .setInputData(workDataOf("sim" to rule.simKey, "revision" to rule.revision))
                .addTag(deadline).setInitialDelay((at - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS).build())
    }

    fun cancel(context: Context, key: String) { work(context).cancelUniqueWork(name(key)) }

    fun sendNow(context: Context, rule: KeepAliveRule) = work(context).enqueueUniqueWork(
        "quik-keepalive-now-${rule.simKey}", ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<KeepAliveWorker>().setInputData(workDataOf(
            "sim" to rule.simKey, "revision" to rule.revision, "force" to true)).build())

    fun sentMessage(context: Context, messageId: Long, timestamp: Long) = work(context).enqueue(
        OneTimeWorkRequestBuilder<KeepAliveWorker>().setInputData(workDataOf(
            "message" to messageId, "timestamp" to timestamp)).build())

    fun result(context: Context, attempt: String, code: Int, timestamp: Long) = work(context).enqueue(
        OneTimeWorkRequestBuilder<KeepAliveWorker>().setInputData(workDataOf(
            "attempt" to attempt, "result" to code, "timestamp" to timestamp)).build())
}
