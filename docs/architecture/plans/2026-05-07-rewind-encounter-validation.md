# Rewind Encounter Validation Catalog

This pass adds a small test-side encounter shape for validating engine
forward-only state against engine rewind+replay state. Trace recordings are
used only as deterministic input streams; the encounter assertions do not read
ROM trace state as an oracle.

Initial non-disabled catalog entries:

| ID | Game | Zone | Family | Mechanic | Window | Compared snapshot keys |
| --- | --- | --- | --- | --- | --- | --- |
| `s2-ehz1-early-traversal` | Sonic 2 | EHZ1 | `baseline-objects` | Trace-input traversal before torture-scale dynamic spawns | rewind frame 180, compare frame 300 | `camera`, `object-manager`, `rings`, `sprites` |
| `s2-ehz1-mid-run-traversal` | Sonic 2 | EHZ1 | `transient-dynamics` | Mid-run traversal crossing badnik kills, animals, points popups (single rewind) | rewind frame 180, compare frame 1500 | `camera`, `object-manager`, `rings`, `sprites` |

The `s2-ehz1-mid-run-traversal` scenario passes today: a single rewind+replay
matches the forward run across a 22-second window covering early-trace badnik
encounters. Slot drift surfaces in `TestRewindTorture` only after many rewinds
in succession, not from any one rewind window.

## Slot-drift mitigation progress

The `TestRewindTorture` checklist itemized three architectural fixes; all three
have landed:

1. **Capture live `usedSlots` BitSet directly.** Done. `ObjectManagerSnapshot`
   stores the live `usedSlots.toLongArray()` instead of synthesizing a
   restorable subset.
2. **Add rewind codecs for transient dynamic objects.** Done.
   `AnimalObjectInstance`, the `AbstractPointsObjectInstance` family
   (Sonic1/Sonic2/Sonic3k), `ExplosionObjectInstance`, the `ShieldObjectInstance`
   family (base + S3K Fire/Lightning/Bubble), and the
   `InvincibilityStarsObjectInstance` family (base + S3K) are covered. The
   non-player-bound classes use construct-and-restore codecs; the player-bound
   classes use deferred codecs that stash the captured slot for post-restore
   re-spawn.
3. **Coordinate shield re-pin to honour the captured shield slot.** Done.
   `DefaultPowerUpSpawner.addPowerUpObject` now consumes the captured slot via
   `ObjectManager.consumePendingPlayerBoundSlot(...)` before falling back to a
   fresh free slot.

`RewindObjectStateBlob` also needed content-aware `equals`/`hashCode` so the
diff helper doesn't report false divergence on byte-identical compact sidecar
blobs.

**Torture-test progress.** Sniffing `tortureFixedAdjacent` after each landing
(checkpoint interval shown alongside):

| State | Earliest failure |
| --- | --- |
| Pre-step 4 (synthesized usedSlots), CHECKPOINT_INTERVAL=100 | iter 1600: `dynamicObjects[0].slotIndex: A=18 B=19`, full slot cascade |
| Post step 4 + 5a-5c, CHECKPOINT_INTERVAL=100 | iter 1600: placement-managed slot realignment (Masher 25→27, Bridge cascade) |
| Post step 5d-5e + 6, CHECKPOINT_INTERVAL=100 | iter 1600: single-field `ShieldObjectInstance#sequenceIndex: A=9 B=0` |
| Post deferred-codec entry restore, CHECKPOINT_INTERVAL=100 | iter 1700: player physics drift (downstream symptom) |
| Post bucketed-dynamics diff, CHECKPOINT_INTERVAL=10 | iter 1640: player physics drift |
| Same fixes, CHECKPOINT_INTERVAL=5 | iter 1635: player physics drift |
| Same fixes, CHECKPOINT_INTERVAL=1 (exhaustive) | iter 1631: hitbox dimensions transposed (20×38 → 38×20), runningMode GROUND→RIGHTWALL, angle 0→-40 |

The iter-1631 signature is real engine-state drift during torture replay, not a
slot/codec framework gap. Likely sources:

- Subtle drift baked into the keyframe at frame 1620 during torture replay
  (the keyframe captures cycle 1620's intermediate state mid-cycle, while
  Phase A's reference snapshot at frame 1620 is from the post-cycle state of
  the forward-only run — the controller's keyframe and Phase A's reference
  may not be at exactly the same logical step within the frame).
- Some captured state on the player or a level object that affects
  ground/wall transition logic but isn't fully covered by current capture.

