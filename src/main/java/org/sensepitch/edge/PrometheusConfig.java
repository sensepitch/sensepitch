package org.sensepitch.edge;

import lombok.Builder;

/**
 * @author Jens Wilke
 */
@Builder(toBuilder = true)
public record PrometheusConfig(int port, boolean enableJvmMetrics) {

  public static final PrometheusConfig DEFAULT =
      builder().enableJvmMetrics(true).port(9400).build();
}
