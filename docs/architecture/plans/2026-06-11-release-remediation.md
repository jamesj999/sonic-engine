# Release Remediation Master Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Consolidate the sidekick CPU audit, release-review findings, performance audit, and recommended remediation plans into one actionable plan, then fix the confirmed issues according to OpenGGF's ROM-accuracy, trace, documentation, performance, and release policies.

**Architecture:** Treat the temp audit files as source evidence, not shippable artifacts. Fix release-facing regressions first, then sidekick trace parity, then medium-risk framework correctness, then performance work whose equivalence can be proven, then documentation and hygiene. Every behavior change needs a failing test first, ROM/disassembly citation where it affects parity, and focused verification before broader sweeps.

**Tech Stack:** Java 21, Maven, JUnit 5, OpenGGF trace replay fixtures, Sonic 1/Sonic 2/Sonic 3&K disassemblies under `docs/`, project branch-policy trailers.

---

## Source Inputs

- `SIDEKICK_CPU_AUDIT.tmp.md` (now removed from the workspace): disassembly-complete audit of `SidekickCpuController`, `TailsRespawnStrategy`, playable history buffers, and signpost/end-of-act side effects.
- `RELEASE_REVIEW_FINDINGS.tmp.md` (now removed from the workspace): release architecture/code review findings, including entries already fixed, refuted entries, backlog entries, and confirmed release-facing defects.
- `docs/tmp-release-sweep-issues.md` and `docs/tmp-release-review-fix-issues.md` (folded here and removed): temporary release-sweep trackers whose open items are now either completed below, deferred in permanent release docs, or represented in the remaining outstanding list.
- `docs/architecture/audits/release-architecture-review-issues.md` and `docs/project/release-readiness-roadmap.md`: permanent release-history/debt trackers still referenced by guard tests; this file is the active remediation plan, while those files remain evidence for release-policy and bootstrap-debt assertions.
- `docs/architecture/plans/2026-06-11-performance-optimization.md`: performance remediation plan for audio, rendering, rewind, object, and hot-loop overhead.
- `docs/architecture/designs/2026-06-11-performance-optimization-design.md`: source-backed performance problem statement and phase design.
- Conversation remediation plan from 2026-06-11: prioritize release blockers, sidekick parity, medium-risk correctness, docs/release hygiene, and verification.

The removed `.tmp.md` files and tracked `docs/tmp-*` scratch trackers must not be reintroduced. This plan is the durable replacement artifact and the single source of truth for the active release-remediation goal; referenced permanent docs remain only as release-policy evidence and historical debt records.

## Consolidated Current Problem Statement

This file is the single durable remediation artifact for the sidekick CPU
audit, release review, performance audit, and post-merge trace frontier. The
scratch `.tmp.md` source files are gone from the workspace; their confirmed
entries have either been completed below, rejected with evidence, deferred in
permanent discrepancy/release docs, or retained in the outstanding queue here.

As of `develop` commit `ba071713a` after reconciling with `origin/develop`,
the release blocker is no longer a broad configuration or review-hygiene
failure. The remaining work is trace/parity correctness plus evidence-gated
performance remediation:

| Priority | Area | Current problem | Evidence/frontier | Required remediation |
|---|---|---|---|---|
| P0 | S3K complete-run trace parity | ICZ has advanced through the lost-ring Obj37 path and now diverges on a path-follow platform/player carry interaction. | `TestS3kIczCompleteRunTraceReplay`, first error frame 3752 main-player `x expected=0x464D actual=0x464E` plus coupled `camera_x`; path-platform diagnostics are exposed. | Model the ROM state that drives path-follow platform saved-carry/input/standing timing. No ICZ/route/frame carve-out. |
| P0 | S3K complete-run trace parity | HCZ miniboss/vortex release still disagrees on the post-vortex player air bit. | `TestS3kHczCompleteRunTraceReplay`, first error frame 9482 main-player `air expected=1 actual=0`; local timer-only and AirCountdown hypotheses were rejected. | Continue ROM-side child/parent status handoff triage around `sub_6A960 -> loc_6A986`; fix the owning object/status transition. |
| P0 | S3K complete-run trace parity | Several S3K full-run starts/frontiers remain red after the merged sweep. | CNZ frame 0 `y_speed`, MGZ frame 738 `rings`, MHZ frame 0 `tails_cpu_routine`, LBZ frame 0 `camera_y`, AIZ sidekick CPU auto-jump/fallthrough failures. | Triage each from first divergence, preferring route-blocking AIZ -> HCZ and ICZ/MGZ/CNZ issues per project delivery priority. |
| P1 | S2/S3K sidekick audit leftovers | Sidekick interaction/input frontiers remain in S2 level-select traces and S3K CNZ/MGZ dedicated traces. | Post-merge `*TraceReplay` sweep: S2 tails CPU/interact/speed failures; CNZ/MGZ input-alignment failures. | Complete sidekick CPU ROM-state modeling and verify with focused cross-game traces; regenerate only for diagnostic insufficiency, not to mask behavior. |
| P1 | S1/S2 complete-run parity | Many complete-run traces are still red, while shorter known-green traces remain green. | Post-merge sweep: S1 complete-run failures across zones; S2 level-select failures except known greens. | Fix first divergences with per-game disassembly checks and feature flags where behavior differs. |
| P2 | Performance | Verified hot-path waste remains partially planned, with performance work needing clean correctness baselines and trace sweeps. | `docs/architecture/designs/2026-06-11-performance-optimization-design.md`; performance branch acceptance sweep proved no trace regression for its completed subset. | Execute exact-equivalence quick wins first, then rendering/rewind work with measurement, trace sweeps, and audio/render equivalence evidence. |
| P2 | Release hygiene/debt | Deferred/review debt must stay bounded and documented. | `docs/architecture/audits/release-architecture-review-issues.md`, `docs/status/known-discrepancies.md`, `docs/S3K_KNOWN_DISCREPANCIES.md`, release hooks/guards. | Keep guard tests and discrepancy docs current as fixes land; do not reintroduce temp trackers. |

## Ordered Remediation Queue

1. Keep `docs/status/trace-frontier-log.md` current for every trace frontier move or
   full sweep, and use the post-merge full sweep as the current baseline:
   Maven/Surefire reported `Tests run: 90, Failures: 58, Errors: 1,
   Skipped: 0`; parsed trace-class reports showed 59 trace classes, 106 trace
   tests, 58 failures, 1 error, 0 skipped.
2. Work P0 S3K route blockers in order of expected delivery impact:
   ICZ frame 3752 path-follow platform carry timing, HCZ frame 9482
   post-vortex air-state handoff, then CNZ/MGZ/MHZ/LBZ/AIZ current first
   frontiers.
3. For each trace target, follow the trace replay workflow: run focused trace,
   inspect `target/trace-reports`, read the relevant disassembly, implement the
   ROM-state fix, run focused tests plus previously-green cross-game traces,
   then update this file/frontier log as needed.
4. After correctness frontiers stabilize, execute the performance plan in
   evidence-gated phases. Exact-equivalence items may land independently only
   with focused tests and a trace sweep proving no regression.
5. Before claiming the goal complete, require a full `*TraceReplay` sweep with
   no regressions relative to the latest intended frontier, all focused tests
   for touched systems green, release/doc guards green, and all commit trailers
   matching staged files.

## Implementation Status Snapshot

Completed in `bugfix/ai-release-remediation`:

- RB-1/RB-2: gated release debug/cheat/art-viewer shortcuts on `debug.flags.debugView`; moved ring-bounds off F9.
- RB-3: made CNZ post-capsule music/control restoration one-shot.
- RB-4: separated default pause/P2 Start bindings, gated pause to gameplay modes, and added a visible pause indicator.
- RB-5/RB-6: fixed KEY digit parsing/writer round-trip and clamped invalid FPS values.
- RB-7: documented the missing game-over/continue flow and restored sane zero-life pause behavior as the release-safe interim.
- SK-2/SK-3/SK-4/SK-5: restored S2 control-lock logical-input latching, removed the grounded+pushing jump hold carve-out, split the S3K-only object-control nudge gate from S2, added PANIC hurt/dead CPU skip behavior, fixed PANIC equality-facing to match the ROM's no-carry branch, covered PANIC post-`CheckDespawn` latch plus S2/S3K dead-fall threshold behavior, and routed S2 dead-Sonic flight through the ROM routine-4 fly-in path with entry/landing side effects.
- SK-1 unit slice: S3K fresh routine-0 sidekick spawns now reset kinematics, advance to NORMAL for the next object tick without same-frame follow steering, and preserve dormant sentinel `object_control=$83` semantics.
- SK-3 live-push-in-water slice: S2 live `Status_Push` with delayed Sonic not pushing now takes the ROM push-bypass branch underwater without requiring S3K's AIZ reload pulse heuristic.
- SK-3 delayed-press slice: sidekick CPU replay now consumes the delayed ROM press byte directly so consecutive recorded A/B/C press bytes are preserved instead of being collapsed by engine edge reconstruction.
- SK-7 waterline slice: sidekick fly-in target clamping now reads `Water_Level_1` semantics through `WaterSystem.getGameplayWaterLevelY(...)`, so oscillated gameplay waterlines are honored instead of the base/current water register.
- SK-3 airborne handoff slice: S2 airborne live `Status_Push` plus delayed Sonic `Status_Push` now falls through to follow steering instead of taking a non-ROM skip branch.
- SK-3 panic pinball slice: S2 PANIC now ignores `pinball_mode` when `spindash_flag` is clear, while S3K AutoSpin keeps the engine `pinballMode` bridge through `PhysicsFeatureSet.sidekickPanicTreatsPinballModeAsSpindashFlag()`.
- SK-1 HCZ monitor-release slice: S3K monitor breaks now recover the ROM P2 standing bit from monitor-edge geometry, release CPU sidekicks into air when Sonic breaks the shell, and suppress the released sidekick's same-frame gravity tick. `TestS3kHczCompleteRunTraceReplay` moved from frame 125 `tails_air` to frame 374 main-player `y_speed`.
- MC-1: cleared `CollisionSystem.pendingOddSensorFallbackAngles` on reset and documented the right-wall heuristics.
- MC-2: corrected the reviewed MarkObjGone coarse-back checks for AIZ falling log, S3K signpost, S2 Conveyor, SlidingSpikes, Tornado, Nebula, and Cloud.
- MC-3: made the parallax compositing pass the sole owner of BG per-column VScroll so the AIZ fire-wave offset is not applied twice.
- MC-4: reset `PaletteOwnershipRegistry.paletteRotationDisabled` at level-load/act-transition zone-feature boundaries.
- MC-5: refactored S2 Cloud movement onto `SubpixelMotion`.
- MC-5 editor-save subset: transient editor-save read failures are non-destructive, editor saves fall back when `ATOMIC_MOVE` is unsupported, and wrong-length block/chunk states quarantine before any partial map edits apply.
- MC-6: bounded stalled trace-capture encoder shutdown so ffmpeg backpressure cannot hang stop forever.
- MC-7: skipped full level tilemap invalidation during live rewind restore when block, chunk, and map arrays are already the live references.
- MC-8: made deferred GL command groups snapshot caller command lists so reused fallback-render lists cannot alias across buckets.
- MC-9: cached resolved integer/key configuration values with invalidation on config mutation and derived display-aspect refresh.
- MC-10: serialized SoundTestApp interactive-mode backend access onto the audio executor with orderly single-destroy teardown.
- DOC-5: corrected stale S1 SBZ/FZ and LZ water-slide source comments that described implemented behavior as TODO/stub work.
- Save/data-select hardening: added `SaveSlotState.UNAVAILABLE` for transient read failures and preserved it through data select so unreadable saves cannot be overwritten as fresh slots.
- Palette fallback hardening: resolved underwater palette fallback lookups through feature-remapped zone/act keys so remapped zones use the same key for storage and reads.
- S3K ICZ startup palette ownership hardening: added a post-registry-reset level-load palette override hook so ICZ1's lock-on mountain palette keeps owner `s3k.icz.startupPalette` after zone-scoped registries are cleared.
- Display boundary hardening: guarded startup centering and post-resize integer-scale snapping when GLFW cannot report a monitor or video mode.
- PERF-0 trace baseline: captured a clean-worktree `*TraceReplay` sweep for
  performance remediation planning and recorded the parsed trace-class table in
  `docs/architecture/validation/performance/2026-06-12-trace-baseline.md`; the sweep generated useful
  frontiers but ended with `Java heap space`, so it is a partial baseline rather
  than clean all-trace certification.
- Documentation/hook/architecture hygiene: removed stale S3K AIZ2 battleship and S2 latch discrepancy entries, corrected AGENTS/CLAUDE drift, added known-bug/changelog notes, fixed the PowerShell trailer parser no-space substring bug, replaced the two production raw construction-context set/clear call sites with scoped helpers, pruned stale object-service migration guard baselines, added `SessionManager` guard coverage, and removed stale mutation-routing allow-list entries.
- HCZ miniboss trace diagnostics: added focused trace context for the current frame-9482 frontier, including miniboss hit count/defeat/invulnerability state, ground probes, accepted hit frame/source, and final-hit routine/wait/water-effect routine (`lastHit=9362/Sonic:ENEMY hr=16 hw=52 hwr=04`).
- ICZ complete-run bootstrap progression: Sonic+Tails now follows the ROM `Player_mode < 2` ICZ1 snowboard-intro path, and CPU Tails is parked through the routine-`$0A` dormant marker (`object_control=$83`, `$7F00,0`). The ICZ complete-run trace moved from frame 0 `rolling expected=1 actual=0` to frame 29 main-player `y expected=0x00F2 actual=0x00F0`.
- ICZ snowboard release progression: `Obj_LevelIntroICZ1` now applies the ROM
  same-sampled-frame SpeedToPos plus gravity tick when startup object control is
  cleared. The ICZ complete-run trace moved from frame 29 main-player
  `y_speed expected=0x02B8 actual=0x0280` to frame 117 main-player `g_speed
  expected=0x0800 actual=0x1000`.
- ICZ snowboard overlay progression: snowboard ground-speed maintenance now
  waits until the ROM-equivalent active overlay routine has already seen Sonic
  grounded. The ICZ complete-run trace moved from frame 117 main-player
  `g_speed expected=0x0800 actual=0x1000` to frame 163 `rings expected=1
  actual=0`.
- ICZ snowboard ring progression: the ICZ snowboard intro now models ROM
  `object_control=#3/#2` as low-bit object ownership rather than bit-7
  touch-response suppression. Sonic remains object-controlled during the active
  snowboard overlay, but placed-ring collection still runs. The ICZ complete-run
  trace moved from frame 163 `rings expected=1 actual=0` to frame 171
  main-player `air expected=0 actual=1`.
- ICZ snowboard jump-timing progression: the active snowboard overlay now keeps
  `Ctrl_1_locked`-equivalent state published across object ticks and copies raw
  A/B/C/Start into the next-frame logical jump edge, while the headless BK2
  harness no longer injects synthetic logical action edges through control lock.
  The ICZ complete-run trace moved from frame 171 main-player `air expected=0
  actual=1` to frame 488 main-player `x_speed expected=0x120D actual=0x126F`.
- ICZ snowboard slope-handoff progression: the scripted slope exit now applies
  the final ROM `loc_395FE` position row and immediately publishes
  movement-active `object_control=#2`, `top_solid_bit=$E`, and
  `lrb_solid_bit=$F` before entering the normal snowboard overlay. The ICZ
  complete-run trace moved from frame 488 main-player `x_speed expected=0x120D
  actual=0x126F` to frame 505 main-player `x expected=0x1487
  actual=0x1486`, where velocities match and the remaining mismatch is
  one-pixel/subpixel drift.
- ICZ snowboard subpixel progression: the scripted slope table now preserves
  Sonic's low-word `x_sub`/`y_sub` accumulator while writing centre pixels,
  matching ROM `move.w x_pos/y_pos` semantics. The ICZ complete-run trace moved
  from frame 505 main-player `x expected=0x1487 actual=0x1486` to frame 1112
  native-P2/Tails CPU routine `expected=0x0002 actual=0x000A`, with Sonic
  position, subpixels, crash velocities, camera, rings, and dormant Tails
  position/status matching at the new frontier.
- ICZ snowboard crash sidekick-handoff progression: `Obj_LevelIntroICZ1` crash
  release now mirrors the ROM `Tails_CPU_routine=#2` write by releasing
  dormant-marker sidekicks through the level-event CPU-controller handoff while
  preserving the `$7F00,0` marker position and `object_control=$83`. The ICZ
  complete-run trace moved from frame 1112 native-P2/Tails CPU routine
  `expected=0x0002 actual=0x000A` to frame 1314 main-player `y
  expected=0x06EC actual=0x06E7` at the post-crash pile-jump handoff, with
  Sonic `x`, subpixels, velocities, status, rings, camera, and Tails CPU
  routine/counters matching at the new frontier.
- ICZ big snow pile jump progression: `Obj_ICZ1BigSnowPile` jump escape now
  preserves Sonic's ROM `y_pos` while applying the roll/radius state and
  `y_vel=-$600` jump release. The ICZ complete-run trace moved from frame 1314
  main-player `y expected=0x06EC actual=0x06E7` to frame 1646 main-player
  `angle expected=0x0004 actual=0x0000`, with Sonic position, velocities,
  status, rings, and camera matching at the new frontier.
- ICZ horizontal spring angle progression: S3K horizontal `Obj_Spring` launch
  now preserves grounded player `angle(a1)` while applying `x_vel`,
  `ground_vel`, and move-lock state, matching ROM `sub_23190` which does not
  write angle. The ICZ complete-run trace moved from frame 1646 main-player
  `angle expected=0x0004 actual=0x0000` to frame 1667 `camera_x
  expected=0x3C2A actual=0x3C33`; Sonic velocities, angle, status, rings, and
  Tails CPU state match at the new frontier.
