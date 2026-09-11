# Trace Test Mode Camera Focus

Date: 2026-05-04
Status: Draft

## Summary

While a trace session is paused, allow the user to cycle the camera between up to five focus targets using Left/Right arrow keys, then snap the camera back to its original position on unpause. Useful for visually inspecting where the engine vs. ROM trace believe each character is at the moment of a divergence.

The five focus options are:

| Mode               | Label              | Target                                                  |
|--------------------|--------------------|---------------------------------------------------------|
| `DEFAULT`          | `Default`          | Camera position at the moment pause was entered         |
| `SIDEKICK_ENGINE`  | `Sidekick (Eng)`   | Centred on engine's first sidekick                      |
| `SIDEKICK_TRACE`   | `Sidekick (Trace)` | Centred on first sidekick's recorded ROM-trace position |
| `MAIN_ENGINE`      | `Main (Eng)`       | Centred on engine's main playable sprite                |
| `MAIN_TRACE`       | `Main (Trace)`     | Centred on main player's recorded ROM-trace position    |

Trace variants are skipped when their position equals the engine variant's position, so the cycle never offers a "no-op" entry. Sidekick options are skipped entirely when no sidekick exists in the engine. Main options are skipped when the main player is despawned (death, end-of-level, etc.). `DEFAULT` is always present.

## Activation gate

The feature is only active when `TraceSessionLauncher.active() != null`. In any other context (normal pause during regular gameplay) the camera continues to behave exactly as today.

## Component: `TraceCameraFocusController`

Lives in `com.openggf.testmode`. Pure, headless-testable.

### State

- `LiveTraceComparator comparator` (constructor-injected; source of `currentVisualFrame`)
- `FocusMode active` (current selection)
- `List<FocusMode> available` (cycle list, frozen at pause-entry; recomputed on frame-step boundaries)
- `int activeIndex`
- `short savedCamX`, `short savedCamY` (camera snapshot at pause-entry)
- `boolean wasPaused` (edge detector)
- `FocusMode reapplyAfterStep` (non-null when a frame-step is in progress and we owe a re-apply)

### Lifecycle

The controller is ticked at the **top of `GameLoop.stepInternal()`**, before any other logic and before the existing `isPaused()` early-return. A single ordered sequence handles every transition.

```
tick(inputHandler, camera, comparator):
  paused    = isPaused()
  frameStep = paused && wasPaused && inputHandler.isKeyDown(frameStepKey)
              # NOTE: read the same way GameLoop reads it; edge-detect via
              # isKeyPressed if and only if GameLoop does too. The point is
              # to know whether THIS tick will result in a one-step advance.

  if !wasPaused && paused:
      # Pause-edge enter
      savedCamX = camera.getX()
      savedCamY = camera.getY()
      buildAvailable(comparator)
      activeIndex = 0           # DEFAULT
      reapplyAfterStep = null
      # camera is already at savedCam; no setter call needed

  else if wasPaused && !paused:
      # Pause-edge exit (also catches the unpause from a non-frame-step path)
      camera.setX(savedCamX)
      camera.setY(savedCamY)
      clearAllState()

  else if paused && wasPaused:
      if reapplyAfterStep != null:
          # Previous tick was a frame-step. Gameplay has now advanced one step,
          # camera is at savedCam (we restored it before the step). Rebuild the
          # cycle in case main/sidekick spawn state changed, then re-apply the
          # focus the user had selected (or fall back to DEFAULT if gone).
          buildAvailable(comparator)
          activeIndex = indexOf(reapplyAfterStep, available, fallback=0)
          applyFocus(camera, available[activeIndex])
          reapplyAfterStep = null

      else if frameStep:
          # Restore camera to the saved position so this tick's stepInternal
          # runs gameplay updates against the original camera. Remember which
          # focus to reapply next tick.
          reapplyAfterStep = available[activeIndex]
          camera.setX(savedCamX)
          camera.setY(savedCamY)

      else:
          # Normal paused tick: handle cycle input.
          # LEFT/RIGHT resolved from SonicConfiguration.LEFT / .RIGHT (P1 bindings)
          if inputHandler.isKeyPressed(p1Left):  activeIndex = (activeIndex - 1 + n) % n
          if inputHandler.isKeyPressed(p1Right): activeIndex = (activeIndex + 1) % n
          if (any change): applyFocus(camera, available[activeIndex])

  wasPaused = paused
```

The frame-step path is the subtle one: when the user frame-steps, the controller writes the saved camera back **before** the step runs (so object placement, ring spawn windows, etc. all see the original camera), then re-applies the chosen focus on the next tick. This guarantees that gameplay state advances exactly as it would under a normal pause/frame-step, with the focus camera being purely cosmetic.

### Cycle list construction

