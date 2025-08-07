package org.sensepitch.edge;

import java.util.List;
import lombok.Builder;

/**
 * @author Jens Wilke
 */
@Builder
public record NestedTestConfig(
    boolean enable, AllFieldTypesConfig all, List<AllFieldTypesConfig> list) {}
