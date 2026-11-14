# TODO

Open work, grouped by area. Moved here from the README roadmap and from the `TODO`/`FIXME`
comments in the code; an entry from the code names the class and method it is about.

Priority suggestions: P1 = correctness and admission lifetime; P2 = observability and deployment
safety; P3 = deployment-driven features and measured performance work. Confirm priorities against
current production needs. Notes below record decisions to make before implementation.

## Evergreen

- [ ] Improve code according to styling rules
  - Ongoing review principle; record concrete inconsistencies as separate tasks.
- [ ] improve code coverage towards 100%
  - Prioritize uncovered risk and meaningful behavior rather than a percentage alone.
- [ ] Performance test: different Netty allocators, leak detection,
  `-XX:NativeMemoryTracking=summary`
  - P3: compare representative workloads and record throughput, latency and memory before choosing.

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
  - first do low overhead brotli and cache it, recompress with more efficient compression with half of the available CPUs in niced priority, priotising hot resources
- [ ] multiple servers per upstream
  - P3: decide load balancing, retry safety, failover and configuration semantics.
  - One circuit breaker per server, see the `Endpoint` record in `HttpBatchSender`.
- [ ] upstream health and circuit breaker: [`CircuitBreaker`](src/main/java/org/sensepitch/edge/CircuitBreaker.java)
  exists for the request log, it opens on the first failure; for upstreams it needs a failure
  threshold (consecutive failures or a failure rate window, as in Armeria or resilience4j)
  - P3: specify thresholds, recovery probes, per-server versus per-upstream state and retry policy.
  - Keep the backoff, `Retry-After`, single probe and metrics, the request log keeps a threshold
    of one, see [doc/design/circuit-breaker.md](doc/design/circuit-breaker.md).
- [ ] `DownstreamHandler.completeWithError`: the response may have been sent already; drop the
  upstream connection
  - P1: cover failures before and after headers and partial body writes; do not send a second
    response once one has started.
- [ ] `DownstreamHandler.channelInactive`: track and log if the close was unexpected
  - P2: define expected closes and avoid duplicate or noisy logs.
- [ ] `DownstreamHandler.isSharable`: decide on `@Sharable`, the override does nothing
  - Small cleanup: confirm the handler lifecycle and remove the ineffective override if unneeded.

## Circuit breaker

Limitations and the reasoning are in [doc/design/circuit-breaker.md](doc/design/circuit-breaker.md);
the failure threshold for upstreams is under Upstream.

- [ ] `CircuitBreaker.onIgnored`: release the probe. An ignored result of the probe, e.g. a
  status 400, leaves the breaker `HALF_OPEN`, and the next probe waits for the probe timeout
  (11 s with the default request timeout)
  - P2: delays the recovery of the request log only. Decide between releasing the probe and
    closing the breaker, since the server answered.
- [ ] configurable backoff: initial and maximum delay (1 s, 30 s) are constants in
  `OtlpLogDestination`
  - P3: options of a destination, later of an upstream, with the current values as defaults.
- [ ] slow calls count as failures, as resilience4j's slow call rate
  - P3: for upstreams, once latency targets are known.
- [ ] manual control: force a breaker open, e.g. for maintenance of a log server or upstream
  - P3: through the admin server.
- [ ] `Retry-After` as an HTTP date, only seconds are supported
  - P3: small; until then a date is ignored and the backoff applies.

## Ingress, HTTP

- [ ] http2 support
- [ ] quic support
- [ ] http to https forward (there is no plain HTTP listener yet)
  - P3: confirm deployment needs and choose listener, redirect status and host/port behavior.
- [ ] limit body size for incoming requests? There is no limit yet. NGINX has 1M as default,
  `client_max_body_size`, status 413
  - P2: research settings of other tools and present options here first. These settings are always caveats. Basic philosophy is to be transparent, define a buffer for performance and then do backpressure. So no hard limit
- [ ] downstream HTTP/2 support
  - P3: scope protocol negotiation, stream lifecycle, backpressure and compatibility tests.
- [ ] Implement server-timing, https://www.w3.org/TR/server-timing/
  - P3: enable/disable forward of upstream Server-Timing info, log all of it, add own timing info, e.g. upstream connect latency
