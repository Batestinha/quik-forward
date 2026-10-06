/* Copyright (C) 2026, GPL-3.0-or-later */
package dev.octoshrimpy.quik.forwarding

import dev.octoshrimpy.quik.blocking.BlockingClient
import dev.octoshrimpy.quik.manager.PermissionManager
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.repository.ContactRepository
import dev.octoshrimpy.quik.repository.ConversationRepository
import dev.octoshrimpy.quik.repository.MessageContentFilterRepository
import dev.octoshrimpy.quik.repository.SyncRepository
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

enum class ForwardingReconciliationResult { SUCCESS, RETRY }

@Singleton
class ForwardingReconciler @Inject constructor(
    private val source: ForwardingReconciliationSource,
    private val store: ForwardingReconciliationStore,
    private val permissions: PermissionManager,
    private val syncRepository: SyncRepository,
    private val blockingClient: BlockingClient,
    private val conversationRepository: ConversationRepository,
    private val filterRepository: MessageContentFilterRepository,
    private val contactRepository: ContactRepository
) {
    fun reconcile(enqueue: (Long) -> Unit): ForwardingReconciliationResult {
        return try {
            if (!permissions.isDefaultSms() || !permissions.hasReadSms()) {
                return ForwardingReconciliationResult.SUCCESS
            }
            if (syncRepository.syncProgress.blockingFirst() !is SyncRepository.SyncProgress.Idle) {
                return ForwardingReconciliationResult.RETRY
            }

            val through = source.snapshot() ?: return ForwardingReconciliationResult.RETRY
            val saved = store.load()
            if (saved == null) {
                return if (store.save(through)) {
                    ForwardingReconciliationResult.SUCCESS
                } else {
                    ForwardingReconciliationResult.RETRY
                }
            }

            // If a provider database was replaced and its IDs moved backwards, safely re-baseline
            // that provider instead of treating the historical rows as newly received messages.
            val after = ForwardingHighWaterMarks(
                smsId = saved.smsId.coerceAtMost(through.smsId),
                mmsId = saved.mmsId.coerceAtMost(through.mmsId)
            )
            source.incomingMessages(after, through).forEach { uri ->
                val message = syncRepository.syncMessage(uri) ?: return@forEach
                if (shouldForward(message)) enqueue(message.id)
            }

            if (store.save(through)) {
                ForwardingReconciliationResult.SUCCESS
            } else {
                ForwardingReconciliationResult.RETRY
            }
        } catch (error: Exception) {
            Timber.w(error, "Forwarding reconciliation failed")
            ForwardingReconciliationResult.RETRY
        }
    }

    private fun shouldForward(message: Message): Boolean {
        when (blockingClient.shouldBlock(message.address).blockingGet()) {
            is BlockingClient.Action.Block -> return false
            is BlockingClient.Action.Unblock -> conversationRepository.markUnblocked(message.threadId)
            is BlockingClient.Action.DoNothing -> Unit
        }
        if (filterRepository.isBlocked(message.getText(), message.address, contactRepository)) {
            return false
        }

        conversationRepository.updateConversations(listOf(message.threadId))
        return conversationRepository.getOrCreateConversation(message.threadId)?.blocked == false
    }
}
