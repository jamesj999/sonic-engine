# Native Trace Fleet Regeneration Implementation Plan

> **Superseded:** The publication work described here is closed by the v5
> evidence set and remediation records dated 2026-08-04:
> `2026-08-04-trace-contract-remediation-plan.md`,
> `2026-08-04-trace-v5-publication-manifest.md`, and the v5 replacement
> freeze/candidate reports. References below to schema 7, a separate
> `hardware_timing_schema`, and the pre-v5 publication gate are historical
> evidence, not live acceptance criteria.

> **For agentic workers:** Execute the checked steps in order. Fixture bytes
> remain scratch-only until the explicit publication approval gate.

**Goal:** Re-record every native-reproducible canonical trace with the current
headless BizHawk recorder, publish approved bytes, run every executable replay
test, and report all frontiers, with mandatory S1/S2 PLC+DPLC timing and S3K
KosM/direct queue timing.

**Architecture:** Derive a finite invocation matrix from canonical metadata and
native differential gates. Capture into isolated scratch directories, generate
an immutable evidence manifest, pause at the exact-byte publication gate, then
install approved files and measure replay frontiers.

**Tech Stack:** Mono 6.12, BizHawk 2.11 GPGX, native C# recorder,
Java 21/Maven/JUnit 5, shell/JQ/SHA-256/gzip.

## Global Constraints

- Use ROM files discovered at the repository root and verify the documented
  hashes.
- Canonical output comes only from `tools/bizhawk-headless/run.sh`.
- Leave S3K diagnostic hooks and every unmodeled `OGGF_*` recorder variable
  unset.
- Do not replace fixtures before exact-byte user approval.
- Never switch the main workspace branch or disturb its user changes.
- S3K candidates must use hardware-timing schema 2 and physical
  `load_queue_state_per_frame`; no canonical audit opt-out is permitted.
- Every S3K stored row must contain exactly one `s3k_kos_module` and one
  `s3k_kos_direct` queue state. Hardware timing and queue state are
  complementary and both are required.

---

## Frozen Capture Matrix

Every row uses `tools/bizhawk-headless/run.sh --mode trace --rom <ROM>
--movie <MOVIE> --output <SCRATCH>/captures/<ID>` followed by the listed
selector. `<SCRATCH>` is the capacity-checked root created in Task 1 and every
output path must be absent before invocation.

