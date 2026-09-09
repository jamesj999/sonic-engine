# OpenGGF v0.6 Changelog

## v0.6.prerelease (Current development snapshot)

Analysis range: `v0.5.20260411..develop` at `77c244548` (`11653` commits, `9762` non-merge
commits, `8331` files changed, `1562327` insertions, `332466` deletions). Net code growth is
~1,229,900 lines, which includes the TraceChaser tooling extraction and the committed trace and
audio oracle fixtures.

An accuracy and tooling release. Sonic 3 & Knuckles is playable through the Angel Island to
Hydrocity route with later-zone bring-up in progress; Sonic 1 and Sonic 2 gain a long tail of
trace-driven physics, object, boss, and transition fixes. The largest single area is audio: the
SMPS sound driver, YM2612 FM, PSG, and DAC paths were reverse-engineered against the three ROMs'
drivers, and a clean-room fast FM core is now the default. Rewind became gameplay-scoped, the
three games gained ROM-backed Continue screens, `config.yaml` now stores only changed settings,
and trace recording, probing, and publication moved to the pinned `TraceChaser` submodule.

This file is the 0.6 release changelog. The polished website and GitHub release copy, with the
measured validation status and known limitations, is
[docs/changelog/v0.6-release-summary.md](docs/changelog/v0.6-release-summary.md). The full,
unedited 0.6 development ledger this file was condensed from is archived at
[docs/changelog/v0.6-development-ledger.md](docs/changelog/v0.6-development-ledger.md), and
trace evidence lives in [docs/status/trace-frontier-log.md](docs/status/trace-frontier-log.md).

### Architecture and Runtime

Zone behavior continued its move onto shared runtime-owned systems, and the build returned to plain
Maven after an experiment with managed test sessions was withdrawn.

#### Runtime Structure

- **Runtime-owned zone frameworks:** typed zone state, palette ownership, animated tile channels,
  live layout mutation, scroll composition, staged render effects, and frame-level render-mode
  overrides are now supplied by shared runtime registries. Older zone-local paths remain where
  migration has not yet paid for itself.
- **Level frame counter advances at the start of the frame** in all three games, matching the ROM.
  Roughly two dozen call sites that had compensated by reading one frame ahead were simplified, and
  a few that had never compensated are now correct.
- **Camera pipeline follows ROM ordering:** the level frame step runs the camera's player-follow
  scroll before the dynamic level-event handler and its boundary easing after, matching the ROM's
  scroll-before-events order. The one-frame-lag camera-clamp workaround is gone, and the coupled
  Sonic 1 Final Zone boss and S3K Angel Island event compensations now read the camera directly.
- **Hardware service boundary consolidated:** a single definition now owns the S3K frame's
  hardware-timing sequence. Sixteen hand-rolled copies had each dropped the Kosinski module state
  step, starving readiness and hanging on an unbounded wait instead of failing visibly.
- **Shared power-up spawning no longer names Sonic object classes:** S3K's elemental shields, the
  insta-shield, and the Sonic 1 water splash are supplied by `GameModule` factories, with the
  cross-game donor provider contributing S3K shields to Sonic 1 and Sonic 2 hosts. Five frozen
  architecture exceptions were retired.
- **Object service boundaries tightened:** the Aquatic Ruin platform and the Casino Night Point
  Pokey resolve players through the shared `ObjectPlayerQuery` service rather than raw service
  access, and the deprecated `DefaultObjectServices` fallback constructor is gone. Bootstrap
  services compose from the session and engine context directly, keeping their
  fail-without-active-runtime contract.
- **Object priority rendering reuses one palette-mask transition path,** keeping the Angel Island
  act 2 bridge layering fix out of the already-large object manager facade.
- **Discord Rich Presence leaves the gameplay frame free of IPC:** enabled presence captures an
  immutable status on the game thread, coalesces the latest payload on a bounded worker, and
  bounds shutdown waiting for a stalled local client while retaining its privacy and timer
  controls.

#### ROM Pipeline

- **Shared ROM file-channel locking:** two threads reading during a level load could race a seeked
  file position, returning a shifted header that matched no game or truncating a compressed art
  stream mid-decode. Every reader now holds the shared lock across seek and read.
- **Runtime decompressors moved out of the tools package:** Kosinski, Nemesis, Enigma, Saxman, DCM,
  and the resumable Kosinski decoder now live in the data compression package, so the ROM-loading
  pipeline no longer depends on command-line tooling code.
- **An unreadable ROM during the Angel Island intro pre-build is no longer fatal:** the terrain-swap
  tilemap pre-build is a cache warm that the swap frame can redo itself, so a failed read there is
  skipped and logged.

#### Build and Packaging

- **macOS native bundles launch correctly during prerelease builds:** the app assembler now converts
  Maven versions such as `0.6.prerelease` to the numeric `0.6.0` format required by macOS
  `Info.plist` metadata, avoiding an AppKit abort during `NSApplication` startup. The GraalVM
  package passes the Maven version to the assembler, and release smoke validation checks the same
  normalized value.
- **Direct Maven builds:** builds and tests returned to plain Maven. The managed session
  coordinator, its wrappers, and scratch-storage enforcement were removed after causing unbounded
  storage growth.
  Each checkout owns one reusable `target/` tree that also holds per-fork native extraction, and
  release jobs use static target-local paths. The local package launchers select the current Maven
  artifact from a target-local manifest and fail clearly when that manifest or its expected fat jar
  is missing instead of launching a stale version.
- **The build fails fast off JDK 21:** Maven validates its own JVM at the validate phase, with an
  escape hatch. Test forks inherit Maven's JVM rather than the one on `PATH`, so a mismatched JDK
  can no longer turn hundreds of phantom failures into apparent regressions.
- **Trace tooling moved to an external TraceChaser repository,** pinned as an optional submodule at
  an immutable release commit. Trace recording, the pinned emulator dependency, and the probe suite
  all live there. Ordinary builds, packaging, and runtime stay independent of it, and the old
  command paths are thin compatibility forwarders.
- **No AWT in production code:** the engine no longer touches AWT anywhere, keeping a native image
  buildable. macOS sets its dock icon through LWJGL's Objective-C bindings, and native application
  bundles derive their icon file from the packaged PNG.
- **Release and architecture guards tightened** across branch and release policy, trace and rewind
  invariants, ROM-only runtime asset rules, and singleton lifecycle, replacing diagnostic-only or
  tautological checks with behavioral oracles. The opt-in S3K rewind allocation measurement is
  explicitly classified while unknown skips continue to fail closed.
- **Dead code removed** in an evidence-tiered sweep that checked callers, registries, reflection,
  resources, and service loading first. Casualties included unreferenced special-stage scalars,
  boss animation tables, debug primitive rendering, a superseded PSG chip class, an unreachable
  data-select manager, dead water helpers, and the unread `debug.flags.collisionView` key.

### Cross-Game Gameplay

The physics, collision, and playable-state layers took a large pass against all three
disassemblies. Entries below apply to every game unless a game is named.

#### Physics and Playable States

- **The jump press bit comes from the raw controller-poll edge every frame,** matching the ROM's
  polling routine, rather than from the post-lock filtered held bit. The old derivation
  manufactured a spurious jump press on the frame a control lock lifted with the button held.
- **Airborne terrain probes reset to world-space sensor orientation** before selecting the velocity
  quadrant, so a stale loop or wall mode cannot rotate later floor checks. Negative ceiling probes
  use each game's native layout window height, so S3K above-top probes no longer wrap rows.
- **Ground edge-balance facing consumes the ROM-latched probe angles** at the pre-movement decision
  point instead of rescanning afterward, so a freshly landed player no longer turns toward an edge
  a frame early.
- **Rolling turnaround clamps to the ROM's carry asymmetry:** an exact-zero inertia crossing during
  a rightward turnaround now stays rolling instead of settling at zero and unrolling.
- **Shared movement preserves the entry-time `move_lock` decision** through the ground-input and
  balance tail, so a timer decremented later in the same dispatch cannot enable a balance state
  early. Springs write that grounded-input lock only on a horizontal launch, as the ROM does; the
  engine had generalized its own springing marker and invented a lock for other directions.
- **Hurt handling matches the ROM throughout:** recoil clears roll-jump before setting airborne
  state, landing on an angled ceiling zeroes velocity and inertia, the landing branch writes the
  walk animation as well as zeroed speeds, and a character hurt into a landing is still drawn that
  frame with the invulnerability blink starting the next.
- **The `tilt` and `next_tilt` angle latches clear on level load** with the rest of the player
  state table, matching the ROM's whole-object-array clear and preventing a stale balance-branch
  read on a new act's first frame.
- **The global oscillator only advances inside the level loop,** not during title-card or
  pre-level-load passes. This fixes phase-offset moving platforms after a title card, most visibly
  on a special-stage return.
- **Animation scripts carry raw ROM bytes past their own terminator,** so a leftover frame command
  from a longer table reads on into the following script the way the unbounded ROM fetch does.
  Slope rendering separately refreshes the displayed walk frame immediately when facing changes,
  removing a one-frame mismatch with the previous slope frame set.
- **Spindash charge publishes the combined animation word write** in Sonic 2 and S3K, so the
  transition clears the push status even while the spindash animation is already active.
- **Playable frame snapshots, raw animation publication, and object-controlled solid contacts moved
  into `PlayableSpriteController`,** keeping the existing public sprite API and rewind schema.

#### Solid Objects and Collision

- **The shared full-solid X-gate default follows the ROM routine family** and is inclusive, instead
  of requiring a per-object opt-in, while top-solid platform gates correctly stay exclusive. Most
  providers had already overridden the old default in the same direction, and eighteen S3K
  full-solid objects had been silently dropping a valid zero-distance side contact.
- **Standing and pushing bits expire with their slot,** matching every game's delete routine zeroing
  the whole state table slot. A reloaded object at the same layout entry no longer inherits a stale
  answer, and a player is evicted from every other object's standing bit the moment they are seated
  on a new one.
- **Pushing-bit ownership follows the ROM:** the solid tail's animation restart is gated only on the
  object's own pushing bit, and eighteen object routines across the three games now clear their own
  bit where the ROM does. The four-pixel side-air branch releases the player's push bit even when
  the object never raised it, and balance animation clears it on released input.
- **Solid `object_control` bit 7 rejects repositioning a player in every game,** matching the shared
  sign test the ROMs perform before writing a vertical position. Objects opting into
  object-controlled contacts had been losing this rejection, doubling the Angel Island collapsing
  platform's vertical step under a following sidekick.
- **Solid edge semantics separated:** ordinary nonzero side corrections snap the player's position
  to an integer pixel while exact-edge contacts keep fractional motion, and spike and squash-edge
  escapes publish grounded push even while the player moves away.
- **The frame-start touch snapshot is taken for every object before any update runs,** matching the
  ROM's use of live object-RAM pointers. The S3K path had skipped this for objects updated after
  the touch pass, giving a spawned child a stale sample.
- **Object execution follows each game's native loop:** objects executing from a borrowed child slot
  retire at their own slot's position and reserved child slots are released on the Sonic 2 and S3K
  paths, as the Sonic 1 counter-based loop already did. Spilled-ring lifetime and bottom-boundary
  checks follow each game's own branch topology.

#### Camera and Water

- **The camera's horizontal boundary applies directionally,** left on leftward scroll and right on
  rightward, instead of a symmetric per-frame clamp that could yank the camera after an end-of-act
  lock. The eased bottom boundary no longer clamps back to its target after each ease step,
  matching the ROM's un-snapped convergence.
- **Sonic 1 and Sonic 2 advance the dynamic water level before player physics,** matching the ROM's
  object-update order, so a player crossing a rising surface no longer reads a stale water base.
  S3K was already correct, and Sonic 2's water-exit boost now uses its own character routines
  rather than reusing S3K's fast upward exit gate. Water interaction is restricted to the normal
  control routine, so a dying Sonic crossing the surface no longer receives the exit boost.

#### Checkpoints, Death, and Transitions

- **A checkpoint restore no longer overwrites the camera from the saved position,** since the ROM
  recomputes it from the restored player position. A level init refills the leader's delayed
  position ring and clears its status ring, and the running act timer is banked at the star post
  and reinstated on restore in all three games instead of restarting on every special-stage return.
- **Death handling matches each ROM's single decision point:** a death that ends the game no longer
  restarts the level, the life comes off on the crossing frame rather than after an invented
  countdown, a player killed mid-roll takes the ROM's reset-on-floor position lift, and the dying
  fall ends on the camera-derived restart row instead of a screen-height approximation.
- **Boss-defeat explosions carry their ROM object identity and init-frame timing,** shared between
  Sonic 1 and Sonic 2, so an explosion consumes its own init frame before its animation decrements.
- **Special-stage entry plays the ROM's full transition:** Sonic 2 runs its freeze and fade to white
  with the entry sound and music fade, and Sonic 1 and S3K run their own entry fades, instead of
  collapsing the whole entry into one frame. Entry no longer costs an extra fade's worth of frames,
  and the stage is no longer visible through its own white-out.
- **A fade to white begins on the next vertical interrupt** rather than mid-frame, and a
  level-to-level transition fade consumes the recorded rows it owns while gameplay is frozen.
- **Continue screens run for all three games:** Game Over opens the ROM-backed countdown and
  character sequence when continues remain, Start spends one continue and restores three lives, and
  a timeout returns to the title. Sonic 1 and Sonic 2 restart from the act start while S3K keeps
  its checkpoint.
- **Game Over and Time Over run in all three games,** loading the ROM's object pair from ROM art and
  mappings, playing the game-over music, and sliding the words in at the shipped rate with the
  unfixed conjoin flicker. Time Over restarts the level and Game Over leaves it, and zero-life
  gameplay can no longer be paused.
- **Bonus-stage entry and return moved into a dedicated transition coordinator** that reapplies the
  captured object respawn table inside the fresh object manager's reset and preserves star-post
  activation, interior rings, event routines, player and camera and water state, HUD timing,
  shields, and lives.
- **Data-select launches play each host game's own retail entry cue and fade timing,** retaining the
  destination reveal's fully-revealed idle interrupt before the fade completes.

#### Sidekicks and Cross-Game Donation

- **Cross-game character donation follows the donor game's capabilities,** not the presentation
  game. Flight exhaustion, swim animation ownership, and carry restricted to the main player are
  ROM-accurate, and Sonic 1 donor sprites keep their native blue palette in S3K. Animation cadence
  matches the ROM's per-character profile ordering across Sonic 2 and S3K.
- **Sidekick hurt recovery restores default collision radii after a status-only roll clear** on the
  S3K path, preventing a CPU sidekick from probing with a stale rolling box. Sonic 2's distinct
  behavior is preserved, and delayed CPU jump presses now survive the auto-jump carry path instead
  of being erased by a stale on-object status.
- **The Sonic 2 sidekick fly-in no longer lands early or stalls under CPU control:** one
  physics-driven question had been reused to gate both multi-sidekick leader crossing and fly-in
  gravity during engine-owned spawn windows. A dedicated predicate and an explicit spawn-wait
  invariant separate them.
- **S3K fixed dust sidecars use their ROM fixed slot identity** instead of consuming dynamic object
  slots.

### Rendering and Graphics

- **Persistent Plane B nametable:** zone scroll handlers can opt into a Genesis-style persistent
  background nametable that updates only the entering rows and columns on a camera crossing,
  instead of rebuilding a stateless window every frame. It rewinds atomically, republishes one
  coherent upload before the next draw, and scrolls its ring texture incrementally on both axes.
  Existing handlers stay on the stateless builder.
- **Runtime object art becomes ready in the triggering frame** instead of a frame later. Each game
  provider now preflights its fixed virtual-pattern capacity against the pattern-atlas reservation
  before allocating, rejecting negative, oversized, or inconsistent counts.
- **S3K models the ROM's single shared four-entry direct Kosinski queue,** where ordinary and
  module-created streams contend together, rather than decoding module children separately. Both
  queues are serviced at the ROM's real loop-tail phase, after the object pass and before the next
  frame's pre-main-loop point, and fixed module delays gave way to a rewind-safe runtime queue and
  a resumable decoder.
- **Pattern loading cue pacing follows the consuming loop iteration:** an iteration held across a
  lagging vertical blank runs its loop-tail preparation on the closure that actually consumed that
  blank, and a level's staged player dynamic-art transfer sits on the ROM's counted pre-main-loop
  tail instead of at title-card release.
- **Dynamic-art edges publish on the following boundary after a lag frame,** matching the ROM's
  transfer-queue gating, and the shared player dynamic-art ledger no longer loses a logical row on
  a lag frame. A lag frame still publishes a row, it just skips the main-loop increment.
- **SEGA boot screens use ROM-backed logos and PCM timing** for all three title flows.
- **Sonic 1 title screen fades through the palette:** the "SONIC TEAM PRESENTS" text and the
  assembled title screen now fade with the ROM's `PaletteFadeIn` / `PaletteFadeOut` channel order
  (blue, green, then red on the way in) over 22 frames, including the backdrop colour, instead of a
  16-frame black overlay. Objects, background scroll, and the water palette cycle stay frozen during
  the fade as they do in `GM_Title`, so Sonic rises 29 frames after the fade completes.
- **Sonic 1 level fade-in is a palette fade:** the title card reveals the level with the ROM's
  `PalFadeIn_Alt` on palette lines 1-3 (blue, green, then red over 22 frames, underwater lines and
  the line 2 backdrop colour included) while line 0 keeps Sonic, the HUD, and the title card at
  full colour, replacing the blended black overlay. Palette fades now apply where CRAM uploads
  happen, so palette cycles and other writes made during a fade stay faded. The card's black
  plane still covers the release frame, whose foreground tilemap is rebuilt mid-frame. A complete
  palette teardown also clears the active fade and its cached palette owners, so an interrupted
  title-card session cannot tint the next session.