- ICZ swinging-platform trigger-order progression: `Obj_ICZSwingingPlatform`
  now keeps the platform at spawn on the child-solid trigger frame while
  applying the immediate player velocity clamp, then starts circular movement
  on the following object update, matching ROM `sub_8B0B0` -> `loc_8AD20`.
  The ICZ complete-run trace moved from frame 1667 `camera_x expected=0x3C2A
  actual=0x3C33` to frame 1708 main-player `y expected=0x0674
  actual=0x0673`, with Sonic `x`, subpixels, velocities, angle, status, rings,
  and Tails CPU state matching at the new frontier.
- ICZ swinging-platform child-solid progression: `Obj_ICZSwingingPlatform`
  now models the ROM lower/upper child solids as separate standing-bit owners
  and keeps their raw `SolidObjectFull` widths (`$2B`/`$0F`) through the
  top-branch recheck. The ICZ complete-run trace moved from frame 1708
  main-player `y expected=0x0674 actual=0x0673` to frame 1987 Tails
  `tails_x expected=0x3EEE actual=0x3EED`; Sonic position, camera, rings,
  status, and velocities match at the new frontier.
- ICZ CPU Tails stale rolling-push progression: roll-entry animation changes
  now clear `Status_Push`, and the Tails CPU live/frame-start push bypass masks
  grounded rolling nonzero-`ground_vel` stale push state because the S3K rolling
  wall-response tail zeroes `ground_vel` before setting `Status_Push`. The ICZ
  complete-run trace moved from frame 1987 Tails `tails_x expected=0x3EEE
  actual=0x3EED` to frame 2061 native-P2/Tails CPU interact
  `expected=0x0008 actual=0x0000`; Sonic position, camera, rings, sidekick
  position, sidekick speeds, and sidekick status match at the new frontier.
- ICZ segment-column interact progression: `Obj_ICZSegmentColumn` child
  segments now expose the ROM word-0 routine-pointer high word `0x0008`
  through `RomObjectCodePointerProvider`, allowing the existing S3K
  `Tails_CPU_interact` model to refresh from live segment-column ride objects.
  The ICZ complete-run trace moved from frame 2061 native-P2/Tails CPU interact
  `expected=0x0008 actual=0x0000` to frame 2263 main-player `rolling`
  `expected=1 actual=0`; the former CPU interact field now matches through the
  old frontier and new window.
- ICZ Star Pointer/Insta-Shield progression: S3K Insta-Shield now models the
  ROM temporary-invincible 48x48 hurt pass without clearing shield-reactive
  object collision flags, while real shields still run the projectile-deflect
  path. The Star Pointer point hit sequence no longer leaves Sonic in the wrong
  rolling hurt transition. The ICZ complete-run trace moved from frame 2263
  main-player `rolling expected=1 actual=0` to frame 2268 native-P2/Tails
  `tails_x_speed expected=0x0200 actual=-0x00C0`.
- ICZ Star Pointer routine cleanup: `Obj_StarPointer` now checks native P2 via
  the ROM `Find_SonicTails`-equivalent release target, preserves the
  `loc_8BEE6 -> MoveSprite_CircularSimple` launch-frame orbit refresh, copies
  the parent's full fixed-point longword position into orbiting points, and
  applies the ROM `$20` dummy-sprite `Obj_WaitOffscreen` gate before installing
  the active parent routine. These ROM-state cleanups move the ICZ complete-run
  trace from frame 2268 native-P2/Tails hurt-state speed to frame 2472
  main-player `y expected=0x064F actual=0x064A` near ICZ ice-cube debris.
- ICZ ice-cube shatter progression: `Obj_ICZIceCube` shatter now preserves the
  player's native centre `y_pos` while applying the ROM roll/radius state,
  `anim=2`, `y_vel=-$300`, in-air status, and object-release state. The ICZ
  complete-run trace moved from frame 2472 main-player `y expected=0x064F
  actual=0x064A` to frame 2600 main-player `x_speed expected=-0x0524
  actual=-0x0520` and `g_speed expected=-0x0D91 actual=-0x0D85`, where
  position, camera, rings, angle, air, rolling, ground mode, and native-P2/Tails
  fields match at the first error.
- ICZ slide-terrain progression: ICZ1 now publishes the ROM
  `status_secondary` slide bit from `sub_714E -> sub_71E4` after playable
  physics, and player movement suppresses manual down-roll while the slide bit
  is set. The ICZ complete-run trace moved from frame 2600 main-player
  right-wall `x_speed`/`g_speed` mismatch to frame 2644 native-P2/Tails
  one-pixel follow-position mismatch; main Sonic fields and Tails CPU
  routine/counters match at the new first error.
- ICZ slide-terrain facing progression: directional ICZ1 slide terrain now
  refreshes `Status_Facing`/raw slide animation on every publish and reads the
  signed high byte of `ground_vel`, matching ROM `sub_71E4`. This clears the
  frame 2644 native-P2/Tails follow nudge mismatch and moves the ICZ
  complete-run trace to frame 2836 native-P2/Tails `air expected=1 actual=0`;
  Tails position, subpixels, CPU routine/counters, interact word, and main
  Sonic fields match at the new first error.
- ICZ freezer capture progression: freezer capture clouds now survive parent
  offscreen unload into the ROM off-phase scanner, and frozen-player blocks
  preserve capture-frame `x_pos/y_pos` while skipping spawn-frame motion. The
  ICZ complete-run trace moved from frame 2836 native-P2/Tails `air` mismatch
  to frame 2837 native-P2/Tails `x expected=0x3FC4 actual=0x3FC2`; Tails
  `y`, speeds, air, rolling, ground mode, CPU routine/counters, main Sonic,
  camera, and rings match at the first error.
- ICZ frozen-block side-clamp progression: freezer frozen-player blocks now
  clear horizontal velocity before `MoveSprite` when the ROM
  `Camera_X_pos+$20/$128` side clamp has crossed the block. The ICZ
  complete-run trace moved from frame 2837 native-P2/Tails `x expected=0x3FC4
  actual=0x3FC2` to frame 2838 native-P2/Tails `y expected=0x036B
  actual=0x036F`; Tails `x`, speeds, air, rolling, ground mode, CPU
  routine/counters, main Sonic, camera, and rings match at the first error.
- ICZ frozen-block lifetime progression: freezer frozen-player blocks now stay
  persistent while carrying a captured player, matching the ROM `loc_8A84C`
  player-sync/draw loop instead of generic dynamic-object coarse culling. The
  ICZ complete-run trace moved from frame 2838 native-P2/Tails `y
  expected=0x036B actual=0x036F` to frame 2875 main-player `g_speed
  expected=0x03A9 actual=0x0369`; native Tails position/control fields and the
  carried frozen block now match through the former frontier.
- ICZ directional slide-terrain inertia progression: ICZ1 directional slide
  terrain now applies the ROM `loc_723E`/`loc_7254` `ground_vel` step toward
  the signed high-byte slide table target while keeping the facing refresh
  based on the pre-adjustment high byte. The ICZ complete-run trace moved from
  frame 2875 main-player `g_speed expected=0x03A9 actual=0x0369` to frame 2964
  native-P2/Tails `rolling expected=0 actual=1`; main-player position, speeds,
  ground speed, angle, air/rolling state, camera, rings, and Tails CPU
  routine/counters match through the former frontier.
- ICZ freezer sidekick-release progression: frozen-player blocks now break on
  the ROM `loc_8A84C` pre-decrement frame, apply the freezer-specific
  `loc_8A88A` x-velocity override after `HurtCharacter`, and route
  CPU-controlled captured sidekicks through `Hurt_Sidekick` semantics so
  Sonic's shared rings are preserved. The ICZ complete-run trace moved from
  frame 2964 native-P2/Tails `rolling expected=0 actual=1` to frame 2967
  native-P2/Tails `y expected=0x0365 actual=0x0364`; main-player fields, rings,
  sidekick release velocities, sidekick air/rolling state, and Tails CPU
  routine/counters match through the former frontier.
- ICZ freezer subpixel progression: capture clouds and frozen-player blocks now
  copy captured-player `x_pos/y_pos` with ROM word-write semantics, preserving
  `x_sub/y_sub` through the frozen animation handoff, carried-player sync, and
  post-break hurt movement. The ICZ complete-run trace moved from frame 2967
  native-P2/Tails `y expected=0x0365 actual=0x0364` to frame 3102 main-player
  `x expected=0x4409 actual=0x440B` with a matching one-pixel `camera_x`
  offset; Tails position, speeds, subpixels, and CPU state match through the
  former freezer-release frontier.
- ICZ path-follow platform jitter progression: routine-$04 path-follow
  platforms now derive their one-pixel shake from the ROM `V_int_run_count+3`
  low-bit phase instead of direct level-frame parity. The ICZ complete-run
  trace moved from frame 3102 main-player `x expected=0x4409 actual=0x440B`
  to frame 3174 main-player `rings expected=42 actual=0`; main-player
  position, speeds, camera, sidekick position, and Tails CPU state match
  through the former path-platform frontier.
