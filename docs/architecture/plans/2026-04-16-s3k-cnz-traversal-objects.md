# S3K CNZ Traversal Objects Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Sonic 3 & Knuckles Carnival Night Zone traversal-first gimmick objects with ROM-backed behavior, authored art parity where the ROM uses dedicated object art, and explicit validation for the high-motion traversal beats.

**Architecture:** Keep each CNZ traversal gimmick owned by `Sonic3kObjectRegistry` as a normal object instance class with ROM-backed Javadocs and comments. Reuse the existing S3K object-art pipeline for visible objects, keep control-lock behavior explicit inside the owning object for `Cannon`, `Cylinder`, `VacuumTube`, and `SpiralTube`, and centralize shared CNZ tube path data in one documented table file instead of scattering waypoint literals across multiple classes.

**Tech Stack:** Java 21, Maven, JUnit 5, `HeadlessTestFixture`, `DefaultObjectServices`, `Sonic and Knuckles & Sonic 3 (W) [!].gen`, `Sonic3kObjectArtProvider`, `WaypointPathFollower`, `AutomaticTunnelObjectInstance`, `HCZTwistingLoopObjectInstance`, and `docs/skdisasm/sonic3k.asm`.

---

## Shared Context

### File Map

- **Modify:** `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kObjectIds.java`
  Add the in-scope CNZ traversal object IDs and keep the naming explicitly tied to the S3KL table.
- **Modify:** `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java`
  Add the verified mapping, animation, DPLC, and art addresses needed for the visible CNZ traversal objects. Every Sonic 3-side address must note the lock-on offset rule in a comment.
- **Modify:** `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtKeys.java`
  Add art keys for the visible CNZ traversal objects that actually own dedicated object sheets.
- **Modify:** `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArt.java`
  Build the visible CNZ traversal sheets from ROM mappings / DPLCs / level-art anchors, with per-method comments naming the exact disassembly label and whether the data is S&K-side or Sonic 3-side.
- **Modify:** `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java`
  Register the traversal object sheets when zone `0x03` loads.
- **Modify:** `src/main/java/com/openggf/game/sonic3k/objects/Sonic3kObjectRegistry.java`
  Replace placeholder resolution for the targeted S3KL traversal slots while keeping the SKL-side remaps untouched.
- **Create:** `src/main/java/com/openggf/game/sonic3k/objects/CnzBalloonInstance.java`
  Balloon launcher behavior, subtype dispatch, and ROM-backed bounce comments.
- **Create:** `src/main/java/com/openggf/game/sonic3k/objects/CnzRisingPlatformInstance.java`
  Rising platform rider-triggered movement and subtype-driven travel behavior.
- **Create:** `src/main/java/com/openggf/game/sonic3k/objects/CnzTrapDoorInstance.java`
  Trap-door state machine, collision toggling, and subtype-timed open/close behavior.
- **Create:** `src/main/java/com/openggf/game/sonic3k/objects/CnzHoverFanInstance.java`
  Fan push window, force direction, and gating comments tied to the ROM routine.
- **Create:** `src/main/java/com/openggf/game/sonic3k/objects/CnzCannonInstance.java`
  Player capture, forced rolling/radii, aim/launch behavior, and DPLC-backed art.
- **Create:** `src/main/java/com/openggf/game/sonic3k/objects/CnzCylinderInstance.java`
  Cylinder capture/release logic, forced rolling/radii, and subtype-driven routing.
- **Create:** `src/main/java/com/openggf/game/sonic3k/objects/CnzTubePathTables.java`
  Shared vacuum/spiral tube path data and subtype lookup helpers, with comments naming the disassembly source and the S&K-vs-S3 side for each table family.
- **Create:** `src/main/java/com/openggf/game/sonic3k/objects/CnzVacuumTubeInstance.java`
  Vacuum tube capture/path-follow/release behavior, reusing `WaypointPathFollower` where the ROM path format fits it.
- **Create:** `src/main/java/com/openggf/game/sonic3k/objects/CnzSpiralTubeInstance.java`
  Spiral tube capture/path-follow/release behavior, reusing the `HCZTwistingLoopObjectInstance` rolling/collision parity rules where appropriate.
- **Create:** `src/test/java/com/openggf/game/sonic3k/objects/TestCnzTraversalRegistry.java`
  Registry and zone-set canaries proving the targeted S3KL slots no longer resolve to placeholders.
- **Create:** `src/test/java/com/openggf/game/sonic3k/TestCnzTraversalObjectArt.java`
  ROM-backed object-art tests for the visible traversal objects and explicit “controller-only / no dedicated sheet” assertions for invisible transport controllers.
- **Modify:** `src/test/java/com/openggf/game/sonic3k/TestSonic3kObjectArtProvider.java`
  Keep provider-level sheet registration and renderer-order behavior green while adding a CNZ traversal registration canary.
- **Create:** `src/test/java/com/openggf/tests/TestS3kCnzLocalTraversalHeadless.java`
  Headless behavior tests for `Balloon`, `RisingPlatform`, `TrapDoor`, and `HoverFan`.
- **Create:** `src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java`
  Headless behavior tests for `Cannon` and `Cylinder`, including forced rolling, control lock, and collision-radii changes.
- **Create:** `src/test/java/com/openggf/tests/TestS3kCnzTubeTraversalHeadless.java`
  Headless path-follow and release tests for `VacuumTube` and `SpiralTube`.
- **Modify:** `src/test/java/com/openggf/game/sonic3k/TestS3kCnzVisualCapture.java`
  Add representative engine capture beats for `HoverFan`, `Cannon`, `Cylinder`, `VacuumTube`, and `SpiralTube`.
- **Create:** `docs/architecture/validation/2026-04-16-s3k-cnz-traversal-objects-validation.md`
  Record the ROM-vs-engine results for each required traversal-object validation beat.

### Object IDs For This Plan

- `0x41` = `Obj_CNZBalloon`
- `0x42` = `Obj_CNZCannon`
- `0x43` = `Obj_CNZRisingPlatform`
- `0x44` = `Obj_CNZTrapDoor`
- `0x46` = `Obj_CNZHoverFan`
- `0x47` = `Obj_CNZCylinder`
- `0x48` = `Obj_CNZVacuumTube`
- `0x4C` = `Obj_CNZSpiralTube`

Deferred adjacent slots that this plan must not claim:

- `0x45` = `Obj_CNZLightBulb`
- `0x49` = `Obj_CNZGiantWheel`
- `0x4A` = `Obj_Bumper`
- `0x4B` = `Obj_CNZTriangleBumpers`
- `0x4D` = `Obj_CNZBarberPoleSprite`
- `0x4E` = `Obj_CNZWireCage`

### ROM Anchors To Cite In Code

