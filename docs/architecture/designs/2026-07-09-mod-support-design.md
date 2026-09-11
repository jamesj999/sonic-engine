# OpenGGF Drop-In Mod Support — Design

**Date:** 2026-07-09
**Status:** Approved (brainstorming session)
**Relationship to current priorities:** design-ahead document. It does not displace the
S3K playable vertical-slice priority; phasing below is deliberately independent of the
release-slice work and can start whenever capacity allows.

## Goal

Let players drop a single mod jar into a `mods/` folder and have the engine pick it up:
new levels, level graphics, objects/badniks, characters, music, and sound effects —
plus, eventually, fully standalone games built on the engine. Provide a creator
toolchain (SDK + CLI + the in-engine level editor) that converts modern authoring
formats (PNG, wav/ogg/mp3, editor exports) into the engine's native Mega Drive-style
data at build time.

## Decisions made during brainstorming

1. **Scope — both, phased.** Early phases target *additive* mods layered over a base
   game (S1/S2/S3K; base ROM still required). Later phases add *standalone* mods that
   ship all their own assets and need no ROM.
2. **Code in mods — Java code + data.** Mod jars may contain compiled classes
   implementing engine SDK interfaces (Minecraft-Forge trust model: mods are trusted
   code; the user is warned on enable). Pure data mods (reskins, music packs) need no
   code at all.
3. **Asset conversion — build-time.** The SDK/CLI converts source formats
   (PNG tilesheets/sprite sheets, level exports, audio) into engine-native baked data
   packed in the jar. Conversion errors surface at build time with friendly messages.
   Audio is the exception: wav/ogg/mp3 ship as-is in the jar and are decoded/streamed
   at runtime.
4. **Level tooling — grow the in-engine editor** into the primary mod level tool
   (object placement, collision editing, playtest-in-place, export-to-mod-project).
   External-editor (Tiled) import is a later polish item. The editor MVP is
   acknowledged as unfinished; completing it is explicit phased work (§8), not an
   assumed capability.
5. **Audio — streamed track layer** beside the SMPS driver, behind the same
   `AudioManager` command surface.
6. **Discovery — `mods/` folder + in-game mod manager** (enable/disable, ordering,
   trust prompt, conflict surfacing).
7. **Toolset shape — SDK jar + `ggfmod` CLI + project template.** No GUI studio app
   initially; the CLI and editor cover the workflow.
