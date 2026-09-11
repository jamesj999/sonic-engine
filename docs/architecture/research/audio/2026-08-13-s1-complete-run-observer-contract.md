# Sonic 1 complete-run observer frontier contract

Date: 2026-08-13

This note consolidates the source and architecture audits that moved the Sonic
1 complete-run observer through rows 185877 and 216390, then exposed the
managed row-62 correlation defect. It records the source-backed contract at
conductor commit `d8c92d6b6f180dc7ef2d17be0e2e7a17bb39e2d2`; it is not a
terminal-parity or paired-installation result.

## Source model

Two independent CPUs explain the observed overlaps:

- M68K `QueueSound1`, `QueueSound2`, and `QueueSound3` write request bytes and
  return. They do not enter the sound driver or create an M68K driver service
  (`docs/s1disasm/_inc/Queue Sound Routines.asm:17-32`).
- Z80 kind 6 represents the real `zCheckForSamples` wait at `$003A`, which
  polls `zDAC_Sample` until a valid negative sample ID appears
  (`docs/s1disasm/sound/z80.asm:71-82`). An M68K queue write can therefore
  occur while kind 6 is a sole root.
- M68K `UpdateMusic` requests the Z80 bus, may restart the Z80 and retry, then
  owns the stopped bus from `.driverinput` through the driver body and its
  exit (`docs/s1disasm/s1.sounddriver.asm:146-273`). Kind 6 can consequently
  begin beneath an already-open kind-4 `UpdateMusic` service and remain the
  physical native top across any reachable internal driver hook until an
  M68K exit or the later Z80 `$0077` transition.
- Nested M68K driver calls change A7. For example, the call from `$71BB2` to
  `CycleSoundQueue` pushes return PC `$71BBC`; at callback `$71F02`, A7 is
  four bytes below the enclosing `UpdateMusic` entry value. Deeper YM and PSG
  calls add further real return frames.

These are runtime-source relationships, not properties inferred from a trace
row. The configuration may enumerate exact reviewed hooks and kinds, but
shared selection and correlation must not branch on the movie, row, route, or
game name.

## Supersession chain

| Frontier | Preserved observation | Source-backed conclusion | Resulting contract |
|---|---|---|---|
| Row 185877, `$1394` | `first_fault=5:2:1394:6:1:0:4` with sole-root kind 6 | `QueueSound2` is a non-mutating M68K request observation concurrent with the Z80 wait, not a leaked kind-4 lifetime | The three queue PCs admit exact action-7 owners `{0,2,3,6}`. Kind 6 remains observation-only: no begin permission, `ALLOW_CHILDREN`, reservation, or synthetic service. Landed in `132029d90`. |
| Row 216390, `$71B82` | `first_fault=4:2:71b82:6:2:0:4` with root kind 4 and direct child kind 6 | `.driverinput` continues the already-open `UpdateMusic` invocation; no deferred reservation exists | Pair action 7 and action 12 for expected kind 6 at the exact PC/opcode. A pending sole-root reservation selects action 12; no pending exact `kind4 -> kind6` topology selects action 7. Root kind 6 without a reservation remains rejected by managed ownership. Landed in `df457fc0f`. |
| Row 216390, `$71BB2` | `first_fault=5:2:71bb2:6:2:0:4` immediately after `$71B82` succeeds | The overlap covers the complete stopped-bus `UpdateMusic` interval, not one more PC | The configured typed-async class is exactly `{2,3,6}`. All 78 ordinary internal hooks get exact action-7 alternatives for that class plus direct kind 4; all four action-8 and two action-9 exits admit direct-parent close/promotion for that class. Eligibility is `TYPED_ASYNC`, not the accidental `TYPED_ASYNC + ALLOW_CHILDREN` conjunction. Landed in `e7d244f5b`. |
| Managed row 62, `$71F02` | Root kind-4 service enters with A7 `$00FFFDAE`; the nested callback has A7 `$00FFFDAA` after a real `jsr` | An internal callback's instantaneous A7 is not generally its service-entry A7 | Native ABI v4 carries contemporaneous M68K A7 only on action-7 observation markers. Managed correlation binds the exact native owner token and exact callback A7, while begin/retry/deferred identity keeps its service-entry A7 contract. Landed in `d8c92d6b6`. |

Each later finding supersedes the *scope* of the earlier candidate, not its
source evidence. In particular, the `$71BB2` audit replaces a one-PC extension
with the closed 78-hook/six-exit family, and the ABI-v4 decision replaces the
row-62 audit's provisional return-chain whitelist. The queue family and the
strict `$71B82` pending-versus-observation pair remain separate boundaries.

## Current ABI-v4 contract

### Native event and compatibility rules

- The exported observer API reports ABI v4. The event remains 32 bytes.
- For ABI v4 only, an M68K action-7 marker with event kind 10 and value 3 has
  `payload_length = 4`; `payload[0..3]` contain the full contemporaneous A7 in
  little-endian order and `payload[4..7]` are zero.
- The A7 sample is taken at the same instruction boundary as the managed
  execute callback, after that callback returns and before opcode execution.
  Sampling does not mutate emulated state.
- ABI v1-v3 action-7 output remains byte-for-byte payload-free. Every marker
  other than the ABI-v4 action-7/value-3 form also retains zero payload length
  and bytes.
- The existing S1 raw-v1 envelope already serializes `native_abi`,
  `payload_length`, and `payload`, so ABI 4 discriminates this meaning without
  adding or removing raw fields. S2 and S3K stay on ABI v2 and retain their
  rule that non-snapshot events carry no payload.

### Native topology rules

- `queue_expected_kinds` is exactly `{0,2,3,6}` at each of the three exact
  QueueSound PCs. These action-7 hooks observe the current owner and change no
  service, stack, reservation, token, generation, or chip state.