- `Obj_CNZBalloon`
- `Obj_CNZCannon`
- `Map - Cannon.asm`
- `DPLC - Cannon.asm`
- `Obj_CNZRisingPlatform`
- `Map - Rising Platform.asm`
- `Anim - Rising Platform.asm`
- `Obj_CNZTrapDoor`
- `Map - Trap Door.asm`
- `Anim - Trap Door.asm`
- `Obj_CNZHoverFan`
- `Map - Hover Fan.asm`
- `Obj_CNZCylinder`
- `Map - Cylinder.asm`
- `Obj_CNZVacuumTube`
- `Obj_CNZSpiralTube`
- Any subtype or path tables referenced from `docs/skdisasm/Levels/CNZ/...`

### Addressing Guardrail

- Before adding any ROM constant, verify whether the anchor is on the S&K side or Sonic 3 side of the lock-on ROM.
- If an anchor is Sonic 3-side, document the offset handling in the constant or loader method Javadoc instead of copying the disassembly address blindly.
- Do not write plan tasks that depend on “looking this up later”; every implementation task below assumes the agent verifies the final ROM offset before coding.

### Documentation Rule For Every Task

- Add class-level Javadocs naming the exact ROM object and its gameplay role.
- Add method Javadocs or concise block comments for every subtype split, state-machine transition, capture/release rule, forced animation/radius write, and art-loading choice that is not obvious from the Java code.
- When an object is an invisible controller and does not own a dedicated sprite sheet, document that explicitly in both the production class and the art test.

### Default Verification Commands

```bash
mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestCnzTraversalRegistry,TestCnzTraversalObjectArt,TestSonic3kObjectArtProvider,TestSonic3kCnzRuntimeStateRegistration,TestSonic3kCnzScroll,TestS3kCnzWaterHelpersHeadless,TestS3kCnzTeleporterRouteHeadless,TestS3kCnzLocalTraversalHeadless,TestS3kCnzDirectedTraversalHeadless,TestS3kCnzTubeTraversalHeadless"
```

```bash
mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestS3kCnzVisualCapture"
```

```bash
mvn -q -DskipTests package "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen"
```

## Task 1: Shared Traversal Slot Ownership And Visible-Art Scaffolding

**Spec slice:** Shared groundwork for Slices 1 through 3

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kObjectIds.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtKeys.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArt.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/Sonic3kObjectRegistry.java`
- Create: `src/main/java/com/openggf/game/sonic3k/objects/CnzBalloonInstance.java`
- Create: `src/main/java/com/openggf/game/sonic3k/objects/CnzRisingPlatformInstance.java`
- Create: `src/main/java/com/openggf/game/sonic3k/objects/CnzTrapDoorInstance.java`
- Create: `src/main/java/com/openggf/game/sonic3k/objects/CnzHoverFanInstance.java`
- Create: `src/main/java/com/openggf/game/sonic3k/objects/CnzCannonInstance.java`
- Create: `src/main/java/com/openggf/game/sonic3k/objects/CnzCylinderInstance.java`
- Create: `src/main/java/com/openggf/game/sonic3k/objects/CnzVacuumTubeInstance.java`
- Create: `src/main/java/com/openggf/game/sonic3k/objects/CnzSpiralTubeInstance.java`
- Create: `src/test/java/com/openggf/game/sonic3k/objects/TestCnzTraversalRegistry.java`
- Create: `src/test/java/com/openggf/game/sonic3k/TestCnzTraversalObjectArt.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/TestSonic3kObjectArtProvider.java`

- [ ] **Step 1: Write the failing registry and art-registration tests**

```java
@Test
void s3klTraversalSlotsResolveToConcreteCarnivalNightObjects() {
    Sonic3kObjectRegistry registry = cnzRegistry();

    assertInstanceOf(CnzBalloonInstance.class, registry.create(
            new ObjectSpawn(0x1200, 0x0580, Sonic3kObjectIds.CNZ_BALLOON, 0, 0, false, 0)));
    assertInstanceOf(CnzCannonInstance.class, registry.create(
            new ObjectSpawn(0x1600, 0x0680, Sonic3kObjectIds.CNZ_CANNON, 0, 0, false, 0)));
    assertInstanceOf(CnzRisingPlatformInstance.class, registry.create(
            new ObjectSpawn(0x1800, 0x05A0, Sonic3kObjectIds.CNZ_RISING_PLATFORM, 0, 0, false, 0)));
    assertInstanceOf(CnzTrapDoorInstance.class, registry.create(
            new ObjectSpawn(0x1A00, 0x05C0, Sonic3kObjectIds.CNZ_TRAP_DOOR, 0, 0, false, 0)));
    assertInstanceOf(CnzHoverFanInstance.class, registry.create(
            new ObjectSpawn(0x1C00, 0x05E0, Sonic3kObjectIds.CNZ_HOVER_FAN, 0, 0, false, 0)));
    assertInstanceOf(CnzCylinderInstance.class, registry.create(
            new ObjectSpawn(0x1E00, 0x0600, Sonic3kObjectIds.CNZ_CYLINDER, 0, 0, false, 0)));
    assertInstanceOf(CnzVacuumTubeInstance.class, registry.create(
            new ObjectSpawn(0x2000, 0x0620, Sonic3kObjectIds.CNZ_VACUUM_TUBE, 0, 0, false, 0)));
    assertInstanceOf(CnzSpiralTubeInstance.class, registry.create(
            new ObjectSpawn(0x2200, 0x0640, Sonic3kObjectIds.CNZ_SPIRAL_TUBE, 0, 0, false, 0)));
}

@Test
void carnivalNightTraversalSheetsRegisterForVisibleObjects() throws Exception {
    HeadlessTestFixture.builder().withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0).build();
    Sonic3kObjectArtProvider provider = currentCnzObjectArtProvider();

    assertNotNull(provider.getSheet(Sonic3kObjectArtKeys.CNZ_BALLOON));
    assertNotNull(provider.getSheet(Sonic3kObjectArtKeys.CNZ_CANNON));
    assertNotNull(provider.getSheet(Sonic3kObjectArtKeys.CNZ_RISING_PLATFORM));
    assertNotNull(provider.getSheet(Sonic3kObjectArtKeys.CNZ_TRAP_DOOR));
    assertNotNull(provider.getSheet(Sonic3kObjectArtKeys.CNZ_HOVER_FAN));
    assertNotNull(provider.getSheet(Sonic3kObjectArtKeys.CNZ_CYLINDER));
}

private static Sonic3kObjectRegistry cnzRegistry() {
    return new Sonic3kObjectRegistry() {
        @Override
        protected S3kZoneSet getCurrentZoneSet() {
            return S3kZoneSet.S3KL;
        }
    };
}

