# Trace Version Consolidation Implementation Plan

> Execute entirely in the `bugfix/ai-trace-v5-consolidation` worktree. Do not
> merge or copy any implementation, documentation, generated fixture, or
> scratch result to `develop` until every task through final verification is
> complete. Canonical fixture replacement still requires the existing
> exact-byte approval gate.

**Goal:** Replace the unpublished multi-generation trace surface with one
strict v5 contract, regenerate every retained production fixture through the
native BizHawk headless harness, and retain all eight S1 credits-demo routes as
native v5 evidence.

**Architecture:** One `trace_schema: 5` selects the complete current contract.
Provenance is opaque and never controls replay. Level rows are fixed at 42
columns, special-stage rows are fixed by game/profile, hardware timing has one
module-plus-direct grammar, and run manifests have one dynamic-art-gap grammar.
Legacy readers and normalizers are deleted. A programmatic fleet validator and
candidate comparator freeze inventory and deltas before publication.

**Toolchain:** Java 21/Maven/JUnit 5, Mono 6.12, BizHawk 2.11 GPGX, C# 7-era
syntax used by the existing harness, Python 3 standard library for read-only
inventory/comparison tooling, gzip/SHA-256.

## Constraints

- Keep the work isolated in the repository worktree
  `.worktrees/trace-v5-consolidation`.
- Preserve the eight `src/test/resources/traces/s1/credits_*` directories,
  replay classes, and focused consumers until approved native replacements are
  installed atomically.
- Use `tools/retro/s1_credits_trace_recorder.py` only as behavioral reference.
  Canonical credits capture runs through `tools/bizhawk-headless/run.sh` and
  GPGX, with ROM-owned demo input and no BK2 dependency.
- Do not add compatibility readers, version aliases, row-width autodetection,
  or recorder-version behavior gates.
- Do not normalize away proposed fixture differences. Candidate reports must
  expose literal metadata/manifests and decompressed payload changes.
- Do not rewrite historical audits, old implementation plans, validation
  reports, or dated frontier entries. Update maintained contracts and add a
  supersession note where required.
- Preserve hard rule 4: timing data may delay readiness of matching production
  work only; schema consolidation must not broaden authority.

## Task 1: Freeze the baseline and the v5 inventory

**Create:**

- `docs/architecture/validation/trace/2026-08-03-trace-v5-baseline.md`
- `tools/traces/validate_trace_v5.py`
- tests for the validator under the repository's existing tool-test location

1. Record the worktree commit, dirty diff, JDK, Mono, BizHawk, ROM hashes, and
   current fixture counts by game/profile/row width/version field.
   Freeze a deterministic machine-readable per-path inventory for every
   installed file under `src/test/resources/traces/`, with file kind, stored
   SHA-256, logical SHA-256 for gzip payloads, and a stable aggregate hash. The
   inventory generator and Task 8 verifier must share the same implementation
   so added, removed, or changed files are detected.
2. Record the pre-change Java full-suite and `*TraceReplay` outcomes. Classify
   environment-wide failures such as unavailable LWJGL separately so they can
   be compared after the change.
3. Write failing validator tests for the desired fleet rules: v5 envelope,
   forbidden legacy keys, fixed row widths, current timing grammar, current run
   manifest shape, native production provenance, and no alternate `*_retro`
   sidecars.
4. Implement the read-only validator. It must inspect plain or gzipped payloads,
   enumerate every metadata/manifest file, and fail with exact paths. It must
   not rewrite fixtures.
5. Run the validator against current `develop` and retain its expected-red
   inventory as the migration checklist.

## Task 2: Make every native writer emit one v5 envelope

**Modify:** native metadata/manifest writers in
`tools/bizhawk-headless/src/Recording/`, their tests, and the C# project files as
needed.

1. Add a shared native trace-contract owner with constants for
   `trace_schema: 5`, `recorder: native-bizhawk-headless`, and
   `recorder_version: 3.0`.
2. First change exact-literal writer tests to require the v5 envelope and reject
   `lua_script_version`, `csv_version`, `ss_csv_version`,
   `hardware_timing_schema`, and `run_schema`.
3. Update S1, S2, S3K, special-stage, complete-run, and run-manifest writers to
   use that owner. Remove game-specific version constants and parameters rather
   than aliasing them.
4. Rename `dynamic_art_transfer_state_per_frame_v1` to
   `dynamic_art_transfer_state_per_frame` in writers and exact tests.
