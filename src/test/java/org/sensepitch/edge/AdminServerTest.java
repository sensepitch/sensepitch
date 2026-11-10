package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/// @author Jens Wilke
class AdminServerTest {

  final HttpClient client = HttpClient.newHttpClient();
  AdminServer server;

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop();
    }
  }

  void start(Supplier<ReloadResult> reload) throws IOException {
    server = AdminServer.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), reload);
  }

  HttpResponse<String> send(String method, String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void reloadApplied() throws Exception {
    start(() -> ReloadResult.APPLIED);
    var response = send("POST", "/reload");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("Configuration reloaded\n");
  }

  @Test
  void reloadRejectedWithReason() throws Exception {
    start(() -> ReloadResult.rejected("Configuration rejected: unknown upstream ref: x"));
    var response = send("POST", "/reload");
    assertThat(response.statusCode()).isEqualTo(422);
    assertThat(response.body()).contains("unknown upstream ref: x");
  }

  @Test
  void reloadThrowingIsRejected() throws Exception {
    start(
        () -> {
          throw new IllegalStateException("boom");
        });
    var response = send("POST", "/reload");
    assertThat(response.statusCode()).isEqualTo(422);
    assertThat(response.body()).contains("boom");
  }

  @Test
  void noConfigurationFile() throws Exception {
    start(null);
    assertThat(send("POST", "/reload").statusCode()).isEqualTo(409);
  }

  @Test
  void wrongMethodAndPath() throws Exception {
    start(() -> ReloadResult.APPLIED);
    var get = send("GET", "/reload");
    assertThat(get.statusCode()).isEqualTo(405);
    assertThat(get.headers().firstValue("Allow")).hasValue("POST");
    assertThat(send("POST", "/other").statusCode()).isEqualTo(404);
  }

  @Test
  void defaultsToLoopback() throws Exception {
    server = AdminServer.start(AdminConfig.builder().port(freePort()).build(), null);
    assertThat(server.address().getAddress().isLoopbackAddress()).isTrue();
  }

  static int freePort() throws IOException {
    try (var socket = new java.net.ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
