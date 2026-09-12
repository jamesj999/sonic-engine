# Master title hub implementation

## Accepted design

The competition-style title remains a native 320x224 UI with proportional
widescreen expansion. Preserve the existing ROM-backed animated game logos,
scaled into the left pane without stretching or rectangular placeholder art.
Left/right selects a game. Confirm enters the action pane; Back returns to
game selection. Up/down selects Start, Launch options, Time attack, Recordings,
Mods, Settings, or Tools. Existing shortcuts remain optional accelerators.
Tools exposes the trace library without requiring a YAML test-mode gate.
Standalone New Game/Continue remains available through Start.

Keyboard prompts use Enter/Esc and arrows. Controller prompts use A/B and
D-pad after intentional controller input; merely connecting a pad, holding a
control, sub-deadzone drift, or replay overrides must not switch the prompts.
Focus uses cyan framing separately from white stock/default, amber changed,
and red experimental values. Settings retains a visible category rail,
paginates dense pages, and supports draft Apply/Cancel plus controller text
entry. Persistence errors are visible. Restart requirements are explicit;
this iteration does not promise arbitrary subsystem hot reload.

## Owners and implementation

- Input worker: physical presentation modality in control/InputHandler and
  GamepadInputManager; non-ModAPI MenuInput helper and focused tests. Preserve
  gameplay and replay input semantics and published API signatures.
- Settings worker: EngineSettingsScreen, typed draft/persistence model and
  tests. Catalog-backed coverage, numeric/key/text validation, default colors,
  conservative restart notices. No MasterTitleScreen or control edits.
- Integrator: MasterTitleScreen navigation and scaled-logo presentation,
  LaunchConfigPanel prompts/status colors, tests, player documentation, full
  verification, integration and cleanup.

Workers edit disjoint files in the task worktree; only the integrator runs
Maven there. No concurrent Maven processes share a target directory.

## Verification and delivery

Base: dcb14c65485b1f9e2ea2090bed92c1c65d07a11c (develop).
Run ordinary full suite with mse=off and explicit verified REV01 S1/S2 and
locked-on S3K ROM paths on base and task tree, structural guards separately,
and focused menu/configuration/input tests. Compare failures by test identity
and message. Record visual evidence using an actual GL render when available;
command-recording tests verify native/wide bounds without a display.
Merge to the unchanged main-workspace develop branch, run integrated full
suite and guards, push develop only, then remove clean merged task worktree
and branch. Preserve dirty reference submodules and unrelated user files.

## Expanded working model

Primary controls retain the prototype's original 9x10 lettering. A separately
authored 5x7 font (6x8 cells) serves secondary descriptions and metadata; no menu
font is fractionally resampled. Page density is handled through shorter labels,
paging, and dedicated detail/confirmation screens. Shared blue/gold checkerboards
and cyan focus connect settings, launch, recordings, traces, mods, time attack,
room browsing, lobby, standalone startup, and help.

Keyboard text comes from GLFW character callbacks through a bounded frame-local
queue; editor controls ignore gameplay-letter mappings. Controller text entry uses
a visible keyboard with caret and case/symbol controls. Settings, network addresses,
and chat share the editor. The engine keeps its original ROM logo textures and draws
them directly to the physical viewport, allowing larger windows to recover original
art detail. Fixed native child pages center within wider viewports.

Default resolution remains unchanged. Subsystem hot reload beyond dynamic input
and ROM rescan remains a later refinement; the GUI explicitly requests restart.

Title action and child pages own their input ahead of global display/capture
shortcuts; playback shortcuts yield throughout the master title. An already-open
global shader picker keeps its modal input. Failed trace launches release the
picker loading latch while preserving the diagnostic for acknowledgement/retry.
