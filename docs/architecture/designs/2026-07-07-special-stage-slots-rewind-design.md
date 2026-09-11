# Special Stage & Slots Rewind — Re-Simulation Foundation + Sonic 1 Special Stage Design

**Status:** Revised after adversarial review; ready for implementation planning. Branch: `feature/ai-special-stage-rewind` off `develop`.

## Goal

Give the engine's minigame stages live "held-key" rewind, which today exists only for `GameMode.LEVEL` and the Gumball/Pachinko `BONUS_STAGE` path. This spec designs a **reusable re-simulation rewind foundation for special stages** — a per-mode `EngineStepper` seam, a `SpecialStageProvider.supportsRewind()` capability gate, install/record/engage hooks on the `SPECIAL_STAGE` mode edge, and an extracted input→bitmask derivation — and details the **Sonic 1 Special Stage** as the proving vertical slice (a single-manager snapshot adapter with full capture surface and render re-establishment). Three follow-on targets (S3K Slots, S3K Special Stage, S2 Special Stage) are sketched as a roadmap; each gets its own spec.

## Locked Decisions (settled — do not re-litigate)

1. **Rewind model is re-simulation for all four targets.** The existing rewind engine stores a full-state keyframe every `KEYFRAME_INTERVAL = 60` frames plus one input row per frame, and rewinds by restoring the nearest keyframe and replaying `EngineStepper.step(Bk2FrameInput)` forward to the target. Re-sim costs roughly <1 MB/min of history versus ~15–45 MB/min for per-frame full-state snapshots (~30–50× ratio). The price is that forward replay must be deterministic; the determinism reconciliation below confirms that holds for the special stages.
2. **Sequencing:** foundation + S1 special stage first (this doc), then S3K Slots, then S3K special stage, then S2 special stage.
3. **Scope is within-stage rewind only.** Cross-mode rewind (rewinding out of a special stage back into the level, or vice versa) is out of scope; the mode boundary severs the timeline on entry and exit, consistent with `docs/architecture/designs/2026-06-26-rewind-boundary-policy-design.md`.
4. **No zone/mode/frame carve-outs in shared runtime code.** Participation is gated by a per-provider capability predicate, `SpecialStageProvider.supportsRewind()`, mirroring the shipped `BonusStageProvider.supportsRewind()` pattern (see `docs/architecture/plans/2026-07-06-bonus-stage-rewind-gumball-pachinko.md`). The capability predicate is the approved semantic pattern.

## Scope & Roadmap

### Spec 1 (this document, full detail)

- The reusable special-stage re-sim foundation: stepper seam, capability gate, mode-edge install/teardown, widened mode gates, record+engage hooks, extracted input derivation.
- The Sonic 1 Special Stage rewind adapter as the first consumer: `Sonic1SpecialStageManager` capture/restore with an opaque snapshot payload and explicit render re-establishment.

### Specs 2–4 (roadmap only; each gets its own spec + plan)

- **Spec 2 — S3K Slot Machine bonus stage.** Re-simulation. Slots already runs through `LevelFrameStep`, so the existing `LiveRewindStepper` replays it; what is missing is snapshot coverage of `S3kSlotBonusStageRuntime`'s bespoke state (the ~35-field `S3kSlotStageState` with Deques, the swapped-in player sprite / `slotPlayerRuntime`, and `ObjectManager`-tracked reward objects held in parallel lists). The core design problem is snapshot/restore of that runtime plus reward-list reconciliation against restored `ObjectManager` state.
- **Spec 3 — S3K Special Stage.** Re-simulation through the Spec-1 foundation (same stepper, same `"special-stage-runtime"` adapter key). Unlike S1's single flat manager, S3K's state is spread across sim subsystems, so capture is subsystem-delegated: the provider's adapter composes per-subsystem snapshot sections. GameState emerald writes and `audio().setSpeedMultiplier(...)` tempo changes reconcile via existing adapters and the audio command timeline (see Determinism below).
- **Spec 4 — S2 Special Stage.** Re-simulation; the hardest capture surface (~90 fields plus subsystems). Must capture the `lagAccumulator`/`lagCompensation` instance fields that drive the lag-frame skip, and must exclude wall-clock reads (`nanoTime`/`currentTimeMillis`) and the static `Sonic2TrackFrameDecoder.lastUncIndex`/`lastRleIndex`, all of which feed only logger diagnostics and never gate the sim.

### Out of scope (all specs)

- **Cross-mode rewind.** Entering a special stage tears down the level rewind session (`MODE_EXIT_TO_NON_REWINDABLE` → `LiveRewindManager.clear()` already fires on the `LEVEL→SPECIAL_STAGE` edge); exiting builds a fresh level session on the eventual return to `LEVEL`. The special-stage timeline is self-contained.
- Rewind inside `SPECIAL_STAGE_RESULTS` or any other presentation mode.
- Changing keyframe cadence, history length, or the rewind HUD/VHS presentation.

## Background: the Existing Re-Sim Engine

Facts the design builds on (all on `develop` today):

