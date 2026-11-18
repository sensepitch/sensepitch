# Repository Guidelines

## Project Structure & Module Organization
- `src/main/java/org/sensepitch/edge/` contains the core proxy implementation and supporting utilities.
- `src/main/resources/` holds static assets and data (crawler lists, challenge assets, HTML/CSS/JS).
- `src/test/java/org/sensepitch/edge/` contains JUnit 5 and Serenity BDD tests; `src/test/resources/` holds test configs and TLS fixtures.
- `performance-test/` includes benchmarking setup and notes.
- `doc/design/` holds the design documents, one per topic, see "Design Documents" below.
  `doc/kubernetes-support/` is a numbered plan for one feature.
- `pom.xml` is the Maven build definition; `mvnw`/`mvnw.cmd` are the recommended entry points.

## Build, Test, and Development Commands
- `./mvnw clean package` builds the project and produces a shaded JAR at `target/sensepitch-edge-*-with-dependencies.jar`.
- `./mvnw test` runs the JUnit/Serenity test suite via Surefire.
- `./mvnw spotless:check` verifies formatting; `./mvnw spotless:apply` auto-formats Java sources.
- `./mvnw verify` requires Node.js 22 or newer on `PATH` for the challenge JavaScript integration test.
- `./mvnw verify` runs the whole gate: tests, `spotless:check`, the Javadoc build, and the JaCoCo
  coverage floor (`jacoco-check` in `pom.xml`, unit and integration tests combined). Raise the floor
  when coverage improves. The generic request log posting (`HttpBatchSender`, `CircuitBreaker`,
  `CircuitBreakerMetrics`, `Backoff`, `ThreadBatcher`, `BatchingRequestLogger`, `OtlpBatchPayload`,
  `GzipCompressor`, ...) is held at 100% line and
  branch coverage by a class rule. A gap is a design question: remove the stray error handling,
  validate earlier, or test the case; do not lower the rule.
- `./mvnw verify -Dfast` (profile `fast` in `pom.xml`) is the quick loop: it compiles, runs all unit
  and integration tests and builds the fat JAR, but skips coverage (agent, report, floor), the
  Javadoc build with delombok, and the format check. Each part can be skipped on its own:
  `-Djacoco.skip`, `-Dmaven.javadoc.skip -Dlombok.delombok.skip`, `-Dspotless.check.skip`.
  Before a commit run the full `./mvnw verify`.
- `./mvnw verify -Dexcessive` (profile `excessive`) also runs the tests tagged
  `@Tag("excessive")`: measurements of the JVM rather than of our code (allocation, timing), load
  sensitive, run nightly (`.github/workflows/nightly.yml`). Run them when working on performance.
  Such a test must add no coverage of its own; port a missing scenario to a unit test first.
- How the tests are run and why (parallel execution, isolation, log capture, Serenity, what was
  tried and dropped) is in `doc/design/test-speed.md`. Update it when the test setup changes.
- `./mvnw test` runs the unit test classes concurrently in one JVM (JUnit parallel execution, set
  on surefire in `pom.xml`): the methods of a class in order, as many threads as CPU cores.
  Sequential: `-Dtest.parallel=false`. Integration tests (failsafe) and IDE runs are sequential.
- A test class that depends on JVM wide state gets `@Isolated` with a `// alone:` reason, it then
  runs while no other class does. `LogCapture.ofAllThreads()` and `Log4jCapture` refuse to work
  without it. Keep new tests free of shared state instead where possible: `new LogCapture()` is
  per thread, ports come from the OS, files go to `@TempDir`.
- Do not add surefire forks: every test JVM repeats about 2 s of warm-up (class loading, OpenSSL,
  crawler lists), which eats the gain even with many cores, and does not help on one CPU.
- Serenity is used for the step chains only, its reporting is off: the `pom.xml` deactivates
  `SerenityTestExecutionListener` (`test.deactivatedListeners`), which saves about a third of the
  unit test time. `-Dserenity.reports` switches it on and writes `target/serenity-reports`.
- Create step objects with `new`, never inject them with `@Steps`: an injected step object is a
  proxy that swallows a failing assertion unless the listener runs, and for parameterized tests
  even then. With `new`, `@Step` is a marker for the reader and a failing step throws at once.
  `SerenityUsageTest` guards this.
- `./mvnw lombok:delombok javadoc:javadoc` renders the API docs into `target/reports/apidocs` and
  fails on doc errors. Delombok is bound to `verify`, so standalone javadoc needs it named explicitly.
- `.mvn/jvm.config` runs Maven's own JVM with `-XX:TieredStopAtLevel=1` (C1 only): Maven, javac and
  delombok are short-lived, and skipping C2 saves 3-4 s per build. The forked test JVM is unaffected.
