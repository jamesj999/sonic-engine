# Solid-Ordering ROM Accuracy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the shared batched solid-contact model with runtime-owned per-object solid checkpoints that make S1, S2, and S3K object ordering more ROM-accurate without adding new object-specific hacks.

**Architecture:** Introduce a runtime-owned solid execution registry that tracks the currently executing object, previous-frame standing history, and the latest/final checkpoint snapshots for each object that frame. `ObjectManager` remains the only place allowed to execute solid resolution; object code gets a runtime-owned `ObjectSolidExecutionContext` whose `resolveSolidNow` and `resolveSolidNowAll` methods execute a fresh checkpoint on every call, return that batch immediately, and publish it as the object's latest checkpoint for shared helpers and end-of-frame history. Migrate S2/S3K objects first while S1 stays on compatibility mode, then remove the S1 frame-order bridge and finally delete shared compensation shims behind explicit trace/test gates.

**Tech Stack:** Java 21, JUnit 5, Maven, existing `GameRuntime` / `ObjectServices` architecture, `HeadlessTestFixture`, `CollisionTrace`, S1 trace replay framework, bundled S1/S2/S3K disassemblies

**Out of scope:** This plan changes solid-contact ordering only. Touch-response ordering and editor-mode/thread-safety work stay unchanged here and should be handled in follow-up plans if checkpoint ordering exposes new issues there.

---

### Task 1: Seed The Inventory, Sentinel Set, And Baseline Verification Notes

**Files:**
- Create: `docs/architecture/research/2026-04-18-solid-ordering-callsite-inventory.md`

- [ ] **Step 1: Create the inventory document with the exact raw-count commands**

Add a short research file that records the raw grep commands already used in the spec and a table for deduped routine inventory.

```markdown
# Solid Ordering Call-Site Inventory

## Raw reference commands

```powershell
rg -n 'SolidObject' docs/s1disasm
rg -n 'SolidObject|SlopedSolid_SingleCharacter|PlatformObject' docs/s2disasm
rg -n 'SolidObjectFull|SolidObjectTop|SolidObjectTopSloped2' docs/skdisasm
```

## Deduped routine inventory

| Game | Routine | Helper | File | Line | Sentinel? | Notes |
| --- | --- | --- | --- | --- | --- | --- |
```
```

- [ ] **Step 2: Run the raw inventory commands and record the current counts**

Run:

```bash
powershell -Command "(rg -n 'SolidObject' docs/s1disasm | Measure-Object -Line).Lines"
powershell -Command "(rg -n 'SolidObject|SlopedSolid_SingleCharacter|PlatformObject' docs/s2disasm | Measure-Object -Line).Lines"
powershell -Command "(rg -n 'SolidObjectFull|SolidObjectTop|SolidObjectTopSloped2' docs/skdisasm | Measure-Object -Line).Lines"
```

Record the observed counts in the research file. On the current 2026-04-18 baseline they were:

```text
41
268
422
```

Do not hard-fail this task if a later checkout has drifted disassembly counts; the point is to record the current raw-reference baseline for the worker's checkout.

- [ ] **Step 3: Fill the initial deduped sentinel rows from verified local references**

Seed the table with the first migration families so future workers are not guessing the starting set.

```markdown
| S2 | ObjB2 / Tornado | SolidObject | docs/s2disasm/s2.asm | 78301 | yes | SCZ main calls SolidObject inline before follow motion |
| S2 | Obj86 / Flipper | JmpTo2_SlopedSolid | docs/s2disasm/s2.asm | 57865 | yes | Needs same-frame clear/no-contact semantics |
| S2 | Obj40 / Springboard | JmpTo_SlopedSolid_SingleCharacter | docs/s2disasm/s2.asm | 51830 | yes | Standing latch currently crosses frame seam |
| S3K | Obj05 / AIZ-LRZ Rock | SolidObjectFull | docs/skdisasm/sonic3k.asm | 43930 | yes | Engine currently compensates with pre-contact snapshot getters |
| S3K | Obj7B / Hand Launcher | SolidObjectTop | docs/skdisasm/sonic3k.asm | 59305 | yes | Captured players bypass normal rider tracking |
| S1 | Engine frame-order bridge | n/a | src/main/java/com/openggf/LevelFrameStep.java | 87 | yes | S1 runs object exec before player physics, unlike S2/S3K |
| S1 | Engine frame-order bridge | n/a | src/main/java/com/openggf/level/LevelManager.java | 1094 | yes | updateObjectPositionsWithoutTouches pre-applies velocity |
```

- [ ] **Step 4: Commit the inventory baseline**

```bash
git add docs/architecture/research/2026-04-18-solid-ordering-callsite-inventory.md
git commit -m "docs: add solid ordering callsite inventory baseline"
```

### Task 2: Add The Runtime-Owned Solid Execution Registry

**Files:**
- Create: `src/main/java/com/openggf/game/solid/ContactKind.java`
- Create: `src/main/java/com/openggf/game/solid/PlayerStandingState.java`
- Create: `src/main/java/com/openggf/game/solid/PreContactState.java`
- Create: `src/main/java/com/openggf/game/solid/PostContactState.java`
- Create: `src/main/java/com/openggf/game/solid/PlayerSolidContactResult.java`
- Create: `src/main/java/com/openggf/game/solid/SolidCheckpointBatch.java`
- Create: `src/main/java/com/openggf/game/solid/ObjectSolidExecutionContext.java`
- Create: `src/main/java/com/openggf/game/solid/SolidExecutionRegistry.java`
- Create: `src/main/java/com/openggf/game/solid/InertSolidExecutionRegistry.java`
- Create: `src/main/java/com/openggf/game/solid/DefaultSolidExecutionRegistry.java`
- Test: `src/test/java/com/openggf/game/solid/TestSolidExecutionRegistry.java`

- [ ] **Step 1: Write the failing registry tests first**

Create a focused unit test for explicit no-contact results, previous-frame promotion, and current-object context caching. Use the existing lightweight playable-sprite stub instead of inventing a new `PlayableEntity` test double.

```java
package com.openggf.game.solid;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic1.objects.TestPlayableSprite;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TestSolidExecutionRegistry {

    @Test
    void noContactResultIsExplicitAndPromotedAcrossFrames() {
        DefaultSolidExecutionRegistry registry = new DefaultSolidExecutionRegistry();
        PlayableEntity player = new TestPlayableSprite();
        ObjectInstance object = new RegistryTestObject();

        registry.beginFrame(120, List.of(player));
        registry.beginObject(object, () -> new SolidCheckpointBatch(object, Map.of(
                player, new PlayerSolidContactResult(
                        ContactKind.TOP,
                        true,
                        false,
                        false,
                        false,
                        new PreContactState((short) 0x180, (short) 0x40, true),
                        new PostContactState((short) 0, (short) 0, false, true, false)))));
        registry.currentObject().resolveSolidNow(player);
        registry.endObject(object);
        registry.finishFrame();

        PlayerStandingState previous = registry.previousStanding(object, player);
        assertEquals(ContactKind.TOP, previous.kind());
        assertTrue(previous.standing());

        registry.beginFrame(121, List.of(player));
        registry.beginObject(object, () -> new SolidCheckpointBatch(object, Map.of(
                player, PlayerSolidContactResult.noContact(
                        registry.previousStanding(object, player),
                        new PreContactState((short) 0x200, (short) 0, false),
                        new PostContactState((short) 0x200, (short) 0, true, false, false)))));
        PlayerSolidContactResult result = registry.currentObject().resolveSolidNow(player);
        registry.endObject(object);
        registry.finishFrame();

        assertEquals(ContactKind.NONE, result.kind());
        assertTrue(result.standingLastFrame());
        assertEquals(ContactKind.NONE, registry.previousStanding(object, player).kind());
        assertFalse(registry.previousStanding(object, player).standing());
    }

    @Test
    void currentObjectContextAllowsMultipleRealCheckpointsInOneObjectExecutionWindow() {
        DefaultSolidExecutionRegistry registry = new DefaultSolidExecutionRegistry();
        PlayableEntity player = new TestPlayableSprite();
        ObjectInstance object = new RegistryTestObject();
        AtomicInteger resolves = new AtomicInteger();

        assertTrue(registry.currentObject().isInert());
        registry.beginFrame(1, List.of(player));
        registry.beginObject(object, () -> {
            int pass = resolves.incrementAndGet();
            return new SolidCheckpointBatch(object, Map.of(
                    player, new PlayerSolidContactResult(
                            pass == 1 ? ContactKind.TOP : ContactKind.NONE,
                            pass == 1,
                            false,
                            false,
                            false,
                            new PreContactState((short) pass, (short) 0, false),
                            new PostContactState((short) 0, (short) 0, false, pass == 1, false))));
        });
        assertSame(object, registry.currentObject().object());
        assertEquals(ContactKind.TOP, registry.currentObject().resolveSolidNow(player).kind());
        assertEquals(ContactKind.NONE, registry.currentObject().resolveSolidNow(player).kind());
        assertEquals(2, resolves.get());
        assertEquals(ContactKind.NONE,
                registry.currentObject().lastCheckpoint().perPlayer().get(player).kind());
        registry.endObject(object);
        assertTrue(registry.currentObject().isInert());
    }

    private static final class RegistryTestObject implements ObjectInstance {
        private final ObjectSpawn spawn = new ObjectSpawn(0, 0, 0, 0, 0, false, 0);

        @Override public ObjectSpawn getSpawn() { return spawn; }
        @Override public void update(int frameCounter, PlayableEntity player) {}
        @Override public void appendRenderCommands(List<GLCommand> commands) {}
        @Override public boolean isHighPriority() { return false; }
        @Override public boolean isDestroyed() { return false; }
    }
}
```

- [ ] **Step 2: Run the new registry tests to verify they fail**

Run:

```bash
mvn "-Dtest=TestSolidExecutionRegistry" test
```

Expected: FAIL with missing `com.openggf.game.solid` classes.

- [ ] **Step 3: Add the minimal registry/data model implementation**

Create the runtime-owned data model exactly once, with explicit `NONE` contact semantics, a single current-object resolver, fresh per-call manual checkpoints, and an inert fallback implementation for object services used without an active runtime.

```java
package com.openggf.game.solid;

