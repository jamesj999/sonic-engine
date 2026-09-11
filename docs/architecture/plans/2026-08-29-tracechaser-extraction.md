# TraceChaser Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use
> `superpowers:subagent-driven-development` to execute this plan task by task.
> Do not begin history filtering until Task 1 proves the extraction-base gate.

**Goal:** Create `OpenGGF/TraceChaser` with the history and behaviour of
OpenGGF's external trace utilities, publish `v0.1.0`, and switch OpenGGF 0.6 to
an optional pinned `tools/tracechaser` submodule without retaining a second
implementation.

**Architecture:** A history-filtered OpenGGF repository becomes TraceChaser.
TraceChaser owns trace producers, validators, comparison/compression tools,
BizHawk integration, and source-side tests. OpenGGF owns engine-side Java
consumers, canonical traces, immutable consumer copies of small contracts, and
explicit opt-in cross-repository gates. The OpenGGF authority switch is one
atomic commit after the standalone repository is verified and published.

**Tech stack:** Git and `git filter-repo`, GitHub CLI, Git submodules, Bash,
PowerShell, Python 3 `unittest`, Lua, C#/Mono, BizHawk 2.11, Java 21/JUnit 5,
Maven, and GitHub Actions.

**Spec:** `docs/architecture/designs/2026-08-29-tracechaser-extraction.md`

## Fixed workspace variables

Derive and validate these once per shell. Never infer one repository from the
directory shape of another.

```bash
export OPENGGF_FEATURE_ROOT="$(git rev-parse --show-toplevel)"
export OPENGGF_MAIN_ROOT="$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")"
: "${TRACECHASER_WORK_ROOT:?set TRACECHASER_WORK_ROOT to a new absolute directory outside OpenGGF}"
export TRACECHASER_ROOT="$TRACECHASER_WORK_ROOT/TraceChaser"
export TRACECHASER_EVIDENCE_ROOT="$TRACECHASER_WORK_ROOT/evidence"
test "$(git -C "$OPENGGF_MAIN_ROOT" branch --show-current)" = develop
test "$(git -C "$OPENGGF_FEATURE_ROOT" branch --show-current)" = feature/ai-tracechaser-extraction-design
```

Discover ROMs only from `OPENGGF_MAIN_ROOT`, validate SHA-1 values, and store
absolute paths in `OPENGGF_S1_ROM`, `OPENGGF_S2_ROM`, and `OPENGGF_S3K_ROM`.
All Maven invocations must report JDK 21 through `mvn -v`.

## Global constraints

- The extraction base is the `develop` merge containing the in-flight S2
  native-recorder workflow and its documentation. Record the exact merge
  commit/PR; a branch-name match is insufficient.
- Preserve `trace_schema: 5`, current recorder output, compatibility behaviour,
  and committed traces. Do not rerecord merely because paths move.
- TraceChaser is the sole implementation owner after cutover. OpenGGF retains
  only named forwarders, consumer resources, and engine-side code.
- BizHawk support is exactly 2.11. Download official release assets only and
  verify archive identity and required Lua capabilities before use.
- Never commit ROMs, BK2s, BizHawk distributions, upstream build trees, build
  output, caches, scratch captures, or uncurated logs.
- Normal OpenGGF clones, Maven tests, CI, packaging, and runtime work without
  initializing `tools/tracechaser`.
- Opt-in integration tests skip when the checkout is absent, fail when present
  at the wrong commit, and run only when present at the pinned commit.
- OpenGGF canonical fixtures remain under `src/test/resources/traces/` and are
  never overwritten directly by TraceChaser.
- Agent docs and skill mirrors remain byte-identical. Never bypass hooks.

---

### Task 1: Prove the extraction base and freeze reproducible evidence

**Create in OpenGGF:**

- `docs/architecture/validation/trace/2026-08-29-tracechaser-extraction-inventory.tsv`
- `docs/architecture/validation/trace/2026-08-29-tracechaser-extraction-baseline.md`

