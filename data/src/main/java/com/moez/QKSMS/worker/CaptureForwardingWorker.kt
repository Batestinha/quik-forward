/* Copyright (C) 2026, GPL-3.0-or-later */
package dev.octoshrimpy.quik.worker

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.octoshrimpy.quik.forwarding.ForwardingDirection
import dev.octoshrimpy.quik.forwarding.ForwardingManager
import javax.inject.Inject

class CaptureForwardingWorker(context: Context, parameters: WorkerParameters) :
    Worker(context, parameters) {
    companion object {
        const val KEY_MESSAGE_ID = "message_id"
        const val KEY_DIRECTION = "direction"
        const val KEY_JOB_ID = "job_id"
    }

    @Inject lateinit var forwardingManager: ForwardingManager

    override fun doWork(): Result {
        val messageId = inputData.getLong(KEY_MESSAGE_ID, -1)
        val direction = inputData.getString(KEY_DIRECTION)
            ?.let { runCatching { ForwardingDirection.valueOf(it) }.getOrNull() }
            ?: return Result.failure()
        if (messageId <= 0) return Result.failure()
        return try {
            forwardingManager.capture(messageId, direction)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
