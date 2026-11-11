package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;

/// @author Jens Wilke
class ResourceLoaderTest {

  @Test
  void fileList() {
    assertThat(ResourceLoader.getFileList("crawler-ips/"))
        .isNotEmpty()
        .allSatisfy(name -> assertThat(name).startsWith("crawler-ips/"));
  }

  @Test
  void missingResource() {
    assertThatThrownBy(() -> ResourceLoader.loadTextFile("does/not/exist.txt"))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("does/not/exist.txt");
    assertThatThrownBy(() -> ResourceLoader.loadBinaryFile("does/not/exist.bin"))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("does/not/exist.bin");
  }
}
