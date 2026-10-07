/* Copyright (C) 2026, GPL-3.0-or-later */
package dev.octoshrimpy.quik.feature.keepalive

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.text.InputType
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import dagger.android.AndroidInjection
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.base.QkThemedActivity
import dev.octoshrimpy.quik.keepalive.*
import dev.octoshrimpy.quik.manager.PermissionManager
import rikka.shizuku.Shizuku
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class KeepAliveSettingsActivity : QkThemedActivity() {
    @Inject lateinit var manager: KeepAliveManager
    @Inject lateinit var permissions: PermissionManager
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var cards: LinearLayout
    private val executor = Executors.newSingleThreadExecutor()
    private var loading = false
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        KeepAliveObservers.start(applicationContext)
        refresh()
    }
    private val roleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }
    private val shizukuPermission = Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.keepalive_settings_activity)
        title = getString(R.string.keepalive_title)
        showBackButton(true)
        content = findViewById(R.id.keepAliveContent)
        status = label(content, "Loading SIMs…")
        button(content, "Make QUIK the default SMS app") {
            if (Build.VERSION.SDK_INT >= 29) roleRequest.launch(getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_SMS))
            else roleRequest.launch(Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName))
        }
        button(content, "Grant SMS, phone and call-history access") {
            permissionRequest.launch((listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS, Manifest.permission.READ_CALL_LOG) +
                (if (Build.VERSION.SDK_INT >= 26) listOf(Manifest.permission.READ_PHONE_NUMBERS) else emptyList()) +
                if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()).toTypedArray())
        }
        button(content, "Connect Shizuku for call tracking") {
            if (runCatching { Shizuku.pingBinder() && Shizuku.getVersion() >= 13 }.getOrDefault(false)) {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) refresh()
                else Shizuku.requestPermission(7109)
            } else AlertDialog.Builder(this).setTitle("Start Shizuku")
                .setMessage("Install/start Shizuku, then return here and grant QUIK access. On non-root Android 10, Shizuku needs an ADB start after each reboot. SMS timers continue while call tracking is unavailable.")
                .setPositiveButton("Setup instructions") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/guide/setup/"))) }
                .setNegativeButton("Close", null).show()
        }
        button(content, "Refresh SIMs and activity") { refresh() }
        cards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(cards)
        Shizuku.addRequestPermissionResultListener(shizukuPermission)
    }

    override fun onResume() { super.onResume(); if (::cards.isInitialized) refresh() }
    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermission)
        executor.shutdown()
        super.onDestroy()
    }

    private fun refresh() {
        if (loading || isDestroyed) return
        loading = true
        status.text = "Checking SIMs and recorded activity…"
        executor.execute {
            val result = runCatching {
                manager.refresh()
                val sims = manager.sims.discover()
                val rules = manager.store.rules()
                val current = sims.map { sim -> rules.firstOrNull { it.simKey == sim.key }
                    ?: manager.preview(KeepAliveRule(sim.key, sim.subscriptionId, sim.label)) }
                current + rules.filter { rule -> sims.none { it.key == rule.simKey } }
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                loading = false
                result.onSuccess { rules ->
                    status.text = if (!permissions.isDefaultSms()) "Default SMS role is required for automatic sending."
                        else if (!permissions.hasPhone() || !permissions.hasReadSms()) "Grant phone and SMS access to discover SIMs and activity."
                        else if (rules.isEmpty()) "No active SIMs found. Insert or enable a SIM, then refresh."
                        else "${rules.size} SIM configuration(s). Each SIM has an independent timer."
                    render(rules)
                }.onFailure { status.text = "Could not refresh: ${it.message ?: "check permissions and try again"}" }
            }
        }
    }

    private fun render(rules: List<KeepAliveRule>) {
        cards.removeAllViews()
        rules.forEach { rule ->
            val group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 24, 0, 24) }
            cards.addView(group)
            label(group, rule.label).textSize = 20f
            label(group, if (rule.enabled) rule.status else "Disabled")
            val nextSend = when {
                !rule.enabled -> "disabled; preview ${date(KeepAlivePolicy.dueAt(rule), due = true)}"
                rule.status.startsWith("Paused:") -> "paused"
                rule.pendingAttempt != null -> if (rule.status.contains("unknown")) "manual resolution required" else "waiting for the send result"
                else -> date(KeepAlivePolicy.dueAt(rule), due = true)
            }
            label(group, "Last activity: ${date(rule.lastActivityAt)} (${rule.lastActivitySource})\n" +
                "Next send: $nextSend\n" +
                "Estimated ${rule.days}-day deadline: ${date(if (rule.lastActivityAt > 0) rule.lastActivityAt + TimeUnit.DAYS.toMillis(rule.days.toLong()) else 0)}\n" +
                rule.callStatus)
            button(group, "Configure this SIM") { configure(rule) }
            if (rule.enabled && rule.pendingAttempt == null && !rule.status.startsWith("Paused:")) button(group, "Send now") {
                AlertDialog.Builder(this).setTitle("Send keep-alive SMS?")
                    .setMessage("${rule.label}\nTo: ${rule.destinations.getOrNull(rule.destinationIndex).orEmpty()}\n${rule.body}\n\nYour configured fallback numbers will be tried if sending fails. SMS charges may apply.")
                    .setPositiveButton("Send") { _, _ -> KeepAliveScheduler.sendNow(this, rule); status.text = "Send queued; refresh to see the result." }
                    .setNegativeButton("Cancel", null).show()
            }
            if (rule.pendingAttempt != null && rule.status.contains("unknown")) button(group, "Resolve unknown send / retry") {
                AlertDialog.Builder(this).setTitle("Retry with an unknown previous outcome?")
                    .setMessage("Inspect the conversation first. The previous SMS might already have been sent. Retrying can create a duplicate charge.")
                    .setPositiveButton("Retry") { _, _ -> action { manager.retryUnknown(rule.simKey) } }
                    .setNegativeButton("Cancel", null).show()
            }
        }
    }

    private fun configure(rule: KeepAliveRule) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 16) }
        val enabled = SwitchCompat(this).apply { text = "Enable automatic keep-alive"; isChecked = rule.enabled }
        form.addView(enabled)
        val days = field(form, "Inactivity limit (days)", rule.days.toString(), InputType.TYPE_CLASS_NUMBER)
        val margin = field(form, "Send this many days early", rule.marginDays.toString(), InputType.TYPE_CLASS_NUMBER)
        val destinations = field(form, "Fallback phone numbers, one per line (first is preferred)", rule.destinations.joinToString("\n"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        val body = field(form, "Message (one SMS segment)", rule.body, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        label(form, "The first timer uses recorded activity. With no history, or if already overdue, enabling makes an SMS due immediately. Background execution may be delayed by Android; keep the early-send margin.")
        val errors = label(form, "")
        val scroll = ScrollView(this).apply { addView(form) }
        val dialog = AlertDialog.Builder(this).setTitle(rule.label).setView(scroll)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null)
            .setNeutralButton("Delete rule") { _, _ -> action { manager.delete(rule.simKey) } }.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val updated = rule.copy(enabled = enabled.isChecked,
                    days = days.text.toString().toIntOrNull() ?: 0, marginDays = margin.text.toString().toIntOrNull() ?: -1,
                    destinations = KeepAlivePolicy.destinations(destinations.text.toString()),
                    body = body.text.toString())
                val error = KeepAlivePolicy.validationError(updated)
                    ?: if (android.telephony.SmsMessage.calculateLength(updated.body, false)[0] != 1) "The message must fit in a single SMS segment." else null
                if (error != null) { errors.text = error; return@setOnClickListener }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                executor.execute {
                    val saved = runCatching { manager.save(updated) }
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        saved.onSuccess { dialog.dismiss(); refresh() }
                            .onFailure { errors.text = it.message; dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun action(block: () -> Unit) {
        executor.execute {
            val result = runCatching(block)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { refresh() }.onFailure { status.text = it.message ?: "Action failed" }
            }
        }
    }

    private fun label(parent: LinearLayout, value: String): TextView = TextView(this).apply {
        text = value; setPadding(0, 8, 0, 8); parent.addView(this)
    }
    private fun button(parent: LinearLayout, value: String, click: () -> Unit) {
        parent.addView(Button(this).apply { text = value; setOnClickListener { click() } })
    }
    private fun field(parent: LinearLayout, name: String, value: String, type: Int): EditText {
        label(parent, name)
        return EditText(this).apply { inputType = type; setText(value); parent.addView(this) }
    }
    private fun date(timestamp: Long, due: Boolean = false): String = when {
        timestamp <= 0 -> if (due) "Due now (no recorded activity)" else "Unknown"
        due && timestamp <= System.currentTimeMillis() -> "Due now"
        else -> DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))
    }
}