This is per-frame instrumentation territory: the next investigation needs
to diff state at frames 1620-1631 step by step rather than rely on cycle-end
checkpoints.

**Concrete next-step diagnostic.** Write a one-shot test that:

1. Drives `RewindController` forward through frames 0..1631 in a fresh fixture, calling `registry.capture()` after every step into a `Map<Integer, CompositeSnapshot>` keyed by frame.
2. After step 1, calls `controller.seekTo(1620)` and then steps forward one frame at a time to 1631, capturing into a parallel `Map<Integer, CompositeSnapshot>`.
3. Diffs the two maps frame-by-frame via `RewindSnapshotDiff.diffKey` for keys `camera`, `sprites`, `object-manager`, `rings`, `level`.

The first frame whose diff is non-empty pinpoints exactly where the 11-step replay from keyframe 1620 diverges from the original forward stepping. Comparing that diff against the frame-0..1620 reference identifies which specific captured-state field is missing or wrong on the live engine after restore. From there it's a per-field framework fix (extend codec coverage, wire identity context through default subclass capture for `PlayableEntity`/`ObjectInstance` references, or add a missing capture in some subsystem).

A known gap that may or may not be the cause: default object-subclass capture currently excludes `PlayableEntity` and `ObjectInstance` reference fields on object instances (`isDefaultObjectFieldValueType` doesn't include them, even though `RewindCodecs.codecFor` returns `PlayerReferenceCodec`/`ObjectReferenceCodec` for those types). Fields like `FlipperObjectInstance#lockedPlayer`, `GrabberBadnikInstance#grabbedPlayer`, `SpringboardObjectInstance#launchPlayer` are therefore not captured. Fixing this requires plumbing a `RewindCaptureContext` with an identity table through default subclass capture so the reference codecs can encode/resolve player refs by `PlayerRefId`. This may not be the iter-1631 cause (EHZ1 doesn't have flippers/springboards/grabbers), but is a real coverage gap visible in the per-frame diagnostic.

**Diagnostic landed:** `TestRewindIter1631Diagnostic` (Disabled by default) implements
the test described above and adds a strict reflective deep-equals pass alongside
the lenient `RewindSnapshotDiff` pass.

**Findings from running the diagnostic.**

- Lenient diff (the same comparator as `TestRewindTorture`) shows: frames 1620-1629
  identical; first divergent frame is **1630** (camera/sprites). Same iter-1631
  signature as the torture run with CHECKPOINT_INTERVAL=1.
- Strict diff at frame 1620 (immediately after restore) reveals what the lenient
  comparator hides:
    - `level.epochAtCapture: A=0 B=1` — benign (epoch is a CoW generation counter,
      bumped on restore by design).
    - `object-manager.dynamicObjects[N]` order differs by **list index**, not by
      content. Bucketing by `slotIndex` (what the lenient comparator does) shows
      the same `{slotIndex → instance}` set in both runs. The forward run has
      `[Shield@19, Projectile@18, Explosion@24, Animal@20]`; the rewind run has
      `[Projectile@18, Explosion@24, Animal@20, Shield@19]`. The list-tail Shield
      is from the post-restore re-spawn path
      (`DefaultPowerUpSpawner.addPowerUpObject` runs after the dynamic-restore
      loop and `dynamicObjects.add(...)`s at the end).
- Probe: clearing `TouchResponses.overlapping`/`sidekickOverlaps` on restore (they
  were never captured/restored) did **not** eliminate the iter-1630 divergence.
  Either the touch-response edge state is not the cause, or it is part of the
  cause but additional uncaptured state remains. Probe was reverted; finding
  recorded for the future fix to consider.

**Probe matrix (all reverted; none individually or collectively eliminated the
iter-1630 divergence).**

| # | Probe | Result |
| --- | --- | --- |
| 1 | Clear `latchedSolidObjectInstance` and `mgzTopPlatformCarrySolidContactObject` on player restore | Same iter-1630 camera/sprites divergence |
| 2 | Call `TouchResponses.reset()` on object-manager restore | Same divergence |
| 3 | Sort `dynamicObjects` by `slotIndex` at top of every `update()` (normalizes iteration order in both runs) | Same divergence |
| 4 | Clear `SolidContacts.inlineSupportedPlayers`, `objectStandingBitSet`, `objectPushingBitSet`, `objectStandingBitSnapshot` on restore | Same divergence |
| 5 | All four probes combined | Same divergence |

**Implication.** The iter-1631 cause is **not** any of the surfaces enumerated by
the original three proposed fixes. The four most-likely uncaptured-state
candidates have been ruled out individually and collectively.

