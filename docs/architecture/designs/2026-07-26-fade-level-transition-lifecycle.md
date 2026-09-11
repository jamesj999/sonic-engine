# Fade-Based Level Transition Lifecycle

Date: 2026-07-26
Status: Validated design; implementation not started
Scope: Production and headless ownership of fade-based level transitions
Trace evidence: S3K AIZ complete-run f26107/f26179
Explicit exclusion: LBZ is not part of this design's investigation, implementation, or validation

## Problem

The AIZ complete-run replay reaches the native AIZ2-to-HCZ handoff with 26
remaining comparison errors in two clusters:

- f26107: the ROM has cleared player, sidekick, Tails CPU, oscillation, and
  object RAM while the engine still exposes the retired AIZ player.
- f26179: the ROM has initialized the HCZ camera and initial player position
  while the engine still exposes the AIZ camera and player.

This is not an AIZ physics or object-reset defect. The AIZ end-sequence
controller correctly requests HCZ act 1 through the live zone/act transition
API. Production `GameLoop` consumes that request, runs the fade-to-black,
loads the destination level, and hands control to the destination title-card
lifecycle. `RecordingFrameDriver` handles seamless transitions but does not
consume or advance ordinary fade-based transitions, so a headless replay
leaves the request pending and retains the retired level indefinitely.

The ROM sequence establishes the required behavior:

- `StartNewLevel` writes `Current_zone_and_act` and `Restart_level_flag`
  (`docs/skdisasm/sonic3k.asm:180642-180648`).
- `LevelLoop` branches to `Level` immediately after `Process_Sprites` when
  `Restart_level_flag` is set (`sonic3k.asm:7884-7897`).
- `Level` fades, clears display state, resets `Level_frame_counter`, and clears
  object, Tails CPU, and oscillation RAM (`sonic3k.asm:7523-7538,7617-7621`).
- The destination setup installs and dispatches `Obj_TitleCard` before normal
  level setup (`sonic3k.asm:7730-7748`).

The committed fixture declares schema 6, CSV 7, recorder
`6.33-s3k-completerun`, and a trailing `$8C`-frame next-zone handoff. Its
zeroed rows and title-card object states are therefore lifecycle observations,
not absent data or a comparator tolerance.

## Decision

Extract the existing production fade-based transition behavior into a
`FadeLevelTransitionLifecycle` shared by `GameLoop` and the unified headless
frame-driving path.

The lifecycle joins the existing canonical `FrameAdmission` /
`LevelFrameResult` branch. It does not introduce a parallel admission model,
trace phase, or headless-only transition implementation.

The extraction covers only fade-based level requests:

1. respawn;
2. next act;
3. next zone;
4. explicit zone/act;
5. credits.

Seamless transitions, title-card setup, special-stage transitions, and
bonus-stage transitions retain their existing owners and precedence.

## Canonical admission contract

There is one result vocabulary. The existing `LevelFrameResult` enum gains
`TRANSITION_ONLY`. The existing `FrameAdmission` value wraps a
`LevelFrameResult`, and callers inspect `admission.result()`. No parallel
admission enum, fictional result factory, or same-named trace phase is
introduced.

The admission meanings are:

| Admission | Consumes movie row | Advances driver/input history | Advances outer audio/presentation | Runs level gameplay |
|---|---:|---:|---:|---:|
| `SETUP_ONLY` | No | No | No | No |
| `TRANSITION_ONLY` | Yes | Yes | Yes, exactly once | No |
| `PAUSED` | Yes | Yes | Yes, exactly once | Existing paused behavior |
| Existing gameplay/skip/input-only admissions | Yes | Yes | Yes, exactly once | Existing admission-specific behavior |

`SETUP_ONLY` is the sole no-consume retry. It leaves the BK2 cursor, driver
frame, previous-input snapshot, press edges, audio clock, presentation state,
fade counter, and transition-request source unchanged. The same movie row is
retried after setup.

