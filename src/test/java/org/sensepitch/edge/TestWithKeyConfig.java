package org.sensepitch.edge;

import lombok.Builder;

/**
 * @author Jens Wilke
 */
@Builder(toBuilder = true)
public record TestWithKeyConfig(
  String key,
  int number
) implements HasKey { }
