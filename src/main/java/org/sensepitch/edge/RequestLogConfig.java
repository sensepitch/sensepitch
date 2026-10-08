package org.sensepitch.edge;

import lombok.Builder;

/// Configuration of the request log output.
///
/// @param plainText emit the legacy human readable single line format instead of JSON. Useful for
///   local debugging. By default one JSON object per request is written, so a log collector can
///   recognise the log source via the `type` property.
@Builder(toBuilder = true)
public record RequestLogConfig(boolean plainText) {

  public static final RequestLogConfig DEFAULT = RequestLogConfig.builder().build();
}