**Produces:** exact extraction commit; complete versioned path disposition;
clean-main and feature test baselines; immutable external pre-extraction
capture corpus with hashes, counts, and ordering.

- [ ] Fetch `origin` and inspect every worktree from `git worktree list
  --porcelain`. For each worktree record branch, HEAD, `git status --short`,
  and `git diff --name-status develop...HEAD --` for extraction roots, candidate
  Java tests, Lua resources, audio callers, and active docs/skills. Clean
  committed work is still work: stop if any relevant commit is not merged.
- [ ] Identify the exact S2 recorder workflow commit and prove it is an ancestor
  of `develop` with `git merge-base --is-ancestor`. Record commit and PR. Stop
  before filtering if that cannot be proven.
- [ ] Inspect modified disassembly gitlinks in the main workspace. Do not pull
  until `git fetch origin` plus the incoming diff proves no gitlink conflict.
  Preserve all user changes.
- [ ] Fast-forward main `develop`. From clean updated main, run the ordinary full
  suite with all three verified absolute ROM paths and
  `mvn -Dmse=off -Pguards test -B`. Record exact outcomes.
- [ ] Merge updated local `develop` into the feature worktree. Run the same two
  suites there and compare them before extraction changes.
- [ ] Inventory every candidate tracked path and every old-root reference. TSV
  columns are `old_path`, `kind`, `history_disposition`, `history_new_path`,
  `cutover_disposition`, `cutover_new_path`, `owner`, `consumer`, and `notes`.
  History dispositions are only `move` and `exclude`; cutover dispositions are
  `retain`, `delete`, `forwarder`, `historical-reference`,
  `delete-after-task-5`, and `split-after-task-5`. A history move is an exact
  byte-preserving filter/rename, never a conceptual language replacement. Zero
  paths/references remain unclassified. Name the four
  `tools/testing/test_*trace_v5*.py` files and explicitly retain hook installers
  and Surefire tools.
- [ ] Create `TRACECHASER_EVIDENCE_ROOT/pre-extraction` outside both repositories.
  Require `TRACECHASER_WORK_ROOT` not to exist before creating it; never reuse or
  merge an earlier filtered clone or evidence directory.
  Using the extraction-base harness, capture six fixed runs: S1 standard GHZ1,
  S1 complete, S2 standard EHZ1, S2 complete emerald, S3K standard AIZ, and S3K
  complete. Record exact ROM SHA-1, BK2 path/hash, command, recorder commit,
  stored/logical hashes, row count, sidecar count, and sorted member ordering.
  Validate every output. Do not modify canonical fixtures.
- [ ] The six capture IDs and immutable external BK2 inputs are exactly:
  `s1-ghz1` → `src/test/resources/traces/s1/ghz1_fullrun/ghz1_fullrun.bk2`;
  `s1-emeralds-run` →
  `src/test/resources/traces/s1/runs/s1-sonic-complete-withemeralds/sonic1-complete-withemeralds.bk2`;
  `s2-ehz1` → `s2/ehz1_fullrun/s2-ehz1.bk2`;
  `s2-emeralds-run` →
  `src/test/resources/traces/s2/runs/s2-sonic-tails-complete-emeralds/sonic-2-sonic-tails-complete-emeralds.bk2`;
  `s3k-aiz` → `s3k/aiz1_to_hcz_fullrun/s3-aiz1-2-sonictails.bk2`; and
  `s3k-complete` → `s3k/_movies/s3k-complete-sonic-tails.bk2`. Copy no BK2.
  Hash them in place, require the hashes recorded by the reviewed capture matrix,
  run matrix `preflight`, generate the ledger with matrix `expand`, execute only
  these six IDs with their recorded selectors, validate each capture with
  `tools/traces/validate_trace_v5.py`, and compare Task 9 output with
  `tools/traces/compare_trace_v5_candidates.py`. Record the literal generated
  commands in the baseline so the release gate replays byte-for-byte.
- [ ] Run current native, trace-v5 Python, Java/Lua producer, audio, and PLC
  contract tests. Record exact commands and outcomes.
