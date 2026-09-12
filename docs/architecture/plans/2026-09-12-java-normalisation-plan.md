# Java normalisation review and proposed work

Status: implementation complete, with validation limitations recorded below.
Authorised by the user on 12 September 2026.
Reviewed on `develop` at `3e56cfb246`, 12 September 2026. Work stays directly
on `develop`, without worktrees, as requested.

## Scope and conclusion

There are worthwhile refactorings here. The most useful changes establish one
implementation of a responsibility, give Java code semantic names, and encapsulate
state with its owner. ROM accuracy and idiomatic Java are compatible: observable
behavior is the contract; register names and assembly subroutine boundaries need
not dictate the Java design.

This was a broad static pass over the 2,989 production Java files, using file-size,
register-name and exact-block duplication searches, followed by targeted source
and caller inspection. The clone search used windows of 20 noncomment lines; it
is a candidate finder, not a semantic equivalence proof. This is not a line-by-line
audit of every class. The original review ran no tests; implementation validation is recorded below.

## Recommended first tranche

### 1. Finish the debug-shortcut extraction

**Evidence:** `GameLoop.java:1390` calls its own
`resolveBonusStageDebugShortcut`, defined at line 2292. The same implementation
exists in `GameLoopDebugShortcuts.java:38`. `TestGameLoop.java:1044` and nearby
tests exercise the latter. The helper also duplicates checkpoint teleport and
special-stage debug completion behavior; source references found no construction
of the helper.

**Change:** make `GameLoopDebugShortcuts` the production owner and route live
shortcut handling through it. Remove duplicated method bodies; preserve any
necessary facade entry point as a delegate. Give the helper only the dependencies
or operations it needs instead of widening access to loop internals.

**Benefit:** production and tests exercise the same code; mode sequencing becomes
easier to read. **Risk:** low to medium, especially fade callbacks and mode exits.
Validate shortcut modifiers, mode gating, and completion/exit integration.

### 2. Finish the lost-ring spawn extraction

**Evidence:** `LevelManager.java:2866` retains deferred slot allocation, queue
processing and its `PendingLostRingSpawn` record. These also exist in
`LevelLostRingSpawnCoordinator.java`. A production-source reference search found
only the coordinator's declaration and constructor, although the engine map
already describes it as the owner.

**Change:** wire the existing coordinator into `LevelManager`; move queue ownership,
processing and lifecycle cleanup together. Keep public manager methods as thin
delegates. Inspect rewind/checkpoint capture and reset callers before removing
the manager fields. Use narrow dependencies where practical.

**Benefit:** one owner for a timing-sensitive queue and documentation that matches
the implementation. **Risk:** medium to high despite the obvious duplication:
slot reservation, delayed owner clearing, cancellation and processing order are
observable. Validate lost-ring ordering, slot exhaustion, reset and rewind,
including the affected S1 lost-ring trace regression.

### 3. Consolidate default-argument rewind construction

**Evidence:** `SpawnDefaultArgsRewindRecreatable.java:54`,
`SpawnServicesDefaultArgsRewindRecreatable.java:56`,
`SpawnCoordinateDefaultArgsRewindRecreatable.java:56`, and
`SpawnCoordinateSubtypeDefaultArgsRewindRecreatable.java:56` duplicate validation
of defaultable argument types and creation of placeholder arguments.

**Change:** put these operations in the existing `RewindRecreateConstructors`
support class. Keep each marker interface's prefix matching, constructor-selection
rules and diagnostic identity explicit. No new reflective construction framework.

**Benefit:** one policy for permitted placeholders. **Risk:** low to medium.
Validate ambiguous constructors, unsupported primitives, services/coordinate
prefixes, and graph-link restoration using existing rewind tests and guards.

### 4. Replace the copied S3K static DPLC remapper with the shared utility

**Evidence:** `Sonic3kObjectArt.java:1474` implements the same plain remapping
algorithm as `DplcStaticFlattener.java:40`, including contiguous-piece handling
and splitting noncontiguous pieces. The shared utility additionally logs diagnostics.

**Change:** delegate the plain remap to `DplcStaticFlattener`. Review the adjacent
`applyDplcRemapWithDestinationBase` separately; share its mechanics only after
making destination/source-bank semantics explicit.

**Benefit:** one implementation for tile layout and piece splitting. **Risk:**
medium. Preserve missing-frame behavior, column-major ordering and flip metadata.
Use `TestDplcStaticFlattener` plus affected ROM-backed mapping/art tests. Any
pre-existing rendering defect discovered during comparison is a separate fix.

