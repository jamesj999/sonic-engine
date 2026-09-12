# Configuration Reference

Settings live in `config.yaml` in the working directory (next to the JAR), and that file
holds **only the settings you have changed**. Every other setting reads its built-in
default, so a default that changes in a later build applies to every install that never
touched the key. The bundled `src/main/resources/config.yaml` documents every setting with
its default and is written to **`config.yaml.example`** alongside your file on every run;
copy a line from it into `config.yaml` to change that setting. Your own `config.yaml` is
never overwritten by the example.

The file opens with `configFormat: 2`, which marks this sparse format. A `config.yaml`
written by an older build (one that copied every default into it) is converted once on
first load: keys still at their default are dropped, keys you changed are kept, and the
marker is written. One default changed before this format existed —
`gameplay.loadTimeSimulation` moved from `NONE` to `FAST` — so that value is treated as
the former default during the conversion; set `NONE` again afterwards if you want it and
it stays. On first run, a legacy `config.json` is migrated to `config.yaml` and backed up
to `config.json.bak`. Keys are grouped into nested YAML sections rather than flat enum
names.

For development configurations where keeping the complete editable setting list is more
useful, set `config.preserveExplicitDefaults: true` before starting the engine. The
one-time sparse conversion will then retain every explicitly listed recognized setting.
The flag defaults to `false`, preserving the release behavior described above.

**Changing a default** (maintainers): change the `putDefault` line in
`SonicConfigurationService`, the value in `src/main/resources/config.yaml`, and the row in
this file. Nothing else: no migration, no version bump. `TestSparseUserConfig` fails if the
bundled template and the registered default disagree.

