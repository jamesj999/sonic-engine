# Dead and Unfinished Code Sweep Design

**Date:** 2026-08-08

## Requirements

### Goal

Reduce misleading and unreachable production code, then leave a reproducible,
ranked account of genuine unfinished runtime paths without guessing at ROM
behavior or expanding this sweep into unrelated feature implementations.

### Acceptance criteria

1. Every deleted production type has no Java caller, configuration/resource
   registration, service-loader entry, documented supported CLI, or reflective
   reference.
2. The exact dead compatibility methods and constants listed below are removed
   only when production
   and tests have no caller and no serialized or reflective contract.
3. Stale TODO/stub comments are corrected only when the referenced behavior is
   already implemented and tested.
4. Genuine unfinished runtime paths are recorded with exact owners, impact,
   source-of-truth requirements, and verification needed to finish them.
5. Well-written unfinished functionality is retained even when it currently has
   no inbound caller; active progress documentation is updated to reflect its
   actual implementation state.
6. Production compilation passes and the focused source/architecture tests add
   no failures.
7. The full JDK 21 suite introduces no failure or error beyond the recorded
   `develop` baseline.

### Constraints

- Runtime assets remain ROM-only.
- Gameplay behavior is not inferred from marker text or fitted to a trace.
- Reflection, registries, serialization, CLI entry points, and data-driven
  construction count as reachability.
- Historical design, plan, validation, and changelog records are not rewritten
  merely because a referenced implementation is later removed.
- Current status ledgers, current-progress roadmaps, zone research, and living
  architecture references are corrected when the sweep proves them stale.
- Large unfinished features receive dedicated follow-up work rather than a
  partial implementation in this cleanup.

### Non-goals

- Implementing Big Arm, AIZ miniboss napalm, missing zone intros, boss splash
  children, special-stage debug tooling, SMPS meta commands, menu audio, or
  load-time simulation modes.
- Deleting every private member found by a lexical one-reference heuristic.
- Introducing a new static-analysis dependency or a permanent heuristic guard
  with known reflection/registry false positives.
- Deleting a coherent unfinished feature solely because it is not wired into
  the current runtime.

## Exploration synthesis

The repository contains 2,312 production Java files. A marker scan found few
literal production TODOs but many intentional optional-provider `null` returns,
so marker count is not a useful dead-code metric. Maven configures Java 21 but
has no PMD, SpotBugs, Checkstyle, Error Prone, or unused-code analyzer; `javac`
does not report unused Java declarations.

Two independent read-only passes found these declaration-only types. Exact-name
reachability is necessary but, under the preservation rule, not sufficient for
deletion. The high-confidence obsolete/redundant subset is:

- `DebugSpriteMovementManager`
- `NoOpPresenceClient`
- `S3kSavePayload`
- `Sonic2SpecialStageDebugProvider`
- `SpindashCameraTimer`
- `VelocityAnimationProfile`

`DebugArtViewer` is also declaration-only but has a `main`. Its hardcoded
`s2.gen` path, instruction to temporarily modify `Sonic2ObjectArt`, and lack of
tool documentation or tests identify it as an abandoned scratch CLI rather
than a supported tool. It is included in the deletion set.

The following declaration-only code is retained and documented because it is
coherent unfinished functionality rather than obsolete scaffolding:

| Owner | Why retained |
|---|---|
| `Sonic3kSpecialStageScalars` | Contains a substantial Blue Sphere projection implementation. Its simplified/ROM-accurate contradiction needs a future accuracy review and integration decision, not deletion. |
| `DebugColorShaderProgram` and `DebugPrimitiveRenderer` | Form a coherent collision/sensor debug-rendering path recorded in current project history. Lack of a caller means the feature is unwired, not valueless. |
| `CNZBossAnimations` | Preserves a ROM-derived animation table. Before deletion, a dedicated consolidation must prove the live CNZ boss duplicates every script and command semantic. |
| `shader_debug_text.vert` / `.frag` | Coherent debug-text rendering assets; retain with the unwired debug-rendering feature rather than deleting by filename reachability alone. |

