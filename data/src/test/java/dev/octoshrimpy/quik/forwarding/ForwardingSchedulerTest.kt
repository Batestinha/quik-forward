/* Copyright (C) 2026, GPL-3.0-or-later */
package dev.octoshrimpy.quik.forwarding

import android.content.Context
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ForwardingSchedulerTest {

    @Test
    fun constructorDoesNotRequireInitializedWorkManager() {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)

        ForwardingScheduler(context)
    }
}
