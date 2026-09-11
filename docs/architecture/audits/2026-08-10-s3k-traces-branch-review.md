# Review: `bugfix/s3k-traces`

**Branch:** `bugfix/s3k-traces` @ `5b3425dca` · **Merge base:** `0a4642329` on `develop`
**Size:** 144 commits, 202 files, +14404 / -1703
**Date:** 2026-08-10 · **Method:** three independent reviewers over one read-only worktree,
each checking a different rule family against the disassemblies, plus a full
`-Ptrace-replay` profile run to verify the branch's own claims.

---

## Verdict

**The headline claim reproduces, and the work is mechanically disciplined.**

- **15 of 16 S3K trace-replay classes that are red on `develop` are green on this branch**
  (AIZ, CNZ, HCZ, ICZ, LBZ, MGZ standalone and complete-run, gumball, pachinko, slots,
  special stage, hardware timing), with **zero previously-green classes going red anywhere in
  the suite**. MHZ is outside the stated scope and its frontier visibly advanced.
- Every mechanical rule check is clean: **no committed trace fixture modified at all**, no
  uncompressed payloads, no `docs/*disasm` reads added to `src/main`, no zone- or game-name
  string literals in shared runtime, and complete commit-trailer compliance (the only eight
  commits without a `Changelog` trailer are merges, which CLAUDE.md exempts).
- The branch **deletes** fitted constants rather than adding them, and **strengthens**
  `TestHardwareTimingAuthorityGuard` rather than loosening it — adding
  `recordedEdgesCannotSelectProductionServiceBoundaries` to forbid
  `hasPendingCompletionAtCurrentRawFrame` in `src/main`. That is someone meeting the rule-4
  temptation and guarding against it.
- No assertion weakening: 316 assertions added against 54 removed, and all 54 removals were
  individually verified benign. No `@Disabled`, no new `Assumptions.assumeTrue`, no widened
  tolerances. Documentation is placed correctly.

**Two findings block merge**, both in the same family — a predicate or a code path that exists
in the engine but not in the ROM. Everything else is fixable in place.

---

## Findings at a glance

| # | Sev | Area | One line |
|---|---|---|---|
| A1 | **BLOCKER** | rule 3 | Sidekick on-screen re-admission adds three conjuncts the ROM does not have |
| B1 | **BLOCKER** | rule 4 | A production ROM art submission only happens when a trace says a row was held |
| A2 | MAJOR | rule 3 | `CNZ_POST_TITLE_CARD_CONTROL_HANDOFF_DISPATCHES` re-fitted 9 → 2; ROM delay is 0 |
| A3 | MAJOR | rule 2 | Per-object hurt-animation opt-out; ROM writes `anim` unconditionally |
| A4 | MAJOR | rule 3 | Title-card lookup keyed on `getVblaCounter() & 3` with no ROM citation |
| B2 | MAJOR | rule 3 | `FRESH_LEVEL_TRANSITION_OWNER_RETIREMENT_FRAMES = 3` stands in for a data-driven ROM test |
| C1 | MAJOR | tests | Three test classes left behind by the recorded-admission fixture contract |
| C2 | MAJOR | rule 2 | `"s3k".equals(metadata.game())` game-name gate, also redundant |
| C3 | MAJOR | tests | Load-queue comparison shifts and drops expected values |
| C4 | MAJOR | tests | AIZ terminal check replaced by fixture-measured ordinals and opaque digests |
| A5 | MINOR | rule 3 | Uncited per-zone dispatch knobs; comment and code disagree by +1 |
| A6 | MINOR | rule 2 | S3K camera render-copy ordering hard-coded for all games (behaviour-neutral today) |
| A7 | MINOR | docs | Correct call, wrong ROM citation |
| A8 | MINOR | hygiene | Partial CRLF normalisation in the three known mixed-ending files |
| C5–C10 | MINOR | various | Single-exec-path hook, missing held-row markers, CRLF, tabs, name-based guard, over-broad log claim |

Full detail, with ROM citations and reproduction for each, in the three lane reports appended
below.

---

## The two blockers

### A1 — sidekick on-screen re-admission (rule 3)

`SidekickCpuController.java:3979-3998` (commit `c7335ad21`) re-admits a sidekick as on-screen
under `screenIsShaken && isVisibleForCpuDispatch && physicalTopMargin > -width && leaderIsAirborne`.

The owning ROM routine `Tails_FlySwim_Unknown` (`skdisasm/sonic3k.asm:26534-26535`) tests
**only** `tst.b render_flags(a0) / bmi.s`. Three of the four conjuncts have no ROM expression.
The comment admits the predicate compensates a one-pixel engine discrepancy in the shaken
render copy — so it is a fitted correction for a defect elsewhere, and it is only ever true in
a boss-shake window with an airborne leader, which is a situational carve-out.

**Fix:** find the one-pixel render-copy discrepancy and correct that; the predicate then has
nothing to compensate.

### B1 — trace-gated production art submission (rule 4)

`Sonic3kObjectArtProvider.processRuntimeArtQueueAfterPreMainLoop(boolean heldLoopTail)`
(`:1582-1614`, commit `2a08c51b6`) submits the deferred AIZ2 `LoadEnemyArt` KosM batch **only
when `heldLoopTail` is true**. Its sole production caller is `LevelFrameStep.java:441`, passing
`frame.defersLoopTailPreparation()` — a flag set exclusively by
`TraceReplayBootstrap.markReplayIterationDefersLoopTailPreparation`.

So in any run without a trace, the batch is never submitted at all. The same signal also
selects the queue publication shape via `admittedOnHeldTail -> deferChildSubmissionAfterHeldAdmission`.

That is recorded data deciding **what happens**, not merely **when** already-submitted work
becomes ready — outside the hardware-timing exception however well the ROM behaviour is cited.
It is also a hard-rule-3 breach: the behaviour only exists under replay, so it cannot hold for
a movie nobody has recorded.

**Fix:** submit the batch unconditionally at the ROM's own boundary, and let the timing port
decide only when it becomes *ready*.

---

## Notable positives, verified

These were checked against the disassembly and are correct. Recording them so the next reviewer
does not re-litigate them:

- The `LostRingObjectInstance` rewrite reproduces `sonic3k.asm:35685-35701` branch for branch,
  including the subtle render-flag fall-through, with the per-game divergence placed in a typed
  `RingRules.lostRingBoundaryChecksOnlyOnProbeCadence` field — exactly the shape hard rule 2
  asks for. Cross-checked against `s1disasm/_incObj/25,37 Rings.asm:314-352`.
- `requestCnzPostTransitionRelease` **deletes** a fitted frame countdown in favour of an event
  boundary.
- `MgzDrillingRobotnik`'s `0x3F` verified against `BossDefeated` (`sonic3k.asm:180823`) plus
  `Wait_FadeToLevelMusic` (`:179656-179658`) = 64 frames.
- `Blastoid`'s `0x20` verified against `Obj_WaitOffscreen` (`:180274-180275`).
- Every added zone/act predicate sits inside an S3K zone provider; `ObjectSolidContactController`
  branches are gated on `ObjectInteractionRules`, never a game name.
- No `FixBugs = 1` branch is taken anywhere, and the one touched `FixBugs` site keeps its full
  annotation.

---


---

# Review — Lane A: fitted models (hard rule 3) and per-game placement (hard rule 2)

**Branch:** `bugfix/s3k-traces` @ `5b3425dca`, against merge base `0a4642329`.
**Scope reviewed:** all 128 changed files under `src/main/`, shallowly; deep on shared code
(`camera/`, `physics/`, `level/`, `sprites/`, `game/rules/`) and on every numeric literal the
branch adds to gameplay code (420 added lines carrying a literal, extracted mechanically and
triaged by hand).
**Read-only worktree used:** `<local>`. Nothing modified.

## Headline

The mechanical discipline on this branch is genuinely good, and that should be said plainly
before anything else. The orchestrator's clean sweep is not an accident of grep: where I
sampled ROM-derived constants at random they were **correct and correctly cited**, and the
branch contains at least one change (`Sonic3kLevelEventManager.requestCnzPostTransitionRelease`)
that *deletes* a fitted frame-countdown and replaces it with an event-driven publication
boundary — exactly the direction hard rule 3 asks for. Several rewrites (the lost-ring
control-flow rework in particular) are model-the-ROM work of a quality worth copying.

The findings below are concentrated in one area: **object-dispatch-ordering compensation**.
The branch has a recurring habit of expressing "the engine's slotless/folded object model
reaches this publication N passes later than the ROM's" as an integer knob (`...Dispatches`,
`...DelayFrames`) whose value is asserted in prose rather than read out of a routine. That
family is the fitted-model risk on this branch, and one member of it (F1) is a structural
carve-out rather than merely an uncited number.

---

## Findings, most consequential first

### F1 — BLOCKER — Sidekick off-screen check gains three non-ROM conjuncts, true only during a boss-shake-plus-airborne-leader situation

