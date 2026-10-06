/* Copyright (C) 2026, GPL-3.0-or-later */
package dev.octoshrimpy.quik.forwarding

import android.content.SharedPreferences
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class ForwardingReconciliationStore @Inject constructor(
    private val preferences: SharedPreferences
) {
    companion object {
        private const val PREF_INITIALIZED = "forwarding.reconciliation.initialized.v1"
        private const val PREF_SMS_ID = "forwarding.reconciliation.sms_id.v1"
        private const val PREF_MMS_ID = "forwarding.reconciliation.mms_id.v1"
    }

    open fun load(): ForwardingHighWaterMarks? {
        if (!preferences.getBoolean(PREF_INITIALIZED, false)) return null
        return ForwardingHighWaterMarks(
            smsId = preferences.getLong(PREF_SMS_ID, 0),
            mmsId = preferences.getLong(PREF_MMS_ID, 0)
        )
    }

    open fun save(marks: ForwardingHighWaterMarks): Boolean = preferences.edit()
        .putLong(PREF_SMS_ID, marks.smsId)
        .putLong(PREF_MMS_ID, marks.mmsId)
        .putBoolean(PREF_INITIALIZED, true)
        .commit()
}
