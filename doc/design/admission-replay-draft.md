# Admission replay protection (draft)

**Draft, nothing decided.** This is the research for the `TODO.md` entry "restrict admissions
per solved challenge". It covers
[`TimeBasedChallenge`](../../src/main/java/org/sensepitch/edge/TimeBasedChallenge.java),
[`ChallengeGenerationAndVerification`](../../src/main/java/org/sensepitch/edge/ChallengeGenerationAndVerification.java),
[`Deflector`](../../src/main/java/org/sensepitch/edge/Deflector.java) and
[`DefaultAdmissionTokenGenerator`](../../src/main/java/org/sensepitch/edge/DefaultAdmissionTokenGenerator.java).
It records the replay threat, compares enforcement and coordination options, and names the policy
choices that the maintainer has to tick before implementation. Nothing here is a decision.

The flow and code references were checked against `main` at `af435f6` on 2026-11-20. The project
is also described in [Proof-of-work admission](pow-admission.md); that document stays the
description of the current behaviour.

## Purpose and scope

The deflector asks an unadmitted browser to solve a SHA-256 proof-of-work search and gives it an
admission cookie after a correct answer. The admission is a bearer cookie: it raises the cost of
clients that do not keep a cookie, but it does not identify a person or enforce application
security. The project goals say that CAPTCHAs in the applications should become redundant and that
the edge is not an access-control or rate-limiting gateway. The design must therefore stay cheap
on the request path and must not pretend to be an identity system.

The problem in this draft is narrower: one solved challenge currently yields an unlimited number
of admissions, and a copied admission yields unlimited requests until it expires. The questions
are whether an answer may be used once, what "once" means across replicas, how long the evidence
must be retained, and what coordination is acceptable.

Out of scope:

- abnormal-use detection, retention of per-token request streams and alerting; that is a separate
  `TODO.md` entry;
- CAPTCHA, fingerprinting or reputation services;
- protection against a compromised admission secret, browser or TLS endpoint;
- application authorization after the edge has admitted a request.

## Overview: what the edge does today

### Challenge and answer

`TimeBasedChallenge` returns the current wall-clock millisecond value in base 23, without a
signature or random component. Verification parses the value and accepts it when the absolute
difference from server time is less than five minutes, including a timestamp that lies in the
future within that window. The implementation comment says the time check is meant to avoid a
static pre-recorded result.

`ChallengeGenerationAndVerification` then hashes `challenge + nonce` with SHA-256 and checks the
configured hexadecimal prefix. A correct answer only proves that someone found a solution for a
recent-looking timestamp. The page issues a `sensepitch-ch` cookie, but the answer endpoint does
not compare the submitted challenge with that cookie, and no server-side record of issued
challenges exists. The challenge cookie is HttpOnly, Secure and host-only, with the same 30-day
`Max-Age` as the admission cookie.

The browser submits the answer as a GET to `/.sensepitch.challenge.complete` with `challenge` and
`nonce` query parameters. `DeflectorHandler` handles that path locally before any admission. A
correct answer calls `tokenGenerator.newAdmission()` and sets a new `sensepitch-tk` cookie. The
token generator does not receive the challenge or the client identity, and the answer endpoint
keeps no "already answered" state.

### Admission token

The token is a 30-character string: one generator prefix, six time characters from a fixed epoch,
17 characters of a mixed identifier and six checksum characters. The mixed identifier contains
the issuing server's IPv4 address, thread, sequence and random bytes. The checksum is the first
four bytes of SHA-256 over the identifier and the configured secret. `checkAdmission` verifies the
checksum and returns the creation time. The deflector then rejects a token older than
`admissionMaxAgeMillis` (five days by default) or too far in the future. A valid token is accepted
on every request with no request budget or use count.

### Exposure

The following facts follow directly from the code:

- The challenge is only a timestamp and is not signed. A client can choose a timestamp in the
  accepted window; the server cannot tell a challenge it issued from one the client invented.
- The answer endpoint does not bind the answer to the challenge cookie, host, site or node. The
  same `challenge`/`nonce` pair is accepted wherever the hash prefix and clock window match.
- Every accepted answer calls `newAdmission()` with fresh random and sequence bits. One solved
  challenge can be submitted repeatedly during its validity window and each submission creates
  another distinct admission.
- Nothing records a consumed challenge. A replay is indistinguishable from a first correct
  answer; there is no replay counter or rejection path.
- An admission is checked only for checksum and age. A copied cookie can be used by any number of
  clients and requests until it expires.
- The challenge cookie carries no server-side record. The cookie cannot prove that a particular
  browser answered a particular challenge.

