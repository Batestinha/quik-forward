/* Copyright (C) 2026, GPL-3.0-or-later */
package dev.octoshrimpy.quik.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.octoshrimpy.quik.forwarding.ForwardingDirection
import dev.octoshrimpy.quik.forwarding.ForwardingReconciliationResult
import dev.octoshrimpy.quik.forwarding.ForwardingReconciler
import dev.octoshrimpy.quik.forwarding.ForwardingScheduler
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class ReconcileForwardingWorker(context: Context, parameters: WorkerParameters) :
    Worker(context, parameters) {
    companion object {
        private const val UNIQUE_WORK = "quik-forward-reconcile"

        fun register(context: Context) {
            val request = OneTimeWorkRequestBuilder<ReconcileForwardingWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }

    @Inject lateinit var reconciler: ForwardingReconciler
    @Inject lateinit var forwardingScheduler: ForwardingScheduler

    override fun doWork(): Result = when (
        reconciler.reconcile { messageId ->
            val operation = forwardingScheduler.enqueue(messageId, ForwardingDirection.INCOMING)
                ?: throw IllegalArgumentException("Invalid message id: $messageId")
            operation.result.get(30, TimeUnit.SECONDS)
        }
    ) {
        ForwardingReconciliationResult.SUCCESS -> Result.success()
        ForwardingReconciliationResult.RETRY -> Result.retry()
    }
}
