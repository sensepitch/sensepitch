# OCI image

Covers the `Dockerfile` at the repository root, the `.dockerignore` next to it, the
`DockerfileTest` that validates their structure, and the proposed CI workflow in
`DOT-GITHUB-UPDATED/.github/workflows/build-and-push-image.yml`. The document records why the
image is built the way it is; how to run the edge itself is in `README.md`, and the Kubernetes
deployment that consumes the image is the Helm chart of TODO.md item 3.

## Purpose and scope

The image is the packaging that runs the standalone edge on Kubernetes (TODO.md item 2). The
constraints that bound the design:

- The edge is a self-contained shaded JAR, `target/sensepitch-edge-*-with-dependencies.jar`,
  produced by the shade plugin with `org.sensepitch.edge.Main` as the main class. `java -jar`
  runs it with the configuration file as the only argument (`Main`).
- The project targets Java 25 (`maven.compiler.release` in `pom.xml`) and is built and tested
  with Temurin 25 (`distribution: temurin` in `.github/workflows/ci.yml`).
- The shaded JAR bundles native code. `ShadedJarIT` checks the `netty-tcnative-boringssl-static`
  libraries shipped in the JAR: ELF for linux x86_64 and aarch_64, Mach-O for macOS and PE for
  Windows. There is no musl build, so the runtime image must be glibc-based.
- Kubernetes sends `SIGTERM` to PID 1 of the container, so `java` must be PID 1 for the graceful
  shutdown in `README.md` to run; that means exec-form `ENTRYPOINT`, no shell in between.
- The pod must run without privileges: the Helm chart sets `runAsUser`/`runAsGroup` 65532.

## Overview

Two stages. The `build` stage is `eclipse-temurin:25-jdk`; it copies the repository (including
`.git`) and runs the Maven wrapper to produce the shaded JAR, then renames it to a stable
`sensepitch-edge.jar`. Tests, documentation, CI files and the performance setup are excluded from
the build context: the image build skips tests, and the full gate runs before it. The runtime
stage is `eclipse-temurin:25-jre`; it copies that one file, switches to the non-root user and sets
the exec-form entry point. The final image contains only the JRE and the JAR.

## Design decisions

### Temurin JRE on glibc, not Alpine

The runtime image is `eclipse-temurin:25-jre`. Alpine is smaller, but its musl libc cannot load
the bundled tcnative libraries (see the native library list in `ShadedJarIT`, verified against
the shaded JAR at build time). Temurin is also the distribution the project builds and tests with,
so the runtime JRE and the build JDK stay on the same vendor and patch cadence. Consequence: the
image is Ubuntu-sized, but no glibc workaround is needed and the native TLS code path is the same
one the tests exercise.

### Multi-stage build, the shaded JAR is the only artifact

The build runs in a JDK image and is discarded; only `target/*-with-dependencies.jar` is copied
out, under the stable name `/app/sensepitch-edge.jar`. Tests are skipped inside the image build
(`-Dmaven.test.skip=true`): the full gate `./mvnw clean verify` already ran in CI before the image
is built, and running it again in the image would only duplicate it. The shade plugin still runs,
because it is bound to the `package` phase. `-Djacoco.skip` and `-Dspotless.apply.skip` keep the
package phase from doing work the image does not need.

### Non-root UID 65532 and exec-form ENTRYPOINT

`USER 65532:65532` matches the Helm chart's `runAsUser`/`runAsGroup`. The numeric IDs need no
`/etc/passwd` entry, so the same image works with the chart's `readOnlyRootFilesystem` and no
image-specific user database. `ENTRYPOINT ["java", "-jar", "/app/sensepitch-edge.jar"]` is exec
form: Docker starts `java` directly as PID 1, `SIGTERM` reaches it, and the shutdown sequence
described in `README.md` (accept grace, drain, request log) runs. The default `CMD`
`/etc/sensepitch/config/config.yml` matches the chart's `args`, so `docker run image <config>`
overrides it per container.

### Registry ghcr.io/sensepitch/sensepitch-edge

The registry and repository name match the Helm chart's `image.repository` default, so the chart
works once an image is published without overriding values. GitHub Container Registry keeps the
image next to the code, is free for public repositories, and authenticates with the workflow's
`GITHUB_TOKEN`. The proposed workflow tags `main` as `0.2-SNAPSHOT` (the chart's `appVersion`)
and `latest`, and tags `v*` releases with their semver.

### `.git` stays in the build context

`.dockerignore` does not exclude `.git`, on purpose: the git-commit-id plugin is bound to the
`initialize` phase and stamps the unique `Implementation-Version` into the JAR manifest, exactly
like a local `./mvnw package`. The build stage is discarded, so `.git` never reaches the final
image. Excluding it would make the image build silently produce a JAR without the commit stamp.

### A structural test, because the Maven gate has no container runtime

`DockerfileTest` runs in `./mvnw test`. It parses the Dockerfile instructions as Docker would
join continued lines, then checks the two stages, the Maven command and renamed shaded JAR, the
copy into the runtime stage, the non-root user, the exec-form entry point and default command, and
the exposed ports. It also checks that `.dockerignore` keeps `.git` while dropping build output,
IDE files, tests and documentation, and that the proposed workflow stays under
`DOT-GITHUB-UPDATED/`. This catches a packaging regression before an image is built, without
requiring Docker or a network connection in the unit test. The test is structural: it does not
prove that the base image and downloaded dependencies exist, so the proposed workflow still runs
the real `docker build`.

## Alternatives considered

### distroless Java 25

Google's distroless Java images are Debian-based (glibc), so they would load the native
libraries. They ship no shell and no package manager, which reduces the attack surface but makes
`kubectl exec` debugging harder and rules out ad-hoc tooling in the image. The decision went to
Temurin JRE to keep the runtime on the same vendor as build and test; distroless remains a valid
alternative and was not measured offline (no network access in this environment).

### Alpine / musl

Rejected: the shaded JAR has no musl build of the tcnative library, so the TLS listener could
not start. The JAR would need musl natives built and added first, which is out of scope.

### Building with the Maven cache baked into an image layer

A cache layer holding `/root/.m2` would avoid re-downloading dependencies, but it would also
bloat the build stage and go stale. The Dockerfile uses a BuildKit cache mount instead, which
keeps the downloads out of the layers; whether it survives between runs depends on the builder
cache backend of the CI runner (the workflow configures the `gha` cache backend).

## Limitations

- `eclipse-temurin:25-jre` and `eclipse-temurin:25-jdk` are floating tags: they track the newest
  25.x patch. Strict reproducibility needs a digest pin; the digest cannot be determined without
  network access and is left as a planned improvement.
- The structural `DockerfileTest` in the Maven gate checks the image files but does not build or
  run the image. A real `docker build` and a smoke run happen only in the proposed CI workflow.
- The image is built for the runner architecture (`linux/amd64`). A multi-architecture manifest
  is left for when an arm64 fleet is needed.
- The image build downloads all Maven dependencies inside the container; the first build in a
  fresh CI cache is slow.

## Planned improvements

- Pin both base images by digest, refreshed by a scheduled job.
- Build a multi-architecture manifest (linux/amd64, linux/arm64).
- Add an image smoke test in CI: run the image with a test config and assert `/healthz` and
  `/readyz` answer 200 and `SIGTERM` exits.
- Switch the chart and workflow to the release version tag once the project is released.