### 5. Centralise native P1/P2 slot resolution

**Evidence:** `MGZPulleyObjectInstance.java:516`,
`HCZConveyorBeltObjectInstance.java:263`, and
`LbzLoweringGrappleObjectInstance.java:321` repeat native-player selection,
update-player fallback, casts, duplicate rejection and a two-slot result.
`ObjectPlayerQuery` already owns participation policies.

**Change:** provide one semantic slot-resolution operation through the existing
query boundary or a package-local adapter if exposing it would expand Mod API.
Keep each object's grab/release rules and per-slot state local. Format the dense
one-line traversal helpers in `ObjectPlayerQuery` while touching that code.

**Benefit:** consistent player identity and clearer object mechanics. **Risk:**
medium. Preserve native slot identity when P1 is absent, fallback behavior and
the distinction between native P2 and extra engine sidekicks. Validate query
selection plus pulley, conveyor, grapple and graph-rewind regressions.

### 6. Give shields a shared animation/art lifecycle

**Evidence:** Fire, Bubble and Lightning shield classes repeat animation cursor
fields, initialisation, stepping, lazy DPLC binding and rewind invalidation.
`InstaShieldObjectInstance` also repeats the animation stepping block.

**Change:** extract a small composed playback/art collaborator, or a narrowly
scoped intermediate shield base if lifecycle ownership makes that simpler. Keep
ability activation, orientation, visibility, priority, sparks and end-of-life
behavior in the concrete shields.

**Important distinction:** do not blindly substitute `ObjectAnimationState`.
Its update publishes the current frame before advancing and defers animation
switches; the shield loop advances before publishing and switches immediately.
First specify these semantics and preserve them in the shared implementation.

**Benefit:** fixes to playback and rewind binding apply consistently. **Risk:**
medium. Validate first frame, zero delay, all end actions, ability transitions,
priority and restored DPLC state. Existing shield animation, lightning, priority
parity and rewind tests provide starting coverage.

### 7. Replace register-oriented locals with semantic operations

**Evidence:** `SwingMotion.java:25` immediately renames meaningful parameters to
`d0`, `d1`, `d2`. `TailsTailsController.java:483` and `:518` recompute the same
direction-adjusted velocity angle separately for mapping offset and flip flags;
both are called consecutively at lines 311–312. Object-edge balance in
`PlayableSpriteMovement.java:5053` also exposes register-style intermediate names.

**Change:** begin with SwingMotion and tail orientation. Use names such as
signed acceleration, next velocity, velocity limit and adjusted tail angle.
Compute the tail direction once and derive offset/flags together without adding
avoidable per-frame allocations. Address balance naming later with collision
coverage, without changing arithmetic.

**Benefit:** readers can understand intent without mentally executing registers.
**Risk:** low for pure renames, medium for extraction. Preserve every mask,
cast, signed comparison and same-call phase transition. Keep owning ROM routine
references in comments. Do not rename actual register fields in trace schemas.

### 8. Consolidate repeated boss-child presentation

**Evidence:** `CPZBossPump.java:99`, `CPZBossPipeSegment.java:129` and
`CPZBossPipePump.java:141` repeat animation updates and renderer lookup/readiness,
frame validity and flip handling for the same art family.

**Change:** reuse existing object rendering/animation support where it fits;
otherwise add a small CPZ-family presentation helper. Keep each child independently
scheduled and responsible for its movement, lifetime, spawn decisions and priority.

**Benefit:** reduces presentation boilerplate without a large boss inheritance
hierarchy. **Risk:** medium. Validate animation, render priority, child graph
rewind and CPZ boss behavior. Preserve RNG draw order and failed-allocation behavior.

### 9. Share donated data-select preview infrastructure

**Evidence:** `S1DataSelectImageCacheManager.java:227` and
`S2DataSelectImageCacheManager.java:220` duplicate framebuffer capture choreography;
nearby code also duplicates in-flight completion and failure unwrapping. Their
image generators contain additional shared processing blocks.

**Change:** introduce a shared preview capture service and cache-task lifecycle,
with game-specific capture targets, manifest details and generation policy supplied
by small strategies. Start with capture; do not merge all cache formats at once.

**Benefit:** render-thread scheduling, camera/parallax synchronisation and error
handling have one owner. **Risk:** medium. Validate both cache managers, failure
and retry behavior, manifests and actual preview capture in a graphics-capable run.

