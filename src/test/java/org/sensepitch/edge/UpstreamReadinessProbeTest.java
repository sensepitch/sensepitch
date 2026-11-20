package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/// @author Jens Wilke
class UpstreamReadinessProbeTest {

  @Test
  void readyWithoutNetworkTargets() {
    assertThat(new UpstreamReadinessProbe(Set::of).isReady()).isTrue();
  }

  @Test
  void readyWhenAnUpstreamAccepts() throws IOException {
    try (ServerSocket live = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
      Set<String> targets = Set.of("127.0.0.1:" + live.getLocalPort());
      assertThat(new UpstreamReadinessProbe(() -> targets).isReady()).isTrue();
    }
  }

  @Test
  void notReadyWhenNoUpstreamAccepts() throws IOException {
    int port;
    try (ServerSocket closed = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
      port = closed.getLocalPort();
    }
    Set<String> targets = Set.of("127.0.0.1:" + port);
    assertThat(new UpstreamReadinessProbe(() -> targets).isReady()).isFalse();
  }

  @Test
  void readyWhenOneOfSeveralTargetsAccepts() throws IOException {
    try (ServerSocket live = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
      LinkedHashSet<String> targets = new LinkedHashSet<>();
      targets.add("127.0.0.1:" + live.getLocalPort());
      targets.add("127.0.0.1:1");
      assertThat(new UpstreamReadinessProbe(() -> targets).isReady()).isTrue();
    }
  }

  @Test
  void addressParsesPortAndDefault() {
    assertThat(UpstreamReadinessProbe.addressOf("example.com:8080"))
        .returns("example.com", InetSocketAddress::getHostString)
        .returns(8080, InetSocketAddress::getPort);
    assertThat(UpstreamReadinessProbe.addressOf("example.com"))
        .returns("example.com", InetSocketAddress::getHostString)
        .returns(80, InetSocketAddress::getPort);
  }
}
