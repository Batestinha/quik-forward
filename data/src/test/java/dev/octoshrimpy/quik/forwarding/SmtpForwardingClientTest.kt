/* Copyright (C) 2026, GPL-3.0-or-later */
package dev.octoshrimpy.quik.forwarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.mail.Message
import javax.mail.MessagingException

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

    @Test
    fun createsSmtpTransportWithoutProviderDiscovery() {
        val transport = client.createTransport(
            config.copy(
                smtpHost = "smtp.gmail.com",
                smtpPort = 587,
                smtpSecurity = SmtpSecurity.STARTTLS,
                smtpUsername = "sender@example.com"
            )
        )

        assertEquals("smtp", transport.urlName.protocol)
        assertEquals("smtp.gmail.com", transport.urlName.host)
        assertEquals(587, transport.urlName.port)
        assertTrue(transport.startTLS)
        assertTrue(transport.requireStartTLS)
    }

    @Test
    fun requiresCertificateHostnameVerification() {
        val properties = client.smtpProperties(
            config.copy(
                smtpHost = "smtp.gmail.com",
                smtpPort = 587,
                smtpSecurity = SmtpSecurity.STARTTLS
            ),
            sdkInt = 29
        )

        assertEquals("true", properties.getProperty("mail.smtp.ssl.checkserveridentity"))
        assertNull(properties.getProperty("mail.smtp.ssl.hostnameverifier.class"))
        assertEquals("TLSv1.3 TLSv1.2", properties.getProperty("mail.smtp.ssl.protocols"))
        assertEquals("true", properties.getProperty("mail.smtp.starttls.enable"))
        assertEquals("true", properties.getProperty("mail.smtp.starttls.required"))
    }

    @Test
    fun usesTls12OnlyBeforeAndroid10() {
        val properties = client.smtpProperties(config, sdkInt = 28)

        assertEquals("TLSv1.2", properties.getProperty("mail.smtp.ssl.protocols"))
    }

    @Test
    fun includesNestedTlsFailureDetails() {
        val error = MessagingException(
            "Could not convert socket to TLS",
            IllegalArgumentException("Unsupported endpoint identification algorithm: LDAPS")
        )

        assertEquals(
            "Could not convert socket to TLS: Unsupported endpoint identification algorithm: LDAPS",
            client.describe(error, "SMTP connection failed")
        )
    }
}