| ID | ROM | Movie | Selector | Expected publication |
|---|---|---|---|---|
| s1-ghz1 | `s1.gen` | `s1/ghz1_fullrun/ghz1_fullrun.bk2` | default S1 standalone profile (no `--trace-profile`) | `ghz1_fullrun` |
| s1-mz1 | `s1.gen` | `s1/mz1_fullrun/s1-mz1.bk2` | default S1 standalone profile (no `--trace-profile`) | `mz1_fullrun` |
| s1-complete | `s1.gen` | `s1/_movies/s1-complete-run.bk2` | `--trace-profile complete_run` | all 21 `*_completerun` dirs |
| s1-maze-run | `s1.gen` | `s1/runs/s1-ghz-maze-roundtrip/s1-ghz-maze-roundtrip.bk2` | `--run-id s1-ghz-maze-roundtrip` | run `ghz1,ss,ghz2`; `ss` also maps to standalone `special_stage` |
| s2-ehz1 | `s2.gen` | `s2/ehz1_fullrun/s2-ehz1.bk2` | `--trace-profile gameplay_unlock` | `ehz1_fullrun` |
| s2-arz-0/1 | `s2.gen` | `s2/arz/s2-lvl-select-ARZ.bk2` | `--trace-profile level_gated_reset_aware --gameplay-segment 0/1` | `arz` / `arz2` |
| s2-cnz-0/1 | `s2.gen` | `s2/cnz/s2-lvl-select-CNZ.bk2` | same profile, segment `0/1` | `cnz` / `cnz2` |
| s2-cpz-0/1 | `s2.gen` | `s2/cpz/s2-lvl-select-CPZ.bk2` | same profile, segment `0/1` | `cpz` / `cpz2` |
| s2-htz-0/1 | `s2.gen` | `s2/htz/s2-lvl-select-HTZ.bk2` | same profile, segment `0/1` | `htz` / `htz2` |
| s2-mcz-0/1 | `s2.gen` | `s2/mcz/s2-lvl-select-MCZ.bk2` | same profile, segment `0/1` | `mcz` / `mcz2` |
| s2-mtz-0/1/2 | `s2.gen` | `s2/mtz/s2-lvl-select-MTZ.bk2` | same profile, segment `0/1/2` | `mtz` / `mtz2` / `mtz3` |
| s2-ooz-0/1 | `s2.gen` | `s2/ooz/s2-lvl-select-OOZ.bk2` | same profile, segment `0/1` | `ooz` / `ooz2` |
| s2-dez | `s2.gen` | `s2/dez_ending/s2-lvl-select-DEZ-Ending.bk2` | same profile, segment `0` | `dez_ending` |
| s2-scz | `s2.gen` | `s2/scz/s2-lvl-select-SCZ.bk2` | same profile, segment `0` | `scz` |
| s2-wfz | `s2.gen` | `s2/wfz/s2-lvl-select-WFZ.bk2` | same profile, segment `0` | `wfz` |
| s2-special-stage | `s2.gen` | `s2/special_stage/s2-lvl-select-special-stage.bk2` | `--trace-profile s2_special_stage` | standalone `special_stage` only |
| s2-halfpipe-run | `s2.gen` | `s2/runs/s2-ehz-halfpipe-roundtrip/s2-ehz-halfpipe-roundtrip.bk2` | `--run-id s2-ehz-halfpipe-roundtrip --effective-movie-length 22612` | exact five-segment run manifest |
| s2-emeralds-run | `s2.gen` | `s2/runs/s2-sonic-tails-complete-emeralds/sonic-2-sonic-tails-complete-emeralds.bk2` | `--run-id s2-sonic-tails-complete-emeralds` | exact 35-segment manifest |
| s3k-aiz | `s3k.gen` | `s3k/aiz1_to_hcz_fullrun/s3-aiz1-2-sonictails.bk2` | `--trace-profile aiz_end_to_end --load-queue-state` | `aiz1_to_hcz_fullrun` |
| s3k-cnz | `s3k.gen` | `s3k/cnz/s3k-cnz-sonic-tails.bk2` | `--trace-profile level_gated_reset_aware --load-queue-state` | `cnz` |
| s3k-mgz | `s3k.gen` | `s3k/mgz/s3k-mgz-sonic-tails.bk2` | `--trace-profile level_gated_reset_aware --load-queue-state` | `mgz` |
| s3k-complete | `s3k.gen` | `s3k/_movies/s3k-complete-sonic-tails.bk2` | `--trace-profile complete_run --load-queue-state` | exact 15 top-level `*_completerun` dirs |
| s3k-multibonus-c | `s3k.gen` | `s3k/_movies/s3-knux-multibonus-ss.bk2` | `--run-id s3k-multibonus --load-queue-state` | `bonus_gumball`, `bonus_slots`, `bonus_pachinko`, `special_stage` |
| s3k-multibonus-b | `s3k.gen` | same movie | `--run-id s3-knux-multibonus-ss --load-queue-state` | exact nested 25-segment run manifest |

All paths are below `src/test/resources/traces/`. Task 1 expands slash variants
into one literal TSV row per process invocation and freezes exact expected
directory/manifest inventories from the named differential tests.
The current S3K recorder contract must emit `hardware_timing_schema: 2` for
every row above; schema selection is not a CLI opt-in and any schema-1 output
rejects the invocation.

### Task 1: Freeze the reproducible capture inventory

**Files:**
- Read: `src/test/resources/traces/**/metadata.json`
- Read: `tools/bizhawk-headless/tests/*DifferentialTests.cs`
- Create: `.scratch/trace-fleet-regeneration-*/capture-matrix.tsv`

