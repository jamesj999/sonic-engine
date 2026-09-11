# Multi-Stage Trace Runs — Blue Spheres Plan: S3K Special-Stage Trace Capture + Replay

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give S3K blue-spheres special stages a real trace pipeline: the `s3k_special_stage` schema + recorder row writer (the SS detour becomes a real segment), engine additions #3 (comparison snapshot) and #6-S3K (headless-loadability proof), the per-segment replay harness (VBlank pacing + lag skips, NO PC hooks), and the live `freshLoadSignal` wire-up — per the spec's blue-spheres roadmap entry.

**Architecture (verified 2026-07-19 exploration):** The manager exposes rich getters but no aggregate snapshot; the player is subpixel fixed-point on a toroidal grid (256 = 1 cell). The SS RAM block is a phase overlay at BizHawk base `0xE400` (`Stat_table`/`Pos_table_P2`) with hand-derived offsets (table below) — live verification against the first real capture is MANDATORY before the schema is declared frozen. The recorder's v6.30 detour path currently finalizes with NO rows and one merged transition; this plan flips the SS to a real `special_stage` segment with bonus-style entry+exit transitions (invariant `#transitions == #segments-1` is preserved: +1 segment, +1 transition). The harness mirrors `S2SpecialStageReplayHarness` minus everything S2-specific: single-arg `initializeStage(int)` (no startup policy, no lag compensator exists on S3K), stage index pinned from segment metadata (never `consumeStageIndexForEntry` — that consumes live progression), frameCounter compared against row ordinal (no ROM cell backs it).

**Tech Stack:** Java 21 + JUnit 5 (Jupiter only); BizHawk lua (recorder).

## Global Constraints

