# Top-up SIM keep-alive

Open **Settings → SIM keep-alive**. Each discovered SIM has its own disabled-by-default
configuration, activity history and timer. This feature sends SMS; it does not
buy credit, check the balance or guarantee that a carrier will keep a SIM active.

## Setup

1. Keep QUIK as the default SMS app and grant SMS and phone access.
2. Configure the SIM, an inactivity limit, an early-send margin, a single-segment
   message and an ordered list of destination numbers. International numbers are
   recommended. The first number is preferred; later numbers are fallbacks,
   not additional recipients.
3. Review the last activity and next-send preview, then enable the rule. Existing
   sent-message/call history seeds the first timer. No history, or history older
   than the interval, makes a message due immediately when enabled.
4. Allow background operation in the phone's battery settings. WorkManager can
   be delayed by Doze, Samsung sleeping-app settings, force-stop or power loss;
   the early-send margin is important. A force-stopped app must be opened again.

The preset is a **120-day inactivity limit with a seven-day margin**: send after
113 days. This is based on [UZO's conditions for “Sem carregamentos obrigatórios”](https://www.uzo.pt/ajuda/tarifarios-e-adesao),
which require a paid outgoing call or SMS charged to recharge balance within
120 days. Recheck the carrier's current terms. QUIK cannot distinguish paid
activity from a free/bonus allowance. Successful outgoing MMS also resets the
app timer but may not satisfy the carrier's rule. The interval and message are
editable per SIM.

## What resets the timer

- A successfully sent SMS or MMS on that SIM, to any destination.
- An outgoing carrier call with positive connected duration on that SIM.
- A successful keep-alive SMS itself.

Incoming messages/calls, unanswered calls and failed sends do not reset it.
Success means Android's sent callback, not a delivery receipt or proof of a
charge. Call history uses the call's end time. Re-reading the same history does
not extend the timer again. Deleted or inaccessible history cannot be recovered.

### Call tracking and Shizuku

QUIK first tries Android's call-history permission. If unavailable it can use
an explicitly authorized [Shizuku](https://shizuku.rikka.app/guide/setup/)
connection. Tap **Connect Shizuku for call tracking** after starting Shizuku.
On non-root Android 10, Shizuku normally needs to be started through ADB after
each reboot; Android 11+ can use wireless debugging.

The short-lived Shizuku service accepts only a timestamp and queries a fixed
projection of outgoing, connected call metadata. It does not expose a general
shell interface, dial calls, read recordings or return phone numbers. Telecom
phone-account handles are mapped to the SIM; ambiguous accounts are not guessed.
Missing access or unmappable records are visible in settings. SMS-based timers
continue while call tracking is unavailable, and recorded calls are reconciled
when access returns.

## Scheduling and safe retries

Rules and send attempts are persisted separately from the message database.
One-shot WorkManager jobs are scheduled per SIM, with a six-hour reconciliation
job while any rule is enabled. Startup, reboot, app update, subscription changes,
message changes, call-end and clock changes also trigger reconciliation. The
worker rechecks activity, configuration and the active SIM immediately before
sending. SMS jobs have no internet connectivity requirement.

SIM matching uses a hash of its serial identity, not the default SMS SIM or its
slot. An absent/changed SIM or an unavailable serial identity pauses sending.
Grant the default SMS role and phone access if identity is inaccessible. Swapping
SIM slots does not authorize sending through the replacement SIM.

Automatic messages are one SMS segment, with no appended signature or automatic
MMS conversion. They appear in normal conversation history and follow the existing
outgoing email-forwarding settings and filters.

- A confirmed destination-specific failure advances to the next configured number
  after one minute; a successful send stops the sequence.
- No service, radio-off or rate-limit errors retain the current destination.
- After a failed pass, retry delays are one hour, six hours, then 24 hours; later
  retries remain daily. New qualifying activity cancels that retry cycle.
- The dispatch marker is saved before handing the SMS to Android. If the app
  restarts mid-send, it reconciles the provider and waits up to ten minutes for
  a result. An ambiguous outcome pauses automatic sends. **Resolve unknown send /
  retry** requires explicit acknowledgement of a possible duplicate charge.
- Disabling/deleting a rule stops future sends, but cannot recall an SMS already
  submitted to the radio. **Send now** requires confirmation and observes the same
  identity, permission and pending-attempt guards.

This is best-effort automation, not a carrier-account guarantee. Keep sufficient
credit and independently check carrier expiry, especially after reboots or
extended downtime. No call is automatically placed.

## Development verification

Use JDK 17 and Android SDK 34. On a shared host limit CPU/worker usage and use the
host's guarded-build wrapper when installed:

```sh
JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=2 -XX:ParallelGCThreads=2 -XX:ConcGCThreads=1' \
  nice -n 10 ionice -c 2 -n 7 ./gradlew --no-daemon --no-build-cache --max-workers=2 \
  :data:testDebugUnitTest --tests 'dev.octoshrimpy.quik.keepalive.*' \
  --tests 'dev.octoshrimpy.quik.forwarding.*' :presentation:assembleRelease
```

Policy tests cover independent timers, old-history replay, late callbacks,
fallbacks/backoff, pending-send guards, serialization and strict number parsing.
Real-device checks should also cover SIM discovery, direct/Shizuku call access,
one explicitly authorized live SMS, restart persistence and disabled rules.
Do not test by sending to arbitrary numbers or by rewriting the user's message
history. Real carrier expiry cannot be validated by a short test.
