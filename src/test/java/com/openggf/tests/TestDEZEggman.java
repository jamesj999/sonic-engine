package com.openggf.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.openggf.camera.Camera;
import com.openggf.game.rules.GameRules;
import com.openggf.game.solid.DefaultSolidExecutionRegistry;
import com.openggf.game.sonic2.objects.bosses.Sonic2DEZEggmanInstance;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.ObjectSlotLayout;
import com.openggf.level.objects.TestObjectServices;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the DEZ Eggman transition object (ObjC6 State2).
 * Tests initial state, ROM constant accuracy, state machine transitions,
 * and animation parameters.
 * No ROM or OpenGL required.
 *
 * ROM values used in assertions are verified directly against the disassembly
 * (s2.asm:81568-81696) rather than referencing the implementation constants.
 */
public class TestDEZEggman {

    /** ROM: Eggman spawn position from DEZ_1.bin object layout ($440, $168).
     *  Note: ($3F8, $160) is the solid wall child position, NOT Eggman's position. */
    private static final int SPAWN_X = 0x440;
    private static final int SPAWN_Y = 0x168;

    private Sonic2DEZEggmanInstance eggman;

    @BeforeEach
    public void setUp() {
        eggman = new Sonic2DEZEggmanInstance(SPAWN_X, SPAWN_Y);
        eggman.setServices(new TestObjectServices());
    }

    // ========================================================================
    // INITIAL STATE
    // ========================================================================

    @Test
    public void initialStateIsWaitPlayer() {
        // Constructor sets STATE_INIT (0), then updateInit() is called on first update
        // which advances to STATE_WAIT_PLAYER (2). But the constructor itself sets
        // routineSecondary = STATE_INIT = 0. The first update() call will advance it.
        // Let's call update once to trigger the init->wait transition.
        eggman.update(0, null);
        int routine = getIntField("routineSecondary");
        assertEquals(2, routine, "After first update, routineSecondary should be STATE_WAIT_PLAYER (2)");
    }

    @Test
    public void initialFrameIsStanding() {
        assertEquals(0, getIntField("currentFrame"), "Initial frame should be FRAME_STANDING (0)");
    }

    // ========================================================================
    // OBJECT ID & SPAWN
    // ========================================================================

    @Test
    public void objectIdIs0xC6() {
        assertEquals(0xC6, eggman.getSpawn().objectId(), "Object ID should be 0xC6");
    }

    @Test
    public void priorityBucketIsFive() {
        // ROM: move.b #5,priority(a0)
        assertEquals(5, eggman.getPriorityBucket(), "Priority bucket should be 5");
    }

    @Test
    public void isPersistent() {
        assertTrue(eggman.isPersistent(), "Eggman should be persistent (survives screen transitions)");
    }

    @Test
    public void spawnCoordinatesMatchConstructorArgs() {
        assertEquals(SPAWN_X, eggman.getSpawn().x(), "Spawn X should match constructor arg");
        assertEquals(SPAWN_Y, eggman.getSpawn().y(), "Spawn Y should match constructor arg");
    }

    @Test
    public void initialPositionMatchesSpawn() {
        assertEquals(SPAWN_X, eggman.getX(), "Initial X should match spawn");
        assertEquals(SPAWN_Y, eggman.getY(), "Initial Y should match spawn");
    }

    // ========================================================================
    // ROM CONSTANT VERIFICATION
    // ========================================================================

    @Test
    public void runVelocityMatchesRom() throws Exception {
        // ROM: move.w #$200,x_vel(a0)
        int value = getStaticIntField("RUN_VELOCITY");
        assertEquals(0x200, value, "RUN_VELOCITY should be 0x200");
    }

    @Test
    public void jumpXVelMatchesRom() throws Exception {
        // ROM: move.w #$80,x_vel(a0)
        int value = getStaticIntField("JUMP_X_VEL");
        assertEquals(0x80, value, "JUMP_X_VEL should be 0x80");
    }

    @Test
    public void jumpYVelMatchesRom() throws Exception {
        // ROM: move.w #-$200,y_vel(a0)
        int value = getStaticIntField("JUMP_Y_VEL");
        assertEquals(-0x200, value, "JUMP_Y_VEL should be -0x200");
    }

