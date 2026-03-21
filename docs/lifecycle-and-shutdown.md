# Lifecycle & Shutdown

*Every campaign must end. When Caesar returned from Gaul, he did not leave his legions camped on the banks of the Rubicon indefinitely — he issued the order to disband, the soldiers were paid and released, and the land stopped feeding idle men. A `DataAccess` instance is no different: when your application's work is done, the connection pool should be formally dismissed.*

The `DataAccess` interface implements `AutoCloseable`. While modern Operating Systems and PostgreSQL are excellent at reclaiming resources from terminated processes, closing the instance explicitly is considered **best practice for resource hygiene**.

### Why bother?
If you are running a simple CLI tool or a local dev environment, the OS will clean up after you. However, in production environments, calling `.close()` ensures:
*   **Clean Logs:** You avoid `unexpected EOF` noise in your PostgreSQL logs.
*   **Prompt Shutdown:** Background threads (like Hikari’s housekeeper) are terminated immediately, allowing the JVM to exit without a 5-second "hang."
*   **Test Stability:** In large integration test suites, it prevents you from hitting connection limits by leaking pools between test cases.

### Standard Usage

For short-lived scripts or jobs, use Kotlin's `.use {}` block to handle the cleanup automatically:

```kotlin
OctaviusDatabase.fromConfig(config).use { dataAccess ->
    // Issue your commands...
    val cohorts = dataAccess.select("*").from("legions").toListOf<Legion>()
} // The internal HikariCP pool is automatically closed here
```

### Integration Patterns

In long-running services, tie the lifecycle to your framework of choice.

**Ktor (Server):**
If you are using `ktor-server`, tie the closure to the application's stop event via the environment monitor:

```kotlin
val dataAccess = OctaviusDatabase.fromConfig(config)

environment.monitor.subscribe(ApplicationStopped) {
    dataAccess.close()
}
```

**Spring Boot:**
Spring is "AutoCloseable-aware." If you register `DataAccess` as a `@Bean`, Spring will automatically call `close()` during the application context shutdown. No extra code needed.

**Koin:**
If you manage your lifecycle manually via Koin, use the `onClose` hook:
```kotlin
val appModule = module {
    single<DataAccess> { 
        OctaviusDatabase.fromConfig(get()) 
    } onClose { it?.close() }
}
```

### Behavior with Existing DataSource

When initializing via `fromDataSource()`, calling `dataAccess.close()` will invoke the optional `onClose` lambda if you provided one. If no lambda was provided, Octavius assumes the lifecycle of the `DataSource` is managed externally by your framework (e.g., Spring Boot managing its own Hikari pool), and `close()` behaves as a no-op for the connection pool.