- Spec: `docs/architecture/designs/2026-07-18-multi-stage-trace-runs-design.md` (blue-spheres plan = additions #3, #6-S3K + schema + recorder writer + harness + freshLoadSignal). MVP red-allowed comparator per owner decision 2; slots/S1/S2 are later plans.
- Comparison-only invariant; recorder captures generously, comparator binds incrementally.
- **Pacing: VBlank + lag-row skips ONLY.** No RunObjects PC hooks; escalate only if a real divergence report shows pass-bisection artifacts (spec §Pacing).
- Lua: ALL new top-level recorder state/constants are GLOBALS (200-local budget; bundled parser gate `docs/skdisasm/build_tools/lua/lua.exe -e "assert(loadfile('tools/bizhawk/s3k_complete_run_recorder.lua'))"`).
- JUnit 5 only; commit policy as prior plans (trailer block; src/main feat → `Changelog: updated` + CRLF-verified CHANGELOG.md; stage exact paths; every commit ends with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01LPPPMPSUQBgYpxpA82bad5`).
- Recordings absent: the replay test lands skip-if-missing on `src/test/resources/traces/s3k/special_stage/` (dedicated) and activates for run segments via the chain later; the RAM map carries a documented VERIFY-ON-FIRST-CAPTURE obligation (self-check prints in the recorder + a frontier-log note) because no SS-entering bk2 exists to probe today.
- Guard awareness: new src/main trace classes may trip `TestBuildToolingGuard` (profile-gate patterns) and the SS harness/test naming must respect `TestTraceReplayInvariantGuard` (register the new abstract base if the test ends in `*TraceReplay.java`). Register per convention with justification; never weaken.

## Verified S3K SS RAM map (BizHawk `mainmemory` addresses; phase overlay base `0xE400` = `Stat_table`, `sonic3k.constants.asm:331,1012-1057`)

| Column | Symbol | Addr | Size |
|---|---|---|---|
| anim_frame | Special_stage_anim_frame | 0xE420 | u16 |
| x_pos | Special_stage_X_pos | 0xE422 | u16 |
| y_pos | Special_stage_Y_pos | 0xE424 | u16 |
| angle | Special_stage_angle | 0xE426 | u8 |
| velocity | Special_stage_velocity | 0xE428 | s16 |
| turning | Special_stage_turning | 0xE42A | u8 |
| jumping | Special_stage_jumping | 0xE432 | u8 |
| fade_timer | Special_stage_fade_timer | 0xE433 | u8 |
| spheres_left | Special_stage_spheres_left | 0xE438 | u16 |
| ring_count | Special_stage_ring_count | 0xE43A | u16 |
| rings_left | Special_stage_rings_left | 0xE442 | u16 |
| rate | Special_stage_rate | 0xE444 | u16 |
| rate_timer | Special_stage_rate_timer | 0xE43E | u16 |
| clear_timer | Special_stage_clear_timer | 0xE44A | u16 |
| clear_routine | Special_stage_clear_routine | 0xE44C | u8 |
| started | Special_stage_started | 0xE450 | u8 |

CSV schema (`ss_csv_version: 1`, profile `s3k_special_stage`): `frame,input,input_p2,lag,` + the 16 columns above **in TABLE order — note the table is deliberately field-grouped, not address-ordered (`rate_timer` 0xE43E appears after `rate` 0xE444): writer (Task 3) and parser (Task 2) must both follow table order, never address order.** `frame` decimal, `lag` 0/1 via `emu.islagged()`, rest lowercase hex (S2 SS convention). The engine `frameCounter` has NO ROM cell — the comparator compares it against the count of non-lag rows stepped, never a CSV column.

---

### Task 1: `Sonic3kSpecialStageComparisonState` + `captureComparisonState()` (addition #3)

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageComparisonState.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageManager.java` (one new method)
- Test: `src/test/java/com/openggf/game/sonic3k/specialstage/TestSonic3kSpecialStageComparisonState.java`

**Interfaces:**
- Consumes: existing getters — manager `getSpheresLeft():1106/getRingsCollected():1102/getRingsLeft():1110/getFrameCounter():1148/getClearRoutine():1144/getClearTimer():1140/isFinished():1058/hasEmeraldCollected():1094/getPlayer():1128`; player `getXPos()/getYPos():534-535/getAngle():536/getVelocity():537/getTurning():539/getJumping():543/getFadeTimer():548`.
- Produces: a flat record `Sonic3kSpecialStageComparisonState(int playerX, int playerY, int angle, int velocity, int turning, int jumping, int fadeTimer, boolean started, int spheresLeft, int ringsCollected, int ringsLeft, int frameCounter, int clearRoutine, int clearTimer, boolean finished, boolean emeraldCollected)` — 16 fields; `started` = `player.isStarted()` (`:541`), REQUIRED because the CSV has a `started` column (0xE450) the comparator must have a comparand for; `emeraldCollected` is snapshot-completeness only (no CSV column, not a comparand) + `public Sonic3kSpecialStageComparisonState captureComparisonState()` on the manager — pure read, no mutators/caching, javadoc citing addition #3 and modeling on `Sonic2SpecialStageComparisonState`.

- [ ] **Step 1:** Failing test — construct the manager RAW (`new Sonic3kSpecialStageManager()`, NO `initialize()` call: `Sonic3kSpecialStageRomOffsets.areOffsetsVerified()` currently always evaluates true — all five gated offsets hold real values — so `initialize()` always takes the ROM path and needs `GameServices.rom()`). **Seed EVERY record-backing field to a DISTINCT value** using a COPY of the reflection `set(...)` helper from the sibling snapshot test (`TestSonic3kSpecialStageManagerSnapshot.java:227-263`, helper at `:370` — it is private static there, so copy it into the new test class) — NOT just `player.initialize(...)` (which zeroes velocity/turning/jumping/fadeTimer and can't touch the manager scalars; default-zero seeding would make the field-mapping assertions vacuous, unable to catch a swapped-getter bug). Then assert every one of the 16 record fields equals its getter counterpart, and assert two consecutive calls with no update() in between are equal (pure-read proof).
- [ ] **Step 2:** COMPILE-FAIL → implement → green. **Step 3:** CHANGELOG line `- S3K special stage: read-only comparison snapshot for trace replay.` + commit (feat, src/main → Changelog: updated).

---

### Task 2: `S3kSpecialStageTraceData` / `S3kSpecialStageTraceFrame` (parser)

**Files:**
- Create: `src/main/java/com/openggf/trace/S3kSpecialStageTraceFrame.java`, `src/main/java/com/openggf/trace/S3kSpecialStageTraceData.java`
- Test: `src/test/java/com/openggf/tests/trace/TestS3kSpecialStageTraceParsing.java`

**Interfaces:**
- Consumes: the S2 pattern (`SpecialStageTraceData.load` shape: metadata + rows + aux via `TraceData.resolveTraceFile`/`loadAuxEvents`; `SpecialStageTraceFrame.parseCsvRow` column-constant style). S2 classes stay S2-locked (their profile gate throws on anything else).
- Produces: `S3kSpecialStageTraceFrame` record matching the 20-column schema above (ints; `lag` boolean; `parseCsvRow(String line)` hard-throws on wrong column count); `S3kSpecialStageTraceData.load(Path)` hard-gated to `trace_profile == "s3k_special_stage"`, exposing `metadata()/frames()/frameCount()/eventsForFrame(int)`.

- [ ] **Step 1:** Failing parser test: hand-built header+2 rows round-trip (every column value asserted), wrong-column-count throws, wrong-profile metadata throws, gzip-or-plain resolution (write .gz variant in @TempDir).
- [ ] **Step 2:** Implement → green. **Step 3:** CHANGELOG + commit (feat, src/main).

---

### Task 3: Recorder — SS detour becomes a real segment (lua)

**Files:**
- Modify: `tools/bizhawk/s3k_complete_run_recorder.lua`

**Interfaces:**
- Consumes (all verified present, v6.30+): detour handler `:5021-5054` (currently: build `pending_ss_transition` → `finalize_segment()` → `detour_active="special_stage"` → return, NO rows; the `:5045-5054` block returns during `$34/$48`), merged-transition completion at the level re-arm `:5096-5103`, the CrossResetRAM boundary constants `:357-368` (Saved_X/Y, star post, entry flag, emeralds — transition-record inputs; the 16 SS ROW constants 0xE420-0xE450 are ALL-NEW additions this task makes), `GAMEMODE_SPECIAL_STAGE=0x34 :371`, `start_new_segment :4762ish`, `finalize_segment`, `segment_dir_counts`, manifest writer.
- Produces: SS detours now emit a REAL `special_stage` segment: dir token `ss` (repeat-suffixed `ss_2`… via `segment_dir_counts`), `trace_profile: "s3k_special_stage"`, `ss_csv_version: 1`, `special_stage_index` in metadata, the 20-column rows per the schema table, `lag` via `emu.islagged()`. Transition math flips to the bonus-style pair: entry `giant_ring` transition pushed at SS-segment open (indices `#segments_done-1 → #segments_done` AFTER the level segment finalized and the SS segment is about to open), exit `stage_exit` pushed at the level re-arm keyed on `segments_done[#segments_done].kind == "special_stage"` (extend the existing bonus-exit Case-2 predicate to accept either kind). The `pending_ss_transition` merged path is REMOVED (superseded); `detour_active` still gates `$48`/fade frames (no rows after the `$34` window closes — finalize the SS segment on the FIRST non-`$34` frame).
- **Entry-vs-continuation gating (round-2 MAJOR — pin it exactly):** `start_ss_segment` mirrors `start_new_segment` and therefore sets `started=true`, so the existing `if started and game_mode == GAMEMODE_SPECIAL_STAGE` gate at `:5021` would re-fire EVERY `$34` frame, re-finalizing/re-opening per frame. The reworked handler must branch: entry only when `detour_active ~= "special_stage"` (push the giant_ring transition, open the SS segment ONCE, set `detour_active`); continuation frames (`detour_active == "special_stage"` AND `game_mode == $34`) call `write_ss_row()` INSIDE the handler — the normal row path below is unreachable during `$34` because the detour blocks `return` early (`:5043/:5052`); SS finalize runs at the TOP of the `:5045` block on the first non-`$34` frame, before the level-family check.
- **Stage index derivation:** derive the `Current_special_stage` RAM address via the plan-(a) ds.b-walk method (`sonic3k.constants.asm` — the symbol exists; `HPZ_current_special_stage :503` describes itself as "a copy of Current_special_stage", proving the base symbol; cross-check both disasm halves) and read it at SS entry. If the walk is ambiguous, STOP and report BLOCKED with the candidates — do not guess an address into committed capture tooling.
- **Self-verification prints (VERIFY-ON-FIRST-CAPTURE obligation):** at SS-segment open and every 300 SS frames, print `spheres_left`, `ring_count`, `started`, `x_pos/y_pos` — plus a finalize-time sanity summary (spheres_left non-increasing overall, started flipped 0→1 once, x/y within 0xFFFF). These prints are the schema's live verification when the first real recording happens; note them in the README section.

- [ ] **Step 1:** Implement `start_ss_segment()` / `write_ss_row()` / SS finalize per above (globals only; mirror `start_new_segment`'s file/metadata handling INCLUDING the `started`/`current_segment_zone` resets on finalize — the level re-arm's double-finalize protection depends on them; metadata written with the SS profile + index + `ss_csv_version`). Rework the `:5021-5054` and `:5096-5103` blocks to the two-transition shape, AND clean up the superseded merged-path remnants: the `pending_ss_transition` global declaration (`:780-781`) and its truncated-detour fold in `write_run_manifest` (`:1356-1366`) — leaving either behind is dead code referencing a permanently-nil global. Update `precreate_segment_dirs` for `ss/`.
- [ ] **Step 1b (round-1 MAJOR): SS-aware end-of-run finalize.** The generic end-of-run finalize (`:5535-5539` → `finalize_segment`, which hardcodes `kind = bonus_stage-or-level` at `:4915-4916`) would mislabel a movie truncating mid-`$34` as a "level" segment full of SS-schema rows — and because the entry transition was already pushed, the corrupt manifest could VALIDATE. Route an open SS segment through the SS finalize at end-of-run (correct kind/profile), and print a truncation WARNING naming the segment (a truncated SS segment is legitimate data — correctly labeled — but flagged). Hand-trace the mid-$34-truncation scenario in your report.
- [ ] **Step 2:** Parse gate (bundled lua.exe). Hand-trace the three scenarios in your report: stage-free movie (all new paths unreachable — byte-identical output), aiz→SS→aiz (segments [aiz, ss, aiz_2], transitions [giant_ring 0→1, stage_exit 1→2]), aiz→gumball→aiz (unchanged from v6.30 behavior).
- [ ] **Step 3:** Byte-identity regen gate: re-run the plan-(a) Task-8 procedure (detached BizHawk, committed complete-run movie, OGGF_TRACE_STOP_FRAME=40000) — physics.csv/aux byte-identical for the stage-free AIZ segment, no manifest. Bump `LUA_SCRIPT_VERSION` to `6.31-s3k-completerun`.
- [ ] **Step 4:** Commit (tools-only, justified `Changelog: n/a:`).

---

### Task 4: Replay harness + skip-if-missing test (+ addition #6-S3K)

**Files:**
- Create: `src/test/java/com/openggf/tests/trace/s3k/S3kSpecialStageReplayHarness.java`
- Create: `src/test/java/com/openggf/tests/trace/s3k/AbstractS3kSpecialStageTraceReplayTest.java`
- Create: `src/test/java/com/openggf/tests/trace/s3k/TestS3kSpecialStageTraceReplay.java`
- Create: `src/test/java/com/openggf/tests/TestS3kSpecialStageHeadlessBoot.java` (addition #6 proof — runs TODAY)

**Interfaces:**
- Consumes: `Sonic3kSpecialStageProvider` (`initializeStage(int) :82` single-arg throws IOException, `getManager() :241`, `update()/handleInput(int,int)/handlePlayer2Input(int,int)`, `isFinished()`), `captureComparisonState()` (Task 1), `S3kSpecialStageTraceData` (Task 2), `SpecialStageInputMapper`, `RecordedInputSnapshots.fromBk2(current, previous)` press-edge vs previous PHYSICAL row, the S2 boot recipe (`AbstractS2SpecialStageTraceReplayTest.bootHarness :166-186`: GraphicsManager resetState+initHeadless → Rom.open → TestEnvironment.configureRomFixture → initHeadless), report writing per `AbstractS2SpecialStageTraceReplayTest.writeReport :701` conventions (prefix `s3k_special_stage_<idx>`).
- Produces: harness with ONLY `stepFrame(traceFrame)` (VBlank pacing; the test loop skips lag rows: `if (frame.lag()) continue;` consuming the row without stepping — no stepPass/passBinder); comparator loop building `FrameComparison`s (ERROR severity MVP fields, each CSV column vs its record counterpart: playerX/playerY/angle/velocity/turning/jumping/fadeTimer, `started` (CSV 0xE450 column vs record `started`), spheresLeft, ringsCollected/ring_count, ringsLeft, rate/rate_timer AND anim_frame as WARNINGS (no record fields — recorded-not-compared for MVP, listed for the follow-up campaign; `getMappingFrame():545` exists if anim_frame mapping is wanted later), clearRoutine/clearTimer; frameCounter vs stepped-non-lag count; the finish boundary asserts `record.finished` flips on the trace's exit-spin completion frame (fade_timer returns to 0 after its 0→nonzero rise — covers success and failure exits; clear_routine terminal is NOT the anchor: finished flips ≥96 frames later)); character config set BEFORE `initializeStage` (mirror S2 harness ctor `:87-90` — S3K `resolvePlayerCharacter` reads config at init).
- The headless-boot test (addition #6): `@RequiresRom(SonicGame.SONIC_3K)`, boots the REAL ROM path (offsets-verified branch, not the placeholder), `initializeStage(0)`, steps 60 `update()`s with idle input, asserts `isInitialized()`, spheresLeft > 0, player at the stage's start state, `captureComparisonState()` non-null and stable across a no-update double-call. If init throws headlessly (GL/art gap), that discovery is the task's purpose — report DONE_WITH_CONCERNS/BLOCKED with the stack trace, don't mask.

- [ ] **Step 1:** Headless-boot test first (it runs today and de-risks the harness) → green or honest BLOCKED.
- [ ] **Step 2:** Harness + abstract + concrete replay test; concrete skips (assume on `src/test/resources/traces/s3k/special_stage/` dir). Verify guard conformance (`*TraceReplay.java` suffix → register `AbstractS3kSpecialStageTraceReplayTest` in `TestTraceReplayInvariantGuard`'s accepted-bases list per its convention, with justification).
- [ ] **Step 3:** Commit (test-only trailers; if the guard file changed, justify).

---

### Task 5: freshLoadSignal wire-up + recording docs

**Files:**
- Modify: `src/main/java/com/openggf/trace/TraceMetadata.java` (one field: `@JsonProperty("fresh_load") Boolean freshLoad` + accessor defaulting false)
- Modify: `src/main/java/com/openggf/TraceSessionLauncher.java` (`prepareSpecialStageConfiguration`, hardcoded `false` at `:328`: pass `Boolean.TRUE.equals(meta.freshLoad())`; update the pointing comment; **widen the method from private to package-private** so the live meta-reading path is test-reachable — the existing `TestTraceSessionLauncherSsConfig` only exercises the helper with an explicit boolean)
- Modify: `tools/bizhawk/s3k_complete_run_recorder.lua` (SS segment metadata writes `"fresh_load": false` — giant-ring entries are mid-level, never fresh; the field exists for future fresh-boot SS captures)
- Modify: `tools/bizhawk/README.md` (blue-spheres round-trip recording subsection: giant-ring entry procedure, expected `ss/` segment output, the VERIFY-ON-FIRST-CAPTURE self-check prints, `s3k-aiz-bluespheres.bk2` naming)
- Test: extend `TestTraceSessionLauncherSsConfig` — call the now package-private `prepareSpecialStageConfiguration(meta)` directly: fresh_load=true metadata → S3K_SKIP_INTROS=false; fresh_load absent/false → untouched (this exercises the live meta-reading path, not just the explicit-boolean helper). NOTE the setup burden: this path routes through `GameServices.configuration()` (`:323`), so the test needs a live `SonicConfigurationService` — copy the existing test class's `EngineServices.configure` + singleton save/restore idioms (it already has them for the helper tests), plus `@TempDir` + `user.dir` isolation per repo convention if a fresh service boot is needed.

- [ ] **Step 1:** Failing test → implement → green (existing 3-case helper test untouched and green). **Step 2:** Grep `new TraceMetadata(` positional call sites and append the new trailing null. **Step 3:** README + CHANGELOG + commit (feat, src/main → Changelog: updated).

---

### Task 6: Gate + frontier log

- [ ] **Step 1:** Full suite (detached + monitor): no NEW failures vs baseline (known flakes: geyser order-dependent, wire-cage classloader — verify isolated-pass if they appear).
- [ ] **Step 2:** Frontier-log entry: blue-spheres pipeline landed; replay test skips pending `s3k-aiz-bluespheres.bk2`; RAM map derived, VERIFY-ON-FIRST-CAPTURE obligation stated; recorder v6.31.
- [ ] **Step 3:** Commit docs. Merge-time README release-log reminder stands.

## Plan-level notes

- Chain/visual integration comes free: the SS segment kind is already handled by the walker's boundary predicates (`giant_ring` → `isSpecialStageRequested`) and the visual advancer's `special_stage` expected-mode mapping — once this plan's segments exist, `TestS3kBonusRoundTripChain`-style SS chains become recordable without further foundation work.
- Known gotchas baked in: single-arg `initializeStage`; no lag compensator on S3K (nothing to zero); frameCounter vs row ordinal; phase-overlay RAM base (live verification obligation); character config before init.