## Follow-up tranche requiring narrower designs

### 10. Converge duplicate profile/adaptor types after compatibility review

`level.objects.SolidRoutineAdapter` and
`game.profiles.solidroutine.SolidRoutineAdapter` repeat provider forwarding.
`TouchResponseProfile` also exists in both package families; the level-owned
record is annotated `@ModApi`.

Confirm callers and the candidate API surface, then make the canonical
`game.profiles` model authoritative with compatibility adapters only where needed.
Do not remove public types as ordinary duplicate cleanup. If the API surface
changes, update the release descriptor, version and signature pins together and
run compatibility guards. Risk: high; separate approval scope from internal cleanup.

### 11. Decompose large controllers by state ownership, one boundary at a time

The size scan found `SidekickCpuController` at 6,140 lines,
`AbstractPlayableSprite` at 5,579, `PlayableSpriteMovement` at 5,366 and
`ObjectSolidContactController` at 5,209. Size identifies review candidates; it
does not prove an abstraction is missing.

Start with sidekick diagnostic snapshot/report construction (roughly lines
762–1113), separating observational code from decisions. Then assess cohesive
follow/history state ownership. In `Sonic3kAIZEvents` (3,341 lines), inspect
fire-sequence and battleship state as separate owners, using existing zone,
palette, mutation and timing frameworks. Do not begin with a general movement
or collision rewrite, or a generic state-machine framework for all objects.

These changes require a field/rewind ownership map and an explicit ordered update
contract before extraction. Pilot a single boundary and replay affected routes,
especially AIZ → HCZ, before proceeding. Risk: high; defer until the first tranche
has demonstrated value.

## Design rules for the pass

- Prefer composition and existing narrow owners. Add inheritance only for a real
  lifecycle relationship, not merely matching method text.
- Preserve shipped-ROM results, including `FixBugs = 0`, fixed-width arithmetic,
  clock identity, object execution order, allocation failure and RNG consumption.
- Keep useful source references. Name private states semantically where clear,
  retaining explicit ROM codes at serialization/diagnostic boundaries; avoid a
  blanket conversion of routine counters to enums.
- Keep ROM asset loading and hardware timing authority in their current pipelines.
  Do not change trace contracts or hydrate gameplay from comparison data.
- Exclude mechanical normalisation of the reference-derived FM core and compression
  algorithms. Low-level structure can be justified there. Avoid repository-wide
  formatting churn and abstractions for single-use fragments.

## Delivery and verification after review

Implement approved items as independently reviewable commits directly on `develop`.
Recommended order: 1, 3, 4, 5, 6, 7, 8, 9; handle 2 as its own timing-sensitive
change rather than bundling it with debug cleanup. Items 10–11 are later work.

