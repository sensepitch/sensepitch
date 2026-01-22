# Repository Guidelines

## Project Structure & Module Organization
- `src/main/java/org/sensepitch/edge/` contains the core proxy implementation and supporting utilities.
- `src/main/resources/` holds static assets and data (crawler lists, challenge assets, HTML/CSS/JS).
- `src/test/java/org/sensepitch/edge/` contains JUnit 5 and Serenity BDD tests; `src/test/resources/` holds test configs and TLS fixtures.
- `performance-test/` includes benchmarking setup and notes.
- `pom.xml` is the Maven build definition; `mvnw`/`mvnw.cmd` are the recommended entry points.

## Build, Test, and Development Commands
- `./mvnw clean package` builds the project and produces a shaded JAR at `target/sensepitch-edge-1.0-SNAPSHOT-with-dependencies.jar`.
- `./mvnw test` runs the JUnit/Serenity test suite via Surefire.
- `./mvnw spotless:check` verifies formatting; `./mvnw spotless:apply` auto-formats Java sources.
- Run locally after packaging:
  `java -jar target/sensepitch-edge-1.0-SNAPSHOT-with-dependencies.jar`

## Coding Style & Naming Conventions
- Java 21 is the target (`maven.compiler.source/target` set to 21).
- Formatting uses Spotless with Google Java Format; prefer 2-space indentation and standard Google style.
- Lombok is enabled; `lombok.config` enforces `toBuilder` support and a `Builder` class name.
- Keep class and method names descriptive and consistent with existing `*Config`, `*Handler`, and `*Lookup` patterns.

## Testing Guidelines
- Frameworks: JUnit 5, AssertJ, and Serenity BDD.
- Tests live under `src/test/java/` and generally use `*Test` or `*BDDTest` suffixes.
- Run all tests with `./mvnw test`; use Surefire defaults unless a test needs explicit configuration.

## Commit & Pull Request Guidelines
- Recent commits use short, imperative summaries (e.g., “update crawlers”, “fix concurrency issues”).
- Keep commits focused and mention the primary behavior change.
- PRs should include: a concise description, any relevant configuration changes, and screenshots/log snippets when behavior is user-visible.

## Security & Configuration Tips
- Test TLS keys/certs live under `src/test/resources/ssl/` and `src/test/resources/letsencrypt/`; do not reuse these in production.
- Logging for tests is configured via `test-logging.properties` and `src/test/resources/log4j2.xml`.
