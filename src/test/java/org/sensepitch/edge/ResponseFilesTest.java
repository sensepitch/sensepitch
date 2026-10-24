package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// @author Jens Wilke
class ResponseFilesTest {

  @Test
  void classpath() {
    assertThat(read("classpath:fallback/unavailable_page.html")).isEqualTo("<html>down</html>");
    assertThat(read("classpath:/fallback/unavailable_page.html")).isEqualTo("<html>down</html>");
  }

  @Test
  void schemelessFallsBackToClasspath() {
    assertThat(read("fallback/unavailable_page.html")).isEqualTo("<html>down</html>");
  }

  @Test
  void filesystem(@TempDir Path tempDir) throws IOException {
    Path pageFile = tempDir.resolve("page.html");
    Files.writeString(pageFile, "<html>disk</html>");
    assertThat(read(pageFile.toAbsolutePath().toString())).isEqualTo("<html>disk</html>");
    assertThat(read("file:" + pageFile.toAbsolutePath())).isEqualTo("<html>disk</html>");
    assertThat(read(pageFile.toUri().toString())).isEqualTo("<html>disk</html>");
  }

  @Test
  void missingRelativeNamesAbsolutePath() {
    assertThatThrownBy(() -> ResponseFiles.read("does-not-exist.html"))
        .isInstanceOf(UncheckedIOException.class)
        .rootCause()
        .hasMessageContaining(Path.of("does-not-exist.html").toAbsolutePath().toString());
  }

  @Test
  void missing() {
    assertThatThrownBy(() -> ResponseFiles.read("classpath:does/not/exist.html"))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("does/not/exist.html");
    assertThatThrownBy(() -> ResponseFiles.read("file:/does/not/exist.html"))
        .isInstanceOf(UncheckedIOException.class);
  }

  private static String read(String location) {
    return new String(ResponseFiles.read(location), StandardCharsets.UTF_8);
  }
}
