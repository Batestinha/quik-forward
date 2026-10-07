/* Copyright (C) 2026, GPL-3.0-or-later */
package dev.octoshrimpy.quik.keepalive

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.telecom.PhoneAccountHandle
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import dev.octoshrimpy.quik.manager.PermissionManager
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeepAliveSims @Inject constructor(private val context: Context, private val permissions: PermissionManager,
    private val phoneNumbers: dev.octoshrimpy.quik.forwarding.SimPhoneNumbers) {
    @Suppress("DEPRECATION", "MissingPermission")
    fun discover(): List<SimIdentity> {
        if (!permissions.hasPhone()) return emptyList()
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            SubscriptionManager.from(context).activeSubscriptionInfoList.orEmpty().map { info ->
                val serial = runCatching {
                    if (Build.VERSION.SDK_INT >= 24) tm.createForSubscriptionId(info.subscriptionId).simSerialNumber
                    else info.iccId
                }.getOrNull().orEmpty()
                val number = phoneNumbers.read(info.subscriptionId, runCatching { info.number }.getOrNull().orEmpty())
                SimIdentity(if (serial.isNotBlank()) serialKey(serial) else "subscription:${info.subscriptionId}",
                    info.subscriptionId, info.simSlotIndex,
                    "${info.displayName} · SIM ${info.simSlotIndex + 1}" + (if (number.isBlank()) "" else " · $number"), number)
            }
        } catch (_: SecurityException) { emptyList() }
    }

    @Suppress("MissingPermission")
    fun forCall(record: CallRecord, sims: List<SimIdentity>): SimIdentity? = runCatching {
        val component = ComponentName.unflattenFromString(record.component) ?: return null
        val handle = PhoneAccountHandle(component, record.account)
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        if (Build.VERSION.SDK_INT >= 30) {
            val id = tm.getSubscriptionId(handle)
            sims.singleOrNull { it.subscriptionId == id }
        } else if (Build.VERSION.SDK_INT >= 26) {
            val serial = tm.createForPhoneAccountHandle(handle)?.simSerialNumber.orEmpty()
            if (serial.isBlank()) null else sims.singleOrNull { it.key == serialKey(serial) }
        } else {
            // Older Telecom implementations use the ICCID as the account handle. Only exact
            // identity matches are accepted; the value is never interpreted as a slot or sub ID.
            sims.singleOrNull { it.key == serialKey(record.account) }
        }
    }.getOrNull()

    private fun serialKey(serial: String): String = "sim:" + MessageDigest.getInstance("SHA-256")
        .digest(serial.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
