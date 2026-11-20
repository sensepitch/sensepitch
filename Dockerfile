# syntax=docker/dockerfile:1

# Build stage: compile the project and produce the shaded JAR. The full test
# gate (./mvnw clean verify) runs in CI before the image build, so only the JAR
# is needed here and tests are skipped. The Maven repository is a BuildKit cache
# mount, so dependency downloads survive between image builds.
#
# The .git directory is deliberately kept in the build context (it is not in
# .dockerignore) so the git-commit-id plugin can stamp the unique version into
# the JAR manifest, exactly like a local ./mvnw package.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /build

COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -Dmaven.test.skip=true -Djacoco.skip -Dspotless.apply.skip package \
    && mv target/*-with-dependencies.jar sensepitch-edge.jar

# Runtime stage: a slim JRE. The base is glibc-based Ubuntu, which is required
# by the netty-tcnative native libraries bundled in the shaded JAR (they ship
# for glibc Linux, macOS and Windows, not musl), see doc/design/oci-image.md.
FROM eclipse-temurin:25-jre
WORKDIR /app

# Non-root, matching the Helm chart's runAsUser/runAsGroup. The numeric IDs need
# no /etc/passwd entry and work with readOnlyRootFilesystem.
USER 65532:65532

COPY --from=build /build/sensepitch-edge.jar /app/sensepitch-edge.jar

# Exec form, no shell: java is PID 1 and receives SIGTERM directly, so the
# graceful shutdown in README.md runs. CMD is the mounted config.yml, matching
# the Helm chart and overridable per container (docker run image <config>).
ENTRYPOINT ["java", "-jar", "/app/sensepitch-edge.jar"]
CMD ["/etc/sensepitch/config/config.yml"]

# https, metrics, admin (see charts/sensepitch-edge/values.yaml).
EXPOSE 17443 9400 9401
