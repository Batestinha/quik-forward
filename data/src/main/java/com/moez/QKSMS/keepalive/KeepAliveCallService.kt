/* Copyright (C) 2026, GPL-3.0-or-later */
package dev.octoshrimpy.quik.keepalive

import android.content.Context
import android.os.Binder
import android.os.Bundle
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A short-lived Shizuku service. No caller-supplied command, URI, projection or SQL. */
class KeepAliveCallService(context: Context) : IKeepAliveCallService.Stub() {
    private val appUid = context.applicationInfo.uid
    private val userId = appUid / 100000
    private val reader = Executors.newSingleThreadExecutor()

    @Synchronized override fun queryCalls(since: Long): Bundle {
        check(Binder.getCallingUid() == appUid) { "Unexpected caller" }
        require(since >= 0)
        val lines = arrayListOf<String>()
        var complete = false
        var totalCharacters = 0
        pages@ for (page in 0 until 20) {
            val process = ProcessBuilder("/system/bin/content", "query", "--user", userId.toString(),
                "--uri", "content://call_log/calls?limit=200&offset=${page * 200}",
                "--projection", "_id:date:duration:subscription_component_name:subscription_id",
                "--where", "type = 2 AND duration > 0 AND (date + duration * 1000) >= $since AND date <= ${System.currentTimeMillis()}",
                "--sort", "date DESC, _id DESC").redirectErrorStream(true).start()
            try {
                val output = reader.submit<List<String>> {
                    process.inputStream.bufferedReader().use { stream ->
                        val pageLines = mutableListOf<String>()
                        var length = 0
                        stream.forEachLine { line ->
                            length += line.length
                            check(length <= 262144) { "Call query output too large" }
                            pageLines.add(line)
                        }
                        check(process.waitFor() == 0) { "Call query failed" }
                        pageLines
                    }
                }.get(8, TimeUnit.SECONDS)
                check(output.none { it.contains("Exception") || it.startsWith("Error") }) { "Call history access denied" }
                val rows = output.filter { it.startsWith("Row:") }
                check(rows.isNotEmpty() || output.any { it.contains("No result found") }) { "Unrecognized call query response" }
                for (row in rows) {
                    // Binder has a shared transaction-size limit; keep even UTF-16 results well below it.
                    if (totalCharacters + row.length > 196608) break@pages
                    lines.add(row)
                    totalCharacters += row.length
                }
                if (rows.size < 200) { complete = true; break }
            } finally { process.destroy() }
        }
        return Bundle().apply { putStringArrayList("rows", lines); putBoolean("complete", complete) }
    }

    override fun destroy() {
        check(Binder.getCallingUid() == appUid || Binder.getCallingUid() == android.os.Process.myUid())
        reader.shutdownNow()
        kotlin.system.exitProcess(0)
    }
}
