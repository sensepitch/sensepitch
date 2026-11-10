# Architecture

## Overview

```text
                    Kubernetes API
                          │
                 Gateway API resources
                          │
                          ▼
                   Go Controller ───────────► status (GatewayClass, Gateway, HTTPRoute)
                          │
              ┌───────────┴────────────┐
              │                        │
       Resolve Kubernetes       Discover application
       references                profiles internally
              │                        │
       Services / Secrets        /.well-known/
       EndpointSlices            sensepitch-edge/profile
       ReferenceGrants                 │
              │                        │
              └───────────┬────────────┘
                          │
            Versioned configuration snapshot
           (edge pulls it; edge acknowledges it)
                          │
                          ▼
                  Java / Netty Edge  (shared Deployment + LoadBalancer Service)
                          │
                   Gateway listeners
                   HTTPRoute matching
                   Request filtering
                   Backend forwarding
                          │
                          ▼
                    Applications
```

## Responsibilities

**Go controller**

- Watch and reconcile `GatewayClass`, `Gateway`, `HTTPRoute`, `ReferenceGrant`,
  `Service`, `EndpointSlice`, `Secret` and `Namespace`.
- Validate listeners, route attachment (`parentRefs`, `allowedRoutes`, hostname
  intersection) and cross-namespace references.
- Resolve references: backend endpoints from EndpointSlices, TLS material from Secrets.
- Discover application profiles (see `5-application-profiles.md`).
- Serve versioned configuration snapshots to the edge instances and track their
  acknowledgements.
- Write status conditions: `Accepted`, `Programmed`, `ResolvedRefs`, listener status,
  Gateway addresses.
- Only the elected leader writes status. Every replica can compute and serve snapshots.

**Java edge**

- Understand Gateway API routing semantics natively.
- Configure listeners and TLS termination (SNI).
- Match requests against HTTPRoutes, apply Core filters, select weighted backends.
- Validate a new snapshot fully, then activate it atomically; keep the old one on failure.
- Report the applied snapshot version (or the validation error) and readiness.
- Keep working standalone from YAML/env configuration.

## Key decisions

| Decision | Direction |
|---|---|
| Repository | Monorepo: Go controller in `controller/`, independent Go and Maven builds |
| Gateway API version | Pin one release (standard channel) and its matching conformance suite |
| Configuration model | Gateway API-aligned Java records, next to the existing `*Config` records |
| Configuration transport | Versioned snapshots, pulled by the edge (HTTP long-poll), explicit acknowledgement |
| Data-plane deployment | One shared edge Deployment per GatewayClass, installed by Helm; dedicated per-Gateway deployments later |
| Route evaluation | Java runtime |
| Kubernetes reference resolution | Go controller |
| Backend addressing | Pod endpoints from EndpointSlices (not Service DNS), so weights and endpoint changes are exact |
| Profile ownership / discovery | Backend application owns it; Go controller discovers it |
| Profile applicability | Backend-scoped, optional |
| Standalone mode | Supported; Gateway API records may also be given in the YAML file |

## Important constraint: resolution, not translation

Gateway API resources contain Kubernetes references that Netty cannot use. The snapshot
therefore carries the resources **plus resolved data**:

- `backendRefs` → list of endpoint addresses and ports, with weight, or a marker that the
  reference is invalid (so the edge returns 500 as required).
- `certificateRefs` → PEM certificate chain and key.
- Route attachment → only routes accepted for a listener are included, already intersected
  with the listener hostname.

The route rules themselves (matches, filters, precedence) stay in Gateway API form and are
evaluated by the edge. Precedence must not be pre-flattened by the controller in a way that
hides the rules from the edge's own tests.

## Relationship to the existing edge configuration

The existing model (`ProxyConfig`, `SiteConfig`, `PathRouteConfig`, `UpstreamConfig`, …)
stays. Gateway API configuration is a new, parallel section of `ProxyConfig`. Both feed the
same request pipeline (`SanitizeHostHandler` → `UnservicedHost` → site/route selection →
protection → `DownstreamHandler` / upstream).

Open question: whether to later re-implement the legacy `sites` model on top of the
Gateway API route engine (one matcher instead of two). Recommended once Core conformance is
reached, not before.

## Sensepitch-specific features in Kubernetes mode

Bot protection (deflector, cookie gates, crawler detection), JA4, IP traits and fallback
pages are not part of Gateway API. For the first release:

- Global settings (IP lookup, JA4, metrics, admission token secrets) come from the edge's
  bootstrap configuration, mounted from a ConfigMap and a Secret by the Helm chart.
- Protection settings come from the `SensepitchPolicy` resource attached to a Gateway or
  HTTPRoute, and fixed responses from the `SensepitchResponse` filter (both in M1, see
  `2-gateway-api-scope.md`). Existing production configurations must work in M1.
- From M2 on, application profiles may add application-owned path lists (bypass,
  no-bypass); the policy stays authoritative.

## Client IP

The edge reads the client address from the TCP peer and has no PROXY protocol support.
The Helm chart sets `externalTrafficPolicy: Local` on the edge Service. PROXY protocol
support is a follow-up for load balancers that need it.
