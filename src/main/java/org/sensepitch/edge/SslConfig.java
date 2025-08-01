package org.sensepitch.edge;

import lombok.Builder;

/**
 * @author Jens Wilke
 */
@Builder
public record SslConfig(
  String keyPath,
  String certPath
){ }
