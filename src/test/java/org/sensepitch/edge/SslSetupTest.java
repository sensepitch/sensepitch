package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// @author Jens Wilke
@SuppressWarnings("unchecked")
class SslSetupTest {

  @Test
  public void missingHosts() {
    ListenConfig listenConfig =
        ListenConfig.builder()
            .letsEncrypt(true)
            .letsEncryptPrefix("classpath:/letsencrypt/live/")
            .build();
    assertThatThrownBy(() -> Proxy.initializeSniMapping(listenConfig, Collections.EMPTY_SET))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void missingSetup() {
    ListenConfig listenConfig = ListenConfig.builder().hosts(List.of("example.org")).build();
    assertThatThrownBy(() -> Proxy.initializeSniMapping(listenConfig, Collections.EMPTY_SET))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void sniSetup() {
    ListenConfig listenConfig =
        ListenConfig.builder()
            .hosts(List.of("example.org"))
            .snis(
                List.of(
                    SniConfig.builder()
                        .host("example.org")
                        .ssl(
                            SslConfig.builder()
                                .certPath("classpath:/ssl/test.crt")
                                .keyPath("classpath:/ssl/test.key")
                                .build())
                        .build()))
            .build();
    var mapping = Proxy.initializeSniMapping(listenConfig, Collections.EMPTY_SET);
    var obj = mapping.map("example.org");
    assertThat(obj).isNotNull();
  }

  @Test
  public void letsEncrypt() {
    ListenConfig listenConfig =
        ListenConfig.builder()
            .hosts(List.of("example.org"))
            .letsEncrypt(true)
            .letsEncryptPrefix("classpath:/letsencrypt/live/")
            .build();
    var mapping = Proxy.initializeSniMapping(listenConfig, Collections.EMPTY_SET);
    var obj = mapping.map("example.org");
    assertThat(obj).isNotNull();
  }

  @Test
  void openClasspathResource() throws Exception {
    try (InputStream in = Proxy.open("classpath:ssl/test.crt")) {
      assertThat(in.read()).isNotNegative();
    }
    try (InputStream in = Proxy.open("classpath:/ssl/test.crt")) {
      assertThat(in.read()).isNotNegative();
    }
  }

  @Test
  void openMissingResource() {
    assertThatThrownBy(() -> Proxy.open("classpath:ssl/missing.crt"))
        .isInstanceOf(FileNotFoundException.class)
        .hasMessageContaining("ssl/missing.crt");
    assertThatThrownBy(() -> Proxy.open("/does/not/exist.crt"))
        .isInstanceOf(FileNotFoundException.class);
  }

  @Test
  void openFile(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("x.pem");
    Files.writeString(file, "x");
    try (InputStream in = Proxy.open(file.toString())) {
      assertThat(in.read()).isEqualTo('x');
    }
  }

  @Test
  void sslContextWithMissingKey() {
    SslConfig cfg =
        SslConfig.builder().certPath("classpath:ssl/test.crt").keyPath("classpath:nope").build();
    assertThatThrownBy(() -> Proxy.createSslContext(cfg))
        .isInstanceOf(IllegalArgumentException.class)
        .hasCauseInstanceOf(FileNotFoundException.class);
  }
}
