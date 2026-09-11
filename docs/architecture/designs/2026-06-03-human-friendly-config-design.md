# Human-Friendly Configuration Design

**Date:** 2026-06-03
**Status:** Approved design, ready for implementation planning
**Topic:** Replace the flat, undocumented, unordered `config.json` with a grouped, commented, ordered `config.yaml` projected from an enriched `SonicConfiguration` enum.

## Problem

`config.json` is a flat `Map<String, Object>` keyed by `SonicConfiguration` enum names (~95 keys). It is hard to live with for four distinct reasons:

1. **No grouping.** All 95 keys share one namespace; `P2_DOWN` sits next to `DAC_INTERPOLATE`.
2. **No ordering.** JSON objects are unordered and Jackson serializes the backing `HashMap` in hash order, so every `saveConfig()` reshuffles the file.
3. **No inline documentation.** JSON forbids comments, yet `SonicConfiguration` already carries excellent per-key Javadoc that never reaches the user editing the file.
4. **No discoverability/validation.** Nothing tells a user which values are legal (`DISPLAY_ASPECT` presets, GLFW key names, `s1`/`s2`/`s3k`), and a mistyped key is silently ignored.

## Goals

- Group keys into human-readable sections on disk.
- Guarantee deterministic ordering across saves.
- Surface the existing per-key documentation as inline comments.
- Warn on unknown keys and illegal enumerated values instead of failing silently.
- Do all of the above with **minimal blast radius** on the ~existing read path and callers.

## Non-Goals

- In-engine settings UI (future work; noted, not built here).
- Reworking what each setting *does* or its defaults.
- Per-profile / multi-file config, includes, or env-var overrides.

## Resolved Decisions

- **Window/pixel dimensions (was an open question).** The engine does not support arbitrary viewport sizes; widescreen is driven by `DISPLAY_ASPECT` profiles + `DISPLAY_WINDOW_AUTOSIZE`. Therefore:
  - `SCREEN_WIDTH_PIXELS` / `SCREEN_HEIGHT_PIXELS` → **DERIVED** (`persisted=false`), never on disk.
  - `SCREEN_WIDTH` / `SCREEN_HEIGHT` / `SCALE` → **deprecated**, demoted from normal `display` into `debug.window`. They remain functional (used verbatim only when `DISPLAY_WINDOW_AUTOSIZE=false`) so no behavior changes, but they are clearly marked legacy.
- **One file, top-level `debug:` block** (no separate file, no master gate flag). Presentation-only split; debug keys are still always read.

## Chosen Approach

**The `SonicConfiguration` enum becomes the single source of truth; the on-disk file becomes a projection of it.** The file format moves from flat JSON to **nested YAML** with generated section headers and per-key comments.

Reading uses `jackson-dataformat-yaml`, which is in the same `ObjectMapper` family already used today — but it is **not yet a dependency** (`pom.xml` currently declares only `jackson-databind`). Implementation must add `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml` (it transitively pulls SnakeYAML). See the Dependencies & Native Image section below for the GraalVM reflection/resource implications. Writing uses a small **custom emitter** (Jackson/SnakeYAML cannot cleanly emit the comments we want), driven entirely by enum metadata.

Critically, **the read path stays almost untouched.** Nested YAML is parsed and immediately *flattened* back into the existing enum-name-keyed `Map<String, Object>` (`config`). Therefore `getConfigValue()`, `getInt()`/`getShort()`/`getString()`/`getDouble()`/`getBoolean()`, the `transientResolved` display overlay, `applyDefaults()`, and every caller continue to work unchanged. Nesting is purely a disk-serialization concern.

### Example on-disk file (`config.yaml`)

Normal player-facing settings sit at the top; **all debug/developer settings are fenced into a single top-level `debug:` block at the bottom.**

