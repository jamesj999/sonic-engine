# Master Title Legal Disclaimer Screen — Design

**Date:** 2026-05-26
**Status:** Approved (brainstorming complete)
**Owner:** Farrell

## Problem

OpenGGF currently boots straight into the `MasterTitleScreen` (game-select).
The README contains a legal disclaimer noting that no ROM data or copyrighted
assets are bundled, but a user running the compiled engine never sees it. Peer
projects (Ship of Harkinian, SRB2, mGBA, RSDK decompilations) ship their
disclaimers as README/website text only, so adding an in-engine notice puts
OpenGGF in a stronger posture than most.

This design adds a one-screen legal notice rendered on top of the engine before
the master title screen runs. It states the asset/ROM policy explicitly, names
the IP-holder, and disclaims affiliation.

## Scope

In scope:

- A new full-screen disclaimer screen shown on engine startup.
- Configurable via `config.json` (off by default in CI/test contexts; on by
  default for end users).
- Integrated with the existing `FadeManager` for entry, exit, and onward
  master-title fade.
- Hardcoded disclaimer text (not localized, not configurable — the text is
  legal content that should not be silently editable from config).

Out of scope:

- Per-launch "accept once and remember" persistence. The user chose
  configurable display frequency; persistence is a follow-on if ever wanted.
- Localization.
- Branding artwork on the disclaimer screen (deliberately plain white-on-black).
- Audio. The screen renders silently.

## Architecture

### New class: `LegalDisclaimerScreen`

Location: `com.openggf.game.LegalDisclaimerScreen` (sibling of
`MasterTitleScreen`).

Lifetime: created in `Engine.init()` when the new config flag is set, lives
until the user dismisses it, then cleaned up. No singleton, no static state.

Owned GL resources:

- `TexturedQuadRenderer` (one instance, owned)
- `PixelFont` (one instance, owned, loads `pixel-font.png` like
  `MasterTitleScreen` does at line 144)
- 1×1 solid-white texture (tinted black at draw time for the full-screen fill;
  same pattern as `MasterTitleScreen.createSolidWhiteTexture`)

The class is intentionally small (~120 lines). It does not load any
ROM-derived art and does not depend on `GameplayModeContext`,
`GameModuleRegistry`, or any per-game provider — it must run before those
exist.

### State machine

```
INACTIVE (default before initialize)
   │
   │ initialize() runs FadeManager.startFadeFromBlack(this::onFadeInComplete)
   ▼
FADE_IN
   │ fade-from-black complete callback
   ▼
READING (5 s / 300 frames at 60 fps)
   │ frame counter reaches 300
   ▼
DISMISSIBLE  ("Press any key to continue" appears, pulses)
   │ InputHandler.isAnyKeyJustPressed() returns true
   ▼
EXITING       (FadeManager.startFadeToBlack(this::onFadeOutComplete))
   │ fade-to-black complete callback
   ▼
(handed off to Engine.exitLegalDisclaimer, which initializes
 MasterTitleScreen and starts its fade-from-black)
```

Input is ignored in every state except `DISMISSIBLE`.

### `InputHandler` API addition

`LegalDisclaimerScreen` lives in `com.openggf.game`. `InputHandler` currently
exposes only per-key `isKeyPressed(int)` and `isKeyDown(int)`; its `keys[]`
and `previousKeys[]` arrays are package-private to `com.openggf.control`, so
the disclaimer cannot scan them directly. Two options were considered:

1. Add a public helper `InputHandler.isAnyKeyJustPressed()` that returns
   `true` when at least one key transitioned from not-pressed to pressed
   during the current frame.
2. Narrow the dismiss UX to a fixed key set (e.g., `Enter`, `Space`,
   `Escape`, the configured Jump key).

Option 1 is required to honor the agreed UX ("Press any key to continue").
Option 2 would change what the prompt promises. Implementation:

```java
public boolean isAnyKeyJustPressed() {
    for (int i = 0; i < MAX_KEYS; i++) {
        if (keys[i] && !previousKeys[i]) {
            return true;
        }
    }
    return false;
}
```

This is a small focused addition — no other call sites need it yet, but it
is reusable (e.g., for any future "press any key" prompt). It must be added
before `LegalDisclaimerScreen` is implemented.

### New config key

`SonicConfiguration.SHOW_LEGAL_DISCLAIMER_ON_STARTUP` (boolean, default
`true`). Recorded in `CONFIGURATION.md` alongside
`MASTER_TITLE_SCREEN_ON_STARTUP`.