- [ ] unify timeout with keep alive handler: `IngressTimeoutHandler` only works with
  `HttpKeepAliveHandler` next; corner case when the ingress still sends while the upstream is
  responding (could answer with `Connection: close`)
  - P3: QUIC support is more important
- [ ] `RequestLoggingHandler.write` / `log`: on a write error, switch the response, log a
  different status code, or log content length 0?
  - P2: change status to 499 although if its written already, log bytes delivered, we already log original upstream status somewhere, double check. comment that original upstream status is logged for fallback or aborted writes
- [ ] `SiteSelector`: support wildcard hosts (rejected at config load for now)
  - P3: define precedence, matching boundaries and certificate requirements.
  
## Protection, admission

- [ ] bypass config should be in the protection and not in the protection providers => we need a
  proper API for the protection provider
  - P3: define provider-independent bypass semantics and migration of existing configuration.
- [ ] Integrate https://github.com/cruftex/GoodBots
  - P3: define data source, update cadence and conflict behavior with existing crawler lists.
- [ ] Perplexity, https://docs.perplexity.ai/guides/bots: the user agent is in `crawlers.tsv`,
  the IP ranges are not in `crawler-ips/` yet
  - P3: verify the published source and update strategy before adding ranges.
- [ ] minimise + compress challenge resources
  - P3: measure transfer and CPU costs; decide build-time compression and cache behavior.
- [ ] Verify whether the WebCrypto-only `src/main/resources/challenge.html` is unused; remove it if so.
  - The current challenge JavaScript PoW suite already tests WebCrypto and the JS fallback during
    `./mvnw verify`.
- [ ] restrict admissions per solved challenge
  - P1 candidate: define the replay threat and permitted reuse, whether enforcement is per node
    or cluster-wide, and the state, lifetime and coordination cost.
- [ ] expiry of granted admissions: the token carries its creation time, but only the cookie
  `Max-Age` (30 days) limits it
  - P1: 5 days default maximum, 5s clock skew allowance for distributed servers, expired cookie is set to a another cookie  (with '0' suffix), if previous expired cookie is present reset with the same and add a counter, e.g. `admission0: origtoken-n-signature`. so we encode always the original token and how many revisits we have seen.
- [ ] abnormal admission usage detection
  - P3: Define abnormal behavior, data sources, alerting versus enforcement, and privacy/retention. Needs a stream per admission token
- [ ] Admission performance: skip validation for keep-alive connections
  - P3: benchmark first; define behavior when requests on one connection have different cookies
    or a token expires or configuration reloads.
- [ ] Admission performance: deliver the challenge via cookie and serve static HTML
  (`Deflector.outputChallengeHtml` renders the page per request, only the challenge changes)
  - P3: benchmark and define caching, cookie scope and security semantics.
- [ ] improve bypass matching (maybe ASN, geo)
  - Define which traits grant bypass, rule precedence and behavior for unknown or stale data.
- [ ] `TrieIpLabelLookup`: consolidate into `IpTraitsLookup`
  - P3: clarify the desired API and whether collector composition already covers the use case.
- [ ] naming: the deflector is one component of the protection system, next to cookie gates and
  the bypass. Decide whether to rename the metric `sensepitch_deflector_requests_total` to
  `sensepitch_protection_requests_total` (with a label for the component, once more components
  report outcomes) and "deflector" to "protection" in general, or keep it as is. A metric rename
  breaks dashboards and alerts.
  - P3: decide ownership and migration/deprecation before renaming.
- [ ] explain PoW admission
  - P2: Goes to docs/design. Should cover brief overview and current limitations. Details go into the JavaDoc
  
## Request log

Decisions, alternatives and limitations are in
[doc/design/request-log.md](doc/design/request-log.md); the configurable backoff is under Circuit
breaker.

- [ ] shutdown report: "request log not drained after N ms" prints the configured drain budget,
  not the elapsed time, and a loop that did not terminate (e.g. a stuck handler) shows only as
  unsent records (`Proxy.shutdownOnce`)
  - P2: small; print the elapsed time of the phase and name the loops still running.
- [ ] post latency: a histogram of the OTLP post duration per destination; today a slow log server
  shows only once requests time out and the breaker opens
  - P2: choose buckets around the request timeout (default 10 s); record in
    `HttpBatchSender.complete`, cached data point per destination.
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

