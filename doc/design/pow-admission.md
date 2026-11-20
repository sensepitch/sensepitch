# Proof-of-work admission

The deflector asks an unadmitted browser to perform a small SHA-256 search before forwarding
requests to the upstream. A successful answer earns an admission cookie, so subsequent requests
can pass without repeating the work. This raises the cost of clients that do not retain an
admission; it does not establish a person's identity or prevent automation.

This document describes the current flow and limitations, checked against the implementation on
2026-11-14. Implementation details belong in the JavaDoc of
[`Deflector`](../../src/main/java/org/sensepitch/edge/Deflector.java),
[`ChallengeGenerationAndVerification`](../../src/main/java/org/sensepitch/edge/ChallengeGenerationAndVerification.java)
and
[`DefaultAdmissionTokenGenerator`](../../src/main/java/org/sensepitch/edge/DefaultAdmissionTokenGenerator.java).

## Purpose and scope

The admission mechanism raises the cost of repeated unadmitted requests while allowing a browser
to reuse an admission. The following overview records the present design and its limits.

## Overview

Protection runs before upstream forwarding. A protection-wide bypass skips both cookie gates
and the deflector; otherwise configured cookie gates run first. Within the deflector, the order
is:

1. A valid `sensepitch-tk` admission cookie within its server-side age limit passes the request,
   including methods other than GET.
2. A configured deflector bypass or accepted crawler passes without solving a challenge.
   `noBypass` suppresses this deflector bypass for matching URIs; it does not invalidate an
   admission or override the protection-wide bypass.
3. Other methods than GET receive an empty, non-cacheable 403. Request bodies are not saved for
   replay after admission.
4. Unadmitted GET requests to challenge resources, progress reporting and answer endpoints are
   handled locally. Other GET requests receive a non-cacheable 403 HTML challenge page.

The page embeds a challenge and the configured hash prefix. It searches for a nonce such that
the hexadecimal SHA-256 digest of `challenge + nonce` starts with that prefix. The default prefix
is `888`; a three-digit hexadecimal prefix takes about 4,096 attempts on average. The browser
uses WebCrypto when available and a JavaScript implementation otherwise. The default
`powMaxIterations` is 1,000,000, a browser search budget rather than a server-side admission rule.
JavaScript and cookies are needed to complete the normal browser flow. An initially hidden tab
shows a checkbox before starting; this is a browser UI condition, not a server-verified gesture.

The browser submits the challenge and nonce by GET to `/.sensepitch.challenge.complete`. The
server checks freshness and the hash prefix. A correct answer receives 200 and a new
`sensepitch-tk` cookie; an invalid answer receives 400. After success the browser reloads the
original page, which can then pass with the cookie. Exhausted searches or failed submissions
show a message asking the user to refresh.

The page also requests assets under `/.sensepitch.challenge.files` and the handler provides
`/.sensepitch.challenge.step` for progress reporting. Browser timing and method parameters are
telemetry; acceptance depends on the challenge and nonce alone.

## Design decisions

### Stateless challenge verification

[`TimeBasedChallenge`](../../src/main/java/org/sensepitch/edge/TimeBasedChallenge.java) encodes the
server's millisecond timestamp in base 23. Verification accepts a positive timestamp whose
absolute difference from server time is less than five minutes, including future timestamps
within that window. The challenge is not signed and is not tied to a particular browser, IP
address or server. The `sensepitch-ch` cookie is issued with the page but is not checked when
verifying an answer. Verification needs no record of issued challenges or previous answers.

Admission tokens contain a generator prefix, creation time, a mixed identifier and a checksum
derived from SHA-256 over the identifier (including its time) followed by the configured secret.
The mixed identifier includes the issuing server's IPv4 address, thread and sequence information,
and random bytes. The server address contributes to token generation; verification does not
require the receiving server to have that address, and the token is not bound to the client's IP.

### Token generators support rotation

The first entry in `tokenGenerators` issues new tokens. Verification selects an entry by the
token's prefix, allowing old entries to remain while a new first entry issues tokens. Nodes that
need to accept each other's admissions must have matching verification prefixes and secrets.
Removing an entry stops acceptance of its tokens; there is no individual token revocation list.