- ICZ hidden-hurt ring-spend progression: S3K horizontal invisible hurt blocks
  now route their main-player hurt path through ROM `sub_24280` semantics,
  rewinding `y_pos` by the current `y_vel<<8` and delaying lost-ring spill
  until the next level tick. The ICZ complete-run trace moved from frame 3174
  main-player `rings expected=42 actual=0` to frame 3273 main-player
  `rings expected=1 actual=0`; main-player position, speeds, camera, sidekick
  state, and Tails CPU state match through the former hidden-hurt frontier.
- ICZ lost-ring display-timer progression: the shared player
  `invulnerable_time` tick now runs in the ROM display phase before touch
  response reads the spilled-ring collection threshold, while `tickStatus()`
  guards against a second same-frame decrement. The ICZ complete-run trace
  moved from frame 3273 main-player `rings expected=1 actual=0` to frame 3323
  main-player `rings expected=2 actual=1`; main-player position, speeds, angle,
  air/rolling state, camera, sidekick state, and Tails CPU fields match through
  the former re-collection frontier.
- ICZ Obj37 delayed-materialization narrowing: pending S3K lost-ring spills now
  apply the same first Obj37 movement/gravity step that ROM gets when
  `Obj_Bouncing_Ring` allocates new slots during the player slot, and Obj37
  touch response reads the live post-movement collision-list position. Focused
  lost-ring tests are green, but the ICZ complete-run trace still first
  diverges at frame 3323 main-player `rings expected=2 actual=1`; the remaining
  mismatch is narrowed to the slot-44 Obj37 collection position/velocity path,
  not the delayed materialization bridge.
- ICZ Obj37 process-slot cadence narrowing: `ObjectSlotLayout` now separates
  dynamic allocation limits from the full object process-loop slot count, and
  S3K Obj37 floor-probe phase uses the ROM 110-slot `Object_RAM`
  `Process_Sprites` countdown. Focused lost-ring/touch tests are green; the ICZ
  complete-run trace still first diverges at frame 3323 main-player
  `rings expected=2 actual=1`, so the remaining work stays on the second
  lost-ring collection path rather than slot-count cadence.
- ICZ Obj37 floor-probe completion: normal-gravity spilled rings now call the
  shared object floor probe path, matching `RingCheckFloorDist -> Ring_FindFloor`
  (`docs/skdisasm/sonic3k.asm:20098-20110`,
  `docs/skdisasm/sonic3k.asm:35624-35643`). This fixes the frame-3323
  slot-44 bounce/collection position and moves the ICZ complete-run trace to
  frame 3752 main-player `x expected=0x464D actual=0x464E` with matching
  subpixels and a coupled `camera_x` mismatch.
- ICZ frame-3752 diagnostic slice: `IczPathFollowPlatformObjectInstance`
  exposes phase/velocity/subpixel/angle/wait/push/standing/pre-update trace
  context for the current path-follow platform frontier. The focused platform
  unit tests are green; the trace still requires ROM-side saved-carry/input
  timing diagnostics before a safe behavior fix.
- Full trace regression sweep after ICZ diagnostics: the default forked sweep
  generated reports but hit `Java heap space`; the serial `-Xmx3g` rerun
  completed as ordinary trace failures with 59 trace classes, 106 tests, 58
  failures, 1 error, and 0 skipped. The current S3K frontiers remain HCZ frame
  9482 `air`, ICZ frame 3752 `x`, CNZ frame 0 `y_speed`, MGZ frame 738
  `rings`, MHZ frame 0 `tails_cpu_routine`, plus LBZ frame 0 `camera_y`, AIZ
  frame 1095 `x_speed`, and CNZ/MGZ input-alignment issues.

Still outstanding:

- HCZ complete-run progression: frames 125, 374, 624, 896, 898, 940, 953, 957, 973, 974, 1020, 1581, 1671, 2486, 2501, 2635, 2801, 2894, 2976, 3066, 3124, 3318, 3355, 3850, 4286, 4403, 4872, 5726, 5995, 6912, 7341, 8451, 8452, 8683, 8968, 9045, and 9337 have been cleared or advanced by the release-remediation sidekick/object slices. The current HCZ first error is frame 9482 main-player `air` (`expected=1`, `actual=0`) after the HCZ miniboss vortex release. The frame-5726 native-P2 Tails vertical-velocity sign mismatch was resolved by preserving the ROM collapsing-platform standing flag through monitor release, frame 5995 by applying ROM chain-leader healing for despawned sidekicks, frame 6912 by aligning HCZ bridge release ordering, frame 7341 by preserving HCZ moving-platform ride publication, frame 8451/8452 by correcting miniboss object-control and hit-latch handoff timing, frame 8683 by aligning miniboss rocket routine publication, frame 8968 by delaying vortex player pull until the ROM raw-animation wind-up reaches the callback, frame 9045 by driving the water-effect routine gate plus first-contact capture order from the ROM vortex helper sequence, and frame 9337 by preserving the ROM final water-effect pull tick before same-update player release. Frame 9482 diagnostics show both Sonic and Tails standing on solid tile `0xFF` in the engine while the ROM-visible air bits are set, and the final accepted hit occurred at object-manager frame 9362 in miniboss routine `$16` with `waitTimer=52` and water-effect routine `$04`.
- Rejected HCZ frame-9482 hypothesis: a local probe that preserved captured water-effect players and released them after the inherited `waitTimer=52` moved the first divergence backward to frame 9470 (`air expected=0`, `actual=1`). A prior hardcoded `(2*60)-1` defeat-release wait also failed to move the frontier. Do not commit a timer-only release fix; continue by modeling the ROM child/parent status handoff that makes `sub_6A960 -> loc_6A986` set player `Status_InAir` at the correct object-update phase.
- Rejected HCZ frame-9482 hypothesis: the fixed S3K `Obj_AirCountdown` sidecar is not the missing writer. `AirCountdown_Countdown` only sets `Status_InAir` on the drowning-death path after `Player_TouchFloor`, not on every underwater countdown tick; a local broad `owner.setAir(true)` probe regressed HCZ from frame 9482 to frame 298 (`air expected=0`, `actual=1`). Treat the frame-9482 `airCnt` aux rows as timing/context diagnostics, not as the direct release writer.
- Rejected HCZ frame-896 hypothesis: button-local `isSolidFor` counters/underwater-entry deferrals and a broad shared first-frame render-flag lifecycle change were tested. Neither moved the HCZ trace; the shared lifecycle attempt regressed a focused `TestSolidObjectManager` boundary case, so both directions were removed. The accepted direction was ROM `SolidObjectTop_1P` boundary rejection plus `Obj_Button` same-frame trigger publication.
- Remaining SK-1 verification: S3K complete-run trace coverage for fresh sidekick spawn/init-only frame and dormant park semantics. HCZ frame-2894 sidekick follow-history jump-edge publication, frame-3318 conveyor release center preservation, frame-3355 conveyor coarse-back culling, frame-3850 native-P2 roll-stop, frame-4286 water-skim airborne gravity handoff, frame-4403 water-skim subpixel pin, frame-4872 AutoSpin wall-mode X preservation, and the frame-5726 through 9337 HCZ object/sidekick/miniboss slices are covered and advanced; HCZ now needs ROM-state triage of the frame-9482 post-vortex air-state frontier, while ICZ needs frame-3752 path-follow platform saved-carry/input timing triage and LBZ/MGZ complete-run coverage remains outstanding.
- Remaining sidekick audit backlog: complete-run SK-1 trace verification for LBZ/MGZ, CNZ/MGZ input-alignment frontiers, and MGZ complete-run ring mismatch. The former HCZ frame-2894 sidekick input frontier and ICZ frame-0/frame-29/frame-117/frame-163/frame-171/frame-488/frame-505/frame-1112/frame-1314/frame-1646/frame-1667/frame-1708/frame-1987/frame-2061/frame-2263/frame-2268/frame-2600/frame-2644/frame-2836/frame-2837/frame-2838/frame-2875/frame-2964/frame-2967/frame-3102/frame-3174/frame-3273 snowboard startup/ground-speed/ring/jump-timing/slope-handoff/subpixel/dormant-Tails/pile-jump/horizontal-spring-angle/swinging-platform-order/child-solid/stale-rolling-push/segment-column-interact/Insta-Shield/Star-Pointer/slide-terrain timing/facing/freezer capture/side-clamp/frozen-block lifetime/directional-inertia/freezer sidekick-release/freezer subpixel/path-platform jitter/hidden-hurt ring-spend/lost-ring display-timer mismatches are now resolved.
- Performance remediation remains planned but not executed on this branch. Its work is intentionally sequenced after the active correctness/trace issues because several proposed optimizations cross audio, rendering, rewind, and object lifecycles and require baseline measurement plus trace sweeps before implementation.
- Lower-priority release-review hygiene that was not part of the release-blocker fix set.