- [ ] Hash each ROM and each unique BK2.
- [ ] Run `mvn -v`; stop unless Maven reports JDK 21.
- [ ] Discover root `.gen` files and verify CRC32/SHA-1:
  S1 `AFE05EEE`/`69E102855D4389C3FD1A8F3DC7D193F8EEE5FE5B`,
  S2 REV01 `7B905383`/`8BCA5DCEF1AF3E00098666FD892DC1C2A76333F9`,
  S3K `63522553`/`CFBF98C36C776677290A872547AC47C53D2761D6`.
- [ ] Map every canonical non-synthetic fixture directory to exactly one native
  invocation or one explicit unsupported reason.
- [ ] Reconcile payload directories, BK2/manifests/publication identities, and
  gameplay-test fixture references; fail on unmatched items.
- [ ] Verify every planned invocation's profile, gameplay segment, run id, and
  output shape against a current differential test.
- [ ] Record the confirmed capability gap: the current run-mode writer cannot
  arm on a movie that enters `$10` before a level segment.
- [ ] Expand the frozen matrix into literal commands containing `--rom`,
  `--movie`, `--output`, and selector; validate every expected segment and
  manifest entry before executing any row.
- [ ] Confirm the mapping covers all canonical metadata directories exactly
  once.
- [ ] Record filesystem type and available bytes. Derive a numeric peak from
  prior documented raw sizes (S3K complete run 2.84 GB, S2 complete emeralds
  375 MB) plus the summed expected outputs; require `(estimated peak * 1.25)`
  bytes available and record calculation/fail threshold.

### Task 2: Implement standalone S2 special-stage capture with TDD

**Files:**
- Modify: `tools/bizhawk-headless/src/Core/GpgxHost.cs`
- Modify: `tools/bizhawk-headless/src/Core/IGpgxHost.cs`
- Create: `tools/bizhawk-headless/src/Recording/S2SpecialStageCaptureRunner.cs`
- Create: `tools/bizhawk-headless/src/Recording/S2SpecialStageRunObjectsObserver.cs`
- Modify: `tools/bizhawk-headless/src/Recording/S2SpecialStageMetadataWriter.cs`
- Create: `tools/bizhawk-headless/tests/S2SpecialStageCaptureRunnerTests.cs`
- Modify: `tools/bizhawk-headless/src/Recording/S2SpecialStageAuxEventEngine.cs`
- Modify: `tools/bizhawk-headless/src/Program.cs`
- Modify: `tools/bizhawk-headless/tests/TraceCliTests.cs`
- Modify: `tools/bizhawk-headless/tests/S2TraceDifferentialTests.cs`
- Modify: `tools/bizhawk-headless/BizHawk.Headless.Gpgx.csproj`
- Modify: `tools/bizhawk-headless/BizHawk.Headless.Gpgx.Tests.csproj`
- Modify: `tools/bizhawk-headless/tests/TestMain.cs`
- Modify: `tools/bizhawk-headless/docs/s2-trace-recorder-behavior.md`

- [ ] Write failing runner tests for first-`$10`-frame capture, `$10` exit,
  movie-end stop, frame/lag/input rows, standalone metadata, input-sample
  callback identity, one-pass-per-sample enforcement, queue-forward publication
  on the next non-lag row, lag behavior, terminal raw-observation flush, exact
  cursor identity, and callback-failure propagation from
  `s2_ss_trace_recorder.lua`.
- [ ] Run the focused tests and confirm they fail because the standalone runner
  and CLI selector are absent.
- [ ] Implement the minimal runner and `--trace-profile s2_special_stage` CLI
  dispatch; reuse writers without changing run-mode output.
- [ ] Run focused tests to green.
- [ ] Add the exact-movie ROM-backed differential gate, first watching it fail
  on any byte mismatch, then correcting only independently justified recorder
  semantics until physics/aux match and metadata differs only by the reviewed
  native version/date policy.
- [ ] Run the full native no-gates suite and S2 gates with zero failures/skips.
- [ ] Amend the recorder behavior specification and obtain independent
  semantic review with no blocking issues.

### Task 3: Measure the canonical baseline frontiers

**Files:**
- Create: `.scratch/trace-fleet-regeneration-*/baseline/`

- [ ] Mechanically enumerate concrete gameplay replay classes and separate
  trace guards/policies.
