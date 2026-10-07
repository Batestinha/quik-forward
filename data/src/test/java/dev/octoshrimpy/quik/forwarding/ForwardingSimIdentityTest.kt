/* Copyright (C) 2026, GPL-3.0-or-later */
package dev.octoshrimpy.quik.forwarding

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class ForwardingSimIdentityTest {
    @Test fun hidesMissingAndClearlyPlaceholderSimNumbers() {
        listOf("", "unknown", "35100000000000", "+35100000000000", "000000000000000").forEach {
            assertEquals("", usableSimNumber(it))
        }
        assertEquals("+351912345678", usableSimNumber("+351 (912) 345-678"))
        assertEquals("351912345678", usableSimNumber("351912345678"))
    }

    @Test fun anImsNumberReplacesTheLycaSimPlaceholder() {
        assertEquals("+351912345678", firstUsableSimNumber(sequenceOf("", "35100000000000", "+351912345678")))
    }

    @Test fun respectsSourcePriorityAndDoesNotQueryLowerPrioritySourcesUnnecessarily() {
        assertEquals("+351912345678", firstUsableSimNumber(sequence {
            yield("+351912345678")
            throw AssertionError("A valid higher-priority source already provided the number")
        }))
    }

    @Test fun absentOrPlaceholderSourcesLeaveTheNumberExplicitlyUnavailable() {
        assertEquals("", firstUsableSimNumber(sequenceOf("", "35100000000000", "")))
    }

    @Test fun savesOverridesOnlyUnderTheSelectedSimIdentity() {
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        `when`(preferences.edit()).thenReturn(editor)
        ForwardingSimIdentityStore(preferences).save("sim:a", "+351 912 345 678", "Work\nSIM")
        verify(editor).putString("forwarding.sim.sim:a.number", "+351912345678")
        verify(editor).putString("forwarding.sim.sim:a.label", "Work SIM")
        verify(editor).apply()
        verifyNoMoreInteractions(editor)
    }

    @Test fun blankFieldsRestoreAutomaticIdentityWithoutChangingOtherSettings() {
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        `when`(preferences.edit()).thenReturn(editor)
        ForwardingSimIdentityStore(preferences).save("sim:a", "", "")
        verify(editor).remove("forwarding.sim.sim:a.number")
        verify(editor).remove("forwarding.sim.sim:a.label")
        verify(editor).apply()
        verifyNoMoreInteractions(editor)
    }

    @Test fun rejectsSlotIdentityAndInvalidNumbersWithoutSaving() {
        val preferences = mock(SharedPreferences::class.java)
        val store = ForwardingSimIdentityStore(preferences)
        listOf("subscription:1" to "+351912345678", "sim:a" to "351912345678",
            "sim:a" to "+35100000000000", "sim:a" to "*123#").forEach { (key, number) ->
            try { store.save(key, number, ""); fail("Invalid identity accepted") }
            catch (_: IllegalArgumentException) { }
        }
        verifyZeroInteractions(preferences)
    }
}