### Gameplay-Scoped Rewind

Rewind became a gameplay-wide capability this cycle rather than a per-object opt-in. Nearly every
object family now restores through shared machinery, and the remaining coverage gaps closed.

- **Generic recreate rollout:** essentially every remaining object family across the three games,
  covering bosses, badniks, mechanisms, debris and particles, cutscene controllers, and HUD and
  utility objects, moved from bespoke or missing restore paths onto shared spawn-based or
  graph-based generic recreate. Parent, child, and player references relink through the rewind
  identity table and constructor-derived scalars restore compactly. Sonic 1's sixteen switch bytes
  are captured too, and its SBZ3 door singleton rebinds from restored live slots, preserving the
  first-loaded-slot rule across absent, reconstructed, and reused objects.
- **Bespoke dynamic child codecs were deleted as they migrated:** lost rings, shields, boss and
  badnik children, seesaw balls, checkpoint children, Sonic 1 effects, S3K cutscene and miniboss
  children across six zones, signposts, entry flashes, and shared helper dynamics.
- **Special and bonus stage coverage:** Sonic 1 special stages use the keyframe and
  resimulation controller, Sonic 2 special stages capture the manager, objects, intro, checkpoint,
  player, and track animator behind a provider-owned adapter, and S3K Blue Spheres gained a generic
  adapter. The Gumball and Pachinko bonus stages rewind as well, and the slot machine does not.
- **Rewind boundaries are explicit:** level loads, seamless transitions, and gameplay mode changes
  now report a boundary so a live session cannot cross one, and live rewind stops safely at
  in-frame act reloads by re-rooting history at the completed destination frame.
- **Held rewind no longer softlocks on a callback-bearing fade:** landing on a frame with a pending
  non-restorable fade completion, such as special-stage, bonus-stage, ending, act-complete, or
  respawn, clamps rewind at the last good frame instead of orphaning the callback. The
  gameplay-freeze predicate was kept separate from the new rewind-only predicate.
- **Dynamically spawned boss children:** a captured child could be
  restored before its parent existed and silently discard its state, fixed by a parked-entry retry
  loop generalized to a fixed-point loop, and by suppressing construction side effects during
  probe-only construction.
- **Boss children stay correctly registered:** children beyond a boss's construction-time set are
  re-added on restore, stale ordinal counters recompute from the settled live child list, and
  same-class siblings match captured state by a stable per-parent ordinal instead of class-name
  order, ending identity swaps and leaked orphans.
- **Reference-closure validation runs on every real level frame** in Sonic 2 and S3K replay,
  checking captured object references against the strict identity table and exposing lifecycle
  defects at their first frame. It drove fixes for shared unload anchors, spawn-derived intro
  state, unregistered parent references, cutscene button references, and shell parent detachment.
- **Compact capture preserves object-reference arrays** and restores the shared ROM random number
  generator after graph reconstruction but before post-restore callbacks. Parent-linked probe
  constructors skip safely with no live parent, and recreate contexts reject a null object manager.
- **Auto-scroll parallax accumulators no longer drift during held rewind:** frame-driven
  accumulators such as clouds, ripple and heat-haze phases, and the Death Egg star field derive
  from the restored frame counter, so re-deriving any past frame reproduces its value exactly.
  Camera-driven and bonus-stage accumulators gained explicit capture and restore instead.
- **Timed power-ups and their music:** speed shoes capture their remaining
  duration and re-register a behavioral timer, the underwater breath timer, countdown-bubble
  cadence, and drowning cue survive a seek, and a sound-effect-only cleanup policy stops a rewind
  release from cutting invincibility, extra-life, or Super music short.
- **Sonic 2's post-camera object-unload latch is captured,** fixing permuted dynamic slot allocation
  and random-seed divergence after a seek.
- **Live handles are treated as transient** where an object holds a reference that may already be
  gone, covering the Gumball machine's children, the Ice Cap snowboard intro, and the S3K
  special-stage entry flash, each of which had crashed capture or restore.
- **Sonic 1 checkpoint fixes:** rings resolve to their canonical spawn instead of spamming
  identity-miss warnings, the Star Light act 3 boss self-heals its seesaw cache, and the Labyrinth
  conveyor spawner and its platforms survive a seek. Marble Zone display-child links joined rewind
  coverage under the existing display-child policy.
- **Live rewind uses finer gameplay checkpoints and circular input history,** bounding cold replay
  at the cost of retaining more snapshots.
- **A VHS picture-search presentation renders while rewind is held:** tear and noise bands,
  scanline jitter, chroma fringing, tape dropouts, a head-switch strip, and vertical wobble, with a
  short attack and release envelope. Tear bands can be disabled independently, and speed-modifier
  keys rewind at half or double speed with the effect and reverse audio following. Per-game
  profiles prewarm the effect pass so a profile that enables rewind after graphics initialization
  no longer ends up with rewind and no shader.

### Configuration, Menus, and Input

- **Override-only `config.yaml`:** defaults live in code and the example file, and an older
  file with every default written in converts once to format 2, dropping values still at default
  while keeping real changes. A failed YAML replacement leaves the legacy JSON source available
  for the next startup instead of claiming a completed migration. Developers can set
  `config.preserveExplicitDefaults: true` to retain a full explicitly listed test configuration.
- **`gameplay.loadTimeSimulation: FAST`:** now a real mode and the new default. A hand-tuned
  manifest carries measured ROM hardware-load costs, and games without a FAST manifest fall back
  to `NONE` with a warning. The old default is dropped on conversion, so existing installs pick the
  new one up.
- **Controller support arrived:** startup, title, and data-select screens, gameplay, and special
  stages consume a logical two-player input layer. Gamepads map their D-pad, stick, and face
  buttons to the Mega Drive layout, keyboard input feeds the same layer, and rewind and recording
  playback inject logical snapshots instead of fake key codes.
- **User recordings added:** a configurable record key with hold-to-record and stop controls, a
  master-title recordings menu for browsing and playback with version warnings, a session launcher
  that restarts a level and arms deterministic recording, and a desync verifier that reports the
  first playback mismatch without mutating gameplay. Recordings round-trip as movie files with
  sidecar manifest and desync metadata.
- **Capture encoding is configurable:** `capture.encoderPreset` exposes the speed preset and
  defaults to `fast`, `capture.encoderThreads` exposes the thread count, and lossless FFV1 is now
  sliced so threads apply to it too. An exhausted encoder queue logs a rate-limited warning rather
  than silently stalling.
- **Live and trace capture can target DaVinci Resolve on Linux** through DNxHR SQ video and
  lossless 24-bit PCM audio in a QuickTime container.
- **Default ROM filenames simplified** to `s1.gen`, `s2.gen`, and `s3k.gen` across configuration,
  runtime defaults, and documentation, replacing archive-style names. Documentation identifies the
  expected revisions by checksum.
- **Chemical Plant tubes honor the free-fly debug boundary** instead of capturing a debug player.
  Native debug ring and item placement and two-player human monitor behavior remain unavailable
  pending their engine-wide capability owners, and that gap is documented.
- **Prerelease build titles resolve correctly when run from an IDE,** falling back to the commit
  and dirty state when build placeholders are unresolved.
- **A legal-disclaimer startup flow, ROM-derived master-title previews, display shader support, and
  pause and HUD presentation fixes** round out the user-facing surface. The Sonic 3 & Knuckles
  master-title preview includes its ROM-derived TM and copyright graphics.

### Performance

- **Angel Island act 1 intro hitches removed:** the terrain swap uses tilemaps pre-built during
  level load instead of rebuilding both full tilemaps, cutting the swap frame from about 25
  milliseconds to under one, and the fire-overlay art refreshes only the pattern atlas because it
  is pattern-only art.
- **Angel Island act 2 hand-off:** it runs on a background preparer thread. The act 2 level decode,
  object art sheets, and both tilemaps build across the fire event's rise and wait, and the reload
  always joins the build so state stays identical to a synchronous load. Kosinski archive
  inspections are memoized per ROM.
- **Save writes and GPU uploads moved off the gameplay frame:** progression saves encode on the
  issuing frame but write on a dedicated writer thread, with reads, deletes, synchronous writes,
  and shutdown sharing its submission boundary so independent save managers cannot reorder a slot;
  the act hand-off pre-decodes collision tables off the frame and keeps unchanged sprite sheets
  across the reload instead of re-uploading them.
- **Palette-cycling zones no longer accumulate unbounded palette writes in the headless frame
  path.** The per-frame drain was owned only by the windowed game loop, which headless replay and
  benchmark paths bypass, making the cost grow quadratically. Moving the drain into the shared
  frame step removed roughly a sixfold headless slowdown.
- **Solid-path allocation cut roughly a quarter engine-wide:** per-player collision maps are sized
  for a small team rather than the default table, entry iteration avoids per-entry churn, and solid
  parameter and routine-profile records are interned by exact-value keys instead of rebuilt every
  frame per solid object per player, with no behavior change.
- **Steady-state rendering allocation largely eliminated** through trace-visibility flyweights,
  reused sprite-priority buckets, incremental tilemap column uploads, batched pattern-atlas
  overflow with proper resource teardown, pooled overlay and deferred render commands, frame-owned
  render state, cached special-stage and Blue Spheres static data, deduplicated palette conversion
  and underwater uploads, and reused visibility buffers.
- **Audio allocation reduced:** stereo resampling is fused into one traversal per sample,
  deterministic timeline commands are consumed in place from a sorted pending suffix without
  per-frame allocation, and rewind history rings allocate lazily only while a backend owns them,
  removing an unused multi-megabyte default allocation.
- **Audio playback garbage cut to about an eighth per frame on either FM core** (S3K AIZ,
  JFR-weighted: audio-stack 21.5 to 2.8 KB per frame, whole update 40.8 to 23.8 KB), with the
  trajectory digest, the Nuked parity vectors and every fast FM oracle sample unchanged. The
  session queues committed chip-write diagnostics as packed primitives instead of a record and a
  lambda per write (the DAC stream reported one per sample byte), both FM facades resolve their
  DAC sample once per bank and id instead of boxing the id per byte, the fast DSP builds its
  state-array list once per instance, and the per-frame live mutation capture reuses pooled
  per-sequencer backups that session commit and rollback return to the pool instead of building
  an immutable snapshot of every track each frame.
- **Repeated sound effects and music starts consult a generation-aware asset catalog** before
  invoking a loader, avoiding a reload and recopy of asset-sized data per trigger, and prepared
  admission bypasses the whole-driver rollback capture on ordinary paths. Driver snapshots also
  deduplicate external fallback descriptors by identity, cutting capture time sharply.
- **Rewind capture got cheaper across the board:** checkpoints no longer re-clone unchanged
  hardware-timing job payloads, empty render-registry captures return shared immutable instances,
  composite layouts are versioned and shared per registry configuration, animated-tile phases use
  primitive indexed layouts, warm backward-cache lookups probe the expanded segment strip directly,
  and multi-sidekick keyframes omit unused follow-history arrays.
- **Live viewport recording no longer allocates two full frames plus a read buffer per captured
  frame,** roughly halving steady-state capture garbage. The encoder queue is sized from a memory
  budget rather than a fixed frame count, absorbing longer stalls before backpressure reaches the
  game thread.
- **Assorted hot paths tightened:** decoders share an immutable reader per open ROM, Casino Night
  palette cycles reuse ROM-derived patches, the performance overlay uses primitive counters,
  screenshots encode on a bounded worker that skips when busy, gumball art uploads are batched,
  Hydrocity deformation and special-stage render-order scratch are reused, and display shaders
  avoid redundant state queries.

### Level Editor and Tools

The level editor remains experimental and saw only plumbing work this cycle. Tooling effort went
into measurement instead.

- **Level-editor plumbing continued** alongside the runtime layout mutation pipeline, without
  reaching a shipping editor.
- **A headless benchmark tool replays a recorded trace with no pacing,** reporting percentile
  per-subsystem timings so comparisons are not hidden by the engine finishing inside its frame
  budget, and hashing each run's trajectory so a mismatch voids the comparison. A paced mode with a
  frame log measures real-renderer costs, and a companion script runs a pinned JVM matrix.

### Sonic 3 & Knuckles

Sonic 3 & Knuckles received the bulk of this release's gameplay work. Angel Island through Hydrocity
is the primary playable slice and is now stable end to end, with Marble Garden, Carnival Night,
Icecap, Mushroom Hill, and Launch Base brought up behind it. Most entries below correct dispatch
order, collision-publication phase, and object-slot ownership against the disassembly rather than
against measured recordings.

#### Angel Island Zone

- **AIZ1 intro cutscene:** the plane, propeller, booster, and splashes use the ROM's sprite-priority
  buckets so the Tornado draws in front of its own spray, and the intro waves and plane children
  reserve their native child object slots. Plane intro creation, sidekick inertia through the
  object-order grace window, and a freshly spawned Tails' centre position follow native ownership,
  removing several non-ROM bootstrap ticks.
- **AIZ ride vines:** grabbing a vine clears the whole `spin_dash_flag` byte as the ROM's byte write
  does, releasing a speed lock that left a post-tube Tails unable to decelerate. A rider the render
  pass cannot draw is released off screen, a stale jump press on release no longer triggers an
  insta-shield or glide, and a player who grabbed while rolling now walks once carried onto the
  floor. The vine phase word `AIZ_vine_angle` also survives a level load and an act transition
  instead of resetting to zero, because the ROM's oscillating-table clear stops one word short of
  it.
- **AIZ collapsing platform:** the collapse trigger is a fresh per-dispatch read of the standing
  bits rather than a latch, and the countdown no longer starts a dispatch early. One shared solid
  pass runs before either player's release, so overlapping platforms starve each other's trigger,
  and platforms respawn on return because the ROM deletes rather than remembers them.
- **AIZ pushable rocks:** a rock models a native Player 2's saved pre-helper push status
  independently of its own prior-contact bit, so a sustained sidekick push moves it while first-
  contact pushes stay deferred. It leaves the on-screen solid gate on the ROM's frame using its own
  subtype-indexed size, and a smashed rock's destroy mark survives a giant ring round trip.
- **AIZ fire curtain:** the curtain stays continuous across the exact-load seam, exits with its ROM-
  shaped tail once the ROM latches the Act 2 source strip, covers every configured aspect ratio from
  a ROM-backed descriptor cache, and no longer advances on a lag frame. Its refresh phase derives
  its pass count from the ROM's own seed and drain rate rather than an invented constant.
- **AIZ fake-fire camera release:** the Act 1 to Act 2 fake-fire transition releases the post-reload
  camera lock on the background redraw completion signal, not on the fire-curtain ramp crossing a
  fixed threshold. Returning from a special stage reloads the main-level art profile rather than the
  surfing intro art.
- **AIZ miniboss:** the Knuckles napalm attack is ported to native rise, drop, and floor timing with
  ROM-backed art and staggered explosion children, and rewind relinks shared body, arm, and barrel
  children to both the Act 1 cutscene boss and the Act 2 fight boss. The escape countdown survives
  the Act 1 reload and starts the Act 1 track only when it expires, modelling `Restore_LevelMusic`'s
  playlist lookup instead of the loaded act.
- **AIZ2 battleship:** the ship's creation-frame dispatch and its bombs' were wrong in opposite
  directions, and correcting both together makes every recorded bomb drop match. Explosion fragments
  become harmful on the ROM's frame rather than four frames later, and the water-plane disable,
  scroll-lock camera history, and screen event dispatch ordering are now native.
- **AIZ2 end boss:** the splash children allocate in native slot order and follow the ROM emerge,
  drop, and flip scripts. Robotnik can no longer regain touch collision while hidden behind the
  waterfall, since the submerge transition clears the hit-flash timer and invulnerable state
  together, and the two post-defeat waits use the ROM's own literals rather than invented values
  that summed to the same total.
- **AIZ2 camera and shake:** the gradual boundary workers no longer run a frame ahead, since an
  invented accumulator pre-charge, a hard first-dispatch step, and a skip while airborne were
  removed. The rising-water shake owns its native 180-frame timer object instead of a global
  countdown.
- **AIZ2 boss-exit bridges:** the bridges survive and layer correctly through score counting until
  Knuckles triggers the collapse, staying in front of the waterfall but masked by foreground
  terrain, and boss damage no longer erases waterfall-depth art priority on a hurt landing.
- **AIZ badniks and terrain:** Monkey Dude arm-root cadence, signpost sparkle timing, Caterkiller Jr
  child ordering, Bloominator child-slot timing, and Rhinobot's off-screen activation follow the
  ROM. The fire-transition floor's carry phase, falling-log and collapsing-log bridge landings, Act
  2 title-card exit, and terrain queue timing match native dispatch order, and the AIZ-only exact-
  contact landing exception was removed. Vertically wrapping levels also render placed and lost
  rings at their wrapped vertical position when the camera crosses the boundary.
- **AIZ act handoffs:** the seamless Act 1 to Act 2 route keeps the ROM's path-switch sprite-
  priority bit through both boss handoffs and preserves the fixed air-countdown slots. On the route
  into Hydrocity, the destination's terrain art queues inside the title-card window rather than
  after the segment seam, and an omitted title card still queues the four archives its owner queues
  on creation.

#### Hydrocity Zone

- **HCZ water hazards:** the vertical geyser no longer draws before it erupts or triggers on
  approach, matching the ROM's routine gating and asymmetric unsigned proximity window, with render
  priority, gravity re-arming, and jump-height-latch clearing corrected.
- **HCZ horizontal geyser:** it queues its art on spawn, reloads enemy art on cleanup after the
  eruption sheet overwrites it, and survives scrolling off screen, since it has no off-screen unload
  in the ROM. Losing that reload had put every later decompression job permanently out of ordinal
  sync.
