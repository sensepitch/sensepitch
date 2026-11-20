package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Validates the OCI image build files in the repository root, so the Maven gate catches a
/// Dockerfile that would no longer run the shaded JAR as a non-root PID 1 on the pinned Java 25
/// JRE. The why of each property is in `doc/design/oci-image.md`; the Dockerfile itself is not
/// executed here, only its structure is checked.
class DockerfileTest {

  /// The build and runtime stages use the pinned Temurin 25 JDK and JRE, and the shaded JAR is
  /// built with the Maven wrapper.
  @Test
  void buildsTheShadedJarOnTemurin25() throws IOException {
    List<String> instructions = instructions();

    assertThat(instructions)
        .contains("FROM eclipse-temurin:25-jdk AS build", "FROM eclipse-temurin:25-jre");
    assertThat(instructions)
        .anyMatch(line -> line.startsWith("RUN --mount=type=cache,target=/root/.m2 "));
    assertThat(instructions).anyMatch(line -> line.contains("./mvnw"));
    assertThat(instructions).anyMatch(line -> line.contains("-Dmaven.test.skip=true"));
    assertThat(instructions).anyMatch(line -> line.contains("-Djacoco.skip"));
    assertThat(instructions).anyMatch(line -> line.contains("-Dspotless.apply.skip"));
    assertThat(instructions).anyMatch(line -> line.contains(" package "));
    assertThat(instructions)
        .anyMatch(line -> line.contains("mv target/*-with-dependencies.jar sensepitch-edge.jar"));
    assertThat(instructions)
        .contains("COPY --from=build /build/sensepitch-edge.jar /app/sensepitch-edge.jar");
  }

  /// `java` is PID 1: exec-form entry point, no shell, with the mounted config as default.
  @Test
  void javaIsPid1AndRunsTheShadedJar() throws IOException {
    List<String> instructions = instructions();

    assertThat(lastInstruction(instructions, "ENTRYPOINT"))
        .as("ENTRYPOINT")
        .isEqualTo("ENTRYPOINT [\"java\", \"-jar\", \"/app/sensepitch-edge.jar\"]");
    assertThat(lastInstruction(instructions, "CMD"))
        .as("CMD")
        .isEqualTo("CMD [\"/etc/sensepitch/config/config.yml\"]");
    assertThat(instructions).noneMatch(line -> line.startsWith("SHELL "));
  }

  /// The final user is the Helm chart's non-root UID/GID, so the pod runs without privileges.
  @Test
  void runsAsTheChartsNonRootUser() throws IOException {
    List<String> instructions = instructions();

    assertThat(lastInstruction(instructions, "USER")).as("USER").isEqualTo("USER 65532:65532");
    assertThat(instructions).noneMatch(line -> line.startsWith("USER root"));
  }

  /// The container exposes the HTTPS, metrics and admin ports used by the Helm chart and README.
  @Test
  void exposesTheChartPorts() throws IOException {
    List<String> instructions = instructions();

    assertThat(instructions).contains("EXPOSE 17443 9400 9401");
  }

  /// The build context excludes build output and IDE files, and keeps `.git` for the version
  /// stamp, see the `.dockerignore` comment.
  @Test
  void dockerignoreKeepsGitAndDropsBuildOutput() throws IOException {
    Path dockerignore = repoRoot().resolve(".dockerignore");
    assertThat(dockerignore).as(".dockerignore").isRegularFile();

    List<String> patterns = new ArrayList<>();
    for (String line : Files.readAllLines(dockerignore)) {
      String trimmed = line.trim();
      if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
        patterns.add(trimmed);
      }
    }
    assertThat(patterns)
        .contains(
            "target/",
            "target-ide/",
            "dependency-reduced-pom.xml",
            ".idea/",
            ".vscode/",
            "*.iml",
            ".github/",
            "doc/",
            "performance-test/",
            "plans/",
            "src/test/");
    assertThat(patterns).noneMatch(line -> line.equals(".git") || line.startsWith(".git/"));
  }

  /// The CI workflow stays proposed until reviewed, and would publish the chart's image.
  @Test
  void proposedWorkflowIsInactiveAndPushesTheChartImage() throws IOException {
    assertThat(repoRoot().resolve(".github/workflows/build-and-push-image.yml"))
        .as("active workflow")
        .doesNotExist();
    Path workflow =
        repoRoot().resolve("DOT-GITHUB-UPDATED/.github/workflows/build-and-push-image.yml");
    assertThat(workflow).as("proposed workflow").isRegularFile();

    List<String> lines = Files.readAllLines(workflow);
    assertThat(lines).anyMatch(line -> line.contains("docker/build-push-action@"));
    assertThat(lines).anyMatch(line -> line.trim().equals("push: true"));
    assertThat(lines).anyMatch(line -> line.contains("ghcr.io/sensepitch/sensepitch-edge"));
  }

  /// The Dockerfile instructions from the repository root.
  static List<String> instructions() throws IOException {
    return instructions(Files.readAllLines(repoRoot().resolve("Dockerfile")));
  }

  /// Splits the file into Dockerfile instructions, joining lines that continue with `\` and
  /// skipping comments and blank lines.
  static List<String> instructions(List<String> lines) {
    List<String> result = new ArrayList<>();
    StringBuilder current = null;
    for (String raw : lines) {
      String line = raw.trim();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      boolean continues = line.endsWith("\\");
      String content = continues ? line.substring(0, line.length() - 1).trim() : line;
      if (current == null) {
        current = new StringBuilder(content);
      } else {
        current.append(' ').append(content);
      }
      if (!continues) {
        result.add(current.toString());
        current = null;
      }
    }
    if (current != null) {
      result.add(current.toString());
    }
    return result;
  }

  /// The last instruction whose keyword is `keyword`, or `null` when there is none.
  static String lastInstruction(List<String> instructions, String keyword) {
    String found = null;
    for (String instruction : instructions) {
      if (instruction.startsWith(keyword + " ")) {
        found = instruction;
      }
    }
    return found;
  }

  /// The repository root: the Surefire working directory (`basedir`), or a walk up from the
  /// current directory, looking for the `pom.xml` and the `Dockerfile` together.
  static Path repoRoot() {
    String property = System.getProperty("basedir");
    Path start = property == null ? Path.of("").toAbsolutePath() : Path.of(property);
    for (Path dir = start; dir != null; dir = dir.getParent()) {
      if (Files.isRegularFile(dir.resolve("pom.xml"))
          && Files.isRegularFile(dir.resolve("Dockerfile"))) {
        return dir;
      }
    }
    throw new AssertionError("repository root with pom.xml and Dockerfile not found from " + start);
  }
}