- [ ] Run the fleet selection check:
  `mvn -q -Dmse=relaxed "-Dsonic1.rom.path=<S1>" "-Dsonic2.rom.path=<S2>" "-Ds3k.rom.path=<S3K>" "-Dtest=*TraceReplay" test`.
- [ ] For every gameplay class, clear `target/trace-reports`, run that class
  alone with the same ROM properties, and preserve Surefire/replay reports
  under its unique baseline directory.
- [ ] Record exactly one status per class: `green`, `red` (first frame/field,
  errors, warnings), `error` (setup/build/runtime detail), or `not executed`
  (reason), plus fixture recorder version.

### Task 4: Gate the current native recorder

**Files:**
- Modify: `tools/bizhawk-headless/src/Recording/HardwareTimingEventEngine.cs`
- Modify: `tools/bizhawk-headless/tests/HardwareTimingEventEngineTests.cs`
- Modify: `tools/bizhawk-headless/tests/S3KCompleteRunDifferentialTests.cs`
- Modify: `tools/bizhawk-headless/tests/S3KCompleteRunSegmentsDifferentialTests.cs`
- Modify: `tools/bizhawk-headless/tests/S3KRunModeDifferentialTests.cs`
- Read: `tools/bizhawk-headless/tests/S3KTraceDifferentialTests.cs`
- Modify: `tools/bizhawk-headless/docs/s3k-trace-recorder-behavior.md`
- Execute: `tools/bizhawk-headless/test.sh`

- [ ] Write and run a focused failing test that reproduces the CNZ/MGZ
  same-frame direct-Kos callback sequence. Confirm it fails on the staged-PRE
  guard before changing production code.
- [ ] Make the minimum ledger change that preserves each ROM submission while
  keeping PRE retirement completion owned by frame-end reconciliation; rerun
  the focused test green.
- [ ] Prove repeated same-frame direct-Kos callbacks preserve every ordered
  submission rather than overwriting a single staged retirement. Pin
  ordinal/fingerprint order through frame-end reconciliation.
- [ ] Write and run a focused failing complete-run differential test for the
  exact published schema-1 and current schema-2 metadata literals. Continue to
  reject malformed, partially upgraded, or mixed shapes.
- [ ] Cover named-run metadata and manifest schema fields with the same exact
  literal policy. Make the minimum normalizer changes and rerun those focused
  tests green.
- [ ] Run:
  `BIZHAWK_HOME=docs/BizHawk-2.11-linux-x64 S1_ROM_PATH=<S1> S2_ROM_PATH=<S2> S3K_ROM_PATH=<S3K> tools/bizhawk-headless/test.sh`.
- [ ] Map every capture family to its behavior specification, named
  ROM/disassembly invariants, and behavioral/unit test evidence in the
  publication audit.
- [ ] Obtain an independent semantic correctness review; stop unless it reports
  no blocking issues. Treat existing fixture parity as corroboration only.
- [ ] Rebuild the harness; record source commit, dirty-state/diff hash, and
  built-artifact SHA-256.
- [ ] Record the exact selected tests, pass/fail/skip totals, and log path.
- [ ] Stop capture on any applicable ROM-backed skip or any runtime, semantic,
  or unclassified recorder/publication failure. Only the exact enumerated
  successful-invocation schema-migration refusals below may remain red.
- [ ] The independent semantic review, rebuild, dirty-diff freeze, artifact
  hash, and full all-ROM gate all occur after both fixes. Before scratch
  capture, require all semantic/unit tests green and classify every remaining
  ROM-backed failure. Permit only exact, enumerated schema-1-to-schema-2
  migration refusals whose recorder subprocess exited successfully; retain
  those results as red until the proposed schema-2 fleet passes its frozen
  publication tests. Any other failure or skip stops capture.
- [ ] Freeze each permitted-red test's full identity and exact refusal-message
  signature in the publication audit before capture. Verify its recorder exit
  status separately and require every other assertion from that invocation to
  pass; any extra payload, structure, comparator, or process error is
  unclassified and stops capture.

### Task 4A: Close the S3K named-run special-stage queue gap

