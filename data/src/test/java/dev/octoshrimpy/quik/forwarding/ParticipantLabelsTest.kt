/* Copyright (C) 2026, GPL-3.0-or-later */
package dev.octoshrimpy.quik.forwarding

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ParticipantLabelsTest {

    @Test
    fun resolvesSavedNamesAndFallsBackToUnknownAddresses() {
        val labels = resolveParticipantLabels(
            participants = listOf("+351 912 345 678", "+351 900 000 000"),
            savedContacts = listOf("912345678" to "Alice Example"),
            addressesEqual = { first, second ->
                first.filter(Char::isDigit).takeLast(9) ==
                    second.filter(Char::isDigit).takeLast(9)
            }
        )

        assertEquals(listOf("Alice Example", "+351 900 000 000"), labels)
    }

    @Test
    fun removesHeaderBreakingCharactersFromContactNames() {
        val labels = resolveParticipantLabels(
            participants = listOf("123"),
            savedContacts = listOf("123" to "Alice\r\nExample"),
            addressesEqual = { first, second -> first == second }
        )

        assertEquals(listOf("Alice Example"), labels)
    }

    @Test
    fun readsQueuedJobsCreatedBeforeContactLabelsWereAdded() {
        val adapter = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
            .adapter(ForwardingJob::class.java)
        val job = adapter.fromJson(
            """
            {
              "id": "incoming-1",
              "sourceMessageId": 1,
              "direction": "INCOMING",
              "kind": "SMS",
              "timestamp": 1,
              "participants": ["123"],
              "subject": "",
              "body": "Test"
            }
            """.trimIndent()
        )

        assertNotNull(job)
        assertEquals(emptyList<String>(), job?.participantLabels)
        assertEquals("", job?.localNumber)
        assertEquals("", job?.localLabel)
    }
}