Every other admission, including `PAUSED` and `TRANSITION_ONLY`, consumes one
outer row and commits its input history.

## Exact outer-frame order

Production advances the UI fade before `GameLoop` processes the frame.
Headless and capture paths must preserve that ordering:

```java
FrameAdmission canonical =
        canonicalFrameAdmission.admitModePauseSetup(requestedOperation);

if (canonical.result() == LevelFrameResult.SETUP_ONLY) {
    return canonical;
}

ConsumedInput row = cursor.consume();
driverFrame++;
commitPreviousInputAndEdges(row);

outerFramePresenter.advancePreTransitionArbitration(row);

FrameAdmission admitted =
        fadeLevelTransitionLifecycle.arbitrateAfterPresentation(canonical);

execute(admitted);
```

The presentation hook is named for its position:
`advancePreTransitionArbitration`, not `preAdmissionPresentation`. Canonical
mode, pause, and setup admission has already run.

The caller owns exactly one audio/presentation advancement per consumed row.
The lifecycle never advances the fade, audio, title-card presentation, movie
cursor, or driver frame.

This order has two required consequences:

1. An already-active fade advances once before arbitration.
2. A newly claimed request starts its fade after that row's presentation tick,
   so the fade ends its start row at counter zero. The next consumed row
   advances it to one.

Title-card presentation must also have one owner. The implementation must
centralize or gate the current `RecordingFrameDriver` title-card update so it
cannot run in addition to the caller's single presentation advancement.

## Precedence

Canonical mode, pause, and setup admission runs first. A `SETUP_ONLY` result
returns immediately before any request is claimed.

Existing seamless, title-card, special-stage, and bonus-stage paths keep their
current canonical precedence and ownership. The fade lifecycle is inserted
only at the point corresponding to `GameLoop`'s existing fade-request poll.

When the fade lifecycle is eligible to arbitrate, it atomically claims one
request in this production priority:

```text
respawn > next act > next zone > explicit zone/act > credits
```

Only the selected flag and its captured payload are removed. Lower-priority
flags remain pending and are considered after the active fade lifecycle
returns to idle.

A pending fade request wins over a canonical paused level on the request-start
row, matching production request polling before level pause handling. With no
claimed or freezing request, the canonical `PAUSED` result remains
authoritative and still consumes and presents one outer row.

## Typed requests and freeze policy

The transition source returns a sealed typed request. Each request owns its
semantic fade policy rather than relying on request kind inference or a single
global deactivation flag.

```java
sealed interface ClaimedFadeTransition {
    FadeStyle fadeStyle();
    boolean freezesGameplayDuringFade();
    boolean keepsFreezeAfterFadeCallback();
}

enum FadeStyle {
    BLACK,
    WHITE
}
```

The exact policies are:

| Request | Fade style | Request-start row | Later fade-out rows | After fade callback |
|---|---|---|---|---|
| Respawn | Black | `TRANSITION_ONLY` | Does not force a freeze | Existing respawn/load owner |
| Next act | Black | `TRANSITION_ONLY` | Does not force a freeze | Existing next-act/load owner |
| Next zone | Black | `TRANSITION_ONLY` | Does not force a freeze | Existing next-zone/load owner |
| Zone/act | Black | `TRANSITION_ONLY` | Freezes iff captured `deactivateLevelNow` is true | Destination load/title-card owner |
| Credits | White | `TRANSITION_ONLY` | Always freezes | Remains frozen through `endingTransitionPending` until `doEnterEnding` installs ending-mode ownership |

Special-stage and bonus-stage transitions are deliberately absent from this
table because they remain outside this lifecycle.

The request-start row is always `TRANSITION_ONLY`; this matches the current
`GameLoop` consume branches returning immediately after starting the fade.

On subsequent rows:

- a freezing active request returns `TRANSITION_ONLY`;
- a non-freezing active request retains the canonical
  pause/gameplay/skip/input-only result;
