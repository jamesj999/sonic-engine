# Sonic 2 C0 request-consequences design

Date: 2026-09-02
Status: approved direction; written specification awaiting human text review
Scope: Sonic 2 Task 8A Tranche C0 and its ordering relative to Tranches C and D

## Decision

OpenGGF will complete the Sonic 2 request path on the existing session-owned SMPS
driver and physical device. It will not resume the broader session-device migration.
C0 therefore supersedes, for the Sonic 2 Task 8A path, the older roadmap prerequisite
that required Tasks 1-8 of the session-device subplan before any Task 8 comparison. The
roadmap must record this ruling before implementation planning; it does not silently
change the later S3K gate.
C0 is split into three independently reviewed tranches:

1. **C0-A — transactional request consequences.** Make request transfer, queue
   consumption, admission, music/SFX transition consequences, command publication,
   diagnostics, and rollback one transaction.
2. **C0-B — pause and resume.** Model command `$FE`, command `$FF`, `zPaused`,
   the exact pause silence program, paused-frame gating, and the active-track resume
   walk.
3. **C0-C — SFX header key displacement.** Preserve the signed SFX-header value in
   `zTrack.Transpose`, including shipped `FixMusicAndSFXDataBugs = fixBugs = 0`
   data and eight-bit wrapping.

After C0-A, Tranche C production wiring may reach Tranche D and run the first
authenticated comparison. That comparison is an early measurement, not a frontier
claim. C0-B and C0-C must each land and pass review before a result may move the Sonic
2 frontier or claim general request-path parity.

The phrase "E0 transpose" is prohibited in this work. Coordination flag E0 is
`cfPanningAMSFMS`; the value in scope is the SFX header key displacement stored in
`zTrack.Transpose`, which coordination flag E9 can subsequently modify.

## Requirements

### Goals

- Preserve the source order from the 68K mailbox bridge through the Z80 queue and
  accepted driver consequence.
- Prevent any request consequence, command, parity event, or diagnostic from becoming
  externally visible before the enclosing presentation transaction commits.
- Reproduce shipped Sonic 2 music replacement, SFX admission, one-up, continuous
  spin-dash, pause, resume, and header key-displacement behavior from ROM-owned state.
- Keep all reference request evidence comparison-only. It must not call a gameplay or
  audio behavior owner or decide what the engine does.
- Preserve the existing single `SmpsDriverSession` and single physical YM2612/PSG pair.
- Keep S1 music and SFX matches inviolable and retain deterministic rewind.

### Non-goals

- No full logical/physical SMPS ownership migration.
- No changes to Nuked-OPN2, the clean-room PSG core, mixing, or PCM comparison scope.
- No inference of requests from chip writes, track state, audible output, or fixture
  position.
- No game-name, zone, route, frame, or known-BK2 carve-out.
- No publication of a request fixture, production authority, or frontier movement from
  implementation-only evidence.
- No claim that early Tranche D measurement covers `$FE`, `$FF`, or all key-displacement
  cases merely because the bounded movie does not exercise them.

### Constraints

- The shipped `fixBugs = 0` branches are authoritative, including behavior that the
  disassembly labels as buggy.
- Preparation and rollback are physically silent and observer-silent.
- A failure before the transaction seal restores logical state and removes only the
  commands staged by that attempt.
- Publication occurs only after every state owner has prepared successfully and the
  transaction has sealed.
- The existing producer/readers remain fail-closed and production-unbound until their
  separate authority gates pass.

### Acceptance conditions

- Seeded failures before the transaction seal leave session state, registry state, queue
  state, command visibility, timeline, parity, and diagnostics in their exact pre-attempt
  state. Prepared commit operations after the last fallible prepare are non-throwing.
- A post-seal diagnostic-observer failure cannot roll back, retry, or duplicate the
  committed request; it is contained and reported after the production consequence and
  its durable internal evidence have committed exactly once.
- A successful music request applies the shipped stop-SFX-before-load behavior and the
  shipped one-up save/priority order once, with no duplicate consequence after retry.
- A successful SFX request performs ROM priority/admission and continuous spin-dash
  bookkeeping once, with no publication for a rejected request.
