# Time Attack Security & Verification — Design

**Date:** 2026-07-04
**Status:** Approved design, pre-implementation
**Companion to:** `2026-07-04-multiplayer-time-attack-design.md` (the "main spec")

## 1. Purpose

The main spec targets public rooms of hundreds of players. Public + competitive
+ anonymous is a griefing and cheating magnet, and security retrofitted into a
protocol is far more expensive than security reserved for. This spec designs
the full defense posture **now** so that v1 ships with the protocol fields,
hub checks, and storage interfaces in place, and later phases activate
enforcement without protocol breaks.

Design principle: **v1 reserves everything, builds the cheap half, and labels
the gap honestly** (casual rooms are explicitly "unverified times" until the
verifier exists — main spec §2).

## 2. Threat model

| Threat | Vector | Primary defense |
|---|---|---|
| Fabricated time | Modified client sends arbitrary `AttemptFinish` | Replay verification (§6) |
| Physics-hacked client | Altered engine plays "legitimately" faster | Replay verification — replay diverges |
| Slow-motion / assisted play | Client runs the sim below real speed; inputs are humanly impossible but replay-perfect | **Live pacing validation** (§7.1) — replay verification alone cannot catch this |
| Ghost-stream spoofing | Fake positions/teleporting ghosts for visual griefing | Hub sanity checks (§7.2) + replay position cross-check (§6.5) |
| Malformed packets | Crash/hang the hub or other clients via parser abuse | Strict framing, schema validation, fuzzing (§7.3, §10) |
| Resource exhaustion | Oversized messages, message floods, connection floods | Pre-parse rate/byte caps, connection caps, timeouts (§7.3, §8) |
| DDoS | Volumetric floods, join floods, fake-room pollution of the browser | Infra posture + PoW attack mode + authenticated room registry (§8) |
| Impersonation | Griefer claims an established player's name | Keypair identity (§3) |
| Sybil / ban evasion | Regenerate identity after sanction | Trust ladder — new identities are cheap but worthless (§4) |
| Chat/name griefing | Spam, abusive names | Rate limits, name policy, sanctions (§9) |
| Storage-fill via identity spam | Mass keypair creation to bloat the identity store | Write-on-merit persistence + ephemeral NEW cache + GC (§5.1), creation PoW (§3) |

## 3. Identity: pseudonymous keypairs

- Client generates an **Ed25519 keypair** on first run (JDK built-in EdDSA),
  stored under the save directory. The public-key fingerprint is the player's
  stable identity.
- Connect handshake: the **room authority** (master for brokered/relay
  connections, the player-host for direct connect — same authority that issues
  session tokens, §7.3) sends a nonce in its `Welcome`; the client proves
  identity by returning `sign(nonce ‖ serverId)`. `serverId` is the
  authority's **own keypair fingerprint** — masters and player-hosts hold
  identities too — carried in `Welcome` alongside the nonce. Binding the
  signature to the authority's identity prevents replaying a handshake
  captured on one server against another, and identifies the authority to the
  client for free. No passwords, no accounts, no PII.
- **Identity creation PoW stamp:** a new identity includes a one-time
  proof-of-work over its own pubkey (difficulty a master-side config; target
  a few seconds of client compute). Unnoticeable for a real player creating
  one key ever; rate-limits bulk identity farming for flood attacks.
- Identity is deliberately *not* prevention against regeneration — it is the
  substrate that makes bans stick, names unforgeable, and trust accruable.
  Sybil economics are handled by the trust ladder (§4).
- Future escalation path: OAuth/account binding can be layered **on top of**
  keypairs (an account attests to a key); the keypair remains the wire-level
  identity, so no protocol change.

## 4. Trust ladder

Trust is a server-side attestation about an identity. It makes ban evasion
expensive and visible instead of free and invisible: regenerating a key always
works, but lands the attacker at zero trust.

**Tiers** (thresholds are master-server config, not protocol — tunable without
client changes):