- [ ] Commit the inventory and baseline with required trailers. Export the
  resulting 40-character `TRACECHASER_EXTRACTION_BASE`.

`rg` convention: exit 1 with no output passes a zero-match gate; exit 0 requires
classification of every line; exit 2 or higher is a tool failure.

---

### Task 2: Filter history and audit every reachable object

**TraceChaser retained roots:** `LICENSE`, `bizhawk-headless/`, `bizhawk/`,
`retro/`, `traces/`, classified trace-v5 tests, three Lua harnesses, and
`contracts/audio/normalization-contract-v1.json`.

**Create:** `docs/history-import.md`, `history-filter-paths.tsv`.

- [ ] Record `git filter-repo --version`. Create a fresh `--no-local` clone at
  `TRACECHASER_ROOT`, remove OpenGGF remotes, and create `main` at the exact base.
- [ ] Generate `history-filter-paths.tsv` only from rows whose
  `history_disposition` is `move`, using `old_path` and `history_new_path`
  exactly; never read `cutover_disposition` or `cutover_new_path` while building
  the filter. Review it, and use it to construct an explicit
  `git filter-repo --force` invocation with one `--path` and `--path-rename`
  argument per reviewed entry. Record the exact clone and filter commands
  verbatim in `docs/history-import.md`; execute that command, not an
  independently reconstructed list. Java assertion sources remain excluded
  OpenGGF inputs until Task 5 independently implements their replacements.
  Rename old tool roots to root boundaries, selected Python tests to
  `testing/`, Lua resources to `testing/lua/`, and normalization JSON to
  `contracts/audio/`.
- [ ] If the reachable-object audit finds developer-local absolute paths in
  otherwise required history, preserve the files and lineage but redact only
  the exact reviewed literals with `git filter-repo --replace-text`. Record
  each old literal's SHA-256, neutral replacement, affected paths/commits, and
  the literal replacement file in `history-redactions.tsv` and
  `docs/history-import.md`. This exception never applies to ROM/BK2 data,
  binaries, build output, credentials, or other unreviewed content; those remain
  hard failures requiring reclassification or re-filtering.
- [ ] Exclude exactly `bizhawk/diag_aiz2_djf_probe_output.txt` and
  `bizhawk/diag_aiz2_monitor_solid_output.txt`; the evidence allowlist is empty.
- [ ] Record all old/new paths. Prove history with the resulting root commit,
  first native-recorder commit, representative logs, and moved-doc blame. Run
  `git fsck --full`.
- [ ] Before any public remote exists, audit all commits reachable from `main`:
  enumerate paths/blobs with `git rev-list --objects --all`, inspect every blob
  for forbidden data/build/log/machine-path content, and verify retained notice
  licenses/provenance. Implement this as a checked-in all-history scanner with
  tests. It uses the same forbidden suffix/component/content predicates and
  exact notice exceptions as repository policy, streams binary blobs without
  decoding them as text, rejects known ROM/BK2/archive magic and oversize blobs,
  and reports commit/object/path for each violation. Re-filter on any violation.
- [ ] Commit only provenance additions.

---

### Task 3: Establish TraceChaser policy and identity

**Create:** `README.md`, `AGENTS.md`, `CLAUDE.md`, `.gitignore`,
`testing/repository_policy.py`, `testing/test_repository_policy.py`.

- [ ] First write tests rejecting tracked ROMs, BK2s, BizHawk binaries, build
  roots, caches, raw traces, generic logs, and uncurated output; accept source,
  locks, small contracts, root license, and exact curated notices. Observe fail.
- [ ] Implement `find_violations(root: Path) -> list[str]` over
  `git ls-files -z` and a sorted nonzero CLI.
- [ ] Add project policy and ignore rules. README begins “Trace recording and
  analysis tools used by OpenGGF.” and links all workflow docs. Consolidate
  nested agent guidance into byte-identical root agent files.
- [ ] Run tests/scanner/mirror check/`git diff --check`; commit.

---

### Task 4: Make filtered utilities standalone

