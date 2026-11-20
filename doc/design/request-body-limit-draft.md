# Request size limits (draft)

**Draft, nothing decided.** This is the research for the `TODO.md` entry "limit body size for
incoming requests" under "Ingress, HTTP". The options marked `[ ]` are open choices for the
maintainer; the entry in `TODO.md` lists them for ticking. It covers the request decoding in
[`Proxy`](../../src/main/java/org/sensepitch/edge/Proxy.java) (`new HttpServerCodec()` in
`start()`), the request streaming in
[`DownstreamHandler`](../../src/main/java/org/sensepitch/edge/DownstreamHandler.java) and
[`ForwardHandler`](../../src/main/java/org/sensepitch/edge/ForwardHandler.java), and the read
timeout in
[`IngressTimeoutHandler`](../../src/main/java/org/sensepitch/edge/IngressTimeoutHandler.java)
with its [`ConnectionConfig`](../../src/main/java/org/sensepitch/edge/ConnectionConfig.java).
Once the choices are made, the document becomes `request-limits.md` and records the decisions.

## Purpose and scope

The edge has no limit on the size of a request body. NGINX refuses bodies over 1 MiB by default
with status 413. The `TODO.md` entry asks first how other tools handle this and which options
fit the project. These rules from the entry and from `AGENTS.md` bound the design:

- The proxy is transparent: it forwards what the client sends, and the upstream decides. Use a
  buffer for performance, then backpressure. So the default is no hard limit.
- Every limit is a caveat for operators: an upload that works against the upstream alone fails
  behind the edge.
- The configuration has two levels. `listen.connection` (`ConnectionConfig`) holds the connection
  settings, and `sites` with their `paths` (`SiteConfig`, `PathRouteConfig`) hold the routing.
- A refused request is a request level event. It is counted in a metric or recorded in the
  request log, and it is logged at `DEBUG`, the first occurrence at `WARNING`.

## Overview: what the edge does today

Measured on 2026-11-20 on `main` at `8d0270f` with Netty 4.2.20.Final and Temurin 25.0.5. The
"experiments" were throw-away code, not committed. One fed an `EmbeddedChannel` with only
`HttpServerCodec`. The other fed `HttpServerCodec` plus `Proxy.addHttpHandlers`, set up like
`CompleteTest`, with a site that answers with a static text.

### Decoder limits: Netty defaults, not configured

`Proxy.start()` adds `new HttpServerCodec()` without arguments. The defaults are constants in
`io.netty.handler.codec.http.HttpObjectDecoder`, read with `javap -constants` from
`netty-codec-http-4.2.20.Final.jar`:

| Constant | Value |
| --- | --- |
| `DEFAULT_MAX_INITIAL_LINE_LENGTH` | 4096 bytes, the request line |
| `DEFAULT_MAX_HEADER_SIZE` | 8192 bytes, all header lines |
| `DEFAULT_MAX_CHUNK_SIZE` | 8192 bytes, the largest `HttpContent` the decoder emits |
| `DEFAULT_CHUNKED_SUPPORTED` | `true` |
| `DEFAULT_VALIDATE_HEADERS` | `true` |
| `DEFAULT_ALLOW_DUPLICATE_CONTENT_LENGTHS` | `false` |

Netty 4.2 has `HttpDecoderConfig` with a setter for each of these values (`javap`).

What happens at the limits:

- Experiment with the bare codec: a 9000-byte header gives a `DefaultHttpRequest` whose
  `decoderResult()` is `failure(TooLongHttpHeaderException: HTTP header is larger than 8192
  bytes.)`, with the URI `/` and no headers.
- Experiment with the edge pipeline: a 9000-byte header and a 5000-byte request line are both
  answered with `400 Bad Request` and `connection: close`, and the channel is closed. A normal
  request gets `200`.
- No class in `src/main/java` reads `decoderResult()` (grep). **Inferred, not traced:** the 400
  comes from the missing `Host` header of the failed request, so the request is logged and
  counted as a missing host, not as a too-large header. The other proxies answer 431 or 414, see
  "Implementations investigated".

### Request body: streamed, no limit

- Experiment with the edge pipeline: a 20000-byte body with `Content-Length` was accepted (`200`,
  connection kept alive). No code counts body bytes against a limit.