The live `shader_debug_color.*` resources also remain because `GraphicsManager`
loads them directly.

The exact narrow cleanup scope is:

| Owner | Symbol or text | Disposition |
|---|---|---|
| `DisassemblySearchResult` | `hasBinclude()` | Delete caller-free deprecated alias. |
| `TraceReplayBootstrap` | `usesS2TornadoRideStartForTraceReplay(...)` | Delete caller-free deprecated alias; retain the generic metadata predicate and the existing guard against new use of the alias. |
| `TraceMetadata` | `hasPerFrameCnzSlotMachineState()` | Delete caller-free deprecated alias; retain `hasPerFrameSlotMachineState()`. |
| `InitialProcessSpritesLevelManagerBase` | `consumePendingInitialObjectSetupPass()` | Delete caller-free `forRemoval` alias. |
| `CnzMinibossInstance` | `setLower2CounterForTest(int)` | Delete caller-free `forRemoval` test shim and its stale “remaining caller” comment. |
| `LevelManager` | protected no-argument constructor | Delete the caller-free `forRemoval` constructor that always throws. |
| `Sonic3kSpecialStageRomOffsets` | `ART_KOS_RESULTS_GENERAL`, `ART_KOS_RESULTS_TK_ICONS`, `PAL_RESULTS`, and their three size constants | Delete only this unused six-constant results section; production results use verified `Sonic3kConstants` addresses. |
| `Sonic2MCZBossInstance.onHitTaken(...)` | TODO claiming `boss_hurt_sonic` is unwired | Replace with current ownership: `onTouchResponse(...)` already sets the flag for harmful main-player drill contact. |
| `Sonic3kSpringObjectInstance` class Javadoc | “Reverse gravity … currently stubbed” | Remove “currently stubbed”; initialization already swaps vertical spring direction under reverse gravity. |
| `SidekickCpuController` | “Stubbed in Task 2; body lands in Task 4/5” on the two implemented routines | Remove task-history text while retaining the ROM behavior documentation. |

The only runtime-resource cleanup is `kosinski.txt`, which is an unused
reference document misplaced in runtime resources, plus only its
`kosinski\\.txt` Graal include. Preserve its useful content by moving it to
`docs/architecture/research/compression/kosinski-format.md`, adding provenance
and links to the current decompressor owners. No shader resource is deleted.

The unfinished-path inventory is materially different. The completeness
boundary is explicit markers and structural stubs under `src/main/java` and
production `tools`, using `TODO|FIXME|stub|scaffold|not implemented|no-op|Phase
N`, plus default returns/empty methods whose live callers were found during the
marker pass. Generated scaffold output, tests, historical artifacts, and
ordinary optional-provider defaults are excluded. The high-confidence findings
and dispositions are:

