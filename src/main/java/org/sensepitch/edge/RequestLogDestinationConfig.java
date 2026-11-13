package org.sensepitch.edge;

import lombok.Builder;

/// One destination of the request log, see [RequestLogConfig#destinations()].
///
/// @param type `stdout` writes one record per line to standard out, `otlp` sends the records via
///   OTLP/HTTP with protobuf encoding, e.g. to VictoriaLogs or an OpenTelemetry collector
/// @param name name of the destination, must be unique. It identifies the destination across a
///   configuration reload: an unchanged destination is kept, with its buffered records. A changed
///   or removed `otlp` destination keeps sending its buffered records to its own endpoint, without
///   delaying the reload, until they are sent or older than `maximumUnsentLogAgeMillis`. Renaming
///   counts as removing the destination and adding a new one. Defaults to the type for a single
///   destination of a type, further `stdout` destinations get the index appended. Several `otlp`
///   destinations must be named, so inserting one does not replace the unchanged ones. An `otlp` destination reports its metrics with the name as
///   `destination` label. All `stdout` destinations share the standard out writer, labelled
///   `stdout`, so that name is reserved.
/// @param anonymizeClientIp truncate the client address, see [AnonymizedClientRequestLogInfo]
/// @param plainText `stdout` only: emit the legacy human readable single line format instead of
///   JSON
/// @param sampleHeadersInterval if positive, about one in this many requests, picked at random,
///   also logs its request headers as `sensepitch.request.sample_headers`, to study what
///   identifies a client. Cookies, credentials, `traceparent` and the headers the edge adds are
///   left out. `0`, the default, turns it off. Not supported by the plain text format.
/// @param otlp `otlp` only, mandatory for that type: endpoint, batching and resource settings
/// @param disabled skip this destination, e.g. standard out while `otlp` stays. A flag, so a
///   later configuration layer can switch off a destination. The name still counts, so enabling
///   it later does not change the names of the others.
/// @author Jens Wilke
@Builder(toBuilder = true)
public record RequestLogDestinationConfig(
    String type,
    String name,
    boolean anonymizeClientIp,
    boolean plainText,
    int sampleHeadersInterval,
    OtlpConfig otlp,
    boolean disabled) {

  public static final String TYPE_STDOUT = "stdout";
  public static final String TYPE_OTLP = "otlp";
}
