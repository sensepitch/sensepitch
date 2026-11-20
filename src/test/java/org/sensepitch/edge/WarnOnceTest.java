package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.embedded.EmbeddedChannel;
import io.prometheus.metrics.core.metrics.Counter;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/// Request level events of a [ProxyLogger]: counted by the logger, the first occurrence in each
/// series is logged at `WARNING` and names the series, the further ones at `DEBUG`, see
/// [ProxyLogger] "Levels".
///
/// @author Jens Wilke
public class WarnOnceTest {

  private static final ProxyLogger LOG = ProxyLogger.get(WarnOnceTest.class);

  /// Counts the test problems by place and type, a new one per test, so counts and first
  /// occurrences start fresh. Declared with `where` before `type`, the series names them sorted.
  final Counter problems =
      Counter.builder().name("sensepitch_test_problems").labelNames("where", "type").build();

  @RegisterExtension LogCapture logs = new LogCapture();

  @Test
  public void firstOccurrenceIsAWarningNamingTheSeriesTheNextOneDebug() {
    logs.warnProblems();
    EmbeddedChannel channel = new EmbeddedChannel();
    IOException cause = new IOException("broken");
    LOG.incrementAndWarnOnce(problems, new String[] {"here", "one"}, channel, "went wrong", cause);
    LOG.incrementAndWarnOnce(problems, new String[] {"here", "one"}, channel, "went wrong again");
    assertThat(problems.labelValues("here", "one").get()).isEqualTo(2);
    List<LogCapture.Entry> entries = logs.entries();
    assertThat(entries).hasSize(2);
    LogCapture.Entry first = entries.get(0);
    assertThat(first.source()).isEqualTo(WarnOnceTest.class.getName());
    assertThat(first.info().level()).isEqualTo(Level.WARNING);
    assertThat(first.info().channel()).isSameAs(channel);
    assertThat(first.info().message())
        .isEqualTo(
            "went wrong (first occurrence counted in"
                + " sensepitch_test_problems_total{type=\"one\",where=\"here\"}, further ones are"
                + " logged at DEBUG)")
        .doesNotContain("WarnOnceTest");
    assertThat(first.error()).isSameAs(cause);
    LogCapture.Entry second = entries.get(1);
    assertThat(second.info().level()).isEqualTo(Level.DEBUG);
    assertThat(second.info().channel()).isSameAs(channel);
    assertThat(second.info().message()).isEqualTo("went wrong again");
    assertThat(second.error()).isNull();
    channel.finishAndReleaseAll();
  }

  /// Each data point of a counter is a series of its own, also when only the place differs
  @Test
  public void differentSeriesWarnOnTheirOwn() {
    logs.warnProblems();
    LOG.incrementAndWarnOnce(problems, new String[] {"here", "one"}, null, "one");
    LOG.incrementAndWarnOnce(problems, new String[] {"here", "other"}, null, "other");
    LOG.incrementAndWarnOnce(problems, new String[] {"there", "one"}, null, "one elsewhere");
    LOG.incrementAndWarnOnce(problems, new String[] {"here", "one"}, null, "one again");
    assertThat(levels(logs.entries()))
        .containsExactly(Level.WARNING, Level.WARNING, Level.WARNING, Level.DEBUG);
    assertThat(logs.entries().stream().map(e -> e.info().message()).toList())
        .containsExactly(
            "one" + ProxyLogger.firstOccurrenceHint(problems, new String[] {"here", "one"}),
            "other" + ProxyLogger.firstOccurrenceHint(problems, new String[] {"here", "other"}),
            "one elsewhere"
                + ProxyLogger.firstOccurrenceHint(problems, new String[] {"there", "one"}),
            "one again");
  }

  /// An event counted elsewhere is logged the same way, but not counted again
  @Test
  public void warnOnceDoesNotCount() {
    logs.warnProblems();
    problems.labelValues("here", "one").inc();
    LOG.warnOnce(problems, new String[] {"here", "one"}, null, "counted elsewhere");
    LOG.warnOnce(problems, new String[] {"here", "one"}, null, "counted elsewhere again");
    assertThat(problems.labelValues("here", "one").get()).isEqualTo(1);
    assertThat(levels(logs.entries())).containsExactly(Level.WARNING, Level.DEBUG);
    assertThat(logs.entries().getFirst().info().message())
        .isEqualTo(
            "counted elsewhere"
                + ProxyLogger.firstOccurrenceHint(problems, new String[] {"here", "one"}));
  }