- `RewindSnapshottable<S>` (`src/main/java/com/openggf/game/rewind/RewindSnapshottable.java`): `String key()`, `S capture()`, `void restore(S)`, `default void resetForMissingSnapshot()` (throws unless overridden). Note the method is `capture()`, not `snapshot()`.
- `RewindRegistry.capture()` bundles all registered snapshottables into a `CompositeSnapshot`, invoked **only at keyframe boundaries** (frame 0 and every `keyframeInterval` frames), not every frame.
- `RewindController` + `SegmentCache`: backward seek restores the nearest keyframe then replays `EngineStepper.step(Bk2FrameInput)` forward. Per-frame sim snapshots inside the active segment are cached, so repeated backward steps within a segment are served as **O(1) restores of cached sim snapshots without re-running `update()`** — which is why `restore()` must fully re-establish render state (see the S1 section).
- Stepper abstractions: `EngineStepper` (functional: `void step(Bk2FrameInput)`) and `RewindSeekAwareEngineStepper` (adds `restoreToFrame(int, Bk2FrameInput)`). The only existing implementation, package-private `LiveRewindStepper`, hardcodes `LevelFrameStep.execute(...)`.
- `GameplayModeContext.installPlaybackController(InputSource, EngineStepper, int keyframeInterval)` constructs a fresh `RewindController` with a new empty `InMemoryKeyframeStore` and *replaces any previously installed controllers* — re-installing mid-session with a different stepper is supported by design. It touches only the controller/playback fields, not the registry.
- `LiveRewindManager.ensureInstalled()` (`LiveRewindManager.java:252`) chooses the stepper; today it hardcodes `new LiveRewindStepper(...)` and `KEYFRAME_INTERVAL = 60`, guarded by an identity check (`gameplayMode == installedGameplayMode && rewindController != null && inputSource != null`).
- `LiveRewindManager.isRewindableMode(mode)` whitelists only `LEVEL` and `BONUS_STAGE`; all four entry points (`handleRealtimeRewindInput`, `recordExternalFrame`, `resetBufferAtCurrentFrame`, `renderHud`) reject `SPECIAL_STAGE`.
- Mode-boundary severing: `GameLoop.changeGameModeForBoundary(...)` → `reportRewindModeBoundary(old, new)`. `LEVEL→non-LEVEL` fires `MODE_EXIT_TO_NON_REWINDABLE` (full `LiveRewindManager.clear()`); `non-LEVEL→LEVEL` fires `MODE_ENTER_REWINDABLE` (`ensureInstalled()`). Transitions between two non-LEVEL modes fire nothing.
- Config: `REWIND_HISTORY_SECONDS` default 60 (3600 retained frames), gated by `LIVE_REWIND_ENABLED`.
- Input replay: `LiveRewindStepper.step()` builds `RecordedInputSnapshots.fromBk2(input, previous)`, installs it via `inputHandler.setLogicalOverride(...)`, runs the frame, and `clearLogicalOverride()` in `finally`. A recorded row is a `Bk2FrameInput` (P1/P2 held+action masks, start-pressed, debug/shift flags).
- Audio during replay: `RewindController` brackets every forward-replay loop and cached-snapshot restore in an `AudioReplayScope` obtained from `audioManager.beginRewindReplay(fromFrame, targetFrame, reason)` (`RewindController.java:192/278/337/490`), with command-timeline bookkeeping (`beginCommandTimelineFrame`, `discardAudioCommandsAfter`, `pruneAudioCommandsBefore`) and `AudioPresentationPolicy.SUPPRESSED_INTERNAL_RESTORE` on restore. This suppression is **controller-owned and stepper-agnostic** — a new stepper inherits it (see Determinism).

## Architecture — the Reusable Re-Sim Foundation

### Overview

The bonus-stage rewind reused `LevelFrameStep`, so the existing stepper "just worked". Special stages bypass `LevelFrameStep` entirely — `GameLoop.updateSpecialStageMode()` (`GameLoop.java:999`, dispatched from the mode switch at `:918`) drives `activeSpecialStageProvider.update()` directly. The foundation therefore adds exactly one new moving part relative to the bonus pattern: a **per-mode stepper**. Everything else mirrors the shipped bonus design: a capability predicate, widened mode gates, record+engage hooks in the mode's update method, and a provider-state `RewindSnapshottable` registered on the mode edge.

Components:

| Component | Kind | Responsibility |
|---|---|---|
| `SpecialStageProvider.supportsRewind()` | default method, `false` | Capability gate; only providers with verified deterministic re-sim + snapshot coverage return `true` |
| Mode/provider suppliers into `LiveRewindManager` | new collaborators | `Supplier<GameMode>` + `Supplier<SpecialStageProvider>` (a small context holder), injected at construction, so the manager can resolve the current mode and active provider it otherwise cannot see (see the M1 prerequisite under *Install / teardown*) |
| `SpecialStageInputMapper` | new small class (extraction) | Logical-input → MegaDrive bitmask derivation, shared by live path and replay path |
| `SpecialStageStepper` | new class, `implements RewindSeekAwareEngineStepper` | Replays one recorded input row through the active special-stage provider; constructed with the same provider supplier |
| `LiveRewindManager` stepper-kind tracking | modification | Re-installs the session when the required stepper kind changes, not only when `GameplayModeContext` changes; `ensureInstalled()` derives the required kind from injected mode/provider suppliers |
| `LiveRewindManager.isRewindableMode` | modification | Adds `SPECIAL_STAGE` (still subject to the capability + transition-pending gates) |
| `GameLoop.stepInternal()` engagement guard | modification | Widen the top-level rewind guard (`GameLoop.java:785`) with `|| isSpecialStageRewindable()` (engagement lives here, not in the mode update) |
| `GameLoop.updateSpecialStageMode` hook | modification | Record each live frame before the finish check; honor `rewindBlocked` / transition-pending short-circuit |
| `SpecialStageProvider.rewindAdapter()` | default method, empty | Provider-owned optional adapter factory; shared session code registers the provider-supplied adapter under the generic special-stage key without concrete game branches |
| `Sonic1SpecialStageRewindAdapter` | new class, `implements RewindSnapshottable<Sonic1SpecialStageSnapshot>` | S1 keyframe capture/restore (detailed in the next section) |

### Capability gate

```java
// SpecialStageProvider.java
default boolean supportsRewind() { return false; }
String SPECIAL_STAGE_REWIND_KEY = "special-stage-runtime";
default Optional<RewindSnapshottable<?>> rewindAdapter() { return Optional.empty(); }
```

`Sonic1SpecialStageProvider` overrides `supportsRewind()` to `true` and returns a `Sonic1SpecialStageRewindAdapter` from `rewindAdapter()`. S2/S3K providers keep the defaults until their own specs land. This is a semantic capability (the provider attests "my update() is deterministic under recorded input and my sim state is snapshot-covered"), not a game/zone branch, matching the approved bonus-stage pattern. Shared runtime code (`GameLoop`, `LiveRewindManager`, `GameplayModeContext`) consults only provider capability and provider-owned optional adapter state — never the provider's concrete type or game identity.

### Extracted input derivation

Today the logical-input → MegaDrive-bitmask derivation is inline and private in `GameLoop.updateSpecialStageInput()` (`GameLoop.java:3793-3872`, with the private static `directionBits` helper at `:3874`). It reads `inputHandler.logical().player1()/player2()` and builds held bits from `directionBits(heldMask) | InputActionMasks.toMegaDriveButtonBits(actionHeldMask) | (startHeld ? 0x80 : 0)` (UP=0x01, DOWN=0x02, LEFT=0x04, RIGHT=0x08). Crucially, the **pressed** bits are read straight off the already-edge-computed logical snapshot — `directionBits(p1.pressedMask()) | InputActionMasks.toMegaDriveButtonBits(p1.actionPressedMask()) | (p1.startPressed() ? 0x80 : 0)` (`GameLoop.java:3850,3852,3856`) — the live path does **not** derive edges from a previous-held value; the manager simply stores what it is handed (`handleInput`, `Sonic1SpecialStageManager.java:1669-1672`). It then calls `provider.handleInput(held, pressed)` and `provider.handlePlayer2Input(p2Held, p2Logical)`.

