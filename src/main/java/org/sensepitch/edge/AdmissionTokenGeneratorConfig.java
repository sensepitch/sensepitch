package org.sensepitch.edge;

import lombok.Builder;

/// @author Jens Wilke
@Builder(toBuilder = true)
public record AdmissionTokenGeneratorConfig(String prefix, String secret) {}
