/* Copyright (C) 2026, GPL-3.0-or-later */
package dev.octoshrimpy.quik.forwarding

import jakarta.activation.DataHandler
import jakarta.activation.FileDataSource
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.Session
import jakarta.mail.URLName
import jakarta.mail.internet.AddressException
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import org.eclipse.angus.mail.smtp.SMTPTransport
import java.io.File
import java.util.Date
import java.util.IdentityHashMap
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton

class SmtpFailure(message: String, val retryable: Boolean, cause: Throwable? = null) :
    Exception(message, cause)

@Singleton
class SmtpForwardingClient @Inject constructor() {

    fun test(config: ForwardingConfig, password: String) {
        validate(config)
        withConnectedTransport(config, password) { }
    }

    fun send(config: ForwardingConfig, password: String, job: ForwardingJob) {
        validate(config)
        val message = createMessage(config, job)

        withConnectedTransport(config, password) { transport ->
            transport.sendMessage(message, message.allRecipients)
        }
    }

    internal fun createMessage(config: ForwardingConfig, job: ForwardingJob): MimeMessage =
        MimeMessage(Session.getInstance(smtpProperties(config))).apply {
            setFrom(InternetAddress(config.fromAddress.trim(), true))
            setRecipients(
                if (config.bccRecipients) Message.RecipientType.BCC else Message.RecipientType.TO,
                config.recipients.flatMap { InternetAddress.parse(it, true).toList() }.toTypedArray()
            )
            subject = "QUIK Forward: ${job.direction.name.lowercase().replaceFirstChar(Char::uppercase)} " +
                "${job.kind.name} ${job.participants.joinToString()}"
            sentDate = Date(job.timestamp)
            setHeader("Message-ID", "<quik-forward-${job.id}@local>")
            setHeader("X-QUIK-Forward-Direction", job.direction.name)
            setHeader("X-QUIK-Forward-Source-ID", job.sourceMessageId.toString())

            val text = buildString {
                appendLine("Direction: ${job.direction.name.lowercase()}")
                appendLine("Type: ${job.kind.name}")
                appendLine("Address: ${job.participants.joinToString()}")
                appendLine("Date: ${Date(job.timestamp)}")
                if (job.subject.isNotBlank()) appendLine("Subject: ${job.subject}")
                if (job.omittedAttachments.isNotEmpty()) {
                    appendLine("Attachments omitted: ${job.omittedAttachments.joinToString()}")
                }
                appendLine()
                append(job.body)
            }

            if (job.attachments.isEmpty()) {
                setText(text, Charsets.UTF_8.name())
            } else {
                setContent(MimeMultipart().apply {
                    addBodyPart(MimeBodyPart().apply { setText(text, Charsets.UTF_8.name()) })
                    job.attachments.forEach { attachment ->
                        addBodyPart(MimeBodyPart().apply {
                            dataHandler = DataHandler(FileDataSource(File(attachment.path)))
                            fileName = attachment.filename
                            disposition = MimeBodyPart.ATTACHMENT
                        })
                    }
                })
            }
            saveChanges()
            // saveChanges creates its own id, so restore the deterministic id used for deduplication.
            setHeader("Message-ID", "<quik-forward-${job.id}@local>")
        }

    fun validate(config: ForwardingConfig) {
        if (config.smtpHost.isBlank()) throw SmtpFailure("SMTP host is required", false)
        if (config.smtpPort !in 1..65535) throw SmtpFailure("SMTP port is invalid", false)
        try {
            InternetAddress(config.fromAddress.trim(), true).validate()
            if (config.recipients.isEmpty()) throw AddressException("At least one recipient is required")
            config.recipients.forEach { recipient ->
                val parsed = InternetAddress.parse(recipient, true)
                if (parsed.isEmpty()) throw AddressException("Invalid recipient")
                parsed.forEach(InternetAddress::validate)
            }
        } catch (error: AddressException) {
            throw SmtpFailure(error.message ?: "Invalid email address", false, error)
        }
    }

    internal fun smtpProperties(config: ForwardingConfig) = Properties().apply {
        setProperty("mail.smtp.host", config.smtpHost.trim())
        setProperty("mail.smtp.port", config.smtpPort.toString())
        setProperty("mail.smtp.auth", config.smtpUsername.isNotBlank().toString())
        setProperty("mail.smtp.connectiontimeout", "20000")
        setProperty("mail.smtp.timeout", "20000")
        setProperty("mail.smtp.writetimeout", "20000")
        // Android 10's TLS provider does not reliably support the JDK-specific "LDAPS"
        // endpoint-identification algorithm selected by Angus when this setting is true.
        // Keep normal certificate-chain validation and have Angus verify the certificate's
        // DNS names after the handshake instead.
        setProperty("mail.smtp.ssl.checkserveridentity", "false")
        setProperty("mail.smtp.ssl.hostnameverifier.class", "MailHostnameVerifier")
        setProperty("mail.smtp.ssl.protocols", "TLSv1.3 TLSv1.2")
        when (config.smtpSecurity) {
            SmtpSecurity.STARTTLS -> {
                setProperty("mail.smtp.starttls.enable", "true")
                setProperty("mail.smtp.starttls.required", "true")
            }
            SmtpSecurity.TLS -> setProperty("mail.smtp.ssl.enable", "true")
        }
    }

    private fun withConnectedTransport(
        config: ForwardingConfig,
        password: String,
        block: (jakarta.mail.Transport) -> Unit
    ) {
        val transport = createTransport(config)
        try {
            if (config.smtpUsername.isBlank()) {
                transport.connect(config.smtpHost.trim(), config.smtpPort, null, null)
            } else {
                transport.connect(
                    config.smtpHost.trim(), config.smtpPort, config.smtpUsername.trim(), password
                )
            }
            block(transport)
        } catch (error: AuthenticationFailedException) {
            throw SmtpFailure("SMTP authentication failed", false, error)
        } catch (error: AddressException) {
            throw SmtpFailure(error.message ?: "Invalid email address", false, error)
        } catch (error: MessagingException) {
            // Authentication/address errors are permanent; other SMTP and network errors may clear.
            throw SmtpFailure(describe(error, "SMTP connection failed"), true, error)
        } finally {
            try {
                if (transport.isConnected) transport.close()
            } catch (_: MessagingException) {
                // Nothing useful can be done while closing a failed connection.
            }
        }
    }

    /**
     * Android does not reliably expose META-INF mail-provider descriptors through its classloader.
     * Constructing the Angus SMTP transport directly avoids a misleading NoSuchProviderException
     * whose entire message is just "smtp" on affected release builds.
     */
    internal fun createTransport(config: ForwardingConfig): SMTPTransport {
        val session = Session.getInstance(smtpProperties(config))
        val url = URLName(
            "smtp",
            config.smtpHost.trim(),
            config.smtpPort,
            null,
            config.smtpUsername.trim().takeIf(String::isNotBlank),
            null
        )
        return SMTPTransport(session, url)
    }

    /** Include the useful nested TLS/network cause that MessagingException normally hides. */
    internal fun describe(error: MessagingException, fallback: String): String {
        val seen = java.util.Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val messages = mutableListOf<String>()
        var current: Throwable? = error

        while (current != null && seen.add(current)) {
            current.message
                ?.trim()
                ?.takeIf { it.isNotEmpty() && !messages.contains(it) }
                ?.let(messages::add)

            current = when {
                current is MessagingException && current.nextException != null &&
                    !seen.contains(current.nextException) -> current.nextException
                else -> current.cause
            }
        }

        return messages.joinToString(": ").takeIf(String::isNotEmpty) ?: fallback
    }
}
