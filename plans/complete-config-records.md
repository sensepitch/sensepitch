# Plan: complete config records, defaults set by the builder, no `effective*` getters

## Incompatible setup changes
- An explicit `0` (or negative) for a key that used to mean "default" is rejected:
  `otlp.timeoutMillis`, `flushIntervalMillis`, `batchBytes`, `maxConcurrentRequests`,
  `maximumUnsentLogAgeMillis`, `initialRetryDelayMillis`, `maxRetryDelayMillis`,
  `shutdownTimeoutMillis`, `requestLog.drainTimeoutMillis`. Omitting the key keeps the default.
  `maxBufferedBytes` below its minimum: keep today's behaviour (raise to the minimum, warning).
- The configuration dump at start (`Proxy.dumpConfig`) now shows every default as a value. Same
  semantics, more lines.
- No defaults change, no key renamed.

## Is the refactoring an improvement?
- Better: the record is the effective configuration. The dump is complete and, loaded again,
  gives an equal record (replicable). No two ways to read a value (`x()` vs `effectiveX()`); one
  pattern for all config records (`DeflectorConfig` already presets in `builder()`). The
  validation sees real values, not sentinels.
- Worse: a hand-written `build()` in the Lombok builder for the records with derived defaults;
  the loaders must find `builder(ProxyEnvironment)`.
- Neutral: behaviour of a running proxy.
- Verdict: an improvement; the config becomes data that can be dumped and replayed.

## Context
The new retry delays copied the existing `effective*` getter pattern of `OtlpConfig` (7 getters)
and `ProxyConfig` (shutdown, drain). A getter computes a value that is not in the record: the
dump does not show it and a dumped config cannot reproduce it. Defaults belong in the builder,
derived ones are computed in the config class from basic environment information.

## Design

### `ProxyEnvironment` (new record, main)
`record ProxyEnvironment(int eventLoopThreads)`: what defaults may depend on. `current()` reads
it as today's `OtlpConfig.defaultEventLoopThreads()` does (Netty's `io.netty.eventLoopThreads`,
2 × processors). `ProxyContext` gets `ProxyEnvironment environment()`, as you suggested, so code
with a context builds configs the same way. Config loading runs before a `Proxy` exists (in
`Main`), so the loader takes the `ProxyEnvironment` itself, not a whole `ProxyContext`. Tests can
pass a fixed environment for deterministic values.

### Defaults in the builder
- Every config record with defaults overrides `builder()` and presets the constant defaults, as
  `DeflectorConfig.builder()` does, plus `builder(ProxyEnvironment)` where a default depends on
  the environment; `builder()` = `builder(ProxyEnvironment.current())`.
- Derived defaults, computed in the config class by a hand-written `build()` in the Lombok
  `Builder` (Lombok keeps a `build()` it finds and generates the rest), only if the key was not
  set (still `0` in the builder):
  - `OtlpConfig.maxConcurrentRequests` = `eventLoopThreads`
  - `OtlpConfig.maxBufferedBytes` = `2 × eventLoopThreads × batchBytes` (from the final
    `batchBytes`, so a configured batch size is respected)
  - `ProxyConfig`: `requestLog.drainTimeoutMillis` = `shutdownTimeoutMillis / 3`; it crosses
    records, so `ProxyConfig`'s `build()` sets it in the nested `RequestLogConfig`.
- The built record has no `0` meaning "default"; the `0` sentinel exists only inside the builder.
- `toBuilder()` of a complete record keeps all values. A test that changes `batchBytes` that way
  also sets `maxBufferedBytes`, or the minimum check applies, as documented.

### Remove the `effective*` getters
`OtlpConfig`: `effectiveTimeoutMillis`, `effectiveInitialRetryDelayMillis`,
`effectiveMaxRetryDelayMillis`, `effectiveFlushIntervalMillis`, `effectiveBatchBytes`,
`effectiveMaxConcurrentRequests`, `effectiveMaximumUnsentLogAgeMillis`, `effectiveCompression`
(`compression` preset to `gzip`). `effectiveMaxBufferedBytes` (`max(value, minimum)`) and
`minMaxBufferedBytes` stay as validation logic, renamed if needed, since the minimum is a rule
and not a default. `ProxyConfig`: `effectiveShutdownTimeoutMillis`,
`effectiveDrainTimeoutMillis`; their negative/exceeds checks move into validation (compact
constructor or `build()`). About 50 call sites switch to the plain accessor.

### Validation
`OtlpLogDestination.settings` and the `ProxyConfig` checks reject `<= 0` for the keys above and
keep the existing rules (effective max retry delay ≥ initial, drain ≤ shutdown). The new
`OtlpRetryConfigBDDTest` keeps its rejected-value cases; "effective delays" become "built
values".

### Loaders
`config/RecordConstructor` (YAML) and `EnvInjector` call `builder` by reflection: prefer
`builder(ProxyEnvironment)` when the record has it, else `builder()`. `Main` loads with
`ProxyEnvironment.current()`; `Main.parseConfig(String)` keeps its signature for tests.
`RecordConstructor` starts nested records from `DEFAULT` if present: those `DEFAULT`s are built
by the preset `builder()`, so they stay complete.

### Docs and rules
- `AGENTS.md`, Coding Style: "Config records are complete: defaults are preset in an overridden
  `builder()`, derived ones are computed in the builder's `build()` from the other values and a
  `ProxyEnvironment`. No `effective*` getters and no `0` meaning default; the dumped config,
  loaded again, gives an equal record."
- `OtlpConfig`, `ProxyConfig`, `RequestLogConfig` docs: "defaults to" stays, "omitted or zero"
  becomes "omitted". README sections on these keys likewise; the example block shows defaults.
- `TODO.md`: Done entry.

## Tests
- Round trip: `Main.parseConfig(Proxy.toYaml(config))` equals `config`, for a minimal YAML and
  for one with derived defaults (fixed `ProxyEnvironment`), as a `*BDDTest`. This is a generic
  loader test, allowed by the new AGENTS rule.
- Derived defaults follow a configured `batchBytes` and `shutdownTimeoutMillis`.
- Rejected explicit `0` in the existing rejected-config BDD tests (`ProxyRejectedConfigBDDTest`,
  `OtlpRetryConfigBDDTest`).

## Verification
- `./mvnw spotless:check` and `./mvnw clean test jacoco:report` before.
- `./mvnw verify -Dfast` while working; the full `./mvnw verify` at the end (100 % rule of the
  request log classes, coverage floor, Javadoc).
- `grep -rn "effective[A-Z]" src/main src/test` is empty except the renamed minimum check.
- Start the jar with a small config and check the dump: all defaults present; feed the dump back
  as config, same dump.
- No commit; print the suggested commands.
