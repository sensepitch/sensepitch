# Test speed

Design notes for how the build runs the tests and why it is set up this way: what was measured,
what was changed, and what was tried and dropped. The rules for writing tests are in
[`AGENTS.md`](../../AGENTS.md); this document records the reasoning behind them. Open work is in
[`TODO.md`](../../TODO.md), section "Build, tests".

All numbers were measured on 2026-11-14 on one developer machine (16 cores, Java 25) with
`./mvnw clean ...`. Runs vary by about ±1 s, so differences below 2 s are noise unless they were
confirmed by alternating runs in one session. Where that was done, it is said.

## Purpose and scope

`./mvnw test` is run after every change, `./mvnw verify` before every commit. Both should be as
fast as they can be without checking less.

Two rules bound everything below:

- **`./mvnw test` is the full smoke test.** The unit tests alone cover everything that is unit
  testable. `./mvnw verify`, with the integration tests and the JaCoCo floor, is the final check,
  not the only place where code of ours runs. A test is not moved to `*IT` to make the unit tests
  faster when that takes coverage away from them.
- **Nothing is made faster by making it check less.** Every change here was verified by breaking
  an assertion on purpose and seeing the build fail. Two attempts that looked fine failed exactly
  this check, see [Serenity](#serenity-reporting-is-off-step-objects-are-plain).

## Overview

| | Start of the work | Now |
|---|---|---|
| `./mvnw clean verify` | about 50 s | about 33 s |
| unit test phase (surefire, with JaCoCo) | about 21 s | about 9.5 s |
| integration test phase (failsafe) | about 16.5 s | about 11 s |

Where the unit test time went at the start, and what became of it:

| Cost | Size | Now |
|---|---|---|
| Serenity recording and reporting | about 5 to 7 s | off by default |
| Test classes one after the other | about 11 s of tests | concurrent, except 8 isolated classes |
| Tests waiting on real timers | about 6 s | unchanged, see [Planned improvements](#planned-improvements) |
| JaCoCo agent | about 2 s | kept, skippable with `-Dfast` |
| JVM start and warm-up | 1.5 to 2.5 s | fixed |

The switches, all in the [`pom.xml`](../../pom.xml):

| Switch | Effect |
|---|---|
| `-Dtest.parallel=false` | test classes one after the other, as before |
| `-Dserenity.reports` | Serenity records the tests in `target/serenity-reports` |
| `-Dfast` | no JaCoCo, no delombok and Javadoc, no format check; all tests still run |
| `-Djacoco.skip`, `-Dmaven.javadoc.skip -Dlombok.delombok.skip`, `-Dspotless.check.skip` | the parts of `-Dfast` one by one |

## Design decisions

### Run the OCI smoke test only with the image profile

`OciImageIT` uses Docker to run an existing image when Maven runs with `-Poci-image`.
Building the image is a separate step, so the standard Maven gate needs neither Docker nor
container image downloads. An explicitly enabled smoke test fails when the local image cannot
start.

### Test classes run concurrently in one JVM

Surefire runs the unit tests with JUnit's parallel execution: classes concurrently, the methods
of a class in order, as many threads as CPU cores (`junit.jupiter.execution.parallel.*`, set on
surefire only). Measured with alternating runs: the unit test phase went from about 20.5 s to
about 15 s, before the Serenity change.

- Methods stay sequential, because test classes keep servers, ports and fields per class, and
  [`LogCapture`](../../src/test/java/org/sensepitch/edge/LogCapture.java) resets per thread before
  each test.
- The number of threads follows the cores, so one CPU means a sequential run and the test runner
  never oversubscribes the machine.
- It is set for surefire only. Integration tests and runs from an IDE stay sequential.

### Classes that depend on JVM wide state run alone

A test class that reads state shared by the whole JVM carries `@Isolated` with a `// alone:`
comment giving the reason. JUnit then runs it while no other class runs. Today these are:

| Class | Reason |
|---|---|
| `OtlpLogDestinationTest`, `ConfigFileWatcherTest`, `AsyncRequestLogWriterTest`, `ProxyShutdownIT`, `ExternalTcpIT` | capture the log of all threads |
| `LoggingTest`, `LogTargetTest` | capture what arrives in Log4j2, change a Log4j2 level |
| `MetricsAndMemoryTest` | asserts on memory counters of the JVM |

The first two groups are enforced: `LogCapture.ofAllThreads()` and
[`Log4jCapture`](../../src/test/java/org/sensepitch/edge/Log4jCapture.java) throw when the test
class is not `@Isolated`. A new test cannot bring the cross talk back by accident.

Isolation is the expensive answer, an isolated class contributes its full duration to the wall
clock. `OtlpLogDestinationTest` alone is 3.6 s. The better answer is to remove the shared state,
see [Planned improvements](#planned-improvements).

### The log is captured per thread, not from Log4j2

Parallel execution was only possible after the log capture stopped being global. Before, a
capture attached an appender to the Log4j2 root logger, so every capturing test saw the log lines
of all tests running at the same time, and 25 test classes captured, including all slow ones.

Now the loggers of the proxy do not write to Log4j2 in tests.
[`LogTarget.Factory`](../../src/main/java/org/sensepitch/edge/LogTarget.java) is replaced before
the first test class loads, by a JUnit `LauncherSessionListener`, with the
[`LogSink`](../../src/test/java/org/sensepitch/edge/LogSink.java). It stores the records per
logging thread; a test reads what its own thread logged.

- A logger asks the factory once, when it is created, so production code pays nothing when
  logging. A record in a test is one thread local lookup and a list add, nothing is formatted
  until a test reads it.
- The same design is used by slf4j-test, which keeps events in thread locals with getters for the
  current thread and for all threads. The `ListAppender` of Log4j2 is shared by the JVM and its
  maintainers state that it does not support concurrent tests.
- Code that logs from its own threads, e.g. a sender or an event loop, cannot be read per thread.
  For it there is `LogCapture.ofAllThreads()`, at the price of `@Isolated`.
- Two details the first versions got wrong, both found by flaky tests: a thread that ended keeps
  its buffer until the next reset, otherwise what short lived threads logged disappears; and a
  pool thread that erases its thread locals between tasks gets its buffer back, not a new one.
- Trace is enabled in tests so the trace code is covered, but trace records are dropped. Trace
  output is a debugging aid and no acceptance criterion, no test asserts on it.

### Serenity: reporting is off, step objects are plain

Serenity is used for the step chains of the `*BDDTest` classes. Its reporting is not used by the
build and was the largest single cost: `SerenityTestExecutionListener` records every test once
the first Serenity class ran, writes 1163 files to `target/serenity-reports` and generates
reports at the end of the run. Measured with alternating runs: unit test phase 13.3 s and 14.6 s
with the listener, 9.6 s and 9.5 s without.

The listener is deactivated by default (`test.deactivatedListeners`). That is only safe because
of how the step objects are created:

- A step object injected with `@Steps` is a Serenity proxy. A failing assertion in a step is
  recorded and the step returns normally. `SerenityJUnit5Extension.afterEach` throws the failure
  later, if the listener started a test outcome whose name matches the test method.
- Without the listener there is no such outcome: the failing step passes. This is why the first
  attempt to deactivate the listener was reverted.
- With the listener, the name does not match for a `@ParameterizedTest`: the failing step passes
  as well. This was an existing hole, in the two parameterized tests of
  `AdmissionChecksumBDDTest`, found only because broken assertions were tried in both modes.
- A step object created with `new` is a plain object. `@Step` is a marker for the reader and an
  assertion throws at once, whatever Serenity does.

So step objects are created with `new` everywhere, and
[`SerenityUsageTest`](../../src/test/java/org/sensepitch/edge/SerenityUsageTest.java) fails when
a test class injects one.

### Waste in single tests is removed, margins are not cut

Two tests were slow for no reason and were fixed:

- `ProxyShutdownIT`, 7.7 s to 3.2 s: the test upstream slept for 5 s, and stopping it at the end
  of the test waited for the rest of the sleep. The sleep now ends with the test.
- `AdmissionChecksumBDDTest`, 1.7 s to 0.3 s: one test called steps 768 times in a loop, at the
  time on a Serenity proxy that analyses the stack on every call. It is one step now.

What was not done: shortening sleeps, backoffs and flush intervals of tests that wait on real
timers. Each of them trades a few hundred milliseconds for a tighter margin under load, and
parallel execution adds load. The answer for these tests is a clock they control, see
[Planned improvements](#planned-improvements).

### Asynchronous waits share one upper bound

[`TestingParameters`](../../src/test/java/org/sensepitch/edge/TestingParameters.java) bounds
completion waits at 10 seconds and reports which condition failed. Test HTTP servers use the same
grace period at teardown: a server queues a received post before writing its answer, so closing it
immediately can turn a successful test into a late sender failure. The grace costs no wait when
the server is idle and tolerates scheduling pauses while an exchange finishes. Intentional
outages, negative assertions, deliberate pauses and outer test guards retain their own timing.
The timeout is a failure bound, not a substitute for the planned fake clock. It is also the
assumed worst case delay of any single action, which the rules for concurrent tests in
[`AGENTS.md`](../../AGENTS.md) build on: no maximum time is asserted, timers that must not fire
are set to `OUT_OF_REACH_MILLIS` (10 × `TIMEOUT`). The tests that still break these rules are
listed in [`TODO.md`](../../TODO.md).

One baseline/final run on 2026-11-17 in the scheduled x86_64 sandbox (2 available CPUs,
Oracle Java 25.0.5, Node 26.12.1), measured with `/usr/bin/time -v ./mvnw -o clean verify`,
took 62.72 / 75.85 seconds. `ShadedJarIT` rose from 10.34 to 29.83 seconds; its two
`sigtermKeepsFinalShutdownDiagnostic` cases each took about 12.85 seconds after the change.
The wider collector teardown grace is the inferred cause; an HTTP server can consume its grace
while a connection is still ending. Both gates passed with identical coverage. These single
runs establish the observed cost, not a stable performance estimate. A grace that is consumed in
full every run is a defect; finding its cause is in [`TODO.md`](../../TODO.md).

### Move only redundant server scenarios

The selective move on 2026-11-17 compares the unit suite alone, rather than the combined gate.
[OtlpLogDestinationIT](../../src/test/java/org/sensepitch/edge/OtlpLogDestinationIT.java) and
[ReloadableRequestLoggerIT](../../src/test/java/org/sensepitch/edge/ReloadableRequestLoggerIT.java)
reuse the unit classes as fixtures, preserving assertions, completion bounds and graceful server
teardown. New scenarios belong in the unit classes. No production code or build settings changed.

Measurements used Oracle Java 25.0.5, Node 26.12.1, an x86_64 AMD EPYC-Milan sandbox with two
available CPUs, and `/usr/bin/time -v`. The commands were `./mvnw -o clean test jacoco:report`
and, for each individual exclusion:

```sh
./mvnw -o clean test jacoco:report \
  '-Dtest=Test*,*Test,*Tests,*TestCase,!Class#method'
```

The positive patterns match Surefire's default unit class names; a bare negative pattern also
selects integration classes and invalidates this comparison. Each trial ran 663 unit cases,
without failures, errors, skips or IT cases. CSV comparisons checked line and branch counters per
production class; XML comparisons located changed source lines. The two unchanged baselines ran
664 cases with identical coverage: 5,055/5,326 lines and 1,990/2,220 branches. The individual
results:

| Class | Method | Result |
|---|---|---|
| OTLP | `concurrentRequestsUpToTheLimit` | move |
| OTLP | `breakerSendsOneProbePerBackoff` | move; timestamp branch gain |
| OTLP | `partialBatchesOfThreadsArriveInOneRequest` | retain for cumulative coverage |
| OTLP | `partialBatchesBeyondTheBatchSizeAreSplit` | retain for cumulative coverage |
| OTLP | `agedBatchIsDiscarded` | retain; exclusion missed two sender branches |
| OTLP | `outageLosesNothing` | move |
| OTLP | `concurrentThreads` | move |
| OTLP | `retryAfterIsHonoured` | move; async writer gained one branch |
| Reload | `finishCoversDrainingDestinations` | retain; lost 16 lines, nine branches |
| Reload | `finishedDestinationEndsWhenItsRecordsAgedOut` | move |
| Reload | `disableAndEnableByReload` | move |
| Reload | `removedDestinationDoesNotBlockTheReload` | move; timestamp branch gain |
| Reload | `replacedDestinationSendsItsBacklog` | move; timestamp branch gain |

Removing all eleven individually redundant tests together lost three sender lines and four
branches. Keeping only the split test recovered the lines but still missed one packing branch.
Both packing tests therefore stay: one exercises combining partial batches, the other splitting
beyond the byte limit. The nine exclusions together, and the actual move afterwards (655 unit
cases), produced identical per-class counters and source-level coverage to both baselines.

Coverage variation was inspected rather than subtracted from a loss: the gained oldest-record
comparison is `HttpBatchSender.Post.of` choosing the earliest batch timestamp (lines 110–111),
which depends on batch collection order. The async writer gain is its queue check at line 201.
Both unchanged baseline runs missed these same paths; three isolated exclusions gained the former
and two trials gained the latter. These observations are consistent with timing variation; none of these
gains is counted as an improvement or used to excuse a cumulative loss.

The baseline unit builds took 33.79 and 23.60 seconds; the actual move took 22.75 seconds. OTLP
class times were 13.625 / 3.752 seconds before and 2.461 after; reload class times were 2.632 /
2.247 before and 1.458 after. In the unchanged baselines the concurrency-limit method alone varied
from 10.189 to 0.185 seconds. An HTTP server consuming its teardown grace is a plausible cause of
that ten-second difference, not established by these runs. The move reduces work in the unit
phase, but these observations do not establish a stable wall-clock speedup; integration tests
now pay for the nine moved scenarios. The two packing timers and draining-finish retry remain
unit costs for the separate fake-clock task.

The complete `./mvnw -o clean verify` gates passed before and after (86.18 / 77.82 seconds),
including JavaScript, Javadoc, formatting and all coverage checks. Surefire/Failsafe XML showed
664/40 cases before, 655/49 after, with zero failures, errors or skips. Combined coverage remained
5,141/5,326 lines and 2,019/2,220 branches; the posting class rules still pass at 100%. The affected
IT classes cost 0.405 seconds before and 3.349 seconds after (1.619 OTLP, 1.730 reload); all IT
class times summed to 38.283 / 41.152 seconds. These single runs describe the added integration
cost, not a repeatable whole-build saving. Online `./mvnw clean verify` failed before tests both
times because Maven could not write the shared read-only `org/jspecify/jspecify` resolver metadata;
the complete cached offline gates used no skips or exclusions.

### Flaky tests are fixed at the cause

Faster and concurrent runs surfaced three faults that had been there before. None was fixed by a
retry or a wider margin:

- `ProxyShutdownIT` and `ExternalTcpIT` probed a free port, released it, and then started helper
  servers on any port before the proxy bound the probed one. A helper could be given that port:
  `BindException: Address already in use`, seen as a 10 s read timeout. The probe now comes
  after the helpers.
- `CompleteTest` checked at the end of a test that request chunks were released, by their
  reference count. The chunks came from the pooled allocator, and a released pooled buffer can be
  handed out again, also to a test in another thread. 2 failures in 8 parallel runs, none in 25
  after the tracked chunks became unpooled. The cause is inferred from that, not proven.
- The log capture details described above.

### A quick profile, the full gate stays the default

`./mvnw verify -Dfast` skips what does not tell whether the code works: coverage, the Javadoc
build with delombok, and the format check. All tests run and the fat JAR is built. Measured
before the Serenity change: 30.5 s against 38.3 s for the full gate. It is for the inner loop;
before a commit the full `./mvnw verify` runs.

### Excessive tests run nightly

A test that measures the JVM rather than our code is tagged `@Tag("excessive")` and left out of
`./mvnw test` and `verify` (`test.excludedGroups` in `pom.xml`). It runs nightly
([nightly.yml](../../.github/workflows/nightly.yml)) and with `-Dexcessive`, e.g. when working
on performance. Such a test is load sensitive, and a failure among the parallel tests says
little about a change, so it is not part of the gate. It adds no coverage; a scenario only it
covered is ported to a unit test first.

The first is `OtlpAllocationIT`, which bounds the bytes allocated per logged record. It failed
once in a parallel `./mvnw verify` with 357 bytes against the bound of 256, measured normally
120 to 150 (2026-11-16), and passed alone and in the next run. Inferred, not verified: the
warm-up was not enough for the C2 compilation under that load. Moving it out of the unit tests
lost the only test of a URI with a query on the OTLP path; that case is now in
`OtlpLogDestinationTest`.

## Alternatives considered

### Surefire forks

Several test JVMs side by side. Measured with 1, 2, 3, 4 and 8 forks: two forks saved about 2 s,
three nothing, four were slower with JaCoCo, eight much slower. Every JVM repeats about 2 s of
warm-up (class loading, OpenSSL, the crawler lists), and the sum of the test class times went
from 10.8 s to 26.7 s with four forks. Forks also do not help on a machine with one CPU, and they
make the port probes more likely to collide. Not used.

### Deactivating the Serenity listener without changing the tests

Fast, and every test passed, including a test with a deliberately broken step. Reverted, see
above. It is the reason every later change was checked with broken assertions.

### Running the Serenity classes last

A `ClassOrderer`, so the listener would not start recording before the plain tests are done.
Saved under a second: the reports are generated for every test of the plan at the end anyway.
Dropped.

### A Serenity setting that switches the reports off

None was found. An empty `output.formats` is rejected with "No valid output format has been
defined".

### Moving the slow tests to the integration tests

The 27 tests of `OtlpLogDestinationTest` that run against a log server were moved to an `*IT`.
That saved about 4 s in `./mvnw test`, and the combined coverage checked by the gate was
identical. But the unit tests alone lost coverage, lines 95.1 % to 94.5 %, branches 89.8 % to
89.0 %, mostly in `HttpBatchSender`. It was undone, and is the origin of the first rule in
[Purpose and scope](#purpose-and-scope). The later
[selective move](#move-only-redundant-server-scenarios) keeps that rule.

### A mock for the HTTP transport only

Would not make these tests fast. They are slow because they wait on real timers, and isolated
because completions and log lines arrive on other threads. A transport mock changes neither.

### The Maven daemon

`mvnd` 1.0.2 works with this build without changes, and per-build values such as the version in
the manifest are fresh in every build. It saves 1.5 to 3 s, the start of Maven itself; the test
JVMs are forked as before. Useful for single tests (3.1 s against 4.6 s), not a change to the
build.

## Limitations

- The unit test phase is bounded by the isolated classes, which run one after the other, and by
  the two classes waiting on real timers, `OtlpLogDestinationTest` and
  `ReloadableRequestLoggerTest`. The selective move left them at 2.461 s and 1.458 s in one run
  on 2026-11-17; this is an observed cost, not a stable bound.
- Parallel execution is checked by repetition, not proven: 10 and 25 green runs in a row. A race
  between tests that needs rarer timing can still be there.
- `LogCapture` only sees the loggers of the proxy. Netty, JUL and other libraries are visible
  with `Log4jCapture` only, which is JVM wide.
- With `-Dserenity.reports` the reports list the tests, but no steps, for any class.
- The numbers are from one machine. On fewer cores the gain of parallel execution shrinks, on one
  core it is zero; the Serenity and single test gains remain.

## Planned improvements

- **A fake clock and a scheduler the test moves forward**, as in cache2k, for the tests that wait
  on real timers. It needs three seams in `HttpBatchSender`: the timer, the clock and the
  transport. A test then completes requests and advances time from its own thread, which makes
  it fast and deterministic, lets it use the per thread log capture, and removes its
  `@Isolated`. This is the largest gain left. A few real tests stay as `*IT` in addition.
- **The Serenity report mode**: make the reports useful, or drop the mode together with the
  byte-buddy agent it needs.

Both are entries in [`TODO.md`](../../TODO.md).
