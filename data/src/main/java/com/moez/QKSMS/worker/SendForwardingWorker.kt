/* Copyright (C) 2026, GPL-3.0-or-later */
package dev.octoshrimpy.quik.worker

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.octoshrimpy.quik.forwarding.ForwardingManager
import dev.octoshrimpy.quik.forwarding.ForwardingSendResult
import javax.inject.Inject

class SendForwardingWorker(context: Context, parameters: WorkerParameters) :
    Worker(context, parameters) {
    companion object { const val KEY_JOB_ID = "job_id" }

    @Inject lateinit var forwardingManager: ForwardingManager

    override fun doWork(): Result = when (
        val id = inputData.getString(KEY_JOB_ID)?.let(forwardingManager::send)
    ) {
        ForwardingSendResult.Success -> Result.success()
        ForwardingSendResult.Retry -> Result.retry()
        ForwardingSendResult.PermanentFailure, null -> Result.failure()
    }
}
