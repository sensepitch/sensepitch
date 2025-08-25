package org.sensepitch.edge;

import java.util.List;
import java.util.Map;
import lombok.Builder;

/**
 * @author Jens Wilke
 */
@Builder(toBuilder = true)
public record AllFieldTypesConfig(
    int number,
    boolean flag,
    String text,
    List<String> texts,
    List<AllFieldTypesConfig> list,
    Map<String, TestWithKeyConfig> map) {}
