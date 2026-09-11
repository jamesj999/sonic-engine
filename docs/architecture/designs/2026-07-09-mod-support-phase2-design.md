# Mod Support Phase 2 — Additive Content Mods Design

**Date:** 2026-07-09
**Status:** Approved (brainstorming session)
**Parent:** `2026-07-09-mod-support-design.md` §8 Phase 2. Siblings: Phase 0 spec
(`2026-07-09-mod-support-phase0-design.md` — GamePatch composition,
`LoadSource`, editor baseline) and the Phase 1 plan
(`2026-07-09-mod-support-phase1.md` — `ModCatalog`, manager UI, streamed audio).
**Depends on:** Phase 0 (all three workstreams) AND Phase 1 (loader/catalog/manager).
Recon date for all code claims: 2026-07-09.

## Goal

Mods stop being data-only: a mod jar may ship compiled Java (new
objects/badniks, a `GamePatch`), baked art (reskins and new tilesets), and a
complete **new zone in an existing game** — the flagship deliverable. A
`ggfmod` toolchain converts creator formats (PNG, editor exports) into the
baked assets the engine loads, and validates mod jars against the contracts
the engine's own guards enforce on first-party code.

## Components

### A. Mod code loading (`GgfMod` + `ModContext` + classloader)

- Each enabled code mod gets a classloader whose parent is the engine
  classloader, created alongside `ModCatalog` in `Engine.init()` (the Phase 1
  wiring point). Per parent §2, **inter-mod visibility uses a delegating
  loader**: a mod's loader consults the loaders of its *declared*
  dependencies (Phase 1's manifest `dependencies`, already
  topologically ordered by the catalog) before failing a lookup; undeclared
  sibling mods stay invisible. Phase 1's `ModEligibility` refusal
  `"mod code is not supported yet"` is replaced by the trust gate (§F).
- Entry contract (parent spec §2): `class MyMod implements GgfMod
  { void register(ModContext ctx); }`, `entrypoint` named in the manifest.