Key bindings accept either **GLFW key codes** (integers) or human-readable key names such as
`"SPACE"`, `"Q"`, and `"GLFW_KEY_F9"`. See the
[GLFW key token reference](https://www.glfw.org/docs/latest/group__keys.html) for the full list.
Common values are shown in the tables below.

---

## Sections

The `config.yaml` is organized into the following top-level sections:

**Normal sections** (relevant to all users):

| Section | Contents |
|---------|----------|
| `display` | Aspect preset, window autosize, display shader library, deadzone mode, color profile, FPS |
| `gameplay` | Normal-play hardware load-time simulation |
| `config` | Configuration-file persistence behavior |
| `input` | `player1` / `player2` key bindings, `pause` key |
| `audio` | Enabled flag, region, DAC, FM6, PSG settings |
| `characters` | Main character, sidekick, data select combos |
| `roms` | ROM filenames for S1, S2, S3K; default game selection |
| `capture` | Trace video capture output dir, scale, fps, codec |
| `startup` | Title screen, master title, legal disclaimer flags |
| `rewind` | Live rewind enable/key, tape-coast parameters, audio history settings |
| `crossGame` | Cross-game feature donation enable and source |
| `launch` | Per-game master-title launch profiles |
| `discord` | Discord Rich Presence enable, show timer, show zone |
| `timeAttack` | Solo time attack menu and retry keys |

**`debug:` block** (developer/debug tooling — safe to ignore for normal play):

| Sub-section | Contents |
|-------------|----------|
| `debug.flags` | Debug subsystem, editor enable, collision view overlay |
| `debug.keys` | All debug/developer keyboard shortcuts |
| `debug.startup` | Level select on startup, S3K skip intros |
| `debug.playback` | BK2 movie path and playback control keys (unbound by default) |
| `debug.traceRewind` | Key held during Trace Test Mode to rewind engine state |
| `debug.traceRender` | Trace Test Mode / capture visibility flags |
| `debug.testMode` | Test mode enable flag and trace catalog directory |
| `debug.crossGame` | Data-select image regeneration overrides and coord-log key |
| `debug.window` | **DEPRECATED** manual width/height/scale — use `display.aspect` + `display.windowAutosize` instead |

> **Note:** `SCREEN_WIDTH_PIXELS` and `SCREEN_HEIGHT_PIXELS` are **derived** values computed from the
> `display.aspect` preset. They are never stored in `config.yaml`. `debug.window.width` /
> `debug.window.height` / `debug.window.scale` are deprecated; widescreen is driven by
> `display.aspect` profiles.

---

## Display

| Key | YAML path | Type | Default | Description |
|-----|-----------|------|---------|-------------|
| `SCREEN_WIDTH_PIXELS` | *(derived)* | int | `320` | Logical pixel width — the Mega Drive native horizontal resolution. Derived from `display.aspect`; never stored. |
| `SCREEN_HEIGHT_PIXELS` | *(derived)* | int | `224` | Logical pixel height — the Mega Drive native vertical resolution (224 for NTSC, 240 for PAL). Derived; never stored. |
| `SCREEN_WIDTH` | `display` / `debug.window.width` | int | `640` | Actual window width in OS pixels. Derived from aspect preset when `display.windowAutosize=true`. |
| `SCREEN_HEIGHT` | `display` / `debug.window.height` | int | `448` | Actual window height in OS pixels. |
| `SCALE` | `debug.window.scale` | double | `1.0` | **DEPRECATED** additional rendering scale factor. |
| `FPS` | `display.fps` | int | `60` | Target frames per second. Affects game speed — use `60` for NTSC, `50` for PAL. |
| `LOAD_TIME_SIMULATION` | `gameplay.loadTimeSimulation` | enum | `FAST` | Normal-play ROM-load timing: `NONE` completes as soon as production preparation allows; `PROFILED` uses the generator-owned measured profile data; `FAST` (default) uses the hand-tuned copy of that data (`load-time-profiles/s3k-fast-v1.json`, which also carries the S3K title-screen Sonic frame decodes taken from the original hardware capture), and warns then behaves as `NONE` for a game without a FAST manifest; `REALISTIC` is a retained reserved alias that warns when the profile is resolved and returns `PROFILED`. S1/S2 resolve through their default module factory to the supplied immediate profile; their ROM-derived PLC and dynamic-art/DPLC lifecycles remain game-owned and are not retimed by this setting. Trace replay uses its recorded hardware-timing policy and does not consume this setting; queue-state trace diagnostics are comparison-only and never alter this configuration. |
| `CONFIG_PRESERVE_EXPLICIT_DEFAULTS` | `config.preserveExplicitDefaults` | bool | `false` | Developer override: retain explicitly listed recognized settings, including values equal to defaults, when converting an older full configuration to sparse format. |
| `DISPLAY_COLOR_PROFILE` | `display.colorProfile` | string | `"RAW_RGB"` | Palette presentation profile. `"RAW_RGB"` keeps the current direct 8-bit expansion, `"MD_ANALOG"` applies a darker Mega Drive-style analog ramp, and `"NTSC_SOFT"` applies the analog ramp plus mild desaturation. |
| `DISPLAY_COLOR_PROFILE_TOGGLE_KEY` | `display.colorProfileToggleKey` | key | `V` | Runtime key used to cycle display color profiles. The selected profile is saved to `config.yaml` and shown briefly in the bottom-left corner. |
| `DISPLAY_ASPECT` | `display.aspect` | string | `"NATIVE_4_3"` | Display aspect preset. Controls the logical pixel width used by the renderer. Accepted values: `"NATIVE_4_3"` (320 px, exact native behavior), `"WIDE_16_10"` (352 px, supported), `"WIDE_16_9"` (400 px, primary supported widescreen target), `"ULTRA_21_9"` (528 px, best-effort smoke tier), and `"SUPER_32_9"` (800 px, exploratory). Wider presentation does not widen ROM world boundaries or trace-comparison authority. |
| `DISPLAY_WINDOW_AUTOSIZE` | `display.windowAutosize` | bool | `true` | When `true` and a widescreen preset is active, the OS window is derived from the preset at 2x baseline (e.g. `WIDE_16_9` → 800×448). When `false`, `SCREEN_WIDTH`/`SCREEN_HEIGHT` are used verbatim so a custom window size is preserved. Has no effect when `DISPLAY_ASPECT` is `"NATIVE_4_3"`. |
| `DISPLAY_SHADER_LIBRARY_ROOT` | `display.shaderLibraryRoot` | string | `"shaders"` | Root directory scanned for user display shaders, resolved relative to the working directory. |
| `DISPLAY_SHADER_SELECTION` | `display.shaderSelection` | string | `"OFF"` | Last selected display shader. Use `"OFF"` or a root-relative forward-slash path under `DISPLAY_SHADER_LIBRARY_ROOT`. |
| `DISPLAY_SHADER_NEXT_KEY` | `display.shaderNextKey` | key | `RIGHT_BRACKET` | Runtime key used to advance to the next display shader. |
| `DISPLAY_SHADER_PREVIOUS_KEY` | `display.shaderPreviousKey` | key | `LEFT_BRACKET` | Runtime key used to move to the previous display shader. |
| `DISPLAY_SHADER_PICKER_KEY` | `display.shaderPickerKey` | key | `BACKSLASH` | Runtime key used to open the searchable display shader picker. |
| `DISPLAY_SHADER_DEFAULT_PHASE` | `display.shaderDefaultPhase` | enum | `"PRESENTATION"` | Fallback render phase for standalone display shaders. Accepted values: `"SCENE"`, `"PRESENTATION"`, `"FINAL"`. |
| `WIDESCREEN_DEADZONE_MODE` | `display.deadzoneMode` | string | `"PROPORTIONAL"` | Camera horizontal deadzone behaviour on wide screens: `"CENTER_SCALED"` keeps the native 16px deadzone band; `"PROPORTIONAL"` scales the band width with the screen width. **EXPERIMENTAL** — takes effect only when a widescreen preset is active. |

### Display shader library

Display shaders are user-supplied post-processing shaders loaded from the root-level
`shaders/` directory. This is separate from `src/main/resources/shaders`, which contains
engine-owned shaders required for normal rendering. The root `shaders/` directory is
gitignored so local shader packs and third-party shader licenses stay outside the repo
unless they are reviewed separately.

Recommended layout:

```text
shaders/
  Custom/
    warm-crt.glsl
  BizHawk/
    BizScanlines.cgp
    BizScanlines.glsl
  libretro-glsl/
    crt/
    scanlines/
    ...
    .openggf-libretro-glsl.properties
```

The engine scans `.glsl`, `.cgp`, and `.glslp` files at runtime and always includes
`Off`. Use `]` and `[` to cycle quickly, or press `BACKSLASH` to open the searchable
picker for large libraries. The picker filters by root-relative path and inferred
category, so typing `crt` narrows entries such as `libretro-glsl/crt/...`.

The optional libretro GLSL pack installer is available from the shader picker: open the
picker with `BACKSLASH`, then press `F5` to install or update the pack. The app downloads
the upstream zip archive from GitHub, extracts it into `shaders/libretro-glsl/`, strips the
archive's top-level folder, stores update metadata in
`shaders/libretro-glsl/.openggf-libretro-glsl.properties`, and rescans the shader library
when the install/update finishes. That folder is owned by the installer; put personal
shaders in a sibling folder such as `shaders/Custom/`.

Compatibility is intentionally bounded. Fragment-only shaders can sample the current
scene through declared samplers named `s_p`, `SceneTexture`, or `Texture`. RetroArch and
BizHawk-style uniforms are populated by location when declared: `IN.video_size`,
`IN.texture_size`, `IN.output_size`, `InputSize`, `TextureSize`, `OutputSize`,
`FrameCount`, `FrameDirection`, and `MVPMatrix`. Combined shaders may declare
`VertexCoord`, `TexCoord`, and `COLOR` attributes; the engine binds them to fixed quad
locations. The engine does not inject uniform declarations.

HLSL/Cg-only presets can be discovered but fail on selection unless a loadable GLSL
sibling exists. Unsupported preset inheritance, external LUT textures, previous-frame
history, and malformed shaders fail safely: the selection is rejected, the shader is
remembered as failed for the current process, rendering continues, and the user can return
to `Off`.

Display shaders affect presentation only. F12 screenshots are captured after the display
shader composite, so screenshots include the selected shader. Trace replay/capture uses a
separate render path that stops before display shader application, so trace artifacts stay
shader-free; if trace capture is ever refactored to call the main `Engine.display()` path,
display shader application must be gated off for trace capture.

---

## ROM Files

Paths are relative to the working directory (where the JAR is launched).

| Key | YAML path | Type | Default | Description |
|-----|-----------|------|---------|-------------|
| `DEFAULT_ROM` | `roms.default` | string | `"s2"` | Which game to boot: `"s1"`, `"s2"`, or `"s3k"`. Selects the corresponding ROM key below. |
| `SONIC_1_ROM` | `roms.sonic1` | string | `"s1.gen"` | Filename of the Sonic 1 ROM. Expected: World REV01, CRC32 `AFE05EEE`, SHA-1 `69E102855D4389C3FD1A8F3DC7D193F8EEE5FE5B`. |
| `SONIC_2_ROM` | `roms.sonic2` | string | `"s2.gen"` | Filename of the Sonic 2 ROM. Expected: World REV01, CRC32 `7B905383`, SHA-1 `8BCA5DCEF1AF3E00098666FD892DC1C2A76333F9`. |
| `SONIC_3K_ROM` | `roms.sonic3k` | string | `"s3k.gen"` | Filename of the Sonic 3&K locked-on ROM. Expected: CRC32 `63522553`, SHA-1 `CFBF98C36C776677290A872547AC47C53D2761D6`. |

---

## Startup Flow

| Key | YAML path | Type | Default | Description |
|-----|-----------|------|---------|-------------|
| `SHOW_LEGAL_DISCLAIMER_ON_STARTUP` | `startup.legalDisclaimer` | bool | `true` | Show the legal disclaimer screen on engine startup before the master title screen. White text on black, 5-second readability gate, any-key dismiss, fade-in/out transitions. Set `false` for headless tests, trace replay sessions, or CI runs that should not have to drive past this screen. |
| `MASTER_TITLE_SCREEN_ON_STARTUP` | `startup.masterTitleScreen` | bool | `true` | Show the master title / game-selection screen on launch. When `false`, boots directly into the game set by `DEFAULT_ROM`. |
| `TITLE_SCREEN_ON_STARTUP` | `startup.titleScreen` | bool | `true` | Show the game-specific title screen (e.g. Sonic 2 title screen) before gameplay. Ignored when `MASTER_TITLE_SCREEN_ON_STARTUP` is true and game selection is pending. |
| `SKIP_MOD_ZONE_TITLE_CARDS` | `mods.skipModZoneTitleCards` | bool | `true` | Skip Sonic 2 title cards for additive mod zones until mod-supplied title-card art is supported. Stock cards still use the active zone registry name. |
| `LEVEL_SELECT_ON_STARTUP` | `debug.startup.levelSelectOnStartup` | bool | `false` | Jump straight to the level select screen instead of the title screen. Useful for development. |
| `S3K_SKIP_INTROS` | `debug.startup.s3kSkipIntros` | bool | `false` | (S3K only) Skip zone intro sequences such as the AIZ biplane cutscene and boot straight into playable gameplay. |

---

## Cross-Game Donation

| Key | YAML path | Type | Default | Description |
|-----|-----------|------|---------|-------------|
| `CROSS_GAME_FEATURES_ENABLED` | `crossGame.enabled` | bool | `false` | Enable cross-game feature donation. When `false`, each game uses only its own native frontend and gameplay assets. |
| `CROSS_GAME_SOURCE` | `crossGame.source` | string | `"s2"` | Donor game for cross-game features. Currently supports `"s2"` and `"s3k"`. |
| `CROSS_GAME_S1_DATA_SELECT_IMAGE_GEN_OVERRIDE` | `debug.crossGame.s1DataSelectImageGenOverride` | bool | `false` | Force regeneration of the runtime Sonic 1 donated Data Select screenshot cache on the next eligible boot. |
| `CROSS_GAME_S2_DATA_SELECT_IMAGE_GEN_OVERRIDE` | `debug.crossGame.s2DataSelectImageGenOverride` | bool | `false` | Force regeneration of the runtime Sonic 2 donated Data Select screenshot cache on the next eligible boot. |

### Cross-Game Debug Key

| Key | YAML path | Default | Key Name | Description |
|-----|-----------|---------|----------|-------------|
| `CROSS_GAME_S1_DATA_SELECT_IMAGE_COORD_LOG_KEY` | `debug.crossGame.s1DataSelectImageCoordLogKey` | `39` | Apostrophe | While playing Sonic 1, log the current camera as a preview override point for donated Data Select screenshot tuning. |

---

## Launch Profiles

The master title stores per-game launch defaults under `launch.s1`, `launch.s2`,
and `launch.s3k`. Left/right changes games from either hub pane. Up/down enters
the action list from the game pane, then chooses an action within it.
`Enter` (controller A) opens the selected action and also provides a shortcut
into the action pane. `Esc` (controller B) backs out. Opening a menu screen plays
the confirmation cue; backing out plays a distinct cancel cue. Choose **Launch Options** to edit the selected game's profile.
The original ROM-backed animated game logos remain, proportionally scaled into
the left pane. Their original textures are drawn directly into the window
viewport, so a larger window recovers source detail instead of enlarging a
pre-reduced thumbnail. The default logical resolution remains 320x224.
Primary text keeps the original 9x10 pixel font; supplementary details use a
native small font in 6x8 cells, including lowercase descenders. Neither is
fractionally resampled. The game pane shows the sky and the available game tabs;
missing ROMs are dimmed. Checkerboards and cyan focus frames connect menu pages.
**Quit** opens a confirmation with **Return to menu** selected by default;
`Esc`/B from the game pane also opens it. Error pages wait for `Enter`/A or
`Esc`/B rather than dismissing on a timer.

In the launch panel, arrows/D-pad choose a row and change its value. `Enter`/A
saves, `Esc`/B cancels draft edits, and the optional `Tab` or controller
Back/Select shortcut closes and saves. The visible **Stock**, **Save**, and
**Cancel** buttons are reached with up/down; left/right moves between those
buttons. **Stock** restores the stock draft (`Backspace` remains a shortcut).
Stock values are white, non-default/non-stock values amber, and experimental
values red; a separate cyan cursor marks selection. Prompts follow the last
intentional keyboard/controller input rather than the presence of a controller.

### Engine settings from the title

Choose **Settings** for persisted engine preferences. The category rail stays
visible; confirm enters the selected category, Back returns to the rail, and
up/down moves through paginated fields. Left/right changes booleans, enums and
numbers; confirm opens a value picker or text editor. Text editing supports
ordinary keyboard typing and a controller keyboard with all printable ASCII,
case/symbol pages, cursor movement, deletion, and default restoration. Key
bindings also offer physical key/chord capture. Values and categories with non-default settings remain
amber, including after saving. **Apply** writes the draft atomically; save errors
retain the draft for retry. **Cancel** asks before discarding unsaved edits.

Settings requests an engine restart so cached subsystems can adopt all changes.
Input bindings are read dynamically; ROM availability/logos are rescanned after
Apply. Current launch/session overrides remain authoritative until the next
launch. YAML remains an optional editing route.

**Advanced > Trace replays** opens the trace catalog without enabling test mode.
Time Attack, Recordings, and Mods also have visible action-menu entries;
existing function-key shortcuts remain optional accelerators. Global display and
capture shortcuts remain available in game selection, but yield to the action
menu and its child pages so typing or rebinding a key cannot change unrelated
settings. Playback shortcuts do not run on the master title.

Recordings has a separate options page for the target frame, pause-on-desync,
fast-forward, playback, and full recording details. Time Attack exposes a
visible **Start Run**, **Create LAN Room**, **Join LAN Room**, or **Browse Rooms** action; network addresses and lobby chat support the same
keyboard/controller editor. Room creation, refresh, paging, and lobby actions
are visible choices. Mods exposes **Details**, **Order**, **Notices**, and
**Save + Back**, with paginated findings and confirmations. Trace replay lists,
loading/failure pages, standalone New Game/Continue, and help share the same
native typography and keyboard/controller navigation.

Profiles are persistent defaults for future manual launches, but applying a profile is
session-only. A launch can temporarily override live rewind, cross-game donation, debug
tools, display aspect, main character, and sidekick without writing those values into the
global gameplay keys (`rewind.liveEnabled`, `crossGame.*`, `debug.flags.debugView`,
`display.aspect`, or `characters.*`). Programmatic trace launches clear any stale launch
profile overrides and skip profile application.

`crossGameSource: "off"` disables donation for that game launch and restores the built-in
`crossGame.source` default in the session overlay so the donor choice cannot leak from a
previous launch. When `crossGameSource` is `"s3k"`, the launch panel hides
`mainCharacter` and `sidekick` because the donated S3K Data Select screen owns team
selection. When character rows are shown, their options follow the active donor:
Sonic is always available, Tails requires Sonic 2 or Sonic 3&K data, and Knuckles
requires Sonic 3&K data; hand-edited saved values outside that donor set are clamped
before launch. `aspect: "global"` inherits the normal `display.aspect` setting; pinned
aspect values such as `"WIDE_16_9"` apply only for that game session. They change the
logical projection within the existing window, restoring the global projection
when returning to the master title. In the launch panel, pinned
16:10 and 16:9 aspects are amber non-standard choices. The 21:9 preset remains a
best-effort smoke tier, while 32:9 is exploratory; both remain red choices so the panel
does not imply the same support level as 16:10/16:9.

Stock defaults:

| Game | `rewind` | `crossGameSource` | `debugTools` | `aspect` | `mainCharacter` | `sidekick` |
|------|----------|-------------------|--------------|----------|-----------------|------------|
| Sonic 1 | `false` | `"off"` | `false` | `"global"` | `"sonic"` | `"none"` |
| Sonic 2 | `false` | `"off"` | `false` | `"global"` | `"sonic"` | `"tails"` |
| Sonic 3&K | `false` | `"off"` | `false` | `"global"` | `"sonic"` | `"tails"` |

| Key | YAML path | Type | Default | Description |
|-----|-----------|------|---------|-------------|
| `LAUNCH_S1_REWIND` | `launch.s1.rewind` | bool | `false` | Enable live rewind for manual Sonic 1 launches. |
| `LAUNCH_S1_CROSS_GAME_SOURCE` | `launch.s1.crossGameSource` | enum | `"off"` | Cross-game donor for manual Sonic 1 launches: `"off"`, `"s2"`, or `"s3k"`. |
| `LAUNCH_S1_DEBUG_TOOLS` | `launch.s1.debugTools` | bool | `false` | Enable debug tools for manual Sonic 1 launches. |
| `LAUNCH_S1_ASPECT` | `launch.s1.aspect` | enum | `"global"` | Display aspect for manual Sonic 1 launches: `"global"` or a `display.aspect` preset. |
| `LAUNCH_S1_MAIN_CHARACTER` | `launch.s1.mainCharacter` | enum | `"sonic"` | Main character for manual Sonic 1 launches. |
| `LAUNCH_S1_SIDEKICK` | `launch.s1.sidekick` | enum | `"none"` | Sidekick for manual Sonic 1 launches; `"none"` disables sidekick spawning. |
| `LAUNCH_S2_REWIND` | `launch.s2.rewind` | bool | `false` | Enable live rewind for manual Sonic 2 launches. |
| `LAUNCH_S2_CROSS_GAME_SOURCE` | `launch.s2.crossGameSource` | enum | `"off"` | Cross-game donor for manual Sonic 2 launches: `"off"`, `"s1"`, or `"s3k"`. |
| `LAUNCH_S2_DEBUG_TOOLS` | `launch.s2.debugTools` | bool | `false` | Enable debug tools for manual Sonic 2 launches. |
| `LAUNCH_S2_ASPECT` | `launch.s2.aspect` | enum | `"global"` | Display aspect for manual Sonic 2 launches: `"global"` or a `display.aspect` preset. |
| `LAUNCH_S2_MAIN_CHARACTER` | `launch.s2.mainCharacter` | enum | `"sonic"` | Main character for manual Sonic 2 launches. |
| `LAUNCH_S2_SIDEKICK` | `launch.s2.sidekick` | enum | `"tails"` | Sidekick for manual Sonic 2 launches; `"none"` disables sidekick spawning. |
| `LAUNCH_S3K_REWIND` | `launch.s3k.rewind` | bool | `false` | Enable live rewind for manual Sonic 3&K launches. |
| `LAUNCH_S3K_CROSS_GAME_SOURCE` | `launch.s3k.crossGameSource` | enum | `"off"` | Cross-game donor for manual Sonic 3&K launches: `"off"`, `"s1"`, or `"s2"`. |
| `LAUNCH_S3K_DEBUG_TOOLS` | `launch.s3k.debugTools` | bool | `false` | Enable debug tools for manual Sonic 3&K launches. |
| `LAUNCH_S3K_ASPECT` | `launch.s3k.aspect` | enum | `"global"` | Display aspect for manual Sonic 3&K launches: `"global"` or a `display.aspect` preset. |
| `LAUNCH_S3K_MAIN_CHARACTER` | `launch.s3k.mainCharacter` | enum | `"sonic"` | Main character for manual Sonic 3&K launches. |
| `LAUNCH_S3K_SIDEKICK` | `launch.s3k.sidekick` | enum | `"tails"` | Sidekick for manual Sonic 3&K launches; `"none"` disables sidekick spawning. |

Allowed launch profile enums:

| Field | Values |
|-------|--------|
| `crossGameSource` | `"off"`, `"s1"`, `"s2"`, `"s3k"`; the launched game cannot donate to itself and is clamped to `"off"` when hand-edited. |
| `aspect` | `"global"`, `"NATIVE_4_3"`, `"WIDE_16_10"`, `"WIDE_16_9"`, `"ULTRA_21_9"`, `"SUPER_32_9"` |
| `mainCharacter` | `"sonic"`, `"tails"`, `"knuckles"`; selectable values are donor-gated. |
| `sidekick` | `"none"`, `"sonic"`, `"tails"`, `"knuckles"`; selectable values are donor-gated. |

---

## Characters

| Key | YAML path | Type | Default | Description |
|-----|-----------|------|---------|-------------|
| `MAIN_CHARACTER_CODE` | `characters.main` | string | `"sonic"` | Identity of the player-controlled character. Currently only `"sonic"` is supported. |
| `SIDEKICK_CHARACTER_CODE` | `characters.sidekick` | string | `"tails"` | CPU-controlled sidekick spawned alongside the main character. Defaults to `"tails"` (Tails AI enabled). Set to `"sonic"` to clone the player, or `""` (empty) to disable the sidekick. |
| `DATA_SELECT_EXTRA_PLAYER_COMBOS` | `characters.dataSelectExtraCombos` | string | `""` | Extra team combinations shown on the S3K Data Select screen. Format is `main,sidekick1,sidekick2;main2,sidekick1`. The first character in each group is the main character; remaining entries are sidekicks. Example: `"sonic,knuckles;sonic,tails,tails;knuckles,tails"`. This only affects Data Select team choices; normal gameplay and Level Select still use `MAIN_CHARACTER_CODE` and `SIDEKICK_CHARACTER_CODE`. |

---

## Audio

| Key | YAML path | Type | Default | Description |
|-----|-----------|------|---------|-------------|
| `AUDIO_ENABLED` | `audio.enabled` | bool | `true` | Master switch for all audio output (music and SFX). |
| `REGION` | `audio.region` | string | `"NTSC"` | Hardware region: `"NTSC"` (60 Hz) or `"PAL"` (50 Hz). Affects SMPS tempo timing and DAC sample rates. |
| `DAC_INTERPOLATE` | `audio.dacInterpolate` | bool | `false` | Optional smoothing with no hardware counterpart: `true` writes a linearly interpolated value between DAC (drum) samples when the chip bus is idle. Off by default to preserve the hardware's stepped output; it never changes sample pitch or length. |
| `AUDIO_FM_CORE` | `audio.fmCore` | string | `"fast"` | Which FM synthesis core renders music and SFX. `"accurate"` is the cycle-exact Nuked-OPN2 port and the audio parity oracle; `"fast"` is the default register-level clean-room core (lower CPU cost, not bit-exact). Existing explicit selections are preserved. Unknown values fall back to `"accurate"`. Physical audio parity captures explicitly use the accurate core. |
| `AUDIO_INTERNAL_RATE_OUTPUT` | `audio.internalRateOutput` | bool | `false` | Output audio at the YM2612 internal sample rate (~53 kHz) rather than the system rate. Useful for bit-accurate captures; may cause issues on some audio drivers. |
| `FM6_DAC_OFF` | `audio.fm6DacOff` | bool | `true` | Silence FM channel 6 whenever a DAC note is active. Matches the SMPSPlay parity hack used in Sonic 2; prevents FM bleed audible during percussion. |

`audio.psgNoiseShiftEveryToggle` was removed in 0.6: the PSG noise LFSR now
always clocks once per rising edge of the noise square wave (the hardware/
libvgm rule), matching the shipped console. If it is still present in an
existing `config.yaml`, it is ignored with a logged warning rather than
rejected.

## Capture

OpenGGF has two separate recording systems:

- **Live viewport recording:** press `Shift+O` — or whatever `capture.toggleKey`
  is set to, since the modifiers are part of the value — during normal windowed
  execution to start and press it again to stop. This
  writes `capture-live-<UTC timestamp>.mkv` under `capture.outputDir` using
  lossless FFV1 video and stereo FLAC audio. It captures only the physical game
  viewport—not window borders or letterbox/pillarbox bars—and automatically
  stops if the viewport moves or changes size. Forward play, pause/frame-step
  silence, and held-rewind/release audio are synchronized with the displayed
  frames. The red-dot/white-`REC` indicator is window-only: both the MKV and F12
  screenshots exclude it. If a recording ends for a reason you did not ask for,
  a red `REC STOPPED: RESIZED` or `REC STOPPED: ERROR` notice replaces the
  indicator in the same corner for three seconds, so an interrupted recording
  is distinguishable from one you stopped yourself. Pressing the toggle again
  clears the notice. It is window-only on the same terms as the indicator.

### Capture codecs

`capture.codec` selects the video codec and `capture.audioCodec` the audio
codec, for both live viewport recording and trace capture.

| Codec | Lossless | Notes |
|---|---|---|
| `ffv1` (default) | yes | Largest files, most widely playable. |
| `h264` | yes | Much smaller than FFV1. Encoded as RGB — see below. |
| `h265` | yes | Smaller still, slowest to encode. Encoded as RGB. |
| `flac` (default) | yes | Audio matches the engine's output exactly. |
| `aac` | **no** | Lossy. Small files; the audio is not what the engine produced. |
| `mp3` | **no** | Lossy. Most portable, largest quality loss. |

**Why H.264 and H.265 use RGB.** The usual "lossless" settings — `-crf 0`, or
`-x265-params lossless=1` — are lossless in the codec's own colour space, but
the recorder submits RGB frames and converting them to YUV and back does not
return the original pixels. Even 4:4:4, which discards no chroma resolution,
differs. So `h264` uses `libx264rgb` and `h265` uses planar RGB (`gbrp`), which
are byte-exact. The cost is compatibility: RGB H.264/H.265 is valid but some
players will not decode it. If a recording will not play elsewhere, use `ffv1`.

Choosing `aac` or `mp3` means the recording is no longer a faithful capture of
the engine's audio. That is a legitimate choice for sharing a clip; it is not
appropriate for comparing audio against reference material.

### Containers

`capture.container` sets the recording's file extension, and ffmpeg picks its
muxer from it. Recent ffmpeg will write any of the codecs above into either
container — FFV1 and FLAC in MP4 both produce valid files — but *player*
support is far narrower than what ffmpeg will write:

| Container | Plays reliably with |
|---|---|
| `mkv` (default) | Everything here. The safe choice. |
| `mp4` | `h264` or `h265` video with `aac` audio. An MP4 holding FFV1 or FLAC is a valid file that most players will refuse. |

So MP4 is worth choosing when the recording is going somewhere that expects
it, paired with the lossy example below. For anything else, keep `mkv`.

`-movflags +faststart` — the usual "web-optimised" flag — is an MP4 feature
and only takes effect when the container is MP4. Add it through
`capture.ffmpegPass2Args`.

### Overriding the ffmpeg commands

Recording runs ffmpeg twice: the first pass encodes frames arriving on
`pipe:0` into a lossless intermediate, the second muxes that with the raw audio
into the finished file. `capture.ffmpegPass1Args` and `capture.ffmpegPass2Args`
replace the argument list of each pass independently. The executable itself is
resolved from `PATH` and prepended; everything after it is yours.

Each key takes one of three values:

| Value | Effect |
|---|---|
| `default` | The engine's built-in command. Keep this unless you need something specific — it means later improvements still reach you. |
| *(empty)* | Skip the pass. Only valid for pass 2: the encode output is published as-is, so **the recording has no audio**. A fast video-only capture. |
| anything else | Used literally, after placeholder expansion. |

Placeholders for pass 1: `{width}` `{height}` `{fps}` `{scale}`
`{scaledWidth}` `{scaledHeight}` `{videoCodecArgs}` `{videoOut}`.
For pass 2: `{videoIn}` `{audioIn}` `{sampleRate}` `{audioCodecArgs}`
`{output}`.

Arguments split on whitespace. Quote a value whose expansion may contain
spaces — `"{output}"` — and the placeholder still expands inside the quotes. An
unknown placeholder fails when the recording starts, rather than reaching
ffmpeg as a filename and failing later with an unrelated error.

Pass 1 cannot be empty: it is where the submitted frames are encoded. Frames
always arrive as `rawvideo`/`rgba` on `pipe:0`, so a replacement command must
still read them from there.

```yaml
capture:
  codec: "h264"
  audioCodec: "flac"
  # Video only, no mux pass:
  ffmpegPass2Args: ""
```

#### Example: small, portable, lossy

The codec keys stay lossless by design, so compressing for sharing means
replacing the encode pass. This writes CRF 24 H.264 in `yuv420p` — the
most widely playable combination there is — and lets the built-in mux
pass add AAC:

```yaml
capture:
  audioCodec: "aac"
  ffmpegPass1Args: >-
    -y -f rawvideo -pix_fmt rgba -s {width}x{height} -r {fps} -i pipe:0
    -vf vflip,scale={scaledWidth}:{scaledHeight}:flags=neighbor
    -c:v libx264 -crf 24 -preset veryfast -pix_fmt yuv420p -an {videoOut}
```

Note what changed relative to the lossless `h264` codec setting: this uses
`libx264` with `yuv420p` rather than `libx264rgb`, and a CRF of 24 rather
than 0. Both make it lossy, which is the point here — the result is a
small file that plays anywhere, and it is no longer a faithful capture.
There is no large lossless intermediate either, since the encode pass
compresses directly and the mux pass only copies the video stream.

For a web-ready file, set `capture.container` to `mp4` as well and add
`-movflags +faststart` to the mux pass:

```yaml
capture:
  container: "mp4"
  audioCodec: "aac"
  ffmpegPass2Args: >-
    -y -i {videoIn} -f s16le -ar {sampleRate} -ac 2 -i {audioIn}
    -c:v copy {audioCodecArgs} -movflags +faststart {output}
```

- **Trace capture:** the headless `TraceCaptureTool` renders a chosen trace.
  Its scale, frame-rate, and codec settings remain trace-tool options.

Both paths require `ffmpeg` on `PATH`; live media verification and inspection
also use `ffprobe`. Live recording is distinct from the Shift+F9
`debug.recording.recordKey` input/movie recorder.

Live recording treats an unavailable or failed audio tap as an audio-only
failure: video continues and the MKV retains a phase-correct stereo-silence
track. Encoder, output-file, and mux failures still stop the whole recording.
Stopping waits while queued frames continue encoding, then allows the encoder
to finalize the recording under its own timeout. The five-second queue watchdog
measures stalled frame progress, not total recording-finalization time. Application
shutdown can still cancel an unfinished recording after its bounded wait.
For development validation only, launch the JVM with
`-Dopenggf.debug.liveCaptureAudioFailAfterFrames=N` to inject a tap failure
before drain `N + 1`, after exactly `N` successful audio-frame drains. The
property is not stored in `config.yaml`; it is disabled when absent or set to
`-1`. Invalid values and values below `-1` warn and disable injection.

These keys set the code defaults; trace CLI flags override the trace-specific
ones. The bundled `config.yaml` supplies the live toggle default.

| Key | YAML path | Type | Default | Description |
|-----|-----------|------|---------|-------------|
| `CAPTURE_OUTPUT_DIR` | `capture.outputDir` | string | `"target/trace-videos"` | Output directory for live and trace capture videos. |
| `CAPTURE_TOGGLE_KEY` | `capture.toggleKey` | key | `SHIFT+O` | Live viewport recording toggle. The modifiers live in the value (`CTRL+SHIFT+O`, `META+O`, or a bare key such as `SCROLL_LOCK` for none) and are matched exactly. A bare `O` is reserved — the compatibility migration rewrites it back to `SHIFT+O` on every launch. |
| `CAPTURE_SCALE` | `capture.scale` | int | `4` | Trace capture only: integer nearest-neighbor upscale factor applied to captured frames; live viewport recording always uses scale 1. |
| `CAPTURE_FPS` | `capture.fps` | int | `60` | Trace capture only: output frame rate; live recording uses the engine's effective display rate. |
| `CAPTURE_CODEC` | `capture.codec` | string | `"ffv1"` | Video codec for live and trace capture: `ffv1`, `h264` or `h265`. All three are lossless — see the note below. |
| `CAPTURE_CONTAINER` | `capture.container` | string | `"mkv"` | Recording file extension. ffmpeg selects its muxer from this — see "Containers" below. |
| `CAPTURE_AUDIO_CODEC` | `capture.audioCodec` | string | `"flac"` | Audio codec: `flac`, `aac` or `mp3`. **`aac` and `mp3` are lossy**: the recorded audio will not match what the engine produced. `flac` is lossless. |
| `CAPTURE_FFMPEG_PASS1_ARGS` | `capture.ffmpegPass1Args` | string | `"default"` | **Advanced.** Full ffmpeg argument list for the encode pass. See "Overriding the ffmpeg commands" below. |
| `CAPTURE_FFMPEG_PASS2_ARGS` | `capture.ffmpegPass2Args` | string | `"default"` | **Advanced.** Full ffmpeg argument list for the mux pass; leave empty to skip it and record video only. |

Live recording reuses a bounded pool of RGBA arrays (encoder queue capacity plus
two in-flight frames). On OpenGL 2.1 or newer, two additional GPU pixel-pack
buffers overlap readback with the next presentation; their memory is outside
`capture.queueBudgetMb`. Older contexts use synchronous readback. Video and audio
remain paired, and stopping flushes the final pending frame. Sustained GPU or
encoder overload can still block lossless recording; the queue cannot compensate
for an encoder that remains slower than playback.

Invoke the tool through Maven (requires a ROM in the working directory, an offscreen-capable GL context, and `ffmpeg` on `PATH`):

```bash
mvn exec:java "-Dexec.mainClass=com.openggf.tools.TraceCaptureTool" "-Dexec.args=--trace <id|name|dir> --out-dir target/trace-videos"
```

CLI flags (all optional except `--trace`; unspecified flags fall back to the `CAPTURE_*` defaults above):

| Flag | Default | Description |
|------|---------|-------------|
| `--trace <id\|name\|dir>` | (required) | Trace to capture: a 0-based `TraceCatalog` index, a trace directory name, or a direct path to a trace directory. |
| `--out-dir <dir>` | `CAPTURE_OUTPUT_DIR` | Output directory. The file is written as `capture-<trace-name>-<UTC>.mkv`. |
| `--scale <n>` | `CAPTURE_SCALE` | Integer nearest-neighbor upscale factor. |
| `--fps <n>` | `CAPTURE_FPS` | Output frame rate. |
| `--codec <name>` | `CAPTURE_CODEC` | Capture video codec. |
| `--no-ghosts` / `--ghosts` | `TRACE_SHOW_DESYNC_GHOSTS` | Hide / show the desync ghost(s) in the captured video (overrides the config flag for this run). |

Desync-ghost / game-HUD / debug-HUD visibility during capture is governed by the `TRACE_SHOW_*` keys in the Debug section; `--no-ghosts`/`--ghosts` is a per-run override for the ghost flag. Game-HUD and debug-HUD visibility are set via those config keys (no CLI flag yet).

Audio: headless capture installs `HeadlessSmpsAudioBackend`, a true no-device SMPS backend that synthesizes the music for the recording but **opens no audio device** (no speaker output; works on machines with no audio hardware). Audio is captured at the engine's 48 kHz synthesis rate and muxed as lossless FLAC, synced 1:1 with video.

## Time Attack

| Key | YAML path | Type | Default | Description |
|-----|-----------|------|---------|-------------|
| `TIME_ATTACK_RETRY_KEY` | `timeAttack.retryKey` | key | `R` | Instant retry to act start during solo time attack. |
| `TIME_ATTACK_MENU_KEY` | `timeAttack.menuKey` | key | `F10` | Opens the solo Time Attack menu from the master title screen. |
| `TIME_ATTACK_NET_HOST_PORT` | `timeAttack.net.hostPort` | int | `27888` | TCP/WebSocket port for player-hosted LAN race rooms. |
| `TIME_ATTACK_NET_LAST_JOIN_ADDRESS` | `timeAttack.net.lastJoinAddress` | string | `""` | Most recently joined LAN race address. |
| `TIME_ATTACK_NET_DISPLAY_NAME` | `timeAttack.net.displayName` | string | `""` | Multiplayer display name; blank uses the identity prefix. |
| `TIME_ATTACK_NET_MASTER_URL` | `timeAttack.net.masterUrl` | string | `""` | Master-server WebSocket URL for internet race browsing. |
| `TIME_ATTACK_NET_MASTER_TRUST_INSECURE` | `timeAttack.net.masterTrustInsecure` | bool | `false` | Development-only trust-all TLS mode for the master server. |
| `TIME_ATTACK_HUD_MINIMAP` | `timeAttack.hud.minimap` | bool | `true` | Show the multiplayer minimap progress strip. |

### Master verifier settings

These keys belong in the standalone master server's YAML (`MasterServerMain
--config`), not the engine's `config.yaml`:

| YAML key | Default | Description |
|----------|---------|-------------|
| `verifierRegistrationToken` | disabled | Shared bootstrap token required by `openggf-verifier` workers. Keep it secret and rotate it if disclosed. |
| `maxRecordingBytes` | `65536` | Maximum accepted input-only attempt recording size. |
| `maxRecordingStorageBytes` | `536870912` | Maximum total bytes retained in the content-addressed recording store; new uploads receive HTTP 507 when full. |
| `recordingUploadMinIntervalMillis` | `1000` | Minimum interval between accepted recording uploads from the same player identity. |
| `uploadDeadlineSeconds` | `180` | Casual spot-check upload deadline. |
| `verifiedUploadDeadlineSeconds` | `15` | Verified-room upload deadline. |
| `recordingRetentionDays` | `3` | Recording blob retention before scheduled deletion. |
| `verdictGraceMillis` | `10000` | Extra verified-room result hold after the upload window. |
| `spotCheckTopTimes` | `1` | Top casual standings entries selected for spot-checking. |
| `cheatBanDays` | `0` | Failed-verification ban duration; `0` is permanent. |
| `verifierStaleSeconds` | `120` | Worker heartbeat/registration age after which it is unavailable. |
| `verifierLeaseSeconds` | `300` | Job lease duration before requeue. |
| `publicBaseUrl` | `""` | Absolute public HTTPS base for recording requests; blank sends a relative path that clients resolve against their master URL. |

## Debug

BK2 playback controls are unbound by default and must be rebound in `debug.playback`
before BK2 playback can be controlled from the keyboard.

| Key | YAML path | Type | Default | Description |
|-----|-----------|------|---------|-------------|
| `DEBUG_VIEW_ENABLED` | `debug.flags.debugView` | bool | `false` | Eagerly initialise the debug overlay subsystem. Required for any runtime debug keys to function. Does not show anything on-screen until debug mode is activated. |
| `EDITOR_ENABLED` | `debug.flags.editor` | bool | `false` | Allow the experimental in-engine editor overlay to be entered from gameplay with `Shift+Tab`. |
| `LIVE_REWIND_ENABLED` | `rewind.liveEnabled` | bool | `false` | Enable held-key rewind during ordinary live level play. Uses gameplay rewind snapshots, records live input while enabled, and presents reverse audio/fade state while held. |
| `LIVE_REWIND_DETERMINISM_AUDIT` | `debug.rewind.determinismAudit` | bool | `false` | Audit live-rewind determinism: re-simulates each completed rewind keyframe segment during live play and logs the first state divergence, pinpointing state that is missing from rewind capture. Disarms after the first divergence because replayed out-of-snapshot state cannot be rolled back. |
| `LIVE_REWIND_TAPE_COAST_ENABLED` | `rewind.tapeCoastEnabled` | bool | `false` | Enable experimental live-rewind coast after releasing the rewind key. Disabled by default, so held rewind remains one step per visual frame. When enabled, reverse audio playback is resampled to match the current rewind speed (>1.0 pitches up, <1.0 plays slow-motion). |
| `LIVE_REWIND_TAPE_COAST_MIN_STEPS` | `rewind.tapeCoastMinSteps` | number | `0.25` | Initial rewind speed (in steps per visual frame) when the rewind key is first pressed. Values below 1.0 produce a slow-motion start: the speed controller's fractional accumulator spreads each physics step across multiple visual frames. Speed then accelerates toward `LIVE_REWIND_TAPE_COAST_MAX_STEPS`. Used only when tape coast is enabled. |
| `LIVE_REWIND_TAPE_COAST_ACCELERATION` | `rewind.tapeCoastAcceleration` | number | `0.25` | Optional tape-coast acceleration in rewind steps per held frame. Used only when tape coast is enabled. |
| `LIVE_REWIND_TAPE_COAST_DECELERATION` | `rewind.tapeCoastDeceleration` | number | `0.5` | Optional tape-coast deceleration in rewind steps per released frame. Used only when tape coast is enabled. |
| `LIVE_REWIND_TAPE_COAST_MAX_STEPS` | `rewind.tapeCoastMaxSteps` | number | `4.0` | Maximum rewind steps per visual frame for optional tape-coast rewind. Values below 1.0 cap the rewind in slow-motion. Used only when tape coast is enabled. |
| `LIVE_REWIND_VHS_EFFECT` | `rewind.vhsEffect` | bool | `true` | Render an authentic VHS picture-search effect (scrolling noise bars, scanline jitter, chroma bleed, tape dropouts, head-switch strip) while live rewind is active, fading out over ~10 frames after release. Applied after the fade pass and before any user display shader. Only meaningful when `LIVE_REWIND_ENABLED` is true. |
| `LIVE_REWIND_VHS_TEAR_BANDS` | `rewind.vhsTearBands` | bool | `true` | Include the scrolling tear bands in the VHS rewind effect. Set `false` to keep the rest of the effect (scanline jitter, chroma bleed, tape dropouts, head-switch strip, wobble) without the bands. Only meaningful when the VHS effect is enabled. |
| `REWIND_HISTORY_SECONDS` | `rewind.historySeconds` | int | `60` | Seconds of live rewind keyframe and input history to retain. The effective retained window may be up to one keyframe interval longer so replay always has a complete keyframe-to-target input segment. |
| `REWIND_AUDIO_HISTORY_LIMIT_TYPE` | `rewind.audioHistoryLimitType` | string | `"time"` | How the rewind audio PCM history ring is capped. `"time"` caps by `REWIND_AUDIO_HISTORY_SECONDS`; `"size"` caps by `REWIND_AUDIO_HISTORY_SIZE_MB`. Held rewind beyond the cap plays silence on develop (the audio-rewind feature branch engages the reverse resynthesizer instead). |
| `REWIND_AUDIO_HISTORY_SECONDS` | `rewind.audioHistorySeconds` | int | `60` | Seconds of stereo PCM history kept for held-rewind playback when `REWIND_AUDIO_HISTORY_LIMIT_TYPE` is `"time"`. |
| `REWIND_AUDIO_HISTORY_SIZE_MB` | `rewind.audioHistorySizeMb` | int | `10` | Megabytes of stereo PCM history kept for held-rewind playback when `REWIND_AUDIO_HISTORY_LIMIT_TYPE` is `"size"`. Stereo 16-bit at 48 kHz consumes ~192 KB/s, so 10 MB is roughly 54 s at that sample rate (~57 s at 44.1 kHz). |
| `PLAYBACK_MOVIE_PATH` | `debug.playback.moviePath` | string | `""` | Path to a BizHawk BK2 movie for playback debugging. |
| `PLAYBACK_TOGGLE_KEY` | `debug.playback.toggleKey` | key | `""` / unbound | Toggle playback mode. |
| `PLAYBACK_LOAD_KEY` | `debug.playback.loadKey` | key | `""` / unbound | Load/reload the BK2 movie. |
| `PLAYBACK_PLAY_PAUSE_KEY` | `debug.playback.playPauseKey` | key | `""` / unbound | Toggle playback play/pause. |
| `PLAYBACK_STEP_BACK_KEY` | `debug.playback.stepBackKey` | key | `""` / unbound | Step the BK2 cursor backward by one frame. |
| `PLAYBACK_STEP_FORWARD_KEY` | `debug.playback.stepForwardKey` | key | `""` / unbound | Step the BK2 cursor forward by one frame. |
| `PLAYBACK_JUMP_BACK_KEY` | `debug.playback.jumpBackKey` | key | `""` / unbound | Jump the BK2 cursor backward by a larger interval. |
| `PLAYBACK_JUMP_FORWARD_KEY` | `debug.playback.jumpForwardKey` | key | `""` / unbound | Jump the BK2 cursor forward by a larger interval. |
| `PLAYBACK_FAST_RATE_KEY` | `debug.playback.fastRateKey` | key | `""` / unbound | Cycle playback rate (1x/2x/4x/8x). |
| `PLAYBACK_RESET_TO_START_KEY` | `debug.playback.resetToStartKey` | key | `""` / unbound | Reset the BK2 cursor to `PLAYBACK_START_OFFSET_FRAME`. |
| `PLAYBACK_START_OFFSET_FRAME` | `debug.playback.startOffsetFrame` | int | `0` | Starting frame offset for BK2 playback. |
| `RECORDING_RECORD_KEY` | `debug.recording.recordKey` | key | `F9` | `Shift+Record` starts recording from live `LEVEL` mode or opens the master-title recordings menu; plain Record stops an active recording. |
| `TEST_MODE_ENABLED` | `debug.testMode.enabled` | bool | `false` | Replace the master-title game-select with the Trace Test Mode picker that lists every trace in `debug.testMode.catalogDir` and plays the chosen trace back in the live engine. Dev-only. **When `true`, `DISPLAY_ASPECT` is always forced to `NATIVE_4_3` (320×224) regardless of its configured value** — trace replay and test-mode runs are parity-critical and must always run at 320×224. |
| `TRACE_CATALOG_DIR` | `debug.testMode.catalogDir` | string | `"src/test/resources/traces"` | Directory scanned by `TraceCatalog` when `TEST_MODE_ENABLED` is true. Resolved against `user.dir`. |
| `TRACE_SHOW_DESYNC_GHOSTS` | `debug.traceRender.showDesyncGhosts` | bool | `true` | In Trace Test Mode and trace capture, render the desync ghost(s). |
| `TRACE_SHOW_GAME_HUD` | `debug.traceRender.showGameHud` | bool | `true` | Render the game HUD (rings/score/time) during trace replay/capture. |
| `TRACE_SHOW_DEBUG_HUD` | `debug.traceRender.showDebugHud` | bool | `false` | Render the debug HUD during trace replay/capture; individual panels follow the existing `DebugOverlayToggle` states. |
| `DISCORD_RICH_PRESENCE_ENABLED` | `discord.enabled` | bool | `false` | Opt in to publishing OpenGGF menu/gameplay status through the local Discord desktop client. Disabled by default for privacy and no-ops when Discord is unavailable. |
| `DISCORD_RICH_PRESENCE_SHOW_TIMER` | `discord.showTimer` | bool | `true` | Include the current level timer in Discord Rich Presence gameplay status when presence is enabled. |
| `DISCORD_RICH_PRESENCE_SHOW_ZONE` | `discord.showZone` | bool | `true` | Include the current zone and act in Discord Rich Presence gameplay status when presence is enabled. |

Live rewind stores gameplay checkpoints every 10 ticks and audio checkpoints every
60 ticks. A cold backward step therefore replays at most nine gameplay ticks.
Compared with the previous 60-tick gameplay cadence, this retains roughly six
times as many gameplay snapshots and performs more forward-play capture work.
`rewind.historySeconds` bounds that history; reduce it if memory use matters.
Trace playback retains its configured checkpoint interval.

### Level Editor (experimental)

The in-engine level editor (see `EDITOR_ENABLED` / `debug.flags.editor`) uses **hardcoded**
key/mouse bindings — not configurable in `config.yaml`. While playing with `EDITOR_ENABLED` true,
press `Shift+Tab` to toggle gameplay (playtest) ↔ editor.

| Input | Action |
|-------|--------|
| `Shift+Tab` | Toggle editor / playtest mode |
| `F5` | Leave the editor and restart the playtest from the level start (does not save the sidecar) |
| Arrows / gamepad menu directions | Move the world cursor, or browse the focused block/chunk/object library in two dimensions |
| `Page Up` / `Page Down` | Move by one page in the focused library |
| `Tab` | Cycle focused region |
| `Space` while a library is focused | Confirm the hovered block, chunk, or object as the active brush |
| `Insert` | Enter/leave library filter capture; while active, typed text filters the library and other editor shortcuts are suppressed |
| `Backspace` / `Ctrl+Backspace` while filtering | Remove one filter character / clear the filter |
| `O` / gamepad Start | Cycle spawn editing mode: terrain → objects → rings |
| `Space` | Apply the primary action when no library browser is focused |
| Gamepad A | Place the selected object or ring while spawn editing is active |
| `E` | Eyedrop the block or spawn under the cursor |
| Gamepad B | Eyedrop the spawn under the cursor while spawn editing is active |
| `Delete` / gamepad C | Delete the spawn at the cursor while editing objects or rings |
| `M` | Move the selected object/ring spawn to the world cursor |
| `L` | Toggle active layer (FG / BG) |
| `Enter` / `Escape` | Descend / ascend the hierarchy |
| `Ctrl+Z` / `Ctrl+Y` / `Ctrl+S` | Undo / Redo / Save |
| `Ctrl+Shift+E` | Export the complete level to its deterministic local mod-project directory |
| `C` | Toggle the collision overlay |
| `P` | Toggle the collision path (primary / secondary) |
| `V` | Cycle the selected block cell's collision mode |
| `[` / `]` | Decrement / increment the selected chunk's solid-tile index |
| Left mouse (drag) | Paint the selected block as one undoable stroke; in object/ring mode, place a spawn at the cursor |
| Right mouse | Eyedrop the hovered block or spawn |

Bindings live in `EditorInputHandler` and are not affected by the Key Bindings entries above.

#### Complete level export

`Ctrl+Shift+E` writes a complete `ModLevelDefinition` v1 directory beneath
`exports/editor/<game-code>/zone_<zone>_act_<act>`, relative to the process working directory. The
directory is deterministic for the active game, zone, and act. Export is deliberately
non-overwriting: if that directory already exists, the action fails without changing it. Move,
rename, or remove an earlier export before exporting the same level again.

The exporter snapshots the active editor level and uses explicit runtime metadata for the export:

- the displayed name is `<stock zone name> EDITOR EXPORT`;
- the authored zone id is `0x40 + stock zone index`;
- the authored level id is `0x400 + (stock zone index * 16) + act`;
- the start position and music reference come from the active game's zone registry; and
- installed keyed objects retain their fully qualified `owner:key` identity. Objects used only as
  backing records for expanded ring placements are omitted; their individual rings are exported.

Before publishing, every asset and reference is checked for an exact, lossless v1 representation,
and the staged directory is read back through the strict mod-level parser. Publication then renames
the complete staged directory into place, so a failed export does not leave a partial target.

The directory contains data decoded from the user's supplied game ROM as well as editor-authored
changes. OpenGGF does not grant rights to redistribute Sega assets. Treat exports as local mod
development material unless you independently have permission to distribute every included asset;
prefer distributing original replacement assets and mod metadata rather than extracted ROM data.

---

## Key Bindings

Key bindings accept any of the following formats:

| Format | Example | Notes |
|--------|---------|-------|
| GLFW numeric code | `81` | Traditional format |
| Numeric string | `"81"` | Same as above, as a string |
| Key name | `"Q"` | Human-readable, case-insensitive |
| Named key | `"SPACE"`, `"ENTER"`, `"F9"` | Special keys by name |
| Modifier key | `"LEFT_SHIFT"`, `"RIGHT_CONTROL"` | Modifier keys |
| GLFW prefix | `"GLFW_KEY_Q"` | Full GLFW constant name (prefix stripped) |
| Chord | `"SHIFT+O"`, `"CTRL+SHIFT+O"`, `"META+LEFT_BRACKET"` | Key qualified by modifiers — acted on by the bindings listed under *Modifier support per binding* below |

Invalid key names log a warning and fall back to the default binding for that key.

#### Chord syntax

- **Modifier aliases** (case-insensitive): `CTRL`/`CONTROL`, `SHIFT`,
  `ALT`/`OPTION`, and `META`/`SUPER`/`CMD`/`COMMAND`/`WIN`. Whitespace around
  `+` is tolerated, so `"Shift + O"` and `"shift+o"` are the same value.
- **Canonical order is `CTRL, SHIFT, ALT, META`.** That is the spelling the
  engine writes back when it saves the config, whatever order you typed.
- **Matching is exact.** A binding fires only when its declared modifiers are
  held *and* the others are released, so a plain `"O"` does not fire while any
  modifier is down.
- **Binding the plus key:** `+` is the separator, so write `EQUAL` (or `KP_ADD`
  for the numpad). A value of only separators (`"+"`, `"++"`) names no key, so
  it is an unresolvable value under the next bullet and falls back to the
  binding's registered default — it does **not** unbind. `capture.toggleKey:
  "+"` leaves recording live on `SHIFT+O`. Only `""` unbinds.
- **Unresolvable values.** A **non-empty** value that resolves to no key —
  `"NOT_A_KEY"`, `"CTRL+"`, an unknown modifier — logs a warning and falls back
  to that binding's registered default. An **explicitly empty** value (`""`) is
  unbound outright: no default is substituted. That asymmetry is how a shortcut
  is deliberately switched off.
- **`O` is reserved for `capture.toggleKey`.** The migration that carries
  existing installs onto the `SHIFT+O` default matches on the value rather than
  on a schema version, so it re-runs on every launch and rewrites every value
  that resolves to an unmodified `O` — `O`, `GLFW_KEY_O`, `KEY_O`, `79` and the
  quoted `"79"` — back to `SHIFT+O`. Binding *this one action* to a bare `O` is
  not possible; pick another key.

#### Modifier support per binding

Modifiers parse everywhere, but only some bindings act on them. There are three
states, and the third is invisible from the config file:

| State | Meaning | Bindings |
|-------|---------|----------|
| Chord honoured | Read as a chord and matched exactly | `CAPTURE_TOGGLE_KEY` |
| Modifiers ignored | Read as a bare key code; a chord resolves to its key and the modifiers are dropped, so `"CTRL+P"` fires on plain `P` | every binding not named in the other two rows |
| Chord permanently dead | Read through a "no modifier held" check, so the modifier you must hold to type the chord is exactly what blocks the shortcut. The modifiers are still dropped, so `debug.playback.toggleKey: "CTRL+P"` binds plain `P`: the chord as written never fires, and an unmodified `P` does | the nine `PLAYBACK_*` keys; `SPECIAL_STAGE_KEY`, `SPECIAL_STAGE_COMPLETE_KEY`, `SPECIAL_STAGE_FAIL_KEY`, `SPECIAL_STAGE_SPRITE_DEBUG_KEY`, `SPECIAL_STAGE_PLANE_DEBUG_KEY`, `NEXT_ACT`, `NEXT_ZONE`, `DEBUG_LAST_CHECKPOINT_KEY`, `LEVEL_SELECT_KEY`, `CROSS_GAME_S1_DATA_SELECT_IMAGE_COORD_LOG_KEY` |

`UP`/`DOWN`/`LEFT`/`RIGHT` and `DEBUG_MODE_KEY` are in **two** states at once.
Modifiers are ignored for `UP`/`DOWN`/`LEFT`/`RIGHT` on the gameplay path, and
the chord is dead on the special-stage sprite-debug path, which reads them
through the same unmodified-debug-key check. `DEBUG_MODE_KEY`'s chord is dead in
`GameLoop` (both reads go through that check), but `SpriteManager`'s debug-mode
toggle and the live-rewind input recorder read it with a plain key-pressed
check, so `debug.keys.debugMode: "CTRL+D"` still toggles sprite debug mode on a
plain `D` with the Ctrl neither required nor checked.

Some shortcuts are hardcoded and consume a keystroke whatever a binding says:
Shift/Ctrl/Alt+`B` (bonus-stage debug, exactly one modifier), Shift+`Tab`, left
Ctrl+`P` (copy performance stats — debug-only, see below), the editor's
`Tab`-without-Shift and Ctrl+`Z`/`S`/`Y`, and the display shader picker's confirm
key. The sixteen debug-overlay toggles
(`F1`-`F8`, `F10`-`F12`, `K`, `` ` ``, `=`, `P`, `O`) are hardcoded too. They fire
on their bare key with any modifier held — a modifier is usually held for an
unrelated reason, and player two's jump defaults to right Shift — but stand aside
for the frame a chord bound to the *same* key is satisfied, which is what stops
the `SHIFT+O` capture default toggling the object-debug overlay. Binding
`capture.toggleKey` to one of these keys with no modifier makes both fire on the
same keystroke.

