# Mod Support Phase 0 — Engine Foundations Design

**Date:** 2026-07-09
**Status:** Approved (brainstorming session)
**Parent:** `2026-07-09-mod-support-design.md` §8 Phase 0. Sibling artifacts:
`2026-06-12-game-patch-kis2-design.md` (approved design) and
`docs/architecture/plans/2026-06-12-game-patch-kis2.md` (approved 14-task plan) — this
spec **amends** those for mod composition rather than replacing them.
**Gates:** Phase 2 (additive content mods). Phase 1 (music packs) does NOT depend on
this phase and may proceed in parallel.

## Goal

Deliver the three engine foundations content mods stand on:

- **A. GamePatch framework**, implemented per the KiS2 design and *extended* with
  ordered patch composition + enablement gating, so a mod jar can contribute a patch
  over a base game the same way KiS2 does.
- **B. Load-source abstraction**, so a `LoadOp` can read from a mod asset instead of a
  ROM address, with identical behavior for all existing ROM loads.
- **C. Editor completion**, bringing the level-editor MVP to a usable authoring
  baseline: object/ring placement, collision editing, and save-envelope hardening.

The three workstreams are independent of each other and independently shippable.

---

## A. GamePatch framework (+ composition extension)

### Relationship to the KiS2 plan

The KiS2 plan's framework tasks (Task 2 contracts/`DelegatingGameModule`/guard test,
Task 3 logical-ROM resolver, Task 4 `GamePatchRegistry`, Task 7 `LaunchProfile` union,
Task 8 choke-point wiring, Task 9 save-context sanitization) are adopted as written
**except where this section amends them**. The KiS2-content tasks (1, 5–6, 10–12)
stay in that plan and are not Phase 0 scope — but executing them right after the
framework is the natural validation of it, and the Phase 0 plan sequences accordingly.

### Amendment 1 — composition replaces single-patch resolution

KiS2 design non-goal "no patch chaining/stacking (one patch per session)" is
superseded (it was YAGNI before mods). `GamePatchRegistry.resolveModule(GameModule
base, GameplayLaunchRequest request)` now:

1. Collects registered patches for the base `GameId`.
2. Filters to patches that are **enabled** AND whose **activation predicate** accepts
   the request (predicate semantics unchanged from the KiS2 design; enablement is an
   additional gate) AND whose ROM prerequisites resolve.
3. Applies survivors **in enablement order**: `module = patch.apply(module, ctx)` —
   each patch decorates the previous result. Zero survivors → the base module,
   unchanged.

Later patches in the order win where overrides collide, matching the mod-support
spec's §7 "later wins".

### Amendment 2 — `PatchEnablement` seam (no `mods` dependency)

Enablement and order come through a small interface owned by the patch package:

```java
public interface PatchEnablement {
    boolean isEnabled(String patchId);
    int orderOf(String patchId);        // lower = earlier; ties by registration order
    PatchEnablement ALL_ENABLED = ...;  // default: everything enabled, registration order
}
```