The admission cookie is Secure, has path `/`, no explicit Domain, and a 30-day `Max-Age`. It is
readable by JavaScript because HttpOnly is not set. The server validates the token checksum and
returns its creation time, then enforces a server-side age limit, see
[Server-enforced admission expiry](#server-enforced-admission-expiry). The 30-day cookie `Max-Age`
is therefore a browser retention policy, while the server-side limit bounds how long a retained
token stays valid.

### Server-enforced admission expiry

A granted admission expires server-side. The token carries its creation time, so verification
compares that time with the current wall clock and rejects a token that is older than
`admissionMaxAgeMillis` (default 5 days) or whose creation time lies more than
`admissionClockSkewMillis` (default 5 seconds) in the future. The future allowance tolerates clock
skew between the issuing and verifying servers of a distributed fleet. Tokens are second-granular
and the comparisons are strict, so a token exactly at a boundary is still accepted. A rejected
token is handled like an absent admission: the browser is re-challenged and receives a fresh token
after solving, and the request is reported with the `challenge_sent` outcome.

### Preserve revisit history in a separate signed cookie

When an authentic expired admission triggers a challenge page, the response clears that admission
and sets the Secure, HttpOnly, host-only `admission0` cookie for 30 days at path `/`. Clearing the
expired token prevents a page reload from counting it again. The answer endpoint preserves history when
it issues a fresh admission. If the answer presents an expired admission, it records that expiry
as well.

The value is `original-token.count.tag`. The original token's generator prefix selects the secret.
HMAC-SHA-256 covers the token and decimal count with a key derived specifically for `admission0`.
A missing, unverified or retired-key history starts again at one with the currently expired token.
The count describes the signed history the client presented. A client can discard or replay an
older cookie, and concurrent requests can carry the same history.

### The request log shows only verified history

The deflector verifies an `admission0` cookie on every request that carries one, and only then,
so requests without history pay no HMAC. A verified history is attached to the request as
`Sp-Admission-Original-Token` and `Sp-Admission-Revisits`, like `Sp-Admission-Token`, and the
request log writes it as `sensepitch.protection.admission_original_token` and
`sensepitch.protection.admission_revisits` (a number). A response that sets the cookie, a
challenge page for an expired admission or an answer presenting one, logs the values it issues
instead. Admitted requests after a re-challenge thus link the fresh token to the original one.
An unverified, forged or retired-key history is not logged: otherwise a client could put any
token into the log and attribute its requests to another visitor. The legacy plain text format
keeps its columns, because its readers parse by position.

### Outcomes identify the protection path

The deflector reports `passed`, `bypassed`, `method_rejected`, `challenge_sent`, `answer_accepted`
and `answer_rejected` outcomes to metrics and the request log. Validated and newly issued tokens
are attached to the request as `Sp-Admission-Token` for logging. `Sp-Traffic-Type` labels admitted
traffic as `human`, detected crawlers as `bot`, and otherwise undetermined traffic as `unknown`.
The answer endpoint sets `human` even for a rejected answer, so use the protection outcome to
distinguish acceptance from rejection. These labels describe the protection path, not verified
human identity.

## Limitations

- **Replay and precomputation:** a valid challenge/nonce pair can be submitted repeatedly while
  fresh, issuing multiple admissions. Clients can construct timestamp challenges themselves,
  including timestamps slightly in the future. There is no single-use accounting or binding to
  the challenge cookie, client or site.
- **Admission reuse:** an admission can be copied and shared wherever its prefix and secret are
  accepted until it reaches the server-side maximum age (default 5 days). No per-token request
  budget or abnormal-use enforcement is implemented.
- **Checksum strength:** only four bytes of the SHA-256 digest are kept as the token checksum.
  This is a 32-bit check, not a full-length authentication tag; its forgery resistance is limited
  by that size. The current construction is a secret-suffixed hash, not HMAC.
- **Client cost varies:** the same prefix imposes different delays on different devices. Solvers
  can automate or parallelize the search, and a valid cookie avoids further PoW. The search
  budget does not guarantee that every browser will find a solution.
- **Time and fleet configuration:** challenge acceptance depends on server wall clocks and the
  receiving node's hash prefix. Admission acceptance depends on shared verification keys.
  Configuration changes can therefore affect a browser between challenge issue and submission.

## Planned improvements

Restrictions on admissions per solved challenge and abnormal-use detection remain separate
tasks in [TODO.md](../../TODO.md). This overview does not choose their enforcement or cluster
coordination policies.
