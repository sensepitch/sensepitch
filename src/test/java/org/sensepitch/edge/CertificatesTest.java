package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/// @author Jens Wilke
class CertificatesTest {

  @Test
  void changes() {
    Map<String, String> previous = Map.of("a.com", "A1", "b.com", "B", "c.com", "C");
    Map<String, String> current = Map.of("a.com", "A2", "b.com", "B", "d.com", "D");
    assertThat(Certificates.changes(previous, current))
        .isEqualTo(
            "\n  changed a.com: A2"
                + "\n    was: A1"
                + "\n  removed c.com: C"
                + "\n  added d.com: D");
    assertThat(Certificates.changes(previous, previous)).isEmpty();
  }

  @Test
  void describeUnreadable() {
    assertThat(Certificates.describe(SslConfig.builder().certPath("/missing.crt").build()))
        .startsWith("unreadable: ")
        .endsWith("file=/missing.crt");
  }
}
