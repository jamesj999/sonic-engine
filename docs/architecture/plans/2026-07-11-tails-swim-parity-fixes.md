# Tails Swimming Parity Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the erroneous separate tail overlay from S3K Tails swimming and restore ROM-accurate no-input swim velocity.

**Architecture:** Keep each correction at the ROM-equivalent owner. `TailsTailsController` mirrors `Obj_Tails_Tail_AniSelection`, while `PlayableSpriteMovement` keeps the generic underwater gravity reduction confined to the non-flight airborne path. The existing `TailsFlightController` remains unchanged.

**Tech Stack:** Java 21, JUnit 5, Mockito, Maven, S3K disassembly in `docs/skdisasm/sonic3k.asm`.

---

### Task 1: Blank the separate tail overlay during swimming

**Files:**
- Modify: `src/test/java/com/openggf/sprites/managers/TestTailsTailsFlightSelection.java`
- Modify: `src/main/java/com/openggf/sprites/managers/TailsTailsController.java:114-117`

- [ ] **Step 1: Replace the test that encodes the bug with separate flying and swimming expectations**

Add the static import:

```java
import static org.mockito.Mockito.verifyNoInteractions;
```

Replace `everyFlightAndSwimParentAnimationDrawsTailArt` with:

```java
@Test
void flyingParentAnimationsDrawSeparateTailArt() {
    TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0x100, (short) 0x200);
    PlayerSpriteRenderer renderer = mock(PlayerSpriteRenderer.class);
    TailsTailsController controller = new TailsTailsController(tails, renderer, true);

    for (int parentAnimation = 0x20; parentAnimation <= 0x24; parentAnimation++) {
        clearInvocations(renderer);
        tails.setAnimationId(parentAnimation);

        controller.update();
        controller.draw();

        verify(renderer).drawFrame(anyInt(), anyInt(), anyInt(), anyBoolean(), anyBoolean());
    }
}

@Test
void swimmingParentAnimationsKeepSeparateTailBlank() {
    TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0x100, (short) 0x200);
    PlayerSpriteRenderer renderer = mock(PlayerSpriteRenderer.class);
    TailsTailsController controller = new TailsTailsController(tails, renderer, true);

    for (int parentAnimation = 0x25; parentAnimation <= 0x28; parentAnimation++) {
        clearInvocations(renderer);
        tails.setAnimationId(parentAnimation);

        controller.update();
        controller.draw();

        verifyNoInteractions(renderer);
    }
}
```

- [ ] **Step 2: Run the swimming-overlay test and verify RED**

Run:

```powershell
mvn "-Dmse=off" "-Dtest=TestTailsTailsFlightSelection#swimmingParentAnimationsKeepSeparateTailBlank" "-Ds3k.rom.path=s3k.gen" test
```

Expected: FAIL because `drawFrame(...)` is invoked for parent animation `$25`.

- [ ] **Step 3: Correct the S3K Obj05 selection table**

In `ANI_SELECTION_S3K`, change only the swim entries:

```java
0,     // 0x25 Swim -> Blank (body mapping already includes tails)
0,     // 0x26 Swim ascend -> Blank
0,     // 0x27 Swim carry -> Blank
0,     // 0x28 Swim tired -> Blank
```

Keep `$20-$24` mapped to `$0B/$0C` exactly as before.

- [ ] **Step 4: Run both tail-selection tests and verify GREEN**

Run:

```powershell
mvn "-Dmse=off" "-Dtest=TestTailsTailsFlightSelection" "-Ds3k.rom.path=s3k.gen" test
```

Expected: 3 tests pass with 0 failures/errors.

### Task 2: Remove the non-flight underwater reduction from active swimming

**Files:**
- Modify: `src/test/java/com/openggf/sprites/managers/TestPlayableSpriteMovementTailsFlight.java`
- Modify: `src/main/java/com/openggf/sprites/managers/PlayableSpriteMovement.java:3475-3483`

- [ ] **Step 1: Add a no-input idle-swim velocity regression test**

Add beside `activeManualFlightUpdatesVerticalVelocityExactlyOncePerAirborneFrame`:

```java
@Test
void activeManualSwimmingWithoutInputGentlySinksAtFlightGravity() {
    tails.setInWater(true);
    tails.setYSpeed((short) 0);
    tails.getTailsFlightController().activate();

    movement.handleMovement(false, false, false, false,
            false, false, false, false);
    assertEquals((short) 0x0008, tails.getYSpeed(),
            "Tails_FlyingSwimming skips the non-flight underwater -$28 adjustment");

    movement.handleMovement(false, false, false, false,
            false, false, false, false);
    assertEquals((short) 0x0010, tails.getYSpeed(),
            "idle swimming continues with only +$08 flight gravity per frame");
}
```

- [ ] **Step 2: Run the idle-swim test and verify RED**

Run with the main checkout's extracted native library path because this worktree has no independent LWJGL extraction directory:

```powershell
mvn "-Dmse=off" "-Dtest=TestPlayableSpriteMovementTailsFlight#activeManualSwimmingWithoutInputGentlySinksAtFlightGravity" "-Dorg.lwjgl.librarypath=C:\Users\farre\IdeaProjects\sonic-engine\target\native-libs" test
```

Expected: FAIL; first-frame `y_vel` is `-$0020` rather than `$0008`.

- [ ] **Step 3: Gate the generic underwater reduction at its owner**

At the start of `applyUnderwaterAirGravityReduction()`, replace the existing water-only guard with:

```java
// Tails_FlyingSwimming owns y_vel through Tails_Move_FlySwim and then calls
// MoveSprite_TestGravity2 directly; only Tails_Stand_Freespace reaches the
// ordinary underwater reduction (sonic3k.asm:27553-27588).
if (!sprite.isInWater() || isTailsFlightPhysicsActive(sprite)) {
    return;
}
```

Do not modify `TailsFlightController` or water-entry velocity scaling.

- [ ] **Step 4: Run the idle-swim test and verify GREEN**

Run:

```powershell
mvn "-Dmse=off" "-Dtest=TestPlayableSpriteMovementTailsFlight#activeManualSwimmingWithoutInputGentlySinksAtFlightGravity" "-Dorg.lwjgl.librarypath=C:\Users\farre\IdeaProjects\sonic-engine\target\native-libs" test
```

Expected: 1 test passes with 0 failures/errors.

### Task 3: Document and verify the integrated correction

**Files:**
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Add the changelog entry**

Add under the topmost `## Unreleased`:

```markdown
- **fix(tails): correct S3K swimming presentation and idle vertical motion.** Swimming animations `$25-$28` now suppress the separate Obj05 flying-tail overlay because their body mappings already include Tails' tails, and active swimming bypasses the ordinary airborne underwater `-$28` adjustment so no-input motion uses only the ROM's `+$08` flight gravity.
```

- [ ] **Step 2: Run the focused regression suite**

Run:

```powershell
mvn "-Dmse=off" "-Dtest=TestTailsFlightController,TestPlayableSpriteAnimation,TestPlayableSpriteMovementTailsFlight,TestTailsTailsFlightSelection,TestSidekickCpuManualFlight,TestSidekickCpuControllerCarry,TestRespawnStrategies,TestSidekickCpuControllerFlightAutoRecovery" "-Ds3k.rom.path=s3k.gen" "-Dorg.lwjgl.librarypath=C:\Users\farre\IdeaProjects\sonic-engine\target\native-libs" test
```

Expected: all selected tests pass with 0 failures/errors.

- [ ] **Step 3: Run policy and diff checks**

Run:

```powershell
mvn "-Dmse=off" validate
git diff --check
git status --short
```

Expected: Maven validation succeeds, diff check prints nothing, and status lists only the intended source, test, and changelog files (the design and plan are already committed).

- [ ] **Step 4: Commit the implementation**

Stage the intended files and commit with:

```text
fix: correct Tails swimming parity

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
```

- [ ] **Step 5: Request independent code review and correct until approved**

The reviewer must verify the diff against `Obj_Tails_Tail_AniSelection`, `Tails_Stand_Freespace`, `Tails_FlyingSwimming`, and `Tails_Move_FlySwim`. Automatically correct every actionable finding, rerun the focused suite, and resubmit until the result is APPROVED.