- `typed_async_kinds` is exactly `{2,3,6}`. Kind 6 remains
  `TYPED_ASYNC | ALLOW_CONTINUATION` and is not granted `ALLOW_CHILDREN`.
- At `$71B82`, action 12 consumes only a matching pending reservation from the
  sole-root owner topology. The paired action 7 is the no-pending,
  configuration-derived direct-child observation route.
- Each of the 78 internal driver PCs has one action-7 alternative for direct
  kind 4 and one for each configured typed-async top. Runtime typed-async
  execution requires exact depth-two kind-4-parent/typed-child topology and
  exact parent token/depth; sole-root, deeper, reversed, or pending-deferred
  shapes fail closed.
- The six service exits are four action-8 closes (`$71C4C`, `$71FD0`,
  `$721B8`, `$72B9C`) and two action-9 conditional closes (`$72C24`,
  `$72E04`). When typed async remains top, an actual close ends the direct
  kind-4 parent and promotes the existing child without changing its token.
  An action-9 allowed internal return emits only KEEP and changes neither
  entry. This close eligibility is independent of `ALLOW_CHILDREN` because it
  creates no child.
- `$71B4C` retry/reservation behavior and action-11/action-12 immutable
  reservation identity remain unchanged by the internal family.

### Managed ownership rules

- A direct kind-4 internal observation selects its `service_token` as owner.
  A kind-2, kind-3, or kind-6 internal observation selects its
  `parent_token`. The selected token must be nonzero and name the exact open
  managed kind-4 occurrence.
- The ABI-v4 native A7 payload must equal the independently observed managed
  callback A7 at that instruction. It is deliberately not compared with the
  owner's service-entry A7.
- The same owner-token plus instantaneous-A7 rule applies to published and
  prepublication/boundary correlation. Keeping prepublication callbacks is
  necessary when an open service crosses the publication cutoff.
- Service begin, retry, deferred reserve/consume, and conditional-return
  identity retain their stricter entry-A7 or return-PC contracts. Action 11
  and action 12 do not acquire the new payload.
- Any payload-shape, token, topology, or A7 mismatch faults the transactional
  session; no partial row or managed-state update is published.

The instantaneous A7 payload was chosen over a return-address whitelist. An
exact whitelist solution would need a per-hook call-path automaton, bounded
contemporaneous stack copies, and exhaustive coverage of every legal nested
path. A global whitelist, A7 inequality, measured maximum depth, or a
row/PC-specific exception would accept forged or trace-fitted paths.

## Preserved evidence

The ignored audits were hashed before consolidation:

| Audit | SHA-256 |
|---|---|
| `target/audio-parity/native/row185877-source-audit.md` | `e182fb4333449db5ffe925a027c7bd05d22a0ca107a3967a28476607d992a737` |
| `target/audio-parity/native/row185877-arch-review.md` | `d4e4e1c9de51558b47d4e3cd4472c3b6245d3c322b0d1f4d176340af0e452915` |
| `target/audio-parity/native/row216390-source-audit.md` | `020bbd03c259acbc0eca1eb7f5dc4044c01fac9d3c2d28d2728bfc3eac5d0554` |
| `target/audio-parity/native/row216390-arch-review.md` | `67b5d8eeaccc86ca0ae04d1c5853fa368e9c5ede524e817c33bbb8cf565e321d` |
| `target/audio-parity/native/row216390-71bb2-source-audit.md` | `30dd72e4e5830d9405b39d628604d5aabe85372692e25fb0a21460fe3c8df6d2` |
| `target/audio-parity/native/row216390-71bb2-arch-review.md` | `7fd7a8fa8234babdbcad660a1e470a3da00d1c8939890afd4fa3f11ba21524f7` |
| `target/audio-parity/native/row62-managed-audit.md` | `b599fb2a126002c6a326edf34dbf857db77a4ef6fe73e52a71a89e3d857d0b37` |
| `target/audio-parity/native/row62-internal-a7-contract.md` | `92ed41bfad1c5c3a7e89f3671d725c95f0166df097ea2413e346163c7e3847f5` |

The source audits name these underlying reproductions and inputs:

| Evidence | SHA-256 |
|---|---|
| Row-185877 installation-A log | `b7e5c6493aaa2ad2dc9db4248d1b96cfeab19edc48888b2efd260093977dca0d` |
| Row-216390 `$71B82` installation-A log | `6b27ef801679e41ea828e8e5bec207793bf890ac2c25a646d230a302a8ffe0a0` |
| Row-216390 `$71BB2` installation-A log | `45afef8fa3792c036459cf5b26ee6ed425fc98a4b4f53ddf23512f91f6170345` |
| Row-62 typed-async installation-A log | `5e746a6ec4b5bd84cc3eb76397f3386347602556e0001e430deceac589402bdf` |
| Row-62 reviewed HEAD diff | `d506ae9487236b16c9ddfc9e53a420d536e38107999d8a4c194beae7ab93485f` |
| Sonic 1 World REV01 ROM used by the row-62 audit | `1b7f6635bd713f37f3c2f44f302b872c2e3c5f56e63637918dad4637146900fd` |
| Complete-with-emeralds BK2 | `f2e817936d07b2b1f2b80d61451f174189509a2817da2b2349ce0e19b8a5567b` |

The first three real logs are intentionally RED frontier evidence. The row-62
log is a bounded reproduction against a then-current candidate. At the time of
this consolidation, the ABI-v4 implementation and its focused synthetic tests
are committed, but the final paired native installation reproduction and
complete-run terminal result are not recorded here. Neither an earlier ABI-v3
terminal-looking experiment nor passage of one named row establishes current
`Complete(225101)` acceptance.
