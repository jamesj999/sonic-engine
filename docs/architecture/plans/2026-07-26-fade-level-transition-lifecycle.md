# Fade-Based Level Transition Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make production, headless replay, and trace capture execute the same ROM-backed fade level-transition lifecycle so the AIZ complete run crosses into HCZ without retaining retired AIZ state.

**Architecture:** Add one typed, atomic fade-request protocol to `LevelTransitionCoordinator`, then extract the existing `GameLoop` fade/load behavior into a session-owned `FadeLevelTransitionLifecycle` with narrow effects, load, fade, and boundary ports. Insert it after the already-reviewed canonical `FrameAdmission` decision and after exactly one consumed-row presentation tick; `SETUP_ONLY` remains the sole non-consuming retry and `TRANSITION_ONLY` becomes the shared result for request-start and freezing transition rows.

**Tech Stack:** Java 21, JUnit Jupiter 5, Mockito, Maven Surefire, existing `LevelFrameStep`/`FrameAdmission`, `FadeManager`, `LevelManager`, rewind registry and trace-replay infrastructure.

## Global Constraints

- Hard prerequisite: before Task 1, create and independently approve an integration composition from then-current `origin/develop` plus the unified initial `Process_Sprites` Task 6 line at `0419d679a2d06c7c84e1cf55455dbd85f4b55500` and its reviewed follow-on AIZ owner fix/test adaptations after the unified plan's Task 7 passes. Record the resulting commit as `COMPOSED_BASE_SHA` in this plan's execution ledger. Hard-stop if that unified line is not approved and integrated into the composition.
- At the start of every Task 1–8, require `git merge-base --is-ancestor "$COMPOSED_BASE_SHA" HEAD`, require both canonical type files to exist, and confirm `FrameAdmission` still wraps `LevelFrameResult`; stop on any failure.
- The prerequisite supplies `FrameAdmission(LevelFrameResult result)`, `FrameAdmission.result()`, `LevelFrameStep.admit(...)`, and `LevelFrameResult.{PAUSED,SETUP_ONLY,GAMEPLAY_FRAME}`. Extend those exact types; do not introduce another admission enum or trace phase.
- `LevelFrameResult` gains exactly `TRANSITION_ONLY`.
- `SETUP_ONLY` is the sole result that consumes no movie row and advances no cursor, driver frame, previous input, press edge, audio, presentation, fade, title card, or transition request.
- Every other result consumes one row, commits input history, and advances outer audio/presentation exactly once.
- Ordering per consumed row is canonical admission, input/cursor commit, `advancePreTransitionArbitration`, fade arbitration, then admission-specific execution.
- Fade request priority is respawn, next act, next zone, explicit zone/act, credits.
- Request-start rows are always `TRANSITION_ONLY`; later fade rows freeze only according to the captured typed policy.
- No game, zone, route, fixture, trace, or frame selectors; no trace hydration, tolerance, row suppression, synthetic clearing, or `LevelFrameStep` level load.
- Special-stage, bonus-stage, seamless-transition, and title-card ownership and precedence remain unchanged.
- Synchronous start failure requeues the exact token/request and preserves completed-effect state; asynchronous load or ending handoff failure enters terminal `FAILED` and never retries.
- Save, audio fade, recording stop, and playback boundary are individually at-most-once per claim token.
- Post-load music and scheduled playback stay in the load port; successful level/mode boundaries are emitted once by their existing owner.
- Active callback-bearing phases and callback-free fade-in remain non-restorable; no rewind coverage baseline exception.
- Do not inspect, modify, run, or mention an LBZ fixture or LBZ implementation in execution commands.
- Every production behavior change must retain the design's ROM citations: `docs/skdisasm/sonic3k.asm:7523-7538,7617-7621,7730-7748,7884-7897,180642-180648`.

---

## Execution Ledger

Planning-time validation used composition `047af5d22`. That commit is evidence
that the lines can be composed; it is not the final implementation base and
Tasks 1+ must not assume or pin it.

No final `COMPOSED_BASE_SHA` is recorded yet. Task 0 records it only after
composing then-current `origin/develop`, resolving conflicts, and passing the
unified plan's focused and full Task 7 gates.

## File and Interface Map

The executor must re-run this map after composing the prerequisite because line numbers will move.

**Canonical admission stack supplied by the prerequisite**

- Modify `src/main/java/com/openggf/LevelFrameResult.java`: add `TRANSITION_ONLY`.
- Modify `src/main/java/com/openggf/FrameAdmission.java`: keep the existing one-field record and `result()` vocabulary.
- Modify `src/main/java/com/openggf/LevelFrameStep.java`: retain `admit(LevelFrameContext, LevelManager, boolean)` as the canonical pause/setup gate; do not put fades or loads here.
- Modify `src/main/java/com/openggf/tools/RecordingFrameDriver.java`: funnel every consuming entry point through canonical admission, input commit, presentation, lifecycle arbitration, then execution.
- Modify `src/main/java/com/openggf/GameLoop.java`: replace its five independent fade-request polls and private transition state with the shared lifecycle and production ports.

**New lifecycle package**

- Create `src/main/java/com/openggf/game/transition/FadeStyle.java`.
- Create `src/main/java/com/openggf/game/transition/ClaimToken.java`.
- Create `src/main/java/com/openggf/game/transition/ClaimedFadeTransition.java`.
- Create `src/main/java/com/openggf/game/transition/FadeTransitionClaim.java`.
- Create `src/main/java/com/openggf/game/transition/FadeTransitionRequestSource.java`.
- Create `src/main/java/com/openggf/game/transition/LevelManagerFadeTransitionRequestSource.java`.
- Create `src/main/java/com/openggf/game/transition/FadeTransitionStarter.java`.
- Create `src/main/java/com/openggf/game/transition/FadeTransitionStartEffectsPort.java`.
- Create `src/main/java/com/openggf/game/transition/FadeTransitionLoadPort.java`.
- Create `src/main/java/com/openggf/game/transition/TransitionBoundaryPort.java`.
- Create `src/main/java/com/openggf/game/transition/FadeLevelTransitionLifecycle.java`.
- Create `src/main/java/com/openggf/game/transition/FadeLevelTransitionSnapshot.java`.
- Create `src/main/java/com/openggf/game/transition/FadeTransitionCallbackException.java`.
- Create `src/main/java/com/openggf/game/transition/FadeTransitionStartEffects.java`.
- Create `src/main/java/com/openggf/game/transition/GameLoopFadeTransitionPorts.java`.
- Create `src/main/java/com/openggf/tools/OuterFramePresenter.java`.

**Existing request/load/rewind owners**

- Modify `src/main/java/com/openggf/level/LevelTransitionCoordinator.java`: implement atomic claim/acknowledge/requeue without disturbing special-stage, bonus-stage, seamless, or title-card fields.
- Modify `src/main/java/com/openggf/level/LevelManager.java`: delegate the atomic request-source API and register/expose the lifecycle rewind adapter at the session boundary.
- Modify `src/main/java/com/openggf/game/session/GameplayModeContext.java`: own one lifecycle per gameplay session, expose `fadeLevelTransitionLifecycle()`, and register/deregister its rewind adapter with the existing registry.
- Modify `src/main/java/com/openggf/graphics/FadeManager.java` only to adapt its existing black/white start methods to `FadeTransitionStarter`; do not move its visual counter.
- Modify `src/main/java/com/openggf/tools/TraceCaptureTool.java`, `src/test/java/com/openggf/tests/HeadlessTestRunner.java`, and `src/test/java/com/openggf/tests/HeadlessTestFixture.java` only where their return types or outer-presentation adapter wiring require it.
- Modify `src/main/java/com/openggf/trace/replay/TraceReplaySessionBootstrap.java`: install the headless lifecycle in the current `GameplayModeContext` before constructing the driver.