8. **Engine integration — GameModule/GamePatch seams.** An additive mod contributes a
   `GamePatch` over a base game module, per the approved
   `2026-06-12-game-patch-kis2-design.md` framework; a standalone mod *is* a
   `GameModule`. No parallel event-bus plugin architecture.
   **Important status note:** the GamePatch framework is an approved *design*, not yet
   shipped code, and it deliberately scoped out patch stacking ("one patch per
   session"). Mod support therefore requires (a) implementing that framework and
   (b) extending it with ordered patch composition and enable-state-driven activation.
   Both are scoped as Phase 0 (§8).

## 1. Mod anatomy — what's in the jar

A mod is one jar:

- **`META-INF/openggf-mod.yaml`** — manifest:
  - identity: `id` (lowercase, `[a-z0-9-]+`, globally namespaces everything the mod
    registers), `name`, `version`, `authors`, `description`
  - compatibility: `engineApiVersion` (semver range; see §2 for what it is checked
    against and when)
  - `type: patch | standalone`; `baseGame: s1 | s2 | s3k` when `type: patch`
  - `entrypoint:` optional fully-qualified class implementing `GgfMod`; omitted for
    pure-data mods
  - `dependencies:` other mod ids with version ranges
  - **override maps**: declarative `artOverrides:` (base art key → mod asset path) and
    `audioOverrides:` (base track/SFX ID → mod audio ID). A reskin or music pack is
    manifest + assets, zero code.
- **`assets/`** — engine-native baked data produced by the SDK: 4bpp patterns, palette
  lines, chunks/blocks, level layouts, collision data, sprite mappings/DPLCs,
  animation scripts. Stored raw or jar-deflated — no Kosinski/Nemesis compression is
  used for mod content (those exist only for reading real ROMs).
- **`audio/`** — wav/ogg/mp3 files plus `audio-manifest.yaml`: logical track/SFX IDs,
  sample-accurate loop metadata (intro length + loop region), gain, and whether a
  track participates in tempo effects.
- **compiled classes** (optional) — object/badnik/boss/character/event code written
  against the mod API (§2).

### ROM-only-assets rule interaction

The existing hard rule ("runtime assets must come from the user-supplied ROM") applies
to *base-game* content and remains untouched. Mod-supplied assets are the mod author's
own content and load from the jar through a distinct `ModAssetSource` — never as a
fallback source for base-game data. Base games must behave identically with zero mods
installed.

### Level-resource loading path (engine change required)

Today every `LoadOp` is a ROM-address read (`record LoadOp(int romAddr,
CompressionType, int destOffset)`) and `ResourceLoader` pulls from the ROM reader.
Phase 0 generalizes this with a **load-source abstraction**: a `LoadOp` carries either
a ROM address (base games, unchanged behavior) or a `ModAssetSource` reference (mod
jar path, no decompression). `LevelResourcePlan`/`ResourceLoader` composition —
including overlay copy-on-write — then works identically for both sources. This is how
§3's "new levels ride the existing `LevelResourcePlan` path" is made literally true;
without it, mod level data has no loader.

## 2. Loading & lifecycle

- **`ModRepositoryScanner`** scans `mods/` at startup and parses manifests **without
  loading any mod code**, producing immutable `ModDescriptor`s (id, version, type,
  declared content, whether code is present, dependency edges).
- **Mod manager screen** (reachable from the master title): list, enable/disable,
  reorder, per-mod info. Enabling a mod whose jar contains classes triggers a one-time
  trust prompt: *"This mod contains code and runs with full permissions."* Java cannot
  be meaningfully sandboxed post-SecurityManager removal, so the model is informed
  consent, as in every major Java modding ecosystem. Enable/disable/order state
  persists in `mods/modstate.json` (outside the jars, not in `config.yaml`, so mod
  churn doesn't touch user config).
- **Class loading:** each enabled code mod gets its own `URLClassLoader` whose parent
  is the engine classloader. Consequence of the trust model, stated plainly: mods can
  see and call the entire engine, not just the curated API — the API defines what is
  *supported*, not what is *reachable*. Inter-mod class visibility uses a delegating
  loader that consults the loaders of the mod's *declared* dependencies only;
  undeclared sibling mods are not visible. Entry contract:
  `class MyMod implements GgfMod { void register(ModContext ctx); }`.
- **`ModContext`** is the only supported registration door:
  - `registerGamePatch(GamePatch)` — additive mods contribute `GamePatch` instances
    (the KiS2 framework's own type: `GamePatch.apply(GameModule, PatchContext)`).
    The framework extension in Phase 0 lets `GamePatchRegistry.resolveModule(...)`
    compose an **ordered stack** of patches over the base module. A patch applies to a
    session iff it is **enabled** in the mod manager **and** its **activation
    predicate** accepts the launch request (the KiS2 design's predicate model is kept,
    not replaced — enable-state is an additional gate, and mod-manager order decides
    stacking order among the patches that pass both). KiS2 itself becomes just another
    patch in the stack: enabled by default, still activating only when the requested
    main character is Knuckles.
  - `registerGameModule(...)` — standalone mods provide a full `GameModule` (§3,
    Phase 3; requires the ROM-coupling seam change noted there)
  - asset/audio/object/character registry handles, all namespaced by mod id
- **Surfacing:** additive content appears inside the base game's existing flows
  (extra zones in the level flow, characters on the launch-config screen); standalone
  mods appear as new entries on the master-title game select.
- **Mod API and versioning.** Mods compile against the engine jar. The *supported*
  surface is a curated set of types annotated `@ModApi` (entry/contexts, the abstract
  object base classes, `ObjectServices`, `PhysicsProfile` types, provider interfaces,
  manifest/asset format contracts), documented and published as javadoc. Only
  `@ModApi` types carry a compatibility promise; everything else is internal and may
  break without notice. This deliberately avoids extracting the base classes into a
  separate Maven module — they are too coupled to engine internals to split out, and
  the trust model already grants full classpath visibility. A single engine-published
  **mod API version** constant backs the manifest's `engineApiVersion` check. It
  exists from Phase 1 (initially covering only the manifest + baked-asset + audio
  formats, since no mod code runs yet) and bumps per semver as the code API arrives in
  Phase 2 (proposal: additive-only within a major version, one-minor-release
  deprecation window).

### Failure handling (design-level)

- **Malformed jar / unparseable manifest:** the mod is skipped for loading but still
  listed in the mod manager with an error badge and the parse error; never a crash.
- **Missing or version-incompatible dependency:** the dependent mod cannot be enabled;
  the manager shows which requirement failed. Disabling a mod that others depend on
  cascades a disable prompt.
- **Dependency cycles:** all mods in the cycle are refused with the cycle listed.
- **`engineApiVersion` mismatch:** the mod cannot be enabled; the manager shows the
  required vs available API version. No warn-and-load.
- **Mod code throws during `register()` or later lifecycle calls:** the mod is
  disabled for the session, the error is surfaced (manager badge + log with mod id and
  stack trace), and the engine continues without it. A mod must never take the engine
  down at startup.
- **Reordering vs dependencies:** the manager enforces topological consistency —
  a mod is always ordered after its declared dependencies; the user reorders freely
  within that constraint.

## 3. Content types and their seams

| Content | Engine seam | Creator supplies |
|---|---|---|
| New level/zone | zone + level-resource providers on the patch, via the `LevelResourcePlan` / `ResourceLoader` path with the Phase 0 load-source abstraction (§1) | baked layout/chunks/collision from the editor export, tileset PNGs, music mapping, optional event-handler class (default no-op events otherwise) |
| Level graphics | baked patterns/chunks/palettes via `ModAssetSource` | PNG tilesheets + palette definition; SDK enforces 16-color palette lines and 8×8 alignment |
| New object/badnik | `AbstractObjectInstance` / `AbstractBadnikInstance` subclasses registered under mod-namespaced IDs (`mymod:buzzer2`) in the game's object registry | Java class + PNG sprite sheet (SDK bakes mappings/DPLCs) |
| New character | `PhysicsProfile` + moveset hooks + launch-config roster entry | profile constants, art sheets, optional ability code — heaviest lift, phased last |
| Music/SFX | streamed track layer (§4) | wav/ogg/mp3 + audio manifest |
| Replacements | manifest override maps, no code | assets only |

Mod objects flow through the normal object pipeline: `ObjectServices` injection at
construction, touch responses, solid contacts, `DestructionEffects`, the object
behavior contracts (`ObjectControlState`, `ObjectLifetimeOps`, ...), and rewind
capture. There is no parallel "mod object" runtime — a mod badnik is a badnik.

**Rewind:** at *runtime*, mod objects are covered by the same reflection-based
machinery (`GenericFieldCapturer`, compact schema when codecs cover the fields) with
no per-mod work. *Build-time* coverage validation in `ggfmod validate` is new tooling:
the existing coverage analyzers are source-tree scanners hardcoded to the engine repo
(`ObjectClasspathScan` package roots, source-regex supertype resolution) and cannot be
pointed at a mod project as-is. Phase 2 includes a reflection/bytecode-based coverage
checker that generalizes the analyzers' *rules* (recreate path exists, no uncaptured
`final` scalars, object refs captured as rewind ids) to compiled mod classes. Until it
exists, mod rewind gaps surface at runtime only.

**Standalone mods and ROM coupling (Phase 3 seam change, not free):**
`GameModule.createGame(Rom)` and `createTouchResponseTable(RomByteReader)` assume a
ROM, and the boot path requires one today. Standalone support needs an explicit seam
change — e.g. a `GameDataSource` abstraction over "ROM bytes" vs "mod assets" at the
module boundary, plus a boot path that skips ROM detection for standalone entries.
This is flagged here so Phase 3 estimates include it.

## 4. Audio: streamed track layer

- **`StreamedAudioBackend`** sits beside the SMPS driver behind the same
  `AudioManager` command surface — play/stop/fade by track ID. Gameplay code is
  backend-agnostic: a **track registry** resolves IDs at registration time, mapping
  existing int/enum music IDs and new mod-namespaced string IDs (`mymod:music/zone1`)
  to either SMPS data or a stream. Override maps (§1) rebind an existing ID to a
  stream; new zone content references its own IDs directly.
- **Codecs:** ogg via stb_vorbis (already available through LWJGL), wav via
  `javax.sound.sampled`, mp3 via a to-be-chosen decoder (license check is an open
  question below).
- **Tempo/priority semantics mapped onto streams:** speed shoes → rate/pitch shift;
  jingles (invincibility, drowning, 1-up) duck/pause and resume the music stream with
  the same priority rules the SMPS path applies; underwater filtering is optional
  polish (simple low-pass), not required initially.
- **Loop points:** sample-accurate intro + loop region from the audio manifest, so
  looping zone music behaves like SMPS loops rather than whole-file restarts.
- **Rewind:** streamed playback is **excluded from the `AudioCommand` rewind
  timeline** (`AudioManager`'s record/replay path stays SMPS-only). Streams instead
  follow a simple pause-on-rewind-entry / resume-in-place-on-exit model, and a
  backward seek re-issues only the *current* stream state (which track, position
  coarse to the last keyframe) rather than replaying command history. Streamed SFX are
  suppressed during rewind. This is deliberately simpler than SMPS rewind and is an
  accepted, documented divergence for mod audio.
- Mods may also reference built-in SMPS tracks by their existing IDs (e.g. a new zone
  reusing an S2 track). Authoring *new* SMPS/VGM chip music is out of scope initially
  but the track-registry design leaves room for it.

## 5. SDK + CLI toolchain (`ggfmod`)

Two published artifacts plus the engine's annotated API surface: the engine jar
(compile-time dependency; `@ModApi` marks the supported surface, §2),
**`openggf-mod-sdk`** (converter/packager libraries), and the **`ggfmod`** CLI:

- `ggfmod init` — scaffold a Maven project from a template: manifest, sample level,
  sample badnik, character stub, build wiring that calls the converters.
- `ggfmod convert art` — PNG → patterns/palettes/mappings with a quantization report;
  hard errors with actionable messages (>16 colors in a palette line, dimensions not a
  multiple of 8, sheet layout mismatches).
- `ggfmod convert level` — consumes the in-engine editor's export envelope →
  chunks/blocks/layout/collision. (Tiled `.tmx` import is a later addition behind the
  same command.)
- `ggfmod convert audio` — validates/normalizes audio files, verifies loop metadata.
- `ggfmod validate` — manifest schema, asset integrity, ID-collision, API-version, and
  (once the Phase 2 checker exists, §3) rewind-coverage checks.
- `ggfmod package` — assembles the jar.
- `ggfmod run` — launches the engine in **dev mode** with the mod loaded from the
  project's build output (`classes/` + resources) instead of a packed jar, for a fast
  edit → run loop. Dev mode is an explicit opt-in flag and is exempt from the
  trace/test force-disable in §7 (it is the one sanctioned way to run unpacked mods).

Converters reuse existing engine code where it exists (pattern encoding, palette
handling) and live in the engine repo so formats can't drift.

## 6. Level workflow (in-engine editor growth)

The editor MVP (`com.openggf.editor`) currently covers chunk painting, undo/redo, and
the save envelope — it is *not* yet a level-authoring tool. Phase 0 (§8) completes the
foundations and Phase 2 adds the mod-facing pieces:

- object-placement palette (spawn list editing, subtype parameters) — *Phase 0*
- collision-path editing — *Phase 0*
- export envelope hardening — *Phase 0*
- playtest-in-place (the editor's teardown/rebuild mode swap already provides this) —
  *exists today*
- chunk/block library panel sourced from the active mod's tileset — *Phase 2*
- **"export to mod project"**: writes the editor save envelope into the mod project's
  source tree, where `ggfmod convert level` picks it up — *Phase 2*

Creator loop: paint in-engine with real physics → playtest instantly → export →
`ggfmod package` (or keep iterating under `ggfmod run` dev mode).

## 7. Namespacing, compatibility, safety

- Every registry key a mod creates is mod-id-prefixed (`mymod:objects/buzzer2`,
  `mymod:art/tileset-main`, `mymod:music/zone1`). Numeric object IDs used by level
  spawn data are allocated per-mod at load and mapped through the namespaced key, so
  two mods never collide on raw IDs.
- **Virtual pattern IDs:** each enabled mod is allocated a block from a reserved mod
  range above the existing category bases in `PatternAtlas` (existing bases top out at
  `0x100000`; the sparse-map tier makes large ranges cheap; exact base/budget sized
  during Phase 1 implementation).
- **Conflicts:** load order resolves override conflicts (later wins); the mod manager
  surfaces any two mods overriding the same base key.
- **Trace/test hygiene:** mods are force-disabled during trace replay and in headless
  test runs (the latter trivially, since headless harnesses never run the mod scan).
  `ggfmod run` dev mode is the explicit exemption (§5); running the trace picker /
  test mode with mods loaded is unsupported.
- **Saves:** standalone-mod saves are namespaced per mod id in `SaveManager`; additive
  mods that add zones extend the base game's slot metadata in a forward-compatible
  way (achievable today: quarantine triggers on wrong-game/hash/payload-missing, not
  on unknown zone values).

## 8. Phasing

Ordering note (per review): "independently shippable" means each phase delivers
standalone user value when it lands, not that phases can be built in any order.
Phase 0 is a hard prerequisite for **Phase 2** only; **Phase 1 has no Phase 0
dependency** (music packs need the scanner, manager UI, streamed backend, and track
registry — none of the patch/loader/editor foundations) and may proceed in parallel
with Phase 0.

0. **Phase 0 — engine foundations (prerequisite for Phase 2).** Completes the two
   approved-but-unfinished features mod support stands on, plus the loader seam:
   - implement the **GamePatch framework** from the KiS2 design, *extended* with
     ordered patch composition and enable-state activation (§2) — supersedes that
     design's "one patch per session" non-goal, which was YAGNI at the time
   - the **load-source abstraction** for `LoadOp`/`ResourceLoader` (§1)
   - **editor completion**: bring the MVP to a usable level-authoring baseline
     (object placement, collision editing, export envelope hardening) so §6 builds on
     something real
1. **Phase 1 — loader + music packs.** Manifest schema + published format version,
   `ModRepositoryScanner`, mod manager UI with failure handling (§2),
   `StreamedAudioBackend` + track registry, `audioOverrides` maps, trace-mode
   force-disable. Ships user-visible value (music packs) with no mod-code surface at
   all. Art reskins are *not* in Phase 1 — baked art requires the Phase 2 converter.
2. **Phase 2 — additive content mods.** `@ModApi` surface + classloading + trust
   prompt, `ggfmod` CLI with art/level/audio converters (art reskins become possible
   here), object/badnik modding, the reflection-based rewind-coverage checker (§3),
   editor mod-facing features + export (§6), and a new zone in an existing game as the
   flagship sample.
3. **Phase 3 — characters + standalone games.** Character roster/profile/moveset
   seams; `registerGameModule` for no-ROM total conversions, including the
   `GameDataSource` ROM-decoupling seam change (§3).
4. **Phase 4 — polish.** Tiled import, docs site + sample-mod gallery, possible GUI
   studio built on the same SDK libraries.

## Open questions

- **mp3 decoder licensing** — JLayer's license status vs alternatives; worst case,
  mp3 support is dropped in favor of wav/ogg only (ogg covers the use case).
- **Pattern-ID budget per mod** — sized during Phase 1 implementation against
  `PatternAtlas` reserved ranges.
- **Phase 0 scope for the editor** — "usable level-authoring baseline" needs its own
  scoping pass against the editor's current state when Phase 0 is planned.
- **Scheduling** — where Phase 0/1 land relative to the S3K release slice is a
  prioritization call outside this design.