**Contract for unknown ids (pinned here because Phase 2 depends on it):** an
implementation that does not manage a patch id — built-in patches like `"kis2"`
never appear in a mod catalog — MUST report it `isEnabled = true` and order it
**before** all managed patches (`orderOf` returns a value lower than any managed
patch's; among unknown ids, registration order). This keeps built-ins the base-most
layer, active regardless of mod-manager state or force-disable, which Amendment 5's
Knuckles-trace invariant requires. The interface Javadoc and a contract test encode
this.

`GamePatchRegistry` holds one, defaulting to `ALL_ENABLED` — which makes built-in
patches (KiS2) active-by-default exactly as the KiS2 design intends. Phase 2 installs
a `ModCatalog`-backed implementation; `com.openggf.game.patch` never imports
`com.openggf.mods` (dependency points mods → patch, same direction as mods → audio in
Phase 1).

### Amendment 3 — `PhysicsFeatureSet` is dropped from the contract (mandatory)

Recon: `PhysicsFeatureSet` / `PhysicsProvider.getFeatureSet()` do not exist in code,
and reintroducing them is **banned by CI**:
`TestPerGameRuleArchitectureGuard.productionCodeDoesNotUseLegacyPhysicsFeatureSet`
fails on any `src/main` mention of the token and asserts the type file must not
exist. Character abilities already ride `SecondaryAbility` per character (Knuckles →
`GLIDE`) plus `PhysicsProfile` constants and typed `GameRules`.

This amendment therefore **explicitly amends the KiS2 plan's Task 5**, which as
written would fail compile + guard: delete Step 2's "reuse `PhysicsFeatureSet.SONIC_2`
unchanged" decision, the `featureSetIsModuleScopedSonic2` test, and
`Kis2PhysicsProvider`'s `getFeatureSet()` override; and it supersedes the KiS2
design's "New KiS2 `PhysicsFeatureSet` derived from `SONIC_2` with glide/climb
flags" physics paragraph. It also redirects the KiS2 plan's Task 1 Step 2 pointer
("this decides whether `PhysicsFeatureSet.SONIC_2` can be reused verbatim — see
Task 5 Step 2"): the diff-catalogue question itself (does KiS2 ability
dispatch/jump-height behave like stock S2?) remains worth answering, but it now
feeds this amendment's per-game-rule-placement decision, not a feature-set step. Replacement: the KiS2 provider expresses Knuckles'
moveset through `getProfile("knuckles")` + the existing `SecondaryAbility` path
(glide already works this way for stock Knuckles; climb follows the same model). If a
gate genuinely fits none of the existing mechanisms, that is a
per-game-rule-placement decision at implementation time
(`docs/architecture/per-game-rule-placement.md`), not a framework type.

### Amendment 4 — `GameplayLaunchRequest` synthesis is specified per choke point

Recon confirmed no launch-request type exists; "the request" today is scattered
across config keys, `SaveSessionContext.selectedTeam()`, and `TraceMetadata`. The
record is **as KiS2-plan Task 2 defines it** —
`record GameplayLaunchRequest(String gameId, String mainCharacter, List<String>
sidekicks)` — which amends the KiS2 *design's* four-field description: the
cross-game source is a synthesis-time input to `LaunchProfile` availability, not a
request component (`resolveModule` never consults it). Synthesis per choke point:

| Choke point | Source of truth |
|---|---|
| `Engine.initializeGame()` (master-title exit reduces to this) | `ActiveGameplayTeamResolver` inputs: config `MAIN_CHARACTER_CODE`/`SIDEKICK_CHARACTER_CODE` |
| `Engine.launchGameplayFromDataSelect()` | `SaveSessionContext.selectedTeam()` (session team wins over config, matching `ActiveGameplayTeamResolver` precedence) |
| `TraceSessionLauncher` → `initializeGame()` | config keys as already written by `TraceReplaySessionBootstrap.prepareConfiguration` from `TraceMetadata` (no extra plumbing — the launcher already forces the recorded characters into config before launch) |
| `HeadlessGameBoot.boot(...)` | config keys, same as `initializeGame()` |

Resolution happens where the module is constructed/consumed for a session
(`initializeGame` after `detectAndCreateModule`; `launchGameplayFromDataSelect`
before `openGameplaySession(module, saveContext)`; `HeadlessGameBoot` after
detection). `SessionManager` signatures stay unchanged (KiS2 design requirement).

### Amendment 5 — mods force-disable composes at the enablement seam

During trace replay and test mode, patches contributed by mods must be inert (mod
spec §7). This falls out of Amendment 2: the Phase 2 `ModCatalog`-backed
`PatchEnablement` reports mod patches disabled when the catalog is force-disabled.
Built-in patches (KiS2) are NOT mods and stay governed by their activation predicate
alone — a Knuckles trace recorded against KiS2 must keep resolving the KiS2 patch.
Phase 0 needs no extra gate; this paragraph exists so nobody adds one.

### Bootstrap-bypass audit (carried risk)

`GameServices.bootstrapGameModule()` returns the pre-session default and never sees a
patch. `AbstractPlayableSprite` re-resolves physics against the session module via
`refreshRuntimeBoundStateIfNeeded()`, so the known consumer heals. Phase 0 includes
an audit + guard test: no `src/main` consumer may cache a provider obtained from
`bootstrapGameModule()`/`getBootstrapDefault()` without a session-rebind path.
(Mechanism: scanner-based test in the `TestObjectServicesMigrationGuard` idiom
listing allowed call sites.)

### Deliverables (workstream A)

KiS2-plan Tasks 2, 3, 4 (amended), 7, 8, 9 + the composition extension, the
`PatchEnablement` seam, the bootstrap audit/guard, and the
`TestDelegatingGameModuleCoversInterface` guard. Acceptance: KiS2-plan Task 13's
headless integration test shape (framework resolves a stacked patch correctly), plus
all existing trace suites green (patches resolve to base modules when no patch
activates — bit-identical behavior).

---

## B. Load-source abstraction

### Current reality (recon 2026-07-09)

- `record LoadOp(int romAddr, CompressionType compressionType, int destOffsetBytes)`
  with ~30 call sites, all via factory statics. `ResourceLoader.decompress(LoadOp)`
  reads exclusively from `Rom` (FileChannel / `readBytes`);
  `CompressionType.UNCOMPRESSED` **throws** (no length available on the op).
- The plan/loader seam carries **all S3K zones + S2 HTZ only**. `Sonic1Level` and the
  default `Sonic2Level` constructor decompress directly (Enigma/Kosinski/Nemesis),
  bypassing the package. S3K collision indices bypass the plan's collision ops.
- Layout, object/ring spawns, palettes, solid tiles, boundaries, and animated-tile
  scripts are **not** in `LevelResourcePlan` at all — each is a separate
  ROM-address path per game.

### Design

1. **`LoadSource` sealed interface** in `com.openggf.level.resources`:
   - `record RomAddress(int addr)` — today's behavior, byte-for-byte.
   - `record ModAsset(Path jar, String entryPath)` — reads the jar entry fully;
     the entry's own length replaces the missing size (this also finally makes
     `UNCOMPRESSED` executable through the normal op path for mod sources). This is
     the parent spec's "`ModAssetSource`" — parent name = `LoadSource.ModAsset` here.
   `LoadOp` becomes `record LoadOp(LoadSource source, CompressionType compressionType,
   int destOffsetBytes)`. Every existing factory static keeps its `int romAddr`
   signature and wraps in `RomAddress` — **no call-site churn**; new
   `modAssetBase/modAssetOverlay/modAssetAppend(Path, String)` factories produce
   `UNCOMPRESSED` mod ops. A convenience accessor `int romAddr()` (throwing for mod
   sources) keeps the rare direct readers compiling, flagged deprecated-for-new-code.