| Tier | Entry condition (defaults) | Effects |
|---|---|---|
| NEW | fresh identity | "new player" badge; cannot enter verified rooms; cannot create rooms; tight rate limits; chat delayed (read-only first N minutes per room) |
| ESTABLISHED | ≥ 48 h wall-clock age **and** ≥ 10 clean completed rounds | normal limits; can create casual rooms |
| TRUSTED | ≥ 14 days **and** ≥ 50 clean rounds, no sanctions | can enter/create verified rooms; eligible for future ranked play |
| SANCTIONED | ban/timeout verdict | rejected at handshake (permanent) or per-scope restricted (timeout) |

- Accrual requires **wall-clock time + participation**, so trust cannot be
  farmed quickly in bulk. Demotion is instant on a cheat verdict (§6) or
  moderation action, and destroys accrued standing — that asymmetry is the
  entire Sybil defense: burning a trusted identity costs days; issuing the
  ban costs one click.
- "Clean round" = a completed round with no hub violations (§7) and, once the
  verifier exists, no failed verification.

## 5. Persistence

- **All trust-ladder and sanction state lives on the master server** in an
  embedded **SQLite** database (WAL mode, single file), behind an
  `IdentityStore` interface. Client-side storage holds only the keypair.

### 5.1 Write-on-merit: identity spam cannot fill the store

Presenting a valid keypair does **not** create a database row. NEW identities
live only in a **bounded in-memory cache** (LRU, TTL, hard size cap) holding
first-seen time and session counters. An identity is persisted to SQLite only
on its first *durable* event: a completed round, a display-name claim, or a
sanction. Consequences:

- Mass key generation fills a fixed-size cache and evicts other spam keys,
  never touching disk. Combined with the creation PoW (§3) and per-IP creation
  rate limits, bulk identity creation is compute-expensive and storage-free.
- Nothing legitimate is lost: trust accrual requires observed participation
  anyway (§4). Wall-clock age is measured from *server-observed* first-seen —
  and age alone never grants trust (an attacker "aging" keys offline gains
  nothing; clean rounds are the gating resource, and playing rounds is
  rate-limited per identity and per IP).
- Cache eviction of a NEW identity merely resets its first-seen clock —
  exactly the cost profile we want for throwaway keys, imperceptible for a
  real player who plays a round within the TTL.
- **GC bounds the persisted set too:** persisted identities that never pass
  NEW tier and go inactive for N days (config) are purged — recreating one
  loses nothing of value. Sanctions are retained for their full term
  regardless of identity GC, keyed by fingerprint, so a banned key stays
  banned even if its identity row would otherwise age out.
- Schema: `identities` (pubkey fingerprint PK, created/first-seen/last-seen,
  display-name claim, tier, clean-round count), `sanctions` (identity, type,
  reason, issuer, issued-at, expiry), `verdicts` (identity, attempt ref,
  input-recording hash, result, verifier signature, timestamp).
- Verdicts are **signed by the verifier** (§6), so *verifier-attributed*
  findings are unforgeable — a compromised master cannot fabricate a cheat
  verdict. This deliberately does **not** extend to master-issued moderation:
  sanctions and demotions the master applies itself are trusted-master
  operations, mitigated by an append-only audit log of all moderation events
  (actor, action, reason, timestamp). Defending against a fully compromised
  master is out of scope (§12) — the signing boundary just keeps "the
  verifier proved cheating" and "an operator decided" distinguishable and
  separately auditable.
- Ops: the DB is small (KBs per identity); nightly file copy is the backup
  story. A federated or multi-master future graduates to Postgres behind the
  same `IdentityStore` interface — mechanical swap, designed for now, not
  built.
- Privacy by construction: fingerprints, display names, timestamps. No email,
  no PII. IP addresses appear only in short-TTL in-memory rate-limit caches
  and are never written to the persistent store.

## 6. Replay verification service

### 6.1 Why a separate service

Verification replays a run deterministically, which requires the engine and an
operator-supplied ROM. The master is deliberately engine-free and ROM-free
(main spec §6.2), so verification is a separate worker process:
**`openggf-verifier`** — headless engine + ROM, same artifact, tools-style
main class.

### 6.2 Mechanics

- Clients always record the input stream of every attempt (cheap: a few KB per
  run). `AttemptFinish` carries the claimed frame count, the
  **input-recording hash**, and the **ghost-stream hash** from day 1; the
  recording itself is uploaded on demand.
