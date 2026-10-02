/*
 * Copyright (C) 2026
 *
 * This file is part of QUIK Forward.
 * QUIK Forward is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 */
package dev.octoshrimpy.quik.forwarding

import com.squareup.moshi.JsonClass
import java.util.UUID

enum class ForwardingDirection { INCOMING, OUTGOING }
enum class ForwardingMessageKind { SMS, MMS }
enum class SmtpSecurity { STARTTLS, TLS }
enum class RuleAction { ALLOW, DENY }
enum class RuleMatcher { ADDRESS_EXACT, TEXT_LITERAL, TEXT_REGEX }

@JsonClass(generateAdapter = false)
data class ForwardingConfig(
    val enabled: Boolean = false,
    val incomingSms: Boolean = true,
    val incomingMms: Boolean = true,
    val outgoingSms: Boolean = false,
    val outgoingMms: Boolean = false,
    val allowListOnly: Boolean = false,
    val smtpHost: String = "",
    val smtpPort: Int = 587,
    val smtpSecurity: SmtpSecurity = SmtpSecurity.STARTTLS,
    val smtpUsername: String = "",
    val fromAddress: String = "",
    val recipients: List<String> = emptyList()
)

@JsonClass(generateAdapter = false)
data class ForwardingRule(
    val id: String = UUID.randomUUID().toString(),
    val action: RuleAction,
    val matcher: RuleMatcher,
    val value: String,
    val label: String = "",
    val caseSensitive: Boolean = false,
    val incoming: Boolean = true,
    val outgoing: Boolean = true,
    val sms: Boolean = true,
    val mms: Boolean = true
)

@JsonClass(generateAdapter = false)
data class ForwardingAttachment(
    val filename: String,
    val mimeType: String,
    val path: String,
    val size: Long
)

@JsonClass(generateAdapter = false)
data class ForwardingJob(
    val id: String,
    val sourceMessageId: Long,
    val direction: ForwardingDirection,
    val kind: ForwardingMessageKind,
    val timestamp: Long,
    val participants: List<String>,
    val subject: String,
    val body: String,
    val omittedAttachments: List<String> = emptyList(),
    val attachments: List<ForwardingAttachment> = emptyList()
)

sealed class ForwardingSendResult {
    object Success : ForwardingSendResult()
    object PermanentFailure : ForwardingSendResult()
    object Retry : ForwardingSendResult()
}
