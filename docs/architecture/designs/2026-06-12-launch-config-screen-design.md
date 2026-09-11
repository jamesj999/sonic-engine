# Launch Configuration Screen — Design

**Date:** 2026-06-12
**Status:** Approved for planning

## Goal

A per-game pre-launch configuration panel on the master title screen. Each game
(S1/S2/S3K) persists its own launch profile covering the engine's optional
features (rewind, cross-game donation, debug tools, characters). The default is
the stock game — no optional features — and options are enabled per game as
desired. The hover line on the master title shows how far the selected game's
profile diverges from stock and how to open the panel.

## Scope decisions (from brainstorming)

- **Per-game profiles**, not a single global option set.
- **Stock wins for everyone:** profiles start stock; the global config keys
  for the *gameplay* options (rewind, cross-game, debug tools, characters)
  are *ignored* when launching through the master title screen. Direct
  launches (master title disabled, trace sessions) keep today's global-key
  behavior unchanged. No migration/seeding from globals. **Exception:** the
  Widescreen row's stock value is Global — it inherits `display.aspect`
  because aspect is a presentation preference, not a gameplay option (see
  row semantics below).
- **Fully functional:** UI + persistence + options genuinely applied at launch.
- **Rows limited to finished features:** collision view and the level editor
  are excluded for now (unfinished). The debug row is named **Debug Tools**
  because `debug.flags.debugView` now gates both the overlay and the debug
  cheat keys (e.g. give emeralds).
- In-engine implementation only; no committed HTML mock-up.

## UX

### Master title hover line

When the selected game's ROM is present, a centered line renders above the game
menu (~y 177, between the ROM preview matte and the menu row):

- Stock profile: `Stock launch - Tab to configure` (dim grey). ("Launch",
  not "game" — the widescreen row's stock inherits the global display
  aspect, so "stock" describes the launch profile, not original-hardware
  presentation.)
- Diverged: `3 options enabled - Tab to configure` (gold tint).

The separator is a plain hyphen: the PixelFont atlas (`PixelFont.java:26-30`)
covers ASCII letters/digits plus a small special set that does **not**
include U+00B7 `·` — a middle dot would silently fail to render. All panel
and hover strings must stick to atlas glyphs (`*`, `(`, `)` are available).

Missing-ROM games show no line and Tab is a no-op.

### Launch config panel

Pressing **Tab** in the `ACTIVE` state opens the panel over a semi-transparent
black matte (same style as the error overlay). Title: e.g.
`Sonic 2 — Launch Options`. Six rows:

| Row | Values (stock first) | Notes |
|---|---|---|
| Rewind | Off / On | maps to `rewind.liveEnabled` |
| Cross-Game Donation | Off / donor game | donor excludes the game itself; maps to `crossGame.enabled` + `crossGame.source` |
| Debug Tools | Off / On | overlay + debug cheat keys; maps to `debug.flags.debugView` |
| Widescreen | Global / Native 4:3 / 16:10 / 16:9 / 21:9 / 32:9 | maps to `display.aspect` (`WidescreenAspect` presets); see below |
| Main Character | Sonic / Tails / Knuckles | maps to `characters.main` |
| Sidekick | per-game stock / None / Tails / Sonic / Knuckles | S1 stock: None; S2/S3K stock: Tails; maps to `characters.sidekick` |

**Widescreen row semantics — deliberate exception to stock-wins.** Display
aspect is a presentation preference, not a gameplay option, so its stock
value is **Global** — inherit `display.aspect` unchanged, apply no override,
trigger no resize. The row label renders the resolved global preset for
clarity, e.g. `Global (16:9)`. Explicit per-game values pin the aspect for
that game and dynamically resize the window when the game is entered.
Widescreen presets carry the non-standard asterisk; Native 4:3 (pinned) and
Global do not.

Controls: **Up/Down** select row, **Left/Right** cycle value, **Backspace**
reset all rows to stock, **Tab/Esc** close. Closing persists immediately via
`saveConfig()`. A footer line lists the controls. Directional keys are the
**configured** `SonicConfiguration.UP/DOWN/LEFT/RIGHT` bindings, matching how
the master-title menu reads configured LEFT/RIGHT; Tab/Esc/Backspace are
hardcoded GLFW keys, matching the menu's hardcoded Enter.

