package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.sensepitch.edge.TestingParameters.OUT_OF_REACH_MILLIS;
import static org.sensepitch.edge.TestingParameters.TIMEOUT;
import static org.sensepitch.edge.TestingParameters.TIMEOUT_SECONDS;
import static org.sensepitch.edge.TestingParameters.await;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.AbstractMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.Isolated;

/// Graceful shutdown on a real TCP port: the listener closes, requests in flight complete with
/// `Connection: close`, idle connections close at once, and the request log is sent before the
/// shutdown returns. The OTLP destination flushes only every minute, so its records arrive only
/// because the event loops write their end markers when they terminate.
///
/// @author Jens Wilke
@Timeout(value = 40, unit = TimeUnit.SECONDS)
// alone: captures the log of all threads, the proxy logs from its event loops
@Isolated
class ProxyShutdownIT {

  @RegisterExtension LogCapture logs = LogCapture.ofAllThreads();

  private final RequestLogCapture requestLog = new RequestLogCapture();
  private final CountDownLatch upstreamGotRequest = new CountDownLatch(1);
  private final CountDownLatch testOver = new CountDownLatch(1);
  private final AtomicInteger otlpRecords = new AtomicInteger();

  private volatile long upstreamDelayMillis;
  private HttpServer upstream;
  private HttpServer logServer;
  private Proxy proxy;
  private ProxyConfig config;
  private int port;

  /// @param drainTimeoutMillis `null` for the default, a third of the shutdown timeout
  void startProxy(long shutdownTimeoutMillis, Long drainTimeoutMillis) throws Exception {
    startProxy(shutdownTimeoutMillis, drainTimeoutMillis, System::nanoTime, LockSupport::parkNanos);
  }

