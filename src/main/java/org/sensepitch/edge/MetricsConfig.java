package org.sensepitch.edge;

import lombok.Builder;

/**
 * @author Jens Wilke
 */
@Builder(toBuilder = true)
public record MetricsConfig(boolean enable, PrometheusConfig prometheus) {

  public static final MetricsConfig DEFAULT =
      builder().enable(true).prometheus(PrometheusConfig.DEFAULT).build();
}