- **HCZ solid-object contacts:** spinning columns, hand launchers, snake blocks, doors, tension
  bridges, hurt blocks, and Jawz use the ROM's exact contact windows and post-movement carry
  references, fixing false pushes, early landings, and stale platform carry. The breakable bar
  releases on a fresh button press, and conveyors, bars, and fans read raw controller holds rather
  than the sidekick's generated directions.
- **HCZ badniks:** Jawz reproduces its harmful vulnerable-contact explosion instead of vanishing on
  a non-attacking touch, Mega Choppers wait off screen before running any routine and deal their own
  defeat rebound, and Bubbler makers preserve their forced on-screen bit so the first burst appears.
  Turbo Spiker children close parent references during unload while launched shells keep independent
  lifetime, and a launched spike no longer hits a frame early.
- **HCZ miniboss:** the vortex models native object-control movement ownership rather than treating
  capture as signed control, and its post-defeat wait is seeded from the ROM's own literal. The Act
  1 carrier sequence retains and releases both players' control, the water lock, and the gradual
  level-size children on the ROM's schedule across the Act 2 reload.
- **HCZ end boss:** the turbine, water-column, and blade sequence matches native cadence, including
  turbine spin-up before column creation, slowdown collision surviving into the animation callback,
  the camera-release ramp, and body touch after movement. The water column's surface, bubble, and
  spray pieces are independent object-table occupants.
- **HCZ2 Plane B:** the wall-chase render stays a 512-pixel VDP window driven from the wall
  background camera, wraps correctly at the strip boundary, and rebuilds a fresh nametable once the
  ROM clears the wall, removing blocky corruption and stale scroll bleed. Layer-one vertical probes
  reuse the full floor and ceiling state machine, and the moving background wall participates in
  native background collision and grounded clamp behavior. Water skimming, the
  cutscene Knuckles, and the slide terrain run at their native post-player boundary. Still sprites
  across the zone use the ROM's coarse on-screen despawn window instead of an exact screen check,
  restoring the waterfall curtains and the slide and tube crossing pieces.
- **HCZ act transitions:** the Act 1 reload waits for the queued secondary art workload instead of
  results completion, applying the native player and camera offset and transition water height. The
  descent spawns the retained Bubbler particles for their native window, and the Act 2 geyser
  handoff starts Marble Garden's music after the destination load instead of carrying a silencing
  fade forward.
- **HCZ1 spawn position:** complete-run replay takes the leader's spawn position from the ROM's own
  spawn values rather than the trace's first row, which had applied an extra frame of air control
  and left a permanent offset for the whole run.
- **HCZ rewind:** results element timelines, the egg-capsule button parent, blade-impact boss links,
  Turbo Spiker shell launch ownership, and the miniboss vortex bubble's fractional pull survive
  capture and restore with correct reference closure.

#### Marble Garden Zone

- **MGZ pulleys:** the grab, release, and capture path writes only the ROM's literal fields and no
  longer clears standing or air status, detaches the current support, or shifts position words,
  fixing a five-pixel post-release push.
- **MGZ riders and solids:** the dash trigger re-arms only when its own timer has expired, so a
  sustained spindash still flings a landing sidekick. Monitors no longer unseat a rider who changes
  state on top of them, since that exemption is acquire-time only in the ROM. Platforms and spikes
  report their own ROM size to the on-screen render test, and collapsing bridges clear their
  standing bit on a terrain handoff instead of force-releasing a grounded rider.
- **MGZ drilling miniboss:** the ceiling probe uses the S3K ceiling-distance contract, fixing a one-
  pixel-low rumble ladder, and the rumble step reads the correct byte of `V_int_run_count`, which
  had inverted the vertical step. Thruster timing, music cadence, composite render order, and rumble
  and collapse sound ordering follow native dispatch, and its camera-bound worker and post-ceiling
  escape run from their own object slots. Screen shake is owned by the ROM's setup routine and
  sampled once per frame at the tail of the zone's background event, rather than being indexed
  independently by the Tunnelbot and the trigger platform.
- **MGZ rings:** spilled and attracted rings read live player and ring coordinates from the correct
  object-pass phase rather than a stale cache, and an attracted ring tests its give-ring overlap
  against its pre-move position, matching the ROM's next-frame collision-response processing.
- **MGZ2 end boss:** the end boss and the drilling Robotnik follow native object-table and
  collision-list ordering throughout, including air-attack positioning, hit palettes, the terrain-
  phase kill gate, and the eight-hit Knuckles ground fight. The floor-impact handoff no longer emits
  a spurious death sound, and the drill ship art registers from its correct ROM art bank.
- **MGZ2 boss background:** the accelerating boss scroll affects only the horizontal scroll
  calculation and the full-width terrain strip is selected only during the background-rise sequence,
  so the background no longer snaps through unrelated tilemap windows or flash wrapped wall tiles on
  the final frame.
- **MGZ2 rescue and carry:** the post-boss rescue follows the ROM routine by routine, covering
  transition arming carry, the sidekick's auto-fly threshold, carry pickup and release proximity and
  cooldowns, and suppression of the carried player's own animation and movement, and it round-trips
  through save and rewind. Jumping out of the carry discards stale physical object support.
- **MGZ2 pillars:** smashing pillars preserve the grounded lower-half squash-edge semantics and use
  the ROM's inclusive right edge, so a player shoved flush against one keeps a live side contact and
  keeps their push status set.
- **MGZ act transition:** camera-bound children execute their creation-entry phase before the shared
  gradual-worker dispatch, Act 2's workers and title-card reset start from the correct results
  checkpoints, and the carried results owner reloads Act 2 once its queued secondary art workload
  drains rather than waiting on the end-of-level flag.

#### Carnival Night Zone

- **CNZ route objects:** cylinder edge inclusivity and rider handling, orbiting bumper phase, wire
  cage control bits, barber-pole wrap and twist semantics, Batbot child allocation, balloon pop
  scripts, Clamer projectile timing, door extents, Sparkle off-screen gating, Cork Floor balance
  width, spring launch bounds, and Hover Fan art caching were all corrected against the ROM.
- **CNZ rival Knuckles cutscenes:** both cutscenes use their ROM animation scripts, camera locks,
  and control flow.
- **CNZ miniboss:** it publishes its live post-move coordinate, preserves its native pointer-only
  entry dispatch and raw terminator handling for hit contacts, and applies the arena-boundary write
  from its own object slot rather than a later zone-event adapter.
- **CNZ end boss and cannon:** the magnet body, ship, head, arms, and field run the full native
  phase graph of swing, tracking, magnet drop, charge, attraction, descent, defeat, and the capsule
  handoff, with correct rewind reconstruction. The post-boss cannon's camera-target write is carried
  through the dynamic level events tail and republishes sidekick bounds after boundary easing.
- **CNZ seamless reload:** the Act 1 reload preserves the carried results state as the sole Act 2
  title publisher and holds enemy art until title completion.

#### Icecap Zone

- **ICZ snowboard intro:** the wall crash locks controller 1 for the duration of the quake, flings
  the discarded snowboard as its own object, and shakes screen and level scroll in sync. The
  breakable wall and its crashing platform spawn their correct shatter debris, and the scripted-
  slope hand-off no longer fires while the player is airborne.
- **ICZ platforms:** path-follow platforms carry fall and landing state, keep standing-latch
  identity, and report their ROM balance width of 32 pixels so a still rider no longer flips facing.
  Swinging platforms use folded-child solid edges, the ROM chain-release fraction, and per-child
  stale-standing return.
- **ICZ terrain and hazards:** Cork Floors and tension bridges use native height tables, edge
  bounds, and rope-bend phase, crushing columns use native edges and return rounding, and the
  segment column balances riders on its own 32-pixel width rather than the shared default.
- **ICZ badniks and effects:** Penguinator off-screen gating and its floor-angle and slide-recovery
  script, Freezer placeholder lifecycle, snow emitter draw order, and ice cube rider release and
  roll-check timing all match the ROM.
- **ICZ end boss and ice attacks:** the folded multi-piece solid graph handles fresh-contact
  position, structural and frost child slots, capture-frame status, and snow emitter ownership, and
  reserves slots so its six simultaneously live children fit. Boss frost puffs and placed freezer
  clouds freeze every configured sidekick without consuming native object slots.
- **ICZ roll-stop:** the roll-stop path no longer clears the push status a routine early, and the
  swinging platform's compensating one-frame-late status read is removed. The two had been masking
  each other.

#### Launch Base Zone

- **LBZ end boss:** the boss graph covers camera pan, linked platforms, the launcher tower, the
  runner, arm collisions, shuttle-pass side selection, explosion handoff, and post-move collision
  publication at the ROM's slot order and publication timing, and its defeat countdown no longer
  starts a frame early.
- **LBZ miniboss and first fight:** arm and child collision coordinates, tracking deadband, and
  placement timing follow the ROM, and the first Robotnik fight's hit flash, collision byte restore,
  defeat award, platform chain, spike balls, debris, and camera extension match the disassembly.
- **LBZ rolling drums:** ride-latch handoff, animation ownership, and per-player air-latch repair
  are corrected, tumble-mapping ownership passes to the native roll script, and pose derives from
  the previous pass's flip angle. Entering a drum while riding one clears the previous drum's
  standing flag first, so a transfer no longer forces the player airborne.
- **LBZ elevators:** cup elevators follow native solid checkpoint order, edge-balance width,
  control-lock byte, fling facing, off-screen respawn lifecycle, and animation retention. Tube
  elevators separate their control byte, correct bob anchor and phase, and reproduce their full-
  solid offset geometry and action-before-checkpoint ordering.
- **LBZ grapples, badniks, and hazards:** ride grapples correct capture position, chain seeding, and
  off-screen release order, and the lowering grapple releases only on a fresh jump press. Ribots,
  Snale Blasters, Corkeys, Flybot 767, spin launchers, spikes, and player launchers follow native
  slot order, collision publication, and render gating.
- **LBZ1 ground launch:** the launch controller models the full object-control animation-ownership
  handoff including a seamless follower pose carry, and the Knuckles bomb and cup sequence matches
  ROM shake and lock timing through the building collapse.
- **LBZ2 launch pad and turret:** the launch-pad detach waits for the ROM scroll and copy to finish
  before the final fall, and the final boss runs the ROM arc step before reacting to a detached
  turret segment.
- **LBZ2 Death Egg launch and finale:** the launch path publishes ROM-equivalent background and
  runtime signals, loads the Death Egg replacement art and terrain, applies the launch deformation
  and pad-collapse mutation, and keeps the Knuckles cameo art in its ROM tile slot. The finale
  drives the look-up, hang-ride, cameo, explosion, smoke, and foreground-scroll phases from the ROM
  sequence, and the pipe-plug exhaust uses ROM mapping pieces and art.
- **LBZ tunnels:** automatic tunnels apply final path velocity on the correct exit frame and
  preserve fractional position words instead of overwriting them. The tube subtype puffs exit smoke
  on launch, and the exhaust cadence gate reads the frame counter's low byte correctly.

#### Mushroom Hill, Flying Battery, and later zones

- **MHZ objects and slots:** fixed object-table occupants, plane switchers, pollen, cutscene
  children, the pulley, the curled vine, and Madmole follow their ROM slot, lifetime, and geometry.
- **Madmole:** seven timing fixes correct the body's rise step, cooldown arming, arm collision-
  response ordering, gravity, carry-routine handoff, subpixel-preserving player pin, and arc-grab
  target selection. On defeat the badnik leaves a solid cap stump and permanently blocks re-
  emergence, since the cap collision survives the body's death, and its side-drill arm models the
  per-hit player back-reference so its despawn detaches the last player it hit.
- **MHZ vines and swing bars:** the curled vine contours riders to its curved surface with sloped-
  solid landing detection, distinguishing the establishing landing frame from a continued ride.
  Swing bars switch every position write to word-only so a rider keeps their subpixel fraction,
  reject a grab while the player is hurt or dead, play the vertical bar's missing grab sound, and
  force the camera to their own anchor while a player hangs.
- **MHZ1 cutscene door and button:** the door moves on its own trigger frame instead of a frame
  late, uses the inclusive right-edge side contact, and respawns pre-lowered when its button's latch
  is set. The button's top-landing width uses its own wider object-data half-width and its solid box
  sits on the ROM position, and the cutscene stops the player's vertical speed as well as horizontal
  and ground speed. Loading Act 1 as Sonic or Tails also pins the minimum camera X to the ROM's
  level-load override, which the camera had previously started well short of.
- **MHZ giant ring emerald gate:** the giant ring no longer awards 50 rings in an S&K-half level
  once the Chaos Emeralds are complete but the Super Emeralds are not. It starts the Super Emerald
  capture sequence, and the flash object restarts into the arena as the ROM does.
- **MHZ bosses:** the end boss's defeat fragments scatter with the ROM's velocity-table rows and its
  hit-flash palette no longer sticks after the final invulnerability frame. The miniboss animation
  interpreter implements the re-point and re-emit commands so its launch and camera-rise scripts
  settle on the correct frame, and the Act 2 parallax handler uses the boss-area vertical deform for
  every background routine value the ROM uses it for.
- **MHZ small objects:** the mushroom parachute's steering-angle sign correction was swapped,
  leaving it unsteerable, and it now carries the ROM's two horizontal wall sensors so it no longer
  clips through walls. The catapult plays its bounce sound once per launch rather than once per
  rider, pulley lift handles spawn with zero extension and extend only when a rider holds down, the
  sticky vine applies the ROM's full subpixel pull vector, and the Dragonfly's tail ripples one
  segment per frame on its return.
- **New objects for later zones:** the Launch Base and Death Egg floor launcher and the Lava Reef
  collapsing stone walkway are implemented from their ROM routines including timers, debris and
  child spawns, and rider-carry rules. Both were previously placeholders players fell through.
- **LRZ and HPZ entry:** the Lava Reef falling introduction applies to non-Knuckles players
  including Player 2, and checkpoint, big-ring, and bonus returns take the saved-state early return
  before any zone intro branch. The Hidden Palace Super Emerald arena restart drops its title-card
  hold and places the player from the start locations rather than the saved star-post block.
- **Zone table shape:** the zone list has the ROM's real 24-zone, two-act shape, so the Angel Island
  intro and ending, the Lava Reef boss and Hidden Palace, and the Death Egg boss and Super Emerald
  arena segments load instead of throwing out of bounds. Object id `$4F` follows the active zone
  set, so Death Egg staircase placements no longer spawn Marble Garden sinking mud.

#### Title, Data Select, and Level Select

- **Title screen animation:** the Sonic animation advances on the ROM's own four-iteration counter
  rather than a table of per-frame durations measured from hardware. The varying capture durations
  were synchronous Kosinski decompression overrunning a frame, not a title-screen property. The
  frame art decode for the later animation frames now runs as hardware work through the load-time
  manifest, matching hardware overrun timing instead of loading instantly.
- **Debug shortcuts:** special-stage debug routing uses an explicit per-game capability profile, so
  shortcuts unsupported on a given game no longer call stage no-ops.

#### Special Stages

- **Blue Sphere entry:** the pre-boot fade blocks for the ROM's 22 real frames before the stage rate
  timer starts, and the entry object holds its routine for that fade rather than advancing speed and
  rate timers through it. The entry flash's transition into the stage no longer fires a frame early,
  since the ROM's countdown fires on passing below zero rather than on reaching it.
- **Blue Sphere movement and collision:** a coasting player re-accelerates to the current stage rate
  every frame instead of freezing, the grid-cell check runs at the ROM's point in the per-frame
  routine, and turn completion falls through to the position and collision update the same frame.
  This fixes a sphere collected a frame early on landing and a bumper that re-armed short of the
  ROM's turnaround.
- **Special stage emerald art:** the art loads through the real Kosinski module queue and waits on
  the ROM's modules-left predicate instead of a fixed drain measured from a capture.
- **Giant ring capture and return:** capture no longer freezes the camera and writes the player's
  mapping frame and animation as the ROM's capture tail does. The return position comes from the
  entry flash's own coordinates, which is what the ROM saves, and the star post the stage was
  entered from does not re-arm on return.
- **Giant ring level flag:** `f_bigring` is a level variable the engine never cleared, so it
  survived past the stage it triggered and diverted every later act end into an unearned special
  stage. The return also restores the saved level timer instead of restarting the act clock, fixing
  the end-of-act time bonus.
- **Special stage entry ring:** the 50-ring award marks the ring retired and re-queues the badnik-
  explosion art instead of deleting outright, and it retires in the same frame it is touched on the
  all-emeralds route, matching the ROM's fallthrough.
- **Solo Tails palette:** a solo Tails renders on the correct Blue Spheres palette line, matching
  the ROM's art setup for that player mode.

#### Bonus Stages

- **Gumball machine:** container and spin animation cadence matches the ROM's per-value hold
  sequence, and ejected balls dispense on the correct frame, self-poll the ROM's half-open proximity
  box against their live container position, can push the player more than once, and impart the
  ROM's bump velocity. Triangle bumpers use the inclusive-edge contact test and are fully intangible
  during their cooldown, and the machine no longer re-seeds the shared random-number state from a
  session-local counter.
- **Pachinko bumpers and flippers:** round bumpers self-despawn and respawn off the camera's
  vertical band and match the ROM's touch-response-driven re-bounce, angle jitter, and ground-speed
  handling. Flippers measure launch distance and facing correctly, apply the board-wall push, drive
  the locked player's own per-frame move, and keep a separate lock slot per player so a two-
  character ride accelerates both riders.
