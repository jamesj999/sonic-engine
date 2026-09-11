# Handover: BizHawk Lua -> Native C# Recorder Migration

## CLOSED 2026-07-25 — develop `5496583df`, pushed

The migration is complete and merged. All four recorders (S1 standard + complete-run,
S2 all modes + complete-run, S3K standard, S3K complete-run) have byte-parity-gated
native ports. Everything below is kept as the historical record of how it was done and
why; the live guidance now lives in `tools/bizhawk-headless/README.md` and its
`CLAUDE.md`, and in the `trace-replay-bug-fixing` skill.

Landed after the migration itself, same day:

- Both S3K dead-RAM-address defects fixed (`ADDR_FRAMECOUNT` 0xFE08 -> 0xFE04,
  `ADDR_VBLA_WORD` 0xFE12 -> 0xFE0E). All 39 S3K fixtures regenerated with every delta
  categorised before installing. **No frontier moved** in either case.
- Trace payloads gzip at capture, inside the all-or-nothing publication, plus a
  two-layer guard (`validate-policy` at commit time, `TestTraceFixtureCompressionGuard`
  in CI) so an uncompressed payload cannot be committed.
- Run captures stream instead of buffering: S2 complete-emeralds peak RSS
  1.51 GiB -> 227 MiB, staging 638 MB -> 36.8 MB.
- The native suite runs in parallel: 957s -> 371s at `--jobs 8`, with `--game`,
  `--movie`, `--gates-only` and `--no-gates` selection. 375 PASS / 0 FAIL / 0 SKIP.

Branches and worktrees for all of the above are merged and deleted.

---


Written 2026-07-24 by the Fable session (`8ebbeb1c-19f4-4696-9f4a-3eb1b63aeda8`) in case
usage limits cut it off. Untracked on purpose — do not commit this file. The companion
long-term memory is `~/.claude/projects/-home-farrell-code-projects-OpenGGF/memory/native-recorder-migration-status.md`.

## Mission

Replace every BizHawk Lua trace recorder with the native Linux/Mono C# GPGX harness at
`tools/bizhawk-headless/`, gated by **byte-identical** differential comparison against the
canonical fixtures under `src/test/resources/traces/`. Sequence (user-confirmed, sequential
because the recorders share harness files):
S1 standard ✅ → S2 standard (all 3 modes) ✅ → S1 complete-run ✅ → S2 complete-run
capability (new, user-driven) ✅ → **S3K standard (task 7, IN FLIGHT)** → S3K complete-run
(task 8, last).

## THE MIGRATION IS COMPLETE (2026-07-25). develop is at `054eff4c6`, pushed.

All four recorders — S1 standard + complete-run, S2 all modes + complete-run, S3K standard,
S3K complete-run — now have byte-parity-gated native ports. Native suite **358 PASS / 0 FAIL
/ 0 SKIP** on develop before the push; `TestS3kMegaRunChain` at seg0 21 / seg1 12 / seg2
18,725, failing at the seg2 exit boundary (its intended post-fix frontier).

**What remains (none of it recorder-migration work):**
1. **Task #1 — the S3K *standard* recorder still reads the dead `0xFE08`.** See the section
   below. This is the last known instance of the defect class and it sits under the release
   slice. Highest-value follow-up.
2. `chore/ai-trace-test-harness-dedup` — salvaged trace-test-harness refactor, compiles,
   otherwise unverified. Needs a rebase and a green trace suite before landing.
3. `.githooks/post-checkout` is not executable, so git skips it on checkout (`chmod +x`).
4. The `OpenGGF-shared-module` worktree's uncommitted Lua WIP is superseded by `fd3a74291`
   and can be discarded.

**Hard-won lessons worth keeping:**
- **Use `mvn test`, never `mvn surefire:test`,** for anything trace-replay. `surefire:test`
  does not compile; a stale `target/classes` reported 3 AIZ failures where a clean compile
  reports 14. Both the earlier baseline numbers in this effort were taken that way.
