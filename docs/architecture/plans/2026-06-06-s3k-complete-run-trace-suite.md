# S3K Complete-Run Trace Suite (per-zone, pause-aware) — Implementation Plan

> **For agentic workers:** staged build. Stages are sequential; verify each before the next.

**Goal:** Generate a per-zone S3K complete-run trace suite from the single `s3k-complete-sonic-tails.bk2` (Sonic+Tails, 466,334 frames, AIZ→Doomsday), where each zone's trace spans **act1 → seamless act1→act2 transition → act2 → the act2→next-zone exit handoff**, and the engine replays it faithfully **including an accidental pause in HCZ**.

**Architecture:** Mirror the S1 complete-run tooling but segment per **zone** (not per act). Reuse the existing `_movies/` + `source_bk2` dedup framework. Add ROM-accurate in-game pause to the replay tick so the HCZ frozen window stays frame-aligned (comparison-only, no tolerance band).

**Decisions locked (user):** segment end = *through the zone-exit handoff*; coverage = *all segments* (incl. late-game specials); pause handling = *approach A (implement pause in replay)*.

---

## Segment map (from discovery, `C:/tmp/s3k_level_map.txt`)

Segment N = `[zone N first level frame, zone N+1 first level frame)` — includes the act1→act2 seamless transition and the trailing act2→next-zone `0x8C` handoff.

| # | Zone (id) | bk2_frame_offset | act1→act2 | end (excl) |
|---|---|---|---|---|
| 1 | AIZ (0) | 941 | 7242 | 27170 |
| 2 | HCZ (1) | 27170 | 37071 | 58653 | ← **accidental pause inside** |
| 3 | MGZ (2) | 58653 | 74663 | 98052 |
| 4 | CNZ (3) | 98052 | 112021 | 138117 |
| 5 | ICZ (5) | 138117 | 150438 | 163511 |
| 6 | LBZ (6) | 163511 | 185238 | 209756 |
| 7 | MHZ (7) | 209756 | 222612 | 237913 |
| 8 | FBZ (4) | 237913 | 260311 | 282195 |
| 9 | SOZ (8) | 282195 | 311432 | 341703 |
| 10 | LRZ (9) | 341703 | 364076 | 380459 |
| 11 | Z22 (HPZ/special) | 380459 | (internal 0x8C) | 396720 |
| 12 | Z10 (HPZ/special) | 396720 | single | 415362 |
| 13 | SSZ (11) | 415362 | 433657 | 459510 |
| 14 | Z23 (DEZ/special) | 459510 | single | 465614 |
| 15 | Z13 (DDZ) | 465614 | — | 466334 (END) |

S3K RAM: `game_mode 0xF600` (level family = `(gm & 0xDF? )`; `0x0C/0x4C/0x8C` masked to `0x0C`), `zone 0xFE10`, `act 0xFE11`, `apparent_act 0xEE4F`, `player_mode 0xFF08`, `Game_paused 0xFFF63A` (`docs/skdisasm/s3.asm:1694` `Pause_Loop`).

---

## Stage 1 — Per-zone auto-segment recorder + fixtures

**Files:** Create `tools/bizhawk/s3k_complete_run_recorder.lua` (based on `s3k_trace_recorder.lua`: keep its full S3K CSV schema + aux events — cpu_state, interact_state, oscillation, object_state, velocity/position writes, control_lock — needed for Tails/CPU parity).

Extension over the single-arm recorder:
- Track `current_segment_zone`. Re-arm a **new segment** when a fresh level entry for a *different* zone occurs: `is_level_family_mode(gm)` becomes the level `0x0C` AND `zone != current_segment_zone`. (Do **not** re-arm on act change — act1→act2 is seamless and must stay in one segment.)
- Per segment: set `OUTPUT_DIR = BASE/<zoneToken>/`, reset row counters, capture `bk2_frame_offset = emu.framecount()`, write that segment's `metadata.json` (game `s3k`, `zone_id`, `act` per the zone, `source_bk2 = "s3k-complete-sonic-tails.bk2"`, `characters [sonic, tails]`, `main_character sonic`, the S3K `aux_schema_extras`).
- The trailing `0x8C` handoff frames (zone register already = next zone, gm `0x8C`) are recorded into the **current** segment until the next zone's `0x0C` arms the next segment (gives "through zone-exit handoff").
- Record CSV + aux every frame while armed (frozen frames during the HCZ pause are recorded verbatim — `Game_paused` aux flag added so the pause window is visible to diagnostics, comparison-only).
- Stop + `client.exit()` at movie end.

