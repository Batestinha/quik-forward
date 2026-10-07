/* Copyright (C) 2026, GPL-3.0-or-later */
package dev.octoshrimpy.quik.forwarding

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.ContactsContract
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dev.octoshrimpy.quik.compat.SubscriptionManagerCompat
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.repository.ConversationRepository
import dev.octoshrimpy.quik.repository.MessageRepository
import dev.octoshrimpy.quik.util.PhoneNumberUtils
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ForwardingManager @Inject constructor(
    private val context: Context,
    private val preferences: SharedPreferences,
    moshi: Moshi,
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val subscriptionManager: SubscriptionManagerCompat,
    private val phoneNumberUtils: PhoneNumberUtils,
    private val sims: dev.octoshrimpy.quik.keepalive.KeepAliveSims,
    private val simIdentities: ForwardingSimIdentityStore,
    private val secretStore: ForwardingSecretStore,
    private val smtpClient: SmtpForwardingClient,
    private val readMarker: ForwardingReadMarker
) {
    companion object {
        private const val PREF_CONFIG = "forwarding.config.v1"
        private const val PREF_RULES = "forwarding.rules.v1"
        private const val PREF_VERIFIED = "forwarding.smtp.verified.v1"
        private const val PREF_RECEIPTS = "forwarding.receipts.v1"
        const val MAX_ATTACHMENT_BYTES = 10L * 1024L * 1024L
    }

    private val configAdapter = moshi.adapter(ForwardingConfig::class.java)
    private val jobAdapter = moshi.adapter(ForwardingJob::class.java)
    private val rulesAdapter: JsonAdapter<List<ForwardingRule>> = moshi.adapter(
        Types.newParameterizedType(List::class.java, ForwardingRule::class.java)
    )
    private val evaluator = ForwardingRuleEvaluator { first, second ->
        val phoneLike: (String) -> Boolean = { value ->
            value.any(Char::isDigit) && value.all { character ->
                character.isDigit() || character in "+*#()- ."
            }
        }
        if (phoneLike(first) && phoneLike(second)) {
            phoneNumberUtils.compare(first, second)
        } else {
            first.trim().equals(second.trim(), ignoreCase = true)
        }
    }
    private val jobsRoot = File(context.filesDir, "forwarding")

    fun loadConfig(): ForwardingConfig = preferences.getString(PREF_CONFIG, null)
        ?.let { value -> runCatching { configAdapter.fromJson(value) }.getOrNull() }
        ?: ForwardingConfig()

    /** Saves settings and returns the effective config. Unverified changes cannot stay enabled. */
    @Synchronized
    fun saveConfig(config: ForwardingConfig, newPassword: String? = null): ForwardingConfig {
        val password = when {
            newPassword == null -> secretStore.load()
            else -> newPassword.also(secretStore::save)
        }
        val effective = config.copy(enabled = config.enabled && isVerified(config, password))
        preferences.edit().putString(PREF_CONFIG, configAdapter.toJson(effective)).apply()
        return effective
    }

    fun storedPassword(): String = secretStore.load()

    fun loadRules(): List<ForwardingRule> = preferences.getString(PREF_RULES, null)
        ?.let { value -> runCatching { rulesAdapter.fromJson(value) }.getOrNull() }
        .orEmpty()

    @Synchronized
    fun saveRule(rule: ForwardingRule): String? {
        evaluator.validate(rule)?.let { return it }
        val updated = loadRules().filterNot { it.id == rule.id } + rule.copy(value = rule.value.trim())
        preferences.edit().putString(PREF_RULES, rulesAdapter.toJson(updated)).apply()
        return null
    }

    @Synchronized
    fun deleteRule(id: String) {
        val updated = loadRules().filterNot { it.id == id }
        preferences.edit().putString(PREF_RULES, rulesAdapter.toJson(updated)).apply()
    }

    fun test(config: ForwardingConfig, password: String?) {
        val effectivePassword = password ?: secretStore.load()
        smtpClient.test(config, effectivePassword)
    }

    @Synchronized
    fun markVerified(config: ForwardingConfig, password: String?) {
        val effectivePassword = password ?: secretStore.load()
        if (password != null) secretStore.save(password)
        preferences.edit().putString(PREF_VERIFIED, fingerprint(config, effectivePassword)).apply()
    }

    fun isVerified(config: ForwardingConfig, password: String = secretStore.load()): Boolean =
        preferences.getString(PREF_VERIFIED, null) == fingerprint(config, password)

    fun capture(sourceMessageId: Long, direction: ForwardingDirection): String? {
        val config = loadConfig()
        if (!config.enabled || !isVerified(config)) return null
        if (hasReceipt(jobId(direction, sourceMessageId))) return null

        val message = messageRepository.getUnmanagedMessage(sourceMessageId) ?: return null
        val kind = if (message.isMms()) ForwardingMessageKind.MMS else ForwardingMessageKind.SMS
        if (!isTypeEnabled(config, direction, kind)) return null

        val participants = participants(message, direction)
        val participantLabels = participantLabels(message, participants)
        val (localNumber, localLabel) = localIdentity(message)
        val subject = message.getCleansedSubject()
        val body = message.getText(withSubject = false)
        val searchableText = listOf(subject, body).filter(String::isNotBlank).joinToString("\n")
        if (!evaluator.isEligible(
                config, loadRules(), direction, kind, participants, searchableText
            )) return null

        val id = jobId(direction, sourceMessageId)
        val jobDirectory = File(jobsRoot, id).apply { mkdirs() }
        val omitted = mutableListOf<String>()
        val attachments = if (kind == ForwardingMessageKind.MMS) {
            captureAttachments(message, jobDirectory, omitted)
        } else {
            emptyList()
        }

        val job = ForwardingJob(
            id = id,
            sourceMessageId = sourceMessageId,
            direction = direction,
            kind = kind,
            timestamp = message.date.takeIf { it > 0 } ?: System.currentTimeMillis(),
            participants = participants,
            participantLabels = participantLabels,
            localNumber = localNumber,
            localLabel = localLabel,
            subject = subject,
            body = body,
            omittedAttachments = omitted,
            attachments = attachments
        )
        writeAtomically(jobFile(id), jobAdapter.toJson(job))
        return id
    }

    fun send(jobId: String): ForwardingSendResult {
        val job = readJob(jobId)
        if (hasReceipt(jobId)) {
            if (job == null) {
                cleanup(jobId)
                return ForwardingSendResult.Success
            }
            return finishSuccessfulSend(jobId, job)
        }
        if (job == null) return ForwardingSendResult.Success
        val config = loadConfig()
        if (!config.enabled || !isVerified(config)) {
            cleanup(jobId)
            return ForwardingSendResult.PermanentFailure
        }

        return try {
            smtpClient.send(config, secretStore.load(), job)
            markReceipt(jobId)
            finishSuccessfulSend(jobId, job)
        } catch (failure: SmtpFailure) {
            if (failure.retryable) ForwardingSendResult.Retry else {
                cleanup(jobId)
                ForwardingSendResult.PermanentFailure
            }
        } catch (_: IOException) {
            ForwardingSendResult.Retry
        } catch (_: Exception) {
            cleanup(jobId)
            ForwardingSendResult.PermanentFailure
        }
    }

    private fun finishSuccessfulSend(jobId: String, job: ForwardingJob): ForwardingSendResult {
        if (!readMarker.mark(job)) return ForwardingSendResult.Retry
        cleanup(jobId)
        return ForwardingSendResult.Success
    }

    private fun isTypeEnabled(
        config: ForwardingConfig,
        direction: ForwardingDirection,
        kind: ForwardingMessageKind
    ): Boolean = when (direction to kind) {
        ForwardingDirection.INCOMING to ForwardingMessageKind.SMS -> config.incomingSms
        ForwardingDirection.INCOMING to ForwardingMessageKind.MMS -> config.incomingMms
        ForwardingDirection.OUTGOING to ForwardingMessageKind.SMS -> config.outgoingSms
        ForwardingDirection.OUTGOING to ForwardingMessageKind.MMS -> config.outgoingMms
        else -> false
    }

    private fun participants(message: Message, direction: ForwardingDirection): List<String> {
        if (direction == ForwardingDirection.INCOMING || !message.sendAsGroup) {
            return listOf(message.address).filter(String::isNotBlank)
        }
        val conversationAddresses = conversationRepository.getConversation(message.threadId)
            ?.recipients
            ?.map { it.address }
            ?.filter(String::isNotBlank)
            .orEmpty()
        return conversationAddresses.ifEmpty { listOf(message.address).filter(String::isNotBlank) }
    }

    private fun participantLabels(message: Message, participants: List<String>): List<String> {
        val savedContacts = conversationRepository.getConversation(message.threadId)
            ?.recipients
            .orEmpty()
            .map { recipient -> recipient.address to recipient.contact?.name }
        return resolveParticipantLabels(participants, savedContacts, phoneNumberUtils::compare).mapIndexed { index, label ->
            val address = participants[index]
            if (label == address && isPhoneAddress(address)) savedContactName(address) ?: label else label
        }
    }

    private fun localIdentity(message: Message): Pair<String, String> {
        val subscriptions = subscriptionManager.activeSubscriptionInfoList
        val subscription = subscriptions.firstOrNull { it.subscriptionId == message.subId }
        // An old/unknown subscription must not be attributed to whichever SIM is installed now.
        val sim = sims.discover().singleOrNull { it.subscriptionId == message.subId }
        val override = sim?.let { simIdentities.load(it.key) } ?: ForwardingSimIdentity()
        val number = override.number.ifBlank {
            sim?.number ?: usableSimNumber(runCatching { subscription?.number.orEmpty() }.getOrDefault(""))
        }
        val savedName = savedContactName(number)
        val fallbackLabel = subscription?.displayName?.toString()
            ?.takeIf(String::isNotBlank)
            ?: subscription?.simSlotIndex?.takeIf { it >= 0 }?.let { slot -> "SIM ${slot + 1}" }
            ?: "This phone"
        val label = (override.label.takeIf(String::isNotBlank) ?: savedName ?: fallbackLabel)
            .replace(Regex("[\\r\\n]+"), " ")
            .trim()
            .ifBlank { "This phone" }
        return number to label
    }

    private fun savedContactName(number: String): String? {
        if (number.isBlank()) return null
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number)
        )
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf(String::isNotBlank) else null
            }
        }.getOrNull()
    }

    private fun captureAttachments(
        message: Message,
        jobDirectory: File,
        omitted: MutableList<String>
    ): List<ForwardingAttachment> {
        var total = 0L
        val captured = mutableListOf<ForwardingAttachment>()
        message.parts
            .filterNot { it.type.equals("text/plain", true) || it.type.equals("application/smil", true) }
            .forEachIndexed { index, part ->
                val originalName = part.getBestFilename()
                val safeName = "${index}_" + originalName
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                    .take(120)
                    .ifBlank { "attachment_$index" }
                val target = File(jobDirectory, safeName)
                try {
                    var written = 0L
                    context.contentResolver.openInputStream(part.getUri())?.use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                written += count
                                if (total + written > MAX_ATTACHMENT_BYTES) {
                                    throw AttachmentLimitExceeded()
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                    } ?: throw IOException("Attachment is unavailable")
                    total += written
                    captured += ForwardingAttachment(safeName, part.type, target.path, written)
                } catch (_: AttachmentLimitExceeded) {
                    target.delete()
                    omitted += "$originalName (10 MB aggregate limit)"
                } catch (_: Exception) {
                    target.delete()
                    omitted += "$originalName (unavailable)"
                }
            }
        return captured
    }

    private fun fingerprint(config: ForwardingConfig, password: String): String {
        val normalized = listOf(
            config.smtpHost.trim().lowercase(Locale.ROOT), config.smtpPort.toString(),
            config.smtpSecurity.name, config.smtpUsername.trim(), config.fromAddress.trim(),
            config.recipients.joinToString(",") { it.trim().lowercase(Locale.ROOT) }, password
        ).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun jobId(direction: ForwardingDirection, sourceMessageId: Long) =
        "${direction.name.lowercase(Locale.ROOT)}-$sourceMessageId"

    private fun jobFile(id: String) = File(File(jobsRoot, id), "job.json")

    private fun readJob(id: String): ForwardingJob? = jobFile(id).takeIf(File::isFile)
        ?.let { file -> runCatching { jobAdapter.fromJson(file.readText()) }.getOrNull() }

    private fun writeAtomically(target: File, contents: String) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.sink().buffer().use { it.writeUtf8(contents) }
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
    }

    @Synchronized
    private fun markReceipt(id: String) {
        val receipts = preferences.getStringSet(PREF_RECEIPTS, emptySet()).orEmpty().toMutableSet()
        receipts += id
        preferences.edit().putStringSet(PREF_RECEIPTS, receipts).apply()
    }

    private fun hasReceipt(id: String): Boolean =
        preferences.getStringSet(PREF_RECEIPTS, emptySet()).orEmpty().contains(id)

    private fun cleanup(id: String) {
        File(jobsRoot, id).deleteRecursively()
    }

    private class AttachmentLimitExceeded : IOException()
}

internal fun resolveParticipantLabels(
    participants: List<String>,
    savedContacts: List<Pair<String, String?>>,
    addressesEqual: (String, String) -> Boolean
): List<String> = participants.map { address ->
    savedContacts.firstOrNull { (savedAddress) ->
        (isPhoneAddress(savedAddress) && isPhoneAddress(address) && addressesEqual(savedAddress, address)) ||
            savedAddress.trim().equals(address.trim(), ignoreCase = true)
    }?.second
        ?.replace(Regex("[\\r\\n]+"), " ")
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: address
}
