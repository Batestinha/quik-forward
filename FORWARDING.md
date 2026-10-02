# QUIK Forward

This fork is based on QUIK 4.3.7 and uses the application ID
`local.quikforward.sms`. The Android/Kotlin namespace remains
`dev.octoshrimpy.quik` so the fork stays maintainable against upstream.

## Behavior

- Incoming forwarding is scheduled only after QUIK's existing sender,
  conversation-blocking, and content-filter checks accept the message.
- Outgoing forwarding is scheduled only after the carrier reports a successful
  send. With Group messaging off, QUIK creates one carrier message per recipient,
  so forwarding also records one independently sent BCC message per recipient.
- SMS and MMS are independently selectable for incoming and outgoing directions.
- SMTP supports required STARTTLS or implicit TLS. Authentication is optional;
  when used, the app password is encrypted with an Android Keystore AES-GCM key.
- MMS binary parts are copied into private app storage before delivery. The raw
  aggregate attachment limit is 10 MB and omitted files are listed in the email.
- Delivery uses a unique WorkManager chain, network constraints, exponential
  backoff, deterministic mail IDs, and local delivery receipts to avoid duplicates.

## Allow and deny rules

Rules can match an exact phone/address/contact, literal text, or an RE2 regular
expression. Every rule is independently scoped to incoming/outgoing and SMS/MMS.
Deny rules always win.

In **Allowlisted only** mode, at least one applicable allow rule must exist. If
address rules exist, every participant must match one. If text rules exist, the
message body/subject must match one. When both rule classes exist, both checks are
required. In **All except denied** mode, any message without a matching deny rule
is eligible.

## Local verification

Use a Java 17 JDK and Android SDK 34. Keep builds isolated and single-worker when
sharing a development host:

```sh
./gradlew --no-daemon --max-workers=1 :data:testDebugUnitTest
./gradlew --no-daemon --max-workers=1 :presentation:assembleDebug
```

Before enabling forwarding, save the SMTP fields and use **Test connection**.
Changing the server, credentials, sender, or recipient list invalidates the test
and automatically disables forwarding until the new settings are verified.