**Tests**

- Create `src/test/java/com/openggf/level/TestFadeTransitionRequestSource.java`.
- Create `src/test/java/com/openggf/game/transition/TestFadeLevelTransitionLifecycle.java`.
- Create `src/test/java/com/openggf/game/transition/TestFadeTransitionStartEffects.java`.
- Create `src/test/java/com/openggf/tools/TestRecordingFrameDriverFadeTransitions.java`.
- Create `src/test/java/com/openggf/TestGameLoopFadeTransitionIntegration.java`.
- Create `src/test/java/com/openggf/tests/TestFadeTransitionHeadlessIntegration.java`.
- Create `src/test/java/com/openggf/tools/TestTraceCaptureFadeTransitionParity.java`.
- Create `src/test/java/com/openggf/game/transition/TestFadeTransitionRewind.java`.
- Modify `src/test/java/com/openggf/TestGameLoop.java` to replace source-shape assertions for `endingTransitionPending` and five inline consumers with behavioral ownership assertions.
- Modify `src/test/java/com/openggf/tools/TestRecordingFrameDriverInputOnly.java` to assert the unified result/consumption contract.

## Exact New Type Contract

Use these signatures consistently in every task:

```java
public enum FadeStyle { BLACK, WHITE }

public record ClaimToken(long value) { }

public sealed interface ClaimedFadeTransition
        permits ClaimedFadeTransition.Respawn,
                ClaimedFadeTransition.NextAct,
                ClaimedFadeTransition.NextZone,
                ClaimedFadeTransition.ZoneAct,
                ClaimedFadeTransition.Credits {
    FadeStyle fadeStyle();
    boolean freezesGameplayDuringFade();
    boolean keepsFreezeAfterFadeCallback();

    record Respawn() implements ClaimedFadeTransition {
        public FadeStyle fadeStyle() { return FadeStyle.BLACK; }
        public boolean freezesGameplayDuringFade() { return false; }
        public boolean keepsFreezeAfterFadeCallback() { return false; }
    }
    record NextAct() implements ClaimedFadeTransition {
        public FadeStyle fadeStyle() { return FadeStyle.BLACK; }
        public boolean freezesGameplayDuringFade() { return false; }
        public boolean keepsFreezeAfterFadeCallback() { return false; }
    }
    record NextZone() implements ClaimedFadeTransition {
        public FadeStyle fadeStyle() { return FadeStyle.BLACK; }
        public boolean freezesGameplayDuringFade() { return false; }
        public boolean keepsFreezeAfterFadeCallback() { return false; }
    }
    record ZoneAct(int zone, int act, int postLoadMusicId,
                   boolean deactivateLevelNow) implements ClaimedFadeTransition {
        public FadeStyle fadeStyle() { return FadeStyle.BLACK; }
        public boolean freezesGameplayDuringFade() { return deactivateLevelNow; }
        public boolean keepsFreezeAfterFadeCallback() { return false; }
    }
    record Credits(Optional<SaveReason> saveReason) implements ClaimedFadeTransition {
        public Credits {
            Objects.requireNonNull(saveReason, "saveReason");
        }
        public FadeStyle fadeStyle() { return FadeStyle.WHITE; }
        public boolean freezesGameplayDuringFade() { return true; }
        public boolean keepsFreezeAfterFadeCallback() { return true; }
    }
}

public record FadeTransitionClaim(
        ClaimToken token, ClaimedFadeTransition request) { }

public interface FadeTransitionRequestSource {
    FadeTransitionClaim claimHighestPriorityFadeTransition();
    void acknowledgeFadeTransition(ClaimToken token);
    void requeueFadeTransition(ClaimToken token);
}

public interface FadeTransitionStarter {
    void startOut(FadeStyle style, Runnable completion);
    void startIn(FadeStyle style);
    boolean isActive();
}

public interface FadeTransitionStartEffectsPort {
    void prepareStart(ClaimToken token, ClaimedFadeTransition request);
}

public interface FadeTransitionLoadPort {
    void perform(ClaimedFadeTransition request) throws Exception;
}

public interface TransitionBoundaryPort {
    void onLevelLoaded(ClaimedFadeTransition request);
    void onEndingModeInstalled(ClaimedFadeTransition request);
}

public interface OuterFramePresenter {
    void advancePreTransitionArbitration(LogicalInputSnapshot consumedInput);
}

record DrivenRecordingFrame(int inputMask, LevelFrameResult result) { }

public final class FadeTransitionCallbackException extends RuntimeException {
    public FadeTransitionCallbackException(Throwable cause) { super(cause); }
}
```

`DrivenRecordingFrame` is package-private inside `RecordingFrameDriver`. Existing public
recording methods continue returning their current `int` input masks; they set
`lastFrameResult` from the internal record, and tests inspect
`getLastFrameResult()`. No public replay/capture adapter signature changes.

`FadeLevelTransitionLifecycle` must expose:

```java
public FrameAdmission arbitrateAfterPresentation(FrameAdmission canonical);
public Phase phase();
public boolean isRewindBlocked();
public FadeLevelTransitionSnapshot snapshot();
public void restore(FadeLevelTransitionSnapshot snapshot);
public void cancelFailedTransitionForTeardown();

public enum Phase { IDLE, FADING_OUT, LOADING_OR_HANDOFF, FAILED }
```

`arbitrateAfterPresentation` returns the canonical admission when idle with no claim or while a non-freezing request is active; returns `TRANSITION_ONLY` on every request-start row, on a freezing fade row, and while credits await ending ownership. Callback success performs load/handoff, boundary notification, callback-free black fade-in for level loads, and returns ownership to `IDLE` only after the fade-in is no longer active. The `Runnable` callback catches the checked `perform` failure, stores that original `Throwable`, enters `FAILED`, and throws `FadeTransitionCallbackException`; a duplicate callback invocation after leaving `FADING_OUT` is an idempotent no-op. `cancelFailedTransitionForTeardown()` is legal only in `FAILED` and clears failure, active request/token, credits handoff state, and effect ledger before setting `IDLE`.

`GameplayModeContext` is the sole session owner. It gains:

```java
public void installFadeLevelTransitionLifecycle(
        FadeLevelTransitionLifecycle lifecycle);
public FadeLevelTransitionLifecycle fadeLevelTransitionLifecycle();
public void clearFadeLevelTransitionLifecycleForTeardown();
```

Production `GameLoop` installs one instance after gameplay managers attach.
`TraceReplaySessionBootstrap` installs one headless instance in the same current
context; `RecordingFrameDriver`, `HeadlessTestRunner`, and `TraceCaptureTool`
only retrieve it from `SessionManager.getCurrentGameplayMode()`. Installation
throws if a different instance is already installed. Teardown cancels a failed
transition if necessary, deregisters lifecycle and effects adapters, then clears
the field.

The production `TransitionBoundaryPort` methods are deliberate no-ops backed by
existing owners, not heuristic duplicate detection:

- `LevelManager.loadLevel()` already calls
  `LevelRewindBoundaryCoordinator.markLevelLoadBoundary()` after a successful
  load, so `onLevelLoaded` asserts/records completion for tests but emits no
  second boundary.
- `GameLoop.setGameMode(...)` already emits the relevant mode exit/entry rewind
  boundary, so `onEndingModeInstalled` emits no second boundary.

The behavioral tests attach `GameplayModeContext.setRewindBoundaryReporter(...)`
before the load/handoff and assert exactly one existing-owner report.

### Task 0: Compose and Verify the Canonical Admission Prerequisite

**Files:**

- Modify: `docs/architecture/plans/2026-07-26-fade-level-transition-lifecycle.md` (execution-ledger entry only after approval)
- Read: `src/main/java/com/openggf/FrameAdmission.java`
- Read: `src/main/java/com/openggf/LevelFrameResult.java`
- Read: `src/main/java/com/openggf/LevelFrameStep.java`
- Read: `src/main/java/com/openggf/tools/RecordingFrameDriver.java`
- Test: prerequisite Task 7 class list from `docs/architecture/plans/2026-07-26-s3k-initial-object-setup-lifecycle.md`

