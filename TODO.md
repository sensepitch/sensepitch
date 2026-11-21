# TODO

Open work, grouped by area. Moved here from the README roadmap and from the `TODO`/`FIXME`
comments in the code; an entry from the code names the class and method it is about.

Current focus (2026-11-21): first fast tests without flakiness, then run the standalone edge on
Kubernetes as the ingress of our own infrastructure, terminating TLS directly on the nodes,
without a provider load balancer. Optional work outside this focus is parked in
[NOT-YET.md](NOT-YET.md), among it the Gateway API controller; bugs and robustness issues stay
here.

Ordered by priority: fast tests without flakiness first, then bugs, then Kubernetes, then the
other areas. Priority suggestions: P1 = fast and reliable tests, bugs, and what the direct node
ingress on Kubernetes needs; P2 = robustness; P3 = the rest. Notes below record decisions to
make before implementation.

## Fast tests without flakiness

First, because every other task depends on it: a quick test loop and a failure that always
means a defect speed up and secure all work below. Measurements and what was tried are in
[doc/design/test-speed.md](doc/design/test-speed.md). The flaky tests found by the nightly hunt
are listed under Bugs.

- [ ] Timing related tests: fake clock and a scheduler the test moves forward, then back into
  the test scope.
  Today these tests wait on real timers and for completions on other threads,
  which makes them slow, load sensitive, and forces `LogCapture.ofAllThreads()` with `@Isolated`.
  - P1.
  - Library (decided 2026-11-21): morimekta `net.morimekta.utils:testing` (test scope, see
    `pom.xml`), `FakeClock` with `ImmediateScheduledExecutor`, which runs due tasks in the thread
    that ticks. Not `FakeScheduledExecutor`: it runs tasks on real threads and needs a sleep after
    the tick. The production seams take JDK or Netty types (`ScheduledExecutorService`, Netty
    `Ticker`, monotonic in production), never the library's; in tests a small `Ticker` adapter
    over the `FakeClock` drives the breaker and the scheduler from one time. `CircuitBreaker`
    already takes a `Ticker` and `CircuitBreakerBDDTest` drives it with a mock. Why not
    cache2k-testing: see [doc/design/test-speed.md](doc/design/test-speed.md#alternatives-considered).
  - The load sensitive assertions are listed in the next entry; this is the general remedy.
  - Seams needed in `HttpBatchSender` (under the 100% coverage rule), the first user: the timer,
    now `CompletableFuture.delayedExecutor` in `scheduleFlush` and `scheduleProbe`; the clock, now
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
- [ ] Load sensitive timing assertions in tests, against the rules for concurrent tests in
  `AGENTS.md`
  - P1: each one can fail on a loaded machine although the code is right.
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
    related tests" above. The `TimeBox` of cache2k pinpoint for maximum times is not needed any
    more, maximum times are not asserted at all.
- [ ] Event driven waits instead of polling
  - P1, test speed and clarity: `LogCapture`/`LogSink` and `RequestLogCapture` cannot wait for
    an entry, so `ExternalTcpIT` (`awaitLogged`, `awaitRequestLog`, `awaitHandshakeErrors`),
    `ShadedJarIT.awaitOutput` (reads the stderr file) and the counter and gauge `await`s in
    `OtlpLogDestinationTest` and `ReloadableRequestLoggerTest` poll, as does
    `ConfigFileWatcherTest.waitUntilReloads`.
  - Remedy: a blocking wait for an entry on the captures, see "Instance scoped diagnostic
    logging" below; test log servers that signal each post with a `Semaphore` or queue (several
    have a `received` queue already), a latch in the reload callback. `TestingParameters.await`
    stays where no hook exists.
- [ ] Instance scoped diagnostic logging, plan in
  [plans/instance-scoped-logging.md](plans/instance-scoped-logging.md)
  - P1, test speed and clarity, no operator visible change: non-trace loggers become instance
    fields owned by a `LogScope` (proxy instance and component), trace stays on a `static final`
    `TraceLogger`. Tests capture by scope instead of by thread, so `LogCapture.ofAllThreads()`
    and the `@Isolated` it forces go away, and `LogCapture.await` waits for a record without
    polling.
