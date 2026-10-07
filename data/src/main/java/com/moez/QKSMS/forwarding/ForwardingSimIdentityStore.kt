/* Copyright (C) 2026, GPL-3.0-or-later */
package dev.octoshrimpy.quik.forwarding

import android.content.SharedPreferences
import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import javax.inject.Inject
import javax.inject.Singleton

data class ForwardingSimIdentity(val number: String = "", val label: String = "")

/** Android's legacy SIM number and even its default number source can contain a carrier placeholder. */
@Singleton
class SimPhoneNumbers @Inject constructor(private val context: Context) {
    @Suppress("MissingPermission")
    fun read(subscriptionId: Int, legacyNumber: String): String = firstUsableSimNumber(sequence {
        if (Build.VERSION.SDK_INT >= 33) {
            val subscriptions = context.getSystemService(SubscriptionManager::class.java)
            for (source in listOf(SubscriptionManager.PHONE_NUMBER_SOURCE_CARRIER,
                SubscriptionManager.PHONE_NUMBER_SOURCE_UICC, SubscriptionManager.PHONE_NUMBER_SOURCE_IMS)) {
                val candidate = runCatching { subscriptions.getPhoneNumber(subscriptionId, source) }.getOrDefault("")
                yield(candidate)
            }
        }
        yield(legacyNumber)
    })
}

/** User-supplied display identity, keyed by the SIM serial hash, never by slot/default SIM. */
@Singleton
class ForwardingSimIdentityStore @Inject constructor(private val preferences: SharedPreferences) {
    fun load(simKey: String): ForwardingSimIdentity = ForwardingSimIdentity(
        preferences.getString("forwarding.sim.$simKey.number", "").orEmpty(),
        preferences.getString("forwarding.sim.$simKey.label", "").orEmpty()
    )

    fun save(simKey: String, number: String, label: String) {
        require(simKey.startsWith("sim:")) { "SIM identity is unavailable. Grant phone access and make QUIK the default SMS app." }
        val normalized = normalizeSimNumber(number)
        require(normalized.isEmpty() || (normalized.matches(Regex("\\+[1-9][0-9]{6,14}")) && usableSimNumber(normalized).isNotEmpty())) {
            "Enter the real number with + and country code, or leave it blank for automatic detection."
        }
        val edit = preferences.edit()
        if (normalized.isEmpty()) edit.remove("forwarding.sim.$simKey.number")
        else edit.putString("forwarding.sim.$simKey.number", normalized)
        val safeLabel = label.replace(Regex("[\\r\\n]+"), " ").trim()
        if (safeLabel.isEmpty()) edit.remove("forwarding.sim.$simKey.label")
        else edit.putString("forwarding.sim.$simKey.label", safeLabel)
        edit.apply()
    }
}

internal fun normalizeSimNumber(value: String): String = value.trim().replace(Regex("[\\s().-]"), "")

/** SIMs can contain an empty or dummy MSISDN. Never present obvious zero placeholders as a real number. */
internal fun usableSimNumber(value: String): String {
    val number = normalizeSimNumber(value)
    return number.takeIf { it.matches(Regex("\\+?[0-9]{5,15}")) && !it.matches(Regex(".*0{7,}$")) }.orEmpty()
}

internal fun firstUsableSimNumber(candidates: Sequence<String>): String = candidates.map(::usableSimNumber)
    .firstOrNull(String::isNotEmpty).orEmpty()

internal fun isPhoneAddress(value: String): Boolean = value.any { it in '0'..'9' } &&
    value.all { it in '0'..'9' || it in "+*#()- ." }
