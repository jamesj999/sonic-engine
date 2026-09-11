# Launch Config Screen Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the master-title per-game launch options panel from `docs/architecture/designs/2026-06-12-launch-config-screen-design.md`, including persistent per-game profiles, non-persisted session overrides at launch, trace-safe overlay clearing, and dynamic widescreen resize.

**Architecture:** Add a small `com.openggf.game.launch` model/store/applier layer. Persist profile choices under new `launch.s1`, `launch.s2`, and `launch.s3k` config keys. Apply a selected profile by writing session-only overrides in `SonicConfigurationService`, then refresh the `Engine` caches that live longer than a gameplay session. Keep `MasterTitleScreen` responsible only for opening/delegating the panel and keep profile application in `GameLoop.doExitMasterTitleScreen`.

**Tech Stack:** Java 21, Maven, JUnit 5 (Jupiter only — no JUnit 4), LWJGL GLFW key constants, existing OpenGGF configuration/catalog/YAML infrastructure.

---

## Current Context

- Source spec: `docs/architecture/designs/2026-06-12-launch-config-screen-design.md`.
- Current branch at planning time: `develop`.
- The worktree is shared with other agent sessions and may contain unrelated dirty files. Always run `git status --short` before editing or staging. Stage files by exact path only — never `git add -A` or `git add .` — and do not rewrite or revert unrelated changes.
- Use branch name `feature/ai-launch-config-screen`, matching the repo's `feature/ai-` convention.

## Invariants

- Profile values must never be written into global gameplay keys through `setConfigValue`.
- Session overrides must be read before `transientResolved`, and both must be read before the persisted config map.
- `saveConfig()` must serialize the persisted config map only; it must not see session overrides or derived display values.
- Each master-title exit clears stale session overrides before either applying a user profile or skipping programmatic profile application.
- Programmatic launches from `TraceSessionLauncher` must skip profile application and must clear stale overrides before trace snapshot/preparation.
- `crossGame.source` is always overridden during user launches, including donation-off launches, where it is set back to the built-in default.
- `display.aspect` is overridden only for pinned aspect choices. Profile aspect `"global"` means inherit global display settings.
- After any relevant clear/apply that can affect display dimensions, call `resolveDisplayAspect()` before any code reads `SCREEN_WIDTH_PIXELS`, `SCREEN_WIDTH`, or `SCREEN_HEIGHT`.
- `Engine` must refresh cached `debugViewEnabled`, `realWidth`, `realHeight`, `projectionWidth`, `windowWidth`, and `windowHeight` after launch overrides apply and after they clear on return.
- Dynamic resize must apply the resolved `SCREEN_WIDTH` and `SCREEN_HEIGHT` directly with `glfwSetWindowSize`, update the `Engine.windowWidth/windowHeight` fields, and then reshape. Do not rely on `snapWindowToIntegerScale()` for this path — it computes scale from the stale window fields (`Engine.java:1165`) and would snap a native 320 → pinned 400 switch to 400×224 instead of the autosized 800×448.
- **UI strings must use only PixelFont atlas glyphs.** The atlas covers A–Z, a–z, 0–9, and the specials in `PixelFont.ROW2–ROW4` (`PixelFont.java:26-30`). U+00B7 `·` is NOT in the atlas. The hover line separator is a plain hyphen: `Stock launch - Tab to configure`. `*` and `(` `)` are available for the non-standard marker and labels.

## Task 0: Prepare The Worktree

- [ ] Inspect the worktree, verify the commit hooks are installed, and create or switch to the implementation branch.

  ```powershell
  git status --short
  git config core.hooksPath
  git switch -c feature/ai-launch-config-screen
  ```

  Expected output: `git status --short` may show unrelated dirty files (leave them alone). `git config core.hooksPath` must print `.githooks` — if it prints nothing, run `git config core.hooksPath .githooks` once. `git switch -c` succeeds if the branch does not already exist; if it exists, use `git switch feature/ai-launch-config-screen`.

- [ ] Re-read the spec and the current launch/cache code before editing.

  ```powershell
  rg -n "LaunchProfile|session-override|Overlay lifecycle|applyResolvedDisplayDimensions|Widescreen row|18 new" docs/architecture/designs/2026-06-12-launch-config-screen-design.md
  rg -n "getConfigValue|transientResolved|intCache|resolveDisplayAspect|resetToDefaults|createStandalone|applyDefaults|getDefaultValue" src/main/java/com/openggf/configuration/SonicConfigurationService.java
  rg -n "doExitMasterTitleScreen|launchGameByEntry|returnToMasterTitle|setMasterTitleExitHandler" src/main/java/com/openggf/GameLoop.java
  rg -n "debugViewEnabled|realWidth|projectionWidth|windowWidth|snapWindowToIntegerScale|reshape|exitMasterTitleScreen|returnToMasterTitleScreen" src/main/java/com/openggf/Engine.java
  ```

  Expected output: the spec and code references appear at the same named concepts; no command returns an error.

## Task 1: Add Config Keys And Session Overlay Tests

