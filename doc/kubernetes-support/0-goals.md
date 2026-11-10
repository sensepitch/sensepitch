# Kubernetes Support — Goals

Sensepitch Edge becomes an **open-source, general-purpose Kubernetes Gateway API
implementation**. The existing Java/Netty HTTP gateway is its data plane. A new Go
controller is its control plane.

## Documents

| File | Content |
|---|---|
| `0-goals.md` | Goals, principles, scope boundaries, compatibility promise |
| `1-architecture.md` | Target architecture, responsibilities, key decisions |
| `2-gateway-api-scope.md` | Gateway API resources and features in scope |
| `3-gap-analysis.md` | What the existing edge has compared with what the scope needs |
| `4-configuration-distribution.md` | Snapshot protocol and how the edge activates configuration at runtime |
| `5-application-profiles.md` | Discovery through `/.well-known/sensepitch-edge/profile` |
| `6-phases.md` | Implementation steps, estimates, milestones and sequence |
| `7-testing-and-release.md` | Conformance, test strategy, packaging and release |

## Goals

1. Expose applications on Kubernetes through standard `GatewayClass`, `Gateway` and
   `HTTPRoute` resources served by Sensepitch Edge.
2. Pass the official Gateway API **Core conformance** tests for Gateway and HTTPRoute.
3. Apply configuration changes (routes, backends, certificates) **without restarting** the
   gateway.
4. Let applications publish optional, application-owned gateway configuration ("profiles")
   that the controller discovers internally.
5. Provide an independently installable controller, a Helm chart, OCI images,
   documentation and automated releases.

## Design principles

1. **Gateway API as native configuration.** The edge uses Gateway API resource structures
   and semantics directly. There is no proprietary intermediate routing language.
2. **Separate control and data planes.** A Go controller handles Kubernetes integration. The
   Java gateway handles HTTP traffic.
3. **Reference resolution, not translation.** The controller resolves Kubernetes references
   (Services, EndpointSlices, Secrets, ReferenceGrants) into data that Netty can use. It
   does not rewrite routes into another model.
4. **Application-owned configuration.** Applications expose extra configuration through
   `/.well-known/sensepitch-edge/profile`. No Kubernetes-specific application policy CRDs.
   Administrator settings that Gateway API cannot express (bot protection, fixed responses)
   use two small extension resources, `SensepitchPolicy` and `SensepitchResponse`.
7. **Existing setups first.** The first milestone runs the current production edge
   configurations on Kubernetes with the same behaviour.
5. **Standalone operation.** The Java gateway stays usable without Kubernetes, configured
   from YAML or environment variables as today.
6. **Standards compliance first.** Implement Core features correctly before adding Extended
   ones.

## Technology stack

| Component | Technology |
|---|---|
| Controller | Go, controller-runtime, Kubebuilder |
| Kubernetes integration | Gateway API Go libraries (`sigs.k8s.io/gateway-api`), client-go |
| Data plane | Java 25, Netty 4.2 (existing code base) |
| Native configuration | Gateway API-aligned Java records |
| Configuration transport | Versioned JSON snapshots over HTTP (gRPC as an option later) |
| Application profiles | JSON, HTTP-based discovery |
| Packaging | OCI images, Helm |
| Testing | JUnit 5, AssertJ, Serenity BDD, Go tests, envtest, Gateway API conformance suite |

## Compatibility promise for existing setups

The Kubernetes work must not break the current standalone deployment:

- Existing YAML and `SENSEPITCH_EDGE_*` environment configuration keeps its keys, defaults
  and behaviour.
- All new configuration (listeners, Gateway API routes, admin endpoint, control plane
  connection, inline PEM) is **optional and off by default**.
- Any change that would rename, remove or reinterpret existing keys, change a default, or
  add a mandatory setting must be called out explicitly in the phase that introduces it.

Currently no incompatible change is planned.

## Out of scope for the first release

| Feature | Reason |
|---|---|
| Rate limiting | Future application-profile feature |
| Authentication policies | Future application-profile feature |
| Application-controlled caching | Future application-profile feature |
| GRPCRoute | Future protocol support |
| TCPRoute / UDPRoute / TLSRoute | Future protocol support |
| Extended HTTPRoute filters | Follow after Core conformance |
| Distributed configuration storage | Not needed initially |
| Multi-cluster routing | Future extension |
| Service mesh support | Different architecture |
| Kubernetes-specific application policy CRDs | Avoided by design |
| HTTP/2 downstream | Not required for Core; existing edge serves HTTP/1.1 |

## Normative references

- [Gateway API Implementer's Guide](https://gateway-api.sigs.k8s.io/guides/implementers-guide/)
- [HTTPRoute specification](https://gateway-api.sigs.k8s.io/reference/api-types/httproute/)
- [Running the conformance tests](https://gateway-api.sigs.k8s.io/docs/conformance/running-tests/)