### Row markers

Three independent markers:

- **(stock)** — dim marker on rows currently at their stock value.
- **`*` non-standard** — amber tint + asterisk on values impossible in the
  original game, with a footer legend `* not possible in the original game`.
- **`!` experimental** — red tint + exclamation mark for launch choices that
  are not just non-standard but still explicitly experimental, with a footer
  legend `! experimental`.

Non-standard rules:

- Rewind On, Cross-Game Donation ≠ Off, Debug Tools On: always non-standard
  (engine features).
- Widescreen: pinned widescreen presets (16:10, 16:9, 21:9, 32:9) are
  non-standard; Global and pinned Native 4:3 are not. Ultrawide presets
  (21:9 and 32:9) are additionally experimental and use the red `!` marker
  instead of the amber `*`.
- Characters are validated **as a (main, sidekick) pair** against the game's
  authentic `Player_mode` combos:
  - S1: (sonic, none)
  - S2: (sonic, none), (sonic, tails), (tails, none)
  - S3K: (sonic, none), (sonic, tails), (tails, none), (knuckles, none)
  A non-standard pair flags **both** character rows. Tails-alone in S3K is
  non-stock but standard — it gets `(stock)` removed but no asterisk.

## Persistence schema

New `launch:` section in `config.yaml` with nested per-game blocks:

```yaml
launch:
  s1:
    rewind: false
    crossGameSource: "off"   # "off" or donor game id
    debugTools: false
    aspect: "global"         # "global" or a WidescreenAspect preset name
    mainCharacter: "sonic"
    sidekick: "none"
  s2: …   # same shape; sidekick stock "tails"
  s3k: …  # same shape; sidekick stock "tails"
```

6 options × 3 games = **18 new `SonicConfiguration` keys**, each with
`ConfigCatalog` metadata. The `launch` section is placed after `crossGame` and
**before the `debug.*` block** (catalog ordering rule; `TestConfigCatalog`
enforces). Cross-game enabled/source collapse into a single
`crossGameSource` value (`"off"` = disabled) so a row maps to one key.

New package `com.openggf.game.launch`:

- **`LaunchProfile`** (record) — the 6 fields, plus:
  - `stockFor(MasterTitleScreen.GameEntry)` factory,
  - `enabledCount()` — rows differing from stock,
  - per-row cycling helpers (next/previous value given the game entry),
  - `isCharacterPairStandard(GameEntry)` and per-row standardness predicates.
- **`LaunchProfileStore`** — load/save a `LaunchProfile` for a game through
  `SonicConfigurationService`. Unknown hand-edited values clamp to stock with a
  log warning.

## Launch application — session-override overlay

**Hazard found during design:** `DisplayColorProfileController` calls
`saveConfig()` mid-game (V key), which writes the entire in-memory config map.
If the profile were applied by overwriting globals with `setConfigValue`, a
mid-game save would silently persist profile values into the user's global
keys.

**Mechanism:** `SonicConfigurationService` gains a **session-override
overlay** — a sibling map to the existing `transientResolved` overlay (which
already proves the pattern: read before `config`, never saved). Lookup order:
session overrides → `transientResolved` → `config`. Written via
`setSessionOverride(key, value)`, cleared as a unit with
`clearSessionOverrides()`; `saveConfig()` never sees it. It is deliberately
**not** merged into `transientResolved`, whose entries are managed
selectively by `resolveDisplayAspect()`. (The trace picker's existing
"disable test mode for this session" `setConfigValue` call has the same
latent persistence bug and can migrate to the overlay later — out of scope
here.)

