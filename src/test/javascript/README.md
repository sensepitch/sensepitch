# Challenge JavaScript tests

`./mvnw verify` runs this suite as an integration test and requires Node.js 22 or newer on
`PATH` (no npm dependencies). To run it separately from the repository root:

```sh
node --test src/test/javascript/challenge.test.cjs
```

The suite executes the shipped browser script in
an isolated VM with a small browser harness. It exercises real Node WebCrypto and the JavaScript
SHA-256 shim, simulates legacy `msCrypto` events and failures, and checks the proof-of-work loop,
visibility gate, completion transport, and telemetry. It does not run a browser or real HTTP server.

The completion GET to `/.sensepitch.challenge.complete` retains `challenge` and `nonce` and adds:

| Parameter | Meaning |
| --- | --- |
| `completedAt` | Client wall-clock timestamp in Unix epoch milliseconds when the proof is solved. |
| `durationMs` | Elapsed milliseconds from calculation start through the matching digest, including async hashing and cooperative yields. Uses `performance.now()` when available, otherwise the wall clock, clamped to zero if it moves backwards. Excludes waiting for the visibility gate and the completion HTTP request. |
| `method` | `shim` for the pure JavaScript SHA-256 implementation, `native` for WebCrypto or legacy `msCrypto`. Identifies the implementation that produced the matching digest, including synchronous native failure followed by shim fallback. |

These fields are client-reported telemetry; the server continues validating the challenge and nonce.
