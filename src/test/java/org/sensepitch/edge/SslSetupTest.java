package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.FileNotFoundException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/// @author Jens Wilke
@SuppressWarnings("unchecked")
class SslSetupTest {

  @Test
  void exactSniOverridesWildcardAndMissesUseDefault() {
    SslConfig ssl = ConfigReloadTest.TEST_SSL;
    var mapping =
        Proxy.initializeSniMapping(
            ListenConfig.builder()
                .ssl(ssl)
                .snis(
                    List.of(
                        new SniConfig("*.example.org", ssl), new SniConfig("api.example.org", ssl)))
                .build(),
            Set.of());
    var fallback = mapping.map(null);
    var wildcard = mapping.map("one.example.org");
    assertThat(wildcard).isNotSameAs(fallback).isSameAs(mapping.map("TWO.example.org"));
    assertThat(mapping.map("api.example.org")).isNotSameAs(wildcard).isNotSameAs(fallback);
    assertThat(mapping.map("example.org")).isSameAs(fallback);
    assertThat(mapping.map("-bad.example.org")).isSameAs(fallback);
    assertThat(mapping.map("*.example.org")).isSameAs(fallback);
    assertThat(mapping.map("two.one.example.org")).isSameAs(fallback);
  }

  @Test
  void wildcardLetsEncryptUsesSuffixDirectory() {
    var listen =
        ListenConfig.builder()
            .letsEncrypt(true)
            .letsEncryptPrefix("classpath:/letsencrypt/live/")
            .ssl(ConfigReloadTest.TEST_SSL)
            .build();
    var mapping = Proxy.initializeSniMapping(listen, Set.of("*.example.org"));
    assertThat(mapping.map("tenant.example.org")).isNotSameAs(mapping.map("example.org"));
  }

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
  void sslContextWithMissingKey() {
    SslConfig cfg =
        SslConfig.builder().certPath("classpath:ssl/test.crt").keyPath("classpath:nope").build();
    assertThatThrownBy(() -> Proxy.createSslContext(cfg, new ConfigFiles()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasCauseInstanceOf(FileNotFoundException.class);
  }
}