```yaml
# ── Display ──
# (SCREEN_WIDTH_PIXELS / SCREEN_HEIGHT_PIXELS are DERIVED from `aspect` and never appear here)
display:
  aspect: NATIVE_4_3        # Display aspect preset (NATIVE_4_3, WIDE_16_10, WIDE_16_9, ULTRA_21_9, SUPER_32_9)
  windowAutosize: true      # Derive the window from the aspect preset at the 2x baseline
  deadzoneMode: PROPORTIONAL  # Camera horizontal deadzone behaviour on wide screens
  colorProfile: RAW_RGB     # Display-only color profile
  colorProfileToggleKey: V  # Runtime key to cycle the color profile
  fps: 60                   # Frames per second to render

# ── Input · Player 1 ──
input:
  player1:
    up: UP                  # Key to look up
    down: DOWN              # Key to crouch/roll
    left: LEFT              # Key to move left
    right: RIGHT            # Key to move right
    jump: SPACE             # Key to jump
  player2:
    up: I
    jump: RIGHT_SHIFT
  pause: ENTER              # Toggle pause

# ── Audio ──
audio:
  enabled: true             # Music + SFX
  region: NTSC              # NTSC/PAL audio timing

# ... display / input / audio / characters / roms / startup / rewind / crossGame / discord ...

# ═══════════════════════════════════════
#  DEBUG  (developer tooling — safe to ignore for normal play)
# ═══════════════════════════════════════
debug:
  flags:
    debugView: true         # On-screen debug HUD
    editor: false           # Allow entering the level editor from gameplay
    collisionView: false    # Draw collision overlay
  keys:
    debugMode: D            # Toggle debug movement mode
    nextAct: PAGE_UP
    giveEmeralds: E
    frameStep: Q            # Step one frame while paused
  startup:
    levelSelectOnStartup: false   # Open Level Select instead of loading the first zone
    s3kSkipIntros: false          # Skip AIZ biplane etc.
  playback:
    moviePath: ""           # BizHawk BK2 movie for playback debugging
    toggleKey: B
  traceRewind:
    key: R                  # Held in Trace Test Mode to rewind deterministic state
  testMode:
    enabled: false          # Master title becomes the trace picker (dev-only)
    catalogDir: src/test/resources/traces
  crossGame:
    s1DataSelectImageGenOverride: false   # Force-regen S1 data-select image cache
    s2DataSelectImageGenOverride: false   # Force-regen S2 data-select image cache
    s1DataSelectImageCoordLogKey: APOSTROPHE
  window:                   # DEPRECATED — manual window/scale; prefer display.aspect + windowAutosize
    width: 640              # SCREEN_WIDTH  (legacy; used verbatim only when windowAutosize=false)
    height: 448             # SCREEN_HEIGHT (legacy)
    scale: 1.0              # SCALE — AWT debug-viewer scale factor
```

## Architecture

### 1. Enum metadata model

Each `SonicConfiguration` constant gains structured metadata, supplied via constructor arguments:

| Field | Purpose |
|---|---|
| `section` | Dotted section path, e.g. `"input.player1"`, `"audio"`, `"debug.keys"`. |
| `leaf` | The key name inside its section, e.g. `"jump"`. Defaults derivable from the constant name but specified explicitly for clarity. |
| `type` | One of `BOOL`, `INT`, `DOUBLE`, `STRING`, `KEY`, `ENUM` — drives validation and value formatting. |
| `description` | One-line doc, migrated from the existing Javadoc, emitted as the inline `# comment`. |
| `allowedValues` | Optional set for `ENUM`-typed keys (`DISPLAY_ASPECT`, `REGION`, `DEFAULT_ROM`, `WIDESCREEN_DEADZONE_MODE`, `DISPLAY_COLOR_PROFILE`, `CROSS_GAME_SOURCE`, `REWIND_AUDIO_HISTORY_LIMIT_TYPE`). |
| `persisted` | `true` for normal user/debug keys (default). `false` marks a **DERIVED** key — computed at runtime into the `transientResolved` overlay, never read from or written to disk. |

**DERIVED keys.** `SCREEN_WIDTH_PIXELS` and `SCREEN_HEIGHT_PIXELS` are computed from `DISPLAY_ASPECT` (see `resolveDisplayAspect()`); they are `persisted=false`. A DERIVED key has no `section`/`leaf` (it never appears on disk), is **skipped by the writer**, and is **exempt from the "must have a section" completeness check** (it must still declare a `type` and `description`). This resolves the apparent contradiction between "derived values are never written" and "the writer walks every constant": the writer walks every constant but emits only `persisted` ones.

**Declaration order = emit order.** The enum's current order is already a sensible grouping; we tidy it so constants in the same section are contiguous, and so all `persisted` constants are ordered normal-sections-first, then `debug.*` (DERIVED constants may sit anywhere since they're never emitted).

The enum's `name()` remains the canonical map key, so nothing about the in-memory representation or the `defaults` map changes.

