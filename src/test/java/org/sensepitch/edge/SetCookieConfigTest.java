package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.sensepitch.edge.config.RecordConstructor;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.Node;

/// @author Jens Wilke
class SetCookieConfigTest {

  @Test
  void defaults() {
    assertThat(SetCookieConfig.builder().name("beta").build().encode())
        .isEqualTo("beta=1; Path=/; Secure; HTTPOnly; SameSite=Lax");
  }

  @Test
  void allAttributes() {
    String encoded =
        SetCookieConfig.builder()
            .name("beta")
            .value("on")
            .maxAge(3600)
            .path("/shop")
            .domain("example.com")
            .sameSite("strict")
            .build()
            .encode();
    assertThat(encoded)
        .startsWith("beta=on; Max-Age=3600; Expires=")
        .endsWith("; Path=/shop; Domain=example.com; Secure; HTTPOnly; SameSite=Strict");
  }

  @Test
  void delete() {
    assertThat(SetCookieConfig.builder().name("beta").delete(true).build().encode())
        .startsWith("beta=; Max-Age=0; Expires=")
        .endsWith("; Path=/; Secure; HTTPOnly; SameSite=Lax");
  }

  @Test
  void nameMissingFails() {
    assertThatThrownBy(() -> SetCookieConfig.builder().build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cookie name missing");
  }

  @Test
  void deleteWithMaxAgeFails() {
    assertThatThrownBy(() -> SetCookieConfig.builder().name("beta").delete(true).maxAge(1).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exclusive");
  }

  @Test
  void negativeMaxAgeFails() {
    assertThatThrownBy(() -> SetCookieConfig.builder().name("beta").maxAge(-1).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("negative");
  }

  @Test
  void invalidSameSiteFails() {
    assertThatThrownBy(() -> SetCookieConfig.builder().name("beta").sameSite("sometimes").build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sameSite");
  }

  @Test
  void responseWithoutCookiesHasEmptyList() {
    ResponseConfig cfg = ResponseConfig.builder().text("hi").build();
    assertThat(cfg.cookies()).isEmpty();
    assertThat(cfg.encodedCookies()).isEmpty();
  }

  @Test
  void parseResponseWithCookies() {
    String yaml =
        """
        location: /
        cookies:
          - name: beta
            maxAge: 2592000
          - name: alpha
            delete: true
        """;
    Node root = new Yaml().compose(new StringReader(yaml));
    ResponseConfig cfg = RecordConstructor.construct(ResponseConfig.class, root);
    List<SetCookieConfig> cookies = cfg.cookies();
    assertThat(cookies).hasSize(2);
    assertThat(cookies.get(0).name()).isEqualTo("beta");
    assertThat(cookies.get(0).maxAge()).isEqualTo(2592000);
    assertThat(cookies.get(1).name()).isEqualTo("alpha");
    assertThat(cookies.get(1).delete()).isTrue();
    assertThat(cfg.encodedCookies()).hasSize(2);
  }
}