- First `$FE` emits exactly 202 ordered pause-program/control writes; repeated paused
  frames emit none of those writes and do not service logical tracks; `$FF` restores only
  active, non-overridden DAC/FM track slots and ordinary service resumes on the next
  frame.
- SFX tracks receive their signed header key displacement in `zTrack.Transpose`; later E9
  changes use eight-bit arithmetic. Spin Dash Release retains shipped `$90`, not `$10`.
- The early Tranche D report explicitly says `MEASUREMENT_ONLY` and leaves the recorded
  frontier unchanged. Frontier eligibility requires C0-A, C0-B, and C0-C reviews plus the
  existing authenticated-authority gates.

## Exploration synthesis

Two read-only architecture explorations agreed on the following points:

- The current session already owns one logical SMPS driver and one physical device, so
  C0 needs a transaction extension, not another ownership migration.
- The current forward request service publishes `AudioCommand` consequences into
  `AudioManager` before the surrounding presentation commit. Resolver, timeline, and
  parity mutations can therefore survive rollback or duplicate on retry.
- Pause belongs to a game-owned Sonic 2 policy executed by the existing session. The
  mailbox/queue model owns `StopMusic`; the session owns retained pause state and the
  physical write program.
- SFX header key displacement is per admitted track data. It must be copied into each
  prepared SFX track snapshot rather than inferred later from a sound id or event.
- Direct physical snapshot restore must remain silent. Pause/resume is a source event,
  not a snapshot-restore side effect.

The explorations considered different slices of the same boundary and did not conflict.
The only unresolved source claim was whether DAC service continues during a paused
frame; the source verification below closes it.

## Source contract

### Mailbox and queue

The 68K `sndDriverInput` bridge moves the four source slots into Z80-owned storage. The
shipped index-3 path reads `Music1` and writes the low byte of `VoiceTblPtr`; it is not an
unused second-SFX slot (`docs/s2disasm/s2.asm:1270-1332`). The Z80 `zCycleQueue` scans
three queue bytes, clears each as it reads it, compares SFX priority, and may reduce
multiple transfers to one queued consequence (`docs/s2disasm/s2.sounddriver.asm:1496-1535`).
Transfer, scan, acceptance, and playback are therefore distinct observations.

### Music and SFX consequences

On the shipped `FixDriverBugs = 0` path, `zPlayMusic` stops sound effects before loading
new music (`docs/s2disasm/s2.sounddriver.asm:1667-1673`). The one-up path saves the driver
region before clearing `SFXPriorityVal`, so the old priority is restored later; the engine
must retain this shipped ordering (`docs/s2disasm/s2.sounddriver.asm:1675-1724`). SFX is
suppressed while one-up or fade-in state is active, and the priority owner is updated by
the queue/admission routines rather than by the reference stream
(`docs/s2disasm/s2.sounddriver.asm:2116-2120,2334-2337`). Continuous spin-dash selection
and counters remain driver-owned (`docs/s2disasm/s2.sounddriver.asm:2152-2175`).

### Pause, resume, and DAC service

At each Z80 VBlank, a nonzero `StopMusic` bypasses `zUpdateEverything`, calls
`zPauseMusic`, and jumps directly to `zUpdateDAC`
(`docs/s2disasm/s2.sounddriver.asm:393-407`). The first pause sets `zPaused`, calls
`zFMSilenceAll`, then `zPSGSilenceAll`; later paused frames return from `zPauseMusic`
without repeating the silence program (`docs/s2disasm/s2.sounddriver.asm:1422-1430`).
The direct jump still executes the DAC update path: it bank-switches to DAC data and
either starts a queued sample or returns with the DAC timing loop primed for immediate
service (`docs/s2disasm/s2.sounddriver.asm:489-535`). Therefore the precise claim is:
**pause gates `zUpdateEverything`, but the interrupt continues through `zUpdateDAC`**.
It does not mean that pause invents a new sample or rewrites DAC enable state.

The exact first-pause program is:

- YM port 0 register `$28`: `$02,$06,$01,$05,$00,$04`;
- YM registers `$30..$8F`, ascending: port 0 `$FF`, then port 1 `$FF` for each
  register, totalling 192 register writes; and
- PSG `$9F,$BF,$DF,$FF`.