A toggle only stands aside for an action that can actually run, so `P` gives way
to the stats copy only while `DEBUG_VIEW_ENABLED` is on. With debug shortcuts
off — the shipped default — Ctrl+`P` toggles the performance overlay and copies
nothing, so no install has a keystroke that silently overwrites the OS clipboard.
The stats copy also needs the **left** Ctrl specifically: `CTRL` in a configured
chord means either Ctrl, but right Ctrl is player two's default Start, so
matching either would let player two's Start turn player one's `P` into a
clipboard write. `RECORDING_RECORD_KEY` is a
configurable binding whose Shift is still hardcoded at its call sites (the
runtime recording controls and the master title screen), so its modifier cannot
be moved into the value yet.

**Under playback:** live rewind records and reproduces real Shift, Ctrl, Alt and
Super states, so a chord behaves the same on replay as it did live. BK2 movies
carry no modifier column at all, so all four read as released under BK2
playback and any chord requiring a modifier does not fire there.

The tables below list each key's name, default code, and the human-readable key name for the default.

### Gameplay Controls

Keyboard action bindings are Mega Drive actions. A, B, and C all function as
jump buttons during platforming gameplay. By default only A is bound on
keyboard: P1 A is `SPACE`, P2 A is `RIGHT_SHIFT`, and keyboard B/C are
unbound.