- **Input-only attempt recording mode is an explicit contract.** An attempt
  recording contains exactly: per-frame input masks **from the spawn frame
  onward — idle frames recorded as empty masks**, the track's canonical
  start-state descriptor, and the determinism fingerprint — nothing else.
  Recording from spawn (not first input) is what makes the claim verifiable:
  the verifier replays from the canonical start state, derives
  `firstInputFrame` and `finishFrame` itself, and checks the claimed time
  `finishFrame − firstInputFrame` — pre-input idling legitimately advances
  world state and is fully covered by the recorded masks.
  The existing trace/recording stack's rich per-frame sidecar capture
  (desync-lite physics frames and aux fields) is **excluded from the attempt
  path** — at hundreds of retries per round window it would silently turn
  "a few KB" into megabytes. Sidecar capture stays reserved for the verifier
  (which may emit it while replaying, for divergence diagnostics) and for
  explicit debug tooling.
- Verifier workers pull jobs from a master-side queue over HTTP(S): (track
  descriptor, character, physics/determinism fingerprint, input recording,
  claimed frames, ghost hash). A job replays from the track's canonical start
  state — the same bootstrap-contract discipline the trace replay suite
  already proves out.
- Headless replay runs far faster than real time (as the trace suite does), so
  a one-minute run verifies in seconds. Verdict = signed statement: inputs
  reproduce the claimed frame count and the finish trigger actually fired.
- **Determinism fingerprint:** `Hello` and `AttemptFinish` carry a physics
  build fingerprint (engine version + physics-relevant constant hash + ROM
  checksum). Verification jobs are **routed by fingerprint**: each verifier
  worker registers the fingerprints it supports (its build + the ROMs its
  operator supplies), and the master queues a job only to a matching worker.
  A fingerprint with no registered worker is unverifiable, not a failure —
  and a room can only carry the `verified` flag (§6.3) if a matching
  verifier is currently registered for the room's fingerprint.

### 6.3 Verified rooms flow

- Room descriptor carries a `verified` flag from day 1 (v1: always false,
  browser shows the badge state either way).
- In a verified room, a finish enters standings as **pending** and flips to
  verified when the verdict lands (seconds); podium waits for pending
  verdicts. TRUSTED tier required to enter (§4).
- Casual rooms stay client-reported; the verifier spot-checks top times
  post-hoc when capacity allows, and a failed spot-check still sanctions.

### 6.4 Recording custody & upload lifecycle

- **Client-side custody:** every attempt's input recording is captured locally
  and keyed by its hash. Recordings for finished attempts are retained for the
  round duration plus a grace window; the recording behind a personal best is
  kept alongside its `.ggfghost` file. The `.ggfghost` header carries the
  input-recording hash, binding a ghost to the recording that produced it.
- **Upload on demand:** when a time is selected for verification (verified-room
  finish, or post-hoc spot-check), the hub sends a `RecordingRequest`
  (attempt ref + expected hash) over the control channel. The client uploads
  the recording **out-of-band over HTTPS** to a master endpoint
  (`PUT /recordings/{hash}`) — never over the game WebSocket, so a blob can't
  head-of-line-block live ghost streams. Missing the upload deadline (config,
  ~minutes) voids the time and records a violation.
- **Master retention:** uploaded recordings are held for the verification job
  plus a bounded post-verdict window (config, default days), then deleted.
  Verdicts — hashes plus verifier signatures — are retained per §5; the hash
  chain means a verdict stays auditable after the blob is gone.

### 6.5 What replay verification covers — and doesn't

Covers: fabricated times, physics hacks, and (via position cross-check of the
submitted ghost-stream hash against replay output) ghost spoofing bundled with
a real claim. Does **not** cover TAS-style inputs played below real speed —
inputs crafted slowly replay perfectly. That gap is closed by live pacing
validation (§7.1), which is why pacing must exist at the hub from v1.

### 6.6 Empirical cost basis (measured, S1+S2)

The performance assumptions above are no longer estimates. Two reproducible,
non-gating JUnit benchmarks under `src/test/java/com/openggf/tests/trace/`
measure the actual headless-replay cost of the verifier's hot loop against the
real trace corpus (the same headless engine + ROM path the verifier uses):