private static Sonic3kObjectArtProvider currentCnzObjectArtProvider() {
    return (Sonic3kObjectArtProvider) GameModuleRegistry.getCurrent().getObjectArtProvider();
}
```

- [ ] **Step 2: Run the focused registry/art tests to verify they fail**

Run:

```bash
mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestCnzTraversalRegistry,TestCnzTraversalObjectArt,TestSonic3kObjectArtProvider"
```

Expected: FAIL because the traversal object IDs, art keys, factories, and sheet registrations do not exist yet.

- [ ] **Step 3: Add IDs, visible-art keys, registry factories, and documented object stubs**

```java
// Sonic3kObjectIds.java
public static final int CNZ_BALLOON = 0x41;
public static final int CNZ_CANNON = 0x42;
public static final int CNZ_RISING_PLATFORM = 0x43;
public static final int CNZ_TRAP_DOOR = 0x44;
public static final int CNZ_HOVER_FAN = 0x46;
public static final int CNZ_CYLINDER = 0x47;
public static final int CNZ_VACUUM_TUBE = 0x48;
public static final int CNZ_SPIRAL_TUBE = 0x4C;
```

```java
// Sonic3kObjectArtKeys.java
public static final String CNZ_BALLOON = "cnz_balloon";
public static final String CNZ_CANNON = "cnz_cannon";
public static final String CNZ_RISING_PLATFORM = "cnz_rising_platform";
public static final String CNZ_TRAP_DOOR = "cnz_trap_door";
public static final String CNZ_HOVER_FAN = "cnz_hover_fan";
public static final String CNZ_CYLINDER = "cnz_cylinder";
```

```java
// Sonic3kObjectRegistry.java
factories.put(Sonic3kObjectIds.CNZ_BALLOON, (spawn, registry) -> {
    S3kZoneSet zoneSet = getCurrentZoneSet();
    if (zoneSet != S3kZoneSet.S3KL) {
        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
    }
    return new CnzBalloonInstance(spawn);
});
```

```java
/**
 * Carnival Night Zone balloon launcher (`Obj_CNZBalloon`, object $41).
 *
 * <p>This class starts as a registry-owned stub so the traversal slots stop
 * resolving to placeholders before behavior is filled in. Later tasks replace
 * the temporary no-op update body with ROM-backed subtype and bounce logic.
 */
public final class CnzBalloonInstance extends AbstractObjectInstance {
    public CnzBalloonInstance(ObjectSpawn spawn) {
        super(spawn, "CNZBalloon");
    }

    @Override
    public void update(int frameCounter, PlayableEntity playerEntity) {
        // Filled in by Task 2.
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Filled in by later tasks once the visible sheet hookup is in place.
    }
}
```

```java
// Sonic3kObjectArtProvider.java
if (zoneIndex == 0x03) {
    registerSheet(Sonic3kObjectArtKeys.CNZ_BALLOON, art.buildCnzBalloonSheet());
    registerSheet(Sonic3kObjectArtKeys.CNZ_CANNON, art.loadCnzCannonSheet(rom));
    registerSheet(Sonic3kObjectArtKeys.CNZ_RISING_PLATFORM, art.buildCnzRisingPlatformSheet());
    registerSheet(Sonic3kObjectArtKeys.CNZ_TRAP_DOOR, art.buildCnzTrapDoorSheet());
    registerSheet(Sonic3kObjectArtKeys.CNZ_HOVER_FAN, art.buildCnzHoverFanSheet());
    registerSheet(Sonic3kObjectArtKeys.CNZ_CYLINDER, art.buildCnzCylinderSheet());
}
```

- [ ] **Step 4: Run the focused registry/art tests again**

Run:

```bash
mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestCnzTraversalRegistry,TestCnzTraversalObjectArt,TestSonic3kObjectArtProvider"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/constants/Sonic3kObjectIds.java src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtKeys.java src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArt.java src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java src/main/java/com/openggf/game/sonic3k/objects/Sonic3kObjectRegistry.java src/main/java/com/openggf/game/sonic3k/objects/CnzBalloonInstance.java src/main/java/com/openggf/game/sonic3k/objects/CnzRisingPlatformInstance.java src/main/java/com/openggf/game/sonic3k/objects/CnzTrapDoorInstance.java src/main/java/com/openggf/game/sonic3k/objects/CnzHoverFanInstance.java src/main/java/com/openggf/game/sonic3k/objects/CnzCannonInstance.java src/main/java/com/openggf/game/sonic3k/objects/CnzCylinderInstance.java src/main/java/com/openggf/game/sonic3k/objects/CnzVacuumTubeInstance.java src/main/java/com/openggf/game/sonic3k/objects/CnzSpiralTubeInstance.java src/test/java/com/openggf/game/sonic3k/objects/TestCnzTraversalRegistry.java src/test/java/com/openggf/game/sonic3k/TestCnzTraversalObjectArt.java src/test/java/com/openggf/game/sonic3k/TestSonic3kObjectArtProvider.java
git commit -m "feat: claim cnz traversal object slots"
```

## Task 2: Balloon And Rising Platform Local Traversal

**Spec slice:** Slice 1

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/CnzBalloonInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/CnzRisingPlatformInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArt.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/TestCnzTraversalObjectArt.java`
- Create: `src/test/java/com/openggf/tests/TestS3kCnzLocalTraversalHeadless.java`

- [ ] **Step 1: Write the failing Balloon and Rising Platform tests**

```java
@Test
void balloonLaunchesPlayerUsingCentreCoordinatesAndRomBounceSpeed() {
    HeadlessTestFixture fixture = HeadlessTestFixture.builder()
            .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
            .build();

    CnzBalloonInstance balloon = spawnBalloon(0x19C0, 0x05B0, 0x00);
    fixture.sprite().setCentreX((short) 0x19C0);
    fixture.sprite().setCentreY((short) 0x05A8);
    fixture.sprite().setYSpeed((short) 0x0200);

    balloon.update(0, fixture.sprite());

    assertTrue(fixture.sprite().isAir());
    assertTrue(fixture.sprite().getYSpeed() < 0, "Balloon should reverse Y motion into an upward launch");
    assertEquals(0x19C0, fixture.sprite().getCentreX(), "ROM x_pos writes must use centre coordinates");
}

@Test
void risingPlatformStartsOnContactMovesToSubtypeLimitAndStops() {
    HeadlessTestFixture fixture = HeadlessTestFixture.builder()
            .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
            .build();

    CnzRisingPlatformInstance platform = spawnRisingPlatform(0x1D80, 0x06A0, 0x20);
    fixture.sprite().setCentreX((short) 0x1D80);
    fixture.sprite().setCentreY((short) 0x0690);

    for (int frame = 0; frame < 96; frame++) {
        platform.update(frame, fixture.sprite());
    }

    assertTrue(platform.wasTriggeredForTest());
    assertEquals(0x06A0 - platform.getSubtypeTravelForTest(), platform.getCentreY());
    assertEquals(0, platform.getYSpeedForTest());
}

private static CnzBalloonInstance spawnBalloon(int x, int y, int subtype) {
    CnzBalloonInstance object = new CnzBalloonInstance(new ObjectSpawn(x, y, Sonic3kObjectIds.CNZ_BALLOON, subtype, 0, false, 0));
    object.setServices(new DefaultObjectServices(RuntimeManager.getCurrent()));
    return object;
}

private static CnzRisingPlatformInstance spawnRisingPlatform(int x, int y, int subtype) {
    CnzRisingPlatformInstance object = new CnzRisingPlatformInstance(new ObjectSpawn(x, y, Sonic3kObjectIds.CNZ_RISING_PLATFORM, subtype, 0, false, 0));
    object.setServices(new DefaultObjectServices(RuntimeManager.getCurrent()));
    return object;
}
```