Gamepads use GLFW's standard position-based gamepad layout. D-pad and left
stick feed digital movement; the west face button maps to Mega Drive A, south
maps to Mega Drive B, and east maps to Mega Drive C. On an Xbox controller
that is X/A/B respectively; on a PlayStation controller that is
Square/Cross/Circle respectively.

| Key | YAML path | Default | Key Name | Description |
|-----|-----------|---------|----------|-------------|
| `UP` | `input.player1.up` | `265` | ↑ Arrow | Look up / enter tubes. |
| `DOWN` | `input.player1.down` | `264` | ↓ Arrow | Crouch / roll / spindash charge. |
| `LEFT` | `input.player1.left` | `263` | ← Arrow | Move left. |
| `RIGHT` | `input.player1.right` | `262` | → Arrow | Move right. |
| `P1_A` | `input.player1.a` | `32` | Space | Player 1 action button A / jump. |
| `P1_B` | `input.player1.b` | `-1` | unbound | Player 1 action button B. |
| `P1_C` | `input.player1.c` | `-1` | unbound | Player 1 action button C. |
| `START` | `input.player1.start` | `259` | Backspace | Player 1 Start: ROM-accurate in-game pause (`Game_paused` / `Pause_Loop`). A press during level gameplay freezes the level update for the frame while the frame counter still advances; press again to resume. Distinct from `PAUSE_KEY`, which is the loop/timing-level pause that also halts audio. Keyboard-only: the gamepad Start button is instead wired to `PAUSE_KEY`'s pause (see below), since this one has no visible feedback. |
| `P2_UP` | `input.player2.up` | `73` | I | Player 2 look up. |
| `P2_DOWN` | `input.player2.down` | `75` | K | Player 2 crouch / roll. |
| `P2_LEFT` | `input.player2.left` | `74` | J | Player 2 move left. |
| `P2_RIGHT` | `input.player2.right` | `76` | L | Player 2 move right. |
| `P2_A` | `input.player2.a` | `344` | Right Shift | Player 2 action button A / jump. In S2 this feeds the configured sidekick/manual-input path; it does not enable native two-player/competition gameplay. |
| `P2_B` | `input.player2.b` | `-1` | unbound | Player 2 action button B. |
| `P2_C` | `input.player2.c` | `-1` | unbound | Player 2 action button C. |
| `P2_START` | `input.player2.start` | `345` | Right Control | Player 2 Start. |
| `CONTROLLER_ENABLED` | `input.controller.enabled` | `true` | true | Enable GLFW gamepad/controller input. |
| `CONTROLLER_DEADZONE` | `input.controller.deadzone` | `0.35` | 0.35 | Left-stick digital direction deadzone. |
| `CONTROLLER_PLAYER1` | `input.controller.player1` | `"auto"` | auto | Controller assignment for Player 1 (`auto` or `none`). |
| `CONTROLLER_PLAYER2` | `input.controller.player2` | `"auto"` | auto | Controller assignment for Player 2 (`auto` or `none`). |