- The decoder emits the body as it arrives, in `HttpContent` pieces of at most 8192 bytes.
  Experiment with the bare codec: 1 body byte gave a `DefaultHttpContent` of 1 byte, 20000 bytes
  gave 8192 + 8192 + 3616.
- `DownstreamHandler.augmentHeadersAndForwardRequest` turns `autoRead` off on the client
  connection while the upstream connects, if a body is announced (`Content-Length` or chunked),
  and on again after the request head is written upstream.
- `DownstreamHandler.channelRead` queues each `HttpContent` on the upstream connect future;
  `forwardContent` writes it without a flush, `LastHttpContent` goes with `writeAndFlush`.
- Backpressure towards the client: when the upstream connection becomes unwritable,
  `ForwardHandler.channelWritabilityChanged` turns `autoRead` off on the client connection and
  flushes until the upstream is writable again, then turns it on. The buffer per exchange is the
  Netty write buffer high water mark: 32 KiB low and 64 KiB high by default
  (`WriteBufferWaterMark`, `javap`). The edge sets no `WRITE_BUFFER_WATER_MARK`. The socket
  buffers of the kernel come on top. So the memory of an upload is bounded, whatever the size.
- The response direction works the same way, see `DownstreamHandler.channelWritabilityChanged`.
  Since the measurements above, `84fc75a` made the proxy ignore upstream writability notifications
  after the final response, and `4bc202a` made it resume client reads paused by upstream backpressure
  when an early response finishes, e.g. when the upstream answers 413 before the body is complete.
  Such an early response now gets `Connection: close`; the connection is closed after the rest of
  the body was read, or after the read timeout (`IngressTimeoutHandler`).
- No class in `src/main/java` handles `Expect: 100-continue` (grep). What reaches the client in
  that case was not checked.

### Slow uploads are not bounded

`IngressTimeoutHandler.channelRead` resets (`touch()`) the read timeout (`readTimeoutSeconds`,
default 30 s in `ConnectionConfig.DEFAULT`) for every message before `LastHttpContent`. With the
experiment above (1 byte gives one `HttpContent`), **inferred, not tested end to end:** a client
that sends one body byte every 29 s keeps the client connection and an upstream connection open
without end. There is no limit on the total upload time and no minimum rate. The
`ConnectionConfig` doc says "sending one byte does not reset the timeout". That holds for the
request head, which the decoder emits only when complete, not for the body.

This is the larger exposure. Memory is bounded by the backpressure; the cost is connections and
upstream workers, which a few clients can tie up. A size limit alone does not bound it.

## Implementations investigated

All on 2026-11-20. **Measured** means a run on this machine, with the default configuration
except where an opt-in is named. The backend was a small Python server that answers each request
with the number of body bytes it received. NGINX ran on the host. The other proxies ran in
Docker, with curl in a container on the same network. **Unverified** means from memory. The
documentation sites were not reachable from the sandbox: HTTPS to `nginx.org` and
`www.rfc-editor.org` failed with "CONNECT tunnel failed, response 403". None of the unverified
points was checked against sources.

### NGINX 1.28.3 (Ubuntu package `1.28.3-2ubuntu1.12`, `proxy_pass`)

- Measured: 1048576 bytes accepted, 1048577 bytes refused with `413 Request Entity Too Large`
  and `Connection: close`, also when sent chunked. The error log tells "client intended to send
  too large body: 1048577 bytes" (from `Content-Length`) apart from "too large chunked body".
- Measured: with `Expect: 100-continue` and `Content-Length: 10000000`, the 413 came before any
  body byte was sent.
- Measured: `client_max_body_size 0;` in a location turns the limit off there (1048577 bytes
  passed).
- Measured: NGINX buffers the whole request body before it proxies the request. The error log
  says "a client request body is buffered to a temporary file". The limit protects that buffer;
  the edge streams and has no such buffer.
- Measured: a 9000-byte header gave `400 Request Header Or Cookie Too Large`, a 9000-byte URI
  `414 Request-URI Too Large`; 7000 and 5000 bytes passed.
- Unverified: the directives `client_max_body_size` (default `1m`, contexts http, server,
  location), `client_body_buffer_size`, `client_body_timeout` (between two reads, not total),
  `proxy_request_buffering` (default on), `large_client_header_buffers` (default `4 8k`).

