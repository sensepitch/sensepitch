
# Sensepitch Edge

[![CI](https://github.com/sensepitch/sensepitch/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/sensepitch/sensepitch/actions/workflows/ci.yml)
[![Nightly](https://github.com/sensepitch/sensepitch/actions/workflows/nightly.yml/badge.svg)](https://github.com/sensepitch/sensepitch/actions/workflows/nightly.yml)
[![Line coverage](https://raw.githubusercontent.com/sensepitch/sensepitch/badges/badges/jacoco.svg)](https://github.com/sensepitch/sensepitch/actions/workflows/ci.yml?query=branch%3Amain)
[![Branch coverage](https://raw.githubusercontent.com/sensepitch/sensepitch/badges/badges/branches.svg)](https://github.com/sensepitch/sensepitch/actions/workflows/ci.yml?query=branch%3Amain)

Sensepitch Edge is a reverse proxy that improves the availability of web apps by absorbing malicious or excessive traffic, ensuring continuous, low‑latency service.

## Goals

- Ingress / reverse proxy for public facing web applications
- Replacement for other ingress controllers or reverse proxies like NGINX, Openresty, Caddy, Traefik
- Protection from malicious robots, DoS attacks or scanners
- Detect and don't block legitimate crawlers
- Make CAPTCHAs or other protection mechanisms within the applications redundant
- Improve site delivery performance, e.g. by SSL offloading, optimized HTTP protocol handling, caching, compression
- "Waiting room": Ensure healthy applications and consistent UX for users in high load situations and provide a sensible message to users that cannot be served at the moment 
- high concurrent and low latency operation, performance level similar to proven high RPS server like NGINX
- Sensible defaults for good OOTB experience

## Non Goals

- API gateway capabilities, like access control, rate limiting and accounting.
- Security enforcement

## Architecture

Sensepitch Edge is based on Netty, the Java VM and Boring SSL.

Netty provides a solid HTTP core implementation for more than a decade. On the other hand, Java was not a good choice for building edge servers. The problem is high startup times and unpredictable, stop the world, garbage collection pauses of one second or more. The GC pauses lead to response time spikes but also intermittent failures because of resource overflows when unprocessed requests are queued.

In 2025 the JDK’s ZGC virtually eliminates stop‑the‑world pauses (<2ms typical). The startup of Sensepitch Edge is already fast
because of its minimal dependencies. Additional techniques are available to reduce startup time, like GraalVM, s can be reduced more. Another approach to reduce startup times 
is the OpenJDK project (called project Leyden).

Traditionally, the JVM was avoided at the network edge because of long start‑up times and unpredictable “stop‑the‑world” garbage‑collection (GC) pauses. In 2025, however, the JDK's ZGC eliminates stop-the-world pauses. Because of the small footprint Sensepitch Edge already starts quickly. Adding additional technologies like class‑data sharing, GraalVM native images or the forthcoming OpenJDK **Project Leyden** can cut start‑up further.

## Performance

As we are still in the prototyping / proof of concept stage our main goal is to evaluate whether the chosen architecture is competitive in general. To do this, we compare it with a NGINX reverse proxy which is known to be highly optimized. 

- Hardware: Notebook with 8 core, AMD Ryzen AI 7 PRO 360 w/ Radeon 880M
- NGINX Version 1.29
- openjdk version "24.0.1" 2025-04-15
- Load generator: vegeta v12.12.0

The complete setup can be found under `performance-test`. The NGINX setup enables keep alive for upstream connection. However, using keep alive connections is a usual practice and the default for Sensepitch Edge.

The test is run on the developers' notebook with load generator, proxy and target webserver running on the same machine. While this is not ideal since it is not comparable to a production scenario, it is enough for our current purpose. 

For testing we use OpenJDK 24 and switch to ZGC for minimal pause times:

    java -XX:+UseZGC --enable-native-access=ALL-UNNAMED -jar target/sensepitch-edge-*-with-dependencies.jar

On Java 25, `--enable-native-access=ALL-UNNAMED` lets Netty 4.2.17 use the foreign linker to call
libc's `malloc` and `free` directly. These allocations bypass both JVM direct buffer accounting and
native memory tracking. Use Netty's allocator metrics and process RSS to monitor them, see
[Exposed metrics](#exposed-metrics). The shaded JAR sets `Enable-Native-Access: ALL-UNNAMED` in its
manifest, so `java -jar` enables native access also without the flag.

For load testing we use vegeta:

    echo "GET $PROXY_URL/10kb.img" | vegeta attack -insecure -duration=10s -timeout=10s -rate=50000 -keepalive=true | vegeta report

Multiple runs were made and the first one discarded, to allow for warmup and keep alive connections to establish. The target rate of 50000 is above the achievable throughput on the test hardware to cause saturation.

The NGINX result is:

````
Requests      [total, rate, throughput]         433414, 43341.57, 43294.73
Duration      [total, attack, wait]             10.011s, 10s, 10.819ms
Latencies     [min, mean, 50, 90, 95, 99, max]  105.118µs, 3.762ms, 1.578ms, 7.005ms, 13.414ms, 41.816ms, 241.305ms
Bytes In      [total, mean]                     4438159360, 10240.00
Bytes Out     [total, mean]                     0, 0.00
Success       [ratio]                           100.00%
Status Codes  [code:count]                      200:433414  
Error Set:
````

The result for Sensepitch Edge is:

````
Requests      [total, rate, throughput]         328821, 32882.64, 32780.81
Duration      [total, attack, wait]             10.031s, 10s, 31.065ms
Latencies     [min, mean, 50, 90, 95, 99, max]  223.941µs, 44.464ms, 28.368ms, 105.952ms, 143.831ms, 231.852ms, 540.79ms
Bytes In      [total, mean]                     3367127040, 10240.00
Bytes Out     [total, mean]                     0, 0.00
Success       [ratio]                           100.00%
Status Codes  [code:count]                      200:328821  
Error Set:
````

The resulting throughput for Sensepitch Edge is around 75% of the NGINX based reverse proxy. This can be considered "good enough" already. I did not do further profiling. One known bottleneck is the output of the access log requests to `System.out`, which causes lock contention. Noteworthy is the 10 times higher mean latency. I assume a probable cause is the fact that downstream and upstream operations run in two different threads.

Lets have a second look with a non saturating request rate of 10K:

    echo "GET $PROXY_URL/10kb.img" | vegeta attack -insecure -duration=10s -timeout=10s -rate=10000 -keepalive=true | vegeta report

The NGINX result is:

````
Requests      [total, rate, throughput]         100000, 9999.98, 9999.36
Duration      [total, attack, wait]             10.001s, 10s, 615.394µs
Latencies     [min, mean, 50, 90, 95, 99, max]  92.455µs, 805.108µs, 370.83µs, 1.287ms, 1.721ms, 6.579ms, 105.089ms
Bytes In      [total, mean]                     1024000000, 10240.00
Bytes Out     [total, mean]                     0, 0.00
Success       [ratio]                           100.00%
Status Codes  [code:count]                      200:100000  
Error Set:
````

The result for Sensepitch Edge is:

````
Requests      [total, rate, throughput]         100000, 10000.09, 9999.85
Duration      [total, attack, wait]             10s, 10s, 241.637µs
Latencies     [min, mean, 50, 90, 95, 99, max]  135.366µs, 1.767ms, 443.975µs, 2.574ms, 3.799ms, 37.192ms, 174.176ms
Bytes In      [total, mean]                     1024000000, 10240.00
Bytes Out     [total, mean]                     0, 0.00
Success       [ratio]                           100.00%
Status Codes  [code:count]                      200:100000  
Error Set:
````

In the unsaturated scenario, which will be the normal mode of operation, the latency is not significantly higher than with NGINX.

## Proof of concept - phase 1

- Analyze and validate performance Netty based proxy approach
- Validate simple bypass / legitimate crawler detection capabilities
- Validate that PoW challenge is answered by user browsers
- Production experience, monitoring, possible protocol errors, etc.
- Collect legitimate clients that are not real browsers

## Roadmap / TODOs

See [TODO.md](TODO.md).

## Resources

### Netty

- [Netty, the IO framework that propels them all By Stephane LANDELLE](https://www.youtube.com/watch?v=NvnOg6g4114)

### HTTP 1.1 Connection Management with Keep Alive

Robust keep alive is a bit different from what the HTTP/1.1 standard defines.

A connection stays open after a response when the client asked for keep-alive and the response
length is known; the response then carries `Keep-Alive: timeout=<readTimeoutSeconds>`. If the
response starts before the client has sent the complete request body, e.g. an early `413` of the
upstream, it carries `Connection: close`, and the connection is closed once the rest of the body
arrived or after the read timeout. The rules are in the doc of `IngressTimeoutHandler`.

- https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Status/408
- https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Keep-Alive
- https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/Connection_management_in_HTTP_1.x

## Exposed metrics

Metrics are exposed via prometheus at `http://<host>:9400/metrics`, the port is set with
`metrics.prometheus.port`, `metrics.enable: false` switches the export off. This section picks out
metrics that should be monitored.

### `process_resident_memory_bytes`

Total memory consumption of the process as reported by the OS. This is the metric to watch for memory
growth: it covers every allocation whichever way it was made, and it is what the container limit and
the OOM killer act on.

Do not use `process_virtual_memory_bytes` for that. With ZGC the JVM reserves about 252 GiB of
address space at startup, measured against 306 MB resident, so a 100 MiB leak would be a 0.04 percent
change. It is also not monotonic with usage: measured with G1, a 100 MiB allocation moved virtual
memory by 445 MB, and freeing it again moved virtual memory *up* by a further 31 MB while resident
memory fell by 105 MB. It is useful only as a secondary signal, for memory that is reserved and never
touched.

### `jvm_memory_used_bytes{area="heap"}` and `jvm_memory_used_bytes{area="nonheap"}` 

Java heap, which is used for allocating objects and nonheap which is used for JVM internals.
The `nonheap` does not include direct buffers.

### `jvm_buffer_pool_used_bytes{pool="direct"}`

Direct byte buffers as accounted by `java.nio`, including `ByteBuffer.allocateDirect`. With Java 25,
Netty 4.2.17 and native access enabled, Netty's unpooled, pooled and adaptive allocators use libc
directly and do not appear here. Without native access, unpooled buffers fall back to
`ByteBuffer.allocateDirect` and appear here, while pooling allocators can use an Arena that remains
outside this accounting. Use Netty's own metrics for consistent allocator coverage.

### `sensepitch_netty_allocator_used_memory_bytes`

Memory usage of Netty allocators, labelled by `allocator` (`pooled`, `unpooled`, `default`) and `type`
(`direct`, `heap`). Always available. Reports how much memory is in use by the application, not how much
is reserved by the allocator.

`default` is the allocator both the ingress and the upstream connections use, so it is the one to
watch. `pooled` should stay at zero. `unpooled` is the static content cached at startup, a constant.
Only `Unpooled.buffer()` and `Unpooled.directBuffer()` are counted; `copiedBuffer` and
`wrappedBuffer` bypass the allocator and land in the heap metrics instead.

### `jvm_native_memory_committed_bytes{pool="Other"}`

HotSpot's accounting of miscellaneous native allocations, including `java.lang.foreign.Arena`
memory. This does **not** cover Netty's direct libc allocations when native access is enabled.
Needs `-XX:NativeMemoryTracking=summary` at startup, which costs 5 to 10 percent JVM performance
and is a HotSpot feature, so it is off by default and the series are absent without it.

### Tracking down memory growth

1. `process_resident_memory_bytes` rising steadily is the alert.
2. `jvm_memory_used_bytes{area="heap"}` separates a heap problem from an off heap one.
3. `sensepitch_netty_allocator_used_memory_bytes` says which allocator holds it.
4. `jvm_native_memory_committed_bytes{pool="Other"}` helps investigate native allocations tracked
   by HotSpot, but excludes Netty's direct libc allocations with native access enabled.
5. Netty's JFR events give per allocation detail. `io.netty.AllocateChunk`, `io.netty.FreeChunk`,
   `io.netty.AllocateBuffer` and `io.netty.FreeBuffer` carry the allocator type, the size, whether
   the memory is direct, and the address, so allocations and frees can be paired up. Record with
   `jcmd <pid> JFR.start settings=<profile>` using Netty's `Netty Allocator Events.jfc`; the events
   are off by default because they are expensive. See
   [Analyzing Memory Allocator Behavior](https://github.com/netty/netty/wiki/Analyzing-Memory-Allocator-Behavior),
   which also describes `AllocationPatternSimulator` for comparing the pooled and the adaptive
   allocator on a recorded profile.

Leaked buffers are reported separately. Netty's leak detector runs at level `SIMPLE` by default and
logs a buffer that is garbage collected without having been released; raise it with
`-Dio.netty.leakDetection.level=advanced` or `paranoid`. Detection is sampled, so a report means a
real leak while silence proves nothing.

### `sensepitch_deflector_requests_total{ingress="...",outcome="..."}`

Requests handled by the deflector protection, by `ingress` (the requested host, like in the other
`sensepitch_ingress_*` metrics) and `outcome`:

- `passed`: valid admission cookie, the request goes to the upstream
- `bypassed`: no admission cookie, but a configured bypass or a detected crawler lets it through
- `method_rejected`: method other than GET without admission or bypass; empty 403 response
- `challenge_sent`: the challenge page was sent
- `answer_accepted`: correct challenge answer, the admission cookie was issued
- `answer_rejected`: wrong or incomplete answer

A high ratio of `challenge_sent` to `answer_accepted` is normal under bot traffic. Many
`answer_rejected` hint at clients trying to forge answers. Requests for the challenge's scripts and
styles are not counted.

### `sensepitch_ingress_request_outcomes_total{ingress="...",outcome="..."}`

Every request, counted when it is logged, by `ingress` (the requested host) and `outcome`, the
value of `sensepitch.request.outcome` in the request log, see [Request outcome](#request-outcome).
The counts match the request log: one per record. An outcome appears once it happened for the
ingress.

The share of failed requests is the rate of all outcomes except `completed`, `edge_response` and
`fallback`, divided by the rate of all outcomes. `upstream_*` points to the upstream,
`request_timeout`, `response_write_timeout` and `client_aborted` to the client.

### `sensepitch_ingress_request_flavor_count_total{flavor="...",country="..."}`

Requests by traffic type and client country. `flavor` is the value of the `Sp-Traffic-Type`
header: `human`, `bot` or `unknown`, also when no protection handled the request. Before the
`Sp-*` headers, the values were `user`, `crawler`, `deflect` and `unknown`.

### Request level errors

A failure that can happen per request or connection is never logged as an error, an outage or an
attack would flood the log. It is counted, and logged at debug level with the details:

- `sensepitch_ingress_request_errors_total{ingress,phase,type}`: exceptions on the client
  connection. `type="other"` is an unexpected exception, possibly a bug, worth an alert; `ja4` a
  failed TLS fingerprint.
- `sensepitch_upstream_errors_total{upstream,type}`: an upstream that closed during a request
  (`closed_in_flight`), sent data after the response (`unexpected_data`) or failed after it
  (`exception_without_request`).
- `sensepitch_request_log_errors_total`: request log records that could not be written.
- `sensepitch_ip_lookup_errors_total`: failed lookups in an IP database, e.g. a corrupt file.

To see the details, enable debug for the package `org.sensepitch.edge` in the Log4j2
configuration.

### `sensepitch_request_log_dropped_total{destination="..."}`

Request log records dropped because a destination could not keep up, by `destination`: `stdout`,
or the name of an OTLP destination. The request log is written by a thread of its own per
destination; when its queue (stdout) or a thread buffer (OTLP) is full, records are dropped
instead of stalling traffic. `sensepitch_request_log_queued{destination="..."}` shows the records
waiting to be written.

### `sensepitch_request_log_export_failed_total{destination="..."}`

Batches an OTLP destination gave up, because the log server rejected them in a way retrying
cannot fix, e.g. status 400. Their records are dropped. The first failure is also logged as an
error.

### `sensepitch_request_log_export_retries_total{destination="..."}` and `sensepitch_request_log_buffered_bytes{destination="..."}`

Failed attempts that are retried, e.g. while the log server is restarted, and the bytes of the
batches waiting to be sent. A rising `buffered_bytes` shows an outage being bridged; when it nears
`otlp.maxBufferedBytes`, records will be dropped.

### `sensepitch_request_log_sent_records_total{destination="..."}` and `sensepitch_request_log_sent_bytes_total{destination="..."}`

Records an OTLP destination sent successfully, and the bytes of their requests as on the wire,
i.e. compressed. Together with `dropped_total` this gives the share of records lost.

### `sensepitch_request_log_post_duration_seconds{destination="..."}`

Histogram of the duration of the requests an OTLP destination posts, from sending the request to
its response or failure, whatever the outcome. A slow log server shows here before its requests
time out (`otlp.timeoutMillis`, default 10 s) and the circuit breaker opens. The time a batch
waits before it is sent and the compression are not included.

### Circuit breaker metrics: `sensepitch_circuitbreaker_*{component="...",name="..."}`

Every remote the proxy protects with a circuit breaker reports the same metrics, with two labels:
`component`, what the breaker protects, and `name`, the instance. An OTLP destination's breaker
has `component="requestLog"` and the destination name as `name`, e.g. `name="otlp"` or
`name="victorialogs"`, the same value as the `destination` label of the request log metrics. The
basic names follow the resilience4j Micrometer binding, so its dashboards carry over with a prefix
rename.

| Metric | Type | Meaning |
|---|---|---|
| `_state{state}` | gauge | 1 for the current state, `closed`, `open` or `half_open`, 0 for the others |
| `_calls_total{kind}` | counter | each call once: `successful`, `failed` (counts against the breaker, e.g. 503), `ignored` (a failure retrying cannot fix, e.g. 400), `not_permitted` (refused by the breaker) |
| `_transitions_total{from,to,reason}` | counter | each state change; the reason of a trip is the error code, otherwise `backoff_over` or `recovered` |
| `_errors_total{error}` | counter | failed and ignored calls by error code: `http_<status>`, `timeout`, `connect`, `io`, `unexpected` |
| `_consecutive_failures` | gauge | failures in a row, the backoff grows with them |
| `_start_time_seconds` | gauge | when the breaker was created, e.g. at startup or by a reload |
| `_last_trip_time_seconds` | gauge | when it last opened after being closed, 0 if never |
| `_last_restart_time_seconds` | gauge | when it last closed again after a trip, 0 if never |

The breaker counts consecutive failures with an exponential backoff, it has no sliding window, so
there is no failure rate. The gauges of a breaker disappear when its destination is removed, the
counters keep their values.

````promql
sensepitch_circuitbreaker_state{state="open"} == 1                     # open breakers
# dropped records of the destinations whose breaker is open
sensepitch_request_log_dropped_total and on (destination)
  label_replace(sensepitch_circuitbreaker_state{component="requestLog",state="open"} == 1,
                "destination", "$1", "name", "(.*)")
increase(sensepitch_circuitbreaker_transitions_total{to="open"}[1h])   # trips per hour
topk(1, increase(sensepitch_circuitbreaker_errors_total[15m]))         # the current error
````

## Put Sensepitch Edge in front of NGINX

Assuming your existing inbound reverse proxy is NGINX, here is an example how to augment
your NGINX configuration.

## Admission configuration

### Bypass for webhook calls

Example Stipe
- post uri: POST /module/stripe_official/webhook
- user agent: Stripe/1.0 (+https://stripe.com/docs/webhooks)
- IP: 54.187.174.169

Example Paypal
- post uri: POST /module/paypal/webhookhandler
- user agent: PayPal/AUHR-1.0-1
- IP: 173.0.81.140

Without admission cookie or bypass, the deflector answers only GET with the challenge page. Any
other method, e.g. a webhook POST, gets an empty 403 and counts as `method_rejected`, so webhooks
need a bypass.


## Headers sent to the upstream

The edge adds these request headers for the upstream. They all start with `Sp-`, short like
Cloudflare's `Cf-*`. Any incoming header starting with `Sp-` (case insensitive) is removed first, so
a client cannot pretend to be a crawler or human traffic.

| Header | Content |
|---|---|
| `Sp-Traffic-Type` | `human`: valid admission cookie or accepted challenge answer; `bot`: detected crawler; `unknown`: a configured bypass. Absent if no protection handled the request |
| `Sp-Ingress-Pattern` | the configured wildcard when the host matched one |
| `Sp-Admission-Token` | the validated admission token |
| `Sp-Protection-Bypass` | reason the protection was bypassed, see `sensepitch.protection.bypass` |
| `Sp-Ip-Traits`, `Sp-Country`, `Sp-Asn` | traits of the client address, if the IP lookup knows it |
| `Sp-Ja4`, `Sp-Ja4-Raw` | JA4 TLS fingerprint, if enabled, the raw one only if configured |

`Sp-Ip-Traits` is a comma-and-space-separated list of `key=value` pairs. Known built-in traits
appear first in the order `asn`, `country`, `crawler`; unknown traits are omitted. Custom labels
provided by an IP traits collector follow in key order, for example
`asn=64496, country=DE, network=hosting, risk=low`. This adds custom labels to the header, so
upstreams that parse it should accept additional keys. When a client IP is available but no traits
are known, the header is empty. `Sp-Country` and `Sp-Asn` carry the corresponding built-in
values separately when known.

Custom labels are added through `IpTraits.Builder.addLabel`. Keys `asn`, `country` and `crawler`
are reserved. Keys and values must be nonempty visible ASCII without whitespace, commas or `=`;
a later label with the same key replaces the earlier value. Crawler IP bypass requires the
built-in `crawler` trait; mentioning `crawler` in a custom label does not trigger it.

Before, these headers were named `X-Sensepitch-Ja4`, `X-Sensepitch-Ja4-Raw`,
`X-Sensepitch-Ip-Traits`, `X-Sensepitch-Country`, `X-Sensepitch-Asn`,
`X-Senseptich-Admission-Bypass` and `sensepitch-token`. `Sp-Traffic-Type` replaces
`sensepitch-flavor` (`user`, `crawler`, `deflect`).

## Fallback / maintenance pages

If the upstream returns `503` or `500`, or the streamed response head arrives with one of those
statuses, Sensepitch Edge can serve a page or redirect instead of forwarding the upstream error.
This is configured under `fallback:`, either globally or per site:

````yaml
fallback:
  unavailableResponse:
    file: /etc/sensepitch/unavailable.html
    contentType: text/html; charset=UTF-8
  errorResponse:
    text: "Something is broken, please try again later"
sites:
  www.example.com:
  www.example.de:
    fallback:
      unavailableResponse:
        text: "Kaputt, versuch es später nochmal."
````

See `FallbackConfig` and `ResponseConfig` for the slot semantics, the page/redirect shape, and how
global and per-site config is layered.

## Upstream routing

A site routes to its `upstream` by default. Under `paths:`, other upstreams can be selected by
request path prefix or exact path; an exact path wins, otherwise the longest matching prefix wins,
regardless of configuration order.
`overrides:` replace the whole routing (`upstream` and `paths`) when a cookie with the given name
is present, e.g. to switch on a complete beta setup. An override needs its own `upstream`;
the site's `upstream` and `paths` are not inherited. If several override cookies are present, the
first override in the list wins.

Instead of an `upstream`, a path can have a fixed `response`, e.g. to block a path with 404. The
site's protection still applies:

````yaml
sites:
  www.example.com:
    upstream: { target: shop:8080 }
    paths:
      /bo/*:
        response:
          status: 404
          text: Not Found
````

A page `response` can also serve a `file` instead of `text`. Its `contentType` defaults to
`text/html; charset=UTF-8`. A file location with `classpath:` or `file:` uses exactly that source;
a location without scheme is looked up on the filesystem first (relative paths against the working
directory, so better use absolute paths when running as a service), then on the classpath. The
file is read at startup and on each configuration reload, a missing file is a configuration error. The same applies to `file`
in fallback pages. A configured response is sent as is, also with status 500 or 503; it is not
replaced by the fallback page.

````yaml
sites:
  www.example.com:
    upstream: { target: shop:8080 }
    paths:
      /maintenance:
        response:
          status: 503
          file: /etc/sensepitch/maintenance.html
````

Paths ending with `*` are prefixes: `/bo/*` does not match `/bo` itself, `/bo*` matches `/bo` but
also `/books`. Paths without `*` match exactly, ignoring the query string: `/bo` matches `/bo` and
`/bo?x=1`, but not `/bo/` or `/books`. To cover `/bo` and everything below, configure both `/bo`
and `/bo/*`.

A `response` can set `cookies`, e.g. to switch an override on and off without an upstream:

````yaml
sites:
  www.example.com:
    upstream: { target: shop:8080 }
    paths:
      /beta/on/*:
        response:
          location: /
          cookies:
            - name: beta
              maxAge: 2592000
      /beta/off/*:
        response:
          location: /
          cookies:
            - name: beta
              delete: true
    overrides:
      - whenCookie: beta
        upstream: { target: shop-beta:8080 }
````

Cookie attributes: `name` (mandatory), `value` (default `1`), `maxAge` in seconds (default: session
cookie), `delete` (sends `Max-Age=0`), `path` (default `/`), `domain` (default: host-only) and
`sameSite` (`Strict`, `Lax` or `None`, default `Lax`). Cookies are always `Secure` and `HttpOnly`.

Upstreams can be defined globally under `upstreams:` and referenced by name with `ref:`.

````yaml
upstreams:
  api:
    target: api:8080
  api-beta:
    target: api-beta:8080
sites:
  www.example.com:
    upstream:
      target: shop:8080
    paths:
      /api/*:
        upstream: { ref: api }
      /api/v2/*:
        upstream: { target: api-v2:8080 }
    overrides:
      - whenCookie: beta
        upstream:
          target: shop-beta:8080
        paths:
          /api/*:
            upstream: { ref: api-beta }
````

| Request | Upstream |
|---|---|
| `/products` | `shop` |
| `/api/x` | `api` |
| `/api/v2/x` | `api-v2` |
| `/products` with cookie `beta` | `shop-beta` |
| `/api/v2/x` with cookie `beta` | `api-beta` |

### Several sites for one host

Quote wildcard hosts in YAML, for example `"*.example.com"`. The wildcard matches one DNS
label, so it serves `shop.example.com` and excludes both `example.com` and `a.b.example.com`.
An exact host takes precedence across all paths. Upstreams receive the normalized concrete
`Host` header. For a wildcard match, the edge sets `Sp-Ingress-Pattern` to the configured pattern
and uses it for the ingress metric label.

Supply a certificate whose subject alternative names cover the requested hosts. Set
`listen.ssl` for a shared certificate or use `listen.snis` with the wildcard as the host
to select its certificate. An exact SNI entry takes precedence. With `listen.letsEncrypt`,
`*.example.com` reads `privkey.pem` and `fullchain.pem` from the `example.com` directory
under `letsEncryptPrefix`. Provision the wildcard certificate there before loading the config.

The site key is the host, unless the site sets `host`. Several sites can share a host and split
it by `uri` prefix, e.g. to give a part of the host its own protection or fallback, which `paths`
cannot do. The longest matching prefix selects the site, independent of the configuration order. A
site without `uri` (or with `*` or `/*`) gets every request no other site of the host matches;
without such a site, those requests get a 404. Two sites with the same host and `uri` are a
configuration error.

````yaml
sites:
  shop:
    host: www.example.com
    upstream: { target: shop:8080 }
  api:
    host: www.example.com
    uri: /api/*
    protection: { disable: true }
    upstream: { target: api:8080 }
````

## Configuration reload

A YAML configuration file is reloaded without a restart if its last non-empty line is the end
marker `#END`:

````yaml
sites:
  example.com:
    upstream:
      target: "127.0.0.1:8080"
#END
````

The marker shows that the file was written completely. A file read in the middle of writing is
often still valid YAML, but lacks sites or routes at the end. Without the marker the file is not
reloaded, so adding the marker enables live reload. Starting the proxy does not need it.

- **Triggers:** a change of the file content, detected right away by watching the file's
  directory, a change of a file the configuration read, and `POST /reload` on the admin endpoint
  (see below). The files the configuration read are the TLS keys and certificates, secret files
  (see Secrets), response and fallback pages and the IP databases; their directories are watched
  as well, so a certificate renewed by cert-manager or Let's Encrypt, or a rotated Kubernetes
  Secret, is picked up without touching the configuration file. A missing file does not trigger
  a reload, it may be in the middle of a replacement. The admin endpoint also reloads when
  nothing changed.
- **What is reloaded:** sites, upstreams, protection, fallback, `unservicedHost`, `listen.hosts`,
  certificates (`listen.ssl`, `snis`, Let's Encrypt), `ipLookup` and `requestLog`. The IP lookup
  databases are only read again if `ipLookup` or a database file changed. Upstreams with
  unchanged configuration are kept. Request log destinations with unchanged name, settings and
  secret files are kept, with their buffered records. A changed or removed OTLP destination keeps
  sending what it buffered to its own endpoint in the background, without delaying the reload. Each event loop writes an end marker
  into it after the switch, so a record logged just before the switch is sent as well. It ends
  when everything is sent, or dropped after `otlp.maximumUnsentLogAgeMillis` during an outage.
- **Restart needed:** changes of `listen.address`, `listen.httpsPort`, `listen.connection`,
  `metrics`, `ja4` and `traceContext` are logged as a warning and ignored.
- **Invalid configuration:** the error is logged and the active configuration stays. The files
  the rejected configuration tried to read are watched as well, a missing one too, so fixing or
  creating such a file, e.g. a certificate written in two steps, retries the reload without
  touching the configuration file.
- **Log:** a reload logs the configuration changes as a diff, and added, changed or removed
  certificates with subject, serial and expiry.

Keep-alive connections use the new configuration from their next request on.

### Secrets

A secret is configured as an object, either inline with `value` or with `file`, e.g. a mounted
Kubernetes Secret. Exactly one of the two is set. A file is read when the configuration is
applied and watched like the configuration file. One final line break (`\n` or `\r\n`), as
`echo` or an editor adds it, is removed; all other characters are kept, so moving an inline
secret to a file keeps its value. An empty secret, or one of whitespace only, is rejected: a
broken rotation keeps the last working secret active.

````yaml
protection:
  deflector:
    tokenGenerators:
      - prefix: X
        secret:
          file: /etc/sensepitch-edge/secrets/admission
````

As environment variables: `SENSEPITCH_EDGE_PROTECTION_DEFLECTOR_TOKEN_GENERATORS_0_SECRET_FILE`
or `..._SECRET_VALUE`. Secrets are `tokenGenerators[].secret` and
`requestLog.destinations[].otlp.secretHeaders[].secret`, see Destinations. The configuration dump
in the diagnostic log masks an inline `value`. The request log field `sensepitch.config.id` covers
the secrets as well, so a rotated secret gives a new id, see Fields.

Earlier versions took the admission secret as a plain string, `secret: munich`. This form is
rejected now, write `secret: {value: munich}` or move it into a file.

### Admin endpoint

An optional HTTP endpoint for operators and scripts, like the Caddy admin API. It is started if
`admin` is configured; `admin: {}` uses the defaults, address `127.0.0.1` and port `9401`:

````yaml
admin:
  address: 127.0.0.1
  port: 9401
````

````
curl -X POST http://127.0.0.1:9401/reload
````

`/healthz` answers `200` while the admin server runs. `/readyz` answers `200` until shutdown
starts, then `503`; it does not wait for the proxy listener to start.

`POST /reload` answers `200` if the configuration was applied, `422` with the reason if it was
rejected (invalid configuration, missing `#END`), and `409` if the configuration comes from
environment variables. The endpoint has no authentication, so it binds to the loopback interface
by default. Binding another address exposes it to that network. A reload only reads the local
file again, so the worst case is repeated reloads.

## Shutdown

On `SIGTERM` the proxy shuts down gracefully, within `shutdownTimeoutMillis` (default 5000). It
ends as soon as everything is done, so with the default settings an idle proxy exits at once.

1. Readiness becomes false (`/readyz` answers `503`). The proxy keeps accepting and serving new
   and keep-alive requests for `shutdownAcceptGraceMillis` (default 0), then the listener closes.
2. Idle keep-alive connections are closed. A response in flight gets `Connection: close`, and its
   connection is closed after it.
3. The requests in flight may take the shutdown timeout minus the accept grace and
   `requestLog.drainTimeoutMillis`. Requests still running then are aborted and logged with
   status 499.
4. The event loops end, each writes its last request log records. The request log destinations
   send them, within the drain timeout.

````yaml
shutdownTimeoutMillis: 10000      # upper limit of the whole shutdown, including the grace
shutdownAcceptGraceMillis: 2000   # keep accepting after readiness turned false, default 0
requestLog:
  drainTimeoutMillis: 3000        # reserved for the request log, default a third of the above
````

The example allows 2 seconds for routing updates, 5 seconds for the requests in flight and 3
seconds for the request log. The accept grace plus the drain timeout must fit into the shutdown
timeout, negative values are rejected.

A phase that runs out of time is logged at level `INFO` with what was left: the requests in
flight per upstream, or the request log records not sent per destination. Records not sent by
then are lost.

A configuration reload and the shutdown run one at a time: `SIGTERM` during a reload waits until
it is applied, a reload after the shutdown started is rejected. The settings are read when the
shutdown starts, so a reload changes them.

Kubernetes removes the pod from the service endpoints at the same time as it sends `SIGTERM`, so
proxies and load balancers route to it for a moment longer; one probing `/readyz` notices the
`503` after its probe interval times the failure threshold. Set `shutdownAcceptGraceMillis` to
cover that time. It works with any image, no `preStop` hook with a shell and `sleep` is needed;
it cannot guarantee that every client has stopped by then. Fit the whole `shutdownTimeoutMillis`
into the grace period of the platform, e.g. `terminationGracePeriodSeconds`, with some margin.

## Request log

Each request is logged with its fields named after the
[OpenTelemetry semantic conventions](https://opentelemetry.io/docs/specs/semconv/) (checked
against version 1.45.0). The same names are used whatever the destination, so a query works for
the JSON lines on standard out and for the OTLP export alike.

By default the request log goes to standard out, one JSON object per line:

````json
{"timestamp":"2026-11-11T10:00:00.123Z","http.request.method":"GET",
 "server.address":"example.com","url.path":"/search","url.query":"q=edge",
 "http.response.status_code":200,"sensepitch.request.outcome":"completed",
 "sensepitch.duration.total_ns":5321000,"http.response.body.size":5120,
 "client.address":"192.0.2.7","user_agent.original":"Mozilla/5.0 ...", ...,
 "event.name":"sensepitch.request","trace_id":"4bf92f3577b34da6a3ce929d0e0e4736",
 "span_id":"00f067aa0ba902b7"}
````

`requestLog.plainText: true` switches standard out to the legacy single line format, useful for
local debugging.

`requestLog.disabled: true` switches the request log off, the metrics per status code stay.
`disabled: true` on a destination skips only that one, e.g. standard out while the OTLP export
stays. Both are flags rather than an empty list, so a later configuration layer, e.g. an
environment variable like `SENSEPITCH_EDGE_REQUEST_LOG_DISABLED=true`, can switch off a default.

### Destinations

With `requestLog.destinations` the request log is written to several destinations, each with its
own settings. `stdout` writes JSON lines (or plain text) to standard out, `otlp` sends the records
via OTLP/HTTP with protobuf encoding, e.g. to VictoriaLogs or an OpenTelemetry collector:

````yaml
requestLog:
  destinations:
    - type: stdout
    - type: otlp
      name: victorialogs          # metric label, defaults to the type
      anonymizeClientIp: true
      otlp:                       # settings of the OTLP destination, mandatory for type otlp
        endpoint: http://victorialogs:9428/insert/opentelemetry/v1/logs
        headers:
          VL-Stream-Fields: service.name,server.address
        secretHeaders:            # headers with secret values, see Secrets
          - name: Authorization
            secret:
              file: /etc/sensepitch-edge/secrets/victorialogs-auth
        resourceAttributes:       # defaults: service.name sensepitch-edge,
                                  # service.instance.id host name, service.version
          deployment.environment: prod
        timeoutMillis: 10000      # defaults shown
        initialRetryDelayMillis: 1000
        maxRetryDelayMillis: 30000
        flushIntervalMillis: 1000
        batchBytes: 1048576       # per event loop buffer and request
        maxBufferedBytes: 67108864  # batches waiting during an outage
        compression: gzip         # or none
        compressionLevel: 1       # 1 fastest (default) .. 9 smallest
````

`type`, `name` and `anonymizeClientIp` apply to every destination, `plainText` to `stdout` only.
The `name` defaults to the type, but several `otlp` destinations must be named: the name
identifies a destination across a reload (see Configuration reload), and a positional default
would shift when a destination is inserted. Renaming a destination counts as removing it and
adding a new one.
Everything specific to OTLP is in the `otlp` object, which is rejected on a `stdout` destination.

- **`anonymizeClientIp`:** per destination. The client address is truncated to its network, `/24`
  for IPv4 (`192.0.2.7` becomes `192.0.2.0`) and `/48` for IPv6, and the client port is left out.
  The country and ASN are still derived from the full address.
- **`sampleHeadersInterval`:** per destination, `0` (off) by default. If positive, about one in
  this many requests, picked at random, also logs its request headers as
  `sensepitch.request.sample_headers`, `Name: value` in the order received with the original
  casing, to study what identifies a client. `Cookie`, `Authorization`, `Proxy-Authorization`,
  `traceparent` and the headers the edge adds itself (`X-Forwarded-*`, `Sp-*`) are left out. The
  plain text format ignores it.
- **Batching:** each event loop encodes its records into a buffer of its own, so logging a request
  allocates nothing and needs no shared queue. A buffer is handed to the sender when it reaches
  `otlp.batchBytes` or after `otlp.flushIntervalMillis`, and the event loop continues with an
  empty one from a pool. One request carries one batch.
- **Outages:** connection errors, timeouts and the status codes 429, 502, 503 and 504 are retried
  with exponential backoff, honouring `Retry-After`. The nominal delay starts at
  `otlp.initialRetryDelayMillis` (1000 ms) and doubles up to `otlp.maxRetryDelayMillis`
  (30000 ms); jitter picks between half and all of that delay. Omitted or zero values use
  these defaults. Negative values or an effective maximum below the effective initial delay
  reject the destination. A maximum above `otlp.maximumUnsentLogAgeMillis` lets batches age
  out between two probes. A longer `Retry-After` can exceed the configured maximum, capped
  at 300 seconds. Changing either option on reload replaces the destination: new records use
  the new delays, buffered records drain with the old settings. The batches wait meanwhile,
  so a restart of the log server loses no records. The waiting batches are limited by
  `otlp.maxBufferedBytes`; when the limit is reached, new records are dropped and counted in
  `sensepitch_request_log_dropped_total`, the waiting ones are kept. After the outage the extra
  buffers are released again. Other errors, e.g. status 400, cannot succeed by retrying, the batch
  is dropped and counted in `sensepitch_request_log_export_failed_total`.
- **Compression:** posts are compressed with gzip and sent with `Content-Encoding: gzip`, which
  VictoriaLogs and the OpenTelemetry collector accept. The records repeat hosts, attribute keys
  and user agents, so a post shrinks several times. Each destination compresses on one dedicated,
  bounded thread, never on an event loop; the thread is shut down when the destination stops.
  `otlp.compressionLevel` ranges from `1` (fastest, the default) to `9` (smallest); a higher level
  trades CPU for fewer bytes on the wire. The measured trade-off is in
  [the request log design](doc/design/request-log.md#gzip-on-one-dedicated-compression-thread).
  Set `otlp.compression: none` for a log server without gzip support; it would reject the posts
  with a 4xx status, counted in `export_failed_total`.
- **Memory:** about `otlp.batchBytes` per event loop, plus the waiting batches up to
  `otlp.maxBufferedBytes`. Buffers are kept in memory only: when the proxy itself is restarted
  during an outage of the log server, the waiting records are lost. At shutdown the proxy tries
  to send them within the drain timeout, see Shutdown.
- **VictoriaLogs:** the `VL-Stream-Fields` header selects the
  [log stream](https://docs.victoriametrics.com/victorialogs/keyconcepts/#stream-fields) fields.
  Keep them to a few low-cardinality fields, never `client.address` or `url.path`.
- **Environment variables:** destinations are indexed, map entries have a key and a value
  variable:

  ````
  SENSEPITCH_EDGE_REQUEST_LOG_DESTINATIONS_0_TYPE=otlp
  SENSEPITCH_EDGE_REQUEST_LOG_DESTINATIONS_0_OTLP_ENDPOINT=http://victorialogs:9428/insert/opentelemetry/v1/logs
  SENSEPITCH_EDGE_REQUEST_LOG_DESTINATIONS_0_OTLP_HEADERS_0_KEY=VL-Stream-Fields
  SENSEPITCH_EDGE_REQUEST_LOG_DESTINATIONS_0_OTLP_HEADERS_0_VALUE=service.name
  SENSEPITCH_EDGE_REQUEST_LOG_DESTINATIONS_0_OTLP_SECRET_HEADERS_0_NAME=Authorization
  SENSEPITCH_EDGE_REQUEST_LOG_DESTINATIONS_0_OTLP_SECRET_HEADERS_0_SECRET_FILE=/etc/...
  ````

- **Credentials:** put them into `otlp.secretHeaders`, not `otlp.headers`. The configuration,
  including `otlp.headers`, is logged at startup and in the diff of a reload; a secret header
  shows only its file, or a masked inline value. A header must not be in both. A changed secret
  file replaces the destination on the next reload, the old one drains its buffered records with
  the old header.

### Trace context

OpenTelemetry has no request id attribute; requests are identified by their trace context. The
edge assigns every request a W3C trace context: the `span_id` is the request id, logged with the
`trace_id` and sent to upstream in the `traceparent` header. Backends that read the header, which
OpenTelemetry instrumented ones do, continue the trace, so their logs and traces correlate with
the edge.

The edge faces the internet, so by default it ignores a `traceparent` sent by the client, starts
a new trace and removes `tracestate`. The client's header is logged as
`http.request.header.traceparent`. When a trusted CDN or load balancer in front of the edge sets
the header, continue its trace:

````yaml
traceContext:
  trustTraceparent: true
````

A trace started by the edge is flagged as sampled, so backends with a parent based sampler record
it.

### Fields

In the code, the OpenTelemetry names are defined in `OtelLogFields`, the Sensepitch extensions in
`SensepitchLogFields`.

Record level fields are top level keys in JSON and the fields of the log data model in OTLP:

| JSON key | OTLP | Content |
|---|---|---|
| `timestamp` | `time_unix_nano` | start of the request |
| — | `observed_time_unix_nano` | when the record was produced, after the response was sent |
| `event.name` | `event_name` | `sensepitch.request`, identifies request log records |
| — | `severity_text`, `severity_number` | `INFO` (9), `WARN` (13) for server errors and failed requests |
| `trace_id`, `span_id` | same, plus `flags` | trace context of the request, the span id is the request id |
| — | `body` | summary, e.g. `GET /search?q=edge 200` |

Attributes, semantic convention names:

| Name | Content |
|---|---|
| `client.address`, `client.port` | client, subject to `anonymizeClientIp` |
| `server.address`, `server.port` | requested host (sanitized `Host` header) and local port |
| `http.request.method` | method, `_OTHER` for unknown methods, then `http.request.method_original` holds it |
| `url.scheme`, `url.path`, `url.query` | `https` with TLS; the request target split at `?` |
| `http.route` | the matched route: the path pattern of a path route, e.g. `/api/*`, else the `uri` pattern of the site, `/*` if it has none. Absent if no site matched |
| `network.protocol.name`, `network.protocol.version`, `network.transport` | `http`, `1.1`, `tcp` |
| `tls.protocol.name`, `tls.protocol.version`, `tls.cipher` | e.g. `tls`, `1.3`, `TLS_AES_128_GCM_SHA256` |
| `http.response.status_code` | status code |
| `http.response.header.content-type` | response content type, string array |
| `http.request.body.size`, `http.response.body.size` | body bytes |
| `http.request.size`, `http.response.size` | bytes including headers |
| `user_agent.original` | `User-Agent` header |
| `http.request.header.<name>` | string arrays, for `accept-language`, `sec-ch-ua`, `sec-ch-ua-platform`, `sec-ch-ua-mobile`, `referer`, `signature-agent` and the client's `traceparent` |
| `geo.country.iso_code` | country of the client address, if the IP lookup knows it |
| `error.type` | exception class, or the status code for a server error without exception |
| `exception.type`, `exception.message` | the exception, if the request failed |

Attributes without a semantic convention:

| Name | Content |
|---|---|
| `sensepitch.connection.id`, `sensepitch.connection.request_count` | groups the requests of a keep-alive connection |
| `sensepitch.request.outcome` | why the request ended, always present, see below |
| `sensepitch.upstream.status` | status of the upstream response when the edge replaced it with a fallback page |
| `sensepitch.config.id` | short hash (64 bit, 16 hex characters) of the configuration and its secrets (see Secrets) active when the request started. The same configuration and secrets give the same id on every node with the same edge version, a reload with changes or a rotated secret gives a new id. Whoever knows the configuration can try candidate secrets against the id, so secrets should be random |
| `sensepitch.protection.outcome` | deflector outcome: `passed`, `bypassed`, `method_rejected`, `challenge_sent`, `answer_accepted`, `answer_rejected`, the label values of `sensepitch_deflector_requests_total` |
| `sensepitch.upstream.name`, `sensepitch.upstream.target` | the upstream that served the request: its name in the configuration, e.g. `upstreams.backend` or `sites.example.com.paths./api/*.upstream`, and its configured `host:port`. A static response has its path as name, e.g. `sites.example.com.response`, and no target. Absent when the edge answered itself, e.g. with a challenge |
| `sensepitch.client.asn` | autonomous system number of the client address |
| `sensepitch.ip_traits` | traits of the client address, e.g. `asn=64496, country=DE, crawler=...` |
| `sensepitch.tls.client.ja4` | JA4 TLS fingerprint, if enabled |
| `sensepitch.protection.admission_token` | admission token of the protection: of a valid admission cookie, or the token issued for an accepted challenge answer |
| `sensepitch.traffic.type` | `human`, `bot` or `unknown`, see the `Sp-Traffic-Type` header |
| `sensepitch.protection.bypass` | reason the protection was bypassed: `config-ip-match`, `config-uri-match` (a configured `bypass.remotes` or `bypass.uris`), `crawler-agent-match`, `crawler-fragment-agent-match`, `crawler-ip-match` |
| `sensepitch.request.cookie_present` | whether a cookie header was sent, not its content |
| `sensepitch.request.header_names` | names of the request headers, in order |
| `sensepitch.duration.receive_ns`, `…response_ns`, `…total_ns` | durations, see `RequestLogInfo` |

The OTLP export also identifies the edge: the resource has `service.instance.id`, the
`HOSTNAME` environment variable (the pod name in Kubernetes) or else the host name, and
`service.version`, the edge version. `otlp.resourceAttributes` overrides both.

### Request outcome

`sensepitch.request.outcome` tells why a request ended. The values are few, so it suits dashboards
to see at a glance whether the edge, the upstream or the client is involved. The metric
`sensepitch_ingress_request_outcomes_total` counts the same values:

| Value | Meaning |
|---|---|
| `completed` | the upstream or a static response route answered, the response was sent |
| `edge_response` | no upstream was selected, the edge answered itself: challenge, rejected method, unserviced host, no site matched |
| `fallback` | the upstream answered 500 or 503 and the fallback page was sent instead, see `sensepitch.upstream.status` |
| `upstream_connect_failed` | the connection to the upstream failed (502) |
| `upstream_write_failed` | forwarding the request to the upstream failed (502) |
| `upstream_timeout` | the upstream did not respond within the response timeout (504) |
| `request_timeout` | the client did not complete the request in time (408) |
| `response_write_timeout` | the client did not take the response in time |
| `client_aborted` | the connection closed before the response was complete (499) |
| `edge_error` | another error, answered with 502 |

### Migrating from the previous JSON format

The JSON keys were renamed. Collectors that recognised the records by `type: request` must filter
on `event.name: sensepitch.request` instead:

| Previous | Now |
|---|---|
| `type` | `event.name` |
| `time` | `timestamp` |
| `request_id` | `span_id`; the connection is `sensepitch.connection.id` and `…request_count` |
| `host`, `remote_addr` | `server.address`, `client.address` |
| `method`, `uri`, `protocol`, `status` | `http.request.method`, `url.path` + `url.query`, `network.protocol.*`, `http.response.status_code` |
| `content_bytes`, `bytes_received`, `bytes_sent` | `http.response.body.size`, `http.request.size`, `http.response.size` |
| `receive_nanos`, `response_nanos`, `total_nanos` | `sensepitch.duration.*_ns` |
| `headers.*` | `http.request.header.*`, string arrays |
| `user_agent`, `referer`, `signature_agent` | `user_agent.original`, `http.request.header.referer`, `http.request.header.signature-agent` |
| `cookie`, `header_names`, `ip_traits`, `admission_token`, `bypass` | `sensepitch.request.cookie_present`, `sensepitch.request.header_names`, `sensepitch.ip_traits`, `sensepitch.protection.admission_token`, `sensepitch.protection.bypass` |
| `error` | `error.type`, `exception.type`, `exception.message` |

Upstreams now receive the edge's `traceparent` header on every request, see Trace context.

Later, `sensepitch.admission_token` and `sensepitch.bypass` moved next to
`sensepitch.protection.outcome`: they are now `sensepitch.protection.admission_token` and
`sensepitch.protection.bypass`.

## Diagnostic logging

Diagnostic messages go to standard error at level `INFO`, the request log goes to standard out.
To trace the proxy, select the trace configuration that is part of the JAR with Log4j2's own
environment variable, or point it to an own file:

````
LOG4J_CONFIGURATION_FILE=classpath:log4j2-trace.xml
````

An own configuration file needs `shutdownHook="disable"` on its `<Configuration>` element, like
the bundled ones: otherwise Log4j2 stops in parallel to the graceful shutdown on `SIGTERM`, and the
shutdown messages are lost. The proxy stops Log4j2 itself when the shutdown is complete.

Tracing costs a level check per trace call. For maximum performance,
`SENSEPITCH_EDGE_DISABLE_TRACE=true` removes the trace code completely, a configured trace level
then has no effect. The former variable `SENSEPITCH_EDGE_LOG_LEVEL` is no longer supported.

## Build verification

The integration tests run with `./mvnw verify` and require Node.js 22 or newer on `PATH` for
the challenge JavaScript suite. See [its test README](src/test/javascript/README.md).

## Container image

Build and run the image with Docker (or a compatible builder such as Podman):

````
docker build -t ghcr.io/sensepitch/sensepitch-edge .
docker run --rm -p 17443:17443 \
  -v $PWD/config.yml:/etc/sensepitch/config/config.yml:ro \
  ghcr.io/sensepitch/sensepitch-edge
````

The image runs the shaded JAR on a Temurin 25 JRE as a non-root user, with `java` as PID 1 so
`SIGTERM` reaches the graceful shutdown. Files the configuration references (TLS keys and
certificate, the IPinfo database) must be mounted as well. The base-image and registry choices
are in [doc/design/oci-image.md](doc/design/oci-image.md); the proposed CI build-and-push
workflow is `DOT-GITHUB-UPDATED/.github/workflows/build-and-push-image.yml`.

To smoke-test a local image through Docker:

```bash
docker build -t sensepitch-edge:oci-smoke .
./mvnw verify -Poci-image
```

The profile uses the existing image and requires Docker access, Bash, and curl. Set
`-DociImage=<local-image-tag>` to test a different image. The standard Maven gate skips this
test. With the profile enabled, failure to start the container fails the build.

## Kubernetes

The standalone Helm chart deploys the edge with health probes, a ConfigMap, mounted Secrets,
and optional Prometheus scraping. See the [chart operator guide](charts/sensepitch-edge/README.md).

## Local testing

````
iptables -t nat -A OUTPUT -o lo -p tcp --dport 80  -j REDIRECT --to-port 7080
iptables -t nat -A OUTPUT -o lo -p tcp --dport 443 -j REDIRECT --to-port 7443
````

## Blogs about high performance Edge Proxy

- https://dropbox.tech/infrastructure/how-we-migrated-dropbox-from-nginx-to-envoy
- https://blog.cloudflare.com/pingora-open-source/