- [ ] **Step 2: Run the local traversal test to verify it fails**

Run:

```bash
mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestS3kCnzLocalTraversalHeadless"
```

Expected: FAIL because the object stubs do not yet implement bounce or platform movement.

- [ ] **Step 3: Implement Balloon and Rising Platform with ROM-backed comments**

```java
/**
 * ROM object: `Obj_CNZBalloon`.
 *
 * <p>The balloon is a local launcher, not a transport path object. The player
 * touches the balloon, the object recenters them on the balloon's `x_pos`,
 * applies the ROM bounce speed, and immediately returns normal player control.
 */
private void launchPlayer(AbstractPlayableSprite player) {
    player.setCentreX((short) getCentreX());
    player.setCentreY((short) (getCentreY() - CONTACT_Y_OFFSET));
    player.setAir(true);
    player.setRolling(true);
    player.setYSpeed((short) ROM_BALLOON_BOUNCE_Y_SPEED);
}
```

```java
/**
 * ROM object: `Obj_CNZRisingPlatform`.
 *
 * <p>Subtype bits select travel height and trigger behavior. The platform
 * keeps ownership of its own motion instead of delegating to a CNZ manager so
 * the Java state machine remains traceable to the disassembly routine.
 */
private void updateMovingPlatform() {
    if (!triggered) {
        triggered = isPlayerStandingOnTop();
        return;
    }

    int targetY = originY - subtypeTravelPixels;
    if (getCentreY() > targetY) {
        setCentreY((short) (getCentreY() - riseStepPixels));
        ySpeedForTest = -riseStepPixels;
    } else {
        setCentreY((short) targetY);
        ySpeedForTest = 0;
    }
}
```

Also add the narrow package-private test hooks referenced above:

```java
boolean wasTriggeredForTest() { return triggered; }
int getSubtypeTravelForTest() { return subtypeTravelPixels; }
int getYSpeedForTest() { return ySpeedForTest; }
```

Art for this task:

- `Balloon`: `buildCnzBalloonSheet()` must state whether it is ROM-parsed from `Map - Balloon.asm` or a justified hand-authored fallback.
- `RisingPlatform`: `buildCnzRisingPlatformSheet()` must state whether it is ROM-parsed from `Map - Rising Platform.asm` / `Anim - Rising Platform.asm` or a justified fallback.

- [ ] **Step 4: Run the local traversal test and focused art test**

Run:

```bash
mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestS3kCnzLocalTraversalHeadless,TestCnzTraversalObjectArt"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/CnzBalloonInstance.java src/main/java/com/openggf/game/sonic3k/objects/CnzRisingPlatformInstance.java src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArt.java src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java src/test/java/com/openggf/game/sonic3k/TestCnzTraversalObjectArt.java src/test/java/com/openggf/tests/TestS3kCnzLocalTraversalHeadless.java
git commit -m "feat: implement cnz balloon and rising platform"
```

## Task 3: Trap Door And Hover Fan Local Traversal

**Spec slice:** Slice 1

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/CnzTrapDoorInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/CnzHoverFanInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArt.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/TestCnzTraversalObjectArt.java`
- Modify: `src/test/java/com/openggf/tests/TestS3kCnzLocalTraversalHeadless.java`

- [ ] **Step 1: Write the failing Trap Door and Hover Fan tests**

```java
@Test
void trapDoorOpensOnContactRemovesTopCollisionThenClosesBackToSolid() {
    HeadlessTestFixture fixture = HeadlessTestFixture.builder()
            .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
            .build();

    CnzTrapDoorInstance trapDoor = spawnTrapDoor(0x2280, 0x06D0, 0x00);
    fixture.sprite().setCentreX((short) 0x2280);
    fixture.sprite().setCentreY((short) 0x06C0);

    for (int frame = 0; frame < 12; frame++) {
        trapDoor.update(frame, fixture.sprite());
    }
    assertFalse(trapDoor.isSolidForTest(), "Open trap door should stop behaving like a solid top platform");

    for (int frame = 12; frame < 12 + trapDoor.getCloseDelayForTest(); frame++) {
        trapDoor.update(frame, fixture.sprite());
    }
    assertTrue(trapDoor.isSolidForTest(), "Trap door should restore collision after the ROM close window");
}

@Test
void hoverFanAppliesVerticalPushOnlyInsideRomWindow() {
    HeadlessTestFixture fixture = HeadlessTestFixture.builder()
            .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
            .build();

    CnzHoverFanInstance fan = spawnHoverFan(0x27E0, 0x07A0, 0x00);
    fixture.sprite().setCentreX((short) 0x27E0);
    fixture.sprite().setCentreY((short) 0x0790);
    fixture.sprite().setAir(true);
    fixture.sprite().setYSpeed((short) 0x0100);

    fan.update(0, fixture.sprite());
    assertTrue(fixture.sprite().getYSpeed() < 0, "Hover fan should push upward while the player is inside its window");

    fixture.sprite().setCentreX((short) 0x2900);
    fixture.sprite().setYSpeed((short) 0x0100);
    fan.update(1, fixture.sprite());
    assertEquals(0x0100, fixture.sprite().getYSpeed(), "Outside the hover fan window the object must not apply force");
}

private static CnzTrapDoorInstance spawnTrapDoor(int x, int y, int subtype) {
    CnzTrapDoorInstance object = new CnzTrapDoorInstance(new ObjectSpawn(x, y, Sonic3kObjectIds.CNZ_TRAP_DOOR, subtype, 0, false, 0));
    object.setServices(new DefaultObjectServices(RuntimeManager.getCurrent()));
    return object;
}