**Interfaces:**

- Consumes: then-current `origin/develop`; unified Task 6 commit `0419d679a`; and every independently reviewed follow-on AIZ owner fix/test-adaptation commit required by the unified plan's Task 7.
- Produces: an independently approved `COMPOSED_BASE_SHA` where `FrameAdmission.result()` is the only admission vocabulary, `SETUP_ONLY` is already the only no-consume retry, `RecordingFrameDriver` public recording methods still return `int`, and all unified Task 7 gates pass.

- [ ] **Step 1: Resolve the current integration inputs**

Fetch/read then-current `origin/develop`, inspect the reviewed unified branch
history from `0419d679a` through its approved Task 7 head, and list the exact
AIZ owner fix/test-adaptation commits. If Task 7 is not approved, stop before
creating a fade implementation branch.

- [ ] **Step 2: Create a dedicated prerequisite integration composition**

Create a clean integration branch/worktree from the resolved
`origin/develop`, then integrate the reviewed unified line and follow-ons in
their reviewed order. Do not perform this composition inside a trace-specific
worktree.

- [ ] **Step 3: Resolve composition conflicts**

Preserve newer `origin/develop` behavior and the unified stack's single
`FrameAdmission`/`LevelFrameResult` vocabulary. Reject any resolution that
duplicates initial `Process_Sprites`, restores unconditional driver cursor
consumption, or introduces a second admission type.

- [ ] **Step 4: Verify the prerequisite shape**

Run:

```bash
rg -n "record FrameAdmission|enum LevelFrameResult|FrameAdmission admission =|LevelFrameStep\\.admit" \
  src/main/java/com/openggf
```

Expected: one `FrameAdmission` record, one `LevelFrameResult` enum, and both `GameLoop` and `RecordingFrameDriver` consuming `admission.result()`.

- [ ] **Step 5: Run the prerequisite focused Task 7 gates**

Run the exact focused unit, initial-setup, AIZ owner, and duplicate-dispatch
commands recorded in
`docs/architecture/plans/2026-07-26-s3k-initial-object-setup-lifecycle.md`,
one Maven invocation at a time. Expected: every focused gate passes and AIZ
shows one initial `Process_Sprites` owner.

- [ ] **Step 6: Run the prerequisite full Task 7 gates**

Run the exact full non-LBZ replay/guard matrix recorded by unified Task 7.
Expected: all green canaries remain green and all accepted red frontiers retain
or advance their reviewed first-error frame.

- [ ] **Step 7: Independently review the composition**

Give the integration diff, conflict resolutions, focused results, and full
Task 7 results to a fresh reviewer. If review is not `APPROVED`, fix the
composition and repeat Steps 4–7.

- [ ] **Step 8: Record `COMPOSED_BASE_SHA`**

After approval, commit the integration composition if it is not already a
commit, run:

```bash
COMPOSED_BASE_SHA="$(git rev-parse HEAD)"
export COMPOSED_BASE_SHA
```

Append a dated, initially uncommitted execution-ledger entry to this plan
containing: `COMPOSED_BASE_SHA`, resolved `origin/develop` SHA, unified Task 6
SHA, every follow-on SHA, commands/results, and reviewer verdict. Stage that
ledger entry with the first implementation commit whose code is based on it;
this avoids a self-referential commit SHA.

- [ ] **Step 9: Verify Task 1 starts on the recorded base**

Before Task 1 edits, assert:

```bash
test "$(git rev-parse HEAD)" = "$COMPOSED_BASE_SHA"
test -f src/main/java/com/openggf/FrameAdmission.java
test -f src/main/java/com/openggf/LevelFrameResult.java
rg -n "record FrameAdmission|SETUP_ONLY|GAMEPLAY_FRAME" \
  src/main/java/com/openggf/FrameAdmission.java \
  src/main/java/com/openggf/LevelFrameResult.java
```

Expected: all commands exit zero. Tasks 1+ consume only this recorded SHA.

Task 0 creates no fade-lifecycle production or test commit. Its only code
history change is the reviewed prerequisite integration composition needed to
produce `COMPOSED_BASE_SHA`.

### Task 1: Atomic Typed Request Claims

**Files:**

- Create: the first nine type/interface files listed under “New lifecycle package”
- Modify: `src/main/java/com/openggf/level/LevelTransitionCoordinator.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java`
- Test: `src/test/java/com/openggf/level/TestFadeTransitionRequestSource.java`

**Interfaces:**

- Consumes: existing `requestRespawn`, `requestNextAct`, `requestNextZone`, `requestZoneAndAct`, and `requestCreditsTransition`.
- Produces: `FadeTransitionRequestSource` with atomic `FadeTransitionClaim` and exact-token acknowledge/requeue.

- [ ] **Step 1: Write `claimsFadeRequestsInProductionPriorityWithoutClearingLowerRequests`**

Queue all five requests, including `requestZoneAndAct(4, 1, true, 0x2A)`. Construct the request-source adapter with `() -> Optional.of(SaveReason.PROGRESSION_SAVE)`. Assert successive claim/acknowledge pairs are `Respawn`, `NextAct`, `NextZone`, `ZoneAct(4,1,0x2A,true)`, and `Credits(Optional.of(PROGRESSION_SAVE))`; assert all tokens differ and lower requests remain claimable. The adapter passes the supplier value into the coordinator's package-private atomic claim operation, so the token ledger stores the save reason and requeue never consults the supplier again.

- [ ] **Step 2: Write exact-token safety assertions**

In the same class, add `acknowledgeRejectsNonActiveToken` and `requeueReturnsExactTokenAndPayload`. Assert a foreign token throws `IllegalArgumentException`; assert requeue/reclaim returns an equal token and equal record payload.

- [ ] **Step 3: Run the RED tests**

Run:

```bash
mvn -Dmse=off -Dtest=TestFadeTransitionRequestSource test
```

Expected: compilation fails because the typed request protocol does not exist.

- [ ] **Step 4: Add the typed records and sealed request variants**

Implement the exact type contract above. Each request record returns its literal policy: black/non-freezing for respawn/next-act/next-zone; black/captured-deactivation for zone/act; white/freezing/keep-freeze for credits.

- [ ] **Step 5: Replace destructive per-flag consumption with a token ledger**

In `LevelTransitionCoordinator`, keep published flags/payloads until atomic claim, snapshot the selected payload into one `FadeTransitionClaim`, and track one active or requeued claim. `acknowledge` clears only the selected published flag; `requeue` retains the same claim object and never clears lower flags. Preserve all special/bonus/seamless/title-card methods unchanged.

- [ ] **Step 6: Delegate the request-source interface through `LevelManager`**

Expose the three exact methods on `LevelManager`; do not expose separate token or payload getters.

- [ ] **Step 7: Run focused and existing coordinator tests**

Run:

```bash
mvn -Dmse=off -Dtest=TestFadeTransitionRequestSource,TestLevelTransitionCoordinator test
```

Expected: PASS with no request-priority or payload loss.

- [ ] **Step 8: Stage and commit**

