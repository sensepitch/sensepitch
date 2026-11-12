
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

    java -XX:+UseZGC --enable-native-access=ALL-UNNAMED -jar target/sensepitch-edge-1.0-SNAPSHOT-with-dependencies.jar

On Java 25, `--enable-native-access=ALL-UNNAMED` lets Netty 4.2.17 use the foreign linker to call
libc's `malloc` and `free` directly. These allocations bypass both JVM direct buffer accounting and
native memory tracking. Use Netty's allocator metrics and process RSS to monitor them, see
[Exposed metrics](#exposed-metrics).

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
- [ ] upstream connection pool with keep-alive (an earlier version was disabled 2025-09-20 and removed,
  see git history)
- [x] openssl support for HTTPS
- [x] upstream backpressure
- [x] YAML based configuration
- [x] improve default configuration
- [x] metrics: byte IO count per ingress
- [ ] Performance test: different Netty allocators, leak detection, -XX:NativeMemoryTracking=summary
- [x] Remove MetricsBridge, we exclusively use Prometheus
- [ ] bypass config should be in the protection and not in the protection providers => we need a proper API for the protection provider
- [ ] http to https forward
- [ ] send 404 for any non / URL for unserviced domain?
- [ ] only allow known HTTP methods
- [ ] Integrate https://github.com/cruftex/GoodBots
- [ ] minimise + compress, challenge resources
- [ ] unify timeout with keep alive handler
- [ ] Implement server-timing, https://www.w3.org/TR/server-timing/
- [ ] test sha256 pow, remove subtle variant
- [ ] https://docs.perplexity.ai/guides/bots
- [ ] add netty memory pool statistics to monitoring
- [ ] documentation, nice examples are: Envoy
- [ ] config: stop / complain if option is unknown
- [ ] upstream metrics, e.g. pool metrics
- [ ] Logging: Netty LoggingHandler seems not to respect the origin class
- [x] implemented, try markdown JavaDoc, https://openjdk.org/jeps/467
- [ ] explain PoW admission
- [ ] use delombok, so JavaDoc has proper documentation in the builder
- [ ] block requests that are not GET and POST if not admitted or crawler bypassed
- [ ] expose allocator statistics via prometheus
- [ ] expose upstream connection pool statistics via prometheus
- [ ] failure counters
- [ ] cleanup debugging code / use Netty logger
- [ ] test errors of in flight requests
- [ ] restrict admissions per solved challenge
- [ ] default configs / layer configuration / overwrite config tree
- [ ] Enable tracing / debugging switch
- [ ] standard logging target
- [ ] Admission performance: skip validation for keep-alive connections
- [ ] Admission performance: deliver a challenge via cookie and serve static HTML
- [ ] improve bypass matching (maybe ASN, geo)
- [ ] downstream http2 support
- [ ] Expiry of granted admissions
- [ ] abnormal admission usage detection of admission
- [x] reload configuration without closing the port
- [ ] multiple servers per upstream
- [ ] upstream health
- [ ] warmup
- [ ] limit body size for incoming requests? NGINX has 1M as default / client_max_body_size / status 413
- [ ] google-java-format 2, https://github.com/google/google-java-format/releases#release-v2.0.0
  (stuck at 1.36.1: Spotless cannot load 1.37+, 2.x changes the lambda layout). Impact: one-time
  reformat of about 40 files / 930 lines, lambda bodies hug the call (`.forEach(t -> {`) and indent
  less. Done in its own commit, `formatJavadoc` stays off so the `///` comments remain untouched.

## Resources

### Netty

- [Netty, the IO framework that propels them all By Stephane LANDELLE](https://www.youtube.com/watch?v=NvnOg6g4114)

### HTTP 1.1 Connection Management with Keep Alive

Robust keep alive is a bit different from what the HTTP/1.1 standard defines.

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
- `challenge_sent`: the challenge page was sent
- `answer_accepted`: correct challenge answer, the admission cookie was issued
- `answer_rejected`: wrong or incomplete answer

A high ratio of `challenge_sent` to `answer_accepted` is normal under bot traffic. Many
`answer_rejected` hint at clients trying to forge answers. Requests for the challenge's scripts and
styles are not counted.

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
  directory, and `POST /reload` on the admin endpoint (see below). The admin endpoint also
  reloads an unchanged file, which picks up renewed certificate files, e.g. after a Let's
  Encrypt renewal.
- **What is reloaded:** sites, upstreams, protection, fallback, `unservicedHost`, `listen.hosts`,
  certificates (`listen.ssl`, `snis`, Let's Encrypt), `ipLookup` and `requestLog`. The IP lookup
  databases are only read again if `ipLookup` changed. Upstreams with unchanged configuration are
  kept. Request log destinations with unchanged name and settings are kept. A changed OTLP
  destination hands its buffered records over to its replacement of the same name, also during
  an outage of the log server, and records logged into it while the switch happens are passed on,
  so a reload loses nothing. This also works when the change fixes a wrong endpoint. A removed
  destination is closed: it tries to send what it buffered for 5 seconds, then counts the rest in
  `sensepitch_request_log_dropped_total`.
- **Restart needed:** changes of `listen.address`, `listen.httpsPort`, `listen.connection`,
  `metrics`, `ja4` and `traceContext` are logged as a warning and ignored.
- **Invalid configuration:** the error is logged and the active configuration stays.
- **Log:** a reload logs the configuration changes as a diff, and added, changed or removed
  certificates with subject, serial and expiry.

Keep-alive connections use the new configuration from their next request on.

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

`POST /reload` answers `200` if the configuration was applied, `422` with the reason if it was
rejected (invalid configuration, missing `#END`), and `409` if the configuration comes from
environment variables. The endpoint has no authentication, so it binds to the loopback interface
by default. Binding another address exposes it to that network. A reload only reads the local
file again, so the worst case is repeated reloads.

## Request log

Each request is logged with its fields named after the
[OpenTelemetry semantic conventions](https://opentelemetry.io/docs/specs/semconv/) (checked
against version 1.45.0). The same names are used whatever the destination, so a query works for
the JSON lines on standard out and for the OTLP export alike.

By default the request log goes to standard out, one JSON object per line:

````json
{"timestamp":"2026-11-11T10:00:00.123Z","observed_timestamp":"2026-11-11T10:00:00.130Z",
 "event.name":"sensepitch.request","severity_text":"INFO","severity_number":9,
 "trace_id":"4bf92f3577b34da6a3ce929d0e0e4736","span_id":"00f067aa0ba902b7",
 "client.address":"192.0.2.7","client.port":51234,"server.address":"example.com",
 "server.port":443,"http.request.method":"GET","url.path":"/search","url.query":"q=edge", ...}
````

`requestLog.plainText: true` switches standard out to the legacy single line format, useful for
local debugging.

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
        resourceAttributes:       # defaults: service.name sensepitch-edge,
                                  # service.instance.id host name, service.version
          deployment.environment: prod
        timeoutMillis: 10000      # defaults shown
        flushIntervalMillis: 1000
        batchBytes: 1048576       # per event loop buffer and request
        maxBufferedBytes: 67108864  # batches waiting during an outage
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
- **Batching:** each event loop encodes its records into a buffer of its own, so logging a request
  allocates nothing and needs no shared queue. A buffer is handed to the sender when it reaches
  `otlp.batchBytes` or after `otlp.flushIntervalMillis`, and the event loop continues with an
  empty one from a pool. One request carries one batch.
- **Outages:** connection errors, timeouts and the status codes 429, 502, 503 and 504 are retried
  with exponential backoff up to 30 seconds, honouring `Retry-After`. The batches wait meanwhile,
  so a restart of the log server loses no records. The waiting batches are limited by
  `otlp.maxBufferedBytes`; when the limit is reached, new records are dropped and counted in
  `sensepitch_request_log_dropped_total`, the waiting ones are kept. After the outage the extra
  buffers are released again. Other errors, e.g. status 400, cannot succeed by retrying, the batch
  is dropped and counted in `sensepitch_request_log_export_failed_total`.
- **Memory:** about `otlp.batchBytes` per event loop, plus the waiting batches up to
  `otlp.maxBufferedBytes`. Buffers are kept in memory only: when the proxy itself is restarted
  during an outage of the log server, the waiting records are lost. At shutdown the proxy tries
  for 5 seconds to send them.
- **VictoriaLogs:** the `VL-Stream-Fields` header selects the
  [log stream](https://docs.victoriametrics.com/victorialogs/keyconcepts/#stream-fields) fields.
  Keep them to a few low-cardinality fields, never `client.address` or `url.path`.
- **Environment variables:** destinations are indexed, map entries have a key and a value
  variable:

  ````
  SENSEPITCH_EDGE_REQUEST_LOG_DESTINATIONS_0_TYPE=otlp
  SENSEPITCH_EDGE_REQUEST_LOG_DESTINATIONS_0_OTLP_ENDPOINT=http://victorialogs:9428/insert/opentelemetry/v1/logs
  SENSEPITCH_EDGE_REQUEST_LOG_DESTINATIONS_0_OTLP_HEADERS_0_KEY=Authorization
  SENSEPITCH_EDGE_REQUEST_LOG_DESTINATIONS_0_OTLP_HEADERS_0_VALUE=Bearer ...
  ````

- **Configuration dump:** the configuration, including `otlp.headers`, is logged at startup and
  in the diff of a reload. Do not put credentials into `otlp.headers` if the diagnostic log is not
  protected.

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
| `observed_timestamp` | `observed_time_unix_nano` | when the record was produced, after the response was sent |
| `event.name` | `event_name` | `sensepitch.request`, identifies request log records |
| `severity_text`, `severity_number` | same | `INFO` (9), `WARN` (13) for server errors and failed requests |
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
| `sensepitch.config.id` | short hash of the configuration active when the request started. The same configuration has the same id on every node with the same edge version, a reload with changes gives a new id |
| `sensepitch.protection.outcome` | deflector outcome: `passed`, `bypassed`, `challenge_sent`, `answer_accepted`, `answer_rejected`, the label values of `sensepitch_deflector_requests_total` |
| `sensepitch.upstream.name`, `sensepitch.upstream.target` | the upstream that served the request: its name in the configuration, e.g. `upstreams.backend` or `sites.example.com.paths./api/*.upstream`, and its configured `host:port`. A static response has its path as name, e.g. `sites.example.com.response`, and no target. Absent when the edge answered itself, e.g. with a challenge |
| `sensepitch.client.asn` | autonomous system number of the client address |
| `sensepitch.ip_traits` | traits of the client address, e.g. `asn=64496, country=DE, crawler=...` |
| `sensepitch.tls.client.ja4` | JA4 TLS fingerprint, if enabled |
| `sensepitch.admission_token` | admission token of the protection |
| `sensepitch.bypass` | reason the protection was bypassed |
| `sensepitch.request.cookie_present` | whether a cookie header was sent, not its content |
| `sensepitch.request.header_names` | names of the request headers, in order |
| `sensepitch.duration.receive_ns`, `…response_ns`, `…total_ns` | durations, see `RequestLogInfo` |

The OTLP export also identifies the edge: the resource has `service.instance.id`, the
`HOSTNAME` environment variable (the pod name in Kubernetes) or else the host name, and
`service.version`, the edge version. `otlp.resourceAttributes` overrides both.

### Request outcome

`sensepitch.request.outcome` tells why a request ended. The values are few, so it suits dashboards
to see at a glance whether the edge, the upstream or the client is involved:

| Value | Meaning |
|---|---|
| `completed` | the upstream or a static response route answered, the response was sent |
| `edge_response` | no upstream was selected, the edge answered itself: challenge, unserviced host, no site matched |
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
| `cookie`, `header_names`, `ip_traits`, `admission_token`, `bypass` | `sensepitch.request.cookie_present`, `sensepitch.request.header_names`, `sensepitch.ip_traits`, `sensepitch.admission_token`, `sensepitch.bypass` |
| `error` | `error.type`, `exception.type`, `exception.message` |

Upstreams now receive the edge's `traceparent` header on every request, see Trace context.

## Diagnostic logging

Diagnostic messages go to standard error at level `INFO`, the request log goes to standard out.
To trace the proxy, select the trace configuration that is part of the JAR with Log4j2's own
environment variable, or point it to an own file:

````
LOG4J_CONFIGURATION_FILE=classpath:log4j2-trace.xml
````

Tracing costs a level check per trace call. For maximum performance,
`SENSEPITCH_EDGE_DISABLE_TRACE=true` removes the trace code completely, a configured trace level
then has no effect. The former variable `SENSEPITCH_EDGE_LOG_LEVEL` is no longer supported.

## Local testing

````
iptables -t nat -A OUTPUT -o lo -p tcp --dport 80  -j REDIRECT --to-port 7080
iptables -t nat -A OUTPUT -o lo -p tcp --dport 443 -j REDIRECT --to-port 7443
````

## Blogs about high performance Edge Proxy

- https://dropbox.tech/infrastructure/how-we-migrated-dropbox-from-nginx-to-envoy
- https://blog.cloudflare.com/pingora-open-source/
