# Diagnostic logging

Covers the end of diagnostic logging when the process stops: the shutdown order in
[`Main`](../../src/main/java/org/sensepitch/edge/Main.java) and the bundled Log4j2 configurations
[`log4j2.xml`](../../src/main/resources/log4j2.xml) and
[`log4j2-trace.xml`](../../src/main/resources/log4j2-trace.xml). The shutdown itself is described
in [`Proxy#shutdown()`](../../src/main/java/org/sensepitch/edge/Proxy.java). This document records
why logging is stopped the way it is.

## Purpose and scope

`SIGTERM` is the normal end of the proxy on Kubernetes. The graceful shutdown then takes up to
`shutdownTimeoutMillis` and reports what it could not finish (requests still in flight, request
log records not sent). These reports must reach standard error.

The JVM starts all shutdown hooks at once and runs them concurrently ([`Runtime#addShutdownHook`
in JDK 25][jvm-shutdown]). Log4j2 registers its own hook by default ([`shutdownHook` attribute and
`log4j2.shutdownHookEnabled`][log4j], Log4j 2.26.1), which stops the logger context while the
proxy still drains. A stopped context drops further messages.

Verified 2026-11-17 on the development machine (Linux, JDK 25) with
`ShadedJarIT.sigtermKeepsFinalShutdownDiagnostic`: with Log4j2's hook enabled the report "request
log not drained" sent one second after `SIGTERM` is missing on standard error, with the hook
disabled it is there.

## Design decisions

### The bundled configurations disable the Log4j2 hook

`shutdownHook="disable"` on the `<Configuration>` element of both bundled files. The setting sits
where an operator writing an own configuration starts from, and the README asks for it.
`MainShutdownTest` checks both files.

### `Main` stops Log4j2, not `Proxy`

`Main` runs one shutdown action from its own JVM hook and at the normal end of `main`: proxy
shutdown, then the admin server, then `LogManager.shutdown()`. `Proxy#shutdown()` does not touch
Log4j2: logging is process wide, and an embedding application or a test may run several proxies
or log after the proxy ended. The process owner, here `Main`, decides when logging ends.

The proxy keeps its own hook, so an embedded proxy still shuts down on `SIGTERM`. Both hooks call
`Proxy#shutdown()`, the second call waits for the first.

### One action, held under the lock until logging is stopped

The action runs its steps while holding its lock, so a second caller (the main thread when
`proxy.start()` returns, or the hook) waits until logging is stopped instead of returning early.
The JVM exits when all hooks returned, so an early return could cut the first caller off. Logging
is stopped also when the service cleanup throws.

## Alternatives considered

- `LogManager.shutdown()` as the last step of `Proxy#shutdown()`, as first noted in `TODO.md`:
  rejected, it stops logging for everything else in the JVM, see above.
- Setting the system property `log4j2.shutdownHookEnabled=false` in `Main#main`: would also cover
  own configuration files, but only works if nothing initialized Log4j2 before that line, which
  any static logger in a class loaded earlier breaks silently. Not chosen; the attribute in the
  configuration is visible and tested.

## Limitations

- An own Log4j2 configuration without `shutdownHook="disable"` loses the shutdown reports again.
- Messages logged after `LogManager.shutdown()`, e.g. by a thread that outlives the shutdown, are
  dropped.

[log4j]: https://logging.apache.org/log4j/2.x/manual/systemproperties.html#log4j2.shutdownHookEnabled
[jvm-shutdown]: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Runtime.html#addShutdownHook(java.lang.Thread)
