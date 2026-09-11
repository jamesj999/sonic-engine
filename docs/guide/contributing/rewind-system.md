# Rewind System

The rewind system lets a gameplay session return to an earlier frame by restoring a
stored keyframe and replaying deterministic inputs forward to the requested frame.
It was built as a debugger and trace-validation tool first, and the same
gameplay-scoped primitives now also support optional live in-game rewind.

## Where It Can Be Used

Today rewind is safe to use in gameplay-scoped sessions that install a
`PlaybackController` through `GameplayModeContext.installPlaybackController(...)`.
Production use cases are visual Trace Test Mode, headless validation, and
config-gated live gameplay rewind.

Good uses:

- Trace visualisation and trace replay tooling.
- Headless tests that need to seek backward and replay a deterministic segment.
- Live gameplay rewind when `rewind.liveEnabled` is true.
- Rewind determinism debugging for player, sidekick, object, ring, level, palette,
  parallax, and zone-runtime state.
- Presentation debugging for reverse audio and graphical fades during live and
  visual trace rewind.

Avoid using it for:

- Menu/title/data-select state. Rewind is owned by `GameplayModeContext`, not the
  global application shell.
- Rewinding across level, act, or mode boundaries. Those events reset the rewind
  buffer by design.
- Editor undo/redo. The level editor uses its own `MutableLevel` snapshot and command
  history semantics.

## User-Facing Behaviour

### Trace Test Mode

Visual Trace Test Mode installs the rewind controller automatically after a trace
launches. To use it:

1. Enable Trace Test Mode in `config.yaml`:

   ```yaml
   startup:
     masterTitleScreen: true
   debug:
     testMode:
       enabled: true
       catalogDir: "src/test/resources/traces"
   ```

2. Launch the engine and choose a trace from the picker with `Enter`.
3. Hold `debug.traceRewind.key` while the trace is running. The default key is `R`.

The HUD shows `Hold R Rewind` while rewind is available and changes to `REWIND <frame>`
while the key is held. Releasing the key resumes the BK2-driven replay from the
restored frame. `Enter` still pauses/resumes the trace, `Q` still frame-steps while
paused, and `Esc` exits the trace back to the picker.

While the key is held, trace rewind also enters reverse presentation for both
audio and fades. Audio drains the PCM history ring backward and keeps updating
while the trace frame is consumed by rewind. The graphical fade pass suppresses
normal display-driven fade advancement and renders the restored fade snapshot,
so fade-from-black/fade-to-black transitions move with the rewound frame instead
of continuing forward.

#### Fast-forward

While the trace is playing — not paused, not rewinding — the `LEFT`/`RIGHT` bindings
(arrow keys by default) walk a playback speed ladder: `Right` steps up through
1x, 1.5x, 2x, 3x, 5x and `Left` steps back down. Those same keys keep their paused-only
meaning for the camera-focus cycle, so the two never contend.

The current rate shows as `Rate: < 1.5x >` in the trace HUD's transport block, pinned
right-aligned to the top-right corner alongside the movie name, mode, and frame counter.
That block replaces the legacy `== PLAYBACK ==` debug panel for the whole of a trace
session — the panel neither draws nor reserves overlay state while a session is active.

Fast-forward folds extra gameplay steps into each rendered frame, and the audio speeds
and pitches up with the picture the way rewind at speed already does. The VHS tape effect
comes on above 1x and scales with the rate, scrolling its tear bands the opposite way
from a rewind. Rewind itself stays at one step per frame; changing the ladder while the
rewind key is held only takes effect on release.

### Live Play

Live gameplay rewind is disabled by default. To enable it:

```yaml
rewind:
  liveEnabled: true
  liveKey: R
```

While playing a level, hold `rewind.liveKey` to step backward through the live
gameplay buffer. Releasing the key resumes normal gameplay from the restored frame.
The small live HUD is hidden during ordinary play and appears only while the key is
held, showing `LIVE REWIND` plus `REWIND <frame>`.

By default live rewind steps backward one frame per visual frame while the key is
held, with no movement after release. The experimental tape-coast layer remains
opt-in through `rewind.tapeCoastEnabled`; when enabled, the speed starts at
`rewind.tapeCoastMinSteps`, accelerates via `rewind.tapeCoastAcceleration`
up to `rewind.tapeCoastMaxSteps`, and decays via
`rewind.tapeCoastDeceleration` after release. The `RewindSpeedController`
keeps a fractional accumulator so sub-1.0 speeds produce slow-motion rewind
(physics steps land on the visual frames where the accumulator crosses 1.0).
`LiveRewindManager` pushes the current speed into `AudioManager.setReversePlaybackRate`
each visual frame; the `PcmHistoryRing.ReverseCursor` then walks the stored PCM
backward at that rate (>1.0 pitches up, <1.0 stretches into slow-mo).

