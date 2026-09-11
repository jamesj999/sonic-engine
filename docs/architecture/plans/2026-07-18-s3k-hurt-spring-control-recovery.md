# S3K Hurt Spring Control Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make S3K vertical and diagonal springs restore normal player control when they launch a hurt character, matching the ROM's unconditional `routine=2` writes.

**Architecture:** Keep the existing `hurt` flag as the engine representation of native player routine 4. At each S3K spring launch path where the disassembly writes player `routine=2`, clear that flag after establishing the airborne launch state. Preserve horizontal-spring and hurt/water behavior because those paths do not perform the same native routine transition.

**Tech Stack:** Java 17, JUnit Jupiter, Maven.

---

### Task 1: Characterize the missing native routine transition

**Files:**
- Modify: `src/test/java/com/openggf/game/sonic3k/objects/TestSonic3kSpringObjectInstance.java`

- [ ] **Step 1: Write failing regression assertions**

Set a test player to `hurt=true` before invoking the existing up-, down-, and diagonal-spring helpers. Assert that each helper leaves `isHurt()` false, citing `sub_22F98`, `sub_233CA`, and `sub_234E6` respectively.

- [ ] **Step 2: Verify the tests fail for the missing transition**

Run:

```powershell
mvn "-Dtest=com.openggf.game.sonic3k.objects.TestSonic3kSpringObjectInstance" test
```

Expected: FAIL because all affected launch helpers currently leave `hurt=true`.

### Task 2: Restore routine-2 semantics

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/Sonic3kSpringObjectInstance.java`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Implement the minimal production change**

Call `player.setHurt(false)` in `applyUpSpring`, `applyDownSpring`, and `applyDiagonalSpring` immediately after the ROM-equivalent airborne/jumping/on-object state writes. Document the matching `move.b #2,routine(a1)` disassembly line. Do not change `applyHorizontalSpring` or `LevelWaterCoordinator`.

- [ ] **Step 2: Verify the focused tests pass**

Run:

```powershell
mvn "-Dtest=com.openggf.game.sonic3k.objects.TestSonic3kSpringObjectInstance" test
```

Expected: PASS.

- [ ] **Step 3: Record the user-visible parity fix**

Add an Unreleased changelog entry explaining that hurt players launched by S3K vertical/diagonal springs now regain control and resume correct air/water physics.

### Task 3: Validate and commit

**Files:**
- Verify all modified files above.

- [ ] **Step 1: Run relevant cross-game spring tests**

```powershell
mvn "-Dtest=com.openggf.game.sonic1.objects.TestSonic1SpringObjectInstance,com.openggf.game.sonic2.objects.TestSpringObjectInstance,com.openggf.game.sonic3k.objects.TestSonic3kSpringObjectInstance" test
```

Expected: PASS.

- [ ] **Step 2: Run the full build**

```powershell
mvn package
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit on develop**

Stage only the plan, changelog, S3K spring implementation, and its test. Commit with the required branch-policy trailers, marking Changelog updated and all unrelated documentation categories `n/a`.
