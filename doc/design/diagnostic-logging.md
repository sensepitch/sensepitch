# Diagnostic logging

Covers the levels of request level events and the end of diagnostic logging when the process
stops. The levels themselves are described in
[`ProxyLogger`](../../src/main/java/org/sensepitch/edge/ProxyLogger.java), the metrics in
[`ProxyMetrics`](../../src/main/java/org/sensepitch/edge/ProxyMetrics.java). The shutdown part
covers the order in [`Main`](../../src/main/java/org/sensepitch/edge/Main.java) and the bundled
Log4j2 configurations [`log4j2.xml`](../../src/main/resources/log4j2.xml) and
[`log4j2-trace.xml`](../../src/main/resources/log4j2-trace.xml). The shutdown itself is described
in [`Proxy#shutdown()`](../../src/main/java/org/sensepitch/edge/Proxy.java). This document records
why logging is stopped the way it is.

## Purpose and scope

Clients and upstreams disconnect, send invalid input or stop responding, and a bug of ours can
hit every request. Such an event can repeat thousands of times per second. It must stay
observable without flooding the log.

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

### Request level events are DEBUG and counted

An event that can happen per request or connection is logged at `DEBUG` only, whatever its
cause, and counted in a metric (or recorded in the request log). At `ERROR` an upstream outage, an
attack or a bug that hits every request floods the log, buries the lifecycle messages and costs
I/O on the event loops. The metric keeps the event visible and is what an operator alerts on;
`DEBUG` is switched on to find the root cause and then carries the channel, the phase and the
stack trace. `ERROR` stays for faults that do not repeat per request, e.g. a configuration that
cannot be applied; one that may repeat, e.g. a failing request log export, logs once and counts
the rest. The rule is in the `ProxyLogger` doc and in `AGENTS.md`.

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

### Keep `ERROR` for unknown faults on the request path

Tried 2026-11-17: failures were classified per call site as external (I/O, malformed input,
timeouts: `DEBUG`) or unknown (`ERROR`, a possible bug of ours). Dropped: an unknown fault that
repeats per request floods the log just the same, and the type of an exception is no proof of its
origin. The counter of type `other` shows such faults without the flood.

### Stop logging in `Proxy` or through a system property

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
- A premature upstream close is counted in `sensepitch_upstream_errors{type="closed_in_flight"}`;
  the request itself ends later by its timeout or abort outcome.

[log4j]: https://logging.apache.org/log4j/2.x/manual/systemproperties.html#log4j2.shutdownHookEnabled
[jvm-shutdown]: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Runtime.html#addShutdownHook(java.lang.Thread)
