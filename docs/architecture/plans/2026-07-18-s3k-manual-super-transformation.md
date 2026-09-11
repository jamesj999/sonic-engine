# S3K Manual Super Transformation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make S3K Super/Hyper transformation require the ROM-accurate second airborne jump press for Sonic, Tails, and Knuckles, with durable emerald-conversion and rewind-safe controller state.

**Architecture:** Sonic 2 keeps the base controller's automatic trigger. S3K disables it and owns character-specific eligibility; shared movement invokes the explicit controller entry before each character's native fallback ability. `GameStateManager` owns emerald conversion, while player rewind captures and reconciles S3K controller phase/timers without replaying effects.

**Tech Stack:** Java 21, Maven, JUnit 5, Mockito, existing gameplay rewind records.

**Spec:** `docs/architecture/designs/2026-07-18-s3k-manual-super-transformation-design.md`

---

### Task 1: Durable emerald-conversion state

**Files:**
- Modify: `src/main/java/com/openggf/game/GameStateManager.java`
- Modify: `src/main/java/com/openggf/game/rewind/snapshot/GameStateSnapshot.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/dataselect/S3kSaveSnapshotProvider.java`
- Modify: `src/main/java/com/openggf/Engine.java`
- Test: `src/test/java/com/openggf/game/TestGameStateRewindSnapshot.java`
- Test: `src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kSaveSnapshotProvider.java`
- Test: existing Engine data-select restore tests located with `rg "restoreGameplayModeFromDataSelectPayload" src/test/java`

- [ ] **Step 1: Write failing persistence tests**

Add assertions for a converted state with zero Super Emeralds, reset clearing, `GameStateSnapshot` round trip, live save payload key `emeraldsConverted`, explicit load, and legacy inference from a non-empty `superEmeralds` list.

```java
gameState.setEmeraldsConverted(true);
GameStateSnapshot snapshot = gameState.capture();
gameState.setEmeraldsConverted(false);
gameState.restore(snapshot);
assertTrue(gameState.isEmeraldsConverted());
```

- [ ] **Step 2: Run tests and verify RED**

Run: `mvn "-Dtest=TestGameStateRewindSnapshot,TestS3kSaveSnapshotProvider,*DataSelect*" test`

Expected: compilation/assertion failures because `emeraldsConverted` does not yet exist or is absent from payload restoration.

- [ ] **Step 3: Implement the durable owner**

Add `boolean emeraldsConverted`, getter/setter, reset clearing, legacy-compatible save restoration, and snapshot capture/restore. Extend `restoreSaveProgress` with an explicit nullable/optional conversion value or an overload that distinguishes an absent payload key from explicit `false`.

```java
public void restoreSaveProgress(..., Boolean emeraldsConverted) {
    ...
    this.emeraldsConverted = emeraldsConverted != null
            ? emeraldsConverted
            : superEmeralds != null && !superEmeralds.isEmpty();
}
```

Persist `emeraldsConverted` in S3K live/fresh payloads and parse it in `Engine.restoreGameplayModeFromDataSelectPayload` without changing S1/S2 semantics.

- [ ] **Step 4: Verify GREEN**

Run the Task 1 focused command and require zero failures.

### Task 2: Controller-owned S3K eligibility and trigger mode

**Files:**
- Modify: `src/main/java/com/openggf/sprites/playable/SuperStateController.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kSuperStateController.java`
- Test: `src/test/java/com/openggf/sprites/managers/TestPlayableSpriteMovement.java`

- [ ] **Step 1: Write failing controller tests**

Cover no automatic S3K activation, retained automatic S2 activation, Sonic/Knuckles Chaos eligibility only before conversion, Hyper eligibility with all Super Emeralds, Tails requiring all Super Emeralds and main-playable identity, Sonic-only invincibility suppression, all-character HUD-timer suppression, elemental/BASIC shield distinctions, and ring/dead/hurt/object-control gates.

- [ ] **Step 2: Verify RED**

Run: `mvn "-Dtest=TestPlayableSpriteMovement" test`

Expected: S3K automatic activation and missing character/conversion policy assertions fail.

- [ ] **Step 3: Implement narrow hooks**

Keep automatic activation enabled by default and override it in S3K:

```java
protected boolean usesAutomaticJumpTrigger() { return true; }
protected boolean hasTransformationEmeralds() {
    return player.currentGameState().hasAllEmeralds();
}
```

`checkTransformationTrigger()` checks the automatic policy. `canTransform()` delegates emerald/HUD/character conditions through protected hooks. S3K reads player type, `GameStateManager.isEmeraldsConverted()`, Super Emeralds, `SpriteManager.getMainPlayable()`, shield type, and character-specific invincibility exactly as the approved eligibility table specifies.

- [ ] **Step 4: Verify GREEN**

Run the Task 2 test command and require zero failures.

### Task 3: Second-press ordering and air-ability window