- VS Code and Eclipse (m2e) compile into `target-ide/` (the `m2e` profile in `pom.xml`), so their
  background builds never overwrite the classes of a Maven build in `target/`.
- Run locally after packaging:
  `java -jar target/sensepitch-edge-*-with-dependencies.jar`

## Coding Style & Naming Conventions
- Java 25 is the target (`maven.compiler.release` set to 25).
- Formatting uses Spotless with Google Java Format; prefer 2-space indentation and standard Google style.
  Spotless leaves doc comments alone (`formatJavadoc` is off), so wrap `///` lines at 100 columns by hand.
- Lombok is enabled; `lombok.config` sets the `Builder` class name. `toBuilder` can't be enabled
  globally, so config records declare `@Builder(toBuilder = true)` explicitly.
- Keep class and method names descriptive and consistent with existing `*Config`, `*Handler`, and `*Lookup` patterns.
- Use named reusable constants for no-op implementations and immutable empty values.
- Write no code that is not needed: no cache, wrapper or optimization in front of a library that
  already does it (e.g. the Prometheus `Counter` keeps its data points, call `labelValues` per
  event), and no feature nobody asked for. Check what the library does before adding to it. An
  exception needs a clear justification, documented in the doc comment of the code, e.g. a
  measured hot path.
- Read concurrent state once per method invocation when multiple values must come from the same snapshot.
- Lock on a private final lock object (`private final Object lock = new Object()`), not on `this`
  or with a `synchronized` method, so no outside code can hold or contend on the lock.
- Make a field `volatile` only when it is read without the lock (e.g. a lock free fast path).
  Fields only touched under the lock are plain and grouped under a `// guarded by lock` comment;
  a rarely called getter takes the lock instead of making the field volatile.
- Order methods so a class reads top down: entry points first, a factored-out helper that is not
  called from outside right after the method(s) calling it. Group the methods of larger classes by
  role (e.g. the API for callers, the end/lifecycle, timers, sending) with a short `//` section
  comment. Every helper's doc says what it is for, not only what it does.
- Doc comments use Markdown (`///`, JEP 467): backticks for code, `[Foo#bar]` for references,
  `-` for lists, fenced blocks for samples. Do not use HTML tags or `{@code}`/`{@link}`.
  Exceptions: `{@value}` (no Markdown form) and `@see <a href>` (Markdown link syntax is
  rejected in `@see`).
- The doc build runs doclint and fails on unresolvable `[Ref]` links; it is bound to `verify`.
  It delomboks into `target/generated-sources/delombok` first, because javadoc reads sources and cannot see
  Lombok-generated members. `-Xdoclint` on `maven-compiler-plugin` does **not** work here:
  Lombok's annotation processor silently disables javac's doclint.

## Testing Guidelines
- Frameworks: JUnit 5, AssertJ, and Serenity BDD.
- Tests live under `src/test/java/` and generally use `*Test` or `*BDDTest` suffixes.
- Run all tests with `./mvnw test`; use Surefire defaults unless a test needs explicit configuration.
- Prefer Serenity BDD (`*BDDTest`) for behaviour tests: the step chains are more compact and
  readable than plain JUnit, compare `UnservicedHostHandlerBDDTest` with `UnservicedHostHandlerIT`,
  or `ProxyRejectedConfigBDDTest` with its former inline form. Group many rejected-input variants
  into one `@ParameterizedTest` over a step chain.
- No component level tests of the YAML or environment configuration: the generic loading
  (`Main.parseConfig`, `EnvInjector`, the config records) is tested once, a new key of a
  component adds no coverage there. A component tests its configuration through the config
  record and its builder: defaults, effective values, rejected values.
- When a plain JUnit test duplicates a BDD test, keep the BDD test as the unit test and move the
  JUnit one to `*IT` (runs with `verify` only). Port scenarios the BDD test lacks first, and let both
  class docs reference each other, naming the BDD test as the place for new scenarios.
- `./mvnw test` is the full smoke test: the unit tests alone cover everything that is unit
  testable. `./mvnw verify` with the integration tests and the JaCoCo floor is the final check,
  it must not be the only place where code of ours is executed. So do not move a test to `*IT` to
  make `./mvnw test` faster when that takes coverage away from the unit tests; make the test fast
  instead (fake clock, no real timers, see `TODO.md`). Check with `./mvnw clean test jacoco:report`
  and `target/site/jacoco/jacoco.csv` before and after.