5. Add `native_prelude_bootstrap` only where the required frame-zero evidence
   is physically present.
6. Update frozen Lua diagnostic recorders to emit the strict v5 envelope with
   `recorder: lua-bizhawk-diagnostic` and `recorder_version: 3.0`; do not make
   them publication-authoritative or add Java compatibility for their old
   output.
7. Run all native writer/unit tests with no ROM-backed gates.

## Task 3: Add first-class native S1 credits-demo capture

**Create:**

- `tools/bizhawk-headless/src/Recording/S1CreditsDemoCaptureRunner.cs`
- `tools/bizhawk-headless/src/Recording/S1CreditsDemoMetadataWriter.cs`
- `tools/bizhawk-headless/src/Recording/S1CreditsDemoCatalog.cs`
- `tools/bizhawk-headless/src/Recording/S1CreditsDemoCollectionSink.cs`
- `tools/bizhawk-headless/src/Core/IMainRamWriter.cs`
- `tools/bizhawk-headless/tests/S1CreditsDemoCaptureRunnerTests.cs`
- `tools/bizhawk-headless/tests/S1CreditsDemoDifferentialTests.cs`

**Modify:**

- `tools/bizhawk-headless/src/Core/GpgxHost.cs`
- `tools/bizhawk-headless/tests/FakeS1Host.cs`
- `tools/bizhawk-headless/src/Program.cs`
- `tools/bizhawk-headless/tests/TraceCliTests.cs`
- both C# project files and `tests/TestMain.cs`
- maintained S1 harness documentation

1. Add failing host tests for a narrow optional `IMainRamWriter` with bounded
   byte writes and implement it in `GpgxHost` through the existing 68K-RAM
   memory domain. Keep writes out of the general `IGpgxHost` observation
   contract so unrelated fake hosts do not gain mutation authority.
2. Port the eight demo identities, expected zone/act words, timers,
   active-demo recognition, start boundary, end boundary, timeout, and
   collection completion from the stable-retro reference into an independently
   testable native state machine. Boot once, press Start at title, detect the
   level-family mode, and write only verified setup state (`f_demo = 0`,
   `v_creditsnum = 0`, `v_gamemode = GM_Credits`). Do not directly force an
   individual demo or reproduce LZ lamppost/water state; the ROM's real credits
   flow owns those details.
3. Add CLI tests for `--trace-profile credits_demo --credits-target all|0..7`.
   In this mode `--movie` is forbidden rather than required; open GPGX with
   `GpgxHost.CreateGhz1SyncSettings()`. Reject non-S1 ROMs and incompatible
   profile/run/segment/effective-length arguments, and reject
   `--credits-target` outside this profile.
4. Test that external controller input is cleared permanently after redirect,
   then read the ROM controller byte from main RAM for recorded input. Preserve
   direction bits, collapse A/B/C to the engine jump bit, and ignore Start. Do
   not feed a BK2 or a trace row back into emulation.
5. Reuse `S1TraceCsvWriter` for canonical 42-column rows and
   `S1AuxEventEngine` for current auxiliary evidence. Emit one atomic output
   directory per selected demo with existing fixture identities and metadata
   fields (`trace_type`, `input_source`, index, slug, zone, act, start frame).
6. Stage all eight directories under a scratch candidate root as one no-replace
   transaction. Test all eight segment inventories, partial-failure rollback,
   forced canonical compression, lifecycle timeout, skipped/duplicate indices,
   detection/exit-frame exclusion, per-segment aux reset, and exact metadata
   envelope. Assert that this transaction cannot resolve or write a canonical
   fixture path. A single-demo target remains diagnostic, not a partial
   canonical publication. Only Task 10 installs candidate bytes.
7. Observe dynamic-art ownership across the entire pass. Require the ledger to
   drain before each arm, terminal-forward only callbacks belonging to the last
   stored row, and fail with lifecycle details rather than clearing or
   fabricating pending work.
8. Add a ROM-backed differential diagnostic. For each old 20-column fixture
   compare every overlapping field row by row; report new v5 columns and aux
   changes as additions, and report every common-field mismatch without
   normalization. The known constant-zero predecessor `v_framecount` defect
   must remain visible. Do not require predecessor equality to make the normal
   native unit suite green.