**`LaunchProfileApplier`** (in `com.openggf.game.launch`) maps a profile onto
the affected global keys as session overrides:
`rewind.liveEnabled`, `crossGame.enabled`, `crossGame.source`,
`debug.flags.debugView`, `characters.main`, `characters.sidekick`, and —
only when the profile aspect is not `"global"` — `display.aspect`.
Value mappings: profile `crossGameSource="off"` → `crossGame.enabled=false`
**and** `crossGame.source` overridden to the built-in config default, so the
first 6 keys are always deterministically overridden; profile
`sidekick="none"` → empty string for `characters.sidekick` (the existing
disable convention). The aspect key is intentionally asymmetric: `"global"`
means *no override* (inherit the user's display preference); no leak is
possible because every launch clears the overlay first (lifecycle below).

Rules:

- Runs in `GameLoop.doExitMasterTitleScreen`, before the master-title exit
  handler, so downstream readers that resolve config **at or after launch**
  (rewind controller construction, cross-game providers, sprite setup) see
  profile values without reader-code changes.
- Always sets **all 6** managed keys — stock values included — so returning to
  the master title and launching a different game cannot leak the previous
  profile.
- **Skipped for programmatic selections:** `MasterTitleScreen.selectEntry`
  (used by `TraceSessionLauncher`) sets a `programmaticSelection` flag that
  `GameLoop` checks — trace determinism must not depend on user profiles.
- Direct launches that bypass the master title never run the applier; global
  keys behave exactly as today.

### Overlay lifecycle — `clearSessionOverrides()` call sites

Stale overrides must never outlive the launch they belong to. The overlay is
cleared:

1. **At every master-title exit**, in `GameLoop.doExitMasterTitleScreen`,
   *before* the apply/skip decision: user launches clear-then-apply;
   programmatic launches clear-then-skip. This protects downstream game
   init (team bootstrap, providers) from the previous launch's profile.
2. **At the top of `TraceSessionLauncher.launch()`**, before
   `TraceReplaySessionBootstrap.snapshotGameplayConfig()` and
   `prepareConfiguration()` run — both execute *before* `launchGameByEntry`
   reaches the doExit clear (`TraceSessionLauncher.java:129-139`). Without
   this, the snapshot would capture overlay-masked values (and teardown
   would then "restore" profile values into the base map), and the
   trace-required main/sidekick/cross-game writes — which go into the base
   map via `setConfigValue` — would stay masked by the overlay.
3. **On return to the master title** (`GameLoop.returnToMasterTitle` path),
   so the title screen itself and the trace picker read persisted values.
4. **In `SonicConfigurationService.resetToDefaults()`**, so test/headless
   resets start overlay-free.

### Engine-lifetime cached readers

The "no reader changes" claim does **not** hold for state cached in objects
that outlive a master-title launch. Known cases:

- `Engine` caches `DEBUG_VIEW_ENABLED` into its `debugViewEnabled` field at
  construction (`Engine.java:219`) and uses the field in render/profiler/HUD
  paths (428, 1278, 1377, 1401, 1567).
- `Engine` caches `SCREEN_WIDTH_PIXELS` into `realWidth`/`projectionWidth` at
  construction (`Engine.java:216-218`) — handled by the resize hook below.

The implementation must refresh these caches from `configService` at
master-title exit (after the applier runs) and on return to the master title
(after overrides clear). The plan must also audit the remaining managed keys
for Engine-lifetime caches and add the same refresh where found; per-session
objects (rebuilt by `SessionManager` after launch) need nothing.

### Dynamic widescreen resize at launch

When a profile pins an aspect, the window resizes to it on game entry; the
resize happens inside the master-title fade-to-black so the geometry change
is not visible mid-transition. Sequence in `doExitMasterTitleScreen` (user
launches only):

1. Applier sets the `display.aspect` session override (when pinned).
2. `configService.resolveDisplayAspect()` re-derives `SCREEN_WIDTH_PIXELS`
   (and autosized window dimensions) — the existing derivation reads
   `display.aspect` through `getConfigValue`, which consults the session
   overlay first, so it picks up the pin with no changes.
   `TEST_MODE_ENABLED` already forces `NATIVE_4_3` inside
   `resolveDisplayAspect`, preserving trace parity.
3. A new `Engine` hook (`applyResolvedDisplayDimensions()`) re-reads
   `SCREEN_WIDTH_PIXELS` into `realWidth`/`projectionWidth` **and** the
   resolved `SCREEN_WIDTH`/`SCREEN_HEIGHT` (overlaid by
   `resolveDisplayAspect()` when autosize derives a new window), applies the
   resolved window size via `glfwSetWindowSize`, updates
   `windowWidth`/`windowHeight`, and re-runs `reshape`. It must **not** rely
   on `snapWindowToIntegerScale()` alone: that method computes scale from
   the *current* `windowWidth`/`windowHeight` fields (`Engine.java:1165`),
   so switching native 320 → pinned 400 with a stale 640×448 window would
   snap to 400×224 instead of the intended autosized 800×448. The
   implementation plan must audit Engine-lifetime width-dependent resources
   (FBOs, render targets, `GraphicsManager` viewport state) and recreate any
   that bake in the old width.

**Symmetry on return to the master title:** clearing the overlay is followed
by `resolveDisplayAspect()` + the same Engine hook, so the window returns to
the user's global aspect. When the profile aspect is `"global"` (stock) both
directions are no-ops — no override, no resize, matching "dynamic resize
only when a profile is enabled".

## Panel implementation

**`LaunchConfigPanel`** in `com.openggf.game`, following the
`TestModeTracePicker` pattern:

- Constructed with the `GameEntry`, its loaded `LaunchProfile`, the shared
  `PixelFont` / `TexturedQuadRenderer`, and the `LaunchProfileStore`.
- `update(InputHandler)` is pure state-machine logic (testable headless) and
  returns a `CLOSED`/`NONE` result; `MasterTitleScreen` consumes `CLOSED` by
  saving through the store and nulling the panel field.
- `render()` draws matte, title, rows with value + markers, footer. All
  foreground centered on `viewportWidth` like other master-title elements.
- `MasterTitleScreen` integration mirrors the trace picker: a panel field;
  when non-null, `update`/`draw` delegate to it. Tab (`GLFW_KEY_TAB`,
  hardcoded like Enter) opens it from `ACTIVE` when the selected ROM exists.

## Edge cases

- Hand-edited invalid yaml values → clamp to stock at load, log warning.
- Cross-game donor list excludes the launching game; if a stored donor equals
  the game (hand-edit), clamp to `off`.
- Widescreen: all panel elements center on `viewportWidth`; at native 320 the
  layout collapses to fixed literals like the rest of the screen.
- Panel open state blocks game-select navigation and confirm (delegation
  pattern guarantees this, same as the trace picker).
- Test mode (trace picker) takes precedence: when `TEST_MODE_ENABLED`, the
  picker path runs before any Tab handling, unchanged.

## Testing

- `LaunchProfile`: stock factories, `enabledCount`, cycling, standardness
  (pair rules per game) — pure unit tests.
- `LaunchProfileStore`: round-trip + invalid-value clamping — use
  `SonicConfigurationService.createStandalone(Path tempDir)` (the existing
  test seam; avoids the process-global `user.dir` override, which is not
  parallel-fork safe).
- Session overlay: getters honor overrides; `saveConfig()` output excludes
  them; `clearSessionOverrides()` restores persisted values;
  `resetToDefaults()` clears the overlay.
- `LaunchProfileApplier`: sets the 6 always-managed keys (including
  `crossGame.source` when donation is off); pins `display.aspect` only when
  the profile aspect is not `"global"`; stock launch resets prior overrides;
  programmatic selection clears stale overrides and skips application — a
  simulated trace launch after a user launch must see base-map values, not
  leftovers.
- Widescreen pin: `resolveDisplayAspect()` with a pinned session
  `display.aspect` derives the pinned `SCREEN_WIDTH_PIXELS`; clearing the
  overlay and re-resolving restores the global derivation; pinning under
  `TEST_MODE_ENABLED` still forces 320 (existing behavior, regression-guard
  it).
- `LaunchConfigPanel`: navigation, cycling, reset-all, close result — stubbed
  `InputHandler`, no GL.
- Hover-line text: static pure function on `MasterTitleScreen` (existing
  `menuTextColor`-style test approach).
- `TestConfigCatalog` green with the 18 new keys.

## Out of scope / follow-ups

- Collision view and level editor rows (features unfinished).
- Migrating the trace picker's session `setConfigValue` to the overlay.
- Rewind tuning (history seconds, tape-coast) — config.yaml only.
- Per-profile key bindings, audio, or display options beyond the widescreen
  aspect row (e.g. color profile, fps stay global).
- Data select / in-game surfacing of the active profile.