- credits remain `TRANSITION_ONLY` between fade completion and successful
  ending-mode installation, preventing a one-frame return to stale level
  gameplay.

For black level-load transitions, fade-in is callback-free and does not itself
freeze gameplay. The destination title-card or pause owner determines
admission after the load. Credits use `FadeStyle.WHITE` for fade-out and hand
control to the ending-mode owner; this lifecycle does not imply or start a
black fade-in for credits.

## Atomic claim protocol

`LevelTransitionCoordinator` exposes an atomic typed operation rather than
separate flag consumption followed by reads from mutable payload fields:

```java
record FadeTransitionClaim(
        ClaimToken token,
        ClaimedFadeTransition request) {
}

interface FadeTransitionRequestSource {
    FadeTransitionClaim claimHighestPriorityFadeTransition();
    void acknowledgeFadeTransition(ClaimToken token);
    void requeueFadeTransition(ClaimToken token);
}
```

The single atomic claim result binds the token to the request snapshot. The
request contains the complete payload, including fade style, zone, act,
post-load music, and `deactivateLevelNow`; the token retains that exact
request identity and priority across acknowledgement or requeue. No caller
may claim a request and then fetch its token or payload through a second read.

Starting a transition has this exact order:

```text
claim exact typed request and token
→ prepareStart(token, request)
→ startOut(request.fadeStyle(), callback)
→ acknowledge(token)
```

`prepareStart` owns the narrow pre-callback effects that production performs
when accepting a request:

- the request's save reason, when applicable;
- the appropriate audio fade command;
- user-recording stop and playback-boundary notification.

Post-load music selection and scheduled playback activation remain in the
load port.

The start-effects port is token-idempotent. Each individual effect is
at-most-once for a claim token, including when a synchronous failure occurs
after an earlier effect succeeds. Exact-token requeue retains the same token,
so a later retry resumes or confirms preparation without repeating a save,
audio fade, recording stop, or playback boundary.

The lifecycle acknowledges only after
`startOut(request.fadeStyle(), callback)` returns successfully.

If `prepareStart` or `startOut` throws synchronously:

- requeue the exact token and payload;
- leave lower-priority requests pending;
- surface the exception;
- do not roll back the already-consumed outer row.
- preserve the token's completed-effect ledger for at-most-once retry.

If the asynchronous load or mode-handoff callback fails:

- surface the failure loudly;
- enter a terminal `FAILED`/held transition state;
- do not requeue or retry a potentially partial load;
- do not resume the retired level.

Explicit teardown may cancel failed transition state. Ordinary frame
processing may not.

## Lifecycle owner and interfaces

`FadeLevelTransitionLifecycle` belongs in the game/session lifecycle tier. It
owns only:

- the active claimed request and claim token;
- the lifecycle phase (`IDLE`, `FADING_OUT`, `LOADING_OR_HANDOFF`, `FAILED`);
- the request's typed freeze policy;
- the token-keyed start-effects completion state;
- credits' pending ending-mode ownership until `doEnterEnding`.

It does not own:

- movie cursor or previous input;
- driver frame count;
- audio or presentation clocks;
- fade visual counters;
- current level or destination runtime;
- title-card state;
- trace metadata, trace phase, game id, zone id, or frame number.

The dependencies remain narrow:

```java
record FadeTransitionClaim(
        ClaimToken token,
        ClaimedFadeTransition request) {
}

interface FadeTransitionRequestSource {
    FadeTransitionClaim claimHighestPriorityFadeTransition();
    void acknowledgeFadeTransition(ClaimToken token);
    void requeueFadeTransition(ClaimToken token);
}

interface FadeTransitionStarter {
    void startOut(FadeStyle style, Runnable completion);
    void startIn(FadeStyle style);
    boolean isActive();
}

interface FadeTransitionStartEffectsPort {
    void prepareStart(ClaimToken token, ClaimedFadeTransition request);
}

interface FadeTransitionLoadPort {
    void perform(ClaimedFadeTransition request) throws Exception;
}

interface TransitionBoundaryPort {
    void onLevelLoaded(ClaimedFadeTransition request);
    void onEndingModeInstalled(ClaimedFadeTransition request);
}
```