A re-sim stepper must reproduce a recorded frame **identically**. Direction and A/B/C held masks are already recorded as held state in `Bk2FrameInput`, and replay's `RecordedInputSnapshots.fromBk2(current, previous)` computes pressed edges into the override's `pressedMask()`/`actionPressedMask()`/`startPressed()` (`RecordedInputSnapshots.java:20-31`). **Prerequisite fix:** Start must be split the same way before special-stage rewind can claim parity. Today `LiveRewindInputSource.appendFrame()` writes `p1.startPressed()`/`p2.startPressed()` into the `Bk2FrameInput` Start booleans (`LiveRewindInputSource.java:40,43`), while `RecordedInputSnapshots.fromBk2(...)` treats those booleans as current-frame held state. That is good enough for Start-edge consumers but loses a multi-frame Start hold, so the foundation must rename/semantically treat the existing fields as held state and record `startHeld()` in live rows. The pressed edge remains derived from current-held versus previous-held in `RecordedInputSnapshots`. With that correction, a mapper that simply **reads** the snapshot accessors is bit-identical on both paths with **no** previous-held parameter to track. The derivation is extracted into a small stateless unit consumable from both paths:

```java
// com.openggf.game (or com.openggf input support package — implementer's choice, one place only)
public final class SpecialStageInputMapper {
    public record MappedInput(int p1Held, int p1Pressed, int p2Held, int p2Logical) {}
    // Reads held + pressed directly off the snapshot; no previousP1Held.
    // Requires Bk2FrameInput Start booleans to carry held state, not edge-only state.
    public static MappedInput map(LogicalInputSnapshot logical) { ... }
}
```

`GameLoop.updateSpecialStageInput()` becomes: debug/alignment handling (unchanged, live-only) + `SpecialStageInputMapper.map(...)` + the two `provider.handleInput*` calls. The stepper uses the same mapper against the logical override installed from the recorded `Bk2FrameInput`. The debug-key reads and the alignment-test early-return in the current method are **live-only concerns and stay in `GameLoop`** — the stepper replays only the gameplay path.

Because the debug/alignment controls are not represented in `Bk2FrameInput`, any frame that presses one of those controls is a rewind boundary for the special-stage session. `GameLoop` must detect the live-only shortcut set wherever the shortcut is handled (the global `SPECIAL_STAGE_KEY` path in `stepInternal()` plus the mode-local gameplay-debug toggle, alignment-test toggle/adjustments, the F1 lag-model diagnostics display, and alignment-mode early-return controls), call `LiveRewindManager.markBoundary(MODE_EXIT_TO_NON_REWINDABLE)` before/while applying the live mutation, suppress the same frame's post-update `recordExternalFrame(...)`, and reject top-level rewind engagement for that frame. F6/F7 retain only their general debug-overlay behavior and do not mutate special-stage pacing. The next clean frame may install a fresh special-stage rewind session whose frame-0 keyframe captures the new `debugMode`/alignment state. This is why S1 `debugMode` remains Category A snapshot state even though the toggle itself is never replayed.

### `SpecialStageStepper`

```java
// com.openggf.game.rewind — package-private, sibling of LiveRewindStepper
final class SpecialStageStepper implements RewindSeekAwareEngineStepper {
    @Override public void step(Bk2FrameInput input) {
        // 1. Install RecordedInputSnapshots.fromBk2(input, previous) as the logical override
        //    (same mechanism as LiveRewindStepper). This is what pre-computes pressed edges.
        // 2. mapped = SpecialStageInputMapper.map(inputHandler.logical())  // reads pressed directly
        // 3. provider.handleInput(mapped.p1Held(), mapped.p1Pressed());
        //    provider.handlePlayer2Input(mapped.p2Held(), mapped.p2Logical());
        // 4. provider.update();
        // 5. finally: clearLogicalOverride().
    }
    @Override public void restoreToFrame(int frame, Bk2FrameInput input) {
        // No-op — like LiveRewindStepper. The mapper carries no cross-frame edge
        // state; pressed edges come pre-computed on the installed override.
    }
}
```

The stepper deliberately does **not** run debug-key handling, the alignment-test branch, `isFinished()`/`enterResultsScreen` dispatch, or rendering — those are `GameLoop` live-frame concerns. Live frames that do run debug/alignment shortcut handling are not recorded into the active window, so replay never has to synthesize those non-Bk2 inputs. The recorded window may include in-stage exit/finalization frames before the mode flips, but it never contains `SPECIAL_STAGE_RESULTS` frames: the `SPECIAL_STAGE -> SPECIAL_STAGE_RESULTS` boundary disengages rewind and severs the session (see hooks below). During held rewind, replaying a frame that sets the provider's finished state must not dispatch results; after release, the normal live `GameLoop` finish check resumes from the restored state and owns any transition. The stepper resolves `activeSpecialStageProvider` through the injected provider supplier (backed by `GameLoop.getActiveSpecialStageProvider()`, `GameLoop.java:3757`) — it must re-resolve per step rather than caching the provider instance, mirroring the boundary-policy rule that rewind components never trust stale cached references.

**No cross-frame edge state in the stepper/mapper.** Pressed edges are pre-computed onto each installed override by `RecordedInputSnapshots.fromBk2(current, previous)`; the mapper only reads `heldMask()`/`actionHeldMask()`/`startHeld()` plus `pressedMask()`/`actionPressedMask()`/`startPressed()`. The manager's own `heldButtons`/`pressedButtons` are Category A capture fields restored from the keyframe, but the stepper keeps no mirror of them — so there is nothing to re-seed and `restoreToFrame` is a no-op. The live input source must record Start held state every frame, otherwise frame N+1 of a held Start incorrectly replays as released.

### Install / teardown on the `SPECIAL_STAGE` mode edge

**What already works:** entering a special stage from `LEVEL` fires `MODE_EXIT_TO_NON_REWINDABLE` → `LiveRewindManager.clear()`, so level history is fully torn down and the buffer starts clean. Returning to `LEVEL` after the results screen fires `MODE_ENTER_REWINDABLE` → `ensureInstalled()`, which reinstalls the level stepper.

**What is missing:** nothing installs a special-stage session. Three gaps:

1. The `SPECIAL_STAGE` entry edge fires no `MODE_ENTER_REWINDABLE`.
2. `LiveRewindManager.ensureInstalled()`'s identity guard (`gameplayMode == installedGameplayMode && rewindController != null && inputSource != null`) would treat a special-stage entry as "already installed", because the special stage runs under the **same** `GameplayModeContext` as the level.
3. Once a rewind-capable special-stage session exists, the `SPECIAL_STAGE → SPECIAL_STAGE_RESULTS` mode flip must explicitly fire `MODE_EXIT_TO_NON_REWINDABLE`. Otherwise the top-level engagement hook stops calling `LiveRewindManager` because results mode is not rewindable, but the manager can remain logically/presentationally armed from the prior mode.

**Prerequisite — plumb mode/provider visibility into `LiveRewindManager` (M1).** As written today the manager cannot see either the mode or the active provider, so the mechanism below is un-actionable until this is fixed:

