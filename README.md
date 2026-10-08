# Payment Flows Simulator

Live demo companion for the talk **"Building Payment Flows That Don't Break"**.

One device, no backend. The app runs a payment client and a payment server in the same process, with a
fake network between them that can fail at the exact moments the talk describes. You reproduce a double
charge with the Naive client, then show each pattern fixing it with the Safe client.

The app has three tabs: **Pay** (pick a demo, tap Pay, watch the state), **Payments** (every payment saved
on the phone and every charge the bank made) and **What happened** (every step, step by step, in plain English).

No request leaves the device: the fake server answers from an OkHttp interceptor, and the app does not
even declare the `INTERNET` permission.

## The four patterns

| Pattern | What it does | Where |
|---|---|---|
| State machine | `PaymentState` replaces the `isLoading` boolean. Illegal moves (for example Success -> Failed) throw in debug builds. | `model/PaymentState.kt` |
| Offline-first queue | The payment is written to Room (`pending_payments`) before any network call, so it survives a crash. Pay can be tapped again for another payment while earlier ones wait; they are sent one at a time, in order. Network errors are retried with exponential backoff (1s, 2s, 4s, 8s, 16s + jitter), then handed to WorkManager. | `client/SafePaymentClient.kt`, `client/work/BackgroundRetryWorker.kt` |
| Idempotency keys | The client generates one key per user intent and sends the same key on every retry. Tapping Pay again for the same amount and recipient while that payment is unfinished reuses its key. The server claims the key first (`INSERT ... ON CONFLICT DO NOTHING`), and a replay gets the stored result instead of a second charge. | `client/SafePaymentClient.kt`, `server/FakePaymentServer.kt` |
| Reconciliation | On every app start, and from the worker, each unsettled payment asks the server what happened (`GET /payments/{key}`) before doing anything. Payments older than the TTL are never sent silently: the user is asked. | `SafePaymentClient.reconcilePendingPayments` |

`docs/SLIDE_MAP.md` maps each slide to the file and function that implements it.

## How to run

Open the folder in Android Studio and run the `app` configuration, or from a terminal:

```
./gradlew installDebug                 # install on a running emulator or device (API 26+)
./gradlew test                         # JVM tests: state machine, money types
./gradlew connectedDebugAndroidTest    # instrumented tests: server, repository, reconciliation, worker, scenarios
```

`BackgroundRetryWorker` has a real `NetworkType.CONNECTED` constraint, so the emulator needs working
(real) connectivity for the worker to run. The simulated modes are applied on top of that.

## Presenter checklist

On the **Pay** tab, tap a demo chip (the row scrolls sideways). That clears all data, sets every setting and
the amount for that demo, and shows what to do next under the chips. The coloured strip shows the internet
state and has the **Go online** button. The badge under Pay shows the state machine with a plain-English line.

| # | Slide | Chip | What the chip sets | Steps | What the audience sees |
|---|---|---|---|---|---|
| 1 | 3, 13 | Double charge | Careless, bank forgets keys, lose one answer | Tap Pay. The badge shows the error. Tap Pay again. | Red line "Problem: you were charged more than once". Payments tab: nothing saved on the phone, 2 charges at the bank marked "Charged twice". |
| 2 | 14, 15 | Safe retry | Careful, bank remembers keys, lose one answer | Tap Pay once. | Badge goes "Saved on this phone", "Asking the bank…", "No answer yet. Trying again", "Paid" (state names in small print). What happened: "Already saw payment ... Sending back the saved answer. No new charge". Payments tab: 1 charge. |
| 3 | 9, 10, 12 | No internet | Careful, no internet | Tap Pay. Open What happened to watch the 1s/2s/4s/8s/16s waits. Go back to Pay and tap **Go online**. | The payment settles on the switch, 1 charge. (Tap Go online earlier and one of the tries succeeds instead.) |
| 4 | 17, 18 | App crash | Careful, slow internet | Tap Pay, tap **Crash the app** while the status still says "Asking the bank…" (about 4 seconds), open the app again. | The log keeps the lines from before the crash, then "———— The app crashed here ————", then "App restarted. Checking 1 unfinished payment(s)", and a note says what the app found. The app asks the bank, sends again with the same key, ends in Paid with 1 charge. |
| 5 | 18 | Forgotten payment | Careful, no internet | Tap Pay, wait at least 2 minutes (the demo TTL), tap **Crash the app**, open the app again. Tap **Send it**, then **Go online**. | Dialog "Forgotten payment found" as soon as the app opens. Nothing is charged until you tap **Send it**, and it asks only once. 1 charge. |
| 6 | 11 | Not enough money | Careful, internet working, amount 60000 | Tap Pay. | Badge: "Not paid: not enough money", FAILED in small print. No tries in the log, 0 charges. |
| 7 | 15 | Sneaky amount change | Careful, bank remembers keys | Tap Pay, wait for Paid, then tap **Reuse key** (it sends the same key with a bigger amount). | What happened: "The bank refused it". Still 1 charge. |
| 8 | 9, 10 | Many payments | Careful, no internet | Tap Pay, change the amount, tap Pay again, a few times. Open **Payments**. Then tap **Go online** on the Pay tab. | Payments tab shows each one "Saved on phone", the tab badge counts them, and after Go online each is "Paid" with exactly one charge. |

The state machine check (slide 7, Success -> Failed is rejected) is a unit test: run `./gradlew test`
(`PaymentStateTransitionTest`).

**Menu** (top right) has Crash the app, Clear everything and Settings. Settings holds the raw toggles (Careless/Careful,
bank idempotency, the five network modes, lose only one answer) for going off script. Changing one by hand
clears the demo hint.