The production start-effects port contains the existing request-acceptance
effects: save reason, audio fade, recording stop, and playback boundary. Its
token-keyed operations are idempotent and at-most-once.

The production load port contains the existing `GameLoop` transition bodies:
respawn, next act, next zone, explicit zone/act, and credits. It preserves:

- post-load music override order;
- scheduled playback activation;
- destination load and title-card request;
- ending-mode handoff.

Black level-load requests start a callback-free black fade-in after successful
load. Credits do not automatically start a black fade-in; the ending-mode
owner controls its white transition after `doEnterEnding`.

The boundary port has no request-claim callback. Start effects exclusively own
save, audio fade, recording stop, and playback-boundary notification. The
boundary port only delegates successful post-load and ending-mode boundaries.
It reuses the existing `LEVEL_LOAD` and mode-handoff owners and is a no-op when
`LevelManager` or the mode handoff already emitted the boundary, preventing a
duplicate boundary.

## Recording and capture paths

Every consuming `RecordingFrameDriver` operation uses the same funnel:

- normal recording input;
- previous-recording-input drive;
- skipped/VBlank-only row;
- input-only/advance-only row;
- animation-only row;
- headless replay adapters;
- `TraceCaptureTool` adapters.

Transition arbitration occurs after input consumption and the single
pre-transition-arbitration presentation tick. A freezing transition may
replace the requested operation with `TRANSITION_ONLY`, but it cannot prevent
that row's cursor, frame, input-history, audio, and presentation advancement.

This guarantees that the first destination gameplay row sees the correct
previous input and press-edge state.

## Rewind and failure boundaries

The extraction preserves and makes explicit the existing rewind behavior:

- A pending request with `deactivateLevelNow=true` is already non-rewindable
  before claim under the existing transition policy. Extraction must not open
  a rewind window between request publication and atomic claim.
- `IDLE`, with no pending non-rewindable request, is the only normally
  restorable lifecycle phase.
- Fade-out and any other active callback-bearing lifecycle phase retain the
  real `FadeManager` completion callback. Their snapshots are poison-gated,
  non-restorable, and refused by the existing rewind policy.
- A successful full load uses the existing `LEVEL_LOAD` reference-closure and
  rewind boundary. Retired object identities cannot cross it.
- Credits use the existing ending-mode boundary when `doEnterEnding`
  successfully installs the new mode.
- Fade-in remains callback-free, but the lifecycle is still active; it does
  not become normally restorable until the lifecycle returns to `IDLE`.
- A callback failure never manufactures a restorable half-loaded state.

Every mutable lifecycle field—phase, active request, claim token, typed style
and freeze policy, start-effects completion state, credits handoff state, and
failure state—must be registered with rewind coverage through an ordinary
rewind adapter or owning snapshot. No coverage baseline exception is allowed.

Replacing callback poison with a snapshotted callback-free transition machine
is outside this design.

## Alternatives rejected

### Drive `GameLoop` directly from headless playback

This would maximize literal reuse, but current headless fixtures construct a
gameplay context and recording driver without an `Engine`/`GameLoop`.
Introducing `GameLoop` would pull window, presentation, audio, recording,
session, and singleton dependencies into focused trace and unit tests. Offline
capture would still need a separate adapter, and input/presentation could be
advanced twice.

Extracting the small production lifecycle seam is the cleaner form of reuse.

### Implement a narrow transition step in `RecordingFrameDriver` or `LevelFrameStep`

This is initially smaller but creates a second implementation of request
priority, fade timing, level loading, music, scheduled playback, recording
termination, title cards, failure, and rewind boundaries.

