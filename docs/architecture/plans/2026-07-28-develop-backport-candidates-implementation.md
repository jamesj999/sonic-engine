# Develop Backport Candidates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the four defects from the `next` backport audit that still exist on current `develop`, while preserving the already-correct AIZ timing model.

**Architecture:** Make a narrow production diagnostic change in `RewindRegistry`; repair the modifier-documentation guard's source-site parser in test code; correct the S3K ROM-managed allocation range to 90 slots; and make the AIZ ROM integration test drive the production hardware-timing service boundaries. Keep already-landed tunnel/AWT fixes unchanged.

**Tech Stack:** Java 21, JUnit Jupiter 5, Maven Surefire, LWJGL key constants, OpenGGF hardware timing services.

## Global Constraints

- Runtime gameplay assets continue to come only from the user-supplied ROM.
- Do not add game-name or zone carve-outs to shared runtime code.
- Do not hydrate gameplay state from trace comparison data.
- Playable native position writes continue to route through `NativePositionOps`.
- `ObjectSlotLayout.SONIC_3K.dynamicSlotCount()` is 90 because the ROM probes absolute SST slots 4-93.
- AIZ queued art and terrain remain gated by the production hardware-timing service.
- Tests use JUnit Jupiter only and Maven must run on JDK 21.

---

### Task 1: Identify null-producing rewind adapters

**Files:**
- Modify: `src/test/java/com/openggf/game/rewind/TestRewindRegistry.java`
- Modify: `src/main/java/com/openggf/game/rewind/RewindRegistry.java`

**Interfaces:**
- Consumes: `RewindSnapshottable.capture()` and the adapter registered in `RewindRegistry`.
- Produces: a `NullPointerException` message containing both the registered key and `adapter.getClass().getName()`.

- [ ] **Step 1: Write the failing diagnostic test**

Extend `nullSnapshotsAreRejectedAtCapture` to retain the anonymous adapter in a local,
capture the thrown exception, and assert:

```java
RewindSnapshottable<Object> adapter = new RewindSnapshottable<>() {
    @Override public String key() { return "null"; }
    @Override public Object capture() { return null; }
    @Override public void restore(Object snapshot) { }
};
reg.register(adapter);

NullPointerException failure = assertThrows(NullPointerException.class, reg::capture);
assertTrue(failure.getMessage().contains("key: null"));
assertTrue(failure.getMessage().contains(adapter.getClass().getName()));
```

- [ ] **Step 2: Run the test and verify the expected failure**

Run:

```bash
mvn -Dtest=com.openggf.game.rewind.TestRewindRegistry#nullSnapshotsAreRejectedAtCapture test
```

Expected: FAIL because the exception message contains the key but not the anonymous
adapter class name.

- [ ] **Step 3: Append the concrete adapter class to the production diagnostic**

Change the null-snapshot branch in `RewindRegistry.capture()` to build:

```java
"Rewind snapshot must not be null for key: "
        + state.layout.keyAt(i)
        + " (" + state.adapters[i].getClass().getName() + ")"
```

- [ ] **Step 4: Re-run focused rewind tests**

Run:

```bash
mvn -Dtest=com.openggf.game.rewind.TestRewindRegistry test
```

Expected: PASS.

### Task 2: Isolate modifier-check call sites

**Files:**
- Modify: `src/test/java/com/openggf/configuration/TestModifierSupportDocumentation.java`

**Interfaces:**
- Consumes: flattened Java source and an index pointing at a
  `SonicConfiguration.<BINDING>` reference.
- Produces: the immediate input/modifier-check call outside the configuration getter;
  hoisted `int local = ...` assignments continue to return their whole statement plus
  checks applied to the local.

- [ ] **Step 1: Add a synthetic mixed-call regression test**

Use the existing `@TempDir configDir` to write a source file containing two sibling
calls inside a wrapper expression:

```java
Path source = configDir.resolve("MixedInput.java");
Files.writeString(source, """
        class MixedInput {
            boolean read(Input input, Config config) {
                return consume(input.isKeyPressedWithoutModifiers(
                        config.getInt(SonicConfiguration.PLAYBACK_TOGGLE_KEY))
                        || input.isKeyPressed(
                        config.getInt(SonicConfiguration.REWIND_KEY)));
            }
        }
        """);

String noModifiers = readSitesOf(
        "PLAYBACK_TOGGLE_KEY", List.of(source)).getFirst();
String plain = readSitesOf("REWIND_KEY", List.of(source)).getFirst();

assertTrue(noModifiers.contains("isKeyPressedWithoutModifiers("));
assertFalse(plain.contains("isKeyPressedWithoutModifiers("));
assertTrue(plain.contains("isKeyPressed("));
```

Add a private overload `readSitesOf(String name, List<Path> sources)` and make the
existing one delegate to `readSitesOf(name, mainSources())`. Initially keep the current
statement behavior so the new assertion demonstrates the bug.

- [ ] **Step 2: Run the regression test and verify the expected failure**

Run:

```bash
mvn -Dtest=com.openggf.configuration.TestModifierSupportDocumentation#mixedInlineCallsDoNotShareModifierClassification test
```

Expected: FAIL because the `REWIND_KEY` site incorrectly contains
`isKeyPressedWithoutModifiers(` from its sibling call.

- [ ] **Step 3: Implement enclosing-call extraction**

For each reference, continue to compute `statementAround`. If `HOISTED_LOCAL` matches,
keep the statement. Otherwise call a new `callAround(flattened, matcher.start(),
statement)` helper.

The helper must:

1. inspect call-opening parentheses before the reference;
2. use a balanced `matchingClose` helper;
3. retain only calls whose opening and closing parentheses enclose the reference;
4. recognize a call only when the opening parenthesis is preceded by a Java identifier
   or qualified identifier; and
5. skip the innermost configuration getter and return its immediate enclosing call,
   without widening into an outer wrapper containing sibling calls; and
6. fall back to the innermost call or statement when the expected nesting is absent.

This selects `isKeyPressedWithoutModifiers(config.getInt(...))` for the first binding
and `isKeyPressed(config.getInt(...))` for the second without crossing `||`.

- [ ] **Step 4: Run the full modifier documentation guard**

Run:

```bash
mvn -Dtest=com.openggf.configuration.TestModifierSupportDocumentation test
```

Expected: PASS, including the existing hoisted-local coverage.

### Task 3: Service AIZ hardware timing in the ROM integration test

**Files:**
- Modify: `src/test/java/com/openggf/game/sonic3k/features/TestAizFireCurtainRendererRom.java`

**Interfaces:**
- Consumes: `GameServices.hardwareTiming()` and
  `HardwareServiceBoundary.VINT_SERVICE`, `PRE_MAIN_LOOP`, and `POST_OBJECTS`.
- Produces: one `updateWithHardware(Sonic3kAIZEvents, int act, int frame)` helper that
  mirrors `TestSonic3kAIZEvents`.

- [ ] **Step 1: Pin the missing hardware-service behavior**

Run the single continuation test before changing it:

```bash
mvn -Dtest=com.openggf.game.sonic3k.features.TestAizFireCurtainRendererRom#aiz2PaletteCyclerRunsDuringFireContinuation -Ds3k.rom.path='/home/farrell/code/projects/OpenGGF/Sonic and Knuckles & Sonic 3 (W) [!].gen' test
```

Expected: FAIL with `Expected active fire continuation after the act 1 fake-out reload`.
Record this as the red state; the existing test already expresses the desired behavior.

- [ ] **Step 2: Add the hardware update helper**

Import `HardwareServiceBoundary` and add:

```java
private static void updateWithHardware(
        Sonic3kAIZEvents events, int act, int frame) {
    var timing = GameServices.hardwareTiming();
    timing.service(HardwareServiceBoundary.VINT_SERVICE);
    timing.service(HardwareServiceBoundary.PRE_MAIN_LOOP);
    events.update(act, frame);
    timing.service(HardwareServiceBoundary.POST_OBJECTS);
}
```