When `false`, the disclaimer is skipped entirely and `Engine.init()` proceeds
to its existing path (master title or direct gameplay). Test harnesses
(`HeadlessTestRunner`, trace tests, CI) will set this `false` in the test
config so they do not have to drive past a 5-second screen.

### New game mode

`GameMode.LEGAL_DISCLAIMER`, ordered before `MASTER_TITLE_SCREEN`. The
`GameLoop.step()` dispatch adds a new branch immediately above the existing
`MASTER_TITLE_SCREEN` branch (around `GameLoop.java:460`):

```java
if (currentGameMode == GameMode.LEGAL_DISCLAIMER) {
    LegalDisclaimerScreen disclaimer = legalDisclaimerSupplier != null
            ? legalDisclaimerSupplier.get() : null;
    if (disclaimer != null) {
        disclaimer.update(inputHandler);
        if (disclaimer.isDismissed()) {
            exitLegalDisclaimer(disclaimer);
        }
    }
    inputHandler.update();
    return;
}
```

Rendering dispatch for pre-gameplay modes lives in `Engine.draw()` (around
`Engine.java:1388`), not in `GameLoop`. A matching `LEGAL_DISCLAIMER` branch
is added there next to the existing `MASTER_TITLE_SCREEN` branch:

```java
if (getCurrentGameMode() == GameMode.LEGAL_DISCLAIMER) {
    if (camera != null) {
        camera.setX((short) 0);
        camera.setY((short) 0);
    }
    if (legalDisclaimerScreen != null) {
        legalDisclaimerScreen.setProjectionMatrix(getProjectionMatrixBuffer());
        legalDisclaimerScreen.draw();
    }
    return;
}
```

The camera zeroing mirrors the master-title branch — pre-gameplay screens
must not inherit stale camera state from any prior session.

### Engine wiring

`Engine.init()` change (around line 386):

```java
boolean showLegalDisclaimer = configService.getBoolean(
        SonicConfiguration.SHOW_LEGAL_DISCLAIMER_ON_STARTUP);
boolean masterTitleOnStartup = configService.getBoolean(
        SonicConfiguration.MASTER_TITLE_SCREEN_ON_STARTUP);

if (showLegalDisclaimer) {
    legalDisclaimerScreen = new LegalDisclaimerScreen(configService,
            graphicsManager.getFadeManager());
    legalDisclaimerScreen.initialize();
    gameLoop.setGameMode(GameMode.LEGAL_DISCLAIMER);
    // master title (if enabled) is built lazily in exitLegalDisclaimer
} else if (masterTitleOnStartup) {
    // existing path
} else {
    initializeGame();
}
```

New methods on `Engine`:

- `LegalDisclaimerScreen getLegalDisclaimerScreen()` — for `GameLoop` to read
  via supplier (matches `getMasterTitleScreen` pattern).
- `void exitLegalDisclaimer()` — called by `GameLoop` after the disclaimer's
  fade-to-black completes. Cleans up disclaimer GL resources, then either:
  - if `MASTER_TITLE_SCREEN_ON_STARTUP=true`, builds `MasterTitleScreen`,
    switches mode to `MASTER_TITLE_SCREEN`, and calls
    `FadeManager.startFadeFromBlack(null)` to reveal the title.
  - otherwise, calls `initializeGame()` and then unconditionally calls
    `FadeManager.startFadeFromBlack(null)` to reveal whichever startup mode
    `enterConfiguredStartupMode()` selected.

  **`exitLegalDisclaimer` owns the post-disclaimer reveal fade in every
  case.** This is deliberate, not a special case for direct gameplay:
  `Engine.enterConfiguredStartupMode()` (around `Engine.java:670`) routes to
  one of `gameLoop.initializeTitleScreenMode()` (`GameLoop.java:2473`),
  `gameLoop.initializeLevelSelectMode()` (`GameLoop.java:2808`), or
  `loadDefaultStartingLevel(true)` (`Engine.java:682`). None of these three
  starts its own fade — they are stateless mode-setup methods that arrange
  managers and providers but leave the screen black. The disclaimer exit
  handler therefore must call `startFadeFromBlack(null)` itself after
  `initializeGame()` returns, regardless of which sub-path ran. This matches
  the existing `doExitMasterTitleScreen` behavior at `GameLoop.java:2454`,
  which also calls `startFadeFromBlack(null)` unconditionally after the mode
  is set up. Owning the reveal fade in one place avoids any split where some
  startup paths get a fade-in and others do not.

The fade chain for the common case is:

```
[engine boot]
  → fade-from-black over disclaimer text
  → 5s reading
  → key press → fade-to-black
  → cleanup disclaimer + init master title
  → fade-from-black over master title artwork
  → user picks game (existing flow takes over)
```

This is three independent fades, all driven by `FadeManager`, all in the same
style already used for `exitMasterTitleScreen` (`GameLoop.java:2416-2429`).

### Hand-off boundaries

`LegalDisclaimerScreen` does not know about `MasterTitleScreen`. It exposes
`isDismissed()`, returning `true` only after the fade-to-black callback has
fired. `GameLoop`/`Engine` own the transition: the loop polls `isDismissed()`
each frame and invokes `Engine.exitLegalDisclaimer()` once. This keeps the
disclaimer class focused and lets it be removed cleanly if the policy ever
changes.

`Engine` exposes the screen to `GameLoop` via the same supplier-injection
pattern used for `MasterTitleScreen`:

```java
gameLoop.setLegalDisclaimerScreenSupplier(() -> legalDisclaimerScreen);
gameLoop.setLegalDisclaimerExitHandler(this::exitLegalDisclaimer);
```

with a matching `getLegalDisclaimerScreen()` accessor on `Engine`.

### Visual layout (320 × 224)

Solid black fill via 1×1 white texture tinted RGBA(0, 0, 0, 1).

```
Y=22  ┌──────────────────────────────────────────┐
      │                                          │
Y=22  │              LEGAL NOTICE                │  white, brighter
      │                                          │
Y=48  │  OpenGGF is an independent, open-source  │  body, white,
      │  reimplementation of the original Sonic  │  ~40 char lines
      │  the Hedgehog games for the Sega Mega    │  centered horizontally
      │  Drive / Genesis. It is developed and    │
      │  verified against community-maintained   │
      │  disassemblies.                          │
      │                                          │
Y=98  │  No copyrighted Sega assets are          │
      │  distributed with this engine. ROM data, │
      │  sprites, music, and all other game      │
      │  assets must be supplied by the user     │
      │  from a legally obtained copy.           │
      │                                          │
Y=148 │  This project is not affiliated with or  │
      │  endorsed by Sega. Sonic the Hedgehog    │
      │  and all related characters, names, and  │
      │  trademarks are the property of Sega     │
      │  Corporation, to which no claim is made. │
      │                                          │
Y=210 │         Press any key to continue        │  dim until t=5s,
      │                                          │  then pulses
Y=224 └──────────────────────────────────────────┘
```

Line wrapping happens at draw time using `PixelFont.measureWidth` against a
target line width (~280 px after side margins of 20 px each). The text is
stored as an array of paragraphs; each paragraph is word-wrapped at runtime
so that font-metric changes do not require re-laying out the strings.

The "Press any key to continue" line:

- Hidden entirely during `FADE_IN` and `READING`.
- Appears instantly when state transitions to `DISMISSIBLE` (clear signal that
  the gate has lifted).
- Pulses brightness using the same sine-modulation pattern as the menu cursor
  in `MasterTitleScreen.drawGameMenu` (`0.5 + 0.5 * sin(frame * 0.05)`).

### Disclaimer text (final, hardcoded)

```
LEGAL NOTICE

OpenGGF is an independent, open-source reimplementation
of the original Sonic the Hedgehog games for the Sega
Mega Drive / Genesis. It is developed and verified
against community-maintained disassemblies.

No copyrighted Sega assets are distributed with this
engine. ROM data, sprites, music, and all other game
assets must be supplied by the user from a legally
obtained copy.

This project is not affiliated with or endorsed by Sega.
Sonic the Hedgehog and all related characters, names,
and trademarks are the property of Sega Corporation, to
which no claim is made.

           Press any key to continue
```

Header is "LEGAL NOTICE". The body is three paragraphs in the order:
identity → asset/ROM policy → affiliation + IP acknowledgment.

Why this ordering: the first paragraph establishes the project's nature
(independent, open-source, built from disassemblies) before any claim is made
about what is or is not bundled, which implicitly defends the no-assets claim
that follows. The third paragraph holds the formal disclaimer in the most
prominent (terminal) position.

## Testing

`LegalDisclaimerScreen` owns GL resources (`TexturedQuadRenderer`,
`PixelFont`, the solid-white texture). Tests must respect that boundary:
state-machine logic is testable headlessly; the rendered screen is not.
Two-tier strategy:

- **Unit test (no GL):** Extract the state machine into a small POJO
  (e.g., `LegalDisclaimerState`) that `LegalDisclaimerScreen` composes. Fields
  are state, frame counter, dismissed flag. Tests cover the screen's lifecycle
  through method calls, not through GL:
  - Initial state is `FADE_IN`.
  - `onFadeInComplete()` transitions to `READING` and resets the frame counter.
  - `READING` ignores key presses for exactly 300 frames.
  - Frame 300 transitions to `DISMISSIBLE`.
  - `DISMISSIBLE` accepts any key and transitions to `EXITING`.
  - `EXITING` ignores further key presses.
- **Handoff test (no GL):** Drive `GameLoop.step()` with a fake disclaimer
  supplier returning a stub that immediately reports `isDismissed()=true`.
  Assert that `Engine.exitLegalDisclaimer()` runs and the mode advances
  `LEGAL_DISCLAIMER → MASTER_TITLE_SCREEN` (or `LEVEL`, per config). The
  fade chain itself is not exercised here — `FadeManager` is already covered
  by other tests. The point is the dispatch wiring.
- **Manual launch:** Run the engine, verify the disclaimer reads correctly,
  the 5-second gate works, the prompt pulses, and the
  black → text → black → title chain looks smooth. Repeat with
  `MASTER_TITLE_SCREEN_ON_STARTUP=false` and (`TITLE_SCREEN_ON_STARTUP=false`,
  `LEVEL_SELECT_ON_STARTUP=false`) to verify the direct-gameplay fade-from
  -black path works (this is the regression the agent review caught).
- **Test harness skip:** Trace replay tests and other automated test entry
  points must set `SHOW_LEGAL_DISCLAIMER_ON_STARTUP=false` in their test
  configuration so they do not need to drive frames past the disclaimer.
  `MASTER_TITLE_SCREEN_ON_STARTUP=false` is the existing equivalent.

Do not write a headless integration test that constructs the real
`LegalDisclaimerScreen` — the GL resource initialization is incompatible with
the test harness's no-OpenGL style.

## File-level impact

New files:

- `src/main/java/com/openggf/game/LegalDisclaimerScreen.java`
- `src/test/java/com/openggf/game/TestLegalDisclaimerScreen.java`

Modified files:

- `src/main/java/com/openggf/Engine.java` — boot wiring, `exitLegalDisclaimer`
  handler, draw dispatch.
- `src/main/java/com/openggf/GameLoop.java` — new `LEGAL_DISCLAIMER` branch in
  `step()` (draw lives in `Engine`).
- `src/main/java/com/openggf/control/InputHandler.java` — new public
  `isAnyKeyJustPressed()` helper.
- `src/main/java/com/openggf/game/GameMode.java` — new enum constant.
- `src/main/java/com/openggf/configuration/SonicConfiguration.java` — new
  config key.
- `src/main/java/com/openggf/configuration/SonicConfigurationService.java` —
  default value, if defaults are wired there.
- `CONFIGURATION.md` — document new key.
- `CHANGELOG.md` — release note entry.
- `README.md` — note that the in-engine notice now appears.
- `docs/status/known-discrepancies.md` — n/a (no game-behavior divergence).
- `docs/S3K_KNOWN_DISCREPANCIES.md` — n/a.
- `AGENTS.md` / `CLAUDE.md` — note the new boot phase for agent reference.

Test config (e.g., `HeadlessTestRunner` setup, trace harness configs) — add
the new key set to `false` so existing tests are unaffected.

## Open questions

None. Wording, ordering, fade behavior, and dismiss interaction are all
settled. External agent reviews on 2026-05-26 caught five issues — all
incorporated above:

1. Draw dispatch lives in `Engine.draw`, not `GameLoop.draw`.
2. `exitLegalDisclaimer` must call `startFadeFromBlack(null)` after
   `initializeGame()` for every sub-path, since none of
   `initializeTitleScreenMode` / `initializeLevelSelectMode` /
   `loadDefaultStartingLevel` runs its own intro fade.
3. The integration test must use a fake disclaimer supplier rather than
   constructing the GL-bound screen.
4. `InputHandler` needs a new public `isAnyKeyJustPressed()` helper —
   the existing per-key API and package-private arrays do not let
   `com.openggf.game` honor the "any key" prompt.
5. The original draft singled out direct gameplay as the only path needing
   the post-disclaimer reveal fade; that was wrong (see #2).

## Risk and rollback

Risk is low. The screen is additive: gating it on a new config flag (default
`true`) means anyone who hits an issue can set
`SHOW_LEGAL_DISCLAIMER_ON_STARTUP=false` in `config.json` to bypass it
entirely. The screen does not touch ROM-loading, gameplay, or any per-game
provider — failures cannot cascade into game state.