- `s1/TestVerifierCostBenchmark` — pure input-replay cost (no per-frame trace
  validation) and per-level memory footprint.
- `TestVerifierPoolBenchmark` — the pooled warm-level architecture: decode every
  level once, hold them all resident, then service N random verification jobs
  warm (fingerprint/level routing modelled by grouping).

Headline measurements (single core, Opus dev box, S1 complete-run + S2
level-select traces; S3K deferred until its traces are green):

- **Per-run CPU:** a full multi-minute run replays in ~0.1–2 s. Light zones hit
  ~37k–76k frames/sec (~600–1300× real time); the pure input replay is ~10×
  cheaper than a validating trace-replay because it drops the per-frame field
  diff. So a verified-room finish can be verified inline in well under a second.
- **Memory:** all 36 S1+S2 levels held resident simultaneously = **~64 MB**
  (~1.8 MB/level). A worker can keep every level of its fingerprint warm; size
  the pool for throughput, not memory.
- **Warm reuse:** decode-once is ~125–140 ms/level; subsequent jobs on a warm
  level skip the ROM decode entirely. For full runs the decode is a small
  fraction of replay, so warm-holding is a startup/latency win more than a
  throughput one — the throughput lever is parallel workers.

**Prerequisite fix (shipped):** the headless frame path (`LevelFrameStep`) was
missing the per-frame palette-write drain that `GameLoop` owns, so palette-cycling
zones (MTZ/CPZ/CNZ/OOZ/WFZ) were quadratic per run and degraded ~11× across
reused warm sessions. Draining in `LevelFrameStep` fixed it (~6× faster on those
zones, flat across reuse); without it the pooled-worker model is not viable.
See the CHANGELOG entry and these benchmarks before revisiting verifier sizing
(phase-5 plan §Architecture).

## 7. Live hub defenses (built in v1)

These run in `GhostHub`/master regardless of room type and cost almost nothing.

### 7.1 Pacing validation

Attempts happen live inside a wall-clock round window. The hub tracks each
attempt's ghost `frameIndex` against wall time: sustained progression
meaningfully below ~60 frames/sec (tolerance for hiccups and catch-up bursts,
config-tunable) flags the attempt; a flagged attempt's finish is rejected for
standings and recorded as a violation. This is the anti-slow-motion layer —
the one cheat replay verification cannot see.

### 7.2 Ghost-stream sanity

Per upstream frame batch: coordinates within level bounds for the track;
per-frame position delta below a hard cap (max legitimate speed + margin);
`frameIndex` strictly monotonic per attempt; frame rate ≤ 60/sec sustained
(+ bounded burst). Violations: drop the batch → kick on repeat → sanction
record. Implemented as a `GhostStreamValidator` inside `GhostHub`.

