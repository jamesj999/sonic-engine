# S1 Trace Profile Hygiene Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the S1 trace profile semantically green by moving movie-end expectations into reproducible run metadata and correctly owning MZ1 regression probes.

**Architecture:** Add an optional manifest terminal-mode contract whose absence disables tail replay, populate it from the S1 recorder’s observed ROM mode, and remove the game-wide playback-profile rule. Independently migrate MZ1 slot regressions to canonical replay bootstrap, remeasure them, and split only confirmed known-red probes from blocking guards.

**Tech Stack:** Java 21/JUnit Jupiter, Maven Surefire, Lua S1 recorder, C# 7.x native BizHawk recorder tests, JSON run manifests.

## Global Constraints

- ROM/disassembly and recorder output are authoritative.
- No game, route, fixture-name, or frame-number carve-outs.
- No trace hydration, tolerance changes, or physics/aux regeneration.
- Wire values are absent, `level`, or `title_screen`; absence is internal `UNSPECIFIED`.
- S2/S3K defaults remain absent/`UNSPECIFIED`.
- Keep standard and complete-run MZ1 trace replay coverage selected.
- Update `docs/status/trace-frontier-log.md`.

---

### Task 0: Isolate the fix branch

**Files:**
- Worktree: unique `/tmp/openggf-s1-trace-profile-hygiene`
- Branch: `bugfix/ai-s1-trace-profile-hygiene`

- [ ] Record exact fetched `origin/develop` as `FIX_BASE`; create the isolated worktree
      from it and verify tracked cleanliness.
- [ ] Preserve primary-worktree dirty paths; read ROMs and the external completion bundle
      by absolute path only.
- [ ] Before each commit, satisfy every required trailer. A `fix` touching `src/main`
      stages `CHANGELOG.md` or uses an inline justified
      `Changelog: n/a: <reason>`.

### Task 1: Add the typed manifest and walker contract

**Files:**
- Modify: `src/main/java/com/openggf/trace/TraceRunManifest.java`
- Modify: `src/main/java/com/openggf/game/profiles/trace/TracePlaybackProfile.java`
- Modify: `src/main/java/com/openggf/trace/replay/runs/TraceRunReplayWalker.java`
- Modify: `src/test/java/com/openggf/tests/trace/runs/AbstractRunChainTest.java`
- Modify: `src/test/java/com/openggf/tests/trace/TestTraceRunManifest.java`
- Modify: `src/test/java/com/openggf/tests/trace/runs/TestTracePlaybackProfile.java`
- Modify: `src/test/java/com/openggf/tests/trace/runs/TestTraceRunReplayWalkerControlFlow.java`
- Modify: `src/test/java/com/openggf/tools/TraceCaptureToolArgsTest.java`
- Modify related profile tests found by `rg 'expectsTitleScreenAfterMovie|TracePlaybackProfile'`

**Interfaces:**
- Produces nested/public typed enum:
  `TraceRunManifest.ExpectedMovieEndMode { UNSPECIFIED, LEVEL, TITLE_SCREEN }`
- Produces: `TraceRunManifest.expectedMovieEndMode()`
- Consumes: optional JSON `expected_movie_end_mode`
- Preserve source compatibility with an overload matching the current record constructor,
  delegating with `ExpectedMovieEndMode.UNSPECIFIED`, or update every constructor callsite
  found by `rg 'new TraceRunManifest\\('`.

- [ ] Write RED parser tests for absent, `level`, `title_screen`, and invalid values.
- [ ] Run focused parser tests and confirm failures are due to the missing contract.
- [ ] Implement strict optional parsing; unknown/non-string values fail closed.
- [ ] Extract the private remaining-tail policy from `AbstractRunChainTest` into a
      **public pure planning API** on production `TraceRunReplayWalker` (public because
      the production and test packages differ); keep I/O and chain orchestration in
      `AbstractRunChainTest`.
- [ ] Write RED walker tests proving:
  - `UNSPECIFIED` skips tail replay and assertion;
  - declared `LEVEL` and `TITLE_SCREEN` replay remaining rows and assert;
  - `tailStart > movie.frameCount()` fails diagnostically;
  - equality replays zero rows but still asserts.
- [ ] Remove the S1-wide terminal-title profile boolean and implement manifest-only behavior.
- [ ] Run focused parser, profile, and walker tests to GREEN.
- [ ] Commit only this contract with required trailers.

### Task 2: Make recorder output reproducible

