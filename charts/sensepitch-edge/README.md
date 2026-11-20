# Sensepitch Edge Helm chart

This chart runs a standalone Sensepitch Edge fleet from one `config.yml`. It is the packaging
step for TODO.md item 3: it creates a Deployment, Service, PodDisruptionBudget, a ConfigMap for
the configuration, and mounts Secrets for sensitive files. It does not install the Gateway API
controller or any Gateway API resources.

The chart is deliberately small. It assumes the image runs the shaded JAR with the configuration
file as its only argument. No image is published yet, see TODO.md item 2.

## Prerequisites

- Kubernetes 1.25 or newer.
- Helm 3.8 or newer.
- An image that starts `java -jar sensepitch-edge-*-with-dependencies.jar <config file>` and runs
  as a non-root user.
- A TLS certificate and key in a Secret. The edge always terminates TLS; it refuses to start
  without a default certificate.

Create the TLS Secret in the namespace of the release:

````
kubectl create secret tls sensepitch-edge-tls \
  --cert=server.crt --key=server.key --namespace sensepitch-edge
````

The Secret keys are configurable through `tls.certKey` and `tls.keyKey`; they are mounted as
`/etc/sensepitch/tls/tls.crt` and `/etc/sensepitch/tls/tls.key`. The default `config.listen.ssl`
uses those paths. With cert-manager, point `tls.existingSecret` at the certificate's `secretName`.

## Install

Prepare a values file with at least the image and the routes, then install:

````
cat > edge-values.yaml <<'EOF'
image:
  repository: registry.example.com/sensepitch-edge
  tag: "0.2-SNAPSHOT"

config:
  sites:
    www.example.com:
      upstream:
        target: shop.default.svc.cluster.local:8080
EOF

helm upgrade --install edge ./charts/sensepitch-edge \
  --namespace sensepitch-edge --create-namespace \
  --values edge-values.yaml
````

The default Service is `ClusterIP`. Set `service.type=LoadBalancer` or `NodePort` when the edge
is the public entry point. A `LoadBalancer` Service does not expose the admin or metrics ports.

## Configuration

`values.config` is rendered into the `config.yml` key of a ConfigMap. The chart adds two defaults
only when they are absent: `protection.disable: true` (there is no protection scheme without it)
and a static `localhost` site (the edge rejects an empty `sites` map). Supply `config.sites` and
`config.protection` to replace them. The file is mounted as a directory at
`/etc/sensepitch/config`, not with `subPath`, so the kubelet symlink swap is visible to
`ConfigFileWatcher`. It ends with `#END`, which enables reload after a complete update.

Keep these values aligned:

| Setting | Used for |
|---|---|
| `config.listen.address` | `0.0.0.0` or the pod IP, so the Service can reach the listener |
| `config.listen.httpsPort` | container port `https`; the Service forwards to it |
| `config.admin.address` | must be `0.0.0.0` so kubelet probes can reach the admin server |
| `config.admin.port` | container port `admin`; `/healthz` and `/readyz` probes |
| `config.metrics.prometheus.port` | container port `metrics`; Prometheus scraping |
| `config.shutdownTimeoutMillis` | checked against `terminationGracePeriodSeconds` |

The chart fails to render if `config.admin` is missing, if `terminationGracePeriodSeconds` is
less than `config.shutdownTimeoutMillis` plus 5 seconds, or if a ServiceMonitor is enabled while
metrics are disabled.

The admin endpoint has no authentication and can reload the configuration. It binds to
`0.0.0.0` only because kubelet probes need it; it is not part of the main Service. Restrict pod
network access with a NetworkPolicy if the namespace is shared with untrusted workloads.

### Admission token secrets

Create a Kubernetes Secret with an `admission` key in the release namespace. Use
`extraSecretMounts` to mount it and put only the file path in the ConfigMap.

```yaml
extraSecretMounts:
  - name: admission
    secretName: sensepitch-edge-admission
    mountPath: /etc/sensepitch/secrets
config:
  protection:
    deflector:
      tokenGenerators:
        - prefix: X
          secret:
            file: /etc/sensepitch/secrets/admission
```

The default configuration disables protection and serves a static response for `localhost`
for smoke testing. Configure your sites and protection before serving production traffic.

## Probes and shutdown

The Deployment uses the admin port for all three probes:

- startup and liveness: `GET /healthz`
- readiness: `GET /readyz`, which turns `503` when shutdown starts or when no configured network
  upstream accepts a plain TCP connection; a configuration with only static-response sites is
  ready immediately