**Files:**
- Modify: `src/main/java/com/openggf/sprites/managers/PlayableSpriteMovement.java`
- Test: `src/test/java/com/openggf/sprites/managers/TestPlayableSpriteMovement.java`
- Test: `src/test/java/com/openggf/sprites/managers/TestPlayableSpriteMovementTailsFlight.java`

- [ ] **Step 1: Write failing movement tests**

Exercise the real `doJumpHeight` path with a fresh jump edge. Assert normal rejection below `-0x400`, acceptance at `-0x400`, underwater rejection below `-0x200`, acceptance at `-0x200`, and rejection when `doubleJumpFlag != 0`. For eligible main Tails transformation must precede flight; for eligible Knuckles it must precede glide; failed eligibility must preserve the existing fallback path. CPU Tails retains its existing flight gate.

- [ ] **Step 2: Verify RED**

Run: `mvn "-Dtest=TestPlayableSpriteMovement,TestPlayableSpriteMovementTailsFlight" test`

Expected: early-window and Tails/Knuckles ordering assertions fail.

- [ ] **Step 3: Implement one shared window helper and ordered attempts**

```java
private boolean isAirAbilityWindowOpen() {
    int threshold = sprite.isInWater() ? -0x200 : -0x400;
    return sprite.getDoubleJumpFlag() == 0 && sprite.getYSpeed() >= threshold;
}
```

Call explicit transformation only from the fresh-press branch after the window opens. Sonic attempts after elemental shield priority is known; Tails attempts before `tryActivateTailsFlight`; Knuckles attempts before `activateGlide`. Only a successful controller return consumes transformation handling.

- [ ] **Step 4: Verify GREEN**

Run the Task 3 command and require zero failures.

### Task 4: S3K super-controller rewind state

**Files:**
- Modify: `src/main/java/com/openggf/sprites/playable/SuperStateController.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kSuperStateController.java`
- Modify: `src/main/java/com/openggf/sprites/playable/PlayableSpriteController.java`
- Test: `src/test/java/com/openggf/sprites/playable/TestAbstractPlayableSpriteRewindCapture.java`
- Test: `src/test/java/com/openggf/game/rewind/TestPlayableSpriteRewindState.java`

- [ ] **Step 1: Write failing rewind round-trip tests**

Capture and restore NORMAL, mid-TRANSFORMING, and SUPER states. Assert exact phase, ring-drain counter, palette state/frame/timer, transformation timer, next-frame evolution, physics profile, animation set/renderer phase, and shield visibility. Use recording audio/services where available to assert restore does not play SFX/music or mutate rings. Do not assert or capture any raw gameplay object reference.

- [ ] **Step 2: Verify RED**

Run: `mvn "-Dtest=TestAbstractPlayableSpriteRewindCapture,TestPlayableSpriteRewindState" test`

Expected: the aggregate controller state lacks a Super-state payload and restored phases diverge.

- [ ] **Step 3: Add compact state and side-effect-free reconciliation**

Add a base immutable record plus S3K scalar extension (or a single typed S3K record carried through a sealed payload). `PlayableSpriteController.RewindState` captures/restores it only when the installed controller supports complete rewind state. The S3K restore assigns scalars, then reconciles structural art/renderer/profile references based on NORMAL/TRANSFORMING/SUPER without calling activation or revert callbacks. Retain normal structural references across backward/forward replay. S2 returns no partial payload unless its raw stars identity contract is also fully solved.

- [ ] **Step 4: Verify GREEN**

Run the Task 4 command and require zero failures.

### Task 5: Documentation and complete verification

**Files:**
- Modify: `CHANGELOG.md` by merging a concise entry into the user's existing edits
- Modify only if behavior warrants: `docs/S3K_KNOWN_DISCREPANCIES.md`

- [ ] **Step 1: Update documentation without overwriting unrelated edits**

Document manual S3K activation, character rules, conversion gate, and rewind capture. Do not touch `docs/status/trace-frontier-log.md` unless a trace frontier actually moves.

- [ ] **Step 2: Run focused behavior and rewind suites**

```powershell
mvn "-Dtest=TestPlayableSpriteMovement,TestPlayableSpriteMovementTailsFlight,TestSuperStateController,TestGameStateRewindSnapshot,TestS3kSaveSnapshotProvider,TestAbstractPlayableSpriteRewindCapture,TestPlayableSpriteRewindState" test
```

- [ ] **Step 3: Run rewind guards**

```powershell
mvn "-Dtest=TestRewindCoverageGuard,TestStaticStateRewindCoverageGuard,TestRewindFieldAudit,TestRemainingRewindCoverageClosure" test
```

- [ ] **Step 4: Run full build**

Run: `mvn package`

Expected: exit code 0 and no test failures.

- [ ] **Step 5: Inspect final diff**

Run `git diff --check`, `git status --short`, and scoped diffs. Confirm unrelated Jawz/HarmfulExplosion/mods work remains untouched and unstaged.