**Files:**
- Modify: `tools/bizhawk/s1_complete_run_recorder.lua`
- Modify: `tools/bizhawk-headless/src/Recording/S1RunCaptureRunner.cs`
- Modify: `tools/bizhawk-headless/src/Recording/S1RunManifestWriter.cs`
- Modify: `tools/bizhawk-headless/src/Recording/RunManifestWriter.cs`
- Modify: `tools/bizhawk-headless/src/Recording/S2RunManifestWriter.cs`
- Modify: `tools/bizhawk-headless/src/Recording/S3KRunManifestWriter.cs`
- Modify: `tools/bizhawk-headless/src/Recording/S1CompleteRunMetadataWriter.cs`
- Modify: `tools/bizhawk-headless/docs/s1-run-mode-behavior.md`
- Modify: existing `S1RunManifestWriterTests`,
  `S1CompleteRunMetadataWriterTests`, runner/capture tests, and differential tests
- Modify `TestMain.BuildRegistry()` and both `.csproj` files only if a new C# file/class is
  added; verify registration explicitly.

**Interfaces:**
- Consumes: final true-movie-end S1 `v_gamemode`
- Produces: nullable manifest token `level` for `$0C`, `title_screen` for `$04`

- [ ] **Lua authority first:** add final-mode mapping only on true movie completion;
      `S1_STOP_AT_FRAME` and the Lua-only `FRAME_CAP` termination omit the field. Bump
      every Lua version location. Syntax-compile without executing BizHawk globals using:
      `python3 -c 'from pathlib import Path; from lupa import LuaRuntime; s=Path("tools/bizhawk/s1_complete_run_recorder.lua").read_text(); LuaRuntime(unpack_returned_tuples=True).eval("function(x) return assert(load(x)) end")(s)'`.
      If `lupa` is unavailable, record that prerequisite explicitly and use an actual
      EmuHawk load/capture as the syntax/runtime gate; do not claim the compile command ran.
- [ ] Capture the committed short movie with Lua into a new nonexisting scratch directory,
      one EmuHawk at a time, hooks unset. Mechanically compare every physics/aux file,
      segment inventory, BK2 offset, row count, hashes, line endings, and manifest bytes.
      The only permitted payload delta is manifest endpoint/version metadata.
- [ ] Install the Lua-produced committed manifest as a separate fixture-data commit after
      user-approved regeneration; do not install native-produced bytes.
- [ ] Write RED native writer/capture tests for `$0C`, `$04`, and `stopAtFrame`.
      The native runner has no absolute-cap stop, so `FRAME_CAP` omission remains
      Lua-only validation. Update `S1CompleteRunMetadataWriter`'s production version
      constant.
- [ ] Implement nullable endpoint propagation in native S1 runner/writer; update shared
      `RunManifestWriter.Format` callsites so S2/S3K pass null and remain byte-identical.
- [ ] Run:
      `tools/bizhawk-headless/test.sh --filter S1RunManifestWriter --jobs 1`,
      `tools/bizhawk-headless/test.sh --filter S1CompleteRunMetadataWriter --jobs 1`,
      `tools/bizhawk-headless/test.sh --filter S1RunCaptureRunner --jobs 1`, and
      `tools/bizhawk-headless/test.sh --filter S1RunModeDifferential --jobs 1`
      against the Lua-installed fixture. Inspect PASS/FAIL/SKIP counts and CRLF
      normalization.
- [ ] Finish with the full native suite:
      `BIZHAWK_HOME=<abs> S1_ROM_PATH=<abs> S2_ROM_PATH=<abs> S3K_ROM_PATH=<abs> tools/bizhawk-headless/test.sh`;
      report actual counts and reject gate skips.
- [ ] Commit Lua/schema/spec changes separately from the Lua-produced fixture commit and
      the native-port commit. Use `git add -f` for ignored tracked-tool additions and verify
      `git show --stat`/`git ls-files`.

### Task 3: Verify both terminal lifecycles

**Files:**
- Modify only if needed for fixture wiring: committed S1 run-chain test resources/tests
- Runtime only: external completion directory

**Interfaces:**
- Consumes: committed short manifest (`level`)
- Consumes: external full-completion manifest (`title_screen`)

- [ ] Run the committed GHZ maze chain:
      `mvn "-Dtest=com.openggf.tests.trace.runs.TestS1GhzMazeRoundTripChain"
      "-Dsonic1.rom.path=<verified-absolute-s1-rom>" test`; require its 232-row tail to end
      in `LEVEL`.
- [ ] Capture the full completion movie through the Lua authority into a new nonexisting
      scratch output; never overwrite the preserved 604 MB backup. Validate BK2 offset,
      segments, 10,943-row tail, physics/aux hashes, and manifest delta mechanically.