- [ ] Write failing tests for the 18 persistent launch keys.

  Update `src/test/java/com/openggf/configuration/TestConfigCatalog.java` with assertions that:

  - all 18 keys have `ConfigCatalog` metadata,
  - all 18 keys are persisted keys,
  - the emitted catalog order places `launch.*` after `crossGame.*` and before `debug.*`,
  - `launch.s1.sidekick` default is `"none"`,
  - `launch.s2.sidekick` and `launch.s3k.sidekick` defaults are `"tails"`,
  - `launch.*.aspect` is an ENUM key whose allowed values are exactly `"global"`, `"NATIVE_4_3"`, `"WIDE_16_10"`, `"WIDE_16_9"`, `"ULTRA_21_9"`, `"SUPER_32_9"`,
  - `launch.*.crossGameSource` is an ENUM key whose allowed values are exactly `"off"`, `"s1"`, `"s2"`, `"s3k"`,
  - `launch.*.mainCharacter` is an ENUM key whose allowed values are exactly `"sonic"`, `"tails"`, `"knuckles"`,
  - `launch.*.sidekick` is an ENUM key whose allowed values are exactly `"none"`, `"sonic"`, `"tails"`, `"knuckles"`.

  ENUM metadata matters: `validateEnumeratedValues()` already resets out-of-set persisted values to the registered default at load, which gives most of the spec's "clamp invalid hand-edited values" behavior for free. The store-level clamp (Task 6) then only needs the donor-equals-self rule.

  Use enum constants with these names:

  ```java
  LAUNCH_S1_REWIND,
  LAUNCH_S1_CROSS_GAME_SOURCE,
  LAUNCH_S1_DEBUG_TOOLS,
  LAUNCH_S1_ASPECT,
  LAUNCH_S1_MAIN_CHARACTER,
  LAUNCH_S1_SIDEKICK,
  LAUNCH_S2_REWIND,
  LAUNCH_S2_CROSS_GAME_SOURCE,
  LAUNCH_S2_DEBUG_TOOLS,
  LAUNCH_S2_ASPECT,
  LAUNCH_S2_MAIN_CHARACTER,
  LAUNCH_S2_SIDEKICK,
  LAUNCH_S3K_REWIND,
  LAUNCH_S3K_CROSS_GAME_SOURCE,
  LAUNCH_S3K_DEBUG_TOOLS,
  LAUNCH_S3K_ASPECT,
  LAUNCH_S3K_MAIN_CHARACTER,
  LAUNCH_S3K_SIDEKICK
  ```

- [ ] Add `src/test/java/com/openggf/configuration/TestSonicConfigurationSessionOverrides.java`.

  Cover these cases with `SonicConfigurationService.createStandalone(Path tempDir)` (never override `user.dir`):

  - `getBoolean`, `getString`, and `getInt` read session overrides before persisted values.
  - session overrides read before `transientResolved`; use `DISPLAY_ASPECT`, `SCREEN_WIDTH_PIXELS`, and `resolveDisplayAspect()` to prove lookup order.
  - `setSessionOverride()` clears `intCache`, so a previously cached `SCREEN_WIDTH_PIXELS` changes after override plus resolve.
  - `clearSessionOverrides()` restores persisted values and clears `intCache`.
  - `saveConfig()` output excludes session overrides.
  - `resetToDefaults()` clears session overrides.

  A representative test shape:

  ```java
  @Test
  void saveConfigDoesNotPersistSessionOverrides(@TempDir Path dir) {
      SonicConfigurationService cfg = SonicConfigurationService.createStandalone(dir);
      cfg.setConfigValue(SonicConfiguration.LIVE_REWIND_ENABLED, false);
      cfg.setSessionOverride(SonicConfiguration.LIVE_REWIND_ENABLED, true);

      assertTrue(cfg.getBoolean(SonicConfiguration.LIVE_REWIND_ENABLED));
      cfg.saveConfig();

      SonicConfigurationService reloaded = SonicConfigurationService.createStandalone(dir);
      assertFalse(reloaded.getBoolean(SonicConfiguration.LIVE_REWIND_ENABLED));
  }
  ```

- [ ] Run the focused failing tests.

  ```powershell
  mvn "-Dtest=TestConfigCatalog,TestSonicConfigurationSessionOverrides" test
  ```

  Expected output before implementation: compilation fails because the new constants and session overlay APIs do not exist. Note: `-Dtest` still compiles the whole project; the named classes are what must fail for the expected reason.

## Task 2: Implement Config Keys And Session Overlay

- [ ] Add the 18 `SonicConfiguration` enum constants in `src/main/java/com/openggf/configuration/SonicConfiguration.java`.

  Keep them near related persisted gameplay/config keys, with short comments describing their `launch.s1`, `launch.s2`, and `launch.s3k` paths.

- [ ] Register the 18 keys in `src/main/java/com/openggf/configuration/ConfigCatalog.java`.

  Use paths:

  - `launch.s1` keys: `rewind`, `crossGameSource`, `debugTools`, `aspect`, `mainCharacter`, `sidekick`
  - `launch.s2` keys: same leaf names
  - `launch.s3k` keys: same leaf names

  Insert them after the existing `crossGame` user-facing section and before any `debug.*` metadata (the catalog test from Task 1 enforces this). Register `crossGameSource`, `aspect`, `mainCharacter`, and `sidekick` as ENUM-typed with the allowed-value sets from Task 1. Keep `SCREEN_WIDTH_PIXELS` derived and not emitted.

- [ ] Add defaults in `SonicConfigurationService.applyDefaults()` (there is no `setDefaults()`; defaults are `putDefault(...)` calls inside `applyDefaults()`, `SonicConfigurationService.java:502`).

  ```java
  putDefault(SonicConfiguration.LAUNCH_S1_REWIND, false);
  putDefault(SonicConfiguration.LAUNCH_S1_CROSS_GAME_SOURCE, "off");
  putDefault(SonicConfiguration.LAUNCH_S1_DEBUG_TOOLS, false);
  putDefault(SonicConfiguration.LAUNCH_S1_ASPECT, "global");
  putDefault(SonicConfiguration.LAUNCH_S1_MAIN_CHARACTER, "sonic");
  putDefault(SonicConfiguration.LAUNCH_S1_SIDEKICK, "none");
  // ... same for S2 and S3K, with LAUNCH_S2_SIDEKICK and
  // LAUNCH_S3K_SIDEKICK defaulting to "tails"
  ```

- [ ] Add session override storage to `src/main/java/com/openggf/configuration/SonicConfigurationService.java`.

  Implementation requirements:

  - field: `private final Map<String, Object> sessionOverrides = new HashMap<>();`
  - `getConfigValue(SonicConfiguration key)` lookup order: `sessionOverrides`, then `transientResolved`, then `config`
  - public API:

    ```java
    public void setSessionOverride(SonicConfiguration key, Object value)
    public void clearSessionOverrides()
    public boolean hasSessionOverride(SonicConfiguration key)
    ```

  - `setSessionOverride()` and `clearSessionOverrides()` must clear `intCache`.
  - `resetToDefaults()` must clear `sessionOverrides` before its existing `resolveDisplayAspect()` call.
  - `saveConfig()` continues to serialize only `config`.
  - `setConfigValue()` continues to mutate only `config`.

