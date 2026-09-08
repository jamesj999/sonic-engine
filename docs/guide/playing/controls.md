# Controls Reference

Gameplay accepts keyboard input and standard GLFW gamepads. Keyboard bindings
can be changed in `config.yaml` (see [Configuration](configuration.md)) using
GLFW integer codes, human-readable key names such as `"SPACE"` and `"F9"`, or a
name carrying its own modifiers such as `"CTRL+SHIFT+O"` — see
[Configuration](configuration.md) for the chord syntax and which shortcuts act on
one.

## Gameplay

| Key | Action |
|-----|--------|
| Arrow Keys | Move left/right, look up, crouch/roll |
| Space | Player 1 action A / jump |
| I / K / J / L | Player 2 up / down / left / right (`input.player2.up/down/left/right`) |
| Right Shift | Player 2 action A / jump |
| Enter | Pause / unpause |
| Backspace | Player 1 start / in-game pause (engine default; add `input.player1.start` to `config.yaml` to override) |
| Right Control | Player 2 start |
| Q | Advance one frame (while paused) |

Keyboard B and C action bindings are intentionally unbound by default. All
three Mega Drive actions act as jump buttons during platforming gameplay when
bound.

In Sonic 2, these Player 2 bindings control the configured sidekick/manual-input
path; they do not enable the ROM's native two-player/competition mode. Human-P2
competition gameplay, including its monitor-break branch, is not currently
available.

## Continue screens

After Game Over, a remaining continue opens the game's countdown screen. Press
Player 1 Start (Backspace by default, or the gamepad Start button) to use it;
S3K also accepts Player 2 Start. Jump/action buttons do not accept a continue.
In Sonic 1, Start becomes available after Sonic lands on the floor. Acceptance
plays the departure animation, spends one continue and restores three lives.
S1/S2 restart from the act start; S3K retains the checkpoint. Letting the
countdown expire returns to title without spending the continue. These menu
screens do not support held gameplay rewind.

## Gamepads

Controller input is enabled by default through GLFW's gamepad API. D-pad and
left stick feed movement. Face buttons use position-based Mega Drive action
mapping:

| Position | Mega Drive action | Xbox label | PlayStation label |
|----------|-------------------|------------|-------------------|
| West | A | X | Square |
| South | B | A | Cross |
| East | C | B | Circle |

The startup disclaimer, master title, and S3K-style data select accept
controller input. On data select screens, east/C is Back; A, B, and Start
confirm.

Additional gamepad bindings are hardcoded (not configurable) on the primary
connected pad:

| Position/Button | Xbox label | PlayStation label | Action |
|------------------|------------|--------------------|--------|
| North face button | Y | Triangle | Toggle debug movement mode (`debug.keys.debugMode`) when `debug.flags.debugView` is enabled |
| Left bumper | LB | L1 | Hold to rewind live gameplay (`rewind.liveKey`) |
| Right bumper | RB | R1 | Advance one frame while paused (`debug.keys.frameStep`) |
| Back button | View | Select | Open/close the per-game options panel on the main menu (stands in for `Tab` in that flow only) |
| Start | Start | Options | Pause / unpause (`input.pause`, same as Enter -- shows the "PAUSED" overlay and halts audio). Not wired to the separate silent ROM in-game pause (`input.player1.start` / Backspace). |

## Rewind

Live rewind is only active when `rewind.liveEnabled` is `true` in `config.yaml`.
Visual Trace Test Mode uses the same default key through `debug.traceRewind.key`.

| Key | Action |
|-----|--------|
| R | Hold to rewind live gameplay (`rewind.liveKey`) |
| R | Hold to rewind visual trace playback (`debug.traceRewind.key`) |

## Zone Navigation

These shortcuts let you move through the game quickly during development or exploration.

| Key | Action |
|-----|--------|
| Page Down | Cycle to the next zone (`debug.keys.nextZone`) |
| Page Up | Cycle to the next act within the current zone (`debug.keys.nextAct`) |
| F9 | Open the level select screen (`debug.keys.levelSelect`) |

`F9` also toggles the ring-bounds debug overlay. That overlap is current engine
behavior: the level-select shortcut is configurable, while the overlay toggle is
hardcoded in the debug overlay subsystem.

## Debug Overlays

These toggle visual debug information drawn over the game scene. They require
`debug.flags.debugView` to be `true` in config (it defaults to `false`).

| Key | Overlay |
|-----|---------|
| F1 | **Debug text** -- Player position, velocity, angle, and state information |
| F2 | **Shortcuts** -- On-screen reference for available key bindings |
| F3 | **Player panel** -- Detailed player state readout |
| F4 | **Sensor labels** -- Collision sensor ray positions and directions |
| F5 | **Object labels** -- Names and positions of active objects |
| F6 | **Camera bounds** -- Current camera boundary rectangle |
| F7 | **Player bounds** -- Player collision bounding box |
| F8 | **Object points** -- Object origin and debug points |
| F9 | **Ring bounds** -- Ring collision areas |
| F10 | **Plane switchers** -- Plane switcher trigger zones |
| F11 | **Touch response** -- Object touch/collision areas |
| F12 | **Art viewer** -- Loaded sprite art atlas |

F12 also saves a screenshot as `screenshot_yyyyMMdd_HHmmss.png` in the working
directory. PNG encoding and disk writing run in the background, with at most
two screenshots in flight. If both slots are busy, the new request is skipped
and logged. Successful writes and failures are logged; shutdown allows up to
five seconds for accepted writes to finish.

## Debug Mode

| Key | Action |
|-----|--------|
| D | Toggle free-fly debug mode (move camera freely with arrow keys) |
| C | Teleport to the last checkpoint (furthest 'right') in this act. |

The `D` mode is the engine's free-fly debug movement capability. It is not Sonic
2's native `Debug_placement_mode`: ring/item placement and the other level-wide
native placement branches are unavailable.

## Experimental Editor

These controls are only active when `debug.flags.editor` is `true` in `config.yaml`.

| Key | Action |
|-----|--------|
| Shift+Tab | Toggle between gameplay and the experimental editor overlay |
| F5 | Restart the playtest from editor mode |

## Super Sonic / Emerald Debug

| Key | Action |
|-----|--------|
| E | Instantly award all Chaos Emeralds |
| U | Toggle Super Sonic transformation (requires all emeralds) |

## Special Stage Debug

These keys are only active during a Special Stage. Unsupported tools are
unavailable for the active game's stage provider and do not silently call a
stage no-op or consume a stage rewind boundary. The global debug-overlay
bindings remain active during the stage; F1/F3/F4/F12 can toggle their general
overlays (and F12 can take a screenshot), so those states may be visible after
leaving the stage.
Sonic 2 exposes the sprite, plane, alignment, and lag diagnostics. Sonic 1
exposes only direct movement through the normal debug-mode key. Sonic 3&K
exposes stage/layout navigation; its sprite, plane, alignment, and lag tools
are unavailable.

| Key | Action |
|-----|--------|
| Tab | Enter / exit Special Stage mode |
| End | Complete the current Special Stage (award emerald) |
| Delete | Fail the current Special Stage |
| X | Advance to the next S3K special-stage layout |
| Z | Switch between S3 and S&K special-stage layout sets (S3K) |
| F12 | Toggle the Special Stage sprite viewer (S2 only) |
| F3 | Cycle Special Stage plane visibility debug modes (S2 only) |
| F4 | Toggle the Special Stage alignment test (S2 only) |
| F1 | Toggle the Special Stage lag-compensation display (S2 only) |