private static CnzHoverFanInstance spawnHoverFan(int x, int y, int subtype) {
    CnzHoverFanInstance object = new CnzHoverFanInstance(new ObjectSpawn(x, y, Sonic3kObjectIds.CNZ_HOVER_FAN, subtype, 0, false, 0));
    object.setServices(new DefaultObjectServices(RuntimeManager.getCurrent()));
    return object;
}
```

- [ ] **Step 2: Run the local traversal test to verify it fails**

Run:

```bash
mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestS3kCnzLocalTraversalHeadless"
```

Expected: FAIL because the trap-door and hover-fan stubs do not yet implement the ROM state transitions.

- [ ] **Step 3: Implement Trap Door and Hover Fan with subtype/state comments**

```java
private void updateTrapDoor() {
    switch (state) {
        case WAITING -> {
            if (isPlayerStandingOnTop()) {
                state = State.OPENING;
                frameCounter = 0;
            }
        }
        case OPENING -> {
            frameCounter++;
            solidTop = false;
            if (frameCounter >= OPEN_FRAMES) {
                state = State.CLOSING_DELAY;
                frameCounter = 0;
            }
        }
        case CLOSING_DELAY -> {
            frameCounter++;
            if (frameCounter >= closeDelayFrames) {
                state = State.WAITING;
                solidTop = true;
            }
        }
    }
}
```

```java
/**
 * `Obj_CNZHoverFan` is a force-volume object. The ROM checks a bounded window
 * around the fan and applies a fixed upward acceleration while the player stays
 * inside that box. We keep the same behavior object-local and document the
 * exact window constants next to the test seam.
 */
private void applyFanForce(AbstractPlayableSprite player) {
    if (!isInsideForceWindow(player)) {
        return;
    }
    player.setAir(true);
    player.setYSpeed((short) Math.max(player.getYSpeed() - FAN_PUSH_STEP, FAN_MAX_UPWARD_SPEED));
}
```

Also add the narrow package-private trap-door hooks used by the test:

```java
boolean isSolidForTest() { return solidTop; }
int getCloseDelayForTest() { return closeDelayFrames; }
```

Art for this task:

- `TrapDoor`: `buildCnzTrapDoorSheet()` must state whether it is ROM-parsed from `Map - Trap Door.asm` / `Anim - Trap Door.asm` or a justified fallback.
- `HoverFan`: `buildCnzHoverFanSheet()` must state whether it is ROM-parsed from `Map - Hover Fan.asm` or a justified fallback.

- [ ] **Step 4: Run the updated local traversal tests and focused package build**

Run:

```bash
mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestS3kCnzLocalTraversalHeadless,TestCnzTraversalObjectArt"
mvn -q -DskipTests package "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/CnzTrapDoorInstance.java src/main/java/com/openggf/game/sonic3k/objects/CnzHoverFanInstance.java src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArt.java src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java src/test/java/com/openggf/game/sonic3k/TestCnzTraversalObjectArt.java src/test/java/com/openggf/tests/TestS3kCnzLocalTraversalHeadless.java
git commit -m "feat: implement cnz trap door and hover fan"
```

## Task 4: Cannon Directed Traversal And DPLC Parity

**Spec slice:** Slice 2

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/CnzCannonInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArt.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/TestCnzTraversalObjectArt.java`
- Create: `src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java`

- [ ] **Step 1: Write the failing Cannon tests**

```java
@Test
void cannonCapturesForcesRollingRadiiAndLaunchesUsingSubtypeVector() {
    HeadlessTestFixture fixture = HeadlessTestFixture.builder()
            .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
            .build();

    CnzCannonInstance cannon = spawnCannon(0x31C0, 0x07A0, 0x02);
    fixture.sprite().setCentreX((short) 0x31C0);
    fixture.sprite().setCentreY((short) 0x0798);

    cannon.update(0, fixture.sprite());
    assertTrue(fixture.sprite().isControlLocked());
    assertTrue(fixture.sprite().isObjectControlled());
    assertTrue(fixture.sprite().getRolling());
    assertEquals((short) 0x0E, fixture.sprite().getYRadius());
    assertEquals((short) 0x07, fixture.sprite().getXRadius());

    for (int frame = 1; frame <= cannon.getLaunchDelayForTest(); frame++) {
        cannon.update(frame, fixture.sprite());
    }

    assertFalse(fixture.sprite().isControlLocked());
    assertTrue(fixture.sprite().getXSpeed() != 0 || fixture.sprite().getYSpeed() != 0);
}

@Test
void cannonArtUsesDedicatedMappingsAndDplcSheet() throws Exception {
    HeadlessTestFixture.builder().withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0).build();
    Sonic3kObjectArtProvider provider = currentCnzObjectArtProvider();
    ObjectSpriteSheet sheet = provider.getSheet(Sonic3kObjectArtKeys.CNZ_CANNON);

    assertNotNull(sheet);
    assertTrue(sheet.getFrameCount() > 0);
    assertTrue(sheet.getPatterns().length > 0, "Cannon should load dedicated art rather than a placeholder sheet");
}

private static CnzCannonInstance spawnCannon(int x, int y, int subtype) {
    CnzCannonInstance object = new CnzCannonInstance(new ObjectSpawn(x, y, Sonic3kObjectIds.CNZ_CANNON, subtype, 0, false, 0));
    object.setServices(new DefaultObjectServices(RuntimeManager.getCurrent()));
    return object;
}
```

- [ ] **Step 2: Run the Cannon test to verify it fails**

Run:

```bash
mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestS3kCnzDirectedTraversalHeadless,TestCnzTraversalObjectArt"
```

Expected: FAIL because Cannon still has stub behavior and no verified DPLC-backed implementation.

- [ ] **Step 3: Implement Cannon capture/launch logic and dedicated art loading**

```java
private void capturePlayer(AbstractPlayableSprite player) {
    player.setObjectControlled(true);
    player.setControlLocked(true);
    player.setRolling(true);
    player.applyRollingRadii(false);
    player.setAnimationId(ROLLING_ANIMATION_ID);
    player.setCentreX((short) getCentreX());
    player.setCentreY((short) getCentreY());
    launchCountdown = subtypeLaunchDelay(subtype);
}

private void launchPlayer(AbstractPlayableSprite player) {
    LaunchVector vector = subtypeLaunchVector(subtype);
    player.setXSpeed((short) vector.xVel());
    player.setYSpeed((short) vector.yVel());
    player.setObjectControlled(false);
    player.setControlLocked(false);
}
```

Also add the package-private launch-countdown hook used by the test:

```java
int getLaunchDelayForTest() { return launchCountdownInitialValue; }
```

Also lift the forced rolling-radii writes into named ROM constants instead of leaving them as test-only magic numbers:

```java
private static final short ROLL_Y_RADIUS = 0x0E; // ROM rolling y_radius during object-controlled traversal
private static final short ROLL_X_RADIUS = 0x07; // ROM rolling x_radius during object-controlled traversal
```