- **Pachinko orbs and traps:** the item orb reads its reward subtype from the high byte of its
  vertical position and dispatches its reward from its own update pass. The magnet orb's orbit,
  capture math, and touch-response suppression match the ROM and it no longer freezes the captured
  player's input word, and the energy trap starts its rise on the ROM's pass, captures both players,
  and keeps its children alive until scripted teardown. A registration-order bug that silently
  disabled the top-escape exit is fixed, and the first-frame touch-response list is published for
  the entry pass so a bumper can register a touch on the first row.
- **Slot machine reels and rewards:** reel spin velocity uses the correct 8-bit rotate, option-cycle
  timing, reward decrement, and release-launch axes read the ROM-derived counter and correct sine
  and cosine pairing, transient layout animations no longer advance a frame early, and the reel-wall
  flash runs at the ROM's two-frame-per-colour cadence. The reward cage no longer despawns off
  camera, since the ROM's live routine has no unload path.
- **Slot machine physics and exit:** ring and tile checks read the player's ground-projected
  position, ground-speed reversal is no longer clamped at zero, capture no longer zeroes the
  player's subpixel fraction, and the capture window is the ROM's half-open range rather than a
  symmetric absolute test. The goal exit keeps tracking the camera and reports the correct routine
  and grounded state instead of a fabricated airborne one.
- **Bonus-stage round trips:** entry reads recorded input on its first gameplay frame, exit restores
  the returning level's ring count from the interior's live count rather than an inflated total, and
  the exit-fade freeze phase advances the shared playback cursor.
- **Object persistence across bonus stages:** objects destroyed or collected before a star-post
  bonus stage stay that way on return rather than respawning fresh, matching the ROM's respawn-table
  retention, and every layout entry retains native respawn-table state independent of the Sonic 1
  and 2 high-bit convention.

#### Characters and Super Forms

- **Super and Hyper transformation:** Sonic, Tails, and Knuckles require the native second jump-
  button press to transform, respecting shield and invincibility priority, Tails' seven-emerald
  main-player requirement against his flight routine, and Knuckles' emerald gating before falling
  back to glide. Transformation flags, timing, palette state, and character physics round-trip
  through save and rewind.
- **Super form reversion:** beating any boss or miniboss as Super or Hyper reverts the player to
  normal form. The level-timer-pause defeat hook this depends on was duplicated per boss and missing
  on six instances, and now lives once on the shared boss base.
- **Super Sonic underwater palette:** Super Sonic keeps his gold palette underwater, because the
  ROM's palette-cycle apply writes the zone-specific water palette table as well as the surface one.
- **Knuckles glide:** glide activates while star-invincible, since the invincibility gate was
  wrongly shared with Sonic's shield abilities, and the glide, fall, and slide sequence matches the
  ROM's move-then-update ordering, odd-angle floor-probe rule, and jump-release timing. The get-up
  grounds Knuckles on the get-up frame instead of leaving him on the airborne control path.
- **Knuckles animation and monitors:** the walk animation freezes its mapping frame while the push
  status is set, matching the ROM's push-hold branch, and a gliding or sliding Knuckles passes
  through and breaks a monitor instead of being walled by it.
- **Playable Tails:** Tails animates his idle and flying tail sprite as the main playable character,
  not only as a sidekick, including in a solo session.
- **Landing, terrain, and springs:** landing no longer overwrites a live spindash-charge animation
  with Walk, since the ROM gates the landing write on the spindash byte here the same way Sonic 2
  does. Angled-terrain landings publish Walk for rolling and non-rolling players alike and before
  animation runs, and vertical and diagonal springs return a hurt player to the normal control
  routine and clear the jumping flag on launch.

#### Sidekicks

- **Off-screen despawn watchdog:** the sidekick CPU despawns Tails when, off screen and standing on
  an object, that object's routine-code word no longer matches the latched interact word. The rule
  is driven by objects that expose a ROM code pointer, not by zone name.
- **Sidekick park:** solid objects across the game publish the ROM object-code pointer a sidekick
  latches while standing on them, so the ROM's identity compare and park-to-despawn response
  actually run. The compare no longer requires an invented previously-armed precondition that had
  suppressed the first off-screen landing.
- **Catch-up flight:** catch-up flight cancels an in-progress spindash charge as the ROM's recovery
  block does, instead of letting a charge begun before an off-screen respawn survive the flight and
  launch Tails from the wrong table entry.
- **Follow and push:** the follow nudge is no longer suppressed merely for riding a platform,
  restoring the ROM wall-push response, and follow samples leader speed at the correct physics
  phase. The sidekick preserves live solid-object push bits while rolling and copies consecutive
  action-button presses from follower history so multi-button spindash charges reach ROM speed.
- **Animation ownership:** shared player-animation ownership preserves Tails' duck write across
  terrain detach while letting object control that sets only the high bit continue normal movement
  animation.
- **Intro suppression:** the initial CPU branch follows the active level-event provider's zone, act,
  and star-post state rather than stale roster metadata, and Angel Island and Icecap intro sidekicks
  stay hidden until their ROM-owned release, so a live Player 2 cannot enter normal falling physics
  during the intro.
- **AutoSpin and cork floors:** the AutoSpin trigger no longer force-rolls a carried Tails, since
  both routines skip the crossing check while the sidekick owns flight or carry, and a rolling
  sidekick who smashes a cork floor also drops the partner standing on it.

#### Results and Level Exit

- **End sign:** the signpost keeps its horizontal velocity on landing, which is what the hidden
  monitor re-bounce depends on, since the ROM's landing branch touches neither velocity component. A
  bump from below scores for only one player per frame, a tail-jump quirk of the shipped ROM, and
  its off-screen range test uses the ROM's cumulative offset table instead of treating each entry as
  independent.
- **End-sign results sequence:** the sequence distinguishes a real landing wait from an already-
  grounded collapsed dispatch and separately sequences Player 1's ending pose from Tails' later pose
  and control release, matching native player slot ordering.
- **Results creation and exit gating:** the create gate for results children and the act-transition
  signal gate purely on the remaining Kosinski module count, matching the ROM's results-create
  routine, rather than a fitted countdown. Results exits publish the transition-ready flag only when
  the live event provider reports a retained native handoff owner, removing stale zone-name
  inference.
- **Carried results and in-level title cards:** these retain end-sign control handoff, camera
  bounds, and ring, timer, and air resets at their native object-owned wait boundary, and a results-
  owned in-level title card gets a fresh art admission after a consumed seamless resource handoff.
  Upright egg capsules preserve native child, parent, and results dispatch order and the correct
  second-controller lock gate.
- **Music interruption:** an extra life, invincibility, or Super transformation no longer silences
  music for the rest of the act. The presentation path records the ROM's single-slot save-and-
  restore music override, owned only by the 1-up jingle, rather than a stack, and level music is
  restored by re-issuing it with its own gates so boss and drowning music is preserved.

#### Objects and Badniks

- **Object allocation and queue order:** dynamic allocation follows the ROM's full 90-probe window
  over object slots 4 through 93, level-load-resident objects dispatch once before the first main-
  loop frame rather than twice, and level-load enemy art is queued on the ROM's actual frame. A
  large body of trace-driven work also brought results and title-card sequencing and Kosinski art
  queue submission order in line with the ROM's allocation and object-pass ordering across every
  brought-up zone.
- **Init dispatch consumption:** Mega Chopper, Blastoid, Sparkle, Rhinobot, and Monkey Dude consume
  their routine-0 init dispatch as its own frame rather than falling through into main behavior the
  same frame. Caterkiller Jr is modelled differently, since its init genuinely falls through.
- **Collision-list read points:** numerous solid and touch objects were reading collision or touch
  coordinates from before their own same-frame move, or after it where the ROM reads before. These
  were corrected object by object against each routine's actual read point, along with several
  objects' render height and on-screen extents. The floor and wall probes retain a zero-sampled
  shape's angle as the ROM does, and background collision honors empty interleaved layout rows.
- **Bouncing, lost, and attracted rings:** ring collision clearing, spill-counter resets, and floor
  probes defer to the owning object's own pass instead of the frame a hurt allocates them, include
  background collision geometry when scanning for floor, and hand an attracted ring off to a
  bouncing ring when the lightning shield is lost. Hurt-ring spills reserve their complete after-
  current slot chain at the player-slot instant.
- **Ring attraction and collection:** attracted rings collect at the retained player-slot collision
  checkpoint, one ring per player per frame, with newly attracted rings ineligible until their first
  object update.
- **Star pointer:** parents and orbiting children use live post-movement position and preserve their
  initialization-only first dispatch, and orbiting points are deleted when their parent badnik is
  destroyed, matching the ROM's parent-death check on the orbit branch.
- **Collapsing platforms and landing gates:** fragment visibility latches in the render phase,
  platforms stay solid during the fragment phase, and the compensated final-decrement pass is
  exposed to a second player. A sweep of every full-solid call site found three further landing-gate
  width mismatches, at the Angel Island disappearing-floor border, the Carnival Night miniboss top,
  and the Icecap end-boss solid bottom. Moving spikes gate solidity on the prior render pass, and
  horizontal invisible and spike hurt blocks use ordinary lost-ring owner timing rather than a
  forced deferral.
- **Air bubbles and drowning:** underwater players visibly exhale bubbles and the drowning countdown
  digits render again, both driven by the ROM's breathing-bubbles object and its real animation
  script instead of a hand-rolled special case.
- **Animals and star posts:** subtype-zero animals consume their art-variant random draw on the
  animal slot's first dispatch after the explosion allocates it, and star-post bonus round trips
  restore the ROM-faithful checkpoint index, a persistent used-activation mark, and the player's
  saved return position.
- **Debug-only geometry and checklists:** AutoSpin triggers, invisible blocks and their hurt
  variants, twisted ramps, and sinking-mud collision outlines are visible only through the dedicated
  object-debug overlay. The per-zone object checklists are transcribed from the registry's own zone
  gates and guarded by a test.

#### Palette and Visual Systems

- **Sprite priority:** priority buckets and object attributes were corrected across Angel Island's
  intro and boss-exit scenery, Carnival Night's balloons, bulbs, cylinders, and badniks, and Marble
  Garden's boss telegraph raster and atlas isolation, so pieces layer as on hardware. Rings spilled
  by boss damage carry the native high-priority art bit and display bucket.
- **Background deformation:** Angel Island Act 2's direct-entry background deformation and post-
  bombing forest loop, Marble Garden's background rise, and Mushroom Hill Act 2's boss-area vertical
  deform all follow the ROM's own formulas.

### Sonic 1

0.6 drove Sonic 1 toward complete-run trace parity across every zone. Work concentrated on object
slot allocation, platform landing and riding surfaces, camera boundary behavior, and the end-of-act
and special-stage transitions that gate a full route.

#### Green Hill Zone

- **GHZ solid widths:** the purple rock, bridge, collapsing floor and collapsing ledge now balance
  the player, and for the rock cull on screen, at their own ROM `obActWid` byte rather than the
  shared 16px default, which had shifted the balance window and screen-cull bound on every object
  the player stands on in the zone.
- **GHZ collapsing ledge:** the ledge clears the rider's on-object and push status when it
  collapses, so the terrain floor re-seat can run and drop the player onto the ROM-faithful terrain
  Y. It also lands across its full platform half-width and rejects a zero-distance top-solid
  landing, matching the ROM's unsigned strict-penetration band.
- **GHZ spiked-pole helix:** the helix allocates one real object-RAM slot per spike instead of
  drawing every spike from one instance, so ring-scatter slot counts and scattered-ring recollection
  match the ROM. Its animation phase derives from the global animation frame with the correct
  off-by-one, since object execution runs before `SynchroAnimate`, and its hurt-direction test
  compares against the individual child spike's X rather than the parent's.
- **GHZ Egg Prison switch:** the switch drops the player airborne and stops being solid after
  firing, matching `Pri_Switch`'s clear of `Status_OnObj`, its set of `Status_InAir`, and its
  routine handoff to `Pri_Explosion`.
- **Fall-on-stand platforms:** the type-03 platform in GHZ, SLZ and SYZ starts its 30-frame collapse
  countdown on the landing frame itself instead of one frame late, matching the ROM's fall-through
  from `PlatformObject` into `Plat_Action`.
- **Generic platform landing surfaces:** new-landing detection uses the ROM's entry surface
  (`obY-8`) rather than the riding surface (`obY-9`), while continued riding keeps the riding
  surface. An airborne rider is also carried through the unconditional `MvSonicOnPtfm2` Y re-seat on
  the jump-off frame, so the platform's post-move Y overwrites the rolling-radius jump adjustment.
- **GHZ2 camera bottom boundary:** the vertical camera re-clamps to the bottom boundary whenever it
  is actively moving, not only on frames where the normal scroll already moved it, matching the
  `f_bgscrollvert`-gated `SV_BottomBoundaryMoving` branch. The GHZ and SLZ smashable wall's
  post-smash horizontal adjust also shifts the pixel integer only, preserving the sub-pixel
  fraction.

#### Marble Zone

- **MZ2 vertical camera boundary is directional:** the clamp applies only the top boundary while
  scrolling up and only the bottom boundary while scrolling down, matching `ScrollVertical`'s
  `SV_TopBoundary` and `SV_BottomBoundary` split instead of an unconditional symmetric clamp.
- **MZ lava geyser:** the geyser's animation countdown, spawn-frame action dispatch and
  push-block-spawned maker timer match `AnimateSprite` and `Geyser_Main` exactly, ending an
  eruption-period drift. The head defers its first gravity and move step to the frame after spawn,
  no longer shows the previous cycle's ending frame at the start of the next eruption, and a custom
  render height lets the 256px column hurt a player standing under it. Lavafalls no longer flicker
  at their landing spot on their spawn frame, and the lava wall clears its respawn counter at ROM
  timing without rematerializing early.
- **MZ pushable block:** a block that finishes sinking in lava is deleted only when its origin is
  also off-screen instead of parking forever, blocks no longer survive off-screen on an invented
  camera window, a triggering block spawns both lava-geyser makers the ROM spawns, and the 4x1
  variant balances at its subtype-specific ROM width of 64.
- **MZ moving block:** the walk-off bounds check reads the block's pre-move X, matching
  `MBlock_StandOn` calling `ExitPlatform` before `MBlock_Move`, and the block rejects the
  exact-touch zero-distance landing while using its full `obActWid` landing width so a falling rider
  no longer drops through.
- **MZ moving spikes:** horizontally-moving spikes no longer drag a standing rider, since the ROM
  caller passes the post-move `obX` as the `MvSonicOnPtfm` carry reference and the delta is zero.
  Their unload check anchors on the spawn-origin X, matching `spikes_origX`, so a moving spike stays
  loaded and solid as long as the ROM keeps it.
- **MZ Caterkiller:** an off-screen fragment-mode head unloads as a respawnable object and clears
  its placement-counter bit, matching `Cat_Despawn`, which corrected downstream slot ordering.
- **MZ chained stomper:** the stomper reserves two child slots instead of three when its spikes are
  collision-less, matching `CStom_Loop`'s re-run without a fresh `FindNextFreeObj`.
- **MZ glass and green blocks:** the large green pillar culls and balances at its own `obActWid` of
  32 and its short-variant reflection resyncs from the parent's live Y instead of a static spawn
  baseline, so it no longer floats away. The smashable green block preserves the player's centre Y
  across the standing-to-rolling transition, since the top-left position model otherwise shifted the
  derived centre up when the roll height shrank `obHeight` and launched the rebound too high.
- **MZ platforms:** the collapsing floor drops a still-standing rider airborne when its timer
  expires and defers entering the collapse routine by one frame to match `PlatformObject` timing.
  The swinging platform carries a final Y re-seat when a rider walks off onto an abutting platform,
  lands across its full `obActWid`, and seats new riders from its pre-move position.
- **MZ Buzz Bomber missile:** the missile cancels itself only when its parent is destroyed during
  its flare phase, not while active, matching `Msl_ChkCancel`'s call sites. The earlier premature
  deletion shifted slot cadence enough to mis-time a later Batbrain drop.

#### Spring Yard Zone

- **SYZ Roller:** the badnik no longer despawns when it scrolls off the left edge. The shipped
  `FixBugs = 0` branch uses a signed offscreen compare that a negative distance can never satisfy,
  so a Roller that has scrolled left stays alive and keeps rolling. A dormant curled Roller also no
  longer renders, idle-animates, or collides before it activates.
- **SYZ monitor solidity:** the side correction applies whenever Sonic is merely not moving away
  from the monitor, matching `Mon_Solid`'s sign test on `x_vel`, rather than only while he moves
  into it. A broken monitor clears its collision type per `Mon_BreakOpen`, so a spent monitor no
  longer blocks `ReactToItem` from an adjacent one.
- **SYZ2 moving-platform walk-off:** the bounds check references the platform's stored pre-move ride
  baseline for objects using pre-move solid contact, and the final `MvSonicOnPtfm2` carry applies on
  the out-of-bounds exit frame itself.
- **SYZ jump and slope handling:** the jump headroom ceiling probe no longer double-applies the
  Y-flip as both a probe offset and part of the shared vertical-distance calculation, matching the
  single flip in `Sonic_FindCeiling`, and the shared missed-detach replay no longer re-applies
  walking slope resistance on a wall-detach frame when the player was stationary at frame start.

#### Labyrinth Zone

- **LZ water splash:** the splash no longer deletes itself a frame early, no longer duplicates in
  its single reserved slot on repeated water crossings, and no longer consumes a level-object slot
  at all, since `v_splash` is a fixed slot the ROM never runs `FindFreeObj` for. This cleared a
  cascading LZ1 door and switch timing skew.
- **LZ air bubbles:** the bubbles read water height with its surface sway term, via the same
  accessor the sibling breathing-bubble object already used, instead of running up to 15 pixels
  shallow in one direction. The bubble maker and the drowning countdown both replicate the ROM's
  pool-full retry behavior, keeping the shared RNG draw cadence in step instead of dropping spawns.