- [ ] Widen `getDefaultValue` to `public` (`SonicConfigurationService.java:293`).

  It is currently package-private; `LaunchProfileApplier` (Task 8) lives in `com.openggf.game.launch` and needs the built-in `CROSS_GAME_SOURCE` default for donation-off overrides.

- [ ] Update root and bundled YAML samples.

  Files:

  - `config.yaml`
  - `src/main/resources/config.yaml`

  Add the `launch:` section after `crossGame:` and before the debug block, with comments noting profiles are per-game launch defaults edited from the master title screen. (`CONFIGURATION.md` is updated in Task 15 so all doc edits land in one commit.)

- [ ] Run the focused config tests.

  ```powershell
  mvn "-Dtest=TestConfigCatalog,TestSonicConfigurationSessionOverrides,TestConfigYamlWriter,TestConfigServiceYamlRoundTrip,TestSonicConfigurationFileBootstrap,TestDisplayAspectResolution" test
  ```

  Expected output after implementation: Maven ends with `BUILD SUCCESS`.

## Task 3: Add Launch Profile Model Tests

- [ ] Create `src/test/java/com/openggf/game/launch/TestLaunchProfile.java`.

  Test `LaunchProfile.stockFor(MasterTitleScreen.GameEntry)`:

  - S1 stock: rewind off, cross-game off, debug off, aspect global, main sonic, sidekick none.
  - S2 stock: same, sidekick tails.
  - S3K stock: same, sidekick tails.
  - `enabledCount(entry)` returns `0` for stock.

- [ ] Test row cycling.

  Required value order:

  - Rewind: `false`, `true`
  - Cross-game: `"off"` then donor game IDs excluding the launched game
  - Debug tools: `false`, `true`
  - Widescreen: `"global"`, `"NATIVE_4_3"`, `"WIDE_16_10"`, `"WIDE_16_9"`, `"ULTRA_21_9"`, `"SUPER_32_9"`
  - Main character: `"sonic"`, `"tails"`, `"knuckles"`
  - Sidekick: per-game stock first, then remaining unique values from `"none"`, `"tails"`, `"sonic"`, `"knuckles"`

  Include reverse cycling tests for wraparound.

- [ ] Test stock and non-standard markers.

  Cases:

  - rewind on is non-standard.
  - cross-game donor is non-standard.
  - debug tools on is non-standard.
  - aspect `"global"` and `"NATIVE_4_3"` are standard.
  - aspect `"WIDE_16_10"`, `"WIDE_16_9"`, `"ULTRA_21_9"`, and `"SUPER_32_9"` are non-standard.
  - S1 only `(sonic, none)` is standard.
  - S2 `(sonic, none)`, `(sonic, tails)`, and `(tails, none)` are standard.
  - S3K `(sonic, none)`, `(sonic, tails)`, `(tails, none)`, and `(knuckles, none)` are standard.
  - S3K `(tails, none)` is standard but non-stock.
  - when the character pair is non-standard, both character rows are flagged non-standard.

- [ ] Run the failing model tests.

  ```powershell
  mvn "-Dtest=TestLaunchProfile" test
  ```

  Expected output before implementation: compilation fails because `LaunchProfile` does not exist.

## Task 4: Implement Launch Profile Model

- [ ] Create `src/main/java/com/openggf/game/launch/LaunchProfile.java`.

  Implement it as a record:

  ```java
  public record LaunchProfile(
          boolean rewind,
          String crossGameSource,
          boolean debugTools,
          String aspect,
          String mainCharacter,
          String sidekick) {
      public enum Row {
          REWIND,
          CROSS_GAME,
          DEBUG_TOOLS,
          WIDESCREEN,
          MAIN_CHARACTER,
          SIDEKICK
      }
  }
  ```

  Add:

  - `stockFor(MasterTitleScreen.GameEntry entry)`
  - `enabledCount(MasterTitleScreen.GameEntry entry)`
  - `withNext(Row row, MasterTitleScreen.GameEntry entry)`
  - `withPrevious(Row row, MasterTitleScreen.GameEntry entry)`
  - `withStock(MasterTitleScreen.GameEntry entry)`
  - `isStock(Row row, MasterTitleScreen.GameEntry entry)`
  - `isNonStandard(Row row, MasterTitleScreen.GameEntry entry)`
  - `isCharacterPairStandard(MasterTitleScreen.GameEntry entry)`
  - stable display labels for each row value

  Normalize profile strings to lower-case for characters/game IDs and exact `WidescreenAspect` enum names for pinned aspect values.

- [ ] Keep game ID mapping centralized.

  Use `MasterTitleScreen.GameEntry` as the UI-facing game identity. If a mapping helper is needed, add it either to `LaunchProfile` or `MasterTitleScreen.GameEntry`, not as repeated string comparisons in multiple call sites.

- [ ] Run model tests.

  ```powershell
  mvn "-Dtest=TestLaunchProfile" test
  ```

  Expected output: `BUILD SUCCESS`.

## Task 5: Add Launch Profile Store Tests

- [ ] Create `src/test/java/com/openggf/game/launch/TestLaunchProfileStore.java`.

  Use `SonicConfigurationService.createStandalone(Path tempDir)` in every test. Do not override `user.dir`.

- [ ] Test round-trip persistence for each game.

  For S1, S2, and S3K:

  - save a non-stock `LaunchProfile`,
  - create a new standalone config service from the same temp dir,
  - load with a new `LaunchProfileStore`,
  - assert every field round-trips.

- [ ] Test invalid hand-edited values clamp.

  Out-of-set strings (bad aspect, bad character, bad sidekick, bad source) are already reset to defaults by `validateEnumeratedValues()` at service load — assert that behavior holds for one launch key as a regression guard. The store itself must additionally clamp:

  - donor equal to the launched game clamps to `"off"` (in-set but invalid for that game), with a log warning.

- [ ] Test store never touches global gameplay keys.

  Set `LIVE_REWIND_ENABLED`, `CROSS_GAME_FEATURES_ENABLED`, `CROSS_GAME_SOURCE`, `DEBUG_VIEW_ENABLED`, `DISPLAY_ASPECT`, `MAIN_CHARACTER_CODE`, and `SIDEKICK_CHARACTER_CODE` to sentinel values, save a launch profile, then assert the sentinel values remain unchanged.

