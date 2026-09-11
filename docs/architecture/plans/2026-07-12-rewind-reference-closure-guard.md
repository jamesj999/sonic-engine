# Rewind Reference-Closure Guard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Detect dangling captured object references deterministically during S2/S3K replay, repair the MHZ1 lifecycle regression, and keep strict rewind identity validation.

**Architecture:** Production codecs declare whether they consume the rewind identity table. `CompactFieldCapturer` reuses those codecs for a focused closure check, while `ObjectManager` supplies exactly the snapshot-owner population and identity context used by real capture. Both ordinary and S3K trace loops call one post-level-frame guard, and object-specific failures are repaired at their lifecycle owner.

**Tech Stack:** Java 21, JUnit 5/Jupiter, Maven Surefire/MSE, existing compact rewind schema/codecs, `ObjectManager`, headless trace replay.

---

## Execution addendum (empirical final process)

The combined-selector Maven examples originally drafted for Task 0 and Task 5
were empirically superseded because the reliable full-sweep process requires a
fresh Surefire JVM for each class. Precompile once with
`mvn -Dmse=off -DskipTests test-compile`, then execute, in order, every remaining
line preserved in `target/rewind-closure-baseline/commands.txt`. Those 29
class-scoped commands use `surefire:test`, `forkCount=1`, and `-Xmx2g`.

The preserved artifact format is schema v2: `manifest.json` is the sweep wrapper
and the top-level, sorted `method-status.json` is the comparison oracle. Baseline
SHA `a3ad53f4a` ran 29 classes / 61 methods: 41 passed, 19 had pre-existing
assertion failures, and one had the known CNZ behavioral error; there were no
skips or infrastructure errors. The final exclusive sweep at `c882f3d7f`
matched all 61 method statuses exactly, with zero reference-closure failures.
The repair loop fixed MHZ1 actor detachment, ICZ capture-cloud detachment, and
MCZ Obj6A shared unload anchors; it also closed the AIZ glow-child coverage audit
gap by classifying immutable animation/plane values as spawn-derived state.

Timing used the longest status-stable passing route per game, one warm-up and
five serial samples in clean baseline/guarded worktrees. S2 WFZ medians were
28.980 s baseline and 29.117 s guarded (+0.136 s, +0.47%); S3K AIZ complete-run
medians were 38.197 s and 35.753 s (-2.444 s, -6.40%). Neither triggered the
threshold of both more than one second and more than 10% slower.

---

## File map

- `src/main/java/com/openggf/game/rewind/schema/RewindCodec.java` — codec-owned identity-table metadata.
- `src/main/java/com/openggf/game/rewind/schema/RewindCodecs.java` — metadata implementations for direct and nested identity-bearing codecs.
- `src/main/java/com/openggf/game/rewind/schema/CompactFieldCapturer.java` — focused validation and contextual capture failures.
- `src/main/java/com/openggf/game/rewind/GenericRewindEligibility.java` — shared predicate for classes that actually emit compact generic state.
- `src/main/java/com/openggf/level/objects/AbstractObjectInstance.java` — consume the shared compact-route predicate.
- `src/main/java/com/openggf/level/objects/AbstractBadnikInstance.java` — consume the shared compact-route predicate for badnik capture.
- `src/main/java/com/openggf/level/objects/ObjectManager.java` — validate exact snapshot owners against the real identity context.
- `src/main/java/com/openggf/game/sonic3k/objects/Mhz1CutsceneButtonInstance.java` — conditional child detachment endpoint.
- `src/main/java/com/openggf/game/sonic3k/objects/CutsceneKnucklesMhz1Instance.java` — detach from the button during unload.
- `src/test/java/com/openggf/game/rewind/schema/TestRewindReferenceClosureValidation.java` — codec/validator parity and diagnostics.
- `src/test/java/com/openggf/level/objects/TestObjectManagerRewindReferenceClosure.java` — exact owner membership and fallback behavior.
- `src/test/java/com/openggf/game/sonic3k/objects/TestMhz1CutsceneReferenceClosure.java` — frame-driven MHZ1 regression.
- `src/test/java/com/openggf/tests/trace/TraceReplayFrameClosureDriver.java` — testable general/S3K step seam and current-manager lookup.
- `src/test/java/com/openggf/tests/trace/AbstractTraceReplayTest.java` — shared post-frame guard in both replay paths.
- `src/test/java/com/openggf/tests/trace/TestTraceReplayReferenceClosureGuard.java` — trace-context diagnostics and both-loop wiring guard.
- `CHANGELOG.md` — player/developer-visible rewind reliability note.
- `docs/status/trace-frontier-log.md` — exact full-sweep result because the sweep is used to expose closure failures.

### Task 0: Capture the pre-guard trace baseline

**Files:**
- Read: `src/test/java/com/openggf/tests/trace/s2/*.java`
- Read: `src/test/java/com/openggf/tests/trace/s3k/*.java`
- Generate (untracked): `target/rewind-closure-baseline/`

- [ ] **Step 1: Verify ROM and trace prerequisites**

```powershell
$expected = @{
    's2.gen'  = '8BCA5DCEF1AF3E00098666FD892DC1C2A76333F9'
    's3k.gen' = 'CFBF98C36C776677290A872547AC47C53D2761D6'
}
foreach ($rom in $expected.Keys) {
    $actual = (Get-FileHash -Algorithm SHA1 $rom).Hash
    if ($actual -ne $expected[$rom]) { throw "$rom SHA-1 mismatch: $actual" }
}
$metadata = Get-ChildItem src/test/resources/traces/s2,src/test/resources/traces/s3k -Recurse -Filter metadata.json |
    Where-Object { $_.Directory.Name -ne '_movies' }
if ($metadata.Count -eq 0) { throw 'No S2/S3K trace metadata found' }
foreach ($item in $metadata) {
    $dir = $item.Directory
    $meta = Get-Content -Raw $item | ConvertFrom-Json
    $gameRoot = $dir
    while ($gameRoot.Parent -and $gameRoot.Parent.Name -notin @('s2','s3k')) { $gameRoot = $gameRoot.Parent }
    if ($gameRoot.Parent -and $gameRoot.Parent.Name -in @('s2','s3k')) { $gameRoot = $gameRoot.Parent }
    $sharedBk2 = if ($meta.source_bk2) {
        Join-Path $gameRoot.FullName "_movies/$($meta.source_bk2)"
    } else { $null }
    $hasBk2 = ($sharedBk2 -and (Test-Path $sharedBk2)) -or
            [bool](Get-ChildItem $dir -Filter *.bk2 | Select-Object -First 1)
    $hasPhysics = (Test-Path (Join-Path $dir 'physics.csv')) -or
            (Test-Path (Join-Path $dir 'physics.csv.gz'))
    if (-not $hasBk2 -or -not $hasPhysics) {
        throw "Incomplete trace payload: $dir"
    }
}
```