## Non-Negotiable Rules

- No trace-to-engine hydration in committed replay code.
- No zone, route, frame, or "known failing trace" carve-outs for trace fixes.
- Shared behavior differences must be modeled as ROM state, object/profile state, or a `PhysicsFeatureSet`/equivalent feature flag.
- Object runtime assets must come from the user ROM, never from `docs/` fallback bytes.
- Tests use JUnit 5 only.
- If a trace frontier moves, update `docs/status/trace-frontier-log.md` with command, context, result, and first-error frame/field.
- If object/badnik fixes reveal a reusable pitfall, update the relevant `.agents/skills/.../rom-pitfalls.md` and `.claude/skills/.../rom-pitfalls.md`.
- Use `CHANGELOG.md`, discrepancy docs, AGENTS/CLAUDE docs, and commit trailers honestly when touched behavior warrants it.

---

## Confirmed Release-Blocking Problem Statements

### RB-1: Gameplay debug and cheat keys are live in release configs

`debug.flags.debugView=false` is documented as disabling runtime debug keys, but gameplay-affecting keys are still polled in `GameLoop` and `SpriteManager`. This exposes give-emeralds, Super Sonic, free-fly debug mode, level/act skips, special/bonus-stage entry, checkpoint teleport, and level select in normal release play.

Impact: accidental keypresses mutate game state in shipped default config.

Primary files:
- `src/main/java/com/openggf/GameLoop.java`
- `src/main/java/com/openggf/sprites/managers/SpriteManager.java`
- `src/main/java/com/openggf/configuration/SonicConfigurationService.java`
- `CONFIGURATION.md`

Required fix:
- Gate gameplay-affecting debug key polling on `SonicConfiguration.DEBUG_VIEW_ENABLED`.
- Add guard tests proving keys are inert when disabled and still work when enabled.

### RB-2: F12 art-viewer toggle freezes gameplay invisibly in release configs

`DebugOverlayManager.updateInput` runs unconditionally and toggles `OBJECT_ART_VIEWER` with F12. `GameLoop` freezes the level tick while the art viewer is enabled, but the debug UI is gated by `DEBUG_VIEW_ENABLED`, so default release configs can appear hung after a screenshot/debug key collision.

Impact: visible release hang/freeze with no clear recovery affordance.

Primary files:
- `src/main/java/com/openggf/GameLoop.java`
- `src/main/java/com/openggf/debug/DebugOverlayManager.java`
- `src/main/java/com/openggf/debug/DebugOverlayToggle.java`
- `src/main/java/com/openggf/Engine.java`

Required fix:
- Do not process debug overlay toggles when `DEBUG_VIEW_ENABLED=false`, or make art-viewer freeze conditional on debug being enabled.
- Remove or resolve the F9 collision between ring-bounds toggle and level-select key.

### RB-3: CNZ post-boss controller repeats one-shot restoration every frame

After capsule results complete but before cannon spawn, `CnzEndBossInstance` repeatedly calls `restoreLevelMusic()` and `restorePlayerControl()`. This restarts CNZ2 music every frame and clobbers player rolling/control state through the post-boss walk window.

Impact: supported S3K CNZ->ICZ route has broken music and control state.

Primary file:
- `src/main/java/com/openggf/game/sonic3k/objects/CnzEndBossInstance.java`

Required fix:
- Make music/control restoration one-shot at the ROM-appropriate handoff.
- Add a focused test that would fail with repeated `playMusic`/control release calls.

### RB-4: Pause and Player 2 Start share ENTER by default

`input.pause` and `input.player2.start` both default to ENTER. Pause handling runs before sprite input, so P2 Start is eaten by the pause early-return. Pause also runs in menu modes with no visible indicator.

Impact: P2 sidekick takeover is unusable by default; menu screens can appear frozen.

Primary files:
- `src/main/resources/config.yaml`
- `src/main/java/com/openggf/configuration/SonicConfigurationService.java`
- `src/main/java/com/openggf/GameLoop.java`

Required fix:
- Change one default binding so pause and P2 Start do not collide.
- Gate pause to gameplay modes.
- Render a minimal pause indicator or otherwise make pause state visible.
- Add config/default and input-ordering tests.

### RB-5: Digit key config bindings and writer round-trip corrupt number-row keys

KEY values parse raw integers before GLFW key names, so `"1"` resolves to key code `1` instead of `GLFW_KEY_1=49`. The YAML writer emits key names unquoted, so a correct code 49 round-trips to bare `1` and becomes dead.

Impact: valid user key bindings silently break.

Primary files:
- `src/main/java/com/openggf/configuration/SonicConfigurationService.java`
- `src/main/java/com/openggf/configuration/ConfigYamlWriter.java`
- `src/main/java/com/openggf/control/InputHandler.java`

Required fix:
- Resolve GLFW key names before raw integer parsing for KEY config values, or special-case single digit key names.
- Quote key names that YAML would parse as scalars.
- Add load and save/reload round-trip tests for `0` through `9`.

### RB-6: `display.fps` accepts zero and negative values

`Engine.loop()` divides by `targetFps` with no validation. `fps: 0` throws on startup; negative FPS creates an uncapped busy loop.

Impact: user-editable config can crash or unthrottle the engine.

Primary files:
- `src/main/java/com/openggf/Engine.java`
- `src/main/java/com/openggf/configuration/SonicConfigurationService.java`
- `src/main/java/com/openggf/configuration/ConfigCatalog.java`

Required fix:
- Clamp or validate FPS to at least 1.
- Prefer general INT range validation metadata if small enough; otherwise clamp at read/constructor boundary and add tests.

### RB-7: No game-over/continue flow at zero lives

When lives reach zero, the engine respawns indefinitely and pause is disabled by the zero-lives pause guard. Continues are tracked but not consumed. This affects all three games.

Impact: core supported gameplay flow diverges significantly from ROM behavior.

Primary files:
- `src/main/java/com/openggf/game/GameStateManager.java`
- `src/main/java/com/openggf/GameLoop.java`
- `src/main/java/com/openggf/sprites/managers/PlayableSpriteMovement.java`
- `docs/status/known-discrepancies.md`

Required fix:
- Implement the smallest ROM-faithful game-over/continue state flow that fits current architecture, or explicitly document the discrepancy if full implementation is deferred.
- At minimum, restore sane pause behavior if gameplay is intentionally allowed after zero lives.

---

## Sidekick CPU Trace-Parity Problem Statements

### SK-1: S3K sidekick lacks fresh Obj_Tails spawn and init-only first frame

S3K ROM creates a fresh `Obj_Tails` at every level load, zeros kinematics, runs `Tails_Init` on the first frame without physics/CPU, then enters routine `$00` on frame 2 and normal follow later. The engine currently preserves carried-in velocity and runs normal logic during `updateInit`.

Impact: live f1 frontiers in HCZ, ICZ, LBZ, and MGZ complete-run traces.

Primary files:
- `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java`
- `src/main/java/com/openggf/level/LevelManager.java`
- `src/main/java/com/openggf/game/sonic3k/Sonic3kLevelEventManager.java`
- `src/test/java/com/openggf/tests/trace/s3k/*`

Required fix:
- Add S3K sidekick fresh-spawn reset at level load.
- Add init-only first sidekick frame semantics.
- Add ICZ/SSZ dormant park entry with `object_control=$83` semantics at the routine-0 boundary. ICZ is now covered for the Sonic player-mode snowboard-intro bootstrap; SSZ remains to be checked when its trace is in scope.
- Verify against S3K complete-run first-frame traces and must-keep-green S3K tests.

### SK-2: S2 `controlLockLatchesLogicalInput` is disabled despite forced-input bypass

S2 ROM latches `Ctrl_1_Logical` while `Control_Locked`; the engine has the latch and a forced-input bypass, but the S2 feature flag remains false after a prior regression workaround.

Impact: MTZ2 and other control-lock routes under-accelerate sidekick follow history.

Primary files:
- `src/main/java/com/openggf/game/PhysicsFeatureSet.java`
- `src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java`
- `src/main/java/com/openggf/game/sonic2/objects/SignpostObjectInstance.java`

Required fix:
- Flip S2 flag only after focused red/green coverage demonstrates forced signpost input is not re-regressed.
- A/B EHZ1, MTZ2, WFZ, and SCZ trace tests.

### SK-3: Shared Tails CPU normal path contains ungated non-ROM carve-outs

The shared CPU code has S3K-trace-motivated or engine-artifact conditions applied to S2/S3K without feature gates or ROM state ownership:
- grounded+pushing `jumpingFlag` hold/clear carve-out,
- underwater push-pulse narrowing (covered by the SK-3 live-push-in-water slice and `sidekickPushBypassUsesGraceStatus`),
- airborne live/delayed push handoff (covered by the SK-3 airborne handoff slice),
- S3K-only obj_control bit-0 nudge gate applied to S2 (covered by `sidekickFollowNudgeBlockedByObjectControlBit0`),
- panic pinball-mode OR applied to S2 (covered by `sidekickPanicTreatsPinballModeAsSpindashFlag`).