**Determinism check landed (`twoFreshForwardRunsProduceIdenticalPlayerState`).**
Build two fresh fixtures, run each forward 0..1631 with no rewinds, compare
per-frame `PlayerRewindExtra`. **Result: identical across all 1632 frames.**

That rules out:
- Engine non-determinism (RNG, iteration order, JVM hash randomization).
- Input source non-determinism (BK2 movie reads same on both runs).
- `LevelManager`/`ObjectManager`/manager-counter drift across fresh fixtures.

Therefore: **the iter-1631 divergence is exclusively introduced by the rewind
path.** Some live engine state persists from pre-seek frame 1631 across the
`registry.restore(...)` call into post-seek frame 1620, and the four candidates
above are not it. The remaining candidate set:

- Cross-frame state on a manager whose snapshottable adapter doesn't capture
  every cross-frame field (likely candidates: `RingManager`, `ParallaxManager`,
  `WaterSystem`, zone runtime registries, pattern animator, level-event
  manager).
- Per-instance state on a *currently active* `ObjectInstance` whose
  `restoreRewindState` overload doesn't restore every cross-frame field
  (subclass-specific timers, animation cursors, internal bit flags). Restore
  rebuilds the instance with `registry.create(spawn)` — anything the
  constructor sets to a default but the live engine had moved past will be
  reverted to default on the new instance, but anything the new instance reads
  from THIS-instance state that isn't restored from the snapshot stays at
  default while the captured snapshot may reflect a non-default value.
- Per-instance state on `AbstractPlayableSprite` controllers (movement /
  spindash dust / animation) that's not in the corresponding `RewindState`
  record. Movement state was confirmed to capture all 14 of its 14 fields, so
  this is least likely.
- Uncaptured static fields on any manager class (other than the already-
  refreshed `AbstractObjectInstance.cameraBounds`).

**Reflective dump probe landed (`reflectiveDumpAtFrame1620IdentifiesUncapturedObjectFields`).**
Walks every active `AbstractObjectInstance`, the player sprite, plus the
`SpriteManager`, `Camera`, `RingManager`, `ParallaxManager`, `WaterSystem`,
and `ObjectManager` reflectively at frame 1620 in Phase A (forward) and
Phase B (post-`seekTo`) and diffs eagerly-captured strings. Ran with the
JPMS-safe formatter and PlayableEntity/ObjectInstance refs treated as opaque
to avoid identity-hash noise.

**Result:** at frame 1620, **all `AbstractObjectInstance` subclass field state
matches between Phase A and Phase B**. Total fields dumped: 674. Diverging
fields: 17, all of which fall into three benign categories:

1. **ParallaxManager state asymmetry** (`currentZone=-1 vs 0`, `hScroll=zeros
   vs populated`, `maxScroll`, `minScroll`, `vscrollFactorFG`). Phase A's
   headless forward stepping does not populate parallax tables, but Phase B's
   `seekTo` post-restore callback (`recomputeParallaxAfterRewindRestore`)
   does. This is a test-infrastructure asymmetry and feeds only into
   rendering/camera-shake (no gameplay path).
