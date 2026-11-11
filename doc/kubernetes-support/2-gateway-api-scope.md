# Gateway API Scope

The first release targets the **Core** support level of the pinned Gateway API version for
Gateway and HTTPRoute (conformance profile `GATEWAY-HTTP`).

## Kubernetes resources

| Resource | Required functionality |
|---|---|
| `GatewayClass` | Controller name `sensepitch.org/gateway-controller`, `Accepted` status |
| `Gateway` | HTTP/HTTPS listeners, hostnames, ports, TLS, addresses, listener status |
| `HTTPRoute` | Matching, filters, backends, precedence, parent status |
| `ReferenceGrant` | Cross-namespace reference permissions |
| `Service` | Backend identification and port resolution |
| `EndpointSlice` | Backend endpoint discovery and updates |
| `Secret` | TLS certificates |
| `Namespace` | `allowedRoutes` namespace selectors |

## Gateway

- HTTP and HTTPS listeners, multiple listeners and ports.
- Listener hostname matching, including wildcard hostnames.
- TLS termination with SNI, certificates from Secrets, certificate updates without restart.
- Route attachment via `parentRefs` (including `sectionName` and `port`).
- `allowedRoutes` (same namespace, all, selector; route kinds).
- Listener conflict detection (same port/protocol/hostname) with correct conditions.
- Gateway `status.addresses`: the load balancer address, or the node IPs in HostNetwork
  mode. `spec.addresses` selects explicit bind addresses or load balancer IPs where the
  exposure mode supports them.
- `spec.infrastructure`: `labels` and `annotations` are copied to the generated objects;
  `parametersRef` points to a `SensepitchEdgeParameters` object.
- Edge fleets (see `1-architecture.md`): Gateways that share a parameters object with
  `fleet: Shared` are merged into one fleet, with listeners merged by port. With
  `fleet: Dedicated`, every Gateway gets its own fleet.

## HTTPRoute

**Request matching**

| Feature | First release |
|---|---|
| Exact hostname | Yes |
| Wildcard hostname | Yes |
| Exact path | Yes |
| PathPrefix (segment-aware) | Yes |
| Header matching (exact) | Yes |
| Multiple matches and rules | Yes |
| Routing precedence | Yes |
| HTTP method matching | Later (Extended) |
| Query parameter matching | Later (Extended) |
| Regular expression paths | Later |

**Backends**

| Feature | First release |
|---|---|
| Single Service backend | Yes |
| Multiple Service backends | Yes |
| Weighted traffic distribution | Yes |
| Dynamic endpoint discovery | Yes |
| Cross-namespace backend references (with ReferenceGrant) | Yes |
| Invalid backend handling (HTTP 500, `ResolvedRefs=False`) | Yes |
| External backends | Later |

**Filters**

| Filter | First release |
|---|---|
| `RequestHeaderModifier` | Yes |
| `RequestRedirect` | Yes |
| `ResponseHeaderModifier` | Later |
| `URLRewrite` | Later |
| `RequestMirror` | Later |
| `CORS` | Later |
| `ExtensionRef` | Yes, for `SensepitchResponse` only |

If the pinned version's Core set differs from these tables, the pinned version wins.

## Routing semantics the edge must implement

- Hostname intersection between listener and route.
- Exact and segment-aware prefix path matching (`/foo` matches `/foo` and `/foo/bar`, not
  `/foobar`).
- OR semantics between the matches of a rule, AND semantics within one match.
- Precedence and deterministic tie-breaking across all routes attached to a listener:
  exact path over prefix, longer prefix first, more header matches first, then oldest
  route by creation timestamp, then namespace/name alphabetically.
- Backend selection by weight; a weight of zero is never selected; no valid backend gives 500.
- Gateway API defaults (for example a missing match means `PathPrefix /`).
- No matching route gives 404.
- Reserved prefix `/.well-known/sensepitch-edge/` takes precedence over all routes and is
  answered with 404 by the edge (see `5-application-profiles.md`, Exposure). Conformance
  tests do not use this path, so this does not affect conformance.

All of these must be covered by Java unit tests independent of Kubernetes.

## Sensepitch extensions (first release)

The production configurations need two things Gateway API cannot express (see
`3-gap-analysis.md`). Both are in M1. They live in the API group
`gateway.sensepitch.org/v1alpha1`, are resolved by the controller, and reach the edge as
existing Java records (`ResponseConfig`, `ProtectionConfig`).

A third resource, `SensepitchEdgeParameters`, configures the edge fleets and how they are
exposed. It is used by the provisioner only and does not affect routing. It is described
in `1-architecture.md`, "Edge fleets and exposure".

### `SensepitchResponse` (HTTPRoute `ExtensionRef` filter)

The edge answers the request itself; the rule needs no `backendRefs`.

```yaml
apiVersion: gateway.sensepitch.org/v1alpha1
kind: SensepitchResponse
metadata: { name: maintenance }
spec:
  status: 503
  contentType: text/html
  bodyFrom: { configMapKeyRef: { name: maintenance-page, key: index.html } }
  # alternatively: text: "404 from the edge"
  # or a redirect: location: https://example.com/contact?lang=en  (status 302 by default)
```

Used for fixed error pages, maintenance pages, and redirects that `RequestRedirect` cannot
express (absolute URL with a query string).

**Spec**, aligned with the edge's `ResponseConfig`:

| Field | Meaning |
|---|---|
| `status` | Status code. A page may use 100–599 (default 200); a redirect 301, 302, 303, 307 or 308 (default 302) |
| `contentType` | Content type of a page |
| `text` | Page body |
| `bodyFrom.configMapKeyRef` | Page body from a ConfigMap key (instead of `text`) |
| `location` | Redirect target, any absolute or relative URL including a query string |
| `cookies` | Cookies to set, as `SetCookieConfig` |

- A response is either a page (`text` or `bodyFrom`, not both) or a redirect (`location`).
  The controller checks the same rules as the `ResponseConfig` constructor and reports
  violations in the route status.
- The controller resolves `bodyFrom` into `text`, so the edge never reads files in
  Kubernetes mode. A change to the ConfigMap produces a new snapshot.
- A rule that uses the filter has no `backendRefs`, and the filter is its only filter. A rule
  with both is rejected (`Accepted=False`, reason `UnsupportedValue`).
- A missing `SensepitchResponse` or ConfigMap gives `ResolvedRefs=False`; the rule then
  answers 500, like an invalid backend.

#### Worked example

A shop site migrated from the standalone configuration: the default goes to the shop, feeds
and exports go to a cron backend, the contact page redirects to another host with a query
string, and the back office is blocked at the edge.

```yaml
apiVersion: gateway.sensepitch.org/v1alpha1
kind: SensepitchResponse
metadata: { name: edge-404, namespace: shop }
spec:
  status: 404
  text: "404 from the edge"
---
apiVersion: gateway.sensepitch.org/v1alpha1
kind: SensepitchResponse
metadata: { name: contact-de, namespace: shop }
spec:
  location: https://hello.example.com/contact?lang=de
---
apiVersion: gateway.sensepitch.org/v1alpha1
kind: SensepitchResponse
metadata: { name: maintenance, namespace: shop }
spec:
  status: 503
  contentType: text/html
  bodyFrom:
    configMapKeyRef: { name: maintenance-pages, key: maintenance.html }
---
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute
metadata: { name: shop-de, namespace: shop }
spec:
  parentRefs: [ { name: edge, namespace: edge-system } ]
  hostnames: [ www.example.de ]
  rules:
    - backendRefs: [ { name: shop, port: 80 } ]
    - matches:
        - path: { type: PathPrefix, value: /feeds }
        - path: { type: PathPrefix, value: /exports }
      backendRefs: [ { name: shop-cron, port: 80 } ]
    - matches: [ { path: { type: Exact, value: /kontakt } } ]
      filters:
        - type: ExtensionRef
          extensionRef: { group: gateway.sensepitch.org, kind: SensepitchResponse, name: contact-de }
    - matches: [ { path: { type: PathPrefix, value: /backoffice } } ]
      filters:
        - type: ExtensionRef
          extensionRef: { group: gateway.sensepitch.org, kind: SensepitchResponse, name: edge-404 }
```

The maintenance page comes from a ConfigMap:

```sh
kubectl -n shop create configmap maintenance-pages --from-file=maintenance.html
```

**Maintenance mode:** replace the default rule's `backendRefs` with the filter. The more
specific rules (`/kontakt`, `/backoffice`, `/feeds`) keep working, as with a site-level
`response` today, and the edge applies the change without a restart.

```yaml
    - filters:
        - type: ExtensionRef
          extensionRef: { group: gateway.sensepitch.org, kind: SensepitchResponse, name: maintenance }
```

#### Object model and consistency

- **Storage:** every YAML document is its own API object with its own etcd key
  (`/registry/<group>/<resource>/<namespace>/<name>`). Custom resources are stored like
  built-in ones; the CRD only defines the schema, which the API server validates.
- **The edge stays out of it:** the edge never talks to etcd or the API server. The
  controller watches with informers (list, then watch into an in-memory cache) and compiles
  all objects into one snapshot.
- **No atomic apply:** applying a multi-document file creates the objects one by one. The
  controller debounces changes (a few hundred milliseconds) before building a snapshot. A
  route that briefly references a missing response shows `ResolvedRefs=False`, and that
  rule answers 500 until the response exists. Create responses and ConfigMaps before the
  routes that use them; GitOps tools usually apply them together.
- **Lifetime:** responses are not owned by a route. Deleting a route leaves shared
  responses such as `edge-404` in place.
- **Limits:** about 1.5 MiB per object, 1 MiB per ConfigMap, which is enough for
  maintenance pages.

### `SensepitchPolicy` (direct policy attachment)

Owned by the administrator. Attached to a Gateway (defaults for all hosts) or to an
HTTPRoute (applies to the route's hostnames; the route policy overrides the Gateway
policy per field, like site over global protection today).

```yaml
apiVersion: gateway.sensepitch.org/v1alpha1
kind: SensepitchPolicy
metadata: { name: default-protection }
spec:
  targetRefs: [ { group: gateway.networking.k8s.io, kind: Gateway, name: edge } ]
  disable: false
  bypass: { uris: [ "/feeds/*" ], remotes: [ "192.0.2.10" ] }
  deflector:
    bypass: { uris: [ "/webhook*" ], remotes: [] }
    noBypass: { uris: [ "/contact" ] }
  cookieGates:
    - { name: staging-access, accessUri: /staging-access, redirectUrl: / }
```

- The fields mirror `ProtectionConfig`, so the existing edge code applies them unchanged.
- Admission token secrets are not part of the policy. They stay in the edge bootstrap Secret.
- The edge selects protection per host. If routes for the same hostname carry different
  policies, the oldest route's policy wins and the other policies get `Conflicted=True` in
  status.
- In M2, application profiles may add bypass and no-bypass URIs; the policy stays
  authoritative and is merged last.