```java
/**
 * `Obj_CNZCannon` uses `Map - Cannon.asm` plus `DPLC - Cannon.asm`.
 *
 * <p>The mapping and DPLC anchors must be documented with their final ROM
 * offsets after the Sonic 3-side lock-on adjustment has been verified.
 */
public ObjectSpriteSheet loadCnzCannonSheet(Rom rom) {
    Pattern[] patterns = loadKosinskiModuledPatterns(rom, Sonic3kConstants.ART_KOSM_CNZ_CANNON_ADDR);
    List<SpriteMappingFrame> mappings = S3kSpriteDataLoader.loadMappingFrames(reader, Sonic3kConstants.MAP_CNZ_CANNON_ADDR);
    List<SpriteDplcFrame> dplc = S3kSpriteDataLoader.loadDplcFrames(reader, Sonic3kConstants.DPLC_CNZ_CANNON_ADDR);
    return buildDplcBackedSheet(patterns, mappings, dplc, 0);
}
```

Art for this task:

- `Cannon`: ROM-parsed mappings + DPLC + art. The method Javadoc must state the verified ROM side and final offsets for art, mappings, and DPLC data.

- [ ] **Step 4: Run the Cannon test and full directed-traversal package build**

Run:

```bash
mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestS3kCnzDirectedTraversalHeadless,TestCnzTraversalObjectArt,TestSonic3kObjectArtProvider"
mvn -q -DskipTests package "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/CnzCannonInstance.java src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArt.java src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java src/test/java/com/openggf/game/sonic3k/TestCnzTraversalObjectArt.java src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java
git commit -m "feat: implement cnz cannon traversal"
```

## Task 5: Cylinder Directed Traversal And Release Parity

**Spec slice:** Slice 2

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/CnzCylinderInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArt.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/TestCnzTraversalObjectArt.java`
- Modify: `src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java`

- [ ] **Step 1: Write the failing Cylinder tests**

```java
@Test
void cylinderCapturesPlayerAppliesRollingRadiiAndReleasesAtSubtypeExit() {
    HeadlessTestFixture fixture = HeadlessTestFixture.builder()
            .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
            .build();

    CnzCylinderInstance cylinder = spawnCylinder(0x38C0, 0x0800, 0x01);
    fixture.sprite().setCentreX((short) 0x38C0);
    fixture.sprite().setCentreY((short) 0x07F0);

    cylinder.update(0, fixture.sprite());
    assertTrue(fixture.sprite().isControlLocked());
    assertTrue(fixture.sprite().getRolling());

    for (int frame = 1; frame <= cylinder.getRouteFrameCountForTest(); frame++) {
        cylinder.update(frame, fixture.sprite());
    }

    assertFalse(fixture.sprite().isControlLocked());
    assertFalse(fixture.sprite().isObjectControlled());
    assertEquals(cylinder.getExpectedExitXForTest(), fixture.sprite().getCentreX());
    assertEquals(cylinder.getExpectedExitYForTest(), fixture.sprite().getCentreY());
}

private static CnzCylinderInstance spawnCylinder(int x, int y, int subtype) {
    CnzCylinderInstance object = new CnzCylinderInstance(new ObjectSpawn(x, y, Sonic3kObjectIds.CNZ_CYLINDER, subtype, 0, false, 0));
    object.setServices(new DefaultObjectServices(RuntimeManager.getCurrent()));
    return object;
}
```

- [ ] **Step 2: Run the directed-traversal tests to verify Cylinder still fails**

Run:

```bash
mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestS3kCnzDirectedTraversalHeadless"
```

Expected: FAIL because the cylinder stub does not yet implement routing, control lock, or release.

- [ ] **Step 3: Implement Cylinder capture/path/release with ROM-backed comments**

```java
private void beginCylinderRoute(AbstractPlayableSprite player) {
    player.setObjectControlled(true);
    player.setControlLocked(true);
    player.setRolling(true);
    player.applyRollingRadii(false);
    routeIndex = 0;
}

private void advanceCylinderRoute(AbstractPlayableSprite player) {
    RoutePoint point = routePoints[routeIndex];
    player.setCentreX((short) point.centerX());
    player.setCentreY((short) point.centerY());
    routeIndex++;
    if (routeIndex >= routePoints.length) {
        player.setObjectControlled(false);
        player.setControlLocked(false);
    }
}
```

Also add the package-private route test hooks used above:

```java
int getRouteFrameCountForTest() { return routePoints.length; }
int getExpectedExitXForTest() { return routePoints[routePoints.length - 1].centerX(); }
int getExpectedExitYForTest() { return routePoints[routePoints.length - 1].centerY(); }
```

Route and art for this task:

- `Cylinder` route points must be documented as ROM-extracted or as a justified engine-side transcription with the source label named in Javadoc.
- `Cylinder` art must document whether `buildCnzCylinderSheet()` is ROM-parsed from `Map - Cylinder.asm` or a justified fallback.

```java
// Sonic3kObjectArt.java
public ObjectSpriteSheet buildCnzCylinderSheet() {
    // The cylinder is a visible CNZ traversal object and should reuse the ROM
    // mapping anchor rather than hardcoded Java pieces.
    return buildLevelArtSheetFromRom(Sonic3kConstants.MAP_CNZ_CYLINDER_ADDR,
            Sonic3kConstants.ARTTILE_CNZ_MISC + Sonic3kConstants.ARTTILE_CNZ_CYLINDER_OFFSET,
            2);
}
```

- [ ] **Step 4: Run the directed-traversal tests and focused art canary**

Run:

```bash
mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestS3kCnzDirectedTraversalHeadless,TestCnzTraversalObjectArt"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/CnzCylinderInstance.java src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArt.java src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java src/test/java/com/openggf/game/sonic3k/TestCnzTraversalObjectArt.java src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java
git commit -m "feat: implement cnz cylinder traversal"
```

## Task 6: Vacuum Tube Path Verification And Transport Implementation

**Spec slice:** Slice 3

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/objects/CnzTubePathTables.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/CnzVacuumTubeInstance.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/TestCnzTraversalObjectArt.java`
- Create: `src/test/java/com/openggf/tests/TestS3kCnzTubeTraversalHeadless.java`

- [ ] **Step 0: Verify tube path data format, ROM side, and visible-art ownership**

Use `RomOffsetFinder` and the S3K disassembly to verify these preconditions before writing `CnzTubePathTables`:

1. Whether `Obj_CNZVacuumTube` path data lives on the S&K side or the Sonic 3 side of the lock-on ROM.
2. Whether `Obj_CNZSpiralTube` path data lives on the S&K side or the Sonic 3 side of the lock-on ROM.
3. Whether each object uses waypoint pairs compatible with `WaypointPathFollower`, phase tables closer to `HCZTwistingLoopObjectInstance`, or another format.
4. Whether both objects share one table family or use separate data sources.
5. Whether either object owns mappings or `make_art_tile` references. If either one does, add a sheet for it instead of treating it as controller-only.

Record the findings in `CnzTubePathTables` class Javadoc before writing behavior code.

- [ ] **Step 1: Write the failing Vacuum Tube path and behavior tests**