2. **`ResourceLoader` dispatch:** `decompress(op)` branches on the source type: ROM
   sources behave exactly as today; mod sources read the jar entry
   (`UNCOMPRESSED` only in Phase 0 — mod assets ship raw/jar-deflated per the parent
   spec; a compressed mod source is a validation error at plan build).
3. **Determinism/equality:** `LoadOp` equality now includes the source; nothing in
   the engine relies on `LoadOp` identity semantics (verified in recon — plans are
   built fresh per load).

### Explicit scope boundary (parent-spec correction)

The parent spec's §1 wording ("this is how 'new levels ride the existing
`LevelResourcePlan` path' is made literally true") holds **only for patterns, chunks,
blocks, and collision index data** — the resource kinds a plan carries. A
mod-supplied level additionally needs layout, spawns, palettes, solid tiles,
boundaries, and animation scripts, which today are per-game constructor address
parameters. **Phase 0 does not refactor those seams.** Phase 2's mod-level component
(a `ModLevelDefinition` consumed by the patch's zone/level providers) will supply
that data mod-side, using the plan seam for the four plan-shaped kinds and
provider-level overrides for the rest. Converging `Sonic1Level`/default-`Sonic2Level`
loading onto plans is explicitly out of scope for the whole mod effort — the S2
plan constructor already accepts arbitrary plans, which is the vehicle mod levels
use; S1-based mod levels are deferred until demand exists.

### Deliverables (workstream B)

`LoadSource` + widened `LoadOp` with compatible factories, `ResourceLoader` dispatch,
UNCOMPRESSED-for-mod-sources support, unit tests (ROM ops byte-identical on a fixture
plan; mod ops compose/overlay correctly from a jar built in `@TempDir`), and a green
run of the S3K must-keep-green set + `TestSonic3kLevelLoading` (the heaviest plan
consumer) + the S2 HTZ trace(s) covering the only S2 plan zone.

---

## C. Editor completion

### Current reality (recon 2026-07-09)

Works today: block painting (FG/BG) with drag-stroke undo, eyedrop, chunk/pattern
derive (copy-on-write), delta persistence (`EditorSaveEnvelope` v1: blocks, chunks,
map cells; SHA-256 + quarantine), teardown/rebuild mode swap with `MutableLevel`
surviving. Missing: any object/ring editing, any collision editing, spawn
persistence, S3K runtime re-apply (`supportsRuntimeEditApply` returns false for S3K).

Key lever: **the runtime object-spawn mutation path already exists and is unwired** —
`MutableLevel.addObjectSpawn/removeObjectSpawn/moveObjectSpawn/addRingSpawn/
removeRingSpawn` + `objectsDirty` → `LevelDirtyRegionDispatcher` →
`ObjectManager.resyncSpawnList(...)` has zero callers in `src/main`. Collision
storage is likewise already editable and persisted (`ChunkDesc` solidity bits ride
`Block.saveState()`; `Chunk.solidTileIndex/AltIndex` setters exist and ride
`Chunk.saveState()`); only commands + UI are missing.

### Scope (exactly the parent spec's three items)

1. **Object/ring placement.** New editor focus mode with commands
   `PlaceObjectSpawnCommand`, `MoveObjectSpawnCommand`, `DeleteObjectSpawnCommand`
   (+ ring equivalents), each undoable via the spawn-list mutation API above and
   flushed through the existing dirty-dispatch/resync path. Input follows the
   existing hardcoded-binding style (a placement sub-mode on TAB-cycled focus
   regions); object identity is chosen by numeric id + subtype entry (eyedrop an
   existing spawn to copy its id/subtype). **This refines the parent spec's §6 line
   "object-placement palette … Phase 0":** Phase 0 delivers the spawn-list *editing*
   (place/move/delete, subtype parameters); the *browsable* object palette moves to
   Phase 2's mod-facing library work. Overlay rendering marks spawn positions
   (id/subtype text at spawn coords via the existing overlay renderers).
2. **Collision editing.** Two operations, both at existing depths: (a) at BLOCK
   depth, cycle the hovered cell's `ChunkDesc` primary/secondary collision mode bits
   (`0x3000`/`0xC000`); (b) at CHUNK depth, reassign `solidTileIndex`/`AltIndex` by
   eyedrop-from-another-chunk or numeric entry. Both as undoable commands. No new
   persistence schema: both already round-trip through `BlockState`/`ChunkState`.
   A debug-style collision overlay toggle (reusing `LevelDebugRenderer`'s collision
   drawing against the editor camera) makes edits visible.
3. **Save-envelope hardening.** Payload **version 2**: adds `objectSpawns` and
   `ringSpawns` lists (full replacement lists, not deltas — spawn tables are small
   and order matters for slot cadence). **Write policy:** v2 saves write the complete
   current spawn tables **unconditionally on every save** — there is no
   spawn-modification baseline BitSet analogous to `modifiedBlocksSinceBaseline`
   (the transient `objectsDirty` flag is consumed per-frame by the dirty dispatcher
   and is unavailable at save time), and adding one is not worth it for tables this
   small. **Re-apply policy:** applying a v2 payload replaces the level's spawn
   lists, then raises the `objectsDirty`/`ringsDirty` flags so the existing
   resync path (`ObjectManager.resyncSpawnList`) fires on the next frame. Reader
   accepts v1 (no spawn fields → spawn tables untouched) and v2; v1 files upgrade on
   next save; quarantine now applies only to versions > 2. The S3K runtime re-apply
   gate (`supportsRuntimeEditApply`) stays as-is — lifting it is S3K-overlay work
   outside mod foundations — but the gate result gains a visible warning line in the
   editor toolbar when edits exist that will not re-apply.

**Spawn-edit safety rule (new production code, not an existing property):** object
spawn lists feed slot cadence, which trace replay depends on. Today editor entry is
gated only on game mode + `EDITOR_ENABLED` — `GameLoop`'s toggle branch and
`Engine.toggleEditorPlaytestMode()` do **not** consult `TraceSessionLauncher.active()`
even though adjacent gates in the same method do, and `EDITOR_ENABLED` +
`debug.testMode.enabled` are independent flags, so editor entry mid-trace is possible
right now. Workstream C therefore **adds the refusal**: the editor toggle path
declines (with a log line) while `TraceSessionLauncher.active() != null`, mirroring
the adjacent trace gates, plus a guard test asserting the refusal. Editor spawn edits
otherwise only ever apply to `MutableLevel` sessions (already true of all editor
edits).

### Non-goals (unchanged from the MVP blueprint, restated)

Pattern/8×8 art painting, block-flag editing on map writes, free camera pan/zoom,
new-zone/blank-slate creation (Phase 2 decides the mod-level authoring entry point),
remappable editor keys, S3K runtime re-apply.

### Deliverables (workstream C)

The three items above with focused unit tests per command (the editor test suite
pattern: `TestEditorCommands` etc.), payload v1→v2 round-trip tests, the trace-guard
test, and a manual authoring smoke: place/move/delete a badnik and a ring in EHZ,
toggle a cell's solidity, save, exit, re-enter, verify persistence and playtest
behavior (badnik spawns, solidity blocks the player).

---

## Sequencing & verification

Workstreams A/B/C are independent; suggested order A → B → C only because A unblocks
the KiS2 content tasks (the framework's first real consumer) while C is pure
editor-team work. Each workstream lands behind the existing test gates: full default
suite, S3K must-keep-green set, and — for A — a fresh `*TraceReplay` spot sweep
(S1 GHZ1, S2 EHZ1, S3K AIZ1) proving patch resolution is a no-op for unpatched
sessions. `docs/status/trace-frontier-log.md` is updated if any sweep is run.

## Open questions

- **KiS2 content timing:** whether KiS2-plan Tasks 1, 5–6, 10–12 execute immediately
  after workstream A (recommended — first consumer validates the framework) is a
  scheduling call, not a design one.
- **Editor object palette UX:** numeric id entry is deliberately minimal; the
  browsable palette lands with Phase 2's library panes. Revisit if authoring smoke
  shows id entry is unusable in practice.
- **`LoadOp.romAddr()` compatibility accessor:** kept in Phase 0 to avoid call-site
  churn; Phase 2 may remove it once ad-hoc S3K art loads migrate to source-typed
  factories.
