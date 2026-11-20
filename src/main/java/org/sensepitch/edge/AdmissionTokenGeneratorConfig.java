package org.sensepitch.edge;

import lombok.Builder;

/// One admission token generator.
///
/// @param prefix first character of the tokens it issues, selects the generator when a token is
///   verified
/// @param secret mandatory: the secret of the token hash, inline or from a file, see
///   [SecretConfig]
/// @author Jens Wilke
@Builder(toBuilder = true)
public record AdmissionTokenGeneratorConfig(String prefix, SecretConfig secret) {

  public AdmissionTokenGeneratorConfig {
    if (secret == null) {
      throw new IllegalArgumentException(
          "tokenGenerators entry needs a secret, e.g. secret: {file: /path/to/secret}");
    }
  }
}
