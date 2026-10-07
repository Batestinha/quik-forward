/* Copyright (C) 2026, GPL-3.0-or-later */
package dev.octoshrimpy.quik.keepalive

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.Telephony
import androidx.core.content.ContextCompat
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeepAliveActivitySource @Inject constructor(
    private val context: Context, private val catalog: KeepAliveSims, private val shizuku: KeepAliveShizuku
) {
    fun read(sims: List<SimIdentity>, since: Long = 0): ActivitySnapshot {
        val now = System.currentTimeMillis()
        val events = mutableListOf<SimActivity>()
        sims.forEach { sim ->
            latestSent(Telephony.Sms.CONTENT_URI, "type", 2, sim.subscriptionId, 1)?.let {
                if (it <= now) events.add(SimActivity(sim.key, it, "sent SMS"))
            }
            latestSent(Telephony.Mms.CONTENT_URI, "msg_box", 2, sim.subscriptionId, 1000)?.let {
                if (it <= now) events.add(SimActivity(sim.key, it, "sent MMS"))
            }
        }
        val direct = if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED)
            runCatching { directCalls(since) }.getOrNull() else null
        val (calls, status) = direct ?: shizuku.read(since)
        var unmapped = false
        calls.forEach { record ->
            if (record.duration > 0 && record.date <= now) {
                val sim = catalog.forCall(record, sims)
                if (sim != null) events.add(SimActivity(sim.key,
                    (record.date + record.duration * 1000L).coerceAtMost(now), "connected outgoing call"))
                else unmapped = true
            }
        }
        return ActivitySnapshot(events, if (unmapped) "$status; some call accounts could not be matched to a SIM" else status)
    }

    private fun latestSent(uri: Uri, box: String, value: Int, subId: Int, scale: Long): Long? {
        val cursor = context.contentResolver.query(uri, arrayOf("date"), "$box = ? AND sub_id = ?",
            arrayOf(value.toString(), subId.toString()), "date DESC") ?: error("SMS/MMS provider unavailable")
        return cursor.use {
            if (it.moveToFirst()) it.getLong(0) * scale else null
        }
    }

    private fun directCalls(since: Long): Pair<List<CallRecord>, String> {
        val rows = mutableListOf<CallRecord>()
        for (page in 0 until 20) {
            val uri = CallLog.Calls.CONTENT_URI.buildUpon().appendQueryParameter("limit", "200")
                .appendQueryParameter("offset", (page * 200).toString()).build()
            val count = context.contentResolver.query(uri,
                arrayOf("_id", "date", "duration", CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME, CallLog.Calls.PHONE_ACCOUNT_ID),
                "type = ? AND duration > 0 AND (date + duration * 1000) >= ? AND date <= ?",
                arrayOf(CallLog.Calls.OUTGOING_TYPE.toString(), since.toString(), System.currentTimeMillis().toString()),
                "date DESC, _id DESC")?.use { cursor ->
                while (cursor.moveToNext()) rows.add(CallRecord(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2),
                    cursor.getString(3).orEmpty(), cursor.getString(4).orEmpty()))
                cursor.count
            } ?: error("Call history provider unavailable")
            if (count < 200) return rows to "Call tracking active (Android permission)"
        }
        return rows to "Call history is incomplete; SMS timers remain active."
    }
}