- [ ] Install only the proven generated manifest into a separate copy of the external
      bundle (or point the test at the fresh complete output); retain the preserved backup
      unchanged.
- [ ] Run:
      `mvn "-Dtest=com.openggf.tests.trace.runs.TestS1GhzMazeRoundTripChain"
      "-Dsonic1.rom.path=<verified-absolute-s1-rom>"
      "-Dopenggf.trace.s1.run.dir=<fresh-completion-output>" test`;
      require 10,943 tail rows and `TITLE_SCREEN`.
- [ ] Record commands, hashes, row counts, payload equality, and outcomes.

### Task 4: Canonically bootstrap and remeasure MZ1

**Files:**
- Modify: `src/test/java/com/openggf/tests/trace/s1/TestS1Mz1SlotLayoutRegression.java`
- Create: `src/test/java/com/openggf/tests/trace/s1/S1Mz1SlotLayoutHarness.java`
- Create after remeasurement: `src/test/java/com/openggf/tests/trace/s1/DebugS1Mz1SlotLayoutProbe.java`
- Modify: `pom.xml` with explicit `trace-diagnostics` opt-in profile
- Use as reference: `src/test/java/com/openggf/tests/trace/s1/TestS1Mz1LostRingCollectionOrderRegression.java`

**Interfaces:**
- Consumes: `TraceReplaySessionBootstrap`
- Produces: canonical S1 prelude/setup shared by all fourteen ROM-backed MZ1 methods

- [ ] First add a dedicated package-private bootstrap-equivalence characterization in
      `TestS1Mz1SlotLayoutRegression` that compares its current setup with a
      composition-based `S1Mz1SlotLayoutHarness` using
      `TraceReplaySessionBootstrap` through the native S1 object prelude. Make this RED
      only because the prelude is missing, then migrate the setup and make it GREEN.
      Run exactly:
      `mvn -Ptrace-replay "-Dtest=com.openggf.tests.trace.s1.TestS1Mz1SlotLayoutRegression#canonicalBootstrapIncludesNativeS1ObjectPrelude" "-Dsonic1.rom.path=<verified-absolute-s1-rom>" test`.
      Do not use the already-known-red frame-517 slot assertion to characterize bootstrap.
- [ ] Replace `SharedLevel`/manual VBlank/manual oscillator/frame-zero setup with canonical
      bootstrap for all methods.
- [ ] Run all fourteen ROM-backed methods and record exact pass/fail/first-evidence results.
- [ ] Keep every passing assertion in the suite-selected regression class.
- [ ] Move shared state/helpers into the non-test harness with package-private methods.
      Both JUnit classes compose the harness; neither inherits from the other, so selected
      green tests cannot be inherited by diagnostics.
- [ ] Move every still-red slot/lifetime method into `DebugS1Mz1SlotLayoutProbe`.
- [ ] Add `trace-diagnostics` Maven profile that includes `tests/trace/**/Debug*.java` and
      `*Probe*.java` without normal/trace-profile exclusions. Verify direct execution with
      `mvn -Ptrace-diagnostics "-Dtest=com.openggf.tests.trace.s1.DebugS1Mz1SlotLayoutProbe" "-Dsonic1.rom.path=<verified-absolute-s1-rom>" test`
      and require nonzero Surefire tests, zero skips, and the documented known-red
      assertion failures.
- [ ] Remove the stale reflective `usedSlots` test; do not replace it with a derived rewind
      snapshot. Add no new allocator API unless a separate focused unit-test plan proves it
      is needed.
- [ ] Run the selected regression class to zero failures, then direct-run the diagnostic
      class and confirm each documented known-red probe still executes and reproduces.
- [ ] Commit only the bootstrap/ownership changes and required docs.

### Task 5: S1 verification and frontier update

**Files:**
- Modify: `docs/status/trace-frontier-log.md`

**Interfaces:**
- Consumes: Tasks 1–4 commits
- Produces: green S1 trace profile with retained diagnostic frontier evidence

- [ ] Run focused parser/walker/recorder tests with exact class names from Tasks 1–2.
- [ ] Run
      `TestS1Mz1TraceReplay` and `TestS1Mz1CompleteRunTraceReplay` individually, clearing
      `target/trace-reports` between them.