A challenge issued now remains acceptable for about five minutes. Within that window a client can
issue parallel requests and collect many admissions from one proof of work. Each resulting token
then lasts up to five days. A client can also submit a challenge that it constructed itself,
including one for a timestamp up to five minutes in the future, which stretches the usable period
of that value from construction to roughly ten minutes.

This is not a key-forgery problem: the admission checksum still protects against random token
forging as well as its 32-bit size allows. It is a lifecycle problem: the server cannot tell one
answer from the next, and it cannot tell a copied admission from the original user.

## Threat model

The assumed attacker is remote and unauthenticated. It can

- fetch challenge pages and inspect the JavaScript and cookies;
- solve a proof of work once, or solve many challenges in parallel;
- copy a challenge, an answer or an admission cookie between clients and hosts;
- submit requests in parallel and repeat requests;
- choose its own challenge values if the server accepts them.

The attacker cannot read the configured secrets, break HMAC-SHA-256 or SHA-256, or compromise a
server. The attacker may run many source addresses, so binding to one client address would also
break legitimate mobile and NAT users without stopping a distributed attacker.

The security goal for this item is: one solved challenge should not create more than one usable
admission, and a replay should not multiply the admissions or the work saved. A copied admission
is still a bearer credential; limiting its reuse is a separate policy question.

## Design options

### Common prerequisite: a signed, random challenge

A random challenge identifier and a server authentication tag fix the "client invents the
challenge" and "two users receive the same timestamp" problems. A signed challenge also gives the
answer endpoint values that can be covered by a deterministic token derivation and by replay
accounting.

A possible wire format, for discussion only:

```text
v1.<payload-base64url>.<tag-base64url>
payload: issued-at millis | 16 random bytes | host hash | generator prefix | difficulty
tag:     HMAC-SHA-256(key, label | version | payload), truncated to 16 bytes
```

The challenge stays a string that the browser hashes with the solution nonce, so the JavaScript
does not need a new proof-of-work algorithm. The proposal has these properties:

- The random 16-byte identifier makes challenges unique even when many are issued in one
  millisecond. This matters for any consumed-challenge set: a timestamp-only identifier would
  make concurrent users collide and only one of them could answer.
- The HMAC covers the version, issue time, host, issuing generator prefix and difficulty. A
  challenge cannot be transplanted to another host, reused after a configuration change, or
  given a different difficulty by the client.
- A 16-byte tag is far stronger than the four-byte admission checksum. The challenge tag protects
  the server's choice of identifier and bindings; it is not the proof of work.
- The key should be domain-separated from the admission token and history keys, for example by
  deriving it from the generator secret with a distinct label, as `signHistory` already does for
  `admission0`. Verification should use a constant-time comparison.
- A challenge key has to remain available for at least the challenge lifetime plus clock skew,
  not for the five-day admission lifetime. The existing `tokenGenerators` list already provides a
  prefix and rotation mechanism; reusing it avoids a second secret format, but a separate
  `challengeKeys` list is also possible.
- Binding to the client IP or User-Agent is not recommended. Both are copyable or unstable; NAT
  and mobile networks make an IP-bound admission unreliable, and an attacker can copy the
  header values. The host and the issuing generator are meaningful bindings; the client identity
  is not available without a stronger authentication mechanism.
- Comparing the submitted challenge with the `sensepitch-ch` cookie is a useful consistency check
  but not a replay defence: a client that can copy one can copy the other. The signed identifier
  and the single-use rule are what matter.

The challenge cookie's `Max-Age` should probably follow the challenge lifetime rather than the
current 30 days. That is a cookie-hygiene choice, not a substitute for verification.

### Option A: idempotent admission, no state

Derive the admission deterministically from the challenge instead of generating a new random
admission on every answer:

```text
mixed = HMAC-SHA-256(admission-secret, label | host | generator-prefix | challenge-id)[0..12]
token time = challenge issued-at (second granularity)
token prefix = generator prefix selected at challenge issue
```

The token checksum and expiry checks stay as they are, or the token format can be versioned to use
a full HMAC tag. A replay of the same signed challenge then computes the same mixed identifier,
the same time and therefore the same token. The answer endpoint can return 200 with the same
cookie, which is idempotent, or reject the second answer if a local consumed set (Option B) is
also installed.

Advantages:

- one solved challenge yields one token by construction, on every node, without shared state;
- replay and a lost first response are harmless: the browser receives the same admission again;
- no memory per challenge, no cleanup, no coordination and no new failure mode on the answer path;
- it fits the current multi-replica statement that replicas share secrets and no request state.