**File:** `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java:3979-3998`
**Commit:** `c7335ad21` "fix: advance S3K MGZ flight frontier"
**Rule:** Hard rule 3 (carve-out expressed structurally; "ROM-default behaviour except in
&lt;situation that only occurs in one act&gt;" is still a carve-out).

The code re-admits a sidekick as on-screen when:

```java
if (screenIsShaken
        && camera.isVisibleForCpuDispatch(sidekick)
        && physicalTopMargin > -sidekick.getRenderFlagWidthPixels()
        && leaderIsAirborne) {
    onScreen = true;
}
```

**Evidence.** The routine this models is `Tails_FlySwim_Unknown`
(`docs/skdisasm/sonic3k.asm:26534-26535`):

```
Tails_FlySwim_Unknown:
        tst.b   render_flags(a0)
        bmi.s   loc_13C3A
```

That is the whole test. The ROM reads bit 7 of `render_flags` and nothing else. It does not
read the screen-shake state, it does not read the leader's `Status_InAir`, and it has no
top-edge margin. Three of the four conjuncts have no owning ROM expression at all.

The comment above the block states the motive without disguise: *"The engine's shaken render
copy can be one pixel beyond the native top-edge window while the object/CPU pass is still
using the physical camera position."* That is a description of an engine discrepancy, and the
fix compensates for it at the consumer instead of at the producer. `screenIsShaken &&
leaderIsAirborne` is only simultaneously true in a boss-shake window with the leader in the
air — i.e. the MGZ situation the commit title names. Recorded with a different lag or a
different entry frame, the one-pixel condition this absorbs will land on a frame where
`leaderIsAirborne` is false and the compensation silently stops applying.

**What I'd want instead:** if the shaken render copy really is one pixel out relative to the
pass that publishes `render_flags`, fix the publication point (or the shake application
order), so the flag the CPU pass reads is the flag `Render_Sprites` actually left. Note this
is at least correctly fenced off from S1/S2 by `usesS3kCatchUpMarker()` — the per-game
containment is fine; the predicate is the problem.

---

### F2 — MAJOR — `CNZ_POST_TITLE_CARD_CONTROL_HANDOFF_DISPATCHES` retuned 9 → 2; the ROM value is 0

**File:** `src/main/java/com/openggf/game/sonic3k/Sonic3kLevelEventManager.java:100`, used at
`:1392-1406`
**Commit:** `606c6fa33` "fix: align S3K CNZ carried title handoff"
**Rule:** Hard rule 3 (a landed value must be traceable to the ROM routine that owns it).

**Evidence.** The owning routine is `Obj_EndSignControlDoStart`
(`docs/skdisasm/sonic3k.asm:180420-180424`):

```
Obj_EndSignControlDoStart:
        tst.b   (End_of_level_flag).w       ; Wait for title card to finish
        beq.w   locret_8405E
        jsr     Change_Act2Sizes(pc)
        jmp     (Delete_Current_Sprite).l
```

and `Change_Act2Sizes` (`:180580-180604`) falls straight through into `Make_LevelSizeObj`,
which creates the gradual children on that same dispatch. **There is no delay.** The dispatch
on which the flag is observed is the dispatch on which the size change happens and the workers
exist. The ROM-derivable value here is 0, not 2 and not the 9 it replaced.

Two things make this a MAJOR rather than a MINOR. First, the comment sitting immediately above
the constant (`:97-99`) is a *correct* citation — of the SST slot arithmetic
(`sonic3k.asm:7793`, `sonic3k.constants.asm:303-307`) — and it did not change when the value
moved 9 → 2. This is precisely the inverse trap: the citation licenses the surrounding
mechanism (which slot owns the handoff), not the number (how many dispatches it waits). A
reader scanning for provenance will find a file:line above a value it does not justify.
Second, the same commit adds a *second* compensating call in the same frame:

```java
// Sonic3kLevelEventManager.java:356-372 (updateAfterObjectsBeforeCamera)
updatePendingCnzAct2LevelSizeChange();
if (titleFlagWasConsumed) {
    updatePendingCnzAct2LevelSizeChange();   // burn an extra dispatch
}
```

so the effective delay is now the product of a retuned constant and a conditional double-tick.
Two independently-adjustable knobs multiplying into one observable is the shape a fitted model
takes when the underlying ordering is wrong.

**Related, smaller:** the comment at `:1393` says `Change_Act2Sizes` "allocates the four
gradual bound owners". `Child1_Act2LevelSize` (`sonic3k.asm:180608-180615`) is `dc.w 3-1` and
lists three (`Obj_IncLevEndXGradual`, `Obj_DecLevStartYGradual`, `Obj_IncLevEndYGradual`).
The engine carries four accumulators including a min-X one the ROM has no owner for. The
accumulators predate this branch, so this is context rather than a finding against it, but the
new comment restates the wrong count.

---

### F3 — MAJOR — `sidekickTouchHurtPublishesAnimation()` opt-out is contradicted by `HurtCharacter`, and is an object-family stand-in for one trace

**Files:** `src/main/java/com/openggf/level/objects/TouchResponseProvider.java:28-37`,
`src/main/java/com/openggf/level/objects/ObjectTouchResponseController.java:830-849`,
`src/main/java/com/openggf/game/sonic3k/objects/AizSpikedLogObjectInstance.java:443-449`
**Commit:** `bae71cf8e` "fix: advance S3K AIZ sidekick hurt animation frontier"
**Rule:** Hard rule 3 (structural carve-out); secondarily rule 2 (wrong owner).

The new hook lets an object declare that the generic sidekick touch-hurt path must *not*
publish the hurt animation byte, and exactly one production object overrides it: the AIZ
spiked-log child. The override's justification carries no file:line — *"ROM's spiked-log child
reaches the generic touch-hurt owner with Tails' existing anim byte still live"*.

**Evidence against.** `HurtCharacter` (`docs/skdisasm/sonic3k.asm:21109`):

```
loc_10320:
        move.w  #0,ground_vel(a0)
        move.b  #$1A,anim(a0)
        move.b  #120,invulnerability_timer(a0)
```

`anim = $1A` is written unconditionally, on the single path every touch response funnels
through. The only per-toucher branch anywhere in that routine is the SFX selection immediately
below it (`cmpi.l #Obj_Spikes,(a2)` … `sfx_SpikeHit`), which touches `d0`, not `anim`. There
is no object-family variation of the animation write to model, so the interface contract
("object families whose ROM touch owner leaves `anim` untouched") describes a category that
does not exist in the ROM.

Because the hook is keyed on an object identity and has exactly one true instance, its
predicate is functionally "the AIZ1 spiked log" — the same thing a zone carve-out would be, one
level of indirection down. Under rule 2 the smallest *accurate* owner is not an object hook
here; there is no per-object rule to own.

If the underlying divergence is real, the likely candidate is not the write but the *read*:
whether `Animate_Tails` runs before or after the touch dispatcher in the engine's pass order,
i.e. whether the `$1A` byte is observable on the comparison row at all. That is worth chasing
before landing the opt-out.

---

### F4 — MAJOR — Phase-keyed magic table in the in-level title-card handoff, no ROM citation

**File:** `src/main/java/com/openggf/game/sonic3k/titlecard/Sonic3kTitleCardManager.java:476-492`
**Commits:** `07bba825e3` (the `+6`), `3c5fff4bb8` (the `5 : 1 : 0` table and
`retainedControlPollFollowsTitleCompletion`)
**Rule:** Hard rule 3.

```java
int modulePhase = GameServices.level().getObjectManager().getVblaCounter() & 3;
boolean needsChildVisibilityCompensation = modulePhase == 1 || modulePhase == 2;
resetLevelGamestateCountdown = 24 + (needsChildVisibilityCompensation ? 6 : 0);
inLevelExitDelayFrames = modulePhase == 1 ? 5 : modulePhase == 2 ? 1 : 0;
retainedControlPollFollowsTitleCompletion = modulePhase == 2;
```

Five constants (24, 6, 5, 1, 0) and three phase predicates, and not one disassembly reference
in the surrounding twenty lines. The comments are self-referential — they describe the
*engine's* slotless manager ("reaches its predicted display point after 24 updates",
"five updates after the slotless manager would otherwise predict completion") rather than any
ROM routine. The base `24` is inherited from develop (`0424e98774`); the branch adds the
per-phase deltas on top of it, so this is aggravated inherited debt rather than something
invented here — but it is aggravated in the worst possible way, by adding a lookup keyed on
`V_int_run_count & 3`.

A per-phase correction table is the textbook signature the skill warns about: it will be right
for a recording that entered the transition on the phase it was tuned against and wrong for one
that entered a frame earlier. Note the file *also* contains correctly-derived constants a few
lines up (`DISPLAY_HOLD_FRAMES`/`FRESH_LEVEL_TRANSITION_HOLD_FRAMES = 22` cited to
`sonic3k.asm:7897-7900`, `Palette_fade_timer = $16`) — the contrast is what makes the
uncited block conspicuous.

---

### F5 — MINOR (systemic) — The per-zone `...Dispatches` knob family is uniformly uncited

**Files/values added or changed by this branch:**

| Value | Site |
|---|---|
| `2` | `Sonic3kICZEvents.java:337` (`preloadedActCameraReleaseAdditionalDispatches`) and `:724` |
| `1` | `Sonic3kMGZEvents.java:2513` (`carriedResultsRetireDispatches`) |
| `1` | `Sonic3kHCZEvents.java:921` (`carriedResultsRetireDispatches`) |
| `0` | `Sonic3kCNZEvents.java:876` |
| `1` / `2` | `Aiz2BossEndSequenceController.java:46-47`, used at `:240-252` (commit `849d53739f`) |

**Rule:** Hard rule 3 (under-evidenced values).

Every one of these is a small integer describing "how many more owner passes before the
publication lands", justified by prose and by nothing else. Individually each is defensible as
a modelling decision; collectively they are a tuning surface, and `CarriedTitlePublicationTiming`
(`src/main/java/com/openggf/level/CarriedTitlePublicationTiming.java`) exists to plumb it. The
branch widens that record by two more fields.

The AIZ2 pair is the one I'd fix first, because it is internally inconsistent. The comment at
`Aiz2BossEndSequenceController.java:240-247` says the two cases are *"retain one entry"* versus
*"restore immediately"* — which reads as 1 and 0 — while the code returns 2 and 1. A uniform
`+1` between the stated model and the landed values is the classic sign of a constant absorbing
an error one layer down. The cited range (`sonic3k.asm:62709-62720`) is
`clr.b (_unkFAA8).w / st (End_of_level_flag).w / jmp Delete_Current_Sprite` — real and
relevant, but it licenses *that the handoff exists*, not *that it takes one or two passes*.

I am filing this MINOR rather than MAJOR only because these knobs sit behind explicit
per-transition request objects rather than in shared physics, so the blast radius of each is
one transition. If the count keeps growing, it should be escalated: a growing table of
uncited integers is how a fitted model looks at scale.

---

### F6 — MINOR — Camera render-copy publication point is S3K's, applied to all three games

**Files:** `src/main/java/com/openggf/camera/Camera.java:25-29, 912-923, 953-963`,
`src/main/java/com/openggf/LevelFrameStep.java:374-381`
**Commits:** `febdc60b2` "fix: advance S3K LBZ camera-copy frontier", `c7335ad21`
**Rule:** Hard rule 2 / cross-game parity (the per-game *generalisation* direction).

The branch makes `getXWithShake()` / `getYWithShake()` — which everything visible reads —
return a `renderCopyX/Y` published once per frame by `camera.captureRenderCopy()`, called from
shared `LevelFrameStep` **before** `levelEvents.update()`. The comment cites S3K `ScreenEvents`,
and that citation is exact: `docs/skdisasm/sonic3k.asm:102233-102234` publishes both copies at
the head of `ScreenEvents`, ahead of the zone handlers.