9. Add a clean-root native determinism gate that captures all eight routes
   twice and requires identical logical physics/aux payloads, inventory, and
   fixed-date metadata. At each predecessor first-divergence frame, assert the
   emitted common fields equal independent raw-host RAM reads. Record the full
   predecessor delta inventory in the ignored task report for later freezing
   in Task 9; do not approve the six physical-delta routes in this task.
10. Run the focused unit tests and the eight-route ROM-backed native gates. Treat
   predecessor counts 535/539/535/523/538/539/535/537 as validation evidence,
   never as hardcoded stop conditions.

## Task 4: Make Java trace loading strict v5-only

Tasks 4 and 5 are one atomic green checkpoint. Removing the metadata record's
`hardware_timing_schema` component removes its Java accessor, so the strict
loader and sole timing grammar must be implemented, tested, reviewed, and
committed together. Do not create an intermediate compatibility accessor or
commit a tree that does not compile.

Before resuming implementation, replace the separate Task 4 brief with one
joint Tasks 4+5 brief. The joint checkpoint must delete the transient
`hasHardwareTimingStream()` metadata shim and migrate every production caller
(`TraceSessionLauncher`, `TraceRunReplayWalker`, replay bootstrap, capture tool,
and benchmark tool) to the compiled schedule/file-presence contract. It must
also update every removed-accessor call and positional `TraceMetadata`
constructor across all test sources; a focused Surefire selection is not a
substitute for compiling the complete test tree.

**Modify:**

- `src/main/java/com/openggf/trace/TraceMetadata.java`
- `src/main/java/com/openggf/trace/TraceFrame.java`
- `src/main/java/com/openggf/trace/TraceData.java`
- `TraceBinder`, replay/bootstrap consumers, and affected tests/synthetics

1. Write failing metadata tests that accept exactly schema 5, treat provenance
   as opaque, expose semantic capabilities, and reject absent/other schemas and
   all removed fields.
2. Replace `nativePreludeMode()` recorder-version parsing with the explicit
   `native_prelude_bootstrap` capability and update its three behavior callers.
3. Write failing row-parser tests that accept one 42-column level shape and
   reject 11, 18, 19, 20, 22, 37, and 38 columns. Remove version parameters,
   width inference, the overloaded v4 path, and pre-schema execution-counter
   heuristics.
4. Keep special stages on their dedicated readers, selected strictly by game
   and profile, and assert fixed widths S1=14, S2=48, S3K=20.
5. Make animation/subpixel availability inherent in v5 and update callers and
   diagnostics that still mention CSV v7.
6. Replace resource-backed synthetic dependencies with canonical v5 inputs
   generated under test-owned temporary roots or constructed in memory. Leave
   every installed path under `src/test/resources/traces/` byte-identical to
   the frozen Task 1 baseline until Task 10. Retain only small explicit invalid
   inputs inside negative tests; no production legacy allowlist.
7. Run focused trace parsing, bootstrap, catalog, special-stage, and invariant
   guards. First run JDK-21 `mvn -DskipTests test-compile` and require the whole
   test source tree to compile. Tests that execute installed legacy resources,
   including committed timing-order cases, use temporary v5 inputs where they
   are contract tests or are explicitly deferred until Task 10 where they are
   publication tests; installed resources remain untouched.

## Task 5: Collapse hardware timing to the sole current grammar

Complete and commit this task together with Task 4 as the atomic Java
metadata/timing checkpoint described above.

**Modify:** Java timing loader/schedule/compiler, C# timing engine, authority
guard, committed-fixture guard/tests, and synchronized hard-rule documentation.

1. Write failing tests that v5 file presence enables both
   `kos_module_queue` and `kos_decompression_queue`, rejects malformed/currently
   unauthorized events, and has no timing-schema selector.
2. Delete timing schema 1 behavior, constructors, branches, metadata accessors,
   normalizers, and exact-literal compatibility cases in Java and C#. This
   explicitly includes `HardwareTimingStreamLoader.normalizeCanonicalOrder` and
   its special direct-PRE/module-POST swap. Native v5 writers emit canonical
   order directly, and loader tests accept only that order.
3. Preserve ordinal, stable submission fingerprint, prepared production-work,
   and service-boundary matching unchanged.
4. Update `TestHardwareTimingAuthorityGuard` for the unversioned dynamic-art
   capability and prove no new parser/authority path was introduced.
5. Add or retain behavioral replay-port tests for both allowed work kinds and
   rejection of mismatched kind, ordinal, stable submission fingerprint,
   service boundary, unprepared work, duplicate/stale completion edges, and
   suppressed-row escape. The source authority guard complements rather than
   replaces these release tests.
