# Sonic 2 Special-Stage Entry Presentation Design

**Date:** 2026-07-10

## Goal

Remove the exposed, incomplete Sonic 2 special-stage scene before the stage reveal. Normal gameplay should enter promptly, while trace replay and validation must retain the ROM-ordered pre-roll and startup observations required by the green trace.

The special-stage music must begin at the same semantic boundary as the visible reveal, not while the transition screen is still covering initialization.

## Startup policies

Sonic 2 special-stage initialization has two explicit policies:

- `FAST` is the normal-play default. Initialization synchronously advances the production manager through `PRE_ROLL` and `ROM_STARTUP` to the start of `FADE_FROM_WHITE`. This compresses elapsed presentation time without inventing a second state path: the same manager updates still produce the reveal-boundary state.
- `TRACE_ACCURATE` preserves the existing frame-by-frame startup cadence. Headless trace replay and live trace sessions pass this policy directly into initialization.

`SpecialStageStartupPolicy` is an explicit initialization argument rather than latent provider state. The existing `initializeStage(int)` contract remains the normal `FAST` path; a policy-aware overload allows trace callers to request `TRACE_ACCURATE` for that call only. `GameLoop` similarly keeps its existing normal-entry overload and adds a policy-aware entry used by `TraceSessionLauncher`. Aborted launches, resets, initialization failures, and provider reuse cannot leak accurate pacing into a later entry because no pending request exists. Startup policy is independent of lag compensation; `setLagCompensation(0)` remains only the external-pacing bypass.

## Presentation-ready contract

`SpecialStageProvider` gains a default presentation-readiness contract. Existing providers remain ready immediately. The Sonic 2 provider reports ready once its intro reaches `FADE_FROM_WHITE` or a later phase.

`GameLoop` owns entry presentation:

1. Enter and initialize the provider while the completed transition remains opaque.
2. If the provider is ready, start presentation immediately.
3. Otherwise force `FadeManager` into an explicit opaque hold matching the requested reveal direction and mark entry presentation pending.
4. After each special-stage update, recheck readiness.
5. On the first ready observation, start the requested fade-from-white or fade-from-black and play special-stage music exactly once.

`FadeManager` gains explicit `holdWhite()` and `holdBlack()` operations. Each operation cancels any prior fade callback, resets fade counters, and sets a fully opaque white or black overlay. This is required for the live trace path because master-title exit has already started fading from black before `TraceSessionLauncher` enters the accurate special stage; merely declining to start another fade would allow that older fade to keep revealing startup.

This keeps fade and audio ordering in one owner. The Sonic 2 renderer does not draw an extra white rectangle, and the provider does not directly manipulate `FadeManager` or audio services. For providers which are ready immediately, `GameLoop` continues to start music and the reveal before changing mode and notifying the mode listener, preserving the current S1/S3K entry ordering. Deferred S2 trace entry changes mode while opaque, then starts music and reveal together at readiness.

Normal Sonic 2 play reaches the ready boundary synchronously during initialization, so reveal and music begin as part of the existing mode-entry sequence. Trace-accurate startup remains covered until the modeled ROM boundary, then reveals and starts music.

## Fast-forward behavior

The Sonic 2 manager exposes a bounded startup-advance operation used only during initialization. It repeatedly executes the normal update path until `FADE_FROM_WHITE` begins, with a defensive maximum iteration count that fails initialization and includes the current intro phase if the boundary is not reached. A package-visible bounded overload permits a zero-budget guard test without adding state mutation or trace input. Calling the operation after the manager has reached `FADE_FROM_WHITE` or any later phase is illegal and reports that phase.

Fast-forward uses neutral startup input and preserves all resulting player, object, track, intro, frame-counter, and rewind-visible state. It does not jump fields directly or hydrate state from trace data. The operation is illegal after initialization has moved beyond the startup phases.

## Trace integration

Both trace entry points pass `TRACE_ACCURATE` into the initialization call:

- the headless `S2SpecialStageReplayHarness`;
- the live `TraceSessionLauncher` special-stage branch, through the policy-aware `GameLoop.doEnterSpecialStage` overload.

The existing replay comparison, input binding, pass cadence, determinism, and lag-bypass contracts remain unchanged.

## Lifecycle and failure handling

