package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.data.Rom;
import com.openggf.game.DynamicWaterHandler;
import com.openggf.game.GameStateManager;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.WaterDataProvider;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.sonic3k.objects.bosses.LbzFinalBoss2EggCapsuleInstance;
import com.openggf.game.sonic3k.runtime.LbzZoneRuntimeState;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.level.Palette;
import com.openggf.level.WaterSystem;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import com.openggf.tests.TestEnvironment;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Source-facing checks for LBZ Big Arm's route-8 capsule lock writer. */
@com.openggf.tests.rules.RequiresRom(com.openggf.tests.rules.SonicGame.SONIC_3K)
class TestLbzFinalBoss2EggCapsuleInstance {

    private static final int CAMERA_X = 0x4000;
    private static final int CAMERA_Y = 0x0100;
    private static final int THRESHOLD_X = CAMERA_X - 0x60;

    @BeforeEach
    void configureGame() {
        TestEnvironment.configureGameModuleFixture(new Sonic3kGameModule());
    }

    @Test
    void openingDispatchOnlySwitchesCallbackAndRunsGenericSwingMove() throws Exception {
        Fixture fixture = new Fixture(THRESHOLD_X + 3);
        fixture.armButtonTrigger();
        fixture.capsule.update(0, fixture.player);
        int xBeforeOpen = fixture.capsule.getX();
        int yBeforeOpen = fixture.capsule.getY();
        int subBeforeOpen = intField(fixture.capsule, "ySubpixel");

        fixture.capsule.update(1, fixture.player);

        assertEquals((xBeforeOpen + 1) & 0xFFFF, fixture.capsule.getX(),
                "routine $08->$10 must not execute loc_866F4 on its opening entry");
        assertFalse(yBeforeOpen == fixture.capsule.getY()
                        && subBeforeOpen == intField(fixture.capsule, "ySubpixel"),
                "the opening entry still falls through the generic Swing/MoveSprite2 tail");
        assertFalse(fixture.water.isDynamicWaterLocked(Sonic3kZoneIds.ZONE_LBZ, 1));
    }

    @Test
    void openedRouteMovesLeftAfterSwingAndKeepsMoveSprite2AboveThreshold() throws Exception {
        Fixture fixture = new Fixture(THRESHOLD_X + 3);
        fixture.triggerAndOpen();

        int yBefore = fixture.capsule.getY();
        int subBefore = intField(fixture.capsule, "ySubpixel");
        int velocityBefore = intField(fixture.capsule, "yVelocity");
        int xBefore = fixture.capsule.getX();
        fixture.capsule.update(2, fixture.player);

        assertEquals((xBefore - 2) & 0xFFFF, fixture.capsule.getX(),
                "loc_866C6 subtracts two while unsigned x_pos is above Camera_X_pos-$60");
        assertEquals(velocityBefore - 0x10, intField(fixture.capsule, "yVelocity"),
                "Swing_UpAndDown must run before the LBZ route hook");
        assertFalse(yBefore == fixture.capsule.getY()
                        && subBefore == intField(fixture.capsule, "ySubpixel"),
                "the above-threshold entry must still execute MoveSprite2");
        assertFalse(fixture.water.isDynamicWaterLocked(Sonic3kZoneIds.ZONE_LBZ, 1));
        assertFalse(fixture.gameState.isEndOfLevelActive(),
                "the capsule movement writer must not own _unkFAA8");
    }

    @Test
    void thresholdEntryLatchesOnlyWaterLockAndSuppressesThatMoveSprite2Step() throws Exception {
        Fixture fixture = new Fixture(THRESHOLD_X - 1);
        fixture.triggerAndOpen();
        setIntField(fixture.capsule, "currentX", THRESHOLD_X - 1);
        int yBefore = fixture.capsule.getY();
        int subBefore = intField(fixture.capsule, "ySubpixel");
        int velocityBefore = intField(fixture.capsule, "yVelocity");

        fixture.capsule.update(2, fixture.player);

        assertEquals(THRESHOLD_X - 1, fixture.capsule.getX());
        assertEquals(velocityBefore - 0x10, intField(fixture.capsule, "yVelocity"),
                "the latch branch still advances Swing_UpAndDown");
        assertEquals(yBefore, fixture.capsule.getY());
        assertEquals(subBefore, intField(fixture.capsule, "ySubpixel"),
                "loc_866DE returns before MoveSprite2 on the latch entry");
        assertTrue(fixture.water.isDynamicWaterLocked(Sonic3kZoneIds.ZONE_LBZ, 1));
        assertFalse(fixture.gameState.isEndOfLevelActive());
    }

