# Configuration

The engine reads settings from `config.yaml` in the working directory (next to the JAR).
If the file does not exist, the bundled default template is used. A legacy `config.json`
is migrated automatically on first run. This page answers common setup
questions. For the full reference of every key, see the
[Configuration Reference](../../../CONFIGURATION.md).

---

## How do I change the window size?

Set `display.windowAutosize` to `false`, then set `debug.window.width` and
`debug.window.height` to the window dimensions you want in OS pixels.
For pixel-perfect 2x scaling of the native 320x224 resolution:

```yaml
display:
  windowAutosize: false
debug:
  window:
    width: 640
    height: 448
```

For 3x:

```yaml
display:
  windowAutosize: false
debug:
  window:
    width: 960
    height: 672
```

Do not change the derived logical pixel dimensions unless you understand the implications.
They control the Mega Drive logical resolution, not the OS window size.

## How do I skip the title screen?

To skip the master title screen (game picker) and boot directly into a game:

```yaml
startup:
  masterTitleScreen: false
roms:
  default: "s2"
```

To also skip the game's own title screen and go straight to gameplay:

```yaml
startup:
  masterTitleScreen: false
  titleScreen: false
roms:
  default: "s2"
```

## How do I start on a specific zone?

Enable the level select screen:

```yaml
debug:
  startup:
    levelSelectOnStartup: true
```

This opens the game's level select menu instead of starting from the first zone.

## How do I play as Tails?

Set the sidekick character:

```yaml
characters:
  sidekick: "tails"
```

Tails will appear as a CPU-controlled follower alongside Sonic. Set to `""` (empty) to
disable.

Sonic 2 does not currently expose the ROM's native two-player/competition mode
(`Two_player_mode`), so Player 2 bindings do not create a human second player.
They remain available for the configured sidekick/manual-input paths. Native
S2 level debug placement (`Debug_placement_mode`, including ring/item placement)
also has no configuration or input route; the `D` shortcut is engine free-fly
debug movement only.

## How do I enable cross-game features?

Cross-game feature donation lets a donor game provide player sprites, spindash, and
SFX while you play a different base game. For example, playing Sonic 1 with Sonic 2's
sprites and spindash:

```yaml
crossGame:
  enabled: true
  source: "s2"
```

Both the base game ROM and the donor game ROM must be present.

When the donor is `s3k`, Sonic 1 and Sonic 2 can also use the donated S3K Data Select
save screen. The frontend comes from S3K, but slot validation, progression, restart
rules, and save writes still belong to the host game.

On that donated save screen, zone previews and emerald progress are host-owned metadata.
Emeralds keep the S3K save-card layout, but their colors are adapted from the host
game's emerald palette rather than reusing raw host palette slots directly.

## How do I change controls?

Key bindings accept GLFW key codes (integers), human-readable names, or a name
qualified by modifiers. The following formats all work:

- `81`
- `"81"`
- `"Q"`
- `"SPACE"`
- `"LEFT_SHIFT"`
- `"GLFW_KEY_F9"`
- `"SHIFT+O"`
- `"CTRL+SHIFT+O"`

The modifier names are `CTRL`/`CONTROL`, `SHIFT`, `ALT`/`OPTION`, and
`META`/`SUPER`/`CMD`/`COMMAND`/`WIN`, all case-insensitive. Matching is exact: a
binding fires only with exactly the modifiers it names, so a plain `"O"` does not
fire while Shift is held. Because `+` is the separator, bind the plus key as
`"EQUAL"` or `"KP_ADD"`.

Today `capture.toggleKey` (live viewport recording, default `"SHIFT+O"`) is the
one shortcut that acts on a chord. Elsewhere the modifiers are parsed and then
dropped, so the binding ends up on the bare key the chord names: writing
`"CTRL+P"` binds plain `P`, and for a few shortcuts that only ever fire with no
modifier held, the chord as written can never fire at all. The full per-binding
table is in [CONFIGURATION.md](../../../CONFIGURATION.md).