But that ordering is S3K's, not the engine's other two games':

- **Sonic 2** publishes the copies *after* `RunDynamicLevelEvents`
  (`docs/s2disasm/s2.asm:15175-15179`):
  ```
  loc_C4D0:
          bsr.w   RunDynamicLevelEvents
          ...
          move.l  (Camera_X_pos).w,(Camera_X_pos_copy).w
          move.l  (Camera_Y_pos).w,(Camera_Y_pos_copy).w
  ```
  The engine now publishes S2's copy one stage *earlier* than S2's ROM does.
- **Sonic 1** has no such variable at all: `grep -r 'Camera_X_pos_copy\|Camera_Y_pos_copy'
  docs/s1disasm/` returns zero hits. S1's build reads `Camera_X_pos` directly.

**Today this is behaviour-neutral**, which is why it is MINOR and not MAJOR: I checked every
writer of `Camera.x/y`. `updatePosition` runs at step 4a (before the capture); every other
writer goes through `setX`/`setY`, which the branch correctly updates to keep the copy in
sync; and the sole decoupling API, `setYAfterRenderCopy`, has exactly one caller
(`Sonic3kLBZEvents.java:1138-1139`). So no S1/S2 path can currently observe a stale copy.

The risk is structural rather than present: the shared default is now "render uses the last
publication point, wherever S3K put it", and the first S1/S2 event-time camera move that uses
the new API — or any future direct field write during `levelEvents.update()` — will be wrong
for S2 and meaningless for S1. Per `docs/architecture/per-game-rule-placement.md` the
publication point belongs in a typed `GameRules` sub-record (it is a game-wide runtime gate),
not hard-coded to one game's ordering in `LevelFrameStep`.

**Also worth noting:** `Camera.isVisibleForCpuDispatch` is a second visibility policy that the
Javadoc itself calls a "timing bridge". It exists solely to serve F1; if F1 is resolved at the
producer, this method should go with it.

---

### F7 — MINOR — Mis-cited routine on the new solid-push release call

**File:** `src/main/java/com/openggf/level/objects/ObjectSolidContactController.java:1796-1806`
**Rule:** citation accuracy.

The added `publishSolidPushReleaseAnimationWord(...)` call is **correct** — I verified it — but
the comment attributes the walk/run write to `sub_1E0C2` (`sonic3k.asm:41528-41532`), which
only clears the two push bits:

```
sub_1E0C2:
        move.l  d6,d4
        addq.b  #pushing_bit_delta,d4
        bclr    d4,status(a0)
        bclr    #Status_Push,status(a1)
```

The routine that actually writes `anim` is `loc_1E0A2` immediately above
(`sonic3k.asm:41517-41525`), which skips on `anim == 2` (Roll) and `anim == 9` (Spindash) and
otherwise does `move.w #1,anim(a1)` before falling into `sub_1E0C2`. The engine's guards match
`loc_1E0A2` exactly — the code is right, the pointer is off by one label. Retarget the comment
so the next reader can find the licence.

---

### F8 — MINOR — Line endings normalised in two of the three known mixed-CRLF files

**Files:** `src/main/java/com/openggf/level/rings/RingManager.java` (8 lines),
`src/main/java/com/openggf/level/objects/ObjectPlacementController.java` (~57 lines),
`src/main/java/com/openggf/sprites/managers/PlayableSpriteMovement.java`
**Commit:** `5b3425dca` "chore: normalize touched source formatting"

Measured:

```
git diff --numstat 0a4642329..HEAD -- <files>
  8   8    RingManager.java
302 269    PlayableSpriteMovement.java
git diff --ignore-cr-at-eol --numstat 0a4642329..HEAD -- <files>
  (RingManager absent — zero real change)
 45  12    PlayableSpriteMovement.java
```

`ObjectPlacementController.java`'s entire diff is CRLF→LF churn with no logic change; the
`RingManager` hunk is 8 lines of pure `^M` removal. `PlayableSpriteMovement` has 45 real added
lines buried in 302 lines of ending churn.

No correctness impact, and the intent (converging on LF) is defensible. But CLAUDE.md's
warning about these files exists because partial normalisation makes conflicts on
long-lived S3K branches much worse, and it hides real changes from reviewers — I had to use
`--ignore-cr-at-eol` to find the 45 lines that matter in `PlayableSpriteMovement`. Either
normalise these files wholesale in a standalone commit on `develop` (with a `.gitattributes`
entry so they stay normalised), or leave them alone. Doing it partially, inside a 144-commit
feature branch, is the worst of the three options.

---

## What I checked and found correct

These are positive results, not padding — each was a live suspicion that the ROM cleared.

- **`LostRingObjectInstance` control-flow rewrite** (`2e509a603`, `74aaffb8f`) — the best
  modelling work on the branch. The rewrite reproduces S3K `Obj_Bouncing_Ring`
  (`docs/skdisasm/sonic3k.asm:35685-35701`) branch for branch, including the genuinely subtle
  part: `bmi.s loc_1A83C` (rising) and `bne.s loc_1A83C` (off-cadence) skip the boundary check
  entirely, while `bpl.s loc_1A828` (render flag clear) skips only the terrain probe and
  **still falls through** to it. The engine's `stepPhysics` return value encodes exactly that
  three-way distinction. The per-game divergence is placed in a typed `RingRules` field
  (`lostRingBoundaryChecksOnlyOnProbeCadence`), which is the correct owner under rule 2, with
  S1/S2 defaults cited to `Rings.asm:314-356` and `s2.asm:25209-25249`. Textbook.
- **`requestCnzPostTransitionRelease` de-fitting** — the branch *removes* the
  `cnzPendingPostTransitionReleaseFrames` countdown and replaces it with a boolean consumed at
  the modelled `_unkFAA8`-clear publication boundary, with the commentary explicitly noting
  *"instead of estimating its arrival from elapsed frames"*. This is the correct instinct and
  deserves credit even while F2 sits a hundred lines away.
- **`MgzDrillingRobotnikInstance.END_DEFEAT_FADE_WAIT_FRAMES = 0x3F`** — verified.
  `BossDefeated` writes `move.w #$3F,$2E(a0)` (`sonic3k.asm:180823`) and
  `Wait_FadeToLevelMusic` does `subq.w #1,$2E(a0) / bmi.s` (`:179656-179658`), giving the
  64-frame wait the comment claims. Correct value, correct routine, correct arithmetic.
- **`BlastoidBadnikInstance.WAIT_OFFSCREEN_MARGIN = 0x20`** — verified against
  `Obj_WaitOffscreen` (`sonic3k.asm:180274-180275`, `move.b #$20,width_pixels/height_pixels`).
- **`Aiz2BossEndSequenceController` geometry constants** (`$158`, `$1F8`, `$1E6`) — all carry
  routine-level citations and are pre-existing; unchanged by this branch.
- **Zone/act predicates.** The only `currentAct ==` / `zoneIndex ==` conditions the branch adds
  are inside `Sonic3kLevelEventManager` and `Sonic3kZoneFeatureProvider` — the owning zone-event
  and zone-feature providers. That is the boundary rule 2 explicitly permits. No zone predicate
  leaked into `physics/`, `sprites/` or shared `level/` code.
- **`ObjectSolidContactController` per-game guarding** — every branch of the new push-release
  path is gated on `ObjectInteractionRules` fields
  (`solidPushReleaseWritesWalkRunAnimationWord`, `...SkipsWalkRunWhenRolling`,
  `...SkipsWalkRunWhenSpindashing`), not on a game name. Correct placement.
- **`GameRules`/`RingRules` widening** — the two new booleans are added as typed record fields
  with all three per-game values supplied explicitly at the constant sites. No defaulting, no
  `if (game == S3K)`.
- **`SlotAllocator.firstFreeSlot()` / `ObjectManager.firstFreeDynamicSlot()`** — a non-mutating
  `FindFreeObj`, correctly named and correctly bounded. No concerns.

### FixBugs (lane item 4)

I found **no** case on this branch of ported logic silently taking the `FixBugs = 1` branch.
The one `FixBugs` site the branch touches is in `LostRingObjectInstance.update()`, and it
carries the full required annotation form — flag named, `docs/s1disasm/sonic.asm:20` cited as
0 in the shipped ROM, engine branch stated. That is the shape CLAUDE.md asks for and it was
preserved through a substantial rewrite of the surrounding method. No missing-comment MINORs
to report.

---

## Summary

| # | Severity | Area |
|---|---|---|
| F1 | BLOCKER | `SidekickCpuController` off-screen override — 3 non-ROM conjuncts, situational carve-out |
| F2 | MAJOR | CNZ handoff dispatch count retuned 9→2; ROM value is 0; citation licenses the mechanism, not the number |
| F3 | MAJOR | `sidekickTouchHurtPublishesAnimation` opt-out contradicted by `HurtCharacter`'s unconditional `anim = $1A` |
| F4 | MAJOR | Phase-keyed (24/+6/5/1/0) title-card compensation table, zero ROM citation |
| F5 | MINOR | Per-zone `...Dispatches` knob family uniformly uncited; AIZ2 pair internally inconsistent (comment says 1/0, code says 2/1) |
| F6 | MINOR | S3K `ScreenEvents` camera-copy ordering applied to S1 (no such variable) and S2 (publishes later) |
| F7 | MINOR | Solid-push release comment cites `sub_1E0C2`; the write lives in `loc_1E0A2` |
| F8 | MINOR | Partial CRLF normalisation in two known mixed-ending files |

F1 alone should block. F2–F4 are each a value or predicate that will desync a recording nobody
has made yet, and all three are cheap to re-derive from the routines cited above. F5–F8 are
opportunistic.

The branch's ROM-derived constants that I spot-checked were, without exception, right. The
problem is not carelessness with the disassembly — it is that dispatch *ordering* is currently
being corrected with integers instead of with ordering.

---

# Review — Lane B: hard rule 4 and the comparison/production line

Branch `bugfix/s3k-traces` @ `5b3425dca`, against merge base `0a4642329`.
Read-only worktree: `<local>`. Nothing was modified there.

Method: I read the full contract doc, `TestHardwareTimingAuthorityGuard` including its addition,
and every `src/main` and `src/test` change that touches the timing port, the KosM/direct queues,
readiness, or the load-queue comparator. Claims were checked against `docs/skdisasm/sonic3k.asm`
in that same worktree. I did **not** run the trace suite; the two substantive findings below are
established by call-graph and disassembly evidence, not by a measurement, and both are decidable
statically.

