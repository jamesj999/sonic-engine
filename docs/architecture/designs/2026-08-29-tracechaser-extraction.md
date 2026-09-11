# TraceChaser Repository Extraction Design

Date: 2026-08-29
Status: Approved
Target: OpenGGF 0.6

## Summary

Extract OpenGGF's emulator-facing trace utilities into a new
`OpenGGF/TraceChaser` repository while preserving their Git history and current
trace-v5 behaviour. OpenGGF will consume TraceChaser through an optional Git
submodule pinned at `tools/tracechaser`. Trace recording and publication require
that submodule; normal engine builds, tests, releases, and runtime do not.

This is an ownership and packaging migration, not a trace-format redesign. The
cutover must not change recorder output, replay semantics, the committed trace
corpus, or the live trace schema. Legacy cleanup and any future schema reset are
explicitly deferred.

## Context

OpenGGF has accumulated a coherent external trace toolchain under four main
directories:

- `tools/bizhawk-headless/`: the supported native BizHawk/GPGX recorder,
  native audio observers, deterministic build support, and its test runner;
- `tools/bizhawk/`: Lua recorders, launchers, shared modules, differential
  references, and diagnostic probes;
- `tools/retro/`: RetroArch-oriented capture utilities;
- `tools/traces/`: trace-v5 validation, comparison, compression, inventory,
  evidence, and publication utilities.

These tools produce and inspect artifacts consumed by OpenGGF, but they do not
belong to the engine runtime. Keeping them inside the engine repository blurs
ownership, makes their independent lifecycle difficult, and encourages agents
to treat implementation paths as OpenGGF internals.

The extraction audit in
[`2026-08-29-org-project-extraction-candidates.md`](../audits/2026-08-29-org-project-extraction-candidates.md)
identified this toolchain as the strongest independent project boundary. This
design refines that recommendation with the approved project name, submodule
integration, history policy, BizHawk dependency boundary, and 0.6 cutover gates.

## Goals

1. Establish TraceChaser as the sole implementation owner for OpenGGF's
   emulator-facing trace recording and analysis utilities.
2. Preserve relevant commit history, authorship, dates, and blame information.
3. Keep the current trace-v5 contract and observable tool behaviour unchanged.
4. Pin one reviewed TraceChaser commit in OpenGGF through a Git submodule.
5. Keep normal OpenGGF builds and tests independent of the submodule.
6. Make trace workflows at least as discoverable and reliable for agents as
   they are before extraction.
7. Stop storing or assuming repository-local BizHawk distributions; obtain
   BizHawk 2.11 from its official release and verify it.
8. Update active OpenGGF documentation, skills, release material, and guards as
   part of the same coordinated cutover.

## Non-goals

- No trace schema v6 or reinterpretation of schema v5.
- No removal of legacy trace compatibility behaviour during this migration.
- No fixture re-recording merely to accommodate the repository move.
- No move of `src/test/resources/traces/` out of OpenGGF.
- No extraction of engine-side replay, comparison authority, hardware-timing
  admission, Java headless boot, or Java audio-parity tooling.
- No extraction of `RomOffsetFinder`, runtime decompression, rewind inventory,
  repository hooks, or general OpenGGF test infrastructure.
- No large internal reorganisation of TraceChaser before the 0.6 cutover.
- No support promise for BizHawk versions other than 2.11.

## Project identity

- GitHub repository: `https://github.com/OpenGGF/TraceChaser`
- Project name: **TraceChaser**
- Git description: `Trace recording and analysis tools used by OpenGGF.`
- Initial repository shape: history-preserving import with recognisable
  component directories, followed only by the path-portability work required
  for extraction.

TraceChaser does not need a marketing tagline. Its README should lead with the
supported workflows and dependency contract.

## Ownership boundary

### TraceChaser owns

- the native BizHawk 2.11 headless recorder and its C# tests;
- native GPGX observation patches, build recipes, source locks, and proof tools;
- Lua recorders, shared Lua modules, launchers, and diagnostic probes;
- RetroArch capture adapters;
- trace-v5 producer validation;
- candidate comparison, compression, inventory, evidence, and publication
  utilities;
