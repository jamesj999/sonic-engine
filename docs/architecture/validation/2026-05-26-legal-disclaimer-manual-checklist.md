# Manual Verification Checklist: Legal Disclaimer Screen

**Date:** 2026-05-26
**Feature:** `LegalDisclaimerScreen` — startup disclaimer before master title
**Branch:** `develop`
**Artifact:** `target/OpenGGF-0.6.prerelease-jar-with-dependencies.jar`

Run all three scenarios below interactively on a machine with a display and a ROM in the
working directory. Each section lists which config keys to set in `config.json`, the launch
command, what to observe, and what constitutes a regression.

---

## Prerequisites

- ROM file present in working directory (`Sonic and Knuckles & Sonic 3 (W) [!].gen` or any
  supported ROM).
- `config.json` present (created on first launch if absent).
- Build is fresh: `mvn -q package -DskipTests` reports BUILD SUCCESS.
- No `TEST_MODE_ENABLED: true` in config (that replaces master title with the trace picker,
  which changes the post-disclaimer target).

---

## Scenario 1 — Default config: disclaimer shown, master title follows

### Config keys to set

```json
"SHOW_LEGAL_DISCLAIMER_ON_STARTUP": true,
"MASTER_TITLE_SCREEN_ON_STARTUP": true,
"TITLE_SCREEN_ON_STARTUP": true,
"LEVEL_SELECT_ON_STARTUP": false
```

These are the factory defaults. If you have not edited the block, no change is needed.

### Launch command

```
java -jar target/OpenGGF-0.6.prerelease-jar-with-dependencies.jar
```

### Expected sequence

1. **Fade-in from black** — the screen fades up from black over roughly 30 frames (~0.5 s).
2. **Disclaimer content visible** — black background, white centred header "LEGAL NOTICE",
   three body paragraphs in off-white smaller text at ~0.75 scale, word-wrapped within ~280 px:
   - Paragraph 1: OpenGGF independent reimplementation.
   - Paragraph 2: No copyrighted Sega assets distributed; ROM must be user-supplied.
   - Paragraph 3: Not affiliated with Sega; trademarks belong to Sega Corporation.
3. **5-second readability gate** — for the first ~5 seconds (300 frames at 60 fps) after the
   fade-in completes, no dismiss prompt appears and keypresses have no effect.
4. **Dismiss prompt appears** — after the gate, "Press any key to continue" appears at the
   bottom of the screen, pulsing in brightness (sine wave, period ~2 s).
5. **Any key dismisses** — pressing any key while the prompt is visible starts a
   **fade-to-black**.
6. **Transition to master title** — after the fade-to-black completes, the disclaimer GL
   resources are released, `MasterTitleScreen` initializes, and a **fade-from-black** reveals
   the master title / game-selection screen. The reveal fade is started by `exitLegalDisclaimer`
   via `FadeManager.startFadeFromBlack(null)`.

### What would indicate a regression

- Screen is pure black with no content (texture or font init failed — check logs for
  "Failed to initialize legal disclaimer screen").
- Dismiss prompt visible immediately (readability gate not enforced; `READING_FRAMES = 300`
  check failed).
- Keypresses dismiss before the gate expires.
- No fade-in at startup (FadeManager entry callback not wired).
- No fade-to-black on dismiss (FadeManager exit not triggered from `LegalDisclaimerState.tick`
  return value).
- Master title screen not reached after dismissal (exitLegalDisclaimer not called, or
  MasterTitleScreen not initialized).
- Double fade-from-black on the master title (FadeManager called twice).
- Engine logs "Legal disclaimer screen initialized" but nothing is drawn (draw branch not
  active in GameLoop).

---

## Scenario 2 — Disclaimer disabled: master title shown directly

### Config keys to set

```json
"SHOW_LEGAL_DISCLAIMER_ON_STARTUP": false,
"MASTER_TITLE_SCREEN_ON_STARTUP": true,
"TITLE_SCREEN_ON_STARTUP": true,
"LEVEL_SELECT_ON_STARTUP": false
```

Change only `SHOW_LEGAL_DISCLAIMER_ON_STARTUP` from `true` to `false`.