public enum ContactKind {
    NONE,
    TOP,
    SIDE,
    BOTTOM,
    CRUSH
}
```

```java
package com.openggf.game.solid;

public record PreContactState(
        short xSpeed,
        short ySpeed,
        boolean rolling) {

    public static final PreContactState ZERO = new PreContactState((short) 0, (short) 0, false);
}
```

```java
package com.openggf.game.solid;

public record PostContactState(
        short xSpeed,
        short ySpeed,
        boolean air,
        boolean onObject,
        boolean pushing) {

    public static final PostContactState ZERO =
            new PostContactState((short) 0, (short) 0, false, false, false);
}
```

```java
package com.openggf.game.solid;

public record PlayerStandingState(
        ContactKind kind,
        boolean standing,
        boolean pushing) {

    public static final PlayerStandingState NONE =
            new PlayerStandingState(ContactKind.NONE, false, false);
}
```

```java
package com.openggf.game.solid;

import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.ObjectInstance;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

public record SolidCheckpointBatch(
        ObjectInstance object,
        Map<PlayableEntity, PlayerSolidContactResult> perPlayer) {

    public SolidCheckpointBatch {
        IdentityHashMap<PlayableEntity, PlayerSolidContactResult> copy = new IdentityHashMap<>(perPlayer);
        perPlayer = Collections.unmodifiableMap(copy);
    }
}
```

```java
package com.openggf.game.solid;

import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.ObjectInstance;

import java.util.Collections;
import java.util.Map;

public final class ObjectSolidExecutionContext {

    @FunctionalInterface
    public interface Resolver {
        SolidCheckpointBatch resolveNow();
    }

    private static final ObjectSolidExecutionContext INERT =
            new ObjectSolidExecutionContext(null, null, null);

    private final SolidExecutionRegistry owner;
    private final ObjectInstance object;
    private final Resolver resolver;
    private SolidCheckpointBatch lastCheckpoint;

    public ObjectSolidExecutionContext(
            SolidExecutionRegistry owner,
            ObjectInstance object,
            Resolver resolver) {
        this.owner = owner;
        this.object = object;
        this.resolver = resolver;
    }

    public static ObjectSolidExecutionContext inert() {
        return INERT;
    }

    public boolean isInert() {
        return object == null;
    }

    public ObjectInstance object() {
        return object;
    }

    public SolidCheckpointBatch resolveSolidNowAll() {
        if (isInert() || resolver == null) {
            return new SolidCheckpointBatch(object, Collections.emptyMap());
        }
        lastCheckpoint = resolver.resolveNow();
        owner.publishCheckpoint(lastCheckpoint);
        return lastCheckpoint;
    }

    public PlayerSolidContactResult resolveSolidNow(PlayableEntity player) {
        return resolveSolidNowAll().perPlayer().getOrDefault(
                player,
                PlayerSolidContactResult.noContact(
                        PlayerStandingState.NONE,
                        PreContactState.ZERO,
                        PostContactState.ZERO));
    }

    public SolidCheckpointBatch lastCheckpoint() {
        return lastCheckpoint;
    }
}
```

```java
package com.openggf.game.solid;

import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.ObjectInstance;

import java.util.List;

public interface SolidExecutionRegistry {
    void beginFrame(int frameCounter, List<? extends PlayableEntity> players);
    void beginObject(ObjectInstance object, ObjectSolidExecutionContext.Resolver resolver);
    ObjectSolidExecutionContext currentObject();
    PlayerStandingState previousStanding(ObjectInstance object, PlayableEntity player);
    void publishCheckpoint(SolidCheckpointBatch batch);
    void endObject(ObjectInstance object);
    void finishFrame();
    void clearTransientState();

    static SolidExecutionRegistry inert() {
        return InertSolidExecutionRegistry.INSTANCE;
    }
}
```

```java
package com.openggf.game.solid;

import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.ObjectInstance;

import java.util.List;

public enum InertSolidExecutionRegistry implements SolidExecutionRegistry {
    INSTANCE;

    @Override public void beginFrame(int frameCounter, List<? extends PlayableEntity> players) {}
    @Override public void beginObject(ObjectInstance object, ObjectSolidExecutionContext.Resolver resolver) {}
    @Override public ObjectSolidExecutionContext currentObject() { return ObjectSolidExecutionContext.inert(); }
    @Override public PlayerStandingState previousStanding(ObjectInstance object, PlayableEntity player) { return PlayerStandingState.NONE; }
    @Override public void publishCheckpoint(SolidCheckpointBatch batch) {}
    @Override public void endObject(ObjectInstance object) {}
    @Override public void finishFrame() {}
    @Override public void clearTransientState() {}
}
```

```java
package com.openggf.game.solid;

public record PlayerSolidContactResult(
        ContactKind kind,
        boolean standingNow,
        boolean standingLastFrame,
        boolean pushingNow,
        boolean pushingLastFrame,
        PreContactState preContact,
        PostContactState postContact) {

    public static PlayerSolidContactResult noContact(
            PlayerStandingState previous,
            PreContactState preContact,
            PostContactState postContact) {
        return new PlayerSolidContactResult(
                ContactKind.NONE,
                false,
                previous.standing(),
                false,
                previous.pushing(),
                preContact,
                postContact);
    }
}
```

```java
package com.openggf.game.solid;

import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.ObjectInstance;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class DefaultSolidExecutionRegistry implements SolidExecutionRegistry {
    private final IdentityHashMap<ObjectInstance, IdentityHashMap<PlayableEntity, PlayerStandingState>> previous
            = new IdentityHashMap<>();
    private final IdentityHashMap<ObjectInstance, SolidCheckpointBatch> current = new IdentityHashMap<>();
    private ObjectSolidExecutionContext currentContext = ObjectSolidExecutionContext.inert();

    @Override
    public void beginFrame(int frameCounter, List<? extends PlayableEntity> players) {
        current.clear();
        currentContext = ObjectSolidExecutionContext.inert();
    }

    @Override
    public void beginObject(ObjectInstance object, ObjectSolidExecutionContext.Resolver resolver) {
        currentContext = new ObjectSolidExecutionContext(this, object, resolver);
    }

    @Override
    public ObjectSolidExecutionContext currentObject() {
        return currentContext;
    }

    @Override
    public PlayerStandingState previousStanding(ObjectInstance object, PlayableEntity player) {
        IdentityHashMap<PlayableEntity, PlayerStandingState> perPlayer = previous.get(object);
        return perPlayer != null ? perPlayer.getOrDefault(player, PlayerStandingState.NONE) : PlayerStandingState.NONE;
    }

    @Override
    public void publishCheckpoint(SolidCheckpointBatch batch) {
        current.put(batch.object(), batch);
    }

    @Override
    public void endObject(ObjectInstance object) {
        currentContext = ObjectSolidExecutionContext.inert();
    }

    @Override
    public void finishFrame() {
        previous.clear();
        for (Map.Entry<ObjectInstance, SolidCheckpointBatch> entry : current.entrySet()) {
            IdentityHashMap<PlayableEntity, PlayerStandingState> perPlayer = new IdentityHashMap<>();
            for (Map.Entry<PlayableEntity, PlayerSolidContactResult> playerEntry : entry.getValue().perPlayer().entrySet()) {
                PlayerSolidContactResult result = playerEntry.getValue();
                perPlayer.put(playerEntry.getKey(),
                        new PlayerStandingState(result.kind(), result.standingNow(), result.pushingNow()));
            }
            previous.put(entry.getKey(), perPlayer);
        }
    }

    @Override
    public void clearTransientState() {
        current.clear();
        currentContext = ObjectSolidExecutionContext.inert();
    }
}
```

- [ ] **Step 4: Re-run the registry tests and make sure they pass**

Run:

```bash
mvn "-Dtest=TestSolidExecutionRegistry" test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 5: Commit the registry layer**