Impact: plausible contributors to S2 ARZ/CNZ/HTZ/MTZ/OOZ sidekick frontiers and violates project trace rules.

Primary file:
- `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java`

Required fix:
- Prefer modeling the stale ROM state root cause instead of compensating in shared CPU code.
- If a divergence is real per game, introduce a narrow feature flag with S2/S3K disassembly citations.
- Add focused CPU parity tests for each gate before changing production code.

### SK-4: Panic/dead/despawn paths diverge from ROM

PANIC originally lacked the hurt/dead CPU-skip gate and inverted equality-facing. The audit also flagged post-`CheckDespawn` latch writes and dead-fall thresholds; current code models those via `refreshPanicDiagnosticInputBeforeDespawn()` and the deferred dead-fall marker path, now covered by focused tests.

Impact: early despawns and diagnostic divergence in death/hazard-heavy traces.

Primary files:
- `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java`
- `src/main/java/com/openggf/sprites/managers/PlayableSpriteMovement.java`

Completed fix:
- Preserve the completed PANIC hurt/dead skip and equality-facing behavior with focused CPU parity tests.
- Cover PANIC timeout despawn continuing to expose the ROM-visible DOWN latch on the marker frame.
- Cover S2 `Tails_Max_Y_pos+$100` and S3K `Camera_Y_pos+$100` deferred dead-fall marker thresholds.

### SK-5: S2 dead-Sonic flight and fly-in landing omit ROM side effects

Dead-Sonic flight originally routed through S3K-like auto-recovery and missed S2 status wipes, spindash clear, water target clamp, high-priority inheritance, and landing animation/status writes.

Impact: post-death sidekick windows diverge.

Primary files:
- `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java`
- `src/main/java/com/openggf/sprites/playable/TailsRespawnStrategy.java`

Completed fix:
- Route S2-style Tails dead-leader flight into the regular Tails fly-in strategy without running the respawn teleport.
- Restore S2 ROM side effects on dead-Sonic flight entry and landing: spindash clear, status wipe, water-clamped fly target path, Walk animation, priority, and solid-bit inheritance.
- Keep S3K on the existing auto-recovery path via `PhysicsFeatureSet.sidekickRespawnEntersCatchUpFlight()`.

---

## Medium-Risk Correctness Problem Statements

### MC-1: `CollisionSystem.pendingOddSensorFallbackAngles` is not reset or rewind-captured

The cross-frame odd-sensor fallback map is engine-invented, absent from `resetState`, and absent from rewind snapshots.

Required fix:
- Clear it in `CollisionSystem.resetState`.
- Move/capture/clear it on rewind seek, or document it in `docs/status/known-discrepancies.md`.

### MC-2: Seven objects hand-roll MarkObjGone coarse-camera checks incorrectly

Several S2/S3K objects omit the ROM `-0x80` coarse-back margin or masking. AIZ falling log and S3K signpost are on supported S3K content.

Required fix:
- Replace hand-rolled checks with `AbstractObjectInstance.isInRangeAt` or a shared coarse-back helper.
- Prioritize AIZ falling log and S3K signpost, then S2 Conveyor, SlidingSpikes, Tornado, Nebula, and Cloud.

### MC-3: Per-column BG VScroll is applied twice

The VScroll texel-count fix corrected sampling, but an existing path still applies per-column BG VScroll both in the BG tile pass and the parallax scroll pass.

Required fix:
- Determine ownership of per-column VScroll and remove the duplicate application.
- Verify standard and widescreen rendering tests.

Status:
- Completed in `bugfix/ai-release-remediation`: BG per-column VScroll is owned by the parallax compositing pass, with a source-level regression test preventing the BG tile FBO pass from consuming the same buffer.

### MC-4: Palette rotation disable flag can leak across level reload

`PaletteOwnershipRegistry.paletteRotationDisabled` survives frame clears and only resets on full gameplay context teardown.

Required fix:
- Reset at level-load/act-transition boundaries or require writers to release it in teardown.

### MC-5: Editor/save/rewind backlog items

Outstanding release-review entries include MutableLevel disabling `instanceof Sonic3kLevel` event gates, editor save transient I/O handling, atomic move fallback, wrong-length chunk/block drops, and unbounded rewind/input history.

Required fix:
- Triage individually after release-blocker and sidekick work; add focused tests before production changes.

Status:
- Completed in `bugfix/ai-release-remediation`: editor-save transient read failures, atomic-move fallback, and wrong-length block/chunk state validation.
- Completed in `bugfix/ai-release-remediation`: live rewind keyframe, audio logical keyframe, and input history are pruned to a configurable `rewind.historySeconds` horizon aligned to retained keyframe boundaries.
- Completed in `bugfix/ai-release-remediation`: persisted editor edits are temporarily skipped for S3K level loads so the runtime keeps its concrete `Sonic3kLevel` event/overlay surface until `MutableLevel` supports those overlays directly.

### MC-6: Capture encoder stop can hang on stalled ffmpeg

`EncoderSink.stop()` delivered its poison pill with a bounded offer loop but then waited forever on `worker.join()`. If ffmpeg stopped consuming stdin, the worker could remain blocked in `FfmpegEncoder.encode()` and the trace-capture tool would never finish.

Required fix:
- Bound the stop join, abort the encoder on timeout, and surface a `CaptureException` rather than hanging.

Status:
- Completed in `bugfix/ai-release-remediation`: `EncoderSink.stop()` now times out, calls `encoder.abort()`, and throws; `EncoderSinkTest` covers the stalled-worker path with a short timeout.

### MC-7: Live rewind rebuilds full level tilemaps on unchanged geometry

`LevelRewindSnapshotAdapter.restore()` invalidated all manager-owned tilemaps after every restore, even when the restored block, chunk, and map arrays were the same references already installed in the live level. Holding live rewind could therefore force redundant foreground/background cache rebuilds and texture uploads every rendered frame.

Required fix:
- Compare the current level geometry references with the snapshot references before restore.
- Keep invalidating tilemaps when any geometry reference changes.
- Skip invalidation when all geometry references are already current while still bumping the COW epoch and restoring frame/HUD/checkpoint state.

Status:
- Completed in `bugfix/ai-release-remediation`: `LevelRewindSnapshotAdapter` now invalidates only on geometry-reference swaps, with regression coverage for both changed-reference and same-reference restores.

### MC-8: Deferred GL command groups alias caller-owned reusable command lists

`GLCommandGroup` stored the mutable `List<GLCommand>` passed by callers. `ObjectManager` reuses one fallback line-command list across bucket draws before `GraphicsManager.flushWithCamera()` executes the deferred groups, so earlier groups could render the last bucket's line commands instead of their own.

Required fix:
- Snapshot the command list at `GLCommandGroup` construction.
- Keep the command objects themselves shared; only list membership/order needs isolation.
- Add a focused regression test that mutates the source list after construction and verifies the group retains the original commands.

Status:
- Completed in `bugfix/ai-release-remediation`: `GLCommandGroup` now owns an immutable list snapshot and `TestGLCommandGroup` covers caller-side list reuse.

### MC-9: Per-frame key binding reads repeatedly parse strings and allocate fallback exceptions

`GameLoop` reads several key bindings from `SonicConfigurationService.getInt()` every frame. After the digit-key correctness fix, string-backed key bindings still traversed name resolution and fallback parsing repeatedly, and invalid string values could allocate `NumberFormatException` objects each frame.

Required fix:
- Cache resolved integer values inside `SonicConfigurationService`.
- Invalidate the cache when config values change, defaults reset, enum validation rewrites values, or display-aspect derivation refreshes transient integer overlays.
- Add regression coverage proving a cached key is re-resolved after `setConfigValue()`.

Status:
- Completed in `bugfix/ai-release-remediation`: `SonicConfigurationService` now caches `getInt()` results in an `EnumMap`, clears the cache at mutation/derived-overlay boundaries, and `TestConfigKeyNameResolution` verifies cache invalidation.

### MC-10: SoundTestApp interactive mode races EDT against the audio executor

In interactive mode, Swing EDT handlers called `playSmps`/`playSfxSmps`/`stopPlayback`/`toggleMute`/`toggleSolo`/`setSpeedShoes` directly while a `ScheduledExecutorService` thread ran `backend::update` every 16ms — unsynchronized SMPS driver swaps and OpenAL source operations from two threads. Shutdown used `shutdownNow()` followed immediately by `backend.destroy()` with no `awaitTermination`, so an in-flight `update()` could touch AL sources mid-deletion, and a redundant shutdown hook double-destroyed the backend on clean exit. Dev tooling only; the shipped game drives the backend single-threaded from the game loop.

Required fix:
- Marshal all EDT-originated backend mutation onto the executor thread that drives `update()`.
- Shut down with `shutdown()` + `awaitTermination` before `destroy()`, and remove the shutdown hook on the normal exit path.