2. **`ObjectManager.execOrder`**: per-frame scratch array. Phase A has the
   last-update's contents, Phase B has nulls because `Arrays.fill(execOrder,
   null)` runs in the restore. Repopulated at the start of the next
   `update()` call, so benign.
3. **Identity-hash noise on inner-class refs** (`placement`, `solidContacts`,
   `touchResponses`, `objectServices`, `levelManager` on
   `SpriteManager`/`RingManager`). The refs differ by identity; their
   content appears equivalent on inspection.

**Implication:** the iter-1631 divergence is **not** in any per-instance
field of an active `ObjectInstance` at frame 1620, nor in the player sprite,
nor in the major manager fields walked. The captured state genuinely matches
across phases at frame 1620. Yet 10 forward steps later (frame 1630) the
engine diverges.

**Noise-floor cross-check landed (`twoFreshForwardRunsReflectiveDumpsMatch`).**
Two fresh fixtures running forward 0..1620 each produce 24 diverging
reflective fields between them — all structural Java refs (sensors,
renderers, inner-class references, IdentityHashMap-backed sets). Subtracting
this noise floor from the Phase A vs Phase B reflective diff isolates the
genuinely seekTo-introduced divergence:

- **ParallaxManager (7 fields)**: post-restore callback
  `recomputeParallaxAfterRewindRestore` populates parallax; forward run
  doesn't. Visual only.
- **`ObjectManager.dynamicObjects` list order**: known issue (Shield gets
  re-spawned to the end via post-restore callback).
- **`ObjectManager.pendingPlayerBoundEntries`**: empty `ArrayDeque` left in
  map after `consumePendingPlayerBoundEntry` polls (the deque becomes
  empty, but the map entry stays). Phase A's map has never been populated
  so it's empty; Phase B's has the consumed-empty entry. Logically
  equivalent.

**None of these explain the iter-1631 player-physics divergence at frame
1630.** The actual cause is still hidden from the reflective dump.

**Per-frame diff probe (`perFrameReflectiveDumpFindsFirstDivergentSubsystem`).**
Dumps reflective state at every frame from 1620..1631 in both phases,
strips identity-hashes (`@<digits>` → `@<id>`), filters out the 24-field
fresh-vs-fresh noise floor, and reports the first character position where
each remaining diff differs. Findings:

- **Frame 1620**: 6 diverging fields. `player[sonic].renderHFlip:
  A=false B=true` (visual flag, converges in 1 forward step). The other
  5 are persistent through frame 1629:
    - `ObjectManager.dynamicObjects` — list order (Shield repositioned at
      end by post-restore re-spawn).
    - `ObjectManager.pendingPlayerBoundEntries` — empty `ArrayDeque` left
      after `consumePendingPlayerBoundEntry` polls.
    - `ObjectManager.solidContacts` and `ObjectManager.touchResponses` —
      diverge only because their `toString` embeds `dynamicObjects=List[…]`
      from the parent ObjectManager (substring of #1).
    - `RingManager.lostRings` — diverges at the embedded `snapshotEpoch=0
      vs 1` (level CoW generation counter, bumped on restore).
- **Frames 1621..1629**: SAME 5 diffs only. No NEW divergence.
- **Frame 1630**: 24 diverging fields including 19 player-state fields
  (the iter-1631 signature: `air`, `rolling`, `jumping`, `gSpeed`, `ySpeed`,
  `xPixel`, `yPixel`, `xRadius/yRadius`, `mappingFrame`, `animationId`,
  `badnikChainCounter`, etc.).

**The interpretation is now sharp.** From frame 1620 through frame 1629
inclusive, the only genuine reflective-state differences between Phase A
and Phase B are:

1. `dynamicObjects` list ORDER (Shield position).
2. `level.epoch` (cosmetic CoW counter).
3. Empty deque map entry (logically equivalent).
4. `renderHFlip` at frame 1620 only (converges).

Of these, only (1) could plausibly affect gameplay. But the previous probe
that sorted `dynamicObjects` by slotIndex at the top of every `update()`
did NOT eliminate the iter-1631 divergence — strongly suggesting list
order is not the cause either.

**This means the cause is NOT visible to a reflective dump that walks
manager fields, ObjectInstance subclass fields, the player sprite, the
camera, sprite manager, ring manager, parallax manager, and water system,
even with deep-collection inspection.** The remaining suspect set:

1. **Inside solidContacts/touchResponses**: their string diff is dominated
   by the embedded `dynamicObjects` substring. Their own per-player
   `Set`/`Map` content (overlap sets, standing-bit sets) might also
   differ. Need a probe that strips the embedded `dynamicObjects` from
   their toString and compares only the OWN state.
2. **Beyond `formatForCompare`'s `depth >= 3` cut**: deeply nested fields
   that were truncated.
3. **Inside `IdentityHashMap`-backed collections**: my Set/Map walker
   handles the standard interfaces, but `IdentityHashMap` iteration order
   is non-deterministic with respect to identity hash, so the formatted
   element order may not match between phases even when the SET of
   elements is identical.
4. **Subpixel state on a non-`AbstractObjectInstance` container**: e.g.,
   `Camera`'s `levelStarted`/`frozen` flags or some intermediate scrap
   buffer used by the touch-response or solid-contact path.

The `solidContacts`/`touchResponses` content (skipping the embedded
`dynamicObjects`) is the highest-yield next probe — those classes
manipulate per-player overlap state that gets used directly by the
collision pipeline, and their string diff is currently masked.

**Top-level dump probe extended (`SolidContacts`, `TouchResponses`,
`Placement`).** Dumped each as a top-level entry (not nested inside
`ObjectManager`) so their own state isn't masked by the embedded
`dynamicObjects` substring. New diffs found at frame 1620:

- **`SolidContacts.frameCounter: A=1620 B=1631`** — SolidContacts has
  its own `frameCounter` (incremented by `beginInlineFrame()`) that is
  never captured/restored. Phase B's value is offset by 11 from Phase
  A's. **PROBED — syncing this counter from `ObjectManager.frameCounter`
  on restore does NOT fix iter-1631.** Listeners receive this value but
  aren't documented to use it as gameplay-gating.
- `SolidContacts.latestStandingSnapshots`: empty in Phase B post-restore
  (cleared); populated in Phase A. Converges within 1 forward step.
- `SolidContacts.preContactXSpeed`/`preContactYSpeed`: stale at frame
  1620; reset by `beginInlineFrame` on the next forward step.

**At frame 1629** (the last clean frame before the explosion), only:
1. `dynamicObjects` list ORDER (Shield position).
2. `pendingPlayerBoundEntries` (empty deque artifact).
3. `solidContacts`/`touchResponses` (substring of #1).
4. `Placement.usedSlotCounter` (substring of #1).
5. `RingManager.lostRings` (snapshotEpoch CoW counter).
6. `SolidContacts.frameCounter` (offset by 11).

Probes that did NOT fix iter-1631:
- Sort `dynamicObjects` by slotIndex at top of every `update()`.
- Sync `SolidContacts.frameCounter` to `ObjectManager.frameCounter` on
  restore.
- Clear `latchedSolidObjectInstance` / `mgzTopPlatformCarrySolidContactObject`
  on player restore.
- `TouchResponses.reset()` on object-manager restore.
- Clear `SolidContacts.objectStandingBitSet`, `objectPushingBitSet`,
  `inlineSupportedPlayers` on restore.

**Final state of investigation.** Engine is deterministic. Captured
state matches across phases at frame 1620. Reflective state matches at
frames 1620-1629 except for a small set of known structural / counter /
list-order differences, none of which are gameplay-gating per the
probes. Yet player physics still diverges at frame 1630.

**The bug surface is one of:**
- Deeper than reflective depth-3 (depth-4 walk hangs/OOMs).
- Inside JDK-opaque collection contents that my Set/List/Map walker
  doesn't reach (e.g., the per-bucket array of an IdentityHashMap).
- In a subsystem that `fullReflectiveDump` doesn't include (e.g., some
  static singleton state, the `LevelManager` itself, `ZoneRuntimeRegistry`
  state, `AnimatedTileChannelGraph`, audio, debug overlay).
- A subtle effect of accumulated subpixel arithmetic where Phase A and
  Phase B diverge by a tiny amount that's invisible to the snapshot/diff
  comparators but accumulates over 10 frames into player-touch threshold.

## ROOT CAUSE IDENTIFIED (parallel-agent investigation, 2026-05-07)

Four parallel agents probed the remaining hypotheses. **Agent 3 found the
smoking gun via an exhaustive cycle-detected reflective walk** (depth 6,
stable Set/List/Map iteration via leaf-tag sort, walking every reachable
mutable field):

**`ObjectManager.TouchResponses.building` / `.overlapping` buffer-swap
parity diverges between phases.**

`TouchResponses` (`ObjectManager.java:3991`+) holds two `Set<ObjectInstance>`
buffers (`bufferA`, `bufferB`) and two refs (`overlapping`, `building`)
that **swap each frame** in `OverlapBufferPair.swap()` (lines 4007–4011).
After 1631 forward steps, the refs have been swapped 1631 times (odd
parity); after 1620 forward steps, even parity.

`seekTo(1620)` restores all captured state but **the buffer references
themselves are not in any rewind snapshot**. So:
- **Phase A** at frame 1620: even-parity (`overlapping=bufferA, building=bufferB`).
- **Phase B** at frame 1620 (post-`seekTo`): odd-parity (`overlapping=bufferB,
  building=bufferA` — left over from frame 1631 pre-seek).

The parity offset persists through frames 1621–1629 (each swap preserves
the parity offset). This breaks the edge-trigger logic at
`ObjectManager.java:4373`: `!overlappingSet.contains(instance)`. **Phase
B reads `overlapping` from the wrong buffer (one containing frame-1631
state, not frame-1620), so edge detection fires inconsistently.** At
frame 1630 the wrong-parity `overlapping` set causes a touch response
to fire (or miss) when the player approaches a badnik, producing the
observed `badnikChainCounter=1` in B vs 0 in A.

`OverlapBufferPair` (lines 4001–4019) — used inside `sidekickOverlaps`
for each sidekick — has the same parity bug.

### Why earlier `TouchResponses.reset()` probe didn't fix it

The earlier probe called `TouchResponses.reset()` on object-manager
restore (lines 4035–4042 of `ObjectManager.java`). That clears BOTH
buffers AND canonicalizes parity (`overlapping = bufferA`). But by
clearing the buffer contents, it loses Phase A's natural overlap memory
from frame 1619→1620. So Phase B's edge detection at frame 1621 sees
ALL contacts as new — different from Phase A which retained the prior
overlap set. The probe addressed parity but introduced a new content
asymmetry, leaving iter-1631 still divergent.

### Architectural fix LANDED — but does NOT fix iter-1631

Implemented `TouchResponses.captureRewindState`/`restoreRewindState`
(ObjectManager.java) plus a `TouchResponseOverlapState` record (with
nested `SidekickOverlapEntry`) added to `ObjectManagerSnapshot`. The
snapshot encodes:
1. `mainOverlappingSlotIndices` and `mainBuildingSlotIndices` — buffer
   content as slot ids (stable across restore).
2. `mainParitySwapped` — true iff `overlapping == bufferB`.
3. Per-sidekick `SidekickOverlapEntry` keyed by sprite code.

Restore rebuilds `bufferA`/`bufferB` from slot lookup against live
post-restore active objects, then sets the `overlapping`/`building`
refs to match captured parity.

**Verified via diagnostic logging that the fix is invoked correctly and
captures/restores the buffer state.** All 106 rewind unit tests pass
with the fix in place (`TestRewindController`, `TestRewindRegistry`,
`TestRewindTraceSeekDeterminism`, `TestRewindParityAgainstTrace`,
`TestRewindAcrossActBoundary`, `TestRewindDeathRespawnBoundary`,
`TestSegmentCache`, `TestPlaybackController`, `TestInMemoryKeyframeStore`).
No regressions.

**However: the fix does not fix iter-1631.** Probe logging revealed
that during the EHZ1 trace, the TouchResponses buffers are EMPTY at
every frame from 1620 to 1631:

```
TR.capture@frame=1620 mainOver=[] mainBuild=[] swapped=false sk[Tails]: over=[] build=[] swp=false
TR.capture@frame=1621 mainOver=[] mainBuild=[] swapped=true  sk[Tails]: over=[] build=[] swp=true
... (parity oscillates each frame, content stays empty)
```

The parity flips every frame (as expected from the swap), but with
empty content the parity divergence Agent 3 detected via reflective
identity-comparison **does not carry any cross-frame state**. Both
phases see empty `overlapping` sets going into edge-detection at every
frame. The Agent 3 root-cause hypothesis was thus a **reference-identity
artifact**, not a real state divergence.

Architectural value of keeping the fix:
- Real coverage: in scenarios where the player IS overlapping
  touch-response objects (collecting rings, breaking monitors,
  bouncing on badniks at the moment of contact), the buffer content
  is non-empty and the captured state correctly round-trips.
- Future-proofing: torture-test scenarios beyond this specific EHZ1
  case may exercise non-empty buffers.

**The actual iter-1631 cause is STILL unidentified.** What we know:
- It's not in any `RewindSnapshottable` subsystem (verified by Agent 1).
- It's not IdentityHashMap iteration order (verified by Agent 2).
- It's not in any of 165 static mutable fields (verified by Agent 4).
- It's not the TouchResponses parity/content (verified by this fix
  attempt + probe logs showing empty buffers).
- It's not the SolidContacts.frameCounter offset (probe didn't fix).
- It's not in any per-instance `ObjectInstance` field at frame 1620
  (reflective dump shows match).

The cause must be in something subtler still hidden from all our
diagnostic tooling.

### NEW: Multi-seekTo cycles converge to no-divergence

The `smallPreSeekWindowStillDivergesAtFrame1630` test (which performs
3 seekTo+forward cycles before measurement at frame 1630) shows
**zero diverging keys** at frame 1630 — for every pre-seek window
(1, 5, 11 frames).

In contrast, the `singleSeekToOnlyCaptureAtEnd` test (single
`seekTo(1620)` from frame 1631, then forward to 1630, then capture)
shows **2 diverging keys (camera, sprites)** at frame 1630 — same
iter-1631 signature.

Both compare the same frames. Both have the same captured snapshot
to restore from. The only structural difference is the number of
seekTo+forward cycles before the measurement. **This means the bug
is in CONVERGENT uncaptured state**: some state X that

- after a single `seekTo(1620)`, holds the pre-seek frame-1631 value
  rather than the natural frame-1620 value;
- after multiple seekTo+forward cycles, converges (probably back to
  the natural frame-1620 value, or at least to a stable equivalent).

Convergent state is consistent with:
- A cache that's populated on first use after restore and stabilizes
  after subsequent forwards.
- An `IdentityHashMap` whose bucket array gets resized/rebuilt across
  multiple clear+repopulate cycles to a deterministic layout.
- A lookup table (e.g., `cachedTouchResponseObjects`,
  `cachedSolidProviderObjects`, `dynamicFallbackScratch`) populated
  by `rebuildActiveObjectCaches()` that retains stale ordering until
  another rebuild.

Promising next probe: instrument
`ObjectManager.rebuildActiveObjectCaches()` to log the iteration
order it produces, run single-seekTo and multi-cycle scenarios, and
compare the recorded orders frame-by-frame. The frame where the
single-seekTo order differs from a fresh forward-only order is the
corruption surface. The fix has been LANDED as a real coverage
improvement but iter-1631 remains a known framework gap.

### Other hypotheses ruled out by parallel agents

- **Agent 1** (subsystem coverage): extended dump to cover
  `LevelManager`, `ZoneRuntimeRegistry`, `PaletteOwnershipRegistry`,
  `AnimatedTileChannelGraph`, `SpecialRenderEffectRegistry`,
  `AdvancedRenderModeController`, `ZoneLayoutMutationPipeline`,
  `TimerManager`, `GameStateManager`, `GameRng`, `FadeManager`,
  `SolidExecutionRegistry`, `CollisionSystem`, `TerrainCollisionManager`,
  `AbstractLevelEventManager`, `OscillationManager`. **No new
  gameplay-affecting state divergence found in any of these.**
- **Agent 2** (IdentityHashMap iteration order): instrumented
  `activeObjects.values()`, `ridingStates.keySet()`,
  `touchResponses.overlapping`. Iteration order **matches** between
  phases for `activeObjects`. `ridingStates` and `overlapping` are empty
  during the relevant window. **IdentityHashMap iteration order
  ruled out.**
- **Agent 4** (static mutable state): catalogued 165 non-final static
  fields. Probe-resetting the candidates that could plausibly affect
  EHZ gameplay (`SmashableGroundObjectInstance.globalChainBonusCounter`,
  `BlueBallsObjectInstance.activeInstanceCount/gloopToggle`,
  `BombPrizeObjectInstance.soundThrottleCounter`,
  `MTZLongPlatformObjectInstance.mtzPlatformCogX`,
  `AbstractObjectInstance.cameraBounds`) on restore did **not** fix
  iter-1631. **Static state ruled out for this trace.**

**Diagnostic infrastructure left in place.**
`TestRewindIter1631Diagnostic` has 5 test methods now:

1. `replayFromKeyframe1620MatchesForwardRunFrameByFrame` (`@Disabled`):
   the original keyframe-replay diagnostic. Re-run manually to confirm
   any framework fix.
2. `twoFreshForwardRunsProduceIdenticalPlayerState` (passing): proves
   engine determinism across fresh fixtures.
3. `reflectiveDumpAtFrame1620IdentifiesUncapturedObjectFields` (failing,
   intentional): walks live engine state at frame 1620 in both phases,
   diff with deep collection/identity-hash filtering.
4. `twoFreshForwardRunsReflectiveDumpsMatch` (passing, informational):
   produces the noise floor for filtering.
5. `perFrameReflectiveDumpFindsFirstDivergentSubsystem` (failing,
   intentional): per-frame diff 1620..1631 with character-position
   indication of divergence.

These tests stay in-tree as ongoing diagnostics. Re-run after any
framework change to verify it actually closes the iter-1631 surface.

**Where the missing surface is most likely hiding:**
- `TouchResponses` overlap/edge sets, walked by `dumpInstanceFields` but
  formatted as opaque sets — the *content* of those sets is not deeply
  compared. Phase A's overlap set vs Phase B's (cleared by restore in some
  paths) may differ.
- `SolidContacts` per-player maps (`objectStandingBitSet`,
  `objectPushingBitSet`, `inlineSupportedPlayers`), which contain
  `Set<Object>` — the contents differ but our previous probe of clearing
  them on restore didn't fix iter-1631 (so either still incomplete, or not
  the cause).
- `Map<ObjectSpawn, ObjectInstance> activeObjects` and other
  `IdentityHashMap` iteration order in code paths that the slot-sort probe
  didn't normalize.
- Some sub-field of an `ObjectInstance` that's reachable via reflection but
  was deep-cut by `formatForCompare`'s `depth < 3` limit.

**Recommended next probe:** since per-instance state matches, target
sub-helper state explicitly. For each `Map<PlayableEntity, Set<Object>>` in
`SolidContacts` and each `Set<ObjectInstance>` in `TouchResponses`, dump
the content as a slot-keyed sorted list (not just identity hashes) and
include in the diff.

Alternative: binary-search the snapshot keys. Restore everything EXCEPT one
key, then forward 11 frames and check for divergence. Cycle through each
key. The key whose omission causes divergence to disappear is likely
masking a missing capture in some related uncaptured surface.

Alternatively: add reflective state dumping at frame 1620 in Phase A (during
*forward stepping at the moment the engine is naturally at frame 1620*) and at
frame 1620 in Phase B (immediately after `seekTo(1620)`). Diff the dumps. Any
manager or object-instance field that differs is a cross-frame uncaptured
surface — those values are stale-from-frame-1631 in Phase B but were correct
in Phase A. This is the most direct method to enumerate the missing capture.

Search scope for the reflective dump (in priority order based on what could
plausibly affect player physics):

1. `ObjectInstance` subclasses' private fields — anything not in the
   `BadnikRewindExtra`/`PlayerRewindExtra`/`*RewindExtra` records.
2. `RingManager` private fields beyond what its snapshot captures.
3. `ParallaxManager` ripple/scroll counters.
4. `WaterSystem` per-frame state.
5. Zone runtime registries (HTZ earthquake, CNZ rotation, etc. — EHZ has
   minimal zone-specific runtime state, but worth confirming).
6. Any `static` mutable field on a `level.objects` or `sprites` class.

**Likely root-cause shape (multiple uncaptured state surfaces).**

1. `TouchResponses` keeps cross-frame edge-detection state (`overlapping`/
   `sidekickOverlaps`/`bufferA`/`bufferB`) that is **not in any snapshot**. After
   `seekTo`, this state remains at the pre-seek frame's value. The single-clear
   probe above does not fix iter-1631 alone but the gap is real.
2. AbstractPlayableSprite's two `@RewindDeferred` ObjectInstance reference fields
   — `latchedSolidObjectInstance`, `mgzTopPlatformCarrySolidContactObject` — are
   not captured. `latchedSolidObjectInstance` keeps a stale Java ref across
   restore; the recreated solid object instance is a fresh ref, so the player's
   "what am I standing on" pointer is dangling. EHZ1 may still be affected via
   sidekick despawn detection (`SidekickCpuController#checkDespawn`) cascading
   into the visible-player drift.
