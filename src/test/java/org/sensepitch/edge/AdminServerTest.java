package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.sensepitch.edge.CompleteTest.COMMON_CONFIG;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    server =
        AdminServer.start(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), reload, () -> false);
  }

  HttpResponse<String> send(String method, String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void healthzStaysLiveDuringShutdown() throws Exception {
    AtomicBoolean shuttingDown = new AtomicBoolean();
    server =
        AdminServer.start(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null, shuttingDown::get);
    assertThat(send("GET", "/healthz").statusCode()).isEqualTo(200);
    shuttingDown.set(true);
    assertThat(send("GET", "/healthz").statusCode()).isEqualTo(200);
  }

  @Test
  void readyzTracksProxyShutdown() throws Exception {
    Proxy proxy = new Proxy(COMMON_CONFIG, new RequestLogCapture());
    try {
      server =
          AdminServer.start(
              new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
              () -> ReloadResult.APPLIED,
              proxy::isShuttingDown);
      assertThat(send("GET", "/readyz").statusCode()).isEqualTo(200);
      proxy.shutdown();
      assertThat(send("GET", "/readyz").statusCode()).isEqualTo(503);
      assertThat(send("GET", "/healthz").statusCode()).isEqualTo(200);
      assertThat(send("POST", "/reload").statusCode()).isEqualTo(200);
    } finally {
      proxy.shutdown();
    }
  }

  @Test
  void readyzWithoutConfigurationFile() throws Exception {
    start(null);
    assertThat(send("GET", "/readyz").statusCode()).isEqualTo(200);
    assertThat(send("GET", "/readyz/other").statusCode()).isEqualTo(404);
    assertThat(send("GET", "/healthz/other").statusCode()).isEqualTo(404);
  }

  @Test
  void readyzReflectsReadiness() throws Exception {
    AtomicBoolean shuttingDown = new AtomicBoolean();
    AtomicBoolean ready = new AtomicBoolean(true);
    AtomicInteger probeCalls = new AtomicInteger();
    server =
        AdminServer.start(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
            null,
            shuttingDown::get,
            () -> {
              probeCalls.incrementAndGet();
              return ready.get();
            });
    assertThat(send("GET", "/readyz").statusCode()).isEqualTo(200);
    ready.set(false);
    var notReady = send("GET", "/readyz");
    assertThat(notReady.statusCode()).isEqualTo(503);
    assertThat(notReady.body()).isEqualTo("No upstream available\n");
    // Shutdown returns immediately without probing upstreams.
    ready.set(true);
    shuttingDown.set(true);
    var stopping = send("GET", "/readyz");
    assertThat(stopping.statusCode()).isEqualTo(503);
    assertThat(stopping.body()).isEqualTo("Shutting down\n");
    assertThat(probeCalls).hasValue(2);
  }

  @Test
  void readyzProbesTheConfiguredUpstreams() throws Exception {
    try (ServerSocket live = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
      Proxy proxy = new Proxy(upstreamConfig(live.getLocalPort()), new RequestLogCapture());
      try {
        server =
            AdminServer.start(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                () -> ReloadResult.APPLIED,
                proxy::isShuttingDown,
                new UpstreamReadinessProbe(proxy::upstreamTargets)::isReady);
        assertThat(send("GET", "/readyz").statusCode()).isEqualTo(200);
        live.close();
        assertThat(send("GET", "/readyz").statusCode()).isEqualTo(503);
      } finally {
        proxy.shutdown();
      }
    }
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
    server = AdminServer.start(AdminConfig.builder().port(freePort()).build(), null, () -> false);
    assertThat(server.address().getAddress().isLoopbackAddress()).isTrue();
  }

  static int freePort() throws IOException {
    try (var socket = new java.net.ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  static ProxyConfig upstreamConfig(int port) {
    return ProxyConfig.builder()
        .listen(
            ListenConfig.builder()
                .ssl(
                    SslConfig.builder()
                        .keyPath("classpath:ssl/test.key")
                        .certPath("classpath:ssl/test.crt")
                        .build())
                .build())
        .unservicedHost(
            UnservicedHostConfig.builder().defaultLocation("https://sensepitch.io").build())
        .protection(ProtectionConfig.builder().disable(true).build())
        .sites(
            Map.of(
                "upstream.com",
                SiteConfig.builder()
                    .upstream(UpstreamConfig.builder().target("127.0.0.1:" + port).build())
                    .build()))
        .metrics(MetricsConfig.builder().enable(false).build())
        .build();
  }
}
