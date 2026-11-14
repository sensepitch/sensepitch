# TODO

Open work, grouped by area. Moved here from the README roadmap and from the `TODO`/`FIXME`
comments in the code; an entry from the code names the class and method it is about.

## Evergreen

- [ ] Improve code according to styling rules
- [ ] improve code coverage towards 100%
- [ ] Performance test: different Netty allocators, leak detection,
  `-XX:NativeMemoryTracking=summary`

## Upstream

- [ ] upstream connection pool with keep-alive (an earlier version was disabled 2025-09-20 and
  removed, see git history); then expose pool metrics via Prometheus
- [ ] multiple servers per upstream
- [ ] upstream health and circuit breaker: [`CircuitBreaker`](src/main/java/org/sensepitch/edge/CircuitBreaker.java)
  exists for the request log, it opens on the first failure; for upstreams it needs a failure
  threshold (consecutive failures or a failure rate window, as in Armeria or resilience4j)
- [ ] `DownstreamHandler.completeWithError`: the response may have been sent already; drop the
  upstream connection
- [ ] `DownstreamHandler.channelInactive`: track and log if the close was unexpected
- [ ] `DownstreamHandler.write`: sanitize response headers. `Keep-Alive` and `Connection` are
  removed; the other hop-by-hop headers (`Proxy-Connection`, `TE`, `Trailer`, `Upgrade`, those
  named in `Connection`) are not, see `doc/kubernetes-support/3-gap-analysis.md`
- [ ] `DownstreamHandler.isSharable`: decide on `@Sharable`, the override does nothing

## Ingress, HTTP

- [ ] http to https forward (there is no plain HTTP listener yet)
- [ ] limit body size for incoming requests? There is no limit yet. NGINX has 1M as default,
  `client_max_body_size`, status 413
- [ ] downstream HTTP/2 support
- [ ] Implement server-timing, https://www.w3.org/TR/server-timing/
- [ ] unify timeout with keep alive handler: `IngressTimeoutHandler` only works with
  `HttpKeepAliveHandler` next; corner case when the ingress still sends while the upstream is
  responding (could answer with `Connection: close`)
- [ ] `IngressTimeoutHandler` response timeout: it writes a 504 *and* fires
  `UpstreamResponseTimeoutException`, decide which one
- [ ] `ExceptionHandler`: a timeout gets a 502 like any other error, should it get its own status?
- [ ] `RequestLoggingHandler.write` / `log`: on a write error, switch the response, log a
  different status code, or log content length 0?
- [ ] `SiteSelector`: support wildcard hosts (rejected at config load for now)

## Protection, admission

- [ ] bypass config should be in the protection and not in the protection providers => we need a
  proper API for the protection provider
- [ ] block requests that are not GET and POST if not admitted or crawler bypassed;
  `DeflectorHandler` answers any other method with the challenge page
- [ ] Integrate https://github.com/cruftex/GoodBots
- [ ] Perplexity, https://docs.perplexity.ai/guides/bots: the user agent is in `crawlers.tsv`,
  the IP ranges are not in `crawler-ips/` yet
- [ ] minimise + compress challenge resources
- [ ] test the SHA-256 PoW of `challenge/files/script.js` (WebCrypto with a JS fallback); remove
  the unused WebCrypto-only `src/main/resources/challenge.html`
- [ ] restrict admissions per solved challenge
- [ ] expiry of granted admissions: the token carries its creation time, but only the cookie
  `Max-Age` (30 days) limits it
- [ ] abnormal admission usage detection
- [ ] Admission performance: skip validation for keep-alive connections
- [ ] Admission performance: deliver the challenge via cookie and serve static HTML
  (`Deflector.outputChallengeHtml` renders the page per request, only the challenge changes)
- [ ] Admission performance: `DefaultAdmissionTokenGenerator` encodes the computed checksum to
  compare it, decoding the received one and comparing bytes is cheaper
- [ ] improve bypass matching (maybe ASN, geo)
- [ ] `TrieIpLabelLookup`: consolidate into `IpTraitsLookup`
- [ ] naming: the deflector is one component of the protection system, next to cookie gates and
  the bypass. Decide whether to rename the metric `sensepitch_deflector_requests_total` to
  `sensepitch_protection_requests_total` (with a label for the component, once more components
  report outcomes) and "deflector" to "protection" in general, or keep it as is. A metric rename
  breaks dashboards and alerts.
- [ ] explain PoW admission

## Metrics, logging

- [ ] failure counters: count the request outcomes in a metric, e.g. `UPSTREAM_TIMEOUT`,
  `RESPONSE_WRITE_TIMEOUT` (`IngressTimeoutHandler`) and `UPSTREAM_WRITE_FAILED`
  (`DownstreamHandler.channelRead`); today they are in the request log only
- [ ] `ProxyMetrics`: one `sensepitch_edge_server_live` per ingress, to drill down per ingress
  and know how many servers it has

## Configuration, operations

- [ ] default configs / layer configuration / overwrite config tree
- [ ] warmup
- [ ] documentation, nice examples are: Envoy

## Build, tests

- [ ] google-java-format 2, https://github.com/google/google-java-format/releases#release-v2.0.0
  (stuck at 1.36.1: Spotless cannot load 1.37+, 2.x changes the lambda layout). Impact: one-time
  reformat of about 40 files / 930 lines, lambda bodies hug the call (`.forEach(t -> {`) and indent
  less. Done in its own commit, `formatJavadoc` stays off so the `///` comments remain untouched.
- [ ] Serenity: use it properly or remove it. No `@Step` is recorded (4.3.4 and 5.4.2, also not with
  `@Steps` injection), so it produces no useful report. Its cost: `SerenityTestExecutionListener`
  runs for every test, needs the byte-buddy agent (`-javaagent` copy step) and
  `net.bytebuddy.nexus.disabled` to avoid a `sun.misc.Unsafe` warning, 5.x writes ~1000 report
  files (4 MB) to `target/serenity-reports`, and `./mvnw clean verify` takes ~38 s instead of
  ~29.5 s without it (+30 %). Removing it touches 7 test classes (`@ExtendWith`, ~21 `@Step`).
- [ ] Flaky build: one of six `./mvnw clean verify` runs failed (2026-11-13, after the graceful
  shutdown), the failing test is unknown, the log was not kept. Suspects are the timing based
  tests: `ProxyShutdownIT` (duration bounds), `ShadedJarIT.sigtermSendsTheRequestLogAndExits`
  (exit within 4 s), `OtlpLogDestinationTest` and `ReloadableRequestLoggerTest` (awaits). Next
  failure: keep `target/surefire-reports` and `target/failsafe-reports`.

## Done

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