6. Run timing unit tests, authority guards, and S3K recorder tests using
   synthetic/generated v5 inputs. Defer AIZ/HCZ/MHZ committed-fixture replays
   until approved v5 fixtures are installed in Task 10.
7. Run the no-ROM C# timing/writer suite explicitly:
   `BIZHAWK_HOME=... ./test.sh --no-gates --jobs 1`, in addition to the Java
   JDK-21 `test-compile` and focused tests. Require both languages green, both
   fixture-inventory verifiers green, and commit Tasks 4+5 together.

## Task 6: Collapse run manifests to the sole current grammar

**Modify:** `TraceRunManifest`, dynamic-art gap comparison, native manifest
writers, run walkers/catalog tests, synthetics, and publication gates.

1. Write failing tests requiring `trace_schema: 5` and an explicit
   `dynamic_art_gap_transitions` array on every manifest, including empty.
2. Remove `run_schema`, Lua-version properties, schema-1 bypasses, schema-2
   branches, and differential normalizers.
3. Make structural and semantic dynamic-art-gap validation unconditional.
4. Update all run modes and move synthetic run inputs to test-owned temporary
   roots or in-memory builders, then run run-manifest, walker, and catalog tests
   against those generated v5 inputs. Do not rewrite installed trace resources.
   Defer any publication gate that compares with the installed legacy fleet
   until Task 10.

## Task 7: Add programmatic candidate comparison and finish policy docs

**Create:**

- `tools/traces/compare_trace_v5_candidates.py`
- tests for overlapping-column, full-payload, metadata, manifest, timing,
  inventory, and SHA-256 reporting

**Modify:**

- `AGENTS.md` and `CLAUDE.md` together
- `docs/architecture/designs/2026-07-27-cross-game-hardware-timing-trace-contract.md`
- maintained trace framework/replay guides and BizHawk-headless docs
- mirrored `.agents`/`.claude` trace-replay skills that describe live schemas

1. Build a read-only comparator that accepts old and candidate roots,
   inventories both, decompresses payloads, hashes literal stored and logical
   content, compares all common columns by header name, and classifies added or
   removed columns/events separately. Its predecessor scanner describes legacy
   keys/widths without accepting them as v5; `validate_trace_v5.py` runs only on
   candidate or installed-v5 roots. The comparator must never install
   candidates.
2. Make v5-to-v5 comparison literal for metadata/manifests; allow no legacy
   recorder/schema substitution. The one-time credits comparison uses its
   explicit 20-to-42 common-column mode and exposes every addition.
3. Add a supersession section to the hardware-timing design and update hard
   rule 4 to one v5 grammar without changing authority.
4. Document native credits capture, the single schema, opaque provenance,
   fixture validation, capture matrix, and exact-byte publication workflow.
5. Add a read-only test resource-root override confined to trace fixture
   loaders so Java replay tests can consume a complete scratch candidate fleet.
   It selects comparison data only, must not hydrate gameplay state, and must
   not be set by committed default configuration. Add an authority guard for
   that confinement.
6. Define a machine-readable S1 credits raw-host evidence artifact outside the
   candidate fixture root. For each disclosed predecessor first-divergence it
   records route, row, common field, RAM address and endianness or documented
   derivation, raw value, emitted value, and the candidate logical-payload hash.
   Add a credits-only, no-replace raw-observation side channel to the native CLI
   that records pre-writer RAM/derivation values outside both candidate and
   installed roots without affecting capture bytes. Add a builder that joins
   those independent observations to the final comparator report, plus a
   verifier that rejects a mismatch or hash drift.
   The CLI option is accepted only with movie-free
   `credits_demo --credits-target all`. Refactor the collector to stream all 20
   common fields per stored row in canonical order to a bounded private spool;
   capture code must not accept emitted rows or candidate hashes. Validate
   duplicate, missing, out-of-order, malformed, over-limit, existing-path,
   overlap, symlink, partial-capture, serialization, and seal failures. The
   builder and verifier both consume the raw sidecar; test swapped sidecars,
   candidate hash drift, and candidate-derived fabricated evidence.
   Add `--credits-raw-observation-id` as a required opaque printable-ASCII token
   whenever the sidecar option is present; reject controls, separators, empty
   and dot-path identities, and do not synthesize a wall-clock default. Seal
   the stream with a completion record binding that identity, resolved
   candidate root, all-eight completion, row/observation/byte totals, and the
   SHA-256 of preceding bytes; reject truncation or inconsistent totals.