  /// A counter without labels is its own series and named without labels
  @Test
  public void counterWithoutLabelsIsNamedWithoutBraces() {
    logs.warnProblems();
    Counter single = Counter.builder().name("sensepitch_test_single_problems").build();
    LOG.incrementAndWarnOnce(single, ProxyLogger.NO_LABELS, null, "went wrong");
    LOG.incrementAndWarnOnce(single, ProxyLogger.NO_LABELS, null, "went wrong again");
    assertThat(single.get()).isEqualTo(2);
    assertThat(levels(logs.entries())).containsExactly(Level.WARNING, Level.DEBUG);
    assertThat(logs.entries().getFirst().info().message())
        .isEqualTo(
            "went wrong (first occurrence counted in sensepitch_test_single_problems_total,"
                + " further ones are logged at DEBUG)");
  }

  /// Tests see `DEBUG` only, unless they switch the warnings on, so the level does not depend on
  /// the tests that ran before, see [LogCapture#warnProblems()]
  @Test
  public void inTestsEveryOccurrenceIsDebugByDefault() {
    LOG.incrementAndWarnOnce(problems, new String[] {"here", "one"}, null, "one");
    LOG.incrementAndWarnOnce(problems, new String[] {"here", "one"}, null, "two");
    assertThat(levels(logs.entries())).containsExactly(Level.DEBUG, Level.DEBUG);
    logs.warnProblems();
    LOG.incrementAndWarnOnce(problems, new String[] {"here", "one"}, null, "three");
    logs.clear();
    LOG.incrementAndWarnOnce(problems, new String[] {"here", "one"}, null, "after clear");
    assertThat(levels(logs.entries())).containsExactly(Level.DEBUG);
  }

  /// The production target: the first occurrence in the JVM warns, for all loggers together
  @Test
  public void productionTargetWarnsOncePerJvm() {
    Recording target = new Recording();
    Recording otherTarget = new Recording();
    ProxyLogger log = new ProxyLogger.WithTracing("first", target);
    ProxyLogger otherLog = new ProxyLogger.ErrorOnly("second", otherTarget);
    log.incrementAndWarnOnce(problems, new String[] {"here", "one"}, null, "one");
    log.incrementAndWarnOnce(problems, new String[] {"here", "one"}, null, "two");
    otherLog.incrementAndWarnOnce(problems, new String[] {"here", "one"}, null, "three");
    assertThat(target.levels()).containsExactly(Level.WARNING, Level.DEBUG);
    assertThat(target.records.getFirst().message())
        .isEqualTo("one" + ProxyLogger.firstOccurrenceHint(problems, new String[] {"here", "one"}));
    assertThat(otherTarget.levels()).containsExactly(Level.DEBUG);
    log.incrementAndWarnOnce(problems, new String[] {"here", "other"}, null, "another problem");
    assertThat(target.levels()).containsExactly(Level.WARNING, Level.DEBUG, Level.WARNING);
  }

  /// Of threads reporting the same new problem at the same time, exactly one warns
  @Test
  public void concurrentFirstOccurrencesWarnOnce() throws InterruptedException {
    Recording target = new Recording();
    ProxyLogger log = new ProxyLogger.WithTracing("concurrent", target);
    int threadCount = 16;
    for (int round = 0; round < 20; round++) {
      target.records.clear();
      Counter counter = Counter.builder().name("sensepitch_test_concurrent").build();
      CountDownLatch start = new CountDownLatch(1);
      Runnable report =
          () -> {
            try {
              start.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              return;
            }
            log.incrementAndWarnOnce(counter, ProxyLogger.NO_LABELS, null, "concurrent");
          };
      List<Thread> threads = new ArrayList<>();
      for (int i = 0; i < threadCount; i++) {
        threads.add(Thread.ofPlatform().start(report));
      }
      start.countDown();
      for (Thread thread : threads) {
        thread.join();
      }
      List<Level> levels = target.levels();
      assertThat(levels).hasSize(threadCount);
      assertThat(levels).filteredOn(level -> level == Level.WARNING).hasSize(1);
      assertThat(levels).filteredOn(level -> level == Level.DEBUG).hasSize(threadCount - 1);
      assertThat(counter.get()).isEqualTo(threadCount);
    }
  }

  private static List<Level> levels(List<LogCapture.Entry> entries) {
    return entries.stream().map(e -> e.info().level()).toList();
  }

  /// Keeps the records, with the JVM wide first occurrence check of the production targets
  static class Recording implements LogTarget {

    final List<LogInfo> records = Collections.synchronizedList(new ArrayList<>());

    @Override
    public boolean isEnabled(Level level) {
      return true;
    }

    @Override
    public void log(LogInfo info) {
      records.add(info);
    }

    List<Level> levels() {
      synchronized (records) {
        return records.stream().map(LogInfo::level).toList();
      }
    }
  }
}