    @Test
    void routeHookRunsBeforeResultsWhileActiveAndAfterClearWithoutOwningActiveFlag() throws Exception {
        Fixture fixture = new Fixture(THRESHOLD_X + 0x20);
        fixture.triggerAndOpen();

        setIntField(fixture.capsule, "currentX", THRESHOLD_X);
        setIntField(fixture.capsule, "postOpenTimer", 1);
        fixture.capsule.update(3, fixture.player);
        assertFalse(booleanField(fixture.capsule, "resultsStarted"));
        assertFalse(fixture.gameState.isEndOfLevelActive());
        assertTrue(fixture.water.isDynamicWaterLocked(Sonic3kZoneIds.ZONE_LBZ, 1),
                "the final pre-results-eligible entry must still poll the route hook");

        setBooleanField(fixture.capsule, "resultsStarted", true);
        fixture.gameState.setEndOfLevelActive(true);
        fixture.water.setDynamicWaterLocked(Sonic3kZoneIds.ZONE_LBZ, 1, false);
        fixture.capsule.update(4, fixture.player);
        assertTrue(fixture.water.isDynamicWaterLocked(Sonic3kZoneIds.ZONE_LBZ, 1),
                "routine-$0C/results-active entries keep polling the X gate");
        assertTrue(fixture.gameState.isEndOfLevelActive());

        fixture.gameState.setEndOfLevelActive(false);
        fixture.gameState.setEndOfLevelFlag(true);
        fixture.water.setDynamicWaterLocked(Sonic3kZoneIds.ZONE_LBZ, 1, false);
        fixture.capsule.update(5, fixture.player);
        assertTrue(fixture.water.isDynamicWaterLocked(Sonic3kZoneIds.ZONE_LBZ, 1),
                "the post-results capsule entry continues to own _unkFAA2");
        assertFalse(fixture.gameState.isEndOfLevelActive(),
                "the route hook must never rewrite the independently cleared _unkFAA8");
    }

    private static int intField(Object target, String name) throws Exception {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static boolean booleanField(Object target, String name) throws Exception {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static void setIntField(Object target, String name, int value) throws Exception {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static void setBooleanField(Object target, String name, boolean value) throws Exception {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // Continue through the shared capsule owner.
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static final class Fixture {
        final Camera camera = new Camera();
        final WaterSystem water = new WaterSystem();
        final GameStateManager gameState = new GameStateManager();
        final TestablePlayableSprite player = new TestablePlayableSprite(
                "knuckles", (short) 0, (short) 0);
        final LbzFinalBoss2EggCapsuleInstance capsule;

        Fixture(int initialX) {
            camera.setX((short) CAMERA_X);
            camera.setY((short) CAMERA_Y);
            water.loadForLevelFromProvider(new StaticWaterProvider(), null,
                    Sonic3kZoneIds.ZONE_LBZ, 1, PlayerCharacter.KNUCKLES);
            capsule = new LbzFinalBoss2EggCapsuleInstance(initialX, CAMERA_Y + 0x40);
            TestObjectServices services = new TestObjectServices() {
                @Override
                public com.openggf.game.RuntimeArtCoordinator runtimeArtCoordinator() {
                    return TestEnvironment.activeGameplayMode().runtimeArtCoordinator();
                }
            }
                    .withCamera(camera)
                    .withWaterSystem(water)
                    .withGameState(gameState)
                    .withRom(TestEnvironment.currentRom())
                    .withConfiguration(SonicConfigurationService.getInstance());
            services.zoneRuntimeRegistry().install(
                    new LbzZoneRuntimeState(1, PlayerCharacter.KNUCKLES));
            capsule.setServices(services);
        }

        void triggerAndOpen() {
            armButtonTrigger();
            capsule.update(0, player);
            assertTrue(capsule.traceDebugDetails().contains("t=1"),
                    "ordinary button-child range processing must publish the parent trigger");
            capsule.update(1, player);
            assertTrue(capsule.traceDebugDetails().contains("o=1"),
                    "the following parent entry must open the real capsule");
        }

        void armButtonTrigger() {
            player.setCentreX((short) (capsule.getX() + 1));
            player.setCentreY((short) (capsule.getY() + 0x24));
            player.setYSpeed((short) -1);
            player.setAirForTest(true);
            player.setAnimationId(Sonic3kAnimationIds.ROLL);
        }
    }

    private static final class StaticWaterProvider implements WaterDataProvider {
        @Override
        public boolean hasWater(int zoneId, int actId, PlayerCharacter character) {
            return true;
        }

        @Override
        public int getStartingWaterLevel(int zoneId, int actId) {
            return 0x0640;
        }

        @Override
        public Palette[] getUnderwaterPalette(
                Rom rom, int zoneId, int actId, PlayerCharacter character) {
            return null;
        }

        @Override
        public DynamicWaterHandler getDynamicHandler(
                int zoneId, int actId, PlayerCharacter character) {
            return null;
        }
    }
}