- **A recorder reading a dead RAM address silently props up trace frontiers.** When a
  recorder address fix lands, re-derive the affected fixtures — do not assume old captures
  stay comparable.
- **Check all six recorders when auditing any recorder defect.** They are copy-paste
  siblings; `6564667eb` fixed one of two and the other went unnoticed for four days.
- **Categorise every fixture delta before installing a regeneration.** Each one must trace
  to a named cause; anything unexplained stops the install.

## Historical state (updated 2026-07-25 by session `c4b86476`)

- **TASK 7 IS MERGED AND PUSHED. develop is at `77402cdfa`.** The merge commit carries the
  README release-log Highlights entry; the branch also carries `24b41d25f`, which flipped
  the skills' recorder table (S3K standard → native) in both `.claude/` and `.agents/`.
  Full native suite re-run **on develop itself** before pushing: 277 PASS / 0 FAIL / 0 SKIP.
- **TASK 8 IS COMPLETE ON THE BRANCH (2026-07-25 06:58) — awaiting merge.** 12 commits on
  `feature/ai-bizhawk-native-s3k-completerun`, HEAD `b8af440b0`. **355 PASS / 0 FAIL /
  0 SKIP** (independently re-tallied); `git log --stat 77402cdfa..HEAD -- src/test/resources/traces/`
  empty. Identities (A) and (C) are **byte-identical**: one 466,334-row pass reproduces all
  seven `*_completerun` fixtures (5m57s, 235 MB RSS, 2.84 GB out), and a
  `--run-id s3k-multibonus` pass reproduces all four standalone bonus/special-stage dirs —
  physics + aux by sha256 with zero normalization, metadata differing only in
  `recording_date`. Review found and fixed a real input-column indexing bug (`0d2c33bc4`).
- **Identity (B) — `runs/s3-knux-multibonus-ss/` — is gated STRUCTURALLY, not byte-exactly,
  and cannot be byte-gated without regenerating it.** Three independent causes, each
  verified against the fixture bytes (I re-verified all three by hand):
  1. **CRLF.** All 25 (B) segment dirs and its `run_manifest.json` contain `\r\n` — a
     2026-07-19 Windows EmuHawk text-mode artifact. (A) and (C), captured on Linux by the
     same Lua, are LF, and emitting CRLF would break the (A) gate.
  2. **Dead frame counter.** (B)'s `gameplay_frame_counter` column is constant `0000`
     because it predates Lua commit `6564667eb` (2026-07-21, **no version bump**), which
     moved `ADDR_FRAMECOUNT` 0xFE08 (`Debug_placement_mode`, dead) → 0xFE04
     (`Level_frame_counter`). (A) has no constant-zero columns.
  3. **Hooks were armed.** Exactly 4 of 25 segments carry hook-driven families the
     hooks-off port cannot emit: hcz_2 +95 lines, hcz_6 +48, mgz +69, mgz_3 +69 (counts
     confirmed by grep; every other segment is 0).
  So HEAD Lua could not reproduce (B) byte-exactly either. The gate instead pins every
  delta as an exact literal (differing physics columns must be exactly the counter column,
  fixture values must be `0000`, per-segment hook-line counts must equal pinned literals
  and the capture's must be 0, aux equal line-for-line after dropping exactly those, and
  the manifest byte-identical after CRLF→LF plus its single 6.31 version line). Non-vacuity
  was verified by perturbation.
- **RESOLVED 2026-07-25: (B) was regenerated with user approval (`63eccd290`).** Recaptured
  with HEAD Lua v6.32 on Linux (hooks unset, `OGGF_TRACE_RUN_ID=s3-knux-multibonus-ss`,
  `OGGF_BK2_BASENAME=s3-knux-multibonus-ss.bk2`, `OGGF_BK2_FRAME_COUNT=114622`) over the
  same untouched movie. Every delta was categorised before installing: LF (was CRLF), live
  counter + `pre_trace_osc_frames` 0→1 (both from `6564667eb`), 212 hook lines dropped from
  the four armed segments, `capture_mode` added, 6.31→6.32 stamps, `recording_date`.
  **Nothing else changed** — verified cell-by-cell and line-by-line across all 25 segments:
  identical offsets and row counts, every physics cell outside the counter column identical,
  all three SS segments identical outright, manifest identical but for its version line.