Live rewind records input rows after normal level ticks and replays them through the
same `LevelFrameStep` path when rebuilding a rewound segment. Seamless level
transition frames reset the buffer, matching the trace-rewind rule that seeks do not
cross committed level or act boundaries.

The playback wrapper has three states:

| State | Behaviour |
| --- | --- |
| `PLAYING` | Step one frame forward, capture keyframes at the configured interval. |
| `PAUSED` | Do not advance until asked to single-step or resume. |
| `REWINDING` | Move the cursor backward, using cached per-frame snapshots inside the active segment. |

The lowest-level API is `RewindController`:

```java
RewindController controller = gameplayMode.getRewindController();

controller.seekTo(900);       // restore nearest keyframe and replay to frame 900
controller.stepBackward();    // move back one frame, clamped to available history
controller.step();            // step forward using the installed InputSource

int frame = controller.currentFrame();
int earliest = controller.earliestAvailableFrame();
```

Most UI code should use `PlaybackController` instead:

```java
PlaybackController playback = gameplayMode.getPlaybackController();

playback.pause();
playback.stepBackwardOnce();
playback.stepForwardOnce();
playback.play();
```

### Audio And Visual Presentation

Rewind has two state layers:

- **Deterministic gameplay state** is captured/restored by registered
  `RewindSnapshottable` adapters and replayed through the normal forward frame
  path.
- **Presentation state** is allowed to be presentation-only. It must not mutate
  future gameplay intent while internal rewind replay expands a segment.

Audio uses that presentation-only path. `AudioManager` records durable audio
intent through `AudioCommandTimeline`, captures logical SMPS/backend state for
keyframes, suppresses live backend commands during internal replay, and exposes
`beginReverseAudioPresentation()` / `afterRewindRestore(...)` for realtime
rewind. LWJGL forward playback still uses the normal production stream path; a
bounded PCM history ring is populated from the mixed output and drained backward
only while reverse presentation is active. On release, the consumed reverse
cursor is committed so successive rewinds continue from the audio position where
the previous rewind ended.

`LiveRewindManager` owns this lifecycle for ordinary live play. Visual Trace Test
Mode has its own path in `TraceSessionLauncher`, so it must explicitly enter
reverse audio presentation, call `GameServices.audio().update()` for each
consumed held-rewind frame, and clean up with `afterRewindRestore(...)` on
release.

Graphical fades are gameplay-scoped and are snapshotted through `FadeManager`.
The engine display pipeline also calls `FadeManager.update()` once per visual
frame, so rewind presentation must suppress that normal forward advancement while
live or trace rewind is active. `FadeManager.beginReversePresentation()` freezes
display-driven fade updates until the rewind presentation is released; restored
snapshots still carry the actual fade frame/color to render.

## Limits And Guarantees

Rewind is deterministic only for state captured by registered
`RewindSnapshottable` adapters or derived from captured state on the next forward
frame. The current covered state includes:

- Camera, timers, game state, RNG, fades, oscillation, water, parallax, and solid
  execution state.
- Playable sprites, CPU sidekick state, and sidekick follow-history.
- Object manager placement state, slot inventory, per-object state, dynamic object
  entries, and restorable child/projectile state.
- Rings, collected-ring bitsets, sparkle state, and lost-ring state.
- Level event state, level layout state, and mutation-pipeline pending work.
- Runtime-owned zone state, palette ownership, animated tile channels, special
  render effects, advanced render modes, and S2 PLC art progress.

Known limitations:

- Audio rewind is presentation-level, not a promise of exact YM/PSG/DAC
  sample-accurate reverse synthesis. Logical SMPS/backend snapshots keep restore
  and replay deterministic, while audible reverse playback comes from the PCM
  history ring.
- OpenGL/VRAM state is not captured. Rendering is re-derived after restore.
- Level/act changes reset the rewind buffer, so seeks cannot cross act boundaries.
- Death can be rewound until the level reset commits at the end of the death flow.
  Once the level reload boundary is reached, the old buffer is gone.