- tests whose implementation subject moves to TraceChaser;
- small synthetic or golden inputs required to test those utilities;
- producer-facing trace-v5 documentation;
- BizHawk 2.11 installation, verification, and capability preflight.

### OpenGGF retains

- Java trace parsers, replay comparators, run-chain harnesses, and timing
  authority;
- Java tools that boot, capture, or inspect the OpenGGF engine itself;
- the canonical committed trace fixture corpus;
- consumer-side schema and replay tests;
- small consumer-conformance fixtures with provenance and hashes;
- thin bootstrap and compatibility wrappers for the 0.6 cycle;
- OpenGGF-specific agent skills, contributor guidance, and release evidence;
- test-report helpers, Git hooks, and repository policy.

### Cross-repository rule

TraceChaser may consume ROMs, BK2 movies, existing trace trees, and output roots
only through explicit arguments or documented environment variables. It must
not locate files by reaching into an OpenGGF checkout. OpenGGF may invoke the
pinned TraceChaser checkout, but OpenGGF production code and ordinary Maven
tests must not import, compile, or execute TraceChaser. A separately selected,
opt-in integration-test profile may execute the pinned checkout after assuming
that the submodule is initialised; those tests must skip, rather than fail, when
the gitlink worktree is absent.

## Initial TraceChaser layout

The first release preserves recognisable boundaries instead of combining the
repository move with a cosmetic redesign:

```text
TraceChaser/
├── bizhawk-headless/   # native recorder, native observers, tests, fixtures
├── bizhawk/            # Lua recorders, launchers, modules, probes
├── retro/              # alternative emulator capture adapters
├── traces/             # validation, comparison, inventory, publication
├── testing/            # tests for the Python/Lua utilities above
├── contracts/          # portable trace-v5 conformance pack
├── docs/               # installation, workflow, contract, migration guides
├── AGENTS.md
├── CLAUDE.md
├── LICENSE
└── README.md
```

The implementation may remove accidental duplicate entry points when two files
are proven byte-identical and only one is referenced, but broader renaming or
directory consolidation waits until after OpenGGF 0.6.

## Extraction inventory

The history filter includes the tracked implementation and intentional support
files under:

- `tools/bizhawk-headless/`;
- `tools/bizhawk/`;
- `tools/retro/`;
- `tools/traces/`;
- the tests under `tools/testing/` whose subject is one of the moved utilities;
- Lua contract fixtures currently stored elsewhere in OpenGGF when they test a
  moved TraceChaser component rather than OpenGGF production code.

The inventory step classifies every tracked candidate as one of:

1. implementation;
2. test or small golden input;
3. active documentation;
4. curated investigation evidence;
5. compatibility wrapper retained by OpenGGF;
6. generated, third-party, machine-local, or obsolete material to exclude.

### Existing OpenGGF test dependencies

The cutover inventory must account for every Java test that currently reads or
executes a moved path. The initial dispositions are:

| Current OpenGGF test | Cutover disposition |
|---|---|
| `TestS2CompleteRunRealRow769DecodeGate` | Retain as an env-gated OpenGGF integration test. Resolve `tools/tracechaser/bizhawk-headless/test.sh` only after a JUnit assumption confirms that the pinned submodule is present; skip when it is absent, but fail if it is present at a commit other than the recorded gitlink. |
| `TestS3kCompleteRunRealRow810DecodeGate` | Same as the S2 real-row gate: retained and opt-in; skip when the submodule is absent, but fail when it is present at the wrong commit. |
| `TestS1AudioParityProbeContract` | Move its probe and launcher assertions to TraceChaser's source-only contract suite; remove the Java test. |
| `TestPlcTimingEvidenceTool#bothProbeStateMachinesHandleEmptyPartialAndCompletingCalls` | Move this Lua-producer behavioural test to TraceChaser; retain the rest of the Java test class for the OpenGGF-owned evidence tool. |
| `TestTraceAnimationRecorderContract` | Move its recorder assertions to TraceChaser's source-only contract suite; remove the Java test. |
| `TestCompleteRunAudioCutoffFrontier` | Retain the Java test. Copy `gpgx-audio-capability-v1.json` to `src/test/resources/tracechaser/gpgx-audio-capability-v1.json` with origin commit and SHA-256 provenance. |