Two extra variations worth showing if there is time, both covered by tests:

- Naive client with idempotency **ON** still double charges, because it sends a fresh key on every tap.
- Safe client with idempotency **OFF** still double charges, because the server has no memory of the key.

The patterns only work together.

## Tests

| File | Where it runs | Covers |
|---|---|---|
| `PaymentStateTransitionTest` | JVM | every allowed and disallowed transition |
| `MoneyTypesTest` | JVM | scans main sources for money-named `Double`/`Float` fields, amount parsing and formatting, duplicate detection |
| `FakePaymentServerTest` | device | claim-first insert, concurrent duplicates, 409, 422, idempotency OFF, declines, status lookup |
| `SafePaymentClientTest` | device | two payments at once are both saved and each charged once, a double tap saves one payment, key once per intent, persisted before network, same key on retry, no retry on decline, backoff schedule in virtual time, `needs_reconcile` after max retries |
| `ReconciliationTest` | device | every server status branch, TTL path, user confirm and cancel |
| `BackgroundRetryWorkerTest` | device | `TestListenableWorkerBuilder`: simulated offline, success, still processing |
| `DemoScenariosTest` | device | scenarios 1 to 7 end to end through Retrofit, the interceptor and the simulator |

## Choices made where the spec was silent

- **Room-backed tests are instrumented.** The spec asks for Robolectric-free tests, and Room needs a real
  Android runtime, so those run with `connectedDebugAndroidTest` on in-memory databases. `./gradlew test`
  covers the pure logic.
- **The Payments tab observes the server's Room Flow directly.** That list is the audience looking inside
  the server. The payment clients (`SafePaymentClient`, `NaivePaymentClient`) only reach the server through
  Retrofit. `GET /ledger` exists and the tests use it.
- **Three tabs instead of the build spec's one screen** (Pay, Payments, What happened), with demo chips instead of the
  control list. The spec's toggles are all still there under Menu > Settings.
- **All on-screen text and log lines are in plain English** ("No internet", "Asking the bank", "PHONE" /
  "INTERNET" / "BANK" / "BACKGROUND" in the log) so even a teenager can follow. The server is called "the bank"
  and the Naive/Safe clients "Careless app"/"Careful app" on screen only; the code keeps the slide names. State
  names (PENDING, CONFIRMING, ...) are kept in small print under the plain words because they are on slide 7.
  HTTP codes are not shown. Each demo has a "Why it matters" line, and the Pay tab says what a key is.
- **Pay is never disabled in the Safe client.** Every tap is saved and payments are sent one at a time, in
  order, so several can stack up. The double-tap protection is the key reuse for the same amount and recipient.
- **Some classes are named differently from the build spec**, to be easier to read: `SafePaymentClient`
  (spec: `PaymentRepository`), `AppDependencies` (`ServiceLocator`), `FakeNetwork` and `DemoSettings`
  (`NetworkSimulator`), `FakeServerInterceptor` (`FakeBackendInterceptor`), `DemoScreen` and `DemoViewModel`,
  `PendingPayment` (`PendingPaymentEntity`, now the same name as on slides 10, 11 and 14), `PendingPaymentStatus`
  (`PaymentStatus`), `RememberedPayment`
  (`ProcessedPaymentEntity`), `Charge` (`LedgerEntryEntity`), `BackgroundRetryWorker` (`RetryPaymentWorker`).
  Table names (`pending_payments`, `processed_payments`, `ledger`) and `PaymentState` are unchanged.
- **A snackbar says where each payment is going** when Pay is tapped ("Saved on this phone. Sending it to the
  bank now.") and how it ended ("Paid: ...", "Not paid: ...", "Still no answer ...").
- **Changing the network mode triggers reconciliation.** This stands in for a connectivity callback and keeps
  scenario 3 fast. Without it, the WorkManager backoff (10s minimum, doubling) decides when the payment settles.
- **Retries are one initial attempt plus five retries**, with the loop kept in the slide's shape (try, catch,
  delay, double). The first retry is immediate, which is why scenario 2 finishes quickly.
- **"Kill app" also kills the in-process server.** If it dies between claiming a key and charging, the
  `processing` claim is dropped on the next start so the same-key resend goes through. The charge and the
  stored result commit in one transaction, so a `processing` row always means "not charged".
- **Client mode (Naive/Safe) is persisted with the simulator settings**, so it survives "Kill app".
- **The state badge follows the payment being sent right now.** While one is unsettled it stays on RETRYING
  until the worker, a network change, or the old-payment dialog settles it.
- **Log source tags use lighter tints** of the deck's amber, teal and blue. The deck accents are too dark to
  read on the `#0F172A` log background.
- **Duplicate detection is by amount and recipient within 60 seconds**, as specified, so two intentional
  identical payments within a minute are also flagged as charged twice.
- Versions: Gradle 8.13, AGP 8.13.2, Kotlin 2.2.10, KSP 2.2.10-2.0.2, Compose BOM 2025.11.01, Room 2.8.4,
  WorkManager 2.10.5, Retrofit 3.0.0, OkHttp 4.12.0. compileSdk and targetSdk 36, minSdk 26.

## What production would add

- **Real webhooks.** The provider tells the server the final outcome; the client polling a status endpoint is
  the fallback, not the source of truth.
- **Server-side TTL on idempotency keys**, and a lease on `processing` claims so a crashed worker's key is
  released automatically.
- **Key scoping per user**, so one user's key can never collide with or read another's result.
- **Observability dashboards**: duplicate-charge rate, retry counts, time spent in `needs_reconcile`, 409 and
  422 rates, payments waiting on user confirmation.
- A 24 hour client TTL instead of the 2 minute demo value, and encrypted local storage for the queue.
