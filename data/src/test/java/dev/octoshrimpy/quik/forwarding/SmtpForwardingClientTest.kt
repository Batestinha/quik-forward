/* Copyright (C) 2026, GPL-3.0-or-later */
package dev.octoshrimpy.quik.forwarding

import jakarta.mail.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmtpForwardingClientTest {
    private val client = SmtpForwardingClient()
    private val config = ForwardingConfig(
        fromAddress = "sender@example.com",
        recipients = listOf("first@example.com", "second@example.com")
    )
    private val job = ForwardingJob(
        id = "incoming-1",
        sourceMessageId = 1,
        direction = ForwardingDirection.INCOMING,
        kind = ForwardingMessageKind.SMS,
        timestamp = 1,
        participants = listOf("+351000000000"),
        subject = "",
        body = "Test"
    )

    @Test
    fun usesBccRecipientsByDefault() {
        val message = client.createMessage(config, job)

        assertEquals(
            listOf("first@example.com", "second@example.com"),
            message.getRecipients(Message.RecipientType.BCC).map { it.toString() }
        )
        assertNull(message.getRecipients(Message.RecipientType.TO))
    }

    @Test
    fun canUseVisibleToRecipients() {
        val message = client.createMessage(config.copy(bccRecipients = false), job)

        assertEquals(
            listOf("first@example.com", "second@example.com"),
            message.getRecipients(Message.RecipientType.TO).map { it.toString() }
        )
        assertNull(message.getRecipients(Message.RecipientType.BCC))
    }
}