**Files:**
- Modify: `tools/bizhawk-headless/src/Recording/S3KCompleteRunCaptureRunner.cs`
- Modify: `tools/bizhawk-headless/src/Recording/S3KCompleteRunMetadataWriter.cs`
- Modify: `tools/bizhawk-headless/tests/S3KCompleteRunPublicationTests.cs`
- Modify: `tools/bizhawk-headless/tests/S3KRunModeDifferentialTests.cs`
- Modify stale behavior-spec comments that describe special-stage aux as empty.

- [ ] First add failing publication tests for an S3K named-run special-stage
  captured with load-queue state enabled. Require exactly two aux records per
  stored row, contiguous frame indices, `s3k_kos_direct` then
  `s3k_kos_module`, coverage of a lag row, and exactly one pair for the final
  stored row. Prove that a results boundary without a stored row adds no pair.
- [ ] Add metadata tests proving the special-stage metadata advertises
  `aux_schema_extras: ["load_queue_state_per_frame"]` when enabled and omits it
  when disabled.
- [ ] Exercise both canonical named-run identities through the real CLI with
  `--load-queue-state`; validate their special-stage aux pairs and enabled
  metadata. Exercise a disabled real-CLI invocation and prove it emits no
  physical queue pairs and omits the metadata capability.
- [ ] Add an output/projector-failure test that crosses the staged publisher
  boundary. Prove the failure propagates and that no segment or manifest final
  path survives; a runner exception without atomic publication assertions is
  insufficient.
- [ ] In `S3KCompleteRunCaptureRunner.WriteSpecialStageRow`, after
  `ObserveFrameEnd`, conditionally project the ROM-backed queue snapshot with
  `LoadQueueStateProjector.CaptureS3k` and write both records in projector
  order. Do not synthesize, defer, or infer a queue state.
- [ ] Thread the capability into
  `S3KCompleteRunMetadataWriter.FormatSpecialStage` and update exact
  publication/differential literals.
- [ ] Advance the complete-run writer and manifest version to
  `6.39-s3k-completerun`. Add failing exact-shape compatibility tests before
  updating the normalizers. Accept only whole-fixture
  6.38/schema-7/hardware-schema-2 complete-run → 6.39/schema-7/schema-2 and
  the exact 6.37/schema-7/hardware-schema-1 set → 6.39/schema-7/schema-2:
  `ddz`, `dez`, `ending`, `fbz`, `hpz`, `lbz`, `lrz`, `mhz`, `soz`, `ssz`
  complete-run segments plus both named-run identities. Reject any
  cross-segment or segment/manifest version mismatch and every partial/mixed
  version, capability, timing-schema, or trace-schema migration.
- [ ] Correct every non-publication failure found by the all-ROM gate before
  rebuilding: update the 6.39 hardware-version assertion; include the
  actually published S1 complete-run manifest line in stdout expectations;
  make the 15-segment S3K compatibility gate select only the enumerated exact
  6.37/hardware-1 or 6.38/hardware-2 predecessor for each fixture; and
  diagnose the S1 terminal-boundary lifecycle test against the reviewed
  observer contract. Add or adjust tests before production behavior if an
  actual implementation change is required.
- [ ] Run the focused tests, then the all-ROM native gate. Require zero
  unclassified failures and skips.
- [ ] Re-run the independent semantic review, rebuild, and freeze the new
  dirty diff and artifact hash. Because the artifact hash changes, reject the
  entire pre-fix 32-invocation batch as publication input and preserve it only
  as diagnostic evidence.
- [ ] Return to Task 5 for the single post-fix 32-invocation recapture,
  including both S3K multi-bonus identities, under all of Task 5's immutable
  ledger/hash/inventory rules. Do not combine pre-fix S1/S2/S3K bytes with
  post-fix bytes even if their payload hashes happen to match.

### Task 5: Capture every native-reproducible recording

**Files:**
- Create: `.scratch/trace-fleet-regeneration-*/captures/<invocation>/`
- Create: `.scratch/trace-fleet-regeneration-*/capture-commands.tsv`

- [ ] Record filesystem type/free bytes and require estimated peak plus 25%;
  run captures serially.