Costs and limits:

- it does **not** stop a client from copying that one admission and using it in many places;
- it does not reject replay, so it cannot report a replay attempt unless additional state is
  added; `answer_accepted` alone would hide it;
- the token format and the answer path need a versioned change; the time in the token should be
  the challenge issue time, not the replay time, otherwise a replay produces a different token;
- a token generated under an old generator prefix remains valid only while that generator remains
  in `tokenGenerators`. Removing a generator during the challenge window invalidates outstanding
  answers; that is an acceptable explicit configuration change, but must be documented.

If the admission token must also be site-bound, the host needs to be part of the token's verified
data. The current token has no host field, so an answer from one site can otherwise be used on
another site that shares the generator secret. That is a separate format decision.

### Option B: node-local consumed-challenge set

Store each accepted challenge identifier until it can no longer be submitted. The check is one
`putIfAbsent` on the answer path; a second answer sees the entry and is rejected. The record can
be a small map from challenge identifier to expiry, or a time-ordered ring with a sweep. The
retention time is the challenge acceptance window plus the allowed clock skew, not the admission
lifetime.

Advantages:

- strict rejection on one node, with a low-cardinality replay outcome that can be counted and
  logged;
- no network dependency and no new configuration service;
- can be combined with Option A, so a replica that did not see the first answer still produces
  the same token rather than a second distinct one.

Costs and risks:

- memory grows with the challenge issuance rate times the retention time. Using an illustrative
  48 bytes per entry, 300 seconds and an assumed 1,000 challenge pages per second gives about
  14.4 MB; 10,000 per second gives about 144 MB. These numbers are assumptions, not measurements,
  and the map's real overhead depends on the implementation.
- a challenge flood can grow the set; the size must be bounded. A hard cap, eviction and a
  metric for rejected inserts turn the cap into a new denial path. A challenge-issuance rate limit
  would also be needed, which is a separate protection decision.
- with `N` replicas each node accepts the first answer it sees, so a replay can still produce up
  to `N` admissions unless Option A makes those admissions identical. A process restart forgets
  the set. These are not violations of a node-local rule, but they are not cluster-wide
  single-use.
- a strict second-answer response breaks browsers that retry after a lost response. The idempotent
  response of Option A is friendlier; the strict rejection is only worth its cost if replay
  visibility or a single-use policy is required.

A probabilistic structure such as a Bloom filter would reduce memory, but false positives would
reject valid first answers. For an admission mechanism whose purpose is to let legitimate browsers
through, that is a poor trade. Exact identifiers or no set are cleaner.

### Option C: cluster-wide consumed-challenge store

A shared store such as Redis could do one `SET key value NX PX retention` per answer and return the
same token or a rejection on the second answer. This is the only option that enforces strict
single-use across replicas and restarts.

Advantages:

- one admission per challenge cluster-wide, independent of which node answers or how many
  replicas exist;
- the retention and replay decision have one authoritative place.

Costs and risks:

- the project has no shared store today: configuration is local YAML or environment state and the
  edge is deliberately stateless across replicas. Adding Redis adds a deployment dependency,
  credentials, network policy, monitoring and a new way for admission to fail.
- only answers need the store, not every admitted request. The normal hot path stays local, which
  limits the latency impact, but a store outage still blocks new admissions.
- failure behaviour is a policy choice. Fail-open preserves availability but loses single-use
  during the outage; fail-closed locks out every unadmitted browser when the store is down.
  Option A can be the degraded mode in either case, but then a cluster-wide strict policy is not
  actually guaranteed during an outage.
- store TTLs must cover the challenge window plus any verification skew. Clock differences
  between the edge and the store become another correctness input.
- a compromise of the store can suppress answers or leak challenge identifiers; it must not hold
  the admission secrets.

Option C is the right shape if the maintainer requires strict cluster-wide rejection. It is not
needed merely to prevent one challenge from producing many distinct tokens; Option A does that
without coordination.

### Admission reuse limits

Single-use of a challenge is independent of how often the resulting admission may be used. Three
policies are possible:

- **Unlimited requests until expiry.** This is today's behaviour. Option A removes the
  multiplication from replay, but a copied admission remains usable by anyone until the
  five-day maximum age.
- **A request budget per admission.** The server would need mutable state keyed by the token
  (or a token shard) for the admission lifetime. A node-local map has the memory and eviction
  costs of Option B, but over a five-day window and with a lookup on every request. A cluster-wide
  counter would put a store on the hot path and fail the availability goal. A signed cookie
  cannot carry a decrementing counter that the client cannot reset or replay.