| Rank | Owner | Runtime impact | Disposition and evidence needed |
|---|---|---|---|
| P0 | `AizMinibossNapalmProjectile` | Live Knuckles AIZ miniboss projectile has approximate motion, no harmful touch response, and no rendering. | Dedicated S3K object port from `loc_68C96`: motion/floor behavior, collision flags/timing, ROM art/mappings/explosion children, rewind and Knuckles AIZ trace tests. |
| P0 | `LbzFinalBoss2Instance` | Big Arm handoff is an inert invisible persistent object, blocking authentic Knuckles LBZ completion. | Dedicated boss implementation from `Obj_LBZFinalBoss2`, including ROM art/PLC, phases, hit/defeat flow, rewind, and LBZ Knuckles trace. |
| P1 | `Sonic3kLevelEventManager` | LRZ1 non-Knuckles was missing the native falling intro state; SSZ was incorrectly attributed the same behavior. | Port the LRZ falling-intro gate from `SpawnLevelMainSprites` `loc_68A6`; record the SSZ `$0A00/$0A01` source correction (no `loc_68A6` branch) and add character/zone/act bootstrap tests plus traces. |
| P1 | `AizEndBossInstance` | Emerge and re-submerge play sound but omit splash children, affecting visual and slot-order parity in the primary slice. | Port `ChildObjDat_69D2E` subtype behavior with ROM art/mappings, allocation, rewind, render, and AIZ2 boss trace tests. |
| P1 | `Sonic1.getBackgroundScroll()` | Live polymorphic API always returns `{0,0}` while S1 has newer zone parallax owners; rewind/background-Y behavior may be wrong or the API may be obsolete. | First decide ownership against `LevelFrameRuntimeUpdater`; remove the API/caller if redundant or source it from authoritative parallax state, with per-zone rewind tests. |
| P1 (resolved 2026-08-08) | `Sonic3kCoordFlagHandler.handleMetaCommand(...)` | Meta commands `SND_CMD`, `MUS_PAUSE`, and `COPY_MEM` consume bytes but discard semantics. | ROM-backed fixed-point traversal covers the S&K/S3 loader streams plus both 173-entry native SFX banks, including `9B`/`AD` payload differences and `DC-DF` aliases. ROM type-check bytes assert variant dispatch; no target command is reached, so operand-alignment-only custom-stream handling remains accurate. |
| P2 | S1 and S3K special-stage providers/managers | Shared Engine/GameLoop debug and alignment shortcuts silently delegate to scaffold/no-op methods. | Make capability exposure honest: implement game-owned tooling or move optional controls behind a supported debug capability; tests must show a shortcut works or is unavailable. |
| P2 | `MasterTitleScreen` | Navigate/confirm/error methods are called but empty, so host menu interactions are silent. | Add intentionally host-owned, ROM-independent UI SFX or document intentional silence, with interaction tests. |
| P2 (narrowed 2026-08-08) | `LoadTimeProfileFactory` | `FAST` and `REALISTIC` remain accepted reserved aliases. Each factory resolution emits its mode warning; `FAST` returns `LoadTimeProfile.IMMEDIATE` (`NONE`) and `REALISTIC` returns the supplied profiled instance (`PROFILED`). | Retain the modes and resolver. No independent semantics are safe to add yet: the profile submission contract currently covers only S3K Kosinski work, while S1/S2 PLC/DPLC owners use separate production queues. Revisit only after cross-game, boundary-specific measurements and a timing-model contract exist, with readiness/rewind/trace-authority behavior tests for all three games. Do not remove or silently reclassify the modes. |
| P2 | `AbstractLevel.markAllDirty()` | Public TODO/no-op placeholder has no production caller; one rewind test calls it but cannot observe any effect. | Resolve ownership before deletion: prove `LevelManager` snapshot restore already publishes dirty regions and remove the placebo API/test call, or route the contract through the existing dirty-region owner and add a GPU-refresh integration assertion. Retain during this cleanup unless that proof is completed. |
| P2 | `Sonic2MechaSonicInstance` | `ObjectMove` ordering differs from the ROM outer attack loop. | Refactor from `loc_398F4`/`loc_39D44` with phase/child-order tests and DEZ trace evidence. |
| P3 | `Sonic3kSpecialStageManager` results/debug remnants | Three old unverified results offsets are dead; alignment/debug surface is live but empty. | Delete only duplicate constants in this sweep; handle live tooling under the P2 capability design. |
| P3 | `CPZSpinTubeObjectInstance` debug-placement branch | A live object documents the ROM debug-placement path as unsupported by this engine. Normal CPZ tube gameplay is unaffected. | Treat as an engine-wide debug-mode feature dependency, not an object-local stub; implement only with a supported object placement/debug contract and CPZ debug tests. |
| P3 | `MonitorObjectInstance` human-P2 branch | Native S2 competition/human-P2 monitor behavior is absent because competition mode itself is unsupported. One-player and CPU-sidekick monitor behavior is separate. | Record as blocked on an explicit S2 competition-mode design; do not add an object-local game-mode carve-out. |