Built when entering pause (and rebuilt after each frame-step). All position comparisons use the trace coordinate convention (16-bit ROM-RAM `x`/`y`, equivalent to the engine's `getCentreX/Y`).

```
available = [DEFAULT]

mainEngineSprite     = SpriteManager.getActiveSprite()           // null if despawned
firstEngineSidekick  = first(SpriteManager.getSidekicks())       // null if none
traceFrame           = comparator.currentVisualFrame()           // null on first frame
traceSidekick        = traceFrame?.sidekick                      // null pre-v5

if firstEngineSidekick != null:
    available += SIDEKICK_ENGINE
    if traceSidekick != null && traceSidekick.present():
        if (traceSidekick.x, traceSidekick.y) != (sk.centreX, sk.centreY):
            available += SIDEKICK_TRACE

if mainEngineSprite != null:
    available += MAIN_ENGINE
    if traceFrame != null:
        if (traceFrame.x, traceFrame.y) != (main.centreX, main.centreY):
            available += MAIN_TRACE
```

Cycle order is the order entries are added (matches the user-visible numbering in the original feature description).

### Camera mutation contract

The controller **must** mutate only `camera.setX(short)` and `camera.setY(short)`. It must not call `Camera.updatePosition(...)`, `setShakeOffsets`, or any other camera method, and it must not invoke any sprite/object/manager update path.

Justification:
- `Camera.setX/setY` are pure field writes (`camera/Camera.java:538-548`).
- `GraphicsManager.flush()` reads `cam.getXWithShake()`/`getYWithShake()` fresh every frame, so the next rendered frame after a setter call shows the new viewpoint with no further work.
- All consumers of camera position (object placement windows, ring spawning, parallax, HScroll) sample it during their own `update()` calls, which are gated behind the existing `isPaused()` early-return and don't run while paused.
- `updatePosition()` recomputes `x` from the focused sprite and would clobber our focus on the same frame; not calling it is what makes the feature safe.

A doc comment on the controller pins this contract down for future hands.

### Camera centring math

For each focus target:

```
camera.setX((short) clamp(targetCentreX - viewportWidth / 2,  camera.minX, camera.maxX))
camera.setY((short) clamp(targetCentreY - viewportHeight / 2, camera.minY, camera.maxY))
```

For `DEFAULT`, write back `savedCamX`/`savedCamY` directly with no clamping.

The clamp uses `Camera.clampAxisWithWrap` (already used by `updatePosition`) so behaviour at signed-domain wrap-around boundaries matches the camera's normal logic.

### Input handling

While paused, on each tick:
- P1 Left pressed (`inputHandler.isKeyPressed(configService.getInt(SonicConfiguration.LEFT))`, edge-triggered): `activeIndex = (activeIndex - 1 + available.size()) % available.size()`
- P1 Right pressed (`SonicConfiguration.RIGHT`): `activeIndex = (activeIndex + 1) % available.size()`
- Apply focus via the centring math above (or restore for `DEFAULT`).

The cycle keys are the user-configurable P1 Left/Right movement keys — same bindings as in-game movement. They're inert during normal pause (movement input is gated by `!isPaused()`), so reusing them is safe and means the user doesn't have to learn a new control. The P1 input handler resolves both keyboard and gamepad bindings, so the d-pad / left-stick also cycles.

Frame-step key is read first to avoid double-handling the same input.

## HUD: `TraceHudOverlay` additions

When paused **and** a `TraceCameraFocusController` is wired in, render two right-aligned lines anchored to the top-right corner:

```
                                                                       Camera: <Mode>
                                                                       <- -> Cycle Cameras
```

- `Camera: ` is static; the suffix changes per `FocusMode` label.
- The hint line is shown unconditionally (whenever the main line is visible).
- Right-alignment uses `PixelFontTextRenderer.measureText(...)` (or new helper if absent) to compute the X anchor as `viewportWidth - margin - measuredWidth`.

The existing bottom-left HUD is unchanged.

## Wiring

- **`TraceSessionLauncher`**: construct `TraceCameraFocusController(comparator)` alongside the existing `LiveTraceComparator` and `TraceHudOverlay`. Pass it to:
  - `GameLoop` via a setter so `stepInternal()` can tick it.
  - `TraceHudOverlay` via constructor injection so it can read the active label.
- **`GameLoop.stepInternal()`**: at the top, after `refreshRuntimeBindings()` and before any pause-related logic:
  ```java
  if (cameraFocusController != null) {
      cameraFocusController.tick(inputHandler, GameServices.camera(), configService);
  }
  ```
- **`TraceHudOverlay.render`**: after the existing block, call a new `drawTopRightOverlay(text, controller)` method.

When the trace session ends (launcher cleared), `GameLoop` clears its reference to the controller. No persistent state survives a session.

## Tests

New JUnit 5 tests in `src/test/java/com/openggf/testmode/`:

- **`TraceCameraFocusControllerTest`**:
  - Cycle list with all five modes available.
  - Sidekick-absent run (only DEFAULT/MAIN_ENGINE/MAIN_TRACE).
  - Main player despawned (only DEFAULT).
  - Trace and engine positions matching → trace variant skipped.
  - Pre-v5 trace (no `sidekick` field) → no SIDEKICK_TRACE.
  - Left/Right cycling wraps correctly at both ends.
  - On unpause, exactly one `setX(savedX)` and one `setY(savedY)` call observed on a Camera mock; no other Camera methods invoked.
  - On frame-step, camera is restored to saved values before the step runs, then re-applied to the previously-selected focus on the next tick.
  - When activated focus is gone after a frame-step (e.g. sidekick despawned mid-step), controller falls back to DEFAULT.
- **`TestTraceHudOverlay`** (extend existing):
  - Paused + controller wired → top-right `Camera: <label>` and `<- -> Cycle Cameras` strings rendered.
  - Unpaused → top-right strings absent.
  - No controller wired → top-right strings absent (regression guard).

## Out of scope

- No persistence of the active focus across pause/unpause cycles.
- No focus on objects or arbitrary world coordinates (only main and first sidekick, plus default).
- No animation/interpolation; focus snaps instantly.
- No new config keys for the cycle bindings; the existing P1 `LEFT`/`RIGHT` config keys are reused (they're inert during normal pause).
- No support for multiple sidekicks beyond the first (the trace schema only carries one).