Expected SHA-1 values are the project-documented S2 REV01
`8BCA5DCEF1AF3E00098666FD892DC1C2A76333F9` and S3K
`CFBF98C36C776677290A872547AC47C53D2761D6`.

- [ ] **Step 2: Build the fully qualified level-trace selector**

```powershell
$files = rg -l 'class Test.*TraceReplay extends' src/test/java/com/openggf/tests/trace/s2 src/test/java/com/openggf/tests/trace/s3k |
    Where-Object { $_ -notmatch 'SpecialStage' }
$tests = $files | ForEach-Object {
    $text = Get-Content -Raw $_
    $package = [regex]::Match($text, 'package\s+([^;]+);').Groups[1].Value
    $class = [System.IO.Path]::GetFileNameWithoutExtension($_)
    "$package.$class"
}
if ($tests.Count -lt 2) { throw 'S2/S3K trace selector is unexpectedly empty' }
$selector = $tests -join ','
```

- [ ] **Step 3: Run and preserve the baseline manifest**

```powershell
$commandFile = 'target/rewind-closure-baseline/commands.txt'
if (-not (Test-Path $commandFile)) {
    throw "Missing preserved exact sweep command list: $commandFile"
}
mvn -Dmse=off -DskipTests test-compile
Get-Content $commandFile | Select-Object -Skip 1 |
    ForEach-Object {
        # Execute each preserved command as its own process, in order. Each line
        # selects one class and uses forkCount=1, -Xmx2g, and surefire:test.
        Invoke-Expression $_
        if ($LASTEXITCODE -ne 0) { throw "Infrastructure failure: $_" }
    }
```

The empirically preserved `commands.txt` contains the precompile command as its
first line and the 29 exact class-scoped commands after it. Collect each
invocation's XML/log under the baseline artifact directory. Generate schema-v2
`manifest.json` as the sweep wrapper and the authoritative, top-level sorted
method oracle as `method-status.json`:

```powershell
$rows = foreach ($file in Get-ChildItem target/rewind-closure-baseline/TEST-*.xml) {
    [xml]$xml = Get-Content -Raw $file
    foreach ($case in $xml.testsuite.testcase) {
        $status = if ($case.failure) { 'failure' } elseif ($case.error) { 'error' } `
                  elseif ($case.skipped) { 'skipped' } else { 'passed' }
        [pscustomobject]@{ class = [string]$case.classname; method = [string]$case.name; status = $status }
    }
}
$rows | Sort-Object class,method | ConvertTo-Json -Depth 3 |
    Set-Content target/rewind-closure-baseline/method-status.json
foreach ($test in $tests) {
    if (-not ($rows.class -contains $test)) { throw "Selected trace class did not execute: $test" }
}
if ($rows.status -contains 'skipped') { throw 'Unexpected skipped trace with ROM/payload prerequisites present' }
git rev-parse HEAD | Set-Content target/rewind-closure-baseline/sha.txt
```

Any existing failure is baseline debt, not a guard regression.

### Task 1: Put identity requirements on production codecs

**Files:**
- Modify: `src/main/java/com/openggf/game/rewind/schema/RewindCodec.java`
- Modify: `src/main/java/com/openggf/game/rewind/schema/RewindCodecs.java`
- Modify: `src/main/java/com/openggf/game/rewind/schema/CompactFieldCapturer.java`
- Create: `src/test/java/com/openggf/game/rewind/schema/TestRewindReferenceClosureValidation.java`

- [ ] **Step 1: Write failing codec-metadata parity tests**

Use one exact owner fixture (with a no-op `appendRenderCommands`) and register
CAPTURED policies for `targets`, `targetKeys`, `targetValues`, and `state` before
the schema is first requested:

```java
private static final ObjectSpawn OWNER_SPAWN =
        new ObjectSpawn(0x100, 0x120, 1, 0, 0, false, 7);

private static final class ReferenceOwner extends AbstractObjectInstance {
    ObjectInstance direct;
    ObjectInstance[] array;
    List<ObjectInstance> targets;
    Map<ObjectInstance, Integer> targetKeys;
    Map<Integer, ObjectInstance> targetValues;
    ReferenceState state;
    PlayableEntity player;
    @RewindTransient(reason = "fixture exclusion") ObjectInstance transientTarget;
    @RewindDeferred(reason = "fixture deferred relink") ObjectInstance deferredTarget;

    ReferenceOwner(ObjectInstance target) {
        super(OWNER_SPAWN, "ReferenceOwner");
        direct = target;
        array = new ObjectInstance[]{target};
        targets = new ArrayList<>(List.of(target));
        targetKeys = new LinkedHashMap<>(Map.of(target, 1));
        targetValues = new LinkedHashMap<>(Map.of(1, target));
        state = new ReferenceState(target);
    }

    @Override public void appendRenderCommands(List<GLCommand> commands) {}
}

private static final class ReferenceState {
    ObjectInstance target;
    ReferenceState() {}
    ReferenceState(ObjectInstance target) { this.target = target; }
}
```

Register collection/state policies with
`RewindPolicyRegistry.registerFieldPolicy(FieldKey.of(field), CAPTURED)` and
call `RewindSchemaRegistry.clearForTest()` in teardown so cached schemas cannot
leak between fixtures. For each shape, isolate only that field as non-null and
assert:

```java
RewindCodec codec = RewindCodecs.codecFor(field).orElseThrow();
assertTrue(codec.requiresIdentityTable());
RewindCaptureContext missingContext = RewindCaptureContext.withIdentityTable(
        new RewindIdentityTable());