**zoneToken map** (engine-zone naming for dirs/tests): use ROM zone id → token: 0 aiz,1 hcz,2 mgz,3 cnz,4 fbz,5 icz,6 lbz,7 mhz,8 soz,9 lrz,11 ssz,13 ddz; specials 22→`hpz22`,10→`hpz`,23→`dez23` (final names confirmed against engine zone registry at wiring time).

**Run:** `EmuHawk.exe --chromeless --lua=…/s3k_complete_run_recorder.lua --movie=…/s3k-complete-sonic-tails.bk2 s3k.gen` (long, ~full-movie pass). Copy the 15 `trace_output/<zone>/` dirs into `src/test/resources/traces/s3k/<zone>_completerun/` (physics.csv.gz + aux_state.jsonl.gz + metadata.json). The shared bk2 already lives at `src/test/resources/traces/s3k/_movies/s3k-complete-sonic-tails.bk2`.

**Verify:** each segment dir has metadata + physics rows; row counts ≈ window sizes; `source_bk2` set; no per-dir bk2 copy.

## Stage 2 — Approach A: ROM-accurate in-game pause in the replay tick

**Files:** the gameplay per-frame update path used by trace replay (`HeadlessTestFixture.stepFrameFromRecording` → object/physics/camera tick) and live play (`Engine`/`GameLoop`). Add a `Game_paused`-style state distinct from the existing loop-level `paused`/`userPaused`.

- Model `Pause_Loop` (`docs/skdisasm/s3.asm:1694-1757`): Start press toggles in-game pause; while paused, **skip the object/physics/camera update** for the frame but still advance the frame counter and consume input; unpause on the ROM's unpause input.
- Gate on input + pausable game-state (semantic), shared across games (pause is universal in S1/S2/S3K); if the trigger/unpause differs per game, use a `PhysicsFeatureSet`/owning-boundary flag — never a frame/zone carve-out.
- Cross-game: confirm S1/S2 trace baselines unaffected (their traces have no pause press; the path is inert unless Start is pressed during gameplay).

**Verify:** unit-level pause toggle test (paused frame leaves player/object state unchanged, frame counter advances); existing S2/S3K green traces stay green (EHZ1, Arz, Wfz, S3k Aiz/Cnz/Mgz unchanged).

## Stage 3 — Per-zone test wiring + dedup

**Files:** Create `TestS3k<Zone>CompleteRunTraceReplay` per segment (extends `AbstractTraceReplayTest`; `game()=SONIC_3K`, `zone()`/`act()` = engine zone for that segment's start act, `traceDirectory()` = the segment dir). Confirm `zone()` is the **engine** zone index (S3K zone_id == engine progression for S3K per `romZoneToProgressionIndex`), matching the loaded level via engine load logs — same pitfall guard as the S1 suite.

**Verify:** `TraceCatalog` picks each up (shared-bk2 resolution already supported); test-mode picker lists them.

## Stage 4 — Per-segment frontier verification

**Run** each `TestS3k<Zone>CompleteRunTraceReplay`; record first-error frame in `docs/status/trace-frontier-log.md`. **HCZ specifically:** confirm it replays *through* the pause window (no divergence at the pause frame, frame-aligned afterward) thanks to Stage 2.

**Docs/commit:** CHANGELOG + frontier log; trailers; commit on `feature/ai-s3k-complete-run-traces`; integrate to develop via the isolated-worktree compose-verify + FF-push flow. Note S3K is partly Codex's domain — coordinate so the shared `_movies` bk2 and any S3K test overlaps converge.

---

## Risks / notes
- Long recording pass (full 466k-frame movie). One pass yields all 15 segments.
- Late-game specials (z22/10/23) have odd act structure (internal `0x8C`, single act); the per-zone rule still segments them by zone — wiring may need per-case `zone()/act()`.
- The new bk2 is S3K-only; it must not perturb S1/S2. The pause impl (Stage 2) is the only engine change and must keep all existing traces green.