- Tests that mainly exercise the JDK, Netty or other libraries rather than our code (e.g. real TLS
  handshakes, GC or RSS behaviour) belong in `*IT`, so `./mvnw test` stays fast. An `*IT` may
  repeat what a unit test covers against the real thing, it does not replace the unit test.

### Concurrent and asynchronous tests
For every test with more than one thread, process or timer. The assumption: **any action on
another thread or process, and any step of the test itself, may be delayed by the scheduler for
up to `TestingParameters.TIMEOUT`.** A test is correct only if it passes under any such delay.
- Every wait allows the full `TIMEOUT`, never less: `TestingParameters.TIMEOUT`,
  `TIMEOUT_SECONDS`, `await(description, condition)` and `stop(HttpServer)`, no literals. A
  hand-picked shorter bound is a flake. Intentional outages (`stop(0)` inside a test) and the
  class level `@Timeout` as outer guard stay explicit.
- Never assert a maximum elapsed time. Assert what happened and in which order, not how fast. To
  show that a call does not wait for another party (e.g. a reload does not wait for the log
  server), block that party on a latch the test owns so it cannot answer, run the call with
  `get(TIMEOUT_SECONDS, SECONDS)`, and only then release the latch: if the call waited, it could
  not return while the party is blocked.
- Timeouts and other timed behaviour (age limit, flush interval, backoff, drain and shutdown
  timeout, breaker delays) are tested on a simulated clock: the test moves the clock and never
  waits real time. `CircuitBreaker` with its `Ticker` shows the seam.
- Everything that waits real time is an `*IT`. There, asserting that *at least* some time has
  passed is fine (the timer did not fire early), asserting that *at most* some time has passed is
  not. A timer that must not fire during a test is set to `TestingParameters.OUT_OF_REACH_MILLIS`
  (10 × `TIMEOUT`), never a literal.
- Until a class has the clock seam, its real time unit tests stay for coverage (see above) and
  are listed under "Timing related tests" in `TODO.md`; do not add new ones.
- A transient state can be missed. `await` only monotonic conditions (`>=` on a counter) or a
  state the test freezes by holding the other side (worker, server, upstream on a latch or
  semaphore); assert while it is held, then release.
- A negative wait proves nothing ("nothing within 100 ms", "at most 6 in 500 ms"): the unwanted
  action may just be delayed. Wait for a positive event that would come after it, or count over a
  held state; a short negative check is at most a cheap extra, never the assertion.
- React on the event, do not busy wait. The bound is generous, so the normal case must not pay
  for it: the test is woken by the event it expects, signalled by a mock or fake on the other side
  (`CountDownLatch`, `Semaphore`, `BlockingQueue`, `CompletableFuture`), e.g. a test log server
  that releases a semaphore per post, or by the log output the code writes (diagnostic or request
  log). Polling with `TestingParameters.await` is the fallback where no hook exists, a sleep loop
  never.
- A wait or grace period that every run consumes in full is a defect to investigate, not a
  margin.

## Design Documents

- A design document in `doc/design/<topic>.md` records why something is built the way it is and
  what was compared. The behaviour itself is described in the class docs, do not repeat it.
  Examples: `circuit-breaker.md`, `request-log.md`, `test-speed.md`.
- Structure, in this order; leave out a section that has nothing to say:
  1. Title and an introduction: which classes or which part of the build it covers, with links
     to the sources, and what the document is for.
  2. `## Purpose and scope`: the problem, and the rules or constraints that bound the design.
  3. `## Overview`, optional: the parts and how they work together, or the numbers at a glance.
  4. `## Design decisions`: one `###` section per decision, the heading states the decision
     (e.g. "No sender thread"), the text gives the reason and the consequence.
  5. What was compared or rejected: `## Implementations investigated` for other projects that
     were read, `## Alternatives considered` for approaches that were tried or dropped, each with
     the reason or the measurement that decided it.
  6. `## Limitations`: what the design does not do or does not guarantee.
  7. `## Planned improvements`: short, the entries themselves live in `TODO.md`.
- State where facts come from: a measurement with date, machine and how it was taken, a library
  with its version, and say when something is inferred and not verified.
- Link sources relative to the document (`../../src/main/java/...`), wrap at 100 columns.
- Keep the document current: a change that overturns a decision or a number updates the document
  in the same commit. `TODO.md` links to the design document instead of repeating it.

## Commit & Pull Request Guidelines
- Recent commits use short, imperative summaries (e.g., “update crawlers”, “fix concurrency issues”).
- Keep commits focused and mention the primary behavior change.
- PRs should include: a concise description, any relevant configuration changes, and screenshots/log snippets when behavior is user-visible.

## Quick Lookup

