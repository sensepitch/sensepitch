package org.sensepitch.edge;

import lombok.Builder;

/// One destination of the request log, see [RequestLogConfig#destinations()].
///
/// @param type `stdout` writes one record per line to standard out, `otlp` sends the records via
///   OTLP/HTTP with protobuf encoding, e.g. to VictoriaLogs or an OpenTelemetry collector
/// @param name name of the destination, must be unique. Defaults to the type, followed by the
///   index for the second destination of a type. An `otlp` destination reports its metrics with
///   the name as `destination` label. All `stdout` destinations share the standard out writer,
///   labelled `stdout`, so that name is reserved.
/// @param anonymizeClientIp truncate the client address, see [AnonymizedClientRequestLogInfo]
/// @param plainText `stdout` only: emit the legacy human readable single line format instead of
///   JSON
/// @param otlp `otlp` only, mandatory for that type: endpoint, batching and resource settings
/// @author Jens Wilke
@Builder(toBuilder = true)
public record RequestLogDestinationConfig(
    String type, String name, boolean anonymizeClientIp, boolean plainText, OtlpConfig otlp) {

  public static final String TYPE_STDOUT = "stdout";
  public static final String TYPE_OTLP = "otlp";
}