- **LZ1 water current:** the floating-block door implements the ROM's current-disable gate until the
  door's switch is pressed, so the current no longer pushes Sonic into a closed door.
- **LZ conveyor:** the wheels despawn out of range as the ROM does rather than persisting for the
  whole act, the maker re-spawns its platform cluster on re-entry by clearing its latch on unload
  and reuses its own slot for the first platform, contact is checked at the pre-move position with
  the full platform solid profile, and platforms no longer self-cull on their spawn frame. The LZ
  and SBZ conveyors also honour the ROM's wider act-3-only despawn window.
- **LZ Burrobot and harpoon:** the Burrobot falls off a ledge instead of walking on air, matching
  `ObjFloorDist`'s always-a-distance contract, and the harpoon's animation cadence follows the ROM's
  per-frame duration byte instead of losing a frame per cycle, so its hurt box no longer stays
  extended too long.
- **LZ staircase:** child-slot reservations are keyed by the stable placement spawn rather than a
  per-frame dynamic spawn, so unloading staircases free their three reserved slots instead of
  leaking them and starving the SLZ fireball maker.
- **LZ Egg Prison:** landing on the release switch no longer locks the camera, since the ROM only
  stops the timer there.

#### Star Light Zone

- **SLZ elevator:** the elevator rejects the exact-touch zero-distance top-solid landing, adopts the
  full platform family behavior (pre-move solid contact, full `obActWid` landing width, the `obY-9`
  riding surface, exit-frame Y re-seat), and reports its full half-width for edge-balance detection
  so a centered rider is not falsely treated as balancing.
- **SLZ circling platform:** the platform carries an unconditional Y re-seat on the ride-exit frame,
  uses pre-move solid contact, and lands with its full `obActWid` width per `Circ_Platform`.
- **SLZ seesaw:** tilt tracks any riding player rather than the strict standing flag, top landings
  use the seesaw's full width, the exact-touch zero-distance landing is rejected, the slope surface
  is absolute rather than baseline-subtracted per `See_Slope`, and the tilt target is computed
  immediately before the mapping-frame advance, matching the atomic `See_ChkSide` then
  `See_ChgFrame` pair. Its spikeball child spawns in `FindNextFreeObj` slot order, landing above the
  seesaw so it reads the already-updated tilt each frame, and falls through into its launch-frame
  fall step.
- **SLZ staircase:** the staircase reserves its three ROM child slots and executes from the highest
  one, after the SLZ fan, so the fan's push is observed before the continued-ride re-seat. Its timer
  no longer decrements on the frame it is set, non-riding sibling pieces no longer override the
  ridden piece's re-seat, and the multi-piece Y bounding-box check uses the airborne half-height
  uniformly, fixing a 1px-too-low seat shared across all three games.
- **SLZ fan:** the fan evaluates its vertical wind-range gate against the player's centre Y captured
  at the start of the object pass rather than the live value, matching slot order relative to the
  staircase that re-seats the player above it.
- **Horizontal springs:** the spring drives its 15-frame D-pad control lock through the shared
  move-lock timer, which decrements on grounded frames only, matching the ROM's freeze during jump
  modes. It also toggles the player's facing instead of overwriting it, and uses an inclusive right
  solid edge so a player falling flush against its right face still triggers the launch.

#### Scrap Brain Zone and Final Zone

- **SBZ solid widths:** the rotating junction, small door and trapdoor balance at their ROM
  `obActWid` values of 48, 8 and 128 rather than the shared default.
- **SBZ sub-pixel preservation:** the teleporter capture and the conveyor belt push both preserve
  the player's sub-pixel fraction instead of zeroing it, matching the ROM's pixel-word-only writes,
  and the teleporter keeps the fraction through its rise, snap, release and travel phases. An audit
  of every S1 object-to-player position write confirmed these as the only genuine bugs.
- **SBZ spinning platform and trapdoor:** the platform releases its solid latch on detach instead of
  forcing the rider airborne the same frame, so the rider goes airborne the following frame when his
  own floor check finds no support. Its spin cycle gates on the gameplay level frame counter rather
  than the VBlank clock, and a platform cluster clears its spawner latch when it leaves the screen
  so it re-spawns on return.
- **SBZ vanishing platform and saw:** the vanishing platform reads the level frame counter for its
  cycle and joins the standard top-landing family; the saw unloads off-screen against its own origin
  anchor instead of staying persistent forever.
- **SBZ walking bomb:** the fuse no longer decrements on its own spawn frame, and shrapnel deletes
  on the ROM render-flag on-screen bound rather than a wider camera margin, restoring the live
  object count and slot occupancy.
- **SBZ Ball Hog:** the Ball Hog and its cannonball integrate motion with `ObjectFall`'s
  one-frame-delayed gravity ordering, correcting the cannonball's arc, and the Ball Hog's animation
  timer no longer holds its first step a frame short and throws early.
- **SBZ swinging platform and Electrocuter:** the swing anchor spawns its chain links as real
  render-only slot children, one per ROM chain link, instead of modelling the whole chain as one
  object, fixing downstream badnik slot placement. The Electrocuter's zap-frame gate reads the
  seeded level frame counter rather than a free-running object-manager counter.
- **SBZ2 timing and placement:** the end-of-act results screen models the ROM's PLC decompression
  wait before the title card slide-in begins, correcting a right-boundary scroll that fired early.
  Objects spawn within a 0x80-aligned chunk in ROM layout-table order via a stable sort rather than
  strict X order, and lifecycle fixes across the walking bomb, shrapnel, swing chains, ground saw,
  small door, spinning platform, Caterkiller and lost rings realigned slot occupancy with the ROM.

#### Bosses

- **Deferred defeat dispatch:** the GHZ, MZ, SLZ, SYZ and Final Zone bosses defer their
  defeat-routine dispatch by one frame, since the ROM reads `ob2ndRout` fresh each frame rather than
  falling through, so the escape camera-boundary scroll opens on the ROM frame.
- **GHZ boss:** the wrecking ball runs the full defeat sequence, an explosion plus a periodic second
  explosion stream with its own RNG draw, instead of vanishing instantly; the missing draws had
  desynchronised the RNG stream by the prison capsule. The ball starts swinging only once the ship
  reaches combat-reverse per `GBall_Base`, so its hurt box no longer overlaps a rolling player
  early.
- **MZ boss:** the lava countdown seeds from the raw low byte of the per-frame descent RNG draw and
  no longer draws at object init, matching the ROM's first draw landing on the first descent frame.
  The falling fireball drops on its spawn frame instead of one frame late.
- **SYZ boss:** the retracting spike reads the boss's state directly each frame like the ROM's
  independent spike object rather than being pushed by the boss's routine dispatch, so it no longer
  lags during retreat, and it renders behind the Eggman ship in the ROM draw order. The pick-up
  blocks use the correct level tile palette line and the two rightmost arena blocks stay alive
  off-screen. The boss persists through its own escape, stops clearing the level-boundary lock
  early, and derives its block-drop shake from the low byte of the ROM timer.
- **SLZ boss spikeball:** the spikeball no longer ignores an already-defeated boss, explodes in
  place while keeping its slot and seesaw link, spawns its self-destruct fragments at the seesaw
  centre, rejects a no-tilt-change landing launch, applies the seesaw spring launch with the correct
  velocity and roll state, reports its hurt box from centre position, and reproduces the ROM's
  drop-cadence duplicate-ball abort. Explosion fragments are removed on the ROM's render test rather
  than a camera-bounds check.
- **Final Zone:** the cylinders preserve the native subtract-with-borrow retraction cadence at exact
  zero and defer their first extension step one frame after activation, plasma balls allocate from
  the launcher's own slot cursor, and the boss reads the previous frame's camera X for its wait
  exit, matching the ROM's object-execution-before-scroll ordering. The boss advances the random
  seed on every cylinder-attack side-contact frame and every wait-state frame so the plasma spread
  and attack selection draw the ROM sequence, uses an inclusive solid right edge so the rolling
  rebound fires on the correct frame, gates its post-hit collision re-arm on not being defeated,
  preserves sub-pixel through its escape position clamps, and clears only inertia on the post-escape
  control lock. The engine also models Eggman's own `obActWid` and the plasma launcher's ROM-omitted
  zero `obActWid`, a genuine ROM bug rather than an engine gap, so the player balances on the
  launcher the whole time he stands on it. The persistent screen-lock flag drives the level-boundary
  side clamp instead of boss-alive state.
- **Egg Prison capsule:** explosions and animals spawn from the depressed switch's position rather
  than the capsule body's, the explosion phase always starts the frame after the trigger regardless
  of button and body slot order, the random animal X offset negates on the sign of the seed the draw
  just wrote rather than the returned value, S1's capsule releases S1's own animal object without an
  extra draw per animal, and the button freezes and blanks after triggering instead of flashing
  forever. Separately, the boss-hit touch response halves the negated rebound velocity on Sonic 1
  only, matching `React_BossHit`'s negate-then-shift.

#### Title, Level Select, Special Stages, and Ending

- **Title screen:** Sonic's torso no longer shows below the title logo's bottom edge. Object `0F`
  frame 2's thirty blank sprites exhaust the VDP's per-line sprite budget from row 104 down and drop
  the lower-priority title Sonic, which the engine models by cutting his sprite at row 104.
- **Title screen twinkle:** the first sparkle sound now plays as the title fades from black, the
  frame `Obj0E_Sonic_Init` runs, instead of when the "SONIC AND MILES 'TAILS' PROWER IN" text
  appears; the ROM shows that text silently and only spawns the intro object after it has faded out.
- **Title cards:** the release gate no longer holds for an invented minimum of 60 frames; the real
  gate is every element at target with the PLC queue empty. The card's explosion and animal art is
  re-queued from its fixed object slot rather than the renderer's slide-out predicate, so it is
  submitted whether or not the card is visually presented.
- **End of act:** the level main loop stops as soon as the Got-Through card sets its restart flag,
  so the post-act fade no longer runs as ordinary gameplay frames animating Sonic and publishing
  player art transfers the ROM never performs. Advancing runs the whole level restart with its
  mandatory title card instead of a fade-to-black that skipped presentation, letting the emerald
  route cross a plain act-to-act boundary. Signpost sparkles live the ROM's 25 frames instead of a
  flat 16, and the signpost's Pattern Load Cue is submitted through the runtime Nemesis queue from
  the main-loop tail rather than being made resident eagerly outside the queue.
- **Special stage exit and emerald pickup:** the exit is armed only by `SonicSS_ChkGOAL`'s
  GOAL-block touch, or by the emerald sparkle animation's own terminator once a collected emerald's
  script finishes, not by grabbing the emerald directly. Ending the maze the instant an emerald was
  touched had desynced any run that collects one mid-maze without touching GOAL.
- **Special stage bumpers and fall probe:** a touched bumper flashes through its recovery frames as
  a non-solid id until the animation restores it, instead of staying re-triggerable, and the
  fall-probe wall scan visits all four cells and keeps the last solid hit rather than stopping at
  the first, so an adjacent bumper's bounce is no longer masked by a wall hit.
- **Special stage timing, audio, and angle math:** the entry sound now continues through the
  white-out and into the special-stage theme, since Sonic 1 enters that fade without issuing the
  sound driver's music-fade command that would stop the effect. The stage palette cycle fires once
  during the instant setup in addition to its per-frame call, angle transforms mask and add before
  negating to match the ROM's byte-truncating `neg.b`, the stage angle and rotation variables
  initialize at the ROM's instant-setup point after the white-out fade rather than at tick 0, and
  the engine models the ROM's 44-tick hold before the first object pass.
- **Special stage blocks and results:** an UP or DOWN block rewrites itself to the opposite block
  only when it actually changed the stage's rotation speed, and the results card takes its full
  continue branch, jingle plus wait, at 50 or more rings instead of always the short path. The card
  also models the pre-level fade-out and post-whiteout iteration timing on its return to gameplay.
- **Special stage input and load:** jumping responds to A, B and C again, after the logical-input
  routing change had left it gated on an engine-internal bit that only coincidentally matched B. The
  engine also no longer publishes Sonic's dynamic-art transfer while loading the stage, since the
  ROM clears the frame-already-in-VRAM latch at setup rather than loading graphics there.
- **Ending and credits:** Sonic no longer stops dead a few seconds into the ending, whose own loop
  deliberately skips the end-of-act signpost step the engine had been running anyway, walling off
  the ground behind the already-far camera. In the credits demos the vertical-interrupt counter runs
  free from the recording's own starting value, including on lag frames, instead of from a constant
  measured off an older capture, fixing Labyrinth Zone's wind-tunnel sound and pull-down phase.
  Credits music continues across demo loads, and each demo prepares its camera and foreground/background
  scroll before fading in. Sonic runs his native initialization update and holds the standing pose
  through the fade, eliminating the wrong terrain view and inherited running animation.
- **Window and display transitions:** the title screen and gameplay transitions re-read the
  framebuffer size and reshape the viewport from framebuffer pixels, and no longer resize or move
  the host window when a launch profile changes display aspect, fixing scaled and high-DPI windows
  rendering into a small corner.

#### Player Physics and Collision

- **Right-wall odd-angle snap:** ground-angle selection no longer keeps a stale cross-frame fallback
  angle when a right-wall sensor returns zero distance with a flagged angle. `Sonic_Angle` snaps an
  odd selected sensor angle straight from the current angle with no cross-frame cache, so the
  resurrected fallback was reverting the player angle one frame early.
- **Collapsing-floor landing surface:** the collapsing floor models the ROM's separate touch-detect
  surface and riding surface instead of using the riding surface for both, fixing a one-frame-late
  landing where the player sat a pixel too high before re-seating.
- **Monitor top-overlap release:** a monitor's top-overlap floor re-seat no longer re-grounds a
  rising player who just jumped off it, matching the negative-Y-velocity gate in `Mon_Solid`. The
  change is narrowed to jumping riders so it does not mask a separate object-order bug.
- **Edge-balancing camera pan:** the standing-still look-up and look-down camera bias computes the
  player's edge-balancing state for the current frame through a side-effect-free snapshot rather
  than reading the prior frame's stale state, matching the ROM's balance-before-look-up order.
- **Camera boundaries:** the bottom-boundary easing defers the airborne acceleration to the clamp by
  one frame, matching the ROM's scroll-before-events ordering, with the ascending and snap paths
  unaffected. The per-frame horizontal scroll cap applies only to rightward movement in Sonic 1,
  since the ROM's leftward branch is gated behind `FixBugs` and ships uncapped, unlike the later
  games which cap both directions. The screen-wrap Y mask applies only to the game that actually has
  that ROM variable; Sonic 1 and Sonic 2 wrap only in the frame the camera crosses the boundary.
- **Floor angle retention:** walking off a zero-height column retains a solid foot tile's angle, and
  ring groups clear their respawn flag with the ROM's bit-clear rather than a wider reset.
- **Solid-object push-status batching:** batched post-movement collision passes pair a released
  object's prior-pass push latch with the player's frame-start push status instead of a later read,
  matching the inline no-collision path once movement has already cleared the live bit.
- **Spring recovery and speed shoes:** a hurt Sonic regains control immediately off a spring bounce,
  matching the ROM's unconditional routine override rather than leaving input locked until a later
  landing. Speed shoes now cover the correct movement frame, since Sonic 1 was missing the one-tick
  pre-physics compensation its Sonic 2 counterpart already had.
- **Animation fidelity:** slope-running frames retain their flip bits, solid-object push release and
  edge contact use the ROM's inclusive edge and live slot latch, forced signpost movement owns
  animation selection over stale raw input, and ground, roll and coasting movement preserve the
  ROM-owned animation byte until a native routine changes it.

#### Objects and Badniks

- **Orbinaut:** the parent no longer frees its spikeballs' slots when destroyed by the player, only
  when out of range; the old parent-side delete ran on both paths and, on the kill path, outside the
  object pass entirely, shifting slot allocation for the rest of the act. The satellite spike's
  orbit uses the ROM's integer sine and shift arithmetic instead of floating-point trigonometry,
  which had rounded a coordinate a pixel off and caused a premature hurt touch.
- **Fiery explosion:** the S1-specific fiery explosion no longer loses a frame of life to the other
  explosion object's fall-through init, so it lives the ROM's 40 frames and no longer skews the
  slot-allocation-driven RNG species draw in LZ3.
- **Swinging platforms:** chain construction stops at the first failed free-slot search, matching
  the ROM instead of keeping an unregistered child reference, which had broken live rewind when
  object RAM was full. Chain links unload with the parent pivot rather than their own swung-out
  position, and the platform rejects an exact-zero-distance top-solid landing.
- **Monitors:** power-up and explosion children spawn during the monitor's own execution rather than
  the player-touch pass, matching `Mon_BreakOpen` slot and frame timing, and the monitor balances at
  its ROM width of 15.
- **Yadrin and animals:** Yadrin's top spikes hurt Sonic even while attacking, using the ROM's
  actual offset hitbox region instead of a centre-distance comparison that rarely matched a real
  graze. For animals, a floor probe distance of exactly zero counts as no hit, matching the ROM's
  branch, which corrected bounce phase and later slot allocation.
- **Checkpoints and shields:** the starpost twirl ball rests at the ROM-correct orbit angle after
  activation, where an off-by-one lifetime constant had it stop one step too far left, and lampposts
  no longer show a duplicated, offset ball after the twirl. The shield uses the ROM's deliberately
  empty mapping frame between its three visible frames and animates from a local countdown rather
  than the global vertical-interrupt phase.
- **Bumpers and badnik unload:** the pinball bumper's bounce direction uses the player's position at
  the moment of touch instead of one frame later, fixing wrong-direction bounces for fast-moving
  players. S1 badniks defer their out-of-range unload check until after the object routine runs,
  matching the ROM's remember-state-at-routine-end pattern, instead of checking the stale pre-move
  position.
