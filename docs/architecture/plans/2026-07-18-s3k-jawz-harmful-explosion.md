# S3K Jawz Harmful Explosion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reproduce Jawz's ROM behavior by replacing vulnerable-contact Jawz with the short-lived, harmful HCZ explosion child.

**Architecture:** Add a standalone HCZ explosion object with scalar animation state, standard hurt collision, shared explosion rendering, and spawn-coordinate rewind recreation. Jawz allocates it with `spawnChild(...)` and deletes itself; player damage remains owned by the shared touch-response controller.

**Tech Stack:** Java 17, JUnit 5, Maven, OpenGGF object/touch-response/rewind frameworks.

---

### Task 1: Characterize the missing Jawz transformation

**Files:**
- Modify: `src/test/java/com/openggf/tests/TestS3kJawzBadnik.java`

- [ ] **Step 1: Write the failing vulnerable-contact test**

Add a real `ObjectManager` fixture and drive Jawz through initialization, `onTouchResponse(...)`, and its next main dispatch. Assert that Jawz is destroyed and exactly one `HczHarmfulExplosionObjectInstance` exists at Jawz's centre coordinates.

- [ ] **Step 2: Run the focused test and verify RED**

Run `mvn "-Dtest=com.openggf.tests.TestS3kJawzBadnik" test`.

Expected: FAIL because no harmful explosion child is created.

### Task 2: Specify the HCZ explosion lifecycle

**Files:**
- Create: `src/test/java/com/openggf/game/sonic3k/objects/TestHczHarmfulExplosionObjectInstance.java`
- Create: `src/main/java/com/openggf/game/sonic3k/objects/HczHarmfulExplosionObjectInstance.java`

- [ ] **Step 1: Write failing lifecycle tests**

Construct the wished-for object with `(int x, int y)` and assert:

```java
assertEquals(0x8B, explosion.getCollisionFlags());
explosion.update(0, player); // setup dispatch
for (int i = 1; i <= 24; i++) explosion.update(i, player);
assertEquals(0, explosion.getCollisionFlags());
for (int i = 25; i <= 40; i++) explosion.update(i, player);
assertTrue(explosion.isDestroyed());
```

Also assert `requiresRenderFlagForTouch()` is false and that `recreateForRewind(...)` preserves the captured spawn coordinates through `SpawnCoordinateRewindRecreatable`.

- [ ] **Step 2: Run the lifecycle test and verify RED**

Run `mvn "-Dtest=com.openggf.game.sonic3k.objects.TestHczHarmfulExplosionObjectInstance" test`.

Expected: test compilation fails because the class does not exist.

- [ ] **Step 3: Implement the minimal ROM object**

Create a final `AbstractObjectInstance` implementing `TouchResponseProvider` and `SpawnCoordinateRewindRecreatable`. Use constants for `$8B`, delay 7, non-hurting frame 3, and final frame 5. Preserve the separate init dispatch, then pre-decrement the frame timer, advance the mapping frame, disable collision at frame 3, and expire at frame 5. Render via `services().renderManager().getExplosionRenderer()` at the object's centre position.

- [ ] **Step 4: Run the lifecycle test and verify GREEN**

Run `mvn "-Dtest=com.openggf.game.sonic3k.objects.TestHczHarmfulExplosionObjectInstance" test`.

Expected: PASS.

### Task 3: Connect Jawz to the harmful explosion

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/badniks/JawzBadnikInstance.java`
- Modify: `src/test/java/com/openggf/tests/TestS3kJawzBadnik.java`

- [ ] **Step 1: Implement the minimal vulnerable branch**

Replace the vulnerable branch's delete-only behavior with:

```java
spawnChild(() -> new HczHarmfulExplosionObjectInstance(currentX, currentY));
ObjectLifetimeOps.destroyLatched(this);
```

Retain `Check_PlayerAttack` parity and the existing attacked-player defeat/bounce path.

- [ ] **Step 2: Run Jawz tests and verify GREEN**

Run `mvn "-Dtest=com.openggf.tests.TestS3kJawzBadnik" test`.

Expected: PASS.

### Task 4: Verify touch damage and rewind coverage

**Files:**
- Modify: `src/test/java/com/openggf/game/sonic3k/objects/TestHczHarmfulExplosionObjectInstance.java`

- [ ] **Step 1: Add a production touch-controller test**

Register the explosion in an `ObjectManager` using the S3K touch-response table, overlap a vulnerable playable sprite, run the touch pass, and assert the sprite enters hurt/death handling. Advance to mapping frame 3, repeat, and assert no new hurt contact occurs.

- [ ] **Step 2: Run the touch test and verify GREEN**

Run the focused explosion test and expect PASS.

- [ ] **Step 3: Run rewind and guard verification**

Run:

```text
mvn "-Dtest=TestRewindCoverageGuard,TestSpawnRewindRecreatableCleanup,TestNoServicesInObjectConstructors" test
```

Expected: PASS with no new coverage-baseline entry.

### Task 5: Final verification and documentation

**Files:**
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Add an Unreleased changelog entry**

Document that vulnerable Jawz now becomes the ROM harmful HCZ explosion rather than disappearing harmlessly, citing `Obj_Jawz` and `HCZEndBossExplosion_*`.

- [ ] **Step 2: Run focused and package verification**

Run:

```text
mvn "-Dtest=com.openggf.tests.TestS3kJawzBadnik,com.openggf.game.sonic3k.objects.TestHczHarmfulExplosionObjectInstance" test
mvn package
```

Expected: all tests and package build pass.

- [ ] **Step 3: Cross-check against the disassembly**

Confirm child allocation order, centre coordinates, `$8B` collision, animation delay/frame sequence, collision cutoff, and deletion timing against `docs/skdisasm/sonic3k.asm`.