ObjectRefId ownerId = ObjectRefId.dynamic(12, 0, 7);
owner.setSlotIndex(12);
missingContext.requireIdentityTable().registerObject(owner, ownerId);

IllegalStateException fullCapture = assertThrows(IllegalStateException.class,
        () -> CompactFieldCapturer.captureDefaultObjectSubclassScalars(owner, missingContext));
IllegalStateException focused = assertThrows(IllegalStateException.class,
        () -> CompactFieldCapturer.validateDefaultObjectSubclassReferenceClosure(owner, missingContext));
assertDiagnostic(fullCapture, field, ownerId);
assertDiagnostic(focused, field, ownerId);
```

`assertDiagnostic` must assert owner class, `FieldKey`, id, slot, spawn, cause
type, and cause text for both paths:

```java
private static void assertDiagnostic(
        IllegalStateException failure, Field field, ObjectRefId ownerId) {
    assertAll(
            () -> assertTrue(failure.getMessage().contains(FieldKey.of(field).toString())),
            () -> assertTrue(failure.getMessage().contains(ReferenceOwner.class.getName())),
            () -> assertTrue(failure.getMessage().contains(ownerId.toString())),
            () -> assertTrue(failure.getMessage().contains("slot=12")),
            () -> assertTrue(failure.getMessage().contains(OWNER_SPAWN.toString())),
            () -> assertInstanceOf(IllegalStateException.class, failure.getCause()),
            () -> assertTrue(failure.getCause().getMessage().contains(
                    "RewindIdentityTable has no registered id for object reference")));
}
```

Also assert scalar/String/enum/record-value codecs return `false`, transient and
deferred target fields are absent from validation, and both full and focused
paths pass once the target is registered. Name the hard-failure test
`missingReferenceCannotBeBaselined`; it invokes the validator directly and
proves no coverage-baseline input participates.

Add a player fixture. `PlayerReferenceCodec.requiresIdentityTable()` must be
true; `RewindCaptureContext.none()` must fail for a non-null player, a registered
player must pass, and an unregistered player in an otherwise present identity
table must follow the existing codec contract by encoding as null rather than
throwing.

- [ ] **Step 2: Run the new tests and verify RED**

Run:

```powershell
mvn "-Dtest=com.openggf.game.rewind.schema.TestRewindReferenceClosureValidation" test
```

Expected: compilation fails because `RewindCodec.requiresIdentityTable()` and
`CompactFieldCapturer.validateDefaultObjectSubclassReferenceClosure(...)` do not
exist.

- [ ] **Step 3: Add codec-owned identity metadata**

Add the default contract:

```java
default boolean requiresIdentityTable() {
    return false;
}
```

Override it in `PlayerReferenceCodec`, `ObjectReferenceCodec`, `ArrayCodec`,
`CollectionCodec`, `MapCodec`, and `PlainStateHolderCodec`. Arrays and plain
holders recurse through their selected child codecs. Collection/map metadata
must follow the actual element serializer: object/player refs, supported arrays,
and supported plain holders recurse; `RewindStateful` and ordinary value/opaque
paths return false even when `codecFor(elementType)` is empty. Do not blindly
`orElseThrow()` for a `RewindStateful` element. Rewrite
`RewindCodecs.requiresIdentityTable(Field)` as:

```java
public static boolean requiresIdentityTable(Field field) {
    Objects.requireNonNull(field, "field");
    return codecFor(field).map(RewindCodec::requiresIdentityTable).orElse(false);
}
```

Delete only the superseded private recursive
`requiresIdentityTable(Class<?>)` classifier after confirming no callers remain.
Keep `collectionCodecUsesIdentityReferences(Field)`, which
`GenericFieldCapturer` still uses.

- [ ] **Step 4: Add focused validation and contextual failures**

Add:

```java
public static void validateDefaultObjectSubclassReferenceClosure(
        AbstractObjectInstance target,
        RewindCaptureContext context)
```

It must load the same default object-subclass schema, validate support, iterate
only captured plans whose codec reports `requiresIdentityTable()`, and invoke
the same codec capture method as full compact capture with thread-local scratch.

Factor codec dispatch in `captureWithSchema` through one helper that catches an
identity-bearing field failure and throws a new `IllegalStateException` whose
message contains `FieldKey`, owner class, owner rewind id (when registered),
slot, and spawn. Preserve the codec exception as `cause`.

- [ ] **Step 5: Run codec/validator tests and existing codec regression tests**

Run:

```powershell
mvn "-Dtest=com.openggf.game.rewind.schema.TestRewindReferenceClosureValidation,com.openggf.game.rewind.schema.TestRewindObjectReferenceCodecs,com.openggf.game.rewind.schema.TestRewindCollectionCodecs,com.openggf.game.rewind.schema.TestRewindHelperCodecs" test
```

Expected: PASS; direct and nested missing identities fail identically, while
registered references pass.

- [ ] **Step 6: Commit Task 1**

Commit the four Task-1 files with subject `feat(rewind): validate compact reference closure` and the required documentation trailers. Use `Changelog: n/a: infrastructure slice; changelog lands with integration`.

### Task 2: Mirror real ObjectManager compact-capture membership

**Files:**
- Modify: `src/main/java/com/openggf/game/rewind/GenericRewindEligibility.java`
- Modify: `src/main/java/com/openggf/level/objects/AbstractObjectInstance.java`
- Modify: `src/main/java/com/openggf/level/objects/AbstractBadnikInstance.java`
- Modify: `src/main/java/com/openggf/level/objects/ObjectManager.java`
- Create: `src/test/java/com/openggf/level/objects/TestObjectManagerRewindReferenceClosure.java`

- [ ] **Step 1: Write failing membership tests**

Copy the minimal `TrackingRegistry`/`makeManager` pattern from
`TestObjectManagerRewindSnapshot`: an `ObjectRegistry` whose `create(spawn)`
returns a `CompactOwner`, `objectSlotLayout()` returns `SONIC_2`, and a placed
spawn is materialized by `manager.reset(0); manager.update(0, null, null, 1)`.
Use these exact fixture roles:

```java
private static class CompactOwner extends AbstractObjectInstance {
    ObjectInstance target;
    int counter = 3;
    CompactOwner(ObjectSpawn spawn, ObjectInstance target) {
        super(spawn, "CompactOwner");
        this.target = target;
    }
    @Override public void appendRenderCommands(List<GLCommand> commands) {}
}