The gamepad Back/Select/View button on the primary connected pad is a hardcoded (not remappable, no config key) stand-in for the keyboard `Tab` key on the main-menu game-select screen (`MasterTitleScreen`) and its per-game options panel (`LaunchConfigPanel`): it opens the options panel for the selected game and closes it again, mirroring `Tab`'s role in that specific flow only. It has no effect on `Tab`'s other keyboard uses elsewhere (level editor toggle, Special Stage entry, art viewer), which are unrelated screens/modes.

| `PAUSE_KEY` | `input.pause` | `257` | Enter | Pause / unpause the game (`userPaused`): shows the "PAUSED" HUD overlay and halts audio. The gamepad Start button on the primary connected pad also toggles this pause, unconditionally (not remappable) -- gamepad Start does NOT trigger the ROM `Game_paused` ("Player 1 Start" / `START`) pause above, since that one has no visible feedback. |
| `FRAME_STEP_KEY` | `debug.keys.frameStep` | `81` | Q | Advance one frame while paused. The gamepad right bumper (RB/R1) on the primary connected pad also steps a frame, unconditionally (not remappable). |
| `RECORDING_RECORD_KEY` | `debug.recording.recordKey` | `298` | F9 | `Shift+Record` starts/opens user recording flows; plain Record stops active recording. |
| `TRACE_REWIND_KEY` | `debug.traceRewind.key` | `82` | R | Hold during visual Trace Test Mode replay to rewind deterministic engine state in real time, including reverse audio presentation and restored fade snapshots. |
| `LIVE_REWIND_KEY` | `rewind.liveKey` | `82` | R | Hold during live level play to rewind deterministic gameplay state when `LIVE_REWIND_ENABLED` is true, including reverse audio presentation and restored fade snapshots. The gamepad left bumper (L1/LB) on the primary connected pad also holds rewind, unconditionally (not remappable). |
| `LIVE_REWIND_HALF_SPEED_KEY` | `rewind.liveHalfSpeedKey` | `341` | Left Ctrl | Modifier held together with the rewind key for half-speed rewind (one engine step every other frame; reverse audio plays slow-motion). The mirrored left/right variant of a modifier key also counts. Holding both speed modifiers cancels back to normal speed. |
| `LIVE_REWIND_DOUBLE_SPEED_KEY` | `rewind.liveDoubleSpeedKey` | `340` | Left Shift | Modifier held together with the rewind key for double-speed rewind (two engine steps per frame; reverse audio pitches up, and the VHS effect shows a third tear band). The mirrored left/right variant of a modifier key also counts. |
| `TIME_ATTACK_RETRY_KEY` | `timeAttack.retryKey` | `82` | R | Instantly retry the current solo time attack from the act start. |
| `TIME_ATTACK_MENU_KEY` | `timeAttack.menuKey` | `299` | F10 | Opens the solo Time Attack menu from the master title screen. |
| `TIME_ATTACK_NET_HOST_PORT` | `timeAttack.net.hostPort` | `27888` |  | TCP/WebSocket port for player-hosted LAN race rooms. |
| `TIME_ATTACK_NET_LAST_JOIN_ADDRESS` | `timeAttack.net.lastJoinAddress` | `""` |  | Most recently joined LAN race address. |
| `TIME_ATTACK_NET_DISPLAY_NAME` | `timeAttack.net.displayName` | `""` |  | Multiplayer display name; blank uses the identity prefix. |
| `TIME_ATTACK_NET_MASTER_URL` | `timeAttack.net.masterUrl` | `""` |  | Master-server WebSocket URL for internet race browsing. |
| `TIME_ATTACK_NET_MASTER_TRUST_INSECURE` | `timeAttack.net.masterTrustInsecure` | `false` |  | Development-only trust-all TLS mode for the master server. |
| `TIME_ATTACK_HUD_MINIMAP` | `timeAttack.hud.minimap` | `true` |  | Show the multiplayer minimap progress strip. |

