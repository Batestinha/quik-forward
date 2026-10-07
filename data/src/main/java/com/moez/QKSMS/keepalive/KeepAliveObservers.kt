/* Copyright (C) 2026, GPL-3.0-or-later */
package dev.octoshrimpy.quik.keepalive

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.Telephony
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku

/** Process-lifetime listeners; work persists separately when this process is gone. */
object KeepAliveObservers {
    private var started = false
    private var subscriptions: SubscriptionManager.OnSubscriptionsChangedListener? = null
    private val main = Handler(Looper.getMainLooper())

    @Synchronized fun start(context: Context) {
        val app = context.applicationContext
        val reconcile = Runnable { KeepAliveScheduler.reconcile(app) }
        fun changed() { main.removeCallbacks(reconcile); main.postDelayed(reconcile, 1500) }
        if (!started) {
            started = true
            val observer = object : ContentObserver(main) { override fun onChange(selfChange: Boolean) { changed() } }
            listOf(Telephony.Sms.CONTENT_URI, Telephony.Mms.CONTENT_URI, CallLog.Calls.CONTENT_URI).forEach {
                runCatching { app.contentResolver.registerContentObserver(it, true, observer) }
            }
            Shizuku.addBinderReceivedListener { changed() }
            Shizuku.addBinderDeadListener { changed() }
        }
        if (subscriptions == null && ContextCompat.checkSelfPermission(app, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
                override fun onSubscriptionsChanged() { changed() }
            }
            runCatching {
                @Suppress("DEPRECATION")
                SubscriptionManager.from(app).addOnSubscriptionsChangedListener(listener)
                subscriptions = listener
            }
        }
    }
}