7. Run doc mirrors/policy guards and prove AGENTS/CLAUDE and mirrored skills are
   synchronized where required.

## Task 8: Gate the implementation before capture

1. Run all C# unit tests, then ROM-backed behavioral capture gates that validate
   generated scratch output without requiring the installed legacy fleet to be
   byte-identical v5. Do not run exact committed-fixture differential or
   publication gates before Task 9 creates the complete candidate root. Require
   zero unexpected failures or skips in the selected pre-capture gates.
2. Run the Java focused contract set for parsing, timing, manifests,
   special-stage, catalog, compression, authority, and synthetic credits/v5
   contracts. Do not run production fixture replays here: the installed fleet
   is intentionally still legacy until approval and Task 10.
3. Run the v5 validator against generated test candidates and require green.
4. Compare the current worktree and index against the Task 1 frozen inventory
   of `src/test/resources/traces/` and fail if any installed trace resource was
   added, removed, or changed. Generated v5 synthetics must live outside that
   root. Repeat this guard immediately before the Task 10 installer; only that
   installer is allowed to mutate installed trace resources.
   Audit the full Java test source tree for positive-path legacy metadata and
   direct resource-backed synthetic dependencies. Replace them with generated
   temporary v5 inputs, retain legacy literals only in explicit rejection
   tests, and guard that boundary. The resulting unused legacy synthetic files
   remain installed and frozen until their explicit Task 10 deletion delta.
5. Fetch the latest `origin/develop` and reconcile it into this worktree before
   freezing the recorder. Do not update, switch, or merge the main workspace.
   Re-run Tasks 2–8 tests affected by the reconciliation.
6. Obtain independent code review for trace authority, native recorder
   semantics, strict parsing, credits lifecycle, and publication safety. Fix
   every valid issue and repeat until no blocker remains.
7. Rebuild once after review and freeze source commit/diff hash plus native
   artifact SHA-256. Any later source change invalidates all captures.

## Task 9: Regenerate the complete native fleet to scratch

1. Create a new v5 capture-matrix artifact under
   `docs/architecture/validation/trace/` with a supersession link to the
   historical July plan. Derive 36 invocations from the reconciled retained
   tree: the historical 32, the upstream S1 emerald complete run, the
   independent 67-segment S3K Knuckles complete-super-emerald run, and two
   native S1 credits `all` invocations into distinct newly absent roots. Record
   current v5 assumptions, then expand the matrix programmatically to literal
   commands. Each credits command supplies its own stable capture identity and
   distinct newly absent raw-sidecar path outside both roots; freeze its schema,
   record count, byte count, and stored SHA-256 with that invocation.
   Do not edit the dated July implementation plan.
2. Verify ROM and BK2 hashes, scratch capacity, output absence, source/diff
   hash, and harness artifact hash before each invocation.
3. Run the complete matrix serially through `tools/bizhawk-headless/run.sh`.
   Credits use the new movie-free BizHawk-headless selector; all other routes
   retain their reviewed movies/selectors. A credits candidate is inseparable
   from its paired raw sidecar; selecting one of the two captures also selects
   only that sidecar.
4. Freeze invocation command, elapsed time, exit status, output inventory,
   stored/logical SHA-256, row/event counts, segment identities, and manifest
   membership. Reject and diagnose any partial or unexpected output.
5. Run the v5 validator on the entire candidate root. Require every retained
   production fixture and all eight credits demos exactly once.
6. Compare the two post-freeze credits captures. Require identical segment
   inventory, logical physics/aux payloads, and fixed-date metadata; freeze both
   hashes and identify one root as the publication candidate.
7. Run the candidate comparator against the installed predecessor. Classify
   every literal change. For credits, preserve the comparator's literal red
   result and freeze the complete per-route/per-field mismatch inventory. Apply
   the design's S1 credits predecessor-oracle gates: two clean native captures,
   ROM/disassembly justification, and changed replay frontier classification.
   New 42-column and aux evidence is listed separately; no normalization or
   silent exclusion is allowed.