- [ ] Run failing store tests.

  ```powershell
  mvn "-Dtest=TestLaunchProfileStore" test
  ```

  Expected output before implementation: compilation fails because `LaunchProfileStore` does not exist.

## Task 6: Implement Launch Profile Store

- [ ] Create `src/main/java/com/openggf/game/launch/LaunchProfileStore.java`.

  Constructor:

  ```java
  public LaunchProfileStore(SonicConfigurationService configService)
  ```

  Public API:

  ```java
  public LaunchProfile load(MasterTitleScreen.GameEntry entry)
  public void save(MasterTitleScreen.GameEntry entry, LaunchProfile profile)
  ```

- [ ] Add exact key mapping from `GameEntry` to the 6 per-game `SonicConfiguration` keys.

  Keep the mapping in one private method returning a small private record:

  ```java
  private record Keys(
          SonicConfiguration rewind,
          SonicConfiguration crossGameSource,
          SonicConfiguration debugTools,
          SonicConfiguration aspect,
          SonicConfiguration mainCharacter,
          SonicConfiguration sidekick) {}
  ```

- [ ] Clamp donor-equals-self at load.

  Log a warning with the game, the invalid donor, and the `"off"` replacement. Do not throw for hand-edited config. (Out-of-set values are already handled by catalog ENUM validation at service load.)

- [ ] Save only launch profile keys.

  `save()` must use `setConfigValue()` on the 6 `launch.*` keys and call `saveConfig()` once after all 6 writes.

- [ ] Run store tests.

  ```powershell
  mvn "-Dtest=TestLaunchProfile,TestLaunchProfileStore" test
  ```

  Expected output: `BUILD SUCCESS`.

## Task 7: Add Launch Profile Applier Tests

- [ ] Create `src/test/java/com/openggf/game/launch/TestLaunchProfileApplier.java`.

  Use `SonicConfigurationService.createStandalone(Path tempDir)`.

- [ ] Test always-managed keys.

  Applying a user profile must set session overrides for:

  - `LIVE_REWIND_ENABLED`
  - `CROSS_GAME_FEATURES_ENABLED`
  - `CROSS_GAME_SOURCE`
  - `DEBUG_VIEW_ENABLED`
  - `MAIN_CHARACTER_CODE`
  - `SIDEKICK_CHARACTER_CODE`

  For donation off, assert `CROSS_GAME_FEATURES_ENABLED` is `false` and `CROSS_GAME_SOURCE` reads as the built-in default value from `getDefaultValue(SonicConfiguration.CROSS_GAME_SOURCE)`.

- [ ] Test sidekick `"none"` maps to the existing runtime disable convention.

  Applying `sidekick="none"` must make `getString(SIDEKICK_CHARACTER_CODE)` return `""`.

- [ ] Test aspect overlay asymmetry.

  Cases:

  - aspect `"global"` does not create a `DISPLAY_ASPECT` session override (`hasSessionOverride` is false).
  - aspect `"WIDE_16_9"` creates a `DISPLAY_ASPECT` session override with `"WIDE_16_9"`.
  - after `resolveDisplayAspect()`, pinned `"WIDE_16_9"` yields `SCREEN_WIDTH_PIXELS == 400`, `SCREEN_WIDTH == 800`, and `SCREEN_HEIGHT == 448`.
  - after `clearSessionOverrides()` plus `resolveDisplayAspect()`, dimensions return to the persisted global aspect.
  - when `TEST_MODE_ENABLED` is true, pinned aspect still resolves to `SCREEN_WIDTH_PIXELS == 320`.

- [ ] Test no persistence leak.

  Apply a non-stock profile, call `saveConfig()`, create a new standalone service from the same temp dir, and assert global gameplay keys are not the profile values.

- [ ] Run failing applier tests.

  ```powershell
  mvn "-Dtest=TestLaunchProfileApplier" test
  ```

  Expected output before implementation: compilation fails because `LaunchProfileApplier` does not exist.

## Task 8: Implement Launch Profile Applier

- [ ] Create `src/main/java/com/openggf/game/launch/LaunchProfileApplier.java`.

  Constructor:

  ```java
  public LaunchProfileApplier(SonicConfigurationService configService)
  ```

  Public API:

  ```java
  public void apply(LaunchProfile profile)
  ```

- [ ] Map profile values to session overrides.

  Exact mappings:

  - `profile.rewind()` -> `LIVE_REWIND_ENABLED`
  - `profile.crossGameSource().equals("off")` -> `CROSS_GAME_FEATURES_ENABLED=false`
  - donation on -> `CROSS_GAME_FEATURES_ENABLED=true`, `CROSS_GAME_SOURCE=<donor>`
  - donation off -> `CROSS_GAME_SOURCE=<getDefaultValue(CROSS_GAME_SOURCE)>`
  - `profile.debugTools()` -> `DEBUG_VIEW_ENABLED`
  - `profile.mainCharacter()` -> `MAIN_CHARACTER_CODE`
  - `profile.sidekick().equals("none") ? "" : profile.sidekick()` -> `SIDEKICK_CHARACTER_CODE`
  - `profile.aspect().equals("global")` -> no `DISPLAY_ASPECT` override
  - pinned aspect -> `DISPLAY_ASPECT=<WidescreenAspect enum name>`

- [ ] Do not clear inside the applier.

  `GameLoop` owns clear-then-apply sequencing. Keeping the applier side-effect narrow makes it testable and prevents accidental clearing if a future caller batches multiple operations.

- [ ] Run applier tests.

  ```powershell
  mvn "-Dtest=TestLaunchProfile,TestLaunchProfileStore,TestLaunchProfileApplier,TestDisplayAspectResolution" test
  ```

  Expected output: `BUILD SUCCESS`.

## Task 9: Add Panel And Master Title UI Tests

- [ ] Create `src/test/java/com/openggf/game/TestLaunchConfigPanel.java`.

  Use a real `InputHandler` and `handleKeyEvent(key, GLFW_PRESS/GLFW_RELEASE)` to simulate one-frame key presses. Do not require GL or call `render()`.

