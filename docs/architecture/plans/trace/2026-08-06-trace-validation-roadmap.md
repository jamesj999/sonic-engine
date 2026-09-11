# Trace validation roadmap

**Status:** active — the ordering the trace work follows until the project goals below
are met.

## Project goals

The near-term priority is **stabilisation of the engine and the traces**, not new
capability. In order:

1. **Headless traces pass for all games.** S1, S2 and S3K, not just the game whose
   route is currently being walked.
2. **Visual Trace Tests pass for all games.**
3. **Visual Trace Tests for multi-segment / complete runs play end to end with no
   errors.**

These are project goals, not session goals. Everything in *Order of work* below serves
them; anything that does not is deferred regardless of how interesting it is.

Note the measurement trap before quoting progress against goal 1: **`mvn test` runs zero
trace tests** — `pom.xml` excludes `**/tests/trace/**` from the default profile. Use
`-Ptrace-replay -Dmse=off` and wipe `target/surefire-reports` first, or stale XML from
previous runs will be counted as passes.

## The bar

Traces exist to prove engine accuracy. That only means something if a fix holds for a
movie nobody has recorded yet — a green fixture proves the fixture. This is now hard
rule 3's *"the bar is any BK2, not this BK2"* clause in
[CLAUDE.md](../../../../CLAUDE.md) / [AGENTS.md](../../../../AGENTS.md).

**Full per-frame validation is the immediate goal and is not being scaled back.** The
light-touch tier at the bottom of this document is a direction of travel, not a
substitute, and nothing on the critical path should be designed toward it.

## Why the current green is not yet proof

An audit of this session's landed work
(`docs/status/trace-frontier-log.md`, 2026-08-06) found the Sonic 1 object-level
constants sound — ~21 verified ROM-derived, no zone/route carve-outs — but found the
*harness* admitting rows on recorded evidence rather than engine evidence. Three
findings reinforce each other:

1. **Destination admission uses the recorded row.**
   `TraceRunPlaybackCoordinator.destinationReady` gates on
   `sharedBk2Cursor() >= destination.bk2FrameOffset()` (and `==` on the two bridge
   branches). While the coordinator is in `TRANSITION_GAP`, `GameLoop`'s
   `suppressesRunNativeLevelBody()` stops the level body running at all — so admission
   decides *when the engine's main loop starts producing frames*, not merely who
   compares. The engine's real load duration is never observed, in either direction.
2. **The gap DPLC edge is back-dated.** `DynamicArtLifecycleService` computes
   `movieLogicalFrame = firstMainLoopRow - 26`. The 26 is ROM-derived (`Level_Delay` 4 +
   `PalFadeIn_Alt` 22); `firstMainLoopRow` is the recorded row per (1). The field is
   green by construction wherever the admission floor bites — unfalsifiable rather than
   wrong.
3. **`stageResultsEntryNonAdvancingMovieRows = 7`** is documented as ROM-derived but no
   ROM routine can own it — it is the V-blank cost of `SS_Finish`'s `disable_ints`
   block. The measured shape was eight stalled rows plus a double-tick netting seven, so
   the aggregate is right and the per-row clock is wrong across four rows. It seeds
   `ObjectManager.initVblaCounter`, which is the same `mod 8` / `mod 32` class as the
   bug it was introduced to fix.

Closing these is what converts the route from *replays* to *proves*.

## Order of work

Each strand is tagged with the project goal it serves. Strands 1 and 2 are pure
stabilisation and are cross-game; strands 3 and 4 unblock the S1 complete run and are
prerequisites for goal 3 only.

### 1. Ring-count divergences — in progress · goal 1

Ring count was never compared: `EngineDiagnostics` passed a `-1` sentinel and
`TraceBinder` skipped negatives. Fixed on the run path (`9e7590efa`) and the per-act
path (`11d9b67de`). Turning it on lit up **21 real divergences**, none collateral:

| Shape | N | Example |
|---|---|---|
| Engine collapses to 0 | 3 | GHZ3 f6390 `81→0` |
| Engine exactly 10 short | 2 | GHZ1 f2032 `49→39` |
| Engine 1 short | 9 | SLZ1 f855 `2→1` |
| Engine 1 ahead, all S2 | 7 | EHZ1 f1045 `9→10` |

Work the clusters, not the individual tests — seven S2 traces each exactly one ring
ahead is one systematic cause. Establish the *direction* of an off-by-one before
changing anything: engine crediting early and comparator sampling late need opposite
fixes and both turn the test green.

### 2. Latent desyncs with no fixture — in progress · goals 1 and 3

Neither is caught today because no committed movie exercises them.