- Some fields are deliberately annotated `@RewindTransient` because they are derived,
  structural, or live object links. Fields annotated `@RewindDeferred` are known
  synchronization risks that need explicit identity/value codecs before they are
  treated as fully covered.

## Keyframe Interval

The keyframe interval is the number of forward frames between stored full snapshots.
When seeking to frame `F`, the controller restores the nearest keyframe `K <= F` and
replays forward to `F`.

| Interval | Worst replay after restore | Memory use | Seek responsiveness |
| ---: | ---: | --- | --- |
| 60 | 59 frames | lowest | lowest |
| 30 | 29 frames | about 2x interval-60 | better |
| 15 | 14 frames | about 4x interval-60 | best |

For the current S2 EHZ1 benchmark trace, 1200 frames of retained data measured:

| Interval | Stored keyframes | Retained bytes | Bytes per keyframe |
| ---: | ---: | ---: | ---: |
| 60 | 21 | 123,544 | 5,883 |
| 30 | 41 | 232,472 | 5,670 |
| 15 | 81 | 449,232 | 5,546 |

For a 10-minute act budget of 36,000 frames, this projects to roughly:

- Interval 60: 601 keyframes, about 3.37 MiB.
- Interval 30: 1201 keyframes, about 6.49 MiB.
- Interval 15: 2401 keyframes, about 12.70 MiB.

The practical default is interval 60. Use interval 30 if live scrubbing latency
becomes visible under heavier S3K object loads. Interval 15 is useful for stress
testing and very responsive debugging, but it spends more history memory.

## Running The Rewind Tests

Run the normal rewind suite:

```bash
mvn -Dmse=off "-Dtest=*Rewind*" test
```

Generate the full runtime-owner field inventory as a tool, not as a JUnit test:

```bash
mvn -Dmse=off -DskipTests test-compile exec:java \
  "-Dexec.mainClass=com.openggf.tools.rewind.RewindFieldInventoryTool"
```

The command exits non-zero while unsupported fields remain, so redirect its
output when generating a migration worklist.

To list concrete object classes currently covered by default subclass scalar
capture:

```bash
mvn -Dmse=off -DskipTests test-compile exec:java \
  "-Dexec.mainClass=com.openggf.tools.rewind.RewindFieldInventoryTool" \
  "-Dexec.args=--object-rollout-candidates"
```

Use this candidate list before adding per-object rewind annotations or overrides.
Most scalar object state should be handled by the central default-capture path;
leaf-object changes should be reserved for bespoke identity links, child/projectile
lifecycle, or state that requires a custom value record.

To audit annotation density and redundant transient annotations:

```bash
mvn -Dmse=off -DskipTests test-compile exec:java \
  "-Dexec.mainClass=com.openggf.tools.rewind.RewindFieldInventoryTool" \
  "-Dexec.args=--annotation-density"
```

To identify child/spawn graph hotspots that still need an explicit parent-owned,
independent, deterministic, or cosmetic policy decision:

```bash
mvn -Dmse=off -DskipTests test-compile exec:java \
  "-Dexec.mainClass=com.openggf.tools.rewind.ChildGraphPolicyInventoryTool"
```

`RewindBenchmark` is opt-in so default test runs stay fast:

```bash
mvn -Dmse=off "-Dtest=RewindBenchmark" \
  "-Dopenggf.rewind.benchmark.run=true" test
```

To compare keyframe intervals:

```bash
mvn -Dmse=off "-Dtest=RewindBenchmark" \
  "-Dopenggf.rewind.benchmark.run=true" \
  "-Dopenggf.rewind.benchmark.keyframeInterval=30" test
```

The benchmark writes `target/rewind-benchmark-results.json` and prints:

- Forward playback overhead with rewind off/on.
- Capture and restore cost.
- Cold seek cost.
- Hot held-rewind cost within and across segments.
- Retained resident-size estimate by subsystem.
- Audio logical snapshot, restore, and replay phases, with JSON `counters` for
  timeline entries, replayed commands, allocation support, heap delta, and GC deltas.
- Long-tail determinism result, currently expected to stay clean for 1200 frames.

Audio budget gates are disabled unless explicitly opted in:

```bash
mvn -Dmse=off "-Dtest=RewindBenchmark" \
  "-Dopenggf.rewind.benchmark.run=true" \
  "-Dopenggf.rewind.benchmark.audioBudgets=true" test
```