- [ ] Run each standalone profile through `run.sh --mode trace`.
- [ ] Run each later S2 act with its canonical `--gameplay-segment`.
- [ ] Run each complete-run profile with `--trace-profile complete_run`.
- [ ] Run each canonical run identity with `--run-id`.
- [ ] Require mandatory audit flags/capabilities on every invocation. For S3K,
  enable physical load-queue state and require hardware-timing schema 2
  (module plus direct) for standard, complete-run, bonus, special-stage, and
  named-run outputs.
- [ ] Before each row, re-hash the harness artifact and reject any difference
  from the latest independently reviewed build (Task 4, or the superseding
  post-Task-4A build when Task 4A was required).
- [ ] Record command, ROM SHA-1, movie SHA-256, source commit,
  dirty-state/diff hash, built-artifact SHA-256, exit status, elapsed time, and
  output inventory as one immutable `capture-commands.tsv` row immediately
  after each invocation.
- [ ] Reject a row on pre-existing output, nonzero exit, missing/unexpected
  segment, manifest mismatch, or corrupt payload. Identify the cause before
  any rerun; never overwrite or reuse the rejected output.
- [ ] Treat a structural failure found after the final invocation as rejection
  of the whole same-artifact batch. After a recorder fix or rebuild, allocate a
  new batch root and repeat all 32 invocations so the proposed fleet has one
  artifact SHA-256.

### Task 6: Build the publication evidence

**Files:**
- Modify: `src/main/java/com/openggf/trace/TraceData.java`
- Modify:
  `src/test/java/com/openggf/trace/TestLoadQueueTraceComparison.java`
- Create: `.scratch/trace-fleet-regeneration-*/proposed-files.sha256`
- Create: `.scratch/trace-fleet-regeneration-*/publication-report.tsv`
- Create: `docs/architecture/audits/trace/2026-07-29-native-trace-fleet-publication.md`
- Modify: `tools/bizhawk-headless/tests/S1CompleteRunDifferentialTests.cs`
- Modify: `tools/bizhawk-headless/tests/S1RunModeDifferentialTests.cs`
- Modify: `tools/bizhawk-headless/tests/S2TraceDifferentialTests.cs`
- Modify: `tools/bizhawk-headless/tests/S3KTraceDifferentialTests.cs`
- Modify: `tools/bizhawk-headless/tests/S3KCompleteRunSegmentsDifferentialTests.cs`
- Modify: `tools/bizhawk-headless/tests/S3KRunModeDifferentialTests.cs`

- [ ] Verify gzip payloads and parse all JSON/JSONL.
- [ ] Record every file's path, byte length, and literal SHA-256.
- [ ] Record metadata versions, CSV rows, aux events, hardware timing events,
  and manifest order.
- [ ] For every S3K fixture, assert hardware schema 2, both timing kinds,
  exactly two physical queue-state events per stored row, valid
  membership/prepared/remaining fields, and ordered stable fingerprints.
  Cross-check queue transitions against timing boundaries without inferring a
  sub-frame service event from end-frame state alone.
- [ ] Validate busy S3K direct destinations as sign-extended 68K work-RAM
  addresses `$FFFF0000..$FFFFFFFE` / `-65536..-2`; treat `-1` as the absent
  sentinel and reject every other value. Compare accepted values by exact
  32-bit pattern. Add focused lower/upper/out-of-domain regressions and a case that
  parses literal JSON/JSONL containing `active_destination: -12288` through
  `TraceEvent.parseJsonLine` or `TraceData.load`, then exercises validation and
  zero-tolerance comparison; direct record construction alone is insufficient.
- [ ] On JDK 21 run
  `mvn -Dmse=off -Dtest=TestLoadQueueTraceComparison test`, require zero
  failures, errors, and skips, and record the exact result in the publication
  audit before candidate approval.
- [ ] Load representative candidates through Java `TraceData` and prove strict
  S3K direct-queue validation preserves signed 68K RAM destinations such as
  `$FFFFD000`; only `-1` is the unknown/idle sentinel. Exercise the same values
  through the zero-tolerance queue comparator.