- **Object lifecycle:** a mid-loop-spawned child dropped into a slot at or below its parent's
  current execution slot stays touch-ineligible until the frame after its own first execution, and a
  destroyed object is blocked from respawning only when its layout entry is respawn-tracked. An
  object that self-deletes off-screen clears its respawn-table bit so it returns, while player-kill
  deletes keep the bit latched. Invisible solid barriers balance at their subtype-derived ROM width
  and the sideways spring at its `obActWid` of 8.
- **Player art staging:** the level-load player sprite DMA staging is no longer dropped when a run's
  clock spans the load, since the ROM stages Sonic's graphics twice around a level load separated by
  a V-blank. The staged player transfer no longer dispatches on a ROM lag frame, because the
  per-mode VBlank handlers that perform the write are skipped there, and a lag iteration that
  absorbs a V-blank runs its own pattern-cue step on the row that absorbed it.
- **Object placement on camera resync:** Sonic 1's counter-based placement is preserved across the
  level-start camera resync, restoring a dropped SBZ3 floating block that the shared snapped-camera
  path had disturbed.

### Sonic 2

Sonic 2 received the broadest object-level accuracy pass of the release. Most entries below came
out of frame-accurate comparison against the shipped ROM, so the recurring themes are object
execution order, SST slot ownership, and routines that read their state once per frame.

#### Emerald Hill and Chemical Plant

- **EHZ object loading:** Sonic 2 no longer applies the vertical camera-band filter, which is really
  a Sonic 3 & Knuckles rule, so layout entries outside the starting camera band load at the correct
  time. A spring performs only initialization on its load frame.
- **EHZ Coconuts:** a thrown coconut defers its first movement frame, because `Obj98_Init` only
  loads render and collision state on the spawn frame. The early move drifted the trajectory enough
  to flip a player touch between hit and miss.
- **EHZ and HPZ bridge:** the bridge allocates its ROM subsprite child objects, so despawn logic
  that reads the interact slot works, and reports the ROM's fixed `$80` object-edge balance width
  instead of one derived from log geometry.
- **CPZ looping platforms:** an arriving platform can no longer be solid before it has been drawn
  once, restoring the ROM's one-frame air gap during the hand-over.
- **CPZ staircase (Obj78):** the staircase reserves the three child SST slots the ROM allocates for
  its steps, even though the engine renders all four from one instance, and scopes push bridging,
  top-trigger timing, and solid-status latches to those slots. The reservation fixed a slot-order
  drift that shifted every later dynamic object.
- **CPZ spin tubes (Obj1E):** capture, release, and tube-to-tube handoff follow the ROM routines for
  roll status, subpixels, jump-height latch clearing, and slot order. A tube testing entry collision
  against a player already mid-traversal reads that player's frame-start centre, and tubes use
  player-local `obj_control` without asserting global `Control_Locked`.
- **CPZ badniks:** the Grabber (ObjA7) dives on the ROM's frame, since `ObjA7_Main` calls
  `ObjectMove` on the frame that ends the pre-dive delay, and the Spiny's spike projectile spends
  one extra stationary init frame. Both had been arriving early.
- **CPZ water:** dynamic water rises to the correct Mega Mack target, the oscillating waterline
  feeds gameplay water checks, and the splash reads the shared `Water_Level_1`-derived offset
  instead of an S2-specific accessor that diverged by up to eight pixels.
- **CPZ solids:** invisible blocks (Obj74) release the player's push bit on a four-pixel side-air
  contact even when the block never raised it, matching `Solid_NotPushing`. The warp-pipe exit
  spring detaches the launched character on the frame it bounces them, the springboard (Obj40)
  always takes the sloped-solid path, the breakable block (Obj32) stays solid for both players
  within one pass, and the tipping floor (Obj0B) reads its duration from the correct subtype math.

#### Aquatic Ruin and Casino Night

- **ARZ bubble makers:** on-screen status is judged from the camera position the ROM's dispatch
  sees, the catch band's bottom pixel counts as a catch, and the bubble is tested before it floats
  upward, removing a two-frame late gulp and spurious bubbles.
- **Drowning countdown digits:** Sonic 2's Obj0A bubble sheet now loads the six ROM number blocks
  used by `Obj0A_LoadCountdownArt`, and numbers 5 through 0 select mapping frames 8 through `$D`
  from `Ani_obj0A`, so the final-air warning displays digits instead of ordinary bubble frames.
- **ARZ2 badniks and effects:** Whisp (Obj8C) chase cadence, ChopChop (Obj91) patrol bubbles,
  Grounder (Obj8D, Obj8F, Obj90) floor snap and debris, the arrow shooter (Obj22), leaf render
  bounds (Obj2C), the bubble generator (Obj24), breathing bubbles (Obj0A), lost rings (Obj37), skid
  dust, the checkpoint dongle, and the rotating-platform assembly (Obj83) all follow their ROM
  routines' slot, allocation, and random-draw cadence.
- **ARZ2 animals:** popped-badnik animals preserve vertical subpixel carry through the arc and
  display only on their creation pass before movement starts, matching `Obj28_InitRandom`.
- **CNZ slot machines:** the slot machine runs after `RunObjects` on the current native V-int count,
  preserves 16-bit reel-position underflow, and decodes and rewrites `slots_targ` with the ROM shift
  values instead of reversing the displayed reel order, so stopped reels line up with the reward
  paid out by linked Point Pokey cages. Reel graphics stay aligned with the cage when window
  resizing adds horizontal or vertical viewport borders. Robotnik spike prizes advance their shared
  sound counter on cage payout updates and use the secondary sound mailbox, preserving the ROM
  impact-sound cadence across rewind.
- **CNZ Point Pokey:** bumper angle math, capture and release, and linked-cage prize-counter timing
  match the ROM, and the cage bonus sound effect gates on the raw 16-frame `Vint_runcount` mask
  instead of a mis-derived offset constant.
- **CNZ flippers and launchers (Obj85, Obj86):** capture, launch, and pinball-state handoff match
  the ROM, including diagonal captures and the vertical launch input source. Tails landing from the
  vertical launcher runs the ordinary floor reset instead of preserving the rolling bit.
- **CNZ2 miscellany:** the elevator (ObjD5) matches ROM ground-wall and held-input behavior, the
  bumper scan window uses the ROM camera-relative range, slope repel clears `Status_Push` on the
  ROM's frame, and the animation `$FD` switch marker holds the final mapping frame for one call
  before returning to idle, matching `AnimateSprite`.

#### Hill Top and Mystic Cave

- **HTZ art and water:** Hill Top's art supplement over the shared base tileset is applied through
  the same hardcoded-patch path used elsewhere, and the zone no longer reports water.
- **HTZ2 rising lava (Obj30):** the object stays solid through `SolidObject_Always` and
  `DropOnFloor` without gating on the earthquake render flag, bridges sidekick push and interact
  input through its child slot order, and preserves `Status_OnObj` on supported hurt.
- **HTZ2 platforms and Spiker:** platform sag (Obj18) no longer persists from a stale standing bit
  after a rider walks off, and its off-screen lifetime uses the object's saved origin rather than
  its live oscillating position. The Spiker's drill despawns on the ROM on-screen render flag
  instead of a broad X-only lifetime, removing a false hurt.
- **HTZ2 Rexon:** each head seeds its oscillation counter with its head number instead of zero,
  restoring the ROM's staggered wave. The previous collapse pushed the attackable tip head just
  outside the touch band, so the rolling-kill bounce was missed.
- **MCZ platforms and children:** collapsing platforms stay solid during the fragment phase, and
  drawbridges and Crawltons reserve the second object slot the ROM allocates for their multi-sprite
  child. That removed a permanent one-slot skew shifting every later allocation and the slot-gated
  spilled-ring bounce cadence.
- **MCZ2 objects:** the spike-ball and brick anchors (Obj75) key off the ROM anchor rather than the
  moving head, the vine (Obj80) preserves the ROM animation byte through a monitor landing, and
  Obj6A and ObjA3 expose live status bits to sidekick riding and touch code.
- **Crawl contact:** walking into an attacking Crawl routes through the ROM hurt collision flags
  instead of being ignored on non-rolling contact.

#### Oil Ocean and Metropolis

- **OOZ oil surface (Obj07):** the surface executes in its reserved object-RAM band before the
  dynamic level objects, matching the ROM's aliasing of `Oil` onto `WaterSurface1`. It sequences
  submersion, hurt landings, and dead sidekick fall against the ROM routine and follows the ROM move
  lock and logical-input timing on slides.
- **OOZ launchers (Obj48, Obj3D):** ball capture respects the same-pass move order and matches ROM
  routine gating and slot ownership, including debris lifetime and post-break landing radius.
  Launcher-block fragments are removed on the ROM on-screen test in any direction.
- **OOZ springs, spikes, and platforms:** the pressure spring (Obj45) matches ROM compression,
  release, and carry behavior including exact-edge compression, the sliding spike (Obj43) uses
  unsigned travel-span table bytes and the correct hurt category, the spike (Obj36) matches the ROM
  sidekick push-bridge window, and the popping platform (Obj33) covers subpixel preservation, rider
  eligibility, and signed object-control rejection.
- **OOZ badniks:** Aquis (Obj50) chase orientation, shot timing, and projectile spawn match the ROM,
  and its wing is exempt from the shared off-screen unload because it holds its slot for its
  parent's lifetime. Octus (Obj4A) models its falling init as a real multi-frame dispatch, advancing
  its routine only on landing.
- **MTZ cogs (Obj70):** side-contact suppression and grounded push and stop transitions are scoped
  to Obj70's actual SST slot order and standing-bit state, and top-landing collision uses the
  object's full ROM width instead of a generic narrowed one. Staircases, platforms, nuts, buttons,
  and elevators across CPZ and MTZ also use boolean contact latches instead of frame-counter
  comparisons, fixing activation regressions during title cards and multi-sprite updates.
- **MTZ platforms, nuts, and buttons:** the long platform (Obj65) keys stop selection off the ROM
  zone id, models the leader's stale logical-input carry window near the first conveyor stop, and
  lets Tails fall through to a spindash duck. The nut (Obj69) uses the live ROM standing bit, the
  twin stompers (Obj64) keep riders on the pre-move surface, and the button (Obj47) updates before
  same-frame `ButtonVine` consumers.
- **MTZ spiky blocks, monitors, and cylinders:** the spiky block (Obj68) hurts Tails without a
  render-flag gate, matching `Touch_Loop` scanning `collision_flags` directly. Monitor side-entry
  solidity follows ROM post-player timing, and the cylinder (Obj06) resets airborne rolling state on
  capture.
- **MTZ yellow spring walls:** these no longer clear the bounced character's roll-jumping flag. The
  clear lives inside an `if fixBugs` block, and the shipped ROM builds with the bug in place.

#### Sky Chase, Wing Fortress, and Death Egg

- **Tornado (ObjB2):** the plane spends its first executed frame in the ROM's routine-0 entry
  instead of deriving its routine in the constructor, and runs its pilot animation tick during
  normal play and during the title-card leave-loop iterations it stands in for. The pilot is the
  sole art submitter into the Tails bank in these zones when the sidekick object is omitted. Plane
  and ship attachment positions, control-latch timing, and the scripted end jump's input ordering
  now match the ROM as well.
- **WFZ art:** Wing Fortress loads its `ArtKos_WFZ` supplement over the shared `ArtKos_SCZ` base
  tileset, the same hardcoded-patch pattern as Hill Top. Previously the getaway ship and other
  zone-specific foreground art sampled missing tiles.
- **WFZ background:** the ship's thrusters bypass generic off-screen culling and live until the ROM
  X-offset delete point, and the ending background follows the ROM `ScrollBG` chase, advancing at
  most sixteen pixels per frame instead of snapping to the foreground camera. The object that
  patches Plane B on a jump-to-plane trigger now decodes the level layout through the correct
  interleaved 128-byte row format, so its writes reach the intended background cells.
- **WFZ objects and audio:** the wall-turret shot (Obj98) deletes on the ROM's on-screen render flag
  instead of an invented 480-pixel camera margin, eliminating hundreds of spurious shots. The
  propeller's helicopter sound is gated to on-screen play, the rivet plays its explosion sound and
  drops the player through the floor, the belt platform uses its ROM palette line, and the shot-down
  Tornado plays the scatter sound instead of the ring-loss jingle.
- **WFZ ending:** Sonic hangs correctly on the getaway ship, because the Tornado sequence no longer
  re-forces a standing pose over the grabber's hang, and the ship-plating grab fires now that the
  grabber uses the ROM's bit-0 object control rather than bit 7.
- **SCZ and WFZ visuals:** Sky Chase Tornado rider art is restored, stage rings sit in their ROM
  sprite-priority bucket, Wing Fortress object priority bits are applied, and the underside flame
  flicker cadence is corrected.
- **DEZ background and Mecha Sonic:** Plane B's initial vertical origin comes from `Camera_BG_Y_pos`
  instead of a zero default, revealing the intentionally black first background band. Mecha Sonic
  (ObjAF) follows the shipped outer attack loop and no longer spawns a duplicate Eggman transition
  object after defeat.

#### Bosses

- **Boss id lifetime:** `Current_Boss_ID` now has the ROM's per-game lifetime. It is cleared by the
  level-load RAM wipe, not by boss defeat, which the shipped Sonic 2 ROM never does. The engine had
  been doing the opposite, granting or withholding the post-boss `Sonic_LevelBound` extension on the
  wrong levels for all seven boss classes.
- **Boss defeat gating:** defeat explosions and countdowns read `boss_routine` once per frame and
  defer their first countdown decrement to the following frame, matching the ROM's read-once
  convention. That removed extra random draws which desynced later spawns.
- **EHZ2 boss (Obj56):** the boss spends its first executed frame on `Obj56_Init` alone, defers its
  defeat routine by a frame with a direct camera-boundary write, and tests arrival positions before
  moving rather than after.
- **CPZ boss (Obj5D):** defeat dispatch is deferred a frame, pipe and container status-byte
  ownership and container-drop latching follow the ROM bit semantics, and the boss no longer
  overshoots its stop position. It also moves its own parts before its arm and container children.
  During the act 2 retracting pipe the object pass is cut short as the ROM does, where a scratch
  register reused for an id compare truncates the `RunObjects` loop.
- **CPZ boss pump and dripper:** the pump runs a single pass per pipe cycle, since its restart
  branch falls through into deleting the pump head, and the dripper tracks the parent vehicle
  directly rather than the pipe control. The two prior errors cancelled visually but drew an extra
  random number on defeat, which desynced the capsule animals.
- **HTZ2 boss (Obj52):** the boss separates its defeated flag from the active boss id, stages its
  first flee-frame writes to match the ROM handoff row, and stays persistent through its
  camera-widening flee so its camera expansion reaches the capsule.
- **MCZ2 and OOZ2 bosses:** the drill boss (Obj57) matches ROM camera, collision, hurt clearing, and
  debris timing across its escape and defeat paths. The submarine boss (Obj55) matches init and
  surface ordering, laser and wave child lifecycle, vertical clamp timing, and defeated camera
  release, and renders all its ROM child sprites.
- **MTZ3 boss:** first-execution frame, hit reaction and defeat sequencing, and SST slot reservation
  match the ROM, alongside shield-orb orbit, break-away, and burst lifecycle fixes, and the boss's
  camera-opening flee persists through object-window culling.
- **CNZ2 and ARZ2 bosses:** the electric-ball boss (Obj51) matches its proximity trigger, split-ball
  touch positions, camera-boundary flee, ROM mapping frames, and the shared player and sidekick
  boundary sync. The Aquatic Ruin boss (Obj89) matches arrow and pillar contact, sidekick push and
  auto-jump interactions, and camera release.
- **WFZ boss:** the lens renders behind its cover at the ROM sprite priority, the laser beam applies
  collision damage where it had been dead code with no touch response, and the yellow laser walls
  alternate visible and hidden every frame while staying solid.
- **DEZ Death Egg Robot:** the group-animation player spends the ROM's `$C0` end-marker frame before
  reporting a keyframe script complete, which realigned the whole attack-phase clock. The jet stomp
  reads its targeting sensor's reported X one frame after the sensor produces it, matching the
  higher-slot child execution order and restoring the lock-on-then-descend cadence.

#### Title, Level Select, Special Stages, and Ending

- **Title screen and master preview:** the "@ 1992 SEGA" text is back on the title screen and on the
  Sonic 2 preview of the master title, whose emblem also retains the solid green interior behind
  Sonic and Tails. `TitleScreen` stamps the `CopyrightText` words into the
  decoded emblem map at `planeLoc(40,28,26)` before uploading it to Plane A, indexing the standard
  menu font at `ArtTile_ArtNem_FontStuff_TtlScr`; the engine loads that font alongside the emblem
  art and writes the same words. The line uses palette line 0, which the ROM clears at setup and
  only fills when `Obj0E_Sonic_LoadPalette` copies Sonic's palette in at frame 128, so the text
  appears with Sonic rather than fading in with the emblem.
- **Title card:** the wait loop exits on the ROM's own two-part test, the zone-name piece plus the
  art queue, instead of an invented sixty-frame minimum, and the `Obj34_WaitAndGoAway` tail is
  shared between the omitted and displayed presentation paths.
- **Title card object aging:** placed level objects no longer run while the card slides in and holds
  on a re-entry such as a special-stage return. Sonic 2 had inherited an "objects always run"
  default that aged objects like the CPZ Grabber cluster far more than the ROM does.
- **Omitted title-card presentation:** a headless load replays the ROM's leave-loop player animation
  ticks and the gameplay-phase exit tail, so skipping the visual presentation no longer skips real
  art-queue work. The level header's secondary queue entry is serviced across the same window.