### Debug Navigation

| Key | YAML path | Default | Key Name | Description |
|-----|-----------|---------|----------|-------------|
| `NEXT_ACT` | `debug.keys.nextAct` | `266` | PAGE_UP | Skip to the next act within the current zone. |
| `NEXT_ZONE` | `debug.keys.nextZone` | `267` | PAGE_DOWN | Skip to the first act of the next zone. |
| `DEBUG_MODE_KEY` | `debug.keys.debugMode` | `68` | D | Toggle free-fly debug movement mode (requires `DEBUG_VIEW_ENABLED`). The gamepad north face button (Y/Triangle) on the primary connected pad also toggles it, unconditionally (not remappable). |
| `DEBUG_LAST_CHECKPOINT_KEY` | `debug.keys.lastCheckpoint` | `67` | C | Teleport the player to the most recently activated checkpoint. |
| `LEVEL_SELECT_KEY` | `debug.keys.levelSelect` | `298` | F9 | Open the level select screen at runtime. |
| `TEST` | `debug.keys.test` | `84` | T | Generic test button used during development. |

### Super Sonic / Emerald Debug

| Key | YAML path | Default | Key Name | Description |
|-----|-----------|---------|----------|-------------|
| `SUPER_SONIC_DEBUG_KEY` | `debug.keys.superSonic` | `85` | U | Toggle Super Sonic transformation (requires `DEBUG_VIEW_ENABLED` and all emeralds). |
| `GIVE_EMERALDS_KEY` | `debug.keys.giveEmeralds` | `69` | E | Instantly award all Chaos Emeralds (debug shortcut). |
| `HYPER_FORM_DEBUG_KEY` | `debug.keys.hyperForm` | `SHIFT+U` | Shift+U | Toggle the highest S3K form unlocked by Super Emeralds: Hyper Sonic, Super Tails, or Hyper Knuckles. |
| `GIVE_SUPER_EMERALDS_KEY` | `debug.keys.giveSuperEmeralds` | `SHIFT+E` | Shift+E | Instantly award all Super Emeralds (debug shortcut). |