- [ ] For every S1/S2 level fixture, assert exactly one advertised game-kind
  PLC state per stored row. For every supported S1/S2 level and special-stage
  fixture, assert exactly one complete DPLC envelope per stored row, strict
  edge order/pairing, terminal/outstanding ledger validity, standalone/S1 arm
  rules, and exact S2 named-run final-gap → initial-ledger continuity.
- [ ] Compare proposed output structurally and bytewise with canonical
  fixtures.
- [ ] Name the cause of every delta and reject unexplained deltas.
- [ ] Add independent tests freezing literal hashes, lengths, versions,
  inventories, counts, ordering, and ranges; do not derive expectations from a
  candidate invocation.
- [ ] Name methods `ProposedFleet_<family>_MatchesReviewedManifest`; transcribe
  literals only after the audit manifest is frozen. Reviewer compares every
  literal with the manifest and rejects capture-code-derived expectations.
- [ ] Add the explicit `OGGF_PROPOSED_TRACE_ROOT` test-only contract. Each
  proposed-fleet test reads the already captured candidate beneath that root,
  rejects missing/extra files, and neither launches capture nor reads canonical
  fixture bytes as the proposal.
- [ ] Give proposed-fleet publication tests two explicit literal modes. With
  `OGGF_PROPOSED_TRACE_ROOT`, validate only captured candidates against the
  independently frozen candidate literal set and never read canonical bytes.
  Without it, validate only the installed canonical tree against a separately
  frozen canonical literal set. Candidate and canonical expectations may not
  be derived from one another at runtime; missing roots/files or mixed mode
  fail rather than skip.
- [ ] Run
  `OGGF_PROPOSED_TRACE_ROOT=<SCRATCH>/captures tools/bizhawk-headless/test.sh --filter ProposedFleet_`
  with all ROM variables and require all selected tests pass with zero skips.
- [ ] Review the evidence independently until no blocking issue remains.

### Task 7: Exact-byte publication approval

- [ ] Present the complete proposed inventory, digests, and categorized deltas
  to the user.
- [ ] Wait for explicit approval of those exact bytes.

### Task 8: Install and validate approved fixtures

**Files:**
- Modify: `src/test/resources/traces/{s1,s2,s3k}/**`

- [x] Install only the second-approved deterministic-gzip inventory into the
  mapped canonical directories without hand edits; the original plain-output
  inventory is evidence, not an installation source.
- [x] Confirm all 447 installed files match the second-approved portable
  path/length/SHA-256 manifest exactly.
- [x] When the first approved inventory exposed 70 plain payload paths, stop
  publication, build a deterministic `gzip -9 -n` storage inventory, compare
  all decompressed bytes with the first-approved sources, obtain explicit
  approval of the revised exact bytes, and install only that revised inventory.
- [x] Reject plain/compressed sibling overlap after installation. Remove the
  three superseded tracked plain targets
  `s1/ghz1_fullrun/{aux_state.jsonl,physics.csv}` and
  `s2/ehz1_fullrun/physics.csv`, then prove no approved compressed target has a
  plain sibling.
- [x] Add a failing validator regression for the four retail S1 dynamic-art
  submission callback PCs, replace `$1436A` only in the submitted-edge
  whitelist with `$0D20/$0E34/$0F24/$1030`, preserve `$1436A` as the valid
  decision/arming return callback, and require the focused lifecycle test to
  pass.
- [x] Rerun the exact full native command from Task 4; require zero failures
  and zero applicable skips and preserve its log.
- [x] Discover Java guards with
  `rg -l 'Trace|trace' src/test/java | rg 'Guard|Schema|Compression|ReferenceClosure|HardwareTiming'`,
  freeze the concrete class allowlist in the audit, run it with the three ROM
  properties, and require zero failures/errors/skips.

### Task 9: Measure the complete replay fleet

**Files:**
- Create: `docs/architecture/validation/trace/2026-07-29-native-trace-frontiers.md`
- Modify: `docs/status/trace-frontier-log.md`

- [ ] Enumerate executable concrete `*TraceReplay` test classes.
- [ ] Run the full Maven `-Dtest=*TraceReplay` selection with all three ROM
  properties.
- [ ] Report non-gameplay guards/policies separately from gameplay frontiers.
- [ ] Clear `target/trace-reports` and run every concrete gameplay class alone,
  preserving unique reports exactly as in baseline Task 3.