- **End of act:** the signpost fires on the shipped unfixed branch, queuing results art on the first
  walk-off frame rather than dozens of frames later, and the slide-in and results-tally lengths come
  from the ROM's own object timings instead of invented duration constants. The signpost art load
  runs from the level-loop tail, submitting its art as a queue replace rather than an append.
- **Post-act fade:** the fade no longer runs as live gameplay. `Level_Inactive_flag` stops the
  object pass and art submission during the fade to black, matching `Level_MainLoop`.
- **Special-stage return:** the return re-establishes the sidekick's level boundaries so Tails' kill
  plane survives, resets the level frame counter at title-card release, and re-runs `LevelSizeLoad`
  so the player art dedup registers and level-only object slots reset.
- **Special stage setup:** the active team resolves through the standard configuration keys instead
  of an unreachable literal, so Sonic and Tails team play spawns correctly. The main loop's startup
  boundary and exit fade length match the ROM, and entry fade rows are no longer treated as
  art-queue service boundaries.
- **Special stage rendering:** shadow and body draw passes and the player-ordering merge sort use
  the ROM's descending depth-priority bucket order and break ties toward the main character,
  restoring correct occlusion through character swaps.
- **Special stage art and HUD:** Tails renders through his own frame mappings and reverse art
  selection, and the HUD reproduces independent Sonic and Tails ring counts plus the combined total
  and the overseas `TAILS` label art. Startup stays hidden behind an opaque transition until the
  reveal boundary, where music and fade begin together.
- **Special stage lag model:** the flat lag compensator is replaced by a deterministic model keyed
  by track segment type and speed factor, removing the visible lag-frame animation and the old
  accumulator and its function-key toggles. The overlay remains available read-only.
- **Special stage state and cadence:** the player-animation timer, refresh-gated rings-to-go value,
  the shared swap-positions flag, per-player ring counts, the emerald object's pass order and
  pause-only control gate, half-open ring and bomb collision windows, input copy timing, angular
  motion sign truncation, and player spawn gating all follow their native object ticks. Star-post
  stars orbit and collide using the ROM sine table and touch geometry rather than floating-point
  trigonometry, and the results screen is decoupled from the object system.

#### Player Physics and Collision

- **Super Sonic transformation:** the transformation activates just past the jump apex rather than
  on the way up, because the ROM's test reads the high byte of a big-endian word, and Super speeds
  install on the transform frame itself instead of after the animation finishes. The freeze ends
  when the palette fade ends, driven by the palette cycler clearing `obj_control`, and Tails no
  longer transforms at all, since Sonic 2 has no Super Tails.
- **Super Sonic animation and stars:** the alternate bright animation frames, and the art transfers
  they trigger, step in on one frame in four as `SAnim_SuperWalk` does. The stars (Obj7E) use a
  fixed SST slot rather than a dynamic-pool level-object slot, and spindash release uses the ROM
  Super speed table.
- **Landed floor angles:** both characters copy `Primary_Angle` and `Secondary_Angle` into their
  tilt fields every frame rather than only when grounded, fixing a false ledge-balance read after
  landing.
- **Impatient-wait input gate:** once Sonic's wait animation reaches its blink threshold, held
  directions play the blink and get-up animations while grounded updates are skipped, matching the
  ROM's three-frame input lag. The gate is Sonic 2 only and scoped to non-riding stands.
- **Riding push preservation:** a later preserving solid slot can retain a push status established
  by an earlier slot in the same object-execution pass, restoring the visibility window that folded
  CPZ staircase pieces and the ARZ boss arrow rely on. Forced-roll tunnels no longer show spindash
  charge visuals, which came from the shared pinball and spindash byte alias.
- **Ring collection window:** stage rings are collected through the ROM's ring-array window rather
  than the wider chunk-aligned object-placement window, so a sidekick trailing far behind the camera
  no longer banks rings the ROM's window has already scrolled past.
- **Invincibility stars (Obj35):** the effect was rewritten against the disassembly. Star 0 orbits
  at the player's current position with fast rotation while stars 1 to 3 trail behind through a
  position history buffer, each rendering two sub-sprites 180 degrees apart, with a corrected orbit
  offset table, per-star animation tables, and direction-aware rotation.

#### Objects and Badniks

- **Random number parity:** the seed is cleared on every act load, matching the ROM's level RAM
  wipe. The engine had carried the seed across acts, shifting every consumer by the previous act's
  draw count.
- **Egg Prison break delay:** the countdown runs the ROM's thirty passes, not twenty-nine, because
  it branches on zero as well as positive. That realigned every downstream random spawn decision the
  countdown gates and removed two one-frame compensations built around it.
- **Egg Prison pieces:** the button recomputes its eight-pixel depression from the current standing
  bit every pass instead of latching it forever, and the capsule, button, lock, and broken halves
  keep their own solid push and standing bits instead of sharing one latch.
- **Fixed air countdown (Obj0A):** the countdown stops ticking while its bound player is in the dead
  routine, matching the ROM's early return, instead of drawing random numbers while a sidekick lies
  dead underwater.
- **Masher rewind:** capture and restore preserve complete fixed-point motion state, including
  subpixel phase, jump origin, and velocities, so a forced reconstruction resumes on the exact
  trajectory.
- **Animation parity across the level roster:** per-object exact-edge solid widths, push, hurt, and
  landing animation ownership, and boss and parent-slot X coordinate separation were corrected
  across the zone fleet.

#### Sidekick Tails

- **Tails' tails (Obj05):** the trailing sprite dispatches in the post-dynamic-object fixed-slot
  pass, its actual SST location, rather than inside the sidekick's own update. It latches its
  animation from the ROM's derived selection index, including the parent-pushing override applied
  before the change-detection latch, tracks the ROM's read-then-increment split between animation
  and mapping frame, and latches its mapping bank and render flips only at the ROM's write point.
- **Art dedup ownership:** the player art dedup state is keyed by character rather than by team
  slot, matching the ROM. A CPU sidekick's raw sprite code never matched the character-keyed owner,
  so it had been running with no art owner at all.
- **Sidekick collision path:** a CPU sidekick inherits the leader's collision-path bit pair at level
  load, so Tails probes the same 16 by 16 collision array Sonic does after a star post or
  special-stage return restores a non-default path.
- **Sidekick chaining and control counter:** trailing sidekicks keep following the root leader while
  a freshly landed direct sidekick warms its delayed follow-history ring, and fly-in completion no
  longer carries the approach timer into normal control as a false manual-control pause. The CPU
  sidekick models `Tails_control_counter` strictly as the manual control timer.
- **Push-bypass auto-jump:** the sidekick's push-bypass route to the auto-jump gate fires on its
  cadence frame regardless of the jumping latch, matching the ROM branch that skips the latch check
  when Tails is pushing and the delayed leader is not. A grounded sidekick pushing a solid object
  now re-jumps as the ROM does.
- **Sidekick boundaries:** the sidekick's horizontal bounds keep following the camera after a boss
  dies, through the level event routine's per-frame re-copy.

### Audio

Audio is the largest single area of 0.6. The SMPS driver, the YM2612 FM path, the PSG and the
DAC/PCM path were re-derived against the three shipped drivers and are now held in place by
committed driver-parity oracles covering music, sound effects, fades, extra-life restoration and
request scheduling. Full parity and human listening sign-off remain open.

#### Sound Driver (SMPS)

- **Driver service cadence follows the shipped V-blank loops:** music tracks retain the shipped
  service cadence at tempo holds instead of skipping whole V-blank intervals, tempo accumulators
  start and change with the correct game-specific phase, Sonic 2 performs its music-only PAL repeat
  every fifth interval, and locked-on Sonic 3 & Knuckles repeats the full music and effect update on
  its sixth-interval PAL cadence. Driver state, fade delay and fade-out ownership now live with the
  sound session rather than being reconstructed by callers, and requests are consumed at the point
  in the service the ROM consumes them.
- **Shipped driver quirks preserved:** Sonic 2 retains the bugged `$90` spindash-release FM5
  transpose rather than the bug-fixed value and schedules DPCM on the driver's documented two-sample
  cadence, while S3K modulation envelopes apply retail `$85` to `$FF` bytes as signed pitch deltas
  instead of freezing below zero and reproduce the shipped operand read for `$82` and `$84`.
- **S3K driver command routing:** the shipped stop, fade, effect-stop, speed-shoes and PSG-silence
  commands route through the session-owned physical device with their exact write counts and
  transactions, the effect-stop command releases logical ownership without erasing music or raw
  sample state, and the `E4` meta command performs the shipped conditional walk over active effect
  tracks. Boot uses the source-verified program while Sonic 1 and 2 keep their own.
- **S3K music fade and song load:** S3K fades run the 240 frames its driver specifies rather than
  the 120 frames shared with Sonic 1 and 2, so object-triggered fades no longer land on silence part
  way through. Loading a song performs the stereo reset on the shared drum and FM6 channel the ROM
  performs at every load, and clears retained tempo controls so speed-shoes acceleration cannot
  carry into special-stage music.
- **Sonic 1 fade, song-end and jingle restore:** a fade takes its first step one frame later,
  matching the ROM's order of stepping an in-progress fade before applying new requests. Music
  timing keeps ticking once a song's tracks finish, and a tied note continues stepping its volume
  envelope instead of freezing a PSG channel's shimmer. Returning from a 1-up or similar jingle now
  silences and rests channels the way each game's sound program does before fading music back in,
  removing several seconds of distorted music. In Sonic 1 and Sonic 2 the same restore also reloads
  every FM channel's instrument, as the original does, because the jingle left its own instruments
  on the chip; the engine had done that only on a path the game no longer uses, so the level music
  came back on the jingle's instruments until each channel next changed by itself. A second extra
  life during a restore's fade in no longer stacks a second attenuation on the first.
- **Speed shoes tempo:** the tempo speed-up works again in all three games after immutable
  presentation assets started retaining driver cadence mode, and the tempo drop on expiry happens on
  the same frame as the speed restoration rather than one driver update late.
- **Spindash rev pitch:** the rev pitch in Sonic 2 and S3K comes entirely from the sound program's
  own script and index ladder. An invented engine-side playback-speed stretch driven by the charge
  counter, a value the sound program never sees, is gone.
- **Sonic 2 request routing:** audio requests traverse the shipped mailbox and queue order, level
  music starts on the shipped level-entry cadence, and ring and shield monitor sounds route through
  the ROM's music-request slot rather than the effect queue, matching the documented quirk without
  changing what is audible.
  Native SFX track stops clear the request priority latch, so lower-priority effects such as
  Point Pokey's Casino Bonus and the ARZ splash remain audible after other effects end.
- **Drowning recovery and substituted music:** surfacing from the drowning countdown resumes the
  track the ROM specifies, and invincibility, Super and Hyper forms, and boss fights each keep their
  own per-game music substitution instead of being cut off by the zone theme.
- **Driver data integrity:** the supported retail catalogs reject malformed ROM framing instead of
  guessing past it, verified by ROM-backed sweeps over every declared song, effect and DAC catalog.
  Replacing a base ROM, audio profile or donor source publishes its loader, DAC, configuration and
  catalog generation as one transaction, rejecting reentrant publication and restoring the prior
  configuration on failure. The `FF 01`, `FF 02` and `FF 03` meta commands were audited across every
  supported stream, found unreachable, and documented as such rather than implied.

#### Fast FM Core and Chip Emulation

- **The FM synthesiser runs on a ported Nuked-OPN2 core:** the cycle-accurate port replaces the
  older table-driven core and is pinned sample-for-sample against the reference C build by register
  scripts. It was later optimised through construction-preserving changes only, and its snapshot
  state gained value equality so two identical captures compare equal instead of diverging on
  identity. Its phase calculation later gained a small lookup built once at initialisation from the
  retained original arithmetic, skipping zero pitch-modulation work without skipping chip clocks.
- **A clean-room fast FM core is the new default:** `FastYm2612Dsp` is a register-level YM2612
  written from public documentation and selected by `audio.fmCore=fast`, with `accurate` still
  choosing the Java Nuked core and an explicit `accurate` choice surviving reload. It passes every
  supported register script and cuts audio render time from roughly 0.9 ms to under 0.2 ms per frame
  with matching trajectory digests. LFO coverage, channel-3 special mode, feedback into summed
  modulators, some SSG-EG repeat modes and very low attack rates remain documented gaps, and
  physical reference captures stay on the accurate core. `VirtualSynthesizer` picks its chip through
  an `FmChip` seam, and `TraceBenchmarkTool --fm-core` renders the same content through both cores.
- **Fast-core sampling correctness:** operator sampling boundaries for pitch changes, LFO clock and
  pitch modulation, discrete amplitude steps and output delay, scheduled pitch, level and key
  changes at sampling boundaries, the shared DAC output timeline including partial samples, envelope
  operator sampling delay, per-operator frequency-write boundaries, and modulator phase continuity
  across pitch changes all now reproduce the reference core. Timers A and B advance at one and
  sixteen internal frames per unit, correcting a threefold slowdown, and a pending CSM key-off
  finishes when software stops its timer instead of leaving a stuck note.
- **Fast-core rewind and diagnostics:** snapshots copy DSP state defensively on construction and
  access, and register diagnostics preserve both chip banks and report elapsed internal cycles at
  frame boundaries, including after a rewind or a transaction rollback.
- **FM write pacing:** writes are paced by the chip's 34 internal-cycle busy window rather than 13,
  fixing key-ons that were being lost on driver logs in all three games.
- **Voice upload and slot permutation:** the chip's register-slot permutation is applied at the
  register boundary only, not a second time inside the FM algorithm graph, restoring correct timbre
  for asymmetric voices such as the Sonic 1 badnik explosion. Sonic 2 and S3K upload voices using
  their shipped register tables and write order, and Sonic 1 FM effects no longer clear chip
  envelope state or upload into a channel music still owns.

#### PSG and DAC

- **The PSG core is a clean-room SN76489:** rewritten from a public hardware specification, it
  models the Sega-integrated 16-bit LFSR, the period-0 and period-1 constant-high rule the driver
  relies on, tone-2-linked noise, the latch and data protocol, and the attenuator ladder. The two
  core rewrites shifted the FM to PSG balance by 8.3 dB, so a documented preamp calibration restores
  parity.
- **PSG noise always clocks at the hardware rate:** `audio.psgNoiseShiftEveryToggle` is removed and
  the LFSR follows the one-shift-per-rising-edge rule. The old default was a likely cause of
  wrong-sounding S3K noise effects such as the splash, the insta-shield and the collapsing bridge. A
  configuration that still sets the key is ignored with a warning.
- **DAC interpolation no longer stalls the clock:** `audio.dacInterpolate` had queued its synthetic
  write into the real Z80 sample-cadence slot, playing samples roughly 1.7 times too slow, about 9.5
  semitones flat. It now writes only when the bus is idle, and it defaults to off. Sonic 2 playback
  also uses the retail 295 Z80-cycle loop budget per delivered byte rather than 288.
- **S3K PSG envelope and write order:** volume, envelope and frequency-pair writes preserve the
  original two-byte transactions and command order, a fresh envelope-less note emits one volume tail
  rather than two, and track-stop behaviour matches the shipped driver.

#### Sound Effects and Music Ownership

- **Effect admission and channel ownership are modelled per driver:** admission reserves channels at
  the point the driver reserves them, release follows each driver's own rules, and S3K services
  effects before music so a completed effect frees its channels before the same interval's music
  update.
- **S3K admission sends the retail noise-silence write in header order:** fixed channel-RAM service
  order is preserved, and FM3 mode is restored before its music voice once an effect ends.
- **Four dropped driver settings, and the shared noise generator:** three S3K DAC and
  frequency-order settings and one Sonic 1 PSG-silence setting were silently discarded during sound
  preparation. A guard now checks every setting survives preparation, and the collapsing-scenery
  ring-out fade is audible over playing music again. S3K collapsing scenery and the spindash release
  also hand the shared noise generator to the effect's third tone track instead of muting it on the
  effect's first update, fixing a buzzing spindash release.
- **S3K 1-up effect suppression:** new effects are discarded during the 1-up jingle without
  advancing ring stereo alternation, and are admitted again once music restoration begins.
- **Sonic 1 and Sonic 2 1-up effect suppression:** new effects are refused for the same span the
  original refuses them, through the jingle and the level music's fade back in, and Sonic 1 also
  silences the effects already playing when the jingle starts. The block lifts when the fade in
  completes or when an ordinary song replaces the jingle, and it survives a rewind and restore mid
  fade. Previously only Sonic 3 & Knuckles suppressed them, so rings and jumps played over the
  jingle and the fade.
- **Special-stage rings alternate speakers in S3K:** raw ring effect ids sent directly by the Blue
  Sphere stage and by the Mega Chopper receive the same left and right alternation the driver
  applies. A blue-sphere contact no longer loses its request when the animation queue is full, and
  FM effect completion preserves the music rest bit.
- **Sound effects take their turn correctly:** the Sonic 1 waterfall waits for the FM4 channel to
  free instead of seizing it, and plays with its own instrument rather than the music's. A broken
  Sonic 2 monitor's explosion sound plays from the explosion object's own first turn rather than the
  monitor's collision frame, one frame later as the ROM does.
- **All three SEGA chants come out of the emulated sound chip**, sharing the DAC and mixer gain
  with music samples. S1 and S2 use their ROM sample-loop cadence instead of the legacy standalone
  PCM gain; each driver retains its own DAC completion behavior. S3K matches the original cutoff
  behavior. Skipping its intro with Start no longer silences the title screen, because the stop
  command tied to the chant is gated on the chant still playing.

#### Presentation Audio, Capture, and Configuration