```java
@Test
void vacuumTubeSubtypeLookupReturnsDocumentedRomPathFamily() {
    assertTrue(CnzTubePathTables.vacuumPathCount() > 0);
    assertEquals(0x0048, CnzTubePathTables.vacuumObjectId());
    assertTrue(CnzTubePathTables.describeVacuumSource().contains("S&K")
            || CnzTubePathTables.describeVacuumSource().contains("Sonic 3"));
}

@Test
void vacuumTubeCapturesUsesWaypointFollowerAndReleasesAtRomExit() {
    HeadlessTestFixture fixture = HeadlessTestFixture.builder()
            .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
            .build();

    CnzVacuumTubeInstance tube = spawnVacuumTube(0x3EC0, 0x07F0, 0x03);
    fixture.sprite().setCentreX((short) 0x3EC0);
    fixture.sprite().setCentreY((short) 0x07E8);

    tube.update(0, fixture.sprite());

    assertTrue(fixture.sprite().isControlLocked());
    assertTrue(fixture.sprite().getRolling());
    assertTrue(fixture.sprite().isObjectControlled());

    for (int frame = 1; frame <= tube.getExpectedTravelFramesForTest(); frame++) {
        tube.update(frame, fixture.sprite());
    }

    assertFalse(fixture.sprite().isControlLocked());
    assertFalse(fixture.sprite().isObjectControlled());
    assertEquals(tube.getExpectedExitPointForTest().x(), fixture.sprite().getCentreX());
    assertEquals(tube.getExpectedExitPointForTest().y(), fixture.sprite().getCentreY());
}

private static CnzVacuumTubeInstance spawnVacuumTube(int x, int y, int subtype) {
    CnzVacuumTubeInstance object = new CnzVacuumTubeInstance(new ObjectSpawn(x, y, Sonic3kObjectIds.CNZ_VACUUM_TUBE, subtype, 0, false, 0));
    object.setServices(new DefaultObjectServices(RuntimeManager.getCurrent()));
    return object;
}
```

- [ ] **Step 2: Run the tube-traversal tests to verify Vacuum Tube still fails**

Run:

```bash
mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestS3kCnzTubeTraversalHeadless"
```

Expected: FAIL because there is no path-table implementation and the Vacuum Tube object still has stub behavior.

- [ ] **Step 3: Implement documented path tables and Vacuum Tube transport**

```java
/**
 * Shared CNZ tube path data extracted from the disassembly for `Obj_CNZVacuumTube`
 * and `Obj_CNZSpiralTube`.
 *
 * <p>Each table entry must name the original label and whether the data came
 * from the S&K side or the Sonic 3 side after lock-on offset verification.
 */
public final class CnzTubePathTables {
    public record PathDef(String label, int[] points, boolean reverseOnSubtypeBit) {}

    public static PathDef vacuumPath(int subtype) {
        return VACUUM_PATHS[subtype & 0x1F];
    }
}
```

The class Javadoc must explicitly state:

- the verified ROM side for each path family
- whether `VacuumTube` and `SpiralTube` share a table family
- whether either object owns visible art or is controller-only

```java
private void beginRoute(AbstractPlayableSprite player) {
    activePath = CnzTubePathTables.vacuumPath(subtype);
    player.setObjectControlled(true);
    player.setControlLocked(true);
    player.setRolling(true);
    player.applyRollingRadii(false);
    waypointIndex = 0;
    snapToWaypoint(player, activePath.points(), waypointIndex);
    chooseNextVelocity(player);
}

private void chooseNextVelocity(AbstractPlayableSprite player) {
    int[] path = activePath.points();
    int nextIndex = waypointIndex + 2;
    WaypointPathFollower.VelocityResult velocity =
            WaypointPathFollower.calculateWaypointVelocity(
                    player.getCentreX(), player.getCentreY(),
                    path[nextIndex], path[nextIndex + 1], ROUTE_SPEED);
    player.setXSpeed((short) velocity.xVel());
    player.setYSpeed((short) velocity.yVel());
}
```

Also add the package-private Vacuum Tube hooks used by the test:

```java
int getExpectedTravelFramesForTest() { return expectedTravelFrames; }
RoutePoint getExpectedExitPointForTest() { return expectedExitPoint; }
```

- [ ] **Step 4: Run the tube-traversal test and controller-only art assertion**

Run:

```bash
mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestS3kCnzTubeTraversalHeadless,TestCnzTraversalObjectArt"
```

Expected: PASS. `TestCnzTraversalObjectArt` should only assert that `VacuumTube` is controller-only if Step 0 verified that the ROM object owns no mappings or `make_art_tile` path.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/CnzTubePathTables.java src/main/java/com/openggf/game/sonic3k/objects/CnzVacuumTubeInstance.java src/test/java/com/openggf/game/sonic3k/TestCnzTraversalObjectArt.java src/test/java/com/openggf/tests/TestS3kCnzTubeTraversalHeadless.java
git commit -m "feat: implement cnz vacuum tube traversal"
```

## Task 7: Spiral Tube Transport And Twisting-Loop Parity

**Spec slice:** Slice 3

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/CnzTubePathTables.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/CnzSpiralTubeInstance.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/TestCnzTraversalObjectArt.java`
- Modify: `src/test/java/com/openggf/tests/TestS3kCnzTubeTraversalHeadless.java`

- [ ] **Step 1: Write the failing Spiral Tube tests**

```java
@Test
void spiralTubeForcesRollingAnimationAndReleasesWithRestoredControl() {
    HeadlessTestFixture fixture = HeadlessTestFixture.builder()
            .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
            .build();

    CnzSpiralTubeInstance tube = spawnSpiralTube(0x45C0, 0x07A0, 0x01);
    fixture.sprite().setCentreX((short) 0x45C0);
    fixture.sprite().setCentreY((short) 0x0798);

    tube.update(0, fixture.sprite());

    assertTrue(fixture.sprite().isControlLocked());
    assertTrue(fixture.sprite().getRolling());
    assertEquals((short) 0x0E, fixture.sprite().getYRadius());
    assertEquals((short) 0x07, fixture.sprite().getXRadius());

    for (int frame = 1; frame <= tube.getExpectedTravelFramesForTest(); frame++) {
        tube.update(frame, fixture.sprite());
    }

    assertFalse(fixture.sprite().isControlLocked());
    assertFalse(fixture.sprite().isObjectControlled());
    assertEquals(tube.getExpectedExitPointForTest().x(), fixture.sprite().getCentreX());
    assertEquals(tube.getExpectedExitPointForTest().y(), fixture.sprite().getCentreY());
}

private static CnzSpiralTubeInstance spawnSpiralTube(int x, int y, int subtype) {
    CnzSpiralTubeInstance object = new CnzSpiralTubeInstance(new ObjectSpawn(x, y, Sonic3kObjectIds.CNZ_SPIRAL_TUBE, subtype, 0, false, 0));
    object.setServices(new DefaultObjectServices(RuntimeManager.getCurrent()));
    return object;
}
```

