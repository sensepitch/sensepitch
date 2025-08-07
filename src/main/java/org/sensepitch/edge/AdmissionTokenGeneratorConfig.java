package org.sensepitch.edge;

import lombok.Builder;

/**
 * @author Jens Wilke
 */
@Builder
public record AdmissionTokenGeneratorConfig(String prefix, String secret) {}