Status:
- Completed in `bugfix/ai-release-remediation`: `InteractiveState` routes backend commands through `onAudioThread(...)` onto the shared audio executor; teardown drains queued commands before a single `destroy()`; display-only reads (`getDebugState`/`isMuted`/`isSoloed`) remain EDT-polled.

---

## Performance Problem Statements

The performance audit found real hot-path waste, but most items are not release blockers and must be implemented as independently reversible, evidence-backed phases. Any behavior-sensitive optimization is dropped unless old-vs-new equivalence is proven by tests, trace sweeps, or deterministic audio/render evidence.

### PERF-0: Missing baseline measurement

Performance work needs a stable baseline before any optimization claims are credible.

Required fix:
- Capture a clean-source baseline: S3K green-list tests and current `*TraceReplay` frontiers.
- Capture frame-time p50/p99/max and keyframe-spike data over a deterministic driver.
- Capture held-rewind allocation data and GPU upload counters.
- Record durable results under `docs/performance/` or this plan, and update `docs/status/trace-frontier-log.md` for trace sweeps.

### PERF-1: Exact-equivalence hot-path overhead

Verified low-risk waste includes broad zero-filling in `BlipDeltaBuffer`, synchronized read-only `SessionManager` accessors, repeated `GroundSensor` service resolution, uncached generic rewind eligibility/reflection, unconditional render-bucket invalidation, boxed ring active-index storage, and audio-runtime scratch churn.

Required fix:
- Land in small commits with equivalence tests where behavior cannot go red first.
- Preserve byte-identical audio and rewind capture behavior.
- Run focused tests plus S3K green-list tests and trace replay sweep after the phase.

### PERF-2: Over-broad rendering uploads

The renderer uploads whole atlas pages and rebuilds broad BG windows where only small DPLC regions or one scroll column changed. SAT replay and scroll buffers also allocate or draw more than necessary.

Required fix:
- Add dirty-rect or dirty-slot atlas uploads.
- Add incremental BG window rebuild for single-column scroll.
- Batch SAT replay and remove boxed atlas lookup/native upload-buffer churn.
- Verify visually and with trace sweeps; measure upload-byte reduction.

### PERF-3: Held-rewind allocation storm and keyframe reflection spikes

Held rewind rebuilds audio driver state and object instances on intermediate backward frames, while keyframe capture repeatedly pays reflection and codec lookup costs.

Required fix:
- Restore matching objects in place only after auditing non-captured fields and proving full-state equivalence.
- Defer intermediate audio logical restore during held rewind, restoring once at release/seek commit.
- Bound and index `AudioCommandTimeline`.
- Add typed compact access plans and reduce restore-path blob copies.
- Prove rewind determinism and measure allocation reduction before claiming completion.

### PERF-4: Behavior-sensitive cleanup needs explicit evidence gates

Audio fade fallback removal, resampler math changes, trig-table routing, and object/render scratch reuse can alter observable behavior if implemented loosely.

Required fix:
- Compare closed-form tempo math and resampler output against current algorithms.
- Remove fade fallback only if deterministic audio output is sample-identical.
- Route trig calls through ROM lookup tables only with disassembly citations for each call site.
- Keep object/render scratch ownership local and never expose mutable scratch to callers.

---

## Documentation and Release Hygiene Problem Statements

### DOC-1: S3K discrepancy registry is stale for AIZ2 battleship wrap

`docs/S3K_KNOWN_DISCREPANCIES.md` still says post-bombing wrap uses `$80`, but code now uses ROM `$200`.

Required fix:
- Rewrite or remove the stale entry and record current state honestly.

### DOC-2: AGENTS/CLAUDE docs are stale

AGENTS says `debug.flags.debugView` defaults true; CLAUDE understates S3K event handler coverage and misclassifies `debugOverlay()`.

Required fix:
- Update AGENTS/CLAUDE in the same logical change as debug gating/docs cleanup.

### DOC-3: Temporary release review artifacts are tracked

`fable-arch-review.md` and `docs/tmp-0.6-release-tasks.md` are tracked scratch/review artifacts. The current `.tmp.md` audit files are untracked and must stay that way.

Required fix:
- Remove tracked scratch files before release or move them to untracked/ignored local notes.
- Add the unrelated gumball singleton removal to `CHANGELOG.md` if it remains part of a release commit history.

### DOC-4: Config template misses documented keys

Bundled `config.yaml` omits `input.player1.start` and `capture.*` keys.

Required fix:
- Add missing keys and a capture section title.

### DOC-5: S1 event source comments mislabel implemented behavior as TODO work

`Sonic1SBZEvents` still said the SBZ2 boss/collapsing-floor sequence was a TODO and that boss objects were not implemented, even though the handler now spawns `Sonic1FalseFloorInstance`, `Sonic1ScrapEggmanInstance`, and `Sonic1FZBossInstance`. `Sonic1LZWaterEvents.checkWaterSlide()` also described itself as a TODO stub despite the implemented chunk-ID matching and inertia application path.

Required fix:
- Rewrite the SBZ/FZ class comment to describe the implemented routines and keep only the true FZ pattern-preload TODO.
- Rewrite the LZ water-slide Javadoc to describe the current chunk-ID sampling contract.

Status:
- Completed in `bugfix/ai-release-remediation`: stale TODO/stub language was removed and replaced with current implementation notes.

---

## Implementation Tasks

### Task 1: Establish Branch and Durable Plan

**Files:**
- Create: `docs/architecture/plans/2026-06-11-release-remediation.md`

- [x] **Step 1: Create branch `bugfix/ai-release-remediation` from current worktree.**

Run: `git switch -c bugfix/ai-release-remediation`

- [x] **Step 2: Create this consolidated remediation plan.**

This file replaces the two untracked `.tmp.md` files as the durable planning artifact.

- [x] **Step 3: Verify temp audit files remain untracked and are not staged.**

Run: `git status --short`

Expected: `SIDEKICK_CPU_AUDIT.tmp.md`, `RELEASE_REVIEW_FINDINGS.tmp.md`, and the folded `docs/tmp-*` scratch trackers are absent from the workspace unless intentionally recreated as untracked local notes.

### Task 2: Debug-Key and Art-Viewer Release Gating

**Files:**
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Modify: `src/main/java/com/openggf/sprites/managers/SpriteManager.java`
- Modify: `src/main/java/com/openggf/debug/DebugOverlayManager.java`
- Test: existing or new config/input tests under `src/test/java/com/openggf/tests` or `src/test/java/com/openggf/configuration`

- [x] **Step 1: Write failing tests for disabled debug keys.**

Test should assert that with `DEBUG_VIEW_ENABLED=false`, debug shortcuts do not call level select, checkpoint teleport, special/bonus stage entry, give emeralds, Super debug, or sprite debug mode.

- [x] **Step 2: Write failing test for F12 art viewer.**

Test should assert that `OBJECT_ART_VIEWER` cannot enable and cannot freeze gameplay while debug view is disabled.

- [x] **Step 3: Implement a single debug-shortcut gate.**

Route all gameplay-affecting debug key polling through a helper such as `debugShortcutsEnabled()` or inject the existing config state into the call sites.

- [x] **Step 4: Run focused tests.**

Run the new/changed debug input tests.

### Task 3: CNZ Post-Boss One-Shot Restore

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/CnzEndBossInstance.java`
- Test: `src/test/java/com/openggf/game/sonic3k/objects/...`

- [x] **Step 1: Write a failing test that advances the post-defeat window for multiple frames.**

Assert `playMusic(CNZ2)` and player-control release happen once after capsule results completion and before cannon spawn.

- [x] **Step 2: Implement a one-shot latch at the ROM handoff boundary.**

Do not repeatedly clear rolling/control state during player walk.

- [x] **Step 3: Run focused CNZ boss tests and S3K route guards.**

Run CNZ post-boss test plus `TestS3kAiz1SkipHeadless`, `TestSonic3kLevelLoading`, `TestSonic3kBootstrapResolver`, and `TestSonic3kDecodingUtils`.

### Task 4: Input Config Defaults and Validation

**Files:**
- Modify: `src/main/resources/config.yaml`
- Modify: `src/main/java/com/openggf/configuration/SonicConfigurationService.java`
- Modify: `src/main/java/com/openggf/configuration/ConfigYamlWriter.java`
- Modify: `src/main/java/com/openggf/configuration/ConfigCatalog.java`
- Modify: `src/main/java/com/openggf/GameLoop.java`

- [x] **Step 1: Write failing digit-key parse and round-trip tests.**

Cover `"0"` through `"9"` and integer GLFW key codes for number-row keys.

- [x] **Step 2: Write failing FPS validation tests.**

Cover `fps: 0`, negative FPS, and valid FPS.

- [x] **Step 3: Write failing default-collision tests for pause/P2 Start and config template keys.**

Assert defaults do not collide and template includes documented keys.

- [x] **Step 4: Implement parsing/writer/default/validation fixes.**

Keep unknown-name fallback behavior intact.

- [x] **Step 5: Run focused config tests.**

Run all configuration tests and any GameLoop pause tests.

### Task 5: Sidekick CPU S3K Spawn/Init Fix

**Files:**
- Modify: `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java` if level-load ownership requires it
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kLevelEventManager.java`
- Test: sidekick CPU unit tests and S3K trace tests