- **Unified presentation audio:** SMPS, WAV and PCM effects, and raw SEGA PCM commands all resolve
  through one composite, allocation-free presentation voice with unified voice snapshots,
  deterministic command ordering, phase-exact non-consuming capture taps, and full rewind and
  reverse-playback support. Live recording and offline trace capture take the same packets. The
  standalone sound test marshals interactive commands and cleanup to its producer owner executor.
  Shutdown waits are bounded while pending cleanup remains queued.
- **Presentation rebuilds no longer silence the game:** the title-to-gameplay mode reset recreates
  the backend-owned presentation sink instead of letting enabled audio drop after the first reset,
  and the pre-game master title emits its own navigate, confirm and error cues independently of the
  selected game ROM.
- **The live-recording audio lease is rebound, not retired,** across every presentation rebuild that
  is not a genuine teardown, fixing recordings that degraded to phase-correct silence behind a
  single logged warning. A lease is never carried across a sample-rate change, releases refused for
  running off the producer's owner thread no longer orphan it, and reverse-release during rewind
  completes even when a later step fails instead of leaking recreated voices.
- **PCM buffer priming and pause silence:** speaker playback again primes and maintains three
  1,024-frame buffers instead of queuing one per update, which had left roughly 21 ms of audio ahead
  and let ordinary frame jitter starve and restart the source, producing audible clicks. Pausing no
  longer lets silent frames pile up in the queue while the source is halted. Triggering a sound no
  longer deep-copies its cached, already-immutable sequence data, removing a burst allocation that
  landed on exactly the frame a sound started.
- **Licence notices ship with every build:** `NOTICE.md`, `LICENSE`, the `LICENSES/` directory and
  `CREDITS.md` are included in every jar and platform release archive, covering the Nuked OPN2 core.

#### Driver Parity Oracles and Audio Tooling

- **Committed driver-parity oracles:** per-service driver-state and chip write-stream references
  captured from the emulator cover the Sonic 1 sound test and gameplay recordings with per-song
  windows over a complete run, Sonic 2 driver-state and request windows including a 1-up window, and
  the S3K intro. Every matching oracle is a hard assertion, and each engine fix cites the driver
  routine it models.
- **Oracle boundary corrections:** the S3K oracle enforces its Z80-only ownership boundary and
  compares both fade counters, Sonic 2's oracle reuses production initialisation programs instead of
  its own register sequence, and completed-service sampling and mailbox request-consumption timing
  were corrected without changing production behaviour. The live fade path now performs side effects
  that only oracle adapters had supplied, namely immediate S3K PSG silence and Sonic 1 effect stops
  with speed-up clear.
- **Sonic 1 matches the shipped driver:** effects match the committed reference across the full
  Green Hill cycle and the sound-test corpus, and music playback matches the shipped `FixBugs=0`
  driver byte-for-byte over a complete reference cycle.
- **Audio evidence tooling:** an opt-in path captures dispatched chip writes, FM comparison captures
  prove their replay bounds instead of assuming them, and a Green Hill audio timeline comparator
  with a corrected causal boundary between raw ROM requests and resolved driver admissions runs
  against a deterministic emulator audio observer core built reproducibly from pinned inputs.

### Trace Replay and Hardware Timing

Trace replay compares engine state against per-frame recordings taken from the real ROM. Trace data
stays comparison-only: nothing in this release hydrates gameplay from a recorded row.

#### Recorders and Trace Data

- **Native recorder migration is complete:** the native headless harness records full canonical
  traces for all three games and every recorder mode, covering level, complete-run and run-mode
  captures with special-stage detours and the three S3K character profiles. Each mode is gated by a
  ROM-backed differential test proving byte-identical output against the existing Lua fixtures. The
  S3K recorder also attributes exact queue retirements, module scans and direct-count observations
  to the correct ROM boundary, failing closed on malformed, stale or ambiguous transitions.
- **The Sonic 2 run recorder survives in-level reloads:** deaths, star-post restarts, time-overs and
  transitions are recorded as new manifest transition kinds instead of ending the run at the first
  reload.
- **S3K recorder address corrections:** the V-blank word is read from the free-running low word
  rather than the nearly static life count, and the standard recorder's frame counter from the level
  frame counter rather than a dead-zero debug field. Fixtures were regenerated, and replay bootstrap
  seeds the level frame counter from the row before the first driven row to match that field's
  previous-completed-frame contract.
- **S3K bonus-stage arm counter:** the complete-run recorder captures the free-running
  `V_int_run_count` at bonus-segment arm time and publishes it in segment metadata, so replay primes
  bonus-stage runtimes from recorded data instead of a session-local approximation.
- **New comparison-only streams:** Sonic 1 gained a global oscillation bitfield event and two
  extensions to its per-object proximity event carrying counter, timer, sub-state, secondary routine
  and accumulator words. S3K gained an event tracking the Angel Island background redraw routine and
  camera release. Per-frame life counts were added across all five gameplay recorders, and
  recordings now carry the ROM animation id and mapping frame for player and sidekick, compared
  independently of physics under `-Dtrace.verification=physics|animation|all`.
- **Trace fleet regenerated:** the reproducible fleet was rebuilt on the frozen headless emulator
  build with richer per-frame diagnostics, and a large curated Knuckles complete-super-emerald run
  was added as compressed segments with reviewed hashes. Every recorder batch now launches through
  one hidden, no-audio, no-render launcher that hides every emulator-owned startup window.

#### Schema and Hardware-Timing Contract

- **Schema 5 is the only accepted contract:** trace loading accepts only schema-5 metadata and its
  fixed level-row layout. Recorder provenance no longer selects behaviour, and hardware-timing
  authority is determined solely by the presence of the strict stream.
- **Hardware-timing failures are comparison errors, not aborts:** a dropped or unmatched completion
  edge, and a recorded submission still open when a run closes, are reported on their own row with
  full evidence, since in a diverged run both are downstream symptoms. A diagnosed dropped edge
  names whether it is an admission failure or a same-identity mismatch.
- **Native fallback no longer offsets later ordinals:** an arm released through native fallback,
  because the recorded row authority holds no row for it, stops permanently shifting every later
  ordinal, and recorded readiness is scoped to the row span the stream actually covers, fixing a
  deadlock on an unrepresented span. Crossing an uncompared span can advance the shared ordinal
  cursor through an optional per-run interstitial sidecar, proved on both ends before it moves and
  releasing nothing itself.
- **Segment membership is declared, not inferred:** the coordinator no longer derives membership
  from the shared movie cursor, which free-runs through unrecorded choreography; each run drive
  declares its own segment entry and exit.
- **The S3K title card no longer hangs on art queued in an unrecorded stretch:** the timing ledger
  runs such work on the same hardware budget ordinary play would give it, instead of waiting on a
  completion the recorder never wrote down. Work queued while the recording is watching is
  unaffected.
- **Load-queue diagnostics:** recordings can opt into frame-level physical load-queue diagnostics
  for the Sonic 1 and 2 Nemesis queues and the S3K queues, reported with zero tolerance ahead of
  downstream symptoms and never hydrated into gameplay. Sonic 1 Nemesis arming can be
  released by recorded timing as ordinary submitted work, carrying no payload because patterns are
  still decompressed natively.
- **Deterministic load-time simulation for ordinary play:** `gameplay.loadTimeSimulation` selects a
  load-time model, with measured S3K fingerprints plus an estimator for unknown jobs. Trace replay
  keeps its own independent recorded authority, unaffected by the setting.

#### Replay Harness and Run Chains

- **A multi-stage chain-replay harness** puts every multi-segment run onto one manifest-driven,
  data-mapped path with no hardcoded segment counts and no zone or route carve-outs, including
  boundary assertion-mode derivation, a manifest-derived step cap that raises an explicit exception
  instead of hanging, and comparison-only special-stage interiors. All three run chains now execute
  end to end instead of silently skipping.
- **Chain bootstrap fixes:** the drive loop ticks the fade manager before each engine step, since a
  headless run never advanced a fade a boundary handler started and hung on mode transitions, and
  the replay driver performs the same one-time ground, angle and sensor snap the standalone fixture
  always did, in the correct order relative to S3K's post-title-card player-state calls. Multi-stage
  runs also reject duplicate segment directories, the Sonic 1 complete-run recorder assigns unique
  tokens to repeated level arms, and the chain driver rebinds across same-level bridge segments.
- **Run-boundary segment walks count rows, not frames:** a walk no longer stops short of its
  declared rows when a transition freeze spends steps consuming none, and a destination is no longer
  admitted early on a fluke frame-budget surplus.
- **Segment tail ownership:** a level-to-level load's tail rows already report the destination, so
  ownership extends past the recorded level-loop rows using that same predicate, and a chain segment
  keeps production ownership across the S3K seamless in-level act advance.
- **Transition-gap correctness:** the opening ledger snapshot matches the boundary batch it was
  measured against, a destination adopts the row that already ran during its gap instead of skipping
  it, the source-level latch moved off a static field shared across unrelated runs, and gap-edge
  stamps come from rows that actually passed rather than a frozen playback cursor.
- **Whole-run replay boundaries:** a transition gap no longer swallows the source level's last main
  loop iteration, the V-blank clock is carried across a special stage's results presentation bridge
  instead of being frozen, a run can cross from a special-stage return bridge back into its own act,
  and a run whose movie spans its first level's load publishes the player transfer that load stages.
  The pre-comparison warmup preserves the one stateful animated-tile accumulator the ROM has no
  equivalent for, and Sonic 2 no longer runs level-only fixed object slots during the title card.
- **Special-stage identity and pacing:** a run's special-stage segment identity is read from the
  stage the engine is presenting rather than the live provider, which had reset on entering results
  and misattributed ownership for every stage but the first. Object passes are paced from the
  recorded object stream rather than one pass per admitted row.
- **Sonic 2 special-stage replay:** a new frame and metadata parser, a comparison-state accessor,
  exact per-pass input derivation, sign-extended track coordinates, and a stage-finish terminal-pass
  boundary took the harness from thousands of errors to green, closing the Sonic 1 and 2 replay
  fleet with no engine change needed for the harness pieces.
- **`ADVANCE_ONLY` rows suppress gameplay consistently** across forward playback, visual rewind, and
  headless, capture and live replay, while still consuming and latching the recorded controller
  snapshot and action edge. This removed several non-ROM object updates that had been shifting the
  whole downstream object phase.
- **Trace replay memory:** generic aux events use a pooled, interned field map instead of a fresh
  map and strings per event, cutting one large segment's retained memory from 643 MB to 171 MB and
  letting every S3K complete-run segment replay deterministically at a 1 GB heap. This exposed an
  earlier clean pass that had actually been a truncated prefix ended by memory exhaustion.

#### Visual Replay, Reports, and Tooling

- **Whole-run visual replay shares the headless model:** the master-title visual trace player and
  picker use the same represented-row driver, admission receipts and art-timing model as headless
  replay across level and act loads, bonus and special stages, and returns, instead of a separate
  visual-only model. A dedicated harness drives a whole run through the production visual session
  owners headlessly, and failures detach cleanly back to the title screen with diagnostics on
  screen.
- **Trace playback presentation:** the visual trace HUD owns the playback transport display in place
  of the legacy debug panel, playback can fast-forward on a one to five times ladder with matching
  audio pitch and a tear effect, and a special-stage trace plays back at authentic lag-paced speed
  through a parallel session branch with a profile-aware catalog label.
- **Ring count is actually compared:** both the per-act and whole-run comparisons had been
  discarding a real computed value through a formatter that hardcoded a sentinel, silently passing
  every ring check. Enabling it exposed pre-existing ring divergences across Sonic 1 and 2 acts.
- **Object state promoted from parsed to compared:** the Sonic 2 tornado and the Casino Night slot
  machine ROM state are compared rather than merely parsed, surfacing several previously invisible
  divergences.
- **Reports are auditable and always written:** chain reports publish a per-group error breakdown
  for physics, animation and bootstrap that must sum to the flat total, and a run that aborts
  mid-way with a clean comparison so far still writes its report instead of leaving nothing, which
  had been indistinguishable from a run that never started. Diagnostics default to divergent-column
  output, and tolerated sidekick status-byte mismatches stay visible behind a marker so push and
  facing noise remains inspectable.
- **Comparison scope discipline:** gap and tail comparison no longer treats a movie-row stamp as
  engine evidence inside a span the fixture declares unrepresented, a lag row inside an uncompared
  interior is stepped as a lag row even under a blocking palette fade, and split-row camera
  diagnostics sample at the visible instant only when a gameplay row is followed by an unchanged
  V-blank-only row with the same counter.
- **Catalog, capture and release tooling:** capture rejects multi-segment runs up front instead of
  failing part way, run discovery excludes synthetic fixture subtrees, catalog validation uses
  compact run-segment descriptors, and release trace validation compares fresh candidate evidence
  against a reviewed baseline with an explicit source-backed skip policy that fails closed on
  anything unclassified.
- **Trace tooling owner:** the recording, emulator, and probe tooling described under Architecture
  and Runtime now lives in the TraceChaser submodule.

### Test and Quality

- **Structural guards profile:** a `guards` Maven profile runs the structural guard tests on their
  own, source-only and without a ROM. Branch pushes run only the `smoke` profile; the full suite
  and the guards run on pull requests, manual dispatch, and release validation, so both are run
  locally before delivery. The eleven guards that had gone red while no job ran them are fixed,
  mostly by routing objects through existing shared event and service helpers, with anti-growth
  budgets re-baselined to their true current values.
- **A rewind-coverage guard** flags spawnable-object helper fields carrying mutable frame-varying
  state with no rewind codec, using the same captured predicate as the compact capturer. It was then
  extended to per-module service state, surfacing four previously invisible gaps.
- **An object-execution loop guard** compares the Sonic 1 counter-based loop against the Sonic 2 and
  S3K loops over slot-lifecycle vocabulary and fails when a mechanism exists in one and not the
  other, with an explicit ROM-cited allowlist.
- **Fork isolation:** three S3K object-registry test classes had been passing only because no
  earlier test in their fork happened to leave a level loaded. They now establish their zone-set
  precondition rather than inheriting it.
- **Returned-segment assertions:** run chains assert the returned level segment's comparator error
  count they already computed, instead of silently discarding tens of thousands of errors at that
  seam.
- **Fixture correctness:** two trace-harness tests were rejecting themselves over incorrectly built
  derived fixtures, a standalone special-stage capture that labelled every recorded object pass one
  frame early was regenerated, and a new guard asserts every recorded object pass's input-sample
  frame lands on a frame that was actually polled. Several run segments that were replayed but never
  compared now have their own tests, landed failing because they disclose real, previously invisible
  divergences.
- ROM-backed source-data checks no longer read the optional local disassembly trees, and a tooling
  guard rejects new executable test dependencies on them.
- S3K trace-replay test classes moved to a nested naming pattern, one file per zone or stage with a
  nested class per character set and recorded segment, so a report names the character set
  unambiguously. Release-scope selection uses tags for converted classes.
- **Temp-file leaks closed:** tests and the no-argument input handler no longer leak into the system
  temp directory, Maven points the test JVM's temp directory at a bounded location cleaned by `mvn
  clean`, and live recording's lossless intermediate writes beside its output file. A missing ROM
  configuration now fails before the ROM is opened, so headless loaders degrade through their
  existing exception handling instead of flooding logs with stack traces.
- Push validation recognises merge commits again, after a nested content check overwrote the shell
  state used to count parents. True merges stay exempt from per-commit trailer checks while their
  introduced content is still validated.
- The ordinary suite runs on develop and master pushes again, after fixing a Casino Night visual
  capture pre-step desync, rebuilding Marble Garden parachute sessions per test, and removing a
  stale unwired exclude file that had hidden a real failure.

### Documentation and Agent Workflow

- Per-game rule placement guidance documents the smallest-owner path for ROM divergences, namely
  typed `GameRules` for shared runtime rules and existing providers, profiles, registries and object
  hooks for narrower behaviour, instead of adding new broad rule bags.
- The configuration guide corrects its capture key-binding examples, the per-binding modifier table
  and the treatment of a separator-only binding, and documents `capture.scale` as trace-capture
  only.
- The bundled configuration template is written to `config.yaml.example` beside the player's own
  configuration on every run, without touching the player's file.
- The audio and trace status logs record the measured per-oracle boundaries and the current
  comparison state of each recorded run.

### Reverted Work

- The SMPS playback authenticity programme of 2026-08-21 was withdrawn on 2026-08-27 in commit
  `b4c8fbd8a` and deferred to 0.7, because its fixes produced audible regressions the suite could
  not detect. It covered zone music resumption after temporary sequences, extra-life priority and
  fade edge cases, repeated boss explosions, the standalone sound test, FM effect track-stop decay,
  a band-limited PSG renderer, reference chip defaults, PCM boundary localisation, a modulated PSG
  chirp, FM5 instrument inheritance, DAC after pause, the FM effect first-note bus sequence, SEGA
  PCM ownership, the pause chip protocol, the 1-up jingle lifecycle, fade channel rules, and effect
  release and retention rules.
- Audio work dated after that revert was derived afresh against the committed driver-parity
  oracles rather than re-applied from the withdrawn branch.
- The Nuked-OPN2 port landed unwired and was displaced as the default by the clean-room fast core
  later in the same cycle. It remains available as the `accurate` choice, so this is sequencing
  rather than a regression.
- The test-session coordinator, its managed allocation and capacity gating, session-owned report
  roots, session-wrapped certifying builds, and per-fork native isolation under session ownership
  were all removed when builds moved back to direct Maven.
- A trace-replay suppression of the S3K Gumball machine's own frame-zero random-seed reset was
  reverted for violating the comparison-only invariant, leaving the gap between the engine's
  session-local counter and the true hardware counter as a documented discrepancy.
