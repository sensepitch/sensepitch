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
  and run the conformance suite:

  ```sh
  go test ./conformance -run TestConformance -args \
    --gateway-class=sensepitch \
    --conformance-profiles=GATEWAY-HTTP \
    --report-output=conformance-report.yaml
  ```

- Start running it at the end-to-end milestone (development step 4) and track failures,
  rather than waiting for the release.
- Recovery tests: controller restart, edge restart while the controller is down, Secret
  rotation, endpoint churn during load.

## Packaging

- `Dockerfile` for the edge: multi-stage, Maven build, `eclipse-temurin:25-jre` (or a
  distroless Java 25 image), non-root.
- Controller image built from `controller/`.
- Helm chart:
  - CRDs are not bundled; Gateway API CRDs are a documented prerequisite.
  - Controller Deployment with RBAC and leader election.
  - Edge Deployment, LoadBalancer Service with `externalTrafficPolicy: Local`,
    PodDisruptionBudget, probes on the admin port, `terminationGracePeriodSeconds` larger
    than the drain time.
  - Bootstrap ConfigMap and admission-token Secret.
  - The `GatewayClass`.

## Release

- CI builds and tests both modules, runs conformance, and publishes images and the chart
  on tags.
- Publish the conformance report with each release.
- Documentation: installation, architecture (this directory, condensed), configuration
  reference, profile specification, examples.