- [x] **Step 1: Write failing unit coverage for S3K fresh spawn reset and init-only first frame.**

Assert first update does not apply normal follow nudge/physics and kinematics are reset on level load.

- [x] **Step 2: Add dormant park sentinel tests.**

Assert object-control `$83`-equivalent state suppresses same-frame physics through the semantic offscreen sentinel path.

- [x] **Step 3: Implement ROM-modeled routine-0 boundary behavior.**

Use feature/provider state, not generic zone carve-outs in shared follow code.

- [ ] **Step 4: Run S3K focused traces.**

Run HCZ/ICZ/LBZ/MGZ complete-run traces where available plus must-keep-green S3K tests.

### Task 6: Sidekick CPU Shared Carve-Out Cleanup

**Files:**
- Modify: `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java`
- Modify: `src/main/java/com/openggf/game/PhysicsFeatureSet.java` if feature flags are needed
- Test: sidekick CPU parity tests and S2/S3K traces

- [x] **Step 1: Write failing tests for ROM jumping-flag lifecycle.**

Assert held jump is output while `jumpingFlag` is set and flag clears on grounded regardless of pushing.

- [x] **Step 2: Write failing tests for live-push bypass in water.**

Assert S2 live pushing + delayed non-pushing skips follow steering regardless of water and x speed, unless a feature flag explicitly says otherwise.

- [x] **Step 3: Write failing tests for S2/S3K obj_control nudge gate split and panic pinball split.**

S2 must not consume S3K-only bit-0 nudge suppression or pinball panic OR.

- [ ] **Step 4: Implement minimal ROM-faithful behavior or feature flags.**

Every branch must cite S2/S3K disassembly lines in comments or surrounding docs.

- [ ] **Step 5: Run S2 and S3K sidekick trace guards.**

Start with EHZ1, MTZ2, WFZ, SCZ, ARZ/CNZ/HTZ, then S3K AIZ/CNZ/MGZ.

### Task 7: Sidekick Panic/Death/Flying Cleanup

**Files:**
- Modify: `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java`
- Modify: `src/main/java/com/openggf/sprites/managers/PlayableSpriteMovement.java`
- Modify: `src/main/java/com/openggf/sprites/playable/TailsRespawnStrategy.java`

- [x] **Step 1: Write focused tests for PANIC hurt/dead skip and dead-fall threshold.**

Assert counters freeze during hurt/dead PANIC and dead-fall despawn follows `Tails_Max_Y_pos+$100`.

- [x] **Step 2: Write failing tests for S2 dead-Sonic flight side effects.**

Cover spindash clear, status wipe, water clamp, landing animation/status writes.

- [x] **Step 3: Implement ROM side effects with feature flags where needed.**

- [ ] **Step 4: Run death/hazard trace guards.**

Prioritize MTZ/MCZ/OOZ routes with sidekick death windows.

### Task 8: Medium-Risk Framework/Object Fixes

**Files:**
- Modify: `src/main/java/com/openggf/physics/CollisionSystem.java`
- Modify: affected object files listed in MC-2
- Modify: `src/main/java/com/openggf/game/palette/PaletteOwnershipRegistry.java` or level-load owner
- Modify: rendering files for MC-3

- [x] **Step 1: Add reset/rewind tests for odd-sensor fallback state.**
- [x] **Step 2: Add object despawn-window tests for coarse-back margin.**
- [x] **Step 3: Add palette flag reset test.**
- [x] **Step 4: Add/render VScroll ownership test.**
- [x] **Step 5: Implement each minimal fix and run focused tests after each.**

### Task 9: Documentation and Hygiene

**Files:**
- Modify: `docs/S3K_KNOWN_DISCREPANCIES.md`
- Modify: `docs/status/known-discrepancies.md`
- Modify: `AGENTS.md`
- Modify: `CLAUDE.md`
- Modify: `CONFIGURATION.md`
- Modify: `CHANGELOG.md`
- Delete or move: `fable-arch-review.md`, `docs/tmp-0.6-release-tasks.md`

- [x] **Step 1: Update discrepancy docs for AIZ2 wrap and any deferred game-over behavior.**
- [x] **Step 2: Update AGENTS/CLAUDE debug default and service-surface statements.**
- [x] **Step 3: Remove tracked scratch artifacts.**
- [x] **Step 4: Update CHANGELOG for user-visible fixes and prior unmentioned gumball refactor if required.**
- [x] **Step 5: Keep `.tmp.md` audit inputs uncommitted.**

### Task 10: Verification Sweep

**Commands:**
- `mvn "-Dtest=TestTraceReplayInvariantGuard" test`
- `mvn "-Dtest=TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils" test`
- `mvn "-Dtest=*TraceReplay" "-DfailIfNoTests=false" test`
- `mvn test`

- [ ] **Step 1: Run focused tests after every task.**
- [x] **Step 2: Run trace replay invariant guard before any trace-related completion claim.**
- [x] **Step 3: Run S3K must-keep-green tests after S3K sidekick/object/event changes.**
- [x] **Step 4: Run broader trace sweep after sidekick fixes.**
- [ ] **Step 5: Run full Maven test suite before final completion.**
- [x] **Step 6: Update `docs/status/trace-frontier-log.md` for every frontier movement/regression/sweep.**

### Task 11: Performance Baseline and Exact-Equivalence Phase

**Files:**
- Optional create: `docs/architecture/validation/performance/2026-06-11-baseline.md`
- Modify only with tests: audio/session/collision/rewind/render/ring files named in PERF-1

- [ ] **Step 1: Confirm clean correctness baseline.**

Run the S3K green list and current trace sweep, recording known failures/frontiers before perf work.

- [ ] **Step 2: Capture frame-time, rewind-allocation, and GPU-upload baseline.**

Use existing deterministic drivers and scratch counters where needed. Do not commit temporary counters unless they become debug/test helpers.

- [ ] **Step 3: Implement only exact-equivalence quick wins.**

Start with PERF-1 items whose behavior is pinned by unit/equivalence tests.

- [ ] **Step 4: Verify no trace or deterministic output regression.**

Run focused tests, S3K green list, and `*TraceReplay` sweep before committing.

### Task 12: Rendering Upload Reduction Phase

**Files:**
- `src/main/java/com/openggf/graphics/PatternAtlas.java`
- `src/main/java/com/openggf/level/LevelTilemapManager.java`
- `src/main/java/com/openggf/graphics/GraphicsManager.java`
- `src/main/java/com/openggf/graphics/HScrollBuffer.java`
- `src/main/java/com/openggf/graphics/VScrollBuffer.java`

- [ ] **Step 1: Add tests/counters for dirty atlas and BG-scroll uploads.**
- [ ] **Step 2: Implement dirty atlas uploads and incremental BG window rebuilds.**
- [ ] **Step 3: Batch SAT replay and remove upload-buffer allocation churn.**
- [ ] **Step 4: Run visual validation plus trace sweeps.**

### Task 13: Rewind and Audio Performance Phase

**Files:**
- `src/main/java/com/openggf/level/objects/ObjectManager.java`
- `src/main/java/com/openggf/game/rewind/*`
- `src/main/java/com/openggf/audio/**`

- [ ] **Step 1: Audit object non-captured fields before in-place restore.**
- [ ] **Step 2: Add release-equivalence tests for held-rewind audio restore deferral.**
- [ ] **Step 3: Bound `AudioCommandTimeline` and reduce rewind restore copies.**
- [ ] **Step 4: Run rewind determinism tests and full trace sweep.**

### Task 14: Evidence-Gated Performance Cleanup

**Files:**
- Audio math, ring-buffer, object scratch, trig, and level math files named in PERF-4

- [ ] **Step 1: Prove closed-form tempo/resampler/ring-buffer equivalence.**
- [ ] **Step 2: Drop or implement fade fallback and trig-table items based on evidence.**
- [ ] **Step 3: Measure final performance deltas and update changelog/trace log.**

---

## Current Prioritization

1. RB-1/RB-2 debug-key and art-viewer release gating.
2. RB-3 CNZ post-boss one-shot restore.
3. RB-4/RB-5/RB-6 input config defaults, digit keys, pause behavior, and FPS validation.
4. SK-1 S3K sidekick fresh spawn/init-only first frame.
5. SK-2/SK-3/SK-4/SK-5 sidekick parity cleanup.
6. MC-1 through MC-9 medium-risk framework/object fixes.
7. PERF-0 through PERF-4 performance remediation, sequenced by evidence and reversibility.
8. DOC-1 through DOC-5 documentation and release hygiene.

This order fixes shipped user-facing regressions before deep trace frontier work, and fixes correctness/parity before performance cleanup whose success depends on stable trace baselines.