The audio gate properties can be overridden with
`openggf.rewind.benchmark.audio.maxCaptureMeanNs`,
`openggf.rewind.benchmark.audio.maxRestoreMeanNs`,
`openggf.rewind.benchmark.audio.maxReplayMeanNs`, and
`openggf.rewind.benchmark.audio.maxAllocatedBytes`.

## Technical Architecture

The main classes live under `com.openggf.game.rewind`:

| Type | Purpose |
| --- | --- |
| `RewindController` | Public seek/step API. Restores keyframes, replays forward, and owns the segment cache. |
| `PlaybackController` | UI-oriented state machine over the controller. |
| `InputSource` | Supplies deterministic per-frame inputs. Trace mode uses `TraceInputSource`; live mode will need a recorder-backed implementation. |
| `EngineStepper` | Runs one engine frame for a supplied input sample. |
| `KeyframeStore` | Maps frame numbers to `CompositeSnapshot`s. Current implementation is in-memory. |
| `SegmentCache` | Expands one keyframe segment into per-frame snapshots for cheap held rewind. |
| `RewindRegistry` | Ordered registry of subsystem `RewindSnapshottable` adapters. |
| `CompositeSnapshot` | Per-frame map from stable subsystem key to immutable snapshot record. |

Audio rewind lives outside `com.openggf.game.rewind`:

| Type | Purpose |
| --- | --- |
| `com.openggf.audio.rewind.AudioCommandTimeline` | Durable audio-intent log used when restoring/replaying keyframes. |
| `com.openggf.audio.rewind.AudioLogicalSnapshot` | Logical audio manager snapshot covering frame/counter/timeline/backend state. |
| `com.openggf.audio.rewind.Smps*Snapshot` | SMPS driver, sequencer, and track state records used by logical restore. |
| `com.openggf.audio.runtime.DeterministicAudioRuntime` | Frame-clocked audio runtime seam used by tests and deterministic replay plumbing. |
| `com.openggf.audio.runtime.PcmHistoryRing` | Bounded mixed-PCM history used for audible reverse presentation. |

Automatic field capture currently has two side-by-side implementations:

| Type | Purpose |
| --- | --- |
| `GenericFieldCapturer` | Audit-first reflection capturer used while migrating manual object/player extras. Stores ordered `FieldKey` entries and deep-cloned values in `GenericObjectSnapshot`. |
| `RewindTransient` | Reason-bearing annotation for structural, derived, or externally restored fields that must be excluded from automatic capture. |
| `RewindDeferred` | Reason-bearing annotation for fields that are known rewind state but need an explicit identity or value codec before automatic capture is safe. |
| `RewindScanSupport` | Source scanner shared by tests and tools for runtime-owner field audits. |
| `GenericRewindEligibility` | Central eligibility helper for audit classes and default object subclass capture decisions. |
| `com.openggf.game.rewind.identity` | Stable value ids and a per-capture `RewindIdentityTable` for player, object, and spawn references. |
| `com.openggf.game.rewind.schema` | Compact schema foundation: cached per-class field plans, little-endian scalar buffers, value/reference codecs, policy registry, context-aware capture, and `CompactFieldCapturer`. Default non-badnik object subclass scalar state uses this path when every default field has codec support; unsupported shapes fall back to the legacy generic snapshot. |

Compact capture supports primitives/wrappers, `String`, enums, primitive/enum
arrays, `BitSet`, simple immutable records, value-only `List` / `Set` / `Map`
fields, selected helper state (`SubpixelMotion.State`, `ObjectAnimationState`,
`PlatformBobHelper`, `AnimationTimer`), and player/object references when a
`RewindCaptureContext` with a populated `RewindIdentityTable` is supplied.
Final fields are structural by default unless their codec explicitly restores
in place. Final collections/maps whose element, key, or value type is a
player/object identity reference are also treated as structural for default
object subclass capture; the owning object manager restores those links by
stable identity rather than by compact scalar sidecar state. `RewindPolicyRegistry`
centralizes repeated decisions for runtime and rendering service types so shared
base classes do not need redundant `@RewindTransient` annotations.

For concrete `AbstractObjectInstance` subclasses, default subclass scalar capture
is enabled centrally by
`GenericRewindEligibility.usesDefaultObjectSubclassCapture(...)` when the class
does not declare a concrete `captureRewindState` or `restoreRewindState`
override. `GenericFieldCapturer.defaultObjectSubclassCapturedFieldsForAudit(...)`
backs the rollout audit exposed by
`RewindFieldInventoryTool --object-rollout-candidates`. Fields annotated
`@RewindDeferred` are excluded from generic capture until a stable identity/value
codec or manual snapshot path exists.