- **The un-masked engine debt is FIXED (`bab10e408`).** Root cause was a single call site
  violating a documented contract: `TraceReplaySessionBootstrap.alignFrameCountersForReplayStart`
  seeded `LevelManager` with the *first driven* row's counter, but that field holds the
  **previous completed** level frame (ROM increments `Level_frame_counter` before
  `Process_Sprites`; 16 consumers do `getFrameCounter() + 1`). The method's own javadoc and
  the sprite branch three lines above already used the pre-row — only the level branch was
  the outlier, and it was unobservable while both sides were zero. Verified on a real
  compile (`mvn test`, **never** `surefire:test` — it does not compile, and this tree's
  `target/classes` was stale over 13 trace sources): chain returns from seg0/22,216 errors
  to seg0 21 / seg1 12 / seg2 18,725, frontier back at the seg2 exit boundary. seg2's first
  divergence (f192 `y`) is unchanged, so the +14 vs the old 18,711 is downstream noise, not
  a new frontier — do not describe it as "restored exactly". The regression's own first
  divergence was f1934, the AIZ Giant Ride Vine grab (`sonic3k.asm:46714-46748`, hold at
  `:46607-46613`). S1/S2 replays green; `TestS3kAizTraceReplay` 2/14 identically both arms.
- **Superseded description of that debt (kept for context):** `TraceReplaySessionBootstrap:808-815`
  seeds the engine's level/sprite frame counters from this very column and branches on
  `gameplayFrameCounter() > 0` (~:887), and `AizGiantRideVineObjectInstance:127-137` advances
  its angle from the seeded `LevelManager` counter — so a dead-zero column kept the engine in
  its "zero" paths. `TestS3kMegaRunChain` regressed from the seg2 exit boundary to seg0
  (21 → 22,216 errors, first non-camera divergence f1766 `y_speed`). Isolated by experiment:
  zeroing only the counter column restores seg2 exactly; reverting only `pre_trace_osc_frames`
  does not. **User chose to fix the engine debt rather than roll back.** This must land in
  `docs/status/trace-frontier-log.md` before the branch merges.
- Task 8 workflow record: `wf_4c003e55-b0c` on branch
  `feature/ai-bizhawk-native-s3k-completerun`, branched off develop `77402cdfa` at launch
  (per merge lesson 3), in the same worktree. Script:
  `~/.claude/projects/-home-farrell-code-projects-OpenGGF-live-av-recording/c4b86476-2540-4a7f-a2d8-3a58d6e73932/workflows/scripts/migrate-s3k-completerun-recorder-to-native-wf_4c003e55-b0c.js`.
  Phases Spec (3 writers + 2 verifiers) → Implement (3 stages) → Gate (3, each with one
  retry) → Review (3 lenses + fixes) → Finalize. Same model sizing as task 7 (opus/high
  everywhere except the policy lens and Finalize on sonnet). This is the LAST recorder.

### Historical (pre-task-7-merge)

- **develop was at `c6df690e4`** — it had moved past `78a83aa41` by three docs-only commits
  (`7bc3c71d7` CLAUDE/AGENTS rightsizing, `984b181a0` skills made native-recorder-aware,
  `c6df690e4` citation fixes). `78a83aa41` remains the S3K branch's merge base and the
  reference point in every review/verify command. develop contains: native S1 standard +
  complete-run + run mode, native S2 all modes + complete-run capability, Lua
  `s2_trace_recorder.lua` v9.13-s2, ~11 permanent ROM-backed differential gates, suite 211
  PASS at merge time. Native suite: `tools/bizhawk-headless/test.sh` (~4 min; the
  complete-emeralds gate is ~85% of that; `--filter` to iterate).