```bash
git add \
  src/main/java/com/openggf/game/transition/FadeStyle.java \
  src/main/java/com/openggf/game/transition/ClaimToken.java \
  src/main/java/com/openggf/game/transition/ClaimedFadeTransition.java \
  src/main/java/com/openggf/game/transition/FadeTransitionClaim.java \
  src/main/java/com/openggf/game/transition/FadeTransitionRequestSource.java \
  src/main/java/com/openggf/game/transition/LevelManagerFadeTransitionRequestSource.java \
  src/main/java/com/openggf/game/transition/FadeTransitionStarter.java \
  src/main/java/com/openggf/game/transition/FadeTransitionStartEffectsPort.java \
  src/main/java/com/openggf/game/transition/FadeTransitionLoadPort.java \
  src/main/java/com/openggf/game/transition/TransitionBoundaryPort.java \
  src/main/java/com/openggf/level/LevelTransitionCoordinator.java \
  src/main/java/com/openggf/level/LevelManager.java \
  src/test/java/com/openggf/level/TestFadeTransitionRequestSource.java
git commit -m "refactor(level): claim fade transitions atomically"
```

Commit trailers: `Changelog: n/a: internal lifecycle extraction with no user-visible behavior yet`; all other trailers `n/a`.

### Task 2: Lifecycle Policy, Ordering, and Failure State

**Files:**

- Create: `src/main/java/com/openggf/game/transition/FadeLevelTransitionLifecycle.java`
- Create: `src/main/java/com/openggf/game/transition/FadeLevelTransitionSnapshot.java`
- Create: `src/main/java/com/openggf/game/transition/FadeTransitionCallbackException.java`
- Test: `src/test/java/com/openggf/game/transition/TestFadeLevelTransitionLifecycle.java`
- Modify: `src/main/java/com/openggf/LevelFrameResult.java`

**Interfaces:**

- Consumes: Task 1 request source and port interfaces plus canonical `FrameAdmission`.
- Produces: `TRANSITION_ONLY` arbitration, exact start order, typed freeze behavior, terminal callback failure, and lifecycle snapshot value.

- [ ] **Step 1: Add the canonical RED result assertion**

Add `transitionOnlyIsCanonicalLevelFrameResult` and assert:

```java
assertEquals(LevelFrameResult.TRANSITION_ONLY,
        lifecycle.arbitrateAfterPresentation(
                new FrameAdmission(LevelFrameResult.GAMEPLAY_FRAME)).result());
```

with one pending respawn claim.

- [ ] **Step 2: Add `typedFadeFreezePolicy`**

Parameterize all five request records. Assert start row always returns `TRANSITION_ONLY`; subsequent active rows return canonical gameplay for the three non-freezing records, follow `deactivateLevelNow` for both zone/act cases, and return `TRANSITION_ONLY` for credits.

- [ ] **Step 3: Add exact start-order and pause/setup precedence tests**

`startEffectsAndAcknowledgementFollowExactOrderAtMostOnce` records:

```text
claim(token,request), prepare(token,request), startOut(style), acknowledge(token)
```

`pausedPendingRequestUsesProductionPrecedence` expects `TRANSITION_ONLY` from canonical `PAUSED`. `setupOnlyPrecedesPendingFadeClaim` expects unchanged `SETUP_ONLY` and zero request-source calls.

- [ ] **Step 4: Add synchronous and asynchronous failure RED tests**

`synchronousStartFailureRequeuesExactClaim` throws first from `startOut`, then asserts same token/request on retry and one preparation call for each already-completed effect. `loadFailureIsLoudHeldAndNeverRetried` invokes the captured callback twice and asserts one load call, `FAILED`, no requeue, and `TRANSITION_ONLY` forever. Add the equivalent `creditsHandoffFailureIsLoudAndNeverUnfreezes`.

- [ ] **Step 5: Run the RED lifecycle suite**

```bash
mvn -Dmse=off -Dtest=TestFadeLevelTransitionLifecycle test
```

Expected: compile failure for `TRANSITION_ONLY` and missing lifecycle.

- [ ] **Step 6: Add `TRANSITION_ONLY` and the minimal lifecycle state machine**

Implement `IDLE`, `FADING_OUT`, `LOADING_OR_HANDOFF`, and `FAILED`; return early for `SETUP_ONLY`; claim only when the starter is not already active; call `prepareStart`, `startOut`, then acknowledge. Wrap synchronous preparation/start exceptions with exact-token requeue and rethrow.

- [ ] **Step 7: Implement completion ownership**

The callback sets `LOADING_OR_HANDOFF`, calls `loadPort.perform`, then calls exactly one boundary method selected by request kind. For non-credit requests call `startIn(BLACK)` and retain active ownership until `starter.isActive()` is false. For credits call no `startIn`; keep freezing until `onEndingModeInstalled` succeeds. Any callback exception stores the failure, sets `FAILED`, and rethrows without requeue.

- [ ] **Step 8: Run the lifecycle suite**

```bash
mvn -Dmse=off -Dtest=TestFadeLevelTransitionLifecycle test
```

Expected: PASS.

- [ ] **Step 9: Stage and commit**

```bash
git add \
  src/main/java/com/openggf/LevelFrameResult.java \
  src/main/java/com/openggf/game/transition/FadeLevelTransitionLifecycle.java \
  src/main/java/com/openggf/game/transition/FadeLevelTransitionSnapshot.java \
  src/main/java/com/openggf/game/transition/FadeTransitionCallbackException.java \
  src/test/java/com/openggf/game/transition/TestFadeLevelTransitionLifecycle.java
git commit -m "feat(level): add shared fade transition lifecycle"
```

Because this is user-visible runtime behavior under `src/main`, stage `CHANGELOG.md` with a concise fade-transition parity entry and set `Changelog: updated`; other trailers `n/a`.

### Task 3: Token-Idempotent Start Effects

**Files:**

- Create: `src/main/java/com/openggf/game/transition/FadeTransitionStartEffects.java`
- Test: `src/test/java/com/openggf/game/transition/TestFadeTransitionStartEffects.java`

**Interfaces:**

- Consumes: `ClaimToken`, `ClaimedFadeTransition`, and narrow functional delegates for save, audio fade, recording stop, and playback-boundary notification.
- Produces: `prepareStart(token, request)` with an individual completion bit for each effect.

- [ ] **Step 1: Write the four partial-failure RED cases**

For each effect position, throw once after all prior effects succeed, invoke `prepareStart` again with the same token, and assert successful earlier effects remain at one call while the failed and later effects resume in order.

- [ ] **Step 2: Assert token isolation and request semantics**

`differentTokensHaveIndependentLedgers` expects every effect once for each token. `saveRunsOnlyWhenRequestCarriesSaveReason` passes `Credits(Optional.of(PROGRESSION_SAVE))` and asserts that exact reason is saved; `Credits(Optional.empty())` and ordinary requests do not save. Preparation never consults `EndingProvider`.

- [ ] **Step 3: Run RED**

```bash
mvn -Dmse=off -Dtest=TestFadeTransitionStartEffects test
```

Expected: compilation fails because `FadeTransitionStartEffects` is absent.

- [ ] **Step 4: Implement the per-token effect ledger**

Use a `Map<ClaimToken, EnumSet<StartEffect>>`; mark an effect complete only after its delegate returns. Execute in fixed order `SAVE`, `AUDIO_FADE`, `STOP_RECORDING`, `PLAYBACK_BOUNDARY`. Reject reuse of one token with a non-equal request.

- [ ] **Step 5: Run focused tests**

```bash
mvn -Dmse=off -Dtest=TestFadeTransitionStartEffects,TestFadeLevelTransitionLifecycle test
```

Expected: PASS.

- [ ] **Step 6: Stage and commit**

```bash
git add \
  src/main/java/com/openggf/game/transition/FadeTransitionStartEffects.java \
  src/test/java/com/openggf/game/transition/TestFadeTransitionStartEffects.java
git commit -m "fix(level): make fade start effects token idempotent"
```

Trailers: `Changelog: n/a: internal retry-safety support for the documented transition behavior`; all others `n/a`.

### Task 4: Production Fade, Load, and Boundary Ports

**Files:**

