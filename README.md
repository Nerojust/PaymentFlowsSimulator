# Payment Flows Simulator

Live demo companion for the talk **"Building Payment Flows That Don't Break"**.

One screen, one device, no backend. The app runs a payment client and a payment server in the same
process, with a network simulator between them that can fail at the exact moments the talk describes.
You reproduce a duplicate charge with the Naive client, then show each pattern fixing it with the Safe client.

No request leaves the device: the fake server answers from an OkHttp interceptor, and the app does not
even declare the `INTERNET` permission.

## The four patterns

| Pattern | What it does | Where |
|---|---|---|
| State machine | `PaymentState` replaces the `isLoading` boolean. Illegal moves (for example Success -> Failed) throw in debug builds. The Pay button is driven by the state, so a second tap cannot start a second payment. | `model/PaymentState.kt` |
| Offline-first queue | The payment is written to Room (`pending_payments`) before any network call, so it survives a crash. Network errors are retried with exponential backoff (1s, 2s, 4s, 8s, 16s + jitter), then handed to WorkManager. | `client/PaymentRepository.kt`, `client/work/RetryPaymentWorker.kt` |
| Idempotency keys | The client generates one key per user intent and sends the same key on every retry. The server claims the key first (`INSERT ... ON CONFLICT DO NOTHING`), and a replay gets the stored result instead of a second charge. | `client/PaymentRepository.kt`, `server/FakePaymentServer.kt` |
| Reconciliation | On every app start, and from the worker, each unsettled payment asks the server what happened (`GET /payments/{key}`) before doing anything. Payments older than the TTL are never sent silently: the user is asked. | `PaymentRepository.reconcilePendingPayments` |

`docs/SLIDE_MAP.md` maps each slide to the file and function that implements it.

## How to run

Open the folder in Android Studio and run the `app` configuration, or from a terminal:

```
./gradlew installDebug                 # install on a running emulator or device (API 26+)
./gradlew test                         # JVM tests: state machine, money types
./gradlew connectedDebugAndroidTest    # instrumented tests: server, repository, reconciliation, worker, scenarios
```

`RetryPaymentWorker` has a real `NetworkType.CONNECTED` constraint, so the emulator needs working
(real) connectivity for the worker to run. The simulated modes are applied on top of that.

## Presenter checklist

Start every scenario with **Reset all data**. That restores the defaults: Safe client, server idempotency ON,
ONLINE, drop once checked. Default amount is ₦1,250.00.

| # | Slide | Toggles | Steps | What the audience sees |
|---|---|---|---|---|
| 1 | 2, 12 | **Naive**, idempotency **OFF**, **DROP_AFTER_PROCESSING**, drop once checked | Tap Pay. Badge shows the error. Tap Pay again. | Ledger shows 2 charges in red and the "Duplicate charge detected" banner. |
| 2 | 13, 14 | **Safe**, idempotency **ON**, **DROP_AFTER_PROCESSING**, drop once checked | Tap Pay once. | Badge goes Pending, Confirming, Retrying, Success. Log shows the same key resent and "returning stored result, no new charge". Ledger: 1 charge. |
| 3 | 8, 9, 11 | **Safe**, **OFFLINE** | Tap Pay. Watch the queue row and the 1s/2s/4s/8s/16s waits in the log. After the last wait the row becomes `needs_reconcile`. Tap **ONLINE**. | The payment settles immediately on the switch, ledger shows 1 charge. (Switch to ONLINE earlier and one of the retries succeeds instead.) |
| 4 | 16, 17 | **Safe**, **SLOW** | Tap Pay, tap **Kill app** within 4 seconds, relaunch from the launcher. | First log line: "App restarted: reconciling 1 unsettled payments". Reconciliation asks the server, resends with the same key, ends in Success with 1 charge. |
| 5 | 17 | **Safe**, **OFFLINE** | Tap Pay, wait at least 2 minutes (the demo TTL), tap **Kill app**, relaunch, tap **ONLINE**. | Dialog: "This payment from N min ago never completed. Send it now or cancel?" Ledger stays empty until you tap Send. |
| 6 | 10 | **Safe**, **ONLINE** | Enter an amount above 50000 (for example 60000) and tap Pay. | Badge: FAILED: insufficient_funds. No retries in the log, 0 charges. |
| 7 | 14 | **Safe**, idempotency **ON**, **ONLINE** | Tap Pay, wait for Success, then tap **Debug: same key, different amount**. | Log shows the server answering 422 and no new charge. |
| 8 | 6 | none | Run `./gradlew test` (`PaymentStateTransitionTest`). | Success -> Failed is rejected. |

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
| `PaymentRepositoryTest` | device | key once per intent, persisted before network, same key on retry, no retry on decline, backoff schedule in virtual time, `needs_reconcile` after max retries |
| `ReconciliationTest` | device | every server status branch, TTL path, user confirm and cancel |
| `RetryPaymentWorkerTest` | device | `TestListenableWorkerBuilder`: simulated offline, success, still processing |
| `DemoScenariosTest` | device | scenarios 1 to 7 end to end through Retrofit, the interceptor and the simulator |

## Choices made where the spec was silent

- **Room-backed tests are instrumented.** The spec asks for Robolectric-free tests, and Room needs a real
  Android runtime, so those run with `connectedDebugAndroidTest` on in-memory databases. `./gradlew test`
  covers the pure logic.
- **The server panel observes the server's Room Flow directly.** That panel is the audience looking inside
  the server. The payment clients (`PaymentRepository`, `NaivePaymentClient`) only reach the server through
  Retrofit. `GET /ledger` exists and the tests use it.
- **Changing the network mode triggers reconciliation.** This stands in for a connectivity callback and keeps
  scenario 3 fast. Without it, the WorkManager backoff (10s minimum, doubling) decides when the payment settles.
- **Retries are one initial attempt plus five retries**, with the loop kept in the slide's shape (try, catch,
  delay, double). The first retry is immediate, which is why scenario 2 finishes quickly.
- **"Kill app" also kills the in-process server.** If it dies between claiming a key and charging, the
  `processing` claim is dropped on the next start so the same-key resend goes through. The charge and the
  stored result commit in one transaction, so a `processing` row always means "not charged".
- **Client mode (Naive/Safe) is persisted with the simulator settings**, so it survives "Kill app".
- **While a payment is unsettled the badge stays on RETRYING and Pay stays disabled**, until the worker,
  a network change, or the stale-payment dialog settles it.
- **Log source tags use lighter tints** of the deck's amber, teal and blue. The deck accents are too dark to
  read on the `#0F172A` log background.
- **Duplicate detection is by amount and recipient within 60 seconds**, as specified, so two intentional
  identical payments within a minute are also flagged.
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