**Headline:** the branch is mechanically disciplined and the authority instinct is real — it
strengthens its own guard rather than loosening it, and the biggest comparison change is on the
correct (normalisation) side of the line. But one production path is now gated on a signal only a
trace replay can produce, which is a rule-4 breach in the "recorded data decides *what* happens"
direction rather than the usual hydration direction.

---

## Findings

### B1 — BLOCKER — a production ROM art submission only happens when a trace says a row was held

**File:** `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java:1582-1614`,
with `src/main/java/com/openggf/LevelFrameStep.java:439-443`.
**Commit:** `2a08c51b6 fix: advance S3K AIZ held admission frontier`.
**Rule:** CLAUDE.md hard rule 4 — the timing exception may only affect *when* real, engine-created
work becomes ready; anything deciding *what* happens is outside it. Also hard rule 3 ("any BK2").

**The chain, all verifiable by grep:**

1. `Sonic3kAIZEvents.java:2796` calls `deferEnemyKosArtAdmissionUntilAfterPreMainLoop()` for the
   AIZ2 background continuation. That sets `enemyKosSubmitAfterPreMainLoop = true`
   (`Sonic3kObjectArtProvider.java:1967`).
2. While that flag is set, the ordinary pump skips the batch entirely:
   `processRuntimeArtQueue()` at line 1544 — `if (!enemyKosSubmitAfterPreMainLoop) processEnemyKosArt();`
3. The only production caller of the deferred path is `LevelFrameStep.java:441`:
   `processRuntimeArtQueueAfterPreMainLoop(frame.defersLoopTailPreparation())`.
4. `processRuntimeArtQueueAfterPreMainLoop(boolean heldLoopTail)` at line 1582 returns without
   submitting whenever `heldLoopTail == false` (lines 1587-1592), and only ever clears
   `enemyKosSubmitAfterPreMainLoop` on the `true` branch (line 1603).
5. `frame.defersLoopTailPreparation()` is `PlcLifecycleFrame.representedIterationDefersLoopTailPreparation`,
   set exclusively by `PlcFrameLifecycleCoordinator.markRepresentedIterationDefersLoopTailPreparation()`
   (line 286), whose **only** caller in `src/main` is
   `TraceReplayBootstrap.markReplayIterationDefersLoopTailPreparation()` (line 605), reached from
   `markIterationHeldIntoNextRowForReplay(current, next)` — a recorded-counter classification.

Therefore, in any run with no trace attached (a live session, or the editor), `heldLoopTail` is
permanently `false`, `enemyKosSubmitAfterPreMainLoop` is never cleared, and the AIZ2
`LoadEnemyArt` KosM batch is **never submitted at all**. Equally, a future BK2 of the same route
whose recorder sample does not land mid-iteration at that point will never admit the batch.
Recorded timing is deciding *whether* engine work is created, not when a prepared job retires.

Supporting evidence that the production path is unexercised: the only tests that reach this method
(`TestS3kKosStructuralSequence.java:440,623`) call the **no-arg** overload, which hardcodes
`true` (line 1578). The `false` branch has no coverage.

The same signal also selects a *shape*, not just a time: `admittedOnHeldTail =
!enemyKosAdmissionSawUnheldTail` (line 1594) chooses whether to call
`moduleQueue.deferChildSubmissionAfterHeldAdmission()`, which changes the publication order of
subsequent parents' first children. That is a second, independent instance of recorded data
deciding *what* the queue does.

**What a correct fix looks like:** the ROM predicate for "this admission happens at the loop tail"
is a position in `LevelLoop`, not a property of the recorder's sampling. The engine already runs
`PRE_MAIN_LOOP` unconditionally at `LevelFrameStep.java:437`; the deferred admission should fire
there on every iteration, and only the *readiness* of the resulting jobs may be gated by the
timing port. If the intent is genuinely "the ROM had not reached the tail on the represented
iteration", that is a property of the replay's frame decomposition and belongs in the replay
closure (`TraceSuppressedRowClosure`), never as a precondition on the production submission itself.

---

### B2 — MAJOR — a fitted 3-frame constant stands in for the ROM's `$30(a0) == 0` predicate, and it gates this same art submission

**File:** `src/main/java/com/openggf/game/sonic3k/titlecard/Sonic3kTitleCardManager.java:64-69,425-426`.
**Commit:** in the `d6416eee0` / `07e966b2a` results-frontier work (introduced with
`FRESH_LEVEL_TRANSITION_*`).
**Rule:** hard rule 3 — a constant not read out of the disassembly is a fitted model.

The comment claims: *"The native owner reaches LoadEnemyArt only after those three post-exit
dispatches in the fresh-level path (sonic3k.asm:62249-62312)"*, and lands
`FRESH_LEVEL_TRANSITION_OWNER_RETIREMENT_FRAMES = 3`.

The cited routine does not contain a three. `Obj_TitleCardWait2` (sonic3k.asm:62249-62260):

```
62249 Obj_TitleCardWait2:
62250        tst.w   $2E(a0)
62251        beq.s   loc_2D862
62252        subq.w  #1,$2E(a0)
62253        rts
62256 loc_2D862:
62257        tst.w   $30(a0)          ; children still on screen?
62258        beq.s   loc_2D86E
62259        addq.w  #1,$32(a0)
62260        rts
```

The owner spins on `$30(a0) != 0` — the count of surviving card children — incrementing the shared
exit clock `$32`. `$30` is built in `Obj_TitleCardCreate` (62191-62205) by an `addq.w #1,$30(a0)` /
`dbne d1` loop whose `d1` is **data-driven by zone**: `moveq #3,d1` → 4 children for
`ObjArray_TtlCard` (62185), `moveq #1,d1` → 2 for `ObjArray_TtlCardBonus` (62179), `moveq #0,d1` →
1 for `ObjArray_TtlCard2` (62189). Children decrement `$30` as they leave
(`Obj_TitleCardRedBanner`, 62311), on frames chosen by each child's own `$28` delay byte compared
against `$32` (62316). The number of post-exit dispatches is therefore a function of child count
and per-child delays, not a constant.

`3` is correct for whatever the AIZ fixture happens to do and will be wrong the first time the
count differs. This lands in Lane B because the retirement counter is what eventually releases the
enemy-art admission chain in B1; it is also squarely a Lane A concern and the two should be fixed
together. The right model is the ROM's: count modelled children and test for zero.

(`FRESH_LEVEL_TRANSITION_HOLD_FRAMES = 22` in the same block is **fine** — `#$16` is written to
`objoff_2E` and `Palette_fade_timer` at sonic3k.asm:7877-7878. Only the neighbouring comment's line
reference "7897-7900" is off by ~20 lines in the current disassembly.)

---

### B3 — MAJOR — the deferred child is published at `Process_Kos_Queue`, but the ROM only ever publishes children from `Process_Kos_Module_Queue`

**File:** `src/main/java/com/openggf/game/sonic3k/resources/S3kKosModuleQueue.java` (the
`deferredChildSubmission*` machinery, `stepDeferredChildAfterDirectTail`), driven from
`S3kRuntimeArtCoordinator.java:133-135` inside `afterTimingService(PRE_MAIN_LOOP)`.
**Commit:** `2a08c51b6`.
**Rule:** hard rule 3 — a boundary chosen for effect rather than read out of the routine.

The in-code citation for the deferral is right in substance: at sonic3k.asm:2783-2787 the queue
shifts and `jmp (Process_Kos_Module_Queue_Init)`, and `Init` (2694-2716) only sets
`Kos_modules_left` / `Kos_module_destination` and `rts` — it never calls `Queue_Kos`. So the next
parent's first child really is published one call later. Good structural reasoning, and the
`sonic3k.asm:2778-2790` reference should be tightened to 2783-2787 + 2694-2716, which is where the
"Init publishes nothing" fact actually lives.

The placement, however, does not follow. That "one call later" is the **next**
`Process_Kos_Module_Queue` — `LevelLoop:7908` of iteration N+1, i.e. the engine's `POST_OBJECTS`
boundary. The branch instead publishes it from `afterTimingService(PRE_MAIN_LOOP)` of iteration N,
which the coordinator's own doc comment (S3kRuntimeArtCoordinator.java:114-121) correctly maps to
`Process_Kos_Queue` at `LevelLoop:7887` — one boundary **earlier** than the ROM, and before the
intervening object scan. `stepHeadArchive` is even made to bail out at `POST_OBJECTS`
(`if (deferredChildSubmissionReady && deferChildSubmissionAfterHeldAdmission) return;`) so the
ROM-correct boundary is deliberately skipped in favour of the direct tail; the method comment on
`stepHeadModuleAfterDirectTail` concedes it is placed "without inventing another hardware timing
boundary". A publication one service earlier than the ROM's is the classic
"close but not equal, absorbing an error elsewhere" shape, and it is plausibly compensating for B1.

I have not proven a divergence from this, so it is MAJOR rather than BLOCKER — but the boundary
should be justified from the routine or moved to `POST_OBJECTS`.

---

### B4 — MINOR — the direct-queue comparison exclusion is wider than the sampling blindness it models

**File:** `src/main/java/com/openggf/trace/LoadQueueComparisonProjection.java:33-56` (new).
**Commit:** `8fa54b578`.

The projection is on the **right** side of the comparison/production line: it changes only what
the comparator looks at, it is keyed on recorded counters plus a strict production-identity match
(`kind`, `ordinal`, `submissionFingerprint`, `romSourceAddress`, `destinationAddress`, exactly one
candidate), and it does not touch engine state. That is the normalisation the project keeps
concluding is correct for sub-frame V-blank landings, and it is well argued in
`TraceData.loadQueueStatesForComparisonFrame`'s javadoc.

The exclusion, though, drops the whole `s3k_kos_direct` row from *both* sides rather than the
fields the recorder could not see. Everything else that row carries — `queuedFingerprints`,
`totalWork`/`remainingWork` for unrelated pending work, `serviceObservations` — goes uncompared on
that frame, so a genuine direct-queue defect coinciding with an unobserved child would be silent.
Prefer normalising `busy`/`prepared`/`activeSource`/`activeDestination` for the matched job and
leaving the rest of the row compared.

