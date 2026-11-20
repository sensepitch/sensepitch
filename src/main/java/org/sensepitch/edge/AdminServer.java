package org.sensepitch.edge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/// Admin HTTP endpoint, by default on the loopback interface only, like the Caddy admin API.
///
/// - `/healthz` always answers `200` while the admin server is running.
/// - `/readyz` answers `200` while not shutting down and the readiness supplier reports `true`,
///   `503` otherwise.
/// - `POST /reload` reloads the configuration file, also if unchanged, which re-reads the
///   certificate files. Answers `200` if applied, `422` with the reason if rejected, and `409` if
///   the configuration does not come from a file.
///
/// Example: `curl -X POST http://127.0.0.1:9401/reload`
///
/// Uses the HTTP server of the JDK, the traffic is a few requests by operators or scripts.
///
/// @author Jens Wilke
public class AdminServer {

  private static final ProxyLogger LOG = ProxyLogger.get(AdminServer.class);

  private final HttpServer server;

  private AdminServer(HttpServer server) {
    this.server = server;
  }

  /// @param reload reloads the configuration file, or `null` if the configuration does not come
  ///   from a file
  /// @param shuttingDown whether the proxy has started shutting down, `/readyz` answers `503`
  ///   then
  public static AdminServer start(
      AdminConfig cfg, Supplier<ReloadResult> reload, BooleanSupplier shuttingDown)
      throws IOException {
    return start(cfg, reload, shuttingDown, () -> true);
  }

  /// @param reload see [#start(AdminConfig, Supplier, BooleanSupplier)]
  /// @param shuttingDown see [#start(AdminConfig, Supplier, BooleanSupplier)]
  /// @param ready whether `/readyz` answers `200` while not shutting down, e.g. the upstream
  ///   readiness probe
  public static AdminServer start(
      AdminConfig cfg,
      Supplier<ReloadResult> reload,
      BooleanSupplier shuttingDown,
      BooleanSupplier ready)
      throws IOException {
    String address = cfg.address() != null ? cfg.address() : AdminConfig.DEFAULT_ADDRESS;
    int port = cfg.port() != 0 ? cfg.port() : AdminConfig.DEFAULT_PORT;
    return start(new InetSocketAddress(address, port), reload, shuttingDown, ready);
  }

  /// @param bind address and port, port 0 picks a free port
  static AdminServer start(
      InetSocketAddress bind, Supplier<ReloadResult> reload, BooleanSupplier shuttingDown)
      throws IOException {
    return start(bind, reload, shuttingDown, () -> true);
  }

  /// @param bind address and port, port 0 picks a free port
  static AdminServer start(
      InetSocketAddress bind,
      Supplier<ReloadResult> reload,
      BooleanSupplier shuttingDown,
      BooleanSupplier ready)
      throws IOException {
    HttpServer server = HttpServer.create(bind, 0);
    server.createContext("/", exchange -> handle(exchange, reload, shuttingDown, ready));
    server.start();
    LOG.info(
        "Admin server listening on http://"
            + server.getAddress().getHostString()
            + ":"
            + server.getAddress().getPort());
    return new AdminServer(server);
  }

  private static void handle(
      HttpExchange exchange,
      Supplier<ReloadResult> reload,
      BooleanSupplier shuttingDown,
      BooleanSupplier ready)
      throws IOException {
    try (exchange) {
      String path = exchange.getRequestURI().getPath();
      if (path.equals("/healthz")) {
        respond(exchange, 200, "OK");
      } else if (path.equals("/readyz")) {
        boolean stopping = shuttingDown.getAsBoolean();
        boolean readyNow = !stopping && ready.getAsBoolean();
        int status = stopping || !readyNow ? 503 : 200;
        String body = stopping ? "Shutting down" : readyNow ? "OK" : "No upstream available";
        respond(exchange, status, body);
      } else if (!path.equals("/reload")) {
        respond(exchange, 404, "Not found");
      } else if (!exchange.getRequestMethod().equals("POST")) {
        exchange.getResponseHeaders().set("Allow", "POST");
        respond(exchange, 405, "Use POST");
      } else if (reload == null) {
        respond(exchange, 409, "No configuration file to reload");
      } else {
        LOG.info("Reload requested via admin endpoint");
        ReloadResult result;
        try {
          result = reload.get();
        } catch (RuntimeException e) {
          LOG.error("Reload via admin endpoint failed", e);
          result = ReloadResult.rejected(e.toString());
        }
        respond(exchange, result.applied() ? 200 : 422, result.message());
      }
    }
  }

  private static void respond(HttpExchange exchange, int status, String text) throws IOException {
    byte[] body = (text + "\n").getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
    exchange.sendResponseHeaders(status, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }

  /// Port the server listens on, for tests with port 0
  int port() {
    return server.getAddress().getPort();
  }

  InetSocketAddress address() {
    return server.getAddress();
  }

  public void stop() {
    server.stop(0);
  }
}