Invalid names log a warning and fall back to the default binding for that action.
Leaving a binding empty (`""`) is different: it switches that shortcut off, and no
default is substituted. If you want
the raw numeric values, find the code for your preferred key in the
[GLFW key reference](https://www.glfw.org/docs/latest/group__keys.html).

Common key codes:

| Key | Code | Key | Code |
|-----|------|-----|------|
| Arrow Up | 265 | Space | 32 |
| Arrow Down | 264 | Enter | 257 |
| Arrow Left | 263 | Tab | 258 |
| Arrow Right | 262 | Escape | 256 |
| A | 65 | Z | 90 |
| S | 83 | X | 88 |

Example: rebind Player 1 action A (jump) to the A key:

```yaml
input:
  player1:
    a: "A"
```

The engine models Mega Drive A, B, and C as separate actions. All three act as
jump buttons during platforming gameplay, but keyboard B/C are unbound in the
default config. Gamepads use position-based face buttons: west maps to Mega
Drive A, south maps to B, and east maps to C.

Controller input is controlled by `input.controller.enabled`,
`input.controller.deadzone`, `input.controller.player1`, and
`input.controller.player2`. Player assignments currently accept `"auto"` or
`"none"`.

See [Controls](controls.md) for the full list of bindable actions and
`CONFIGURATION.md` for current YAML key names.

## How do I enable the editor overlay?

```yaml
debug:
  flags:
    editor: true
```

With that enabled, press `Shift+Tab` during gameplay to enter the experimental editor overlay,
and press `Shift+Tab` again to resume playtesting.

## How do I enable live rewind?

Live rewind is an experimental gameplay debugging feature. Enable it explicitly:

```yaml
rewind:
  liveEnabled: true
  liveKey: R
```

With that enabled, hold the configured live rewind key during normal level play to rewind the live
gameplay buffer. The on-screen live rewind HUD is hidden during ordinary play and
appears only while the key is held, showing the current rewind frame. Rewind history
resets at committed level and act transition boundaries.

Live rewind also reverses audio presentation from the recent mixed PCM history
and freezes graphical fade progression to the restored rewind snapshots. The
game resumes normal forward audio/fade presentation when the key is released.

Held rewind defaults to one rewind step per visual frame and stops immediately when
released. Experimental tape-coast rewind is available only when
`rewind.tapeCoastEnabled` is set to `true`. With coast enabled, the rewind
speed starts at `rewind.tapeCoastMinSteps` on press (values below 1.0 give
a slow-motion start), accelerates while held using
`rewind.tapeCoastAcceleration` up to `rewind.tapeCoastMaxSteps`,
and decays after release using `rewind.tapeCoastDeceleration`. Reverse audio
is resampled in lockstep with the current rewind speed, so fast rewind pitches up
and slow-motion rewind plays back lower and stretched in time.

## How do I mute audio?

```yaml
audio:
  enabled: false
```

## How do I make colors less bright?

The default display profile is the raw RGB expansion of Mega Drive palette values. You can
select a darker analog-style presentation, or a slightly softer NTSC-style presentation:

```yaml
display:
  colorProfile: "MD_ANALOG"
```

Accepted values are `"RAW_RGB"`, `"MD_ANALOG"`, and `"NTSC_SOFT"`. During play, press
`V` to cycle profiles; the selected profile is saved to `config.yaml` and a short
confirmation appears in the bottom-left corner. The key can be rebound with
`display.colorProfileToggleKey`.

## How do I use a wider display aspect? (experimental)

> **This feature is experimental and incomplete.** Widescreen rendering — UI pillarboxing,
> extended parallax columns, and camera deadzone scaling — is not finished. Only
> `NATIVE_4_3` (the default 320×224 resolution) is fully supported. Setting a widescreen
> preset will change the logical pixel width but may produce missing art at the sides,
> stretched HUD elements, or other visual artifacts.

To try a widescreen preset anyway:

```yaml
display:
  aspect: "WIDE_16_9"
```

Available presets:

| Value | Pixel width | Notes |
|-------|-------------|-------|
| `"NATIVE_4_3"` | 320 | Default; fully supported |
| `"WIDE_16_10"` | 352 | Experimental |
| `"WIDE_16_9"` | 400 | Experimental |
| `"ULTRA_21_9"` | 528 | Experimental |
| `"SUPER_32_9"` | 800 | Experimental |

By default (`display.windowAutosize: true`), the OS window is automatically sized to
twice the preset pixel width (e.g. `WIDE_16_9` → 800×448). To keep a custom window size,
set `display.windowAutosize` to `false` and configure `debug.window.width` /
`debug.window.height` manually.

## How do I switch between NTSC and PAL?

```yaml
audio:
  region: "PAL"
```

PAL runs at 50 Hz instead of 60 Hz and affects audio timing. The default is `"NTSC"`.

## How do I use different ROM filenames?

If your ROM files have different names from the defaults:

```yaml
roms:
  sonic1: "my-sonic1.bin"
  sonic2: "my-sonic2.bin"
  sonic3k: "my-s3k.bin"
```

Paths are relative to the working directory.

## How do I skip S3K intro cutscenes?

```yaml
debug:
  startup:
    s3kSkipIntros: true
```

This skips sequences like the AIZ biplane intro and boots straight into gameplay.