  void startProxy(
      long shutdownTimeoutMillis, Long drainTimeoutMillis, LongSupplier clock, LongConsumer wait)
      throws Exception {
    upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    upstream.createContext(
        "/",
        exchange -> {
          upstreamGotRequest.countDown();
          try {
            // the delay ends early when the test is over, otherwise stopping the upstream
            // waits for the rest of it
            testOver.await(upstreamDelayMillis, TimeUnit.MILLISECONDS);
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
    // probed after the helper servers have their ports: probed earlier, one of them could be
    // given the port just released, the proxy then fails to bind and the test talks to the helper
    port = freePort();
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
                                        .flushIntervalMillis(OUT_OF_REACH_MILLIS)
                                        .build())
                                .build()))
                    .build())
            .metrics(MetricsConfig.builder().enable(false).build())
            .shutdownTimeoutMillis(shutdownTimeoutMillis)
            .build();
    proxy = new Proxy(config, requestLog, clock, wait);
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
    testOver.countDown();
    if (upstream != null) {
      TestingParameters.stop(upstream);
    }
    if (logServer != null) {
      TestingParameters.stop(logServer);
    }
  }

  /// A request in flight completes, its response closes the connection, and it is logged in all
  /// destinations before the shutdown returns, well before the timeout
  @Test
  void requestInFlightCompletes() throws Exception {
    startProxy(10_000, null);
    upstreamDelayMillis = 300;
    try (SSLSocket socket = tlsSocket()) {
      send(socket, "upstream.com");
      assertThat(upstreamGotRequest.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
      long start = System.nanoTime();
      CompletableFuture<Void> shutdown = CompletableFuture.runAsync(proxy::shutdown);
      String response = readResponse(socket.getInputStream());
      assertThat(response).startsWith("HTTP/1.1 200").contains("from upstream");
      assertThat(response.toLowerCase(Locale.ROOT)).contains("connection: close");
      assertThat(socket.getInputStream().read()).as("closed after the response").isEqualTo(-1);
      shutdown.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
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
    startProxy(10_000, null);
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
    startProxy(2_000, 1_000L);
    upstreamDelayMillis = 5_000;
    try (SSLSocket socket = tlsSocket()) {
      send(socket, "upstream.com");
      assertThat(upstreamGotRequest.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
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
                    .contains("requests still in flight after")
                    .contains("ms, total 1, by upstream {sites.upstream.com.upstream=1}"));
  }

  /// With the log server down, the shutdown ends at its timeout and reports the records not sent
  @Test
  void unsentRecordsAreReported() throws Exception {
    startProxy(1_000, 500L);
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
                    .contains("request log not drained after")
                    .contains("ms, unsent records {otlp=1}"));
  }

  /// A reload after the shutdown started is rejected, it would create destinations that are
  /// never finished, and end markers for loops that are gone
  @Test
  void reloadAfterShutdownIsRejected() throws Exception {
    startProxy(1_000, null);
    proxy.shutdown();
    ReloadResult result = proxy.reload(config);
    assertThat(result.applied()).isFalse();
    assertThat(result.message()).isEqualTo("Shutting down");
  }

  /// A shutdown waits for an actual reload paused while reading its configuration. Once the
  /// shutdown thread is blocked, the listener is still open; releasing the reload lets both end.
  @Test
  void shutdownWaitsForReload() throws Exception {
    startProxy(10_000, null);
    CountDownLatch readingConfig = new CountDownLatch(1);
    CountDownLatch continueReload = new CountDownLatch(1);
    Map<String, SiteConfig> pausedSites =
        new AbstractMap<>() {
          @Override
          public Set<Entry<String, SiteConfig>> entrySet() {
            readingConfig.countDown();
            try {
              assertThat(continueReload.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                  .as("reload configuration released")
                  .isTrue();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new AssertionError("reload interrupted", e);
            }
            return config.sites().entrySet();
          }
        };
    ProxyConfig next = config.toBuilder().sites(pausedSites).build();
    CompletableFuture<ReloadResult> reload =
        CompletableFuture.supplyAsync(() -> proxy.reload(next));
    FutureTask<Void> shutdown = new FutureTask<>(proxy::shutdown, null);
    try {
      assertThat(readingConfig.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .as("reload reading configuration")
          .isTrue();
      Thread shutdownThread = Thread.ofPlatform().name("shutdown-during-reload").start(shutdown);
      TestingParameters.await(
          "shutdown waits for reload",
          () -> shutdownThread.getState() == Thread.State.BLOCKED || shutdown.isDone());
      assertThat(shutdownThread.getState())
          .as("shutdown waits for reload")
          .isEqualTo(Thread.State.BLOCKED);
      assertThat(shutdown.isDone()).isFalse();
      assertThat(proxy.isShuttingDown()).isFalse();
      try (Socket probe = new Socket()) {
        probe.connect(new InetSocketAddress("127.0.0.1", port), (int) TIMEOUT.toMillis());
      }
    } finally {
      continueReload.countDown();
    }
    assertThat(reload.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).applied()).isTrue();
    shutdown.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(proxy.isShuttingDown()).isTrue();
    assertThat(proxy.reload(config).applied()).isFalse();
  }

  /// During the accept grace the proxy serves new and keep-alive traffic while readiness is false.
  /// The fake grace consumes the entire connection budget; a stalled request is then aborted and
  /// logged, while the reserved log budget remains available. No real sleep is used.
  @Test
  void acceptGraceKeepsServingThenClosesWithinTheBudget() throws Exception {
    AtomicLong now = new AtomicLong(1234);
    CountDownLatch delaying = new CountDownLatch(1);
    CountDownLatch releaseDelay = new CountDownLatch(1);
    startProxy(
        10_000,
        3000L,
        now::get,
        nanos -> {
          assertThat(nanos).isEqualTo(TimeUnit.MILLISECONDS.toNanos(7000));
          delaying.countDown();
          try {
            assertThat(releaseDelay.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
          } catch (InterruptedException e) {
            throw new AssertionError(e);
          }
          now.addAndGet(nanos);
        });
    assertThat(proxy.reload(config.toBuilder().shutdownAcceptGraceMillis(7000).build()).applied())
        .isTrue();
    AdminServer admin =
        AdminServer.start(new InetSocketAddress("127.0.0.1", 0), null, proxy::isShuttingDown);
    try (SSLSocket keepAlive = tlsSocket()) {
      send(keepAlive, "example.com");
      assertThat(readResponse(keepAlive.getInputStream())).startsWith("HTTP/1.1 200");
      CompletableFuture<Void> shutdown = CompletableFuture.runAsync(proxy::shutdown);
      try {
        assertThat(delaying.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        var ready =
            HttpClient.newHttpClient()
                .send(
                    HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + admin.port() + "/readyz"))
                        .build(),
                    HttpResponse.BodyHandlers.ofString());
        assertThat(ready.statusCode()).isEqualTo(503);
        send(keepAlive, "example.com");
        assertThat(readResponse(keepAlive.getInputStream())).startsWith("HTTP/1.1 200");
        try (SSLSocket fresh = tlsSocket()) {
          send(fresh, "example.com");
          assertThat(readResponse(fresh.getInputStream())).startsWith("HTTP/1.1 200");
        }
        upstreamDelayMillis = 60_000;
        send(keepAlive, "upstream.com");
        assertThat(upstreamGotRequest.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
      } finally {
        releaseDelay.countDown();
      }
      shutdown.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat(otlpRecords.get()).isEqualTo(4);
      assertThat(requestLog.lines()).hasSize(4);
      assertThat(logs.messages(Proxy.class))
          .anyMatch(m -> m.contains("requests still in flight after 7000 ms"));
      assertThat(logs.messages(Proxy.class)).noneMatch(m -> m.contains("request log not drained"));
      try (Socket probe = new Socket()) {
        assertThatThrownBy(
                () ->
                    probe.connect(
                        new InetSocketAddress("127.0.0.1", port), (int) TIMEOUT.toMillis()))
            .isInstanceOf(IOException.class);
      }
    } finally {
      releaseDelay.countDown();
      admin.stop();
    }
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
    socket.connect(new InetSocketAddress("127.0.0.1", port), (int) TIMEOUT.toMillis());
    socket.setSoTimeout((int) TIMEOUT.toMillis());
    return socket;
  }

  private static int freePort() throws IOException {
    try (ServerSocket probe = new ServerSocket(0)) {
      return probe.getLocalPort();
    }
  }

  /// Waits for the listener before a test opens its client connection.
  private void awaitListening() throws Exception {
    await(
        "proxy binds port " + port,
        () -> {
          try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress("127.0.0.1", port), 250);
            return true;
          } catch (IOException notUpYet) {
            return false;
          }
        });
  }
}
