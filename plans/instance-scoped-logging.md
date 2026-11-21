# Plan: instance-scoped diagnostic logging, tests capture and wait by scope

## Incompatible setup changes
None. The diagnostic log output (Log4j2 to stderr) is unchanged: the new scope is not rendered,
logger names stay the class names, so level configuration per package keeps working. No config
keys, no defaults change. `Proxy` constructors stay source compatible.

## Is the refactoring an improvement?
- Better: a test captures exactly the records of the proxy or component it built, whatever thread
  logged them. `LogCapture.ofAllThreads()` and the `@Isolated` it forces go away for logging, so
  more classes run concurrently (test speed). Waiting for a log event becomes a plain wait on one
  buffer, which makes the AGENTS rule "react on the event" practical. Trace and non-trace
  logging get separate types, so the static trace fast path cannot be misused for errors.
- Worse: about 20 classes get a logger passed in instead of a static field; several constructors
  gain a parameter (largest test fallout `OtlpLogDestination`, 31 sites, mostly through helpers).
- Neutral: production behaviour and output; trace performance (stays `static final`).
- Verdict: an improvement, the test side gets simpler and faster for a one-time plumbing cost.

## Context
Tests that capture logs of event loops or sender threads need `LogCapture.ofAllThreads()`,
which sees every test's records, so their classes must be `@Isolated`; waiting for a record
across threads would need global sequence numbers and filtering by unique ports. Owning the
records by instance instead of by thread removes both problems at the source.

## Design

### `LogScope` (new, main)
`record LogScope(String instance, String component)`: who owns a record. `LogScope.next()` takes
the next value of a JVM wide counter (`"p1"`, `"p2"`, ...), `scope.component("otlp:vl")` derives
a component scope of the same instance. Not rendered in production; carried to the
`LogTarget` factory only. Doc: the instance is a proxy (or whatever the caller groups, e.g. Main),
the component names the part (`listener`, `admin`, `metrics`, `config-watcher`, `otlp:<name>`,
`stdout:<name>`, `upstream:<name>`).

### Split `ProxyLogger`
- `TraceLogger` (new interface, from the trace part of `ProxyLogger`): `isTraceEnabled`, `trace*`,
  `traceChannelRead`; `static TraceLogger get(Class<?>)`; keeps the `WithTracing`/`ErrorOnly`
  (rename: `TraceDisabled`) pair and the `SENSEPITCH_EDGE_DISABLE_TRACE` kill switch and its JIT
  argument. Only in `static final` fields. Trace is dropped by the test sink anyway.
- `ProxyLogger` keeps debug/info/warn/error/`downstreamError`/`upstreamError`/`log`/`channelId`;
  created with `ProxyLogger.get(Class<?> source, LogScope scope)`, held in instance fields.
  `ProxyLogger.get(Class)` without scope is removed, so a static non-trace logger no longer
  compiles.
- `LogTarget.of(String loggerName, LogScope scope)`; the `Factory` function takes both. The Log4j
  default ignores the scope (output unchanged). Trace loggers get `LogScope.NONE`.

### Plumbing (from the usage map)
- `Proxy`: field `LogScope logScope`, from a new constructor parameter in an overload or
  `LogScope.next()`; getter `logScope()`. `ProxyContext` gains `LogScope logScope()`, which
  already reaches `EdgeState.build`, `UpstreamRegistry`, `SiteSelector`, `DefaultUpstream`, so
  `DownstreamHandler` and `ForwardHandler` get their logger from there.
- Per-channel handlers (`RequestLoggingHandler`, `ExceptionHandler`, `Ja4Handler`): a
  `ProxyLogger` created once in `Proxy` and passed into the constructor, not one per channel.
- Gaps that get a `LogScope` parameter: `Protection.handlerSupplier` (→ `Deflector`,
  `ProtectionBypass`, `DefaultBypassCheck`), `EdgeState.buildIpTraitsLookup` (→
  `CombinedIpTraitsLookup`, `IpInfoCountryAndAsnLookup`), `RequestLoggerFactory.create` /
  `createDestination` and `ReloadableRequestLogger` (→ `JsonRequestLogger`, `OtlpLogDestination`
  with component `otlp:<name>`, `HttpBatchSender`), `AsyncRequestLogWriter`.
- Static contexts: `Proxy.dumpConfig` becomes an instance method or takes the logger;
  `MetricsServer.start`, `AdminServer.start` take a `LogScope`, `AdminServer.handle` gets the
  logger passed; `OtlpLogDestination.settings` (runs before `super`) takes the logger;
  `EdgeState`'s ad hoc logger uses `ctx.logScope()`.