That is 202 writes derived from `zFMSilenceAll` plus `zPSGSilenceAll`
(`docs/s2disasm/s2.sounddriver.asm:1412-1418,2518-2540`), not a legacy compatibility
count. Independent DAC sample streaming may also write `$2A`; such data writes are not
part of the 202-write pause program.

Command `$FF` clears `StopMusic` and `zPaused`, then calls `zResumeTrack` for DAC plus six
music FM tracks followed by three SFX FM tracks
(`docs/s2disasm/s2.sounddriver.asm:1432-1462`). `zResumeTrack` skips stopped and
SFX-overridden tracks, restores panning/AMS/FMS and the current voice, and does not
advance notes (`docs/s2disasm/s2.sounddriver.asm:1468-1490`). The driver's comment states
that music is not updated until the next frame (`docs/s2disasm/s2.sounddriver.asm:1455-1460`).

### Header key displacement and `FixBugs = 0`

`zTrack.Transpose` is defined as transpose from coordination flag E9, and note paths add
that byte to the note (`docs/s2disasm/s2.sounddriver.asm:100,878,1178`). E0 dispatches to
`cfPanningAMSFMS` (`docs/s2disasm/s2.sounddriver.asm:2867-2873,3004-3021`). E9 dispatches
to the routine that adds its signed byte to the current `zTrack.Transpose` using eight-bit
arithmetic (`docs/s2disasm/s2.sounddriver.asm:2913-2921,3197-3202`).

The SFX header supplies the initial signed key displacement. Spin Dash Rev supplies `$FE`
(`docs/s2disasm/sound/sfx/E0 - Spin Dash Rev.asm:1-8`). Spin Dash Release supplies `$10`
only when `FixMusicAndSFXDataBugs` is enabled; the shipped `fixBugs = 0` branch supplies
`$90` (`docs/s2disasm/sound/sfx/BC - Spin Dash Release.asm:1-12` and
`docs/s2disasm/s2.asm:27,68`). The driver copies the SFX header's track pointer and key
offset into track RAM before applying the spin-dash addition
(`docs/s2disasm/s2.sounddriver.asm:2288-2307`). The existing `$90`-to-`$10` Java rewrite
must be removed. The implementation site must carry the project-required `FixBugs = 0`
comment naming the disabled fixed branch.

## Architecture

### C0-A transaction boundary

`AudioPresentationProducer` remains the coordinator. Its forward transaction will stage,
prepare, seal, and publish in this order:

1. Capture the presentation registry, `SmpsDriverSession`, Sonic 2 request service, and
   forward-command append position; open a transaction-private command reservation and
   outcome ledger.
2. Transfer the game-owned mailbox, run the Z80 queue cycle, and compute accepted request
   consequences without publishing them.
3. Resolve those consequences through `AudioPresentationCommandResolver` into the
   transaction-private reserved batch. Resolution and ROM-backed program preparation are
   fallible here, but the resolved batch is externally invisible: it has not reached the
   durable command queue, timeline, parity, lifecycle owner, or diagnostics.
4. Apply the reserved batch to the existing registry/session mutations and collect typed
   outcomes. Music transition policy performs stop-SFX-before-load, one-up save/priority
   ordering, and replacement state changes inside those mutations.
5. Apply the typed outcomes to the prepared Sonic 2 lifecycle mutation, then ask the
   registry, session, request service, command reservation, and durable timeline/parity
   append owners to prepare commit. This is the last fallible phase; each prepared
   participant guarantees that its commit operation cannot throw.
6. Non-throwingly commit the registry, session, lifecycle state, reserved command batch,
   and durable timeline/parity evidence, then seal one immutable request receipt. A caller
   cannot supply or toggle a production-correlation decision.
7. Publish only fallible, behavior-inert diagnostic callbacks from the sealed receipt.

An exception before sealing rolls every participant back and discards only this attempt's
private resolved batch. An exception during post-seal diagnostic publication is contained
and reported as an observer failure; it cannot reopen or replay the committed driver
request. Resolver output, lifecycle state, and durable command/timeline/parity evidence
are prepared state owners, not post-commit callbacks. The only fallible post-seal
publication is behavior-inert diagnostics. Diagnostic APIs must therefore be bounded and
non-reentrant, and tests must establish their failure policy before production wiring is
enabled.

The concrete names may follow local idiom, but the contracts are fixed:

```java
interface PreparedSonic2RequestConsequences {
    void prepareCommit();
    CommittedSonic2RequestReceipt commit();
    void rollback();
}

interface CommittedSonic2RequestReceipt {
    List<AudioCommand> commands();
    List<Sonic2RequestOutcome> outcomes();
}
```

Only typed outcomes from the transaction-private applied batch may advance the prepared
lifecycle owner. Only the committed receipt may reach diagnostic observers; durable
timeline/parity projection commits non-throwingly with the other prepared state. The
receipt contains consequences computed from production state; it never contains reference
values.

### C0-B pause state

The Sonic 2 mailbox/queue service recognizes `$FE` and `$FF` as semantic pause/resume
requests and stages them into the session transaction. Shared code does not switch on a
game id or raw command. The Sonic 2 policy owns the ordered write programs and the track
selection semantics.

`SmpsDriverSession` snapshots a small pause state: running/paused plus any prepared
transition. While paused, mailbox transfer still occurs, but queue cycle, request dispatch,
fade/tempo progression, music/SFX track service, and spin-dash counter progression do not.
The session executes the pause transition before normal activation/service. First `$FE`
emits 202 writes and performs no logical track service; subsequent paused frames emit no
pause writes. `$FF` emits the source-owned resume writes and performs no logical track
service in that frame. Queued work waits for the next ordinary frame.

The pause program must use ordinary physical writes. It must not call a generic silence
helper that adds `$2A`, `$2B`, or unrelated initialization writes. Resume must not key on
physical snapshot contents; it walks the logical Sonic 2 track state described above.

### C0-C header key displacement

The SFX program loader retains the signed header key displacement as ROM-backed program
data. Admission copies it into every prepared track snapshot. Track state applies it as an
unsigned byte carrying a signed displacement, so header load and E9 additions wrap exactly
at eight bits. The value survives snapshot, rewind, override, and retry through logical
state; it is not recomputed from a request id.

This tranche removes the Spin Dash Release `$90` rewrite and adds the required source
comment. Focused tests must distinguish `$90` from `$10`, `$FE` from zero, and an E9 update
that wraps across `$00/$FF`.

## Early Tranche D comparison

After C0-A and Tranche C wiring review, the lane may restore the native trust roots, perform
the two clean native builds, run duplicate power-on captures to separate absent external
paths, install the independently reviewed authority identities, and compare the bounded
Sonic 2 window.

That run answers one question early: whether the production request transfer and
transactional consequence path moves the first divergence beyond tick 210. It must retain
fail-closed unavailability for any comparison domain not yet implemented. Its validation
record is labelled `MEASUREMENT_ONLY`; `docs/status/audio-frontier-log.md` is not updated as
a moved frontier at this point.

After C0-B and C0-C are reviewed, repeat the authenticated comparison from the profile
boundary. Only that repeat is eligible for a frontier entry, and only if every ordinary
authority, duplicate-capture, comparator, S1 regression, and review gate also passes.

## Rejected alternatives

### Resume full session-device migration

Rejected. The production graph already has the one session and physical device required by
these semantics. Reopening the larger migration would increase merge exposure without
closing the tick-210 request boundary.

### Exclude pause/resume from production wiring

Rejected. `$FE` and `$FF` are ordinary values in the same shipped request domain. Silently
excluding them would make the producer correct only for the current movie and violate the
"any BK2" rule.

### Delay all comparison until all C0 work is complete

Rejected by ordering decision. C0-A is independently measurable. An early authenticated
comparison shortens the feedback loop, while the explicit `MEASUREMENT_ONLY` status and
unchanged frontier prevent that measurement from overstating parity.

### Publish then compensate on rollback

Rejected. Timeline, parity, and observer history are append-only evidence. Compensating
records cannot make an externally observed false request disappear.

## Review and delivery gates

1. Human review and approval of this committed specification.
2. A separate implementation plan with one RED-first task per transaction owner and
   explicit seeded-failure boundaries.
3. Independent review of C0-A before Tranche C production binding.
4. Authenticated early Tranche D measurement with no frontier claim.
5. Independent reviews of C0-B and C0-C as separate tranches.
6. Final whole-C0 review, authenticated repeat comparison, and frontier-eligibility
   decision.

No implementation begins from this document until gate 1 is complete.