### HAProxy 3.2.27 (Docker image `haproxy:3.2`)

- Measured, defaults: a 20 MiB body passed, with `Content-Length` and chunked. A 16000-byte
  header gave 431, 8000 bytes passed. A 70000-byte URI gave 414, 9000 bytes passed.
- Measured, opt-in: `http-request deny deny_status 413 if { req.hdr_val(content-length) gt
  1048576 }` refused a 2 MiB body with `Content-Length` with 413. The same body sent chunked
  passed, since the rule only looks at the header.
- Unverified: the header limit follows from `tune.bufsize` (default 16 KiB); `option
  http-buffer-request` with `req.body_size` checks chunked bodies too, up to the buffer size.

### Caddy 2.11.9 (Docker image `caddy:2`)

- Measured, defaults: a 20 MiB body passed, with `Content-Length` and chunked. A 17000-byte
  header passed, 32000 bytes gave 431; a 70000-byte URI gave 431.
- Measured, opt-in: the module `http.handlers.request_body` exists (`caddy list-modules`);
  `request_body { max_size 1MB }` refused a 2 MiB body with 413 and `Connection: close`, with
  `Content-Length` and chunked. 1000000 bytes passed.

### Envoy 1.36.13 (Docker image `envoyproxy/envoy:v1.36-latest`)

- Measured, defaults (HTTP connection manager and router only): a 20 MiB body passed, with
  `Content-Length` and chunked. A 61000-byte header passed, a 70000-byte header and a 70000-byte
  URI gave 431.
- Measured, opt-in: the buffer filter `envoy.filters.http.buffer` with `max_request_bytes:
  1048576` refused a 2 MiB body with 413 and `connection: close`, with `Content-Length` and
  chunked. 1000000 bytes passed.
- Unverified: the buffer filter collects the whole body before forwarding, which is why it has a
  limit. The header limit is `max_request_headers_kb` (default 60).
  `per_connection_buffer_limit_bytes` (default 1 MiB) is a flow control watermark like the Netty
  one, not a size limit.

### Netty 4.2.20.Final `HttpObjectAggregator`

Read with `javap -c` from the jars; no sources were available offline. `MessageAggregator` checks
`isContentLengthInvalid` against `maxContentLength` when the head arrives, and the collected
bytes while content arrives. `HttpObjectAggregator.handleOversizedMessage` answers a request with
`413` (`REQUEST_ENTITY_TOO_LARGE`, `Content-Length: 0`). It sends `TOO_LARGE_CLOSE` and closes
when the request is complete already (`FullHttpMessage`), when `autoRead` is off, or when the
client neither expects `100-continue` nor keeps the connection alive. Otherwise it keeps the
connection and skips the rest of the body. The edge does not aggregate, so the class does not
fit, but its reactions are a template.

### JDK `com.sun.net.httpserver` (Temurin 25.0.5, `lib/src.zip`)

`sun.net.httpserver.ServerConfig` has `DEFAULT_MAX_REQ_HEADERS = 200` and
`DEFAULT_MAX_REQ_HEADER_SIZE = 380 * 1024`; `ServerImpl` answers a parse problem with 400 and
has no 413 or body size check (grep).

### RFC 9110 and RFC 6585

Not accessible offline. Unverified: RFC 9110 section 15.5.14 defines 413 Content Too Large, the
server may close the connection and may send `Retry-After` if the condition is temporary;
section 15.5.15 defines 414 URI Too Long; RFC 6585 defines 431 Request Header Fields Too Large.
Netty has the constants `REQUEST_ENTITY_TOO_LARGE`, `REQUEST_URI_TOO_LONG` and
`REQUEST_HEADER_FIELDS_TOO_LARGE` (`javap`).

### Summary

| Tool | Body limit by default | Request body | Header too large |
| --- | --- | --- | --- |
| NGINX 1.28.3 | 1 MiB, 413 | buffered completely | 400 (URI: 414) |
| HAProxy 3.2.27 | none | streamed | 431 (URI: 414) |
| Caddy 2.11.9 | none, opt-in 413 | streamed | 431 |
| Envoy 1.36.13 | none, opt-in 413 (buffer filter) | streamed | 431 |
| sensepitch edge | none | streamed, 64 KiB water mark | 400 (missing host, inferred) |

