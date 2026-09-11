# Live Monitor Rewind Follow-up Design

## Problem

Sonic 2 monitors reportedly remain visibly broken during normal live gameplay rewind. Production `ObjectManager.rewindSnapshottable()` characterization now proves that direct object restoration is correct: forced reconstruction and the normal reuse-enabled policy both restore the intact monitor, remove monitor-content and explosion children, restore placement and slot topology, produce an equivalent immediate snapshot, and remain intact after one resumed update.

The remaining defect therefore lies after snapshot capture or outside direct object restoration: frame selection/keyframe replay in `RewindController`, held-input orchestration in `LiveRewindManager`, or presentation/render-command state.

## Scope

This follow-up targets normal live gameplay rewind only. It preserves the existing snapshot schema and object restore implementation unless a new integration test proves those layers receive or emit different state through the live controller path.

Audio, VHS effects, Trace Test Mode presentation, unrelated render caches, and other games are out of scope except as regression verification. Only a presentation cache proven to own the stale monitor output may change.

## Layered Reproduction

### RewindController boundary

The first test will use the production `RewindRegistry`, `ObjectManager` adapter, and `RewindController` timeline:

1. Materialize and initialize an intact Sonic 2 monitor.
2. Use a replayable `EngineStepper` whose recorded logical input row contains a dedicated break action; when that action is present, the stepper invokes the real monitor touch/break path during the engine step before the frame is captured.
3. Keep controller frame 0 as the intact keyframe, record the break input and step to frame 1, then step normally to frame 2.
4. First seek/step to frame 1 and prove keyframe restore plus input re-simulation reconstructs the broken monitor and break children. Then step/seek to frame 0 and prove the intact monitor is restored.
5. Assert only controller-owned results: selected frame number, monitor collision/broken state, absence of break children, and object snapshot equivalence.

The test must prove the broken frame is reconstructed from the recorded input row, not from an out-of-band mutation or a per-frame snapshot assumption. It must not call `ObjectManager.restore()` directly. The stepper may be a focused deterministic fixture, but it must use the production `EngineStepper`/`InputSource` contracts and the real monitor callback/object manager; replaying the same recorded row from frame 0 must reproduce frame 1's break. A >60-frame variant uses the same replayable break action with a retained keyframe interval crossing.

### LiveRewindManager boundary

If `RewindController` passes, a second test will build a production `GameplayModeContext` fixture, register the real object adapter, and let `LiveRewindManager.ensureInstalled()` create and install its own `LiveRewindInputSource`, `LiveRewindStepper`, playback controller, and rewind controller through the context. No prebuilt controller is injected. The test will seed history through that installed context, drive the normal held-rewind input path, and assert that held input selects the expected earlier cursor/frame and restored monitor state before release/replay cleanup.

This test will use the smallest existing `TestLiveRewindManager*` fixture and avoid GLFW/OpenGL. Introducing a controller injection seam is not part of this design; if a production context fixture cannot expose the required object timeline, stop and specify that ownership seam separately.

### Presentation boundary

If the controller and live-manager state/topology tests pass, a third test will render the restored live object graph. Before each render, it will clear the recording sink and renderer invocation history so commands from the broken frame cannot be confused with newly emitted commands. It will invoke the real `ObjectManager` render/bucket path used by the level renderer, with the real `MonitorObjectInstance` and `ObjectRenderManager`; only `PatternSpriteRenderer`'s draw sink may be a recorder/mock.

The intact held-frame oracle is a monitor `drawFrameIndex` call using a non-broken mapping frame (never `BROKEN_FRAME == 0x0B`) at the restored monitor coordinates, with no draw calls from `MonitorContentsObjectInstance` or `ExplosionObjectInstance`. The broken-frame precondition must first observe frame `0x0B` plus break-child rendering. If live state is intact but newly emitted commands still select `0x0B`, presentation is the first failing boundary.

## Root-cause Rule

Only the first failing boundary may change:

- Controller state/topology failure: repair snapshot cursor selection or restore sequencing.
- Live manager failure with green controller: repair held-input orchestration or context cursor timing.
- Correct live-manager state but stale newly emitted render commands: invalidate or rebuild only the proven object render cache/bucket/presentation state after restore.

No unconditional render-cache invalidation, monitor-specific live-rewind exception, zone check, route check, or frame-number carve-out is allowed.

If both boundaries and render-command output are green, stop without a production change and report that the symptom requires a reproducible full-window capture or user-provided exact gameplay sequence.

## Testing and Verification

Confirmed defect reproductions must fail before production changes. Negative-control layers may pass and select the next boundary.

Mandatory verification includes:

- New controller monitor regression; when it passes, the live-manager regression is mandatory; when both pass, the presentation regression is mandatory.
- Existing Sonic 2 monitor and object-adapter characterization tests.
- `TestLiveRewindManager*`, `TestRewindController`, and relevant render-order tests.
- `TestRewindCoverageGuard`, `TestStaticStateRewindCoverageGuard`, and `TestRewindArchitectureGuard`.
- All `*Rewind*` tests.

The cross-game `*TraceReplay` sweep remains best-effort and must report exact unrelated failures without modifying trace data.

### Manual live-gameplay acceptance

With a valid `s2.gen`, start normal Sonic 2 gameplay in Emerald Hill Zone Act 1 and use the first monitor encountered on the standard route. Allow at least 60 intact frames of rewind history, break the monitor with a rolling jump, then hold configured live rewind until the cursor crosses back before the break. While the key is held, verify the intact monitor casing/frame is visible and monitor contents/explosion are absent. Release rewind and verify the monitor remains intact until legitimately broken again. Repeat with a short rewind that crosses only a few frames and with a rewind crossing more than 60 frames/keyframe history. Record the exact coordinates/frame counters used in the final test report.

## Success Criteria

- Live rewind selects an intact pre-break monitor frame when stepping before destruction.
- The held live-rewind presentation renders the intact monitor frame and no break-only children.
- The first failing boundary has a focused regression and minimal universal fix.
- Existing object-adapter, Masher, Buzzer, coverage, and rewind suites remain green.
