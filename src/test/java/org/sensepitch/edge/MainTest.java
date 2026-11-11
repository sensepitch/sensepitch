package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Loading the configuration file given on the command line, see [Main]
///
/// @author Jens Wilke
class MainTest {

  @Test
  void loadConfig(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("edge.yaml");
    Files.writeString(file, "listen:\n  httpsPort: 8443\nsites:\n  www.example.com:\n");
    ProxyConfig cfg = Main.loadConfig(file);
    assertThat(cfg.listen().httpsPort()).isEqualTo(8443);
    assertThat(cfg.sites()).containsKey("www.example.com");
  }

  @Test
  void missingConfigFile(@TempDir Path dir) {
    Path missing = dir.resolve("missing.yaml");
    assertThatThrownBy(() -> Main.loadConfig(missing))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("missing.yaml");
  }
}
