/* Copyright (C) 2026, GPL-3.0-or-later */
package dev.octoshrimpy.quik.worker

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.octoshrimpy.quik.keepalive.KeepAliveManager
import javax.inject.Inject

class KeepAliveWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    @Inject lateinit var manager: KeepAliveManager
    override fun doWork(): Result = try {
        val attempt = inputData.getString("attempt")
        val message = inputData.getLong("message", 0)
        val key = inputData.getString("sim")
        when {
            attempt != null -> manager.result(attempt, inputData.getInt("result", 1), inputData.getLong("timestamp", System.currentTimeMillis()))
            message > 0 -> { manager.recordSentMessage(message, inputData.getLong("timestamp", System.currentTimeMillis())); manager.refresh() }
            key != null -> manager.sendDue(key, inputData.getLong("revision", -1), inputData.getBoolean("force", false)) { isStopped }
            else -> manager.refresh()
        }
        Result.success()
    } catch (_: Exception) { Result.retry() }
}