- **Task 7 (S3K standard recorder) is well past half done** on branch
  `feature/ai-bizhawk-native-s3k-recorder` in worktree
  `<repo>/.worktrees/bizhawk-headless-poc`.
  Committed: Spec phase (`6fbef1399`, `fcb4a5395`, `5f2ba7fdd` + corrections `4b322ee2f`,
  `4cd94b263`), Implement Stage A `b13a1f89c` (S3KRam + S3KTraceCsvWriter + RomIdentity
  S3K + Bk2Reader tolerances), Stage B `b1c272007` (S3KAuxEventEngine, all frame-polled
  families), Stage C `5e9fed24d` (hook deferral pinned — see below). **Suite 255 PASS /
  0 FAIL / 0 SKIP at `5e9fed24d`.**
- **The original workflow `wf_8b15c4fe-573` is DEAD** — a user tool-rejection at 20:05 BST
  killed its Stage D agent mid-`test.sh`. Its journal is at
  `~/.claude/projects/-home-farrell-code-projects-OpenGGF/8ebbeb1c-19f4-4696-9f4a-3eb1b63aeda8/subagents/workflows/wf_8b15c4fe-573/journal.jsonl`;
  script alongside under `…--worktrees-bizhawk-headless-poc/…/workflows/scripts/`. Do NOT
  resume that run — Stage A–C are committed and its Stage D prompt is superseded.
- **TASK 7 IS COMPLETE ON THE BRANCH (2026-07-24 23:05) — awaiting merge.** Continuation
  workflow `wf_c2c050fa-d80` finished green: Stage D `0caef337f`, gates `193343285` (AIZ),
  `1cf5df7f7` (CNZ), `914efc08c` (MGZ) — **all three byte-identical on the first attempt
  with zero production fixes** — then review fixes `a81e56b05` (refuse every unmodeled
  S3K env var) + `aaff910ff` (stream non-discarding captures instead of buffering), and
  docs `48fb0d019`. **Suite 277 PASS / 0 FAIL / 0 SKIP** (independently re-tallied from the
  preserved log). Verified by hand: `git log --stat 78a83aa41..HEAD -- src/test/resources/traces/`
  empty, all 11 S3K files tracked despite `tools/*`, worktree dirty only in the preserved files.
  Metadata deltas pinned per fixture: version line + `recording_date` for AIZ/CNZ, plus MGZ's
  leftover `pre_trace_osc_frames` line asserted positionally and consumed once.
  **Next: merge inline + push (see "Merge protocol", incl. the skills flip), then task 8.**
- Continuation workflow record (session `c4b86476`, launched 2026-07-24 20:10). Script:
  `~/.claude/projects/-home-farrell-code-projects-OpenGGF-live-av-recording/c4b86476-2540-4a7f-a2d8-3a58d6e73932/workflows/scripts/migrate-s3k-recorder-to-native-continue-wf_c2c050fa-d80.js`.
  Phases: StageD (finish + commit the uncommitted profiles/CLI work) → Gate (3, sequential,
  each with one fresh retry) → Review (3 lenses + fix application) → Finalize (docs).
  Model sizing: opus/high for Stage D, all gates, the Lua-parity and quality review lenses,
  and fix application; sonnet for the policy-checklist lens and Finalize. Resume with
  `Workflow({scriptPath, resumeFromRunId: "wf_c2c050fa-d80"})` — read `journal.jsonl` and
  `git log` on the branch first and skip anything already committed.
- **Stage D's state when the continuation picked it up** (in case it must be redone by
  hand): written-but-uncommitted — new `src/Recording/S3KTraceCaptureRunner.cs`,
  `src/Recording/S3KTraceMetadataWriter.cs`, `tests/S3KTraceCaptureRunnerTests.cs`,
  `tests/S3KTraceMetadataWriterTests.cs`, plus modified `src/Program.cs` (S3K trace branch,
  `RunS3kTrace`, `RejectS3kDiagnosticHookEnvironment`), `tests/TraceCliTests.cs`,
  `tests/TestMain.cs`, both `.csproj`. Never verified by a suite run.
