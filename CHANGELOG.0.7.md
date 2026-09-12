# OpenGGF 0.7 — Development changelog

Unreleased. This line carries the work promoted from `next` after 0.6.20260911.
The [0.7 roadmap](docs/project/v0.7-roadmap.md) prioritizes complete stock-game
campaigns; feature/API publication remains subject to the 0.8 roadmap.

## Gameplay and presentation

- **Master title hub:** controller-first navigation uses left/right to change games
  from either pane and up/down to enter and move through actions, with confirmation
  on menu entry and distinct cancel feedback on back. It keeps the existing
  animated ROM logos, adds visible launch, time-attack, recordings, mods, settings,
  advanced, and quit entries, and follows intentional input with keyboard/controller prompts.
  Catalog-backed engine settings offer visible categories, onscreen value/path/key
  editing, atomic Apply/Cancel, and amber non-default values. Launch options retain
  white stock, amber changed, and red experimental status at native resolution.
  Checkerboard pages retain full-size primary lettering and use an authored native
  small font with lowercase descenders for metadata instead of fractional downscaling.
  Visible game tabs dim missing ROMs, the sky remains visible beside the action list,
  unavailable games hide profile status, and missing-ROM hints have a dark backing.
  Error pages retain accurate diagnostics until dismissed. Settings help shows
  two lines at once; launch rows use the shared left-aligned focus style. Recordings, traces,
  mods, time attack, room browsing, and lobbies have bounded pages and visible
  actions; text entry works with keyboards or controllers without triggering global
  shortcuts, and failed trace launches can be acknowledged and retried. Game logos render from
  their original textures at window resolution, preserving detail as the window grows.

- **Widescreen presentation:** ordinary UI/HUD surfaces, titles, results, endings,
  diagnostics, all three special stages, scene effects, and trace-video capture.
  Native 320x224 remains the gameplay/trace authority. 352x224 and 400x224 are
  supported presentation targets, 528x224 is a best-effort smoke tier, and
  800x224 remains exploratory.
- **S3K campaign work:** the promoted development baseline includes further MHZ
  and FBZ work, LBZ Big Arm, Super Emerald sanctuary/progression, and powered
  effects. Giant-ring sanctuary entry uses the same emerald ceremony, palette,
  camera, and background setup as direct sanctuary loading. MHZ end-boss debris
  retains the ROM trajectory when the boss faces left, and Madmole’s submerged
  body keeps its final collision position until the ROM’s deferred deletion.
  Cutscene doors retain their lowered state when streamed out and back in, and
  boss debris follows the native initialization and flicker sequence. Act 1
  camera limits use the locked-on ROM’s height rule for all characters, and
  the Act 1 boss and its thrusters stay alive during offscreen attack phases.
  Its defeat loads the explosion art and finishes the full burst sequence
  across the signpost handoff.
  Seamless act handoffs retain fixed object owners without duplicates, avoiding
  a rewind-capture crash after the MHZ signpost.
  Complete routes, finales, and continuous replay chains remain gates.

## Development features carried forward

- **Unpublished Mod API candidate:** mod loading, creator tooling, characters,
  standalone games, and custom-zone work are now available on the development
  line. The API descriptor remains the version/publication authority; this
  branch rollover does not publish or freeze it.
- **Existing feature foundations:** retain editor, racing, prepared-loading,
  audio-core, and mod regression coverage while stock campaigns mature.

## Build and release

- **Local test categories:** select related subsystem checks from changed paths, with
  common tests and structural guards retained, broad fallback for shared changes,
  and bounded diagnostics and automatic temporary-file cleanup. Full CI and release
  validation remain unchanged.

- **Release-line integration:** preserve hosted release builds, snapshot policy
  checks, current launcher artifact selection, Linux packaging, and automatic
  publication on master pushes from the 0.6 release branch.
- **Rollover-safe tooling:** SDK scaffold dependencies and the complete-audio
  launcher derive artifact versions from build metadata. Keep the unpublished
  API candidate independent from the engine branch version.

See the [development ledger](docs/changelog/v0.7-prerelease-detailed.md) for
integration evidence and the [release summary](docs/changelog/v0.7-release-summary.md)
for scope and limitations. No new feature is certified by this rollover alone.