- Create: `src/main/java/com/openggf/game/transition/GameLoopFadeTransitionPorts.java`
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Modify: `src/main/java/com/openggf/graphics/FadeManager.java`
- Modify: `src/test/java/com/openggf/TestGameLoop.java`
- Test: `src/test/java/com/openggf/TestGameLoopFadeTransitionIntegration.java`

**Interfaces:**

- Consumes: existing `GameLoop` methods `requestSessionSave`, recording stop, `finishUserRecordingPlaybackAtLevelBoundary`, `activateScheduledPlaybackForLoadedLevel`, level loads, post-load music, ending initialization, and mode changes.
- Produces: concrete starter/start-effects/load/boundary ports; `GameLoop` installs exactly one instance into the current `GameplayModeContext` and thereafter retrieves that session-owned instance.

- [ ] **Step 1: Write `productionPortsPreserveLoadSideEffectOrder`**

For explicit zone/act with music, assert:

```text
suppress ordinary music → loadZoneAndAct → activate scheduled playback
→ play requested post-load music → LEVEL_LOAD boundary → startIn(BLACK)
```

Assert start effects occurred before `startOut`, not inside the callback.

- [ ] **Step 2: Write `creditsFreezeThroughFadeAndEndingModeHandoff`**

Assert white fade-out, no black fade-in, one ending initialization, one mode boundary, and lifecycle ownership retained until mode installation. Add the no-ending-provider branch assertion: it installs title-screen ownership once and does not expose level gameplay.

- [ ] **Step 3: Write boundary de-duplication assertions**

`levelLoadBoundaryIsNotDuplicatedWhenLevelManagerAlreadyEmittedIt` installs a
`RewindBoundaryReporter` probe, runs `LevelManager.loadZoneAndAct`, calls the
no-op `onLevelLoaded`, and asserts one `LEVEL_LOAD`. The ending equivalent calls
`setGameMode`, then the no-op `onEndingModeInstalled`, and asserts only the
existing mode boundary.

- [ ] **Step 4: Run RED**

```bash
mvn -Dmse=off -Dtest=TestGameLoopFadeTransitionIntegration,TestGameLoop test
```

Expected: new behavioral tests fail because five requests are still consumed inline and ending state is still owned by `GameLoop`.

- [ ] **Step 5: Adapt `FadeManager`**

Implement a small adapter that maps `startOut(BLACK, cb)` to `startFadeToBlack(cb)`, `startOut(WHITE, cb)` to `startFadeToWhite(cb)`, `startIn(BLACK)` to `startFadeFromBlack(null)`, and `startIn(WHITE)` to `startFadeFromWhite(null)`. Keep counter advancement and callback storage in `FadeManager`.

- [ ] **Step 6: Port respawn load**

Map `Respawn` to `levelManager.loadCurrentLevel()` followed by scheduled
playback activation. Preserve the existing log and exception behavior.

- [ ] **Step 7: Port next-act load**

Map `NextAct` to `levelManager.nextAct()` followed by scheduled playback
activation; wrap `IOException` as `"Failed to load next act"`.

- [ ] **Step 8: Port next-zone load**

Map `NextZone` to `levelManager.nextZone()` followed by scheduled playback
activation; wrap `IOException` as `"Failed to load next zone"`.

- [ ] **Step 9: Port explicit zone/act load**

For `ZoneAct`, suppress ordinary music only when `postLoadMusicId >= 0`, call
`loadZoneAndAct`, activate scheduled playback, then play the captured override;
preserve the existing zone/act exception text.

- [ ] **Step 10: Port credits handoff**

Initialize the current `EndingProvider`, select its phase mode through
`setGameMode`, or install title-screen ownership when absent. Do not start a
black fade-in.

- [ ] **Step 11: Install the production lifecycle**

After gameplay managers attach, construct one lifecycle from
`LevelManagerFadeTransitionRequestSource`, the concrete start effects, and
production ports, then call
`context.installFadeLevelTransitionLifecycle(lifecycle)`. On each level outer
frame retrieve `context.fadeLevelTransitionLifecycle()`. Remove the five
`consume*Request` branches, private `start*Fade` methods, duplicated
`endingTransitionPending`, and duplicated start effects only after the
behavioral tests cover their replacements.

- [ ] **Step 12: Remove the five inline consumers and fade starters**

Delete only the now-delegated `consumeRespawnRequest`,
`consumeNextActRequest`, `consumeNextZoneRequest`, `consumeZoneActRequest`,
`consumeCreditsRequest`, and their private start/load methods. Retain unrelated
special-stage, bonus-stage, seamless, and title-card branches.

- [ ] **Step 13: Replace source-shape assertions**

In `TestGameLoop`, assert that level-mode arbitration delegates to the lifecycle after canonical setup/pause admission and that `isNonRewindableTransitionPending()` delegates to lifecycle state. Do not assert private field spelling.

- [ ] **Step 14: Run production tests**

```bash
mvn -Dmse=off -Dtest=TestGameLoopFadeTransitionIntegration,TestGameLoop,FadeManagerTest test
```

Expected: PASS.

- [ ] **Step 15: Stage and commit**

```bash
git add \
  src/main/java/com/openggf/game/transition/GameLoopFadeTransitionPorts.java \
  src/main/java/com/openggf/GameLoop.java \
  src/main/java/com/openggf/graphics/FadeManager.java \
  src/test/java/com/openggf/TestGameLoop.java \
  src/test/java/com/openggf/TestGameLoopFadeTransitionIntegration.java
git commit -m "refactor(level): share production fade transition ports"
```

Stage `CHANGELOG.md` only if Task 2 did not already contain the final user-visible entry; trailer must remain `Changelog: updated` across the behavior-changing series.

### Task 5: One Consuming Outer-Frame Funnel

**Files:**

- Create: `src/main/java/com/openggf/tools/OuterFramePresenter.java`
- Modify: `src/main/java/com/openggf/tools/RecordingFrameDriver.java`
- Modify: `src/test/java/com/openggf/tools/TestRecordingFrameDriverInputOnly.java`
- Test: `src/test/java/com/openggf/tools/TestRecordingFrameDriverFadeTransitions.java`

**Interfaces:**

- Consumes: canonical `FrameAdmission`, shared lifecycle, and `OuterFramePresenter.advancePreTransitionArbitration(LogicalInputSnapshot)`.
- Produces: identical consumption/presentation/arbitration for normal, previous-input, skip, input-only, and animation-only paths while preserving every existing public `int` mask return.

- [ ] **Step 1: Write `setupOnlyIsOnlyNonConsumingAdmission`**

Capture BK2 index, driver frame, previous snapshot, forced-jump edge, audio presentation count, title-card count, fade counter, and request-source call count. Assert all remain unchanged for `SETUP_ONLY`; parameterize every other `LevelFrameResult` and assert one consume/commit/presentation.

- [ ] **Step 2: Write fade ordering RED tests**

`activeFadeFromBlackSetupOnlyRetryDoesNotAdvanceAnything` seeds fade counter K and row I, then asserts retry advances to K+1/I+1 once. `transitionStartRowTicksPresentationBeforeFadeStarts` records events and asserts `present,startOut`; fade counter remains zero until the next consumed row.

- [ ] **Step 3: Write gameplay exclusion RED tests**

`transitionOnlyConsumesInputButRunsNoGameplay` asserts cursor/frame/input/audio/presentation +1 and player/object/camera/oscillator/level-counter/rewind-closure unchanged. `pausedConsumedRowAdvancesExistingFadeExactlyOnce` asserts the same consuming presentation contract with no gameplay.

- [ ] **Step 4: Parameterize all five entry points**

`freezingTransitionUsesEveryRecordingEntryPoint` covers:

```text
stepFrameFromRecording
stepFrameFromRecordingUsingPreviousInput
skipFrameFromRecording
consumeRecordingFrameInputOnly
advancePlayableAnimationsOnly coupled to its consuming row
```

