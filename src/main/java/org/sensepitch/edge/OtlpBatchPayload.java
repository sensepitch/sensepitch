package org.sensepitch.edge;

import static org.sensepitch.edge.OtlpEncoding.EXPORT_RESOURCE_LOGS;
import static org.sensepitch.edge.OtlpEncoding.RESOURCE_LOGS_RESOURCE;
import static org.sensepitch.edge.OtlpEncoding.RESOURCE_LOGS_SCHEMA_URL;
import static org.sensepitch.edge.OtlpEncoding.RESOURCE_LOGS_SCOPE_LOGS;
import static org.sensepitch.edge.OtlpEncoding.SCOPE_LOGS_LOG_RECORDS;
import static org.sensepitch.edge.OtlpEncoding.SCOPE_LOGS_SCHEMA_URL;
import static org.sensepitch.edge.OtlpEncoding.SCOPE_LOGS_SCOPE;
import static org.sensepitch.edge.OtlpEncoding.SCOPE_NAME;

import java.util.List;
import java.util.Map;

/// OTLP/HTTP with protobuf encoding as the payload of an [HttpBatchSender].
///
/// The records in a batch are already encoded `ScopeLogs.log_records` entries. A request wraps
/// the records of its batches into one `ExportLogsServiceRequest`, with one `ResourceLogs` for the
/// configured resource attributes and one `ScopeLogs` for the instrumentation scope
/// {@value OtlpLogDestination#SCOPE}. Only these enclosing messages are encoded per request, the
/// batches are passed to the HTTP client as they are.
///
/// @author Jens Wilke
final class OtlpBatchPayload implements BatchPayload<OtlpBatch> {

  static final String CONTENT_TYPE = "application/x-protobuf";

  private final int sampleHeadersInterval;

  /// `ResourceLogs.resource` field, constant
  private final byte[] resourceField;

  /// `ScopeLogs.scope` field, constant
  private final byte[] scopeField;

  /// `ScopeLogs.schema_url` field, follows the records
  private final byte[] scopeSchemaField;

  /// `ResourceLogs.schema_url` field, follows the `ScopeLogs`
  private final byte[] resourceSchemaField;

  /// @param resourceAttributes configured attributes, see [OtlpLogDestination#encodeResource(Map)]
  /// @param sampleHeadersInterval see [RequestLogDestinationConfig#sampleHeadersInterval()]
  OtlpBatchPayload(Map<String, String> resourceAttributes, int sampleHeadersInterval) {
    this.sampleHeadersInterval = sampleHeadersInterval;
    resourceField =
        new ProtobufWriter()
            .bytes(RESOURCE_LOGS_RESOURCE, OtlpLogDestination.encodeResource(resourceAttributes))
            .toByteArray();
    scopeField =
        new ProtobufWriter()
            .message(
                SCOPE_LOGS_SCOPE,
                new ProtobufWriter(32).string(SCOPE_NAME, OtlpLogDestination.SCOPE))
            .toByteArray();
    scopeSchemaField =
        new ProtobufWriter().string(SCOPE_LOGS_SCHEMA_URL, OtelLogFields.SCHEMA_URL).toByteArray();
    resourceSchemaField =
        new ProtobufWriter()
            .string(RESOURCE_LOGS_SCHEMA_URL, OtelLogFields.SCHEMA_URL)
            .toByteArray();
  }

  @Override
  public OtlpBatch newBatch(int batchBytes) {
    return new OtlpBatch(batchBytes);
  }

  /// Frames each record as a `ScopeLogs.log_records` entry
  @Override
  public RecordEncoder<OtlpBatch> newEncoder() {
    OtlpRequestLogSink sink = new OtlpRequestLogSink(sampleHeadersInterval);
    return (batch, info) -> {
      ProtobufWriter out = batch.writer;
      int mark = out.size();
      int record = out.startMessage(SCOPE_LOGS_LOG_RECORDS);
      try {
        sink.encode(out, info);
      } catch (RuntimeException e) {
        // leave no partial record behind
        out.truncate(mark);
        throw e;
      }
      out.endMessage(record);
    };
  }

  @Override
  public void writeBody(List<OtlpBatch> batches, BodySink sink) {
    int recordBytes = 0;
    for (OtlpBatch batch : batches) {
      recordBytes += batch.size();
    }
    byte[] prefix = prefix(recordBytes);
    sink.write(prefix, 0, prefix.length);
    for (OtlpBatch batch : batches) {
      sink.write(batch.writer.array(), 0, batch.size());
    }
    sink.write(scopeSchemaField, 0, scopeSchemaField.length);
    sink.write(resourceSchemaField, 0, resourceSchemaField.length);
  }

  @Override
  public String contentType() {
    return CONTENT_TYPE;
  }

  /// The enclosing messages up to the records, their lengths depend on the size of the records
  byte[] prefix(int recordBytes) {
    int scopeLogsLength = scopeField.length + recordBytes + scopeSchemaField.length;
    int resourceLogsLength =
        resourceField.length
            + ProtobufWriter.varintSize((long) RESOURCE_LOGS_SCOPE_LOGS << 3)
            + ProtobufWriter.varintSize(scopeLogsLength)
            + scopeLogsLength
            + resourceSchemaField.length;
    ProtobufWriter prefix = new ProtobufWriter(resourceField.length + scopeField.length + 32);
    prefix.lengthDelimited(EXPORT_RESOURCE_LOGS, resourceLogsLength);
    prefix.raw(resourceField);
    prefix.lengthDelimited(RESOURCE_LOGS_SCOPE_LOGS, scopeLogsLength);
    prefix.raw(scopeField);
    return prefix.toByteArray();
  }
}