### Special Stage Debug

These keys are only active while a Special Stage is running.

| Key | YAML path | Default | Key Name | Description |
|-----|-----------|---------|----------|-------------|
| `SPECIAL_STAGE_KEY` | `debug.keys.specialStage` | `258` | Tab | Enter / exit Special Stage mode (debug). |
| `SPECIAL_STAGE_COMPLETE_KEY` | `debug.keys.specialStageComplete` | `269` | End | Complete the current Special Stage and award the emerald. |
| `SPECIAL_STAGE_FAIL_KEY` | `debug.keys.specialStageFail` | `261` | Delete | Fail the current Special Stage without awarding the emerald. |
| `SPECIAL_STAGE_SPRITE_DEBUG_KEY` | `debug.keys.specialStageSpriteDebug` | `301` | F12 | Toggle the Special Stage sprite debug viewer. |
| `SPECIAL_STAGE_PLANE_DEBUG_KEY` | `debug.keys.specialStagePlaneDebug` | `292` | F3 | Cycle Special Stage plane visibility debug modes. |

---

## Test-only system properties

These properties are read by JVM system property lookups (`-D<name>=<value>` on
the `mvn` or `java` command line) rather than `config.yaml`. They exist for
diagnostic test runs only and must remain unset in CI.

| Property | Type | Purpose |
| --- | --- | --- |
| `oggf.trace.hydrate` | Boolean (default `false`) | Diagnostic hydrate switch for trace replay tests. When `true` AND the trace's `metadata.json` declares a recorder version at or above `9.2-s2` (see `TraceMetadata.nativePreludeMode()`), the test harness snaps engine state to the recorded ROM frame-0 snapshot (player position-record buffer, sidekick CPU state, per-slot SST values) BEFORE the per-frame comparison loop begins. A run with this enabled is **NOT a valid green replay**: the switch masks the very divergences trace replay is designed to surface. Use only to isolate prelude bugs from gameplay-loop bugs. A `WARN`-level log line emits when the switch fires; `TestTraceHydrateSwitchDefault` is the CI guard that asserts the property is unset on master. |