### B5 — MINOR — game-name literal in the comparison normaliser

**File:** `src/main/java/com/openggf/trace/TraceData.java:274` — `if (!"s3k".equals(metadata.game()) ...)`.
**Commit:** `8fa54b578`.

Not a hard-rule-2 breach (this is comparison-only code, outside `level/`, `sprites/`,
`game/rules/`, and there is precedent in `TraceReplayBootstrap`), but the gate is redundant: the
normaliser already selects on the `s3k_kos_module` / `s3k_kos_direct` wire names, which no other
game emits. Dropping the literal removes a carve-out shape from a file reviewers will keep
re-reading.

### B6 — MINOR — the terminal-pending-work allowlist is fixture-measured

**File:** `src/test/java/com/openggf/tests/trace/s3k/TestS3kAizTraceReplay.java:80-107`, with the
mechanism at `AbstractTraceReplayTest.java:203-256,564-572`.
**Commit:** `786e0c993`.

`expectedPendingHardwareTimingAtTraceEnd` replaces `closeHardwareTimingReplayRun()` (which runs
`port.verifyRunComplete()`) with `verifyHardwareTimingSegmentEdges()` + an exact pending list +
`abortHardwareTimingReplayRun()`. The reasoning — the recorded prefix ends before the destination
title owner claims its last two parents — is sound, and only the "no work left pending" leg of the
closure is waived; every recorded edge must still be consumed, and the expectation is an exact
`assertEquals`, so it pins rather than suppresses. Credit for that shape.

Still, ordinals `38..41` and four `sha256:` fingerprints are values read off this fixture. Anything
that legitimately changes the submission ordinal upstream forces a hand-edit here with no way to
tell a real regression from re-numbering. Deriving the expectation from the trace's own
`hardware_timing` tail (the last recorded submissions with no matching completion) would make it
self-maintaining and, unlike the current form, correct for the next recording.

---

## Things I checked that are clean, and things worth praising

- **NOTE (praise) — the guard addition is sound.** `recordedEdgesCannotSelectProductionServiceBoundaries`
  (`TestHardwareTimingAuthorityGuard.java:136-155`) walks all of `src/main` and asserts an empty
  violation list for `hasPendingCompletionAtCurrentRawFrame(`. It has no allowlist, no root
  exemption, and mirrors the existing `SUPPRESSED_OBSERVER_APPLY` idiom. It genuinely closes the
  "recorded lookahead picks the boundary" hole. The diff to that file is **additive only** — no
  existing pattern, assertion or root was touched.
- **NOTE — every other named guard is byte-identical.** `git diff 0a4642329..HEAD` is empty for
  `TestTraceFixtureCompressionGuard`, `TestTraceFixtureMovieAlignmentGuard`,
  `TestTraceFixtureLagPolledInputGuard`, `TestExecLoopSlotLifecycleParityGuard`,
  `TestRewindCoverageGuard`, `TestStaticStateRewindCoverageGuard`, `TestBuildToolingGuard`.
  Nothing weakened, narrowed or allowlisted.
- **NOTE — the rewind baseline removal is a fix, not a suppression, but its cause is undocumented.**
  `static-state-coverage-baseline.txt` loses only
  `com.openggf.game.sonic3k.titlecard.Sonic3kTitleCardManager#missingRewindAdapter`, and its
  stale explanatory comment (lines 41-43) is left behind above the now-removed entry. The class
  already implemented `RewindSnapshottable` at `0a4642329`, so the entry was closed by the branch
  registering/expanding that adapter's coverage rather than by an exemption — the guard is
  strictly stricter afterwards. Two clean-ups worth asking for: delete the orphaned comment, and
  name the closing change in the commit message.
- **NOTE — no test-side hydration.** I read the diffs to `AbstractTraceReplayTest`,
  `TestTraceReplayStartPositionPolicy`, `TestS3kAizTraceReplay`, `TestS3kCnzTraceReplay`,
  `TestS3kHczCompleteRunTraceReplay`, `TestLoadQueueTraceComparison`,
  `TestTraceHardwareTimingScheduleCompiler` and `TestRecordingFrameDriverHardwareTiming`. No new
  path writes a recorded value into a sprite, manager, controller or queue; the new test surface is
  assertion-only. `LoadQueueComparisonProjection` is read-only on both sides.
- **NOTE (praise) — the new `Pos_table_index` witness is structural, not measured.** The `+4`
  in `TestTraceReplayStartPositionPolicy.java:181-183` and the `PLAYABLE_ANIMATION_ONLY`
  promotion in `TraceReplayBootstrap` come from `addq.b #4,(Pos_table_index+1).w`
  (sonic3k.asm:22129/22148) — one `Sonic_RecordPos` entry. That is exactly the "rule, not a
  number" form the skill asks for, and it replaces a hook that the native recorder does not emit.
  Worth noting for the author: Tails uses `Pos_table_index_P2` (22153-22158); confirm the
  recorder's `"tails"` `cpu_state.posTableIndex` is the P2 index and not P1, or the witness is
  reading Sonic's clock.
- **NOTE — `TraceSuppressedRowClosure`'s new boundary work is on the right side of the line.**
  Running `POST_OBJECTS` then `PRE_MAIN_LOOP` on a held row
  (`TraceSuppressedRowClosure.java:118-125`) models `LevelLoop` reaching 7908 and then 7887 on an
  iteration the recorder sampled mid-flight. It affects *when* engine-created work retires, creates
  nothing, and carries no gameplay value. Fine.
- **NOTE — `HardwareTimingService.isExportableAcrossSegment` is a read of the production
  submission contract**, not of recorded data. Fine.
- **NOTE — `RuntimeArtCoordinator.ownsHeldLevelCounterHardwareTail()` is a constant `true` for
  S3K and `false` by default.** Placement is correct (a provider capability, not a name string),
  but there is no citation for why S1's PLC tail and S2's DPLC tail do *not* own the same held row
  — both run their queue service in the same loop-tail position. Worth one sentence of ROM
  justification in the interface doc, or the default will quietly become a per-game carve-out by
  omission.
- **NOTE (style, out of lane) — `LevelFrameStep.java:368-383` mixes tab and space indentation**
  in the newly added `camera.captureRenderCopy()` block, which survived the
  `chore: normalize touched source formatting` tip commit.

---

# Review — Lane C: consistency, test quality, and whether the claims are true

**Branch:** `bugfix/s3k-traces` @ `5b3425dca` — 144 commits, 202 files, +14404/−1703 vs merge base `0a4642329` on `develop`.
**Reviewed in:** `<local>` (read-only; nothing committed, rebased or modified there).
**Control:** detached worktree at `0a4642329`, identical command, identical ROMs, `-Dsurefire.runOrder=alphabetical`.

---

## 0. Verdict up front

**The headline claim is true and I reproduced it.** Fifteen of the sixteen S3K trace-replay classes that are red on `develop` are green on this branch, including every zone the branch claims: AIZ, CNZ, HCZ, ICZ, LBZ, MGZ, both standalone and complete-run, plus gumball/pachinko/slots/special-stage and the hardware-timing replay. **No previously-green class anywhere in the suite went red.** Numbers and per-class attribution in §1.

**The work is also mechanically disciplined**, and that deserves saying plainly rather than being buried. Beyond the orchestrator's clean greps, the branch *removes* fitted models rather than adding them, *strengthens* a guard while touching the guarded subsystem, and lands the one new `GameRules` flag in exactly the shape hard rule 2 asks for — which I verified line-by-line against all three disassemblies. Details in §2.

The findings in §3 are, with one exception, refinements on a fundamentally sound piece of work. The exception is a small set of sibling test classes that were left behind by a fixture-contract change and now die in setup.

---

## 1. Verification of the headline claim

### 1a. Full `-Ptrace-replay` profile

```
mvn -Ptrace-replay -Dmse=off -Dsurefire.forkCount=1 -Dsurefire.runOrder=alphabetical \
  -Dsonic1.rom.path=<root>/s1.gen -Dsonic2.rom.path=<root>/s2.gen -Ds3k.rom.path=<root>/s3k.gen test
```

| | classes | tests | failures | errors | skipped |
|---|---|---|---|---|---|
| control `0a4642329` | 156 | **769** | **8** | **64** | 4 |
| branch `5b3425dca` | 136 | 679 | 4 | 26 | 4 |

The control reproduces the orchestrator's baseline exactly (769 / 8 / 64), so the harness is sound.

