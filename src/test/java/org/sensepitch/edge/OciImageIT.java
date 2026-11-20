package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/// Exercises the built OCI image through Docker when the oci-image Maven profile is enabled.
@EnabledIfSystemProperty(named = "ociImage.enabled", matches = "true")
class OciImageIT {

  @TempDir Path dir;

  @Test
  void servesTrafficAndShutsDownOnSigterm() throws Exception {
    Path output = dir.resolve("oci-smoke.log");
    Process smoke =
        new ProcessBuilder(
                "bash", "src/test/resources/oci-smoke.sh", System.getProperty("ociImage"))
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    try {
      assertThat(smoke.waitFor(90, TimeUnit.SECONDS))
          .as("OCI smoke test timed out; output:%n%s", Files.readString(output))
          .isTrue();
      assertThat(smoke.exitValue())
          .as("OCI smoke test output:%n%s", Files.readString(output))
          .isZero();
    } finally {
      if (smoke.isAlive()) {
        smoke.destroy();
        if (!smoke.waitFor(TestingParameters.TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          smoke.destroyForcibly();
        }
      }
    }
  }
}