---

## Example `config.yaml`

The following is the bundled default `src/main/resources/config.yaml`:

```yaml
# OpenGGF configuration — grouped and documented.
# Indentation is significant (YAML). This file is rewritten cleanly on save.

# ── Display ──
display:
  aspect: "NATIVE_4_3"   # Display aspect preset; resolves screen pixel width, height stays 224
  windowAutosize: true   # Derive the window size from the aspect preset at the 2x baseline
  shaderLibraryRoot: shaders   # Root directory scanned for user display shaders
  shaderSelection: "OFF"   # Last selected display shader: OFF or a root-relative forward-slash path
  shaderNextKey: RIGHT_BRACKET   # Runtime key to advance to the next display shader
  shaderPreviousKey: LEFT_BRACKET   # Runtime key to move to the previous display shader
  shaderPickerKey: BACKSLASH   # Runtime key to open the searchable display shader picker
  shaderDefaultPhase: PRESENTATION   # Fallback render phase for standalone display shaders
  deadzoneMode: "PROPORTIONAL"   # Camera horizontal deadzone behaviour on wide screens
  colorProfile: "RAW_RGB"   # Display-only color profile for Mega Drive palette presentation
  colorProfileToggleKey: V   # Runtime key to cycle the display color profile
  fps: 60   # Frames per second to render (changes game speed)

# ── Input ──
input:
  pause: ENTER   # Toggle pause
  controller:
    enabled: true   # Enable gamepad/controller input
    deadzone: 0.35   # Analog controller deadzone
    player1: "auto"   # Controller assignment for Player 1
    player2: "auto"   # Controller assignment for Player 2
  player1:
    up: UP   # Player 1: look up
    down: DOWN   # Player 1: crouch/roll
    left: LEFT   # Player 1: move left
    right: RIGHT   # Player 1: move right
    a: SPACE   # Player 1: action button A
    b: ""   # Player 1: action button B
    c: ""   # Player 1: action button C
    start: BACKSPACE   # Player 1: start (in-game pause)
  player2:
    up: I   # Player 2: look up
    down: K   # Player 2: crouch/roll
    left: J   # Player 2: move left
    right: L   # Player 2: move right
    a: RIGHT_SHIFT   # Player 2: action button A
    b: ""   # Player 2: action button B
    c: ""   # Player 2: action button C
    start: RIGHT_CONTROL   # Player 2: start

# ── Audio ──
audio:
  enabled: true   # Enable music and SFX
  region: "NTSC"   # Region for audio timing
  dacInterpolate: false   # Optional DAC smoothing (no hardware counterpart); true interpolates between PCM samples
  fmCore: fast   # FM core: accurate (cycle-exact Nuked-OPN2) or fast (register-level clean-room core)
  internalRateOutput: false   # Output audio at the internal YM2612 rate (~53kHz)
  fm6DacOff: true   # Mute FM6 when a note plays on it while DAC is enabled (SMPSPlay parity hack)

# ── Characters ──
characters:
  main: "sonic"   # Sprite code of the main playable character
  sidekick: "tails"   # Sprite code of the CPU sidekick; empty string disables the sidekick
  dataSelectExtraCombos: ""   # Semicolon-separated extra player combos for the data select screen

# ── ROMs ──
roms:
  sonic1: "s1.gen"   # Filename of the Sonic 1 ROM
  sonic2: "s2.gen"   # Filename of the Sonic 2 ROM
  sonic3k: "s3k.gen"   # Filename of the Sonic 3&K ROM
  default: "s2"   # Which game to load by default

# ── Startup ──
startup:
  titleScreen: true   # Show the title screen on startup
  masterTitleScreen: true   # Show the master (game-selection) title screen on startup
  legalDisclaimer: true   # Show the legal disclaimer screen before the master title screen

# ── Rewind (live) ──
rewind:
  liveEnabled: false   # Enable held-key rewind during ordinary live play
  liveKey: R   # Key held to rewind during live play
  liveHalfSpeedKey: LEFT_CONTROL   # Modifier held with the rewind key for half-speed rewind
  liveDoubleSpeedKey: LEFT_SHIFT   # Modifier held with the rewind key for double-speed rewind
  tapeCoastEnabled: false   # Continue rewinding with a decelerating tape-coast after key release
  tapeCoastAcceleration: 0.25   # Per-tick speed increase while tape-coast is held
  tapeCoastDeceleration: 0.5   # Per-tick speed decrease after release
  tapeCoastMaxSteps: 4.0   # Maximum rewind steps per tick
  tapeCoastMinSteps: 0.25   # Minimum rewind steps per tick; below 1.0 gives slow-motion rewind
  vhsEffect: true   # VHS picture-search effect while live rewind is active
  vhsTearBands: true   # Include the scrolling tear bands in the VHS rewind effect
  historySeconds: 60   # Seconds of live rewind keyframe and input history to retain
  audioHistoryLimitType: "time"   # How the rewind audio PCM history ring is sized
  audioHistorySeconds: 60   # Seconds of PCM history kept when audioHistoryLimitType=time
  audioHistorySizeMb: 10   # Megabytes of PCM history kept when audioHistoryLimitType=size

# ── Cross-Game ──
crossGame:
  enabled: false   # Enable cross-game feature donation (e.g. S2 sprites in S1)
  source: "s2"   # Donor game for cross-game features

# ── Launch Profiles ──
# Per-game launch defaults edited from the master title screen.
launch:
  s1:
    rewind: false   # Default Sonic 1 launch profile: enable live rewind
    crossGameSource: "off"   # Default Sonic 1 launch profile: cross-game donor
    debugTools: false   # Default Sonic 1 launch profile: enable debug tools
    aspect: "global"   # Default Sonic 1 launch profile: display aspect override
    mainCharacter: "sonic"   # Default Sonic 1 launch profile: main character
    sidekick: "none"   # Default Sonic 1 launch profile: sidekick character
  s2:
    rewind: false   # Default Sonic 2 launch profile: enable live rewind
    crossGameSource: "off"   # Default Sonic 2 launch profile: cross-game donor
    debugTools: false   # Default Sonic 2 launch profile: enable debug tools
    aspect: "global"   # Default Sonic 2 launch profile: display aspect override
    mainCharacter: "sonic"   # Default Sonic 2 launch profile: main character
    sidekick: "tails"   # Default Sonic 2 launch profile: sidekick character
  s3k:
    rewind: false   # Default Sonic 3&K launch profile: enable live rewind
    crossGameSource: "off"   # Default Sonic 3&K launch profile: cross-game donor
    debugTools: false   # Default Sonic 3&K launch profile: enable debug tools
    aspect: "global"   # Default Sonic 3&K launch profile: display aspect override
    mainCharacter: "sonic"   # Default Sonic 3&K launch profile: main character
    sidekick: "tails"   # Default Sonic 3&K launch profile: sidekick character

# ── Discord Rich Presence ──
discord:
  enabled: false   # Enable Discord Rich Presence updates
  showTimer: true   # Show the level timer in Rich Presence
  showZone: true   # Show the zone and act in Rich Presence

# ════════════════════════════════════════════
#  DEBUG  (developer tooling — safe to ignore for normal play)
# ════════════════════════════════════════════
debug:
  flags:
    debugView: false   # Enable the debug overlay subsystem; visible HUD starts hidden until toggled
    editor: false   # Allow entering the level editor from gameplay
  keys:
    test: T   # Debug-only test button
    nextAct: PAGE_UP   # Advance to the next act
    nextZone: PAGE_DOWN   # Advance to the next zone
    debugMode: D   # Toggle debug movement mode
    frameStep: Q   # Step forward one frame while paused
    lastCheckpoint: C   # Teleport to the last checkpoint
    levelSelect: F9   # Open the level select screen
    superSonic: U   # Toggle Super Sonic debug mode
    giveEmeralds: E   # Give all chaos emeralds
    hyperForm: SHIFT+U   # Toggle Hyper Sonic, Super Tails, or Hyper Knuckles
    giveSuperEmeralds: SHIFT+E   # Give all super emeralds
    specialStage: TAB   # Toggle special stage mode
    specialStageComplete: END   # Complete the special stage with an emerald
    specialStageFail: DELETE   # Fail the special stage
    specialStageSpriteDebug: F12   # Toggle the special stage sprite debug viewer
    specialStagePlaneDebug: F3   # Cycle special stage plane visibility debug modes
  startup:
    levelSelectOnStartup: false   # Open Level Select on startup instead of loading the first zone
    s3kSkipIntros: false   # Skip S3K zone intro sequences (AIZ biplane, etc.)
  playback:
    moviePath: ""   # Path to a BizHawk BK2 movie for playback debugging
    toggleKey: ""   # Toggle playback mode
    loadKey: ""   # Load/reload the BK2 movie
    playPauseKey: ""   # Toggle playback play/pause
    stepBackKey: ""   # Step the cursor back one frame
    stepForwardKey: ""   # Step the cursor forward one frame
    jumpBackKey: ""   # Jump the cursor back by a larger interval
    jumpForwardKey: ""   # Jump the cursor forward by a larger interval
    fastRateKey: ""   # Cycle playback rate (1x/2x/4x/8x)
    resetToStartKey: ""   # Reset the cursor to the start offset
    startOffsetFrame: 0   # Starting frame offset for BK2 playback
  recording:
    recordKey: F9   # Shift+key starts/opens recordings; key alone stops active recording
  traceRewind:
    key: R   # Key held in Trace Test Mode to rewind deterministic engine state
  rewind:
    determinismAudit: false   # Re-simulate each completed live-rewind keyframe segment and log the first divergence
  traceRender:
    showDesyncGhosts: true   # Render desync ghosts in Trace Test Mode and trace capture
    showGameHud: true   # Render the game HUD during trace replay/capture
    showDebugHud: false   # Render the debug HUD during trace replay/capture
  testMode:
    enabled: false   # Replace the master title with the trace picker (dev-only)
    catalogDir: "src/test/resources/traces"   # Directory scanned for traces when test mode is enabled
  crossGame:
    s1DataSelectImageGenOverride: false   # Force regeneration of the S1 data-select image cache
    s2DataSelectImageGenOverride: false   # Force regeneration of the S2 data-select image cache
    s1DataSelectImageCoordLogKey: APOSTROPHE   # Log the current camera position as an S1 data-select preview override
  window:
    width: 640   # DEPRECATED manual window width; used only when display.windowAutosize=false
    height: 448   # DEPRECATED manual window height; used only when display.windowAutosize=false
    scale: 1.0   # DEPRECATED AWT debug-viewer scale factor
```