`LevelFrameStep` is specifically the wrong owner: ROM and engine fades and full
level replacement occur outside `LevelLoop`. Loading immediately would also
initialize the destination too early relative to the recorded cleared-RAM
title-card interval.

### End AIZ replay at the transition request

The fixture explicitly advertises and records the next-zone handoff. Unlike
the green bonus-stage traces, AIZ has no live provider whose completed
lifecycle semantically terminates comparison before the tail. Ending or
skipping comparison would conceal the missing production lifecycle.

### Clear engine sprites or loosen comparison

Synthetic zeroing, trace hydration, tolerances, and row suppression would copy
the observed trace rather than execute the ROM-owned transition. They would
also fail at the f26179 destination initialization boundary.

## RED test matrix

Production code must not be written until the following focused tests fail for
the intended reason.

### Canonical admission and ordering

1. `setupOnlyIsOnlyNonConsumingAdmission`
   - `SETUP_ONLY` leaves cursor, driver frame, previous input, press edges,
     audio, presentation, fade, and request source unchanged.
   - Every other admission consumes once.

2. `activeFadeFromBlackSetupOnlyRetryDoesNotAdvanceAnything`
   - Seed callback-free fade-from-black at counter K and BK2 row I.
   - A `SETUP_ONLY` attempt leaves K and I unchanged and claims no request.
   - Retrying the same row with a consuming admission advances cursor to I+1
     and fade to K+1 exactly once.

3. `transitionStartRowTicksPresentationBeforeFadeStarts`
   - The start row increments presentation/audio once.
   - Fade starts afterward at counter zero.
   - The next consumed row advances it to one.

4. `transitionOnlyConsumesInputButRunsNoGameplay`
   - Cursor, driver frame, previous input, audio, and presentation advance once.
   - Player, objects, camera, oscillator, level counter, and gameplay closure
     do not run.

5. `pausedConsumedRowAdvancesExistingFadeExactlyOnce`
   - `PAUSED` consumes and presents once while running no level gameplay.

6. Parameterize normal, previous-input, skip, input-only, and animation-only
   driver entry points.
   - An active freezing transition produces the same consumption and
     `TRANSITION_ONLY` behavior through every path.

### Priority, policy, and failure

7. `claimsFadeRequestsInProductionPriorityWithoutClearingLowerRequests`
   - Queue all five request kinds.
   - Each atomic claim returns its request and token together.
   - Claim and acknowledge them in the declared order.
   - Prove lower requests and payloads remain intact.
   - Prove acknowledgement uses the exact token returned with that request.

8. `startEffectsAndAcknowledgementFollowExactOrderAtMostOnce`
   - Observe the atomic claim's token/request pair, `prepareStart` with that
     token, style-aware `startOut`, then acknowledgement of the same token in
     that exact order.
   - Save reason, audio fade, recording stop, and playback boundary each run
     at most once for the token.
   - Post-load music and scheduled playback do not run before the callback.

9. `synchronousStartFailureRequeuesExactClaim`
   - Preserve exact token, request type, priority, fade style, zone, act,
     music, and deactivate payload.
   - Reclaiming after requeue returns the same token/request identity.
   - Prove save, audio fade, recording stop, and playback boundary effects
     remain at-most-once when the completion ledger resumes that same token.

10. `loadFailureIsLoudHeldAndNeverRetried`
   - One callback invocation and one load attempt.
   - No requeue, gameplay resume, or second attempt.

11. `typedFadeFreezePolicy`
    - Start row freezes all five kinds.
    - Later fade rows do not force a freeze for respawn, next act, or next zone.
    - Zone/act follows captured `deactivateLevelNow`.
    - Credits use white fade-out and remain frozen through ending ownership
      transfer.

12. `pausedPendingRequestUsesProductionPrecedence`
    - A claimable request starts on a canonical paused row after its one
      presentation tick.

13. `setupOnlyPrecedesPendingFadeClaim`
    - Setup retries do not claim or reorder pending requests.

