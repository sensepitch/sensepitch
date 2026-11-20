package org.sensepitch.edge;

import lombok.Builder;

/// An HTTP request header whose value is a secret, e.g. the `Authorization` header of a log
/// server, see [OtlpConfig#secretHeaders()].
///
/// @param name mandatory: the header name
/// @param secret mandatory: the header value, inline or from a file, see [SecretConfig]
/// @author Jens Wilke
@Builder(toBuilder = true)
public record SecretHeaderConfig(String name, SecretConfig secret) {

  public SecretHeaderConfig {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("secretHeaders entry needs a name");
    }
    if (secret == null) {
      throw new IllegalArgumentException(
          "secretHeaders entry " + name + " needs a secret, e.g. secret: {file: /path/to/secret}");
    }
  }
}
