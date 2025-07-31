package org.sensepitch.edge;

import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * @author Jens Wilke
 */
@Builder(toBuilder = true)
public record AllFieldTypesConfig(
  int number,
  boolean flag,
  String text,
  List<String> texts,
  List<AllFieldTypesConfig> configList,
  Map<String, AllFieldTypesConfig> configMap,
  Map<String, TestWithKeyConfig> withKeyMap
) {
}
