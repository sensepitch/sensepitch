# Architecture

## Overview

```text
                    Kubernetes API
                          │
                 Gateway API resources
                          │
                          ▼
                   Go Controller ───────────► status (GatewayClass, Gateway, HTTPRoute)
                          │  └── provisioner ──► edge fleets (Deployment / DaemonSet,
                          │                       Service, PDB, bootstrap ConfigMap)
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
                  Java / Netty Edge  (one or more fleets, see "Edge fleets and exposure")
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

**Provisioner** (same Go binary, separate reconciler group)

- Create and own the edge workloads for each fleet: Deployment or DaemonSet, Service,
  ServiceAccount, PodDisruptionBudget, bootstrap ConfigMap (including the `controlPlane`
  section). Owner references clean them up when the fleet goes away.
- Write Gateway `status.addresses`: the load balancer address, or the node IPs of the pods
  in HostNetwork mode.
- In unmanaged mode, create nothing and only find the edge pods by a label selector.

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
| Controller vs. operator | One controller-manager with two reconciler groups: Gateway API controller and provisioner. No separate operator binary |
| Data-plane deployment | Edge fleets provisioned by the controller from `SensepitchEdgeParameters`; shared or dedicated per Gateway; any exposure mode; unmanaged mode as escape hatch |
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

## Edge fleets and exposure

The provisioner deals with two independent concepts:

- A **fleet** is a set of edge pods that share one snapshot.
- **Exposure** is how traffic reaches those pods.

Both are configured in the `SensepitchEdgeParameters` resource. It is referenced from
`GatewayClass.spec.parametersRef` (defaults) and from
`Gateway.spec.infrastructure.parametersRef` (per Gateway):

```yaml
apiVersion: gateway.sensepitch.org/v1alpha1
kind: SensepitchEdgeParameters
metadata: { name: public }
spec:
  fleet: Shared                        # Shared: one fleet per parameters object
                                       # Dedicated: one fleet per Gateway
  workload:
    kind: DaemonSet                    # or Deployment
    replicas: 2                        # Deployment only
    nodeSelector: { edge.sensepitch.org/public: "true" }
    # tolerations, affinity, resources, topology spread
  exposure:
    mode: HostNetwork                  # LoadBalancer | NodePort | HostNetwork | ClusterIP | Unmanaged
    service: { annotations: {}, loadBalancerClass: "", externalTrafficPolicy: Local }
    hostNetwork: { addresses: from-node }   # or a list of explicit bind IPs
    addressType: ExternalIP            # which node IP goes into status.addresses
  downstream:                          # what sits in front of the edge
    proxyProtocol: none                # none | v1 | v2
    trustedProxies: []                 # CIDRs allowed to send X-Forwarded-For/-Proto
```

### The fleet is the selector

- With `fleet: Shared`, every Gateway that references the same parameters object is served
  by the same pods, so all of them share one IP.
- With `fleet: Dedicated`, each Gateway gets its own pods. They can be isolated further by
  `nodeSelector`.
- In a shared fleet, listeners from different Gateways are merged by port and told apart by
  SNI and hostname. Conflicts are reported in the listener status (`Conflicted`,
  `PortUnavailable`), as the Gateway API spec allows for merged Gateways.
- Routes choose their Gateway with `parentRefs`. Gateways restrict which routes may attach
  with `allowedRoutes.namespaces.selector`.

### Exposure variants

| Variant | Settings |
|---|---|
| Load balancer, L4 passthrough (TLS at the edge) | `mode: LoadBalancer`, `externalTrafficPolicy: Local`, or `proxyProtocol: v2` when the load balancer supports it |
| Load balancer terminates TLS | `mode: LoadBalancer` or `ClusterIP`, HTTP listeners only, `trustedProxies` or PROXY protocol; the edge takes the client IP and scheme from the forwarded headers |
| Direct node ingress, DNS spreading | `mode: HostNetwork`, `workload.kind: DaemonSet` on labelled nodes; `status.addresses` lists the node IPs; external-dns (Gateway / HTTPRoute source) publishes A records for all of them |
| NodePort | `mode: NodePort`, for setups with their own port forwarding |
| One IP per Gateway | `fleet: Dedicated`, with a load balancer per Gateway, or HostNetwork on separate node pools or explicit bind addresses (`Gateway.spec.addresses`) |
| One IP for all Gateways | `fleet: Shared` with any exposure mode |
| Escape hatch | `mode: Unmanaged`: the pods are installed by hand or by Helm; the controller finds them by a label selector and only serves snapshots |

DNS spreading uses the standard path: the controller writes `status.addresses` and
external-dns reads them. No separate DNS integration is needed. When a node leaves, the
controller removes its IP from the addresses before the pod drains. The drain time must be
at least the DNS TTL.

HostNetwork binds ports 80 and 443 on the node, so the provisioner adds the
`NET_BIND_SERVICE` capability to the generated pod spec.

### Client IP

Today the edge reads the client address from the TCP peer only. Depending on the exposure:

- HostNetwork, or a load balancer with `externalTrafficPolicy: Local`: the TCP peer is the
  client.
- A load balancer that supports it: PROXY protocol v1/v2 (Netty `HAProxyMessageDecoder`),
  enabled per fleet.
- A load balancer that terminates TLS: `X-Forwarded-For` and `X-Forwarded-Proto`, accepted
  only from `trustedProxies`.
