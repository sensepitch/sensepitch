# Implementation Phases

Estimates assume one experienced developer who knows the Java/Netty code base. One day is
about eight hours. The ranges add up; parallel work does not reduce total effort.

## Steps and estimates

| # | Step | Content | Estimate |
|---|---|---|---:|
| 1 | Architecture assessment | Gap analysis (`3-gap-analysis.md`), pin Gateway API version, define snapshot schema | 2–3 days |
| 2 | Go controller foundation | Kubebuilder in `controller/`, watches, RBAC, leader election, build, image | 2–4 days |
| 3 | Gateway API controller integration | GatewayClass, Gateway, HTTPRoute, listeners, attachment, EndpointSlices (including selector-less Services for external backends), TLS, ReferenceGrant, status | 10–16 days |
| 4 | Java configuration alignment | Gateway API records, listener model, matching, precedence, Core filters, weighted backends; per-listener bind address, PROXY protocol v1/v2, trusted forwarded headers | 7–13 days |
| 5 | Configuration distribution | Snapshot protocol per fleet, `EdgeState` swap, listener rebinding, acknowledgements, admin endpoint | 4–7 days |
| 6 | Provisioner and exposure modes | `SensepitchEdgeParameters` CRD; Deployment/DaemonSet, Service, PDB and bootstrap ConfigMap generation; shared and dedicated fleets, Gateway merging; LoadBalancer, NodePort, HostNetwork, ClusterIP and unmanaged mode; `status.addresses` from the load balancer or node IPs; external-dns example (see `1-architecture.md`) | 6–10 days |
| 7 | Sensepitch extensions | `SensepitchResponse` filter and `SensepitchPolicy` (see `2-gateway-api-scope.md`): CRDs, controller resolution and status, edge support reusing `ResponseConfig` and `ProtectionConfig` | 4–7 days |
| 8 | Production parity | Edge bootstrap for token Secret, IP database, metrics; converter from existing `config.yml` to Gateway API resources; parity tests against the production configurations | 3–5 days |
| 9 | Profile specification | JSON schema, versioning, discovery protocol, extensibility | 2–3 days |
| 10 | Profile discovery | HTTP discovery, caching, refresh, validation, failure handling, optional profile port, edge-side block of the reserved profile path | 5–8 days |
| 11 | Security and observability | Trust boundaries, graceful shutdown, status, metrics, logging | 6–10 days |
| 12 | Integration and conformance testing | Conformance suite in CI, kind-based integration, recovery and regression tests | 8–15 days |
| 13 | Public distribution | OCI images, Helm chart, CI/CD release, docs, examples | 3–6 days |
| | **Total** | | **62–107 days** |

## Milestones

### M1 — Production configurations on Kubernetes (steps 1–8, 38–65 days)

