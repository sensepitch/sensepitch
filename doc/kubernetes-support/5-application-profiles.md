# Application Profiles

Applications can provide gateway configuration of their own through:

```http
GET /.well-known/sensepitch-edge/profile
```

The interface is optional and owned by the application. **The first release only builds the
discovery and configuration lifecycle; no application-specific functionality uses the
profile yet.** Candidates for later are bot protection settings, rate limiting,
authentication and caching.

## Format

- JSON with an explicit `schemaVersion`.
- Published JSON schema; unknown major versions are rejected, unknown fields in a known
  version are ignored with a warning.
- Documented as its own specification (step 8 in `6-phases.md`).

## Discovery

- Done by the Go controller, not by the edge.
- Only backends referenced by accepted HTTPRoutes are queried. The profile applies to that
  backend (backend-scoped).
- Requests go to the backend endpoints from the EndpointSlice, with the Host header of the
  route.
- HTTP caching with `ETag` / `If-None-Match` and `Cache-Control: max-age`, plus a periodic
  refresh with an upper and lower bound.
- A changed profile produces a new snapshot version (field `profiles`, keyed by backend).

## Rolling deployments

Different endpoints of one backend may return different profiles while a rollout runs.
The controller detects this (different ETag or content across endpoints), keeps the last
consistent profile, and reports the inconsistency in an event and metric until it resolves.

## Exposure

`/.well-known/` is public by convention (RFC 8615; ACME, `security.txt`, OpenID discovery
live there, and crawlers probe it). A normal HTTPRoute with `PathPrefix /` would forward
the profile path to the application, so anyone could read the profile through the edge.
Profiles will later carry protection settings (bypass URIs, cookie-gate names, limits), so
they must not be readable from outside.

The path stays, because it is conventional and easy to discover, but with these rules:

1. **Reserved prefix in the edge.** The edge never forwards external requests for
   `/.well-known/sensepitch-edge/*`; it answers 404 itself. The check runs early in the
   pipeline, before route matching, the same way the deflector reserves
   `/.sensepitch.challenge.*`. Any request that reaches the application on this path
   therefore comes from inside the cluster. The block is active in Gateway API mode or when
   profiles are enabled, so existing standalone setups are unaffected.
2. **Optional dedicated port.** If the backend Service has a port with
   `appProtocol: sensepitch.org/profile` (or named `sensepitch-profile`), the controller
   fetches from that port instead of the traffic port. Applications with a management port
   (as for metrics or health) can keep the profile off the public listener entirely.
3. **No secrets in profiles.** Profiles carry settings, never credentials. Secrets stay in
   the edge bootstrap Secret or in Kubernetes Secrets.
4. **Controller marker.** The controller sends `User-Agent: sensepitch-controller/<version>`.
   This identifies the request; it is not authentication.
5. **Network policy.** The Helm chart documents an optional NetworkPolicy that allows the
   controller to reach backend pods.

## Security

- Exposure rules: see [Exposure](#exposure).
- Fetch only from referenced, authorized backends; never from arbitrary hosts.
- Do not follow redirects.
- Enforce connect/read timeouts and a response size limit.
- A profile can only set application-level settings; it can never override administrator
  settings (listeners, TLS, bootstrap secrets, global limits).
- On an invalid profile, keep the last valid one (explicit failure policy, configurable per
  GatewayClass later).

## Missing profiles

A 404 or an unreachable profile endpoint is valid and means "no profile". It must not affect
standard Gateway API routing or route status.