- [ ] Derive the exact previously audited S1 group from every reviewed row whose trace
      profile is `Yes` and source path is `/trace/s1/`: 33 `TRACE_REPLAY` sources, two
      selected abstract/helpers, and the selected `TestVerifierCostBenchmark` diagnostic.
      Map declared package + top-level class, reject duplicates, save the exact 36-FQCN
      request list to `target/debug-trace-audit/s1-trace-requested.txt`, and compare it to
      the previously audited 36. Use the preserved reviewed inventory at
      `/tmp/openggf-debug-trace-audit/docs/architecture/audits/testing/debug-trace-tests.md` as input;
      it is intentionally not present on the fix branch. Run from the fix worktree:
      `python3 -c 'import pathlib,re; a=pathlib.Path("/tmp/openggf-debug-trace-audit/docs/architecture/audits/testing/debug-trace-tests.md"); paths=[]; [(lambda c: paths.append(re.search(r"\(\.\./\.\./(src/test/java/com/openggf/tests/trace/s1/[^)]+\.java)\)",c[0]).group(1)))([x.strip() for x in line.strip().strip("|").split("|")]) for line in a.read_text().splitlines() if "/trace/s1/" in line and len([x.strip() for x in line.strip().strip("|").split("|")])>3 and [x.strip() for x in line.strip().strip("|").split("|")][3]=="Yes"]; out=[]; [(lambda s: out.append(re.search(r"^package\s+([^;]+);",s,re.M).group(1)+"."+re.search(r"^(?:(?:public|abstract|final)\s+)*class\s+(\w+)",s,re.M).group(1)))(pathlib.Path(x).read_text()) for x in paths]; assert len(out)==len(set(out))==36,(len(out),len(set(out))); d=pathlib.Path("target/debug-trace-audit"); d.mkdir(parents=True,exist_ok=True); (d/"s1-trace-requested.txt").write_text(",".join(out)+"\n")'`.
- [ ] Run the exact group with:
      `timeout --signal=TERM --kill-after=30s 90m mvn -Ptrace-replay
      "-Dtest=$(tr -d '\n' < target/debug-trace-audit/s1-trace-requested.txt)"
      "-Dsonic1.rom.path=<verified-absolute-s1-rom>" test`.
- [ ] Reconcile every requested class against fresh Surefire XML; allow zero tests only
      for the two named helper/abstract sources, require the other 34 to execute, and
      require zero failures/errors/skips for every executed selected test.
- [ ] Update the frontier log with command, commit/worktree, counts, moved terminal
      regression, MZ1 selected guards, and every retained diagnostic first failure.
- [ ] Commit the frontier update.
- [ ] Request independent implementation review; fix and re-review until GREEN.

### Task 6: Resume the audit on the reviewed fix

**Files:**
- Audit branch/worktree only

**Interfaces:**
- Consumes: exact reviewed S1-fix commit
- Produces: new audit `AUDIT_BASE` and audit-only patch range

- [ ] Before changing audit state, record the reviewed audit commit IDs
      `2c09512b8`, `c1d5c5acd`, `3715b75b6`, and `4e876a51b`, plus their prerequisite
      commits, and verify each with `git show --stat`.
- [ ] Leave `/tmp/openggf-debug-trace-audit` and its branch untouched. Create the unique
      `/tmp/openggf-debug-trace-audit-resumed` worktree and
      `bugfix/ai-debug-trace-test-audit-resumed` branch from the reviewed S1-fix HEAD;
      record that HEAD as `AUDIT_BASE`.
- [ ] Cherry-pick the exact Data Select guard and audit-document commit sequence once.
      Resolve only the expected frontier-document overlap by preserving both the S1-fix
      evidence and audit history; abort on any source overlap.
- [ ] Verify `AUDIT_BASE..HEAD` contains only the audit allowlist, then resume the audit
      at the S1 group, followed by S2, S3K, and the full trace profile.
- [ ] Resume Task 5 of the audit at S1, then S2, S3K, and full profile.

### Task 7: Hand off the reviewed fix (single apply point)

- [ ] Run this task only from `/tmp/openggf-s1-trace-profile-hygiene`. Verify that its
      `HEAD` exactly equals the independently reviewed S1-fix commit and is not the
      resumed-audit HEAD. Then require `git diff --name-only FIX_BASE..HEAD` in that
      worktree to match the reviewed allowlist, create a binary-capable patch for that
      exact range there, verify no overlapping primary edits, and apply only that patch
      to primary. Stop on any overlap/rejected hunk; never reset/checkout the dirty
      primary worktree.
- [ ] This is the **only** point where the S1-fix patch is applied to the primary
      worktree. Task 6 bases its new audit worktree directly on the reviewed fix commit
      and must not apply the patch to primary.
