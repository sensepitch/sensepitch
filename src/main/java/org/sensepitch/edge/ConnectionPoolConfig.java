package org.sensepitch.edge;

import lombok.Builder;

/**
 * @author Jens Wilke
 */
@Builder(toBuilder = true)
public record ConnectionPoolConfig(int idleTimeoutSeconds, int maxSize) {

  static int IDLE_TIMEOUT_SECONDS = 30;

  static ConnectionPoolConfig DEFAULT =
      ConnectionPoolConfig.builder()
          .idleTimeoutSeconds(IDLE_TIMEOUT_SECONDS)
          .maxSize(10_000)
          .build();
}