- **Bind to a client property.** IP, IP prefix or ASN binding needs no per-token state but breaks
  mobile, CGNAT and shared-network users. It limits sharing across networks, not repeated use from
  the same network, and the property can change during the token lifetime. It also conflicts with
  the project's goal of not being an access-control gateway.

A middle ground is to limit the admission token's lifetime or re-challenge periodically; that
does not limit concurrent sharing but reduces the value of a leaked token. The existing
`admissionMaxAgeMillis` already gives this control. It is a policy choice, not a replay fix.

The `admission0` history cookie is not a quota: it is signed but client-controlled, and a client
can discard it, replay an older one, or reset the count by never presenting it. It can support
telemetry, not enforcement.

### Per-node versus cluster-wide

| Property | Option A, idempotent | Option B, node-local set | Option C, shared store |
| --- | --- | --- | --- |
| Distinct tokens from one challenge | one everywhere | up to one per node | one |
| State on the answer path | none | per node, bounded by issue rate | one remote operation |
| State on normal admitted requests | none | none | none |
| Replica restart | no effect | forgets consumed answers | retained by the store |
| New infrastructure | none | none | Redis-like service and policy |
| Replay rejection/visibility | none | local | cluster-wide |
| Failure of coordination | none | none | affects new answers |

## Recommendation for approval, not a decision

The draft recommends Option A as the first increment, with the signed, random, host-bound
challenge and a versioned deterministic token. It meets "one admission per solved challenge"
across replicas without adding state or a failure mode to an availability-focused proxy, and it
keeps the existing stateless architecture. The price is that replay is idempotent rather than
rejected, and a copied admission still has unlimited reuse until expiry.

Option B should be added only if replay rejection or replay telemetry is a product requirement.
Option C should be deferred until a strict cluster-wide policy and a store are explicitly wanted.
A per-token request budget should not be added speculatively: it puts state on the hottest path
and its memory cost is driven by five-day token retention.

None of those choices is made here. The unchecked options are in `TODO.md`.

## Observability and verification plan

If Option A is approved, the implementation should keep the existing `answer_accepted` and
`answer_rejected` outcomes and add counters or bounded outcomes for the new rejection reasons:
bad signature, wrong host, unsupported version, expired challenge, unknown generator and, if
Option B exists, replay. Response statuses should distinguish an invalid answer (400) from a
temporary configuration problem in the same way the current endpoint does; the exact status is a
design choice. A replay that returns the same token is still an accepted answer and must not be
logged at request level above `DEBUG` without a count.

The tests would need to cover, with a fake clock and no real timers:

- the same signed challenge and nonce submitted twice produces the same token under Option A;
- a challenge from another host, with a tampered tag or with a changed difficulty is rejected;
- an expired challenge is rejected and no token is issued;
- two different challenges produce different tokens, including two challenges issued in the same
  millisecond;
- old-format challenges and old admission tokens follow the chosen migration policy;
- with Option B, the second answer is rejected, the entry expires at the retention boundary, and
  a concurrent pair has exactly one winner;
- with Option C, a store error follows the chosen fail-open or fail-closed policy and is counted;
- key rotation: an answer issued under the previous generator still works while that generator is
  configured, and stops working when it is removed.

The existing `ChallengeTest`, `DeflectorHandlerTest`, `AdmissionRevisitTest` and
`DeflectorAdmissionExpiryTest` are the starting points. No implementation or test is part of this
research turn.

## Implementations investigated

All sources below were read in this environment on 2026-11-20. No comparable proof-of-work
implementation was available locally, so the closest readable replay-prevention implementations
are used as evidence; the proof-of-work products named under "Not accessible" were not examined.

### Passlib 1.9.3

Package `python3-passlib` 1.9.3-1ubuntu1. The TOTP tutorial has a section "Preventing Token
Reuse" in `/usr/share/doc/python3-passlib/html/_sources/narr/totp-tutorial.rst.txt` (around
lines 672-700): an observed token can be replayed within the time window; applications are
"strongly encouraged" to record the last counter per user in a temporary cache for at least
`period + window` seconds and reject a counter that is not newer. The implementation in
`/usr/lib/python3/dist-packages/passlib/totp.py` (`TOTP.match`, lines 1126-1218) raises
`UsedTokenError` when the matched counter equals `last_counter`; `cache_seconds` is
`period + window` (lines 1822-1843).

This is a directly comparable time-window replay defence, although it stores a monotonic counter
per user rather than a set of challenge identifiers. It supports two conclusions used above:
retention is the verification window, not the credential lifetime, and exact state is needed to
reject a replay. It does not prescribe a cross-replica store, so the per-node versus shared
choice remains open.

