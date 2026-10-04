# Slide map

Slide numbers are the ones the build spec references. All paths are under
`app/src/main/java/com/nerojust/paymentsim/`.

| Slide | Topic | File | Function / type |
|---|---|---|---|
| 2 | The bug: a duplicate charge | `client/NaivePaymentClient.kt` | `pay()` (boolean flag, fresh UUID per tap, broad catch) |
| 6 | Payment state machine | `model/PaymentState.kt` | `PaymentState`, `isAllowed()`, `transition()` |
| 8 | Offline-first: queue before network | `client/PaymentRepository.kt` | `initiatePayment()` (the `dao.insert` before `send`) |
| 9 | The queued payment entity | `client/db/PendingPaymentEntity.kt` | `PendingPaymentEntity`; statuses in `model/PaymentStatus.kt` |
| 10 | Retry with exponential backoff | `client/PaymentRepository.kt` | `retryWithBackoff()` |
| 11 | Background retry with WorkManager | `client/work/RetryPaymentWorker.kt` | `doWork()`, `enqueue()` |
| 12 | Why the retry double charges | `server/FakePaymentServer.kt` | `createPayment()`, the idempotency OFF branch; banner logic in `ui/SimulatorViewModel.kt` `duplicateChargeIds()` |
| 13 | Idempotency key, client side | `client/PaymentRepository.kt` | `initiatePayment()` (key generated once, reused for the same intent), `send()` |
| 14 | Idempotency, server side | `server/FakePaymentServer.kt` | `createPayment()` (claim first, 409, 422, stored result); tables in `server/db/ProcessedPaymentEntity.kt`, `server/db/LedgerEntryEntity.kt`; claim query `server/db/ServerDao.kt` `claim()` |
| 16 | The crash mid-payment | `ui/SimulatorViewModel.kt` | `killApp()`; restart hook in `App.kt` `onCreate()` |
| 17 | Reconciliation | `client/PaymentRepository.kt` | `reconcilePendingPayments()`, `reconcile()`, `reconcilePayment()`, `cancelPayment()` |

## Supporting pieces

| What | File | Function / type |
|---|---|---|
| Failure injection (offline, drop after processing, slow, 500) | `network/NetworkSimulator.kt` | `beforeServer()`, `afterServer()` |
| Routing requests to the fake server | `server/FakeBackendInterceptor.kt` | `intercept()` |
| Object graph | `di/ServiceLocator.kt` | `ServiceLocator` |
| Event log | `log/EventLog.kt` | `EventLog.log()` |
| Money parsing and formatting | `model/Money.kt` | `parseAmountMinor()`, `formatMinor()` |
| The screen | `ui/SimulatorScreen.kt` | `SimulatorScreen()` |
