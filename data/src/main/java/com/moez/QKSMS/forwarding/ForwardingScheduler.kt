/* Copyright (C) 2026, GPL-3.0-or-later */
package dev.octoshrimpy.quik.forwarding

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import dev.octoshrimpy.quik.worker.CaptureForwardingWorker
import dev.octoshrimpy.quik.worker.SendForwardingWorker
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ForwardingScheduler @Inject constructor(context: Context) {
    private val appContext = context.applicationContext

    fun enqueue(messageId: Long, direction: ForwardingDirection): Operation? {
        if (messageId <= 0) return null
        val workManager = WorkManager.getInstance(appContext)
        val id = "${direction.name.lowercase(Locale.ROOT)}-$messageId"
        val input = Data.Builder()
            .putLong(CaptureForwardingWorker.KEY_MESSAGE_ID, messageId)
            .putString(CaptureForwardingWorker.KEY_DIRECTION, direction.name)
            .putString(CaptureForwardingWorker.KEY_JOB_ID, id)
            .build()
        val capture = OneTimeWorkRequestBuilder<CaptureForwardingWorker>()
            .setInputData(input)
            .build()
        val send = OneTimeWorkRequestBuilder<SendForwardingWorker>()
            .setInputData(Data.Builder().putString(SendForwardingWorker.KEY_JOB_ID, id).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        return workManager.beginUniqueWork("quik-forward-$id", ExistingWorkPolicy.KEEP, capture)
            .then(send)
            .enqueue()
    }
}
