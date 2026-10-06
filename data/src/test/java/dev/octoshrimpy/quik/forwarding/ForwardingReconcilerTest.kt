/* Copyright (C) 2026, GPL-3.0-or-later */
package dev.octoshrimpy.quik.forwarding

import android.net.Uri
import dev.octoshrimpy.quik.blocking.BlockingClient
import dev.octoshrimpy.quik.manager.PermissionManager
import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.repository.ContactRepository
import dev.octoshrimpy.quik.repository.ConversationRepository
import dev.octoshrimpy.quik.repository.MessageContentFilterRepository
import dev.octoshrimpy.quik.repository.SyncRepository
import io.reactivex.Observable
import io.reactivex.Single
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class ForwardingReconcilerTest {
    private val source = mock(ForwardingReconciliationSource::class.java)
    private val store = mock(ForwardingReconciliationStore::class.java)
    private val permissions = mock(PermissionManager::class.java)
    private val syncRepository = mock(SyncRepository::class.java)
    private val blockingClient = mock(BlockingClient::class.java)
    private val conversationRepository = mock(ConversationRepository::class.java)
    private val filterRepository = mock(MessageContentFilterRepository::class.java)
    private val contactRepository = mock(ContactRepository::class.java)
    private val reconciler = ForwardingReconciler(
        source,
        store,
        permissions,
        syncRepository,
        blockingClient,
        conversationRepository,
        filterRepository,
        contactRepository
    )
    private val saved = ForwardingHighWaterMarks(smsId = 10, mmsId = 20)
    private val through = ForwardingHighWaterMarks(smsId = 12, mmsId = 21)

    @Before
    fun setUp() {
        `when`(permissions.isDefaultSms()).thenReturn(true)
        `when`(permissions.hasReadSms()).thenReturn(true)
        `when`(syncRepository.syncProgress).thenReturn(
            Observable.just(SyncRepository.SyncProgress.Idle)
        )
    }

    @Test
    fun firstRunEstablishesBaselineWithoutForwardingHistory() {
        `when`(source.snapshot()).thenReturn(through)
        `when`(store.load()).thenReturn(null)
        `when`(store.save(through)).thenReturn(true)
        val enqueued = mutableListOf<Long>()

        val result = reconciler.reconcile(enqueued::add)

        assertEquals(ForwardingReconciliationResult.SUCCESS, result)
        assertTrue(enqueued.isEmpty())
        verify(store).save(through)
    }

    @Test
    fun forwardsOnlyAcceptedMessagesAndAdvancesCheckpoint() {
        val uri = mock(Uri::class.java)
        val message = Message().apply {
            id = 42
            threadId = 7
            address = "+351000000000"
            type = Message.TYPE_SMS
            body = "Test"
        }
        `when`(source.snapshot()).thenReturn(through)
        `when`(store.load()).thenReturn(saved)
        `when`(source.incomingMessages(saved, through)).thenReturn(listOf(uri))
        `when`(syncRepository.syncMessage(uri)).thenReturn(message)
        `when`(blockingClient.shouldBlock(message.address))
            .thenReturn(Single.just(BlockingClient.Action.Unblock))
        `when`(filterRepository.isBlocked(message.body, message.address, contactRepository))
            .thenReturn(false)
        `when`(conversationRepository.getOrCreateConversation(message.threadId))
            .thenReturn(Conversation(id = message.threadId, blocked = false))
        `when`(store.save(through)).thenReturn(true)
        val enqueued = mutableListOf<Long>()

        val result = reconciler.reconcile(enqueued::add)

        assertEquals(ForwardingReconciliationResult.SUCCESS, result)
        assertEquals(listOf(42L), enqueued)
        verify(conversationRepository).markUnblocked(7)
        verify(store).save(through)
    }

    @Test
    fun enqueueFailureKeepsCheckpointForRetry() {
        val uri = mock(Uri::class.java)
        val message = Message().apply {
            id = 42
            threadId = 7
            address = "+351000000000"
            type = Message.TYPE_SMS
            body = "Test"
        }
        `when`(source.snapshot()).thenReturn(through)
        `when`(store.load()).thenReturn(saved)
        `when`(source.incomingMessages(saved, through)).thenReturn(listOf(uri))
        `when`(syncRepository.syncMessage(uri)).thenReturn(message)
        `when`(blockingClient.shouldBlock(message.address))
            .thenReturn(Single.just(BlockingClient.Action.DoNothing))
        `when`(filterRepository.isBlocked(message.body, message.address, contactRepository))
            .thenReturn(false)
        `when`(conversationRepository.getOrCreateConversation(message.threadId))
            .thenReturn(Conversation(id = message.threadId, blocked = false))

        val result = reconciler.reconcile { throw IllegalStateException("enqueue failed") }

        assertEquals(ForwardingReconciliationResult.RETRY, result)
        verify(store, never()).save(through)
    }

    @Test
    fun doesNotInitializeCheckpointBeforeSmsAccessIsGranted() {
        `when`(permissions.hasReadSms()).thenReturn(false)

        val result = reconciler.reconcile { }

        assertEquals(ForwardingReconciliationResult.SUCCESS, result)
        verify(source, never()).snapshot()
        verify(store, never()).save(through)
    }

    @Test
    fun waitsForAnInProgressMessageSync() {
        `when`(syncRepository.syncProgress).thenReturn(
            Observable.just(SyncRepository.SyncProgress.Running(0, 0, true))
        )

        val result = reconciler.reconcile { }

        assertEquals(ForwardingReconciliationResult.RETRY, result)
        verify(source, never()).snapshot()
    }
}