- [ ] Test panel navigation.

  Cases:

  - configured `UP`/`DOWN` move the selected row and wrap.
  - configured `LEFT`/`RIGHT` cycle the selected row and wrap.
  - hardcoded `GLFW_KEY_BACKSPACE` resets all rows to stock.
  - hardcoded `GLFW_KEY_TAB` closes and returns a close result.
  - hardcoded `GLFW_KEY_ESCAPE` closes and returns a close result.
  - closing saves exactly once through the injected `LaunchProfileStore`.

- [ ] Test row display data.

  The panel should expose package-private row view data without requiring a renderer:

  ```java
  record RowView(String label, String value, boolean stock, boolean nonStandard) {}
  ```

  Assert:

  - Widescreen global label includes the resolved global preset, such as `Global (WIDE_16_9)` rendered as `Global (16:9)`.
  - pinned widescreen values show the configured preset label.
  - non-standard rows report `nonStandard=true`.
  - S3K tails-alone is non-stock but standard.

- [ ] Update `src/test/java/com/openggf/game/TestMasterTitleScreenLayout.java`.

  Add tests for a static hover-line helper on `MasterTitleScreen`:

  ```java
  static String launchHoverLine(int enabledCount)
  ```

  - `launchHoverLine(0)` returns exactly `Stock launch - Tab to configure`.
  - `launchHoverLine(1)` returns exactly `1 option enabled - Tab to configure`.
  - `launchHoverLine(3)` returns exactly `3 options enabled - Tab to configure`.

  The separator is a plain hyphen. Do NOT use U+00B7 `·` — it is not in the PixelFont atlas (`PixelFont.java:26-30`) and would silently not render.

- [ ] Add master-title integration tests.

  Update or add tests near `TestMasterTitleScreenLayout`:

  - Tab opens the panel only when the selected ROM is available.
  - when `TEST_MODE_ENABLED` is true, trace picker handling takes precedence and Tab does not open the launch panel.
  - while the panel is open, game-select left/right and confirm input are ignored because `update()` delegates to the panel.
  - closing the panel saves and returns to normal master-title input.

  Add these test seams to `MasterTitleScreen`:

  - constructor accepting `LaunchProfileStore`,
  - `boolean isLaunchConfigPanelOpenForTest()`,
  - `LaunchProfile currentLaunchProfileForTest()`,
  - `void setRomAvailableForTest(GameEntry entry, boolean available)`.

- [ ] Run failing UI tests.

  ```powershell
  mvn "-Dtest=TestLaunchConfigPanel,TestMasterTitleScreenLayout" test
  ```

  Expected output before implementation: compilation fails because `LaunchConfigPanel` and the new master-title helpers do not exist.

## Task 10: Implement Panel And Master Title Integration

- [ ] Create `src/main/java/com/openggf/game/LaunchConfigPanel.java`.

  Follow the `TestModeTracePicker` pattern:

  - constructor receives `MasterTitleScreen.GameEntry`, the current `LaunchProfile`, `LaunchProfileStore`, `SonicConfigurationService` (for configured directional keys and the resolved global aspect label), `PixelFont`, and `TexturedQuadRenderer`;
  - `update(InputHandler inputHandler)` is pure state-machine logic and returns or stores a `Result` enum with `NONE` and `CLOSED`;
  - `consumeResult()` returns and clears the last result;
  - `render(int viewportWidth)` draws a black matte, title, rows, markers, and footer;
  - `rowViews()` exposes immutable row data for tests.

- [ ] Use configured directional keys.

  In `LaunchConfigPanel.update()`:

  - read `SonicConfiguration.UP`, `DOWN`, `LEFT`, and `RIGHT` via `configService.getInt(...)`;
  - use hardcoded `GLFW_KEY_TAB`, `GLFW_KEY_ESCAPE`, and `GLFW_KEY_BACKSPACE`;
  - do not use hardcoded arrow-key constants for movement.

- [ ] Render markers and footer with atlas-safe glyphs.

  - stock rows: dim `(stock)` suffix;
  - non-standard values: amber tint plus `*` suffix;
  - footer legend: `* not possible in the original game`;
  - footer controls line listing Up/Down, Left/Right, Backspace, Tab/Esc.

- [ ] Integrate with `src/main/java/com/openggf/game/MasterTitleScreen.java`.

  Add fields:

  ```java
  private final LaunchProfileStore launchProfileStore;
  private LaunchConfigPanel launchConfigPanel;
  private boolean programmaticSelection;
  ```

  Behavior:

  - initialize `launchProfileStore` from the screen's `SonicConfigurationService`;
  - if `launchConfigPanel != null`, delegate `update()` and `draw()` to it, then save/close on `CLOSED`;
  - keep the existing trace-picker branch before Tab handling;
  - in normal active mode, hardcoded Tab opens the panel for the selected `GameEntry` only when `romAvailable[selectedIndex]` is true;
  - draw the hover line (`launchHoverLine(...)`) between the preview/matte and game menu, centered on `viewportWidth`, dim grey at stock and gold-tinted when diverged — only when `romAvailable[selectedIndex]` is true (missing-ROM games show no hover line, and Tab is a no-op for them);
  - keep the existing Enter/JUMP confirm behavior.

- [ ] Add programmatic launch tracking.

  `MasterTitleScreen.selectEntry(GameEntry entry)` is used by `TraceSessionLauncher` and must mark `programmaticSelection = true`. User confirmation in `update()` must mark `programmaticSelection = false` before setting `gameSelected = true`.

  Add:

  ```java
  public boolean isProgrammaticSelection()
  ```

  `GameLoop` will capture this value before fade-to-black starts.

- [ ] Run panel/master-title tests.

  ```powershell
  mvn "-Dtest=TestLaunchConfigPanel,TestMasterTitleScreenLayout,TestModeTracePickerTest" test
  ```

  Expected output: `BUILD SUCCESS`.

## Task 11: Add Launch Lifecycle Tests In GameLoop And Trace Launcher

