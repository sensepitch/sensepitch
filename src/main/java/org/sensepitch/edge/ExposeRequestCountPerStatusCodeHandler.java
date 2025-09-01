package org.sensepitch.edge;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.prometheus.metrics.core.metrics.Histogram;
import io.prometheus.metrics.model.registry.Collector;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.CounterSnapshot.CounterDataPointSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import io.prometheus.metrics.model.snapshots.Unit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * @author Jens Wilke
 */
public class ExposeRequestCountPerStatusCodeHandler implements HasMultipleMetrics, RequestLogger {

  private final Histogram requestDuration =
      Histogram.builder()
          .name("sensepitch_ingress_request_duration_seconds")
          .help("HTTP total duration of the request from established connection to response received")
          .unit(Unit.SECONDS)
          .labelNames("ingress", "method", "status_code")
          .classicExponentialUpperBounds(0.002, 2.0, 14)
          .build();

  private final Histogram responseTime =
      Histogram.builder()
          .name("sensepitch_ingress_response_duration_seconds")
          .help("HTTP request response duration in seconds, from request received to first byte " +
            "of response, not effected by client connectivity")
          .unit(Unit.SECONDS)
          .labelNames("ingress", "method", "status_code")
          .classicExponentialUpperBounds(0.002, 2.0, 14)
          .build();

  private final Histogram responseSize =
    Histogram.builder()
      .name("sensepitch_ingress_response_size")
      .help("")
      .unit(Unit.BYTES)
      .labelNames("ingress")
      .classicExponentialUpperBounds(256, 2.0, 14)
      .build();

  public ExposeRequestCountPerStatusCodeHandler() {}

  @Override
  public void registerCollectors(Consumer<Collector> consumer) {
    consumer.accept(requestDuration);
    consumer.accept(responseTime);
    consumer.accept(responseSize);
  }

  @Override
  public void logRequest(RequestLogInfo info) {
    String ingress = info.request().headers().get(HttpHeaderNames.HOST);
    int statusCode = info.response().status().code();
    // timeout before request was received, ignore
    if (statusCode == 408 && info.request().method().equals(RequestLogInfo.NIL_METHOD)) {
      return;
    }
    if (statusCode < 100) {
      statusCode = 99;
    }
    if (statusCode >= 600) {
      statusCode = 600;
    }
    String[] labelValues =
        new String[] {
          ingress,
          info.request().method().name(),
          statusCode + ""
        };
    requestDuration
        .labelValues(labelValues)
        .observe(Unit.nanosToSeconds(info.totalDurationNanos()));
    responseTime.labelValues(labelValues).observe(Unit.nanosToSeconds(info.responseTimeNanos()));
    // TODO: need to add header and http framing overhead
    responseSize.labelValues(ingress).observe(info.contentBytes());
  }
}