### Key files
| File | Purpose |
|---|---|
| `Main.java` | Entry point, loads config from YAML or env vars, starts `Proxy` |
| `Proxy.java` | Netty `ServerBootstrap`, channel pipeline, SSL setup, `start()`, graceful `shutdown()` (listener, connection drain, event loops, request log, one deadline) |
| `ProxyConfig.java` | Root config record (holds `listen`, `sites`, `metrics`, etc.) |
| `ListenConfig.java` | Listen/bind settings (`address`, `httpsPort`, `ssl`, `hosts`, `snis`) |
| `HttpBatchSender.java` | Generic async posting of request log batches (concurrency cap, breaker, age limit); payload via `BatchPayload`, e.g. `OtlpBatchPayload` |
| `CircuitBreaker.java` | Passive, reusable circuit breaker with `Backoff`; used by `HttpBatchSender`, meant for upstreams too |
| `CircuitBreakerMetrics.java` | Metrics standard of all breakers, `sensepitch_circuitbreaker_*{component,name}`, resilience4j names plus transitions, errors and times |

### Configuration loading
- **Env vars**: prefix `SENSEPITCH_EDGE_`, camelCase → UPPER_SNAKE_CASE (e.g. `SENSEPITCH_EDGE_LISTEN_ADDRESS`)
- **YAML file**: first CLI arg, deserialized via SnakeYAML into records
- All config is Lombok `@Builder(toBuilder = true)` records

### Testing patterns
- **`EmbeddedChannel`**: Most tests use in-process Netty channels, no real TCP
- **`ExtendableSteps`**: BDD-style step classes in `*BDDTest.java` files
- **`proxy.addHttpHandlers(pipeline)`**: The key entry to test only the HTTP pipeline without a real server socket
- SSL test certs: `classpath:ssl/test.key` and `classpath:ssl/test.crt`

## Agent Workflow Reminders

- **Every plan starts with a summary of incompatible setup changes**: config (YAML/env) keys that were renamed, removed or reinterpreted, changed defaults, new mandatory settings, and behaviour changes for existing setups. If there are none, say so explicitly.
- **For refactoring tasks, the plan includes a summary of whether the change is an improvement**: what gets better (clarity, coupling, performance, extensibility), what gets worse or stays neutral, and an overall verdict.
- **Run `./mvnw spotless:check` BEFORE making any edits** to establish the formatting baseline. This ensures your diff only contains your changes, not unrelated reformatting.
- **Run `./mvnw spotless:apply` only on your changed files** after editing, or run it project-wide and then verify via `git diff` that no unrelated files were touched. If Spotless reformatted unrelated files, revert them with `git checkout -- <file>` before committing.
- **Run tests before AND after** your changes to confirm you didn't break anything.
- **Check `git diff` before committing** to review exactly what changed — reject any accidental reformatting of unrelated files.

## Security & Configuration Tips
- Test TLS keys/certs live under `src/test/resources/ssl/` and `src/test/resources/letsencrypt/`; do not reuse these in production.
- Diagnostic logs go through `ProxyLogger` → Log4j2 → standard error (`src/main/resources/log4j2.xml`);
  the request log is written directly to standard out. Never use `System.out`/`System.err` elsewhere.
- Tests use `src/test/resources/log4j2-test.xml`, which has no appender, so test runs are silent.
  In tests the `ProxyLogger`s do not write to Log4j2 but to the in-memory `LogSink` (installed by
  a JUnit `LauncherSessionListener` via `LogTarget.Factory`), which stores the records per logging
  thread. Assert on log output with the `LogCapture` JUnit extension: `new LogCapture()` sees what
  the test's own thread logged, `LogCapture.ofAllThreads()` is for code logging from its own
  threads (event loops, senders, writers) and also sees other tests. `Log4jCapture` is only for
  tests of the way into Log4j2 itself (Netty, JUL, `LogTarget.Log4jOutput`). Capture the request
  log by passing a `RequestLogCapture` (a `RequestLogOutput`) to `Proxy` or the request logger.
- Log levels and the reasons are described in the `ProxyLogger` class doc ("Levels"), the log
  methods link there. The important rule: **never log a request level event at `INFO` or above,
  errors included**. A request level event is anything that can happen per request or
  connection: a misbehaving client or upstream, a timeout, but also an unexpected exception that
  may be a bug of ours. Log it at `DEBUG`, for root cause analysis.
- **Every request level event is counted in a metric** (or recorded in the request log), so it is
  never silent and can be investigated when needed. A new request level log line comes with its
  counter and a test that asserts both.
- Tests do not assert on trace output, it is no acceptance criterion. Trace is enabled in tests
  only so the trace code is covered; `LogCapture` captures `DEBUG` and above, so debug output can
  be asserted.