- [ ] Update `src/test/java/com/openggf/TestGameLoop.java`.

  Add tests for `doExitMasterTitleScreen` behavior by starting a master-title selection, completing the fade manager callback, and asserting config state through the shared standalone/bootstrap config service:

  - user launch clears stale session overrides, applies the selected game's profile, calls `resolveDisplayAspect()`, and only then invokes `masterTitleExitHandler`;
  - programmatic launch clears stale session overrides, skips profile application, calls `resolveDisplayAspect()`, and then invokes `masterTitleExitHandler`;
  - a programmatic launch immediately after a user launch sees base-map values, not leftover session overrides;
  - return-to-master-title clears session overrides and calls `resolveDisplayAspect()` before invoking `returnToMasterTitleHandler`.

  Keep the existing `testMasterTitleScreenSelectionStartsBootstrapFadeWithoutGameplayRuntime` behavior: the exit handler must still wait until fade completion.

- [ ] Add trace-launch clear test.

  Add a package-private helper in `TraceSessionLauncher`:

  ```java
  static void clearLaunchSessionOverridesBeforeTraceSnapshot(SonicConfigurationService config)
  ```

  The helper should call `clearSessionOverrides()` and `resolveDisplayAspect()`. Test it from `src/test/java/com/openggf/trace/replay/TraceReplaySessionBootstrapConfigTest.java` or a new `src/test/java/com/openggf/TestTraceSessionLauncherLaunchConfig.java`.

  Required assertion:

  - set base `MAIN_CHARACTER_CODE="sonic"`, `SIDEKICK_CHARACTER_CODE="tails"`, `CROSS_GAME_FEATURES_ENABLED=false`;
  - set session overrides to different profile values;
  - call the helper;
  - `TraceReplaySessionBootstrap.snapshotGameplayConfig()` captures the base values, not the profile overrides;
  - `prepareConfiguration()` writes trace-required base-map values that are no longer masked by session overrides.

- [ ] Run failing lifecycle tests.

  ```powershell
  mvn "-Dtest=TestGameLoop,TraceReplaySessionBootstrapConfigTest,TestTraceSessionLauncherLaunchConfig" test
  ```

  Expected output before implementation: compilation or assertion failures for missing lifecycle code. If no new `TestTraceSessionLauncherLaunchConfig` file is created, omit it from the `-Dtest` list.

## Task 12: Implement Launch Lifecycle

- [ ] Update `src/main/java/com/openggf/GameLoop.java`.

  Add fields instantiated lazily from `configService` (keep them in `GameLoop`, not `Engine`):

  ```java
  private LaunchProfileStore launchProfileStore;
  private LaunchProfileApplier launchProfileApplier;
  ```

- [ ] Capture programmatic selection before fade.

  In `exitMasterTitleScreen(MasterTitleScreen masterScreen)`:

  ```java
  String selectedGameId = masterScreen.getSelectedGameId();
  boolean programmaticSelection = masterScreen.isProgrammaticSelection();
  fadeManager.startFadeToBlack(() -> doExitMasterTitleScreen(selectedGameId, programmaticSelection));
  ```

  Keep the existing fade-active guard.

- [ ] Clear/apply at the top of `doExitMasterTitleScreen`.

  Required order:

  ```java
  private void doExitMasterTitleScreen(String selectedGameId, boolean programmaticSelection) {
      configService.clearSessionOverrides();
      MasterTitleScreen.GameEntry entry = MasterTitleScreen.GameEntry.fromGameId(selectedGameId);
      if (!programmaticSelection) {
          LaunchProfile profile = launchProfileStore().load(entry);
          launchProfileApplier().apply(profile);
      }
      configService.resolveDisplayAspect();
      if (masterTitleExitHandler != null) {
          masterTitleExitHandler.accept(selectedGameId);
      }
      // ... existing callback staging and game-mode handling unchanged
  }
  ```

  `GameEntry.fromGameId(String)` does not exist yet — add it to `MasterTitleScreen.GameEntry` (case-insensitive match on `gameId`, throw `IllegalArgumentException` for unknown IDs) and test it in `TestMasterTitleScreenLayout`.

- [ ] Clear on return to master title.

  In `GameLoop.returnToMasterTitle()`:

  ```java
  configService.clearSessionOverrides();
  configService.resolveDisplayAspect();
  if (returnToMasterTitleHandler != null) {
      returnToMasterTitleHandler.run();
  }
  ```

- [ ] Clear at trace launch top.

  In `src/main/java/com/openggf/TraceSessionLauncher.java`, call the helper before `TraceReplaySessionBootstrap.snapshotGameplayConfig()` (the bootstrap itself resolves config via `GameServices.configuration()` — use the same accessor):

  ```java
  clearLaunchSessionOverridesBeforeTraceSnapshot(GameServices.configuration());
  TraceReplaySessionBootstrap.ConfigSnapshot configSnapshot =
          TraceReplaySessionBootstrap.snapshotGameplayConfig();
  ```

  Keep this before trace data/movie load and before `prepareConfiguration()`.

- [ ] Run lifecycle tests.

  ```powershell
  mvn "-Dtest=TestGameLoop,TraceReplaySessionBootstrapConfigTest,TestTraceSessionLauncherRewindPresentation" test
  ```

  Expected output: `BUILD SUCCESS`.

## Task 13: Add Engine Cache And Resize Tests

- [ ] Add focused `Engine` tests in `src/test/java/com/openggf/TestEngine.java`.

  Use reflection against an `Engine` constructed with a standalone/mock `EngineContext`; do not require an initialized GLFW window. Add package-private pure helpers to avoid calling GL in tests.

- [ ] Test cache refresh.

  Cases:

  - `debugViewEnabled` is false at construction, session override sets `DEBUG_VIEW_ENABLED=true`, `refreshLaunchSessionCachedConfig()` updates the field to true.
  - clearing the override and calling `refreshLaunchSessionCachedConfig()` updates the field back to the persisted value.

- [ ] Test resolved display dimension read.

  Add a package-private record/helper:

  ```java
  record ResolvedDisplayDimensions(int pixelWidth, int pixelHeight, int windowWidth, int windowHeight) {}
  ResolvedDisplayDimensions readResolvedDisplayDimensionsForLaunch()
  ```

  Assert:

  - persisted native global yields `pixelWidth=320`, `pixelHeight=224`, `windowWidth=640`, `windowHeight=448`;
  - pinned `"WIDE_16_9"` after `resolveDisplayAspect()` yields `pixelWidth=400`, `pixelHeight=224`, `windowWidth=800`, `windowHeight=448`;
  - pinned `"ULTRA_21_9"` under `TEST_MODE_ENABLED=true` yields `pixelWidth=320`.