- `LiveRewindManager` is constructed with only `configService` (`GameLoop.java:334`).
- `GameplayModeContext.getGameMode()` is hardcoded to `GameMode.LEVEL` (`GameplayModeContext.java:628-631`) — it is **not** a live mode source.
- The active special-stage provider lives on `GameLoop` (`activeSpecialStageProvider`, `GameLoop.java:151`), not on the context.
- `handleModeEnterRewindableBoundary() → ensureInstalled()` (`LiveRewindManager.java:315-321`) receives no mode argument.

The design therefore injects a mode/provider accessor into `LiveRewindManager` at construction — a `Supplier<GameMode>` + `Supplier<SpecialStageProvider>` (or one small context holder exposing both) backed by `GameLoop.getCurrentGameMode()` / `GameLoop.getActiveSpecialStageProvider()` — and `ensureInstalled()` derives the **required stepper kind** from those suppliers on every call, including the boundary path. `SpecialStageStepper` is constructed with the same provider supplier so it re-resolves per step. The double-fire boundary itself is sound and `clear()` opens no null-out hole: `ensureInstalled()` rebuilds from `SessionManager.getCurrentGameplayMode()`. At boundary time the data is already resolvable — `currentGameMode` is already `SPECIAL_STAGE` (`GameLoop.java:576` runs before the boundary report at `:577`) and the provider is already set (`:1902` before `:1904`) — so once the supplier is plumbed the boundary path can derive the required kind correctly.

**Design:** `LiveRewindManager` tracks the **installed stepper kind** alongside the installed context:

```java
enum StepperKind { LEVEL_FRAME, SPECIAL_STAGE }
```

`ensureInstalled()` computes `requiredStepperKind()` from the supplier-provided current `GameMode` + active provider capability: `SPECIAL_STAGE` with `supportsRewind()` → `SPECIAL_STAGE`, else `LEVEL_FRAME`. The guard becomes *same context && same kind && controller/input present*. When the kind differs, it re-installs: fresh `LiveRewindInputSource`, the kind's stepper, `installPlaybackController(inputs, stepper, KEYFRAME_INTERVAL)` — which by design replaces the prior controller with a fresh empty `InMemoryKeyframeStore`. Keyframe interval stays 60; history budget stays `REWIND_HISTORY_SECONDS`; the `LIVE_REWIND_ENABLED` gate is unchanged.

The install trigger for the special-stage session is the mode-boundary reporter: `reportRewindModeBoundary(old, new)` additionally fires `MODE_ENTER_REWINDABLE` when the **new** mode is `SPECIAL_STAGE` **and** `getActiveSpecialStageProvider().supportsRewind()` (the provider is set in `doEnterSpecialStage` before the mode change at `GameLoop.java:1904`, so it is resolvable at boundary time). On the `LEVEL→SPECIAL_STAGE` transition this means **both** events fire from one boundary report, in this order: `MODE_EXIT_TO_NON_REWINDABLE` (sever the level timeline via `clear()`), then `MODE_ENTER_REWINDABLE` (install the special-stage session with the `SPECIAL_STAGE` stepper kind). On `SPECIAL_STAGE→SPECIAL_STAGE_RESULTS`, the reporter deregisters the special-stage adapter and fires `MODE_EXIT_TO_NON_REWINDABLE` for rewind-capable providers, clearing any active special-stage rewind presentation/session before results mode begins. A direct `SPECIAL_STAGE→LEVEL` transition must deregister, fire exit, then fire the level enter boundary so the fresh level frame-0 capture cannot include stale `"special-stage-runtime"` state. When the capability is `false`, only the existing level-exit event fires on entry, no session installs, and every `LiveRewindManager` entry point rejects the mode as today — an unsupported special stage behaves exactly as before this change.

Adapter registration rides the same edge via a `GameplayModeContext` hook. Unlike the older bonus-stage adapter, this hook must not branch on a concrete game provider: it deregisters `SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY`, then registers `provider.rewindAdapter()` only when `provider.supportsRewind()` and the optional adapter is present. Two load-bearing ordering points, to be asserted as tested invariants:

- **(a) Register between `GameLoop.java:1902` and `:1904`** — i.e. after `activeSpecialStageProvider` is set but **before** `changeGameModeForBoundary(SPECIAL_STAGE)`. The boundary report triggers `installPlaybackController`, and `RewindController` captures frame 0 in its constructor (`RewindController.java:76`); if the adapter is not yet registered its key is absent from the frame-0 composite, and a later restore hits the throwing `resetForMissingSnapshot()` (`RewindRegistry.restore` at `:93,:103`).
- **(b) Deregister before supported special-stage exit can install another session** — `reportRewindModeBoundary` must call `deregisterSpecialStageAdapter()` before emitting `MODE_EXIT_TO_NON_REWINDABLE` for a supported `SPECIAL_STAGE -> non-SPECIAL_STAGE` edge, so a following `MODE_ENTER_REWINDABLE` cannot capture stale special-stage state. Keep the top-of-`doExitResultsScreen` deregistration at `GameLoop.java:2555` as idempotent cleanup before `activeSpecialStageProvider` resets to `NoOpSpecialStageProvider.INSTANCE`.

Mirroring the bonus-stage lesson (develop `455066237`), a failed special-stage entry must also deregister. The `resetForMissingSnapshot()` default stays throwing so any registration-lifecycle bug fails loudly rather than silently dropping stage state.

### Widened mode gate + record/engage hooks

This mirrors the shipped bonus-stage split exactly: the bonus path has a **RECORD hook only** inside `updateBonusStageMode` (`GameLoop.java:1445-1448`, before its completion check at `:1451`), while **engagement is the top-level `stepInternal()` guard**, widened to `(currentGameMode == GameMode.LEVEL || isBonusStageRewindable())` (`GameLoop.java:785-791`), which early-returns and skips the whole mode update when rewind engages.