**The branch row is not a like-for-like total**, and I want to be scrupulous about why: my branch run died with `Java heap space` on entering `TestS3kAizCompleteRunTraceReplay`, dropping the last 20 classes. I had a control run executing concurrently on the same machine at the time, so this is almost certainly my contention and **not** attributable to the branch. I re-ran the dropped classes separately (§1b) and they pass comfortably. I am flagging it only so nobody reads "136 classes" as a branch defect. (Note the branch's own frontier-log commands all carry `-Dsurefire.argLine=-Xmx3g`; the profile default heap is tight for the S3K complete runs on either tree.)

Per-class diff over the 136 classes both trees ran:

- **Green on control → red on branch: none.** Zero regressions.
- **Red on control → green on branch:** `TestS3kReplayReferenceClosureIntegration`.
- **Red on both, improved:** `TestTraceReplayStartPositionPolicy` 2F/22E → 1F/22E.
- **Red on both, unchanged:** `TestAbstractTraceReplayDynamicArtTerminal` (2E — missing uncompressed `s2/ehz1_fullrun/physics.csv`, environmental), `TestS1GhzMazeRoundTripChain` (1F), `TestS2EhzHalfpipeRoundTripChain` (1F).
- **Red on both, shape changed:** `TestS3kMegaRunChain` 1E → 1F (`source comparator cannot exhaust after boundary for aiz: … left LEVEL at tail step 22, comparator cursor 4544 of 4654`). Still red either way; worth a glance from whoever owns the run-chain comparator, but not a regression.
- `TestS3kAizPrefixClosureContract` — 2E on both, but the *reason* changed for the worse. See finding **C-1**.

### 1b. The S3K replay fleet (the actual claim)

Sixteen classes, run alone with `-Dsurefire.argLine=-Xmx3g`, same flags. Branch result: **62 tests, 0 failures, 2 errors.**

| class | control `0a4642329` | branch `5b3425dca` |
|---|---|---|
| `TestS3kAizTraceReplay` | 16 T, **4F 1E** | 16 T, **green** |
| `TestS3kAizCompleteRunTraceReplay` | **1E** (`unsupported-row-POST` @ raw 6351) | **green** |
| `TestS3kCnzTraceReplay` | 27 T, **21E** | 27 T, **green** |
| `TestS3kCnzCompleteRunTraceReplay` | **1E** (@ raw 39940) | **green** |
| `TestS3kHczCompleteRunTraceReplay` | **2E** (@ raw 31361) | **green** |
| `TestS3kIczCompleteRunTraceReplay` | **1E** (@ raw 25280) | **green** |
| `TestS3kLbzCompleteRunTraceReplay` | **1E** (@ raw 46114) | **green** |
| `TestS3kMgzTraceReplay` | **1E** (`KOS_DECOMPRESSION_QUEUE#24`) | **green** |
| `TestS3kMgzCompleteRunTraceReplay` | **1E** (@ raw 39274) | **green** |
| `TestS3kHardwareTimingReplay` | 4 T, **1E** | 4 T, **green** |
| `TestS3kGumballBonusTraceReplay` | **1E** | **green** |
| `TestS3kPachinkoBonusTraceReplay` | **1E** | **green** |
| `TestS3kSlotsBonusTraceReplay` | **1E** | **green** |
| `TestS3kSpecialStageTraceReplay` | green | green |
| `TestS3kMhzCompleteRunTraceReplay` | 1E (`unsupported-row-POST` @ raw 28017) | 1E (`expected completion KOS_DECOMPRESSION_QUEUE#335 …; engine pending: <none>`) |
| `TestS3kMgzF498AirRollPhysics` | 1E | 1E (setup — see **C-1**) |

**Every claimed closure reproduces.** MHZ is not claimed (it sits after LBZ, outside the branch's stated AIZ→LBZ scope) and its frontier visibly *advanced* — from a structural row-phase abort at raw 28017 to a real hardware-completion frontier at `#335`. That is progress, not a regression.

**MINOR (C-11) — the frontier log slightly overstates the end state.** The final entry (`2026-08-10 — S1/S2 and S3K-through-LBZ replay fleet completion audit`, `docs/status/trace-frontier-log.md`) says *"The full replay fleet has no remaining in-scope failing frontier to select."* That audit ran a **13-class allowlist**, not the profile. Under the profile there remain: `TestS3kMhzCompleteRunTraceReplay`, `TestS3kMgzF498AirRollPhysics`, `TestS3kAizPrefixClosureContract`, `TestS3kMegaRunChain`, `TestS1GhzMazeRoundTripChain`, `TestS2EhzHalfpipeRoundTripChain`, `TestTraceReplayStartPositionPolicy`, `TestAbstractTraceReplayDynamicArtTerminal`. Most are pre-existing or environmental and none contradict the closure claims — but "no remaining in-scope failing frontier" reads as a stronger statement than the evidence supports, and the next reader of that log will be misled about what was covered. Please scope the sentence to the allowlist that was actually run.

---

## 2. What is right, verified rather than assumed

These are not padding; each was a place a violation could have hidden and did not.

**The branch deletes fitted constants instead of adding them.**
- `Aiz2BossEndSequenceController.java:46-47` — `POST_RESULTS_CONTROL_RESTORE_DELAY = 4` / `RIDING_SIDEKICK_CONTROL_RESTORE_DELAY = 6` become `RELEASE_OWNER_BEFORE/AFTER_CONTROLLER_DELAY = 1/2`, selected by an SST-ordering predicate (`Aiz2BossEndSequenceState.isButtonBeforeBridgeDispatch()`) with the citation at the use site (`Aiz2BossEndSequenceController.java:243-251`, `sonic3k.asm:62709-62720,138313-138331,181978-181990`). A delay derived from *which object allocated first* is a model; `4` and `6` were not.
- `Sonic3kLevelEventManager.cnzPendingPostTransitionReleaseFrames` (an `int` countdown) collapses to a `boolean`.
- `S3kSignpostInstance.java:108-115` — `RESULTS_POST_OBJECT_RETIRE_DISPATCHES = 0`, with the synthetic parent pass deleted outright.
- `TestS3kIczMinibossObject.java:492` — `resultsWaitDurationAdjustment` assertion corrected from `1` to `0`. A test that pinned a fitted value was **lowered**, which is the opposite of the failure mode this project has been bitten by.

**`TestHardwareTimingAuthorityGuard` was strengthened while the guarded subsystem was being changed** (`recordedEdgesCannotSelectProductionServiceBoundaries`, forbidding `hasPendingCompletionAtCurrentRawFrame` anywhere under `src/main`). Adding a guard against the mistake you are most at risk of making is exactly right.

**No test was weakened in the usual ways.** Across the entire `src/test` diff: **zero** additions or removals of `@Disabled`, `Assumptions.assume*`, `@Tag`, or tolerance widening; 316 added assertions against 54 removed. I read all 54 removals. Every one is either an assertion on code the branch deleted (`TestObjectPhysicsStandardizationGuard.java:286-291` drops two entries for `S3kSeamlessMutationExecutor.processInitialAizTransitionFloorContact` / `playerQueryFromGameServices` — I confirmed both are gone from `src/main`), or a corrected fitted expectation as above.

**`RingRules.lostRingBoundaryChecksOnlyOnProbeCadence` is ROM-correct — verified against all three disassemblies, not taken on trust.**
- S1 `RLoss_Bounce` reaches `.chkdel` (lifetime + bottom-boundary) via **both** `bmi.s .chkdel` (still rising) and `bne.s .chkdel` (off-cadence) — `docs/s1disasm/_incObj/25, 37 Rings.asm:314-352`. Value `false`. Correct.
- S3K's equivalent branches to `loc_1A83C`, which is the `Add_SpriteToCollisionResponseList` / `Draw_Sprite` tail and **skips** `loc_1A828`'s `Ring_spill_anim_counter` and `Camera_max_Y_pos + $E0` checks — `docs/skdisasm/sonic3k.asm:35679-35703`. Value `true`. Correct.
- This is precisely the shape hard rule 2 wants: a typed, game-wide semantic predicate on `GameRules`, not a game-name branch, with the ROM asymmetry named. Good work.

**Artifact placement is clean.** The only added docs are `docs/architecture/audits/2026-08-06-llm-review-remediation.md` (correct subdirectory for a point-in-time assessment), `CHANGELOG.md`, `README.md`, and `docs/status/trace-frontier-log.md`. No loose Markdown in `docs/`, no `docs/plans`, no `archive`/`misc`/`notes`. The frontier log is genuinely append-only (the diff is pure `+` after the protected prefix), the 25 new entries all carry command, commit/worktree context, pass/fail, error counts and first-error frame/field, and I grepped every added line for `/home/`, `/tmp/`, `C:\`, `.cache/` and `/mnt/` — **zero machine-local paths**; commands use repo-relative `./*.gen`.

**A "two paths" trap was correctly avoided.** `LoadQueueComparisonProjection` is applied in **both** comparison paths — `AbstractTraceReplayTest.compareLoadQueuesIfAdvertised` and `TraceStructuralRowComparator.compare` — and `markIterationHeldIntoNextRowForReplay` landed in **both** `AbstractTraceReplayTest` and `LiveTraceComparator` (the visual-launcher path). Given this codebase's history with standalone-vs-chain comparator divergence, that is the right reflex.

**`slotFor` → `slotForSpawn` is an improvement, not a retune.** In `TestS3kCnzTraceReplay` the CNZBalloon matcher changes from live position `0x06F8` to placement-data `0x0700`. The new helper (`TestS3kCnzTraceReplay.java:1012-1027`) matches on `aoi.getSpawn()`, which is strictly more stable across engine changes than matching a live coordinate. Correct direction.

---

## 3. Findings

### C-1 — MAJOR — three sibling test classes were left behind by the recorded-admission fixture contract, and one now dies in setup

**Files:** `src/test/java/com/openggf/tests/trace/TestS3kAizPrefixClosureContract.java:45,136`; `src/test/java/com/openggf/tests/trace/s3k/TestS3kMgzF498AirRollPhysics.java`
**Introduced by:** the commits that migrated fixture construction to recorded admission — `d6416eee0` *fix: advance S3K AIZ results frontier*, `578a008a7` *fix(trace): restore post-merge replay boundaries*, `786e0c993` *test(trace): align AIZ focused replay lifecycle*
**Principle:** "two paths that should agree, but don't" — the seventh instance of the class that has repeatedly cost this project rounds.

The branch introduces a required fixture-construction step for anything driving recorded hardware timing: either `HeadlessTestFixture.builder().withHardwareReadinessAdmissionPolicy(HardwareReadinessAdmissionPolicy.RECORDED)` (`TestS3kAizTraceReplay.java:815-822`, `TestS3kHczCompleteRunTraceReplay.java:58-60`) or `fixture.gameplayMode().activateRecordedHardwareAdmission()` (`TestS3kCnzTraceReplay.java:1259,1367`). Classes that miss it now throw `IllegalStateException: gameplay context was not constructed for recorded hardware admission` **before the test body runs**.

Reproduction (branch, `-Xmx3g`, S3K allowlist):

```
TestS3kAizPrefixClosureContract.standaloneAizPrefixClosesWithoutDispatchingLoadedLevelEarly
  → IllegalState: gameplay context was not constructed for recorded hardware admission
TestS3kAizPrefixClosureContract.currentSchemaMgzUsesTheSameClosureDriverWithoutPrefixObjectLeakage
  → same
TestS3kMgzF498AirRollPhysics.airborneRollingFrame498UsesRomAirVelocityOrdering
  → same
```

Control at `0a4642329`, same command:

```
TestS3kAizPrefixClosureContract.standaloneAizPrefixClosesWithoutDispatchingLoadedLevelEarly
  → unsupported-row-POST: raw_frame=20795 has no scheduled object/POST phase; phase=VBLANK_ONLY; kind=KOS_MODULE_QUEUE
TestS3kAizPrefixClosureContract.currentSchemaMgzUsesTheSameClosureDriverWithoutPrefixObjectLeakage
  → gameplay context was not constructed for recorded hardware admission
TestS3kMgzF498AirRollPhysics.airborneRollingFrame498UsesRomAirVelocityOrdering
  → gameplay context was not constructed for recorded hardware admission
```

Why this matters rather than being cosmetic: `standaloneAizPrefixClosesWithoutDispatchingLoadedLevelEarly` used to *execute the replay* and fail at a real frontier (raw 20795). On this branch it never gets that far. The AIZ prefix-closure contract is precisely the invariant this branch spends the most code on — `TraceSuppressedRowClosure`, the held-row POST/PRE tails, `expectedPendingHardwareTimingAtTraceEnd` — and its dedicated contract test is now inert. The error count is identical, so a green-count comparison hides it completely; only reading the messages surfaces it.

**Suggested fix:** add the two-line fixture-builder change to these three tests, then confirm `standaloneAizPrefixClosesWithoutDispatchingLoadedLevelEarly` either passes or fails at a real frontier. If it should be structurally impossible to build a replay fixture without recorded admission, that belongs in the builder as a required parameter rather than a runtime `IllegalStateException` discovered per test class.

---

### C-2 — MAJOR — `TraceData` gates the new queue normalization on a game-name string literal, and the gate is redundant

**File:** `src/main/java/com/openggf/trace/TraceData.java:274-276`
**Introduced by:** `9e9a1613b` *fix(trace): compare S3K queues at atomic boundary*
**Rule:** hard rule 2 ("no game-name carve-outs — use the smallest accurate owner") and hard rule 3's "do not branch on … game name". The orchestrator's grep only covered `level/`, `sprites/` and `game/rules/`, so this slipped through.

```java
private LoadQueueComparisonNormalization buildComparisonLoadQueueStates() {
    if (!"s3k".equals(metadata.game()) || frames.size() < 2
            || hardwareTimingSchedule == null) {
        return LoadQueueComparisonNormalization.empty();
    }
```

The gate is also **unnecessary**. Every subsequent step of the method already selects on the queue-kind wire names `"s3k_kos_module"` and `"s3k_kos_direct"` (`TraceData.java:299-307, 335-345, 396-401`), and `directCompletionEdges` filters on `HardwareWorkKind.KOS_DECOMPRESSION_QUEUE`. A trace with no S3K module/direct queue rows produces an empty normalization map with or without the string check. Deleting the `"s3k".equals(...)` clause is behaviour-preserving today and removes the forbidden shape.

I am rating this MAJOR rather than BLOCKER because `TraceData` is comparison harness rather than gameplay runtime, so whether hard rule 2's "shared runtime code" reaches it is a judgement call — a reviewer who reads it as runtime should treat it as a BLOCKER. Either way the fix is a one-line deletion, so it is not worth arguing about.

---

### C-3 — MAJOR — the load-queue comparison now shifts and drops expected values rather than fixing the sampling boundary

**Files:** `src/main/java/com/openggf/trace/TraceData.java:251-345` (`loadQueueStatesForComparisonFrame`, `buildComparisonLoadQueueStates`); `src/main/java/com/openggf/trace/LoadQueueComparisonProjection.java:29-60`
**Introduced by:** `9e9a1613b`, `c1cec7b77` *fix(trace): normalize unobserved S3K direct children*
**Principle:** hard rule 3's spirit — comparison-only code is exempt from rule 4, but a comparator that rewrites ground truth to match the engine is doing the same job a fitted constant does, one layer up.

Two mechanisms:

1. **Expectation shifting.** On a held tail, `buildComparisonLoadQueueStates` replaces the recorded `s3k_kos_direct` row for frame *N* with the recorded row from frame *N+1* (`withFrame(nextDirect, currentFrame.frame())`, `TraceData.java:336-345`). The comparator is then handed a value the recorder never observed at that frame.
2. **Field dropping.** `LoadQueueComparisonProjection.project` removes the `s3k_kos_direct` entry from **both** expected and actual whenever `unobservedDirectChildForComparisonFrame` fires (`LoadQueueComparisonProjection.java:50-59`). That is a compared field silently disappearing on a trace-derived condition.

The stated justification is sound as far as it goes — the recorder can sample between `Process_Kos_Module_Queue` and the direct FIFO service, while replay publishes queue diagnostics atomically after both. But **this branch already built the mechanism that makes the honest fix available**: `LevelFrameStep.serviceHardwarePostObjectsOnly` and `serviceHardwarePreMainLoopOnly` (`LevelFrameStep.java:141-151`) are exactly the two boundaries in question. Publishing a queue-diagnostic heartbeat at each of them, and comparing the recorded row against whichever boundary the row's own phase says it sampled, would make the normalization unnecessary and would generalise to a recording nobody has made yet. The current guard conditions (`isHeldTail` + five `sameQueueStateIgnoringFrame` equalities + `nextDirectCompletions.size() == 1`) are a heuristic tuned to the shapes present in today's fixtures; the first recording that sits half-in that shape will get a silently unchecked queue row.

Credit where due: the three unit tests added for this (`TestLoadQueueTraceComparison.normalizesPreExistingModuleBatchAtAtomicHeldRowBoundary`, `retainsBatchFirstAdmittedOnHeldTail`, `identifiesDirectChildMissedBetweenIdleFrameEndHeartbeats`) are built from **synthetic** frames and hand-constructed queue states, not from a fixture. That is the right way to test this and it is why I can read the intended semantics at all.

**Suggested fix:** treat the normalization as a documented interim, record it under `docs/status/known-discrepancies.md` as a comparison-side divergence with an owner, and open the two-boundary heartbeat as the real fix. If it stays, at minimum make the dropped-field case *loud* (a warning-severity comparison entry rather than silent removal) so a future divergence there is visible.

---

### C-4 — MAJOR — the AIZ full-run's terminal hardware check is replaced by four fixture-measured ordinals and four opaque digests

**File:** `src/test/java/com/openggf/tests/trace/s3k/TestS3kAizTraceReplay.java:80-110`; mechanism in `src/test/java/com/openggf/tests/trace/AbstractTraceReplayTest.java:203-256, 564-572`
**Introduced by:** `07e966b2a` *fix: remediate S3K trace branch review findings*
**Principle:** "a constant derived by measuring a fixture's own rows … is a fitted model even when every test passes." The values here are test-side rather than production-side, but the staleness failure mode is the same one this project has already had to clean up.

```java
return List.of(
    expectedPendingHardwareWork(HardwareWorkKind.KOS_MODULE_QUEUE, 38, 0x0D6F28,
        "sha256:10eb568a70724c579f022914f56227c2c7fa421aafa8578aebaa874f0cffb0ca"),
    ... 39, 40, 41 ...);
```

Of the four fields per entry, only `romSourceAddress` is ROM-derived. The **ordinals `38`–`41`** are a count of how many hardware jobs this particular recording happened to submit before its last row, and the **submission fingerprints** are opaque digests read off a run. Re-record `aiz1_to_hcz_fullrun` one frame longer, or change what the engine submits anywhere earlier in AIZ, and all eight of those values change — the test then fails for a reason unrelated to the behaviour it is guarding.

The bigger concern is what it replaces. `AbstractTraceReplayTest` now calls `fixture.abortHardwareTimingReplayRun()` on this path instead of `closeHardwareTimingReplayRun()`, and **`abort` deliberately skips `verifyRunComplete()`** (`HeadlessTestFixture.java:172-188` vs `219-231`). So the AIZ full run's end-of-run "every recorded edge was consumed" check is gone, and what stands in its place is an allowlist measured from the fixture.

`HeadlessTestFixture` already has the principled primitive for exactly this situation and it is **pre-existing, not something you would have to build**: `closeHardwareTimingReplayPrefix(int inclusiveRawFrame)` (`HeadlessTestFixture.java:190-197`) — *"Closes recorded timing at a verified semantic trace prefix while leaving later, unrepresented schedule edges untouched."* That expresses "this fixture is a prefix" with **one** boundary value read from the trace, instead of eight values measured from it. If the prefix boundary can be derived from the trace (last represented raw frame), that is the version that survives a re-recording.

At minimum, the assertion should compare against values read out of the `TraceData`/schedule object rather than string literals, so a regenerated fixture updates them automatically.

---

### C-5 — MAJOR — `CNZ_POST_TITLE_CARD_CONTROL_HANDOFF_DISPATCHES` was re-fitted 9 → 2; the ROM has no such delay

**File:** `src/main/java/com/openggf/game/sonic3k/Sonic3kLevelEventManager.java:100` (use site `:1392-1399`)
**Introduced by:** this branch (value change; the knob itself predates it)
**Rule:** hard rule 3 — "a value that is close to the ROM's but not equal is usually absorbing an error elsewhere."

```java
// Dynamic_object_RAM+object_size (sonic3k.asm:7793) == dynamic slot 1, which
// is absolute SST slot 4 (Player_1, Player_2, Reserved_object_3 precede the
// dynamic range -- sonic3k.constants.asm:303-307).
private static final int CNZ_POST_TITLE_CARD_CONTROL_HANDOFF_DISPATCHES = 2;   // was 9
```

I read the cited routine. `docs/skdisasm/sonic3k.asm:180420-180425`:

```
Obj_EndSignControlDoStart:
        tst.b   (End_of_level_flag).w       ; Wait for title card to finish
        beq.w   locret_8405E
        jsr     Change_Act2Sizes(pc)
        jmp     (Delete_Current_Sprite).l
```

`Change_Act2Sizes` runs on **the same dispatch** that observes the flag. There is no `2`-dispatch delay and there was never a `9`-dispatch delay. The comment at the use site (`:1395-1398`) cites `sonic3k.asm:180407-180419` for "the later `Obj_EndSignControl` owner observes the flag after its retained slot chain advances" — true as far as it goes, but that citation establishes *that* there is a slot-ordering delay, not that its magnitude is 2. And the block comment sitting above the constant still describes slot arithmetic that yields neither 9 nor 2; it is now stale decoration on a different number.

I want to be fair about this one: the knob is **not new**, the divergence it compensates for is genuine and documented in the code ("the engine reload rebuild removes that object chain"), and moving 9 → 2 is moving *toward* the ROM. But a value that changes because a trace went green, with a comment that does not derive it, is a fitted model by this project's own definition, and it will desync a CNZ recording with a different object population. The right shape here is the same one the branch used successfully elsewhere: derive the delay from the SST ordering of the surviving owner (as `Aiz2BossEndSequenceState.isButtonBeforeBridgeDispatch()` does), or register the compensation in `docs/status/known-discrepancies.md` with the reason the engine cannot represent the native chain. Please also refresh the stale block comment either way.

---

### C-6 — MINOR — the new post-object zone-feature dispatch is wired into only one of the two object-execution paths

**File:** `src/main/java/com/openggf/LevelFrameStep.java:278-296`
**Introduced by:** this branch (adds `LevelManager.updateZoneFeaturesAfterObjectExecution`, `LevelManager.java:1192-1213`)

The new hook is invoked only inside the `if (inlineSolidResolution)` arm:

```java
Runnable afterExecBeforePlacement = spriteManager != null
        ? () -> { spriteManager.advancePlayableFixedSlotsAfterObjectExecution();
                  levelManager.updateZoneFeaturesAfterObjectExecution(); }
        : levelManager::updateZoneFeaturesAfterObjectExecution;
```

The `else` arm — the legacy objects-before-physics path selected when `GameRules.objectInteraction().objectsExecuteAfterPlayerPhysics()` is false — never calls it. `grep` confirms `updateZoneFeaturesAfterObjectExecution` has exactly two call sites, both in that one arm.

This is **latent, not live**: today only `Sonic3kZoneFeatureProvider` overrides `updateAfterObjectExecution` (the `ZoneFeatureProvider` default is a no-op), and S3K takes the inline path. But the next provider that implements the hook on a game using the legacy path will get silence, and silence is the hardest failure mode to diagnose. One line in the `else` arm, or hoisting the call to a point both arms reach, closes it.

---

### C-7 — MINOR — `markIterationHeldIntoNextRowForReplay` is missing from the CNZ and HCZ scenario drivers

**Files:** `src/test/java/com/openggf/tests/trace/s3k/TestS3kCnzTraceReplay.java:1421-1434` (`driveReplayFrame`); `src/test/java/com/openggf/tests/trace/s3k/TestS3kHczCompleteRunTraceReplay.java:76-85`

The branch's new held-row marker is called in four places: `TraceReplayBootstrap` (definition), `LiveTraceComparator` (visual launcher), `AbstractTraceReplayTest` (production replay loop), and `TestS3kAizTraceReplay.stepReplayFrame`. The CNZ and HCZ scenario drivers call `markVblankStarvedIterationForReplay` immediately alongside — but not `markIterationHeldIntoNextRowForReplay`.

Those two drivers therefore step held rows differently from the replay path they are meant to be a focused view of. Since `TraceSuppressedRowClosure` now branches on `ownsHeldLevelCounterHardwareTail()` for exactly these rows, a scenario test that walks 20,000 frames through the CNZ driver is not walking the same engine states as `replayMatchesTrace`. Both classes are green today, so this is a correctness-of-the-harness point rather than a live bug — but a focused test that silently diverges from the thing it focuses on is worth less than it appears to be. The three-line addition matches what AIZ already does.

---

### C-8 — MINOR — the branch turns a pure-LF file into a mixed-ending one, and partially normalises two others

**Files:** `src/main/java/com/openggf/sprites/managers/PlayableSpriteMovement.java`; `src/main/java/com/openggf/level/rings/RingManager.java`; `src/main/java/com/openggf/level/objects/ObjectPlacementController.java`
**Introduced by:** the CRLF injection accumulates across `12cfc6768`, `7a1b1bc3a`, `ac95f5d68` and later; the partial normalization is `5b3425dca` *chore: normalize touched source formatting*

Measured byte-wise (`data.count(b"\r\n")`):

| file | at `0a4642329` | at `5b3425dca` |
|---|---|---|
| `PlayableSpriteMovement.java` | **0** CRLF / 5201 lines | **254** CRLF / 5234 lines |
| `RingManager.java` | 1367 CRLF / 2030 lines | 1359 CRLF / 2030 lines |
| `ObjectPlacementController.java` | 1141 CRLF / 1581 lines | 1084 CRLF / 1581 lines |

`PlayableSpriteMovement.java` was **clean LF on `develop`** and this branch makes it mixed — this is the origin of the hazard the team has been working around, not a pre-existing condition. Conversely the final formatting commit converts 8 CRLF lines in `RingManager.java` and 57 in `ObjectPlacementController.java` to LF, leaving both *more* mixed than before. Every future diff, merge and `git blame` on these three files pays for it, and partial normalization is worse than either extreme.

Pick a direction per file and land it as a standalone whitespace-only commit that reviewers can skip.

---

### C-9 — MINOR — tab indentation inserted into a space-indented file

**File:** `src/main/java/com/openggf/LevelFrameStep.java:374-382`
**Introduced by:** this branch; survived `5b3425dca` *chore: normalize touched source formatting*

Nine lines (the `camera.captureRenderCopy()` block and its comment) are tab-indented in a file that is otherwise entirely 4-space. `LevelFrameStep.java` has exactly 9 tab-led lines and they are all from this change. CLAUDE.md's "write code that reads like the code around it" — and the commit that claims to normalize touched formatting missed it.

---

### C-10 — MINOR — the new authority guard is name-based and the same shape now exists under a different name

**Files:** `src/test/java/com/openggf/trace/timing/TestHardwareTimingAuthorityGuard.java:50-51,136-155`; `src/main/java/com/openggf/trace/TraceData.java:270-272, 351-361`

`recordedEdgesCannotSelectProductionServiceBoundaries` forbids the literal token `hasPendingCompletionAtCurrentRawFrame` anywhere under `src/main`. Meanwhile the branch adds `TraceData.unobservedDirectChildForComparisonFrame(frame)`, which does recorded-edge lookahead into the *next* raw frame (`directCompletionEdges(nextFrame.frame())`) and uses the result to change what gets compared.

I do **not** think this is a violation — the guard's stated concern is recorded timing selecting which *production boundary runs*, and `TraceData` selects nothing in production. But a guard keyed on one method name is one rename away from vacuous, and this branch has just demonstrated that the shape can be re-expressed under a different name in the same subsystem. If the invariant is "recorded edges must not look ahead of the current row to influence production", it is worth expressing structurally — e.g. by package/type reachability from the timing authority — rather than by string.

---

### C-11 — MINOR — frontier-log closure statement is broader than the run behind it

Detailed at the end of §1b.

---

### C-12 — NOTE — one redundant opaque digest in a headless test

**File:** `src/test/java/com/openggf/tests/TestS3kCnzTeleporterRouteHeadless.java` (added assertion)

```java
assertEquals("sha256:70da89e553f70fe647a00489dec5f2612854986b444b87a2e8d81ab0f821e431",
        explosionJobs.getLast().handle().submissionFingerprint(),
        "the cannon handoff must submit the exact ROM-backed KosM parent");
```

The two preceding filters already pin the job to `Sonic3kConstants.ART_KOSM_BADNIK_EXPLOSION_ADDR` and `ARTTILE_EXPLOSION * 32`, which is the ROM-derived identity the message actually claims. The digest adds a dependency on the fingerprint algorithm without adding a distinct assertion. Harmless, but it will be the thing that breaks the day the fingerprint scheme changes, for no diagnostic gain. Contrast with the neighbouring `assertEquals(expectedCannonSlot, cannon.getSlotIndex())` where `expectedCannonSlot` is read live from `objectManager.firstFreeDynamicSlot()` — that is the pattern to copy.

---

## 4. What I checked and found nothing wrong with

Recording these so the next reviewer does not repeat them:

- All 54 removed assertions in the `src/test` diff, traced individually to deleted production code or a corrected fitted expectation.
- `@Disabled` / `Assumptions.assume*` / `@Tag` / tolerance deltas across the whole test diff: **none**.
- Added test literals matching `raw_frame = <3+ digits>` or bare ordinal counts: **none** outside C-4.
- Docs placement; frontier-log append-only-ness; absence of machine-local paths in the frontier log.
- `Sonic3kZoneFeatureProvider.updateAfterObjectExecution:392-395` zone-id branch — permitted; it sits inside the owning zone-feature provider and mirrors the pre-existing `updateAfterPlayablePhysics` shape directly above it.
- Both `ObjectManager` exec-loop entry points (`updateObjectPositionsPostPhysicsWithoutTouches`, `updateObjectPositionsWithoutTouches`) received the new parameter — the sibling was not missed.
- `S3kBossDefeatSignpostFlow.withNativeControlSlot` is applied only by `AizMinibossInstance`, which initially looked like a capability-matrix asymmetry. It is not: the slot is passed as `getSlotIndex()` (live, not a constant) and the reuse in `AizMinibossInstance.updateLevelEndCameraUnlock` is guarded by `retainedResultsOwnerSlot > getSlotIndex()`, i.e. a genuine `FindNextFreeObj` ordering predicate. Other bosses do not need it because they do not fold a results owner into a later slot. Correct as written.

---

## 5. Summary of findings

| id | severity | one-line |
|---|---|---|
| C-1 | MAJOR | Three test classes left behind by the recorded-admission fixture contract; the AIZ prefix-closure contract test now dies in setup |
| C-2 | MAJOR | `"s3k".equals(metadata.game())` game-name gate in `TraceData`, and it is redundant |
| C-3 | MAJOR | Load-queue comparison shifts and drops expected values instead of sampling the two boundaries the branch itself added |
| C-4 | MAJOR | AIZ terminal hardware check replaced by 4 fixture-measured ordinals + 4 opaque digests; skips `verifyRunComplete()` |
| C-5 | MAJOR | `CNZ_POST_TITLE_CARD_CONTROL_HANDOFF_DISPATCHES` re-fitted 9 → 2; ROM shows a same-dispatch call |
| C-6 | MINOR | New post-object zone-feature dispatch wired into only one of two exec paths (latent) |
| C-7 | MINOR | `markIterationHeldIntoNextRowForReplay` missing from CNZ/HCZ scenario drivers |
| C-8 | MINOR | `PlayableSpriteMovement.java` went pure-LF → 254 CRLF lines; two other files partially normalised |
| C-9 | MINOR | Tab indentation in space-indented `LevelFrameStep.java:374-382` |
| C-10 | MINOR | Name-based authority guard; equivalent lookahead shape now exists under another name |
| C-11 | MINOR | Frontier-log "no remaining in-scope failing frontier" is broader than the 13-class run behind it |
| C-12 | NOTE | Redundant opaque digest assertion in `TestS3kCnzTeleporterRouteHeadless` |

None of these is a BLOCKER. C-1 is the one I would want fixed before merge regardless of severity label, because it costs coverage on the exact invariant this branch is about and a failure-count comparison cannot see it.