- [ ] Assign every gameplay class exactly one of `green`, `red`, `error`, or
  `not executed`, with fixture recorder version and required detail.
- [ ] Identify the eight `credits-retro-1.4` S1 fixtures as retained,
  non-regenerable legacy inputs; never label them regenerated.
- [ ] Extract every green result and every red first frame/field, error count,
  warning count, and report path.
- [ ] Record unsupported retained legacy fixtures distinctly.
- [ ] Compare post-install results with baseline and update
  `docs/status/trace-frontier-log.md` for every moved frontier, new regression,
  greened trace, or selected next target, including exact command,
  commit/worktree, error count, and first frame/field.

### Task 10: Verify, commit, integrate, and deliver

- Modify: `.githooks/validate-policy.sh`
- Modify: `.githooks/validate-policy.ps1`
- Create: `.githooks/machine-local-path-grandfather.sha256`
- Modify: `src/test/java/com/openggf/tests/TestBuildToolingGuard.java`

- [ ] Make machine-local textual-path validation additions-aware for existing
  files: inspect only lines introduced relative to the commit parent, while
  inspecting the full content of new files. Preserve historic audit evidence
  byte-for-byte, reject every newly added machine-local path, and keep shell/
  PowerShell behavior identical. Add a tooling regression for grandfathered
  old lines plus rejected new lines; never bypass hooks.
- [ ] For the already-neutralized frontier history only, generate a tracked
  path-scoped SHA-256 manifest from the independently verified
  `53de63da2:docs/status/trace-frontier-log.md` offending lines. Store hashes,
  never raw machine paths, plus the verified occurrence count for each
  path+hash. Permit an offending line only when its file path and exact line
  hash match and the final-file occurrence count does not exceed the baseline
  allowance; consume each occurrence once. Reject altered, novel, replayed
  duplicate, wrong-path, or new-file uses. Pin missing/extra entries and exact
  multiplicity against the restored baseline in `TestBuildToolingGuard`, with
  same-path duplicate/replay cases for both shell and PowerShell.
- [ ] Store the verified baseline prefix byte length and SHA-256 in the
  grandfather manifest and require the final frontier file to begin with that
  exact byte sequence before any line exemption applies. Add shell/PowerShell
  tests for deletion, substitution, reorder, single replay after deletion, and
  every multiplicity-greater-than-one entry; all must fail. Only appended
  portable lines may follow the immutable prefix.
- [ ] Exercise both policy implementations against the same cases:
  an unchanged grandfathered path in an existing file passes, a newly added
  path line fails, and a newly added file containing the path fails. Before
  committing run `mvn -Dmse=off -Dtest=TestBuildToolingGuard test` on JDK 21
  and require zero failures, errors, and skips.
- [ ] Stage only the audit, validation report, supporting evidence intended for
  the repo, frozen publication tests, approved fixtures, README/change-log
  entry, frontier log, and every Task 2, Task 4, and Task 6
  source/test/project/spec deliverable listed above, including the signed S3K
  address validator and parsed-JSON regression. Also stage both Task 10 policy
  scripts, the hashed grandfather manifest, and `TestBuildToolingGuard`. Leave
  no relevant artifact untracked.
- [ ] Create policy-compliant commits with every required trailer; because this
  feature branch will merge into `develop`, stage the mandatory `README.md`
  release/change-log update. Never use `--no-verify`.
- [ ] Review all documentation and fixture changes independently.
- [ ] Fetch and fast-forward the branch currently checked out in the main
  workspace without
  overwriting user changes.
- [ ] Run and record the full suite on that updated main-workspace integration
  baseline.
- [ ] Run the same full suite plus focused native/trace tests in the
  development worktree and compare with the baseline.
- [ ] Merge the worktree branch into the branch checked out in the main
  workspace.
- [ ] Rerun the full suite and compare against baseline.
- [ ] Push the branch checked out in the main workspace, then remove the clean
  merged worktree and its fully merged local branch.
- [ ] Report capture challenges, categorized deltas, exact tests, frontiers,
  conflicts, commits, and pushed branch.