Each must leave its public mask return unchanged, set
`driver.getLastFrameResult()` to `TRANSITION_ONLY`, advance once, and run no
gameplay/animation-only mutation while frozen.

- [ ] **Step 5: Run RED**

```bash
mvn -Dmse=off \
  -Dtest=TestRecordingFrameDriverFadeTransitions,TestRecordingFrameDriverInputOnly test
```

Expected: failures show skip/input-only paths bypass admission/presentation and normal driver title-card update occurs before the required funnel.

- [ ] **Step 6: Implement the private consuming funnel**

Refactor the private driver funnel around:

```java
FrameAdmission canonical = LevelFrameStep.admit(context, levelManager, startEdge);
if (canonical.result() == LevelFrameResult.SETUP_ONLY) {
    return new DrivenRecordingFrame(inputMask, canonical.result());
}
commitConsumedRow(snapshot);
outerFramePresenter.advancePreTransitionArbitration(snapshot);
FrameAdmission admitted = lifecycle.arbitrateAfterPresentation(canonical);
return new DrivenRecordingFrame(
        inputMask, executeAdmission(admitted, beforeGameplay));
```

Move title-card/fade/audio presentation into `OuterFramePresenter`; remove the independent `updateActiveTitleCardOverlay()` call from `stepFrame`.

The public method assigns `lastFrameResult = driven.result()` and returns
`driven.inputMask()`.

- [ ] **Step 7: Route normal recording input**

Make `stepFrameFromRecording` call the private funnel and preserve its existing
mask return and `beforeGameplay` callback.

- [ ] **Step 8: Route previous-input recording**

Make `stepFrameFromRecordingUsingPreviousInput` pass the previous row's logical
snapshot but the current row's validation mask; advance its cursor only when
the result is not `SETUP_ONLY`.

- [ ] **Step 9: Route skipped/VBlank-only rows**

Move held-title-card, level-event VBlank-only, and VBlank-counter work into the
admission-specific executor; a freezing `TRANSITION_ONLY` row runs none of that
gameplay-owned work.

- [ ] **Step 10: Route input-only rows**

Commit the logical snapshot and press edge through the funnel without timers,
sprites, camera, oscillator, or closure work.

- [ ] **Step 11: Gate animation-only work**

Keep `advancePlayableAnimationsOnly()` as `void`; execute it only when the
immediately consumed row's `lastFrameResult` permits animation-only work, and
skip it for `TRANSITION_ONLY`.

- [ ] **Step 12: Preserve input-edge behavior**

Commit `previousDriverSnapshot`, `inputHandler.update()`, current BK2 index, frame counter, and forced action edge exactly once before execution for every consuming result. Ensure the first destination gameplay row observes the transition row as its previous input.

- [ ] **Step 13: Run driver tests**

```bash
mvn -Dmse=off \
  -Dtest=TestRecordingFrameDriverFadeTransitions,TestRecordingFrameDriverInputOnly,TestPlaybackAdvanceOnlyInputBridge test
```

Expected: PASS.

- [ ] **Step 14: Stage and commit**

```bash
git add \
  src/main/java/com/openggf/tools/OuterFramePresenter.java \
  src/main/java/com/openggf/tools/RecordingFrameDriver.java \
  src/test/java/com/openggf/tools/TestRecordingFrameDriverInputOnly.java \
  src/test/java/com/openggf/tools/TestRecordingFrameDriverFadeTransitions.java
git commit -m "fix(trace): drive fade transitions through consumed rows"
```

Stage `CHANGELOG.md` and use `Changelog: updated`.

### Task 6: Rewind Poison, Snapshot Coverage, and Boundaries

**Files:**

- Modify: `src/main/java/com/openggf/game/transition/FadeLevelTransitionLifecycle.java`
- Modify: `src/main/java/com/openggf/game/transition/FadeLevelTransitionSnapshot.java`
- Modify: `src/main/java/com/openggf/game/transition/FadeTransitionStartEffects.java`
- Modify: `src/main/java/com/openggf/level/LevelTransitionCoordinator.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java`
- Modify: `src/main/java/com/openggf/game/session/GameplayModeContext.java`
- Test: `src/test/java/com/openggf/game/transition/TestFadeTransitionRewind.java`
- Test: `src/test/java/com/openggf/tests/TestRewindAcrossFadeLevelTransition.java`

**Interfaces:**

- Consumes: lifecycle phase/request/token/effect ledger and existing `RewindBoundary.LEVEL_LOAD`/mode boundary.
- Produces: idle-only ordinary restore, preclaim deactivation poison, callback poison, post-load identity closure, and complete coverage. `FadeTransitionStartEffects` implements `RewindSnapshottable<FadeTransitionStartEffects.Snapshot>` under key `fade-transition-start-effects`; the lifecycle snapshot does not duplicate that ledger.

- [ ] **Step 1: Write `idleLifecycleSnapshotRestores`**

Snapshot idle state with no pending deactivating request, mutate request-source counters, restore, and assert exact state equality.

- [ ] **Step 2: Write the four refusal tests**

`pendingDeactivatingRequestIsNonRewindableBeforeClaim`, `pendingCallbackSnapshotIsRefused`, `callbackFreeFadeInRemainsNonRestorableUntilIdle`, and `failedLifecycleNeverRestores` must each assert the existing rewind engagement predicate rejects the snapshot.

- [ ] **Step 3: Write `postLoadEpochContainsNoRetiredObjectIds`**

Capture old playable/object rewind ids, complete one level load, assert `LEVEL_LOAD` boundary once, and assert no old identity is resolvable in the destination epoch.

- [ ] **Step 4: Run RED**

```bash
mvn -Dmse=off \
  -Dtest=TestFadeTransitionRewind,TestRewindAcrossFadeLevelTransition,TestRewindCoverageGuard test
```

Expected: new tests fail because lifecycle state is not registered and callback-free fade-in is not lifecycle-poisoned.

- [ ] **Step 5: Register all mutable state**

Capture lifecycle phase, active request, token, fade style/freeze policy through
the request record, credits handoff state, and failure descriptor in the
lifecycle adapter. Capture the token-to-effect `Map` in
`FadeTransitionStartEffects.Snapshot`, deep-copying each `EnumSet`. Register
both adapters from
`GameplayModeContext.installFadeLevelTransitionLifecycle(...)`; deregister both
from `clearFadeLevelTransitionLifecycleForTeardown()`. Restore is allowed only
when the lifecycle snapshot and current lifecycle are both `IDLE`; restore the
effects snapshot in the same registry transaction. Do not baseline-ignore any
field.

- [ ] **Step 6: Preserve preclaim poison**

Make `isRewindBlocked()` consult both `LevelTransitionCoordinator` pending deactivating state and lifecycle active/failed state. Claiming must not create a one-frame restorable gap.

- [ ] **Step 7: Run rewind and architecture guards**

```bash
mvn -Dmse=off \
  -Dtest=TestFadeTransitionRewind,TestRewindAcrossFadeLevelTransition,TestRewindCoverageGuard,TestStaticStateRewindCoverageGuard,TestProductionSingletonClosureGuard,TestRewindRoundTripProbe test
```

Expected: PASS. If `TestRewindRoundTripProbe` finds a genuine gap, update `docs/status/rewind-round-trip-gaps.md`; do not weaken the guard.

- [ ] **Step 8: Stage and commit**

```bash
git add \
  src/main/java/com/openggf/game/transition/FadeLevelTransitionLifecycle.java \
  src/main/java/com/openggf/game/transition/FadeLevelTransitionSnapshot.java \
  src/main/java/com/openggf/game/transition/FadeTransitionStartEffects.java \
  src/main/java/com/openggf/level/LevelTransitionCoordinator.java \
  src/main/java/com/openggf/level/LevelManager.java \
  src/main/java/com/openggf/game/session/GameplayModeContext.java \
  src/test/java/com/openggf/game/transition/TestFadeTransitionRewind.java \
  src/test/java/com/openggf/tests/TestRewindAcrossFadeLevelTransition.java
git commit -m "fix(rewind): poison active fade level transitions"
```