`PerObjectRewindSnapshot.compactGenericState` stores the compact sidecar for
default object subclass fields. Restore prefers that blob when present and uses
`genericState` only as compatibility fallback. Classes with concrete
`captureRewindState` / `restoreRewindState` overrides remain responsible for
their own bespoke state.

Encounter validation lives under `src/test/java/com/openggf/game/rewind/encounter`.
Those tests compare engine forward-only snapshots against engine rewind+replay
snapshots for selected subsystem keys. Trace/BK2 data may supply inputs, but ROM
trace state is not used as a rewind oracle.

The controller never runs the game backward. It always restores an earlier state and
then advances forward using the same simulation path as normal play. This is the
central determinism guarantee: if seek+replay diverges from original forward play,
some synchronization-relevant state is missing, restored incorrectly, or derived from
an untracked source.

## Adding Rewind Coverage

When adding a new gameplay-scoped subsystem, decide whether its state is:

- Captured directly: add a snapshot record and a `RewindSnapshottable`.
- Derived: mark structural/derived fields with `@RewindTransient` and ensure the
  next forward frame recreates them from captured state.
- Deferred: annotate with `@RewindDeferred` only when a stable identity/value codec
  is required but not implemented yet.

For object and badnik work, synchronization-relevant fields include routine/state
bytes, timers, velocities, phase counters, child/projectile spawn state, RNG-derived
choices, collision latches that affect future frames, and any player/object link that
cannot be cheaply re-derived. Do not store raw live object references in snapshots;
store stable identities such as object slots, spawn records, player role, or explicit
value records.

Prefer extending the central capture stack before editing many leaf object classes:
add a codec, policy-registry rule, or shared base-class snapshot when the same field
shape repeats. Add per-object annotations or rewind overrides only for genuinely
bespoke state, identity links, or child lifecycle that cannot be represented by the
generic scalar path.

Before considering a new subsystem covered, run:

```bash
mvn -Dmse=off "-Dtest=*Rewind*" test
mvn -Dmse=off "-Dtest=TestRewindStateBuffer,TestRewindSchemaRegistry,TestCompactFieldCapturer,TestCompactFieldCapturerPolicy,TestRewindRecordCodecs,TestRewindHelperCodecs,TestRewindCollectionCodecs,TestRewindPolicyRegistry,TestRewindPlayerReferenceCodecs,TestRewindObjectReferenceCodecs,TestRewindIdentityTable" test
mvn -Dmse=off "-Dtest=TestRewindEncounterValidation" test
mvn -Dmse=off "-Dtest=RewindBenchmark" \
  "-Dopenggf.rewind.benchmark.run=true" test
```

When touching audio/fade presentation specifically, also run the focused
presentation suite:

```bash
mvn -Dmse=off "-Dtest=com.openggf.TestTraceSessionLauncherRewindPresentation,com.openggf.graphics.TestFadeManagerRewindSnapshot,com.openggf.game.rewind.TestLiveRewindManagerAudioCleanup,com.openggf.audio.TestAudioManagerRewindSuppression" test
```

If the benchmark reports a divergent key, treat it as a real coverage gap unless the
diff comparator itself is demonstrably wrong.

### Performance attribution

When the debug performance overlay is enabled, the rewind hot path
appears under five sections:

- `rewind.capture` — `RewindRegistry.capture()` (snapshot bundle build).
- `rewind.restore` — `RewindRegistry.restore()` (snapshot apply).
- `rewind.step` — `RewindController.stepBackward()` outer body
  (audio bookkeeping, segment-cache array alloc, primer calls).
- `rewind.seek` — `RewindController.seekTo()` outer body.
- `rewind.tick` — each `engineStepper.step(...)` call replayed during
  segment-cache cold expansion or seek forward-stepping.

The profiler does not nest sections (`PerformanceProfiler.beginSection`
implicitly ends the active one), so `rewind.step` / `rewind.seek` are
re-opened explicitly after each inner section closes. Every `beginSection`
is paired with `endSection` in a `try/finally` so exceptions cannot leave
a dangling active section.

Production rewind code depends on the `SectionProfiler` interface
(`com.openggf.debug.SectionProfiler`), not the `PerformanceProfiler`
singleton directly — keeps tests cheap and avoids singleton coupling.