The path audit also identifies contract-only Java tests beyond those six. They
test moved Lua implementations rather than OpenGGF Java behaviour and therefore
move, with equivalent assertions, into TraceChaser's source-only suite:

- `TestBizhawkProbeContractGuard`, preserving its recursive enumeration of
  every `bizhawk/probes/*.lua` file and its requirement that every probe other
  than `probe_runtime.lua` delegates lifecycle and hook ownership to
  `ProbeRuntime`;
- `TestTraceRecorderCounterAddresses`;
- `S2SpecialStageRecorderContractTest`;
- `TestS1CompleteRunLuaContract` and `TestS1CompleteRunProbeContract`;
- `TestS1AudioParityLuaContract`;
- `TestS1GameplayAudioTimelineLuaContract` and
  `TestS1Ghz1GameplayAudioProbeContract`.

Java tests whose only reference is explanatory prose remain in OpenGGF and have
their citations rewritten during cutover. Before deletion, an automated path
audit over `src/main/` and `src/test/` must reach zero unclassified references
to the former implementation roots.

### Lua contract fixtures

The following fixtures move with the Lua components and the contract tests that
drive them:

- `src/test/resources/bizhawk/probe_runtime_contract_test.lua`;
- `src/test/resources/bizhawk/s1_audio_parity_contract_test.lua`;
- `src/test/resources/bizhawk/s1_gameplay_audio_timeline_contract_test.lua`.

The shared `normalization-contract-v1.json` vector remains in OpenGGF because
OpenGGF Java normalizer, comparator, and JSONL tests consume it. TraceChaser
receives a conformance-pack copy with the same origin-and-hash manifest used for
other cross-repository contract inputs.

### `tools/testing` classification

The four trace-v5 Python tests receive explicit dispositions:

| Current path | Cutover disposition |
|---|---|
| `tools/testing/test_compare_trace_v5_candidates.py` | Move with the candidate comparator. |
| `tools/testing/test_trace_v5_capture_matrix.py` | Move with the capture-matrix implementation; make OpenGGF fixture and movie roots explicit integration inputs. |
| `tools/testing/test_validate_trace_v5.py` | Move with the validator. |
| `tools/testing/test_trace_v5_publication_manifest.py` | Retain in OpenGGF because it guards OpenGGF's canonical publication transaction and predecessor archive. |

`install-hooks.sh`, `install-hooks.ps1`, `Compare-SurefireRedSet.ps1`, and
`Test-CompareSurefireRedSet.ps1` remain OpenGGF repository infrastructure and
must not enter the history filter.

The tracked files `tools/bizhawk/diag_aiz2_djf_probe_output.txt` and
`tools/bizhawk/diag_aiz2_monitor_solid_output.txt` have no committed reader or
documentation evidence consumer and are excluded. The initial
diagnostic-output evidence allowlist is therefore empty. Any later exception
must identify the exact file as immutable evidence and record its reason and
consumer; filename shape alone never makes a log durable.

The extraction excludes:

- ROM images and ROM-derived runtime assets;
- BK2 movies unless a future legal and repository-policy review explicitly
  approves a small redistributable fixture;
- canonical OpenGGF trace payloads;
- BizHawk archives, binaries, DLL trees, extracted distributions, and copied
  upstream source trees;
- `bin/`, `obj/`, `.scratch/`, `__pycache__/`, caches, temporary output, and
  generated reports;
- user configuration and absolute machine paths;
- uncurated console output and one-off capture logs.

## History preservation

TraceChaser is created from a fresh clone of OpenGGF with `git filter-repo`.
The filter retains only the approved paths and renames their roots into the
initial layout above. The process preserves commit authors, author dates,
commit dates, and messages for retained changes.

The import records:

- the exact OpenGGF extraction-base commit;
- the history-filter command and tool version;
- the old-to-new path map;
- the resulting TraceChaser root commit and first native commit;
- verification that representative pre-extraction commits remain reachable and
  that `git blame` on selected C#, Lua, Python, shell, and documentation files
  identifies their original authors.

This provenance lives in `docs/history-import.md` in TraceChaser and in the
OpenGGF cutover validation record. The filtered repository is never force-pushed
after its public baseline is accepted.

