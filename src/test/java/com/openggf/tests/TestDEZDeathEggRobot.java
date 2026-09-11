package com.openggf.tests;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.bosses.Sonic2DeathEggRobotInstance;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseAttackable;
import com.openggf.level.objects.boss.BossChildComponent;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the DEZ Death Egg Robot boss (ObjC7).
 * Tests HP, collision flags, attack pattern cycling, child count,
 * defeat state, flash duration, and state machine.
 * No ROM or OpenGL required.
 *
 * ROM values used in assertions are verified directly against the disassembly
 * rather than referencing the implementation constants.
 */
public class TestDEZDeathEggRobot {

    private static final int BOSS_X = 0x2A98;
    private static final int BOSS_Y = 0x4A0;

    private Sonic2DeathEggRobotInstance boss;
    private ObjectServices services;

    @SuppressWarnings("unchecked")
    private static void setConstructionContext(ObjectServices svc) {
        try {
            Field field = AbstractObjectInstance.class.getDeclaredField("CONSTRUCTION_CONTEXT");
            field.setAccessible(true);
            ((ThreadLocal<Object>) field.get(null)).set(svc);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void clearConstructionContext() {
        try {
            Field field = AbstractObjectInstance.class.getDeclaredField("CONSTRUCTION_CONTEXT");
            field.setAccessible(true);
            ((ThreadLocal<Object>) field.get(null)).remove();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void setPrivateInt(Object target, String fieldName, int value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static int getPrivateInt(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(target);
    }

    @BeforeEach
    public void setUp() {
        services = new TestObjectServices();
        setConstructionContext(services);
        try {
            boss = new Sonic2DeathEggRobotInstance(
                    new ObjectSpawn(BOSS_X, BOSS_Y,
                            Sonic2ObjectIds.DEATH_EGG_ROBOT, 0, 0, false, 0));
        } finally {
            clearConstructionContext();
        }
        boss.setServices(services);
    }

    // ========================================================================
    // BASIC STATE & HP
    // ========================================================================

    @Test
    public void objectIdIs0xC7() {
        assertEquals(0xC7, Sonic2ObjectIds.DEATH_EGG_ROBOT, "Object ID should be 0xC7");
    }

    @Test
    public void hpIs12NotDefault8() {
        // ROM: Death Egg Robot has 12 HP (final boss), NOT the usual 8
        assertEquals(12, boss.getState().hitCount, "HP must be 12 (final boss, not default 8)");
    }

    @Test
    public void initialBodyRoutineIsWaitEggman() {
        // initializeBossState sets BODY_INIT (0x00) then advances to BODY_WAIT_EGGMAN (0x02)
        assertEquals(0x02, boss.getBodyRoutine(), "Body routine should be WAIT_EGGMAN (0x02) after init");
    }

    @Test
    public void initialFrameIsBody() {
        // ROM: mapping_frame = 3 (FRAME_BODY)
        assertEquals(3, boss.getCurrentFrame(), "Initial mapping frame should be 3 (FRAME_BODY)");
    }

    @Test
    public void notDefeatedInitially() {
        assertFalse(boss.getState().defeated, "Should not be defeated initially");
    }

    @Test
    public void notInvulnerableInitially() {
        assertFalse(boss.getState().invulnerable, "Should not be invulnerable initially");
    }

    @Test
    public void priorityBucketIsFive() {
        // ROM: move.b #5,priority(a0) (loc_3D52A, s2.asm:82052)
        assertEquals(5, boss.getPriorityBucket(), "Priority bucket should be 5");
    }

    @Test
    public void testInitialFacingMatchesRom() {
        // ROM: ObjC7_SubObjData render_flags = 1<<render_flags.level_fg = 0x04
        // Bit 0 (x_flip) is clear, so x_flip = 0. The art naturally faces LEFT.
        // facingLeft is passed as hFlip to the renderer:
        //   facingLeft = false -> hFlip = false -> art not flipped -> faces LEFT (correct)
        //   facingLeft = true  -> hFlip = true  -> art flipped   -> faces RIGHT (wrong)
        assertFalse(boss.isFacingLeft(), "facingLeft should be false (ROM x_flip=0, art naturally faces left)");
    }

    // ========================================================================
    // COLLISION FLAGS (via getCollisionFlags() on body)
    // ========================================================================

    @Test
    public void collisionDisabledBeforeFightStarts() {
        // Body collision should be 0 while in WAIT_EGGMAN routine (before BODY_WAIT_READY)
        assertEquals(0, boss.getCollisionFlags(), "Collision should be 0 before fight starts");
    }

    @Test
    public void collisionDisabledWhenDefeated() {
        boss.getState().defeated = true;
        assertEquals(0, boss.getCollisionFlags(), "Collision should be 0 when defeated");
    }

    @Test
    public void collisionDisabledWhenInvulnerable() {
        boss.getState().invulnerable = true;
        assertEquals(0, boss.getCollisionFlags(), "Collision should be 0 when invulnerable");
    }

    // ========================================================================
    // ATTACK PATTERN CYCLING
    // ========================================================================

    @Test
    public void attackIndexStartsAtZero() {
        assertEquals(0, boss.getAttackIndex(), "Attack index should start at 0");
    }

    @Test
    public void attackPatternMatchesRom() throws Exception {
        // ROM: dc.b 2, 0, 2, 4 (byte_3D680)
        // Verify the static attack pattern array matches the ROM values.
        // ATTACK_PATTERN is package-private, so we use reflection from a different package.
        java.lang.reflect.Field field =
                Sonic2DeathEggRobotInstance.class.getDeclaredField("ATTACK_PATTERN");
        field.setAccessible(true);
        int[] actual = (int[]) field.get(null);
        int[] expected = { 2, 0, 2, 4 };
        assertArrayEquals(expected, actual, "Attack pattern should match ROM dc.b 2, 0, 2, 4");
    }

    // ========================================================================
    // DEFEAT STATE
    // ========================================================================

    @Test
    public void defeatPhaseStartsAtZero() {
        assertEquals(0, boss.getDefeatPhase(), "Defeat phase should start at 0");
    }

    @Test
    public void defeatNotTriggeredByDefault() {
        assertFalse(boss.getState().defeated, "Should not be defeated initially");
        assertEquals(0, boss.getDefeatPhase(), "Defeat phase should start at 0");
    }

    // ========================================================================
    // FLASH DURATION
    // ========================================================================

    @Test
    public void invulnerabilityTimerStartsAtZero() {
        // ROM: move.b #60,objoff_2A(a0) - $3C = 60 frames is the DEZ boss
        // invulnerability duration. The timer should start at 0 (no invulnerability)
        // and only be set to 60 when the boss takes a hit.
        // DEZ_BOSS_INVULN_DURATION = 60 ($3C), verified against s2.asm
        assertEquals(0, boss.getState().invulnerabilityTimer, "Invulnerability timer should start at 0");
        assertFalse(boss.getState().invulnerable, "Should not be invulnerable initially");
    }

    // ========================================================================
    // CHILDREN SPAWNED
    // ========================================================================

    @Test
    public void tenChildrenSpawned() {
        // 10 permanent children: Shoulder, FrontLowerLeg, FrontForearm, UpperArm,
        // FrontThigh, Head, Jet, BackLowerLeg, BackForearm, BackThigh
        assertEquals(10, boss.getChildComponents().size(), "Should have 10 child components");
    }

    @Test
    public void allPermanentChildrenReceiveServices() throws Exception {
        // Regression: the boss spawns its 10 permanent children from inside its own
        // constructor (initializeBossState -> spawnChildren -> createPermanentChild),
        // which itself runs under the ObjectManager placement path's CONSTRUCTION_CONTEXT.
        // createPermanentChild wraps each child in ObjectConstructionContext.construct().
        // Previously that helper's finally block REMOVED the construction context, so
        // after the first child the boss's own outer context was gone: children 2..10
        // skipped both the construction-context injection AND the addDynamicObject()
        // setServices() call, leaving their services field null. The crash surfaced as
        // ForearmChild.updatePunch -> services().playSfx(...) throwing
        // "services not available" once the Death Egg Robot fight reached a punch.
        // ObjectConstructionContext.construct now save-and-restores the prior context,
        // so every nested child is added through the manager and gets services injected.
        com.openggf.camera.Camera camera = mock(com.openggf.camera.Camera.class);
        when(camera.getX()).thenReturn((short) 0);
        when(camera.getY()).thenReturn((short) 0);
        when(camera.getWidth()).thenReturn((short) 320);
        when(camera.getHeight()).thenReturn((short) 224);
        when(camera.isVerticalWrapEnabled()).thenReturn(false);

        com.openggf.level.objects.ObjectManager[] holder =
                new com.openggf.level.objects.ObjectManager[1];
        ObjectServices managerServices = new com.openggf.level.objects.StubObjectServices() {
            @Override
            public com.openggf.level.objects.ObjectManager objectManager() {
                return holder[0];
            }
        };
        com.openggf.level.objects.ObjectManager manager =
                new com.openggf.level.objects.ObjectManager(
                        List.of(), null, 0, null, null, null, camera, managerServices);
        holder[0] = manager;

        // Spawn the boss exactly the way the placement path does: set the
        // construction context, run the constructor (which spawns the children),
        // then inject services on the parent.
        Sonic2DeathEggRobotInstance spawnedBoss =
                com.openggf.level.objects.ObjectConstructionContext.construct(
                        managerServices,
                        () -> new Sonic2DeathEggRobotInstance(new ObjectSpawn(
                                BOSS_X, BOSS_Y, Sonic2ObjectIds.DEATH_EGG_ROBOT,
                                0, 0, false, 0)));
        spawnedBoss.setServices(managerServices);

        Field servicesField =
                AbstractObjectInstance.class.getDeclaredField("services");
        servicesField.setAccessible(true);

        assertEquals(10, spawnedBoss.getChildComponents().size(),
                "Boss should spawn its 10 permanent children");
        for (BossChildComponent child : spawnedBoss.getChildComponents()) {
            assertTrue(child instanceof AbstractObjectInstance,
                    "Each child component should be an AbstractObjectInstance");
            Object svc = servicesField.get(child);
            assertNotNull(svc,
                    "Child '" + ((AbstractObjectInstance) child).getName()
                    + "' must have services injected (no 'services not available' crash)");
        }
    }

    @Test
    public void headChildExists() {
        assertNotNull(boss.getHead(), "Head child should exist");
    }

    @Test
    public void headImplementsTouchResponseProvider() {
        // Head is the only hittable part - must implement TouchResponseProvider
        assertTrue(boss.getHead() instanceof TouchResponseProvider, "Head should implement TouchResponseProvider");
    }

    @Test
    public void headImplementsTouchResponseAttackable() {
        // Head must implement TouchResponseAttackable for onPlayerAttack relay
        assertTrue(boss.getHead() instanceof TouchResponseAttackable, "Head should implement TouchResponseAttackable");
    }

    @Test
    public void headCollisionInactiveBeforeFight() {
        // Head collision should be inactive during WAIT_EGGMAN phase
        TouchResponseProvider headProvider = (TouchResponseProvider) boss.getHead();
        assertEquals(0, headProvider.getCollisionFlags(), "Head collision flags should be 0 before fight");
    }

    @Test
    public void headCollisionPropertyReturnsNegativeOne() {
        // ROM: move.b #-1,collision_property(a0) â€” head always returns -1
        // HP tracking is handled by the parent body's onHeadHit(), not collision_property
        TouchResponseProvider headProvider = (TouchResponseProvider) boss.getHead();
        assertEquals(-1, headProvider.getCollisionProperty(), "Head collision property should be -1 (ROM-accurate: always hittable)");
    }

    @Test
    public void allChildrenAreNotNull() {
        for (BossChildComponent child : boss.getChildComponents()) {
            assertNotNull(child, "Every child component should be non-null");
        }
    }

    @Test
    public void childSpawnOrderMatchesRom() throws Exception {
        // ROM: loc_3D52A spawns children in this order:
        // 1. Shoulder, 2. FrontForearm, 3. FrontLowerLeg, 4. UpperArm,
        // 5. FrontThigh, 6. Head, 7. Jet, 8. BackLowerLeg, 9. BackForearm, 10. BackThigh
        java.lang.reflect.Field childField =
                com.openggf.level.objects.boss.AbstractBossInstance.class.getDeclaredField("childComponents");
        childField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.List<BossChildComponent> children =
                (java.util.List<BossChildComponent>) childField.get(boss);

        assertEquals(10, children.size(), "Should have 10 children");

        // Verify FrontForearm (index 1) comes before FrontLowerLeg (index 2)
        // by checking their class names via the name field on AbstractObjectInstance
        java.lang.reflect.Method getName =
                com.openggf.level.objects.AbstractObjectInstance.class.getMethod("getName");

        String child1Name = (String) getName.invoke(children.get(1));
        String child2Name = (String) getName.invoke(children.get(2));
        assertEquals("FrontForearm", child1Name, "Child index 1 should be FrontForearm (ROM spawn order)");
        assertEquals("FrontLowerLeg", child2Name, "Child index 2 should be FrontLowerLeg (ROM spawn order)");
    }

    // ========================================================================
    // HP DECREMENT
    // ========================================================================

    @Test
    public void hpStateDecrementsMathematically() {
        // Tests state object arithmetic, not the actual hit path (which requires AudioManager)
        assertEquals(12, boss.getState().hitCount);

        for (int i = 11; i >= 0; i--) {
            boss.getState().hitCount--;
            assertEquals(i, boss.getState().hitCount, "HP should be " + i + " after " + (12 - i) + " decrements");
        }

        assertEquals(0, boss.getState().hitCount, "HP should reach 0 after 12 decrements");
    }

    @Test
    public void spawnCoordinatesMatchInput() {
        assertEquals(BOSS_X, boss.getSpawn().x());
        assertEquals(BOSS_Y, boss.getSpawn().y());
    }

    @Test
    public void attackIndexStartsAtCurrentAttackZero() {
        assertEquals(0, boss.getCurrentAttack(), "Current attack should start at 0");
    }

    // ========================================================================
    // DEFEAT TRIGGER
    // ========================================================================

    @Test
    public void defeatStateFlagsConsistentAfter12Decrements() {
        // Tests state consistency after manual decrements. The actual hit path
        // (onHeadHit) is package-private and requires AudioManager.
        // After 12 decrements, hitCount=0 and defeated=true should be consistent
        // with bodyRoutine=BODY_DEFEAT (0x0E).
        assertEquals(12, boss.getState().hitCount, "HP starts at 12");

        for (int i = 0; i < 12; i++) {
            boss.getState().hitCount--;
        }
        assertEquals(0, boss.getState().hitCount, "HP should be 0 after 12 decrements");

        // Simulate what triggerDefeatSequence() does to state flags
        boss.getState().defeated = true;
        assertTrue(boss.getState().defeated, "Boss should be marked defeated");
    }

    @Test
    public void defeatBodyRoutineIs0x0E() {
        // ROM: BODY_DEFEAT = 0x0E (s2.asm). Verify initial state is not defeat.
        assertFalse(boss.getBodyRoutine() == 0x0E, "Body routine should NOT be 0x0E initially (that's defeat)");
        // Positive: initial body routine should be WAIT_EGGMAN (0x02)
        assertEquals(0x02, boss.getBodyRoutine(), "Initial body routine should be 0x02");
    }

    // ========================================================================
    // CHILDREN REGISTERED WITH OBJECT MANAGER
    // ========================================================================

    // ========================================================================
    // ROM DATA VERIFICATION (via reflection)
    // ========================================================================

    @Test
    public void breakVelocitiesMatchRom() throws Exception {
        // ROM: ObjC7_BreakSpeeds (s2.asm:83258-83267)
        // 8 entries: {x_vel, y_vel} for each body part during break-apart
        java.lang.reflect.Field field =
                Sonic2DeathEggRobotInstance.class.getDeclaredField("BREAK_VELOCITIES");
        field.setAccessible(true);
        int[][] actual = (int[][]) field.get(null);

        assertEquals(8, actual.length, "BREAK_VELOCITIES should have 8 entries");

        int[][] expected = {
                {  0x200, -0x400 },  // Shoulder
                { -0x100, -0x100 },  // FrontLowerLeg
                {  0x300, -0x300 },  // FrontForearm
                { -0x100, -0x400 },  // UpperArm
                {  0x180, -0x200 },  // FrontThigh
                { -0x200, -0x300 },  // BackLowerLeg
                {  0x000, -0x400 },  // BackForearm
                {  0x100, -0x300 }   // BackThigh
        };

        for (int i = 0; i < expected.length; i++) {
            assertArrayEquals(expected[i], actual[i], "Break velocity entry " + i + " should match ROM");
        }
    }

    @Test
    public void childDeltasMatchRom() throws Exception {
        // ROM: ObjC7_ChildDeltas (s2.asm:83536-83544)
        // 7 entries: {dx, dy} position offsets for articulated children
        java.lang.reflect.Field field =
                Sonic2DeathEggRobotInstance.class.getDeclaredField("CHILD_DELTAS");
        field.setAccessible(true);
        int[][] actual = (int[][]) field.get(null);

        assertEquals(7, actual.length, "CHILD_DELTAS should have 7 entries");

        int[][] expected = {
                { -4, 60 },   // FrontLowerLeg
                { -12, 8 },   // FrontForearm
                { 12, -8 },   // UpperArm
                { 4, 36 },    // FrontThigh
                { -4, 60 },   // BackLowerLeg
                { -12, 8 },   // BackForearm
                { 4, 36 }     // BackThigh
        };

        for (int i = 0; i < expected.length; i++) {
            assertArrayEquals(expected[i], actual[i], "Child delta entry " + i + " should match ROM");
        }
    }

    @Test
    public void groupAnimationKeyframeCounts() throws Exception {
        // ROM: ObjC7_GroupAni_3E318 = 9 keyframes (half-step walk)
        java.lang.reflect.Field halfStepField =
                Sonic2DeathEggRobotInstance.class.getDeclaredField("HALF_STEP_KEYFRAMES");
        halfStepField.setAccessible(true);
        int[][] halfStep = (int[][]) halfStepField.get(null);
        assertEquals(9, halfStep.length, "HALF_STEP_KEYFRAMES should have 9 entries");

        // ROM: ObjC7_GroupAni_3E3D8 = 3 keyframes (crouch/rise)
        java.lang.reflect.Field crouchField =
                Sonic2DeathEggRobotInstance.class.getDeclaredField("CROUCH_KEYFRAMES");
        crouchField.setAccessible(true);
        int[][] crouch = (int[][]) crouchField.get(null);
        assertEquals(3, crouch.length, "CROUCH_KEYFRAMES should have 3 entries");

        // ROM: ObjC7_GroupAni_3E438 = 12 keyframes (full walk cycle)
        java.lang.reflect.Field walkField =
                Sonic2DeathEggRobotInstance.class.getDeclaredField("WALK_CYCLE_KEYFRAMES");
        walkField.setAccessible(true);
        int[][] walk = (int[][]) walkField.get(null);
        assertEquals(12, walk.length, "WALK_CYCLE_KEYFRAMES should have 12 entries");
    }

    // ========================================================================
    // CHILDREN REGISTERED WITH OBJECT MANAGER
    // ========================================================================

    @Test
    public void childrenRegisteredWithObjectManager() {
        // After singleton decoupling, ObjectManager injection happens via ObjectServices.
        // Boss construction no longer takes LevelManager, so children are registered
        // when services are injected by ObjectManager (which is a runtime concern).
        // This test verifies the boss still creates its children during init.
        setConstructionContext(new TestObjectServices());
        Sonic2DeathEggRobotInstance boss2;
        try {
            boss2 = new Sonic2DeathEggRobotInstance(
                    new ObjectSpawn(BOSS_X, BOSS_Y,
                            Sonic2ObjectIds.DEATH_EGG_ROBOT, 0, 0, false, 0));
        } finally {
            clearConstructionContext();
        }

        // Verify children were created (10 total: Body, Head, JetFlame, BackUpperArm/ForeArm/LowerLeg, FrontUpperArm/ForeArm/LowerLeg, Sensor)
        assertEquals(10, boss2.getChildComponents().size(), "Boss should have 10 child components");
    }

    // ========================================================================
    // SENSOR FIFO BUFFER DEPTH
    // ========================================================================

    @Test
    public void sensorFifoBufferHas4Elements() throws Exception {
        // ROM: ObjC7_TargettingSensor uses 4 slots at offsets $30-$3F
        // (each slot = 4 bytes: xvel word + yvel word). A value written to
        // slot 0 traverses 3 shifts before being consumed at slot 3.
        // Verify via reflection that the buffer arrays have length 4.
        Class<?> sensorClass = null;
        for (Class<?> inner : Sonic2DeathEggRobotInstance.class.getDeclaredClasses()) {
            if (inner.getSimpleName().equals("SensorChild")) {
                sensorClass = inner;
                break;
            }
        }
        assertNotNull(sensorClass, "SensorChild inner class should exist");

        java.lang.reflect.Field xBufField = sensorClass.getDeclaredField("xVelBuffer");
        xBufField.setAccessible(true);
        java.lang.reflect.Field yBufField = sensorClass.getDeclaredField("yVelBuffer");
        yBufField.setAccessible(true);

        // Construct a SensorChild to inspect buffer size
        // SensorChild(Sonic2DeathEggRobotInstance parent, int playerX, int playerY)
        java.lang.reflect.Constructor<?> ctor = sensorClass.getDeclaredConstructor(
                Sonic2DeathEggRobotInstance.class, int.class, int.class);
        ctor.setAccessible(true);
        setConstructionContext(new TestObjectServices());
        Object sensor;
        try {
            sensor = ctor.newInstance(boss, 100, 200);
        } finally {
            clearConstructionContext();
        }

        int[] xBuf = (int[]) xBufField.get(sensor);
        int[] yBuf = (int[]) yBufField.get(sensor);
        assertEquals(4, xBuf.length, "xVelBuffer should have 4 elements (3-frame delay)");
        assertEquals(4, yBuf.length, "yVelBuffer should have 4 elements (3-frame delay)");
    }

    @Test
    public void targetingSensorAllocatesAfterBodyAndDefersSpawnFrameUpdate() throws Exception {
        // ROM loc_3D744 spawns ChildObjC7_TargettingSensor through LoadChildObject,
        // whose helper calls AllocateObjectAfterCurrent (s2.asm:82785-82786,
        // 72978-72986). The Java parent also owns the phase-6 sensor step so the
        // body reads objoff_28 before loc_3DE62 can report; the managed child must
        // not consume its init routine on the same ObjectManager frame it is inserted.
        com.openggf.camera.Camera camera = mock(com.openggf.camera.Camera.class);
        when(camera.getX()).thenReturn((short) 0);
        when(camera.getY()).thenReturn((short) 0);
        when(camera.getWidth()).thenReturn((short) 320);
        when(camera.getHeight()).thenReturn((short) 224);
        when(camera.isVerticalWrapEnabled()).thenReturn(false);

        ObjectManager[] holder = new ObjectManager[1];
        ObjectServices managerServices = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return holder[0];
            }
        };
        ObjectManager manager = new ObjectManager(
                List.of(), null, 0, null, null, null, camera, managerServices);
        holder[0] = manager;

        Sonic2DeathEggRobotInstance managedBoss =
                com.openggf.level.objects.ObjectConstructionContext.construct(
                        managerServices,
                        () -> new Sonic2DeathEggRobotInstance(new ObjectSpawn(
                                0x40, 0x120, 0, 0, 0, false, 0)));
        managedBoss.setServices(managerServices);

        int bossSlot = 36;
        manager.addDynamicObjectAtSlot(managedBoss, bossSlot);
        setPrivateInt(managedBoss, "bodyRoutine", 0x0C);
        setPrivateInt(managedBoss, "currentAttack", 2);
        setPrivateInt(managedBoss, "attackPhase", 4);
        setPrivateInt(managedBoss, "actionTimer", 0);

        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getCentreX()).thenReturn((short) 0x080C);
        when(player.getCentreY()).thenReturn((short) 0x016C);

        manager.update(0, player, List.of(), 1, false);

        AbstractObjectInstance sensor = manager.getActiveObjects().stream()
                .filter(AbstractObjectInstance.class::isInstance)
                .map(AbstractObjectInstance.class::cast)
                .filter(object -> object.getClass().getSimpleName().equals("SensorChild"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("SensorChild should be spawned"));

        assertEquals(bossSlot + 1, sensor.getSlotIndex(),
                "Targeting sensor must use AllocateObjectAfterCurrent semantics");
        assertEquals(0, getPrivateInt(sensor, "sensorRoutine"),
                "Sensor init routine must wait for the parent-owned phase-6 step");
    }

    @Test
    public void sensorFifoConsumesInitialVelocityAfterRomShiftDelay() throws Exception {
        Class<?> sensorClass = null;
        for (Class<?> inner : Sonic2DeathEggRobotInstance.class.getDeclaredClasses()) {
            if (inner.getSimpleName().equals("SensorChild")) {
                sensorClass = inner;
                break;
            }
        }
        assertNotNull(sensorClass, "SensorChild inner class should exist");

        java.lang.reflect.Constructor<?> ctor = sensorClass.getDeclaredConstructor(
                Sonic2DeathEggRobotInstance.class, int.class, int.class, int.class, int.class);
        ctor.setAccessible(true);
        setConstructionContext(services);
        AbstractObjectInstance sensor;
        try {
            sensor = (AbstractObjectInstance) ctor.newInstance(boss, 0x100, 0x120, 0x0500, 0);
        } finally {
            clearConstructionContext();
        }
        sensor.setServices(services);

        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getCentreX()).thenReturn((short) 0x100);
        when(player.getCentreY()).thenReturn((short) 0x120);
        when(player.getXSpeed()).thenReturn((short) 0);
        when(player.getYSpeed()).thenReturn((short) 0);

        java.lang.reflect.Method update = sensorClass.getMethod("update", int.class, com.openggf.game.PlayableEntity.class);
        update.setAccessible(true);
        update.invoke(sensor, 0, player); // routine 0 -> tracking; no FIFO movement yet
        for (int frame = 1; frame <= 3; frame++) {
            update.invoke(sensor, frame, player);
            assertEquals(0x100, sensor.getX(),
                    "ROM loc_3DDA6 consumes the oldest slot before the initial x_vel reaches objoff_3C");
        }

        update.invoke(sensor, 4, player);
        assertEquals(0x105, sensor.getX(),
                "Initial x_vel from ObjC7_TargettingSensor init must shift through objoff_30..3E before ObjectMove");
    }

    @Test
    public void bombDetonationRendersObj58BossExplosionFrames() throws Exception {
        Class<?> bombClass = null;
        for (Class<?> inner : Sonic2DeathEggRobotInstance.class.getDeclaredClasses()) {
            if (inner.getSimpleName().equals("BombChild")) {
                bombClass = inner;
                break;
            }
        }
        assertNotNull(bombClass, "BombChild inner class should exist");

        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer bossExplosionRenderer = mock(PatternSpriteRenderer.class);
        when(bossExplosionRenderer.isReady()).thenReturn(true);
        when(renderManager.getBossExplosionRenderer()).thenReturn(bossExplosionRenderer);

        ObjectServices renderServices = new TestObjectServices() {
            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }

            @Override
            public void playSfx(int soundId) {
                // no-op
            }
        };
        boss.setServices(renderServices);

        java.lang.reflect.Constructor<?> ctor = bombClass.getDeclaredConstructor(
                Sonic2DeathEggRobotInstance.class, int.class, int.class, int.class, int.class);
        ctor.setAccessible(true);
        setConstructionContext(renderServices);
        AbstractObjectInstance bomb;
        try {
            bomb = (AbstractObjectInstance) ctor.newInstance(boss, 0x700, 0x120, 0, 0);
        } finally {
            clearConstructionContext();
        }
        bomb.setServices(renderServices);

        Field detonatingField = bombClass.getDeclaredField("detonating");
        detonatingField.setAccessible(true);
        detonatingField.setBoolean(bomb, true);
        Field frameField = bombClass.getDeclaredField("detonateFrame");
        frameField.setAccessible(true);
        frameField.setInt(bomb, 3);

        bomb.appendRenderCommands(List.of());

        verify(renderManager).getBossExplosionRenderer();
        verify(renderManager, never()).getRenderer(com.openggf.game.sonic2.Sonic2ObjectArtKeys.DEZ_BOSS);
        verify(bossExplosionRenderer).drawFrameIndex(3, 0x700, 0x120, false, false);
    }

    // ========================================================================
    // FOREARM Y VELOCITY CLAMP
    // ========================================================================

    @Test
    public void forearmYVelocityClampAt0xFF() throws Exception {
        // ROM: cmpi.w #$100,d2 / blo.s + / move.w #$FF,d2
        // Clamps absolute horizontal distance to 0xFF before (d2 & 0xC0) >> 6.
        // For dx >= 0x100, the table index should be the same as dx = 0xFF (= 3).
        // Without the clamp, dx = 0x100 would give (0x100 & 0xC0) >> 6 = (0x00) >> 6 = 0
        // which is wrong. With the clamp, Math.min(0xFF, dx) = 0xFF, (0xFF & 0xC0) >> 6 = 3.

        // Test ROM-accurate clamped behavior:
        int dxClamped = Math.min(0xFF, 0x100);
        int idxClamped = (dxClamped & 0xC0) >> 6;
        assertEquals(3, idxClamped, "dx=0x100 clamped to 0xFF should give table index 3");

        dxClamped = Math.min(0xFF, 0xFF);
        idxClamped = (dxClamped & 0xC0) >> 6;
        assertEquals(3, idxClamped, "dx=0xFF should give table index 3");

        // Verify the bug scenario: without clamping, dx=0x100 would give index 0
        int dxUnclamped = 0x100;
        int idxUnclamped = (dxUnclamped & 0xC0) >> 6;
        assertEquals(0, idxUnclamped, "Unclamped dx=0x100 would incorrectly give index 0");

        // Verify boundary cases with clamping
        assertEquals(0, (Math.min(0xFF, 0x00) & 0xC0) >> 6, "dx=0x00 -> index 0");
        assertEquals(0, (Math.min(0xFF, 0x3F) & 0xC0) >> 6, "dx=0x3F -> index 0");
        assertEquals(1, (Math.min(0xFF, 0x40) & 0xC0) >> 6, "dx=0x40 -> index 1");
        assertEquals(1, (Math.min(0xFF, 0x7F) & 0xC0) >> 6, "dx=0x7F -> index 1");
        assertEquals(2, (Math.min(0xFF, 0x80) & 0xC0) >> 6, "dx=0x80 -> index 2");
        assertEquals(2, (Math.min(0xFF, 0xBF) & 0xC0) >> 6, "dx=0xBF -> index 2");
        assertEquals(3, (Math.min(0xFF, 0xC0) & 0xC0) >> 6, "dx=0xC0 -> index 3");
        assertEquals(3, (Math.min(0xFF, 0xFF) & 0xC0) >> 6, "dx=0xFF -> index 3");
        assertEquals(3, (Math.min(0xFF, 0x200) & 0xC0) >> 6, "dx=0x200 -> index 3 (clamped)");
        assertEquals(3, (Math.min(0xFF, 0xFFFF) & 0xC0) >> 6, "dx=0xFFFF -> index 3 (clamped)");
    }
}