If the rewind status file changes, stage it explicitly and set the appropriate documentation trailer according to `.githooks/run-policy`.

### Task 7: Real Headless and Capture Integration

**Files:**

- Create: `src/test/java/com/openggf/tests/TestFadeTransitionHeadlessIntegration.java`
- Create: `src/test/java/com/openggf/tools/TestTraceCaptureFadeTransitionParity.java`
- Modify: `src/main/java/com/openggf/tools/TraceCaptureTool.java`
- Modify: `src/main/java/com/openggf/trace/replay/TraceReplaySessionBootstrap.java`
- Modify: `src/test/java/com/openggf/tests/HeadlessTestRunner.java`
- Modify: `src/test/java/com/openggf/tests/HeadlessTestFixture.java`

**Interfaces:**

- Consumes: unified recording-driver funnel.
- Produces: live AIZ-controller transition, non-deactivating Sonic 2 transition, and headless/capture parity without frame selectors.

- [ ] **Step 1: Write the AIZ real-controller integration**

Create the method and fixture that loads AIZ2 and exposes the live end-sequence
controller. Do not drive it yet.

- [ ] **Step 2: Drive AIZ to the semantic request**

In
`aizControllerTransitionClearsRetiredRuntimeAndEntersHczTitleCard`, drive until
`levelManager.hasPendingFadeTransition()` with a 2,000-iteration safety bound;
fail with the controller routine/state when the bound expires.

- [ ] **Step 3: Assert the AIZ transition boundaries**

Continue the same test through completion and assert:

```text
request-start and deactivating fade rows consume input/presentation
retired AIZ player/object identities do not dispatch
one destination load replaces the playable through LevelManager
one LEVEL_LOAD boundary occurs
destination title-card ownership is active before destination gameplay
```

The safety bound is only a test timeout; no assertion or production branch may use a trace frame.

- [ ] **Step 4: Build the Sonic 2 request fixture**

Load the existing non-AIZ Sonic 2 headless fixture and publish
`requestZoneAndAct(destinationZone, destinationAct, false)` through its
`ObjectServices`.

- [ ] **Step 5: Assert the Sonic 2 non-deactivating integration**

`sonic2NonDeactivatingZoneActKeepsGameplayDuringFade` asserts the universal
start row is `TRANSITION_ONLY`, later black-fade rows admit gameplay, and the
first destination row receives the correct previous-input/action edge.

- [ ] **Step 6: Build the shared parity input**

Create one short in-memory `Bk2Movie` with a press edge on the transition row
and held input on the first destination row; use the same movie for both
adapters.

- [ ] **Step 7: Write headless/capture parity**

`headlessAndCaptureUseIdenticalFadeAdmissionSequence` feeds the same short BK2 movie and semantic request schedule to both adapters; assert equal consumed-row count, result sequence, presentation count, and first destination input snapshot.

- [ ] **Step 8: Run RED**

```bash
mvn -Dmse=off \
  -Dtest=TestFadeTransitionHeadlessIntegration,TestTraceCaptureFadeTransitionParity test
```

Expected: AIZ remains in retired state and capture lacks the shared lifecycle/presenter wiring.

- [ ] **Step 9: Install the headless lifecycle in bootstrap**

In `TraceReplaySessionBootstrap`, construct the headless ports after the
gameplay managers exist and install one lifecycle through
`GameplayModeContext.installFadeLevelTransitionLifecycle`. Construct
`RecordingFrameDriver` only afterward; its constructor retrieves the installed
instance.

- [ ] **Step 10: Route headless runner through the installed lifecycle**

Keep `HeadlessTestRunner` as a pure delegate to `RecordingFrameDriver`; remove
any transition-specific construction from the runner.

- [ ] **Step 11: Route capture through the same bootstrap**

Make `TraceCaptureTool` use the same bootstrap/session lifecycle and outer
presenter. It must not construct a lifecycle. Delete no replay phases and add
no trace-derived state writes.

- [ ] **Step 12: Run integration tests**

```bash
mvn -Dmse=off \
  -Dtest=TestFadeTransitionHeadlessIntegration,TestTraceCaptureFadeTransitionParity,TraceCaptureSessionTest test
```

Expected: PASS.

- [ ] **Step 13: Stage and commit**

```bash
git add \
  src/main/java/com/openggf/tools/TraceCaptureTool.java \
  src/main/java/com/openggf/trace/replay/TraceReplaySessionBootstrap.java \
  src/test/java/com/openggf/tests/HeadlessTestRunner.java \
  src/test/java/com/openggf/tests/HeadlessTestFixture.java \
  src/test/java/com/openggf/tests/TestFadeTransitionHeadlessIntegration.java \
  src/test/java/com/openggf/tools/TestTraceCaptureFadeTransitionParity.java
git commit -m "test(trace): verify shared fade transition surfaces"
```

Trailers: `Changelog: n/a: integration coverage for the already documented runtime behavior`; other trailers `n/a`.

### Task 8: Target Trace, Non-LBZ Fleet, and Documentation

**Files:**

- Modify: `docs/status/trace-frontier-log.md`
- Modify: `docs/status/known-discrepancies.md` only if the implementation changes an approved bootstrap/discrepancy contract
- Modify: `CHANGELOG.md` if the final composed behavior entry is not already staged
- Test: existing trace and guard classes only

**Interfaces:**

- Consumes: completed lifecycle implementation.
- Produces: evidence that the f26107/f26179 lifecycle clusters are removed without regressing accepted non-LBZ frontiers.

- [ ] **Step 1: Discover ROM paths without renaming files**

```bash
TRACE_REPO_ROOT="$(git rev-parse --show-toplevel)"
S1_TRACE_ROM="$(find "$TRACE_REPO_ROOT" -maxdepth 1 -type f -name '*.gen' -exec sha1sum {} + |
  awk '$1=="69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b" {print substr($0,43); exit}')"
S2_TRACE_ROM="$(find "$TRACE_REPO_ROOT" -maxdepth 1 -type f -name '*.gen' -exec sha1sum {} + |
  awk '$1=="8bca5dcef1af3e00098666fd892dc1c2a76333f9" {print substr($0,43); exit}')"
S3K_TRACE_ROM="$(find "$TRACE_REPO_ROOT" -maxdepth 1 -type f -name '*.gen' -exec sha1sum {} + |
  awk '$1=="cfbf98c36c776677290a872547ac47c53d2761d6" {print substr($0,43); exit}')"
test -n "$S1_TRACE_ROM" -a -n "$S2_TRACE_ROM" -a -n "$S3K_TRACE_ROM"
printf '%s\n%s\n%s\n' "$S1_TRACE_ROM" "$S2_TRACE_ROM" "$S3K_TRACE_ROM"
```

Expected: three existing absolute paths; the selection itself verifies the
documented SHA-1 values.

- [ ] **Step 2: Run focused lifecycle/unit/guard gates**

```bash
mvn -Dmse=off \
  -Dtest=TestFadeTransitionRequestSource,TestFadeLevelTransitionLifecycle,TestFadeTransitionStartEffects,TestRecordingFrameDriverFadeTransitions,TestGameLoopFadeTransitionIntegration,TestFadeTransitionRewind,TestFadeTransitionHeadlessIntegration,TestTraceCaptureFadeTransitionParity,TestPlaybackAdvanceOnlyInputBridge,TestArchitecturalSourceGuard,TestTraceReplayInvariantGuard,TestTraceHydrateSwitchDefault,TestRewindCoverageGuard,TestStaticStateRewindCoverageGuard,TestProductionSingletonClosureGuard test
```