- **After task 7 completes:** merge inline (see "Merge protocol"), push, then task 8.

### `tools/*` is gitignored — the silent-drop trap

`.gitignore:48` is `tools/*`. Files already tracked stay tracked, but **every new file under
`tools/` is invisible to `git status` and needs `git add -f`**. A forgotten `-f` still builds
and tests green locally while the file is missing from the commit. Always confirm with
`git show --stat HEAD` and `git ls-files tools/bizhawk-headless | grep S3K` after committing.

## Task 7 facts (verified)

- Gates (all 6.28-s3k-stamped, trace_schema 6, chars sonic+tails, movies in each dir):
  - `traces/s3k/aiz1_to_hcz_fullrun/` — profile `aiz_end_to_end`, movie
    `s3-aiz1-2-sonictails.bk2`, offset 511, 20798 frames
  - `traces/s3k/cnz/` — `level_gated_reset_aware`, `s3k-cnz-sonic-tails.bk2`, offset 3171, 42253 frames
  - `traces/s3k/mgz/` — `level_gated_reset_aware`, `s3k-mgz-sonic-tails.bk2`, offset 2602, 35912 frames
- **NOT task 7:** `special_stage/`, `bonus_gumball|pachinko|slots/`, `runs/s3-knux-multibonus-ss/`
  are stamped `6.32-s3k-completerun` → produced by `s3k_complete_run_recorder.lua` → task 8 gates.
- **Version drift:** fixtures 6.28-s3k, HEAD Lua stamps `6.30-s3k` (line ~958). v6.29
  *changed metadata content* ("stop advertising replay phase controls"), and repo history
  hand-normalized some fixtures. The permitted metadata delta must be **empirically pinned**
  (Lua git history + real capture diff), asserted exactly in the gates. physics/aux: byte-identical, zero normalization, always.
- **Main technical risk — RESOLVED, no exec callbacks needed (`5e9fed24d`).** All three
  gated fixtures were captured with `OGGF_TRACE_ENABLE_DIAGNOSTIC_HOOKS` unset, so **zero**
  hook-driven aux families appear in any of them. The native port therefore *defers* the
  GpgxHost M68K exec/memwrite callback surface rather than building it, and
  `S3KHookAbsenceTests` pins that decision to the fixture bytes: per fixture it asserts zero
  aux lines for the 13 deferred families (12 exec/memwrite + env-armed `cnz_event_ram`),
  anchors non-vacuously on per-frame poll counts, and requires the `capture_mode` metadata
  line. Regenerating a fixture with hooks enabled fails those gates — that is the designed
  signal that native exec-hook capture must then actually be built. `Program.cs` refuses
  loudly if `OGGF_TRACE_ENABLE_DIAGNOSTIC_HOOKS=1` / `OGGF_S3K_CNZ_EVENT_RAM_RANGE` /
  `OGGF_S3K_RNG_CALL_RANGE` are set for an S3K capture. Documented in
  `docs/s3k-profiles-and-hooks.md` §2.4 and `docs/s3k-aux-events.md` §5.
  **Task 8 must re-derive this per-fixture** — the completerun fixtures may well have been
  captured with hooks armed, in which case the LibGPGX callback surface finally has to be
  wired (watch delegate GC-pinning under Mono).
- New env var: `S3K_ROM_PATH` (follow the S1/S2 SKIP-when-absent convention).
  S3K ROM SHA-1 `CFBF98C36C776677290A872547AC47C53D2761D6` (worktree + main checkout `s3k.gen`).

## QUEUED FOLLOW-UP: the S3K *standard* recorder still reads the dead address

