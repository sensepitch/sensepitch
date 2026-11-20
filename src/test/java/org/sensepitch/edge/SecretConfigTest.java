package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/// A secret is configured inline or as a file and is not revealed in the log, see
/// [SecretConfig]. Reading it is tested in [ConfigFilesTest].
///
/// @author Jens Wilke
class SecretConfigTest {

  @Test
  void exactlyOneOfValueOrFile() {
    assertThatThrownBy(() -> SecretConfig.builder().build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exactly one of value or file");
    assertThatThrownBy(() -> SecretConfig.builder().value("s").file("/f").build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exactly one of value or file");
  }

  @Test
  void emptyValueIsRejected() {
    for (String value : new String[] {"", " \t"}) {
      assertThatThrownBy(() -> SecretConfig.builder().value(value).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("A secret value must not be empty");
    }
  }

  @Test
  void toStringMasksTheValue() {
    assertThat(SecretConfig.builder().value("top-secret").build().toString())
        .doesNotContain("top-secret")
        .contains(SecretConfig.MASK);
    assertThat(SecretConfig.builder().file("/etc/token").build().toString())
        .contains("file=/etc/token");
  }

  /// The configuration is logged at startup and as diff on a reload
  @Test
  void yamlDumpMasksTheValue() {
    String yaml =
        Proxy.toYaml(
            ProxyConfig.builder()
                .protection(
                    ProtectionConfig.builder()
                        .deflector(
                            DeflectorConfig.builder()
                                .tokenGenerators(
                                    List.of(
                                        generator("X", SecretConfig.builder().value("top-secret")),
                                        generator("Y", SecretConfig.builder().file("/etc/token"))))
                                .build())
                        .build())
                .build());
    assertThat(yaml)
        .doesNotContain("top-secret")
        .contains("value: '" + SecretConfig.MASK + "'")
        .contains("file: /etc/token");
  }

  static AdmissionTokenGeneratorConfig generator(String prefix, SecretConfig.Builder secret) {
    return AdmissionTokenGeneratorConfig.builder().prefix(prefix).secret(secret.build()).build();
  }

  @Test
  void tokenGeneratorNeedsASecret() {
    assertThatThrownBy(() -> AdmissionTokenGeneratorConfig.builder().prefix("X").build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("needs a secret");
  }

  @Test
  void secretHeaderNeedsNameAndSecret() {
    SecretConfig secret = SecretConfig.builder().value("s").build();
    assertThatThrownBy(() -> SecretHeaderConfig.builder().secret(secret).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("needs a name");
    assertThatThrownBy(() -> SecretHeaderConfig.builder().name(" ").secret(secret).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("needs a name");
    assertThatThrownBy(() -> SecretHeaderConfig.builder().name("Authorization").build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Authorization needs a secret");
  }
}