- `ModContext` registration surface (all namespaced by mod id):
  - `registerGamePatch(GamePatch patch)` — feeds the Phase 0
    `GamePatchRegistry`; the mod-backed `PatchEnablement` implementation
    (Phase 0 Amendment 2's pinned contract) reports mod patches per catalog
    enable-state/order and leaves built-ins unmanaged.
  - `registerObject(String key, int requestedId, ObjectFactory factory)` and
    `registerObjectArt(String key, BakedSheetRef sheet)` — consumed by the
    mod's own `GamePatch` when it decorates the base module's
    `ObjectRegistry`/`ObjectArtProvider` (§B/§C). Registration outside a
    patch is invalid — mod content always scopes to a base game via a patch.
  - `modAssets()` — a `ModAssetSource` root for the mod's own jar
    (Phase 0 §B `LoadSource.ModAsset` factory access).
- **Failure handling** (extends parent §2): `register()` throwing disables
  the mod for the session with a manager badge; a mod registering content
  without a patch, or against a base game that doesn't match its manifest
  `baseGame`, is refused at registration with a logged reason.

### B. Object/badnik modding

- Mod object classes extend the existing base classes
  (`AbstractObjectInstance` / `AbstractBadnikInstance`) and receive
  `ObjectServices` through the normal ThreadLocal construction path — no
  parallel object runtime (parent spec §3).
- **Id model (recon-corrected):** `ObjectSpawn` masks `objectId & 0xFF` —
  object ids are hard 8-bit, and the parent spec's "allocated per-mod at
  load" must operate inside that space. Design:
  - Mod-supplied spawn data (mod zones, §D) references objects by
    **namespaced string key** (`mymod:buzzer2`).
  - At session load, the patch's decorated `ObjectRegistry` allocates each
    key a free byte id from the base game's unused range (computed from the
    stock registry's registered ids), deterministically ordered
    (mod order, then key order) so the same enabled-mod set always yields
    the same ids — rewind snapshots and respawn tables stay stable within a
    session and across identical sessions.
  - `requestedId` in `registerObject` is a hint honored when free (lets a
    mod pin ids for its own cross-references); conflicts fall back to
    allocation. Exhaustion (no free bytes) blocks the mod at load with a
    manager badge.
  - **Not in Phase 2:** injecting mod objects into *stock* zone layouts
    (that's layout mutation of base levels; revisit with demand).
- **Rewind (two engine fixes + one rule):**
  1. `ObjectRewindDynamicCodecs.genericRecreate` resolves the snapshot's
     class-name string via a loader-less `Class.forName` — mod classes from
     a child loader fail to restore. Fix: a `ModClassResolver` consulted
     first (name → `Class` map populated at `register()` time from each
     mod's loader), falling back to `Class.forName` for engine classes.
  2. Registry-path recreation (`ctx.objectRegistry().create(spawn)`) works
     for mod objects iff the allocation in the id model above is
     deterministic — which it is by construction; a test asserts a mod
     object survives a snapshot/restore round-trip.
  3. Rule: mod objects wanting bespoke dynamic-recreate implement
     `RewindRecreatable` exactly like first-party objects; `ggfmod validate`
     (§E) checks the three coverage rules the engine's source-scan guards
     cannot see (they are `src/main`-only and structurally blind to mod
     jars — recon confirmed all five guards).

### C. Art pipeline: baked sheets, reskins, pattern budget

- **Baked sheet format (SDK output, engine input):** a versioned container
  (one file per sheet in the mod jar) holding: pattern data (N × 32-byte
  4bpp Sega-format tiles — `Pattern.fromSegaFormat`'s exact layout: 8 rows ×
  4 bytes, high nibble = left pixel), mapping frames (per frame: piece list
  with `xOffset, yOffset, widthTiles, heightTiles, tileIndex, hFlip, vFlip,
  paletteIndex, priority` — the `SpriteMappingPiece` component set), palette
  line index, frame delay, and optionally a 16-color palette line. The
  engine-side reader materializes an `ObjectSpriteSheet` directly; the SDK
  writes the container from PNG + a small YAML sheet manifest
  (frame boxes / piece layout). No ROM byte formats in the container —
  `SpriteMappingPiece` fields are the schema, serialized plainly.
- **Reskins (`artOverrides`, deferred from Phase 1):** manifest maps base
  art keys (`Sonic2ObjectArtKeys` strings and their S1/S3K equivalents) to
  mod sheet assets; the patch's decorated `ObjectArtProvider` swaps the
  sheet at `loadArtForZone` time. Later mods win (parent §7). A reskin-only
  mod is manifest + baked sheets, zero code — it synthesizes an implicit
  data-only patch (no entrypoint needed), keeping Phase 1's no-code story
  for pure reskins.
- **Pattern-ID budget (closes the parent's open question):** each enabled
  mod is allocated one `0x8000`-pattern window starting at `0x108000`
  (first free space above `SEGA_BOOT_LOGOS`, which ends there), in
  enabled-order, registered via `PatternAtlas.registerRange(base, size,
  "mod:" + id)`. **Engine change required (recon):**
  `validatePatternIdGovernance` accepts only ids inside
  `PatternAtlasRange.forPatternId(...)` — the *enum* constants — so
  dynamically registered ranges are rejected at render time today.
  Governance must additionally consult the dynamically registered ranges,
  and `registerRange(int, int, String)` must enforce the same
  block-alignment invariant the enum tier gets (dynamic ranges live in the
  sparse-fallback storage tier, which is fine for mod volumes). Mods
  needing more than one window declare it in the manifest; the manager
  surfaces total budget use.

### D. New zone in an existing game (flagship)

Recon identified exactly two structural blockers plus a seam list; the design
addresses each:

1. **`LevelData` enum is compile-time.** Zone enumeration moves behind a
   small interface: `LevelDescriptor` (levelIndex, startX, startY) with the
   enum constants adapting to it (a default `LevelData implements
   LevelDescriptor` retrofit — zero behavior change for stock zones), and
   `ZoneRegistry`/`AbstractZoneRegistry` typed against the interface. Mod
   zones provide synthetic descriptors with **synthetic level indices from a
   reserved band** (0x400+, far above every per-game enum band) so nothing
   collides with ROM-derived indices.
2. **`Sonic2.loadLevel` resolves every data address from ROM directories by
   zone id.** Mod zones never enter that path: the mod's `GamePatch`
   decorates the module so that for synthetic level indices, `loadLevel`
   routes to a **`ModZoneLoader`**, and `getMusicId(levelIdx)` (which
   otherwise reads garbage from `LEVEL_SELECT_ADDR` for synthetic indices)
   likewise resolves from the definition. The loader builds the level with
   a `LoadSource.ModAsset` plan (patterns/chunks/blocks/collision — the
   four plan-shaped kinds, Phase 0 §B) and a **`ModLevelDefinition`**
   asset: layout map, object/ring spawn lists (object entries by
   namespaced key, resolved through §B's allocator), 4×16-color palettes,
   solid-tile collision data, boundaries, start positions, music id (stock
   SMPS id or a Phase 1 streamed track id), zone display name.
   **Engine change required (recon):** the existing plan constructor
   (`Sonic2Level(rom, zone, characterPaletteAddr, levelPalettesAddr, …,
   LevelResourcePlan, mapAddr, solidTileHeightsAddr, …)`) still takes the
   layout map, palettes, and solid-tile data as **ROM address ints** —
   exactly the seams Phase 0 §B deferred. Phase 2 adds a constructor
   overload (or builder) accepting in-memory layout/palette/solid-tile
   data alongside the plan, reusing the existing decode logic; this is the
   promised "provider-level overrides" realization, confined to
   `Sonic2Level` (S3K mod zones follow the same shape when demanded).
3. **Progression: append-only indices + a successor redirect.** Zone
   identity in S2's events, scroll handlers, and save slots IS the
   progression list index (`Sonic2LevelEventManager`/
   `Sonic2ScrollHandlerProvider` switch on `ZONE_EHZ=0..ZONE_DEZ=10`;
   `S2SaveSnapshotProvider` persists the index), so **splicing a mod zone
   into the stock order is unsafe** — every downstream stock zone would
   inherit its neighbor's events and existing saves would silently retarget.
   Therefore:
   - Mod zones are **appended at stable indices ≥ the stock count**
     (S2: 11+). Stock indices never move; stock saves are untouched; the
     index-keyed event/scroll switches see an out-of-range index for mod
     zones and fall to their graceful defaults (recon-verified `default ->
     null` / 1:1 scroll), exactly like the synthetic-ROM-id story in item 4
     covers the ROM-id-keyed paths.
   - **Reachability via a successor redirect (engine change):**
     `advanceToNextLevel()` gains a `ZoneProgressionPlan` seam — default
     implementation is today's linear list order, bit-identical. A patch
     overrides successors, e.g. "MTZ results → mod zone 11; mod zone 11
     results → SCZ (8)". Redirects are only valid at **results-driven**
     boundaries (S2: EHZ..MTZ; the SCZ→WFZ→DEZ tail is event/cutscene
     chained and DEZ's boss requests credits directly — recon); the
     manifest declares `insertAfter: <stock zone>` and a redirect after an
     event-chained zone is refused at load with a manager badge. Default:
     after the last results-driven stock zone.
   - Data-select support is NOT automatic (recon: `S2DataSelectProfile`
     hardcodes an 11-entry `CLEAR_RESTARTS` list with bounds checks, and
     the S2 preview-image cache uses fixed zone-key sets). Phase 2 extends
     the S2 data-select host profile (module-owned, so the patch can
     decorate it) to consult the patched registry **beyond the stock
     count** — which is exactly where mod-zone saves land under
     append-only indices: mod-zone slots get a **generic mod-zone preview
     tile** and a registry-driven restart destination. A slot saved in a
     mod zone and later loaded with that mod disabled follows the parent
     §7 rule (preserved, not quarantined) and restarts at zone 0 with a
     logged notice.
4. **Synthetic ROM zone id:** mod zones carry a synthetic `zoneIndex` from
   a reserved band (0x40+) used only by the graceful per-zone switches
   (palette cycler, event manager, boss art, resource plans — all default
   to no-op/null for unknown ids, recon-verified). The known non-graceful
   consumer is `TitleCardManager`'s hardcoded `ZONE_NAMES` array (a
   duplicate of the registry's name list, with a wrong-name
   `ZONE_NAMES[0]` fallback for out-of-range zones); it gains a
   registry-driven fallback (name from `ZoneRegistry.getZoneName`), which
   also removes the existing fallback bug. (Further hardcoded name copies
   exist in the debug-only level-select screens; those are out of scope —
   mod zones simply don't appear there in Phase 2.)
5. **Minimal viable zone is genuinely minimal:** static palette, no
   animation, no events, no water — every runtime framework
   (`ZoneRuntimeRegistry`, `PaletteOwnershipRegistry`,
   `AnimatedTileChannelGraph`, palette cycler, event manager) defaults to
   no-op for unregistered zones (recon-verified). Ambitious mod zones
   register real handlers through their patch code.
6. **Trace/rewind:** `TraceCatalog`'s S2/S3K zone mapping is pass-through
   (inert for mod zones); `ZoneRuntimeSnapshot` identity validation only
   engages if the mod installs a `ZoneRuntimeState`, which must then report
   its stable synthetic `zoneIndex()`.

### E. `ggfmod` SDK/CLI

- **Location — declared amendment of parent §5:** the parent named a
  separate `openggf-mod-sdk` artifact; Phase 2 supersedes that with package
  `com.openggf.tools.modsdk` inside the engine module, following the
  `tools` CLI conventions (`main()` + pure-logic split). Rationale: mods
  already compile against the engine jar (parent §2's `@ModApi` decision),
  so the SDK shipping in the same jar adds no new dependency surface for
  mod authors; a separate Maven module is deliberately rejected for now
  (single-module repo; revisit if a GL-free toolchain jar is demanded).
  CLI entry `GgfModCli` dispatching subcommands; invoked
  `java -cp <engine-jar> com.openggf.tools.modsdk.GgfModCli <cmd>` (a thin
  `ggfmod` script ships in `docs/modding/`).
- Subcommands (parent §5, made concrete):
  - `init` — scaffold a Maven mod project (manifest, sample badnik using
    `ObjectScaffoldTool`'s generator style, sample sheet manifest, build
    wiring).
  - `convert art` — PNG + sheet manifest → baked sheet container (§C);
    quantization report; hard errors: >16 colors/line, non-8×8-multiple
    dimensions, piece boxes outside the image.
  - `convert level` — editor full-level export (§G) → plan assets
    (pattern/chunk/block/collision binaries) + `ModLevelDefinition`.
  - `convert audio` — Phase 1's audio validation (loop metadata, wav/ogg).
  - `validate` — manifest schema, asset integrity, id-range and pattern
    budget checks, entrypoint presence, and the checks the engine's
    source-scan guards can't do on mods: object classes extend the base
    classes and the three rewind coverage rules' structural halves
    (recreate path exists, `final` scalar inventory, object-ref field
    inventory) via **reflection** over the mod jar on an isolated loader —
    plus the method-body checks (`services()` calls in constructors,
    ref-field capture usage) via **bytecode analysis**. **Declared new
    dependency:** `org.ow2.asm:asm` (compile scope, small and stable) —
    reflection cannot see method bodies and the engine's own guard for this
    is a source scanner with nothing to reuse.
  - `package` — assemble the jar.
  - `run` — dev-mode launch (Phase 1's flag) with the mod from build output.

### F. Trust gate

- Enabling a mod with `containsCode` requires a one-time trust grant: the
  manager's existing two-press arm/commit idiom (Phase 1's cascade-confirm
  pattern) shows *"This mod contains code and runs with full permissions"*
  on first press; second press grants. The grant persists on
  `ModState.Entry` as two added fields: `trusted` (boolean) and
  `trustedJarSha256` (the jar's content hash recorded at grant time); a
  hash mismatch at scan time revokes the grant and re-prompts (hashing
  happens once per scan, not per frame). Format compatibility is a
  non-issue by construction: Phase 1's `ModStateStore` parses
  `modstate.json` into a plain `Map` (not a POJO), so added fields are
  tolerated in both directions. Untrusted code mods stay disabled with a
  badge. Data-only mods are unaffected.

### G. Editor mod-facing features (the Phase 2 half of parent §6)

- **Chunk/block library pane** sourced from the active mod's tileset
  (browsable palette — the piece deferred from Phase 0), plus the browsable
  object palette for spawn placement (Phase 0 ships numeric-id entry; this
  upgrades it, listing stock ids + the mod's namespaced keys).
- **Full-level export** ("export to mod project"): unlike Phase 0's
  delta-envelope, serializes the complete level from `MutableLevel` —
  patterns, chunks, blocks, map, solid tiles + chunk collision indices,
  palettes, spawns, boundaries, start position — into the mod project's
  source tree where `ggfmod convert level` bakes it.
- **Envelope spawn entries for mod objects persist the namespaced key, not
  the byte id.** Phase 0's v2 envelope stores spawns by numeric id, which
  is stable for stock objects but drifts for mod objects whenever the
  enabled-mod set (or the stock id census) changes between authoring
  sessions. Phase 2 extends the v2 `ObjectSpawnState` with an optional
  `objectKey` field: the editor writes the key for any id inside the
  mod-allocated range, and envelope apply resolves keys through the §B
  allocator (a key whose mod is absent skips that spawn with a logged
  count). Stock-object entries are unchanged; v2 files without the field
  read as before. **Compatibility pin (amends Phase 0 §C.3):** the
  envelope's spawn-entry parsing must be map-shaped and ignore unknown
  fields (the `ModStateStore` idiom), so a Phase 0-vintage reader tolerates
  Phase 2 envelopes; the version stays 2, no quarantine-threshold change. This is also the
  new-zone authoring entry point: start from a snapshot of any loaded stock
  zone (`MutableLevel.snapshot`), edit freely, export as a NEW zone (the
  export never contains ROM-copyright-relevant data beyond what the creator
  chose to keep — the docs make the licensing implication explicit: shipping
  a lightly-edited stock zone ships Sega's level data, and mod authors are
  responsible for their content).
- **`@ModApi` surface:** annotation `com.openggf.game.ModApi` (CLASS
  retention) applied to the curated set — `GgfMod`, `ModContext`,
  `GamePatch` + `PatchContext` + `GameplayLaunchRequest`, `ObjectServices`,
  `AbstractObjectInstance`, `AbstractBadnikInstance`, `ObjectSpawn`,
  `ObjectControlState`, `ObjectLifetimeOps`, `ObjectPlayerQuery`,
  `PhysicsProfile`, the `level.objects` utility helpers, the baked-sheet
  reader types, `ModLevelDefinition`. (The set spans four packages —
  recon-confirmed — the annotation, not a package, defines the surface.)
  A signature-snapshot guard test freezes the annotated types' public
  surface (additions allowed, removals/changes fail); the mod API version
  constant bumps to major 2.

## Explicitly out of scope for Phase 2

Streamed SFX overrides (Phase 1 follow-on), characters and standalone games
(Phase 3), Tiled import and docs site (Phase 4), mod objects in stock zone
layouts, S1-based mod zones (S1 bypasses the plan seam entirely — Phase 0 §B
scope note), lifting the editor's S3K runtime re-apply gate.

## Verification strategy

- Unit: id allocator determinism; baked-sheet round-trip (SDK write → engine
  read → `ObjectSpriteSheet` equality); `ModClassResolver` recreate
  round-trip with a test jar + child loader; `LevelDescriptor` retrofit
  (stock zones bit-identical); synthetic-index routing.
- Integration: a **sample mod** built by `ggfmod init` in CI style — one
  badnik + one minimal zone appended to S2 — loaded headless, zone
  playable-loads, badnik spawns, snapshot/restore round-trips.
- Regression gates: full suite, S3K must-keep-green, trace spot sweep with
  the sample mod force-disabled (mods-off behavior bit-identical), and one
  sweep with it enabled but its zone unvisited (stock zones unaffected).

## Open questions

- **Object-id headroom per game:** S2 already occupies most of 0x00–0xDC;
  the free-byte census per game happens at implementation. If headroom is
  tighter than ~20 ids for any game, widening `ObjectSpawn.objectId` past
  8 bits (mod-spawn path only) becomes a Phase 2.5 follow-up.
- **`TitleCardManager` letter art for mod zone names:** stock title cards
  compose from ROM letter art; a mod zone name may need character coverage
  the ROM set lacks. Fallback: skip the title card for mod zones (config
  default) until mod-supplied title art is specced.
