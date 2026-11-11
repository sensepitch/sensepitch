package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.netty.util.concurrent.EventExecutor;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

/// Boots the proxy on a real TCP port with TLS.
///
/// @author Raid Thabet
@Timeout(value = 40, unit = TimeUnit.SECONDS)
class ExternalTcpIT {

  private final short ALWAYS_CLOSED_PORT = 1;

  private Proxy proxy;
  private int port;
  private final RequestLogCapture requestLog = new RequestLogCapture();

  @RegisterExtension LogCapture logs = new LogCapture();

  /// Plain HTTP upstream, records the `traceparent` header of each request it receives
  private HttpServer upstream;

  private final BlockingQueue<String> upstreamTraceparents = new LinkedBlockingQueue<>();

  @BeforeEach
  void startProxy() throws Exception {
    port = freePort();
    upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    upstream.createContext(
        "/",
        exchange -> {
          String traceparent = exchange.getRequestHeaders().getFirst(TraceContext.TRACEPARENT);
          upstreamTraceparents.add(traceparent != null ? traceparent : "missing");
          byte[] body = "from upstream".getBytes(StandardCharsets.US_ASCII);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    upstream.start();

    ProxyConfig config =
        ProxyConfig.builder()
            .listen(
                ListenConfig.builder()
                    .ssl(
                        SslConfig.builder()
                            .keyPath("classpath:ssl/test.key")
                            .certPath("classpath:ssl/test.crt")
                            .build())
                    .address("127.0.0.1")
                    .httpsPort(port)
                    .build())
            .sites(
                Map.of(
                    "example.com",
                    SiteConfig.builder()
                        .response(ResponseConfig.builder().text("a test response").build())
                        .protection(ProtectionConfig.builder().disable(true).build())
                        .build(),
                    "upstream.com",
                    SiteConfig.builder()
                        .upstream(
                            UpstreamConfig.builder()
                                .target("127.0.0.1:" + upstream.getAddress().getPort())
                                .build())
                        .protection(ProtectionConfig.builder().disable(true).build())
                        .build(),
                    // port 1 is closed, so forwarding here fails and the proxy logs
                    "deadupstream.com",
                    SiteConfig.builder()
                        .upstream(
                            UpstreamConfig.builder()
                                .target("127.0.0.1:" + ALWAYS_CLOSED_PORT)
                                .build())
                        .protection(ProtectionConfig.builder().disable(true).build())
                        .build()))
            .metrics(MetricsConfig.builder().enable(false).build())
            .build();

    proxy = new Proxy(config, requestLog);
    Thread thread =
        new Thread(
            () -> {
              try {
                proxy.start();
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            },
            "proxy-under-test");
    thread.setDaemon(true);
    thread.start();

    awaitListening();
    // the proxy logs after binding the port, wait for it, so the startup messages are not
    // captured after the clear
    awaitLogged("Proxy listening");
    // clear only now, so startup and the readiness probe stay out of the assertions
    logs.clear();
  }

  @AfterEach
  void stopProxy() throws Exception {
    proxy.shutdown();
    upstream.stop(0);
  }

  /// The upstream receives the trace context the edge assigned, and the request log reports the
  /// same span id, plus the data only a real TLS connection has
  @Test
  void upstreamReceivesTraceparentOfRequestLog() throws Exception {
    String clientTraceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
    try (SSLSocket socket = tlsSocket()) {
      socket.startHandshake();
      socket
          .getOutputStream()
          .write(
              ("GET /path?q=1 HTTP/1.1\r\nHost: upstream.com\r\nConnection: close\r\n"
                      + "traceparent: "
                      + clientTraceparent
                      + "\r\n\r\n")
                  .getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      String response =
          new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
      assertThat(response).startsWith("HTTP/1.1 200").contains("from upstream");
    }
    TraceContext forwarded = TraceContext.parse(upstreamTraceparents.poll(10, TimeUnit.SECONDS));
    assertThat(forwarded).as("valid traceparent forwarded").isNotNull();
    assertThat(forwarded.traceId())
        .as("client trace is not trusted by default")
        .isNotEqualTo("0af7651916cd43dd8448eb211c80319c");
    JsonNode record = new ObjectMapper().readTree(awaitRequestLog());
    assertThat(record.get("trace_id").asText()).isEqualTo(forwarded.traceId());
    assertThat(record.get("span_id").asText()).isEqualTo(forwarded.spanId());
    assertThat(record.get("http.request.header.traceparent").get(0).asText())
        .isEqualTo(clientTraceparent);
    assertThat(record.get("client.address").asText()).isEqualTo("127.0.0.1");
    assertThat(record.get("client.port").asInt()).isPositive();
    assertThat(record.get("server.address").asText()).isEqualTo("upstream.com");
    assertThat(record.get("server.port").asInt()).isEqualTo(port);
    assertThat(record.get("url.scheme").asText()).isEqualTo("https");
    assertThat(record.get("url.path").asText()).isEqualTo("/path");
    assertThat(record.get("url.query").asText()).isEqualTo("q=1");
    assertThat(record.get("tls.protocol.name").asText()).isEqualTo("tls");
    assertThat(record.get("tls.protocol.version").asText()).isIn("1.2", "1.3");
    assertThat(record.get("tls.cipher").asText()).startsWith("TLS_");
    assertThat(record.get("http.response.status_code").asInt()).isEqualTo(200);
  }

  /// The record is written when the response is sent, which can be after the client read it
  @SuppressWarnings("BusyWait")
  private String awaitRequestLog() throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (requestLog.lines().isEmpty()) {
      if (System.nanoTime() > deadline) {
        throw new IllegalStateException("no request log record");
      }
      Thread.sleep(5);
    }
    return requestLog.lines().getFirst();
  }

  @Test
  void garbageIsDroppedSilently() throws Exception {
    sendGarbage();
    assertNothingLogged();
  }

  @Test
  void serverSurvivesGarbage() throws Exception {
    sendGarbage();
    try (SSLSocket socket = tlsSocket()) {
      socket.startHandshake();
    }
    assertNothingLogged();
  }

  @Test
  void respondsWithText() throws Exception {
    try (SSLSocket socket = tlsSocket()) {
      socket.startHandshake();
      socket
          .getOutputStream()
          .write(
              "GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n"
                  .getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();

      // Connection: close ends the stream, so read to EOF
      String response =
          new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
      assertThat(response).startsWith("HTTP/1.1 200");
      assertThat(response).contains("a test response");
      assertNothingLogged();
    }
  }

  @Test
  void respondsWithTextHttp10() throws Exception {
    try (SSLSocket socket = tlsSocket()) {
      socket.startHandshake();
      socket
          .getOutputStream()
          .write("GET / HTTP/1.0\r\nHost: example.com\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();

      String response =
          new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
      assertThat(response).startsWith("HTTP/1.1 200");
      assertThat(response).contains("a test response");
      assertNothingLogged();
    }
  }

  @Test
  void deadUpstreamIsLogged() throws Exception {
    try (SSLSocket socket = tlsSocket()) {
      socket.startHandshake();
      socket
          .getOutputStream()
          .write(
              "GET / HTTP/1.1\r\nHost: deadupstream.com\r\nConnection: close\r\n\r\n" // http 1.0
                  // version
                  // (separate
                  // test)
                  .getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      awaitUpstreamFailureHandled(socket);
    }
    assertLogged();
  }

  @Test
  void deadUpstreamIsLoggedHttp10() throws Exception {
    try (SSLSocket socket = tlsSocket()) {
      socket.startHandshake();
      socket
          .getOutputStream()
          .write(
              "GET / HTTP/1.0\r\nHost: deadupstream.com\r\n\r\n"
                  .getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      awaitUpstreamFailureHandled(socket);
    }
    assertLogged();
  }

  /// The upstream connect fails asynchronously, so the log is not written yet when the request has
  /// been flushed. Reading the fallback response to EOF is the observable proof that the proxy is
  /// done with the failed upstream, which puts the log write in the queue that
  /// [#drainEventLoops()] then drains.
  private void awaitUpstreamFailureHandled(SSLSocket socket) throws Exception {
    assertThat(socket.getInputStream().readAllBytes())
        .describedAs("proxy must answer even when the upstream is dead")
        .isNotEmpty();
    drainEventLoops();
  }

  private void assertLogged() {
    assertThat(proxyMessages())
        .describedAs("proxy must log the failed upstream connection")
        .isNotEmpty();
  }

  private void assertNothingLogged() throws InterruptedException {
    drainEventLoops();
    assertThat(proxyMessages()).describedAs("proxy must stay silent for malformed input").isEmpty();
  }

  // the group is owned and closed by the proxy, the test only borrows it
  @SuppressWarnings("resource")
  private void drainEventLoops() throws InterruptedException {
    for (EventExecutor executor : proxy.eventLoopGroup()) {
      assertThat(executor.submit(() -> {}).await(5, TimeUnit.SECONDS))
          .describedAs("event loop did not drain")
          .isTrue();
    }
  }

  /// Messages of the proxy's own loggers, Netty and other libraries are not of interest here
  private List<String> proxyMessages() {
    return logs.messages("org.sensepitch.edge");
  }

  /// Writes bytes that are not a valid TLS ClientHello, then expects the peer to hang up.
  private void sendGarbage() throws IOException {
    byte[] bytes = new byte[512];
    new Random(42).nextBytes(bytes);
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
      socket.setSoTimeout(2_000);
      socket.getOutputStream().write(bytes);
      socket.getOutputStream().flush();
      try {
        assertThat(socket.getInputStream().read()).isEqualTo(-1);
      } catch (IOException expectedOnReset) {
        // connection reset counts as a clean drop
      }
    }
  }

  private SSLSocket tlsSocket() throws Exception {
    SSLContext context = SSLContext.getInstance("TLS");
    // the test certificate is self-signed, validation is deliberately skipped
    context.init(
        null,
        new TrustManager[] {
          new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public X509Certificate[] getAcceptedIssuers() {
              return new X509Certificate[0];
            }
          }
        },
        new SecureRandom());
    SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket();
    socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
    socket.setSoTimeout(5_000);
    return socket;
  }

  private static int freePort() throws IOException {
    try (ServerSocket probe = new ServerSocket(0)) {
      return probe.getLocalPort();
    }
  }

  @SuppressWarnings("BusyWait")
  private void awaitLogged(String messagePart) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (proxyMessages().stream().noneMatch(m -> m.contains(messagePart))) {
      if (System.nanoTime() > deadline) {
        throw new IllegalStateException("proxy did not log: " + messagePart);
      }
      Thread.sleep(5);
    }
  }

  @SuppressWarnings("BusyWait")
  private void awaitListening() throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      try (Socket probe = new Socket()) {
        probe.connect(new InetSocketAddress("127.0.0.1", port), 250);
        return;
      } catch (IOException notUpYet) {
        // backoff
        Thread.sleep(25);
      }
    }
    throw new IllegalStateException("proxy did not bind port " + port);
  }
}
