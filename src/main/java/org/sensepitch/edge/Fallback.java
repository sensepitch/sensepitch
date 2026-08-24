package org.sensepitch.edge;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/// Builds fallback handlers from config. The configured page bodies are read once, at construction,
/// and shared by every handler.
///
/// @author Raid Thabet
public class Fallback {

  /// A resolved redirect.
  ///
  /// @param status 3xx status code
  /// @param location target URL for the {@code Location} header
  public record Redirect(int status, String location) {}

  private static final String CLASSPATH_PREFIX = "classpath:";

  private static final String FILE_PREFIX = "file:";

  private final FallbackConfig config;

  private final byte[] unavailableContent;

  private final byte[] errorContent;

  /// @throws IllegalArgumentException if a page response has neither {@code file} nor {@code text}
  /// @throws UncheckedIOException if a configured page resource cannot be read
  public Fallback(FallbackConfig config) {
    this.config = config;
    this.unavailableContent = pageBody(config.unavailableResponse());
    this.errorContent = pageBody(config.errorResponse());
  }

  public FallbackHandler newHandler() {
    return new FallbackHandler(config, unavailableContent, errorContent);
  }

  /// Build the body of the page {@code cfg} describes: the resource named by {@link
  /// ResponseConfig#file()} (see {@link #openResource} for how the location is resolved), or else
  /// the inline {@link ResponseConfig#text()} as UTF-8. Returns {@code null} for a redirect, which
  /// has no body. {@link ResponseConfig} already rejects setting both {@code file} and {@code
  /// text}.
  ///
  /// @throws IllegalArgumentException if a page {@code cfg} has neither {@code file} nor {@code
  ///   text} (an empty page config)
  private static byte[] pageBody(ResponseConfig cfg) {
    if (resolvedRedirect(cfg.location(), cfg.status()) != null) {
      return null;
    }
    if (cfg.file() != null) {
      return readResource(cfg.file());
    }
    if (cfg.text() != null) {
      return cfg.text().getBytes(StandardCharsets.UTF_8);
    }
    throw new IllegalArgumentException("page config has neither file nor text");
  }

  /// Read the resource fully; an unresolvable location is a configuration error that fails
  /// construction.
  private static byte[] readResource(String file) {
    try (InputStream in = openResource(file)) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read fallback page: " + file, e);
    }
  }

  /// Open the resource named by {@code location}. An explicit {@code classpath:} or {@code file:}
  /// scheme picks exactly that source; a bare (schemeless) location is tried on the filesystem
  /// first and then on the classpath.
  private static InputStream openResource(String location) throws IOException {
    if (location.startsWith(CLASSPATH_PREFIX)) {
      return openClasspath(location.substring(CLASSPATH_PREFIX.length()));
    }
    if (location.startsWith(FILE_PREFIX)) {
      return new FileInputStream(location.substring(FILE_PREFIX.length()));
    }
    // No scheme: try the filesystem first, then the classpath.
    Path filePath = Path.of(location);
    if (Files.isReadable(filePath)) {
      return Files.newInputStream(filePath);
    }
    return openClasspath(location);
  }

  private static InputStream openClasspath(String path) throws IOException {
    if (path.startsWith("/")) {
      path = path.substring(1);
    }
    InputStream in = Fallback.class.getClassLoader().getResourceAsStream(path);
    if (in == null) {
      throw new FileNotFoundException("Classpath resource not found: " + path);
    }
    return in;
  }

  public static Redirect resolvedRedirect(String location, int status) {
    if (location != null) return new Redirect(status, location);
    return null;
  }
}