- [ ] Test the 320-to-400 counterexample.

  Set the engine's current `windowWidth/windowHeight` fields to `640/448`, set a session aspect override to `"WIDE_16_9"`, call `resolveDisplayAspect()`, then call the non-GL part of `applyResolvedDisplayDimensions()`. Assert the fields become `800/448`, not `400/224`.

- [ ] Add a source or behavior guard for `GraphicsManager` projection width.

  Assert the display-refresh helper updates `graphicsManager.setProjectionWidth((int) projectionWidth)` (`GraphicsManager.setProjectionWidth` exists at `GraphicsManager.java:1021`), because title-card and widescreen render paths read `GraphicsManager.getProjectionWidth()`.

- [ ] Run failing engine tests.

  ```powershell
  mvn "-Dtest=TestEngine,TestDisplayAspectResolution,TestWidescreenNativeRegression,TestS2TitleCardManagerWidescreenGuard" test
  ```

  Expected output before implementation: assertion failures or missing helper methods. (`TestWidescreenNativeRegression`, `TestS2TitleCardManagerWidescreenGuard`, and `TestTitleCardWidescreenCentering` live in `src/test/java/com/openggf/widescreen/`.)

## Task 14: Implement Engine Cache And Resize Hooks

- [ ] Update `src/main/java/com/openggf/Engine.java`.

  Add:

  ```java
  private void refreshLaunchSessionCachedConfig() {
      debugViewEnabled = configService.getBoolean(SonicConfiguration.DEBUG_VIEW_ENABLED);
  }
  ```

  Audit remaining managed keys with:

  ```powershell
  rg -n "LIVE_REWIND_ENABLED|CROSS_GAME_FEATURES_ENABLED|CROSS_GAME_SOURCE|DEBUG_VIEW_ENABLED|MAIN_CHARACTER_CODE|SIDEKICK_CHARACTER_CODE|DISPLAY_ASPECT|SCREEN_WIDTH_PIXELS|SCREEN_WIDTH|SCREEN_HEIGHT" src/main/java/com/openggf -g "*.java"
  ```

  For each hit, classify the consumer: Engine-lifetime (lives across master-title launches — needs refresh in this hook plus a test) vs per-gameplay-session (rebuilt by `SessionManager` after the overrides apply — needs nothing). The expected outcome is that only `Engine` itself needs refresh, but verify rather than assume.

- [ ] Add `applyResolvedDisplayDimensions()`.

  Required behavior:

  ```java
  void applyResolvedDisplayDimensions() {
      int resolvedPixelWidth = configService.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS);
      int resolvedPixelHeight = configService.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS);
      int resolvedWindowWidth = configService.getInt(SonicConfiguration.SCREEN_WIDTH);
      int resolvedWindowHeight = configService.getInt(SonicConfiguration.SCREEN_HEIGHT);

      realWidth = resolvedPixelWidth;
      realHeight = resolvedPixelHeight;
      projectionWidth = realWidth;
      windowWidth = resolvedWindowWidth;
      windowHeight = resolvedWindowHeight;
      graphicsManager.setProjectionWidth((int) projectionWidth);

      if (glfwInitialized && window != 0L) {
          isSnappingWindowSize = true;
          glfwSetWindowSize(window, resolvedWindowWidth, resolvedWindowHeight);
          isSnappingWindowSize = false;
          reshape(resolvedWindowWidth, resolvedWindowHeight);
      }
  }
  ```

  If `reshape()` must also run in tests without GL, split the pure field update into a package-private helper and keep GL calls guarded. Do not call `snapWindowToIntegerScale()` from this method.

- [ ] Call the hooks after master-title launch overrides apply.

  In `Engine.exitMasterTitleScreen(String gameId)` (`Engine.java:516`, registered as the `masterTitleExitHandler`), before ROM/default-game setup and before `initializeGame()`:

  ```java
  refreshLaunchSessionCachedConfig();
  applyResolvedDisplayDimensions();
  ```

- [ ] Call the hooks after return-to-master clears overrides.

  In `Engine.returnToMasterTitleScreen()` (`Engine.java:584`), after `GameLoop.returnToMasterTitle()` has cleared and re-resolved config but before recreating/drawing the master title screen:

  ```java
  refreshLaunchSessionCachedConfig();
  applyResolvedDisplayDimensions();
  ```

- [ ] Verify width-dependent resource audit.

  `GraphicsManager.getTilePriorityFBO(width, height)` already resizes the tile-priority FBO on demand. Confirm no other FBO/render target in `src/main/java/com/openggf/graphics` permanently bakes the old screen width:

  ```powershell
  rg -n "glGenFramebuffers|FboHelper|createFbo|new int\[.*width" src/main/java/com/openggf/graphics -g "*.java"
  ```

  If a baked allocation is found, recreate or resize it in `applyResolvedDisplayDimensions()` and add a focused test.

- [ ] Run engine and widescreen tests.

  ```powershell
  mvn "-Dtest=TestEngine,TestDisplayAspectResolution,TestWidescreenNativeRegression,TestS2TitleCardManagerWidescreenGuard,TestTitleCardWidescreenCentering" test
  ```

  Expected output: `BUILD SUCCESS`.

## Task 15: Wire Documentation And Config Samples

- [ ] Update user-facing configuration docs.

  Files:

  - `CONFIGURATION.md`

  Document:

  - the new `launch:` section shape,
  - stock profile defaults,
  - `crossGameSource: "off"` behavior,
  - `aspect: "global"` inheritance,
  - that profile application is session-only and cannot persist into global gameplay keys,
  - panel controls: configured Up/Down/Left/Right, hardcoded Tab/Esc/Backspace.

- [ ] Update `CHANGELOG.md`.

  Add a concise entry for the master-title launch configuration screen and session-only profile application. This is staged with the Task 17 wiring commit so the changelog-justification hook passes.

- [ ] Re-run docs/catalog checks.

  ```powershell
  rg -n "launch:|crossGameSource|aspect: \"global\"|Stock launch|Tab" CONFIGURATION.md config.yaml src/main/resources/config.yaml
  ```

  Expected output: the new launch section and controls are documented in all three files.

