/* Copyright (C) 2026, GPL-3.0-or-later */
package dev.octoshrimpy.quik.keepalive

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeepAliveShizuku @Inject constructor(private val context: Context) {
    companion object {
        private val row = Regex("^Row: \\d+ _id=(\\d+), date=(\\d+), duration=(\\d+), subscription_component_name=(.*?), subscription_id=(.*)$")
        fun parseRow(line: String): CallRecord? = row.matchEntire(line)?.destructured?.let { (id, date, duration, component, account) ->
            CallRecord(id.toLong(), date.toLong(), duration.toLong(), component, account)
        }
    }

    @Synchronized fun read(since: Long): Pair<List<CallRecord>, String> {
        if (!runCatching { Shizuku.pingBinder() && Shizuku.getVersion() >= 13 &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)) {
            return emptyList<CallRecord>() to "Call tracking unavailable: start Shizuku and grant access. SMS timers remain active."
        }
        val connected = CountDownLatch(1)
        var remote: IKeepAliveCallService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                remote = IKeepAliveCallService.Stub.asInterface(binder)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { remote = null; connected.countDown() }
        }
        val args = Shizuku.UserServiceArgs(ComponentName(context, KeepAliveCallService::class.java))
            .daemon(false).processNameSuffix("keepalive_calls").tag("quik-keepalive-calls").version(1)
        return try {
            Shizuku.bindUserService(args, connection)
            check(connected.await(8, TimeUnit.SECONDS)) { "Shizuku connection timed out" }
            val result = checkNotNull(remote).queryCalls(since)
            val lines = result.getStringArrayList("rows").orEmpty()
            val records = lines.mapNotNull(::parseRow)
            records to if (records.size == lines.size && result.getBoolean("complete")) "Call tracking active (Shizuku)"
                else "Call history is incomplete; SMS timers remain active."
        } catch (_: Exception) {
            emptyList<CallRecord>() to "Call tracking unavailable: check Shizuku access. SMS timers remain active."
        } finally { runCatching { Shizuku.unbindUserService(args, connection, true) } }
    }
}