Intentional exclusions are recorded in the audit: scaffold TODO text emitted by
`ObjectScaffoldTool`; optional `ObjectArtProvider` count support; guarded
`ObjectWindowingStrategy`, queue-restore, and sized-resource overload
exceptions; locked-on-only `SK_alone_flag`; documented null-object providers;
camera-owned AIZ ship translation no-op; test upload sinks; and shipped
`FixBugs=0` comments. These are contracts, not unfinished implementations.
Every match inside the declared scan boundary receives one of these recorded
dispositions: dead cleanup, stale cleanup, ranked unfinished work, intentional
contract, generated output, historical artifact, unsupported broader game mode,
or ambiguous ownership retained for follow-up.

The recorded red baseline at `345fa27c2` ran 14,258 tests successfully, with 33
failures, 15 errors, and 35 skipped tests. The failures predate this branch and
span rewind, S2 special-stage cadence, source/architecture ratchets, CNZ
art-readiness, configuration, and presentation tests. Final comparison must use
the same JDK 21 command and confirm no new or worsened result. The exploratory
run was `mvn test`; its Surefire XML is under this worktree's
`target/surefire-reports`. Delivery evidence is stricter: after discovering and
hash-checking the actual three ROM files, run from the repository root with
`WORKTREE=$(pwd)`:

```bash
mvn -Dmse=off \
  "-Dsonic1.rom.path=${WORKTREE}/Sonic The Hedgehog (W) (REV01) [!].gen" \
  "-Dsonic2.rom.path=${WORKTREE}/Sonic The Hedgehog 2 (W) (REV01) [!].gen" \
  "-Ds3k.rom.path=${WORKTREE}/Sonic and Knuckles & Sonic 3 (W) [!].gen" \
  clean test
```

`mvn -v`, CRC32/SHA-1 results, command, commit/worktree context, totals, and
every failure/error test identity and outcome are copied to
`docs/architecture/validation/2026-08-08-dead-and-unfinished-code-sweep.md`.
Comparison treats a new failure, new error, failure-to-error change, changed
assertion/exception, or disappeared executed test as a regression. A changed
parameterized display name appears as a removed/added row keyed by class plus
Surefire testcase name and requires explicit review; the tool does not infer a
fallback identity.

Task 4 exposed a host-native concurrency blocker in its focused test set. Two
clean runs with the default four Surefire forks executed the new reverse-gravity
assertion successfully, then terminated native-initialization forks with
`SIGBUS` in `ld-linux-x86-64.so.2`; they emitted only 51 and 53 passing testcase
rows, respectively. Those incomplete manifests are not accepted as regression
evidence, and the 84/86 missing baseline rows are not waived. The identical
focused class set is therefore run before and after the comment-only edit with
`-Dsurefire.forkCount=1`, the repository's documented CI fork setting. That
serial pair must contain the same complete testcase identities and outcomes;
the earlier focused baseline remains contextual only, and the required updated
full-suite baseline/development/merged comparison remains the delivery gate.

Task 5 reproduced the same shared-native-extraction race at full-suite scale in
the development worktree. The default four-fork run stopped after 12,256
normalized testcase rows and cascaded to 2,853 errors after
`GlfwKeyNameResolver` failed to initialize from a missing `liblwjgl.so`; it is
retained only as `development-default-fork-incomplete.tsv.gz` diagnostic
evidence. Delivery therefore adds `-Dsurefire.forkCount=1` to the exact clean
ROM-backed command and reruns both the updated `develop` baseline and the
development branch in that repository-documented CI mode. Only that complete,
like-for-like serial pair is accepted for the pre-merge regression comparison;
post-merge verification must use the same serial command.

The first serial pair is also diagnostic rather than accepted evidence. Maven
Surefire's default `filesystem` class order differed between the separately
clean-built baseline and development trees. In the development order, the
pre-existing failing `TestTraceSessionLauncherProductionFailureCleanup` ran
before `TestGameLoopSpecialStageRewindGate` and leaked its active launcher;
forcing that two-class order reproduced the gate's exact two errors and one
failure. Conversely, ordering the new spring test before the gate passed all
21 tests, rejecting the spring-leak hypothesis. The complete serial development
rerun reproduced the same rejected manifest. Comparable full-suite evidence
therefore additionally sets `-Dsurefire.runOrder=alphabetical` on both baseline
and development (and later merged verification). No rewind-gate regression is
waived: every baseline PASS row must be restored under this deterministic
one-fork order, with only the expected spring characterization added as PASS.

