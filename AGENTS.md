# Repository Guidelines

## Project Structure & Module Organization
- `src/main/java/org/sensepitch/edge/` contains the core proxy implementation and supporting utilities.
- `src/main/resources/` holds static assets and data (crawler lists, challenge assets, HTML/CSS/JS).
- `src/test/java/org/sensepitch/edge/` contains JUnit 5 and Serenity BDD tests; `src/test/resources/` holds test configs and TLS fixtures.
- `performance-test/` includes benchmarking setup and notes.
- `pom.xml` is the Maven build definition; `mvnw`/`mvnw.cmd` are the recommended entry points.

## Build, Test, and Development Commands
- `./mvnw clean package` builds the project and produces a shaded JAR at `target/sensepitch-edge-*-with-dependencies.jar`.
- `./mvnw test` runs the JUnit/Serenity test suite via Surefire.
- `./mvnw spotless:check` verifies formatting; `./mvnw spotless:apply` auto-formats Java sources.
- `./mvnw verify` runs the whole gate: tests, `spotless:check`, the Javadoc build, and the JaCoCo
  coverage floor (`jacoco-check` in `pom.xml`, unit and integration tests combined). Raise the floor
  when coverage improves. The generic request log posting (`HttpBatchSender`, `CircuitBreaker`,
  `CircuitBreakerMetrics`, `Backoff`, `ThreadBatcher`, `BatchingRequestLogger`, `OtlpBatchPayload`,
  `GzipCompressor`, ...) is held at 100% line and
  branch coverage by a class rule. A gap is a design question: remove the stray error handling,
  validate earlier, or test the case; do not lower the rule.
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
- Read concurrent state once per method invocation when multiple values must come from the same snapshot.
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
- When a plain JUnit test duplicates a BDD test, keep the BDD test as the unit test and move the
  JUnit one to `*IT` (runs with `verify` only). Port scenarios the BDD test lacks first, and let both
  class docs reference each other, naming the BDD test as the place for new scenarios.
- Tests that mainly exercise the JDK, Netty or other libraries rather than our code (e.g. real TLS
  handshakes, GC or RSS behaviour) belong in `*IT`, so `./mvnw test` stays fast.

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
  Assert on log output with the `LogCapture` JUnit extension; capture the request log by passing a
  `RequestLogCapture` (a `RequestLogOutput`) to `Proxy` or the request logger.
