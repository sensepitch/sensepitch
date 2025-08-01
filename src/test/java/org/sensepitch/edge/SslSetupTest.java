package org.sensepitch.edge;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author Jens Wilke
 */
@SuppressWarnings("unchecked")
class SslSetupTest {

  @Test
  public void missingHosts() {
    ListenConfig listenConfig = ListenConfig.builder()
      .letsEncrypt(true)
      .letsEncryptPrefix("classpath:/letsencrypt/live/")
      .build();
    assertThatThrownBy(() ->
      Proxy.initializeSniMapping(listenConfig, Collections.EMPTY_SET))
      .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void missingSetup() {
    ListenConfig listenConfig = ListenConfig.builder()
      .hosts(List.of("example.org"))
      .build();
    assertThatThrownBy(() -> Proxy.initializeSniMapping(listenConfig, Collections.EMPTY_SET))
    .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void sniSetup() {
    ListenConfig listenConfig = ListenConfig.builder()
      .hosts(List.of("example.org"))
      .snis(List.of(
        SniConfig.builder()
          .host("example.org")
          .ssl(SslConfig.builder()
            .certPath("classpath:/ssl/test.crt")
            .keyPath("classpath:/ssl/test.key")
            .build())
          .build()
        ))
      .build();
    var mapping = Proxy.initializeSniMapping(listenConfig, Collections.EMPTY_SET);
    var obj = mapping.map("example.org");
    assertThat(obj).isNotNull();
  }

  @Test
  public void letsEncrypt() {
    ListenConfig listenConfig = ListenConfig.builder()
      .hosts(List.of("example.org"))
      .letsEncrypt(true)
      .letsEncryptPrefix("classpath:/letsencrypt/live/")
      .build();
    var mapping = Proxy.initializeSniMapping(listenConfig, Collections.EMPTY_SET);
    var obj = mapping.map("example.org");
    assertThat(obj).isNotNull();
  }

}