8. Generate and verify the machine-readable raw-host evidence artifact from
   step 7's final first-divergence inventory, binding every disclosed
   row/value to the publication candidate's logical hash. Build it only from
   the frozen CLI raw-observation sidecar and comparator report; reject missing,
   extra, stale-hash, emitted-versus-raw mismatches, or candidate-derived values
   that lack an independent raw observation. Freeze the comparator report
   before evidence construction. Comparator, builder, and verifier outputs are
   no-replace; the verifier rereads the predecessor, candidate, and raw sidecar
   and independently recomputes the expected evidence.
9. Point the strict Java replay tests at the read-only candidate root and run
   all eight S1 credits replays before approval. Freeze pass/fail, first-error
   frame/field, and frontier classification in the candidate report. The
   installed legacy fixtures remain untouched.
10. Record the immutable report in
   `docs/architecture/validation/trace/2026-08-03-trace-v5-candidates.md`.
   Include an exact deletion manifest for each obsolete `*_retro` sidecar and
   unused legacy synthetic, with predecessor stored/logical hashes. No credits
   fixture, replay, or focused consumer may appear in that manifest.

## Task 10: Approve and publish candidates atomically

1. Present the frozen inventory, hashes, and classified deltas for explicit
   exact-byte approval. For S1 credits, call out that predecessor equality is
   red, include every shared-field mismatch and the independent native/ROM
   evidence, and require explicit approval of those replacement bytes. Do not
   replace canonical fixtures before approval.
2. After explicit approval of the frozen publication delta manifest, install
   exactly the frozen candidate bytes and delete exactly the approved deletion
   paths: obsolete `*_retro` sidecars and the named unused resource-backed
   legacy synthetics. The installer verifies each predecessor stored/logical
   hash and rejects any added, removed, or changed installed trace path outside
   that manifest. It must never delete an `s1/credits_*` directory, credits
   replay class, or focused credits consumer.
3. Re-run the validator and comparator against installed bytes and prove exact
   candidate identity.
4. Run native publication/differential gates, Java fixture guards, all eight S1
   credits replays, and the complete `*TraceReplay` fleet. Update the frontier
   log with exact commands, counts, and first errors.
5. Run the full three-ROM Maven suite on JDK 21 and compare with Task 1. No test
   that passed at baseline may regress; environment/pre-existing failures must
   not worsen or change due to this branch.

## Task 11: Commit, reconcile, integrate, and clean up

1. Ensure all design, plan, validation, maintained documentation, code, tests,
   and approved fixture bytes are tracked. Leave scratch captures untracked and
   outside publication.
2. Update `CHANGELOG.md` and the `README.md` release/change-log section as
   required by commit/merge policy. Commit coherent worktree changes with all
   required trailers; never use `--no-verify`.
3. Fetch and fast-forward the main-workspace `develop` only after this worktree
   is fully green. On that fast-forwarded, pre-merge `develop`, run and preserve
   the full three-ROM Maven baseline with `surefire.forkCount=1` for a stable,
   memory-bounded comparison. A fresh current-`develop` four-fork run loaded
   every LWJGL native successfully; its failure was Java heap exhaustion, so
   there is no confirmed native-library extraction defect to work around. If new upstream
   source or recorder-relevant documentation
   appeared after Task 8's freeze, reconcile it into this worktree and return to
   Tasks 8–9 for a fresh review, artifact freeze, complete fleet recapture, new
   comparison, and new approval. Never combine candidate bytes from different
   source/diff/artifact hashes. A doc-only upstream change still gets the full
   verification required by its affected surface, while any uncertainty is
   treated as freeze-invalidating.
4. After the frozen batch still matches the reconciled source, run that same
   single-fork three-ROM suite plus focused/native gates in the reconciled
   worktree and preserve the result. Commit the resolution and merge the
   completed branch into main-workspace `develop` without switching the main
   workspace. Run the same full suite and trace fleet on merged `develop`,
   compare against the explicitly recorded pre-merge main baseline and
   worktree result, and push only `develop`.
5. Verify the worktree is clean and fully merged, remove it, delete the local
   worktree branch, and prune metadata. Preserve and report any unknown or
   user-authored change instead of discarding it.

## Required final evidence

- One strict v5 fleet with no legacy version fields or compatibility branches.
- Native BizHawk-headless capture and replay coverage for all eight S1 credits
  demos.
- Immutable candidate and publication hashes with every delta classified.
- Native unit/gate results, focused Java contracts, complete trace fleet, and
  full Maven baseline comparisons.
- Integrated/pushed branch and commit identities, or an exact unresolved state
  if any mandatory integration step fails.