## Metrics, logging

- [ ] failure counters: count the request outcomes in a metric, e.g. `UPSTREAM_TIMEOUT`,
  `RESPONSE_WRITE_TIMEOUT` (`IngressTimeoutHandler`) and `UPSTREAM_WRITE_FAILED`
  (`DownstreamHandler.channelRead`); today they are in the request log only
  - P2: choose outcomes, labels and cardinality; use the existing request outcome definitions.
- [ ] `ProxyMetrics`: one `sensepitch_edge_server_live` per ingress, to drill down per ingress
  and know how many servers it has
  - P3: define ingress identity and expected behavior for multiple servers and reloads.

## Kubernetes

The plan is in [doc/kubernetes-support/](doc/kubernetes-support/), the gaps in
`3-gap-analysis.md`. Items 1-4 run today's `config.yml` edge on Kubernetes without a controller;
5-6 are also part of M1.

- [ ] 1. `/healthz` and `/readyz` on the admin port; `/readyz` answers 503 from the start of the
  shutdown, before the listener closes
  - P1: small. Replaces the `preStop` sleep hint in the README with a signal the load balancer
    sees; decide whether readiness also waits for the first successful config load.
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
- [ ] 5. plain HTTP listener and several listeners (`ListenConfig` has one HTTPS port)
  - P2: needed for HTTP to HTTPS redirects, cert-manager HTTP-01 and Gateway listeners; define
    the listener model with the Gateway API records to avoid doing it twice.
- [ ] 6. PROXY protocol v1/v2 per listener and `X-Forwarded-For`/`-Proto` only from trusted
  proxy CIDRs
  - P2: behind a cloud load balancer the client IP is lost today, and the deflector and IP traits
    depend on it.
- [ ] 7. M1 path, see `6-phases.md` "Development order": Gateway API Java records and snapshot
  schema, then the `config.yml` converter run against the production configurations, then the
  Go controller
  - P2: start once 1-4 are done; the converter shows early whether the model covers production.

## Configuration, operations

- [ ] default configs / layer configuration / overwrite config tree
  - P3: Define source precedence, map/list merge rules, deletion and reload semantics.
- [ ] warmup before ready
  - P3: makes sense after cache is implemented, reload a previous cache content or define some URLs to spider through
  [ ] documentation, nice examples are: Envoy
  - Choose audience and deliverables: operator setup, reference configuration or architecture.

## Build, tests

- [ ] google-java-format 2, https://github.com/google/google-java-format/releases#release-v2.0.0
  (stuck at 1.36.1: Spotless cannot load 1.37+, 2.x changes the lambda layout). Impact: one-time
  reformat of about 40 files / 930 lines, lambda bodies hug the call (`.forEach(t -> {`) and indent
  less. P3: verify formatter/tool compatibility first. Done in its own commit, `formatJavadoc`
  stays off so the `///` comments remain untouched.
- [ ] Serenity: use it properly or remove it. No `@Step` is recorded (4.3.4 and 5.4.2, also not with
  `@Steps` injection), so it produces no useful report. Its cost: `SerenityTestExecutionListener`
  runs for every test, needs the byte-buddy agent (`-javaagent` copy step) and
  `net.bytebuddy.nexus.disabled` to avoid a `sun.misc.Unsafe` warning, 5.x writes ~1000 report
  files (4 MB) to `target/serenity-reports`, and `./mvnw clean verify` takes ~38 s instead of
  ~29.5 s without it (+30 %). Removing it touches 7 test classes (`@ExtendWith`, ~21 `@Step`).
  - P2: choose whether useful reports justify the runtime and maintenance cost.
- [ ] Flaky build: one of six `./mvnw clean verify` runs failed (2026-11-13, after the graceful
  shutdown), the failing test is unknown, the log was not kept. Suspects are the timing based
  tests: `ProxyShutdownIT` (duration bounds), `ShadedJarIT.sigtermSendsTheRequestLogAndExits`
  (exit within 4 s), `OtlpLogDestinationTest` and `ReloadableRequestLoggerTest` (awaits). Next
  failure: keep `target/surefire-reports` and `target/failsafe-reports`.
  - P2: preserve both report directories in CI before attempting a speculative fix.

## Done

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