- `LiveRewindManager.isRewindableMode(mode)` adds `SPECIAL_STAGE`. All four entry points keep their existing structure; capability filtering happens at install time (no session is ever installed for a non-rewindable provider) plus a cheap provider-capability check in `handleRealtimeRewindInput`/`recordExternalFrame` so a stray call in an unsupported stage is inert.
- **Engage — widen the `stepInternal()` guard, not the mode update.** The top-level guard at `GameLoop.java:785` becomes `(currentGameMode == GameMode.LEVEL || isBonusStageRewindable() || isSpecialStageRewindable())`. `isSpecialStageRewindable()` is `currentGameMode == SPECIAL_STAGE && activeSpecialStageProvider.supportsRewind() && !specialStageTransitionPending` (and honors `rewindBlocked` via the existing `handleRealtimeRewindInput` argument). When rewind engages, `stepInternal()` early-returns before `updateSpecialStageMode()` ever runs — no engagement branch is added inside the mode update.
- **Record — inside `updateSpecialStageMode()`, before the finish check.** Add `liveRewindManager.recordExternalFrame(currentGameMode, specialStageTransitionPending, inputHandler)` after the provider's live frame completes but **before** `ssProvider.isFinished()` (`GameLoop.java:1056`), mirroring the bonus record-before-completion ordering (`:1445-1448` before `:1451`) — see m5 below. Skip this record hook when a live-only debug/alignment shortcut severed the special-stage rewind session earlier in the same frame. The recorded row is built from the same logical snapshot the live frame consumed, so replay input is bit-identical to live input.
- **Invariant:** `TestGameLoopSpecialStageRewindGate` — held rewind must disengage the moment a transition-pending flag is set — is preserved because `isSpecialStageRewindable()` includes `!specialStageTransitionPending`, exactly as `isBonusStageRewindable()` does for `bonusStageTransitionPending`; recording is skipped whenever the guard would not have engaged.

### Data flow

**Normal (recorded) frame in a rewind-supported special stage:**

1. `GameLoop.stepInternal()` reaches the top-level rewind guard at `:785`. `isSpecialStageRewindable()` is true but the rewind key is not held, so `handleRealtimeRewindInput` does not engage and the guard falls through (no early return).
2. Mode switch → `updateSpecialStageMode()`.
3. Debug keys → `updateSpecialStageInput()`: `SpecialStageInputMapper.map(...)` → `provider.handleInput/handlePlayer2Input` → `provider.update()`.
4. **Before** the `isFinished()` check (`:1056`), `liveRewindManager.recordExternalFrame(...)` appends this frame's `Bk2FrameInput` row; if the frame index is a multiple of 60 (or frame 0), `RewindRegistry.capture()` bundles all registered adapters — including `Sonic1SpecialStageRewindAdapter` — into the keyframe's `CompositeSnapshot`. Recording must precede the finish check because on the finishing frame `isFinished() → enterResultsScreen → doEnterResultsScreen` runs synchronously (`ssProvider.reset()` at `:2493`, `changeGameModeForBoundary(SPECIAL_STAGE_RESULTS)` at `:2496`) before control returns to record.
5. `isFinished()` check runs; on a non-finishing frame nothing further happens.

**Rewound (replayed) frame while the rewind key is held:**

1. The `stepInternal():785` guard's `handleRealtimeRewindInput` sees the held key, engages, and drives `RewindController.stepBackward()`; `stepInternal()` early-returns, so `updateSpecialStageMode()` does not run this frame.
2. Controller opens an `AudioReplayScope` (`beginAudioReplay`, reason `STEP_BACKWARD`), restores the nearest keyframe ≤ target — which invokes `Sonic1SpecialStageRewindAdapter.restore(snapshot)`, including render re-establishment — then replays forward via `SpecialStageStepper.step(row_k)` for each intermediate frame, caching per-frame sim snapshots in the `SegmentCache`.
3. Subsequent backward steps inside the cached segment restore cached sim snapshots directly — **no `update()` runs**, so the restored frame is presented purely from what `restore()` re-established.
4. The live provider render path then draws the restored state; audio presentation during the held window comes from the reverse-audio machinery, with re-triggered SFX suppressed by the replay scope.

## S1 Special Stage Component

### Shape

One adapter, one opaque snapshot payload. All S1 special-stage sim state lives in `Sonic1SpecialStageManager` — there are no sim subsystems (physics/collision/item logic are inline private methods; collaborators are the stateless statics `Sonic1SpecialStageBlockType`/`TrigLookupTable`, the constant `dataLoader`, and render objects). Given ~50 fields, a public record with 50 components would be noise; the design uses an **opaque snapshot class** (`Sonic1SpecialStageSnapshot`, package-private to the S1 special-stage package) holding primitives plus **cloned** arrays, produced and consumed only by the manager:

```java
// Manager grows two methods (called only by the adapter):
Sonic1SpecialStageSnapshot captureRewindSnapshot();
void restoreRewindSnapshot(Sonic1SpecialStageSnapshot snapshot);

// com.openggf.game.sonic1.specialstage
final class Sonic1SpecialStageRewindAdapter
        implements RewindSnapshottable<Sonic1SpecialStageSnapshot> {
    @Override public String key() { return "special-stage-runtime"; }
    ...
}
```

**Key choice:** `SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY == "special-stage-runtime"` — deliberately game-neutral. Exactly one special stage is ever active, so S2/S3K adapters reuse the same single-active-stage slot in the `CompositeSnapshot`, and `resetForMissingSnapshot()` semantics stay uniform across games. (The adapter is only registered while its stage is active, so `resetForMissingSnapshot()` should never fire in practice; keep the throwing default so a registration-lifecycle bug fails loudly.)

### Capture surface

**Category A — capture (sim state read/written by `update()`):**

| Group | Fields |
|---|---|
| Lifecycle | `finished`, `emeraldCollected`, `debugMode`, `currentStage`, `ringsCollected`. Also `initialized` (`:69`, gates `update()` at `:266`): capture it too, or document the invariant that it is always true during an active session — the round-trip test builds a bare manager, so capturing it keeps the test honest. Recommend capturing. |
| Maze layout | `byte[] layout` (0x4000). Only the block-buffer window `[0x1020, 0x3020)` (~0x2000 bytes) is ever mutated; capturing just that slice is a sanctioned optimization (the remainder is always-zero/reconstitutable). Start with the full-array clone; slice later if payload matters. |
| Rotation | `ssAngle`, `ssRotate`, `debugSavedAngle`, `debugSavedRotate` |
| Physics | `long sonicPosX/Y`, `sonicVelX/Y`, `sonicInertia`, `sonicAirborne`, `sonicFacingLeft` |
| Camera | `cameraX/Y` — **capture, don't re-derive**: it retains its value near the origin edge, so it is genuine state, not a pure function of position |
| Items | `ghostState`, `upDownCooldown`, `reverseCooldown` (`lastCollisionBlockId/Row/Col` are frame-transient — optional) |
| Animation | `ringAnimFrame/Timer`, `wallVramAnimFrame/Timer`, `int[8][4] ssAnimBuffer`, `int[8] ssAnimGlassFinalBlock`, `sonicAnimId/FrameIndex/FrameTimer`, `palSsTime/Num/Index`, `ani2Frame/Timer`, `ani3Frame/Timer`, `sonicSpriteFrame` |
| Exit sequence | `exitTriggered`, `exitPhase`, `exitTimer`, `exitFadeStarted`, `exitFadeTimer` |
| Input edge state | `heldButtons`, `pressedButtons` |
| BG animation | `bgAnimState`, `bgUsingPlane6`, `fgAnimPlaneIndex`, `fgYScroll`, `bgYScroll`, `bgExtraScrollX`, `int[20] bgSineBuffer`, `int[14] bgBandBuffer` |
| Palettes | `Palette[4] ssPalettes` — incrementally mutated by palette cycling; **must deep-copy the color values** (cannot be reconstructed from the `palSs*` counters alone) |