    @Test
    public void gravityMatchesRom() throws Exception {
        // ROM: addi.w #$10,y_vel(a0) at loc_3D01E
        int value = getStaticIntField("GRAVITY");
        assertEquals(0x10, value, "GRAVITY should be 0x10");
    }

    @Test
    public void pauseTimerMatchesRom() throws Exception {
        // ROM: move.w #$18,objoff_2A(a0)
        int value = getStaticIntField("PAUSE_TIMER");
        assertEquals(0x18, value, "PAUSE_TIMER should be 0x18");
    }

    @Test
    public void jumpTimerMatchesRom() throws Exception {
        // ROM: move.w #$50,objoff_2A(a0)
        int value = getStaticIntField("JUMP_TIMER");
        assertEquals(0x50, value, "JUMP_TIMER should be 0x50");
    }

    @Test
    public void proximityRadiusMatchesRom() throws Exception {
        // ROM: addi.w #$5C,d2 / cmpi.w #$B8,d2
        int value = getStaticIntField("PROXIMITY_RADIUS");
        assertEquals(0x5C, value, "PROXIMITY_RADIUS should be 0x5C");
    }

    @Test
    public void jumpThresholdXMatchesRom() throws Exception {
        // ROM: cmpi.w #$810,x_pos(a0)
        int value = getStaticIntField("JUMP_THRESHOLD_X");
        assertEquals(0x810, value, "JUMP_THRESHOLD_X should be 0x810");
    }

    // ========================================================================
    // RUNNING ANIMATION
    // ========================================================================

    @Test
    public void runningFramesMatchRom() throws Exception {
        // ROM: Ani_objC5_objC6 anim 0: frames {2, 3, 4}
        int[] frames = getStaticIntArrayField("RUNNING_FRAMES");
        assertArrayEquals(new int[]{2, 3, 4}, frames, "RUNNING_FRAMES should be {2, 3, 4}");
    }

    @Test
    public void runningAnimSpeedMatchesRom() throws Exception {
        // ROM: Ani_objC5_objC6 anim 0 speed = 5
        int value = getStaticIntField("RUNNING_ANIM_SPEED");
        assertEquals(5, value, "RUNNING_ANIM_SPEED should be 5");
    }

    // ========================================================================
    // PAUSE TIMER COUNTDOWN
    // ========================================================================

    @Test
    public void pauseStateCountsDown() {
        // Force into PAUSE state with known timer
        setIntField("routineSecondary", 4); // STATE_PAUSE
        setIntField("timer", 3);

        // Step 3 frames - timer should count down 3->2->1->0
        eggman.update(0, null);
        assertEquals(2, getIntField("timer"), "Timer should be 2 after first update");

        eggman.update(1, null);
        assertEquals(1, getIntField("timer"), "Timer should be 1 after second update");

        eggman.update(2, null);
        assertEquals(0, getIntField("timer"), "Timer should be 0 after third update");

        // Still in pause state because timer >= 0 after decrement (timer-- gives 0, not < 0)
        assertEquals(4, getIntField("routineSecondary"), "Should still be in PAUSE state (timer=0 is not < 0)");

        // One more frame: timer-- gives -1, which IS < 0, so transition to RUN
        eggman.update(3, null);
        assertEquals(6, getIntField("routineSecondary"), "Should transition to RUN state after timer goes negative");
    }

    @Test
    public void waitStateUsesClosestSidekickForRomOrientationCheck() {
        TestablePlayableSprite main = new TestablePlayableSprite("sonic", (short) 0x300, (short) 0x168);
        TestablePlayableSprite sidekick = new TestablePlayableSprite("tails", (short) 0x410, (short) 0x168);
        eggman.setServices(new TestObjectServices() {
            @Override
            public ObjectPlayerQuery playerQuery() {
                return new ObjectPlayerQuery(() -> main, () -> List.of(sidekick));
            }
        });

        eggman.update(0, main); // Init -> Wait
        eggman.update(1, main);

        assertEquals(4, getIntField("routineSecondary"),
                "Obj_GetOrientationToPlayer should trigger from the closest sidekick when main is outside range");
        assertEquals(1, getIntField("currentFrame"), "Triggered wait state should show the surprised frame");
        assertEquals(0x18, getIntField("timer"), "Triggered wait state should load the ROM pause timer");
    }

