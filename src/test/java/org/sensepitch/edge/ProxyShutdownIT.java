package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

/// Graceful shutdown on a real TCP port: the listener closes, requests in flight complete with
/// `Connection: close`, idle connections close at once, and the request log is sent before the
/// shutdown returns. The OTLP destination flushes only every minute, so its records arrive only
/// because the event loops write their end markers when they terminate.
///
/// @author Jens Wilke
@Timeout(value = 40, unit = TimeUnit.SECONDS)
class ProxyShutdownIT {

  @RegisterExtension LogCapture logs = new LogCapture();

  private final RequestLogCapture requestLog = new RequestLogCapture();
  private final CountDownLatch upstreamGotRequest = new CountDownLatch(1);
  private final AtomicInteger otlpRecords = new AtomicInteger();

  private volatile long upstreamDelayMillis;
  private HttpServer upstream;
  private HttpServer logServer;
  private Proxy proxy;
  private ProxyConfig config;
  private int port;

  void startProxy(long shutdownTimeoutMillis, long drainTimeoutMillis) throws Exception {
    port = freePort();
    upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    upstream.createContext(
        "/",
        exchange -> {
          upstreamGotRequest.countDown();
          try {
            Thread.sleep(upstreamDelayMillis);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          byte[] body = "from upstream".getBytes(StandardCharsets.US_ASCII);
          try {
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
          } catch (IOException closedByTheProxy) {
            // the proxy gave up on the request
          }
          exchange.close();
        });
    upstream.start();
    logServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    logServer.createContext(
        "/v1/logs",
        exchange -> {
          ExportLogsServiceRequest request = OtlpLogDestinationTest.decode(exchange);
          otlpRecords.addAndGet(request.getResourceLogs(0).getScopeLogs(0).getLogRecordsCount());
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    logServer.start();
    ProtectionConfig noProtection = ProtectionConfig.builder().disable(true).build();
    config =
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
                        .protection(noProtection)
                        .build(),
                    "upstream.com",
                    SiteConfig.builder()
                        .upstream(
                            UpstreamConfig.builder()
                                .target("127.0.0.1:" + upstream.getAddress().getPort())
                                .build())
                        .protection(noProtection)
                        .build()))
            .requestLog(
                RequestLogConfig.builder()
                    .drainTimeoutMillis(drainTimeoutMillis)
                    .destinations(
                        List.of(
                            RequestLogDestinationConfig.builder().type("stdout").build(),
                            RequestLogDestinationConfig.builder()
                                .type("otlp")
                                .otlp(
                                    OtlpConfig.builder()
                                        .endpoint(
                                            "http://127.0.0.1:"
                                                + logServer.getAddress().getPort()
                                                + "/v1/logs")
                                        .flushIntervalMillis(60_000)
                                        .build())
                                .build()))
                    .build())
            .metrics(MetricsConfig.builder().enable(false).build())
            .shutdownTimeoutMillis(shutdownTimeoutMillis)
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
  }

  @AfterEach
  void stop() {
    if (proxy != null) {
      proxy.shutdown();
    }
    if (upstream != null) {
      upstream.stop(0);
    }
    if (logServer != null) {
      logServer.stop(0);
    }
  }

  /// A request in flight completes, its response closes the connection, and it is logged in all
  /// destinations before the shutdown returns, well before the timeout
  @Test
  void requestInFlightCompletes() throws Exception {
    startProxy(10_000, 0);
    upstreamDelayMillis = 300;
    try (SSLSocket socket = tlsSocket()) {
      send(socket, "upstream.com");
      assertThat(upstreamGotRequest.await(10, TimeUnit.SECONDS)).isTrue();
      long start = System.nanoTime();
      CompletableFuture<Void> shutdown = CompletableFuture.runAsync(proxy::shutdown);
      String response = readResponse(socket.getInputStream());
      assertThat(response).startsWith("HTTP/1.1 200").contains("from upstream");
      assertThat(response.toLowerCase(Locale.ROOT)).contains("connection: close");
      assertThat(socket.getInputStream().read()).as("closed after the response").isEqualTo(-1);
      shutdown.get(10, TimeUnit.SECONDS);
      assertThat(millisSince(start)).as("faster than the timeout").isLessThan(5_000);
    }
    assertThat(requestLog.lines())
        .singleElement()
        .asString()
        .contains("\"http.response.status_code\":200");
    assertThat(otlpRecords.get()).as("sent before the shutdown returned").isOne();
    assertThat(logs.messages(Proxy.class)).noneMatch(m -> m.startsWith("Shutdown:"));
  }

  /// An idle keep-alive connection is closed at once
  @Test
  void idleConnectionIsClosed() throws Exception {
    startProxy(10_000, 0);
    try (SSLSocket socket = tlsSocket()) {
      send(socket, "example.com");
      assertThat(readResponse(socket.getInputStream()))
          .startsWith("HTTP/1.1 200")
          .contains("a test response");
      long start = System.nanoTime();
      proxy.shutdown();
      assertThat(millisSince(start)).isLessThan(5_000);
      assertThat(socket.getInputStream().read()).isEqualTo(-1);
    }
    assertThat(otlpRecords.get()).isOne();
  }

  /// A request that takes longer than the shutdown allows is aborted when only the drain timeout
  /// is left. Its record, status 499, is still sent within the drain timeout, and the requests in
  /// flight are reported.
  @Test
  void slowRequestIsAbortedAndStillLogged() throws Exception {
    startProxy(2_000, 1_000);
    upstreamDelayMillis = 5_000;
    try (SSLSocket socket = tlsSocket()) {
      send(socket, "upstream.com");
      assertThat(upstreamGotRequest.await(10, TimeUnit.SECONDS)).isTrue();
      long start = System.nanoTime();
      proxy.shutdown();
      long took = millisSince(start);
      assertThat(took).as("waited for the request").isGreaterThanOrEqualTo(900);
      assertThat(took).as("within the shutdown timeout").isLessThan(2_500);
    }
    assertThat(requestLog.lines())
        .singleElement()
        .asString()
        .contains("\"http.response.status_code\":499");
    assertThat(otlpRecords.get()).as("the aborted request is sent too").isOne();
    assertThat(logs.messages(Proxy.class))
        .anySatisfy(
            m ->
                assertThat(m)
                    .contains(
                        "requests still in flight after 1000 ms, total 1, by upstream"
                            + " {sites.upstream.com.upstream=1}"));
  }

  /// With the log server down, the shutdown ends at its timeout and reports the records not sent
  @Test
  void unsentRecordsAreReported() throws Exception {
    startProxy(1_000, 500);
    try (SSLSocket socket = tlsSocket()) {
      send(socket, "example.com");
      assertThat(readResponse(socket.getInputStream())).startsWith("HTTP/1.1 200");
    }
    logServer.stop(0);
    long start = System.nanoTime();
    proxy.shutdown();
    assertThat(millisSince(start)).as("ends at the timeout").isBetween(400L, 1_500L);
    assertThat(logs.messages(Proxy.class))
        .anySatisfy(
            m ->
                assertThat(m)
                    .contains("request log not drained after 500 ms, unsent records {otlp=1}"));
  }

  /// A reload after the shutdown started is rejected, it would create destinations that are
  /// never finished, and end markers for loops that are gone
  @Test
  void reloadAfterShutdownIsRejected() throws Exception {
    startProxy(1_000, 0);
    proxy.shutdown();
    ReloadResult result = proxy.reload(config);
    assertThat(result.applied()).isFalse();
    assertThat(result.message()).isEqualTo("Shutting down");
  }

  /// A shutdown waits for a reload in progress. The test holds the monitor of the proxy, as a
  /// reload does, the listener stays open until it is released.
  @Test
  void shutdownWaitsForReload() throws Exception {
    startProxy(10_000, 0);
    CompletableFuture<Void> shutdown;
    synchronized (proxy) {
      shutdown = CompletableFuture.runAsync(proxy::shutdown);
      Thread.sleep(200);
      assertThat(shutdown).isNotDone();
      try (Socket probe = new Socket()) {
        probe.connect(new InetSocketAddress("127.0.0.1", port), 1_000);
      }
    }
    shutdown.get(10, TimeUnit.SECONDS);
    assertThat(proxy.reload(config).applied()).isFalse();
  }

  static void send(SSLSocket socket, String host) throws IOException {
    socket.startHandshake();
    socket
        .getOutputStream()
        .write(
            ("GET / HTTP/1.1\r\nHost: " + host + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
    socket.getOutputStream().flush();
  }

  /// Reads one response with a `Content-Length`, the connection may stay open
  static String readResponse(InputStream in) throws IOException {
    ByteArrayOutputStream head = new ByteArrayOutputStream();
    while (!head.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
      int b = in.read();
      if (b < 0) {
        throw new IOException("closed in the header: " + head);
      }
      head.write(b);
    }
    String header = head.toString(StandardCharsets.US_ASCII);
    int length = 0;
    for (String line : header.split("\r\n")) {
      if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
        length = Integer.parseInt(line.substring("content-length:".length()).trim());
      }
    }
    return header + new String(in.readNBytes(length), StandardCharsets.US_ASCII);
  }

  static long millisSince(long startNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
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
    socket.setSoTimeout(10_000);
    return socket;
  }

  private static int freePort() throws IOException {
    try (ServerSocket probe = new ServerSocket(0)) {
      return probe.getLocalPort();
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
        Thread.sleep(25);
      }
    }
    throw new IllegalStateException("proxy did not bind port " + port);
  }
}
