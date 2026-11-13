package org.sensepitch.edge;

import java.util.List;
import lombok.Builder;

/// Configuration of the request log.
///
/// By default one JSON object per request is written to standard out. The field names follow the
/// OpenTelemetry semantic conventions, see [OtelLogFields] and [RequestLogFieldsWriter]. With
/// `destinations` the request log can be written to several destinations at once, e.g. standard
/// out and an OTLP endpoint like VictoriaLogs, each with its own format and anonymization setting.
///
/// @param plainText emit the legacy human readable single line format instead of JSON. Useful for
///   local debugging. Only used when no `destinations` are configured.
/// @param destinations where the request log is written to. If absent, the request log goes to
///   standard out in the format selected by `plainText`.
/// @param disabled no request log at all, the metrics per status code are still counted. A flag
///   rather than an empty list, so a later configuration layer can switch off a default.
/// @param drainTimeoutMillis the part of [ProxyConfig#shutdownTimeoutMillis()] reserved for
///   sending the request log at shutdown, after the connections are closed. It covers the records
///   of the requests aborted at shutdown. `0` means a third of the shutdown timeout.
@Builder(toBuilder = true)
public record RequestLogConfig(
    boolean plainText,
    List<RequestLogDestinationConfig> destinations,
    boolean disabled,
    long drainTimeoutMillis) {

  public static final RequestLogConfig DEFAULT = RequestLogConfig.builder().build();
}
