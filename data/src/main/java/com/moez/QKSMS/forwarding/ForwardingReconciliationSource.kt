/* Copyright (C) 2026, GPL-3.0-or-later */
package dev.octoshrimpy.quik.forwarding

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.BaseColumns
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import com.google.android.mms.pdu_alt.PduHeaders
import javax.inject.Inject
import javax.inject.Singleton

data class ForwardingHighWaterMarks(val smsId: Long, val mmsId: Long)

@Singleton
open class ForwardingReconciliationSource @Inject constructor(
    private val contentResolver: ContentResolver
) {
    open fun snapshot(): ForwardingHighWaterMarks? {
        val smsId = highestId(Sms.CONTENT_URI) ?: return null
        val mmsId = highestId(Mms.CONTENT_URI) ?: return null
        return ForwardingHighWaterMarks(smsId, mmsId)
    }

    open fun incomingMessages(
        after: ForwardingHighWaterMarks,
        through: ForwardingHighWaterMarks
    ): List<Uri> = buildList {
        addAll(
            queryIds(
                uri = Sms.CONTENT_URI,
                afterId = after.smsId,
                throughId = through.smsId,
                typeColumn = Sms.TYPE,
                typeValue = Sms.MESSAGE_TYPE_INBOX
            ).map { ContentUris.withAppendedId(Sms.CONTENT_URI, it) }
        )
        addAll(
            queryIds(
                uri = Mms.CONTENT_URI,
                afterId = after.mmsId,
                throughId = through.mmsId,
                typeColumn = Mms.MESSAGE_BOX,
                typeValue = Mms.MESSAGE_BOX_INBOX,
                extraSelection = "${Mms.MESSAGE_TYPE} = ?",
                extraArgument = PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF.toString()
            ).map { ContentUris.withAppendedId(Mms.CONTENT_URI, it) }
        )
    }

    private fun highestId(uri: Uri): Long? = contentResolver.query(
        uri,
        arrayOf(BaseColumns._ID),
        null,
        null,
        "${BaseColumns._ID} DESC"
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

    private fun queryIds(
        uri: Uri,
        afterId: Long,
        throughId: Long,
        typeColumn: String,
        typeValue: Int,
        extraSelection: String? = null,
        extraArgument: String? = null
    ): List<Long> {
        if (throughId <= afterId) return emptyList()
        val selection = buildString {
            append("${BaseColumns._ID} > ? AND ${BaseColumns._ID} <= ? AND $typeColumn = ?")
            if (extraSelection != null) append(" AND $extraSelection")
        }
        val arguments = mutableListOf(
            afterId.toString(),
            throughId.toString(),
            typeValue.toString()
        ).apply { if (extraArgument != null) add(extraArgument) }

        return contentResolver.query(
            uri,
            arrayOf(BaseColumns._ID),
            selection,
            arguments.toTypedArray(),
            "${BaseColumns._ID} ASC"
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getLong(0))
            }
        } ?: throw IllegalStateException("SMS/MMS provider is unavailable")
    }
}
