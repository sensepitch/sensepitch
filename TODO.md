# TODO

Open work, grouped by area. Moved here from the README roadmap and from the `TODO`/`FIXME`
comments in the code; an entry from the code names the class and method it is about.

Ordered by priority: bugs first, then Kubernetes, then the other areas. Priority suggestions:
P1 = bugs and running the standalone edge on Kubernetes (items 2-4 and the drain delay); P2 = the
M1 path, observability, admission lifetime; P3 = features and measured performance work. Confirm
priorities against current production needs. Notes below record decisions to make before
implementation.

## Scheduled Cleanup and Refactorings

- [ ] Move the real server tests of `OtlpLogDestinationTest` and `ReloadableRequestLoggerTest` to
  `*IT` where the unit tests keep their coverage
  - P2: the full move was undone 2026-11-14 because the unit tests lost coverage (see "Timing
    related tests" under Build, tests). This time move only what the remaining unit tests already
    cover: baseline `./mvnw clean test jacoco:report`, then exclude the slowest and most timing
    dependent tests one at a time (`-Dtest='!OtlpLogDestinationTest#x,...'`) and keep a test
    excluded only if `target/site/jacoco/jacoco.csv` stays identical.
  - The moved tests go to a new `OtlpLogDestinationIT` and the existing `ReloadableRequestLoggerIT`,
    with the unit test as fixture; the class docs name the unit test as the place for new
    scenarios. If almost nothing is movable, record the measurement in
    `doc/design/test-speed.md` instead; the remedy is then the fake clock.

## Bugs

- [ ] Flaky, not investigated: `ProxyShutdownIT`,
  `ShadedJarIT.sigtermSendsTheRequestLogAndExits`
  - P2: also the `SIGTERM` path.
- [ ] `RequestLoggingHandler.write` / `log`: on a write error, switch the response, log a
  different status code, or log content length 0?
  - P2: change status to 499 although if its written already, log bytes delivered, we already
    log original upstream status somewhere, double check. comment that original upstream status
    is logged for fallback or aborted writes
- [ ] unify timeout with keep alive handler: `IngressTimeoutHandler` only works with
  `HttpKeepAliveHandler` next; corner case when the ingress still sends while the upstream is
  responding (could answer with `Connection: close`)
  - P3: QUIC support is more important

## Kubernetes

The plan is in [doc/kubernetes-support/](doc/kubernetes-support/), the gaps in
`3-gap-analysis.md`. Items 1-4 run today's `config.yml` edge on Kubernetes without a controller;
5-6 are also part of M1.

- [ ] 2. OCI image: Dockerfile with a Java 25 runtime, non-root, `java` as PID 1 so `SIGTERM`
  arrives (exec form); CI builds and pushes it
  - P1: decide base image (distroless or Temurin JRE) and registry; keep the shaded jar.
- [ ] 3. manifests or a minimal Helm chart for a standalone edge: Deployment with probes and
  `terminationGracePeriodSeconds` above `shutdownTimeoutMillis`, Service, PodDisruptionBudget,
  config from a ConfigMap, secrets from Secrets, scrape annotations or ServiceMonitor
  - P1: production on Kubernetes before M1; becomes the base of the provisioner (phase 6).
- [ ] 4. secrets out of the config file: `tokenGenerators` are in clear text today; read them,
  the TLS keys and the IPinfo database from mounted files
  - P1: a reload watches only `config.yml`, so a renewed certificate Secret (cert-manager)
    triggers no reload; watch the referenced files too. Add a test for the ConfigMap symlink
    swap, which `ConfigFileWatcher` is designed for but no test covers.
- [ ] 5. plain HTTP listener and several listeners (`ListenConfig` has one HTTPS port), with the
  HTTP to HTTPS forward (there is no plain HTTP listener yet)
  - P2: confirm the redirect status and host/port behavior. Needed for HTTP to HTTPS redirects,
    cert-manager HTTP-01 and Gateway listeners; define the listener model with the Gateway API
    records to avoid doing it twice.
- [ ] 6. PROXY protocol v1/v2 per listener and `X-Forwarded-For`/`-Proto` only from trusted
  proxy CIDRs
  - P2: behind a cloud load balancer the client IP is lost today, and the deflector and IP traits
    depend on it.
- [ ] 7. M1 path, see `6-phases.md` "Development order": Gateway API Java records and snapshot
  schema, then the `config.yml` converter run against the production configurations, then the
  Go controller
  - P2: start once 1-4 are done; the converter shows early whether the model covers production.
    Gateway API hostnames use wildcards, see the `SiteSelector` entry under Ingress.
- [ ] `/readyz` answers `200` only after the upstreams were probed and at least one configured
  upstream is available
  - P3: today it answers `200` from the start until the shutdown begins. Decide what probes an
    upstream (a request, a connect) and whether it reuses the upstream circuit breaker.

## Protection, admission

- [ ] restrict admissions per solved challenge; see
  [PoW admission design](doc/design/pow-admission.md)
  - P2: define the replay threat and permitted reuse, whether enforcement is per node
    or cluster-wide, and the state, lifetime and coordination cost.
- [ ] expiry of granted admissions: the token carries its creation time, but only the cookie
  `Max-Age` (30 days) limits it
  - P2: 5 days default maximum, 5s clock skew allowance for distributed servers, expired cookie
    is set to a another cookie  (with '0' suffix), if previous expired cookie is present reset
    with the same and add a counter, e.g. `admission0: origtoken-n-signature`. so we encode
    always the original token and how many revisits we have seen.
- [ ] bypass config should be in the protection and not in the protection providers => we need a
  proper API for the protection provider
  - P3: define provider-independent bypass semantics and migration of existing configuration.
- [ ] minimise + compress challenge resources
  - P3: measure transfer and CPU costs; decide build-time compression and cache behavior.
- [ ] abnormal admission usage detection
  - P3: Define abnormal behavior, data sources, alerting versus enforcement, and privacy/retention.
    Needs a stream per admission token
- [ ] Admission performance: skip validation for keep-alive connections
  - P3: benchmark first; define behavior when requests on one connection have different cookies
    or a token expires or configuration reloads.
- [ ] Admission performance: deliver the challenge via cookie and serve static HTML
  (`Deflector.outputChallengeHtml` renders the page per request, only the challenge changes)
  - P3: benchmark and define caching, cookie scope and security semantics.
- [ ] improve bypass matching (maybe ASN, geo)
  - Define which traits grant bypass, rule precedence and behavior for unknown or stale data.
- [ ] `TrieIpLabelLookup`: consolidate into `IpTraitsLookup`
  - P3, no operator-visible effect: clarify the desired API and whether collector composition
    already covers the use case.
- [ ] naming: the deflector is one component of the protection system, next to cookie gates and
  the bypass. Decide whether to rename the metric `sensepitch_deflector_requests_total` to
  `sensepitch_protection_requests_total` (with a label for the component, once more components
  report outcomes) and "deflector" to "protection" in general, or keep it as is. A metric rename
  breaks dashboards and alerts.
  - P3: decide ownership and migration/deprecation before renaming.

## Request log

Decisions, alternatives and limitations are in
[doc/design/request-log.md](doc/design/request-log.md); retry backoff is described in
[doc/design/circuit-breaker.md](doc/design/circuit-breaker.md).

- [ ] compression budget: gzip runs on the common pool at a fixed level (`BEST_SPEED`)
  - P3: a small bounded executor and `otlp.compressionLevel`; zstd once the target log servers
    accept it. Measure CPU per MiB first.
- [ ] failover endpoints: several endpoints per OTLP destination, each with its own breaker, see
  the `Endpoint` record in `HttpBatchSender`
  - P3: decide failover order versus round robin; shares the design with "multiple servers per
    upstream".
- [ ] disk spool: waiting batches are lost when the proxy restarts during a log server outage or
  hits the shutdown deadline
  - P3: only if deployments ask for it; needs a volume, a disk budget, fsync policy and replay
    order.

## Upstream

- [ ] upstream connection pool with keep-alive (an earlier version was disabled 2025-09-20 and
  removed, see git history); then expose pool metrics via Prometheus
  - P2: define connection lifetime, reuse, error handling and metrics. Coordinate
    with multiple upstream servers and their health policy.
- [ ] cache upstream responses with cache2k in netty buffers
- [ ] Allow cache to be tiered on disk and Redis
  - P2 relatively important so we don't have restart spikes
- [ ] transparent compression
  - all responses are compressed with low overhead brotli, add benchmarks
- [ ] best compression for cached content
  - first do low overhead brotli and cache it, recompress with more efficient compression with half
    of the available CPUs in niced priority, priotising hot resources
- [ ] multiple servers per upstream
  - P3: decide load balancing, retry safety, failover and configuration semantics.
  - One circuit breaker per server, see the `Endpoint` record in `HttpBatchSender`.
- [ ] upstream health and circuit breaker:
  [`CircuitBreaker`](src/main/java/org/sensepitch/edge/CircuitBreaker.java) exists for the request
  log, it opens on the first failure; for upstreams it needs a failure threshold (consecutive
  failures or a failure rate window, as in Armeria or resilience4j)
  - P3: specify thresholds, recovery probes, per-server versus per-upstream state and retry policy.
  - Keep the backoff, `Retry-After`, single probe and metrics, the request log keeps a threshold
    of one, see [doc/design/circuit-breaker.md](doc/design/circuit-breaker.md).

## Circuit breaker

Limitations and the reasoning are in [doc/design/circuit-breaker.md](doc/design/circuit-breaker.md);
the failure threshold for upstreams is under Upstream.

- [ ] slow calls count as failures, as resilience4j's slow call rate
  - P3: for upstreams, once latency targets are known.
- [ ] manual control: force a breaker open, e.g. for maintenance of a log server or upstream
  - P3: through the admin server.
- [ ] `Retry-After` as an HTTP date, only seconds are supported
  - P3: small; until then a date is ignored and the backoff applies.

## Ingress, HTTP

- [ ] HTTP/2 support, downstream
  - P3: scope protocol negotiation, stream lifecycle, backpressure and compatibility tests.
- [ ] quic support
- [ ] limit body size for incoming requests? There is no limit yet. NGINX has 1M as default,
  `client_max_body_size`, status 413
  - P2: research settings of other tools and present options here first. These settings are always
    caveats. Basic philosophy is to be transparent, define a buffer for performance and then do
    backpressure. So no hard limit
- [ ] Implement server-timing, https://www.w3.org/TR/server-timing/
  - P3: enable/disable forward of upstream Server-Timing info, log all of it, add own timing info,
    e.g. upstream connect latency
- [ ] `SiteSelector`: support wildcard hosts (rejected at config load for now)
  - P3: define precedence, matching boundaries and certificate requirements.

## Metrics, logging

- [ ] `ProxyMetrics`: one `sensepitch_edge_server_live` per ingress, to drill down per ingress
  and know how many servers it has
  - P3: define ingress identity and expected behavior for multiple servers and reloads.

## Configuration, operations

- [ ] default configs / layer configuration / overwrite config tree
  - P3: Define source precedence, map/list merge rules, deletion and reload semantics.
- [ ] warmup before ready
  - P3: makes sense after cache is implemented, reload a previous cache content or define some URLs
    to spider through
- [ ] documentation, nice examples are: Envoy
  - Choose audience and deliverables: operator setup, reference configuration or architecture.

## Build, tests

- [ ] google-java-format 2, https://github.com/google/google-java-format/releases#release-v2.0.0
  (stuck at 1.36.1: Spotless cannot load 1.37+, 2.x changes the lambda layout). Impact: one-time
  reformat of about 40 files / 930 lines, lambda bodies hug the call (`.forEach(t -> {`) and indent
  less. P3, no operator-visible effect: verify formatter/tool compatibility first. Done in its own
  commit, `formatJavadoc` stays off so the `///` comments remain untouched.
- [ ] Serenity reports (`-Dserenity.reports`) are of little use: no step is recorded for any
  class, the reports list tests only. The byte-buddy agent (`-javaagent` copy step) and
  `net.bytebuddy.nexus.disabled` are kept for that mode; whether they are needed with the
  listener off was not tried.
  - P3, no operator-visible effect: decide whether to make the reports useful or drop the report
    mode and the agent.
- [ ] Timing related tests: fake clock and a scheduler the test moves forward, then back into
  the test scope. Measurements and what was tried are in
  [doc/design/test-speed.md](doc/design/test-speed.md).
  Today these tests wait on real timers and for completions on other threads,
  which makes them slow, load sensitive, and forces `LogCapture.ofAllThreads()` with `@Isolated`.
  The pattern exists in cache2k (simulated clock with a scheduler that advances) and should be
  taken from there; `CircuitBreaker` already takes a `Ticker` and `CircuitBreakerBDDTest` drives
  it with a mock.
  - Seams needed in `HttpBatchSender` (under the 100% coverage rule): the timer, now
    `CompletableFuture.delayedExecutor` in `scheduleFlush` and `scheduleProbe`; the clock, now
    `System.nanoTime()` for the age of a batch; and the transport, now `httpClient.sendAsync` in
    `post`, so a test completes a request itself with a status, a `Retry-After` or an
    `IOException`. Production defaults stay as they are, no config change.
  - Candidates: `OtlpLogDestinationTest` (3.6 s and `@Isolated`; 27 of its 34 tests run against a
    log server, about 20 of them test sender logic: retry, give up, outage, buffer limit,
    breaker probes, aging, slow start, concurrency limit), `ReloadableRequestLoggerTest` (3.3 s
    on 2026-11-16; since 2026-11-18 it sets 10 to 50 ms retry delays, real time still),
    `RequestLoggerFactoryTest`,
    `HttpBatchSenderTest`,
    `AsyncRequestLogWriterTest`, `ConfigFileWatcherTest`, and the durations in `ProxyShutdownIT`.
  - The tests stay in the test scope meanwhile, see the coverage rule in `AGENTS.md`. Moving
    `OtlpLogDestinationTest`'s 27 server tests to an `*IT` was tried 2026-11-14 and undone: it
    saved about 4 s in `./mvnw test`, but the unit tests alone lost coverage, lines 95.1 % ->
    94.5 %, branches 89.8 % -> 89.0 % (`HttpBatchSender` +20 lines and +11 branches missed,
    `ThreadBatcher`, `OtlpBatchPayload`, `OtlpLogDestination`, `OtlpConfig`).
  - On the fake clock a test needs no `@Isolated` any more. Keep a few real ones in addition, as
    `*IT`: the wire format (headers, content type, gzip, body parsed by the OTLP classes), a
    refused connection, and one multi threaded end to end run. They may only repeat what a unit
    test covers.
  - Write the converted tests as step chains (`*BDDTest`).
  - New scenarios for the configurable retry delays, on the simulated clock only: the nominal
    delay stops at `initialRetryDelayMillis × 2^20` below a larger `maxRetryDelayMillis` (see
    Limitations in [doc/design/circuit-breaker.md](doc/design/circuit-breaker.md)); with
    `maxRetryDelayMillis` above `maximumUnsentLogAgeMillis` the batches age out between two
    probes, are counted as dropped, and the next probe still comes.
  - P2.
- [ ] Component level config tests, against the rule in `AGENTS.md`
  - P3, no operator visible effect: `RequestLoggerFactoryTest.environmentVariables` loads
    destinations from env vars; `environmentVariableMapEntryNeedsValue` tests the env loader
    itself; `ConfigTest` has cases built on component configs (`configDeflector`,
    `readRoutingFromYaml`, `readRoutingFromEnvironment`).
  - Remove a case if `./mvnw clean test jacoco:report` shows no coverage loss; a case that
    covers a loader path moves to `ConfigTest` with a generic record.
- [ ] Load sensitive timing assertions in tests, against the rules for concurrent tests in
  `AGENTS.md`
  - P2: each one can fail on a loaded machine although the code is right.
  - Maximum elapsed time, drop it: `ReloadableRequestLoggerTest`
    `replacedDestinationSendsItsBacklog` and `removedDestinationDoesNotBlockTheReload` (reload
    `< 500` ms, instead hold the log server on a latch during the reload), `ProxyShutdownIT`
    (`< 5_000` twice, `< 2_500` in `slowRequestIsAbortedAndStillLogged`), `ShadedJarIT` (exit
    `< 4_000` after `destroy()`). The minimum times in `*IT` (`>= 900`) stay.
  - Short configured timings that race the test's own steps:
    `ReloadableRequestLoggerTest.finishedDestinationEndsWhenItsRecordsAgedOut` (age 300 ms, then
    `awaitAttempts(1)`: a pause over 300 ms may age the records out before the first attempt,
    inferred, not reproduced), `OtlpLogDestinationTest.agedBatchIsDiscarded` (age 200 ms).
  - Rate over a real time window: `OtlpLogDestinationTest` "probes in 500 ms, with 10 more
    batches waiting" `<= 6` after `Thread.sleep(500)`.
  - Sleep then assert, cannot fail but proves little and costs time: `Thread.sleep(100)` before
    `maxInFlight` in `OtlpLogDestinationTest` and `OtlpSlowStartIT`, `poll(100 ms)` is null in
    `OtlpLogDestinationTest` and `ReloadableRequestLoggerTest.awaitReceived`, `Thread.sleep(200)`
    before `isNotDone()` in `ProxyShutdownIT.shutdownWaitsForReload`.
  - Remedy for the timers, ages, backoff and the probe rate: the simulated clock, see "Timing
    related tests". The `TimeBox` of cache2k pinpoint (below) for maximum times is not needed
    any more, maximum times are not asserted at all.
- [ ] Event driven waits instead of polling
  - P2, test speed and clarity: `LogCapture`/`LogSink` and `RequestLogCapture` cannot wait for
    an entry, so `ExternalTcpIT` (`awaitLogged`, `awaitRequestLog`, `awaitHandshakeErrors`),
    `ShadedJarIT.awaitOutput` (reads the stderr file) and the counter and gauge `await`s in
    `OtlpLogDestinationTest` and `ReloadableRequestLoggerTest` poll, as does
    `ConfigFileWatcherTest.waitUntilReloads`.
  - Remedy: a blocking wait for an entry on the captures, see "Instance scoped diagnostic
    logging" below; test log servers that signal each post with a `Semaphore` or queue (several
    have a `received` queue already), a latch in the reload callback. `TestingParameters.await`
    stays where no hook exists.
