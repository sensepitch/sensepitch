package org.sensepitch.edge;

import lombok.Builder;

/// A secret, given either inline as `value` or as a `file` to read it from, e.g. a mounted
/// Kubernetes Secret. Exactly one of the two is set. The file is read by
/// [ConfigFiles#secret(SecretConfig)] when the configuration is applied, a reload reads it again.
///
/// ```yaml
/// secret:
///   file: /etc/sensepitch-edge/token-secret
/// ```
///
/// [#toString()] and the YAML dump of the configuration, see [Proxy#toYaml(ProxyConfig)], mask
/// the value, so it does not end up in the diagnostic log.
///
/// @param value the secret in clear text
/// @param file path of a file holding the secret; one final line break (`\n` or `\r\n`) is
///   removed, other characters are kept. An empty file is rejected.
/// @author Jens Wilke
@Builder(toBuilder = true)
public record SecretConfig(String value, String file) {

  /// Replaces the value in [#toString()] and the YAML dump
  static final String MASK = "*****";

  public SecretConfig {
    if ((value == null) == (file == null)) {
      throw new IllegalArgumentException(
          "A secret needs exactly one of value or file, e.g. secret: {file: /path/to/secret}");
    }
    if (value != null && value.isBlank()) {
      throw new IllegalArgumentException("A secret value must not be empty");
    }
  }

  /// The value masked, so a logged configuration does not reveal it
  @Override
  public String toString() {
    return "SecretConfig[value=" + (value != null ? MASK : null) + ", file=" + file + "]";
  }
}