**Create:** `testing/test_checkout_portability.py`,
`docs/migration-from-openggf.md`. **Modify:** all imported entry points/tests.

- [ ] Write failing tests from a path containing spaces. Reject machine paths,
  implicit OpenGGF discovery, source-tree writes, and unresolved `tools.*`
  imports.
- [ ] Rewrite eight retained imports from `tools.traces.*` to `traces.*`. Remove
  the self-import in `bizhawk-headless/trace_v5_capture_matrix.py` through one
  canonical module plus tested thin entry point.
- [ ] Port `test_compare_trace_v5_candidates.py` and `test_validate_trace_v5.py`:
  move OpenGGF fixture/skill/Java assertions to OpenGGF consumer tests; source
  tests must not assume `.agents`, `.claude`, `src/`, or OpenGGF validation docs.
- [ ] Keep migration-only scope. Where an API must change to carry explicit
  roots, use its existing structured representation or the smallest typed argv
  boundary and stringify only at ledger output. Do not perform unrelated typing
  or API redesign. Consolidate duplicate modules only after a test proves them
  byte-identical and singly referenced; otherwise preserve both thin entry points.
- [ ] Resolve project root from script paths. Require OpenGGF inputs explicitly,
  default BizHawk to `.dependencies/BizHawk-2.11-linux-x64`, and require scratch
  outside both repositories.
- [ ] Rewrite live citations to root-relative TraceChaser paths, run tests and
  zero-reference audit, classify all matches, and commit.

---

### Task 5: Replace every moved producer contract

**Create:** `testing/test_probe_contract.py`, `testing/test_lua_recorders.py`,
`testing/test_audio_lua_contracts.py`, `testing/test_plc_probe_contract.py`.

- [ ] Port recursive `TestBizhawkProbeContractGuard` policy over every
  `bizhawk/probes/**/*.lua`; add a nested synthetic violation test so enumeration
  cannot narrow silently.
- [ ] Split `TestTraceAnimationRecorderContract`: producer structure moves;
  OpenGGF canonical-fixture assertions stay in a consumer test.
- [ ] Port recorder-counter, S2 special-stage, S1 complete-run, audio parity,
  and gameplay-audio producer assertions. Explicitly replace both methods of
  `TestS1AudioParityProbeContract`, including BK2 SHA-256 launcher behaviour,
  and all of `TestS1CompleteRunLuaContract`.
- [ ] Move PLC harness to `testing/lua/` and preserve exact execution semantics.
  Resolve overridable `LUA_BIN` through `PATH`, report version, and skip only
  executable contracts when unavailable; structural enumeration never skips.
- [ ] Run the TraceChaser replacement suite before any Java deletion; commit.

---

### Task 6: Separate and enforce the two BizHawk 2.11 locks

**Create:** archive lock, fetch/preflight scripts and tests, and
`docs/install-bizhawk-2.11.md`.

- [ ] Preserve official Linux archive URL/hash as the Lua-runtime lock. Keep the
  GPGX source lock distinct with its own consumer/docs.
- [ ] Write failing tests accepting exactly 2.11 and rejecting 2.11.1, older,
  newer, missing-capability, unparseable, and wrong-hash inputs. Diagnostics
  report raw detected and expected versions.
- [ ] Lift existing `client.invisibleemulation` and other required capability
  checks. Acquire only into `.dependencies`, verify before extraction, and
  support explicit user installations.
- [ ] Run tests and real 2.11 preflight; commit.

---

### Task 7: Publish a semantic trace-v5 conformance contract

**Create:** `contracts/v5/` fixtures, manifest, schema, README, and tests.

- [ ] Build a small synthetic pack covering metadata, ordinary 42-column rows,
  S1/S2/S3K special widths 14/48/20, auxiliary JSONL, the bounded
  `hardware_work_completed` timing shape, removed-v5-field rejection,
  deterministic gzip, run-manifest collection shapes, member ordering, and
  positive/negative cases for every rule.
- [ ] Manifest path, SHA-256, expected outcome, parser entry, and normalized
  semantic result/diagnostic. Test stored and logical hashes.