Task 5 integration later found seven staged user-owned modifications in main.
That blocker is resolved outside this sweep: the user committed all seven as
`a8bfbcd7a85e00d760409e0dc9e02d16ef9763c8`, main and `origin/develop` now
match that commit, and main has no tracked or staged changes. Its unrelated
untracked files remain user-owned and must not be added, moved, removed, or
rewritten. The earlier `3f0fd4a70` staged-patch fingerprints are historical
blocker evidence only and must not be used as current integration guards.

Because `a8bfbcd7a` changes test owners and expected outcomes, renew both sides
of the regression gate. Run the exact clean ROM-backed alphabetical one-fork
suite on main at `a8bfbcd7a`, normalize a new `updated-baseline.tsv.gz`, and
restore only the generated rewind report. Merge `a8bfbcd7a` into the feature
worktree without switching main or touching its untracked files, run the
identical suite there, and normalize `development.tsv.gz`. Acceptance requires
zero missing baseline PASS rows, zero removed or reclassified
class/name/outcome/type rows, and only the sweep's expected added reverse-
gravity spring PASS; every raw message diff must still be reviewed.

The renewed pair completed after merging `a8bfbcd7a` without conflict in
feature merge commit `d9e552fbc`. Baseline has 14,341 rows (14,262 PASS, 34
FAILURE, 14 ERROR, 31 SKIPPED); development has 14,342 rows (14,263 PASS, 34
FAILURE, 14 ERROR, 31 SKIPPED). Four-column comparison has zero removed or
reclassified outcomes and exactly the added spring PASS; baseline-PASS
comparison has zero missing rows. The complete 28-line raw diff contains only
that PASS, one volatile launcher identity, and ordering of the same 13 guard
paths. This is the accepted renewed pre-integration gate.

Later integration remains fail-closed. Main must still equal the renewed base,
have no tracked/staged changes, and be an ancestor of the reviewed feature tip
before `git merge --ff-only`. Preserve a porcelain snapshot of its unrelated
untracked files before and after each fast-forward. Run post-merge validation
in a separate clean detached task worktree at the feature tip with the same
deterministic full-suite command, commit `merged.tsv.gz` and its validation
update there, then fast-forward main to that evidence commit only while HEAD,
ancestry, tracked cleanliness, and untracked status remain unchanged. Any
mismatch or regression requires renewed review.

Clean builds are mandatory for every comparable full-suite run so deleted
sources cannot survive as stale bytecode in `target/classes` or the packaged
JAR. `tools/test-reports/surefire-outcome-manifest.xsl` normalizes every
Surefire testcase to a sorted row containing class, method/display name,
pass/failure/error/skipped state, exception type, and single-line message.
Compressed manifests live under
`docs/architecture/validation/evidence/dead-code-sweep/`; `diff` between the
updated baseline, development, and merged manifests is the executable
comparison. Including passed and skipped rows detects disappeared or
reclassified tests and retains parameterized invocation identity.

## Approaches considered

### A. Delete only orphan top-level types

This is lowest risk but leaves equally well-proven dead compatibility members,
duplicate constants, stale markers, and no durable inventory of real unfinished
work.

### B. Remove every lexical one-reference member and finish every marker

This maximizes raw deletion count but is not evidence-safe. Private members may
still be reflective targets, and the unfinished findings cross bosses, audio,
special stages, rendering, configuration, and level bootstrap. The resulting
change would be difficult to verify or revert.

### C. Evidence-tiered sweep — selected

Delete only reachability-proven types and narrow members, correct stale markers,
and publish a ranked audit for the remaining runtime gaps. This produces a
reviewable cleanup while retaining enough evidence for later domain-specific
work.

## Architecture decision

The sweep changes no runtime ownership or data flow. Its classification boundary
is:

1. **Dead:** no inbound static or dynamic reachability and no supported external
   entry point. Remove it.