private static final class CustomCaptureOwner extends CompactOwner {
    CustomCaptureOwner(ObjectSpawn spawn, ObjectInstance target) {
        super(spawn, target);
    }
    @Override public PerObjectRewindSnapshot captureRewindState() {
        return super.captureRewindState();
    }
}

private static final class UnsupportedCompactOwner extends AbstractObjectInstance {
    MutableFixtureValue state = new MutableFixtureValue();
    UnsupportedCompactOwner(ObjectSpawn spawn) { super(spawn, "Unsupported"); }
    @Override public void appendRenderCommands(List<GLCommand> commands) {}
}

private static final class MutableFixtureValue implements RewindStateful<Integer> {
    int value = 9;
    @Override public Integer captureRewindStateValue() { return value; }
    @Override public void restoreRewindStateValue(Integer state) { value = state; }
}
```

`MutableFixtureValue` implements `RewindStateful`, giving generic capture a real
field while `CompactFieldCapturer.supportsDefaultObjectSubclassScalars(...)`
returns false. Create a real `ObjectManager` fixture with:

1. a default compact owner pointing at an unmanaged target — validation fails;
2. separate placed and ordinary non-auxiliary dynamic owners pointing at a
   normally registered target — both validation paths pass;
3. a custom-capture owner with a stale field — validation ignores it;
4. an auxiliary dynamic owner with a stale field — validation ignores it as an
   owner; a normal owner pointing at an auxiliary-only target fails because
   auxiliary objects intentionally receive no rewind id and cannot be restored;
5. `UnsupportedCompactOwner` — the shared predicate is false and a real
   `rewindSnapshottable().capture()` entry has non-null `genericState()` and null
   `compactGenericState()`;
6. a default badnik compact owner — validation uses the badnik predicate.

The primary assertion is:

```java
assertThrows(IllegalStateException.class,
        objectManager::validateRewindReferenceClosure);
```

and all exclusion fixtures use `assertDoesNotThrow`.

- [ ] **Step 2: Run the membership tests and verify RED**

Run:

```powershell
mvn "-Dtest=com.openggf.level.objects.TestObjectManagerRewindReferenceClosure" test
```

Expected: compilation fails because the shared compact predicate and manager
validation method do not exist.

- [ ] **Step 3: Centralize the exact compact-route predicate**

Add:

```java
public static boolean usesCompactDefaultSubclassCapture(Class<?> type) {
    boolean defaultEligible = AbstractBadnikInstance.class.isAssignableFrom(type)
            ? usesDefaultBadnikSubclassCapture(type)
            : usesDefaultObjectSubclassCapture(type);
    return defaultEligible
            && CompactFieldCapturer.supportsDefaultObjectSubclassScalars(type);
}
```

Update normal-object and badnik capture to use this helper for the compact branch
while preserving their existing generic fallback when default capture is
eligible but compact capture is unsupported.

- [ ] **Step 4: Implement exact ObjectManager closure validation**

Add `validateRewindReferenceClosure()` which:

```java
RewindCaptureContext context = rewindCaptureContext();
for (ObjectInstance instance : activeObjects.values()) {
    validateCompactOwner(instance, context);
}
for (ObjectInstance instance : dynamicObjects) {
    if (!auxiliaryDynamicObjects.contains(instance)) {
        validateCompactOwner(instance, context);
    }
}
```

`validateCompactOwner` accepts only `AbstractObjectInstance` instances for which
`usesCompactDefaultSubclassCapture(type)` is true, then delegates to the focused
`CompactFieldCapturer` operation. Do not prune or mutate ids/objects.

- [ ] **Step 5: Run membership, snapshot, and disposition tests**

Run:

```powershell
mvn "-Dtest=com.openggf.level.objects.TestObjectManagerRewindReferenceClosure,com.openggf.level.objects.TestObjectManagerRewindSnapshot,com.openggf.game.rewind.schema.TestRewindFieldDispositionGuard,com.openggf.game.rewind.coverage.TestRewindCoverageGuard" test
```

Expected: PASS with no new baseline entry.

- [ ] **Step 6: Commit Task 2**

Commit the five Task-2 files with subject `feat(rewind): guard live object reference closure` and required trailers. Keep `Changelog: n/a: infrastructure slice; changelog lands with integration`.

### Task 3: Repair the MHZ1 stale back-reference

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/Mhz1CutsceneButtonInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/CutsceneKnucklesMhz1Instance.java`
- Create: `src/test/java/com/openggf/game/sonic3k/objects/TestMhz1CutsceneReferenceClosure.java`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Write the exact failing lifecycle test**

Build the harness with the same concrete ingredients as
`TestS3kMhzCutsceneGraphRewind.Harness`: an `ObjectManager[]` holder,
`TestCamera`, `StubObjectServices` overriding `objectManager()`, `camera()`,
`romZoneId()`, and `featureZoneId()`, an installed `MhzZoneRuntimeState`, and a
small `Sonic3kObjectRegistry` override returning `ZONE_MHZ`. Use an empty placed
spawn list so the test is independent of camera spawn-window behavior.

Create both objects through `objectManager.createDynamicObject(...)` using the
package-private two-argument actor constructor. Explicitly establish both sides
of the edge—the constructor only establishes actor → button:

```java
Mhz1CutsceneButtonInstance button = objectManager.createDynamicObject(
        () -> new Mhz1CutsceneButtonInstance(BUTTON_SPAWN));
CutsceneKnucklesMhz1Instance actor = objectManager.createDynamicObject(
        () -> new CutsceneKnucklesMhz1Instance(ACTOR_SPAWN, button));
setObjectField(button, "spawnedKnuckles", actor);
setEnumField(actor, "routine", "EXIT");
Object motion = readObjectField(actor, "motion");
setIntField(motion, "x", -0x1000);
```

Create a `TestablePlayableSprite` centered inside the camera and drive one
manager frame so `update()` calls `setDestroyed(true)` and the manager calls
`onUnload()` and removes the actor. Assert the actor is absent and then call
closure validation:

```java
objectManager.update(0, player, List.of(), 0, false);
assertFalse(objectManager.getActiveObjects().contains(actor));
assertDoesNotThrow(objectManager::validateRewindReferenceClosure);
assertNull(readObjectField(button, "spawnedKnuckles"));
```

Before the production fix, the final two assertions must fail with the reported
`RewindIdentityTable has no registered id` cause.

- [ ] **Step 2: Run the MHZ test and verify RED**

Run:

```powershell
mvn "-Dtest=com.openggf.game.sonic3k.objects.TestMhz1CutsceneReferenceClosure" test
```

Expected: FAIL because the removed actor remains in
`Mhz1CutsceneButtonInstance.spawnedKnuckles`.

- [ ] **Step 3: Add conditional lifecycle detachment**

Add to the button:

```java
void detachSpawnedKnuckles(CutsceneKnucklesMhz1Instance actor) {
    if (spawnedKnuckles == actor) {
        spawnedKnuckles = null;
    }
}
```

Add to the actor:

```java
@Override
public void onUnload() {
    if (parentButton != null) {
        parentButton.detachSpawnedKnuckles(this);
    }
}
```

Do not mark either captured live link transient; live capture/restore must retain
the exact bidirectional graph.

- [ ] **Step 4: Run MHZ and graph restore tests**

Run:

```powershell
mvn "-Dtest=com.openggf.game.sonic3k.objects.TestMhz1CutsceneReferenceClosure,com.openggf.game.sonic3k.objects.TestS3kMhzCutsceneGraphRewind" test
```

Expected: the new lifecycle test passes. If the older graph class still has its
known spawn-window fixture failures on clean `develop`, record them separately;
the new test and closure behavior must remain green.

- [ ] **Step 5: Commit Task 3**

Add an Unreleased changelog entry for the MHZ1 stale-reference crash, then
commit the four Task-3 files with subject
`fix(rewind): detach unloaded MHZ1 Knuckles actor`. Use `Changelog: updated`.

### Task 4: Enforce closure after every real trace level frame

**Files:**
- Create: `src/test/java/com/openggf/tests/trace/TraceReplayFrameClosureDriver.java`
- Modify: `src/test/java/com/openggf/tests/trace/AbstractTraceReplayTest.java`
- Create: `src/test/java/com/openggf/tests/trace/TestTraceReplayReferenceClosureGuard.java`

- [ ] **Step 1: Write failing trace-guard tests**

Create a package-private test-source driver with two explicit entry points used
by the two production replay branches:

```java
final class TraceReplayFrameClosureDriver {
    static int driveGeneral(
            TraceExecutionPhase phase,
            IntSupplier step,
            IntSupplier skip,
            Runnable validateAfterStep) {
        if (phase == TraceExecutionPhase.VBLANK_ONLY) {
            return skip.getAsInt();
        }
        int input = step.getAsInt();
        validateAfterStep.run();
        return input;
    }

    static int driveS3k(
            TraceExecutionPhase phase,
            boolean usePreviousInput,
            IntSupplier step,
            IntSupplier stepUsingPreviousInput,
            IntSupplier skip,
            Runnable validateAfterStep) {
        if (phase == TraceExecutionPhase.VBLANK_ONLY) {
            return skip.getAsInt();
        }
        int input = (usePreviousInput ? stepUsingPreviousInput : step).getAsInt();
        validateAfterStep.run();
        return input;
    }
}
```

Add a package-private `validateCurrentObjectManager(...)` operation to this
driver. It accepts `Supplier<ObjectManager>` plus game/zone/act/index/frame/phase
context. Production passes a supplier that calls `GameServices.levelOrNull()`
and `level.getObjectManager()` every time; it must never capture the manager held
near the start of `replayMatchesTrace`, because act transitions replace it.

Use recording lambdas in tests to assert exact runtime order:

```java
List<String> events = new ArrayList<>();
int input = TraceReplayFrameClosureDriver.driveGeneral(
        TraceExecutionPhase.FULL_LEVEL_FRAME,
        () -> { events.add("step"); return 7; },
        () -> { events.add("skip"); return 9; },
        () -> events.add("validate"));
events.add("compare");
assertEquals(List.of("step", "validate", "compare"), events);
assertEquals(7, input);
```

Repeat through `driveS3k` for both ordinary and previous-input steps. A
`VBLANK_ONLY` test must produce only `skip`, with zero validations. Use a
`CountingObjectManager extends ObjectManager` overriding
`validateRewindReferenceClosure()` and an `AtomicReference<ObjectManager>`
supplier; swap from manager A to B between calls and assert B is validated on the
second call. Also test null supplier results and contextual exception wrapping
with the original closure exception as cause.

To prove the production loops are wired—not merely the driver—add this protected
observer to `AbstractTraceReplayTest` and call it only after successful closure
validation:

```java
protected void onRewindReferenceClosureValidated(
        int traceIndex, TraceFrame frame, TraceExecutionPhase phase) {
    // test observer hook
}
```

In `TestTraceReplayReferenceClosureGuard.java`, add two package-private top-level
integration classes named `TestS2ReplayReferenceClosureIntegration` and
`TestS3kReplayReferenceClosureIntegration`. They extend
`TestS2Ehz1TraceReplay` and `TestS3kAizCompleteRunTraceReplay`, override the observer to
increment a counter, and override `replayMatchesTrace()` with `@Test` to call
`super.replayMatchesTrace()` then assert the counter is positive. The S2 class
exercises the general loop; the S3K class exercises `replayS3kTrace`. The driver
unit tests pin zero observer calls for VBlank-only rows and validation-before-
comparison ordering.