There is necessarily a short coordination window in which the candidate
TraceChaser repository exists while OpenGGF still contains the source paths.
The extraction-base trigger is the merge of the currently in-flight S2 native
recorder workflow into `develop`, including its focused tests and documentation.
The exact merge commit becomes the recorded extraction base. Before filtering,
the maintainer verifies that no unmerged S2 recorder change remains in its
development worktree.

That trigger deliberately does not wait for the queued S1 complete-run and two
S3K native workflows. Once the extraction base is named, new recorder work
starts in TraceChaser. If any queued workflow has already acquired unique
commits by then, those commits are replayed onto the filtered candidate and do
not land as a second OpenGGF implementation. Corrections required to stabilise
the frozen candidate land once in OpenGGF and are replayed into TraceChaser only
until the authority-switch commit. The OpenGGF cutover is the single authority
switch; no released OpenGGF revision maintains two implementations.

## BizHawk dependency policy

TraceChaser supports **BizHawk 2.11**, not 2.11.1 or an unpinned later release.
The current tooling depends on 2.11 Lua capabilities, including
`client.invisibleemulation`, that are not available in the required form in
later builds. The official upstream is
[`TASEmulators/BizHawk`](https://github.com/TASEmulators/BizHawk), and the 2.11
release is obtained from its
[`2.11` release assets](https://github.com/TASEmulators/BizHawk/releases/tag/2.11).

TraceChaser does not redistribute BizHawk. It provides:

- a machine-readable dependency lock for version 2.11;
- official release URLs, platform archive names, and SHA-256 hashes;
- an opt-in installer that stages without replacement, verifies the archive,
  checks the extracted layout and required capabilities, and then publishes the
  install atomically into an ignored local dependency directory;
- manual installation instructions for each supported platform;
- a preflight command that accepts an explicit BizHawk home, reports the actual
  version, verifies required assemblies and Lua capabilities, and rejects an
  incompatible installation before recording;
- reviewed lock updates for any future BizHawk change.

Two existing locks serve different consumers and remain distinct after the
migration.

The Lua/runtime installer lock comes from
`tools/bizhawk/fetch_bizhawk_2_11_linux.sh`:

```text
archive: BizHawk-2.11-linux-x64.tar.gz
sha256: cdaf9650d880bae660d63a388430f630b8d8a96b1ba59ebf0e0195a645c3bab8
```

Its existing extracted-layout checks and
`client.invisibleemulation` capability check are lifted into the TraceChaser
dependency preflight rather than reimplemented as a new contract.

The reproducible native GPGX observer source lock comes from
`tools/bizhawk-headless/native/gpgx-audio-observer/source-lock.json`. It pins
the BizHawk source commit
`427556b5ef3ac437eba754d90c5e7e9096c9a8df`, the Genesis Plus GX and musl source
commits, Git object identities, and critical-file hashes used by the native
rebuild. It is not an archive-install lock and remains owned by the native build
workflow.

The implementation verifies rather than assumes corresponding Windows release
asset hashes before adding them to the lock. An installer never downloads from
an OpenGGF or TraceChaser mirror when the official asset is available.

## OpenGGF submodule integration

OpenGGF adds:

```ini
[submodule "tools/tracechaser"]
    path = tools/tracechaser
    url = https://github.com/OpenGGF/TraceChaser.git
```

The gitlink points at the commit tagged as the first OpenGGF-compatible
TraceChaser release. OpenGGF does not track a submodule branch and never follows
TraceChaser's default branch implicitly. An upgrade is a reviewed gitlink
change accompanied by compatibility evidence and documentation updates.

Default clone, Maven, CI, release packaging, and runtime do not initialise or
require TraceChaser. A contributor or agent entering a trace workflow uses:

```bash
git submodule update --init --recursive tools/tracechaser
```

OpenGGF supplies `tools/tracechaser.sh` and `tools/tracechaser.ps1` as the
checkout-aware bootstrap entry points. They do not download automatically.
They either delegate to the pinned checkout or fail with the exact submodule
initialisation command and the expected gitlink identity. Command delegation
uses one canonical resolver: it rejects a dirty same-HEAD checkout, symbolic
links or non-directory ancestors between the gitlink root and target, and any
target whose resolved path escapes the checkout. Shell, Lua, Python, and
PowerShell compatibility entry points execute only the path returned by that
resolver.

The two retained native-to-Java decode gates are not part of the ordinary test
selection. Each method first resolves the optional checkout: absence skips and
a present wrong commit fails. Only after an exact checkout is established does
the existing ROM/movie environment opt-in become a JUnit assumption. An
explicit integration command selects them, so an unset capture environment can
never hide a wrong gitlink.

For the 0.6 cycle, the documented public commands at former paths remain as
thin forwarders where removing the path would otherwise make an agent follow
stale instructions. Forwarders contain no recorder, parser, validator, or
publication logic. Active documentation and skills use the new canonical
TraceChaser paths; old-path wrappers are transitional safety, not the preferred
interface. Their removal is reconsidered for 0.7.

## Trace-v5 contract and data flow

The migration preserves the sole live v5 envelope and every existing producer
and consumer rule:

```text
ROM + BK2
    -> TraceChaser capture into external scratch storage
    -> TraceChaser trace-v5 validation
    -> TraceChaser candidate comparison and publication preparation
    -> reviewed OpenGGF fixture installation
    -> OpenGGF replay and timing-authority tests
```

TraceChaser owns producer validation. OpenGGF owns replay interpretation and
gameplay authority. Neither side may use trace data to decide gameplay values
beyond the existing hardware-timing exception documented by OpenGGF.

The portable conformance pack contains small positive and negative documents
for metadata, physics-row shape, auxiliary JSONL, hardware timing, compression,
and run manifests. TraceChaser validates the producer pack. OpenGGF retains a
small consumer copy under `src/test/resources/tracechaser/` because ordinary
tests must pass without an initialised submodule. The central
`provenance.json` records:

- the originating TraceChaser commit;
- the contract-pack version;
- a path-and-SHA-256 manifest;
- the OpenGGF consumer tests that use each item.

When TraceChaser is initialised, a structural integration check byte-compares
the complete v5 tree, GPGX capability JSON, and audio-normalization contract
against every declared source path. With the submodule absent, ordinary Maven
parses all 31 v5 accept/reject cases and validates the exact copy set and hashes
without network access; mutation tests reject tampered, missing, and extra
provenance entries.

Canonical fixture installation remains an explicit, user-approved operation.
TraceChaser validators and comparators never overwrite
`src/test/resources/traces/` directly.

## Agent and contributor experience

Extraction is not complete if it makes OpenGGF trace work harder to discover.
The supported agent path is:

1. read the OpenGGF trace skill relevant to the task;
2. run the OpenGGF bootstrap wrapper;
3. initialise the exact submodule commit if prompted;
4. run TraceChaser's dependency preflight;
5. install or select official BizHawk 2.11 if prompted;
6. provide user-supplied ROM and BK2 paths explicitly;
7. capture into a durable scratch directory outside both repositories;
8. validate and compare before requesting fixture installation approval.

Errors distinguish at least these cases and provide one recovery action:

- uninitialised or wrong TraceChaser gitlink;
- absent BizHawk installation;
- unsupported BizHawk version or missing capability;
- absent, wrong, or unverified ROM;
- absent or incompatible BK2 movie;
- existing output directory;
- insufficient scratch space;
- malformed or incomplete trace-v5 output;
- candidate mismatch;
- attempted write into canonical OpenGGF fixtures.

No error suggests copying ROMs, movies, or BizHawk binaries into Git.

## Documentation migration

### TraceChaser documentation

TraceChaser ships with:

- a workflow-first root README;
- `docs/install-bizhawk-2.11.md` for manual and verified installation;
- `docs/trace-v5-contract.md` for producer-facing schema rules;
- capture guides for S1, S2, and S3K, including complete-run modes;
- Lua probe and native-headless guides;
- validation, comparison, compression, inventory, and publication guides;
- scratch, security, and prohibited-artifact policy;
- contributor, test, and release instructions;
- `docs/history-import.md` and `docs/migration-from-openggf.md`;
- mirrored `AGENTS.md` and `CLAUDE.md` scoped to TraceChaser.

### OpenGGF documentation

The coordinated cutover updates all active material that teaches or enforces a
trace workflow, including:

- `README.md`, `CHANGELOG.0.6.md`, and the 0.6 public/detailed release copy;
- `AGENTS.md` and `CLAUDE.md` together;
- contributor setup, trace replay, trace framework, trace-v5 publication, and
  headless-testing guides where they name migrated commands;
- current agent workflow runbooks;
- every affected file mirrored under `.agents/skills/` and `.claude/skills/`;
- architecture indexes and the extraction audit's status;
- structural guards for submodule optionality, documentation paths, and
  prohibited duplicate implementations.

The initial active-path rewrite contains these 13 contributor-guidance and
skill files:

- `docs/guide/contributing/trace-framework-reference.md`;
- `docs/guide/contributing/trace-replay.md`;
- `docs/guide/contributing/trace-v5-publication.md`;
- `docs/agent-workflow/runbooks/runbook-multi-agent-trace-orchestration.md`;
- `docs/agent-workflow/runbooks/runbook-s1-v37-regen.md`;
- `bizhawk-headless-trace/SKILL.md`, `plc-system/SKILL.md`,
  `s1-trace-replay/SKILL.md`, and `trace-replay-bug-fixing/SKILL.md`, each under
  both `.agents/skills/` and `.claude/skills/`.

Release notes, discrepancy documents, and repository/agent guidance are
additional cutover obligations rather than part of that count. The cutover
commit stages `README.md` as required by merge policy and sets
`Agent-Docs: updated` and `Skills: updated` with both mirror pairs staged.

Historical designs, audits, validation reports, and frontier records retain
their original commands as point-in-time evidence. They receive a central
migration mapping rather than mass rewriting that would falsify history. Active
guidance must not direct agents to removed implementation paths.

### Source references and emitted commands

The current `src/main/` tree contains nine references to
`tools/bizhawk-headless` across comments and one emitted command. Every source
and test comment that cites a moved recorder file is rewritten to its
`tools/tracechaser/bizhawk-headless/...` path, which resolves at the pinned
gitlink commit. In particular, direct `src/Recording/*.cs:line` citations remain
specific rather than being replaced by a generic project link.

`S3kLoadTimeProfileGenerator` currently emits
`tools/bizhawk-headless/run.sh --mode load-time`; the authority-switch commit
changes that user-facing command to the canonical TraceChaser invocation. The
cutover path audit covers Java string literals and comments as well as Markdown.

## Validation strategy

### TraceChaser source-only CI

TraceChaser CI requires no ROM or BK2 and covers:

- deterministic native harness builds where the pinned toolchain is available;
- the native test runner without ROM-backed gates;
- Lua shared-module and contract tests;
- Python validation, comparison, inventory, and publication tests;
- positive and negative trace-v5 conformance fixtures;
- BizHawk dependency-lock and installer tests without trusting the network;
- checkout-path portability, including paths containing spaces;
- guards rejecting ROMs, BK2s, BizHawk distributions, build output, caches,
  scratch captures, uncurated logs, and machine-local paths;
- documentation command and path checks;
- history-import provenance checks.

### Maintainer capture validation

The first release is not eligible for OpenGGF pinning until a clean checkout
outside the OpenGGF tree:

1. obtains or selects official BizHawk 2.11;
2. passes dependency preflight;
3. runs the existing reviewed capture matrix with user-supplied ROMs and
   movies;
4. exercises representative S1, S2, and S3K standard and complete-run paths;
5. validates every candidate;
6. compares the results with the pre-extraction outputs;
7. records exact hashes, sizes, counts, ordering, and permitted volatile
   metadata differences;
8. receives independent review before the release tag is created.

Any unexplained recorder-output delta blocks the migration. The repository move
does not justify fixture changes.

### OpenGGF validation

Before and after cutover, OpenGGF records and compares:

- the full ROM-backed Maven suite with absolute ROM paths;
- the fresh-JVM structural guard suite;
- focused trace replay and trace-contract tests;
- a submodule-absent ordinary clone/build/test path;
- a submodule-initialised wrapper, contract-manifest, and TraceChaser preflight
  path;
- release packaging proof that TraceChaser and BizHawk artifacts are absent
  from the OpenGGF runtime distribution.

The TraceChaser optionality assertions extend the existing
`TestBuildToolingGuard` policy added by `c1833132c`, alongside the canonical
optional-disassembly checks. They verify the gitlink is trackable, default
clone/setup and ordinary CI do not initialise it, and trace setup retains an
explicit opt-in command. A separate boundary guard rejects migrated
implementation files under their former OpenGGF roots; it does not reject the
named compatibility wrappers or consumer-conformance copies.

## Coordinated cutover

The migration proceeds through these gates:

1. Merge the in-flight S2 native recorder workflow, verify its worktree has no
   unmerged recorder changes, record that `develop` merge commit as the
   extraction base, and complete the file classification.
2. Create the history-filtered TraceChaser candidate.
3. Remove excluded and third-party material from the candidate.
4. Make paths portable without changing recorder behaviour.
5. Establish BizHawk 2.11 locks, installation guidance, and preflight.
6. Freeze the portable v5 conformance pack.
7. Pass TraceChaser source-only CI and clean-checkout capture validation.
8. Publish the first TraceChaser release tag.
9. In one OpenGGF change, delete migrated implementations, add the pinned
   `tools/tracechaser` gitlink, add thin wrappers, resolve every disposition in
   the test/resource inventory above, and update the 13 active guidance/skill
   files plus guards, source citations and commands, README, agent docs, release
   notes, and conformance provenance.
10. Pass OpenGGF submodule-absent, submodule-present, full ROM-backed, guard,
    focused trace, and release-package validation.
11. Merge and push the OpenGGF cutover only after both repositories' evidence
    is reviewed.

The old OpenGGF implementation paths are not retained as a fallback copy.
Rollback uses Git history: revert the OpenGGF cutover and gitlink change, or pin
the last accepted TraceChaser commit. A failed cutover leaves the TraceChaser
repository available for diagnosis but does not force an OpenGGF release.

## Release and versioning

The first TraceChaser release is `v0.1.0` and records its compatible OpenGGF
range. OpenGGF pins the release commit, not a floating tag or branch.
TraceChaser release notes identify:

- the OpenGGF extraction base;
- trace schema compatibility (`trace_schema: 5`);
- supported BizHawk version (`2.11`);
- supported host/toolchain matrix;
- capture validation evidence;
- known limitations and intentionally retained legacy behaviour.

OpenGGF 0.6 release notes state that trace tooling is now supplied through the
optional TraceChaser submodule and give the exact initialisation command.

## Deferred work

After the migration and OpenGGF 0.6 release, separate designs may consider:

- removing old trace-format compatibility and re-recording retained fixtures;
- introducing a new sole-supported trace schema;
- consolidating TraceChaser's directory and command structure;
- removing 0.6 compatibility wrappers in OpenGGF 0.7;
- extracting ROM/disassembly analysis utilities after a stable codec API exists;
- external trace-fixture hosting if repository weight becomes an active limit;
- an audio-parity project after a public engine-independent stream contract
  exists.

None of these may expand the migration implementation plan.

## Acceptance criteria

The extraction is complete only when:

1. `OpenGGF/TraceChaser` exists with verified retained history.
2. TraceChaser contains all approved external trace utilities and no prohibited
   generated, ROM, movie, BizHawk, or machine-local material.
3. Official BizHawk 2.11 acquisition and preflight are documented and pinned.
4. TraceChaser passes source-only CI and reviewed ROM-backed capture validation.
5. OpenGGF contains no migrated implementation copy.
6. OpenGGF pins the accepted TraceChaser release commit at
   `tools/tracechaser`.
7. OpenGGF ordinary builds and tests pass with the submodule absent, with every
   former Java-to-tool dependency either moved, consumer-copied, or explicitly
   skipped as one of the two opt-in integration gates above.
8. Trace workflows and the two opt-in native-to-Java integration gates pass
   with the submodule initialised at the pinned commit.
9. Active OpenGGF docs and mirrored skills use the new canonical workflow.
10. Existing trace-v5 outputs and OpenGGF replay behaviour remain compatible.
11. OpenGGF 0.6 full tests, guards, and release packaging show no regression.