3. `dynamicObjects` list ORDER divergence on restore (Shield gets re-added last
   via the post-restore re-spawn path). Most iterations re-sort by slot, but
   `populateDynamicFallbackScratch` walks list order directly. Whether that path
   actually runs in this scenario depends on slot layout for the affected
   dynamic objects; if it does, fallback execution order diverges.

**Architectural fixes needed (no quick patch).**

1. Make `TouchResponses` (and similar cross-frame edge-detection holders) into
   a `RewindSnapshottable` so its overlap sets round-trip with the rest of the
   engine. Reference fields must encode through slot-indexed identity, since
   `ObjectInstance` Java refs change across restore.
2. Plumb a `RewindCaptureContext` with an identity table through default object
   subclass capture so `RewindCodecs.codecFor` can serialize the
   `@RewindDeferred` `PlayableEntity`/`ObjectInstance` reference fields on
   `AbstractPlayableSprite` (and elsewhere) as stable ids.
3. Either (a) preserve the captured `dynamicObjects` order during the post-
   restore re-spawn path (insert Shield at its captured list index instead of
   appending), or (b) prove that no iteration of `dynamicObjects` actually
   depends on list order and tighten the contract.

These are framework refactors, not field-level fixes. Track them as the
`TestRewindTorture` re-enable blockers.

Incremental enabling path:

1. Add focused encounter entries by game, zone, object family, and mechanic
   before enabling broad torture coverage.
2. Prefer short windows around one mechanic: monitor break, badnik collision,
   platform ride, spring launch, bumper, boss phase, act boundary.
3. Keep `TestRewindTorture` disabled until the matching focused encounters for
   transient dynamic objects are stable. Its current comments remain the
   architectural checklist for enabling the stress patterns.
4. Keep lightweight pattern/bounds tests non-disabled; they validate schedule
   generation without depending on object snapshot coverage.
