
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
- [x] use keep alive / connection pool upstream
- [x] openssl support for HTTPS
- [x] upstream backpressure
- [x] YAML based configuration
- [x] improve default configuration
- [ ] metrics: byte IO count per ingress
- [ ] add netty memory pool statistics to monitoring
- [ ] make Netty socket options available, check connection timeout and SO_TIMEOUT
- [ ] config: stop / complain if option is unknown
- [ ] upstream metrics, e.g. pool metrics
- [ ] Logging: Netty LoggingHandler seems not to respect the origin class
- [ ] try markdown JavaDoc, https://openjdk.org/jeps/467
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
- [ ] reload configuration without closing the port
- [ ] multiple servers per upstream
- [ ] upstream health
- [ ] warmup
- [ ] limit body size for incoming requests? NGINX has 1M as default / client_max_body_size / status 413

## Resources

### Netty

- [Netty, the IO framework that propels them all By Stephane LANDELLE](https://www.youtube.com/watch?v=NvnOg6g4114)

### HTTP 1.1 Connection Management with Keep Alive

Robust keep alive is a bit different from what the HTTP/1.1 standard defines.

- https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Status/408
- https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Keep-Alive
- https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/Connection_management_in_HTTP_1.x

## Exposed metrics

Metrics are exposed via prometheus. This section picks out metrics that should be monitored.

### `process_resident_memory_bytes`

Total memory consumption of the process as reported by the OS.

### `jvm_memory_used_bytes{area="heap"}` and `jvm_memory_used_bytes{area="nonheap"}` 

Java heap, which is used for allocating objects and nonheap which is used for JVM internals.
The `nonheap` does not include direct buffers.

### `jvm_buffer_pool_used_bytes{pool="direct"}`

Netty makes use of pooled direct byte buffers.

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


## Local testing

````
iptables -t nat -A OUTPUT -o lo -p tcp --dport 80  -j REDIRECT --to-port 7080
iptables -t nat -A OUTPUT -o lo -p tcp --dport 443 -j REDIRECT --to-port 7443
````