```bash
git add src/main/java/com/openggf/game/solid src/test/java/com/openggf/game/solid/TestSolidExecutionRegistry.java
git commit -m "feat: add runtime solid execution registry"
```

### Task 3: Wire The Registry Into GameRuntime, GameServices, And ObjectServices

**Files:**
- Modify: `src/main/java/com/openggf/game/GameRuntime.java`
- Modify: `src/main/java/com/openggf/game/RuntimeManager.java`
- Modify: `src/main/java/com/openggf/game/GameServices.java`
- Modify: `src/main/java/com/openggf/level/objects/ObjectServices.java`
- Modify: `src/test/java/com/openggf/level/objects/StubObjectServices.java`
- Modify: `src/test/java/com/openggf/level/objects/TestObjectServicesRuntimeDefaults.java`

- [ ] **Step 1: Add failing service-exposure tests**

Extend the runtime-defaults test so both real and stub object services expose a usable solid execution registry.

```java
@Test
void stubObjectServicesExposeInertSolidExecutionRegistryWithoutActiveRuntime() {
    StubObjectServices services = new StubObjectServices();

    assertNotNull(services.solidExecutionRegistry());
    assertTrue(services.solidExecutionRegistry().currentObject().isInert());
}

@Test
void runtimeBackedObjectServicesExposeRuntimeOwnedSolidExecutionRegistry() {
    TestEnvironment.resetAll();
    DefaultObjectServices services = new DefaultObjectServices(RuntimeManager.getCurrent());

    assertSame(GameServices.solidExecutionRegistry(), services.solidExecutionRegistry());
}
```

- [ ] **Step 2: Run the object-services runtime test to verify it fails**

Run:

```bash
mvn "-Dtest=TestObjectServicesRuntimeDefaults" test
```

Expected: FAIL because the service accessors do not exist yet.

- [ ] **Step 3: Add runtime, global, and object-service accessors**

Wire the registry through the same runtime-owned path used by zone runtime and palette ownership.

```java
// GameRuntime.java
private final SolidExecutionRegistry solidExecutionRegistry;

public SolidExecutionRegistry getSolidExecutionRegistry() {
    return solidExecutionRegistry;
}

public void clearTransientFrameState() {
    zoneLayoutMutationPipeline.clear();
    solidExecutionRegistry.clearTransientState();
}
```

```java
// RuntimeManager.java
SolidExecutionRegistry solidExecutionRegistry = new DefaultSolidExecutionRegistry();

GameRuntime runtime = new GameRuntime(
        services, gameplayMode.getWorldSession(), gameplayMode,
        camera, timers, gameState, fadeManager, waterSystem, parallaxManager,
        terrainCollisionManager, collisionSystem, spriteManager, levelManager, rng,
        zoneRuntimeRegistry, paletteOwnershipRegistry, animatedTileChannelGraph,
        specialRenderEffectRegistry, advancedRenderModeController,
        zoneLayoutMutationPipeline, solidExecutionRegistry);
```

```java
// GameServices.java
public static SolidExecutionRegistry solidExecutionRegistry() {
    return requireRuntime("solidExecutionRegistry").getSolidExecutionRegistry();
}

public static SolidExecutionRegistry solidExecutionRegistryOrNull() {
    GameRuntime rt = runtimeOrNull();
    return rt != null ? rt.getSolidExecutionRegistry() : null;
}
```

```java
// ObjectServices.java
default SolidExecutionRegistry solidExecutionRegistry() {
    return GameServices.hasRuntime()
            ? GameServices.solidExecutionRegistry()
            : SolidExecutionRegistry.inert();
}

default ObjectSolidExecutionContext solidExecution() {
    return solidExecutionRegistry().currentObject();
}
```

- [ ] **Step 4: Make object services compile against the new API**

`DefaultObjectServices` already inherits the new default methods from `ObjectServices`; only the stub needs an explicit override for no-runtime tests.

```java
// StubObjectServices.java
private final SolidExecutionRegistry solidExecutionRegistry = SolidExecutionRegistry.inert();

@Override
public SolidExecutionRegistry solidExecutionRegistry() {
    return solidExecutionRegistry;
}
```

- [ ] **Step 5: Re-run the registry and object-services slices**

Run:

```bash
mvn "-Dtest=TestSolidExecutionRegistry,TestObjectServicesRuntimeDefaults" test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 6: Commit the runtime/service wiring**

```bash
git add src/main/java/com/openggf/game/GameRuntime.java src/main/java/com/openggf/game/RuntimeManager.java src/main/java/com/openggf/game/GameServices.java src/main/java/com/openggf/level/objects/ObjectServices.java src/test/java/com/openggf/level/objects/StubObjectServices.java src/test/java/com/openggf/level/objects/TestObjectServicesRuntimeDefaults.java
git commit -m "feat: expose solid execution registry through runtime services"
```

### Task 4: Add Manual Checkpoint Mode To ObjectManager And Keep A Compatibility Fallback

**Files:**
- Create: `src/main/java/com/openggf/level/objects/SolidExecutionMode.java`
- Modify: `src/main/java/com/openggf/level/objects/SolidObjectProvider.java`
- Modify: `src/main/java/com/openggf/level/objects/ObjectManager.java`
- Modify: `src/main/java/com/openggf/level/objects/SolidObjectListener.java`
- Modify: `src/test/java/com/openggf/level/objects/TestSolidObjectManager.java`

- [ ] **Step 1: Repair the stale test harness and add failing unit tests for manual checkpoints**

First make `TestSolidObjectManager` use the current runtime bootstrap path so later red/green results are meaningful on this checkout. Then extend it with one manual-checkpoint probe object and one legacy auto object. Reuse the existing local `TestPlayableSprite` and `buildManager` helper from that test file.

```java
@BeforeEach
public void setUp() {
    TestEnvironment.resetAll();
}

@Test
void manualCheckpointObjectSeesStandingStateInsideUpdate() {
    TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
    player.setWidth(20);
    player.setHeight(20);
    player.setCentreX((short) 100);
    player.setCentreY((short) 83);
    player.setYSpeed((short) 0x100);
    player.setAir(true);

    ManualCheckpointProbeObject object = new ManualCheckpointProbeObject(100, 100);
    ObjectManager manager = buildManager(object);

    manager.update(0, player, List.of(), 0, false, true, false);

    assertTrue(object.standingSeenInsideUpdate);
    assertEquals(1, object.manualCheckpointCount);
    assertEquals(0, object.compatibilityCallbackCount);
}

@Test
void legacyAutoObjectStillReceivesOnePostUpdateCheckpoint() {
    TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
    player.setWidth(20);
    player.setHeight(20);
    player.setCentreX((short) 100);
    player.setCentreY((short) 83);
    player.setYSpeed((short) 0x100);
    player.setAir(true);

    AutoCheckpointProbeObject object = new AutoCheckpointProbeObject(100, 100);
    ObjectManager manager = buildManager(object);

    manager.update(0, player, List.of(), 0, false, true, false);

    assertEquals(1, object.compatibilityCallbackCount);
}

private static final class ManualCheckpointProbeObject extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener {
    private boolean standingSeenInsideUpdate;
    private int manualCheckpointCount;
    private int compatibilityCallbackCount;
    private final SolidObjectParams params = new SolidObjectParams(16, 8, 8);

    private ManualCheckpointProbeObject(int x, int y) {
        super(new ObjectSpawn(x, y, 0, 0, 0, false, 0), "ManualCheckpointProbe");
    }

    @Override
    public SolidExecutionMode solidExecutionMode() {
        return SolidExecutionMode.MANUAL_CHECKPOINT;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return params;
    }

