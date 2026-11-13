package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/// Checks the fat JAR produced by the shade plugin, as it is deployed.
///
/// The proxy always uses [io.netty.handler.ssl.SslProvider#OPENSSL], so a TLS handshake with the
/// JAR started in a separate JVM proves that the tcnative library of the build host was found in
/// the JAR, extracted and loaded. The libraries of the other platforms cannot be loaded here, so
/// for those the JAR entry is checked to be a binary for the expected OS and CPU.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ShadedJarIT {

  private static Path jar;

  @TempDir Path dir;

  private Process process;

  @BeforeAll
  static void locateJar() {
    String property = System.getProperty("shadedJar");
    assertThat(property).as("system property shadedJar, set by failsafe").isNotNull();
    jar = Path.of(property);
    assertThat(jar).as("shaded JAR, built in the package phase").isRegularFile();
  }

  @AfterEach
  void stopProcess() throws InterruptedException {
    if (process != null) {
      process.destroy();
      if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroyForcibly();
      }
    }
  }

  @Test
  void servesHttpsWithHostNativeLibrary() throws Exception {
    int port = freePort();
    Path config = writeConfig(port);
    Path stderr = dir.resolve("stderr.log");
    process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar",
                jar.toString(),
                config.toString())
            .redirectOutput(dir.resolve("stdout.log").toFile())
            .redirectError(stderr.toFile())
            .start();
    awaitOutput(stderr, "Proxy listening on 127.0.0.1:" + port);

    String response = get(port);

    assertThat(response).startsWith("HTTP/1.1 200").endsWith("ok");
    List<String> lines = Files.readAllLines(stderr);
    assertThat(lines).anyMatch(line -> line.contains("Open SSL: BoringSSL"));
    // e.g. the JDK warning about restricted methods, when native access is not enabled
    assertThat(lines).noneMatch(line -> line.startsWith("WARNING"));
  }

  /// `SIGTERM` shuts down gracefully: the request log is complete when the process exits, also the
  /// OTLP export, which flushes only every minute, and the process exits right away when there
  /// is nothing left to wait for
  @Test
  void sigtermSendsTheRequestLogAndExits() throws Exception {
    java.util.concurrent.atomic.AtomicInteger records =
        new java.util.concurrent.atomic.AtomicInteger();
    com.sun.net.httpserver.HttpServer logServer =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    logServer.createContext(
        "/v1/logs",
        exchange -> {
          records.addAndGet(
              OtlpLogDestinationTest.decode(exchange)
                  .getResourceLogs(0)
                  .getScopeLogs(0)
                  .getLogRecordsCount());
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    logServer.start();
    try {
      int port = freePort();
      Path config =
          writeConfig(
              port,
              """
              requestLog:
                destinations:
                  - type: stdout
                  - type: otlp
                    otlp:
                      endpoint: http://127.0.0.1:%d/v1/logs
                      flushIntervalMillis: 60000
              """
                  .formatted(logServer.getAddress().getPort()));
      Path stderr = dir.resolve("stderr.log");
      Path stdout = dir.resolve("stdout.log");
      process =
          new ProcessBuilder(
                  Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                  "-jar",
                  jar.toString(),
                  config.toString())
              .redirectOutput(stdout.toFile())
              .redirectError(stderr.toFile())
              .start();
      awaitOutput(stderr, "Proxy listening on 127.0.0.1:" + port);
      assertThat(get(port)).startsWith("HTTP/1.1 200");
      long start = System.nanoTime();
      process.destroy();
      assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("exited").isTrue();
      assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
          .as("faster than the shutdown timeout")
          .isLessThan(4_000);
      assertThat(Files.readAllLines(stdout)).singleElement().asString().contains("\"url.path\"");
      assertThat(records.get()).as("OTLP record sent before the exit").isOne();
      assertThat(Files.readAllLines(stderr)).noneMatch(line -> line.contains("Shutdown:"));
    } finally {
      logServer.stop(0);
    }
  }

  /// Checks the header of each bundled tcnative library: object file format and CPU type.
  @ParameterizedTest
  @CsvSource({
    "libnetty_tcnative_linux_x86_64.so,       ELF,    x86_64",
    "libnetty_tcnative_linux_aarch_64.so,     ELF,    aarch64",
    "libnetty_tcnative_osx_x86_64.jnilib,     Mach-O, x86_64",
    "libnetty_tcnative_osx_aarch_64.jnilib,   Mach-O, aarch64",
    "netty_tcnative_windows_x86_64.dll,       PE,     x86_64",
  })
  void containsNativeLibraryForPlatform(String name, String format, String cpu) throws IOException {
    byte[] header;
    try (JarFile file = new JarFile(jar.toFile())) {
      ZipEntry entry = file.getEntry("META-INF/native/" + name);
      assertThat(entry).as(name).isNotNull();
      try (InputStream in = file.getInputStream(entry)) {
        header = in.readNBytes(4096);
      }
    }
    assertThat(binaryType(header)).isEqualTo(format + " " + cpu);
  }

  /// A snapshot build gets a unique version, e.g. `0.2-20261112.153045-dbe0c1a`, a release build
  /// keeps the plain version.
  @Test
  void manifestHasUniqueVersion() throws IOException {
    String version;
    try (JarFile file = new JarFile(jar.toFile())) {
      version = file.getManifest().getMainAttributes().getValue("Implementation-Version");
    }
    assertThat(version).matches("\\d+(\\.\\d+)*(-\\d{8}\\.\\d{6}-[0-9a-f]{7})?");
  }

  /// Object file format and CPU of a native library, derived from its first bytes.
  static String binaryType(byte[] header) {
    ByteBuffer buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
    if (buf.getInt(0) == 0x464c457f) { // "\x7fELF"
      return "ELF " + cpuName(buf.getShort(18), 0x3e, 0xb7);
    }
    if (buf.getInt(0) == 0xfeedfacf) { // Mach-O 64 bit, little endian
      return "Mach-O " + cpuName(buf.getInt(4), 0x01000007, 0x0100000c);
    }
    if (buf.getShort(0) == 0x5a4d) { // "MZ", the PE header follows at offset e_lfanew
      int pe = buf.getInt(0x3c);
      assertThat(buf.getInt(pe)).as("PE signature").isEqualTo(0x00004550);
      return "PE " + cpuName(buf.getShort(pe + 4) & 0xffff, 0x8664, 0xaa64);
    }
    return "unknown";
  }

  private static String cpuName(int code, int x86_64, int aarch64) {
    if (code == x86_64) {
      return "x86_64";
    }
    if (code == aarch64) {
      return "aarch64";
    }
    return "cpu 0x" + Integer.toHexString(code);
  }

  /// Same shape as `performance-test/ja4-benchmark.yaml`: the response comes from the proxy
  /// itself, so no upstream is needed.
  private Path writeConfig(int port) throws IOException {
    return writeConfig(port, "");
  }

  /// @param extra YAML appended to the configuration
  private Path writeConfig(int port, String extra) throws IOException {
    Path cert = copyResource("ssl/test.crt");
    Path key = copyResource("ssl/test.key");
    String yaml =
        """
        listen:
          address: 127.0.0.1
          httpsPort: %d
          ssl:
            certPath: %s
            keyPath: %s
        metrics:
          enable: false
        protection:
          disable: true
        sites:
          localhost:
            response:
              text: ok
        """
                .formatted(port, cert, key)
            + extra;
    return Files.writeString(dir.resolve("config.yaml"), yaml);
  }

  /// The forked JVM sees only the JAR, not the test classpath.
  private Path copyResource(String name) throws IOException {
    Path target = dir.resolve(Path.of(name).getFileName());
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
      assertThat(in).as(name).isNotNull();
      Files.copy(in, target);
    }
    return target;
  }

  @SuppressWarnings("BusyWait")
  private void awaitOutput(Path log, String text) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (!Files.readString(log).contains(text)) {
      if (!process.isAlive()) {
        throw new IllegalStateException(
            "JAR exited with " + process.exitValue() + ":\n" + Files.readString(log));
      }
      if (System.nanoTime() > deadline) {
        throw new IllegalStateException("JAR did not log: " + text + "\n" + Files.readString(log));
      }
      Thread.sleep(50);
    }
  }

  private static String get(int port) throws Exception {
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
        null);
    try (SSLSocket socket =
        (SSLSocket) context.getSocketFactory().createSocket("127.0.0.1", port)) {
      socket.setSoTimeout(10_000);
      socket
          .getOutputStream()
          .write(
              "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                  .getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      return new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
    }
  }

  private static int freePort() throws IOException {
    try (ServerSocket probe = new ServerSocket(0)) {
      return probe.getLocalPort();
    }
  }
}