Upstream connect attempts run sequentially with a one-second timeout per target. Several slow
targets can exhaust the chart's two-second readiness probe timeout before a reachable target is
checked.

`terminationGracePeriodSeconds` defaults to 30. The default `config` limits shutdown to 10
seconds (`shutdownTimeoutMillis`), of which 5 seconds may be spent accepting requests after
readiness turns false and 3 seconds are reserved for the request log. That leaves a 20-second
margin for Linux, kubelet, and the container runtime. A smaller margin than 5 seconds is rejected
at render time. If you raise the shutdown timeout, raise the grace period too; the chart checks
the rounded-up shutdown seconds and fails otherwise.

## Metrics

Metrics are served on the `metrics` container port (`/metrics`). The chart creates a separate
`ClusterIP` Service for it when `config.metrics.enable` is true; it is never added to the public
`https` Service.

For Prometheus installations that discover pods, the chart adds `prometheus.io/scrape`,
`prometheus.io/port`, and `prometheus.io/path` annotations. Set
`metrics.prometheusAnnotations.enabled=false` to turn them off.

For the Prometheus Operator, enable the optional ServiceMonitor. The
`monitoring.coreos.com/v1` CRD must already exist:

````
helm upgrade --install edge ./charts/sensepitch-edge \
  --namespace sensepitch-edge \
  --set serviceMonitor.enabled=true
````

The ServiceMonitor selects the separate metrics Service in the release namespace. Add labels
through `serviceMonitor.labels` if your Prometheus instance selects ServiceMonitors by label.

## Extra Secret files

Mount additional Secrets read-only for files the edge reads by path, for example the IPinfo
database:

````yaml
extraSecretMounts:
  - name: ipinfo
    secretName: sensepitch-edge-ipinfo
    mountPath: /etc/sensepitch/ipinfo

config:
  ipLookup:
    ipInfoPath: /etc/sensepitch/ipinfo/ipinfo.mmdb
````

The volume name is prefixed with `extra-`, so `name` only needs to be unique among these mounts.

## PodDisruptionBudget

The PDB defaults to `maxUnavailable: 1` and `replicaCount: 2`, so a voluntary node drain keeps
one pod serving. With one replica, `maxUnavailable: 1` permits the drain but a rollout has no
ready pod until the replacement starts. Set `podDisruptionBudget.minAvailable` instead of
`maxUnavailable` only when the replica count can honour it.

## Validation

The chart is validated with Helm alone; no cluster is needed for rendering:

````
helm lint charts/sensepitch-edge --strict
helm template edge charts/sensepitch-edge --namespace sensepitch-edge >/tmp/rendered.yaml
helm template edge charts/sensepitch-edge --namespace sensepitch-edge \
  --set serviceMonitor.enabled=true >/tmp/rendered-servicemonitor.yaml
````

The render fails as intended when the shutdown margin is too small:

````
helm template edge charts/sensepitch-edge \
  --set terminationGracePeriodSeconds=10
````

## Values

| Key | Default | Meaning |
|---|---|---|
| `replicaCount` | `2` | edge pods |
| `image.repository` | `ghcr.io/sensepitch/sensepitch-edge` | replace until item 2 publishes an image |
| `image.tag` | `""` | image tag; empty uses `.Chart.AppVersion` |
| `imagePullSecrets` | `[]` | pull Secrets for a private registry |
| `service.type` | `ClusterIP` | main HTTPS Service type |
| `service.port` | `443` | main Service port; target is the `https` container port |
| `tls.existingSecret` | `sensepitch-edge-tls` | Secret with the certificate and key |
| `tls.certKey` / `tls.keyKey` | `tls.crt` / `tls.key` | keys inside the TLS Secret |
| `extraSecretMounts` | `[]` | additional read-only Secret file mounts |
| `serviceMonitor.enabled` | `false` | create a Prometheus Operator ServiceMonitor |
| `metrics.prometheusAnnotations.enabled` | `true` | add pod scrape annotations |
| `podDisruptionBudget.enabled` | `true` | create the PDB |
| `podDisruptionBudget.maxUnavailable` | `1` | used unless `minAvailable` is set |
| `terminationGracePeriodSeconds` | `30` | must exceed the shutdown timeout by 5 seconds |
| `config` | see `values.yaml` | rendered into the `config.yml` ConfigMap |

The chart also passes through `podAnnotations`, `podLabels`, `nodeSelector`, `tolerations`,
`affinity`, `priorityClassName`, `resources`, and `env` for standard cluster integration.
