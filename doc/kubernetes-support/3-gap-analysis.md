# Gap Analysis: Existing Edge versus Gateway API

This compares the current code (state of commit `4336764`) with what
`2-gateway-api-scope.md` needs. Source paths are relative to
`src/main/java/org/sensepitch/edge/`.

## Configuration model

| Area | Existing | Gap |
|---|---|---|
| Root | `ProxyConfig` with `listen`, `sites`, `upstreams`, `protection`, `fallback`, … | New optional Gateway API section (listeners, routes, resolved backends) |
| Listeners | `ListenConfig`: one HTTPS port (`httpsPort`, default 17443), one address | Multiple listeners, plain HTTP listeners, per-listener hostname |
| Sites | `SiteConfig` keyed by exact host, optional `uri` prefix ending in `*` | Wildcard hostnames are rejected (`SiteSelector`); need route-level hostnames |
| Paths | `PathRouteConfig`: exact path or string prefix (`PrefixTree`) | Segment-aware PathPrefix; OR/AND match semantics; precedence across routes |
| Header match | Only cookie-name overrides (`RoutingOverrideConfig.whenCookie`) | Exact header matching |
| Filters | `ResponseConfig` can redirect (`location`, `status`) and set cookies | `RequestHeaderModifier`; full `RequestRedirect` (scheme, host, port, path, status) |
| Backends | `UpstreamConfig`: one `target` `host[:port]` | Multiple endpoints, weights, invalid-backend marker |
| TLS | `SslConfig(keyPath, certPath)`, `snis`, Let's Encrypt directory | Inline PEM from the snapshot; certificate swap at runtime |
| Unknown host | `UnservicedHost`: 400 or redirect | 404 for no matching route in Gateway API mode |

The config loader (`Main`, `config/RecordConstructor`, `EnvInjector`) builds immutable
Lombok `@Builder(toBuilder = true)` records via SnakeYAML. Because JSON is valid YAML, the
same loader can read JSON snapshots.

## Runtime behaviour

| Area | Existing | Gap |
|---|---|---|
| Reconfiguration | None. `Proxy` builds `SiteSelector`, SNI mapping, known hosts, `UnservicedHost` once into `final` fields | Immutable snapshot object behind an `AtomicReference`, read per request; delegating SNI `Mapping` |
| Validation | Exceptions in record and `SiteSelector` constructors | Reusable: build and validate a snapshot before swapping it, report errors |
| Metrics | `PrometheusMetricsBridge` starts its HTTP server inside the `Proxy` constructor | Move to `Main` so a rebuild does not register twice |
| Crawler DB | `DetectCrawler` parses the TSV on each `Deflector` construction | Cache it across rebuilds |
| Upstream connections | No pooling (pool code disabled in `DefaultUpstream`), DNS via Netty `Bootstrap` | Endpoint selection by weight; pooling optional |
| Health | No health or readiness endpoint | `/healthz`, `/readyz` on an opt-in admin port |
| Shutdown | Done: SIGTERM closes the listener, drains the connections, then the request log, within `shutdownTimeoutMillis` (see README) | Not ready on SIGTERM, once a readiness endpoint exists |
| Client IP | TCP peer, `X-Forwarded-For` set; no PROXY protocol | PROXY protocol v1/v2 per listener; incoming `X-Forwarded-For` / `-Proto` accepted only from trusted proxy CIDRs (load balancer terminating TLS) |
| Bind address | One `listen.address` for all listeners | Bind address per listener, so one node can serve several IPs (one IP per Gateway in HostNetwork mode) |
| Gateway merging | One listen configuration | Listeners from several Gateways merged by port in a shared fleet; hostname and SNI keep them apart |
| Reserved paths | Only the deflector's `/.sensepitch.challenge.*` paths | Block `/.well-known/sensepitch-edge/*` from external requests (profile exposure) |
| Response headers | Not sanitized (`FIXME` in `DownstreamHandler`) | Hop-by-hop header handling for conformance |