    @Override
    public void update(int frameCounter, PlayableEntity player) {
        PlayerSolidContactResult result = services().solidExecution().resolveSolidNow(player);
        standingSeenInsideUpdate = result.standingNow();
        manualCheckpointCount++;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {}

    @Override
    public boolean isHighPriority() { return false; }

    @Override
    public boolean isDestroyed() { return false; }

    @Override
    public void onSolidContact(PlayableEntity player, SolidContact contact, int frameCounter) {
        compatibilityCallbackCount++;
    }
}

private static final class AutoCheckpointProbeObject extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener {
    private int compatibilityCallbackCount;
    private final SolidObjectParams params = new SolidObjectParams(16, 8, 8);

    private AutoCheckpointProbeObject(int x, int y) {
        super(new ObjectSpawn(x, y, 0, 0, 0, false, 0), "AutoCheckpointProbe");
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return params;
    }

    @Override
    public void update(int frameCounter, PlayableEntity player) {}

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {}

    @Override
    public boolean isHighPriority() { return false; }

    @Override
    public boolean isDestroyed() { return false; }

    @Override
    public void onSolidContact(PlayableEntity player, SolidContact contact, int frameCounter) {
        compatibilityCallbackCount++;
    }
}
```

- [ ] **Step 2: Run the solid object manager slice to verify it fails**

Run:

```bash
mvn "-Dtest=TestSolidObjectManager" test
```

Expected: FAIL because `MANUAL_CHECKPOINT`, object-context checkpoint publication, and the shared execution helper do not exist yet.

- [ ] **Step 3: Add execution mode to `SolidObjectProvider`**

Keep legacy objects working by default.

```java
package com.openggf.level.objects;

public enum SolidExecutionMode {
    AUTO_AFTER_UPDATE,
    MANUAL_CHECKPOINT
}
```

```java
public interface SolidObjectProvider {
    SolidObjectParams getSolidParams();