- [ ] **Step 2: Run the trace-guard tests and verify RED**

Run:

```powershell
mvn "-Ds2.rom.path=s2.gen" "-Ds3k.rom.path=s3k.gen" "-Dtest=com.openggf.tests.trace.TestTraceReplayReferenceClosureGuard,com.openggf.tests.trace.TestS2ReplayReferenceClosureIntegration#replayMatchesTrace,com.openggf.tests.trace.TestS3kReplayReferenceClosureIntegration#replayMatchesTrace" test
```

Expected: compilation failure because `TraceReplayFrameClosureDriver` does not
exist.

- [ ] **Step 3: Implement the shared drivers and dynamic manager validation**

The dynamic validator performs a null-safe lookup and wraps failures:

```java
static void validateCurrentObjectManager(
        Supplier<ObjectManager> currentManager,
        String game,
        String zone,
        int act,
        int traceIndex,
        TraceFrame frame,
        TraceExecutionPhase phase) {
    ObjectManager objectManager = currentManager.get();
    if (objectManager == null) {
        return;
    }
    try {
        objectManager.validateRewindReferenceClosure();
    } catch (IllegalStateException cause) {
        throw new IllegalStateException(
                "Invalid rewind reference closure after " + game
                        + " zone=" + zone + " act=" + act
                        + " traceIndex=" + traceIndex + " romFrame=" + frame.frame()
                        + " phase=" + phase,
                cause);
    }
}
```

Replace the frame-step conditional in the general loop with `driveGeneral` and
the corresponding conditional in `replayS3kTrace` with `driveS3k`. Each passes a
validation lambda whose manager supplier dynamically evaluates:

```java
() -> {
    var level = GameServices.levelOrNull();
    return level != null ? level.getObjectManager() : null;
}
```

This places validation after each real `fixture.stepFrameFromRecording*()` and
before the caller can compare or early-stop. Special-stage replay is untouched.
Invoke `onRewindReferenceClosureValidated(...)` from the validation lambda so
the two concrete integration subclasses prove the actual loops reached it.

- [ ] **Step 4: Run trace-guard tests plus one exact-method S2/S3K smoke**

Use exact replay methods so additional class-local tests do not pollute the
smoke timing:

```powershell
mvn "-Ds2.rom.path=s2.gen" "-Ds3k.rom.path=s3k.gen" "-Dtest=com.openggf.tests.trace.TestTraceReplayReferenceClosureGuard,com.openggf.tests.trace.TestS2ReplayReferenceClosureIntegration#replayMatchesTrace,com.openggf.tests.trace.TestS3kReplayReferenceClosureIntegration#replayMatchesTrace" test
```

Expected: PASS, or a precise closure failure naming the owning field and first
invalid frame. Do not relax the guard for a failing route.

- [ ] **Step 5: Commit Task 4**

Commit the three Task-4 files with subject
`test(rewind): validate reference closure during traces` and required trailers.

### Task 5: Sweep S2/S3K, eliminate closure failures without baseline regressions, and document

**Files:**
- Modify as failures identify: the smallest owning object class and its focused graph/lifecycle test.
- Modify: `CHANGELOG.md`
- Modify: `docs/status/trace-frontier-log.md`

- [ ] **Step 1: Run the full S2/S3K level trace closure sweep**

List concrete S2/S3K level trace classes and run them with the closure guard:

```powershell
if (Test-Path target/rewind-closure-guarded) {
    Remove-Item -Recurse -Force -LiteralPath target/rewind-closure-guarded
}
New-Item -ItemType Directory -Force target/rewind-closure-guarded | Out-Null
mvn -Dmse=off -DskipTests test-compile
Get-Content target/rewind-closure-baseline/commands.txt | Select-Object -Skip 1 |
    ForEach-Object {
        # Run each exact baseline class command independently; redirect its XML
        # and log into the guarded artifact directory before the next command.
        Invoke-Expression $_
        if ($LASTEXITCODE -ne 0) { throw "Infrastructure failure: $_" }
    }
```

Expected intermediate outcome: either green or one/more failures whose nested
cause names `owner#field`, owner identity, target, and first frame.
Generate the schema-v2 `target/rewind-closure-guarded/manifest.json` wrapper and
top-level sorted `method-status.json` with the exact Task-0 XML parser, assert all
29 selected classes are represented and no method is skipped, then compare
`(class, method, status)` against the Task-0 `method-status.json`. Classify every
new failure as closure-only, physics-frontier movement, or unrelated regression.

```powershell
$baseline = Get-Content -Raw target/rewind-closure-baseline/method-status.json | ConvertFrom-Json
$guarded = Get-Content -Raw target/rewind-closure-guarded/method-status.json | ConvertFrom-Json
$baselineByKey = @{}; $baseline | ForEach-Object { $baselineByKey["$($_.class)#$($_.method)"] = $_.status }
$regressions = foreach ($row in $guarded) {
    $key = "$($row.class)#$($row.method)"
    if (-not $baselineByKey.ContainsKey($key) -or
            ($baselineByKey[$key] -eq 'passed' -and $row.status -ne 'passed')) {
        $row
    }
}
$guardedKeys = @{}; $guarded | ForEach-Object { $guardedKeys["$($_.class)#$($_.method)"] = $_.status }
foreach ($row in $baseline) {
    $key = "$($row.class)#$($row.method)"
    if ($row.status -eq 'passed' -and
            (-not $guardedKeys.ContainsKey($key) -or $guardedKeys[$key] -ne 'passed')) {
        $regressions += $row
    }
}
if ($regressions) {
    $regressions | Format-Table | Out-String | Set-Content target/rewind-closure-guarded/regressions.txt
    throw 'Guarded trace manifest regressed versus baseline; see regressions.txt'
}
```

Closure failures enter the TDD repair loop; unrelated status regressions block
the task immediately.

