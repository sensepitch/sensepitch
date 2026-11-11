package org.sensepitch.edge;

import java.util.Map;
import lombok.Builder;

/// Configuration that environment variables cannot fill, see `ConfigTest`
///
/// @param ratio a type not supported by [EnvInjector]
/// @param noKeys map values without [HasKey], so the key cannot be injected
/// @author Jens Wilke
@Builder(toBuilder = true)
public record EnvErrorsConfig(double ratio, Map<String, AllFieldTypesConfig> noKeys) {}
