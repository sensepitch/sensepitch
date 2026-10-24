package org.sensepitch.edge;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/// Builds fallback handlers from config. The configured page bodies are read once, at construction,
/// and shared by every handler.
///
/// @author Raid Thabet
public class Fallback {

  /// A resolved redirect.
  ///
  /// @param status 3xx status code
  /// @param location target URL for the `Location` header
  public record Redirect(int status, String location) {}

  private final FallbackConfig config;

  private final byte[] unavailableContent;

  private final byte[] errorContent;

  /// @throws IllegalArgumentException if a page response has neither `file` nor `text`
  /// @throws UncheckedIOException if a configured page resource cannot be read
  public Fallback(FallbackConfig config) {
    this.config = config;
    this.unavailableContent = pageBody(config.unavailableResponse());
    this.errorContent = pageBody(config.errorResponse());
  }

  public FallbackHandler newHandler() {
    return new FallbackHandler(config, unavailableContent, errorContent);
  }

  /// Build the body of the page `cfg` describes: the resource named by [ResponseConfig#file()] (see
  /// [ResponseFiles] for how the location is resolved), or else the inline [ResponseConfig#text()]
  /// as UTF-8. Returns `null` for a redirect, which has no body. [ResponseConfig] already rejects
  /// setting both `file` and `text`.
  ///
  /// @throws IllegalArgumentException if a page `cfg` has neither `file` nor `text` (an empty page
  ///   config)
  private static byte[] pageBody(ResponseConfig cfg) {
    if (resolvedRedirect(cfg.location(), cfg.status()) != null) {
      return null;
    }
    if (cfg.file() != null) {
      return ResponseFiles.read(cfg.file());
    }
    if (cfg.text() != null) {
      return cfg.text().getBytes(StandardCharsets.UTF_8);
    }
    throw new IllegalArgumentException("page config has neither file nor text");
  }

  public static Redirect resolvedRedirect(String location, int status) {
    if (location != null) return new Redirect(status, location);
    return null;
  }
}