For each item, inspect the relevant domain skill before implementation, install
hooks if needed, and confirm Java 21. Add focused behavioral tests for uncovered
boundaries rather than tests that mirror helpers. Run affected existing tests
during iteration and the change-based category runner before delivery, with all
structural guards in their separate JVM. Pin the actual pre-change `develop`
commit as `--base` (this review's base is `3e56cfb246`); do not use an advanced
HEAD to hide already committed changes. Review the runner plan and add semantic
categories where needed; do not narrow its selection.

Run affected trace fixtures separately with verified absolute ROM paths. S3K
changes must retain the four mandatory bootstrap/loading checks named in
AGENTS.md and relevant AIZ/HCZ coverage. Inspect completed-run skips and the
measurement-hazard reference before claiming validation. Formatting and this
planning document do not constitute runtime validation.

Update the engine map when ownership changes and use the documentation obligation
checklist and seven commit trailers. Keep refactor-only notes in the develop
release line where warranted; no new discrepancy/frontier claims unless verified
behavior or trace results actually change.

## Implementation record

The integration base remains `3e56cfb246` on `develop`. All eleven items are
implemented within their stated boundaries; item 11 is the single diagnostic
pilot and ownership assessment, not a general controller rewrite.

| Item | Implementation and evidence |
| --- | --- |
| 1 | `ec6a4f4998`, `dbc5d2f436`: live helper wiring with narrow dependencies; loop retains provider-aware rewards and fade sequencing. Debug/loop focused tests pass, including live callback integration. |
| 2 | `b40ee206a5`: coordinator owns immediate/deferred spawns, queue, reset and rewind. Added registry/slot-exhaustion tests exposed missing external-reservation restoration; `fde0567b7a` adds post-restore reconciliation; registry/slot-exhaustion regressions pass. |
| 3 | `846537b98e`: one placeholder policy in `RewindRecreateConstructors`; four explicit marker prefixes retained. Constructor ambiguity/default/service/coordinate tests pass. |
| 4 | `7bd16fde72`: plain S3K mapping delegates to `DplcStaticFlattener`. CNZ cannon destination `$448` and source-bank semantics remain separate. Flattener and ROM-backed S3K loading/decoding tests pass. |
| 5 | `2738335230`: package-local native-slot adapter for pulley/conveyor, dead LBZ resolver removed, query traversal formatted. Slot identity/fallback and existing pulley/conveyor/grapple graph tests pass. |
| 6 | `db0a80d789`: composed shield animation/art lifecycle; same-call advance/switch semantics and cursor restoration before lazy art binding covered. Shield/priority/rewind/donor tests pass. |
| 7 | `b09026d462`: semantic SwingMotion/balance locals and one scalar tail angle; masks/arithmetic retained. Motion/tail/follow tests pass. |
| 8 | `1ddd144708`: shared CPZ child presentation with independent child scheduling. Presentation and CPZ graph rewind tests pass. |
| 9 | `5665fd4212`: shared render capture and asynchronous cache-task lifecycle; game manifests/targets remain local. Cache/failure/retry tests pass; actual S1/S2 GL captures verified. |
| 10 | `6c812cd1a2`, `055c98f2b9`: canonical mechanics behind unchanged public facades. Getter order/count regression tests and Mod API pin test pass. See [compatibility review](../designs/2026-09-12-profile-adapter-compatibility-convergence.md). |
| 11 | Diagnostic-only pilot implemented after first-tranche focused validation. Controller retains snapshot/API/gameplay ownership. Field map, ordered update contract and follow/history/AIZ assessments are in [pilot design](../designs/2026-09-12-controller-state-ownership-pilot-design.md). Pilot committed as `a84eb3e001`; scalar-format, sidekick and rewind checks pass. |

### Validation evidence

Java 21 is verified and `.githooks` installed. Root S1 REV01, S2 REV01 and
locked-on S3K ROMs match the CRC32/SHA-1 values in `AGENTS.md`; tests use their
absolute paths. The measurement-hazard table was reviewed.

- Initial debug/loop run: 87 tests, no failures/errors/skips.
- Combined focused run on the working tree based at `ec6a4f4998`: 577 tests,
  two new queue-test expected-slot errors and one new subclass-reflection test
  error; zero skips. All four mandatory S3K bootstrap/loading classes and the
  Mod API signature checks passed.
- Correction run on the working tree at `6c812cd1a2`: 120 tests, no
  failures/errors/skips, including shield restore-before-art/donor integration,
  lost-ring queue tests and live debug fade/reward integration.
- Final reservation/diagnostic correction run: 36 tests, no failures/errors/skips.
- Subsequent pilot/graph run: getter order, API, sidekick parity/rewind and CPZ/
  player-object graphs passed. A new formatter expectation had extra `=` signs
  absent from the original format; expectation corrected. New registry tests
  exposed lost-ring reservations omitted by `ObjectManager.captureOwnedUsedSlotBits`;
  the coordinator now reclaims its own slots in a post-restore callback.

Actual preview verification used the production capture service with a hidden
OpenGL 4.1 core context matching the engine, through `HeadlessGameBoot`'s existing
native-lifecycle seam. The default tool context requests OpenGL 2.1 and cannot
compile the current 4.1 shaders; no production graphics change was made for this
verification. The temporary harness is `target/PreviewCaptureVerification.java`;
it boots each verified ROM, calls `DataSelectPreviewCapture.capture(0, 0x180,
0x100)` on a worker while pumping render-thread tasks, and saves the framebuffer.
Both JVMs exited zero: S1 320×224 with 14 distinct colors, S2 320×224 with 19.
Both images were visually inspected. Outputs/logs are bounded temporary files
`target/java-normalisation-preview-s1.*` and `...-s2.*`.

### Completed integration validation and limits

- `python3 tools/testing/run_categories.py --base 3e56cfb246 --run` at
  `de893f0c07`, with release/engine-map/plan documentation edits only, selected
  all 2,499 candidate classes and separate guards. Run
  `20260912T145440Z-c573b892` completed **20,329 ordinary tests: 27 failures,
  8 errors, 97 skips**. This is a completed full selection, **not a green suite**.
  All four mandatory S3K bootstrap/loading checks and Mod API signature checks
  passed without skips. Skip inventory was inspected: optional benchmarks and
  captures, absent reference material, disabled editor cases, and tests using
  legacy ROM filenames account for the skipped coverage.
- The separate guard lane completed **656 tests, 0 failures/errors/skips**.
- The earlier incomplete sandbox run stopped at native graphics initialization;
  its cache warmup reflection failure was fixed in `de893f0c07`. Lua 5.4.8 and
  PowerShell 7.6.6 were supplied from existing installations for the completed
  guard run; native graphics access was enabled. No ROM aliases were created.
- The ordinary failures include FBZ route/matrix failures, macOS shell/base64/
  secure-directory incompatibilities, sample/scaffolder build prerequisites,
  one Mod API hook fixture assertion, legacy donor-ROM setup, and GL 2.1 capture
  helpers trying to compile 4.1 shaders. This pass does not claim that all these
  unrelated tests are healthy. The donor/setup fixes below are test-only;
  broader platform/tooling and FBZ repairs remain separate work.
- Focused verification of the donor-ROM setup and GL 4.1 test-capture
  corrections completed **85 tests, 0 failures/errors/skips** in the independent
  export at `de893f0c07` plus those exact test-only edits. Those optional edits remain
  only in the comparison export and are not part of this delivery. See the
  [validation handoff](2026-09-12-java-normalisation-validation-handoff.md).
  No production code changed after the
  completed full selection and paired route measurements. The full suite was
  not repeated or represented as green.

The focused route comparison used an independent source export with its own
`target/`, first at `3e56cfb246`, then rebuilt from scratch at `de893f0c07`.
Neither measurement switched the working branch or reused another tree's build.
Both used Java 21 and the same three verified absolute ROM paths:

```text
mvn -Dmse=off -Ptrace-replay \
  -Dtest=TestS1Mz1LostRingCollectionOrderRegression,TestS1Sbz2CompleteRunTraceReplay,TestS3kAizTraceReplay,TestS3kHczZoneSliceTraceReplay,TestFbzAct2RouteHeadless \
  -Dsonic1.rom.path=<verified absolute S1 REV01 path> \
  -Dsonic2.rom.path=<verified absolute S2 REV01 path> \
  -Ds3k.rom.path=<verified absolute locked-on S3K path> test
```

Both completed **27 tests, 5 failures, 0 errors, 0 skips**. All five failure
messages are byte-for-byte identical across the pair:

| Fixture/check | Before and after |
| --- | --- |
| S1 MZ lost-ring ordering | 6 tests pass |
| S1 SBZ2 complete run | passes |
| S3K AIZ → HCZ | 59 errors; first frame 5497, `camera_x` (expected `$0010`, actual `$0012`); two existing fire-reveal/reload camera-lock assertions also fail |
| S3K HCZ complete run | 4571 errors; first frame 9482, `air` (expected 1, actual 0) |
| FBZ act 2 route | same `obj74-crossing-lost-flat-control` at frame 31034 |

These matched measurements supersede older log totals for this comparison.
They show no regression in the measured routes, not full route parity. No trace
payload, timing contract, comparison tolerance or gameplay hydration was changed.
Temporary command/result summaries live under `target/java-normalisation-*`;
the durable result and scope are recorded here.

### Requirement audit

Items 1–9 each have a production owner, focused behavioral coverage and their
own reviewable commits. Item 10 retains public record identities and declared
methods, with canonical internal mechanics and verified signature pins. Item 11
has the field/rewind map, ordered update contract, follow/history and AIZ owner
assessment, and one scalar diagnostic pilot. Read-only final review confirmed
unchanged motion arithmetic, sampling order, sentinels and formatting.

Queue reset and restore ordering, shield cursor/art restoration, provider getter
order, CPZ child scheduling and native P1/P2 identity have explicit regressions.
ROM loading remains authoritative; fixed-width arithmetic, clocks and RNG paths
were retained. Engine-map and develop release-line notes describe the new owners.
No public Mod API surface, configuration, runtime asset source, release topology,
AGENTS/CLAUDE guidance or mirrored skill changed. The user subsequently requested committing the remaining documentation,
integrating `origin/develop`, and pushing `develop`; no release is requested.