- `Main`: one `LogScope.next()` for the process, passed to `Proxy`, `ConfigFileWatcher`,
  `AdminServer`.
- Remove the unused `LOG` fields of `DeflectorHandler` and `IngressTimeoutHandler`.

### Test side
- `LogSink`: buffers per `LogScope.instance()` instead of per thread (map of synchronized
  buffers); each `Entry` keeps the component. `add` does `notifyAll` on its buffer (lock is held
  anyway), so a waiter wakes at once.
- `LogCapture` (JUnit extension, field as today): owns a fresh `LogScope.next()` per test,
  `logs.scope()` for components a test builds itself, `logs.capture(proxy.logScope())` for a
  proxy that generated its own. `entries()`, `messages(...)`, `errors()` read only the captured
  instances. New: `Entry await(String description, Predicate<Entry> condition)` and
  `String awaitMessage(Class<?> source, String messagePart)`, waiting on the buffer up to
  `TestingParameters.TIMEOUT`, failing with the description like `TestingParameters.await`.
- Remove `LogCapture.ofAllThreads()` and its `requireIsolated` check; per-thread capture goes
  too (every non-trace record has a scope). Drop `@Isolated` from classes whose only reason was
  the log capture (check each `// alone:` reason, e.g. `OtlpLogDestinationTest`, `ExternalTcpIT`,
  `ProxyShutdownIT`, `ConfigFileWatcherTest`, `AsyncRequestLogWriterTest`).
- Tests building components pass `logs.scope()`; tests building a `Proxy` capture its scope.
  `RequestLogCapture.awaitLine(description, predicate)` (notify inside its existing
  `synchronized (lines)`) for the request log, same failure style.
- Replace the polling users: `ExternalTcpIT.awaitLogged`/`awaitRequestLog`; metric-then-log waits
  in `OtlpLogDestinationTest` (`givingUpIsLoggedOnce`, `agedBatchIsDiscarded`).
- `Log4jCapture` unchanged (Netty, JUL, the Log4j2 path itself).
- `LogSinkTest`/`LogCaptureTest`: records of two scopes are separated; a record logged by another
  thread after `await` started wakes it (thread released by a latch, no sleep); a record from
  before is found at once; a missing one fails with the description (test the deadline through a
  package-private overload with a passed deadline, so the case does not wait 10 s).

### Docs
- `doc/design/diagnostic-logging.md`: decision "Records are owned by an instance scope, not by a
  thread" (why: test isolation and waiting; the scope is not rendered), "Trace stays static"
  (JIT argument moved from the `ProxyLogger` doc), alternative considered: global sequence numbers
  with per-thread buffers (complicated, still needs unique predicates).
- `AGENTS.md`: Security & Configuration Tips paragraph on `LogSink`/`LogCapture` rewritten
  (capture by scope, no `@Isolated` for logs, `await`); the "React on the event" rule names
  `LogCapture.await` and `RequestLogCapture.awaitLine`; coding style: non-trace loggers are
  instance fields from a `LogScope`, only `TraceLogger` is `static final`.
- `doc/design/test-speed.md`: the `@Isolated` classes that become concurrent, with before/after
  `./mvnw test` time.
- `TODO.md`: move the log part of "Event driven waits instead of polling" to Done.

## Order of work
1. `LogScope`, `TraceLogger` split, `LogTarget` factory with scope, `LogSink`/`LogCapture` by
   scope with `await` (+ their tests).
2. Migrate main classes per the plumbing list; compile fixes the test sites.
3. Migrate tests, drop `ofAllThreads`/`@Isolated`, replace polling with `await`.
4. Docs.

## Verification
- `./mvnw spotless:check` and `./mvnw clean test jacoco:report` before (baseline time, coverage,
  `target/site/jacoco/jacoco.csv`).
- After each step `./mvnw verify -Dfast`; at the end full `./mvnw verify` (coverage floor, 100 %
  rule of `HttpBatchSender` and friends, Javadoc).
- Compare `./mvnw test` time and coverage with the baseline; `grep -rn "ProxyLogger.get(.*class)"`
  without scope and `ofAllThreads` in `src/` are empty.
- Run the proxy jar once (`java -jar target/...with-dependencies.jar` with a test config) and
  check the stderr lines look as before.
- No commit; print the suggested commit command.