### OAuthlib 3.3.1

Package `python3-oauthlib` 3.3.1-1build1. `oauthlib/oauth1/rfc5849/request_validator.py`, lines
571-620, defines `validate_timestamp_and_nonce`. It quotes section 3.3 of the OAuth 1 specification:
a nonce is a random string that lets the server verify that a request was never made before and
"helps prevent replay attacks"; the nonce must be unique for the same timestamp, client
credentials and token. The docstring says an implementation should keep a nonce/timestamp record
and reject a pair already seen, and shows a set/database lookup. It explicitly requires a
storage-backed check; it does not choose a TTL or a cluster topology.

`oauthlib/common.py` (`generate_nonce`, lines 161-167) appends a random 64-bit number to the epoch
timestamp to make collisions unlikely. The 64-bit example is weaker than the 16-byte random
challenge identifier recommended here; it is useful evidence for the shape (time plus random),
not a minimum for this design.

These sources are about one-time request nonces, not proof of work, but they confirm the two
standard mechanisms: authenticate the issued value and keep exact state for the retention window
if replay must be rejected.

### This project

The code links in the introduction, the tests named above and
[Proof-of-work admission](pow-admission.md) were read. The existing
`doc/kubernetes-support/3-gap-analysis.md` states that the challenge is not signed and that
signing is a separate hardening item; that note is consistent with this draft.

### Not accessible

The shell has no external network in this sandbox. HTTPS requests to `www.rfc-editor.org` and
`datatracker.ietf.org` were refused by the network policy, so RFC 6238 (TOTP), RFC 5849 (OAuth 1),
RFC 7519 (JWT) and RFC 8555 (ACME nonces) were not read. The source and documentation of Anubis,
Cloudflare Turnstile, hCaptcha and Hashcash were not available locally and could not be fetched.
No claims about their implementations are made here; the comparison above is limited to sources
that were actually readable. In particular, this draft does not claim that Turnstile or Anubis use
a particular state model. The comparison is therefore anchored in this project and in two local
implementations of replay prevention, not in other proof-of-work products.

## Alternatives considered

- **Keep the timestamp challenge and shorten the window.** A shorter window narrows replay and
  precomputation but does not stop repeated submissions inside it, and a client can still mint a
  challenge. Rejected as a complete fix; it is only a risk-reduction knob.
- **Use a random challenge without signing it.** The random value prevents same-millisecond
  collisions, but the client can still choose identifiers, precompute solutions and choose a
  future timestamp. It is not enough for a deterministic token or a consumed set. Rejected.
- **Bind the answer to the challenge cookie.** Helpful for consistency, but the cookie is not a
  secret and can be copied with the answer. It does not replace signing or single-use. Retained
  only as an optional check.
- **Bind to client IP or User-Agent.** Breaks mobile, CGNAT and shared clients, is copyable, and
  turns a protection feature into an access-control rule. Rejected.
- **Bloom filter or approximate set.** Saves memory, but false positives deny legitimate first
  answers. Rejected for this purpose.
- **Strict shared store now.** Correct for cluster-wide single-use but adds a dependency and a new
  outage mode to a proxy whose architecture has no shared request state. Deferred as Option C.
- **Make the answer a POST or require a CSRF token.** A malicious client can send a POST and copy
  a token just as easily; this changes the browser flow without solving replay. Not a fix.
- **Count admission uses in the signed cookie.** The client can replay an earlier cookie or drop
  it, so a self-contained counter cannot enforce a limit. Rejected.

## Limitations

- Option A does not prevent sharing a valid admission and does not reject or report a replay. It
  only prevents one solve from creating more than one token.
- The memory estimates for Option B are arithmetic assumptions, not measurements; a real map's
  overhead, concurrency and eviction policy must be measured before choosing a cap.
- The draft does not decide whether old, unsigned challenges or old token formats remain
  acceptable during a rollout. A mixed fleet needs an explicit migration and retirement window.
- The HMAC tag protects challenge fields, but a compromised generator secret still allows an
  attacker to mint challenges and tokens. Key compromise is out of scope.
- The admission token keeps its existing 32-bit checksum unless the format is versioned. The
  draft mentions a stronger tag but does not make that change.
- No implementation, load test or end-to-end browser test was done. The conclusions about
  coordination and state are design reasoning checked against the code.

## Planned improvements

The open choices and the follow-up are in the `TODO.md` entry "restrict admissions per solved
challenge". Once the choices are ticked, this document becomes the basis for a decision document
and the implementation, and the unchecked options move to the implementation plan.