**Deep-copy on both capture and restore:** `layout`, `ssAnimBuffer` (2D — clone each row), `ssAnimGlassFinalBlock`, `bgSineBuffer`, `bgBandBuffer`, `ssPalettes` color values. A snapshot must never alias live arrays, or later live mutation corrupts stored keyframes (this is the level-side copy-on-write lesson applied here).

**Category B — exclude (render-derived):** `wallRotFrame` (pure function of `ssAngle`); `int[224] bgHScrollData`. Note `bgHScrollData` is **not** self-healing on restore: on the O(1) cached-snapshot path no `update()` runs for any step, so if it were merely excluded it would stay frozen for the **entire** backward scrub through a cached segment (not just one frame). The `restore()` render re-establishment therefore rebuilds it from the pure output producer `fillHScrollFromBands(...)` (see below); the alternative is to promote it to Category A (+~896 bytes).

**Category C — exclude (init-only / constant references):** `dataLoader`, `renderer`, `graphicsManager`, `sonicSpriteRenderer`, `sonicRollScript`, `sonicRoll2Script`, `bgRenderer`, `fgRenderer`, `bgCloudBase`, `bgFishBase`, `ssPaletteCycle1/2` (read-only ROM source tables), and the constant tilemaps `fgPlaneTilemaps`/`bgPlane5Tilemap`/`bgPlane6Tilemap` (loaded once, never mutated — only the **active-plane selection** `bgUsingPlane6`/`fgAnimPlaneIndex` is sim state, and that is Category A).

### `restore()` render re-establishment — critical

`SegmentCache` serves repeated backward steps by restoring sim snapshots directly **without re-running `update()`**, so nothing downstream will repair render caches. After restoring fields, `restoreRewindSnapshot` must do the following. Every call touching `graphicsManager`/`bgRenderer`/`fgRenderer` **must be null-guarded** (m6): those collaborators are null in a headless round-trip test, and the manager already guards similar paths (e.g. `:1260`).

1. **Re-upload all four palette lines:** `graphicsManager.cachePaletteTexture(ssPalettes[i], i)` for `i = 0..3`. Palette cycling pushes to the GraphicsManager palette-texture cache incrementally; without this, restored frames show stale colors.
2. **Re-select the active tilemaps:** `bgRenderer.setTilemap(bgUsingPlane6 ? bgPlane6Tilemap : bgPlane5Tilemap)` and `fgRenderer.setTilemap(fgPlaneTilemaps[fgAnimPlaneIndex])`.
3. **Mark both plane FBOs dirty:** `bgRenderer.markDirty()`, `fgRenderer.markDirty()`, forcing a redraw from the restored layout/animation state.
4. **Rebuild `bgHScrollData` with the pure output producer — NOT `updateBgAnimate()`.** `updateBgAnimate()` advances captured Category-A accumulators (`bgExtraScrollX++/--` and `bgYScroll++` at `:1443-1444,1462`, sine phase at `:1454-1455`, band decrement at `:1466-1469`), so calling it in `restore()` would push restored state one frame forward — a determinism bug. Instead call only the pure producer `fillHScrollFromBands(scrollBuffer, bandWidths)` (`:1490-1506`), branched on the restored `bgAnimState`: `bgAnimState < 8` → (`bgSineBuffer`, `SS_SINE_BAND_WIDTHS`), else → (`bgBandBuffer`, `SS_SCROLL_BAND_WIDTHS`). This reproduces the scanline scroll table from already-restored state without mutating any accumulator. (Equivalent alternative: capture `bgHScrollData` as Category A, +~896 bytes — the `fillHScrollFromBands` route is preferred as it stores nothing extra.)

### Payload budget

≈16.6 KB per keyframe with the full `layout` clone (≈8.8 KB with the block-buffer-slice optimization). At interval 60 over a 60-second history window that is ≈1 MB — comfortably inside the re-sim budget that motivated the model choice.

## Determinism Reconciliation

### Audio — resolved by the existing controller-owned replay scope

`GameServices.audio()` is **not** a rewind adapter; every `playSfx`/`playMusic`/`fadeOutMusic` from `Sonic1SpecialStageManager.update()` is fire-and-forget, so naive forward replay would audibly re-trigger them. This includes **music**, not just SFX: `update()` issues `playMusic(GameMusic.EXTRA_LIFE)` (`:589`) and `playMusic(GameMusic.EMERALD)` (`:600`). All of them route through `GameServices.audio()` (the `AudioManager`) and so are covered by the replay scope below; the manual gate must listen for double-triggered **music** as well as SFX. The existing LEVEL path already solves this **inside `RewindController`, not inside the stepper**: every forward-replay loop and cached-snapshot restore is bracketed by `AudioReplayScope ignored = beginAudioReplay(from, target, reason)` → `audioManager.beginRewindReplay(...)` (`RewindController.java:192`, `:278`, `:337`, `:490`), with the audio command timeline reconciled via `beginCommandTimelineFrame` / `discardAudioCommandsAfter` / `pruneAudioCommandsBefore` and restores applied under `AudioPresentationPolicy.SUPPRESSED_INTERNAL_RESTORE`. `LiveRewindStepper` itself contains **no** audio handling — confirming the mechanism is stepper-agnostic. `SpecialStageStepper` therefore inherits suppression for free.

**Implementation checkpoint (not an open design question):** verify during implementation that S1 special-stage SFX/music calls route through the same `AudioManager` command timeline the replay scope governs (they use `GameServices.audio()`, i.e. the `AudioManager`, so they should), and that reverse-audio PCM-history arming in `LiveRewindManager` behaves under the special-stage session the same way it does for LEVEL/BONUS_STAGE. Files: `LiveRewindManager.java`, `RewindController.java`, `LiveRewindStepper.java` (as the reference behavior), `LevelFrameStep.java` (reference only). The manual in-stage gate must include a listen-for-double-trigger check.

### Other hazards (all verified during investigation)

