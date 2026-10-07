# QUIK Forward

This fork is based on QUIK 4.3.7 and uses the application ID
`local.quikforward.sms`. The Android/Kotlin namespace remains
`dev.octoshrimpy.quik` so the fork stays maintainable against upstream.

For per-SIM inactivity timers and automatic SMS, see [SIM keep-alive](SIM_KEEPALIVE.md).

## Behavior

- Incoming forwarding is scheduled only after QUIK's existing sender,
  conversation-blocking, and content-filter checks accept the message.
- Outgoing forwarding is scheduled only after the carrier reports a successful
  send. With Group messaging off, QUIK creates one carrier message per recipient,
  so forwarding also records one independently sent BCC message per recipient.
- SMS and MMS are independently selectable for incoming and outgoing directions.
- SMTP supports required STARTTLS or implicit TLS. Authentication is optional;
  when used, the app password is encrypted with an Android Keystore AES-GCM key.
- Forwarding destinations use the concealed BCC recipient field by default. This
  is configurable; disabling it puts destination addresses in the visible To field.
- MMS binary parts are copied into private app storage before delivery. The raw
  aggregate attachment limit is 10 MB and omitted files are listed in the email.
- Delivery uses a unique WorkManager chain, network constraints, exponential
  backoff, deterministic mail IDs, and local delivery receipts to avoid duplicates.

## Sender and recipient identity

Saved contacts are shown as `Name (Number)`. Alphanumeric SMS addresses such as
`Lyca Mobile` have no phone number to recover; these are labelled `Lyca Mobile
(sender ID)`, not `Unknown (Lyca Mobile)`. MMS email addresses are kept as addresses.

For incoming messages, the recipient is the SIM identified by the message's
subscription ID. Outgoing messages use that SIM as the sender. The app no longer
substitutes the only currently installed SIM for an old/unknown subscription.
Blank or obvious all-zero placeholder SIM numbers are shown as `number unavailable`.
On Android 13+, granting phone-number access lets QUIK try the carrier, SIM and
IMS sources in order, skipping empty/placeholder values. This can recover the
real number when a Lyca SIM has a dummy MSISDN but IMS exposes the actual number.

Use **Email forwarding → SIM phone numbers and names** to supply a SIM's real
number (with country code) and optional display name. These overrides are bound
to the SIM identity, not its slot. A blank name uses the saved contact name,
then the SIM's display name. Blank fields restore automatic detection. Overrides
affect newly captured forwarding jobs and do not change SMTP verification or
write any number to the SIM. Verify which number belongs to each SIM before
setting an override; QUIK cannot reliably infer a real number from a placeholder.

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

## Release signing

Local release builds use the persistent signing material below. Both files are
ignored by Git and must be backed up together in a secure offline location:

- `.secrets/quik-forward-release.p12`
- `.secrets/release-signing.password`

Do not regenerate or lose this key: Android will only accept application updates
signed by the same certificate. CI can instead provide `QUIK_RELEASE_KEYSTORE`,
`QUIK_RELEASE_KEY_ALIAS`, and `QUIK_RELEASE_PASSWORD` environment variables.