    @Test
    public void runStateSpawnsExhaustPuffWhenTimerUnderflows() {
        ObjectManager objectManager = mock(ObjectManager.class);
        eggman.setServices(new TestObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        });

        setIntField("routineSecondary", 6); // STATE_RUN
        setIntField("currentX", 0x500);
        setIntField("currentY", 0x168);
        setIntField("xFixed", 0x500 << 16);
        setIntField("yFixed", 0x168 << 16);
        setIntField("xVel", 0x200);
        setIntField("puffTimer", 0);

        eggman.update(0, null);

        ArgumentCaptor<ObjectInstance> captor = ArgumentCaptor.forClass(ObjectInstance.class);
        verify(objectManager).addDynamicObjectAfterCurrent(captor.capture());
        ObjectInstance puff = captor.getValue();

        assertEquals("ExhaustPuff", puff.getClass().getSimpleName(), "ROM should spawn ObjC6 subtype $AA exhaust child");
        assertEquals(0xC6, puff.getSpawn().objectId(), "Exhaust child should stay ObjC6");
        assertEquals(0xAA, puff.getSpawn().subtype(), "Exhaust child subtype should be $AA");
        assertEquals(0x500, puff.getX(), "Exhaust child should spawn at Eggman's pre-move X");
        assertEquals(0x150, puff.getY(), "ROM subtracts $18 from Eggman's Y when spawning exhaust");
        assertEquals(5, getIntField(puff, "currentFrame"), "Exhaust child uses ObjC6 mapping frame 5");
        assertEquals(-0x100, getIntField(puff, "xVel"), "ROM initializes exhaust x_vel to -$100");
        assertEquals(0, getIntField(puff, "yVel"), "ROM initializes exhaust y_vel to 0");
        assertEquals(8, getIntField(puff, "timer"), "ROM initializes exhaust timer to 8");
        assertEquals(0x20, getIntField("puffTimer"), "Parent puff timer should reload to $20 after spawning");
    }

    @Test
    public void exhaustPuffUsesRomState4GravityBeforeMoveAndTimerDeletion() {
        ObjectManager objectManager = mock(ObjectManager.class);
        eggman.setServices(new TestObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        });

        setIntField("routineSecondary", 6); // STATE_RUN
        setIntField("currentX", 0x500);
        setIntField("currentY", 0x168);
        setIntField("xFixed", 0x500 << 16);
        setIntField("yFixed", 0x168 << 16);
        setIntField("xVel", 0x200);
        setIntField("puffTimer", 0);
        eggman.update(0, null);

        ArgumentCaptor<ObjectInstance> captor = ArgumentCaptor.forClass(ObjectInstance.class);
        verify(objectManager).addDynamicObjectAfterCurrent(captor.capture());
        ObjectInstance puff = captor.getValue();

        puff.update(1, null);
        assertEquals(0x4FF, puff.getX(), "First State4 update moves exhaust left by one pixel");
        assertEquals(0x150, puff.getY(), "Gravity-before-move adds only $10 subpixels, so integer Y remains");
        assertEquals(0x10, getIntField(puff, "yVel"), "State4 adds $10 to y_vel before ObjectMove");
        assertEquals(7, getIntField(puff, "timer"), "State4 decrements objoff_2A before movement");

        setIntField(puff, "timer", 0);
        puff.update(2, null);
        assertTrue(puff.isDestroyed(), "Exhaust child deletes when timer decrement goes negative");
        assertEquals(0x4FF, puff.getX(), "Deletion frame should not apply movement");
    }

    @Test
    public void openingBarrierWallStillStopsPlayerBeforeAnimationDeletesIt() {
        ObjectInstance wall = newBarrierWall();
        setIntField(wall, "wallState", 2); // WALL_STATE_OPENING
        setIntField(wall, "openingFrameIndex", 3);
        setIntField(wall, "openingAnimTimer", 0);

        TestablePlayableSprite player =
                new TestablePlayableSprite("sonic", (short) 0x03E5, (short) 0x0160);
        player.setGameRulesForTest(GameRules.SONIC_2);
        player.setWidth(20);
        player.setHeight(38);
        player.setCentreX((short) 0x03E5);
        player.setCentreY((short) 0x0160);
        player.setAirForTest(false);
        player.setXSpeed((short) 0x100);
        player.setGSpeed((short) 0x100);

        MutableObjectServices services = new MutableObjectServices();
        Camera camera = mock(Camera.class);
        when(camera.getX()).thenReturn((short) 0x0300);
        when(camera.getY()).thenReturn((short) 0x0100);
        when(camera.getWidth()).thenReturn((short) 320);
        when(camera.getHeight()).thenReturn((short) 224);
        when(camera.isVerticalWrapEnabled()).thenReturn(false);
        services.withCamera(camera);
        ObjectManager objectManager = new ObjectManager(
                List.of(),
                sonic2Registry(wall),
                0,
                null,
                null,
                null,
                camera,
                services);
        services.objectManager = objectManager;
        ((AbstractObjectInstance) wall).setServices(services);
        objectManager.addDynamicObject(wall);

        objectManager.update(0x0345, player, List.of(), 0, false, true, false);

        assertEquals(0x03E5, player.getCentreX(),
                "ObjC6_State3_State2 calls SolidObject before AnimateSprite advances to State3");
        assertEquals(0, player.getXSpeed(), "The last opening-frame wall contact must still zero x_vel");
        assertEquals(0, player.getGSpeed(), "The last opening-frame wall contact must still zero ground_vel");
    }

    // ========================================================================
    // REFLECTION HELPERS
    // ========================================================================

    private int getIntField(String fieldName) {
        try {
            Field field = Sonic2DEZEggmanInstance.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.getInt(eggman);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read field: " + fieldName, e);
        }
    }

    private void setIntField(String fieldName, int value) {
        setIntField(eggman, fieldName, value);
    }

    private static int getIntField(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.getInt(target);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read field: " + fieldName, e);
        }
    }

    private static void setIntField(Object target, String fieldName, int value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.setInt(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field: " + fieldName, e);
        }
    }

    private static ObjectInstance newBarrierWall() {
        try {
            Class<?> wallClass = Class.forName(
                    "com.openggf.game.sonic2.objects.bosses.Sonic2DEZEggmanInstance$BarrierWall");
            Constructor<?> ctor = wallClass.getDeclaredConstructor(int.class, int.class);
            ctor.setAccessible(true);
            return (ObjectInstance) ctor.newInstance(0x03F8, 0x0160);
        } catch (Exception e) {
            throw new RuntimeException("Failed to construct DEZ barrier wall", e);
        }
    }

    private static ObjectRegistry sonic2Registry(ObjectInstance instance) {
        return new ObjectRegistry() {
            @Override
            public ObjectInstance create(com.openggf.level.objects.ObjectSpawn spawn) {
                return instance;
            }

            @Override
            public void reportCoverage(List<com.openggf.level.objects.ObjectSpawn> spawns) {
                // No-op for this focused object-manager fixture.
            }

            @Override
            public String getPrimaryName(int objectId) {
                return "TEST";
            }

            @Override
            public ObjectSlotLayout objectSlotLayout() {
                return ObjectSlotLayout.SONIC_2;
            }
        };
    }

    private static final class MutableObjectServices extends TestObjectServices {
        private ObjectManager objectManager;

        private MutableObjectServices() {
            withSolidExecutionRegistry(new DefaultSolidExecutionRegistry());
        }

        @Override
        public ObjectManager objectManager() {
            return objectManager;
        }
    }

    private static int getStaticIntField(String fieldName) throws Exception {
        Field field = Sonic2DEZEggmanInstance.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static int[] getStaticIntArrayField(String fieldName) throws Exception {
        Field field = Sonic2DEZEggmanInstance.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (int[]) field.get(null);
    }
}


