# Testing and Release

## Java edge

- Unit tests with `EmbeddedChannel` and `proxy.addHttpHandlers(pipeline)`, no Kubernetes:
  - hostname intersection, wildcard hostnames
  - exact and segment-aware prefix paths, header matches, OR/AND semantics
  - precedence and tie-breaking across routes
  - weighted backend selection, invalid backends (500), no route (404)
  - `RequestHeaderModifier`, `RequestRedirect`
  - snapshot swap during a keep-alive connection; invalid snapshot keeps the old state
  - SNI certificate swap
- A shared set of routing test cases (JSON fixtures) that both the Java tests and the
  controller's tests use, so both sides agree on semantics.
- Gate: `./mvnw verify` (tests, Spotless, Javadoc).

## Go controller

- Unit tests for attachment, reference resolution and status logic.
- Golden-file tests: Gateway API objects in, snapshot JSON out.
- envtest for reconcilers against a real API server.

## Integration and conformance

- kind cluster in CI: install Gateway API CRDs (pinned standard channel), the Helm chart,
  and run the conformance suite against dedicated fleets behind LoadBalancer Services
  (cloud-provider-kind provides the addresses), the setup most implementations use:

  ```sh
  go test ./conformance -run TestConformance -args \
    --gateway-class=sensepitch \
    --conformance-profiles=GATEWAY-HTTP \
    --report-output=conformance-report.yaml
  ```

- Start running it once dedicated fleets work (development order item 6) and track failures,
  rather than waiting for the release.
- Recovery tests: controller restart, edge restart while the controller is down, Secret
  rotation, endpoint churn during load.
- Exposure matrix in kind: LoadBalancer with and without PROXY protocol, HostNetwork
  DaemonSet (node IPs in `status.addresses`), NodePort, unmanaged mode; shared and
  dedicated fleets, including listener conflicts between merged Gateways.
- Provisioner tests with envtest: generated objects per exposure mode (golden files),
  owner references, cleanup when a Gateway or parameters object is deleted.

## Packaging

- `Dockerfile` for the edge: multi-stage, Maven build, `eclipse-temurin:25-jre` (or a
  distroless Java 25 image), non-root.
- Controller image built from `controller/`.
- Helm chart:
  - Gateway API CRDs are not bundled; they are a documented prerequisite. The Sensepitch
    CRDs (`SensepitchEdgeParameters`, `SensepitchPolicy`, `SensepitchResponse`) are bundled.
  - Controller Deployment with RBAC (including creating Deployments, DaemonSets, Services,
    PodDisruptionBudgets and ConfigMaps for the provisioner) and leader election.
  - No edge Deployment: the provisioner creates the edge fleets. The generated pods have
    probes on the admin port and a `terminationGracePeriodSeconds` larger than the drain
    time.
  - Admission-token Secret, referenced by the generated bootstrap ConfigMap.
  - The `GatewayClass` and a default `SensepitchEdgeParameters` (values select the default
    exposure mode).
  - An example manifest for unmanaged mode, for users who want to write the edge
    Deployment themselves.

## Release

- CI builds and tests both modules, runs conformance, and publishes images and the chart
  on tags.
- Publish the conformance report with each release.
- Documentation: installation, architecture (this directory, condensed), configuration
  reference, profile specification, examples.