The evidence: the default limit of NGINX goes with buffering the body. The measured streaming
proxies have no limit by default and make it opt-in, which matches the project's philosophy.

## Design decisions (open)

None of these is decided. The choices are ticked in `TODO.md`.

### Default body limit

- [ ] A: no limit, as today. Backpressure bounds the memory. The README says so and that the
  upstream enforces its own limit.
- [ ] B: an optional limit, off by default, like Caddy and Envoy.
- [ ] C: a default limit like NGINX's 1 MiB. Against the philosophy of the entry, listed for
  completeness: it breaks uploads that work without the edge.

### Where an optional limit is configured (with B)

- [ ] `listen.connection.maxRequestBodyBytes`, one value for all sites.
- [ ] Per site and path route (`SiteConfig`, `PathRouteConfig`), the path route overriding the
  site, like NGINX's location. The limit is known after the site selection, so the check is a
  handler after `SiteSelectorHandler`, which also covers static responses and protection.
- [ ] Both: a global value with overrides per site and path route.

### How a too-large body is refused (with B)

- [ ] `Content-Length` over the limit: 413 with `Connection: close` before the body is read and
  before the upstream is connected, like NGINX and `HttpObjectAggregator`. The rest is not read
  and discarded, a client could keep sending.
- [ ] Chunked body over the limit: part of it is forwarded already. Close the upstream, answer
  413 with `Connection: close` if no response started, else only close the client connection,
  as `DownstreamHandler.completeWithError` does.
- [ ] `Expect: 100-continue` with a too-large `Content-Length` gets the 413 instead of
  `100 Continue`. Needs a check of the `Expect` handling of today first.

### Refusals are visible (with B and the header limits)

- [ ] A new `RequestOutcome`, e.g. `REQUEST_TOO_LARGE` (`request_too_large`), counted in
  `sensepitch_ingress_request_outcomes_total`. A new label value for dashboards and alerts.
- [ ] Only the status 413 in the request log, with `http.request.body.size`, no new outcome.

### Header and request line limits

- [ ] Keep the Netty defaults (4096 and 8192 bytes), but answer a decoder failure explicitly:
  check `decoderResult()` in the first handler after the codec and send 431 for
  `TooLongHttpHeaderException`, 414 for `TooLongHttpLineException`, 400 otherwise, with an
  outcome or counter. This is needed whatever the limits are, since today the failure is
  (inferred) reported as a missing host.
- [ ] Raise the defaults towards the streaming proxies (refused at: NGINX 9000 bytes, HAProxy
  16000, Caddy 32000, Envoy 70000). Large cookies and tokens exceed 8 KiB; the cost is memory per
  connection while the head is parsed.
- [ ] Make them configurable in `listen.connection` (`maxInitialLineLength`, `maxHeaderSize`),
  passed to `HttpServerCodec` with `HttpDecoderConfig`.

### Slow uploads

- [ ] A total time to receive the request, e.g. `listen.connection.requestTimeoutSeconds`,
  answered with 408, next to the idle `readTimeoutSeconds`.
- [ ] A minimum upload rate after a grace period. More complex; how other servers do it was not
  verified here.
- [ ] Only correct the `ConnectionConfig` doc: a body byte does reset the read timeout.

## Alternatives considered

- `HttpObjectAggregator` in the edge pipeline gives a limit and 413 for free, but buffers every
  body in memory. That contradicts streaming with backpressure, so it is rejected.
- Reading and discarding the rest of an oversized body to keep the connection alive costs
  bandwidth for a refused request and lets a client hold the connection. NGINX, Caddy and Envoy
  all closed the connection after the 413 in the measurements.

## Limitations

- No end-to-end test with a real slow client; the slow upload exposure is inferred from the code
  and the decoder experiment.
- HTTP/1.1 only. HTTP/2 and QUIC (open entries in `TODO.md`) have their own frame and flow
  control limits.
- The header thresholds of the other proxies were found by trying sizes; they are ranges, not
  exact values.

## Planned improvements

The open choices are in the `TODO.md` entry under "Ingress, HTTP".