### Launch command

```
java -jar target/OpenGGF-0.6.prerelease-jar-with-dependencies.jar
```

### Expected sequence

1. Disclaimer screen is **never shown** — no black screen with "LEGAL NOTICE" text.
2. Engine goes directly to `MasterTitleScreen` initialization (same path as if disclaimer
   had never existed).
3. Master title screen appears with its normal entry fade.

### What would indicate a regression

- Disclaimer screen briefly appears even with the flag false (conditional guard broken).
- Master title screen fails to appear (Engine.init branch for `!showLegalDisclaimer &&
  masterTitleOnStartup` not reached).
- Log contains "Legal disclaimer screen initialized" when flag is false.

---

## Scenario 3 — Disclaimer enabled, bypass all intermediate screens, direct gameplay

### Config keys to set

```json
"SHOW_LEGAL_DISCLAIMER_ON_STARTUP": true,
"MASTER_TITLE_SCREEN_ON_STARTUP": false,
"TITLE_SCREEN_ON_STARTUP": false,
"LEVEL_SELECT_ON_STARTUP": false
```

All three screen-bypass flags set to false; disclaimer still enabled.

### Launch command

```
java -jar target/OpenGGF-0.6.prerelease-jar-with-dependencies.jar
```

### Expected sequence

1. **Disclaimer shown** — same fade-in, content, 5-second gate, pulsing prompt, dismiss as
   Scenario 1.
2. **Fade-to-black on dismiss** — same as Scenario 1.
3. **Direct to gameplay** — after the fade-to-black, `exitLegalDisclaimer` calls
   `initializeGame()` (because `MASTER_TITLE_SCREEN_ON_STARTUP = false`), which loads the ROM,
   builds the gameplay session, and calls `enterConfiguredStartupMode()`. With both
   `TITLE_SCREEN_ON_STARTUP` and `LEVEL_SELECT_ON_STARTUP` false, it falls through to
   `loadDefaultStartingLevel`, which loads zone 0 act 0.
4. **Reveal fade-from-black** — `exitLegalDisclaimer` fires `FadeManager.startFadeFromBlack(null)`
   after the `initializeGame()` call, so the first gameplay frame fades in from black rather than
   cutting hard.
5. **Gameplay runs** — Sonic is controllable in the first zone, act 0 of the detected ROM.

### What would indicate a regression

- Disclaimer not shown (flag gate broken).
- After dismiss, master title appears instead of gameplay (`MASTER_TITLE_SCREEN_ON_STARTUP`
  not re-read in `exitLegalDisclaimer`).
- After dismiss, game-specific title screen appears instead of going straight to gameplay
  (`TITLE_SCREEN_ON_STARTUP` not respected by `enterConfiguredStartupMode`).
- No reveal fade-from-black at gameplay start (cut to full-bright rather than fading in).
- Engine hangs or crashes during `initializeGame()` (ROM load failure is expected if ROM is
  absent; ensure ROM is present for this scenario).
- Gameplay starts but on zone/act other than 0/0 (default level load path not taken).

---

## Logging signals to check in all scenarios

The following INFO log lines from `LegalDisclaimerScreen` should match the scenario:

| Scenario | Expected log lines |
|----------|--------------------|
| 1 and 3  | `Legal disclaimer screen initialized` on startup; `Legal disclaimer screen cleaned up` when dismissed |
| 2        | Neither of those lines should appear |

If an initialization error occurs, `SEVERE: Failed to initialize legal disclaimer screen: ...`
will appear and the engine will throw `RuntimeException`. This is not a regression; it means
a resource (e.g. `pixel-font.png`) could not be loaded — investigate the classpath, not the
disclaimer logic.

---

## Config restore

After testing, restore `config.json` to your normal working values. The factory defaults are:

```json
"SHOW_LEGAL_DISCLAIMER_ON_STARTUP": true,
"MASTER_TITLE_SCREEN_ON_STARTUP": true,
"TITLE_SCREEN_ON_STARTUP": true,
"LEVEL_SELECT_ON_STARTUP": false
```