Found 2026-07-25 while scouting for prior art. **`6564667eb` fixed exactly one file.**
`tools/bizhawk/s3k_trace_recorder.lua:375` still declares `ADDR_FRAMECOUNT = 0xFE08`
(`Debug_placement_mode`, dead-zero) and reads it at 10+ sites feeding the CSV counter
column and every aux `vfc`. Verified: `aiz1_to_hcz_fullrun`, `cnz` and `mgz` all carry
an all-zero counter column — i.e. **the primary release slice's frontiers are validated
against a constant the ROM never produces**, through the same
`TraceReplaySessionBootstrap` seeding path that cost 22k errors on the run fixtures.
The native harness mirrors the split faithfully for byte-parity (a passing test asserts
the standard profiles read `Debug_placement_mode`), so the defect currently lives in two
implementations.

Root cause of the miss: each of the six recorders carries its own ROM address constants.
`fd3a74291` extracted the leaf helpers but deliberately left the constants per-recorder,
so a fix in one cannot propagate — the exact duplication
`tools/bizhawk/SHARED_MODULE_HANDOFF.md` catalogues.

Scope correction committed on develop as `4967ad1b2`. Full work plan is in the session
task list; deliberately queued **after** task 8 merges so the release slice does not take
an unmeasured frontier shift while the replay harness is mid-change.