    default SolidExecutionMode solidExecutionMode() {
        return SolidExecutionMode.AUTO_AFTER_UPDATE;
    }
}
```

- [ ] **Step 4: Route every object-update path through one shared execution helper**

Make `ObjectManager` own the compatibility bridge, not the objects, and use the same helper from every real `instance.update(...)` path.

```java
// ObjectManager update
SolidExecutionRegistry registry = objectServices.solidExecutionRegistry();
registry.beginFrame(frameCounter, collectActivePlayers(player, sidekicks));
boolean counterBased = placement.isCounterBasedRespawn();
try {
    if (counterBased) {
        updateCounterBasedExecThenLoad(cameraX, player, sidekicks, inlineSolidResolution, solidPostMovement);
    } else {
        runExecLoop(cameraX, player, sidekicks, inlineSolidResolution, solidPostMovement);
    }
} finally {
    registry.finishFrame();
}
```

```java
// ObjectManager shared helper
private void executeObjectWithSolidContext(
        ObjectInstance instance,
        PlayableEntity player,
        List<? extends PlayableEntity> sidekicks,
        boolean inlineSolidResolution,
        boolean solidPostMovement) {
    SolidExecutionRegistry registry = objectServices.solidExecutionRegistry();
    SolidExecutionMode mode = null;
    if (inlineSolidResolution && instance instanceof SolidObjectProvider provider) {
        mode = provider.solidExecutionMode();
    }

    ObjectSolidExecutionContext.Resolver resolver =
            mode == SolidExecutionMode.MANUAL_CHECKPOINT
                    ? () -> solidContacts.processManualCheckpoint(instance, player, sidekicks, solidPostMovement)
                    : null;

    registry.beginObject(instance, resolver);
    try {
        instance.update(vblaCounter, player);
        if (mode == SolidExecutionMode.AUTO_AFTER_UPDATE && !instance.isDestroyed()) {
            registry.publishCheckpoint(
                    solidContacts.processCompatibilityCheckpoint(instance, player, sidekicks, solidPostMovement));
        }
    } finally {
        registry.endObject(instance);
    }
}
```

```java
// ObjectManager helper use sites
// runExecLoop(...): replace every direct instance.update(vblaCounter, player) call with
// executeObjectWithSolidContext(instance, player, sidekicks, inlineSolidResolution, solidPostMovement)
//
// updateCounterBasedExecThenLoad(...): change signature to accept sidekicks, inlineSolidResolution,
// and solidPostMovement, then replace every direct instance.update(vblaCounter, player) call with
// executeObjectWithSolidContext(instance, player, sidekicks, inlineSolidResolution, solidPostMovement)
//
// runNewlyLoadedObjects(...): change signature to accept sidekicks, inlineSolidResolution,
// and solidPostMovement, then replace the direct update call there with the same helper
//
// This includes the slot-based loops and the slotless fallback loops. Do not leave any real
// object update path outside executeObjectWithSolidContext(...).

// ObjectManager helper
private List<PlayableEntity> collectActivePlayers(
        PlayableEntity player,
        List<? extends PlayableEntity> sidekicks) {
    ArrayList<PlayableEntity> players = new ArrayList<>(1 + sidekicks.size());
    if (player != null) {
        players.add(player);
    }
    players.addAll(sidekicks);
    return players;
}
```

```java
// ObjectManager.SolidContacts
SolidCheckpointBatch processManualCheckpoint(
        ObjectInstance instance,
        PlayableEntity player,
        List<? extends PlayableEntity> sidekicks,
        boolean postMovement) {
    return resolveCheckpointBatch(instance, player, sidekicks, postMovement);
}

SolidCheckpointBatch processCompatibilityCheckpoint(
        ObjectInstance instance,
        PlayableEntity player,
        List<? extends PlayableEntity> sidekicks,
        boolean postMovement) {
    SolidCheckpointBatch batch = resolveCheckpointBatch(instance, player, sidekicks, postMovement);
    emitCompatibilityCallbacks(instance, batch);
    return batch;
}
```

Manual-checkpoint objects publish through `ObjectSolidExecutionContext.resolveSolidNowAll()` only. Compatibility-mode objects publish once after `update()` through the `AUTO_AFTER_UPDATE` path above. Do not publish from `resolveCheckpointBatch(...)` itself.

- [ ] **Step 5: Keep `SolidObjectListener` as a compatibility adapter only**

Do not remove the listener yet; make its new role explicit in comments and tests.

```java
public interface SolidObjectListener {
    void onSolidContact(PlayableEntity player, SolidContact contact, int frameCounter);
    // Compatibility adapter only. Manual-checkpoint objects should branch on
    // PlayerSolidContactResult instead of relying on this callback long-term.
}
```

- [ ] **Step 6: Re-run the object-manager slice**

Run:

```bash
mvn "-Dtest=TestSolidObjectManager" test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 7: Commit the checkpoint plumbing**

```bash
git add src/main/java/com/openggf/level/objects/SolidExecutionMode.java src/main/java/com/openggf/level/objects/SolidObjectProvider.java src/main/java/com/openggf/level/objects/ObjectManager.java src/main/java/com/openggf/level/objects/SolidObjectListener.java src/test/java/com/openggf/level/objects/TestSolidObjectManager.java
git commit -m "feat: add manual solid checkpoint mode"
```

### Task 5: Convert Shared Helper APIs To Snapshot-Based Semantics And Lock The Architecture With Sentinel Tests

**Files:**
- Modify: `src/main/java/com/openggf/physics/CollisionSystem.java`
- Modify: `src/main/java/com/openggf/physics/CollisionTrace.java`
- Modify: `src/main/java/com/openggf/physics/NoOpCollisionTrace.java`
- Modify: `src/main/java/com/openggf/physics/RecordingCollisionTrace.java`
- Modify: `src/main/java/com/openggf/physics/CollisionEvent.java`
- Modify: `src/main/java/com/openggf/level/objects/ObjectManager.java`
- Create: `src/test/java/com/openggf/tests/TestSolidOrderingSentinelsHeadless.java`
- Create: `src/test/java/com/openggf/tests/TestSolidOrderingCollisionTraces.java`
- Modify: `src/test/java/com/openggf/tests/physics/CollisionSystemTest.java`

- [ ] **Step 1: Add failing tests for same-frame standing, no-contact clear, multi-player batches, and checkpoint-backed helper queries**

Create a new headless sentinel test class using a tiny synthetic platform object that calls `services().solidExecution().resolveSolidNowAll()` from inside `update()`. Use an explicit S2 fixture so the test does not rely on implicit module defaults.

```java
@Test
void sameFrameStandingStateIsVisibleInsideObjectUpdate() {
    HeadlessTestFixture fixture = HeadlessTestFixture.builder()
            .withZoneAndAct(Sonic2ZoneConstants.ZONE_EHZ, 0)
            .build();

    SolidOrderingProbeObject probe = new SolidOrderingProbeObject(0x120, 0x100);
    GameServices.level().getObjectManager().addDynamicObject(probe);
    fixture.sprite().setCentreX((short) 0x120);
    fixture.sprite().setCentreY((short) 0x0F3);
    fixture.sprite().setAir(true);
    fixture.sprite().setYSpeed((short) 0x100);

    fixture.stepFrame(false, false, false, false, false);

    assertTrue(probe.standingSeenInsideUpdate());
}

@Test
void noContactResultClearsInTheFrameThePlayerLeaves() {
    HeadlessTestFixture fixture = HeadlessTestFixture.builder()
            .withZoneAndAct(Sonic2ZoneConstants.ZONE_EHZ, 0)
            .build();

    SolidOrderingProbeObject probe = new SolidOrderingProbeObject(0x120, 0x100);
    GameServices.level().getObjectManager().addDynamicObject(probe);

    fixture.sprite().setCentreX((short) 0x120);
    fixture.sprite().setCentreY((short) 0x0F3);
    fixture.sprite().setAir(true);
    fixture.sprite().setYSpeed((short) 0x100);
    fixture.stepFrame(false, false, false, false, false);

    fixture.sprite().setCentreX((short) 0x180);
    fixture.stepFrame(false, false, false, false, false);

    assertEquals(ContactKind.NONE, probe.lastKind());
    assertTrue(probe.lastStandingLastFrame());
}

private static final class SolidOrderingProbeObject extends AbstractObjectInstance
        implements SolidObjectProvider {
    private final SolidObjectParams params = new SolidObjectParams(16, 8, 8);
    private boolean standingSeenInsideUpdate;
    private ContactKind lastKind = ContactKind.NONE;
    private boolean lastStandingLastFrame;

    private SolidOrderingProbeObject(int x, int y) {
        super(new ObjectSpawn(x, y, 0, 0, 0, false, 0), "SolidOrderingProbe");
    }

    @Override
    public SolidExecutionMode solidExecutionMode() {
        return SolidExecutionMode.MANUAL_CHECKPOINT;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return params;
    }

    @Override
    public void update(int frameCounter, PlayableEntity player) {
        PlayerSolidContactResult result = services().solidExecution().resolveSolidNow(player);
        standingSeenInsideUpdate = result.standingNow();
        lastKind = result.kind();
        lastStandingLastFrame = result.standingLastFrame();
    }

    boolean standingSeenInsideUpdate() { return standingSeenInsideUpdate; }
    ContactKind lastKind() { return lastKind; }
    boolean lastStandingLastFrame() { return lastStandingLastFrame; }
}
```

```java
// CollisionSystemTest.java
@Test
public void testHasStandingContactDelegatesToLatestSnapshot() throws Exception {
    ObjectManager objectManager = mock(ObjectManager.class);
    AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
    Field field = CollisionSystem.class.getDeclaredField("objectManager");
    field.setAccessible(true);
    field.set(collisionSystem, objectManager);

    when(objectManager.latestStandingSnapshot(player)).thenReturn(true);

    assertTrue(collisionSystem.hasStandingContact(player));
    verify(objectManager).latestStandingSnapshot(player);
}

@Test
public void testGetHeadroomDistanceDelegatesToLatestSnapshot() throws Exception {
    ObjectManager objectManager = mock(ObjectManager.class);
    AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
    Field field = CollisionSystem.class.getDeclaredField("objectManager");
    field.setAccessible(true);
    field.set(collisionSystem, objectManager);

    when(objectManager.latestHeadroomSnapshot(player, 0x40)).thenReturn(12);

    assertEquals(12, collisionSystem.getHeadroomDistance(player, 0x40));
    verify(objectManager).latestHeadroomSnapshot(player, 0x40);
}
```

```java
// TestSolidOrderingCollisionTraces.java
@Test
void sameFrameManualCheckpointEmitsOrderedCheckpointTraceEvents() throws Exception {
    HeadlessTestFixture fixture = HeadlessTestFixture.builder()
            .withZoneAndAct(Sonic2ZoneConstants.ZONE_EHZ, 0)
            .build();
    RecordingCollisionTrace trace = new RecordingCollisionTrace();
    GameServices.collision().setTrace(trace);

    SolidOrderingProbeObject left = new SolidOrderingProbeObject(0x120, 0x100);
    SolidOrderingProbeObject right = new SolidOrderingProbeObject(0x140, 0x100);
    GameServices.level().getObjectManager().addDynamicObject(left);
    GameServices.level().getObjectManager().addDynamicObject(right);

    fixture.sprite().setCentreX((short) 0x120);
    fixture.sprite().setCentreY((short) 0x0F3);
    fixture.sprite().setAir(true);
    fixture.sprite().setYSpeed((short) 0x100);

    fixture.stepFrame(false, false, false, false, false);

    assertEquals(List.of(
            CollisionEvent.EventType.SOLID_CHECKPOINT_START,
            CollisionEvent.EventType.SOLID_CHECKPOINT_RESULT,
            CollisionEvent.EventType.SOLID_CHECKPOINT_RESULT,
            CollisionEvent.EventType.SOLID_CHECKPOINT_START,
            CollisionEvent.EventType.SOLID_CHECKPOINT_RESULT,
            CollisionEvent.EventType.SOLID_CHECKPOINT_RESULT),
            trace.getEvents().stream()
                    .filter(event -> event.type().name().startsWith("SOLID_CHECKPOINT"))
                    .map(CollisionEvent::type)
                    .toList());
}
```

- [ ] **Step 2: Run the new headless sentinel slice and verify it fails**

Run:

```bash
mvn "-Dtest=TestSolidOrderingSentinelsHeadless,TestSolidOrderingCollisionTraces,CollisionSystemTest" test
```

Expected: FAIL because `CollisionSystem.hasStandingContact(...)` and `getHeadroomDistance(...)` do not delegate to the new snapshot helpers yet, the probe object still observes the old shared-pass timing, and the trace API does not expose explicit checkpoint events yet.

- [ ] **Step 3: Rewrite `CollisionSystem` and `ObjectManager` helper queries around latest checkpoint snapshots**

Make helper queries consume the authoritative batch/history instead of rescanning the old shared-pass state.

```java
// CollisionSystem.java
public boolean hasStandingContact(AbstractPlayableSprite player) {
    if (objectManager == null) {
        return false;
    }
    return objectManager.latestStandingSnapshot(player);
}

public int getHeadroomDistance(AbstractPlayableSprite player, int hexAngle) {
    if (objectManager == null) {
        return Integer.MAX_VALUE;
    }
    return objectManager.latestHeadroomSnapshot(player, hexAngle);
}
```

```java
// ObjectManager.java
public boolean latestStandingSnapshot(PlayableEntity player) {
    return solidContacts.latestStandingSnapshot(player);
}

public int latestHeadroomSnapshot(PlayableEntity player, int hexAngle) {
    return solidContacts.latestHeadroomSnapshot(player, hexAngle);
}
```

- [ ] **Step 4: Extend the collision trace API with explicit checkpoint events**

Use the existing collision-trace stack instead of inventing an external text format the repo cannot yet produce.

```java
// CollisionTrace.java
void onSolidCheckpointStart(String objectType, int objectX, int objectY);
void onSolidCheckpointResult(String objectType, String playerLabel, String kind,
                             boolean standingNow, boolean standingLastFrame);
```

```java
// NoOpCollisionTrace.java
@Override public void onSolidCheckpointStart(String objectType, int objectX, int objectY) {}
@Override public void onSolidCheckpointResult(String objectType, String playerLabel, String kind,
        boolean standingNow, boolean standingLastFrame) {}
```

```java
// CollisionEvent.java
public enum EventType {
    TERRAIN_PROBES_START,
    TERRAIN_PROBE_RESULT,
    TERRAIN_PROBES_COMPLETE,
    SOLID_CONTACTS_START,
    SOLID_CANDIDATE,
    SOLID_RESOLVED,
    SOLID_CONTACTS_COMPLETE,
    SOLID_CHECKPOINT_START,
    SOLID_CHECKPOINT_RESULT,
    POST_ADJUSTMENT
}
```

```java
// RecordingCollisionTrace.java
@Override
public void onSolidCheckpointStart(String objectType, int objectX, int objectY) {
    events.add(CollisionEvent.position(
            CollisionEvent.EventType.SOLID_CHECKPOINT_START,
            objectType,
            objectX,
            objectY));
}

@Override
public void onSolidCheckpointResult(String objectType, String playerLabel, String kind,
        boolean standingNow, boolean standingLastFrame) {
    events.add(new CollisionEvent(
            CollisionEvent.EventType.SOLID_CHECKPOINT_RESULT,
            objectType + ":" + playerLabel + ":" + kind,
            0, 0, 0, (byte) 0, standingNow, standingLastFrame));
}
```

```java
// ObjectManager.SolidContacts.resolveCheckpointBatch(...)
CollisionTrace trace = GameServices.collision().getTrace();
trace.onSolidCheckpointStart(instance.getClass().getSimpleName(), instance.getX(), instance.getY());
for (Map.Entry<PlayableEntity, PlayerSolidContactResult> entry : perPlayer.entrySet()) {
    PlayerSolidContactResult result = entry.getValue();
    String playerLabel = entry.getKey().isCpuControlled() ? "sidekick" : "main";
    trace.onSolidCheckpointResult(
            instance.getClass().getSimpleName(),
            playerLabel,
            result.kind().name(),
            result.standingNow(),
            result.standingLastFrame());
}
```

- [ ] **Step 5: Make multi-player checkpoint batches update shared rider/support state once**

Ensure the same checkpoint batch drives both the per-player result map and support tracking.

```java
private SolidCheckpointBatch resolveCheckpointBatch(
        ObjectInstance instance,
        PlayableEntity player,
        List<? extends PlayableEntity> sidekicks,
        boolean postMovement) {
    IdentityHashMap<PlayableEntity, PlayerSolidContactResult> perPlayer = new IdentityHashMap<>();
    resolveCheckpointForPlayer(instance, player, postMovement, perPlayer);
    for (PlayableEntity sidekick : sidekicks) {
        resolveCheckpointForPlayer(instance, sidekick, postMovement, perPlayer);
    }
    return new SolidCheckpointBatch(instance, perPlayer);
}
```

- [ ] **Step 6: Re-run the helper and sentinel slices**

Run:

```bash
mvn "-Dtest=TestSolidOrderingSentinelsHeadless,TestSolidOrderingCollisionTraces,CollisionSystemTest,TestSolidObjectManager" test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 7: Commit the shared helper conversion**

```bash
git add src/main/java/com/openggf/physics/CollisionSystem.java src/main/java/com/openggf/physics/CollisionTrace.java src/main/java/com/openggf/physics/NoOpCollisionTrace.java src/main/java/com/openggf/physics/RecordingCollisionTrace.java src/main/java/com/openggf/physics/CollisionEvent.java src/main/java/com/openggf/level/objects/ObjectManager.java src/test/java/com/openggf/tests/TestSolidOrderingSentinelsHeadless.java src/test/java/com/openggf/tests/TestSolidOrderingCollisionTraces.java src/test/java/com/openggf/tests/physics/CollisionSystemTest.java
git commit -m "refactor: drive solid helpers from checkpoint snapshots"
```

### Task 6: Migrate The S2 And S3K Sentinel Objects To Manual Checkpoints

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/objects/TornadoObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/SpringObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/SpringboardObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/FlipperObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/SeesawObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/CollapsingPlatformObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/AizLrzRockObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/CorkFloorObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/BreakableWallObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/HCZHandLauncherObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/AizCollapsingLogBridgeObjectInstance.java`
- Modify: `src/test/java/com/openggf/game/sonic2/objects/TestTornadoObjectInstance.java`
- Modify: `src/test/java/com/openggf/tests/TestS2Htz1Headless.java`
- Modify: `src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java`
- Modify: `src/test/java/com/openggf/tests/TestSolidOrderingSentinelsHeadless.java`

- [ ] **Step 1: Add failing sentinel tests for the known workaround families**

Use the existing unit/headless tests where they already fit.

```java
// TestTornadoObjectInstance.java
@Test
void tornadoSczMainReadsStandingStateFromManualCheckpointBeforeFollowMotion() throws Exception {
    TornadoObjectInstance tornado = createTornado(100, 0x100, 0x50);
    TestPlayableSprite main = new TestPlayableSprite("main", (short) 100, (short) 100);
    main.setAir(false);

    invokePrivate(tornado, "updateSczMain",
            new Class<?>[]{AbstractPlayableSprite.class}, main);

    assertTrue((boolean) getField(tornado, "lastMainStanding"));
}
```

```java
// TestSolidOrderingSentinelsHeadless.java
@Test
void flipperClearsControlLockOnSameFrameNoContactResult() {
    HeadlessTestFixture fixture = HeadlessTestFixture.builder()
            .withZoneAndAct(Sonic2ZoneConstants.ZONE_CNZ, 0)
            .build();

    FlipperObjectInstance flipper = spawnFlipper(fixture, 0x1530, 0x04F0);
    fixture.sprite().setCentreX((short) 0x1530);
    fixture.sprite().setCentreY((short) 0x04E4);
    fixture.sprite().setAir(false);
    fixture.stepFrame(false, false, false, false, false);

    fixture.sprite().setCentreX((short) 0x1590);
    fixture.stepFrame(false, false, false, false, false);

    assertEquals(0, (int) getField(flipper, "playerFlipperState"));
    assertFalse(fixture.sprite().isObjectControlled());
}

@Test
void springboardLaunchesFromCurrentFrameStandingState() {
    HeadlessTestFixture fixture = HeadlessTestFixture.builder()
            .withZoneAndAct(Sonic2ZoneConstants.ZONE_HTZ, 0)
            .build();

    SpringboardObjectInstance springboard = spawnSpringboard(fixture, 0x1080, 0x04C0);
    fixture.sprite().setCentreX((short) 0x1080);
    fixture.sprite().setCentreY((short) 0x04B5);
    fixture.sprite().setAir(true);
    fixture.sprite().setYSpeed((short) 0x0180);

    fixture.stepFrame(false, false, false, false, false);

    assertTrue((boolean) getField(springboard, "launchSequenceActive"));
    assertTrue(fixture.sprite().getYSpeed() < 0);
}

@Test
void corkFloorStoresPreContactStateFromCheckpointBatch() throws Exception {
    HeadlessTestFixture fixture = HeadlessTestFixture.builder()
            .withZoneAndAct(Sonic3kZoneIds.ZONE_AIZ, 0)
            .build();

    CorkFloorObjectInstance floor = spawnCorkFloor(fixture, 0x1180, 0x0520, 0);
    fixture.sprite().setCentreX((short) 0x1180);
    fixture.sprite().setCentreY((short) 0x0512);
    fixture.sprite().setAir(true);
    fixture.sprite().setYSpeed((short) -0x200);

    fixture.stepFrame(false, false, false, false, false);

    assertEquals(-0x200, (int) getField(floor, "savedPreContactYSpeed"));
}

private static FlipperObjectInstance spawnFlipper(HeadlessTestFixture fixture, int x, int y) {
    FlipperObjectInstance object = new FlipperObjectInstance(new ObjectSpawn(
            x, y, Sonic2ObjectIds.FLIPPER, 0, 0, false, 0), "Flipper");
    object.setServices(new TestObjectServices()
            .withLevelManager(GameServices.level())
            .withCamera(GameServices.camera())
            .withParallaxManager(GameServices.parallax())
            .withSpriteManager(GameServices.sprites()));
    GameServices.level().getObjectManager().addDynamicObject(object);
    return object;
}

private static SpringboardObjectInstance spawnSpringboard(HeadlessTestFixture fixture, int x, int y) {
    SpringboardObjectInstance object = new SpringboardObjectInstance(new ObjectSpawn(
            x, y, Sonic2ObjectIds.SPRINGBOARD, 0, 0, false, 0), "Springboard");
    object.setServices(new TestObjectServices()
            .withLevelManager(GameServices.level())
            .withCamera(GameServices.camera())
            .withParallaxManager(GameServices.parallax())
            .withSpriteManager(GameServices.sprites()));
    GameServices.level().getObjectManager().addDynamicObject(object);
    return object;
}

private static CorkFloorObjectInstance spawnCorkFloor(
        HeadlessTestFixture fixture, int x, int y, int subtype) {
    CorkFloorObjectInstance object = new CorkFloorObjectInstance(new ObjectSpawn(
            x, y, Sonic3kObjectIds.CORK_FLOOR, subtype, 0, false, 0));
    object.setServices(new TestObjectServices()
            .withLevelManager(GameServices.level())
            .withCamera(GameServices.camera())
            .withParallaxManager(GameServices.parallax())
            .withSpriteManager(GameServices.sprites()));
    GameServices.level().getObjectManager().addDynamicObject(object);
    return object;
}

private static Object getField(Object target, String fieldName) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    return field.get(target);
}
```

- [ ] **Step 2: Run the S2/S3K sentinel slice and confirm it fails first**

Run:

```bash
mvn "-Dtest=TestTornadoObjectInstance,TestS2Htz1Headless,TestS3kCnzDirectedTraversalHeadless,TestSolidOrderingSentinelsHeadless" test
```

Expected: FAIL in the new ordering-sensitive assertions.

- [ ] **Step 3: Switch each sentinel object to `MANUAL_CHECKPOINT` and branch on `PlayerSolidContactResult`**

Replace callback-only state reconstruction with direct checkpoint results. When a ROM routine calls `SolidObject*` more than once, issue more than one `resolveSolidNow(...)` call in the matching order; do not assume the registry caches one result for the whole `update(...)`.

```java
@Override
public SolidExecutionMode solidExecutionMode() {
    return SolidExecutionMode.MANUAL_CHECKPOINT;
}

private PlayerSolidContactResult checkpoint(AbstractPlayableSprite player) {
    return services().solidExecution().resolveSolidNow(player);
}
```

```java
// TornadoObjectInstance.java
private boolean lastMainStanding;
private boolean standingTransition;

private void updateSczMain(AbstractPlayableSprite player) {
    PlayerSolidContactResult contact = checkpoint(player);
    boolean mainStandingNow = contact.standingNow();
    standingTransition = mainStandingNow != lastMainStanding;
    lastMainStanding = mainStandingNow;
    moveWithPlayer(player, mainStandingNow);
    moveObeyPlayer(player, mainStandingNow);
}
```

```java
// CorkFloorObjectInstance.java / BreakableWallObjectInstance.java / AizLrzRockObjectInstance.java
private short savedPreContactXSpeed;
private short savedPreContactYSpeed;
private boolean savedPreContactRolling;

private void capturePreContact(PlayerSolidContactResult result) {
    savedPreContactRolling = result.preContact().rolling();
    savedPreContactXSpeed = result.preContact().xSpeed();
    savedPreContactYSpeed = result.preContact().ySpeed();
}
```

- [ ] **Step 4: Delete object-local latches only where the checkpoint result fully replaces them**

Make the latch deletions concrete in the migrated files:

```java
// FlipperObjectInstance.java
private void updatePlayerLock(AbstractPlayableSprite player) {
    PlayerSolidContactResult result = checkpoint(player);
    if (!isHorizontal() && playerFlipperState != 0 && result.kind() == ContactKind.NONE) {
        playerFlipperState = 0;
        player.setObjectControlled(false);
    }
}
```

```java
// SpringboardObjectInstance.java
private void updateLaunchSequence(AbstractPlayableSprite player) {
    PlayerSolidContactResult result = checkpoint(player);
    launchSequenceActive = result.standingNow() || (launchSequenceActive && isWithinLaunchXRange(player));
    if (launchSequenceActive) {
        runCompressionAndLaunch(player);
    }
}
```

- [ ] **Step 5: Re-run the S2/S3K sentinel slice**

Run:

```bash
mvn "-Dtest=TestTornadoObjectInstance,TestS2Htz1Headless,TestS3kCnzDirectedTraversalHeadless,TestSolidOrderingSentinelsHeadless" test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 6: Commit the S2 and S3K migrations separately**

```bash
git add src/main/java/com/openggf/game/sonic2/objects/TornadoObjectInstance.java src/main/java/com/openggf/game/sonic2/objects/SpringObjectInstance.java src/main/java/com/openggf/game/sonic2/objects/SpringboardObjectInstance.java src/main/java/com/openggf/game/sonic2/objects/FlipperObjectInstance.java src/main/java/com/openggf/game/sonic2/objects/SeesawObjectInstance.java src/main/java/com/openggf/game/sonic2/objects/CollapsingPlatformObjectInstance.java src/test/java/com/openggf/game/sonic2/objects/TestTornadoObjectInstance.java src/test/java/com/openggf/tests/TestS2Htz1Headless.java src/test/java/com/openggf/tests/TestSolidOrderingSentinelsHeadless.java
git commit -m "feat: migrate sonic 2 solid ordering sentinels"

git add src/main/java/com/openggf/game/sonic3k/objects/AizLrzRockObjectInstance.java src/main/java/com/openggf/game/sonic3k/objects/CorkFloorObjectInstance.java src/main/java/com/openggf/game/sonic3k/objects/BreakableWallObjectInstance.java src/main/java/com/openggf/game/sonic3k/objects/HCZHandLauncherObjectInstance.java src/main/java/com/openggf/game/sonic3k/objects/AizCollapsingLogBridgeObjectInstance.java src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java src/test/java/com/openggf/tests/TestSolidOrderingSentinelsHeadless.java
git commit -m "feat: migrate sonic 3k solid ordering sentinels"
```

### Task 7: Move Sonic 1 Off The Pre-Apply/Post-Batch Bridge And Migrate The S1 Sentinel Objects

**Files:**
- Modify: `src/main/java/com/openggf/LevelFrameStep.java`
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java`
- Modify: `src/main/java/com/openggf/sprites/managers/SpriteManager.java`
- Modify: `src/main/java/com/openggf/physics/CollisionSystem.java`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/Sonic1PlatformObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/Sonic1ButtonObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/Sonic1SpikeObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/Sonic1PushBlockObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/Sonic1BreakableWallObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/bosses/Sonic1FalseFloorInstance.java`
- Modify: `src/test/java/com/openggf/tests/TestS1SpikeDoubleHit.java`
- Modify: `src/test/java/com/openggf/tests/TestHeadlessMZ2PushBlockGap.java`
- Modify: `src/test/java/com/openggf/game/sonic1/objects/TestSonic1SpikeObjectInstance.java`
- Modify: `src/test/java/com/openggf/game/sonic1/objects/TestSonic1PlatformObjectInstanceRespawn.java`
- Modify: `src/test/java/com/openggf/tests/trace/s1/TestS1Ghz1TraceReplay.java`
- Modify: `src/test/java/com/openggf/tests/trace/s1/TestS1Mz1TraceReplay.java`

- [ ] **Step 1: Add failing S1 regression assertions before changing the scheduler**

Use the existing spike, push-block, and platform tests first; add a small same-frame standing probe only if the existing tests do not catch the bridge removal.

```java
@Test
void spikeContactDoesNotDoubleHitAcrossSameFrameStandingClear() {
    HeadlessTestFixture fixture = HeadlessTestFixture.builder()
            .withZoneAndAct(Sonic1ZoneConstants.ZONE_SYZ, 0)
            .build();

    // Existing fixture setup from TestS1SpikeDoubleHit, but assert exactly one hurt event
    // after the player leaves the object in the same frame.
}
```

- [ ] **Step 2: Run the S1 sentinel and trace smoke slice**

Run:

```bash
mvn "-Dtest=TestS1SpikeDoubleHit,TestHeadlessMZ2PushBlockGap,TestSonic1SpikeObjectInstance,TestSonic1PlatformObjectInstanceRespawn,TestS1Ghz1TraceReplay,TestS1Mz1TraceReplay" test
```

Expected: PASS before the scheduler change.

- [ ] **Step 3: Remove the S1 bridge from the frame flow by reusing the existing inline-order flag**

Do not introduce a second scheduling flag. Reuse `LevelManager.usesInlineObjectSolidResolution()` and update every existing call site that depends on it. After this step the method means “objects execute after player physics and solid checkpoints happen during object execution”, not “collision model is DUAL_PATH”. Keep the existing name in this plan to limit churn, but update the method Javadoc and every affected call-site comment so workers do not treat it as a pure collision-model flag anymore.

```java
// LevelFrameStep.java
boolean inlineSolidResolution = levelManager.usesInlineObjectSolidResolution();
if (inlineSolidResolution) {
    wrapper.wrap("physics", spriteUpdate);
    wrapper.wrap("objects", levelManager::updateObjectPositionsPostPhysicsWithoutTouches);
} else {
    wrapper.wrap("objects", levelManager::updateObjectPositionsWithoutTouches);
    wrapper.wrap("physics", spriteUpdate);
}
```

```java
// LevelManager.java
public boolean usesInlineObjectSolidResolution() {
    GameModule activeModule = activeGameModule();
    if (activeModule == null || activeModule.getPhysicsProvider() == null
            || activeModule.getPhysicsProvider().getFeatureSet() == null) {
        return false;
    }
    return activeModule.getPhysicsProvider().getFeatureSet().collisionModel() == CollisionModel.DUAL_PATH
            || activeModule instanceof Sonic1GameModule;
}
```

```java
// GameLoop.java line ~565 and line ~3136
// keep using levelManager.usesInlineObjectSolidResolution() so title-card and ending paths
// follow the same post-physics object ordering as the main frame step once S1 flips over
```

```java
// SpriteManager.java line ~749 and line ~783
boolean usesInlineSolidResolution = levelManager != null && levelManager.usesInlineObjectSolidResolution();
if (!isUnified && !usesInlineSolidResolution) {
    applySolidContacts(levelManager, playable, false, false);
}
// remove the later call:
// levelManager.getObjectManager().updateSolidContacts(playable, postMovement, deferSideToPostMovement);
// because S1 will no longer need the dedicated post-movement batch after this step
```

```java
// LevelManager.java method updateObjectPositionsWithoutTouches() line ~1094
// remove the player-velocity pre-application block from this method body
// because S1 will no longer run object execution before movement after this step
```

- [ ] **Step 4: Migrate the S1 sentinel objects to manual checkpoints or snapshot-backed helpers**

Use the same object-side pattern introduced for S2/S3K, but only after Step 3 moves S1 object execution post-physics. Before Step 3, S1 objects must stay in compatibility mode because a manual checkpoint inside pre-physics object execution would still see stale player state.

```java
// Sonic1PlatformObjectInstance.java
@Override
public SolidExecutionMode solidExecutionMode() {
    return SolidExecutionMode.MANUAL_CHECKPOINT;
}

@Override
public void update(int frameCounter, PlayableEntity player) {
    PlayerSolidContactResult contact = services().solidExecution().resolveSolidNow((AbstractPlayableSprite) player);
    playerStanding = contact.standingNow();
    if (!inFallingRoutine) {
        bobHelper.update(playerStanding);
    }
    applyMovement((AbstractPlayableSprite) player);
    applyNudge();
}
```

```java
// Sonic1ButtonObjectInstance.java
@Override
public void update(int frameCounter, PlayableEntity playerEntity) {
    AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
    PlayerSolidContactResult contact = services().solidExecution().resolveSolidNow(player);
    boolean pressed = contact.standingNow();
    if (!pressed && blockPressable) {
        pressed = checkMZBlockContact();
    }
    playerStanding = false;
    currentFrame = pressed ? 1 : 0;
}
```

- [ ] **Step 5: Re-run the S1 slice**

Run:

```bash
mvn "-Dtest=TestS1SpikeDoubleHit,TestHeadlessMZ2PushBlockGap,TestSonic1SpikeObjectInstance,TestSonic1PlatformObjectInstanceRespawn,TestS1Ghz1TraceReplay,TestS1Mz1TraceReplay" test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 6: Commit the S1 scheduler migration**

```bash
git add src/main/java/com/openggf/LevelFrameStep.java src/main/java/com/openggf/GameLoop.java src/main/java/com/openggf/level/LevelManager.java src/main/java/com/openggf/sprites/managers/SpriteManager.java src/main/java/com/openggf/physics/CollisionSystem.java src/main/java/com/openggf/game/sonic1/objects/Sonic1PlatformObjectInstance.java src/main/java/com/openggf/game/sonic1/objects/Sonic1ButtonObjectInstance.java src/main/java/com/openggf/game/sonic1/objects/Sonic1SpikeObjectInstance.java src/main/java/com/openggf/game/sonic1/objects/Sonic1PushBlockObjectInstance.java src/main/java/com/openggf/game/sonic1/objects/Sonic1BreakableWallObjectInstance.java src/main/java/com/openggf/game/sonic1/objects/bosses/Sonic1FalseFloorInstance.java src/test/java/com/openggf/tests/TestS1SpikeDoubleHit.java src/test/java/com/openggf/tests/TestHeadlessMZ2PushBlockGap.java src/test/java/com/openggf/game/sonic1/objects/TestSonic1SpikeObjectInstance.java src/test/java/com/openggf/game/sonic1/objects/TestSonic1PlatformObjectInstanceRespawn.java src/test/java/com/openggf/tests/trace/s1/TestS1Ghz1TraceReplay.java src/test/java/com/openggf/tests/trace/s1/TestS1Mz1TraceReplay.java
git commit -m "feat: migrate sonic 1 solid ordering to checkpoints"
```

### Task 8: Remove Shared Compensation Shims Behind Passing Gates And Run Final Verification

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/ObjectManager.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/TornadoObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/AizLrzRockObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/CorkFloorObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/BreakableWallObjectInstance.java`
- Modify: any remaining migrated sentinel file still calling `getPreContact*()` or `refreshRidingTrackingPosition`

- [ ] **Step 1: Audit every remaining caller before removing shared shims**

Run:

```bash
rg -n "getPreContactXSpeed|getPreContactYSpeed|getPreContactRolling|refreshRidingTrackingPosition" src/main/java
```

Expected before cleanup:

```text
src/main/java/com/openggf/game/sonic2/objects/TornadoObjectInstance.java
src/main/java/com/openggf/game/sonic3k/objects/AizLrzRockObjectInstance.java
src/main/java/com/openggf/game/sonic3k/objects/CorkFloorObjectInstance.java
src/main/java/com/openggf/game/sonic3k/objects/BreakableWallObjectInstance.java
src/main/java/com/openggf/level/objects/ObjectManager.java
```

Do not delete the shared getters until every non-`ObjectManager` caller is migrated or deliberately left behind with a follow-up issue.

- [ ] **Step 2: Remove only the shared shims whose gates are now covered**

Start with the shims already replaced by migrated sentinel families.

1. Delete `refreshRidingTrackingPosition(ObjectInstance)` from `ObjectManager` once the Task 8 Step 1 audit shows Tornado no longer calls it.
2. Delete `getPreContactXSpeed()`, `getPreContactYSpeed()`, and `getPreContactRolling()` from `ObjectManager` once the Task 8 Step 1 audit shows only `ObjectManager` still references them.
3. Delete the velocity-based side/top reclassification branch in `ObjectManager.SolidContacts.resolveContactInternal(...)` only after the focused smoke slice in Step 3 passes with the migrated S1/S2/S3K sentinels.
4. If the audit still shows any non-sentinel caller after Tasks 6 and 7, stop the cleanup there and leave that shim in place for a follow-up migration instead of guessing.

```java
// migrated objects
savedPreContactXSpeed = result.preContact().xSpeed();
savedPreContactYSpeed = result.preContact().ySpeed();
savedPreContactRolling = result.preContact().rolling();
```

- [ ] **Step 3: Run the focused shared-ordering verification slices**

Run:

```bash
mvn "-Dtest=TestSolidExecutionRegistry,TestObjectServicesRuntimeDefaults,TestSolidObjectManager,CollisionSystemTest,TestSolidOrderingSentinelsHeadless,TestTornadoObjectInstance,TestS2Htz1Headless,TestS3kCnzDirectedTraversalHeadless,TestS1SpikeDoubleHit,TestHeadlessMZ2PushBlockGap,TestSonic1SpikeObjectInstance,TestSonic1PlatformObjectInstanceRespawn,TestS1Ghz1TraceReplay,TestS1Mz1TraceReplay" test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 4: Run one broader multi-game smoke slice**

Run:

```bash
mvn "-Dtest=TestCollisionLogic,TestCollisionModel,CollisionSystemTest,TestS2Htz1Headless,TestS3kCnzDirectedTraversalHeadless,TestS1SpikeDoubleHit" test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 5: Treat these exact test slices as the shim-removal gates**

The shim cleanup is allowed only if all of the following are green in the same worker session:

```text
TestSolidExecutionRegistry
TestObjectServicesRuntimeDefaults
TestSolidObjectManager
CollisionSystemTest
TestSolidOrderingSentinelsHeadless
TestTornadoObjectInstance
TestS2Htz1Headless
TestS3kCnzDirectedTraversalHeadless
TestS1SpikeDoubleHit
TestHeadlessMZ2PushBlockGap
TestSonic1SpikeObjectInstance
TestSonic1PlatformObjectInstanceRespawn
TestS1Ghz1TraceReplay
TestS1Mz1TraceReplay
TestCollisionLogic
TestCollisionModel
```

- [ ] **Step 6: Record the actual verification evidence in the handoff**

Use the real commands and Maven result lines from the worker session. Do not write "should pass".

- [ ] **Step 7: Commit the shim cleanup and final integration**

```bash
git add src/main/java/com/openggf/level/objects/ObjectManager.java src/main/java/com/openggf/game/sonic2/objects/TornadoObjectInstance.java src/main/java/com/openggf/game/sonic3k/objects/AizLrzRockObjectInstance.java src/main/java/com/openggf/game/sonic3k/objects/CorkFloorObjectInstance.java src/main/java/com/openggf/game/sonic3k/objects/BreakableWallObjectInstance.java
git commit -m "refactor: remove solid ordering compatibility shims"
```