- [ ] **Step 2: Apply the per-failure TDD loop until closure is clean and status is non-regressed**

For each unique `owner#field` failure, in first-frame order:

1. Reproduce on guarded HEAD and check Task-0/base results to establish whether
   the route was previously green.
2. Inspect the exact field policy, every assignment/producer, every target
   unload/removal path, and the owner's recreate/restore relink path. For a
   game-modeled object, read the corresponding disassembly lifetime/ownership
   routine before deciding the invariant.
3. Add two focused tests: a forward-lifecycle test that drives the real removal
   path and a capture/restore graph test that proves the required live graph.
4. Run both and observe RED for the lifecycle defect without weakening the live
   graph assertion.
5. Only when the target is proven independently unloadable, add conditional
   detach at the child/parent lifecycle boundary. Only when the field is proven
   derived structural state, mark that exact field transient and prove
   `afterRewindRestoreSettled()` relinks it.
6. If neither invariant is proven, stop that edge and return it to design review;
   do not guess, mark transient, or continue committing speculative repairs.
7. Run the focused tests, existing graph test,
   `TestRewindFieldDispositionGuard`,
   `TestCapturedPolicyCompactReachabilityGuard`, and
   `TestRewindCoverageGuard`, then rerun the complete guarded S2/S3K sweep.

Do not add game/zone/frame/route checks, missing-reference-to-null behavior, or
baseline entries. The terminal condition is zero closure failures and no status
regression versus the Task-0 manifest; pre-existing baseline physics failures do
not prevent completion. Commit each independent owner graph separately with a
`fix(rewind): ...` subject and required trailers before moving to the next edge.

- [ ] **Step 3: Measure representative trace overhead reproducibly**

Determine the longest S2 and S3K fixtures by parsing every `metadata.json`
`trace_frame_count`, then map each winning trace directory to its concrete test
class. Record the exact baseline SHA from Task 0 and guarded HEAD SHA.

Build candidates only from Task-0 selected source files:

```powershell
$files = rg -l 'class Test.*TraceReplay extends' src/test/java/com/openggf/tests/trace/s2 src/test/java/com/openggf/tests/trace/s3k |
    Where-Object { $_ -notmatch 'SpecialStage' }
$candidates = foreach ($file in $files) {
    $text = Get-Content -Raw $file
    $package = [regex]::Match($text, 'package\s+([^;]+);').Groups[1].Value
    $class = [System.IO.Path]::GetFileNameWithoutExtension($file)
    $pathMatch = [regex]::Match($text, 'Path\.of\("([^"]+)"\)')
    $routeMatch = [regex]::Match($text, 'super\("([^"]+)"')
    $traceDir = if ($pathMatch.Success) { $pathMatch.Groups[1].Value } `
                elseif ($routeMatch.Success) { "src/test/resources/traces/s2/$($routeMatch.Groups[1].Value)" } `
                else { throw "Cannot resolve trace directory from $file" }
    $meta = Get-Content -Raw (Join-Path $traceDir 'metadata.json') | ConvertFrom-Json
    [pscustomobject]@{
        game = if ($file -match '[\\/]s2[\\/]') { 's2' } else { 's3k' }
        test = "$package.$class#replayMatchesTrace"
        traceDir = $traceDir
        frames = [int]$meta.trace_frame_count
    }
}
$baselineRows = Get-Content -Raw target/rewind-closure-baseline/method-status.json | ConvertFrom-Json
$guardedRows = Get-Content -Raw target/rewind-closure-guarded/method-status.json | ConvertFrom-Json
$baselineStatus = @{}; $baselineRows | ForEach-Object { $baselineStatus["$($_.class)#$($_.method)"] = $_.status }
$guardedStatus = @{}; $guardedRows | ForEach-Object { $guardedStatus["$($_.class)#$($_.method)"] = $_.status }
$timed = $candidates | Group-Object game | ForEach-Object {
    $passing = $_.Group | Where-Object {
        $baselineStatus[$_.test] -eq 'passed' -and $guardedStatus[$_.test] -eq 'passed'
    } | Sort-Object frames -Descending
    if ($passing) { $passing | Select-Object -First 1 }
    else {
        $stable = $_.Group | Where-Object {
            $baselineStatus.ContainsKey($_.test) -and
            $baselineStatus[$_.test] -eq $guardedStatus[$_.test]
        } | Sort-Object frames -Descending
        if (-not $stable) { throw "No status-stable timing route for $($_.Name)" }
        $stable | Select-Object -First 1
    }
}
$timed | ConvertTo-Json | Set-Content target/rewind-closure-timing-targets.json
```

Create two temporary clean timing worktrees from those SHAs. Confirm identical
`java -version`, Maven version, ROM hashes, `config.yaml`, and trace resources.
In each worktree run `mvn -DskipTests package` once and one unmeasured warm replay.
Then measure the exact `FullyQualifiedClass#replayMatchesTrace` selector five
times with `-Dmse=off`, explicit ROM properties, and PowerShell
`Measure-Command`. Record all samples, median, and min/max spread in final review
notes. A guarded median that is both more than 10% and more than one second slower
triggers profiling/optimization; mandatory trace coverage remains enabled.

Use detached timing worktrees so the delivery branch is untouched:

