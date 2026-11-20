package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.sensepitch.edge.CompleteTest.COMMON_CONFIG;
import static org.sensepitch.edge.TestingParameters.TIMEOUT_SECONDS;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/// [ProxyConfig#shutdownAcceptGraceMillis()] on a fake clock: budget validation, reload, spurious
/// wakeups and concurrent callers. Real listener and request log behaviour is covered by
/// [ProxyShutdownIT].
class ProxyAcceptGraceTest {

  @RegisterExtension LogCapture logs = new LogCapture();

  /// Shutdown and drain timeout, `null` if omitted, accept grace, valid
  static Stream<Arguments> budgets() {
    return Stream.of(
        Arguments.of(null, null, 0L, true),
        Arguments.of(null, null, 3334L, true),
        Arguments.of(null, null, 3335L, false),
        Arguments.of(6000L, null, 4000L, true),
        Arguments.of(6000L, null, 4001L, false),
        Arguments.of(5000L, 1000L, 4000L, true),
        Arguments.of(5000L, 1000L, 4001L, false),
        Arguments.of(5000L, 5000L, 0L, true),
        Arguments.of(5000L, 5000L, 1L, false),
        Arguments.of(null, null, -1L, false),
        Arguments.of(null, null, Long.MAX_VALUE, false),
        Arguments.of(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, false));
  }

  @ParameterizedTest
  @MethodSource("budgets")
  void validatesTheBudget(Long total, Long drain, long grace, boolean valid) {
    ProxyConfig.Builder builder =
        COMMON_CONFIG.toBuilder()
            .shutdownAcceptGraceMillis(grace)
            .requestLog(RequestLogConfig.builder().drainTimeoutMillis(drain).build());
    if (total != null) {
      builder.shutdownTimeoutMillis(total);
    }
    ProxyConfig cfg = builder.build();
    if (valid) {
      cfg.checkShutdownBudget();
      long expectedTotal = total != null ? total : ProxyConfig.DEFAULT_SHUTDOWN_TIMEOUT_MILLIS;
      assertThat(cfg.requestLog().drainTimeoutMillis())
          .isEqualTo(drain != null ? drain : expectedTotal / 3);
    } else {
      assertThatThrownBy(() -> new Proxy(cfg, new RequestLogCapture()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("shutdownAcceptGraceMillis");
    }
  }

  @Test
  void noGraceByDefault() {
    Proxy proxy =
        new Proxy(
            COMMON_CONFIG,
            new RequestLogCapture(),
            () -> 123L,
            ignored -> {
              throw new AssertionError("no grace must not wait");
            });
    proxy.shutdown();
    proxy.shutdown();
  }

  @Test
  void reloadChangesGraceAndInvalidReloadKeepsActiveBudget() {
    AtomicLong now = new AtomicLong(9000);
    AtomicLong waited = new AtomicLong();
    Proxy proxy =
        new Proxy(
            COMMON_CONFIG,
            new RequestLogCapture(),
            now::get,
            nanos -> {
              waited.addAndGet(nanos);
              now.addAndGet(nanos);
            });
    try {
      ProxyConfig next =
          COMMON_CONFIG.toBuilder()
              .shutdownAcceptGraceMillis(4000)
              .shutdownTimeoutMillis(6000)
              .build();
      assertThat(proxy.reload(next).applied()).isTrue();
      assertThat(logs.messages(Proxy.class)).noneMatch(m -> m.contains("needs a restart"));
      assertThat(proxy.reload(next.toBuilder().shutdownTimeoutMillis(5000).build()).applied())
          .isFalse();
      assertThat(proxy.reload(next.toBuilder().shutdownAcceptGraceMillis(-1).build()).applied())
          .isFalse();
      proxy.shutdown();
      assertThat(waited.get()).isEqualTo(TimeUnit.MILLISECONDS.toNanos(4000));
    } finally {
      proxy.shutdown();
    }
  }

  @Test
  void adminChangeNeedsARestart() {
    ProxyConfig cfg = COMMON_CONFIG.toBuilder().admin(AdminConfig.builder().build()).build();
    Proxy proxy = new Proxy(cfg, new RequestLogCapture());
    try {
      assertThat(
              proxy
                  .reload(
                      cfg.toBuilder().admin(cfg.admin().toBuilder().port(12345).build()).build())
                  .applied())
          .isTrue();
      assertThat(logs.messages(Proxy.class)).anyMatch(m -> m.contains("'admin' needs a restart"));
    } finally {
      proxy.shutdown();
    }
  }

  @Test
  void spuriousWakeupAndInterruptDoNotShortenTheGrace() {
    AtomicLong now = new AtomicLong(Long.MAX_VALUE - TimeUnit.SECONDS.toNanos(1));
    AtomicInteger calls = new AtomicInteger();
    ProxyConfig cfg = COMMON_CONFIG.toBuilder().shutdownAcceptGraceMillis(2000).build();
    Proxy proxy =
        new Proxy(
            cfg,
            new RequestLogCapture(),
            now::get,
            remaining -> {
              int call = calls.incrementAndGet();
              assertThat(remaining)
                  .isEqualTo(TimeUnit.MILLISECONDS.toNanos(call < 3 ? 2000 : 1500));
              if (call == 1) {
                Thread.currentThread().interrupt();
              } else if (call == 2) {
                now.addAndGet(TimeUnit.MILLISECONDS.toNanos(500));
              } else {
                now.addAndGet(remaining);
              }
            });
    try {
      proxy.shutdown();
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      assertThat(calls.get()).isEqualTo(3);
    } finally {
      Thread.interrupted();
      proxy.shutdown();
    }
  }

  @Test
  void readinessAndReloadChangeBeforeTheGraceAndConcurrentCallerWaits() throws Exception {
    AtomicLong now = new AtomicLong();
    CountDownLatch waiting = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ProxyConfig cfg = COMMON_CONFIG.toBuilder().shutdownAcceptGraceMillis(1000).build();
    Proxy proxy =
        new Proxy(
            cfg,
            new RequestLogCapture(),
            now::get,
            nanos -> {
              waiting.countDown();
              try {
                assertThat(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
              } catch (InterruptedException e) {
                throw new AssertionError(e);
              }
              now.addAndGet(nanos);
            });
    CompletableFuture<Void> first = CompletableFuture.runAsync(proxy::shutdown);
    try {
      assertThat(waiting.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
      assertThat(proxy.isShuttingDown()).isTrue();
      assertThat(proxy.reload(cfg).applied()).isFalse();
      FutureTask<Void> second = new FutureTask<>(proxy::shutdown, null);
      Thread caller = Thread.ofPlatform().start(second);
      TestingParameters.await(
          "second shutdown waits for the first",
          () -> caller.getState() == Thread.State.WAITING || second.isDone());
      assertThat(second.isDone()).isFalse();
      release.countDown();
      first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      proxy.shutdown();
    }
  }
}