Replace every direct `events.update(...)`, `act1Events.update(...)`, and
`act2Events.update(...)` in the class with this helper.

- [ ] **Step 3: Re-run the continuation test**

Run:

```bash
mvn -Dtest=com.openggf.game.sonic3k.features.TestAizFireCurtainRendererRom#aiz2PaletteCyclerRunsDuringFireContinuation -Ds3k.rom.path='/home/farrell/code/projects/OpenGGF/Sonic and Knuckles & Sonic 3 (W) [!].gen' test
```

Expected: PASS.

- [ ] **Step 4: Record the renderer diagnostic baseline**

Run:

```bash
mvn -Dtest=com.openggf.game.sonic3k.features.TestAizFireCurtainRendererRom -Ds3k.rom.path='/home/farrell/code/projects/OpenGGF/Sonic and Knuckles & Sonic 3 (W) [!].gen' test
```

Expected: 2 passes and the two pre-existing descriptor-sampling diagnostic failures
(`realAizFakeoutProducesNonEmptyCurtainPlan` and
`realAizFakeoutSamplesFlameOverlayTileRange`) identified by audit item 5. Hardware
servicing also makes the phase-progression diagnostic green. Do not alter the executor,
synthesize descriptors, or weaken those assertions in this backport.

### Task 4: Correct and verify the S3K allocation boundary

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/ObjectSlotLayout.java`
- Modify: `src/main/java/com/openggf/level/InitialDynamicSstDispatcher.java`
- Modify: `src/main/java/com/openggf/level/InitialProcessSpritesCoordinator.java`
- Modify: `src/main/java/com/openggf/level/InitialProcessSpritesExecutor.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/S3kInitialFixedSstDispatcher.java`
- Modify: `src/test/java/com/openggf/tests/objects/TestSlotAllocator.java`
- Modify: `src/test/java/com/openggf/level/objects/TestObjectManagerInitialS3kSetupPass.java`
- Modify: `CHANGELOG.md`
- Retain: `docs/architecture/designs/2026-07-28-develop-backport-candidates-design.md`
- Retain: `docs/architecture/plans/2026-07-28-develop-backport-candidates-implementation.md`

**Interfaces:**
- Consumes: the ROM loops in `AllocateObject` and `Offset_ObjectsDuringTransition`.
- Produces: a 90-slot S3K dynamic allocation window over absolute slots 4-93.

- [ ] **Step 1: Add the failing S3K slot boundary test**

Add:

```java
@Test
void s3kAllocationIncludesFinalRomProbe() {
    SlotAllocator allocator =
            new SlotAllocator(ObjectSlotLayout.SONIC_3K, SlotEmptyPredicate.ID_BYTE);
    for (int slot = 4; slot <= 93; slot++) {
        assertEquals(slot, allocator.allocate());
    }
    assertEquals(-1, allocator.allocate());
    assertFalse(allocator.reserve(94));
}
```

Run:

```bash
mvn -Dtest=com.openggf.tests.objects.TestSlotAllocator test
```

Expected: FAIL at the old 89-slot limit.

- [ ] **Step 2: Correct the production layout and rerun slot tests**

Change `ObjectSlotLayout.SONIC_3K` from `dynamicSlotCount=89` to `90`, then rerun
`TestSlotAllocator` and the focused object-manager slot tests.

- [ ] **Step 3: Align the initial dispatch ownership boundary**

Rename the managed initial-dispatch contract to slots 4-93, remove slot 93 from
post-dynamic fixed dispatch, and document that slot 93 aliases the first empty
`Level_object_RAM` SST. Pin a dynamic object in absolute slot 93 ahead of fixed
slots 94-109 in the initial collision-list order.

- [ ] **Step 4: Verify already-landed automatic-tunnel routing**

Run:

```bash
mvn -Dtest=com.openggf.game.sonic3k.objects.TestAutomaticTunnelObjectInstance test
```

Expected: PASS.

- [ ] **Step 5: Verify the AWT-free production path**

Run:

```bash
mvn -Dtest=com.openggf.game.TestProductionAwtBlacklistGuard,com.openggf.graphics.TestWindowIconLoader test
```

Expected: PASS.

- [ ] **Step 6: Update changelog**

Record the ROM-correct S3K slot allocation boundary and actionable rewind capture
failure diagnostics in the unreleased changelog.

### Task 5: Verification, review, and integration

**Files:**
- All files changed by Tasks 1-4.

**Interfaces:**
- Consumes: the completed implementation branch.
- Produces: reviewed, regression-checked changes integrated and pushed on `develop`.

- [ ] **Step 1: Run combined focused verification**

Run:

```bash
mvn -Dtest=com.openggf.game.rewind.TestRewindRegistry,com.openggf.configuration.TestModifierSupportDocumentation,com.openggf.tests.objects.TestSlotAllocator,com.openggf.game.sonic3k.objects.TestAutomaticTunnelObjectInstance,com.openggf.game.TestProductionAwtBlacklistGuard,com.openggf.graphics.TestWindowIconLoader,com.openggf.game.sonic3k.events.TestSonic3kAIZEvents -Ds3k.rom.path='/home/farrell/code/projects/OpenGGF/Sonic and Knuckles & Sonic 3 (W) [!].gen' test
```

Expected: all modified/guard classes pass. `TestSonic3kAIZEvents` retains its independently
reproduced baseline of three failures and one error; no additional AIZ event failure is
acceptable.

- [ ] **Step 2: Run the full suite in the development worktree**

Run:

```bash
mvn test \
  -Dsonic1.rom.path='/home/farrell/code/projects/OpenGGF/Sonic The Hedgehog (W) (REV01) [!].gen' \
  -Dsonic2.rom.path='/home/farrell/code/projects/OpenGGF/Sonic The Hedgehog 2 (W) (REV01) [!].gen' \
  -Ds3k.rom.path='/home/farrell/code/projects/OpenGGF/Sonic and Knuckles & Sonic 3 (W) [!].gen'
