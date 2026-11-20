package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpContent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import net.serenitybdd.annotations.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Rotating a secret file of a running proxy: the site uses its own protection and a host that
/// differs from its key, the new secret takes effect with the reload, and a broken replacement
/// keeps the last working secret. The file watching itself is tested in [ConfigFileWatcherTest].
///
/// @author Jens Wilke
class SecretRotationBDDTest {

  static final String SITE_KEY = "shop";
  static final String HOST = "www.example.test";
  static final String CONTENT = "welcome";

  @TempDir Path dir;

  final Steps steps = new Steps();

  @AfterEach
  void finish() {
    steps.proxy.finish_and_check_for_leaks();
  }

  @Test
  void rotatedSiteSecretTakesEffect() throws IOException {
    steps
        .given_a_site_with_its_own_secret_file_and_lets_encrypt(dir, "one")
        .then_the_watched_files_are_those_of_the_effective_host_and_site_protection()
        .then_admitted_with_a_token_of("one")
        .then_challenged_with_a_token_of("two")
        .when_the_secret_file_is_replaced_by("two\n")
        .then_the_reload_is(true)
        .then_admitted_with_a_token_of("two")
        .then_challenged_with_a_token_of("one");
  }

  @Test
  void emptyReplacementKeepsTheLastWorkingSecret() throws IOException {
    steps
        .given_a_site_with_its_own_secret_file_and_lets_encrypt(dir, "one")
        .when_the_secret_file_is_replaced_by("\n")
        .then_the_reload_is(false)
        .then_admitted_with_a_token_of("one");
  }

  static class Steps {

    final CompleteTest.Steps proxy = new CompleteTest.Steps();
    Path dir;
    Path secret;
    Path letsEncrypt;
    ProxyConfig config;

    @Step
    Steps given_a_site_with_its_own_secret_file_and_lets_encrypt(Path dir, String secretValue)
        throws IOException {
      this.dir = dir;
      secret = Files.writeString(dir.resolve("admission"), secretValue);
      letsEncrypt = Files.createDirectories(dir.resolve("live").resolve(HOST));
      copyResource("letsencrypt/live/example.org/privkey.pem", letsEncrypt);
      copyResource("letsencrypt/live/example.org/fullchain.pem", letsEncrypt);
      SiteConfig site =
          SiteConfig.builder()
              .host(HOST)
              .protection(
                  ProtectionConfig.builder()
                      .deflector(
                          DeflectorConfig.builder()
                              .serverIpv4Address("127.0.0.1")
                              .tokenGenerators(
                                  List.of(
                                      AdmissionTokenGeneratorConfig.builder()
                                          .prefix("X")
                                          .secret(
                                              SecretConfig.builder()
                                                  .file(secret.toString())
                                                  .build())
                                          .build()))
                              .build())
                      .build())
              .response(ResponseConfig.builder().text(CONTENT).build())
              .build();
      config =
          ProxyConfig.builder()
              .listen(
                  ListenConfig.builder()
                      .letsEncrypt(true)
                      .letsEncryptPrefix(dir.resolve("live") + "/")
                      .build())
              .unservicedHost(
                  UnservicedHostConfig.builder().defaultLocation("https://sensepitch.io").build())
              .protection(ProtectionConfig.builder().disable(true).build())
              .sites(Map.of(SITE_KEY, site))
              .metrics(MetricsConfig.builder().enable(false).build())
              .build();
      proxy.given_initialized_proxy_with(config);
      return this;
    }

    static void copyResource(String resource, Path dir) throws IOException {
      try (InputStream in = SecretRotationBDDTest.class.getResourceAsStream("/" + resource)) {
        Files.copy(in, dir.resolve(Path.of(resource).getFileName()));
      }
    }

    @Step
    Steps then_the_watched_files_are_those_of_the_effective_host_and_site_protection() {
      assertThat(proxy.proxy.referencedFiles())
          .containsOnlyKeys(
              secret.toAbsolutePath(),
              letsEncrypt.resolve("privkey.pem").toAbsolutePath(),
              letsEncrypt.resolve("fullchain.pem").toAbsolutePath());
      return this;
    }

    @Step
    Steps then_admitted_with_a_token_of(String secretValue) {
      assertThat(contentWithTokenOf(secretValue)).isEqualTo(CONTENT);
      return this;
    }

    @Step
    Steps then_challenged_with_a_token_of(String secretValue) {
      assertThat(contentWithTokenOf(secretValue)).isNotEqualTo(CONTENT);
      return this;
    }

    /// Requests the site with an admission cookie made with the secret
    String contentWithTokenOf(String secretValue) {
      String token =
          new DefaultAdmissionTokenGenerator(new byte[] {127, 0, 0, 1}, 'X', secretValue)
              .newAdmission();
      proxy.when_requesting_with_cookie(HOST, "/", Deflector.TOKEN_COOKIE_NAME + "=" + token);
      ByteBuf content = ((HttpContent) proxy.response).content();
      return content.toString(StandardCharsets.UTF_8);
    }

    @Step
    Steps when_the_secret_file_is_replaced_by(String content) throws IOException {
      Path tmp = Files.writeString(dir.resolve("tmp"), content);
      Files.move(tmp, secret, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      return this;
    }

    @Step
    Steps then_the_reload_is(boolean applied) {
      assertThat(proxy.proxy.reload(config).applied()).isEqualTo(applied);
      return this;
    }
  }
}