- [ ] **Step 2: Run the tube-traversal test to verify Spiral Tube still fails**

Run:

```bash
mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestS3kCnzTubeTraversalHeadless"
```

Expected: FAIL because Spiral Tube still has stub behavior and no verified route table hookup.

- [ ] **Step 3: Implement Spiral Tube using the shared tube tables and HCZ-style rolling parity**

```java
private void beginSpiralRoute(AbstractPlayableSprite player) {
    activePath = CnzTubePathTables.spiralPath(subtype);
    player.setObjectControlled(true);
    player.setControlLocked(true);
    player.setRolling(true);
    player.applyRollingRadii(false);
    player.setAnimationId(ROLLING_ANIMATION_ID);
    progressIndex = 0;
}

private void advanceSpiralRoute(AbstractPlayableSprite player) {
    RoutePoint point = activePath.route()[progressIndex];
    player.setCentreX((short) point.centerX());
    player.setCentreY((short) point.centerY());
    progressIndex++;
    if (progressIndex >= activePath.route().length) {
        player.setObjectControlled(false);
        player.setControlLocked(false);
    }
}
```

Also add the package-private Spiral Tube hooks used by the test:

```java
int getExpectedTravelFramesForTest() { return expectedTravelFrames; }
RoutePoint getExpectedExitPointForTest() { return expectedExitPoint; }
```

```java
// TestCnzTraversalObjectArt.java
@Test
void tubeControllersDocumentTheirNoSheetParity() throws Exception {
    HeadlessTestFixture.builder().withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0).build();
    Sonic3kObjectArtProvider provider = currentCnzObjectArtProvider();

    assertNull(provider.getSheet("cnz_vacuum_tube"),
            "Vacuum Tube should stay sheet-less only if Step 0 verified that the ROM object is only a controller");
    assertNull(provider.getSheet("cnz_spiral_tube"),
            "Spiral Tube should stay sheet-less only if Step 0 verified that the ROM object is only a controller");
}
```

- [ ] **Step 4: Run the full traversal regression gate before validation work**

Run:

```bash
mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestCnzTraversalRegistry,TestCnzTraversalObjectArt,TestSonic3kObjectArtProvider,TestSonic3kCnzRuntimeStateRegistration,TestSonic3kCnzScroll,TestS3kCnzWaterHelpersHeadless,TestS3kCnzTeleporterRouteHeadless,TestS3kCnzLocalTraversalHeadless,TestS3kCnzDirectedTraversalHeadless,TestS3kCnzTubeTraversalHeadless"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/CnzTubePathTables.java src/main/java/com/openggf/game/sonic3k/objects/CnzSpiralTubeInstance.java src/test/java/com/openggf/game/sonic3k/TestCnzTraversalObjectArt.java src/test/java/com/openggf/tests/TestS3kCnzTubeTraversalHeadless.java
git commit -m "feat: implement cnz spiral tube traversal"
```

## Task 8: Visual Capture Extension And Traversal Validation Artifact

**Spec slice:** Validation across Slices 1 through 3

**Files:**
- Modify: `src/test/java/com/openggf/game/sonic3k/TestS3kCnzVisualCapture.java`
- Create: `docs/architecture/validation/2026-04-16-s3k-cnz-traversal-objects-validation.md`

- [ ] **Step 1: Add the validation template and visual-capture beats**

```markdown
# S3K CNZ Traversal Objects Validation

| Beat | ROM anchor | Engine trigger | Result | Notes |
|------|------------|----------------|--------|-------|
| Balloon launch arc | `Obj_CNZBalloon` | Spawn at the first Act 1 balloon and land on it |  |  |
| Rising platform travel limit | `Obj_CNZRisingPlatform` | Stand on subtype-driven platform until it stops |  |  |
| Trap door open/close cadence | `Obj_CNZTrapDoor` | Stand on the door and wait through one cycle |  |  |
| Hover fan push window | `Obj_CNZHoverFan` | Enter and leave the force window in Act 1 |  |  |
| Cannon capture and launch angle | `Obj_CNZCannon` | Trigger one representative cannon subtype |  |  |
| Cylinder capture and release | `Obj_CNZCylinder` | Trigger one representative cylinder subtype |  |  |
| Vacuum tube transport | `Obj_CNZVacuumTube` | Trigger one representative route |  |  |
| Spiral tube transport | `Obj_CNZSpiralTube` | Trigger one representative route |  |  |
```

```java
@Test
void captureTraversalObjectReferenceFrames() throws Exception {
    assumeTrue(initialized, "CNZ visual capture environment was not initialized");

    captureHoverFanFrame();
    captureCannonFrame();
    captureCylinderFrame();
    captureVacuumTubeFrame();
    captureSpiralTubeFrame();
}
```

- [ ] **Step 2: Run the regular regression suite before capturing**

Run:

```bash
mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestCnzTraversalRegistry,TestCnzTraversalObjectArt,TestSonic3kObjectArtProvider,TestSonic3kCnzRuntimeStateRegistration,TestSonic3kCnzScroll,TestS3kCnzWaterHelpersHeadless,TestS3kCnzTeleporterRouteHeadless,TestS3kCnzLocalTraversalHeadless,TestS3kCnzDirectedTraversalHeadless,TestS3kCnzTubeTraversalHeadless"
```

Expected: PASS.

- [ ] **Step 3: Run the visual-capture helper and export the engine PNGs**

Run:

```bash
mvn test "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestS3kCnzVisualCapture"
```

Expected: PASS with new PNGs under `target/s3k-cnz-visual/engine/`.

- [ ] **Step 4: Compare the engine captures against ROM/emulator references and fill in the report**

Open `docs/architecture/validation/2026-04-16-s3k-cnz-traversal-objects-validation.md` and replace the blank `Result` / `Notes` cells with the observed ROM-vs-engine findings. Do not prefill expected outcomes. Use `PASS`, `LIKELY`, `FAIL`, or `SKIP` exactly.

```markdown
| Beat | ROM anchor | Engine trigger | Result | Notes |
|------|------------|----------------|--------|-------|
| Hover fan push window | `Obj_CNZHoverFan` | Enter and leave the force window in Act 1 |  |  |
| Cannon capture and launch angle | `Obj_CNZCannon` | Trigger one representative cannon subtype |  |  |
| Vacuum tube transport | `Obj_CNZVacuumTube` | Trigger one representative route |  |  |
| Spiral tube transport | `Obj_CNZSpiralTube` | Trigger one representative route |  |  |
```

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/openggf/game/sonic3k/TestS3kCnzVisualCapture.java docs/architecture/validation/2026-04-16-s3k-cnz-traversal-objects-validation.md
git commit -m "test: validate cnz traversal objects"
```