### 2. Section taxonomy & debug compartmentalisation

Every key is classified by **audience** — normal player-facing vs developer/debug — and that classification drives where it lands on disk. Debug is **not a clean key prefix**, so classification is per-key (several keys live in "mixed" feature areas). The marker is the section path: any section under the `debug.` prefix is part of the fenced debug block; everything else is a normal top-level section. The writer draws the debug fence the first time it emits a `debug.*` section (no separate `audience` enum field needed — the `debug.` prefix is the single source of that fact).

**DERIVED keys (never on disk):** `SCREEN_WIDTH_PIXELS`, `SCREEN_HEIGHT_PIXELS` — computed from `DISPLAY_ASPECT`, `persisted=false`, no section.

**Normal sections (top of file):**

- `display` — `aspect`, `windowAutosize`, `deadzoneMode` (WIDESCREEN_DEADZONE_MODE), `colorProfile` + `colorProfileToggleKey`, `fps`. Note: the raw window size and scale are **not** here — see `debug.window` below.
- `input.player1` — UP/DOWN/LEFT/RIGHT/JUMP
- `input.player2` — P2_* keys
- `input` (general) — `pause` (PAUSE_KEY); normal gameplay control
- `audio` — enabled, region, DAC interpolate, internal-rate output, PSG noise mode, FM6 DAC off
- `characters` — main/sidekick character codes, data-select extra combos
- `roms` — S1/S2/S3K ROM filenames, default ROM
- `startup` — title / master-title / legal-disclaimer on startup (the normal ones only)
- `rewind` — `LIVE_REWIND_*` (tape-coast) and `REWIND_AUDIO_*`: these are the **player-facing** held-key live-rewind feature, so they stay in the normal area
- `crossGame` — `CROSS_GAME_FEATURES_ENABLED`, `CROSS_GAME_SOURCE`: user-facing cross-game donation toggles
- `discord` — DISCORD_RICH_PRESENCE_*

**Debug block (single top-level `debug:`, fenced at the bottom):**

- `debug.flags` — DEBUG_VIEW_ENABLED, EDITOR_ENABLED, DEBUG_COLLISION_VIEW_ENABLED
- `debug.keys` — TEST, NEXT_ACT, NEXT_ZONE, DEBUG_MODE_KEY, FRAME_STEP_KEY, DEBUG_LAST_CHECKPOINT_KEY, LEVEL_SELECT_KEY, SUPER_SONIC_DEBUG_KEY, GIVE_EMERALDS_KEY, special-stage keys (SPECIAL_STAGE_KEY/COMPLETE/FAIL/SPRITE_DEBUG/PLANE_DEBUG)
- `debug.startup` — LEVEL_SELECT_ON_STARTUP, S3K_SKIP_INTROS (dev conveniences pulled out of normal `startup`)
- `debug.playback` — all PLAYBACK_* (BK2 movie path, keys, start offset)
- `debug.traceRewind` — TRACE_REWIND_KEY (dev-only; distinct from the player-facing live rewind above)
- `debug.testMode` — TEST_MODE_ENABLED, TRACE_CATALOG_DIR
- `debug.crossGame` — `CROSS_GAME_S1_DATA_SELECT_IMAGE_GEN_OVERRIDE`, `CROSS_GAME_S2_DATA_SELECT_IMAGE_GEN_OVERRIDE`, `CROSS_GAME_S1_DATA_SELECT_IMAGE_COORD_LOG_KEY` (debug tooling, split from the user-facing crossGame toggles)
- `debug.window` — **DEPRECATED** manual window controls: `SCREEN_WIDTH`, `SCREEN_HEIGHT`, `SCALE`. Still read (used verbatim only when `DISPLAY_WINDOW_AUTOSIZE=false`), but demoted out of the normal `display` section because the engine does not support arbitrary viewport sizes — widescreen is driven by `DISPLAY_ASPECT` profiles. Descriptions carry a "deprecated, prefer display.aspect" note.

