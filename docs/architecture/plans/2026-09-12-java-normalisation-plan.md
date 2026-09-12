# Java normalisation review and proposed work

Status: implementation in progress, authorised by the user on 12 September 2026.
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
audit of every class. No tests were run and no runtime behavior was verified.

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

The integration base remains `3e56cfb246` on `develop`. Implementation is in
progress; the numbered requirements above remain the completion checklist.

- Item 1: live debug actions now use `GameLoopDebugShortcuts`, with explicit
  teleport dependencies and a result-transition callback. The loop retains
  provider-aware reward publication and fade ownership; the unused helper's
  obsolete unconditional reward path was removed. Focused command
  `mvn -Dmse=off '-Dtest=TestGameLoop,TestGameLoopDebugShortcuts' test` completed
  on the dirty `develop` tree based at `3e56cfb246`: 87 tests, zero failures,
  errors or skips (`target/java-normalisation-debug-tests.log`).
- Items 3, 4, 5, 7 and 9: implementation underway; combined validation pending.
  The initial combined run failed compilation on the extracted native-slot
  record's private component access in HCZ; accessors now replace those accesses.
- Items 2, 6 and 8: independent implementation work in progress.
- Items 10–11: compatibility review and narrower state-ownership designs remain
  outstanding, after first-tranche validation.
- Final category/guard validation against the pinned base, ROM/trace coverage,
  graphics capture, documentation and independently reviewable commits remain
  outstanding. No full-suite or route-parity claim is made yet.
