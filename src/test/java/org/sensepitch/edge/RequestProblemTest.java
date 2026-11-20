package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.embedded.EmbeddedChannel;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/// Request problems of a [ProxyLogger]: the first occurrence of a problem is logged at `WARNING`
/// and names the metric, the further ones at `DEBUG`, see [ProxyLogger] "Levels".
///
/// @author Jens Wilke
public class RequestProblemTest {

  private static final ProxyLogger LOG = ProxyLogger.get(RequestProblemTest.class);

  static final ProxyLogger.RequestProblem PROBLEM =
      new ProxyLogger.RequestProblem("RequestProblemTest.problem", "test_problems");

  static final ProxyLogger.RequestProblem OTHER_PROBLEM =
      new ProxyLogger.RequestProblem("RequestProblemTest.otherProblem", "test_other_problems");

  @RegisterExtension LogCapture logs = new LogCapture();

  @Test
  public void firstOccurrenceIsAWarningTheNextOneDebug() {
    logs.warnProblems();
    EmbeddedChannel channel = new EmbeddedChannel();
    IOException cause = new IOException("broken");
    LOG.requestProblem(PROBLEM, channel, "went wrong", cause);
    LOG.requestProblem(PROBLEM, channel, "went wrong again");
    List<LogCapture.Entry> entries = logs.entries();
    assertThat(entries).hasSize(2);
    LogCapture.Entry first = entries.get(0);
    assertThat(first.source()).isEqualTo(RequestProblemTest.class.getName());
    assertThat(first.info().level()).isEqualTo(Level.WARNING);
    assertThat(first.info().channel()).isSameAs(channel);
    assertThat(first.message())
        .endsWith(
            "went wrong (first occurrence of RequestProblemTest.problem, further ones are logged"
                + " at DEBUG and counted in test_problems)");
    assertThat(first.error()).isSameAs(cause);
    LogCapture.Entry second = entries.get(1);
    assertThat(second.info().level()).isEqualTo(Level.DEBUG);
    assertThat(second.info().channel()).isSameAs(channel);
    assertThat(second.message()).endsWith("went wrong again").doesNotContain("first occurrence");
    assertThat(second.error()).isNull();
    channel.finishAndReleaseAll();
  }

  @Test
  public void differentProblemsWarnOnTheirOwn() {
    logs.warnProblems();
    LOG.requestProblem(PROBLEM, null, "one");
    LOG.requestProblem(OTHER_PROBLEM, null, "other");
    LOG.requestProblem(PROBLEM, null, "one again");
    LOG.requestProblem(OTHER_PROBLEM, null, "other again");
    assertThat(levels(logs.entries()))
        .containsExactly(Level.WARNING, Level.WARNING, Level.DEBUG, Level.DEBUG);
    assertThat(logs.messages())
        .containsExactly(
            "one (first occurrence of RequestProblemTest.problem, further ones are logged at DEBUG"
                + " and counted in test_problems)",
            "other (first occurrence of RequestProblemTest.otherProblem, further ones are logged"
                + " at DEBUG and counted in test_other_problems)",
            "one again",
            "other again");
  }

  /// Tests see `DEBUG` only, unless they switch the warnings on, so the level does not depend on
  /// the tests that ran before, see [LogCapture#warnProblems()]
  @Test
  public void inTestsEveryOccurrenceIsDebugByDefault() {
    LOG.requestProblem(PROBLEM, null, "one");
    LOG.requestProblem(PROBLEM, null, "two");
    assertThat(levels(logs.entries())).containsExactly(Level.DEBUG, Level.DEBUG);
    logs.warnProblems();
    LOG.requestProblem(PROBLEM, null, "three");
    logs.clear();
    LOG.requestProblem(PROBLEM, null, "after clear");
    assertThat(levels(logs.entries())).containsExactly(Level.DEBUG);
  }

  /// The production target: the first occurrence in the JVM warns, for all loggers together
  @Test
  public void productionTargetWarnsOncePerJvm() {
    Recording target = new Recording();
    Recording otherTarget = new Recording();
    ProxyLogger log = new ProxyLogger.WithTracing("first", target);
    ProxyLogger otherLog = new ProxyLogger.ErrorOnly("second", otherTarget);
    ProxyLogger.RequestProblem problem = uniqueProblem();
    log.requestProblem(problem, null, "one");
    log.requestProblem(problem, null, "two");
    otherLog.requestProblem(problem, null, "three");
    assertThat(target.levels()).containsExactly(Level.WARNING, Level.DEBUG);
    assertThat(target.records.getFirst().message())
        .isEqualTo("one" + problem.firstOccurrenceHint());
    assertThat(otherTarget.levels()).containsExactly(Level.DEBUG);
    log.requestProblem(uniqueProblem(), null, "another problem");
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
      ProxyLogger.RequestProblem problem = uniqueProblem();
      CountDownLatch start = new CountDownLatch(1);
      Runnable report =
          () -> {
            try {
              start.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              return;
            }
            log.requestProblem(problem, null, "concurrent");
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
    }
  }

  private static ProxyLogger.RequestProblem uniqueProblem() {
    return new ProxyLogger.RequestProblem(
        "RequestProblemTest." + UUID.randomUUID(), "test_unique_problems");
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