14. `creditsFreezeThroughFadeAndEndingModeHandoff`
    - Start row consumes once and starts fade at zero.
    - The fade style is white.
    - Every fade row consumes/presents once and runs no level gameplay.
    - `endingTransitionPending` retains `TRANSITION_ONLY` after fade completion.
    - `doEnterEnding` installs ending ownership exactly once, clears lifecycle
      ownership, and uses the existing mode boundary.
    - No black fade-in is started by the fade-level lifecycle.

15. `creditsHandoffFailureIsLoudAndNeverUnfreezes`
    - A failed `doEnterEnding` enters `FAILED`, never retries, and never exposes
      stale level gameplay.

### Integration and cross-surface behavior

16. AIZ real-controller headless transition
    - Drive the live AIZ2 controller until it requests HCZ.
    - Use semantic state waits with bounded safety limits, not trace frames.
    - Prove the request-start and deactivating fade rows consume input and
      presentation without retired AIZ gameplay.
    - Prove full load clears/replaces old playable identity through
      `LevelManager`, emits one existing `LEVEL_LOAD` boundary, and transfers
      to HCZ title-card ownership.

17. Non-AIZ Sonic 2 explicit zone/act transition
    - Exercise the same production lifecycle through an object/service request.
    - Use a non-deactivating request and prove gameplay continues after the
      universal start row while fade advances.
    - Prove one load, title-card ordering, and correct destination input edge.

18. Headless/capture parity
    - `TraceCaptureTool` and `HeadlessTestRunner` consume the same number of
      rows, produce the same admission sequence, advance presentation equally,
      and expose the same first destination gameplay input.

19. Rewind lifecycle
    - An idle snapshot with no pending non-rewindable request restores.
    - A pending deactivating request is non-rewindable before claim.
    - A pending-callback snapshot is refused.
    - The post-load epoch contains no retired object ids.
    - Callback-free fade-in remains non-restorable until lifecycle `IDLE`.
    - Rewind coverage includes every mutable lifecycle and start-effects field
      without a baseline exception.

## Validation matrix

### Target

- `TestS3kAizCompleteRunTraceReplay`
  - Remove the f26107 and f26179 lifecycle clusters.
  - Inspect every newly exposed error; do not assume green.
- Focused AIZ end-sequence object suite.
- Standalone AIZ replay must retain or advance its accepted frontier.

### Non-LBZ S3K

- CNZ standalone and complete-run.
- HCZ complete-run accepted frontier.
- MGZ standalone and complete-run.
- ICZ complete-run.
- MHZ complete-run.
- Gumball, Pachinko, Slots, and special-stage green canaries.

LBZ is explicitly excluded from every implementation and validation command
for this work.

### Cross-game

- S1 GHZ standard and complete-run.
- S2 EHZ and selected level-select replay.
- New Sonic 2 non-deactivating transition integration.

### Infrastructure

- Canonical frame-admission and `LevelFrameResult` tests.
- `RecordingFrameDriver` normal, prior-input, skip, input-only, and
  animation-only tests.
- `TraceCaptureTool` parity tests.
- Fade snapshot poison and callback-free restore tests.
- Playback advance-only input bridge.
- Reference-closure, rewind round-trip, rewind coverage, and static-state
  rewind guards.
- Architectural source, trace invariant, and hydration guards.
- Existing `GameLoop` fade, level-load, title-card, scheduled playback, and
  post-load music-order tests.

## Non-negotiable implementation constraints

- No game, zone, route, fixture, trace, or frame selectors.
- No comparison tolerances, row suppression, or terminal-tail shortcuts.
- No trace-to-engine hydration or synthetic clearing from recorded fields.
- No duplicate fade tick, title-card update, audio frame, movie-row consume, or
  rewind boundary.
- No direct full-level load from `LevelFrameStep`.
- No retry after an asynchronous load or ending-mode failure.
- No LBZ inspection, implementation, or validation.
