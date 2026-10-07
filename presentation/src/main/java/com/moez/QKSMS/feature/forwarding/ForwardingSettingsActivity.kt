/* Copyright (C) 2026, GPL-3.0-or-later */
package dev.octoshrimpy.quik.feature.forwarding

import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import dagger.android.AndroidInjection
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.base.QkThemedActivity
import dev.octoshrimpy.quik.forwarding.ForwardingConfig
import dev.octoshrimpy.quik.forwarding.ForwardingManager
import dev.octoshrimpy.quik.forwarding.ForwardingRule
import dev.octoshrimpy.quik.forwarding.RuleAction
import dev.octoshrimpy.quik.forwarding.RuleMatcher
import dev.octoshrimpy.quik.forwarding.SmtpSecurity
import java.util.UUID
import javax.inject.Inject

class ForwardingSettingsActivity : QkThemedActivity() {

    @Inject lateinit var forwardingManager: ForwardingManager
    @Inject lateinit var sims: dev.octoshrimpy.quik.keepalive.KeepAliveSims
    @Inject lateinit var simIdentities: dev.octoshrimpy.quik.forwarding.ForwardingSimIdentityStore

    private lateinit var enabled: SwitchCompat
    private lateinit var incomingSms: SwitchCompat
    private lateinit var incomingMms: SwitchCompat
    private lateinit var outgoingSms: SwitchCompat
    private lateinit var outgoingMms: SwitchCompat
    private lateinit var allowOnly: SwitchCompat
    private lateinit var smtpHost: EditText
    private lateinit var smtpPort: EditText
    private lateinit var smtpSecurity: Spinner
    private lateinit var smtpUsername: EditText
    private lateinit var smtpPassword: EditText
    private lateinit var fromAddress: EditText
    private lateinit var recipients: EditText
    private lateinit var bccRecipients: SwitchCompat
    private lateinit var status: TextView
    private lateinit var ruleList: LinearLayout
    private lateinit var testButton: Button
    private var loading = false
    private var pendingContactValue: EditText? = null
    private var pendingContactLabel: String = ""
    private val phoneNumberPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { selectSimIdentity() }

