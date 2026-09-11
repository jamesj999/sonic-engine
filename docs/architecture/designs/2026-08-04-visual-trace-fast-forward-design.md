# Visual Trace Fast-Forward — Design

**Date:** 2026-08-04
**Status:** Implemented
**Scope:** Visual Trace Test Mode playback speed. No change to headless trace replay,
`TraceCaptureTool`, live gameplay, or the recorded trace contract.

## Goal

Let a visual trace session run faster than real time while it is playing, on a speed
ladder of **1x, 1.5x, 2x, 3x, 5x**. Right steps up, Left steps down. Audio speeds up with
the picture, and the VHS tape effect comes on and scales with the rate.

Left/Right are the existing `LEFT`/`RIGHT` bindings (arrow keys by default), matching
`TraceCameraFocusController`, which already owns those keys **while paused** for its focus
cycle. The ladder only moves while playback is running, so the two never contend.

## Transport

`TracePlaybackSpeedController` (`com.openggf.testmode`) holds the ladder and hands out
whole extra gameplay steps per outer frame through an accumulator, so 1.5x alternates one
and two extra-step frames.

`GameLoop.step()` opens the frame with
`TraceSessionLauncher.beginFastForwardOuterFrame(input, paused)` and then pumps that many
additional `stepInternal()` passes into the same rendered frame — the same shape as the
existing user-recording fast-forward pump directly below it, except the scene is still
rendered (that is the point here) and the count comes from the ladder rather than a fixed
cap. The ladder is read before the first step because `stepInternal()` ends by consuming
key edges. `isFastForwardPumpAllowed()` is re-checked between pumped steps: any of them
can end the session, engage a rewind, or start the completion fade.

## Audio

The SMPS driver is sample-clocked — it advances with the samples the mixer pulls, not with
gameplay ticks — so pumping extra steps alone would leave audio at 1x against an N x
picture. `AudioPresentationProducer.setForwardRate(double)` is the forward mirror of the
existing `setReverseRate`: the FORWARD path renders `rate x` an outer frame of source
audio and decimates it, nearest-neighbour, into the one packet the frame is allowed to
emit. Fast-forward therefore sounds like tape: faster **and** pitched up, exactly as
rewind at speed already does.

The source is pulled in chunks no larger than the mixer's declared capacity, so no buffer
has to be sized for the fastest rate, and one `beginRendering()`/`endRendering()` bracket
spans all the chunks. Packet length, history writes, and capture fan-out are unchanged:
still exactly one clocked packet per outer frame, and history stores the decimated packet
that was actually heard.

`MAX_FORWARD_RATE` (8.0) bounds the worst case — each whole multiple costs another full
mixer pass inside the one frame. It is a cost ceiling, not a musical one.

`TraceSessionLauncher` restates the rate every outer frame rather than only on change, the
way `LiveRewindManager` restates the reverse rate: the producer can be rebuilt mid-session
and comes back at real time. Both session exit paths (`retryPendingTeardown` and
`abortIncompleteSession`) run `resetFastForward()`, because the rate lives on the shared
producer and outlives the session that set it.

## Presentation

`TraceSessionLauncher` owns a `RewindEffectEnvelope` driven by both of its transports:
fast-forward ticks it with the ladder rate, held rewind ticks it at base speed (trace
rewind walks a fixed one step per frame). `RewindEffectEnvelope` already clamps latched
speed to 0.25..4.0, so the 5x rung presents as 4.0 — the shader's own clamp, and its top
tear band saturates at 2.0 regardless.

`RewindVhsEffectPass.apply(...)` gained a signed `scrollDirection`
(`REWIND_SCROLL_DIRECTION` / `FAST_FORWARD_SCROLL_DIRECTION`) so a fast-forwarding
transport scrolls its tear bands the opposite way from a rewinding one. Only the band
motion is signed; the tape damage itself is the same either way, so `speed` stays the
unsigned magnitude the shader clamps.

`GameLoop.tapeEffectIntensity()/tapeEffectSpeed()/tapeEffectScrollDirection()` prefer an
active trace session's envelope over `LiveRewindManager`'s. The two are mutually
exclusive: `stepInternalBody()` already routes rewind to the trace session **instead of**
`liveRewindManager` for the whole session, so the live envelope is stale throughout.
`liveRewindEffectIntensity()` survives for the live-capture presentation state alone, so a
visual trace transport is not classified as a live rewind for recording.

## HUD

The legacy `== PLAYBACK ==` debug panel stands down for the whole of a trace session and
the trace HUD renders the transport itself, pinned right-aligned to the top-right corner
(`STATUS_TOP_Y`, clear of both the ROM's left-anchored top bar and the paused
camera-focus block at `TOP_Y`):

```
                                                    ghz1.bk2
                                                 Mode: LEVEL
                                            Frame: 1234/5678
                                              Rate: < 1.5x >
```

`Rate` is the fast-forward ladder — Right increases, Left decreases — and both arrows stay
drawn at the ends of the ladder so the line keeps its width. Frame is
`cursor / lastFrameIndex`, the same pair the legacy panel showed.

Dropped from the legacy panel: `Input` (the trace HUD already draws the BK2 input glyphs),
`Session started` / status message (redundant), `State`, and `First Active`. `Movie` and
`Mode` are kept.

`PlaybackDebugManager.setOverlayOwnedExternally(boolean)` is the handoff — it empties
`buildOverlayLines()` and clears `isHudVisible()`, so the panel neither draws nor reserves
overlay state. `TraceSessionLauncher.becomeActiveSession()` /
`releaseActiveSession()` are the single choke points for it: every entry and exit path
routes `activeSession` through those two methods precisely so no path can miss the
handoff. `SpecialStageTraceHudOverlay` delegates wholesale to `TraceHudOverlay`, so
special-stage segments get the same block without their own wiring.

The fast-forward rate is deliberately **not** reported on the rewind status line — the
Rate line is its single display, and unlike the rewind line it stays visible for run
sessions, which never install a rewind controller.

## Deliberate non-goals

- **Frame-accurate audio command placement.** A frame's worth of queued audio commands
  applies at the head of the mixed block, so an SFX can land up to `rate` gameplay frames
  early inside one outer frame. This is the ordinary cost of turbo and is inaudible at
  these ratios.
- **Rewinding faster.** Trace rewind stays one step per frame. The ladder is read while
  rewinding but only takes effect on release.
- **Pitch-preserving speed-up.** Out of scope; tape character is the intent, and it
  matches rewind.

## Testing

- `TestTracePlaybackSpeedController` — ladder climb/saturation, blocked input, whole and
  fractional step accumulation (100 outer frames at 1.5x advance exactly 150 gameplay
  steps), reset dropping the carried fraction, labels, extra-step ceiling.
- `TestAudioPresentationProducer` — decimation across a chunk boundary with the voice
  advancing by the consumed source frames, fractional-rate frame spacing, NaN/non-positive
  fallback to real time, and rate scoping (SILENT and REVERSE unaffected).
- `TestTraceHudOverlay` — transport block pinned top-right in stacked line order, and
  omitted entirely without an active session status.
- `TestPlaybackDebugManagerOverlayOwnership` — the legacy panel silences and hands back,
  and the transport accessors feeding the replacement HUD.
- `TestRewindVhsEffectPass` — signed scroll advance and backward wrap.
- `TestDisplayShaderPipelineSmoke` — GL apply through the widened signature.
- Visuals and audio verified by running a trace session and walking the ladder.
