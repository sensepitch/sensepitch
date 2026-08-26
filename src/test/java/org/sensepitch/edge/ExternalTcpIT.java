package org.sensepitch.edge;

import io.netty.util.concurrent.EventExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the proxy on a real TCP port with TLS.
 *
 * @author Raid Thabet
 */
@Timeout(value = 40, unit = TimeUnit.SECONDS)
class ExternalTcpIT {

  private final short ALWAYS_CLOSED_PORT = 1;

  private Proxy proxy;
  private int port;
  private ByteArrayOutputStream logBuffer;
  private PrintStream originalLogOutput;

  @BeforeEach
  void startProxy() throws Exception {
    port = freePort();

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
                    // port 1 is closed, so forwarding here fails and the proxy logs
                    "deadupstream.com",
                    SiteConfig.builder()
                        .upstream(UpstreamConfig.builder().target("127.0.0.1:" + ALWAYS_CLOSED_PORT).build())
                        .protection(ProtectionConfig.builder().disable(true).build())
                        .build()))
            .metrics(MetricsConfig.builder().enable(false).build())
            .build();

    proxy = new Proxy(config);
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
    // redirect only now, so startup and the readiness probe stay out of the buffer
    logBuffer = new ByteArrayOutputStream();
    originalLogOutput = redirectProxyLog(new PrintStream(logBuffer, true));
  }

  @AfterEach
  void stopProxy() throws Exception {
    redirectProxyLog(originalLogOutput);
    proxy.shutdown();
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
              .write(
                      "GET / HTTP/1.0\r\nHost: example.com\r\n\r\n"
                              .getBytes(StandardCharsets.US_ASCII));
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
              "GET / HTTP/1.1\r\nHost: deadupstream.com\r\nConnection: close\r\n\r\n" // http 1.0 version (separate test)
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

  /**
   * The upstream connect fails asynchronously, so the log is not written yet when the request has
   * been flushed. Reading the fallback response to EOF is the observable proof that the proxy is
   * done with the failed upstream, which puts the log write in the queue that {@link
   * #drainEventLoops()} then drains.
   */
  private void awaitUpstreamFailureHandled(SSLSocket socket) throws Exception {
    assertThat(socket.getInputStream().readAllBytes())
        .describedAs("proxy must answer even when the upstream is dead")
        .isNotEmpty();
    drainEventLoops();
  }

  private void assertLogged() {
    assertThat(logBuffer.toString(StandardCharsets.UTF_8))
        .describedAs("proxy must log the failed upstream connection")
        .isNotEmpty();
  }

  private void assertNothingLogged() throws InterruptedException {
    drainEventLoops();
    assertThat(logBuffer.toString(StandardCharsets.UTF_8))
        .describedAs("proxy must stay silent for malformed input")
        .isEmpty();
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

  private static PrintStream redirectProxyLog(PrintStream target) throws Exception {
    Field field = LogTarget.StreamOutput.class.getDeclaredField("output");
    field.setAccessible(true);
    PrintStream previous = (PrintStream) field.get(LogTarget.INSTANCE); // get output from INSTANCE
    field.set(LogTarget.INSTANCE, target);
    return previous;
  }

  /** Writes bytes that are not a valid TLS ClientHello, then expects the peer to hang up. */
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