2. **Stale:** a comment claims work is missing but the owning behavior and tests
   exist. Correct the comment.
3. **Unfinished:** a live path silently no-ops, approximates, or omits ROM
   behavior. Keep the code visible and record a dedicated follow-up contract.
4. **Intentional optional behavior:** defaults, null-object seams, scaffold
   generation, and unsupported-ROM guards whose contracts explicitly permit the
   behavior. Leave them alone.

Deletion batches remain grouped by ownership so a regression can be traced:

- orphan types and their now-unused imports/resources;
- obsolete compatibility methods/constants and associated stale guards;
- stale implementation comments; and
- audit/release documentation.

Rollback is ordinary commit reversal. There is no migration, serialization,
configuration, save-data, ROM-address, or trace-schema change.

## Feature design

### Reachability proof

For each top-level candidate, search its exact simple name across production,
tests, resources, configuration, scripts, and current documentation. A `main`
method is retained unless the class is demonstrably scratch code with no
supported invocation. Service-loader files and string-based reflection are
checked explicitly.

`DebugArtViewer` meets the scratch-code exception after checking current CLI
catalogs, Maven exec configuration, scripts, resources, contributor/tooling
docs, and git history. No current surface invokes it; its historical edits were
incidental logging/AWT/default-filename migrations, while the body still tells
the user to modify another production class temporarily. Its hardcoded ROM
lookup also conflicts with current ROM discovery policy. The audit records this
evidence before deletion.

For private/deprecated member candidates, require both no caller and no textual
reflection/guard/serialization contract. Ambiguous candidates remain in the
audit rather than being deleted.

### Documentation freshness

For every unfinished finding, inspect current non-historical status ledgers,
current-progress roadmaps, zone research, and living architecture references.
Update stale completion claims, missing blockers, and obsolete ownership text
in the existing authoritative file. Historical dated designs, plans,
validation reports, and changelog entries remain untouched. The audit maps each
finding to its authoritative current document or states that the audit is its
first current record.

The initial freshness review requires these corrections:

| Current document | Correction |
|---|---|
| `README.md`, `docs/guide/playing/game-status.md` | Qualify AIZ route/completion claims; list napalm, AIZ splash, falling-intro, Big Arm, and Mecha Sonic parity gaps; refresh the status date. |
| `docs/architecture/research/s3k-zones/aiz-analysis.md` | Add a dated current-engine subsection for napalm and end-boss splash gaps without rewriting the original disassembly analysis. |
| `docs/architecture/research/s3k-zones/lbz-analysis.md` | Replace “verify/re-audit” Big Arm wording with the concrete inert/invisible blocker and implementation requirement. |
| `docs/architecture/research/s3k-zones/lrz-analysis.md`, `ssz-analysis.md` | Record LRZ falling-intro and SSZ source-correction zone notes: LRZ1 non-Knuckles was missing initialization; SSZ `$0A00/$0A01` has no `SpawnLevelMainSprites` `loc_68A6` branch. |
| `CONFIGURATION.md`, `docs/guide/playing/controls.md` | State that S2 exposes F12/F3; S3K F12 toggles manager state without a viewer provider, S3K F3 is a no-op, and S1 leaves both as no-ops. |
| `docs/guide/cross-referencing/architecture-overview.md`, `docs/guide/contributing/audio-system.md`, `docs/guide/contributing/architecture.md` | Qualify universal SMPS register-write parity, document `Sonic3kCoordFlagHandler`, and record the three discarded meta-command semantics. |
| `S3K_OBJECT_CHECKLIST.md` | Clarify that checked means concrete registry coverage, not full ROM parity, and that dynamic children may remain absent. |
| `docs/status/s3k-known-bugs.md` | Add current entries for napalm, Big Arm, falling intros, and splash children; preserve historical trace investigations. |

### Audit artifact

Create `docs/architecture/audits/2026-08-08-dead-and-unfinished-code.md` with:

- commands and limitations of the scan;
- removed types/members and proof;
- intentional false positives/exclusions;
- ranked unfinished paths with runtime impact;
- the correct source of truth and focused verification for each; and
- explicit deferrals.

Priorities follow the project release route: AIZ/HCZ stability first, then
current S3K route blockers, then tooling/polish, with cross-game/audio work
ranked by demonstrated reachability.

### Error handling

If deletion exposes a compile reference or focused test dependency, restore the
candidate and classify it with the newly discovered reachability. If a marker's
implementation status is ambiguous, leave the source unchanged and record the
ambiguity. Baseline failures are compared by test identity and outcome rather
than hidden by aggregate counts.

### Testing

- Compile production and tests after each deletion batch.
- Assert every deleted FQCN is absent from both clean `target/classes` output
  and the packaged JAR.
- Run focused tests for owners of deleted compatibility methods/constants and
  corrected stale comments.
- Run architecture/source guards.
- Use exact `rg` assertions for removed stale text; runtime owner tests prove the
  behavior those corrected comments describe.
- Add a focused S3K spring test that enables `reverseGravityActive`, executes
  native initialization for both vertical subtypes, and verifies UP/DOWN swap
  before removing the stale “currently stubbed” text.
- Run the full JDK 21 suite and compare its exact failures/errors with the
  recorded baseline.
- For this renewed gate, fetch without advancing main, require main and
  `origin/develop` to remain exactly at reviewed baseline `a8bfbcd7a`, and
  require its tracked/index state to remain clean. The renewed baseline suite
  and identical development suite have completed with the accepted results
  above.
- Before integration, require main still at `a8bfbcd7a`, preserve and compare
  its unrelated untracked status, prove ancestry to the reviewed feature tip,
  and use only a fail-closed fast-forward. Any tracked/index or baseline-HEAD
  change stops integration for renewed review.
- Run the post-merge suite in a separate clean detached validation worktree at
  the fast-forwarded feature tip, not in dirty main. Compare it to the newly
  recorded updated baseline, commit only merged validation evidence there,
  then fast-forward main to that evidence commit only while renewed clean-main
  HEAD, ancestry, tracked/index, and unrelated-untracked guards still hold.
- Before each suite, require `docs/status/rewind-round-trip-gaps.md` to have no
  pre-existing user diff in that workspace. After each suite, inspect and
  restore only the test-generated diff to the current workspace `HEAD`; repeat
  after baseline, development, and post-merge runs.

## Risks

- String/reflection reachability can evade Java reference search. Mitigation:
  repo-wide exact-name search and conservative exclusion.
- Historical docs can look like live callers. Mitigation: classify artifacts by
  purpose and do not rewrite history.
- A red baseline can hide regressions. Mitigation: preserve Surefire XML and
  compare test identities and outcomes, not only totals.
- Broad mechanical deletion creates noisy conflicts. Mitigation: keep this
  tranche to proven orphan types and narrow members; retain the larger lexical
  inventory as audit evidence only.
- Main or its remote baseline can change after the renewed comparison.
  Mitigation: assert the exact `a8bfbcd7a` HEAD, clean tracked/index state,
  ancestry, and byte-identical unrelated-untracked status before the guarded
  fast-forward; use a detached clean validation worktree and stop on any
  mismatch. The prior dirty-index fingerprints are historical evidence only.

## Delivery documentation and policy

- Stage the design, implementation plan, audit, validation report, and every
  supporting artifact created for this task.
- Stage every current status/roadmap/research documentation correction made by
  the freshness pass; do not leave documentation assets untracked.
- Update `CHANGELOG.md` with the dead-code cleanup and audit summary.
- Stage a `README.md` release/change-log summary before merging the worktree
  branch into `develop`, as required by merge policy.
- Commits use the repository trailer block. `Changelog`, `Agent-Docs`, and any
  other mapped trailer name the staged update; unmapped areas use a justified
  `n/a` where required. Never bypass hooks.
- `docs/status/rewind-round-trip-gaps.md` was regenerated by the exploratory
  full suite. It is test output unrelated to this task and must be restored to
  the branch version rather than staged.
