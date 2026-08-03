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

## Quick Lookup

### Key files
| File | Purpose |
|---|---|
| `Main.java` | Entry point, loads config from YAML or env vars, starts `Proxy` |
| `Proxy.java` | Netty `ServerBootstrap`, channel pipeline, SSL setup, `start()` |
| `ProxyConfig.java` | Root config record (holds `listen`, `sites`, `metrics`, etc.) |
| `ListenConfig.java` | Listen/bind settings (`address`, `httpsPort`, `ssl`, `hosts`, `snis`) |

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

- **Run `./mvnw spotless:check` BEFORE making any edits** to establish the formatting baseline. This ensures your diff only contains your changes, not unrelated reformatting.
- **Run `./mvnw spotless:apply` only on your changed files** after editing, or run it project-wide and then verify via `git diff` that no unrelated files were touched. If Spotless reformatted unrelated files, revert them with `git checkout -- <file>` before committing.
- **Run tests before AND after** your changes to confirm you didn't break anything.
- **Check `git diff` before committing** to review exactly what changed — reject any accidental reformatting of unrelated files.

## Security & Configuration Tips
- Test TLS keys/certs live under `src/test/resources/ssl/` and `src/test/resources/letsencrypt/`; do not reuse these in production.
- Logging for tests is configured via `test-logging.properties` and `src/test/resources/log4j2.xml`.