    private val contactPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        contentResolver.query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                pendingContactValue?.setText(cursor.getString(0))
                pendingContactLabel = cursor.getString(1).orEmpty()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.forwarding_settings_activity)
        title = getString(R.string.forwarding_title)
        showBackButton(true)

        bindViews()
        smtpSecurity.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("STARTTLS (required)", "TLS/SSL")
        )
        populate(forwardingManager.loadConfig())
        refreshRules()

        enabled.setOnCheckedChangeListener { _, checked ->
            if (!loading && checked && !forwardingManager.isVerified(
                    configFromForm(checked), passwordForCurrentForm()
                )) {
                loading = true
                enabled.isChecked = false
                loading = false
                status.setText(R.string.forwarding_not_verified)
                Toast.makeText(this, R.string.forwarding_not_verified, Toast.LENGTH_LONG).show()
            }
        }
        findViewById<Button>(R.id.saveForwarding).setOnClickListener { save() }
        testButton.setOnClickListener { testConnection() }
        findViewById<Button>(R.id.addAllowRule).setOnClickListener {
            showRuleDialog(RuleAction.ALLOW, null)
        }
        findViewById<Button>(R.id.addDenyRule).setOnClickListener {
            showRuleDialog(RuleAction.DENY, null)
        }
        findViewById<Button>(R.id.forwardingSimIdentity).setOnClickListener {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.READ_PHONE_NUMBERS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                phoneNumberPermission.launch(android.Manifest.permission.READ_PHONE_NUMBERS)
            else selectSimIdentity()
        }
    }

    private fun selectSimIdentity() {
        val active = sims.discover()
        if (active.isEmpty()) {
            AlertDialog.Builder(this).setMessage("No active SIMs are accessible. Grant phone permission and make QUIK the default SMS app.")
                .setPositiveButton("OK", null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Choose the SIM to label in emails")
            .setItems(active.map { it.label }.toTypedArray()) { _, index -> editSimIdentity(active[index]) }.show()
    }

    private fun editSimIdentity(sim: dev.octoshrimpy.quik.keepalive.SimIdentity) {
        val saved = simIdentities.load(sim.key)
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 16) }
        form.addView(TextView(this).apply {
            text = "Some SIMs report a blank or placeholder phone number. Enter this SIM’s real number; it will be used as the recipient for incoming messages and sender for outgoing messages. This changes email labels only, not the SIM or carrier settings."
        })
        form.addView(TextView(this).apply { text = "Real SIM number (+country code); blank = automatic" })
        val number = EditText(this).apply { inputType = InputType.TYPE_CLASS_PHONE; setText(saved.number); form.addView(this) }
        form.addView(TextView(this).apply { text = "Display name; blank = saved contact or SIM name" })
        val label = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT; setText(saved.label); form.addView(this) }
        val errors = TextView(this).also(form::addView)
        val dialog = AlertDialog.Builder(this).setTitle(sim.label)
            .setView(android.widget.ScrollView(this).apply { addView(form) })
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching { simIdentities.save(sim.key, number.text.toString(), label.text.toString()) }
                    .onSuccess { dialog.dismiss(); Toast.makeText(this, "SIM identity saved for newly forwarded messages.", Toast.LENGTH_LONG).show() }
                    .onFailure { errors.text = it.message }
            }
        }
        dialog.show()
    }

    private fun bindViews() {
        enabled = findViewById(R.id.forwardingEnabled)
        incomingSms = findViewById(R.id.incomingSms)
        incomingMms = findViewById(R.id.incomingMms)
        outgoingSms = findViewById(R.id.outgoingSms)
        outgoingMms = findViewById(R.id.outgoingMms)
        allowOnly = findViewById(R.id.allowOnly)
        smtpHost = findViewById(R.id.smtpHost)
        smtpPort = findViewById(R.id.smtpPort)
        smtpSecurity = findViewById(R.id.smtpSecurity)
        smtpUsername = findViewById(R.id.smtpUsername)
        smtpPassword = findViewById(R.id.smtpPassword)
        fromAddress = findViewById(R.id.fromAddress)
        recipients = findViewById(R.id.emailRecipients)
        bccRecipients = findViewById(R.id.bccRecipients)
        status = findViewById(R.id.forwardingStatus)
        ruleList = findViewById(R.id.ruleList)
        testButton = findViewById(R.id.testForwarding)
    }

    private fun populate(config: ForwardingConfig) {
        loading = true
        enabled.isChecked = config.enabled
        incomingSms.isChecked = config.incomingSms
        incomingMms.isChecked = config.incomingMms
        outgoingSms.isChecked = config.outgoingSms
        outgoingMms.isChecked = config.outgoingMms
        allowOnly.isChecked = config.allowListOnly
        smtpHost.setText(config.smtpHost)
        smtpPort.setText(config.smtpPort.toString())
        smtpSecurity.setSelection(if (config.smtpSecurity == SmtpSecurity.STARTTLS) 0 else 1)
        smtpUsername.setText(config.smtpUsername)
        fromAddress.setText(config.fromAddress)
        recipients.setText(config.recipients.joinToString("\n"))
        bccRecipients.isChecked = config.bccRecipients
        loading = false
        updateVerificationStatus(config)
    }

    private fun configFromForm(isEnabled: Boolean = enabled.isChecked) = ForwardingConfig(
        enabled = isEnabled,
        incomingSms = incomingSms.isChecked,
        incomingMms = incomingMms.isChecked,
        outgoingSms = outgoingSms.isChecked,
        outgoingMms = outgoingMms.isChecked,
        allowListOnly = allowOnly.isChecked,
        smtpHost = smtpHost.text.toString().trim(),
        smtpPort = smtpPort.text.toString().toIntOrNull() ?: 0,
        smtpSecurity = if (smtpSecurity.selectedItemPosition == 0) {
            SmtpSecurity.STARTTLS
        } else {
            SmtpSecurity.TLS
        },
        smtpUsername = smtpUsername.text.toString().trim(),
        fromAddress = fromAddress.text.toString().trim(),
        recipients = recipients.text.toString()
            .split(Regex("[;\\n]+"))
            .map(String::trim)
            .filter(String::isNotBlank),
        bccRecipients = bccRecipients.isChecked
    )

    private fun typedPassword(): String? = smtpPassword.text.toString().takeIf(String::isNotEmpty)

    private fun passwordForCurrentForm(): String = typedPassword() ?: forwardingManager.storedPassword()

    private fun save() {
        val requested = configFromForm()
        val saved = forwardingManager.saveConfig(requested, typedPassword())
        smtpPassword.text?.clear()
        loading = true
        enabled.isChecked = saved.enabled
        loading = false
        updateVerificationStatus(saved)
        val message = if (requested.enabled && !saved.enabled) {
            getString(R.string.forwarding_saved_not_verified)
        } else {
            getString(R.string.forwarding_saved)
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun testConnection() {
        val config = configFromForm()
        val password = typedPassword()
        testButton.isEnabled = false
        status.text = "Testing secure SMTP connection…"
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val result = runCatching {
                forwardingManager.test(config, password)
                forwardingManager.markVerified(config, password)
                forwardingManager.saveConfig(config, null)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                testButton.isEnabled = true
                if (result.isSuccess) {
                    smtpPassword.text?.clear()
                    loading = true
                    enabled.isChecked = result.getOrThrow().enabled
                    loading = false
                    status.setText(R.string.forwarding_verified)
                } else {
                    val detail = result.exceptionOrNull()?.message
                        ?.takeIf(String::isNotBlank)
                        ?: getString(R.string.forwarding_test_failed_unknown)
                    status.text = getString(R.string.forwarding_test_failed, detail)
                    Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
                }
            }
        }, "smtp-settings-test").start()
    }

    private fun updateVerificationStatus(config: ForwardingConfig) {
        status.setText(
            if (forwardingManager.isVerified(config)) R.string.forwarding_verified
            else R.string.forwarding_not_verified
        )
    }

    private fun refreshRules() {
        ruleList.removeAllViews()
        forwardingManager.loadRules().forEach { rule ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val description = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = describe(rule)
                setOnClickListener { showRuleDialog(rule.action, rule) }
            }
            val delete = Button(this).apply {
                text = "Delete"
                setOnClickListener {
                    forwardingManager.deleteRule(rule.id)
                    refreshRules()
                }
            }
            row.addView(description)
            row.addView(delete)
            ruleList.addView(row)
        }
    }

    private fun describe(rule: ForwardingRule): String {
        val scopes = buildList {
            if (rule.incoming) add("incoming")
            if (rule.outgoing) add("outgoing")
            if (rule.sms) add("SMS")
            if (rule.mms) add("MMS")
        }.joinToString(" · ")
        val displayValue = rule.label.takeIf(String::isNotBlank)?.let { "$it (${rule.value})" } ?: rule.value
        return "${rule.action.name}: ${rule.matcher.name.replace('_', ' ')} — $displayValue\n$scopes"
    }

    private fun showRuleDialog(action: RuleAction, existing: ForwardingRule?) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val matcher = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ForwardingSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Exact address/contact", "Literal text", "Safe RE2 regular expression")
            )
            setSelection(existing?.matcher?.ordinal ?: 0)
        }
        val value = EditText(this).apply {
            hint = "Value"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(existing?.value.orEmpty())
        }
        val pickContact = Button(this).apply {
            text = "Pick contact"
            setOnClickListener {
                pendingContactValue = value
                contactPicker.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
            }
        }
        val incoming = checkBox("Incoming", existing?.incoming ?: true)
        val outgoing = checkBox("Outgoing", existing?.outgoing ?: true)
        val sms = checkBox("SMS", existing?.sms ?: true)
        val mms = checkBox("MMS", existing?.mms ?: true)
        val caseSensitive = checkBox("Case-sensitive text", existing?.caseSensitive ?: false)
        listOf<View>(matcher, value, pickContact, incoming, outgoing, sms, mms, caseSensitive)
            .forEach(content::addView)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (action == RuleAction.ALLOW) "Allow rule" else "Deny rule")
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if ((!incoming.isChecked && !outgoing.isChecked) || (!sms.isChecked && !mms.isChecked)) {
                    Toast.makeText(this, "Choose at least one direction and message type", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val rule = ForwardingRule(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    action = action,
                    matcher = RuleMatcher.values()[matcher.selectedItemPosition],
                    value = value.text.toString(),
                    label = pendingContactLabel.takeIf(String::isNotBlank) ?: existing?.label.orEmpty(),
                    caseSensitive = caseSensitive.isChecked,
                    incoming = incoming.isChecked,
                    outgoing = outgoing.isChecked,
                    sms = sms.isChecked,
                    mms = mms.isChecked
                )
                forwardingManager.saveRule(rule)?.let { error ->
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                pendingContactValue = null
                pendingContactLabel = ""
                dialog.dismiss()
                refreshRules()
            }
        }
        dialog.setOnDismissListener {
            pendingContactValue = null
            pendingContactLabel = ""
        }
        dialog.show()
    }

    private fun checkBox(label: String, checked: Boolean) = CheckBox(this).apply {
        text = label
        isChecked = checked
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
