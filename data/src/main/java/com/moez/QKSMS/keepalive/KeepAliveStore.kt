/* Copyright (C) 2026, GPL-3.0-or-later */
package dev.octoshrimpy.quik.keepalive

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.squareup.moshi.Moshi
import javax.inject.Inject
import javax.inject.Singleton

/** Separate from the message Realm: attempts survive message resyncs and callback redelivery. */
@Singleton
class KeepAliveStore @Inject constructor(context: Context, moshi: Moshi) :
    SQLiteOpenHelper(context, "sim-keepalive.db", null, 1) {
    private val ruleAdapter = moshi.adapter(KeepAliveRule::class.java)
    private val attemptAdapter = moshi.adapter(KeepAliveAttempt::class.java)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE rules (id TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE attempts (id TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized fun rules(): List<KeepAliveRule> = readableDatabase.query("rules", arrayOf("value"), null, null, null, null, null).use {
        val result = mutableListOf<KeepAliveRule>()
        while (it.moveToNext()) ruleAdapter.fromJson(it.getString(0))?.let(result::add)
        result
    }
    @Synchronized fun rule(key: String): KeepAliveRule? = read("rules", key)?.let(ruleAdapter::fromJson)
    @Synchronized fun attempt(id: String): KeepAliveAttempt? = read("attempts", id)?.let(attemptAdapter::fromJson)
    @Synchronized fun save(rule: KeepAliveRule) = write("rules", rule.simKey, ruleAdapter.toJson(rule))
    @Synchronized fun save(attempt: KeepAliveAttempt) = write("attempts", attempt.id, attemptAdapter.toJson(attempt))
    @Synchronized fun remove(key: String) { writableDatabase.delete("rules", "id = ?", arrayOf(key)) }

    @Synchronized fun <T> transaction(block: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        return try { block().also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }

    private fun read(table: String, id: String): String? = readableDatabase.query(
        table, arrayOf("value"), "id = ?", arrayOf(id), null, null, null
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun write(table: String, id: String, value: String) {
        writableDatabase.insertWithOnConflict(table, null, ContentValues().apply {
            put("id", id); put("value", value)
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "Could not persist keep-alive state" } }
    }
}