- [ ] Complete config records, plan in
  [plans/complete-config-records.md](plans/complete-config-records.md)
  - P2, the dumped configuration is complete and can be replayed: defaults preset in an
    overridden `builder()`, derived defaults (`maxBufferedBytes`, `maxConcurrentRequests`,
    `requestLog.drainTimeoutMillis`) computed in the builder's `build()` from a
    `ProxyEnvironment`, the `effective*` getters of `OtlpConfig` and `ProxyConfig` removed.
  - Incompatible: an explicit `0` that meant "default" is rejected, see the plan.
- [ ] Instance scoped diagnostic logging, plan in
  [plans/instance-scoped-logging.md](plans/instance-scoped-logging.md)
  - P2, test speed and clarity, no operator visible change: non-trace loggers become instance
    fields owned by a `LogScope` (proxy instance and component), trace stays on a `static final`
    `TraceLogger`. Tests capture by scope instead of by thread, so `LogCapture.ofAllThreads()`
    and the `@Isolated` it forces go away, and `LogCapture.await` waits for a record without
    polling.
- [ ] `ShadedJarIT` waits the full stop grace of its test servers
  - P2: with `TestingParameters.stop(HttpServer)` the class went from 10.3 s to 29.8 s, about
    12.85 s per `sigtermKeepsFinalShutdownDiagnostic` case, see
    [test speed design](doc/design/test-speed.md#asynchronous-waits-share-one-upper-bound). A
    connection of the child JVM is the suspected cause, not verified.
  - The wait for the child JVM to listen went from 30 s to the 10 s `TIMEOUT`. A JVM start is
    many steps, not one action, so it needs its own bound, a multiple of `TIMEOUT` in
    `TestingParameters`.
- [ ] Investigate cache2k pinpoint for the test patterns
  - P3: `cache2k-pinpoint` (module of cache2k, 2.6.1) has `PinpointParameters.TIMEOUT`,
    `Await` with a `TimeoutError` naming the condition, `TimeBox` (timing assertions only count when
    run within the time box, for "no retry within 100 ms" and the duration ranges in
    `ProxyShutdownIT`), and `SupervisedExecutor`/`TaskSuccessGuardian` for exceptions of worker
    threads. It is not on Maven Central (its deploy is skipped), so decide: publish it with a
    cache2k release, or copy the needed classes.
- [ ] To avoid flooding we made request level errors debug level. That has the effect that 
    misconfigurations are not immediately visible and will cause frustrartion during initial setup. 
    Introduce a generic way to log each problem class once at warning level, like 
    LOG.reqeustLevelProblem(Object type, ....). Use the class or add a constant with <classname>.<problem name>.
    Maybe unify logging and metrics so there is only one method call and unify with the 
    instance level logging change. P2

## Evergreen / Cleanups

- [ ] Scrutinise the todos listed here and suggest removals or lower prio for things that have no
  visible effect to the operator
- [ ] Check on GitHub CI actions and add a TODO or fix right away if something is broken
- [ ] Improve code according to styling rules
  - Ongoing review principle; record concrete inconsistencies as separate tasks.
- [ ] improve code coverage towards 100%
  - Prioritize uncovered risk and meaningful behavior rather than a percentage alone.
- [ ] Move a test case that is doing real socket connections or sleeps from test to verify and
  maintain code coverage
  - Goal: `./mvnw test` at about 5 s wall time. The classes run in parallel, the methods of a
    class in order, so one slow class sets the wall time and a few hundred ms count.
  - Move only what adds no coverage of its own: compare `target/site/jacoco/jacoco.csv` of
    `./mvnw clean test jacoco:report` with and without the test (exclude with
    `-Dtest='**/*Test,**/*BDDTest,!Class#method'`, a bare exclusion pulls in the `*IT`s). One
    branch of `AsyncRequestLogWriter` varies between runs, rerun before reading a difference.
  - Done 2026-11-16: `ReloadableRequestLoggerTest.noRecordIsLostWhileReloading` (0.3 s, no own
    coverage) to `ReloadableRequestLoggerIT`, which uses the unit test class as its fixture.
  - Next: `ReloadableRequestLoggerTest` still takes 3.3 s in the full run (2026-11-16):
    `finishCoversDrainingDestinations` 1.4 s, `finishedDestinationEndsWhenItsRecordsAgedOut`
    0.3 s, `disableAndEnableByReload` 0.2 s, `removedDestinationDoesNotBlockTheReload` 0.15 s.
    Probably real timers (retry backoff after 503, age limit), not verified. What cannot move
    for coverage gets a fake clock instead, see "Timing related tests" under Build, tests.
- [ ] Performance test: different Netty allocators, leak detection,
  `-XX:NativeMemoryTracking=summary`
  - P3: compare representative workloads and record throughput, latency and memory before choosing.

## Governance

- [ ] add LICENSE
  - P2 before an image is published (Kubernetes item 2): a public image needs a license.
- [ ] add CONTRIBUTING.md

## Done

- [x] Request level events are logged at debug and counted (2026-11-17): every failure that can
  happen per request or connection, an unexpected exception included, is `DEBUG` with channel,
  phase and stack trace, and counted (`sensepitch_ingress_request_errors` with type `ja4`, new
  `sensepitch_upstream_errors`, `sensepitch_request_log_errors`, `sensepitch_ip_lookup_errors`).
  The rule is in the `ProxyLogger` doc and `AGENTS.md`, the reason in
  [diagnostic logging](doc/design/diagnostic-logging.md).

- [x] Error log flood while an upstream is down (2026-11-17):
  `DownstreamHandler.failExchange` logs upstream connect and write failures at debug with the
  outcome, exception type and message. Request-log exceptions, outcomes, metrics and response
  behavior are preserved. `UpstreamFailureBDDTest` checks these reports and ignored late failures.

- [x] Accept grace at shutdown (2026-11-17): `shutdownAcceptGraceMillis` (default 0) keeps
  accepting and serving after readiness turned `503`, within the shutdown deadline and without
  taking the request log's reservation; no `preStop` `sleep` needed. Tested on a fake clock
  (`ProxyAcceptGraceTest`) and with real connections (`ProxyShutdownIT`). Renamed and moved from
  `admin.drainDelayMillis` before release. See [README](README.md#shutdown) and
  [shutdown design](doc/design/request-log.md#graceful-shutdown-drains-the-request-log-last).

- [x] Lock on a private lock object (2026-11-17): `HttpBatchSender`, `ThreadBatcher`,
  `CircuitBreakerMetrics`, `Proxy` and `ConfigFileWatcher` now encapsulate synchronization.
  Preserved critical sections, lock ordering and callbacks outside locks; retained volatile
  visibility for reads outside locks. The shutdown/reload integration test coordinates an actual
  reload without holding the proxy monitor. See
  [doc/design/request-log.md](doc/design/request-log.md#private-locks-preserve-ownership-and-lock-ordering).

- [x] Configurable OTLP retry backoff (2026-11-17): per-destination
  `initialRetryDelayMillis` and `maxRetryDelayMillis`, defaulting to 1000 ms and 30000 ms.
  Reject negative or reversed effective bounds; reload replaces the destination while its old
  backlog drains with the old settings. See [doc/design/circuit-breaker.md](doc/design/circuit-breaker.md).

- [x] One central test timeout instead of literal waits (2026-11-17): `TestingParameters`
  supplies the 10-second completion bound, descriptive `await` and graceful HTTP server stop.
  Replaced the listed completion waits, deadline loops and unbounded queue reads; intentional
  outages, negative waits, deliberate pauses and outer test guards remain explicit. See
  [test speed design](doc/design/test-speed.md#asynchronous-waits-share-one-upper-bound).

- [x] Log4j2 shutdown hook ran concurrently with ours and dropped the shutdown reports on
  `SIGTERM` (2026-11-17), see [doc/design/diagnostic-logging.md](doc/design/diagnostic-logging.md).
  Found on the way: `long` config values (`shutdownTimeoutMillis`,
  `requestLog.drainTimeoutMillis`) could not be set via YAML or environment.
- [x] Rename the `ProxyLogger` constant `DEBUG` to `LOG` in `DownstreamHandler`,
  `RequestLoggingHandler`, and `JsonRequestLogger` for consistent naming; no behavior change.

- [x] Flaky test: `OtlpLogDestinationTest.badRequestGivesUpTheBatch` expected one log line and got
  two in CI run 43810408003 (2026-11-16). The extra line, "Request log export to ... failed:
  java.io.IOException: HTTP/1.1 header parser received no bytes. Buffering records and retrying",
  came from the sender of an earlier test, not from this one: the log assertion is the last of the
  test, so its `export_retries` was zero, its own sender never retried. Root cause in the test
  server: it put a post into `received` before writing the answer, a test could end right after
  taking its last post, and `server.stop(0)` closes open connections at once (JDK 25
  `sun.net.httpserver.ServerImpl.stop`). The cut post failed, and the sender of the ended test,
  which does not wait in `@AfterEach`, logged the warning on its own thread into the all threads log
  capture of the next test. The sender is not at fault: each `HttpBatchSender` has its own
  `HttpClient`, so the first post of a test cannot reuse a pooled connection, and a connection
  closed by the server is a retryable failure as the OTLP specification asks. Shown with a throwaway
  test that delays the answer by 300 ms after publishing (2026-11-16, not committed): with `stop(0)`
  exactly this warning is logged, with `stop(1)` nothing. The CI flake itself was not reproduced,
  and the port of the extra line is not in the TODO, so which earlier test cut its post is inferred.
  Fix: `@AfterEach` of `OtlpLogDestinationTest` and `ReloadableRequestLoggerTest` stops the server
  with `stop(1)`, which lets a running exchange write its answer and returns at once when none runs.
  Publishing only after answering was tried and dropped: tests that wait for the sender and then
  read `received` without blocking, e.g. `closeSendsPendingRecords`, failed 1 of 5 runs.
  `ProxyOptionsTest` is unaffected, `Proxy.shutdown()` drains the request log before its collector
  stops.
- [x] CI did not run (2026-11-16): every run since at least 2026-11-15 23:03 UTC stayed `queued`
  on the `self-hosted` runner, probably offline (not verified). Both workflows run on
  `ubuntu-latest` now, free for a public repository; a new push cancels the run of the previous
  one (`concurrency` in `ci.yml`), setup-java caches the Maven dependencies. First hosted run
  green in about 1.5 min. The runs queued for the self-hosted runner are left over.
- [x] Flaky test: `ReloadableRequestLoggerTest.noRecordIsLostWhileReloading` dropped 4-5 records
  (2026-11-16, failed 7 of 8 runs alone). Root cause in the test: it logged from plain threads
  into a logger built for one thread, so a worker could log into a replaced destination for the
  first time after it finished, which refuses the record. Fix: the workers are executors passed
  to the logger as logging threads, as the event loops are. Now in `ReloadableRequestLoggerIT`.
- [x] Flaky test: `OtlpAllocationTest.loggingDoesNotAllocate(false)` measured 357 bytes per
  record against the bound of 256 in one parallel `./mvnw verify` (2026-11-16), it passed alone
  and in the next run. Inferred, not verified: under the parallel load the warm-up was not
  enough for the C2 compilation. Moved to `OtlpAllocationIT`, it measures the JIT more than our
  code. Its only unit coverage, the ranged `OtlpRequestLogSink.string` for a URI with a query,
  was ported to `OtlpLogDestinationTest.recordIsExportedWithResourceScopeAndAttributes`; unit
  line coverage is the same before and after (5017 lines), one branch of
  `AsyncRequestLogWriter.reportError` varies with timing between runs. Tagged `excessive`: it
  runs nightly and with `-Dexcessive`, not in `./mvnw verify`, see
  [doc/design/test-speed.md](doc/design/test-speed.md).
- [x] Flaky build: `OtlpLogDestinationTest.outageLosesNothing` timed out on machines with 2 CPUs
  (2026-11-15). Root cause: `maxBufferedBytes` default (2×event_loops×batchBytes) was 512 KiB on
  2 CPUs, smaller than the 1000-record backlog the test builds; records were dropped and the count
  was never reached. The 30-second timeout was reproduced on baseline `e22c564` with
  `./mvnw clean test -Dtest='OtlpLogDestinationTest#outageLosesNothing'`; it does not establish
  the cause of the earlier unrecorded failure. Fix: set `maxBufferedBytes(64 MiB)` explicitly in
  that test. 3 of 3 runs pass
  in 0.9 s. `ProxyShutdownIT`, `ShadedJarIT.sigtermSendsTheRequestLogAndExits` are under Bugs.
  - CI preserves both unit and integration test report directories for investigation.
- [x] explain PoW admission: [doc/design/pow-admission.md](doc/design/pow-admission.md), overview
  and current limitations (2026-11-16).
- [x] shutdown report: print elapsed phase times and report a still-running event loop group
  (`Proxy.shutdownOnce`). Naming the stuck loops was dropped, see
  [doc/design/request-log.md](doc/design/request-log.md).
- [x] Removed the unused WebCrypto-only `src/main/resources/challenge.html`.
  - The current challenge JavaScript PoW suite already tests WebCrypto and the JS fallback during
    `./mvnw verify`.
- [x] `DownstreamHandler.isSharable`: removed the redundant override; the handler remains unshared.

- [x] Kubernetes 1. `/healthz` and `/readyz` on the admin port (2026-11-16). `/healthz` answers
  `200` while the admin server runs; `/readyz` answers `503` from the start of the shutdown
  (`Proxy.isShuttingDown`), before the listener closes, `200` before. Readiness does not wait for
  the listener to start; the configuration is loaded before the admin server starts, so it needs
  no extra wait for the first load. Tests: `AdminServerTest`.

- [x] request log post latency: a histogram of the OTLP post duration per destination; today a slow log server
  shows only once requests time out and the breaker opens (2026-11-15).
  `sensepitch_request_log_post_duration_seconds{destination}`, buckets 30 ms–5 s;
  recorded in `HttpBatchSender.complete`. See branch
  `hermes/otlp-post-latency-histogram`.

- [x] `DownstreamHandler.completeWithError`: the response may have been sent already; drop the
  upstream connection (2026-11-15). `completeWithError` now always closes the upstream of the
  exchange (also one still connecting) and ends the exchange. Before the response has started it
  answers with the error status as before; once `DownstreamHandler.write` has passed the response
  headers it closes the client connection without a second response, so the client sees a
  truncated response (short content length or missing last chunk), and the request log records
  the reported outcome (e.g. `UPSTREAM_WRITE_FAILED`). Callers: a failed request write upstream,
  a failed connect, and a second request before the response is complete. The upstream reports
  the failed write asynchronously, possibly after the response was complete; `failExchange`
  ignores a failure whose exchange is over (the upstream future is no longer the active one), so
  it neither sends a second response nor changes the log of the next keep-alive request. Tests:
  `UpstreamFailureBDDTest` (before the response, after the headers, after a partial body, after
  the complete response, pipelined request). `ExceptionHandler` already checks
  `RequestLoggingHandler.isResponseStarted` and was not changed.

- [x] `CircuitBreaker.onIgnored` closes an open or half open breaker (2026-11-15). Before, an
  ignored result of the probe, e.g. a status 400, left the breaker `HALF_OPEN` and the next probe
  waited for the probe timeout (11 s with the default request timeout). Decided to close rather
  than only release the probe: the server answered, so it is up, and a released probe would most
  likely succeed one round trip later anyway. `HttpBatchSender` logs the recovery for a batch
  given up as for one sent. Covered by `CircuitBreakerBDDTest.ignoredProbe_closes` and
  `OtlpLogDestinationTest.badRequestOfTheProbeEndsTheOutage`.

- [x] Failure counters (2026-11-16): `sensepitch_ingress_request_outcomes_total{ingress,outcome}`
  counts every logged request by its `RequestOutcome`. Counted once in `RequestLoggingHandler`
  when the request is logged, not where an outcome is reported: the first report wins, so
  counting at the report sites (`IngressTimeoutHandler`, `DownstreamHandler`) would double count,
  and the sum would no longer match the request log. The `ingress` label is the sanitized host,
  like on every ingress metric. No zero series for outcomes that did not happen, like the other
  labelled counters; a per ingress cache to create them was dropped, the Prometheus `Counter`
  already caches its data points.

- [x] `ingress` label on `sensepitch_ingress_request_errors`, `sensepitch_ingress_receive_timeout`
  and `sensepitch_ingress_requests_{started,completed,aborted,in_flight}` (2026-11-16). The
  ingress is the host of the request in progress. Without one (a handshake error, or a timeout
  or error before the first request or between keep-alive requests) it is the TLS server name
  (SNI), sanitized like the host header: `SanitizeHostHandler` stores the `SniCompletionEvent`
  and sanitizes it only when an error or timeout is counted
  (`ProxyMetrics.ingress(ChannelHandlerContext)`); `missing_host` without a name, also for bytes
  that are no TLS (netty fires the event with no name, seen in `ExternalTcpIT`), `nil_host`
  before the client sent anything. The requests counters are Prometheus metrics
  now instead of `LongAdder`s with a custom collector; `in_flight` is a gauge incremented and
  decremented with them, the shutdown report sums it over the ingresses. Incompatible for
  queries that match the series exactly, sums are unchanged. The fixed data point fields
  `ingressReceiveTimeoutFirstRequest`/`KeepAlive` are gone, the label values are dynamic now.
  `request_errors` counts the upstream response and the response write timeout as
  `type="timeout"`, before `other`: they are no unexpected errors, and are also in
  `request_outcomes`; each metric counts a timeout once.
  `sensepitch_ingress_connections_{opened,closed,active}` stay without label, the documented
  exception in the `ProxyMetrics` class doc: labelling them by SNI was tried and dropped, it
  sanitizes the name and looks up labelled data points on every connection for a breakdown that
  is rarely needed.

- [x] `DownstreamHandler.channelInactive` logs an unexpected close (2026-11-15). Expected is a
  close without an exchange in progress (idle keep-alive, after the response, shutdown drain) or
  one the proxy requested: every close of ours passes `DownstreamHandler.close` (error responses,
  timeouts, `ExceptionHandler` incl. connection resets, which it already counts). Unexpected is
  any other close during an exchange, i.e. the client closing it, or the event loops ending after
  the drain timed out. It is no fault of ours, so it is logged once per channel at debug, with the
  phase (`upload`, `waiting`, `responding`, named like the `ExceptionHandler` phases) and the
  upstream state (`connecting`, `connected`, `connect failed`). The request log already has the
  request as `CLIENT_ABORTED`, so the debug line carries only what it lacks. No metric: `ingressRequestsAborted` counts these.
  `channelInactive`, `channelWritabilityChanged` and `exceptionCaught` no longer call `resultNow()`
  on a failed connect (it threw). `ProxyLogger` got a debug level, the levels are in `AGENTS.md`.
- [x] Serenity: reporting off by default, `-Dserenity.reports` switches it on (2026-11-14).
  Serenity stays for the BDD step chains. The `pom.xml` deactivates
  `SerenityTestExecutionListener`, so no test is recorded and nothing is written to
  `target/serenity-reports` (1163 files otherwise). Measured in one session, parallel unit tests
  with JaCoCo: test phase 13.3 s and 14.6 s with reports, 9.6 s and 9.5 s without; full
  `./mvnw clean verify` about 33 s. Not solved by a Serenity setting, there is none: an empty
  `output.formats` is rejected, and running the Serenity classes last saved under a second.
- [x] Failing `@Step` passed silently (2026-11-14). Cause: a step object injected with `@Steps`
  is a Serenity proxy that records a failure and returns normally;
  `SerenityJUnit5Extension.afterEach` rethrows it only if the listener started a test outcome
  whose name matches the method, which is never the case for a `@ParameterizedTest`, and not at
  all without the listener. Affected were the three classes using `@Steps`
  (`AdmissionChecksumBDDTest`, `CircuitBreakerBDDTest`, `UnservicedHostHandlerBDDTest`), in
  practice the 2 parameterized tests of `AdmissionChecksumBDDTest`; the other parameterized BDD
  tests created their step objects with `new` and were never affected (an earlier version of
  this entry listed 6 classes, which was wrong). Fix: step objects are created with `new`
  everywhere, `SerenityUsageTest` fails on an injected one. Checked by breaking steps in plain
  and parameterized tests, with reports off and on.


- [x] refuse methods other than GET without admission or bypass with an empty 403;
  count and log the deflector outcome as `method_rejected`. Challenge telemetry uses GET
  (XHR with an image fallback) instead of `sendBeacon`, which always sends POST

- [x] Admission performance: decode the received checksum and compare hash bytes instead of
  Base62-encoding the computed checksum in `DefaultAdmissionTokenGenerator.checkAdmission`

- [x] `IngressTimeoutHandler` response timeout: fire `UpstreamResponseTimeoutException` to close
  the upstream, let `ExceptionHandler` send the 504 once, and wait for the write before closing
- [x] `ExceptionHandler`: respond to `UpstreamResponseTimeoutException` with 504 instead of 502

- [x] `DownstreamHandler.write`: remove upstream hop-by-hop response headers, including headers
  named in `Connection`; keep chunked framing for the downstream HTTP codec

- [x] `IpTraitsHandler`: include custom key/value traits in the upstream header, sorted by key;
  `IpTraits.Builder.addLabel` rejects reserved keys and separators, `DetectCrawler` matches the
  `crawler` key exactly instead of a substring

- [x] remove the commented-out debug log lines (`ForwardHandler`, `IngressTimeoutHandler`,
  `IpTraitsHandler`, `RequestLoggingHandler`) and the commented-out Netty `LoggingHandler`
  (`Proxy.addHttpHandlers`)

Checked against the code when moving the list here, 2026-11-13:

- [x] config: stop / complain if option is unknown (`RecordConstructor`, "Unknown parameter")
- [x] send 404 for any non / URL for unserviced domain (`UnservicedHost`: 404 without a default
  location, else a redirect to the not found page for any URL but `/`)
- [x] only allow known HTTP methods (`SanitizeHostHandler` maps others to `INVALID`, answered
  with 400)
- [x] add netty memory pool statistics to monitoring / expose allocator statistics via
  prometheus (`NettyAllocatorMetrics`)
- [x] use delombok, so JavaDoc has proper documentation in the builder (bound to `verify`)
- [x] Enable tracing / debugging switch (`log4j2-trace.xml`, `SENSEPITCH_EDGE_DISABLE_TRACE`,
  see `ProxyLogger`)
- [x] standard logging target (Log4j2 to standard error, the request log to standard out)
- [x] cleanup debugging code / use Netty logger (`ProxyLogger` on Log4j2, no `System.out`; the
  leftover commented-out lines were removed later, see the first entry)
- [x] obsolete: Logging: Netty LoggingHandler seems not to respect the origin class (not used)
- [x] minimal POC with transparent passthrough
- [x] expose statistics counters
- [x] simple request log, with request, response status, content length
- [x] SNI ssl configuration
- [x] bypass config for URL prefixes
- [x] redirect option if host does not match
- [x] proxy headers
- [x] pass to NGINX configuration
- [x] host based upstream routing
- [x] pow deflection: use server IP in token
- [x] pow deflection: make time a parameter
- [x] make admission secret configurable
- [x] can we use Builder in lombok?
- [x] openssl support for HTTPS
- [x] upstream backpressure
- [x] YAML based configuration
- [x] improve default configuration
- [x] metrics: byte IO count per ingress
- [x] Remove MetricsBridge, we exclusively use Prometheus
- [x] markdown JavaDoc, https://openjdk.org/jeps/467
- [x] rename the headers the edge adds from `X-Sensepitch-*` to a short `Sp-*` prefix, like
  Cloudflare's `Cf-*` (e.g. `Sp-Protection-Bypass`); upstreams reading them must follow
- [x] reload configuration without closing the port