- **S1 title card holds 60 frames the ROM does not.** `Sonic1TitleCardManager`'s
  `DISPLAY_HOLD_DURATION` cites `Card_Wait` (`_incObj/34 Title Cards.asm:74`), which is
  the *post-release* slide-out timer. `Level_TtlCardLoop` (`sonic.asm:2814-2842`) exits
  on elements-at-target plus empty `v_plc_buffer`, with no minimum hold. Harmless where
  the drain exceeds ~96 frames (MZ 146, GHZ 150); latent everywhere else.
- **Death with no lives left wrongly restarts the level.** `Sonic_HandleDeath`
  (`_incObj/01 Sonic.asm:2013-2020, 2042-2045`) decrements `v_lives` on the crossing
  frame and sets `restartime = 0` on game over *or* time over; `Sonic_ResetLevel`
  (`:2066-2067`) then never writes `f_restart`. Any BK2 containing a game over desyncs
  immediately. The lives write is also 60 frames late for HUD comparison.

### 3. The PLC arming closure — design under review · goal 3

The shape *"entry completes on row f, recorded row f+1 is a lag row"* occurs 15 times
corpus-wide. `99746ffa9` models it the wrong way round — right 1 time in 15. The
discriminator is sub-frame 68000 cycle position, which no committed column carries and
which a native model is *permanently* incapable of predicting, since a new movie's rows
fall wherever its own 68000 history puts them.

- [2026-08-06-s1-nemesis-plc-timing-kind-admission-review.md](../../designs/2026-08-06-s1-nemesis-plc-timing-kind-admission-review.md)
  — the design review discharging the contract's gate
- [2026-08-06-s1-nemesis-plc-timing-stream-plan.md](2026-08-06-s1-nemesis-plc-timing-stream-plan.md)
  — the phased plan

**Phase 1 is the `99746ffa9` revert and is mandatory regardless of whether the rest
proceeds.** It depends only on the baseline measurements, it is the pure correctness
fix, and it shrinks the surface every later phase is measured against.

### 4. The level-load span · goal 3

The un-timed span between the counted title-card drain and the counted pre-main-loop
tail is what finding (1) papers over. Three separate investigations converged on the
same answer: an **engine-counted load model** for the derivable part plus a
**hardware-timing entry** for the genuinely un-timed residual (`NemDec`, `ClearScreen`,
`Hud_Base`, `LevelDataLoad`, `LoadTilesFromStart`, `ObjPosLoad`).

- [2026-08-06-level-load-span-timing-port-scope.md](../../designs/2026-08-06-level-load-span-timing-port-scope.md)
  — rejected the timing-port route on "no representable `raw_frame` for a gap row"
- [2026-08-06-recorded-level-load-span-segment.md](../../designs/2026-08-06-recorded-level-load-span-segment.md)
  — NO-GO standalone; recording the span *dissolves* the objection above, making it the
  timing port's prerequisite rather than its alternative

Sequenced after (3) because it needs the same S1 timing-stream infrastructure.

## Later: the light-touch tier — serves no current goal

The end goal is that a BK2 needs only light-touch processing to be runnable. We are
nowhere near it, and **the sanctioned home for it already exists** — do not build a
parallel one, and do not extend the v5 trace schema toward it (v5 has no optional-payload
capability and its guards actively forbid one).

- **`desync-lite`** — the sidecar in
  [2026-06-29-user-recording-playback-design.md](../../designs/2026-06-29-user-recording-playback-design.md)
  (plan: [2026-06-29-user-recording-playback.md](../2026-06-29-user-recording-playback.md)):
  15 scalars per row, comparison-only, non-mutating. The every-frame tier
  shipped (`src/main/java/com/openggf/game/recording/`). The manifest already reserves
  `sampleInterval`, and `UserRecordingVerifier` already looks rows up by *frame index*
  and tolerates absent rows unless the sidecar declares every-frame. What is missing is
  a writer emitting at an interval and a `sampleMode` the verifier accepts.
- **Elastic checkpoint windows** — the S3K fixture design specified phases where drift
  inside a checkpoint-bounded window is diagnostic rather than failing. `TraceEvent.Checkpoint`
  shipped; the relaxed comparison mode never did ("elastic" appears only in docs).

There is no drivable sidecar-only tier today and building one is not authorised. The
blocker is earlier than "undrivable":
`TraceHardwareTimingScheduleCompiler.compileForInstall` keys `traceIndexByRawFrame` on
the trace's own rows and resolves every edge through it, so **a payload-free session
cannot install a schedule at all** — before frame admission (which lives in the per-row
`lag` payload, not any sidecar) or `TraceRunManifest`'s rejection of segments lacking
payload capabilities even come into play. Reaching the light-touch goal therefore means
reproducing frame admission from something other than recorded rows: a change to the
replay contract, not a recorder feature.

The capability the bar actually asks for is already met without a thinner artifact — the
timing stream falls out of the same harness pass as the payload, so an arbitrary BK2
costs one mechanical capture and no per-movie work.