- [ ] Producer tests run every case. Specify an OpenGGF consumer test that runs
  the same accept/reject cases through actual Java parsers and compares
  normalized semantics—not only hashes.
- [ ] Run conformance suite/scanner; commit.

---

### Task 8: Complete standalone docs and source-only CI

**Create:** game capture guides for S1/S2/S3K, native-headless, Lua probes,
validate/compare/publish, scratch/security, contributing, releasing, trace-v5,
and `.github/workflows/source-only.yml`.

- [ ] CI uses a pinned runner/toolchain and runs exact Python/scanner/shell/Lua/
  conformance/docs commands without BizHawk, ROMs, BK2s, OpenGGF, or
  post-checkout network. Native tests that require BizHawk are a separate
  integration job fed only by a pre-provisioned, hash-verified 2.11 cache; the
  source-only job must not pretend to execute them. Record which tests may skip
  and fail CI if any other selected test skips.
- [ ] The source-only workflow pins `ubuntu-24.04`, provisions Python 3 and
  Lua 5.4 before checkout, then disables network-dependent steps and runs:
  `python3 -m unittest discover -s testing -p 'test_*.py' -v`,
  `python3 testing/repository_policy.py --root .`, every tracked shell script
  through `bash -n`, executable Lua contracts with `LUA_BIN=lua5.4`, the v5
  manifest validator, docs/link audit, and `git diff --check`. The separate
  native integration job runs `BIZHAWK_HOME=<verified-cache>`
  `bizhawk-headless/test.sh` after the archive-lock preflight; no source-only
  success depends on that optional job.
- [ ] Document standard/complete workflows for all games plus validation,
  comparison, compression, inventory, publication, external scratch/security,
  contribution, and release.
- [ ] Run the workflow in a fresh clone outside OpenGGF and handle `rg` exit
  semantics explicitly; commit.

---

### Task 9: Reproduce the corpus, audit, and publish `v0.1.0`

**Create:** `docs/validation/v0.1.0-capture.md`, `CHANGELOG.md`.

- [ ] In a fresh clone, acquire/preflight official BizHawk 2.11 and reproduce
  Task 1's six captures. Compare all hashes, counts, ordering, and normalized
  semantics with immutable pre-extraction evidence. Pass the absolute external
  OpenGGF fixture/movie root recorded in Task 1; require it to be outside
  TraceChaser and verify all six BK2 hashes before capture. Differences block
  release.
- [ ] `CHANGELOG.md` states the compatible OpenGGF range, schema-v5 compatibility,
  exact BizHawk 2.11 support, tested host/toolchain matrix, six-capture validation
  evidence, and retained limitations.
- [ ] Repeat the all-reachable-object and license/provenance audit. Obtain
  independent review of history, policy, CI, BizHawk, captures, and release tree.
- [ ] Use `gh repo view OpenGGF/TraceChaser` to establish nonexistence and verify
  org authorization before mutation. Stop on unexpected existence/access.
- [ ] Create the public repo only after gates pass. Commit evidence, create
  annotated `v0.1.0`, push main/tag, and create a source-only release.
- [ ] Fresh-fetch the remote and prove `refs/tags/v0.1.0^{commit}` equals the
  intended object. Export exact `TRACECHASER_V0_1_0_COMMIT`; never pin a branch.

---

### Task 10: Atomically switch OpenGGF authority to TraceChaser

This entire task is one OpenGGF commit. Do not commit the gitlink, wrappers,
deletions, test dispositions, or docs separately. The boundary guard passes only
when every step is staged together.

- [ ] First add failing tests for exact gitlink, wrapper states, boundary
  allowlist, consumer semantics, retained callers, generated commands, and Maven
  optionality.
- [ ] Add the submodule at the exact release object. Wrappers read expected ID
  from the index, reject unsafe paths, and return 2 absent, 3 wrong commit, 4
  unsafe/missing. Print the exact explicit init command on absence.
