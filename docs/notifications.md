# Notifications (LISTEN / NOTIFY)

*The Roman cursus publicus — the imperial postal relay — could carry a message from Rome to the furthest province in days, not months. PostgreSQL's LISTEN/NOTIFY is the cursus publicus of your database: asynchronous, lightweight, and remarkably fast. Octavius gives you a Flow-based interface to receive these dispatches as they arrive.*

Octavius Database provides first-class support for PostgreSQL's asynchronous notification mechanism via `LISTEN` and `NOTIFY`.

## Table of Contents

- [Overview](#overview)
- [Sending Notifications — notify()](#sending-notifications--notify)
- [Receiving Notifications — createChannelListener()](#receiving-notifications--createchannellistener)
- [Multiple Channels](#multiple-channels)
- [Unsubscribing](#unsubscribing)
- [Transactions and NOTIFY](#transactions-and-notify)
- [Connection Management](#connection-management)

---

## Overview

PostgreSQL LISTEN/NOTIFY is a lightweight pub/sub mechanism built into the database:

- **NOTIFY** sends an event (with optional string payload) to a named channel
- **LISTEN** subscribes a connection to a channel and receives all subsequent notifications
- Notifications are delivered asynchronously and do not require polling a table

Common use cases: cache invalidation, real-time UI updates, cross-service events, triggering background jobs.

---

## Sending Notifications — notify()

`DataAccess.notify()` sends a notification to a PostgreSQL channel via `pg_notify`.

```kotlin
// With payload
dataAccess.notify("legion_dispatch", "legion_id:VII")

// Without payload
dataAccess.notify("senate_bell")

// Returns DataResult — always handle errors
dataAccess.notify("senate_decrees", payload)
    .onFailure { error -> logger.error { "Dispatch failed: $error" } }
```

**Signature:**
```kotlin
fun notify(channel: String, payload: String? = null): DataResult<Unit>
```

The payload is limited to **8000 bytes** (PostgreSQL constraint). For larger data, send an identifier and fetch the full record separately.

---

## Receiving Notifications — createChannelListener()

`DataAccess.createChannelListener()` returns a `PgChannelListener` — a handle to a dedicated database connection used exclusively for listening.

```kotlin
dataAccess.createChannelListener().use { listener ->
    listener.listen("legion_dispatch")

    listener.notifications()
        .collect { notification ->
            println("Channel: ${notification.channel}")
            println("Payload: ${notification.payload}")
            println("Sender PID: ${notification.pid}")
        }
}
```

`notifications()` returns a cold `Flow<PgNotification>`. It polls the underlying connection every **500 ms**. Cancel the collecting coroutine or call `close()` to stop.

**`PgNotification` fields:**

| Field     | Type      | Description                                                     |
|-----------|-----------|-----------------------------------------------------------------|
| `channel` | `String`  | Name of the channel the notification was sent to                |
| `payload` | `String?` | Payload string, or `null` / empty string if none was sent       |
| `pid`     | `Int`     | Process ID of the PostgreSQL backend that sent the notification |

---

## Multiple Channels

A single listener can subscribe to any number of channels:

```kotlin
dataAccess.createChannelListener().use { listener ->
    listener.listen("legion_dispatch", "senate_decrees", "aerarium_transfers")

    listener.notifications()
        .collect { notification ->
            when (notification.channel) {
                "legion_dispatch"      -> handleDispatch(notification.payload)
                "senate_decrees"       -> handleDecree(notification.payload)
                "aerarium_transfers"   -> handleTransfer(notification.payload)
            }
        }
}
```

---

## Unsubscribing

Unsubscribe from specific channels without closing the listener. All methods return `DataResult<Unit>`:

```kotlin
// Unsubscribe from one channel
listener.unlisten("legion_dispatch")

// Unsubscribe from all channels at once
listener.unlistenAll()
```

The listener can be reused — call `listen()` again to subscribe to new channels after unlistening.

---

## Transactions and NOTIFY

`notify()` participates in transactions: if a transaction is rolled back, any notifications sent within it are **not delivered**.

```kotlin
// Notification is delivered only if the transaction commits
dataAccess.transaction { tx ->
    tx.insertInto("edicts").values(listOf("issuer_id", "text"))
        .execute("issuer_id" to 1, "text" to "Let it be known throughout the provinces...")
        .getOrElse { return@transaction DataResult.Failure(it) }

    dataAccess.notify("senate_decrees", "new_edict")  // delivered on commit
    DataResult.Success(Unit)
}
```

This is a useful guarantee: listeners only see events for changes that were actually committed.

---

## Connection Management

Each `PgChannelListener` holds a **dedicated JDBC connection** that is separate from the main HikariCP pool. This ensures that listeners never compete with regular queries for pool slots.

Always release the connection when done:

```kotlin
// Option 1: use {} block (recommended)
dataAccess.createChannelListener().use { listener ->
    // ...
}  // connection released here

// Option 2: explicit close
val listener = dataAccess.createChannelListener()
try {
    // ...
} finally {
    listener.close()
}
```

`close()` executes `UNLISTEN *` and closes the underlying connection.

### Typical Pattern in a Service

```kotlin
class SenateEventService(private val db: DataAccess) {

    fun startListening(scope: CoroutineScope): Job = scope.launch {
        db.createChannelListener().use { listener ->
            listener.listen("senate_decrees")
            listener.notifications()
                .catch { e -> logger.error(e) { "Dispatch listener error" } }
                .collect { notification ->
                    processDecree(notification.payload)
                }
        }
    }
}
```

The `Job` can be cancelled to shut down the listener cleanly — cancellation propagates to the `collect`, which causes `use { }` to close the connection.