## Multiple replicas

- Admission tokens (`DefaultAdmissionTokenGenerator`) are stateless: SHA-256 over id and
  secret. All replicas need the same `tokenGenerators` secrets, which fits a Secret.
- The challenge (`TimeBasedChallenge`) is a timestamp in a ±5 minute window, stateless but
  not signed. Fine across replicas with synced clocks; signing it is a separate hardening item.
- No shared in-memory state between requests. Running several replicas is safe.

## Packaging

No Dockerfile, Helm chart or Kubernetes manifests exist. CI (`.github/workflows/ci.yml`)
runs `./mvnw clean verify` and uploads the shaded jar.

## Production configurations versus Gateway API

This section reviews the two production edge configurations: one for a shop's production
environment, one for its staging and demo environment. It lists what they use and whether
standard Gateway API resources can express it. Hostnames, cookie names and secrets are left
out on purpose.

### Covered by Gateway API

| Used today | Gateway API equivalent | Note |
|---|---|---|
| `sites` keyed by host | `HTTPRoute.spec.hostnames` | |
| Site `upstream` (`target` or `ref` to `upstreams`) | `backendRefs` to a Service | The `localhost:<port>` targets become Services |
| Global default `upstream`, used by sites without their own | — | No equivalent, but nothing is lost: every route names its backends, which is more verbose |
| Exact path (`/contact`) | `path: Exact` | |
| Path prefix ending in `/*` (`/feeds/*`, `/exports/*`, `/backoffice/*`) | `path: PathPrefix` | Small difference: `PathPrefix /feeds` also matches `/feeds` itself |
| Path routed to another upstream (cron backend for feeds and exports) | Additional rule with its own `backendRefs` | |
| Redirect to another host and path (`response.location`) | `RequestRedirect` filter | Not covered if the target has a query string, see gaps |
| `listen.hosts` (extra hosts redirected to `www.` by `UnservicedHost`) | Route for the bare host with `RequestRedirect` `hostname: www…`, status 301 | One small route per host |
| `unservicedHost.defaultLocation` | Catch-all route without hostnames on a listener without hostname: an `Exact /` rule redirects to `/`, a `PathPrefix /` rule redirects with `ReplaceFullPath /NOT_FOUND` | Works, but HTTPS needs a default certificate on that listener |
| `listen.httpsPort` | Gateway HTTPS listener | Gateway ports are the public ports (443). The edge listens on a container port, so the Service or the snapshot must map 443 to it |
| `listen.letsEncrypt` | `certificateRefs` to Secrets issued by cert-manager (Gateway API support) | HTTP-01 needs an HTTP listener, which the edge does not have yet. DNS-01 avoids that |
| Upstream outside the cluster (`target: 127.0.0.1:3080` for a path still served by old infrastructure) | Service without selector plus a manually maintained EndpointSlice | Works because the controller resolves EndpointSlices. `ExternalName` Services stay unsupported |
| Maintenance mode (swap the config file, restart) | Edit the HTTPRoute; the change is picked up without a restart | Needs a direct response, see gaps |

### Not covered by Gateway API