Also checked and dismissed while scouting: no branch, worktree, or stash anywhere
contains a fix for the live-counter engine debt. The `OpenGGF-shared-module` worktree's
uncommitted Lua WIP is a strictly-inferior earlier draft of what landed as `fd3a74291`
(develop's versions are supersets) — safe to discard. The stranded trace-test-harness
refactor in `.claude/worktrees/agent-a17f7fc9d6076cb05` was rescued to branch
`chore/ai-trace-test-harness-dedup` (compiles; unverified beyond that; needs a rebase and
a green trace suite before it lands, and must NOT land concurrently with the counter fix
since it rewrites the same harness and would make the frontier attribution unprovable).

## Task 8 — verified fixture inventory (derived 2026-07-25; don't re-derive)

`s3k_complete_run_recorder.lua`, 5918 lines, `LUA_SCRIPT_VERSION = "6.32-s3k-completerun"`
at line 357. Two movies in `traces/s3k/_movies/`, and **three distinct capture identities**:

- **(A) complete-run pass over `s3k-complete-sonic-tails.bk2`** → 7 dirs, profile
  `complete_run`, 6.32, `capture_mode` present, **no** `run_id`:
  aiz 941/26228, hcz 27170/31482, mgz 58653/39398, cnz 98052/40064, icz 138117/25393,
  lbz 163511/46244, mhz 209756/28156 (offset/frames). ~238k input rows total.
- **(B) run pass over `s3-knux-multibonus-ss.bk2`, `run_id=s3-knux-multibonus-ss`** →
  `runs/s3-knux-multibonus-ss/`: 25 segments + `run_manifest.json` (run_schema 1,
  rom_checksum `C5B1C655C19F462ADE0AC4E17A844D10`). aiz+aiz_2..5, hcz+hcz_2..6, mgz+mgz_2..3
  (`complete_run`); ss, ss_2, ss_3 (`s3k_special_stage`); gumball, gumball_2, slots,
  slots_2..5, pachinko (`s3k_bonus_stage`). **Version drift inside one run dir:** level/ss
  segments *and the manifest* stamp 6.31, bonus segments stamp 6.32.
- **(C) a SECOND run pass over the SAME movie, `run_id=s3k-multibonus`** → the published
  standalone `bonus_gumball` (5570/1430), `bonus_pachinko` (92963/3051), `bonus_slots`
  (9142/1200), `special_stage` (48174/4630). Same offsets/frames as their (B) counterparts
  but **not copies**: 6.32, different run_id, recorded 2026-07-23 vs 2026-07-19. The three
  bonus dirs carry `capture_mode` *and* a decimal `v_int_run_count` (5529/92662/9097);
  `special_stage/` carries neither. (v6.32's changelog comment at Lua line 284 introduces
  `v_int_run_count` — the exact rule must be pinned from code, not guessed.)

Sampled completerun aux streams show only frame-polled families — several already ported for
the standard recorder — plus candidates new to this recorder: `game_paused_state`,
`object_appeared`, `object_removed`, `player_mode_set`. **Verify hook absence per fixture
anyway**; task 7's deferral does not carry over, and a hook family here forces the LibGPGX
exec-callback surface.

Reuse: the whole S3K standard stack (`S3KRam`, `S3KTraceCsvWriter`, `S3KAuxEventEngine`,
`S3KTraceMetadataWriter`, `S3KTraceCaptureRunner` incl. its `TraceStreamSink`), plus the
S1/S2 run machinery (`RunManifestWriter`, `RunManifestTransition`, `NoReplacePublisher`).
Note `--run-id` currently *rejects* S3K with a "not migrated yet" error — that's the seam.

## Environment (do not re-derive)

```
BIZHAWK_HOME=<repo>/docs/BizHawk-2.11-linux-x64
S1_ROM_PATH=<checkout>/s1.gen  S2_ROM_PATH=<checkout>/s2.gen  S3K_ROM_PATH=<checkout>/s3k.gen
cd <checkout> && tools/bizhawk-headless/test.sh [--filter <substr>]
```
- Both the main checkout and the worktree have all three ROMs (SHA-verified).
- Toolchain: Mono 6.12 + xbuild, **C# 7.x only**, non-SDK csproj — every new .cs file
  hand-added to BOTH `BizHawk.Headless.Gpgx.csproj` and `...Tests.csproj`. Tests are a
  dependency-free console runner (`tests/TestMain.cs` registry + AssertEx), not NUnit.
- Lua captures WORK on Linux (old README blocker is stale): repo-local BizHawk build,
  hardware GL, `DISPLAY=:0`, via `tools/bizhawk/run_bizhawk_lua.sh <lua> <bk2> <rom>` with
  `OGGF_TRACE_RUN_ID` / `OGGF_BK2_FRAME_COUNT` / `OGGF_BK2_BASENAME` env. Output lands in
  `tools/bizhawk/trace_output/` (CWD-relative to the script). Lua print() never reaches
  stdout — judge by output files. One EmuHawk at a time.
- **Preserve, never stage/revert:** worktree's dirty `docs/rewind/real-gaps.md`, untracked
  `docs/*disasm`, main checkout's dirty `.idea/vcs.xml`, untracked `mods/`,
  `tools/bizhawk/NUL`, `tools/bizhawk/SHARED_MODULE_HANDOFF.md`, and
  `tools/bizhawk/trace_output.s1-complete-emeralds-backup` (user's S1 capture).
  `tools/bizhawk/trace_output/` currently holds the validated Lua full capture of the
  S2 complete-emeralds movie — also preserve.

## Hard invariants

- Fixtures under `src/test/resources/traces/` are read-only ground truth. On mismatch fix
  production code — never fixtures, never looser normalization. (The only sanctioned fixture
  writes so far: halfpipe regen + complete-emeralds install, both user-approved, both merged.)
- physics.csv / aux_state.jsonl / run_manifest.json: byte-identical, zero normalization.
  metadata: `recording_date` + an exactly-pinned version-line delta only.
- The Lua recorder is the behavioral authority; specs are second; disasm resolves RAM questions.
- Stop conditions must be evaluated POST-advance in the Lua's on_frame_end source order —
  this bug was independently found in BOTH the S1 and S2 ports. Don't reintroduce it.
- Line endings: run-mode published files are CRLF (`ExpandRunNewlines`, matches Windows-captured
  fixtures); plain mode + S1 complete-run are LF. Lua-on-Linux writes LF (environmental).
- Commit policy: hooks active (`git config core.hooksPath .githooks` if missing), NEVER
  `--no-verify`, trailer block on every non-merge commit (`Changelog: updated` iff CHANGELOG.md
  staged; feat/fix touching `src/main/` needs updated-or-justified). End commit messages with
  the Co-Authored-By line and a `Claude-Session:` link for the CURRENT session.
- Never commit ROMs, BizHawk binaries, or capture outputs.

## Merge protocol (learned the hard way)

1. Workflow-dispatched merge/push agents get blocked by the safety classifier — always
   merge and push **inline from the main session**.
2. develop is checked out at the MAIN checkout. If a running workflow's agents commit in a
   checkout, **pause that workflow** (TaskStop, then resume after) before switching that
   checkout's branch.
3. Long-lived branches conflict with develop (this bit the S2-emeralds branch: 3-file
   conflict vs the S1-completerun refactor). Either branch immediately before merging, or
   `git merge develop` INTO the branch first, resolve, run the full suite as referee, then
   `--no-ff` merge out.
4. Every merge into develop stages a README.md release-log Highlights entry in the merge
   commit (precedents: `efee41a9d`, `e4619c4e8`, `9391556c4`, `78a83aa41`). Run the full
   suite on develop before pushing.
5. **Flip the skills' recorder table when task 7 (and again when task 8) lands.** develop's
   `984b181a0` made `trace-replay-bug-fixing`, `s1-trace-replay`, `s1-retro-trace`, and
   `trace-green-fleet` native-aware, with a per-game "current recorder" table that still
   lists S3K as Lua-only. The S3K branch is based on `78a83aa41` and does **not** contain
   those rows, so the flip happens at/after merge, in `.claude/skills/…` **and** its
   `.agents/skills/…` mirror. The two trees are near-identical *except* `trace-green-fleet`,
   which has genuinely divergent Codex vs Claude orchestration contracts — never
   sed-mirror that one.

## Facts worth not rediscovering

- Bk2Reader accepts enum domains for Overscan (0-3), Filter (0-2), GenesisFMSoundChip (0-3
  — machine-relevant, forwarded to the core so replay honors the recorded chip). Movies with
  other sync-setting deltas: inspect and extend tolerances deliberately, never blanket-accept.
- S2 `Game_Mode` semantics: `$0C` level, `$10` special stage, `$8C` = level|title-card bit →
  the in-level reload family (deaths, time overs, act AND zone transitions — S2 act changes
  DO reload via $8C); `$14` continue screen is terminal. v9.13-s2 records reloads as
  `death_restart` / `level_advance` manifest transitions (constants on the shared
  `RunManifestTransition`; `TraceRunManifest.ENTRY_KINDS` accepts them).
- S1 complete-run: Lua at HEAD stamps 3.17 (not 3.15); gates pin fixture 3.14/3.15 →
  produced 3.17, verified byte-compat via version-bump commit diffs. The 8 `credits_*`
  fixtures are stable-retro provenance, out of recorder scope. The standalone S1
  `special_stage/` fixture is a published copy of the maze run's `ss/` segment.
- Perf (this box): S2 halfpipe run 22.8k frames ≈ 8 s (~2790 fps); full complete-emeralds
  run 259,590 frames ≈ 3 min 30 s (~1240 fps, ~1.5 GB peak RSS from all-or-nothing buffering,
  375 MB output); S1 GHZ1 4745 frames ≈ 1.5 s. Lua-on-Linux ≈ 840 fps. Native needs no display.
- Prior workflow scripts (reusable as templates) live under
  `~/.claude/projects/-home-farrell-code-projects-OpenGGF*/…/workflows/scripts/` — the
  task-7 S3K script is the most complete template for task 8.
- Spec docs so far: `tools/bizhawk-headless/docs/s1-trace-recorder-behavior.md`,
  `s1-complete-run-behavior.md`, `s1-run-mode-behavior.md`, `s2-trace-recorder-behavior.md`,
  `s2-run-mode-behavior.md` (§11 = complete-run extension), plus the three written and
  adversarially-verified on the S3K branch: `s3k-trace-recorder-behavior.md` (core RAM map
  + CSV + metadata delta), `s3k-aux-events.md` (every aux family + per-fixture presence
  table), `s3k-profiles-and-hooks.md` (profiles, offset derivations, hook inventory, §2.4
  deferral).

## Task list mapping

Harness tasks #1–#6, #9 completed; #7 in_progress (S3K standard); #8 pending (S3K
complete-run). Keep `docs/status/trace-frontier-log.md` out of scope — recorder migration does
not move replay frontiers.