- [ ] Add `tracechaser-integration` JUnit tag/profile. Ordinary Maven excludes it.
  S2/S3K real-row gates retain env gating: absent skips, wrong commit fails,
  correct commit runs.
- [ ] Copy and manifest the complete v5 pack and GPGX capability JSON; preserve
  normalization JSON in its consumer path. Ordinary tests parse all accept/reject
  cases with real Java parsers and compare semantics. Present mode also compares
  source tree/hashes.
- [ ] Preserve retained responsibilities. Split fixture-owned animation checks;
  migrate all PLC support reads; no retained method points at deleted paths.
- [ ] Update retained audio callers `run_s1_audio_parity.sh`,
  `run_s1_ghz1_gameplay_audio_timeline.sh`, and
  `build-s1-ym-busy-program.py` to use verified pinned paths. Test before delete.
- [ ] Consume only the inventory's `cutover_disposition` for the authority
  switch. Keep every individually named `forwarder` row for
  run/test/Lua/validation/comparison/compression and the public capture-matrix
  entry point unless every supported caller is migrated and the old command is
  explicitly historical. Apply `delete-after-task-5` and `split-after-task-5`
  only after the corresponding Task 5 replacement proof passes.
- [ ] Delete all other old-root implementation files and moved producer tests/
  resources only after replacements pass. Explicitly disposition both S1 tests,
  both decode gates, PLC evidence, animation recorder, and GPGX consumer.
- [ ] Rewrite all live source comments/strings and generated load-time command to
  pinned paths; verify cited line anchors at the pinned commit.
- [ ] Update all 13 active docs/skills, mirrored agent docs, README,
  `CHANGELOG.0.6.md`, public/detailed release docs, `docs/README.md`, extraction
  audit, and both discrepancy docs. Record explicit no-change rationale if a
  discrepancy file needs no semantic edit.
- [ ] Run zero-reference audit, TraceChaser replacements, focused OpenGGF tests,
  full correct-checkout integration profile, guards, and packaging. Confirm JAR
  contains no TraceChaser/BizHawk artifacts.
- [ ] Stage everything together, inspect for no dual implementation/dangling
  caller, and create one policy-compliant authority-switch commit.

---

### Task 11: Verify fresh clones, integrate, push, and clean up

Use these exact JDK-21 OpenGGF gates in Task 1 and again here, with all three
verified absolute ROM properties on the ordinary suite:

```bash
mvn -v
mvn -Dmse=off -B -Dsonic1.rom.path="$OPENGGF_S1_ROM" -Dsonic2.rom.path="$OPENGGF_S2_ROM" -Ds3k.rom.path="$OPENGGF_S3K_ROM" test
mvn -Dmse=off -Pguards test -B
mvn -Dmse=off -DskipTests package -B
mvn -Dmse=off -Ptracechaser-integration test -B
```

The baseline record must supply the expected ROM skip count and literal focused
selectors for producer/audio/PLC and the two real-row classes; a changed skip
count is a failed comparison even when Maven is green.

- [ ] In a disposable ordinary clone without initialized submodules, run full
  ordinary suite with absolute ROMs, guards, packaging, and source scans. Do not
  use destructive submodule deinit in a working checkout.
- [ ] In a second disposable clone initialize only TraceChaser, verify object/tag,
  run present-mode/profile tests, and prove a temporary wrong checkout fails.
- [ ] Fetch/fast-forward main `develop`, preserving user changes. Run full
  ordinary/guard suites on this updated integration baseline.
- [ ] Merge updated local develop into feature, resolve conflicts, and rerun full
  plus focused suites. No new failure versus updated main is allowed.
- [ ] Merge feature into main workspace's checked-out develop without switching.
  Reconcile conflicts and satisfy staged README merge policy.
- [ ] Run full ordinary, guards, packaging, absent-source scans, and focused tests
  on merged main. Record exact comparisons and reconciled upstream changes.
- [ ] Push only OpenGGF develop. Reverify TraceChaser remote/tag. Inspect and
  remove completed worktree only after merge/verification/push; delete its fully
  merged local branch and prune. Report exact commits and unresolved state.
