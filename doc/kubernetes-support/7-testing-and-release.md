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

## When a cluster is needed

TODO item 3 is the first cluster gate. Its CI job deploys the standalone chart to a disposable
single-node kind cluster on a GitHub-hosted Ubuntu runner using the locally built image.
The open Kubernetes items that follow item 3 start after that deployment check passes.

| Stage | Tool | Cluster |
|---|---|---|
| Java edge, converter, listeners, PROXY protocol | `EmbeddedChannel`, JSON fixtures | no |
| OCI image | `OciImageIT` with Docker | no |
| Manifests and Helm chart | `helm template` and `kubeconform`, offline schema check | no |
| Controller reconcilers, provisioner golden files | envtest: kube-apiserver and etcd inside `go test`, no nodes | no |
| Snapshot distribution, optional | envtest, a local edge JVM, hand-written EndpointSlices pointing to a local backend | no |
| Standalone edge deployment (TODO item 3) | single-node kind, Helm, locally loaded image | yes |
| Gateway API routing (development order items 5 and 6) | same kind setup with Gateway API CRDs and controller | yes |
| Dedicated fleets, conformance, parity, recovery, exposure matrix (item 7 on) | kind with cloud-provider-kind | yes |

End-to-end routing is the first Gateway API step that needs a cluster: it needs pods, the
network between them and EndpointSlices maintained by the endpoint controller, none of which
envtest has. This split is inferred from the tools' scope, not measured.

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

- Start running it once dedicated fleets work (development order item 7) and track failures,
  rather than waiting for the release.
- Why cloud-provider-kind: the suite creates many Gateways, each listening on port 80 and
  expecting its own address. cloud-provider-kind gives each LoadBalancer Service its own IP,
  reachable from the CI runner. k3s ServiceLB binds a hostPort on the node IP, so a second
  Gateway on port 80 cannot be scheduled; HostNetwork and NodePort cannot give one port 80
  address per Gateway either. MetalLB would work but needs an address pool and more setup.
  Inferred from the documentation of these tools, not tried.
- Schedule: the kind smoke test runs on every pull request; conformance and the exposure
  matrix take minutes and run nightly (`.github/workflows/nightly.yml`) and on pull requests
  that touch `controller/` or the chart.
- Recovery tests: controller restart, edge restart while the controller is down, Secret
  rotation, endpoint churn during load.
- Exposure matrix in kind: LoadBalancer with and without PROXY protocol, HostNetwork
  DaemonSet (node IPs in `status.addresses`), NodePort, unmanaged mode; shared and
  dedicated fleets, including listener conflicts between merged Gateways.
- Provisioner tests with envtest: generated objects per exposure mode (golden files),
  owner references, cleanup when a Gateway or parameters object is deleted.

## Packaging

- `Dockerfile` for the edge: multi-stage Maven build, runs the shaded JAR on
  `eclipse-temurin:25-jre`, non-root, `java` as PID 1. The base-image and registry choice is
  recorded in [oci-image.md](../design/oci-image.md); the image is
  `ghcr.io/sensepitch/sensepitch-edge` (matching the Helm chart).
- Controller image built from `controller/`.
- The [standalone Helm chart](../../charts/sensepitch-edge/README.md) deploys the edge directly
  with a ConfigMap, mounted Secrets, and admin probes.
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
