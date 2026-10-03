/* Copyright (C) 2026, GPL-3.0-or-later */
package dev.octoshrimpy.quik.forwarding

import dev.octoshrimpy.quik.manager.NotificationManager
import dev.octoshrimpy.quik.manager.ShortcutManager
import dev.octoshrimpy.quik.manager.WidgetManager
import dev.octoshrimpy.quik.repository.MessageRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ForwardingReadMarker @Inject constructor(
    private val messageRepository: MessageRepository,
    private val notificationManager: NotificationManager,
    private val shortcutManager: ShortcutManager,
    private val widgetManager: WidgetManager
) {
    fun mark(job: ForwardingJob): Boolean {
        if (job.direction != ForwardingDirection.INCOMING) return true

        return runCatching {
            messageRepository.markMessagesRead(listOf(job.sourceMessageId))
                .forEach(notificationManager::update)
            shortcutManager.updateBadge()
            widgetManager.sendDatasetChanged()
        }.isSuccess
    }
}