**Where bounds and caps come from — the ROM-free constraint.** `GhostHub`
must run on the engine-free, ROM-free master, so it cannot load levels and
must not trust host-supplied limits. Track checks consume a
**`TrackValidationProfile`** (track id → level width/height, max plausible
per-frame speed, frame-rate cap): pure numeric metadata of the same kind as
the ROM offsets already checked into the constants files — no asset bytes.
The speed cap is **intentionally a conservative global ceiling per track**
(maximum across all characters, rules profiles, and legitimate boosts —
springs, speed shoes — plus margin), not keyed by character: the validator is
a gross-spoofing filter and must never false-positive a legitimate run;
character-precise judgment belongs to replay verification (§6).
An engine-side build tool exports the profile table for the supported track
list; the table is a checked-in resource bundled with the master. The
player-host path instead builds the profile live from its loaded level.
`GhostStreamValidator` takes a `TrackValidationProfileSource` — bundled table
on the master, live level on a host. A track with no profile **explicitly
degrades** to the track-independent checks only (monotonicity, frame-rate
caps, a global speed ceiling from the repo's physics constants).

### 7.3 Protocol hygiene

- Strict length-prefixed framing with hard per-type size caps; schema
  validation; unknown/undecodable message → disconnect. No parser ever sees
  unbounded input.
- Pre-parse per-connection and per-IP message/byte rate caps.
- Handshake timeout, idle timeout, max concurrent connections per IP.
- **TLS (`wss://`) required on the master** — integrity, privacy, and
  front-proxy compatibility. Player-hosted direct connect may be plaintext
  (LAN/VPN context).
- Session tokens are issued by the **room authority** — whoever admits you:
  the master for brokered/relay rooms, the player-host for direct-connect
  rooms. The envelope carries the token from day 1 in both cases. Phase 2
  host-issued tokens are opaque room-scoped random values (cheap membership
  validation only); identity-bound token semantics arrive with the master in
  phase 3. Same field, upgraded meaning — no protocol break.

## 8. DDoS posture

- TCP/WebSocket only → no UDP reflection/amplification class at all.
- Master sits comfortably behind a standard proxy/CDN (Cloudflare et al.)
  because everything is `wss` on 443 — an infra decision the design keeps
  compatible, not a component we build.
- Browser/list queries are paginated and rate-limited (a list response is the
  largest reply the master sends; cap it).
- **Fake-room pollution:** rooms exist only while their host holds a live,
  identity-authenticated connection; per-identity and per-IP room caps; NEW
  tier cannot create rooms at all (§4).
- **Attack mode:** a master toggle that escalates admission cost under load —
  join/connect requires a fresh PoW stamp (difficulty scales with load).
  Legitimate clients solve it in under a second; flood economics break.
- Room join floods: per-identity join rate limits; NEW-tier joins queue behind
  established players when a room is under pressure.

## 9. Griefing & moderation

- Impersonation is solved structurally by identity (§3): display names are
  claims bound to a fingerprint; the browser/standings disambiguate duplicate
  names by badge/fingerprint suffix.
- Chat: server-side rate limit (main spec), NEW-tier read-only window,
  per-room host mute/kick; kicks by hosts are room-scoped, sanctions are
  master-scoped and require the moderation path.
- Sanction issuance: v1 = operator tooling on the master (CLI/admin endpoint);
  community moderation (reports, host flags feeding review) is future work.

## 10. Testing

- **Protocol fuzzing** in CI: frame-level fuzzer against the decoder (random
  and mutation-based), asserting no crash/hang/allocation blowup.
- **Adversarial client harness:** `GhostLoadTestTool` (main spec §10) gains
  malicious modes — teleporting ghosts, pacing violations, oversized frames,
  message floods, handshake abandonment — asserting the hub survives, applies
  the violation ladder, and healthy clients are unaffected.
- **Verifier integration:** replay-verify recorded legitimate runs (must pass)
  and doctored recordings — edited inputs, altered claimed frames, mismatched
  ghost hash (must fail). The existing trace corpus doubles as fixtures.
- **Trust ladder unit tests:** tier transitions, demotion-on-verdict,
  config-threshold boundaries, sanction expiry.

## 11. What lands when

| Phase (main spec §11) | Security content |
|---|---|
| 1 — Solo | Keypair generation + storage (identity exists before networking); input recording of every attempt |
| 2 — Direct connect | Protocol hygiene (§7.3) in full; `GhostStreamValidator` incl. pacing; all reserved fields live in messages (identity handshake, hashes, token, `verified` flag) |
| 3 — Master server | TLS; `IdentityStore` (SQLite) + trust ladder enforcement; sanctions; attack-mode PoW; fake-room defenses; fuzzing + adversarial harness in CI |
| 4+ — Post-v1 | `openggf-verifier` workers; verified rooms flip on; spot-checking; moderation tooling beyond operator CLI |

The rule embedded in this table: **every protocol field, storage interface,
and hub check exists by phase 2–3; only the verifier service and its
enforcement flip are deferred.** Nothing post-v1 requires a protocol break.

## 12. Out of scope

- Community moderation workflows (reports, reviewer queues).
- Defending against a fully compromised master (it terminates TLS, brokers
  rooms, and applies sanctions; §5's signing boundary limits what it can
  *forge*, not what it can *do*). Operators who cannot trust their master
  host have no room hosting either.
- Account/OAuth binding on top of keypairs.
- Anti-cheat beyond replay + pacing (input-entropy heuristics, anomaly
  detection) — possible later analytics over stored recordings, no protocol
  impact.
- Legal/DMCA posture of operator-run verifiers is the operator's ROM, same as
  any user of the engine; no ROM bytes ever transit the network (main spec §7).
