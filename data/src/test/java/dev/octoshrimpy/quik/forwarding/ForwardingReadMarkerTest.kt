/* Copyright (C) 2026, GPL-3.0-or-later */
package dev.octoshrimpy.quik.forwarding

import dev.octoshrimpy.quik.manager.NotificationManager
import dev.octoshrimpy.quik.manager.ShortcutManager
import dev.octoshrimpy.quik.manager.WidgetManager
import dev.octoshrimpy.quik.repository.MessageRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class ForwardingReadMarkerTest {
    private val messageRepository = mock(MessageRepository::class.java)
    private val notificationManager = mock(NotificationManager::class.java)
    private val shortcutManager = mock(ShortcutManager::class.java)
    private val widgetManager = mock(WidgetManager::class.java)
    private val marker = ForwardingReadMarker(
        messageRepository,
        notificationManager,
        shortcutManager,
        widgetManager
    )
    private val incomingJob = ForwardingJob(
        id = "incoming-42",
        sourceMessageId = 42,
        direction = ForwardingDirection.INCOMING,
        kind = ForwardingMessageKind.SMS,
        timestamp = 1,
        participants = listOf("+351000000000"),
        subject = "",
        body = "Test"
    )

    @Test
    fun marksOnlyTheForwardedMessageAndRefreshesItsConversation() {
        `when`(messageRepository.markMessagesRead(listOf(42))).thenReturn(setOf(7))

        assertTrue(marker.mark(incomingJob))

        verify(messageRepository).markMessagesRead(listOf(42))
        verify(notificationManager).update(7)
        verify(shortcutManager).updateBadge()
        verify(widgetManager).sendDatasetChanged()
    }

    @Test
    fun leavesOutgoingMessagesAlone() {
        assertTrue(marker.mark(incomingJob.copy(direction = ForwardingDirection.OUTGOING)))

        verify(messageRepository, never()).markMessagesRead(listOf(42))
        verify(shortcutManager, never()).updateBadge()
        verify(widgetManager, never()).sendDatasetChanged()
    }

    @Test
    fun requestsARetryWhenMarkingReadFails() {
        `when`(messageRepository.markMessagesRead(listOf(42)))
            .thenThrow(IllegalStateException("database unavailable"))

        assertFalse(marker.mark(incomingJob))
    }
}