```

Record exact failures. The two known AIZ renderer diagnostics and the isolated
`TestSonic3kAIZEvents` baseline of three failures/one error may remain red; no new
failure is acceptable.

- [ ] **Step 3: Run the available trace replay sweep**

Run the repository's `*TraceReplay` suite with all three discovered ROM paths. Record
the command, context, pass/fail counts, and first divergent frames in
`docs/status/trace-frontier-log.md` if the frontier changes. Diagnose changes against
the ROM's 90-probe allocation loop; do not tune the slot count to trace output.

- [ ] **Step 4: Run verification-before-completion and request code review**

Inspect `git diff --check`, changed files, test reports, and policy trailers. Delegate an
independent code review and resolve every valid issue, repeating until no blocking issue
remains.

- [ ] **Step 5: Commit the implementation branch**

Stage only intended source, test, changelog (if any), design, and plan files. Commit with
all required trailers; never use `--no-verify`.

- [ ] **Step 6: Update the integration baseline**

In the main workspace, preserve all user changes, fetch the remote, and fast-forward
`develop`. Run the full suite with the same three absolute ROM properties from Step 2
and record exact failures.

- [ ] **Step 7: Rebase or merge the updated baseline into the development branch**

Reconcile upstream conflicts in the isolated worktree, rerun the focused tests and full
suite, and confirm no new failure relative to the updated baseline.

- [ ] **Step 8: Merge into main-workspace `develop`**

Merge the completed branch without switching the main workspace. Because this is a
non-master branch merging into `develop`, update and stage the README release/change-log
section as required by repository policy.

- [ ] **Step 9: Run post-merge regression comparison**

Run the same full suite in the main workspace and confirm no baseline-passing test now
fails and no baseline failure worsens because of this work.

- [ ] **Step 10: Push and clean up**

Push only `develop`. Verify the implementation worktree is clean and fully merged, remove
it, delete the fully merged local `bugfix/ai-develop-backport-candidates` branch, and
prune worktree metadata.