- [ ] `ShadedJarIT` waits the full stop grace of its test servers
  - P1: with `TestingParameters.stop(HttpServer)` the class went from 10.3 s to 29.8 s, about
    12.85 s per `sigtermKeepsFinalShutdownDiagnostic` case, see
    [test speed design](doc/design/test-speed.md#asynchronous-waits-share-one-upper-bound). A
    connection of the child JVM is the suspected cause, not verified.
  - The wait for the child JVM to listen went from 30 s to the 10 s `TIMEOUT`. A JVM start is
    many steps, not one action, so it needs its own bound, a multiple of `TIMEOUT` in
    `TestingParameters`.

## Bugs

- [ ] Check the nightly flaky test hunt, the workflow "Flaky tests":
  https://github.com/sensepitch/sensepitch-edge/actions/workflows/flaky-tests.yml
  - P1: a test that failed in any of its runs is a flaky test, and fixing it has priority over
    all other work here. Its run summary lists each failed test with the number of runs it failed
    in; add an entry below per flaky test until it is fixed. The general remedies are in
    "Fast tests without flakiness" above.
- [ ] Check the nightly build, the workflow "Nightly" (full gate plus the `@Tag("excessive")`
  tests, 21:47 Singapore time):
  https://github.com/sensepitch/sensepitch-edge/actions/workflows/nightly.yml
  - P1: a failed run is a bug, fix it before other work. An excessive test that fails is load
    sensitive or a real regression of allocation or timing; add an entry below per problem until
    it is fixed. The failed run uploads `nightly-test-reports` (kept 7 days).
- [ ] Flaky, not investigated: `ProxyShutdownIT`,
  `ShadedJarIT.sigtermSendsTheRequestLogAndExits`
  - P2: also the `SIGTERM` path.
  - The workflow "Flaky tests" (`.github/workflows/flaky-tests.yml`) counts failures over ten
    runs each evening; take the failure rates from its run summary.
- [ ] The challenge page shows a fixed host: `challenge/challenge.html` has
  `Connecting to: www.packingpanic.com` hard-coded, so every protected site names that host to
  its visitors. Fill in the requested host with the template placeholders `Deflector` already
  replaces (`{{ENDPOINT}}`, `{{PREFIX}}`), escaped for HTML, or drop the line; test it.
  - P1: visible to every challenged visitor of every site.

## Kubernetes, direct node ingress

The target: cloud hosts, each with its own public IPv4 and IPv6 address on its network
interface, no NAT and no load balancer in front; certificates come from an ACME client on the
host (HTTP-01), the edge reads its Let's Encrypt directory. On Kubernetes the edge runs as a
DaemonSet on the ingress nodes, binds 80 and 443 on the node addresses, and DNS lists the node
addresses (DNS spreading, see "Exposure variants" in
[1-architecture.md](doc/kubernetes-support/1-architecture.md)). The TCP peer stays the client,
so `bypass.remotes` and the IP traits keep working without PROXY protocol. The edge runs today's
`config.yml` from the [standalone chart](charts/sensepitch-edge/README.md); the Gateway API
controller (Kubernetes items 6 to 8, milestones M1 to M3) is in [NOT-YET.md](NOT-YET.md).

The numbers are kept from the earlier plan, other documents refer to them. Items after item 3
build on its kind deployment check in CI.

- [ ] 2. OCI image: Dockerfile with a Java 25 runtime, non-root, `java` as PID 1 so `SIGTERM`
  arrives (exec form); CI builds and pushes it
  - P1.
  - Decided: Temurin JRE (glibc, same vendor as build), registry
    `ghcr.io/sensepitch/sensepitch-edge`, shaded jar kept; see
    [doc/design/oci-image.md](doc/design/oci-image.md).
  - `DockerfileTest` validates the stages, Maven command, shaded JAR copy, non-root user, PID 1
    entry point, exposed ports, build context and the proposed workflow's location.
- [ ] 3. manifests or a minimal Helm chart for a standalone edge: Deployment with probes and
  `terminationGracePeriodSeconds` above `shutdownTimeoutMillis`, Service, PodDisruptionBudget,
  config from a ConfigMap, secrets from Secrets, scrape annotations or ServiceMonitor
  - P1: production on Kubernetes; the node ingress mode is item 9.
  - The standalone chart is in [charts/sensepitch-edge](charts/sensepitch-edge/README.md).
  - Completion requires `helm template` and `kubeconform` validation plus a passing deployment
    test in GitHub Actions on a hosted Ubuntu runner. Create a disposable single-node kind
    cluster with pinned kind and node image versions. Build the edge image once, load it with
    `kind load docker-image`, and install the chart with `helm upgrade --install --wait` and
    `image.pullPolicy=Never`.
  - Verify pod readiness and HTTPS through the Service. Rotate a mounted Secret and verify the
    running edge uses the new value. Terminate a pod and verify graceful shutdown within
    `terminationGracePeriodSeconds`. Reuse the same setup and smoke check locally.
- [x] 4. secrets out of the config file: `SecretConfig` (`value` or `file`) for `tokenGenerators`
  and the OTLP `secretHeaders`; the TLS keys and the IP databases are files already. The files a
  configuration read are recorded by `ConfigFiles` and watched by `ConfigFileWatcher`, so a
  renewed certificate Secret (cert-manager) reloads; the ConfigMap symlink swap is tested.
  See [doc/design/config-files.md](doc/design/config-files.md).
- [ ] 5. plain HTTP listener and several listeners (`ListenConfig` has one HTTPS port), with the
  HTTP to HTTPS forward (there is no plain HTTP listener yet)
  - P1: with direct node ingress the edge owns port 80 on the node, no other server answers it.
    Confirm the redirect status and host/port behavior. Also needed for an HTTP-01 challenge
    (item 12). The listener model should stay compatible with the Gateway API listeners
    (NOT-YET.md), without waiting for them.
- [ ] 9. node ingress mode in the chart: DaemonSet on labelled ingress nodes, ports 80 and 443 on
  the node addresses. Decide how the ports are bound:
  - P1.
  - [ ] `hostNetwork: true`: the edge binds the node addresses itself. Ports below 1024 as
    non-root need a capability; `capabilities.add: [NET_BIND_SERVICE]` is not effective for a
    non-root process without file capabilities on `java`, and the
    `net.ipv4.ip_unprivileged_port_start` sysctl is the node's in the host network namespace
    (inferred from the Kubernetes and Linux capability rules, verify on the cluster).
    `1-architecture.md` assumes the capability is enough.
  - [ ] `hostPort` 80/443 to container ports 17080/17443: the pod stays non-root in its own
    network namespace; the CNI `portmap` plugin forwards with DNAT, which keeps the client
    address (verify with the chosen CNI). The scheduler allows one pod per host port and node,
    which rules out the overlap of item 10.
  - The node firewall must let 80 and 443 through to the chosen path.
- [ ] 10. restart on a node without refused connections: with host ports a DaemonSet update
  stops the old pod before the new one binds (`maxSurge: 0`), and the node refuses connections
  in between. Tick one:
  - P1, decide together with item 9.
  - [ ] accept it: browsers try the next address of the name; check the clients that matter,
    e.g. payment provider webhooks, which may not retry
  - [ ] `SO_REUSEPORT` and DaemonSet `maxSurge: 1`: the new pod binds next to the old one, which
    stops accepting once the new one is ready (`shutdownAcceptGraceMillis`). Needs
    `hostNetwork` and the Netty epoll or io_uring transport (today NIO, `Proxy`).
- [ ] 11. dual stack: the default `listen.address` `0.0.0.0` accepts IPv4 only, the nodes have
  IPv6 as well. Verify that `address: "::"` accepts both on one socket, and that an IPv4 client
  then appears as IPv4, not `::ffff:` mapped, in `bypass.remotes`, the IP traits, the request log
  and `X-Forwarded-For`. With `hostPort` the cluster and CNI need dual stack too.
  - P1, before AAAA records point to the nodes.
- [ ] 12. certificates on Kubernetes: an ACME client on the host writes the Let's Encrypt directory
  `listen.letsEncrypt` reads today. Tick one:
  - P1.
  - [ ] cert-manager with DNS-01, one Secret per host or wildcard, mounted through `snis`;
    renewal reloads (item 4). Needs a DNS provider with an API cert-manager supports
  - [ ] cert-manager with HTTP-01: its solver publishes the challenge through Ingress or
    HTTPRoute objects, which only a controller serves; the standalone edge would need a fixed
    route for `/.well-known/acme-challenge/` to the solver (item 5)
  - [ ] an ACME client in the edge, with the account and keys shared by all nodes
  - Also: the chart mounts one TLS Secret (`tls.existingSecret`); many hosts need a list of
    Secrets or the `letsEncrypt` directory layout from one volume.
- [ ] 13. IP database on the pods: production reads the IPinfo database (`ipLookup.ipInfoPath`).
  A Secret or ConfigMap holds at most 1 MiB (`extraSecretMounts` in the chart), the database is
  larger (inferred, check the file size). Decide the volume: init container download, image
  layer, or hostPath; and the update without a restart (check whether `ConfigFileWatcher`
  watches it).
  - P1.
- [ ] 14. draining with DNS spreading: a node's address stays in client caches for the DNS TTL
  after it is removed from DNS. Decide who maintains the records without the controller (by hand
  or external-dns with a node source), the TTL, and the order for
  a node drain or upgrade: remove from DNS, wait the TTL, then drain.
  `terminationGracePeriodSeconds` cannot cover a TTL of minutes.
  - P2: until then, drain at quiet times.
- [ ] 15. operator documentation for the direct node ingress: chart values, DNS, certificates,
  firewall, the restart and drain procedure, in the chart README
  - P1, with items 9 to 14.
- [ ] 16. port the two production configurations (shop production, staging and demo, see
  "Production configurations versus Gateway API" in
  [3-gap-analysis.md](doc/kubernetes-support/3-gap-analysis.md)) to the chart: `localhost:<port>`
  upstreams become Service names, the upstream still served by old infrastructure
  (`127.0.0.1:3080`) a host address on the private network; list what does not fit.
  - P1: shows early what the chart or the edge lacks.
- [x] `/readyz` answers `200` only after the upstreams were probed and at least one configured
  upstream is available
  - P3: probed with a short-timeout plain TCP connect, short-circuiting on the first success; a
    configuration with only static-response sites is ready immediately. Reusing an upstream
    circuit breaker is N/A: upstreams are plain HTTP with one connect per request and no pool or
    breaker exists today.

## Protection, admission

- [ ] restrict admissions per solved challenge; see the
  [replay draft](doc/design/admission-replay-draft.md) and
  [PoW admission design](doc/design/pow-admission.md)
  - P3: today a challenge is a bare timestamp and an answer can be replayed for five minutes,
    each replay minting another admission. The draft's recommendation, not a decision: sign a
    random, host-bound challenge and derive one admission token deterministically from it, so a
    replay and every replica return the same token without shared state. Tick the open choices
    before implementing:
    - [ ] enforcement: stateless idempotent token, node-local consumed-challenge set, or
      cluster-wide shared store
    - [ ] replay handling: idempotent success, or strict rejection plus a replay metric
    - [ ] permitted reuse: unlimited requests per admission until expiry, or a per-token budget
    - [ ] bind the admission token to the site as well, or keep the challenge binding only
    - [ ] challenge lifetime and clock-skew window; key rotation and overlap period
    - [ ] migration policy for the current unsigned challenge and token format
  - Stays here as a security issue. With several nodes a node-local set lets a replay succeed
    once per node.
- [x] expire granted admissions and preserve the original token across revisits
  - The default maximum age is five days with five seconds of future clock skew. On expiry,
    the challenge records the original token and revisit count in a signed `admission0` cookie.
    Fresh admissions preserve the history. See [PoW admission design](doc/design/pow-admission.md).

## Request log

Decisions, alternatives and limitations are in
[doc/design/request-log.md](doc/design/request-log.md); retry backoff is described in
[doc/design/circuit-breaker.md](doc/design/circuit-breaker.md). Failover endpoints and the disk
spool are in [NOT-YET.md](NOT-YET.md).

- [x] compression budget: gzip runs on the common pool at a fixed level (`BEST_SPEED`)
  - Done: one bounded compression thread per OTLP destination, `otlp.compressionLevel` from 1 (the
    former `BEST_SPEED`, default) to 9. CPU and size measurements are in
    [the request log design](doc/design/request-log.md#gzip-on-one-dedicated-compression-thread).
    zstd remains contingent on the target log servers accepting it.

## Circuit breaker

Limitations and the reasoning are in [doc/design/circuit-breaker.md](doc/design/circuit-breaker.md).
Slow calls and the failure threshold for upstreams are in [NOT-YET.md](NOT-YET.md).

- [x] manual control: force a breaker open, e.g. for maintenance of a log server or upstream
  - P3: through the admin server, `POST /circuit-breakers/<component>/<name>/open` and `/close`
    (2026-11-20), see [doc/design/circuit-breaker.md](doc/design/circuit-breaker.md).

## Ingress, HTTP

- [ ] Request size limits: decide the options below, then implement the chosen ones. Research,
  measurements and sources in the draft
  [request-body-limit-draft.md](doc/design/request-body-limit-draft.md)
  - P2. Today: no body limit; the body streams with backpressure (64 KiB Netty water mark), so
    memory is bounded. Netty's header (8192 bytes) and request line (4096) limits answer 400,
    (inferred) as a missing host. Measured on 2026-11-20: NGINX 1.28.3 refuses bodies over 1 MiB
    with 413 because it buffers them; HAProxy 3.2, Caddy 2.11 and Envoy 1.36 stream and have no
    default limit, Caddy and Envoy offer an opt-in 413.
  - Found: a slow upload is not bounded. Every body byte resets the read timeout, so one byte per
    29 s keeps the client and upstream connections open (inferred from the code and a decoder
    experiment).
  - Body limit, tick one:
    - [ ] no limit, as today; document it in the README
    - [ ] optional `maxRequestBodyBytes`, off by default
    - [ ] default limit like NGINX (1 MiB)
  - With an optional limit, where:
    - [ ] `listen.connection`
    - [ ] per site and path route
    - [ ] both, a global value with overrides
  - With an optional limit, the refusal: 413 with `Connection: close` before the body when
    `Content-Length` is too large, mid-stream close of upstream and client for chunked bodies
    - [ ] as described
    - [ ] also answer `Expect: 100-continue` with 413 (check today's `Expect` handling first)
  - Visibility of refusals:
    - [ ] new `RequestOutcome` `request_too_large` in `sensepitch_ingress_request_outcomes_total`
    - [ ] status 413 in the request log only
  - Header and request line:
    - [ ] answer decoder failures explicitly: 431 too large header, 414 too long request line,
      400 other, with outcome or counter
    - [ ] raise the defaults (other proxies refused at 9000 to 70000 bytes)
    - [ ] configurable in `listen.connection` via `HttpDecoderConfig`
  - Slow uploads:
    - [ ] total request receive timeout in `listen.connection`, answered with 408
    - [ ] minimum upload rate
    - [ ] only correct the `ConnectionConfig` doc, which says a byte does not reset the timeout
- [x] `SiteSelector`: support single-label wildcard hosts
  - Exact hosts win before URI selection. See [routing and certificates](README.md#several-sites-for-one-host).

## Build, tests

The test speed and flakiness work is the first section, "Fast tests without flakiness". The
optional test work (cache2k pinpoint, component level config tests, unified request level
logging) is in [NOT-YET.md](NOT-YET.md).

## Evergreen / Cleanups

- [ ] Scrutinise the todos listed here and suggest removals or lower prio for things that have no
  visible effect to the operator; move optional work to [NOT-YET.md](NOT-YET.md)
- [ ] Check on GitHub CI actions and add a TODO or fix right away if something is broken
- [ ] Improve code according to styling rules
  - Ongoing review principle; record concrete inconsistencies as separate tasks.
- [ ] improve code coverage towards 100%
  - Prioritize uncovered risk and meaningful behavior rather than a percentage alone.
  - `IngressTimeoutHandler.TimeoutTask` (2026-11-21): the `assert future == null` branch in
    `schedule` and the `!ctx.channel().isOpen()` early return in `run` are not covered. The timer
    is cancelled in `channelInactive`, so the return may be reachable only between `close` and
    the inactive event; decide whether to test it or remove it (not verified).
    - P3

## Governance

- [ ] add LICENSE
  - P2 before an image is published (Kubernetes item 2): a public image needs a license.

## Done

- [x] Complete config records, plan in
  [plans/complete-config-records.md](plans/complete-config-records.md) (2026-11-20). Builders
  materialize constant and derived defaults, and the YAML dump loads again as an equal record
  with secrets restored. `OtlpConfig` and `ProxyConfig` use plain accessors for resolved values.
  See the plan for settings that now reject an explicit zero.

- [x] Add the historic admission token to the log (2026-11-20). JSON and OTLP logs include the
  original token and revisit count from verified `admission0` history. See
  [PoW admission design](doc/design/pow-admission.md).

- [x] Upstream informational responses (1xx, e.g. `100 Continue`) (2026-11-20). `ForwardHandler`
  keeps the upstream attached to the downstream and `DownstreamHandler` keeps the exchange active
  through a 1xx and its `LastHttpContent`; the final response then ends it as before. Both still
  sanitize hop-by-hop response headers. Covered end to end by `InformationalResponseBDDTest` with
  a `100 Continue` during an upload, the final response after the body, and a normal single
  response.

- [x] Unify timeout and keep-alive handling (2026-11-20). `IngressTimeoutHandler` decides
  keep-alive itself, `HttpServerKeepAliveHandler` left the pipeline. A response that starts while
  the client still sends the request body gets `Connection: close`; the connection is closed after
  the rest of the body was read, or after the read timeout (phase `after_response` of
  `sensepitch_ingress_receive_timeout`), instead of waiting for another request.

- [x] Resume client reads paused by upstream backpressure when the response finishes before
  the upstream becomes writable again (2026-11-20).

- [x] Ignore upstream writability changes after the final response content has been forwarded
  (2026-11-20). A late notification no longer closes the upstream or reports an upstream error.

- [x] Cleanup: first occurrence logging per metric series, counted by the logger (2026-11-20).
  `LOG.incrementAndWarnOnce(counter, labelValues, channel, message, cause)` replaces
  `requestProblem` and its `RequestProblem` record: it counts the event and logs it in one call.
  `LOG.warnOnce(...)` logs an event that is counted elsewhere (request outcome, aborted). The first
  occurrence is per series (data point, e.g. per ingress), and the `WARNING` names the series, e.g.
  `sensepitch_ingress_request_errors_total{ingress="a",phase="waiting",type="timeout"}`, instead
  of a `<Class>.<problem>` key. Label values come from the enums `IngressErrorType`,
  `UpstreamErrorType`, `RequestOutcome`. A second request during an exchange is now counted in
  `sensepitch_ingress_request_errors_total{type="another_request"}`. Upstream connect and write
  failures warn separately, by their `outcome`. An upstream exception during an exchange is logged
  by `ForwardHandler` at `DEBUG` only, since `ExceptionHandler` counts it and warns. It is no longer
  counted a second time as upstream `closed_in_flight` when the upstream is closed afterwards.

- [x] Log each request level problem once at warning level (2026-11-19). The first occurrence in
  the JVM is logged at `WARNING` and names the metric that counts it. Subsequent occurrences
  are logged at `DEBUG`. Uses include downstream, upstream and exception handling, JA4, IP
  traits lookup, and request-log writer failures. Follow-up in Build, tests.

- [x] `sensepitch_edge_server_live` per ingress (2026-11-19): one series with the value 1 and the
  label `ingress` per known host, the hosts `SanitizeHostHandler` accepts (sites and
  `listen.hosts`, without the special hosts), sorted. Ingress identity is the sanitized host, as on
  every ingress metric; summed by `ingress` over all instances it gives the number of servers of an
  ingress. Read from the current `EdgeState` at every scrape, so a reload adds and removes series
  without a restart. No series without known hosts. Incompatible for queries that match the
  former series without labels exactly; aggregate first, e.g. `max without (ingress)`. Covered by
  `MetricsExportTest.serverLivePerIngress`/`serverLiveFollowsReload` and the exposition golden
  files.

- [x] Failed response writes are logged as aborted deliveries (2026-11-18):
  `RequestLoggingHandler` reports 499 and `CLIENT_ABORTED` when the final write fails, counts the
  request as aborted instead of completed, and logs only content written before the failed write.
  The upstream response status, or the upstream status replaced by a fallback page, remains in
  `sensepitch.upstream.status`. Covered by `RequestLoggingHandlerTest` for a full response, a
  streamed body, a fallback and a completed write.

- [x] `Retry-After` as an HTTP date (2026-11-17): `Backoff` converts dates against the current
  wall clock, with an explicit time input for tests. Seconds, the 300 s cap and the minimum
  exponential backoff are preserved; invalid, past and current dates add no delay. See
  [doc/design/circuit-breaker.md](doc/design/circuit-breaker.md).

- [x] Move redundant real server tests to `*IT` (2026-11-17): five OTLP scenarios now run in
  `OtlpLogDestinationIT`, four reload scenarios in `ReloadableRequestLoggerIT`, with the unit
  classes as fixtures. Thirteen individual exclusions and cumulative comparisons retained both
  packing tests, aged-batch expiry and draining-destination finish in the unit suite. Unit line
  and branch coverage is unchanged; the fake clock remains separate work. Measurements and the
  retained cases are in [test speed design](doc/design/test-speed.md#move-only-redundant-server-scenarios).

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