## Task 16: Final Verification

- [ ] Run all focused tests touched by the feature.

  ```powershell
  mvn "-Dtest=TestConfigCatalog,TestSonicConfigurationSessionOverrides,TestConfigYamlWriter,TestConfigServiceYamlRoundTrip,TestSonicConfigurationFileBootstrap,TestDisplayAspectResolution,TestWidescreenNativeRegression,TestLaunchProfile,TestLaunchProfileStore,TestLaunchProfileApplier,TestLaunchConfigPanel,TestMasterTitleScreenLayout,TestModeTracePickerTest,TestGameLoop,TraceReplaySessionBootstrapConfigTest,TestTraceSessionLauncherRewindPresentation,TestEngine,TestS2TitleCardManagerWidescreenGuard,TestTitleCardWidescreenCentering" test
  ```

  Expected output: `BUILD SUCCESS`.

- [ ] Run the full test suite.

  ```powershell
  mvn test
  ```

  Expected output: `BUILD SUCCESS`. Known acceptable noise: lwjgl/glfw `UnsatisfiedLinkError` chatter and occasional `TestBundledConfigResource` flakes are environmental, not feature regressions — re-run a flaked class once before investigating.

- [ ] Run package build.

  ```powershell
  mvn package
  ```

  Expected output: `BUILD SUCCESS` and the jar is produced under `target/`.

- [ ] Inspect final diff and ensure unrelated files are not staged.

  ```powershell
  git status --short
  git diff -- src/main/java/com/openggf/configuration/SonicConfiguration.java src/main/java/com/openggf/configuration/SonicConfigurationService.java src/main/java/com/openggf/configuration/ConfigCatalog.java src/main/java/com/openggf/game/launch src/main/java/com/openggf/game/LaunchConfigPanel.java src/main/java/com/openggf/game/MasterTitleScreen.java src/main/java/com/openggf/GameLoop.java src/main/java/com/openggf/TraceSessionLauncher.java src/main/java/com/openggf/Engine.java CONFIGURATION.md CHANGELOG.md config.yaml src/main/resources/config.yaml
  ```

  Expected output: only launch-config related changes are present in the reviewed diff. Existing unrelated dirty files remain unstaged.

## Task 17: Commit In Coherent Slices

Branch policy: every commit message needs the seven trailers, each starting with `updated` or `n/a`. `feat:` commits touching `src/main/` must either stage `CHANGELOG.md` with `Changelog: updated` or justify with `Changelog: n/a: <reason>` — a bare `n/a` is rejected by the commit-msg hook. The full feature changelog entry lands with the wiring commit (commit 3).

- [ ] Commit config schema and overlay.

  ```powershell
  git add src/main/java/com/openggf/configuration/SonicConfiguration.java src/main/java/com/openggf/configuration/SonicConfigurationService.java src/main/java/com/openggf/configuration/ConfigCatalog.java src/test/java/com/openggf/configuration/TestConfigCatalog.java src/test/java/com/openggf/configuration/TestSonicConfigurationSessionOverrides.java config.yaml src/main/resources/config.yaml
  git commit -m "feat: add launch profile config keys and session-override overlay

Changelog: n/a: feature changelog entry lands with the master-title wiring commit on this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
  ```

  Expected output: commit succeeds and the hook accepts the trailers.

- [ ] Commit launch profile model/store/applier.

  ```powershell
  git add src/main/java/com/openggf/game/launch src/test/java/com/openggf/game/launch
  git commit -m "feat: add launch profile model, store, and applier

Changelog: n/a: feature changelog entry lands with the master-title wiring commit on this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
  ```

  Expected output: commit succeeds.

- [ ] Commit master-title panel, lifecycle wiring, and changelog.

  ```powershell
  git add src/main/java/com/openggf/game/LaunchConfigPanel.java src/main/java/com/openggf/game/MasterTitleScreen.java src/main/java/com/openggf/GameLoop.java src/main/java/com/openggf/TraceSessionLauncher.java src/main/java/com/openggf/Engine.java src/test/java/com/openggf/game/TestLaunchConfigPanel.java src/test/java/com/openggf/game/TestMasterTitleScreenLayout.java src/test/java/com/openggf/TestGameLoop.java src/test/java/com/openggf/TestEngine.java src/test/java/com/openggf/trace/replay/TraceReplaySessionBootstrapConfigTest.java CHANGELOG.md
  git commit -m "feat: add master title launch options panel and lifecycle

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
  ```

  Expected output: commit succeeds. If `TestTraceSessionLauncherLaunchConfig.java` was created in Task 11, add it to the `git add` list.

- [ ] Commit documentation.

  ```powershell
  git add CONFIGURATION.md
  git commit -m "docs: document launch profiles and config panel

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: updated
Skills: n/a"
  ```

  Expected output: commit succeeds.

- [ ] Note for merge time: when this branch merges into `develop`, the merge must stage a `README.md` update summarizing the change in the release/change log section (repo merge policy). Handle this during the finishing-a-development-branch flow, not now.

## Implementation Notes

- `LaunchProfileStore.save()` can call `saveConfig()` because it writes only `launch.*` keys. `LaunchProfileApplier.apply()` must never call `saveConfig()`.
- Use `getDefaultValue(SonicConfiguration.CROSS_GAME_SOURCE)` (made public in Task 2) for the donation-off `crossGame.source` override.
- `clearSessionOverrides()` must not clear `transientResolved`; use `resolveDisplayAspect()` after clear when display dimensions matter.
- `resolveDisplayAspect()` already forces native 320px in test mode. Preserve that behavior.
- Keep test mode precedence in `MasterTitleScreen.update()`: trace picker logic remains before launch panel Tab handling.
- `MasterTitleScreen.selectEntry()` is the programmatic path today. If a future direct caller needs user semantics, add an explicit method instead of weakening the programmatic flag.
- Keep profile logic out of `Engine.java`; `Engine` should only refresh its lifetime caches and display dimensions after `GameLoop` has applied or cleared config overlays.
- All user-visible panel/hover strings must stick to PixelFont atlas glyphs (ASCII letters/digits plus the specials in `PixelFont.ROW2-ROW4`). No `·`.