```powershell
$baselineSha = (Get-Content target/rewind-closure-baseline/sha.txt).Trim()
$guardSha = (git rev-parse HEAD).Trim()
$s2Rom = (Resolve-Path s2.gen).Path
$s3kRom = (Resolve-Path s3k.gen).Path
$config = (Resolve-Path config.yaml).Path
$parent = Split-Path (git rev-parse --show-toplevel) -Parent
$baseWt = Join-Path $parent 'sonic-engine-rewind-timing-base'
$guardWt = Join-Path $parent 'sonic-engine-rewind-timing-guard'
function Measure-Trace([string]$worktree, [string]$sha, [object]$target) {
    Push-Location $worktree
    try {
        Copy-Item -Force $config (Join-Path $worktree 'config.yaml')
        mvn '-Dmse=off' '-DskipTests' package | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Timing compile failed at $sha" }
        $romArgs = @("-Ds2.rom.path=$s2Rom", "-Ds3k.rom.path=$s3kRom")
        $expectedStatus = $baselineStatus[$target.test]
        $ignore = if ($expectedStatus -eq 'passed') { @() } else { @('-Dmaven.test.failure.ignore=true') }
        mvn '-Dmse=off' @romArgs @ignore "-Dtest=$($target.test)" test | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Timing warm-up infrastructure failed: $($target.test)" }
        $samples = 1..5 | ForEach-Object {
            $elapsed = Measure-Command {
                mvn '-Dmse=off' @romArgs @ignore "-Dtest=$($target.test)" test | Out-Null
                if ($LASTEXITCODE -ne 0) { throw "Timing replay infrastructure failed: $($target.test)" }
            }
            $elapsed.TotalSeconds
        }
        $sorted = $samples | Sort-Object
        [pscustomobject]@{
            sha=$sha; game=$target.game; test=$target.test; frames=$target.frames
            samples=$samples; median=$sorted[2]; min=$sorted[0]; max=$sorted[4]
        }
    } finally { Pop-Location }
}

try {
    git worktree prune
    foreach ($path in @($baseWt, $guardWt)) {
        if (Test-Path $path) { throw "Refusing stale timing directory: $path" }
    }
    git worktree add --detach $baseWt $baselineSha
    if ($LASTEXITCODE -ne 0) { throw 'Failed to create baseline timing worktree' }
    git worktree add --detach $guardWt $guardSha
    if ($LASTEXITCODE -ne 0) { throw 'Failed to create guarded timing worktree' }
    $results = foreach ($target in $timed) {
        Measure-Trace $baseWt $baselineSha $target
        Measure-Trace $guardWt $guardSha $target
    }
    $results | ConvertTo-Json -Depth 4 | Set-Content target/rewind-closure-timing.json
} finally {
    if (Test-Path $baseWt) { git worktree remove --force $baseWt }
    if (Test-Path $guardWt) { git worktree remove --force $guardWt }
}
```

For each game compute `guardMedian - baseMedian` and percentage. More than 10%
and more than one second triggers profiling before the task can proceed.

- [ ] **Step 4: Update changelog and trace frontier log**

Extend the Unreleased MHZ1 changelog entry to explain that trace replay now
rejects dangling captured object references and that all sweep-discovered owners
detach or relink correctly.

Because this full sweep selects closure-repair targets, add a
`docs/status/trace-frontier-log.md` entry with the exact sweep command, branch commit,
pass/fail/skip counts, and whether any previously passing trace changed its
physics frontier. Closure-only failures are recorded separately from physics
comparison errors.

- [ ] **Step 5: Run the full verification gate**

Run:

```powershell
mvn "-Ds2.rom.path=s2.gen" "-Ds3k.rom.path=s3k.gen" "-Dtest=com.openggf.game.rewind.schema.TestRewindReferenceClosureValidation,com.openggf.level.objects.TestObjectManagerRewindReferenceClosure,com.openggf.game.sonic3k.objects.TestMhz1CutsceneReferenceClosure,com.openggf.game.sonic3k.objects.TestS3kMhzCutsceneGraphRewind,com.openggf.tests.trace.TestTraceReplayReferenceClosureGuard,com.openggf.tests.trace.TestS2ReplayReferenceClosureIntegration#replayMatchesTrace,com.openggf.tests.trace.TestS3kReplayReferenceClosureIntegration#replayMatchesTrace,com.openggf.game.rewind.schema.TestRewindFieldDispositionGuard,com.openggf.game.rewind.schema.TestCapturedPolicyCompactReachabilityGuard,com.openggf.game.rewind.coverage.TestRewindCoverageGuard,com.openggf.tests.TestS3kAiz1SkipHeadless,com.openggf.tests.TestSonic3kLevelLoading,com.openggf.tests.TestSonic3kBootstrapResolver,com.openggf.tests.TestSonic3kDecodingUtils" test
mvn "-Dtest=*TraceReplay" "-DfailIfNoTests=false" test
```

Expected: zero new closure failures, zero coverage/disposition regressions, and
all previously green traces remain green. If baseline `develop` failures remain,
report them with their pre-change reproduction rather than claiming full-suite
green.

- [ ] **Step 6: Commit documentation and final fixes**

Commit `CHANGELOG.md`, `docs/status/trace-frontier-log.md`, and any final focused fixes
with subject `fix(rewind): enforce live reference closure`. Fill every branch
policy trailer accurately; use `Changelog: updated` and do not bypass hooks.

### Task 6: Final independent review

**Files:**
- Review all changes from the design commit through branch HEAD.

- [ ] **Step 1: Request spec-compliance review**

Provide the complete design, this plan, base SHA, head SHA, and verification
output to a fresh reviewer. Fix every Critical/Important gap and re-request
review until approved.

- [ ] **Step 2: Request code-quality review**

After spec approval, request a separate code-quality review focused on schema
parity, exception causality, snapshot membership, test false positives, trace
cost, and object lifecycle correctness. Fix and re-review until approved.

- [ ] **Step 3: Re-run verification after the final review fix**

First repeat Task 5 Step 1 with the exact fully qualified selector: clear
Surefire reports, run the complete selector with explicit ROMs and failure-ignore
so every method executes, regenerate the guarded JSON manifest, and compare it
to Task 0. Require zero closure failures, zero unexpected skips, and no new
method-status regression. Then repeat Task 5 Step 5, including the broader
`*TraceReplay` gate. Inspect `git diff --check` and `git status --short` before
making any completion claim.

- [ ] **Step 4: Commit review fixes and publish the branch**

If review fixes remain uncommitted, commit them with accurate trailers. Confirm
the branch is `bugfix/ai-rewind-reference-closure`, push that same branch, and
open a draft pull request whose body summarizes the introducing commits, strict
closure invariant, sweep findings, focused tests, baseline comparison, and
performance measurements. Do not create or switch to a second delivery branch.
