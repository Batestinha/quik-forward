/* Copyright (C) 2026, GPL-3.0-or-later */
package dev.octoshrimpy.quik.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import dev.octoshrimpy.quik.keepalive.KeepAliveScheduler
import java.util.concurrent.Executor

class KeepAliveSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val attempt = intent.getStringExtra("attempt") ?: return
        val result = resultCode
        val pending = goAsync()
        try {
            KeepAliveScheduler.result(context, attempt, result, System.currentTimeMillis()).result
                .addListener({ pending.finish() }, Executor { it.run() })
        } catch (_: Exception) { pending.finish() }
    }
}

class KeepAliveSignalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == TelephonyManager.ACTION_PHONE_STATE_CHANGED && intent.getStringExtra(TelephonyManager.EXTRA_STATE) != TelephonyManager.EXTRA_STATE_IDLE) return
        if (action in setOf(TelephonyManager.ACTION_PHONE_STATE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, "android.telephony.action.CARRIER_CONFIG_CHANGED"))
            KeepAliveScheduler.reconcile(context, if (action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) 15 else 0)
    }
}
