# Configuration Distribution

## Edge bootstrap

The edge starts from a small bootstrap configuration (YAML file or `SENSEPITCH_EDGE_*`
environment variables, as today):

- metrics, IP lookup, JA4, request log
- admission token secrets (mounted from a Kubernetes Secret)
- `admin`: port for `/healthz` and `/readyz`, drain time (new, optional)
- `controlPlane`: controller URL, token file, edge identity (new, optional)

Without `controlPlane`, the edge runs standalone exactly as today.

## Snapshot

A snapshot is one JSON document containing everything the data plane needs for routing:

```json
{
  "version": 42,
  "listeners": [
    { "name": "default/web/https", "port": 443, "protocol": "HTTPS",
      "hostname": "*.example.com",
      "tls": { "certificates": [ { "certPem": "…", "keyPem": "…" } ] } }
  ],
  "routes": [
    { "name": "shop/storefront", "creationTimestamp": "…",
      "parents": [ "default/web/https" ],
      "hostnames": [ "shop.example.com" ],
      "rules": [
        { "matches": [ { "path": { "type": "PathPrefix", "value": "/" } } ],
          "filters": [],
          "backends": [
            { "weight": 1, "endpoints": [ "10.1.2.3:8080", "10.1.2.4:8080" ] } ] } ] }
  ],
  "profiles": {}
}
```

- Field names follow the Gateway API types. Kubernetes references are replaced by resolved
  data (`endpoints`, PEM). A backend that could not be resolved carries an `invalid` reason.
- Java side: new records (`GatewayListenerConfig`, `HttpRouteConfig`, `HttpRouteRuleConfig`,
  `HttpRouteMatchConfig`, `HttpRouteFilterConfig`, `BackendConfig`, …), loaded through the
  existing `RecordConstructor`. The schema is versioned; the edge rejects unknown major
  versions.
- The same records can be written in the standalone YAML file.

## Transport

- The controller debounces Kubernetes changes (a few hundred milliseconds) before it builds
  a new snapshot version, so a batch of applied objects becomes one version.

- The edge pulls: `GET /v1/snapshot?known=<version>` with long-polling. The controller
  answers immediately when a newer version exists, otherwise holds the request up to a
  timeout.
- Acknowledgement: the next request carries `applied=<version>` or
  `rejected=<version>&reason=…`, plus the pod identity.
- Authentication: a projected ServiceAccount token checked by the controller via
  TokenReview. The controller endpoint is cluster-internal only.
- The controller sets `Programmed=True` on a Gateway once all ready edge pods have applied a
  version containing it. A rejected snapshot is reported in Gateway/route status and logged.
- Edge pods need no Kubernetes RBAC.

gRPC streaming can replace long-polling later without changing the snapshot content.

## Activation in the edge

1. Parse the snapshot into records.
2. Build a complete immutable `EdgeState`: route tables per listener, SNI mapping, known
   hosts, SSL contexts, backend selectors. Constructors validate; an exception rejects the
   snapshot and the old state stays active.
3. Swap the `AtomicReference<EdgeState>`.
4. Handlers (`SanitizeHostHandler`, `UnservicedHost.Handler`, `SiteSelectorHandler`) read
   the current state once per request; keep-alive connections use the new state on their
   next request. `SniHandler` uses a delegating `Mapping`.
5. Listener ports that appear or disappear are bound or closed. Changed ports are bound
   before old ones close.
6. Acknowledge the version.

`/readyz` reports ready only after the first snapshot has been applied.

## Failure behaviour

- Controller unreachable: keep serving the last applied snapshot, retry with backoff.
- Edge restarts while controller is down: not ready until a snapshot arrives. (Optional
  later: persist the last snapshot to an `emptyDir`.)
- Invalid snapshot: keep the old state, acknowledge with `rejected`.
