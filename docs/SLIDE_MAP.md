# Slide map

Slide numbers are the ones the build spec references. All paths are under
`app/src/main/java/com/nerojust/paymentsim/`.

| Slide | Topic | File | Function / type |
|---|---|---|---|
| 2 | The bug: a duplicate charge | `client/NaivePaymentClient.kt` | `pay()` (boolean flag, fresh UUID per tap, broad catch) |
| 6 | Payment state machine | `model/PaymentState.kt` | `PaymentState`, `isAllowed()`, `transition()` |
| 8 | Offline-first: queue before network | `client/SafePaymentClient.kt` | `initiatePayment()`, `save()` (the `dao.insert` before `send`) |
| 9 | The queued payment entity | `client/db/PendingPayment.kt` | `PendingPayment`; statuses in `model/PendingPaymentStatus.kt` |
| 10 | Retry with exponential backoff | `client/SafePaymentClient.kt` | `retryWithBackoff()` |
| 11 | Background retry with WorkManager | `client/work/BackgroundRetryWorker.kt` | `doWork()`, `enqueue()` |
| 12 | Why the retry double charges | `server/FakePaymentServer.kt` | `createPayment()`, the idempotency OFF branch; banner logic in `ui/DemoViewModel.kt` `duplicateChargeIds()` |
| 13 | Idempotency key, client side | `client/SafePaymentClient.kt` | `save()` (key generated once, reused for the same intent), `send()` |
| 14 | Idempotency, server side | `server/FakePaymentServer.kt` | `createPayment()` (claim first, 409, 422, stored result); tables in `server/db/RememberedPayment.kt`, `server/db/Charge.kt`; claim query `server/db/ServerDao.kt` `claim()` |
| 16 | The crash mid-payment | `ui/DemoViewModel.kt` | `killApp()`; restart hook in `App.kt` `onCreate()` |
| 17 | Reconciliation | `client/SafePaymentClient.kt` | `reconcilePendingPayments()`, `reconcile()`, `reconcilePayment()`, `cancelPayment()` |

## Supporting pieces

| What | File | Function / type |
|---|---|---|
| Failure injection (offline, drop after processing, slow, 500) | `network/FakeNetwork.kt` | `beforeServer()`, `afterServer()` |
| Routing requests to the fake server | `server/FakeServerInterceptor.kt` | `intercept()` |
| Object graph | `di/AppDependencies.kt` | `AppDependencies` |
| Event log | `log/EventLog.kt` | `EventLog.log()` |
| Money parsing and formatting | `model/Money.kt` | `parseAmountMinor()`, `formatMinor()` |
| The screens | `ui/DemoScreen.kt`, `ui/PayTab.kt`, `ui/PaymentsTab.kt`, `ui/LogTab.kt` | `DemoScreen()` and one function per tab |
| Demo presets | `ui/Scenarios.kt` | `scenarios` |
