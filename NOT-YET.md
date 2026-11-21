# Not yet

Optional work, outside the current focus: running the standalone edge on Kubernetes with direct
node ingress, see [TODO.md](TODO.md). Moved out of `TODO.md` on 2026-11-21; the entries keep
their text, numbers and priorities from there. An entry moves back to `TODO.md` when it becomes a
need, e.g. a bug or a production requirement. Bugs and robustness issues are never parked here.

## Kubernetes, Gateway API controller

The Gateway API implementation with its Go controller (milestones M1 to M3, plan in
[doc/kubernetes-support/](doc/kubernetes-support/)). Not needed to run today's `config.yml` edge
from the standalone chart. The numbers continue those of the Kubernetes section in `TODO.md`.

- [ ] 6. PROXY protocol v1/v2 per listener and `X-Forwarded-For`/`-Proto` only from trusted
  proxy CIDRs
  - P2: behind a cloud load balancer the client IP is lost today, and the deflector and IP traits
    depend on it.
  - Not needed with direct node ingress: the TCP peer is the client.
- [ ] 7. M1 path, see `6-phases.md` "Development order": Gateway API Java records and snapshot
  schema, then the `config.yml` converter run against the production configurations, then the
  Go controller
  - P2: start once 1-4 are done; the converter shows early whether the model covers production.
    Gateway API hostnames use wildcards, see the `SiteSelector` entry under Ingress.
- [ ] 8. extend the kind environment from item 3 with pinned Gateway API standard-channel CRDs,
  the controller image, an echo backend, and cloud-provider-kind for LoadBalancer Services.
  See `6-phases.md` "Development order" item 5 and
  `7-testing-and-release.md`.
  - P2: needed before the end-to-end routing test of item 7.

## Protection, admission

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
[doc/design/request-log.md](doc/design/request-log.md).

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
  - P2: do a draft design first, researching what parameters are needed, looking at other proxy products
- [ ] cache upstream responses with cache2k in netty buffers
- [ ] Allow cache to be tiered on disk and Redis
  - P2 relatively important so we don't have restart spikes
- [ ] transparent compression: all responses to the client are compressed with low overhead brotli, add benchmarks
- [ ] best compression for cached content
  - first do low overhead brotli and cache it, recompress with more efficient compression with half
    of the available CPUs in niced priority, priotising hot resources
- [ ] multiple servers per upstream
  - P3: decide load balancing, retry safety, failover and configuration semantics.
  - One circuit breaker per server, see the `Endpoint` record in `HttpBatchSender`.
- [ ] upstream health and circuit breaker:
  [`CircuitBreaker`](src/main/java/org/sensepitch/edge/CircuitBreaker.java) exists for the request
  log, it opens on the first failure; for upstreams it needs a failure threshold (consecutive
  failures or a failure rate window, as in Armeria or resilience4j). This breaker replaces the TCP readiness probe.
  - P2: do research and design draft and give decisions here
  - P3: specify thresholds, recovery probes, per-server versus per-upstream state and retry policy.
  - Keep the backoff, `Retry-After`, single probe and metrics, the request log keeps a threshold
    of one, see [doc/design/circuit-breaker.md](doc/design/circuit-breaker.md).

## Circuit breaker

Limitations and the reasoning are in [doc/design/circuit-breaker.md](doc/design/circuit-breaker.md);
the failure threshold for upstreams is under Upstream.

- [ ] slow calls count as failures, as resilience4j's slow call rate
  - P3: for upstreams, once latency targets are known.

## Ingress, HTTP

- [ ] HTTP/2 support, downstream
  - P3: scope protocol negotiation, stream lifecycle, backpressure and compatibility tests.
- [ ] quic support
  - P3
- [ ] Implement server-timing, https://www.w3.org/TR/server-timing/
  - P3: enable/disable forward of upstream Server-Timing info, log all of it, add own timing info,
    e.g. upstream connect latency

## Metrics, logging

- [ ] Cleanup: Consolidate all sensepitch metrics prefixes as constants into one place including their descritpion P3

## Configuration, operations

- [ ] warmup before ready
  - P3: makes sense after cache is implemented, reload a previous cache content or define some URLs
    to spider through
- [ ] have good documentation, nice examples are: Envoy
  - Choose audience and deliverables: operator setup, reference configuration or architecture.
  - The operator documentation for the Kubernetes setup is in `TODO.md`, Kubernetes item 15.

## Build, tests

The test speed and flakiness work (fake clock, event driven waits, instance scoped logging,
`ShadedJarIT` stop grace) moved to [TODO.md](TODO.md#fast-tests-without-flakiness) as top
priority on 2026-11-21.

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
- [ ] Component level config tests, against the rule in `AGENTS.md`
  - P3, no operator visible effect: `RequestLoggerFactoryTest.environmentVariables` loads
    destinations from env vars; `environmentVariableMapEntryNeedsValue` tests the env loader
    itself; `ConfigTest` has cases built on component configs (`configDeflector`,
    `readRoutingFromYaml`, `readRoutingFromEnvironment`).
  - Remove a case if `./mvnw clean test jacoco:report` shows no coverage loss; a case that
    covers a loader path moves to `ConfigTest` with a generic record.
- [ ] Investigate cache2k pinpoint for the test patterns
  - P3: `cache2k-pinpoint` (module of cache2k, 2.6.1) has `PinpointParameters.TIMEOUT`,
    `Await` with a `TimeoutError` naming the condition, `TimeBox` (timing assertions only count when
    run within the time box, for "no retry within 100 ms" and the duration ranges in
    `ProxyShutdownIT`), and `SupervisedExecutor`/`TaskSuccessGuardian` for exceptions of worker
    threads. It is not on Maven Central (its deploy is skipped), so decide: publish it with a
    cache2k release, or copy the needed classes.
- [ ] Unify request level logging and metrics, so a request problem is reported with one method
    call that logs and counts, see `ProxyLogger.requestProblem`, and unify it with the instance
    level logging change. The `RequestProblem` constants already name the metric that counts them.
    `RequestLoggingHandler` now reports request-log failures with a `RequestProblem` and
    increments the error counter separately. P3

## Evergreen / Cleanups

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
    for coverage gets a fake clock instead, see "Timing related tests" in
    [TODO.md](TODO.md#fast-tests-without-flakiness).
- [ ] Performance test: different Netty allocators, leak detection,
  `-XX:NativeMemoryTracking=summary`
  - P3: compare representative workloads and record throughput, latency and memory before choosing.

## Governance

- [ ] add CONTRIBUTING.md