Pending entry-presentation state belongs to `GameLoop` and is cleared when presentation starts, special-stage entry fails, the provider is replaced, or the mode exits. The state records only pending/not-pending plus the white/black reveal direction. The music/reveal helper is idempotent so repeated readiness checks cannot restart either effect.

Pre-ready presentation is intentionally a non-rewindable entry boundary. In current use it exists only for live trace sessions, where `TraceSessionLauncher.active()` already suppresses special-stage live-rewind capture; headless replay has no `GameLoop` presentation state. Normal `FAST` initialization is ready before the special-stage frame-zero rewind snapshot. If a future normal-play provider defers readiness, it must first define rewind reconciliation for pending direction, fade, and audio rather than inheriting this trace-only exception.

If fast initialization cannot reach the reveal boundary within its guard limit, initialization throws with the current intro phase rather than exposing a partially initialized scene.

## Tests

Tests will prove:

- normal provider initialization reaches `FADE_FROM_WHITE` immediately;
- an explicit trace-accurate initialization preserves `PRE_ROLL` and the existing frame-by-frame bootstrap behavior;
- an accurate initialization, reset, failure, or abandoned trace launch cannot affect a later default initialization, which remains `FAST`;
- `FAST` and `TRACE_ACCURATE` selection are unchanged with lag compensation enabled or forced to zero;
- fast-forward guard exhaustion reports the current phase, and calling fast-forward after startup is rejected;
- entry presentation remains pending while a provider is not ready;
- a pending black reveal replaces an already-running fade-from-black with an opaque `HOLD_BLACK`, covering the real `TraceSessionLauncher` launch path;
- reveal and stage music start together exactly once when readiness changes;
- concrete S1 white entry and S3K black/white entry retain immediate fade, music, mode-change, and listener ordering;
- the Sonic 2 special-stage unit suite, green replay, and two-run determinism test remain green.

## Non-goals

- No user-facing configuration toggle.
- No coupling between startup policy and lag compensation.
- No changes to S1 or S3K special-stage timing.
- No trace-state hydration or comparison tolerance.
- No renderer-local replacement fade.

## Addendum (2026-09-06): the entry sequence runs in normal play

The `FAST` fast-forward above was reversed. Compressing `PRE_ROLL` and
`ROM_STARTUP` into initialization removed the ROM's visible transition: the
level's last frame never faded to white, the stage music started on the same
frame as `SndID_SpecStageEntry`, and the manager's `reset()` stopped playback
outright, which cut both that sound and `MusID_FadeOut` (s2.asm:6543-6546).

Now every provider steps its ROM entry sequence's V-int waits frame by frame
under both policies, and `SpecialStageStartupPolicy` only says whether the
hardware load span is reproduced:

- `SpecialStageProvider#isEntryFadeToWhiteActive()` marks the ROM's
  `Pal_FadeToWhite` / `PaletteWhiteOut` window. `EngineRenderDispatcher`
  routes the special-stage draw and clear colour to the level while it holds,
  `GameLoop` leaves the level camera alone until it ends, and
  `SpecialStageEntryPresentationController` runs `startFadeToWhite(null,
  Integer.MAX_VALUE)` deferred to the next V-int instead of `holdWhite()`, so
  the frozen level fades over 22 frames and parks white until readiness.
- The ROM's masked-interrupt load (104 lag rows between the fade rows and
  the first `Vint_S2SS` row in every recorded stage, s2.asm:6557-6645) is
  reproduced only under `TRACE_ACCURATE`, where the timing port admits the
  recorded rows; `FAST` skips it so normal play goes straight from the fade
  to startup. `Sonic2SpecialStageManager.armLiveEntryLoadHold()` can
  reproduce the span as lag frames (rewind-safe) for a future accurate
  presentation mode, but nothing arms it yet. The live lag model no longer
  skips `PRE_ROLL` updates, since `Vint_Fade` rows cannot lag.
- S1 reports readiness after the `PaletteWhiteOut` half of its hold and S3K
  after its pre-boot fade, so `bgm_SS` / `mus_SpecialStage` and the fade from
  white start where the ROM starts them; neither policy retires those holds
  any more. The S3K standalone replay harness retires the pre-boot hold
  itself because its comparator begins at the first post-load row.
- `Sonic2SpecialStageManager.reset()` no longer touches audio; the transition
  owners start and fade music themselves.

`advanceToEntryPresentation()` and `advanceThroughEntryFade()` remain as test
helpers only.