| Hazard | Status | Reconciliation |
|---|---|---|
| RNG advancement | None | No special stage advances `GameRng`; and `GameRng` is a registered rewind adapter regardless |
| Shared frame counter | None | Each stage uses its own captured `frameCounter`-style fields (Category A) |
| S1 `startFadeToWhite` (`Sonic1SpecialStageManager:871`) | Safe | Guarded once by `exitFadeStarted` (captured); `FadeManager` is a registered adapter and rolls back; replay re-issue is idempotent |
| S1 GameState writes in `update()` | None | S1 writes no GameState in `update()` |
| S3K emerald marks (`:759/:761`) — *Spec 3* | Safe | Set-once idempotent bit-sets; `GameStateManager` is a registered adapter and rolls back before replay |
| S3K `audio().setSpeedMultiplier(...)` — *Spec 3* | Covered | Audio command timeline under the replay scope; re-verify in Spec 3 |
| S2 wall-clock reads (`:990–:1062`) — *Spec 4* | Harmless | Feed only logger diagnostics, never gate the sim; exclude |
| S2 `lagAccumulator`/`lagCompensation` (`:221-222`) — *Spec 4* | Must capture | Instance fields that drive the lag-frame skip; Category A in Spec 4 |
| `Sonic2TrackFrameDecoder.lastUncIndex/lastRleIndex` statics — *Spec 4* | Harmless | Process-global diagnostics, no sim effect; exclude |
| Registered adapters (`GameStateManager`, `FadeManager`, `Camera`, `TimerManager`, `OscillationStaticAdapter`, `GameRng`) | Already covered | All roll back on backward seek; the special-stage managers themselves are covered only by the new adapter + replay designed here |

## Testing Strategy

End-to-end in-stage rewind cannot run headless (needs ROM + GL), so the automated suite proves the parts and a **manual gate** proves the whole.

### Unit tests (JUnit 5 / Jupiter only)

- **`TestSonic1SpecialStageRewindSnapshot`** — capture/restore round-trip against a bare manager built with **null** `graphicsManager`/`bgRenderer`/`fgRenderer` (the render re-establishment calls must be null-guarded per m6, so `restore()` runs headless):
  - Mutate sim state → `capture()` → mutate further (including in-place array writes to `layout`, `ssAnimBuffer`, `bgSineBuffer`, `bgBandBuffer`, palette colors) → `restore()` → assert full field equality with the first state, proving both value restoration **and** that the snapshot held clones, not aliases.
  - Assert `capture()` twice in a row yields equal-but-not-same array instances.
  - Assert `restore()` does **not** advance the BG accumulators (`bgExtraScrollX`, `bgYScroll`, sine phase, band buffer) — i.e. no `updateBgAnimate()` leaked into restore (M3 guard).
- **`TestGameplayModeContextSpecialStageRewindAdapter`** — provider-owned adapter registration against the generic special-stage key:
  - Assert registering the S1 provider includes `SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY` in a captured `CompositeSnapshot`, then deregistering removes it.
  - Assert `key()` is `"special-stage-runtime"` and `resetForMissingSnapshot()` keeps the throwing default.
- **`TestSpecialStageRewindCapability`** — `SpecialStageProvider` default `supportsRewind()` is `false`; `NoOpSpecialStageProvider` is `false`; the S1 provider is `true`; S2/S3K providers remain `false` (pins the rollout order).
- **`TestLiveRewindInputSourceStartHeld`** — pin the prerequisite input-row semantics before the special-stage mapper lands: hold Start for at least two live frames, append both rows, replay the second row through `RecordedInputSnapshots.fromBk2(current, previous)`, and assert `startHeld()==true` and `startPressed()==false`. This test fails on current `develop` because `appendFrame()` records `startPressed()` instead of `startHeld()`.
- **`TestSpecialStageInputMapper`** — the extracted derivation reproduces the previous inline behavior: held direction bits (UP=0x01/DOWN=0x02/LEFT=0x04/RIGHT=0x08), `InputActionMasks.toMegaDriveButtonBits` composition, Start held bit 0x80 in the held mask, Start edge bit 0x80 in the pressed mask, and **pressed bits read straight off the snapshot** (`pressedMask()`/`actionPressedMask()`/`startPressed()`) — the mapper takes no previous-held parameter. Include a case feeding the same `LogicalInputSnapshot` (as produced live vs. via a `RecordedInputSnapshots.fromBk2` override after the Start-held fix) and asserting identical masks, proving live and replay are bit-identical.
- **`TestLiveRewindManagerSpecialStageMode`** — `isRewindableMode(SPECIAL_STAGE)` is true; entry points remain inert when no session is installed / capability is false; the mode/provider suppliers (M1) resolve correctly; **stepper-kind re-install**: same `GameplayModeContext` with a changed required kind re-installs (fresh controller/input source), same kind is a no-op (mirrors `TestLiveRewindManagerBonusStageMode` idiom).
- **`TestSpecialStageStepperReplay`** — with a scripted fake provider supplied via the injected provider supplier, `step(row)` maps the row through the mapper, calls `handleInput`/`handlePlayer2Input`/`update()` exactly once in order, skips debug/alignment behavior, never dispatches results even if `update()` makes `isFinished()` true, clears the logical override on exception, keeps no cross-frame edge state (`restoreToFrame` is a no-op), and re-resolves the active provider per step.
- **`TestGameLoopSpecialStageRewindDebugBoundary`** — pressing any live-only special-stage shortcut (`SPECIAL_STAGE_KEY`, X/Z stage/layout debug, complete/fail, sprite/plane debug, sprite-debug navigation, gameplay-debug toggle, alignment toggle/adjustment, or F1 lag-model display) in a rewind-capable special stage calls `MODE_EXIT_TO_NON_REWINDABLE`, skips same-frame recording, and lets the next clean frame install a fresh session whose frame-0 snapshot captures the resulting state. Also assert top-level rewind engagement is rejected on those live-only shortcut frames, while F6/F7 only toggle their general camera/player-bound overlays.

### Invariant preservation

- **`TestGameLoopSpecialStageRewindGate` must stay green with its existing assertions untouched.** The new engagement/record hooks in `updateSpecialStageMode` sit behind the same `rewindBlocked` / `specialStageTransitionPending` short-circuit the test enforces. New cases may be added (never weakened): assert held rewind also never engages when the provider's `supportsRewind()` is false.
- **S3K must-green set stays green:** `TestS3kAiz1SkipHeadless`, `TestSonic3kLevelLoading`, `TestSonic3kBootstrapResolver`, `TestSonic3kDecodingUtils` (this work touches no S3K provider behavior — their `supportsRewind()` stays default-false — but run the set anyway).
- Existing bonus-stage rewind tests (`TestBonusStageRewindCapability`, `TestBonusStageCoordinatorRewindAdapter`, `TestLiveRewindManagerBonusStageMode`) must be unaffected by the `isRewindableMode` and `ensureInstalled` changes.

### Manual gate (explicit; required before merge)

With `s1.gen`, enter an S1 special stage and verify: (1) held rewind visibly rewinds maze rotation, Sonic's motion, collected items/rings, palette cycling, and BG animation with no stale colors/tilemaps/FBO content after release; (2) repeated short back-steps within one segment (cached-snapshot path) render correctly; (3) no double-triggered SFX/music during or after rewind; (4) rewinding across a keyframe boundary (>60 frames) is seamless; (5) the exit/fade sequence disengages rewind as soon as the mode flips to `SPECIAL_STAGE_RESULTS`; (6) level rewind still works normally after returning from the stage; (7) an S2/S3K special stage still has rewind fully inert.