**Borderline judgement calls (documented so they aren't re-litigated):**

- `PAUSE_KEY` → normal (`input`); `FRAME_STEP_KEY` → debug (only meaningful while debugging).
- `DISPLAY_COLOR_PROFILE_TOGGLE_KEY` → normal (`display`), since it toggles a normal display setting.
- `EDITOR_ENABLED` → debug (`debug.flags`); the editor is dev tooling, default off.
- `SCREEN_WIDTH` / `SCREEN_HEIGHT` / `SCALE` → debug (`debug.window`), deprecated (see open-question resolution above). `SCREEN_WIDTH_PIXELS` / `SCREEN_HEIGHT_PIXELS` → DERIVED, never persisted.
- The `rewind`/`crossGame`/`startup` splits above are the deliberate resolution of the three mixed areas.

This split is **presentation only** — no master gate flag and no behavior change. Debug keys are still always read into the flat map, and runtime gating (e.g. `DEBUG_VIEW_ENABLED` checks at call sites) is unchanged.

`VERSION` is currently unused (no references in `src/`). Leave it out of the emitted file (or assign it a `meta` section); do not invent behavior for it.

### 3. Read path

`loadConfig()`:
1. Resolve the config file (working dir or next-to-executable for native image — unchanged logic, new filename).
2. Parse YAML into a nested `Map<String, Object>` via the Jackson YAML mapper.
3. **Flatten** the tree to enum-name keys: for each leaf, find the constant whose `section` + `leaf` matches, and store `config.put(constant.name(), value)`.
4. Collect any leaf with no matching constant into an `unknownKeys` list and log a warning (problem #4).
5. Run existing migration hooks and `applyDefaults()` exactly as today.

Flattening is the **only** new step on read; everything downstream sees the same flat map it sees today.

### 4. Write path

`saveConfig()` delegates to a new `ConfigYamlWriter`:
1. Walk `SonicConfiguration.values()` in declaration order (persisted constants are ordered so all normal sections precede all `debug.*` sections), **skipping any constant with `persisted=false`** (DERIVED keys never reach disk).
2. When `section` changes, emit a blank line + `# ── <Human Section Title> ──` header and open the nested mapping blocks for that path.
3. The first time a `debug.*` section is reached, emit the debug fence banner (`# ═══ DEBUG (developer tooling — safe to ignore for normal play) ═══`) and open the top-level `debug:` mapping, so the whole debug compartment renders as one fenced block at the bottom.
4. For each constant, emit `<leaf>: <formatted value>   # <description>`, reading the value from the flat `config` map (falling back to the default).
5. Values formatted by `type` (quote strings when needed, bare booleans/numbers, key names as bare tokens).

This guarantees deterministic ordering and grouping every save (problems #1 and #2), and inline docs (problem #3). The writer is pure-text and metadata-driven — no reflection on live objects beyond the enum.

`transientResolved` (derived display values) is **never written**, preserving today's invariant that `SCREEN_WIDTH_PIXELS` etc. are computed, not persisted. This is now also enforced structurally by `persisted=false` on those constants (the writer skips them regardless of overlay state).

### 5. Migration

Extend the existing `ConfigMigrationService` pattern. On startup, in priority order:
1. If `config.yaml` exists → load it (steady state).
2. Else if legacy `config.json` exists → read the flat JSON, map each key through the enum to populate `config`, then write `config.yaml`, and rename the old file to `config.json.bak`. Log a one-line migration notice.
3. Else → classpath fallback (`/config.json` resource) then defaults, then write a fresh `config.yaml`.

Existing in-map migrations (`detectAwtKeyCodes`, `migrateDeprecatedS1PreviewCoordLogKey`, `migrateDeprecatedDisplayColorProfileToggleKey`, `S3K_SKIP_AIZ1_INTRO` rename) continue to run against the flat map after load, unchanged.

### 6. Validation

A startup validation pass (building on the warning logic already in `getInt()`):
- Unknown section/leaf keys → `LOGGER.warning` (already collected during flatten).
- `ENUM`-typed values not in `allowedValues` → warn and fall back to the registered default.
- Key-typed values that resolve to no GLFW code → existing behavior (warn, default/unbound).

### 7. Filename / packaging / docs references to update

`config.json` is referenced well beyond the load/save code. The planning checklist must cover **all** of these, grouped by kind (verified via `grep -rn config.json` over the tracked tree):

**Code (load/save/resolve + user-facing strings):**
- `src/main/java/com/openggf/configuration/SonicConfigurationService.java` — primary change site, plus ~12 log/comment strings (`"...from working directory"`, `"Failed to save config.json"`, the `resolveDisplayAspect()` Javadoc, etc.) that name the file.
- `src/main/java/com/openggf/testmode/TestModeTracePicker.java:82` — on-screen text `"Check TRACE_CATALOG_DIR in config.json"`; update wording to the new filename/section path.

**Bundled default & packaging:**
- `src/main/resources/config.json` — the **bundled classpath default**. Convert to `src/main/resources/config.yaml` (or keep the `.json` as a legacy fallback that the loader can still read; decide in planning — keeping both eases transition).
- `pom.xml:181-183` — antrun step copying `src/main/resources/config.json` next to the native binary; update the copied filename.
- `pom.xml` dependencies — add `jackson-dataformat-yaml` (see Dependencies & Native Image).
- `src/packaging/assemble-macos-app.sh:70-77` — copies the config next to the `.app`; update to `config.yaml`.

**Docs:**
- `CONFIGURATION.md` — 6 references (intro at lines 3-4, the `DISPLAY_COLOR_PROFILE_TOGGLE_KEY` row, the editor note, the JVM-properties note, the `## Example config.json` section). Rewrite for the YAML layout, the section taxonomy, the `debug:` compartment, deprecated `debug.window`, and migration behavior.
- `CLAUDE.md` / `AGENTS.md` Configuration sections that name `config.json` (update via the `Agent-Docs` trailer when those files are staged).

### Dependencies & Native Image

- **Add dependency:** `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml` (transitively `org.yaml:snakeyaml`). Match the existing Jackson version to avoid a version skew with `jackson-databind`.
- **GraalVM native image:** SnakeYAML uses reflection. Verify/extend the existing GraalVM config (`reflect-config.json` / `resource-config.json`) so YAML parsing works in the native image; the bundled `config.yaml` resource must be registered in `resource-config.json` just as `config.json` is today. Validate with a native-image smoke run (the widescreen native regression test path is a good anchor).

## Testing Strategy

JUnit 5 only (per repo policy). New/updated tests:

1. **Round-trip stability** — load → save → load produces byte-identical YAML; ordering is deterministic across repeated saves (directly refutes problem #2).
2. **Flatten/unflatten symmetry** — every constant survives nest→flatten→nest with its value intact.
3. **Migration** — a sample legacy flat `config.json` migrates to an equivalent `config.yaml`, `.bak` is created, and all values match.
4. **Unknown-key warning** — an unrecognized leaf logs a warning and does not crash.
5. **Metadata completeness** — every `persisted` constant (i.e. excluding DERIVED keys and dormant `VERSION`) has a non-empty `section` and `description`; `ENUM`-typed constants have non-empty `allowedValues`. Every constant (DERIVED included) has a `type` and `description`.
6. **DERIVED keys never persisted** — saving never emits `SCREEN_WIDTH_PIXELS`/`SCREEN_HEIGHT_PIXELS`, even if present in the overlay; loading a file that contains them flattens them away with an unknown/derived warning rather than persisting.
7. **Debug compartmentalisation** — all `persisted` `debug.*` constants are contiguous and come after every normal section in declaration order (so the fenced block is unbroken), and the emitted file contains exactly one `debug:` top-level block with the fence banner.
8. **Existing tests** — update the config tests, the widescreen native regression test, and `TestSonicConfigurationFileBootstrap` to the new filename/format; keep their behavioral assertions intact. Add coverage that `debug.window.width/height/scale` still drive window size when `windowAutosize=false` (deprecated-but-functional).

## Risks & Mitigations

- **YAML whitespace sensitivity** — hand-editing can break indentation. Mitigation: the emitter always rewrites a clean file on save, and validation warns on parse problems; document the format in `CONFIGURATION.md`.
- **Comment emission** — Jackson's YAML writer can't emit our comments, so the custom emitter is load-bearing. Mitigation: keep it small and fully test-covered (round-trip test). Reading still uses the robust library parser.
- **External tooling reading `config.json`** — the packaging script and any docs must move together; the `.bak` + transition fallback reduce breakage.

## Stretch (optional, not required)

- Generate a `config.schema.json` from the same enum metadata for editor autocomplete/validation. Inline comments already cover discoverability, so this is additive, not essential.

## Documentation Obligations

- `CONFIGURATION.md` — document the new YAML layout, sections, and the migration behavior.
- `CHANGELOG.md` — engine change touching `src/main/` (`Changelog: updated`).
- Commit trailers per repo policy.