Expected: PASS with zero failures/errors/skips.

- [ ] **Step 3: Run AIZ complete-run alone**

```bash
mvn -Dmse=off -Ptrace-replay -Dsurefire.forkCount=1 -DreuseForks=false \
  -Dtest=TestS3kAizCompleteRunTraceReplay \
  "-Ds3k.rom.path=$S3K_TRACE_ROM" test
```

Expected: no f26107 retained-AIZ cluster and no f26179 destination-initialization cluster. If a later error appears, record its exact count and first frame/field; do not claim green unless the report contains zero comparison errors.

- [ ] **Step 4: Run standalone AIZ and must-keep-green guards**

```bash
mvn -Dmse=off -Ptrace-replay -Dsurefire.forkCount=1 -DreuseForks=false \
  -Dtest=TestS3kAizTraceReplay,TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils \
  "-Ds3k.rom.path=$S3K_TRACE_ROM" test
```

Expected: all must-keep-green classes pass and standalone AIZ retains or advances the accepted frontier from the composed prerequisite base.

- [ ] **Step 5: Run the focused AIZ end-sequence object suite**

```bash
mvn -Dmse=off -Dtest=TestAiz2BossEndSequenceObjects test
```

Expected: PASS; the controller still publishes the semantic HCZ request and no
object-specific behavior was replaced by trace or lifecycle state.

- [ ] **Step 6: Run each non-LBZ S3K trace class separately**

Run one class per Maven invocation, clearing only generated `target/trace-reports` and `target/surefire-reports` between invocations through the build's normal clean target:

```bash
for trace_class in \
  TestS3kCnzTraceReplay TestS3kCnzCompleteRunTraceReplay \
  TestS3kHczCompleteRunTraceReplay \
  TestS3kMgzTraceReplay TestS3kMgzCompleteRunTraceReplay \
  TestS3kIczCompleteRunTraceReplay TestS3kMhzCompleteRunTraceReplay \
  TestS3kGumballBonusTraceReplay TestS3kPachinkoBonusTraceReplay \
  TestS3kSlotsBonusTraceReplay TestS3kSpecialStageTraceReplay
do
  mvn -q clean
  mvn -Dmse=off -Ptrace-replay -Dsurefire.forkCount=1 -DreuseForks=false \
    "-Dtest=${trace_class}" "-Ds3k.rom.path=$S3K_TRACE_ROM" test
done
```

Expected: every previously green class stays green and every red class retains or advances its authoritative composed-base first-error frame. Do not run a wildcard that includes the excluded route.

- [ ] **Step 7: Run cross-game canaries separately**

```bash
mvn -Dmse=off -Ptrace-replay -Dsurefire.forkCount=1 -DreuseForks=false \
  -Dtest=TestS1Ghz1TraceReplay,TestS1Ghz1CompleteRunTraceReplay \
  "-Ds1.rom.path=$S1_TRACE_ROM" test
mvn -Dmse=off -Ptrace-replay -Dsurefire.forkCount=1 -DreuseForks=false \
  -Dtest=TestS2Ehz1TraceReplay,TestS2ArzLevelSelectTraceReplay \
  "-Ds2.rom.path=$S2_TRACE_ROM" test
```

Expected: accepted green/frontier state retained; no shared-admission regression.

- [ ] **Step 8: Update the frontier log**

Append command, composed base/branch SHA, target pass/fail, comparison-error count, first-error frame/field, explicit removal of f26107/f26179, all canary outcomes, and the next genuine target. State that excluded work was not inspected or run.

- [ ] **Step 9: Run documentation and diff guards**

```bash
mvn -Dmse=off \
  -Dtest=TestBuildToolingGuard,TestArchitecturalSourceGuard,TestTraceFixtureCompressionGuard test
git diff --check
```

Expected: PASS and no whitespace errors.

- [ ] **Step 10: Stage final documentation and commit**

```bash
git add docs/status/trace-frontier-log.md CHANGELOG.md
git add docs/status/known-discrepancies.md  # only when Step 7 established a real contract change
git commit -m "docs(trace): record fade transition frontier"
```

Set `Changelog: updated`; set `Known-Discrepancies: updated` only if that file is staged; all remaining trailers follow `.githooks/run-policy`.

## Final Review Checkpoint

## Approved RED Matrix Traceability

| Design item | Concrete test method | Task |
|---:|---|---:|
| 1 | `TestRecordingFrameDriverFadeTransitions.setupOnlyIsOnlyNonConsumingAdmission` | 5 |
| 2 | `TestRecordingFrameDriverFadeTransitions.activeFadeFromBlackSetupOnlyRetryDoesNotAdvanceAnything` | 5 |
| 3 | `TestRecordingFrameDriverFadeTransitions.transitionStartRowTicksPresentationBeforeFadeStarts` | 5 |
| 4 | `TestRecordingFrameDriverFadeTransitions.transitionOnlyConsumesInputButRunsNoGameplay` | 5 |
| 5 | `TestRecordingFrameDriverFadeTransitions.pausedConsumedRowAdvancesExistingFadeExactlyOnce` | 5 |
| 6 | `TestRecordingFrameDriverFadeTransitions.freezingTransitionUsesEveryRecordingEntryPoint` | 5 |
| 7 | `TestFadeTransitionRequestSource.claimsFadeRequestsInProductionPriorityWithoutClearingLowerRequests` | 1 |
| 8 | `TestFadeLevelTransitionLifecycle.startEffectsAndAcknowledgementFollowExactOrderAtMostOnce` and `TestFadeTransitionStartEffects.partialFailureResumesWithoutRepeatingCompletedEffects` | 2–3 |
| 9 | `TestFadeLevelTransitionLifecycle.synchronousStartFailureRequeuesExactClaim` | 2 |
| 10 | `TestFadeLevelTransitionLifecycle.loadFailureIsLoudHeldAndNeverRetried` | 2 |
| 11 | `TestFadeLevelTransitionLifecycle.typedFadeFreezePolicy` | 2 |
| 12 | `TestFadeLevelTransitionLifecycle.pausedPendingRequestUsesProductionPrecedence` | 2 |
| 13 | `TestFadeLevelTransitionLifecycle.setupOnlyPrecedesPendingFadeClaim` | 2 |
| 14 | `TestGameLoopFadeTransitionIntegration.creditsFreezeThroughFadeAndEndingModeHandoff` | 4 |
| 15 | `TestFadeLevelTransitionLifecycle.creditsHandoffFailureIsLoudAndNeverUnfreezes` | 2 |
| 16 | `TestFadeTransitionHeadlessIntegration.aizControllerTransitionClearsRetiredRuntimeAndEntersHczTitleCard` | 7 |
| 17 | `TestFadeTransitionHeadlessIntegration.sonic2NonDeactivatingZoneActKeepsGameplayDuringFade` | 7 |
| 18 | `TestTraceCaptureFadeTransitionParity.headlessAndCaptureUseIdenticalFadeAdmissionSequence` | 7 |
| 19 | `TestFadeTransitionRewind` methods plus `TestRewindAcrossFadeLevelTransition.postLoadEpochContainsNoRetiredObjectIds` | 6 |

Before integration, request two independent reviews:

1. Spec review: map every approved design section and all 19 RED matrix items to Tasks 1–8.
2. Code-quality review: verify there is one admission vocabulary, one presentation tick per consumed row, one atomic request claim, token-idempotent effects, one load/boundary owner, strict rewind poison, no selectors/hydration/tolerances, and no excluded-route contact.

After fixes, rerun Task 8 Steps 2–8 on the exact reviewed HEAD. Integrate only the reviewed commits onto current `develop`; then repeat the focused AIZ complete-run, standalone AIZ, cross-game canaries, non-LBZ S3K matrix, rewind guards, architecture guards, and `git diff --check` on the composed integration commit.