M1 must run the existing production edge configurations (see "Production configurations
versus Gateway API" in `3-gap-analysis.md`) on Kubernetes with the same behaviour.

- Working Go controller with GatewayClass, Gateway and HTTPRoute reconciliation.
- Java edge using Gateway API-aligned configuration.
- Dynamic configuration distribution, HTTP and HTTPS routing, certificates from Secrets
  (issued by cert-manager).
- Edge fleets provisioned by the controller, shared and dedicated, with all exposure modes:
  LoadBalancer (passthrough or TLS-terminating in front), NodePort, HostNetwork with DNS
  spreading through external-dns, and unmanaged.
- EndpointSlice-based backend discovery and weighted forwarding, including backends outside
  the cluster through selector-less Services.
- `SensepitchResponse` filter: fixed responses (404 with text, maintenance page with a body
  from a ConfigMap) and redirects to absolute URLs with a query string.
- `SensepitchPolicy`: protection on by default, per-host disable, deflector bypass and
  no-bypass URIs, trusted remote addresses, cookie gates.
- Edge bootstrap from Kubernetes objects: admission token secrets from a Secret, IP
  database file in a volume, Prometheus metrics.
- A converter (`java -jar … convert config.yml`) that emits Gateway API and Sensepitch
  resources from an existing configuration file.

**Acceptance:**
- Both production configurations convert without manual edits, except for documented
  deviations (`/tower*` becomes `PathPrefix /tower`).
- A parity test sends the same request matrix (hosts × paths × with and without admission
  cookie, cookie gate, bypass address) to a standalone edge with `config.yml` and to the
  edge in kind with the converted resources. Status, `Location` and the selected backend
  must match.
- Configuration changes, including switching to the maintenance page, take effect without
  restarting the edge.

### M2 — Application profile discovery (steps 9–10, cumulative 45–76 days)

- Documented profile protocol, JSON schema and validation.
- Discovery through backend Services, HTTP caching, periodic refresh.
- Dynamic updates; defined behaviour for missing and invalid profiles.
- Applications can contribute their own bypass and no-bypass URIs through the profile; the
  `SensepitchPolicy` stays authoritative for administrator settings.

**Acceptance:** an application publishes and updates its profile, and the edge receives
validated updates without restarting.

### M3 — Public release candidate (steps 11–13, cumulative 62–107 days)

- Conformance results, status reporting, error handling, security hardening.
- Metrics and logging, Helm installation, published images, documentation, automated
  release pipeline.

**Acceptance:** a user installs Sensepitch Edge on a supported cluster, configures standard
Gateway API resources, and the Core conformance tests pass.

## Development order

The table groups work by area. To reduce rework, build in this order:

1. **Configuration model.** Finish the gap analysis, define the Gateway API Java records and
   the snapshot schema. Load them from a standalone YAML file first.
2. **Converter early.** Write the `config.yml` converter right after the model. Running it
   on the production configurations shows early whether the model covers them.
3. **Edge runtime basics.** `EdgeState` behind an `AtomicReference`, metrics moved out of the
   `Proxy` constructor, admin endpoint (`/healthz`, `/readyz`), Dockerfile.
4. **Minimal controller.** Watch GatewayClass, Gateway and HTTPRoute; serve snapshots.
5. **End-to-end routing.** The smallest working path, with one HTTP listener and one backend:

   ```text
   GatewayClass → Gateway → HTTPRoute → Service/EndpointSlice → Netty edge → backend
   ```

   This validates the controller-to-Java architecture before the complex semantics.
   Use a shared fleet behind a LoadBalancer Service here, since it is the quickest
   working setup.
6. **Provisioner and exposure.** HostNetwork DaemonSet with node IPs in `status.addresses`
   and external-dns (the production target), then dedicated fleets, NodePort and unmanaged
   mode. Dedicated fleets must work before the conformance suite runs, because it creates
   many Gateways and expects each one to get an address. Run the conformance suite in CI
   from here on and track the failing tests.
7. **Complete HTTPRoute Core.** Matching, precedence, filters, multiple backends, weights,
   ReferenceGrant, invalid backends.
8. **TLS and lifecycle.** HTTPS listeners from Secrets, certificate updates, status,
   graceful shutdown with DNS-aware draining, PROXY protocol and trusted forwarded headers,
   recovery behaviour.
9. **Sensepitch extensions and parity.** `SensepitchResponse`, `SensepitchPolicy`, edge
   bootstrap from Kubernetes objects, parity tests. This completes M1.
10. **Application profiles.** Specification, discovery, integration into snapshots.
11. **Conformance and release.** Close remaining gaps, harden, publish the first release.

## Rules for each phase

- State incompatible setup changes first (expected: none).
- Before edits: `./mvnw spotless:check` and `./mvnw test`. After: `./mvnw verify` and review
  `git diff`.
- New config records get `///` Markdown Javadoc.

## Main risks

1. Matching the Gateway API precedence and attachment semantics exactly with the existing
   pipeline design.
2. Runtime listener rebinding and certificate swapping in Netty without dropping
   connections.
3. Conformance tests that depend on details the edge does not handle yet (hop-by-hop
   headers, Host handling, response codes).
4. Protection is selected per host in the edge today, while Gateway API attaches policies to
   routes. Several routes for one hostname with different policies must be detected and
   reported as a conflict.
5. HostNetwork mode: ports 80/443 may already be taken on a node (another ingress, a node
   service). The provisioner must report this in the Gateway status instead of leaving a
   crash-looping pod.
6. DNS spreading: clients keep cached node IPs for the TTL. Draining must wait for it, and
   a failed node stays in DNS until the controller notices and external-dns syncs.
7. Shared fleets: listeners from several Gateways on the same port and hostname conflict.
   Detecting and reporting this has to follow the Gateway API rules for merged Gateways.