## Risks / Open Questions

1. **Identity-guard re-install trigger.** The stepper-kind tracking in `ensureInstalled()` is the one genuinely new mechanism in `LiveRewindManager`. Risk: a missed edge (e.g. special-stage entry from a non-LEVEL mode, or editor interplay) leaves the wrong stepper installed. Mitigation: derive the required kind from current mode + provider capability *every time* `ensureInstalled()` runs, not from the transition that triggered it; cover with `TestLiveRewindManagerSpecialStageMode`.
2. **Audio suppression completeness.** Design-resolved via the controller-owned `AudioReplayScope`, but the implementation checkpoint above (command-timeline routing of S1 special-stage audio calls + PCM-history arming under the new session) must be verified, and the manual gate listens for double-triggers. If a gap surfaces, the fix belongs in `AudioManager`'s replay-scope handling, not in the stepper.
3. **Render re-establishment correctness.** The three mandatory `restore()` steps are derived from code reading, not yet from a running restore; the cached-snapshot (no-`update()`) path is exactly where an omission would show. The manual gate's items (1)–(2) are the acceptance check; budget for adding a missed cache re-prime if one appears.
4. **`bgHScrollData` rebuild.** This is a determinism concern, not cosmetic: `updateBgAnimate()` must **not** be called in `restore()` (it advances captured accumulators). `restore()` calls only the pure `fillHScrollFromBands(...)`, or the field is captured as Category A. `TestSonic1SpecialStageRewindSnapshot`'s round-trip asserts a `restore()` leaves the accumulators (`bgExtraScrollX`, `bgYScroll`, sine phase, band buffer) unchanged from the captured values, catching an accidental `updateBgAnimate()` regression.
5. **Exit-window frames.** `specialStageTransitionPending` is set only on **entry** (`GameLoop.java:1879`, cleared `:1897`) and is never set during the exit spin. Recording stops when the mode flips to `SPECIAL_STAGE_RESULTS`, and the boundary reporter must also fire `MODE_EXIT_TO_NON_REWINDABLE` on that edge so an active special-stage rewind presentation/session is cleared. Frames recorded during the exit/fade sequence replay the fade idempotently (`exitFadeStarted`-guarded; `FadeManager` rolls back); the manual gate item (5) should confirm no visual hiccup.

## File-Touch List

**Create:**

- `src/main/java/com/openggf/game/SpecialStageInputMapper.java` *(final package placement at implementer's discretion, but one shared unit)*
- `src/main/java/com/openggf/game/rewind/SpecialStageStepper.java`
- `src/main/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageSnapshot.java` *(alongside the manager; adjust to the manager's actual package)*
- `src/main/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageRewindAdapter.java` *(colocated with the snapshot so package-private manager snapshot methods remain local)*
- Tests: `src/test/java/com/openggf/game/TestSpecialStageRewindCapability.java`, `src/test/java/com/openggf/game/TestSpecialStageInputMapper.java`, `src/test/java/com/openggf/game/rewind/TestLiveRewindInputSourceStartHeld.java`, `src/test/java/com/openggf/game/sonic1/specialstage/TestSonic1SpecialStageRewindSnapshot.java`, `src/test/java/com/openggf/game/session/TestGameplayModeContextSpecialStageRewindAdapter.java`, `src/test/java/com/openggf/game/rewind/TestLiveRewindManagerSpecialStageMode.java`, `src/test/java/com/openggf/game/rewind/TestSpecialStageStepperReplay.java`, `src/test/java/com/openggf/TestGameLoopSpecialStageRewindDebugBoundary.java`

**Modify:**

- `src/main/java/com/openggf/game/SpecialStageProvider.java` — add `default boolean supportsRewind() { return false; }`, `SPECIAL_STAGE_REWIND_KEY`, and default-empty `rewindAdapter()`
- `src/main/java/com/openggf/game/rewind/LiveRewindInputSource.java` / `src/main/java/com/openggf/debug/playback/Bk2FrameInput.java` — make the recorded Start booleans carry held state every frame (`startHeld()` from live input); `RecordedInputSnapshots.fromBk2(...)` continues deriving the edge by comparing current held vs previous held
- S1 special-stage provider — override `supportsRewind()` to `true`
- `src/main/java/com/openggf/game/sonic1/.../Sonic1SpecialStageManager.java` — `captureRewindSnapshot()` / `restoreRewindSnapshot(...)` incl. render re-establishment
- `src/main/java/com/openggf/game/rewind/LiveRewindManager.java` — accept the mode/provider suppliers (`Supplier<GameMode>` + `Supplier<SpecialStageProvider>`, or a small context holder) at construction (M1); add `StepperKind` tracking; have parameterless `ensureInstalled()` derive `requiredStepperKind()` from the suppliers on every call (`:252`, boundary path `:315-321`); construct `SpecialStageStepper` with the provider supplier; widen `isRewindableMode`; capability checks at entry points
- `src/main/java/com/openggf/game/session/GameplayModeContext.java` — `registerSpecialStageAdapter(...)` / `deregisterSpecialStageAdapter()` using only `SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY`, `supportsRewind()`, and `rewindAdapter()`
- `src/main/java/com/openggf/GameLoop.java` — pass the mode/provider suppliers into the `LiveRewindManager` constructor (`:334`) (M1); widen the top-level engagement guard at `:785` with `|| isSpecialStageRewindable()` and add that predicate (M2); extract input derivation from `updateSpecialStageInput()` (`:3793-3872`, `:3874`) into the mapper (reading pressed off the snapshot, M4); add live-only debug/alignment shortcut detection that severs and suppresses same-frame special-stage rewind recording; add the RECORD hook in `updateSpecialStageMode()` before the finish check (`:1056`, m5) — no engage hook here; fire `MODE_ENTER_REWINDABLE` on the capability-true `SPECIAL_STAGE` edge in `reportRewindModeBoundary`; deregister the adapter before any supported `SPECIAL_STAGE` exit boundary that may be followed by a level enter; register the adapter between `:1902` and `:1904` and keep the `:2555` results-screen deregistration as idempotent cleanup, with deregistration on failed entry
- Docs: `CHANGELOG.md`, `docs/status/known-discrepancies.md` (within-stage-only rewind scope note), `README.md` on merge to `develop`, plus the required commit trailers

**Explicitly untouched:** `LiveRewindStepper.java`, `LevelFrameStep.java`, `RewindController.java`/`SegmentCache` (the foundation plugs into their existing seams), all S2/S3K special-stage code (capability stays default-false until Specs 3–4).
