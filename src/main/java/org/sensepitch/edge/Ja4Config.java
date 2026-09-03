package org.sensepitch.edge;

import lombok.Builder;

/// Configuration of the JA4 TLS client fingerprint.
///
/// @param enable compute a JA4 fingerprint for every incoming TLS connection and send it to the
///   upstream in the {@value Ja4HeaderHandler#JA4_HEADER} request header
/// @param raw additionally send the unhashed fingerprint in the {@value
///   Ja4HeaderHandler#JA4_RAW_HEADER} request header. Useful to inspect the cipher and extension
///   lists a client sends, has no effect when {@code enable} is not set.
@Builder(toBuilder = true)
public record Ja4Config(boolean enable, boolean raw) {

  public static final Ja4Config DEFAULT = Ja4Config.builder().build();
}