| Used today | Why Gateway API cannot express it | Proposal |
|---|---|---|
| Direct response with status and text (`/tower*` → 404 "Moved!", back-office paths → 404) | HTTPRoute has no direct response in Core or Extended | `ExtensionRef` filter that points to a small sensepitch resource (`status`, `contentType`, `text` or a ConfigMap reference). This is a standard extension point, not an application policy |
| Maintenance page (`response.status: 503`, `file: …html`) | Same as above; the body comes from a file | Same filter, with the body from a ConfigMap |
| Redirect to an absolute URL with a query string (`…/contact?lang=en`) | `RequestRedirect` can set scheme, host, port and path, but not the query | Same filter, with a `location` (the edge already supports this in `ResponseConfig`) |
| String prefix without a segment boundary (`/tower*` matches `/towers`) | `PathPrefix` matches whole segments only | Use `PathPrefix /tower` and accept the difference, or use `RegularExpression` (implementation-specific; the edge would have to implement it) |
| Protection on by default, `protection.disable` per host | Not part of Gateway API | See "Protection ownership" below |
| Deflector `bypass.uris`: payment webhooks, partner exports, data feeds, monitoring, Apple Pay domain verification. These are host-independent prefix patterns | Not part of Gateway API | Application-owned: a good fit for the application profile, because the application knows its webhooks and feeds |
| Deflector `bypass.remotes` (monitoring and partner IP addresses) | Not part of Gateway API | Owned by the administrator; does not belong in an application profile |
| Deflector `noBypass.uris`, including a pattern with a query string (`/index.php?controller=registration`) | Not part of Gateway API. Matching on the query is only Extended (`queryParams`) for routing | Application-owned (profile). The edge keeps its own raw-URI matching for protection |
| `tokenGenerators` (admission token secrets) | Not part of Gateway API | Edge bootstrap configuration from a Kubernetes Secret. Today it is in clear text in the configuration file |
| Cookie gates, global or per host (staging access, emergency "zero-day" lockdown with access URI and redirect) | Not part of Gateway API | Owned by the administrator. Must take effect within seconds in an emergency, so it should not depend on profile refresh intervals |
| `ipLookup.ipInfoPath` (IPinfo database) | Infrastructure, not routing | Edge bootstrap configuration. The `.mmdb` file has to reach the pod (volume, init container, or a periodic update) |
| `metrics.prometheus` | Infrastructure | Edge bootstrap configuration, plus a ServiceMonitor or scrape annotations in the Helm chart |

### Protection ownership

The production configurations mix settings that belong to different owners:

- **Application:** which paths are webhooks, feeds or verification endpoints (`bypass.uris`)
  and which must always be challenged (`noBypass.uris`). This fits the application
  profile (`5-application-profiles.md`).
- **Administrator:** whether protection is on for a host, trusted remote addresses, cookie
  gates, token secrets. These are settings an operator needs to change quickly and
  centrally. Today they are coupled to hosts and routes.

Gateway API has no place for the administrator part. Options:

1. Defaults in the GatewayClass `parametersRef`, plus per-host overrides keyed by hostname
   in the same object. Simple, but keyed by hostname instead of by Kubernetes object.
2. A policy resource attached to a Gateway or HTTPRoute (direct policy attachment,
   GEP-713). This is the Gateway API way for administrator policies, but it relaxes the
   "no CRDs" principle, which was written for application policies.
3. An `ExtensionRef` filter on route rules (for example "protection off" for one rule).
   This expresses per-path exceptions well, but not per-host defaults.

**Decision:** option 2 (`SensepitchPolicy`) holds all protection settings in M1, including
the application-owned path lists, because profiles only arrive in M2. From M2 on, profiles
may add application path lists; the policy stays authoritative.

### Consequences for the scope

The production configurations must be supported in M1 (`6-phases.md`). That needs Core
features plus two Sensepitch extensions, both defined in `2-gateway-api-scope.md`:

- **`SensepitchResponse`** (`ExtensionRef` filter): maintenance pages, "moved" responses,
  redirects with a query string.
- **`SensepitchPolicy`** (policy attachment): all protection settings.

In addition, M1 needs selector-less Services for backends outside the cluster, the edge
bootstrap from Kubernetes objects (token Secret, IP database, metrics), and a converter
from `config.yml` to these resources.

## Observations outside the scope

- Site-level prefix matching in `SiteSelector` uses a single `floorEntry`, not a longest
  prefix walk, so nested site prefixes can miss. Not affected by the Gateway API path, but
  worth fixing separately.
- Admission token expiry is not enforced; the caller only checks for a positive time.

## Result

The largest gap is routing semantics (multi-listener, hostname intersection, segment
prefixes, header matches, precedence, weights). The second is runtime reconfiguration.
Both are additive and leave the existing configuration untouched.
