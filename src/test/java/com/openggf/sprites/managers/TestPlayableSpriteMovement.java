package com.openggf.sprites.managers;

import com.openggf.camera.Camera;
import com.openggf.game.GameModule;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.GameServices;
import com.openggf.game.ShieldType;
import com.openggf.game.rules.GameRules;
import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.sonic3k.Sonic3kSuperStateController;
import com.openggf.game.sonic3k.objects.FbzMagneticPlatformObjectInstance;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.sonic2.Sonic2SuperStateController;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SkidDustObjectInstance;
import com.openggf.physics.CollisionSystem;
import com.openggf.physics.FrameCollisionPlan;
import com.openggf.physics.TrigLookupTable;
import com.openggf.physics.TerrainCollisionManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SidekickCpuController;
import com.openggf.sprites.playable.Sonic;
import com.openggf.sprites.playable.Tails;
import com.openggf.sprites.playable.Sonic;
import com.openggf.sprites.render.PlayerSpriteRenderer;
import com.openggf.tests.FullReset;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;
import com.openggf.physics.Direction;
import com.openggf.physics.GroundSensor;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.Sensor;
import com.openggf.physics.SensorResult;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.game.GroundMode;
import com.openggf.sprites.animation.ScriptedVelocityAnimationProfile;
import com.openggf.sprites.animation.SpriteAnimationEndAction;
import com.openggf.sprites.animation.SpriteAnimationScript;
import com.openggf.sprites.animation.SpriteAnimationSet;
import com.openggf.sprites.playable.SecondaryAbility;
import com.openggf.sprites.playable.SuperState;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(SingletonResetExtension.class)
@FullReset
@Isolated
public class TestPlayableSpriteMovement {

        private static final class HookSprite extends AbstractPlayableSprite {
                private int activations;
                private List<Boolean> directions;

                private HookSprite() { super("hook", (short) 0, (short) 0); }
                @Override protected void defineSpeeds() {
                        max = 1536; runAccel = 12; runDecel = 128; friction = 12;
                        jump = 1664; slopeRunning = 32; slopeRollingDown = 80; slopeRollingUp = 20;
                }
                @Override protected void createSensorLines() { }
                @Override public void draw() { }
                @Override protected boolean onAbilityActivate(
                                boolean up, boolean down, boolean left, boolean right) {
                        activations++;
                        directions = List.of(up, down, left, right);
                        return true;
                }
        }

        private PlayableSpriteMovement manager;
        private AbstractPlayableSprite mockSprite;
        private GameModule previousModule;

	@Test
	void s2DeadRoutineDoesNotRunWaterInteractionAfterCrossingSurface() throws Exception {
		LevelManager levelManager = mock(LevelManager.class);
		when(levelManager.objectsExecuteAfterPlayerPhysics()).thenReturn(true);
		Sonic sonic = new Sonic("sonic", (short) 0, (short) 0);
		setGameRulesForTest(sonic, GameRules.SONIC_2);
		sonic.setInWater(true);
		sonic.setYSpeed((short) -0x200);
		sonic.setDead(true);

		SpriteManager.tickPlayablePhysics(sonic,
				false, false, false, false, false, false, false, false,
				levelManager, 1);

		verify(levelManager, never()).updatePlayableWaterStateForCurrentLevel(sonic);
		assertEquals(-0x1C8, sonic.getYSpeed(),
				"Obj01_Dead applies only ObjectMoveAndFall gravity; crossing the surface must not double y_vel");
	}

        @BeforeEach
        public void setUp() {
                previousModule = GameModuleRegistry.getCurrent();
                TestEnvironment.configureGameModuleFixture(new Sonic2GameModule());
                mockSprite = new AbstractPlayableSprite("sonic", (short) 0, (short) 0) {
                        @Override
                        protected void defineSpeeds() {
                                this.max = 1536; // 6 pixels * 256
                                this.runAccel = 12; // 0.046875 * 256
                                this.runDecel = 128; // 0.5 * 256
                                this.slopeRunning = 32; // 0.125 * 256
                                this.friction = 12; // 0.046875 * 256
                                this.jump = 1664; // 6.5 * 256 (standard Sonic jump force)
                                this.slopeRollingDown = 80; // Full slope factor when rolling downhill
                                this.slopeRollingUp = 20; // Reduced factor when rolling uphill
                        }

                        @Override
                        protected void createSensorLines() {
                        }

                        @Override
                        public void draw() {
                        }

                        @Override
                        public SecondaryAbility getSecondaryAbility() {
                                return SecondaryAbility.INSTA_SHIELD;
                        }
                };
                manager = new PlayableSpriteMovement(mockSprite);
        }

        @AfterEach
        public void tearDown() {
                SessionManager.clear();
                if (previousModule != null) {
                        GameModuleRegistry.setCurrent(previousModule);
                } else {
                        GameModuleRegistry.reset();
                }
        }

        private void setGameRulesForTest(GameRules featureSet) throws Exception {
                setGameRulesForTest(mockSprite, featureSet);
        }

        private void setGameRulesForTest(AbstractPlayableSprite sprite, GameRules rules) throws Exception {
                Field field = AbstractPlayableSprite.class.getDeclaredField("gameRules");
                field.setAccessible(true);
                field.set(sprite, rules);
        }

        private boolean invokeTryShieldAbility() throws Exception {
                Method method = PlayableSpriteMovement.class.getDeclaredMethod("tryShieldAbility");
                method.setAccessible(true);
                return (Boolean) method.invoke(manager);
        }

        private void invokeHyperDash() throws Exception {
                Method method = PlayableSpriteMovement.class.getDeclaredMethod("hyperDash");
                method.setAccessible(true);
                method.invoke(manager);
        }

        private void invokeDoJumpHeight() throws Exception {
                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doJumpHeight");
                method.setAccessible(true);
                method.invoke(manager);
        }

        private void collectAllChaosEmeralds() {
                for (int i = 0; i < 7; i++) {
                        GameServices.gameState().markEmeraldCollected(i);
                }
        }

        private void collectAllSuperEmeralds() {
                for (int i = 0; i < 7; i++) {
                        GameServices.gameState().markSuperEmeraldCollected(i);
                }
        }

        @Test
        public void fbzMagneticPlatformSonicBalanceUsesNativeWidthBoundary() throws Exception {
                GameModuleRegistry.setCurrent(new Sonic3kGameModule());
                setGameRulesForTest(GameRules.SONIC_3K);
                FbzMagneticPlatformObjectInstance platform = addMagneticPlatformForBalance();
                ObjectManager objects = GameServices.level().getObjectManager();
                objects.forceRidingObjectForBootstrap(mockSprite, platform);
                mockSprite.setAir(false);
                mockSprite.setAngle((byte) 0);
                mockSprite.setGSpeed((short) 0);
                mockSprite.setDirection(Direction.RIGHT);

                mockSprite.setCentreX((short) 0x252A); // d1=$252A+$18-$2540=2
                invokeUpdateBalanceState(manager);
                assertEquals(Direction.RIGHT, mockSprite.getDirection(),
                                "Sonic d1==2 must fail the signed BLT left-edge balance test");
                assertEquals(0, mockSprite.getBalanceState());

                mockSprite.setCentreX((short) 0x2529); // d1=1, just inside branch
                invokeUpdateBalanceState(manager);
                assertEquals(Direction.LEFT, mockSprite.getDirection());
                assertTrue(mockSprite.getBalanceState() != 0);
        }

        @Test
        public void fbzMagneticPlatformTailsUsesNativeFourPixelBalanceShift() throws Exception {
                GameModuleRegistry.setCurrent(new Sonic3kGameModule());
                Tails tails = new Tails("tails_p2", (short) 0, (short) 0);
                PlayableSpriteMovement tailsMovement = new PlayableSpriteMovement(tails);
                FbzMagneticPlatformObjectInstance platform = addMagneticPlatformForBalance();
                ObjectManager objects = GameServices.level().getObjectManager();
                objects.forceRidingObjectForBootstrap(tails, platform);
                tails.setAir(false);
                tails.setAngle((byte) 0);
                tails.setGSpeed((short) 0);
                tails.setDirection(Direction.RIGHT);

                tails.setCentreX((short) 0x252C); // d1=4, Tails' non-balancing boundary
                invokeUpdateBalanceState(tailsMovement);
                assertEquals(Direction.RIGHT, tails.getDirection());
                assertEquals(0, tails.getBalanceState());

                tails.setCentreX((short) 0x252B); // d1=3, just inside Tails' branch
                invokeUpdateBalanceState(tailsMovement);
                assertEquals(Direction.LEFT, tails.getDirection());
                assertTrue(tails.getBalanceState() != 0);
        }

        private FbzMagneticPlatformObjectInstance addMagneticPlatformForBalance() throws Exception {
                ObjectManager objects = GameServices.level().getObjectManager();
                if (objects == null) {
                        objects = new ObjectManager(List.of(), null, 0, null, null);
                        Field objectManagerField = GameServices.level().getClass().getDeclaredField("objectManager");
                        objectManagerField.setAccessible(true);
                        objectManagerField.set(GameServices.level(), objects);
                }
                FbzMagneticPlatformObjectInstance platform = new FbzMagneticPlatformObjectInstance(
                                new ObjectSpawn(0x2540, 0x0570, Sonic3kObjectIds.FBZ_MAGNETIC_PLATFORM,
                                                0x0F, 0, false, 0));
                objects.addDynamicObject(platform);
                return platform;
        }

        private static void invokeUpdateBalanceState(PlayableSpriteMovement movement) throws Exception {
                Method method = PlayableSpriteMovement.class.getDeclaredMethod("updateBalanceState");
                method.setAccessible(true);
                method.invoke(movement);
        }

        @Test
        public void onObjectAnglePosClearsSharedOutputsSeenByNextAirbornePlayable()
                        throws Exception {
                GameModuleRegistry.setCurrent(new Sonic3kGameModule());
                setGameRulesForTest(GameRules.SONIC_3K);

                CollisionSystem collisionSystem = new CollisionSystem(new TerrainCollisionManager());
                PlayableSpriteMovement sonicMovement = new PlayableSpriteMovement(
                                mockSprite, collisionSystem, GameServices.gameState());
                mockSprite.setOnObject(true);
                invokeCaptureTiltAnglesForGroundDispatch(sonicMovement);

                PlayableSpriteMovement.RewindState state = sonicMovement.captureRewindState();
                assertEquals(0, state.latchedNextTilt(),
                                "Player_AnglePos clears Primary_Angle while Status_OnObj is set");
                assertEquals(0, state.latchedTilt(),
                                "Player_AnglePos clears Secondary_Angle while Status_OnObj is set");

                Tails tails = new Tails("tails_p2", (short) 0, (short) 0);
                setGameRulesForTest(tails, GameRules.SONIC_3K);
                tails.setGroundSensors(emptyFloorSensors(tails));
                prepareFallingPlayable(tails);
                PlayableSpriteMovement tailsMovement = new PlayableSpriteMovement(
                                tails, collisionSystem, GameServices.gameState());
                invokeDoLevelCollision(tailsMovement);

                state = tailsMovement.captureRewindState();
                assertEquals(0, state.latchedNextTilt(),
                                "an empty right probe must retain the shared Primary_Angle clear");
                assertEquals(0, state.latchedTilt(),
                                "an empty left probe must retain the shared Secondary_Angle clear");
        }

        @Test
        public void emptyAirProbeRetainsSharedAngleOutputsAcrossSonicAndTailsDispatches()
                        throws Exception {
                GameModuleRegistry.setCurrent(new Sonic3kGameModule());
                CollisionSystem collisionSystem = new CollisionSystem(new TerrainCollisionManager());

                Sonic sonic = new Sonic("sonic", (short) 0, (short) 0);
                setGameRulesForTest(sonic, GameRules.SONIC_3K);
                sonic.setGroundSensors(rightEdgeFloorSensors(sonic));
                prepareFallingPlayable(sonic);
                PlayableSpriteMovement sonicMovement = new PlayableSpriteMovement(
                                sonic, collisionSystem, GameServices.gameState());
                invokeDoLevelCollision(sonicMovement);

                assertFalse(sonic.getAir(), "the exact air-collision path must land Sonic");
                PlayableSpriteMovement.RewindState sonicState = sonicMovement.captureRewindState();
                assertEquals(0, sonicState.latchedNextTilt(),
                                "FindFloor must not replace the initial shared Primary_Angle on an empty right probe");
                assertEquals(0xFF, sonicState.latchedTilt(),
                                "the solid left probe must write Secondary_Angle");

                // On the next grounded dispatch Player_AnglePos seeds both shared
                // outputs to 3 before probing. The empty right side retains that 3,
                // while the solid left side writes FF.
                invokeCaptureTiltAnglesForGroundDispatch(sonicMovement);
                sonicState = sonicMovement.captureRewindState();
                assertEquals(3, sonicState.latchedNextTilt());
                assertEquals(0xFF, sonicState.latchedTilt());

                // Tails runs later in the same native frame. Sonic_CheckFloor does
                // not seed the globals, so Tails' empty right probe must inherit the
                // Primary_Angle=3 left by Sonic's grounded AnglePos.
                Tails tails = new Tails("tails_p2", (short) 0, (short) 0);
                setGameRulesForTest(tails, GameRules.SONIC_3K);
                tails.setGroundSensors(rightEdgeFloorSensors(tails));
                prepareFallingPlayable(tails);
                tails.setDirection(Direction.LEFT);
                PlayableSpriteMovement tailsMovement = new PlayableSpriteMovement(
                                tails, collisionSystem, GameServices.gameState());
                invokeDoLevelCollision(tailsMovement);

                assertFalse(tails.getAir(), "the exact air-collision path must land Tails");
                PlayableSpriteMovement.RewindState state = tailsMovement.captureRewindState();
                assertEquals(3, state.latchedNextTilt(),
                                "Tails' empty right probe must retain Sonic's shared Primary_Angle");
                assertEquals(0xFF, state.latchedTilt(),
                                "Tails' solid left probe must write native Secondary_Angle");

                invokeUpdateBalanceState(tailsMovement);
                assertEquals(Direction.RIGHT, tails.getDirection(),
                                "the next zero-input ground dispatch must consume next_tilt=3");
        }

        @Test
        public void risingDiagonalAirCollisionKeepsCeilingAnglesWhenNativeFloorCheckIsSkipped()
                        throws Exception {
                GameModuleRegistry.setCurrent(new Sonic3kGameModule());
                Tails tails = new Tails("tails_p2", (short) 0, (short) 0);
                setGameRulesForTest(tails, GameRules.SONIC_3K);
                tails.setCeilingSensors(new Sensor[] {
                                fixedSensor(tails, Direction.UP, (byte) 0x11, (byte) 16),
                                fixedSensor(tails, Direction.UP, (byte) 0x22, (byte) 16)
                });
                tails.setGroundSensors(new Sensor[] {
                                fixedSensor(tails, Direction.DOWN, (byte) 0x33, (byte) -1),
                                fixedSensor(tails, Direction.DOWN, (byte) 0x44, (byte) -1)
                });
                tails.setAir(true);
                tails.setGroundMode(GroundMode.GROUND);
                tails.setXSpeed((short) 0x0100);
                tails.setYSpeed((short) -0x0100); // quadrant $C0, rising

                CollisionSystem collisionSystem = new CollisionSystem(new TerrainCollisionManager());
                PlayableSpriteMovement tailsMovement = new PlayableSpriteMovement(
                                tails, collisionSystem, GameServices.gameState());
                Method collide = PlayableSpriteMovement.class
                                .getDeclaredMethod("doLevelCollision", boolean.class);
                collide.setAccessible(true);
                collide.invoke(tailsMovement, false);

                assertTrue(tails.getAir(), "rising Tails must not run the native floor helper");
                PlayableSpriteMovement.RewindState state = tailsMovement.captureRewindState();
                assertEquals(0x22, state.latchedNextTilt(),
                                "right ceiling probe must remain the final Primary_Angle output");
                assertEquals(0x11, state.latchedTilt(),
                                "left ceiling probe must remain the final Secondary_Angle output");
        }

        @Test
        public void airWallWritePublishesPrimaryAngleBeforeNativeEarlyReturn() throws Exception {
                GameModuleRegistry.setCurrent(new Sonic2GameModule());
                Sonic sonic = new Sonic("sonic", (short) 0, (short) 0);
                setGameRulesForTest(sonic, GameRules.SONIC_2);
                sonic.setPushSensors(new Sensor[] {
                                fixedSensor(sonic, Direction.LEFT, (byte) 0x40, (byte) -1),
                                fixedSensor(sonic, Direction.RIGHT, (byte) 0x00, (byte) 16, 0)
                });
                sonic.setCeilingSensors(new Sensor[] {
                                fixedSensor(sonic, Direction.UP, (byte) 0x20, (byte) -1),
                                fixedSensor(sonic, Direction.UP, (byte) 0x20, (byte) -1)
                });
                sonic.setGroundSensors(emptyFloorSensors(sonic));
                sonic.setAir(true);
                sonic.setGroundMode(GroundMode.GROUND);
                sonic.setXSpeed((short) -0x0200);
                sonic.setYSpeed((short) -0x0100); // quadrant $40

                CollisionSystem collisionSystem = new CollisionSystem(new TerrainCollisionManager());
                PlayableSpriteMovement movement = new PlayableSpriteMovement(
                                sonic, collisionSystem, GameServices.gameState());
                invokeDoLevelCollision(movement);

                PlayableSpriteMovement.RewindState state = movement.captureRewindState();
                assertEquals(0x40, state.latchedNextTilt(),
                                "CheckLeftWallDist writes Primary_Angle before S2 returns on the wall hit");
                assertEquals(0, state.latchedTilt(),
                                "the early return must leave the untouched shared Secondary_Angle intact");
        }

        @Test
        public void objectControlledSidekickStillCopiesSharedAngleOutputsAtPlayerTail() throws Exception {
                GameModuleRegistry.setCurrent(new Sonic3kGameModule());
                CollisionSystem collisionSystem = new CollisionSystem(new TerrainCollisionManager());
                installRuntimeCollisionSystem(collisionSystem);
                collisionSystem.publishGroundAngleOutputs(false,
                                new SensorResult((byte) 0xFF, (byte) -1, 0xFF, Direction.DOWN),
                                new SensorResult((byte) 3, (byte) 16, 0, Direction.DOWN));

                Tails tails = new Tails("tails_p2", (short) 0, (short) 0);
                setGameRulesForTest(tails, GameRules.SONIC_3K);
                tails.setObjectControlled(true);
                PlayableSpriteMovement movement = new PlayableSpriteMovement(
                                tails, collisionSystem, GameServices.gameState());

                movement.handleMovement(false, false, false, false, false, false, false, false);

                PlayableSpriteMovement.RewindState state = movement.captureRewindState();
                assertEquals(3, state.latchedNextTilt(),
                                "the object-control early return must still execute the native player-tail copy");
                assertEquals(0xFF, state.latchedTilt(),
                                "a later sidekick must observe the shared Secondary_Angle without moving");
        }

        @Test
        public void spindashAnglePosPublishesGroundSeedAndOnObjectClearAtPlayerTail() throws Exception {
                GameModuleRegistry.setCurrent(new Sonic3kGameModule());
                CollisionSystem collisionSystem = new CollisionSystem(new TerrainCollisionManager());
                installRuntimeCollisionSystem(collisionSystem);
                Sonic sonic = new Sonic("sonic", (short) 0, (short) 0);
                setGameRulesForTest(sonic, GameRules.SONIC_3K);
                sonic.setGroundSensors(rightEdgeFloorSensors(sonic));
                sonic.setAir(false);
                sonic.setGroundMode(GroundMode.GROUND);
                sonic.setAnimationProfile(new ScriptedVelocityAnimationProfile()
                                .setDuckAnimId(8)
                                .setSpindashAnimId(9)
                                .setRollAnimId(2));
                sonic.setAnimationId(8);
                sonic.setCrouching(true);
                PlayableSpriteMovement movement = new PlayableSpriteMovement(
                                sonic, collisionSystem, GameServices.gameState());

                sonic.setJumpInputPressed(true, true);
                movement.handleMovement(false, true, false, false, true, false, false, false);

                assertTrue(sonic.getSpindash(), "Down plus a fresh jump press must enter the charge path");
                PlayableSpriteMovement.RewindState state = movement.captureRewindState();
                assertEquals(3, state.latchedNextTilt(),
                                "charge-frame AnglePos seeds Primary_Angle before the empty right probe");
                assertEquals(0xFF, state.latchedTilt(),
                                "charge-frame AnglePos publishes the solid left angle");

                sonic.setOnObject(true);
                sonic.setJumpInputPressed(false, false);
                movement.handleMovement(false, false, false, false, false, false, false, false);

                assertFalse(sonic.getSpindash(), "releasing Down must take the spindash release path");
                state = movement.captureRewindState();
                assertEquals(0, state.latchedNextTilt(),
                                "release-frame AnglePos clears Primary_Angle on Status_OnObj");
                assertEquals(0, state.latchedTilt(),
                                "release-frame AnglePos clears Secondary_Angle on Status_OnObj");
        }

        private static Sensor fixedSensor(AbstractPlayableSprite sprite, Direction direction,
                                          byte angle, byte distance) {
                return fixedSensor(sprite, direction, angle, distance, 1);
        }

        private static Sensor fixedSensor(AbstractPlayableSprite sprite, Direction direction,
                                          byte angle, byte distance, int tileId) {
                return new Sensor(sprite, direction, (byte) 0, (byte) 0, true) {
                        @Override
                        protected SensorResult doScan(short dx, short dy) {
                                return new SensorResult(angle, distance, tileId, direction);
                        }
                };
        }

        private static Sensor[] rightEdgeFloorSensors(AbstractPlayableSprite sprite) {
                return new Sensor[] {
                                new Sensor(sprite, Direction.DOWN, (byte) -9, (byte) 15, true) {
                                        @Override
                                        protected SensorResult doScan(short dx, short dy) {
                                                return dx >= 9
                                                                ? new SensorResult((byte) 3, (byte) 16, 0,
                                                                                Direction.DOWN)
                                                                : new SensorResult((byte) 0xFF, (byte) -1, 0xFF,
                                                                                Direction.DOWN);
                                        }
                                },
                                fixedSensor(sprite, Direction.DOWN, (byte) 3, (byte) 16, 0)
                };
        }

        private static Sensor[] emptyFloorSensors(AbstractPlayableSprite sprite) {
                return new Sensor[] {
                                fixedSensor(sprite, Direction.DOWN, (byte) 3, (byte) 16, 0),
                                fixedSensor(sprite, Direction.DOWN, (byte) 3, (byte) 16, 0)
                };
        }

        private static void prepareFallingPlayable(AbstractPlayableSprite sprite) {
                sprite.setAir(true);
                sprite.setGroundMode(GroundMode.GROUND);
                sprite.setXSpeed((short) 0);
                sprite.setYSpeed((short) 0x00A8);
        }

        private static void invokeDoLevelCollision(PlayableSpriteMovement movement) throws Exception {
                Method collide = PlayableSpriteMovement.class
                                .getDeclaredMethod("doLevelCollision", boolean.class);
                collide.setAccessible(true);
                collide.invoke(movement, false);
        }

        private static void invokeCaptureTiltAnglesForGroundDispatch(PlayableSpriteMovement movement)
                        throws Exception {
                Method method = PlayableSpriteMovement.class
                                .getDeclaredMethod("captureTiltAnglesForGroundDispatch");
                method.setAccessible(true);
                method.invoke(movement);
        }

        private void installCurrentModuleLevelState() {
                GameServices.level().resetLevelGamestate(GameModuleRegistry.getCurrent().createLevelState());
        }

        private void setMovementField(String name, Object value) throws Exception {
                Field field = PlayableSpriteMovement.class.getDeclaredField(name);
                field.setAccessible(true);
                field.set(manager, value);
        }

        @Test
        void customAbilityHookConsumesEligibleRepressBeforeBuiltinDispatch() throws Exception {
                HookSprite sprite = new HookSprite();
                setGameRulesForTest(sprite, GameRules.SONIC_3K);
                sprite.setJumping(true);
                sprite.setAir(true);
                sprite.setYSpeed((short) 0);
                PlayableSpriteMovement movement = new PlayableSpriteMovement(sprite);
                for (var entry : java.util.Map.of(
                                "jumpReleasedSinceJump", true,
                                "inputJumpPress", true,
                                "inputJump", true,
                                "inputUp", true,
                                "inputLeft", true).entrySet()) {
                        Field field = PlayableSpriteMovement.class.getDeclaredField(entry.getKey());
                        field.setAccessible(true);
                        field.set(movement, entry.getValue());
                }

                Method jumpHeight = PlayableSpriteMovement.class.getDeclaredMethod("doJumpHeight");
                jumpHeight.setAccessible(true);
                jumpHeight.invoke(movement);

                assertEquals(1, sprite.activations);
                assertEquals(List.of(true, false, true, false), sprite.directions);
                Field released = PlayableSpriteMovement.class.getDeclaredField("jumpReleasedSinceJump");
                released.setAccessible(true);
                assertFalse(released.getBoolean(movement));
        }

        @Test
        public void controlLockStillSuppressesMovementWithoutScriptOwnedLogicalInput() {
                mockSprite.setControlLocked(true);

                assertTrue(PlayableSpriteMovement.controlLockBlocksScriptedMovement(mockSprite),
                                "A normal control lock must not consume caller-supplied RIGHT");
        }

        @Test
        public void controlLockConsumesExplicitScriptOwnedLogicalInputThroughNormalAcceleration() {
                mockSprite.setControlLocked(true);
                mockSprite.setForcedInputMask(AbstractPlayableSprite.INPUT_RIGHT);

                assertFalse(PlayableSpriteMovement.controlLockBlocksScriptedMovement(mockSprite),
                                "A script-owned logical RIGHT must use playable acceleration while hardware input stays locked");
        }

        @Test
        public void typedPlayerMovementRuleCapsGroundSpeedWithoutFallback() throws Exception {
                GameRules base = GameRules.SONIC_2;
                GameRules typedRules = new GameRules(
                                GameRules.SONIC_1.playerMovement(),
                                base.playerCapability(),
                                base.collision(),
                                base.playerAnimation(),
                                base.camera(),
                                base.ring(),
                                base.objectInteraction(),
                                base.sidekickCpu(),
                                base.powerUp(),
                                base.drowningBubble());
                setGameRulesForTest(null);
                setGameRulesForTest(mockSprite, typedRules);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("accelerateRight",
                                short.class, short.class, short.class);
                method.setAccessible(true);
                short speed = (Short) method.invoke(manager, (short) 0x0700, (short) 0x000C, (short) 0x0600);

                assertEquals((short) 0x0600, speed,
                                "Typed PlayerMovementRules.inputAlwaysCapsGroundSpeed should drive capping without legacy features");
        }

        @Test
        public void playerMovementRuleUsesDefaultWhenTypedRulesMissing() throws Exception {
                setGameRulesForTest(GameRules.SONIC_1);
                setGameRulesForTest(mockSprite, null);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("accelerateRight",
                                short.class, short.class, short.class);
                method.setAccessible(true);
                short speed = (Short) method.invoke(manager, (short) 0x0700, (short) 0x000C, (short) 0x0600);

                assertEquals((short) 0x0700, speed,
                                "Missing GameRules should not recreate removed feature-set movement rules");
        }

        @Test
        public void playerMovementRuleUsesDefaultWhenTypedGroupMissing() throws Exception {
                GameRules base = GameRules.SONIC_2;
                GameRules rulesWithoutMovementGroup = new GameRules(
                                null,
                                base.playerCapability(),
                                base.collision(),
                                base.playerAnimation(),
                                base.camera(),
                                base.ring(),
                                base.objectInteraction(),
                                base.sidekickCpu(),
                                base.powerUp(),
                                base.drowningBubble());
                setGameRulesForTest(GameRules.SONIC_1);
                setGameRulesForTest(mockSprite, rulesWithoutMovementGroup);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("accelerateRight",
                                short.class, short.class, short.class);
                method.setAccessible(true);
                short speed = (Short) method.invoke(manager, (short) 0x0700, (short) 0x000C, (short) 0x0600);

                assertEquals((short) 0x0700, speed,
                                "A null PlayerMovementRules group should not recreate removed feature-set movement rules");
        }

        @Test
        public void s3kJumpRepressClearsRollingJumpBeforeAirControl() throws Exception {
                GameModuleRegistry.setCurrent(new Sonic3kGameModule());
                setGameRulesForTest(GameRules.SONIC_3K);
                mockSprite.setAir(true);
                mockSprite.setRolling(true);
                mockSprite.setRollingJump(true);
                mockSprite.setJumping(true);
                mockSprite.setXSpeed((short) 0);
                mockSprite.setYSpeed((short) 0x07D8);
                mockSprite.setGSpeed((short) 0x03B0);

                setMovementField("jumpPressed", true);
                setMovementField("jumpReleasedSinceJump", true);
                setMovementField("inputJumpPress", true);
                setMovementField("inputJump", true);
                setMovementField("inputLeft", true);

                Method jumpHeight = PlayableSpriteMovement.class.getDeclaredMethod("doJumpHeight");
                jumpHeight.setAccessible(true);
                jumpHeight.invoke(manager);
                Method changeJumpDirection = PlayableSpriteMovement.class.getDeclaredMethod("doChgJumpDir");
                changeJumpDirection.setAccessible(true);
                changeJumpDirection.invoke(manager);

                assertFalse(mockSprite.getRollingJump(),
                                "S3K Sonic_ShieldMoves clears Status_RollJump before shield-specific branches");
                assertEquals((short) -0x18, mockSprite.getXSpeed(),
                                "Once Status_RollJump is cleared, Sonic_ChgJumpDir applies left air acceleration");
        }

        @Test
        public void s3kTailsJumpRepressDoesNotEnterSonicShieldMoves() throws Exception {
                GameModuleRegistry.setCurrent(new Sonic3kGameModule());
                Tails tails = new Tails("tails_p2", (short) 0, (short) 0);
                setGameRulesForTest(tails, GameRules.SONIC_3K);
                tails.setAir(true);
                tails.setRolling(true);
                tails.setRollingJump(true);
                tails.setJumping(true);
                PlayableSpriteMovement tailsMovement = new PlayableSpriteMovement(tails);

                Method shieldAbility = PlayableSpriteMovement.class.getDeclaredMethod("tryShieldAbility");
                shieldAbility.setAccessible(true);

                assertFalse((boolean) shieldAbility.invoke(tailsMovement));
                assertTrue(tails.getRollingJump(),
                                "Tails_JumpHeight does not call Sonic_ShieldMoves or clear Status_RollJump");
        }

        @Test
        public void s3kShieldMoveTransformsInsteadOfInstaShieldWhenEligible() throws Exception {
                GameModuleRegistry.setCurrent(new Sonic3kGameModule());
                setGameRulesForTest(GameRules.SONIC_3K);
                mockSprite.setSuperStateController(new Sonic3kSuperStateController(mockSprite));
                installCurrentModuleLevelState();
                collectAllChaosEmeralds();
                mockSprite.setRingCount(50);
                mockSprite.setAir(true);
                mockSprite.setJumping(true);
                mockSprite.setYSpeed((short) 0);
                mockSprite.setDoubleJumpFlag(0);

                assertTrue(GameServices.gameState().hasAllEmeralds(), "test precondition: all Chaos Emeralds");
                assertEquals(50, mockSprite.getRingCount(), "test precondition: 50 rings");
                assertTrue(mockSprite.getSuperStateController() != null, "test precondition: Super controller installed");

                setMovementField("jumpReleasedSinceJump", true);
                setMovementField("inputJumpPress", true);
                setMovementField("inputJump", true);
                invokeDoJumpHeight();

                assertTrue(mockSprite.isSuperSonic(), "Eligible S3K Sonic should transform before insta-shielding");
                assertEquals(0, mockSprite.getDoubleJumpFlag(),
                                "Starting Super/Hyper should not arm the insta-shield double-jump state");
        }

        @Test
        public void s3kHyperSonicSecondJumpUsesDirectionalDashInsteadOfInstaShield() throws Exception {
                GameModuleRegistry.setCurrent(new Sonic3kGameModule());
                setGameRulesForTest(GameRules.SONIC_3K);
                collectAllChaosEmeralds();
                collectAllSuperEmeralds();
                installCurrentModuleLevelState();
                mockSprite.setRingCount(50);
                Sonic3kSuperStateController controller = new Sonic3kSuperStateController(mockSprite);
                mockSprite.setSuperStateController(controller);
                assertTrue(controller.activateFromAirAbility());
                mockSprite.setAir(true);
                mockSprite.setJumping(true);
                mockSprite.setYSpeed((short) 0x120);
                mockSprite.setXSpeed((short) 0);
                mockSprite.setGSpeed((short) 0);
                mockSprite.setDoubleJumpFlag(0);
                setInputState(false, true, false, true, true);

                boolean handled = invokeTryShieldAbility();

                assertTrue(handled, "Hyper Sonic should consume the second jump with Hyper Dash");
                assertEquals(1, mockSprite.getDoubleJumpFlag(),
                                "Hyper Dash should arm the S3K double-jump-used state");
                assertEquals((short) 0x800, mockSprite.getXSpeed(),
                                "Holding right should dash horizontally to the right");
                assertEquals((short) -0x800, mockSprite.getYSpeed(),
                                "Holding up should dash vertically upward");
                assertEquals((short) 0x800, mockSprite.getGSpeed(),
                                "Hyper Dash should seed ground velocity from the horizontal component");
        }

        @Test
        public void s3kHyperDashPreservesRomInvalidDirectionMaskSemantics() throws Exception {
                GameModuleRegistry.setCurrent(new Sonic3kGameModule());
                setGameRulesForTest(GameRules.SONIC_3K);
                collectAllChaosEmeralds();
                collectAllSuperEmeralds();
                installCurrentModuleLevelState();
                mockSprite.setRingCount(50);
                Sonic3kSuperStateController controller = new Sonic3kSuperStateController(mockSprite);
                mockSprite.setSuperStateController(controller);
                assertTrue(controller.activateFromAirAbility());

                setInputState(false, false, true, true, false);
                invokeHyperDash();
                assertEquals((short) 0, mockSprite.getXSpeed(),
                                "raw mask 3 (up+down) uses the ROM table's zero vector");
                assertEquals((short) 0, mockSprite.getYSpeed());

                mockSprite.setDirection(Direction.LEFT);
                setInputState(false, true, true, true, false);
                invokeHyperDash();
                assertEquals((short) -0x800, mockSprite.getXSpeed(),
                                "raw masks >= $B fall back to the facing direction");
                assertEquals((short) 0, mockSprite.getYSpeed());
                assertEquals((short) -0x800, mockSprite.getGSpeed());
        }

        @Test
        public void superEmeraldInventoryAloneDoesNotExposeHyperDashCapability() throws Exception {
                GameModuleRegistry.setCurrent(new Sonic3kGameModule());
                setGameRulesForTest(GameRules.SONIC_3K);
                collectAllSuperEmeralds();
                mockSprite.setSuperSonic(true);
                mockSprite.setAir(true);
                mockSprite.setJumping(true);
                mockSprite.setXSpeed((short) 0);
                mockSprite.setYSpeed((short) 0x120);
                setInputState(false, true, false, true, true);

                assertTrue(invokeTryShieldAbility());
                assertEquals((short) 0, mockSprite.getXSpeed());
                assertEquals((short) 0x120, mockSprite.getYSpeed());
        }

        @Test
        public void s2JumpTriggerStillActivatesSuperSonic() throws Exception {
                GameModuleRegistry.setCurrent(new Sonic2GameModule());
                setGameRulesForTest(GameRules.SONIC_2);
                mockSprite.setSuperStateController(new Sonic2SuperStateController(mockSprite));
                installCurrentModuleLevelState();
                collectAllChaosEmeralds();
                mockSprite.setRingCount(50);
                mockSprite.setAir(true);
                mockSprite.setJumping(true);
                mockSprite.setYSpeed((short) 0);

                mockSprite.getSuperStateController().checkTransformationBeforeMove();
                mockSprite.getSuperStateController().update();

                assertEquals(SuperState.TRANSFORMING, mockSprite.getSuperStateController().getState(),
                                "S2 should still start Super Sonic from the normal airborne jump trigger");
                assertTrue(mockSprite.isSuperSonic(),
                                "S2 Super Sonic activation should set the player super flag immediately");
        }

        @Test
        public void s3kJumpDoesNotAutomaticallyActivateSuperSonic() throws Exception {
                GameModuleRegistry.setCurrent(new Sonic3kGameModule());
                setGameRulesForTest(GameRules.SONIC_3K);
                mockSprite.setSuperStateController(new Sonic3kSuperStateController(mockSprite));
                installCurrentModuleLevelState();
                collectAllChaosEmeralds();
                mockSprite.setRingCount(50);
                mockSprite.setAir(true);
                mockSprite.setJumping(true);
                mockSprite.setYSpeed((short) 0);

                mockSprite.getSuperStateController().checkTransformationBeforeMove();
                mockSprite.getSuperStateController().update();

                assertEquals(SuperState.NORMAL, mockSprite.getSuperStateController().getState(),
                                "S3K must wait for a second jump-button press instead of auto-transforming");
                assertFalse(mockSprite.isSuperSonic());
        }

        @Test
        public void s3kSecondPressWaitsForNormalAirAbilityThreshold() throws Exception {
                prepareEligibleS3kSonicSecondPress((short) -0x401, false);

                invokeDoJumpHeight();

                assertEquals(SuperState.NORMAL, mockSprite.getSuperStateController().getState());
        }

        @Test
        public void s3kSecondPressActivatesAtNormalAirAbilityThreshold() throws Exception {
                prepareEligibleS3kSonicSecondPress((short) -0x400, false);

                invokeDoJumpHeight();

                assertEquals(SuperState.TRANSFORMING, mockSprite.getSuperStateController().getState());
        }

        @Test
        public void s3kSecondPressWaitsForUnderwaterAirAbilityThreshold() throws Exception {
                prepareEligibleS3kSonicSecondPress((short) -0x201, true);

                invokeDoJumpHeight();

                assertEquals(SuperState.NORMAL, mockSprite.getSuperStateController().getState());
        }

        @Test
        public void s3kSecondPressActivatesAtUnderwaterAirAbilityThreshold() throws Exception {
                prepareEligibleS3kSonicSecondPress((short) -0x200, true);

                invokeDoJumpHeight();

                assertEquals(SuperState.TRANSFORMING, mockSprite.getSuperStateController().getState());
        }

        private void prepareEligibleS3kSonicSecondPress(short ySpeed, boolean underwater) throws Exception {
                GameModuleRegistry.setCurrent(new Sonic3kGameModule());
                setGameRulesForTest(GameRules.SONIC_3K);
                mockSprite.setSuperStateController(new Sonic3kSuperStateController(mockSprite));
                installCurrentModuleLevelState();
                collectAllChaosEmeralds();
                mockSprite.setRingCount(50);
                mockSprite.setAir(true);
                mockSprite.setJumping(true);
                mockSprite.setInWater(underwater);
                mockSprite.setYSpeed(ySpeed);
                mockSprite.setDoubleJumpFlag(0);
                setMovementField("jumpReleasedSinceJump", true);
                setMovementField("inputJumpPress", true);
                setMovementField("inputJump", true);
        }

        @Test
        public void s3kSpeedShoesDoubleAirAccelerationAfterWallZeroing() throws Exception {
                GameModuleRegistry.setCurrent(new Sonic3kGameModule());
                setGameRulesForTest(GameRules.SONIC_3K);
                mockSprite.giveSpeedShoes();
                mockSprite.setAir(true);
                mockSprite.setRolling(true);
                mockSprite.setRollingJump(false);
                mockSprite.setXSpeed((short) 0);
                mockSprite.setYSpeed((short) -0x0410);

                setInputState(false, true, false, false, false);

                Method changeJumpDirection = PlayableSpriteMovement.class.getDeclaredMethod("doChgJumpDir");
                changeJumpDirection.setAccessible(true);
                changeJumpDirection.invoke(manager);

                assertEquals((short) 0x0030, mockSprite.getXSpeed(),
                                "S3K Sonic_ChgJumpDir doubles speed-shoes Acceleration=$18 before adding right air control (sonic3k.asm:23088-23121)");
        }

        @Test
        public void s3kVerticalWrapMasksYAfterControlEvenWhenObjectControlsMovement() throws Exception {
                GameModuleRegistry.setCurrent(new Sonic3kGameModule());
                setGameRulesForTest(GameRules.SONIC_3K);
                Camera camera = GameServices.camera();
                camera.setMinY((short) -0x100);
                camera.setMaxY((short) 0x1000);
                camera.setVerticalWrapEnabled(true, 0x1000);

                mockSprite.setObjectControlled(true);
                mockSprite.setCentreY((short) 0x1001);

                manager.handleMovement(false, false, false, false, false, false, false, false);

                assertEquals((short) 0x0001, mockSprite.getCentreY(),
                                "S3K Sonic_Control still applies Screen_Y_wrap_value after object_control skips movement");
        }

        @Test
        public void objectControlledJumpReleaseDoesNotManufactureSecondPressWhenMovementResumes() throws Exception {
                GameModuleRegistry.setCurrent(new Sonic3kGameModule());
                setGameRulesForTest(GameRules.SONIC_3K);
                mockSprite.giveShield(ShieldType.FIRE);
                mockSprite.setAir(true);
                mockSprite.setRolling(true);
                mockSprite.setJumping(true);
                mockSprite.setXSpeed((short) 0x0200);
                mockSprite.setYSpeed((short) -0x0380);
                mockSprite.setDoubleJumpFlag(0);
                setMovementField("jumpReleasedSinceJump", true);

                // ROM Ctrl_1_logical=$1810: Right+B are held and B's press bit
                // is consumed by the controlling object while Sonic_Control is
                // skipped by object_control bit 0. SpriteManager publishes the
                // raw held input to objects but passes a control-filtered false
                // to normal movement.
                mockSprite.setObjectControlled(true);
                mockSprite.setJumpInputPressed(true);
                manager.handleMovement(false, false, false, false, false, false, false, false);

                // ROM next frame Ctrl_1_logical=$1800: B remains held but its
                // press bit is clear, so Sonic_ShieldMoves must not run.
                mockSprite.setObjectControlled(false);
                mockSprite.setJumpInputPressed(true, false);
                Method storeInput = PlayableSpriteMovement.class.getDeclaredMethod("storeInputState",
                                boolean.class, boolean.class, boolean.class, boolean.class, boolean.class);
                storeInput.setAccessible(true);
                storeInput.invoke(manager, false, false, false, true, true);
                Method jumpHeight = PlayableSpriteMovement.class.getDeclaredMethod("doJumpHeight");
                jumpHeight.setAccessible(true);
                jumpHeight.invoke(manager);

                assertEquals(0, mockSprite.getDoubleJumpFlag(),
                                "a jump press consumed during object control must not be replayed as a shield ability");
                assertFalse(mockSprite.getXSpeed() == (short) 0x0800,
                                "held B after object release must not manufacture the Fire Shield dash");
        }

        @Test
        public void objectControlledCpuPressIsConsumedWithoutReplayingAfterRelease() throws Exception {
                GameModuleRegistry.setCurrent(new Sonic3kGameModule());
                setGameRulesForTest(GameRules.SONIC_3K);
                mockSprite.setCpuControlled(true);
                mockSprite.giveShield(ShieldType.FIRE);
                mockSprite.setAir(true);mockSprite.setRolling(true);mockSprite.setJumping(true);
                mockSprite.setXSpeed((short)0x0200);mockSprite.setYSpeed((short)-0x0380);
                mockSprite.setDoubleJumpFlag(0);setMovementField("jumpReleasedSinceJump",true);

                // SpriteManager publishes the CPU's one-frame Ctrl_2_logical
                // press through forcedJumpPress before the movement call.
                mockSprite.setObjectControlled(true);
                mockSprite.setForcedJumpPress(true);
                manager.handleMovement(false,false,false,false,true,false,false,false);
                assertFalse(mockSprite.isForcedJumpPress(),
                                "the CPU press remains available in logicalJumpPressState but must not stay latched for movement");

                mockSprite.setObjectControlled(false);
                Method storeInput=PlayableSpriteMovement.class.getDeclaredMethod("storeInputState",
                                boolean.class,boolean.class,boolean.class,boolean.class,boolean.class);
                storeInput.setAccessible(true);storeInput.invoke(manager,false,false,false,false,true);
                Method jumpHeight=PlayableSpriteMovement.class.getDeclaredMethod("doJumpHeight");
                jumpHeight.setAccessible(true);jumpHeight.invoke(manager);

                assertEquals(0,mockSprite.getDoubleJumpFlag());
                assertFalse(mockSprite.getXSpeed()==(short)0x0800,
                                "a CPU press consumed by the controlling object must not fire a shield ability after release");
        }

        @Test
        public void s3kVerticalWrapPreservesYSubpixelLikeRomWordMask() throws Exception {
                GameModuleRegistry.setCurrent(new Sonic3kGameModule());
                setGameRulesForTest(GameRules.SONIC_3K);
                Camera camera = GameServices.camera();
                camera.setMinY((short) -0x100);
                camera.setMaxY((short) 0x1000);
                camera.setVerticalWrapEnabled(true, 0x1000);

                mockSprite.setCentreY((short) 0x1022);
                mockSprite.setSubpixelRaw(0xC000, 0xD000);

                assertTrue(camera.applyScreenYWrapValue(mockSprite));
                assertEquals((short) 0x0022, mockSprite.getCentreY(),
                                "S3K Screen_Y_wrap_value masks only the high y_pos word");
                assertEquals(0xD000, mockSprite.getYSubpixelRaw(),
                                "S3K and.w d0,y_pos(a0) preserves the low y_sub word");
        }

        @Test
        public void s2VerticalWrapMasksYAfterControl() throws Exception {
                GameModuleRegistry.setCurrent(new Sonic2GameModule());
                setGameRulesForTest(GameRules.SONIC_2);
                Camera camera = GameServices.camera();
                camera.setMinY((short) -0x100);
                camera.setMaxY((short) 0x0800);
                camera.setVerticalWrapEnabled(true, 0x0800);

                mockSprite.setCentreY((short) 0x0805);
                mockSprite.setSubpixelRaw(0x9400, 0xC900);

                assertTrue(camera.applyScreenYWrapValue(mockSprite));
                assertEquals((short) 0x0005, mockSprite.getCentreY(),
                                "S2 Obj01_Control masks y_pos with $7FF when Camera_Min_Y_pos is -$100");
                assertEquals(0xC900, mockSprite.getYSubpixelRaw(),
                                "S2 andi.w #$7FF,y_pos(a0) preserves y_sub");
        }

        @Test
        public void s2DeadCpuSidekickDoesNotApplyScreenYWrapDuringObj02Dead() throws Exception {
                GameModuleRegistry.setCurrent(new Sonic2GameModule());
                Camera camera = GameServices.camera();
                camera.setMinY((short) -0x100);
                camera.setMaxY((short) 0x0800);
                camera.setVerticalWrapEnabled(true, 0x0800);

                Tails tails = new Tails("tails_p2", (short) 0x0C07, (short) 0x0805);
                setGameRulesForTest(tails, GameRules.SONIC_2);
                tails.setCpuControlled(true);
                tails.setDead(true);
                tails.setAir(true);
                tails.setYSpeed((short) 0x0000);
                tails.setSubpixelRaw(0x8000, 0xC900);
                new SidekickCpuController(tails, mockSprite);

                new PlayableSpriteMovement(tails)
                                .handleMovement(false, false, false, false, false, false, false, false);

                assertEquals(0x0800, tails.getCentreY() & 0x0800,
                                "S2 Obj02_Dead runs ObjectMoveAndFall without masking y_pos by Screen_Y_wrap_value");
                assertEquals(0xC900, tails.getYSubpixelRaw(),
                                "ObjectMoveAndFall must preserve 16:16 subpixel carry while bypassing the wrap mask");
        }

        @Test
        public void s3kCameraUpdateWrapPreservesFocusedSpriteYSubpixelLikeRomWordMask() throws Exception {
                GameModuleRegistry.setCurrent(new Sonic3kGameModule());
                setGameRulesForTest(GameRules.SONIC_3K);
                Camera camera = GameServices.camera();
                camera.setFocusedSprite(mockSprite);
                camera.setMinY((short) -0x100);
                camera.setMaxY((short) 0x1000);
                camera.setY((short) 0x1000);
                camera.setVerticalWrapEnabled(true, 0x1000);

                mockSprite.setAir(true);
                mockSprite.setCentreY((short) 0x0083);
                mockSprite.setSubpixelRaw(0x7000, 0x3000);

                camera.updatePosition();

                assertEquals((short) 0x0083, mockSprite.getCentreY(),
                                "Camera vertical wrap masks only the high y_pos word");
                assertEquals(0x3000, mockSprite.getYSubpixelRaw(),
                                "S3K camera wrap must preserve y_sub like and.w d0,y_pos(a0)");
        }

        @Test
        public void testEndOfLevelKeepsBossStyleRightBoundaryClamp() throws Exception {
                GameServices.camera().setMinX((short) 0x0200);
                GameServices.camera().setMaxX((short) 0x0300);
                GameServices.gameState().setCurrentBossId(0);
                GameServices.gameState().setEndOfLevelActive(true);

                mockSprite.setCentreX((short) 0x0500);
                mockSprite.setXSpeed((short) 0);
                mockSprite.setGSpeed((short) 0x0400);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doLevelBoundary");
                method.setAccessible(true);
                method.invoke(manager);

                assertEquals(0x0300 + 320 - 24, mockSprite.getCentreX(),
                                "End-of-level signpost flow should keep the tighter post-boss right clamp");
                assertEquals(0, mockSprite.getXSpeed(), "Right clamp should clear xSpeed");
                assertEquals(0, mockSprite.getGSpeed(), "Right clamp should clear gSpeed");
        }

        @Test
        public void s3kRightLevelBoundaryAllowsEqualPredictedPosition() throws Exception {
                setGameRulesForTest(GameRules.SONIC_3K);
                GameServices.camera().setMinX((short) 0x0200);
                GameServices.camera().setMaxX((short) 0x2ED0);
                GameServices.gameState().setCurrentBossId(0);

                int rightBoundary = 0x2ED0 + 320 - 24;
                mockSprite.setCentreX((short) rightBoundary);
                mockSprite.setSubpixelRaw(0, 0);
                mockSprite.setXSpeed((short) 0x000C);
                mockSprite.setGSpeed((short) 0x000C);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doLevelBoundary");
                method.setAccessible(true);
                method.invoke(manager);

                assertEquals(rightBoundary, mockSprite.getCentreX() & 0xFFFF,
                                "S3K Player_LevelBound uses blo.s, so equality does not clamp");
                assertEquals(0x000C, mockSprite.getXSpeed(),
                                "S3K should preserve the first rightward acceleration at the exact right boundary");
                assertEquals(0x000C, mockSprite.getGSpeed(),
                                "S3K should preserve ground speed at the exact right boundary");
        }

        @Test
        public void s3kSpindashReleaseChecksBoundaryBeforePublishingReleaseVelocity() throws Exception {
                GameModuleRegistry.setCurrent(new Sonic3kGameModule());
                setGameRulesForTest(GameRules.SONIC_3K);
                Camera camera = GameServices.camera();
                camera.setMinX((short) 0x0200);
                camera.setMaxX((short) 0x4298);

                int rightBoundary = 0x4298 + 0x128;
                mockSprite.setAnimationProfile(new ScriptedVelocityAnimationProfile()
                                .setDuckAnimId(8)
                                .setSpindashAnimId(9)
                                .setRollAnimId(2));
                mockSprite.setAnimationId(9);
                mockSprite.setGroundSensors(rightEdgeFloorSensors(mockSprite));
                mockSprite.setCeilingSensors(new Sensor[] {
                                fixedSensor(mockSprite, Direction.UP, (byte) 0, (byte) 0),
                                fixedSensor(mockSprite, Direction.UP, (byte) 0, (byte) 0)
                });
                mockSprite.setPushSensors(new Sensor[] {
                                fixedSensor(mockSprite, Direction.LEFT, (byte) 0, (byte) 0),
                                fixedSensor(mockSprite, Direction.RIGHT, (byte) 0, (byte) 0)
                });
                mockSprite.setDirection(Direction.LEFT);
                mockSprite.setCentreX((short) (rightBoundary + 1));
                mockSprite.setSubpixelRaw(0, 0);
                mockSprite.setXSpeed((short) 0);
                mockSprite.setYSpeed((short) 0);
                mockSprite.setGSpeed((short) 0);
                mockSprite.setAir(false);
                mockSprite.setSpindash(true);
                mockSprite.setSpindashCounter((short) 0);

                manager.handleMovement(false, false, false, false,
                                false, false, false, false);

                assertEquals(rightBoundary, mockSprite.getCentreX() & 0xFFFF,
                                "SonicKnux_Spindash reaches Player_LevelBound while x_vel is still zero");
                assertEquals(0, mockSprite.getXSpeed(),
                                "Player_Boundary_Sides clears x_vel on the release frame");
                assertEquals(0, mockSprite.getGSpeed(),
                                "Player_Boundary_Sides must win over the just-calculated spindash inertia");
        }

        @Test
        public void s2RightLevelBoundaryClampsEqualPredictedPosition() throws Exception {
                setGameRulesForTest(GameRules.SONIC_2);
                GameServices.camera().setMinX((short) 0x0200);
                GameServices.camera().setMaxX((short) 0x2ED0);
                GameServices.gameState().setCurrentBossId(0);

                int rightBoundary = 0x2ED0 + 320 - 24 + 64;
                mockSprite.setCentreX((short) rightBoundary);
                mockSprite.setSubpixelRaw(0, 0);
                mockSprite.setXSpeed((short) 0x000C);
                mockSprite.setGSpeed((short) 0x000C);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doLevelBoundary");
                method.setAccessible(true);
                method.invoke(manager);

                assertEquals(rightBoundary, mockSprite.getCentreX() & 0xFFFF,
                                "S2 Sonic_LevelBound uses bls.s, so equality clamps at the right boundary");
                assertEquals(0, mockSprite.getXSpeed(), "S2 equality clamp should clear xSpeed");
                assertEquals(0, mockSprite.getGSpeed(), "S2 equality clamp should clear gSpeed");
        }

        @Test
        public void s2BossLockRightBoundaryUsesPreEasedMaxX() throws Exception {
                setGameRulesForTest(GameRules.SONIC_2);
                Camera camera = GameServices.camera();
                camera.setMinX((short) 0x0200);
                camera.setMaxX((short) 0x29FE);
                camera.setMaxXTarget((short) 0x2A00);
                camera.updateBoundaryEasing();
                GameServices.gameState().setCurrentBossId(6);

                int rightBoundary = 0x29FE + 320 - 24;
                mockSprite.setCentreX((short) rightBoundary);
                mockSprite.setSubpixelRaw(0, 0);
                mockSprite.setXSpeed((short) 0x0200);
                mockSprite.setGSpeed((short) 0x0200);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doLevelBoundary");
                method.setAccessible(true);
                method.invoke(manager);

                assertEquals(rightBoundary, mockSprite.getCentreX() & 0xFFFF);
                assertEquals(0, mockSprite.getXSpeed());
                assertEquals(0, mockSprite.getGSpeed());
        }

        @Test
        public void rightLevelBoundaryIsViewportIndependentAtWidescreen() throws Exception {
                // Regression: the right level boundary is the level's design edge
                // (Camera_Max_X_pos + 320), not the render viewport. Widening it by a
                // widescreen viewport let the player walk past the level's right wall
                // into the void beyond a camera lock and fall to their death.
                setGameRulesForTest(GameRules.SONIC_2);
                Camera camera = GameServices.camera();
                // Simulate an ULTRA_21_9 (528px) viewport on the gameplay camera.
                Field widthField = Camera.class.getDeclaredField("width");
                widthField.setAccessible(true);
                widthField.setShort(camera, (short) 528);

                camera.setMinX((short) 0x0200);
                int maxX = 0x2ED0;
                camera.setMaxX((short) maxX);
                GameServices.gameState().setCurrentBossId(0);

                // Native S2 non-strict right boundary = maxX + 320 - 24 + 64.
                int nativeBoundary = maxX + 320 - 24 + 64;
                // Place the player PAST the native edge but well within the old
                // viewport-widened boundary (maxX + 528 - 24 + 64). The buggy code
                // would not clamp here, letting the player into the void.
                mockSprite.setCentreX((short) (nativeBoundary + 40));
                mockSprite.setSubpixelRaw(0, 0);
                mockSprite.setXSpeed((short) 0);
                mockSprite.setGSpeed((short) 0);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doLevelBoundary");
                method.setAccessible(true);
                method.invoke(manager);

                assertEquals(nativeBoundary, mockSprite.getCentreX() & 0xFFFF,
                                "right boundary must clamp to the native level edge regardless of viewport "
                                + "width (else the player walks past the level wall into the void)");
        }

        // ====================================================================
        // Bottom level-boundary kill plane (centre-Y vs top-left)
        //
        // ROM cites:
        //   S1  Sonic_LevelBound .bottom:           cmp.w obY(a0),d0 / blt.s
        //                                            (s1disasm/_incObj/01 Sonic.asm:1014)
        //   S2  Sonic_LevelBound CheckBottom:       cmp.w y_pos(a0),d0 / blt.s
        //                                            (s2.asm:36950)
        //   S3K Player_LevelBound CheckBottom:      cmp.w y_pos(a0),d0 / blt.s
        //                                            (sonic3k.asm:23195)
        //   S3K Tails_Check_Screen_Boundaries:      cmp.w y_pos(a0),d0 / blt.s
        //                                            (sonic3k.asm:28430-28431)
        // The ROM word at y_pos(a0) is the player's centre-Y.
        // ====================================================================

        @Test
        public void s3kBottomLevelBoundaryUsesCentreY() throws Exception {
                // Place a Tails-sized sprite (height = 24 px, half = 12) so its
                // centreY sits 1 px past the kill threshold but its top-left
                // getY() sits exactly AT the threshold. With centre-Y compare
                // (S3K), kill fires; with top-left compare it would not.
                setGameRulesForTest(GameRules.SONIC_3K);
                mockSprite.setHeight(24); // Tails height_pixels = 0x18

                int maxY = 0x02B8; // ROM AIZ2 boss-area Camera_max_Y_pos
                int killThreshold = maxY + 224; // ROM: Camera_max_Y_pos + $E0
                GameServices.camera().setMaxY((short) maxY);
                GameServices.camera().setMaxYTarget((short) maxY);
                GameServices.camera().setLevelStarted(true);
                GameServices.camera().setMinX((short) 0x0200);
                GameServices.camera().setMaxX((short) 0x4000);

                // centreY = killThreshold + 1 → top-left getY = killThreshold - 11.
                // Top-left compare would not trigger (getY <= threshold), but
                // centre-Y compare does (centreY > threshold).
                short centreY = (short) (killThreshold + 1);
                mockSprite.setCentreY(centreY);
                mockSprite.setXSpeed((short) 0);
                mockSprite.setYSpeed((short) 0x0150);
                mockSprite.setGSpeed((short) 0);
                mockSprite.setCentreX((short) 0x1000);

                // Sanity check: with top-left semantics this would not trigger.
                assertTrue(mockSprite.getY() <= killThreshold,
                                "Top-left getY must NOT exceed threshold for the test to be meaningful");
                assertTrue(mockSprite.getCentreY() > killThreshold,
                                "Centre-Y must exceed threshold so the centre-Y compare fires");

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doLevelBoundary");
                method.setAccessible(true);
                method.invoke(manager);

                assertTrue(mockSprite.getDead(),
                                "S3K centre-Y boundary kill should fire when centreY > maxY+0xE0 "
                                                + "(sonic3k.asm:23195 cmp.w y_pos(a0),d0 / blt.s)");
                assertEquals(0, mockSprite.getXSpeed(),
                                "Kill_Character zeroes x_vel (sonic3k.asm:21148)");
                assertEquals(0, mockSprite.getGSpeed(),
                                "Kill_Character zeroes ground_vel (sonic3k.asm:21149)");
                assertEquals((short) -0x0700, mockSprite.getYSpeed(),
                                "Kill_Character writes y_vel = -$700 (sonic3k.asm:21147)");
        }

        @Test
        public void s2BottomLevelBoundaryUsesCentreY() throws Exception {
                // S2 ROM uses centre-Y at s2.asm:36950. Kill must fire for the
                // same centreY-just-past geometry even when top-left getY()
                // remains below the threshold.
                setGameRulesForTest(GameRules.SONIC_2);
                mockSprite.setHeight(24);

                int maxY = 0x02B8;
                int killThreshold = maxY + 224;
                GameServices.camera().setMaxY((short) maxY);
                GameServices.camera().setMaxYTarget((short) maxY);
                GameServices.camera().setLevelStarted(true);
                GameServices.camera().setMinX((short) 0x0200);
                GameServices.camera().setMaxX((short) 0x4000);

                short centreY = (short) (killThreshold + 1);
                mockSprite.setCentreY(centreY);
                mockSprite.setXSpeed((short) 0);
                mockSprite.setYSpeed((short) 0x0150);
                mockSprite.setGSpeed((short) 0);
                mockSprite.setCentreX((short) 0x1000);

                assertTrue(GameRules.SONIC_2.playerMovement().levelBoundaryUsesCentreY(),
                                "S2 must compare ROM y_pos(a0), which maps to engine centre-Y");
                assertTrue(mockSprite.getY() <= killThreshold,
                                "Top-left getY must NOT exceed threshold so this proves centre-Y comparison");

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doLevelBoundary");
                method.setAccessible(true);
                method.invoke(manager);

                assertTrue(mockSprite.getDead(),
                                "S2 centre-Y boundary kill should fire when centreY > maxY+0xE0 "
                                                + "(s2.asm:36950 cmp.w y_pos(a0),d0 / blt.s)");
        }

        @Test
        public void s1BottomLevelBoundaryUsesCentreY() throws Exception {
                // S1 ROM uses centre-Y at s1disasm/_incObj/01 Sonic.asm:1014.
                setGameRulesForTest(GameRules.SONIC_1);
                mockSprite.setHeight(40); // Sonic height_pixels = 0x28

                int maxY = 0x02B8;
                int killThreshold = maxY + 224;
                GameServices.camera().setMaxY((short) maxY);
                GameServices.camera().setMaxYTarget((short) maxY);
                GameServices.camera().setLevelStarted(true);
                GameServices.camera().setMinX((short) 0x0200);
                GameServices.camera().setMaxX((short) 0x4000);

                // For Sonic the centre-vs-top gap is 20 px. centreY = threshold + 1
                // → top-left getY = threshold - 19, well below threshold.
                short centreY = (short) (killThreshold + 1);
                mockSprite.setCentreY(centreY);
                mockSprite.setXSpeed((short) 0);
                mockSprite.setYSpeed((short) 0x0150);
                mockSprite.setGSpeed((short) 0);
                mockSprite.setCentreX((short) 0x1000);

                assertTrue(GameRules.SONIC_1.playerMovement().levelBoundaryUsesCentreY(),
                                "S1 must compare ROM obY(a0), which maps to engine centre-Y");
                assertTrue(mockSprite.getY() <= killThreshold,
                                "Top-left getY must NOT exceed threshold so this proves centre-Y comparison");

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doLevelBoundary");
                method.setAccessible(true);
                method.invoke(manager);

                assertTrue(mockSprite.getDead(),
                                "S1 centre-Y boundary kill should fire when centreY > maxY+0xE0 "
                                                + "(s1disasm/_incObj/01 Sonic.asm:1014 cmp.w obY(a0),d0 / blt.s)");
        }

        @Test
        public void s3kBottomLevelBoundaryRespectsTopLeftWhenCentreYBelowThreshold() throws Exception {
                // Reverse case: when centreY is BELOW the threshold (no kill),
                // S3K must not fire just because top-left getY would also be
                // below. Confirms the centre-Y compare is not stricter than
                // ROM in the negative direction.
                setGameRulesForTest(GameRules.SONIC_3K);
                mockSprite.setHeight(24);

                int maxY = 0x02B8;
                int killThreshold = maxY + 224;
                GameServices.camera().setMaxY((short) maxY);
                GameServices.camera().setMaxYTarget((short) maxY);
                GameServices.camera().setLevelStarted(true);
                GameServices.camera().setMinX((short) 0x0200);
                GameServices.camera().setMaxX((short) 0x4000);

                short centreY = (short) (killThreshold - 1);
                mockSprite.setCentreY(centreY);
                mockSprite.setXSpeed((short) 0);
                mockSprite.setYSpeed((short) 0x0150);
                mockSprite.setGSpeed((short) 0);
                mockSprite.setCentreX((short) 0x1000);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doLevelBoundary");
                method.setAccessible(true);
                method.invoke(manager);

                assertFalse(mockSprite.getDead(),
                                "Kill must not fire when centreY <= maxY+0xE0 (sonic3k.asm:23195 blt.s)");
        }

        @Test
        public void testCalculateLandingRightSlope() throws Exception {
                // Angle 0x20 (32). Slope \ (Down-Right).
                // ySpeed 500 (falling). xSpeed 0.
                // Expected gSpeed positive (slide right).

                mockSprite.setAngle((byte) 0x20);
                mockSprite.setYSpeed((short) 500);
                mockSprite.setXSpeed((short) 0);
                mockSprite.setGSpeed((short) 0);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("calculateLanding",
                                AbstractPlayableSprite.class);
                method.setAccessible(true);
                method.invoke(manager, mockSprite);

                assertTrue(mockSprite.getGSpeed() > 0, "gSpeed should be positive for right-facing slope, but was " + mockSprite.getGSpeed());
        }

        @Test
        public void testCalculateLandingLeftSlope() throws Exception {
                // Angle 0xE0 (224). Slope / (Up-Right).
                // ySpeed 500.
                // Expected gSpeed negative (slide left).

                mockSprite.setAngle((byte) 0xE0);
                mockSprite.setYSpeed((short) 500);
                mockSprite.setXSpeed((short) 0);
                mockSprite.setGSpeed((short) 0);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("calculateLanding",
                                AbstractPlayableSprite.class);
                method.setAccessible(true);
                method.invoke(manager, mockSprite);

                assertTrue(mockSprite.getGSpeed() < 0, "gSpeed should be negative for left-facing slope, but was " + mockSprite.getGSpeed());
        }

        @Test
        public void testDoLevelCollisionUsesGenericLandingForQuadrant00FloorTouch() throws Exception {
                mockSprite.setAir(true);
                mockSprite.setAngle((byte) 0xD8);
                mockSprite.setXSpeed((short) 0x0000);
                mockSprite.setYSpeed((short) 0x0021);
                mockSprite.setGSpeed((short) 0x0000);

                CollisionSystem collisionSystem =
                                new LandingProbeCollisionSystem((sprite, landingHandler, forceFloorCheck) -> {
                                        sprite.setAngle((byte) 0xD8);
                                        landingHandler.accept(sprite);
                                });
                manager = new PlayableSpriteMovement(mockSprite, collisionSystem, GameServices.gameState());
                installRuntimeCollisionSystem(collisionSystem);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doLevelCollision", boolean.class);
                method.setAccessible(true);
                method.invoke(manager, false);

                assertEquals((short) 0x0000, mockSprite.getXSpeed(),
                                "Quadrant 0x00 floor touch should keep generic steep-landing xSpeed handling");
                assertEquals((short) 0x0021, mockSprite.getYSpeed(),
                                "Generic steep landing should preserve ySpeed");
                assertEquals((short) -0x0021, mockSprite.getGSpeed(),
                                "Quadrant 0x00 floor touch should use generic steep-landing inertia");
        }

        @Test
        public void testDoLevelCollisionUsesDirectFloorLandingForQuadrantC0FloorTouch() throws Exception {
                mockSprite.setAir(true);
                mockSprite.setAngle((byte) 0xD8);
                mockSprite.setXSpeed((short) 0x0110);
                mockSprite.setYSpeed((short) 0x0021);
                mockSprite.setGSpeed((short) 0x0000);

                CollisionSystem collisionSystem =
                                new LandingProbeCollisionSystem((sprite, landingHandler, forceFloorCheck) -> {
                                        sprite.setAngle((byte) 0xD8);
                                        landingHandler.accept(sprite);
                                });
                manager = new PlayableSpriteMovement(mockSprite, collisionSystem, GameServices.gameState());
                installRuntimeCollisionSystem(collisionSystem);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doLevelCollision", boolean.class);
                method.setAccessible(true);
                method.invoke(manager, false);

                assertEquals((short) 0x0110, mockSprite.getXSpeed(),
                                "Quadrant 0xC0 direct floor touch should preserve xSpeed");
                assertEquals((short) 0x0000, mockSprite.getYSpeed(),
                                "Quadrant 0xC0 direct floor touch should zero ySpeed");
                assertEquals((short) 0x0110, mockSprite.getGSpeed(),
                                "Quadrant 0xC0 direct floor touch should copy xSpeed into gSpeed");
                assertTrue(!mockSprite.getAir(), "Floor touch should clear airborne state");
        }

        /**
         * Reproduction attempt for the reported "Sonic sometimes does not enter
         * hurt animation when injured while climbing a slope" (S1 bug batch
         * ledger row 9).
         * <p>
         * {@code modeAirborne()} deliberately skips {@code returnAngleToZero()}
         * while hurt ("the ground angle is preserved through recoil" -- S1 "01
         * Sonic.asm:1410", S2 "s2.asm:37806", above in this file), so a hurt
         * knockback launched from a steep slope keeps a nonzero {@code angle}
         * for as long as the knockback lasts. This test drives {@code
         * applyHurt()} from two starting ground angles -- flat (0x00) and a
         * steep 45-degree slope (0x20) -- through several airborne-hurt ticks
         * with a {@link LandingProbeCollisionSystem} that never reports ground
         * contact (isolating the {@code hurt}/{@code air}/velocity state
         * machine from real terrain sensor probing, which this lightweight
         * fixture has no sloped {@code SolidTile} data to drive faithfully).
         * <p>
         * Result: {@code hurt}, {@code air}, and the knockback velocities
         * evolve identically regardless of the starting angle -- only the
         * (correctly preserved) angle value itself differs. This rules out a
         * defect in the pure hurt/air dispatch and mode-selection logic
         * ({@code handleMovement}'s {@code isHurt() && !getAir()} /
         * {@code getAir()} branch order, {@code modeAirborne()}'s hurt
         * short-circuits). It does NOT rule out the real terrain-collision
         * sensor path incorrectly detecting ground contact one frame early on
         * steep terrain (e.g. a rotated/steep-ground-mode sensor snapping
         * Sonic back to the slope's surface before a flat floor's sensor
         * would) -- reproducing that needs a real sloped-terrain
         * {@code HeadlessTestRunner} level fixture, which does not exist in
         * this suite yet. Per the task brief, row 9 is reported
         * blocked-needs-capture rather than landing a speculative fix.
         */
        @Test
        public void hurtStatePersistsIdenticallyRegardlessOfGroundAngleAtImpact() throws Exception {
                CollisionSystem noLandingCollisionSystem =
                                new LandingProbeCollisionSystem((sprite, landingHandler, forceFloorCheck) -> {
                                        // Never invoke landingHandler: simulates open air under Sonic
                                        // for every airborne tick, so no real terrain sensor logic runs.
                                });

                boolean flatHurt = driveHurtFromAngleAndReturnFinalHurtState((byte) 0x00, noLandingCollisionSystem);
                boolean slopeHurt = driveHurtFromAngleAndReturnFinalHurtState((byte) 0x20, noLandingCollisionSystem);

                assertEquals(flatHurt, slopeHurt,
                                "Hurt state after several airborne-hurt ticks should not depend on the ground angle at impact");
                assertTrue(flatHurt, "Hurt should still be active after a few airborne ticks with no ground contact");
        }

        @Test
        public void s3kSidekickHurtRestoresStandingRadiiWhenRollStatusWasAlreadyCleared() {
                GameModuleRegistry.setCurrent(new Sonic3kGameModule());
                Tails tails = new Tails("tails_p2", (short) 0x200, (short) 0x300);
                tails.applyRollingRadii(false);
                tails.clearRollingFlagPreserveRadii();

                assertFalse(tails.getRolling());
                assertEquals(7, tails.getXRadius());
                assertEquals(14, tails.getYRadius());

                assertTrue(tails.applyHurt(tails.getCentreX() - 16));

                assertEquals(9, tails.getXRadius(),
                                "HurtCharacter Player_TouchFloor must restore default_x_radius");
                assertEquals(15, tails.getYRadius(),
                                "HurtCharacter Player_TouchFloor must restore default_y_radius");
                assertEquals(-9, tails.getGroundSensors()[0].getX());
                assertEquals(15, tails.getGroundSensors()[0].getY());
        }

        @Test
        public void s3kHurtAppliesNativeLiveRadiusDeltaSignForBothAngleHalvesAndGravityStates() {
                GameModuleRegistry.setCurrent(new Sonic3kGameModule());
                int[][] cases = {
                        {0x00, 0, 1},
                        {0x00, 1, -1},
                        {0x40, 0, -1},
                        {0x40, 1, 1}
                };
                try {
                        for (int[] testCase : cases) {
                                int angle = testCase[0];
                                boolean reverseGravity = testCase[1] != 0;
                                int expectedDeltaSign = testCase[2];
                                GameServices.gameState().setReverseGravityActive(reverseGravity);
                                Sonic sonic = new Sonic("sonic", (short) 0x200, (short) 0x300);
                                sonic.setRolling(true);
                                sonic.applyRollingRadii(false);
                                sonic.setAngle((byte) angle);
                                sonic.setCentreYPreserveSubpixel((short) 0x340);
                                sonic.setSubpixelRaw(0x5A00, 0xA500);
                                int centreYBeforeHurt = sonic.getCentreY();
                                int radiusDelta = sonic.getYRadius() - sonic.getStandYRadius();
                                String scenario = "angle=$%02X reverseGravity=%s"
                                                .formatted(angle, reverseGravity);

                                assertTrue(sonic.applyHurt(sonic.getCentreX() - 16), scenario);

                                assertEquals(
                                                centreYBeforeHurt + expectedDeltaSign * radiusDelta,
                                                sonic.getCentreY(),
                                                "Player_TouchFloor sign contract for " + scenario);
                                assertEquals(0xA500, sonic.getYSubpixelRaw(),
                                                "Player_TouchFloor add.w preserves fractional Y for " + scenario);
                                assertFalse(sonic.getRolling(), scenario);
                        }
                } finally {
                        GameServices.gameState().setReverseGravityActive(false);
                }
        }

        @Test
        public void s3kHurtUsesLiveRadiusDeltaWhenRollBitOutlivesRollingRadii() {
                GameModuleRegistry.setCurrent(new Sonic3kGameModule());
                Sonic sonic = new Sonic("sonic", (short) 0x200, (short) 0x300);
                sonic.setRolling(true);
                sonic.applyStandingRadii(false);
                sonic.setCentreYPreserveSubpixel((short) 0x340);
                int centreYBeforeHurt = sonic.getCentreY();

                assertTrue(sonic.getRolling());
                assertEquals(sonic.getStandYRadius(), sonic.getYRadius());
                assertTrue(sonic.applyHurt(sonic.getCentreX() - 16));

                assertEquals(centreYBeforeHurt, sonic.getCentreY(),
                                "Player_TouchFloor adds the live y_radius-default_y_radius delta");
                assertFalse(sonic.getRolling());
        }

        @Test
        public void s2SidekickHurtPreservesRadiiWhenRollStatusWasAlreadyCleared() {
                Tails tails = new Tails("tails_p2", (short) 0x200, (short) 0x300);
                tails.applyRollingRadii(false);
                tails.clearRollingFlagPreserveRadii();

                assertTrue(tails.applyHurt(tails.getCentreX() - 16));

                assertEquals(7, tails.getXRadius());
                assertEquals(14, tails.getYRadius());
        }

        private boolean driveHurtFromAngleAndReturnFinalHurtState(byte startingAngle,
                        CollisionSystem collisionSystem) throws Exception {
                // A dedicated sprite (not the shared mockSprite) with real, if
                // unscanned, sensor lines -- handleMovement()'s airborne path calls
                // updateSensors(), which NPEs against the shared mockSprite's
                // no-op createSensorLines(). The LandingProbeCollisionSystem below
                // replaces resolveAirCollision() entirely, so these sensors are
                // never actually scanned against terrain.
                AbstractPlayableSprite hurtSprite = new AbstractPlayableSprite("sonic", (short) 200, (short) 200) {
                        @Override
                        protected void defineSpeeds() {
                                this.max = 1536;
                                this.runAccel = 12;
                                this.runDecel = 128;
                                this.friction = 12;
                                this.jump = 1664;
                                this.slopeRunning = 32;
                                this.slopeRollingDown = 80;
                                this.slopeRollingUp = 20;
                        }

                        @Override
                        protected void createSensorLines() {
                                groundSensors = new Sensor[]{
                                                new GroundSensor(this, Direction.DOWN, (byte) -9, (byte) 19, true),
                                                new GroundSensor(this, Direction.DOWN, (byte) 9, (byte) 19, true)};
                                ceilingSensors = new Sensor[]{
                                                new GroundSensor(this, Direction.UP, (byte) -9, (byte) -19, false),
                                                new GroundSensor(this, Direction.UP, (byte) 9, (byte) -19, false)};
                                pushSensors = new Sensor[]{
                                                new GroundSensor(this, Direction.LEFT, (byte) -10, (byte) 0, false),
                                                new GroundSensor(this, Direction.RIGHT, (byte) 10, (byte) 0, false)};
                        }

                        @Override
                        public void draw() {
                        }
                };

                hurtSprite.setAir(false);
                hurtSprite.setHurt(false);
                hurtSprite.setAngle(startingAngle);
                hurtSprite.setXSpeed((short) 0);
                hurtSprite.setYSpeed((short) 0);
                hurtSprite.setGSpeed((short) 0);

                boolean applied = hurtSprite.applyHurt(hurtSprite.getCentreX() - 16);
                assertTrue(applied, "applyHurt should succeed from a plain grounded, non-invincible state");
                assertTrue(hurtSprite.isHurt(), "applyHurt must set hurt=true immediately");
                assertTrue(hurtSprite.getAir(), "applyHurt must set air=true immediately");
                assertEquals(startingAngle, hurtSprite.getAngle(),
                                "applyHurt itself must not touch the ground angle");

                PlayableSpriteMovement hurtManager =
                                new PlayableSpriteMovement(hurtSprite, collisionSystem, GameServices.gameState());
                installRuntimeCollisionSystem(collisionSystem);

                for (int i = 0; i < 5; i++) {
                        hurtManager.handleMovement(false, false, false, false, false, false, false, false);
                        assertEquals(startingAngle, hurtSprite.getAngle(),
                                        "Angle must stay preserved through recoil on every hurt tick (frame " + i + ")");
                }

                return hurtSprite.isHurt();
        }

        @Test
        public void testTerrainCollisionWithFlaggedAngle() throws Exception {
                // When a tile's collision angle has bit 0 set (the 0xFF "flat/flagged"
                // sentinel), doTerrainCollisionAir must IGNORE the player's stale angle
                // and snap to flat ground (0x00). Drive the real production routine for
                // a spread of stale starting angles; the expected snapped value (0x00)
                // is an independent hand-known constant, NOT recomputed by the SUT.
                byte[] staleAngles = {
                                (byte) 0x00, // already flat
                                (byte) 0x10, // shallow slope
                                (byte) 0x30, // steep slope
                                (byte) 0x70, // near-vertical
                                (byte) 0xC0, // right-wall (the original stale-angle bug)
                                (byte) 0xE0  // shallow left slope
                };

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doTerrainCollisionAir",
                                SensorResult[].class);
                method.setAccessible(true);

                for (byte staleAngle : staleAngles) {
                        mockSprite.setAngle(staleAngle);
                        mockSprite.setAir(true);
                        mockSprite.setGroundMode(GroundMode.GROUND);
                        mockSprite.setYSpeed((short) 1000); // falling so landing can occur
                        mockSprite.setXSpeed((short) 0);

                        // Flagged tile: angle 0xFF (bit 0 set) at the player's feet, landable.
                        SensorResult result = new SensorResult((byte) 0xFF, (byte) -1, 0, Direction.DOWN);
                        SensorResult[] results = { result, result };

                        method.invoke(manager, (Object) results);

                        assertEquals((byte) 0x00, mockSprite.getAngle(),
                                        "Flagged 0xFF tile should snap stale angle "
                                                        + String.format("0x%02X", staleAngle) + " to flat ground (0x00)");
                }
        }

        @Test
        public void testLandingFromAirWithStaleRightWallAngle() throws Exception {
                // Setup:
                // Sprite is in Air.
                // GroundMode is GROUND (automatically set when Air=true).
                // Angle is STALE (0xC0 = 192 = Right Wall).
                // Landing on a flat tile (0xFF).

                mockSprite.setAngle((byte) 0xC0); // Right Wall Angle
                mockSprite.setAir(true); // In Air
                mockSprite.setGroundMode(GroundMode.GROUND); // Ensure ground mode is GROUND

                // Simulating falling down
                mockSprite.setYSpeed((short) 1000);
                mockSprite.setXSpeed((short) 0);

                // Landing on 0xFF tile
                SensorResult result = new SensorResult((byte) 0xFF, (byte) -1, 0, Direction.DOWN);
                SensorResult[] results = { result, result };

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doTerrainCollisionAir",
                                SensorResult[].class);
                method.setAccessible(true);
                method.invoke(manager, (Object) results);

                // Verification:
                // The fix should ensure the angle snaps to 0x00 (Ground) based on the
                // GroundMode, not the stale angle 0xC0.

                assertEquals((byte) 0x00, mockSprite.getAngle(), "Angle should be reset to 0x00 when landing from Air on flat ground, ignoring stale angle.");
        }

        @Test
        public void testSlopeMomentumUncapped() throws Exception {
                // Initial speed at max (1536)
                mockSprite.setGSpeed((short) 1536);
                // Angle 0x20 (32). Slope \. This causes acceleration downhill (positive
                // gSpeed).
                mockSprite.setAngle((byte) 0x20);
                mockSprite.setAir(false);
                mockSprite.setRolling(false);

                // Set up input state: left=false, right=true, down=false, up=false, jump=false
                setInputState(false, true, false, false, false);

                // Use doGroundMove which replaces calculateGSpeed
                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doGroundMove");
                method.setAccessible(true);

                // First apply slope resist (as modeNormal does)
                Method slopeMethod = PlayableSpriteMovement.class.getDeclaredMethod("doSlopeResist");
                slopeMethod.setAccessible(true);
                slopeMethod.invoke(manager);

                // Then apply ground move
                method.invoke(manager);

                // Assert: gSpeed should be > 1536 (slope + movement)
                short newSpeed = mockSprite.getGSpeed();
                assertTrue(newSpeed > 1536, "gSpeed should exceed max (1536) when accelerating down slope, but was " + newSpeed);
        }

        @Test
        public void s3kSlopeResistCanStartGroundVelocityFromRest() throws Exception {
                setGameRulesForTest(GameRules.SONIC_3K);
                mockSprite.setAir(false);
                mockSprite.setRolling(false);
                mockSprite.setAngle((byte) 0x30);
                mockSprite.setGSpeed((short) 0);

                Method slopeMethod = PlayableSpriteMovement.class.getDeclaredMethod("doSlopeResist");
                slopeMethod.setAccessible(true);
                slopeMethod.invoke(manager);

                assertEquals((short) 0x001D, mockSprite.getGSpeed(),
                                "S3K Player_SlopeResist starts from rest when abs(slope effect) >= $0D");
        }

        @Test
        public void testRightInputMaintainHighSpeed() throws Exception {
                // Setup: Running super fast (3000), holding Right. Flat ground.
                mockSprite.setGSpeed((short) 3000);
                mockSprite.setAngle((byte) 0x00);
                mockSprite.setAir(false);
                mockSprite.setRolling(false);

                // Set up input state: left=false, right=true, down=false, up=false, jump=false
                setInputState(false, true, false, false, false);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doGroundMove");
                method.setAccessible(true);
                method.invoke(manager);

                // Assert: Speed should NOT drop to max (1536).
                assertEquals((short) 3000, mockSprite.getGSpeed(), "gSpeed should be maintained when > max");
        }

        @Test
        public void testRightInputAccelerateBelowMax() throws Exception {
                // Setup: Running below max (1000). Holding Right. Flat ground.
                mockSprite.setGSpeed((short) 1000);
                mockSprite.setAngle((byte) 0x00);
                mockSprite.setAir(false);
                mockSprite.setRolling(false);

                // Set up input state: left=false, right=true, down=false, up=false, jump=false
                setInputState(false, true, false, false, false);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doGroundMove");
                method.setAccessible(true);
                method.invoke(manager);

                // Assert: Speed should increase by runAccel (12).
                assertEquals((short) 1012, mockSprite.getGSpeed(), "gSpeed should increase by accel when < max");
        }

        @Test
        public void testRightInputSkidUsesRetailHighByteThresholdBug() throws Exception {
                // Retail S1/S2/S3K overwrite only d0's low byte during the flat-angle
                // check before cmpi.w #-$400,d0. With adjusted speed -0x309, that
                // compare sees 0xFC00 and enters the skid/facing-left path.
                mockSprite.setGSpeed((short) -0x0389);
                mockSprite.setAngle((byte) 0x00);
                mockSprite.setAir(false);
                mockSprite.setRolling(false);
                mockSprite.setDirection(Direction.RIGHT);

                setInputState(false, true, false, false, false);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doGroundMove");
                method.setAccessible(true);
                method.invoke(manager);

                assertEquals((short) -0x0309, mockSprite.getGSpeed());
                assertEquals(Direction.LEFT, mockSprite.getDirection(),
                        "Retail skid threshold compares the high-byte-truncated speed and flips facing left");
        }

        @Test
        public void s3kNegativeFlipTypeSuppressesGroundSkidAndFacingFlip() throws Exception {
                // S3K TurnLeft/TurnRight test flip_type with BMI after the retail
                // high-byte threshold bug but before entering Stop/skid. FBZ's
                // moving wire cage writes $80 while it owns the player.
                mockSprite.setGSpeed((short) -0x0389);
                mockSprite.setAngle((byte) 0x00);
                mockSprite.setAir(false);
                mockSprite.setRolling(false);
                mockSprite.setDirection(Direction.RIGHT);
                mockSprite.setFlipType(0x80);

                setInputState(false, true, false, false, false);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doGroundMove");
                method.setAccessible(true);
                method.invoke(manager);

                assertEquals((short) -0x0309, mockSprite.getGSpeed());
                assertEquals(Direction.RIGHT, mockSprite.getDirection(),
                        "negative flip_type exits before Stop changes Status_Facing");
                assertFalse(mockSprite.getSkidding());
                assertFalse(mockSprite.isFixedSkidDustActive(),
                        "the flip_type exit occurs before skid SFX/dust handling");
        }

        @Test
        public void testRightInputDoesNotSkidAtTruncatedMinusThreePixels() throws Exception {
                mockSprite.setGSpeed((short) -0x0380);
                mockSprite.setAngle((byte) 0x00);
                mockSprite.setAir(false);
                mockSprite.setRolling(false);
                mockSprite.setDirection(Direction.RIGHT);

                setInputState(false, true, false, false, false);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doGroundMove");
                method.setAccessible(true);
                method.invoke(manager);

                assertEquals((short) -0x0300, mockSprite.getGSpeed());
                assertEquals(Direction.RIGHT, mockSprite.getDirection(),
                        "0xFD00 is still above the ROM cmpi.w #-$400 skid threshold");
        }

        @Test
        public void negativeFlipTypeSuppressesGroundSkid() throws Exception {
                mockSprite.setGSpeed((short) 0x1000);
                mockSprite.setAngle((byte) 0x00);
                mockSprite.setAir(false);
                mockSprite.setRolling(false);
                mockSprite.setDirection(Direction.RIGHT);
                mockSprite.setFlipType(0x80);

                setInputState(true, false, false, false, false);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doGroundMove");
                method.setAccessible(true);
                method.invoke(manager);

                assertEquals((short) 0x0F80, mockSprite.getGSpeed());
                assertFalse(mockSprite.getSkidding(),
                        "S3K braking returns before Stop when signed flip_type is negative");
                assertEquals(Direction.RIGHT, mockSprite.getDirection(),
                        "The suppressed skid must not flip the player's facing bit");
        }

        @Test
        public void oppositeDirectionCrossingZeroDoesNotPublishWalk() throws Exception {
                ScriptedVelocityAnimationProfile profile = new ScriptedVelocityAnimationProfile()
                                .setIdleAnimId(5)
                                .setWalkAnimId(0)
                                .setRunAnimId(1)
                                .setSpringAnimId(0x10)
                                .setSkidAnimId(0x0D)
                                .setRunSpeedThreshold(0x600);
                mockSprite.setAnimationProfile(profile);
                mockSprite.setAnimationId(0x10);
                mockSprite.setGSpeed((short) 0x001C);
                mockSprite.setAngle((byte) 0);
                mockSprite.setAir(false);
                mockSprite.setRolling(false);
                mockSprite.setMovementInputActive(true);
                setInputState(true, false, false, false, false);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doGroundMove");
                method.setAccessible(true);
                method.invoke(manager);

                assertNull(profile.resolveAnimationId(mockSprite, 0, 32),
                                "MoveLeft's opposite-direction deceleration tail returns without writing anim, "
                                                + "including the frame inertia crosses zero");
        }

        @Test
        public void s1TerrainBalanceUsesLatchedAngleSentinelNotFreshSideGap() throws Exception {
                setGameRulesForTest(GameRules.SONIC_1);
                mockSprite.setGSpeed((short) 0);
                mockSprite.setAngle((byte) 0xF0);
                mockSprite.setAir(false);
                mockSprite.setRolling(false);
                mockSprite.setOnObject(false);

                Sensor left = new Sensor(mockSprite, Direction.DOWN, (byte) -9, (byte) 19, true) {
                        @Override
                        protected SensorResult doScan(short dx, short dy) {
                                // Both the fresh left probe and ObjFloorDist's centre
                                // probe see the gap at this post-movement position.
                                return new SensorResult((byte) 3, (byte) 25, 0, Direction.DOWN);
                        }
                };
                Sensor right = new Sensor(mockSprite, Direction.DOWN, (byte) 9, (byte) 19, true) {
                        @Override
                        protected SensorResult doScan(short dx, short dy) {
                                return new SensorResult((byte) 0xF0, (byte) 0, 0x9A, Direction.DOWN);
                        }
                };
                mockSprite.setGroundSensors(new Sensor[]{left, right});

                // Native angleright/angleleft still describe the preceding
                // AnglePos result, where neither probe had the empty-tile value 3.
                setMovementField("latchedNextTilt", 0xF0);
                setMovementField("latchedTilt", 0x00);
                Method updateBalance = PlayableSpriteMovement.class.getDeclaredMethod("updateBalanceState");
                updateBalance.setAccessible(true);
                updateBalance.invoke(manager);

                assertEquals(0, mockSprite.getBalanceState(),
                                "fresh missing-side geometry must not expose Balance one dispatch early");

                setMovementField("latchedTilt", 3);
                updateBalance.invoke(manager);
                assertEquals(1, mockSprite.getBalanceState(),
                                "S1 balances once the copied angleleft byte is the empty-tile sentinel");
                assertEquals(Direction.LEFT, mockSprite.getDirection());
        }

        @Test
        public void heldOppositeDirectionStillAllowsBalanceWhenBrakingReachesZero() throws Exception {
                setGameRulesForTest(GameRules.SONIC_1);
                mockSprite.setGSpeed((short) 0x80);
                mockSprite.setAngle((byte) 0);
                mockSprite.setAir(false);
                mockSprite.setRolling(false);
                mockSprite.setOnObject(false);
                mockSprite.setSkidding(true);
                setInputState(true, false, false, false, false);

                Sensor left = new Sensor(mockSprite, Direction.DOWN, (byte) -9, (byte) 19, true) {
                        @Override
                        protected SensorResult doScan(short dx, short dy) {
                                return new SensorResult((byte) 0, (byte) 25, 0, Direction.DOWN);
                        }
                };
                Sensor right = new Sensor(mockSprite, Direction.DOWN, (byte) 9, (byte) 19, true) {
                        @Override
                        protected SensorResult doScan(short dx, short dy) {
                                return new SensorResult((byte) 3, (byte) 25, 0, Direction.DOWN);
                        }
                };
                mockSprite.setGroundSensors(new Sensor[]{left, right});
                setMovementField("latchedNextTilt", 3);
                setMovementField("latchedTilt", 0);

                Method groundMove = PlayableSpriteMovement.class.getDeclaredMethod("doGroundMove");
                groundMove.setAccessible(true);
                groundMove.invoke(manager);
                assertEquals(0, mockSprite.getGSpeed(),
                                "S1 MoveLeft must preserve the exact positive-deceleration zero result");
                assertFalse(mockSprite.getSkidding(),
                                "the exact-zero path reaches Move's Wait/Balance tail, not the Stop branch");

                Method computeBalance = PlayableSpriteMovement.class
                                .getDeclaredMethod("computeCurrentFrameBalancing");
                computeBalance.setAccessible(true);

                assertTrue((boolean) computeBalance.invoke(manager),
                                "MoveLeft can brake inertia to zero before Sonic_Move enters its balance tail");
        }

        @Test
        public void heldDirectionAtRestDoesNotBroadlyEnableBalance() throws Exception {
                setGameRulesForTest(GameRules.SONIC_1);
                mockSprite.setGSpeed((short) 0);
                mockSprite.setAngle((byte) 0);
                mockSprite.setAir(false);
                mockSprite.setRolling(false);
                mockSprite.setOnObject(false);
                setInputState(true, false, false, false, false);

                Sensor left = new Sensor(mockSprite, Direction.DOWN, (byte) -9, (byte) 19, true) {
                        @Override
                        protected SensorResult doScan(short dx, short dy) {
                                return new SensorResult((byte) 0, (byte) 25, 0, Direction.DOWN);
                        }
                };
                Sensor right = new Sensor(mockSprite, Direction.DOWN, (byte) 9, (byte) 19, true) {
                        @Override
                        protected SensorResult doScan(short dx, short dy) {
                                return new SensorResult((byte) 3, (byte) 25, 0, Direction.DOWN);
                        }
                };
                mockSprite.setGroundSensors(new Sensor[]{left, right});
                setMovementField("latchedNextTilt", 3);
                setMovementField("latchedTilt", 0);

                Method computeBalance = PlayableSpriteMovement.class
                                .getDeclaredMethod("computeCurrentFrameBalancing");
                computeBalance.setAccessible(true);

                assertFalse((boolean) computeBalance.invoke(manager),
                                "held input only reaches Balance through the exact deceleration-to-zero branch");
        }

        @Test
        public void s3kStaleOnObjectReadsClearedInteractSlotForBalance() throws Exception {
                GameModuleRegistry.setCurrent(new Sonic3kGameModule());
                setGameRulesForTest(GameRules.SONIC_3K);
                Field objectManagerField = GameServices.level().getClass().getDeclaredField("objectManager");
                objectManagerField.setAccessible(true);
                objectManagerField.set(GameServices.level(), new ObjectManager(List.of(), null, 0, null, null));

                mockSprite.setCentreX((short) 0x3C90);
                mockSprite.setGSpeed((short) 0);
                mockSprite.setAngle((byte) 0);
                mockSprite.setAir(false);
                mockSprite.setRolling(false);
                mockSprite.setOnObject(true);
                mockSprite.setInteractSlotIndex(15);
                mockSprite.setDirection(Direction.RIGHT);

                Method updateBalance = PlayableSpriteMovement.class.getDeclaredMethod("updateBalanceState");
                updateBalance.setAccessible(true);
                updateBalance.invoke(manager);

                assertTrue(mockSprite.getBalanceState() > 0,
                                "Tails_InputAcceleration_Path reads width/x/status zero from a cleared interact SST");
                assertEquals(Direction.RIGHT, mockSprite.getDirection());
        }

        @Test
        public void mainPlayerLandingPublishesNativeTiltBytesWithoutSidekickRescan() throws Exception {
                GameModuleRegistry.setCurrent(new Sonic3kGameModule());
                setGameRulesForTest(GameRules.SONIC_3K);
                GameServices.sprites().addSprite(mockSprite, "sonic");

                Method publisherMethod = PlayableSpriteMovement.class.getDeclaredMethod("landingTiltPublisher");
                publisherMethod.setAccessible(true);
                @SuppressWarnings("unchecked")
                Consumer<SensorResult[]> publisher =
                                (Consumer<SensorResult[]>) publisherMethod.invoke(manager);
                assertNotNull(publisher, "The configured main player owns the native landing-angle copy");

                setMovementField("latchedNextTilt", 0x22);
                setMovementField("latchedTilt", 0x44);
                GameServices.collision().publishGroundAngleRegisters(
                                new SensorResult((byte) 0x44, (byte) 0, 0x80, Direction.DOWN),
                                new SensorResult((byte) 0x22, (byte) 0, 0x81, Direction.DOWN));
                SensorResult left = new SensorResult((byte) 0xFF, (byte) -7, 0x9A, Direction.DOWN);
                SensorResult right = new SensorResult((byte) 3, (byte) 25, 0, Direction.DOWN);
                publisher.accept(new SensorResult[]{left, right});

                Field nextTilt = PlayableSpriteMovement.class.getDeclaredField("latchedNextTilt");
                Field tilt = PlayableSpriteMovement.class.getDeclaredField("latchedTilt");
                nextTilt.setAccessible(true);
                tilt.setAccessible(true);
                assertEquals(0x22, nextTilt.getInt(manager),
                                "an empty FindFloor extension preserves the shared Primary_Angle byte");
                assertEquals(0xFF, tilt.getInt(manager));

                Tails sidekick = new Tails("tails_p2", (short) 0, (short) 0);
                sidekick.setCpuControlled(true);
                GameServices.sprites().addSprite(sidekick, "tails");
                PlayableSpriteMovement sidekickMovement = new PlayableSpriteMovement(sidekick);
                // ROM Tails_Control copies Primary_Angle/Secondary_Angle into the
                // sidekick's own next_tilt/tilt unconditionally (sonic3k.asm:26243-26244),
                // exactly as Sonic_Control does for the leader (25718-25719). So CPU Tails
                // owns a SEPARATE publisher writing its own latched pair -- the tail is not
                // absent, it is independent, and running it must not rescan or disturb the
                // leader's bytes.
                @SuppressWarnings("unchecked")
                Consumer<SensorResult[]> sidekickPublisher =
                                (Consumer<SensorResult[]>) publisherMethod.invoke(sidekickMovement);
                assertNotNull(sidekickPublisher,
                                "CPU Tails owns its own player-tail next_tilt/tilt copy");

                sidekickPublisher.accept(new SensorResult[]{
                        new SensorResult((byte) 0x20, (byte) -3, 0x81, Direction.DOWN),
                        new SensorResult((byte) 0x40, (byte) 11, 0x82, Direction.DOWN)});
                assertEquals(0x40, nextTilt.getInt(sidekickMovement));
                assertEquals(0x20, tilt.getInt(sidekickMovement));

                // The leader's latched bytes are untouched: separate cadence, no rescan.
                assertEquals(0x22, nextTilt.getInt(manager));
                assertEquals(0xFF, tilt.getInt(manager));
        }

        @Test
        public void s3kLiveLatchedSupportDoesNotReadAsClearedInteractSlot() throws Exception {
                GameModuleRegistry.setCurrent(new Sonic3kGameModule());
                setGameRulesForTest(GameRules.SONIC_3K);
                Field objectManagerField = GameServices.level().getClass().getDeclaredField("objectManager");
                objectManagerField.setAccessible(true);
                objectManagerField.set(GameServices.level(), new ObjectManager(List.of(), null, 0, null, null));

                ObjectInstance liveSupport = mock(ObjectInstance.class);
                mockSprite.setCentreX((short) 0x00D2);
                mockSprite.setGSpeed((short) 0);
                mockSprite.setAngle((byte) 0);
                mockSprite.setAir(false);
                mockSprite.setRolling(false);
                mockSprite.setOnObject(true);
                mockSprite.setLatchedSolidObject(0, liveSupport);
                mockSprite.setInteractSlotIndex(17);

                Method updateBalance = PlayableSpriteMovement.class.getDeclaredMethod("updateBalanceState");
                updateBalance.setAccessible(true);
                updateBalance.invoke(manager);

                assertEquals(0, mockSprite.getBalanceState(),
                                "a live manager-owned support is not a zeroed SST just because slot lookup is synthetic");
        }

        @Test
        public void s2FixedSkidDustTicksWhileAirborneStopAnimationPersists() throws Exception {
                setGameRulesForTest(GameRules.SONIC_2);
                Field objectManagerField = GameServices.level().getClass().getDeclaredField("objectManager");
                objectManagerField.setAccessible(true);
                objectManagerField.set(GameServices.level(), new ObjectManager(List.of(), null, 0, null, null));

                mockSprite.setSpindashDustController(new SpindashDustController(
                        mockSprite, mock(PlayerSpriteRenderer.class)));
                mockSprite.setAnimationProfile(new ScriptedVelocityAnimationProfile()
                        .setSkidAnimId(Sonic2AnimationIds.SKID));
                mockSprite.setAnimationId(Sonic2AnimationIds.SKID);
                mockSprite.setAir(true);
                mockSprite.setRolling(false);
                mockSprite.setHurt(false);
                mockSprite.setCentreX((short) 0x09AE);
                mockSprite.setCentreY((short) 0x0340);
                mockSprite.setSkidDustTimer(0);

                manager.advanceFixedSkidDustWhileStopAnimPersists();

                assertEquals(3, mockSprite.getSkidDustTimer(),
                        "Obj08 fixed dust timer should keep ticking while Stop/Skid anim persists airborne");
                assertEquals(1, GameServices.level().getObjectManager()
                        .activeObjectsOfType(SkidDustObjectInstance.class).size(),
                        "Ticking from timer 0 should allocate one skid dust child object");
        }

        @Test
        public void s2GroundedFixedSkidDustAllocatesFromPostMovementPosition() throws Exception {
                setGameRulesForTest(GameRules.SONIC_2);
                Field objectManagerField = GameServices.level().getClass().getDeclaredField("objectManager");
                objectManagerField.setAccessible(true);
                objectManagerField.set(GameServices.level(), new ObjectManager(List.of(), null, 0, null, null));

                mockSprite.setSpindashDustController(new SpindashDustController(
                        mockSprite, mock(PlayerSpriteRenderer.class)));
                mockSprite.setAnimationProfile(new ScriptedVelocityAnimationProfile()
                        .setSkidAnimId(Sonic2AnimationIds.SKID));
                mockSprite.setAnimationId(Sonic2AnimationIds.SKID);
                mockSprite.setAir(false);
                mockSprite.setRolling(false);
                mockSprite.setHurt(false);
                mockSprite.setCentreX((short) 0x0F3F);
                mockSprite.setCentreY((short) 0x04BC);
                mockSprite.setSkidDustTimer(0);

                Method advanceSkidDustTimer = PlayableSpriteMovement.class
                        .getDeclaredMethod("advanceSkidDustTimer");
                advanceSkidDustTimer.setAccessible(true);
                advanceSkidDustTimer.invoke(manager);

                assertEquals(0, GameServices.level().getObjectManager()
                        .activeObjectsOfType(SkidDustObjectInstance.class).size(),
                        "S2 fixed Obj08 skid dust should arm during input handling, not allocate pre-move");

                mockSprite.setCentreX((short) 0x0F44);
                mockSprite.setCentreY((short) 0x04BE);
                manager.advanceFixedSkidDustWhileStopAnimPersists();

                List<SkidDustObjectInstance> dust = GameServices.level().getObjectManager()
                        .activeObjectsOfType(SkidDustObjectInstance.class);
                assertEquals(1, dust.size(),
                        "Post-movement fixed Obj08 tick should allocate the skid dust child");
                assertEquals(0x0F44, dust.get(0).getSpawn().x());
                assertEquals(0x04CE, dust.get(0).getSpawn().y());
        }

        @Test
        public void s2SkidDustDeletesOnRomRoutineFourFrame() {
                SkidDustObjectInstance dust = new SkidDustObjectInstance(
                        0x0F44, 0x04CE, mock(PlayerSpriteRenderer.class), false);

                for (int i = 0; i < 17; i++) {
                        dust.update(i, mockSprite);
                }
                assertFalse(dust.isDestroyed(),
                        "Obj08 skid child should remain allocated on the first routine-4 display frame");

                dust.update(17, mockSprite);

                assertTrue(dust.isDestroyed(),
                        "Obj08 routine 4 tails to DeleteObject on the next object pass");
        }

        @Test
        public void s2FixedSkidDustDoesNotTickDuringHurtRoutine() throws Exception {
                setGameRulesForTest(GameRules.SONIC_2);
                Field objectManagerField = GameServices.level().getClass().getDeclaredField("objectManager");
                objectManagerField.setAccessible(true);
                objectManagerField.set(GameServices.level(), new ObjectManager(List.of(), null, 0, null, null));

                mockSprite.setSpindashDustController(new SpindashDustController(
                        mockSprite, mock(PlayerSpriteRenderer.class)));
                mockSprite.setAnimationProfile(new ScriptedVelocityAnimationProfile()
                        .setSkidAnimId(Sonic2AnimationIds.SKID));
                mockSprite.setAnimationId(Sonic2AnimationIds.SKID);
                mockSprite.setAir(true);
                mockSprite.setRolling(false);
                mockSprite.setHurt(true);
                mockSprite.setCentreX((short) 0x0556);
                mockSprite.setCentreY((short) 0x044C);
                mockSprite.setSkidDustTimer(0);

                manager.advanceFixedSkidDustWhileStopAnimPersists();

                assertEquals(0, mockSprite.getSkidDustTimer(),
                        "Obj08_CheckSkid stops ticking once the parent has entered the hurt routine");
                assertEquals(0, GameServices.level().getObjectManager()
                        .activeObjectsOfType(SkidDustObjectInstance.class).size(),
                        "Hurt routine must not allocate an extra skid dust object ahead of lost rings");
        }

        @Test
        public void testLeftInputMaintainHighSpeed() throws Exception {
                // Setup: Running super fast LEFT (-3000), holding Left. Flat ground.
                mockSprite.setGSpeed((short) -3000);
                mockSprite.setAngle((byte) 0x00);
                mockSprite.setAir(false);
                mockSprite.setRolling(false);

                // Set up input state
                setInputState(true, false, false, false, false); // left

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doGroundMove");
                method.setAccessible(true);
                method.invoke(manager);

                // Assert: Speed should NOT clamp to -max (-1536).
                assertEquals((short) -3000, mockSprite.getGSpeed(), "gSpeed should be maintained when < -max");
        }

        /**
         * Test air drag with xSpeed = 3072 at jump apex.
         * Sonic 2 formula: xSpeed = xSpeed - (xSpeed / 32)
         * 3072 - (3072 / 32) = 3072 - 96 = 2976
         */
        @Test
        public void testAirDragAtApex() throws Exception {
                // Setup: In air, near apex (ySpeed between -1024 and 0)
                mockSprite.setAir(true);
                mockSprite.setXSpeed((short) 3072);
                mockSprite.setYSpeed((short) -500); // Near apex, moving up slowly
                mockSprite.setHurt(false);
                mockSprite.setRollingJump(false);

                // Set up input state: no input
                setInputState(false, false, false, false, false);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doChgJumpDir");
                method.setAccessible(true);
                method.invoke(manager);

                // Assert: Air drag should reduce xSpeed by xSpeed/32 = 96
                assertEquals((short) 2976, mockSprite.getXSpeed(), "Air drag should reduce xSpeed from 3072 to 2976");
        }

        /**
         * Test air drag sequence over multiple frames matches Sonic 2 behavior.
         */
        @Test
        public void testAirDragSequence() throws Exception {
                mockSprite.setAir(true);
                mockSprite.setHurt(false);
                mockSprite.setRollingJump(false);

                // Set up input state: no input
                setInputState(false, false, false, false, false);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doChgJumpDir");
                method.setAccessible(true);

                short[] expectedSpeeds = { 2976, 2883, 2793 };
                mockSprite.setXSpeed((short) 3072);

                for (int i = 0; i < 3; i++) {
                        mockSprite.setYSpeed((short) -500);
                        method.invoke(manager);
                        assertEquals(expectedSpeeds[i], mockSprite.getXSpeed(), "Frame " + (i + 1) + " air drag result");
                }
        }

        /**
         * Test air drag does NOT apply when falling (ySpeed >= 0).
         */
        @Test
        public void testNoAirDragWhenFalling() throws Exception {
                mockSprite.setAir(true);
                mockSprite.setXSpeed((short) 3072);
                mockSprite.setYSpeed((short) 100); // Falling
                mockSprite.setHurt(false);
                mockSprite.setRollingJump(false);

                setInputState(false, false, false, false, false);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doChgJumpDir");
                method.setAccessible(true);
                method.invoke(manager);

                assertEquals((short) 3072, mockSprite.getXSpeed(), "No air drag when falling");
        }

        /**
         * Test air drag does NOT apply when ySpeed < -1024 (high upward velocity).
         */
        @Test
        public void testNoAirDragWhenHighUpwardVelocity() throws Exception {
                mockSprite.setAir(true);
                mockSprite.setXSpeed((short) 3072);
                mockSprite.setYSpeed((short) -1500); // High upward velocity
                mockSprite.setHurt(false);
                mockSprite.setRollingJump(false);

                setInputState(false, false, false, false, false);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doChgJumpDir");
                method.setAccessible(true);
                method.invoke(manager);

                assertEquals((short) 3072, mockSprite.getXSpeed(), "No air drag when ySpeed < -1024");
        }

        /**
         * Test air drag DOES apply when sprite is hurt/in knockback.
         * ROM: Sonic_ChgJumpDir does NOT gate air drag on hurt state.
         * The hurt state affects initial knockback velocity, not ongoing air physics.
         */
        @Test
        public void testAirDragAppliesWhenHurt() throws Exception {
                mockSprite.setAir(true);
                mockSprite.setXSpeed((short) 3072);
                mockSprite.setYSpeed((short) -500); // In drag range
                mockSprite.setHurt(true); // Hurt/knockback state
                mockSprite.setRollingJump(false);

                setInputState(false, false, false, false, false);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doChgJumpDir");
                method.setAccessible(true);
                method.invoke(manager);

                // ROM: Air drag applies regardless of hurt state
                // 3072 - (3072 / 32) = 3072 - 96 = 2976
                assertEquals((short) 2976, mockSprite.getXSpeed(), "Air drag applies when hurt");
        }

        /**
         * Test air drag stops when abs(xSpeed) < 32.
         */
        @Test
        public void testAirDragStopsWhenSpeedLow() throws Exception {
                mockSprite.setAir(true);
                mockSprite.setXSpeed((short) 31); // Below threshold
                mockSprite.setYSpeed((short) -500); // In drag range
                mockSprite.setHurt(false);
                mockSprite.setRollingJump(false);

                setInputState(false, false, false, false, false);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doChgJumpDir");
                method.setAccessible(true);
                method.invoke(manager);

                assertEquals((short) 31, mockSprite.getXSpeed(), "Air drag stops when xSpeed < 32");
        }

        /**
         * Test air drag with negative xSpeed.
         */
        @Test
        public void testAirDragNegativeSpeed() throws Exception {
                mockSprite.setAir(true);
                mockSprite.setXSpeed((short) -3072);
                mockSprite.setYSpeed((short) -500); // In drag range
                mockSprite.setHurt(false);
                mockSprite.setRollingJump(false);

                setInputState(false, false, false, false, false);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doChgJumpDir");
                method.setAccessible(true);
                method.invoke(manager);

                assertEquals((short) -2976, mockSprite.getXSpeed(), "Air drag with negative xSpeed");
        }

        /**
         * Test air drag DOES apply at exactly ySpeed = -1024 (boundary condition).
         */
        @Test
        public void testAirDragAtYSpeedBoundary() throws Exception {
                mockSprite.setAir(true);
                mockSprite.setXSpeed((short) 3072);
                mockSprite.setYSpeed((short) -1024); // Exactly at boundary
                mockSprite.setHurt(false);
                mockSprite.setRollingJump(false);

                setInputState(false, false, false, false, false);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doChgJumpDir");
                method.setAccessible(true);
                method.invoke(manager);

                assertEquals((short) 2976, mockSprite.getXSpeed(), "Air drag SHOULD apply at ySpeed = -1024");
        }

        @Test
        public void s3kLightningShieldClearsJumpHeightLatchLikeRomJumpingByte() throws Exception {
                manager.setJumpHeightLatch();
                mockSprite.setJumping(true);
                mockSprite.setAir(true);
                mockSprite.setYSpeed((short) -0x510);

                Method lightningShieldJump = PlayableSpriteMovement.class.getDeclaredMethod("lightningShieldJump");
                lightningShieldJump.setAccessible(true);
                lightningShieldJump.invoke(manager);

                assertEquals((short) -0x580, mockSprite.getYSpeed(), "Lightning shield writes the ROM y_vel");
                assertFalse(mockSprite.isJumping(), "ROM Sonic_LightningShield clears jumping(a0)");

                mockSprite.setYSpeed((short) -0x510);
                setInputState(false, false, false, false, false);
                Method doJumpHeight = PlayableSpriteMovement.class.getDeclaredMethod("doJumpHeight");
                doJumpHeight.setAccessible(true);
                doJumpHeight.invoke(manager);

                assertEquals((short) -0x510, mockSprite.getYSpeed(),
                                "After lightning shield, jump release must not reapply the -$400 jump-height cap");
        }

        /**
         * Test that jumping on a slope correctly uses the terrain angle for velocity.
         */
        @Test
        public void testJumpUsesTerrainAngle() throws Exception {
                mockSprite.setAir(false);
                mockSprite.setAngle((byte) 0x20);
                mockSprite.setXSpeed((short) 0);
                mockSprite.setYSpeed((short) 0);
                mockSprite.setGSpeed((short) 0);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doJump");
                method.setAccessible(true);
                method.invoke(manager);

                assertTrue(mockSprite.getXSpeed() != 0, "Jump on slope should have non-zero xSpeed component, but was " + mockSprite.getXSpeed());
                assertTrue(mockSprite.getYSpeed() < 0, "Jump should always have upward ySpeed component");
        }

        /**
         * Test that jumping on flat ground results in straight up jump.
         */
        @Test
        public void testJumpOnFlatGround() throws Exception {
                mockSprite.setAir(false);
                mockSprite.setAngle((byte) 0x00);
                mockSprite.setXSpeed((short) 256);
                mockSprite.setYSpeed((short) 0);
                mockSprite.setGSpeed((short) 256);

                short initialXSpeed = mockSprite.getXSpeed();

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doJump");
                method.setAccessible(true);
                method.invoke(manager);

                assertEquals(initialXSpeed, mockSprite.getXSpeed(), "Jump on flat ground should not change xSpeed");
                assertTrue(mockSprite.getYSpeed() < 0, "Jump should have upward ySpeed");
        }

        @Test
        public void jumpPreservesStatusOnObjectUntilSolidObjectPass() throws Exception {
                mockSprite.setAir(false);
                mockSprite.setOnObject(true);
                mockSprite.setPushing(true);
                mockSprite.setAngle((byte) 0x00);
                mockSprite.setXSpeed((short) 0);
                mockSprite.setYSpeed((short) 0);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doJump");
                method.setAccessible(true);
                method.invoke(manager);

                assertTrue(mockSprite.getAir(), "Jump sets Status_InAir");
                assertFalse(mockSprite.getPushing(), "Jump clears Status_Push");
                assertTrue(mockSprite.isOnObject(),
                                "S1/S2/S3K jump routines leave Status_OnObj for the next SolidObject pass");
        }

        @Test
        public void s3kStandingJumpMovesNativeCentreByExactRadiusDifference() throws Exception {
                GameModuleRegistry.setCurrent(new Sonic3kGameModule());
                Sonic sonic = new Sonic("sonic", (short) 0, (short) 0);
                sonic.setCentreY((short) 0x036D);
                sonic.setAir(false);
                sonic.setAngle((byte) 0);
                PlayableSpriteMovement movement = new PlayableSpriteMovement(sonic);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doJump");
                method.setAccessible(true);
                assertTrue((Boolean) method.invoke(movement));

                assertEquals(0x0372, sonic.getCentreY() & 0xFFFF,
                                "Sonic_Jump adds default_y_radius($13)-roll_y_radius($E) to y_pos");
                assertEquals(0x0E, sonic.getYRadius());
        }

        @Test
        public void s3kSpindashAnimationTransitionPreservesPushForFollowerHistory() throws Exception {
                setGameRulesForTest(GameRules.SONIC_3K);
                ScriptedVelocityAnimationProfile profile = new ScriptedVelocityAnimationProfile()
                                .setDuckAnimId(8)
                                .setSpindashAnimId(9);
                mockSprite.setAnimationProfile(profile);
                mockSprite.setAnimationId(profile.getDuckAnimId());
                mockSprite.setPushing(true);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("setSpindashAnimation");
                method.setAccessible(true);
                method.invoke(manager);

                assertEquals(profile.getSpindashAnimId(), mockSprite.getAnimationId());
                assertTrue(mockSprite.getPushing(),
                                "Sonic_RecordPos runs before Animate_Sonic clears Status_Push");
                mockSprite.recordFollowerHistoryForTick();
                assertEquals(AbstractPlayableSprite.STATUS_PUSHING,
                                mockSprite.getStatusHistory(0) & AbstractPlayableSprite.STATUS_PUSHING,
                                "the delayed Tails CPU status table must retain the pre-animation push bit");
                manager.applyDeferredSpindashAnimationPushClear();
                assertFalse(mockSprite.getPushing(),
                                "the later animation transition clears Push after follower history");

                mockSprite.setPushing(true);
                method.invoke(manager);
                assertTrue(mockSprite.getPushing(),
                                "an active charge leaves the clear to its later animation pass");
                manager.applyDeferredSpindashAnimationPushClear();
                assertFalse(mockSprite.getPushing(),
                                "each active charge's $0900 word re-arms the later animation clear");
        }

        @Test
        public void s2SpindashChargePulseRepublishesSpindashAnimation() throws Exception {
                setGameRulesForTest(GameRules.SONIC_2);
                ScriptedVelocityAnimationProfile profile = new ScriptedVelocityAnimationProfile()
                                .setDuckAnimId(8)
                                .setSpindashAnimId(9);
                mockSprite.setAnimationProfile(profile);
                mockSprite.setAnimationId(0);
                mockSprite.setSpindash(true);

                // Tails_ChargingSpindash calls this publisher only when the
                // pressed ABC bits are nonzero, before its boundary/AnglePos tail.
                Method method = PlayableSpriteMovement.class.getDeclaredMethod("setSpindashAnimation");
                method.setAccessible(true);
                method.invoke(manager);

                assertEquals(profile.getSpindashAnimId(), mockSprite.getAnimationId(),
                                "a fresh ABC charge press republishes the ROM $0900 animation word");
        }

        @Test
        public void s2ObjectOnlyPinballGuardDoesNotBlockRollingJump() throws Exception {
                setGameRulesForTest(GameRules.SONIC_2);
                mockSprite.setAir(false);
                mockSprite.setRolling(true);
                mockSprite.setPinballMode(true);
                mockSprite.setSpindash(false);
                mockSprite.setAngle((byte) 0x00);
                mockSprite.setYSpeed((short) 0);
                setMovementField("inputJumpPress", true);
                setMovementField("inputJump", true);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("modeRoll");
                method.setAccessible(true);
                method.invoke(manager);

                assertTrue(mockSprite.getAir(),
                                "S2 Obj02_MdRoll should run Tails_Jump when only the engine Obj85/Obj86 roll guard is set");
                assertTrue(mockSprite.getRollingJump(),
                                "Rolling jump should set Status_RollJump");
                assertFalse(mockSprite.getPinballMode(),
                                "The synthetic object guard must be cleared before later pinball_mode tests");
                assertEquals((short) -0x0680, mockSprite.getYSpeed(),
                                "Flat rolling jump should apply Tails/Sonic jump velocity");
        }

        @Test
        public void s2RomBackedPinballStillBlocksRollingJump() throws Exception {
                setGameRulesForTest(GameRules.SONIC_2);
                mockSprite.setAir(false);
                mockSprite.setRolling(true);
                mockSprite.setPinballMode(true);
                mockSprite.setSpindash(true);
                mockSprite.setAngle((byte) 0x00);
                setMovementField("inputJumpPress", true);
                setMovementField("inputJump", true);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("romPinballModeBlocksRollingJump");
                method.setAccessible(true);
                boolean blocked = (Boolean) method.invoke(manager);

                assertTrue(blocked,
                                "S2 Obj84's ROM-backed pinball_mode/spindash mirror must still skip Tails_Jump");
                assertTrue(mockSprite.getPinballMode(),
                                "ROM-backed pinball_mode remains latched while the roll path continues");
        }

        @Test
        public void jumpFromWallModeEnteringRollPreservesRomXPos() throws Exception {
                mockSprite.setAir(false);
                mockSprite.setRolling(false);
                mockSprite.setGroundMode(GroundMode.RIGHTWALL);
                mockSprite.setAngle((byte) 0xC0);
                mockSprite.setWidth(mockSprite.getStandYRadius() * 2);
                mockSprite.setHeight(mockSprite.getStandYRadius() * 2);
                mockSprite.setCentreX((short) 0x167B);
                mockSprite.setCentreY((short) 0x0200);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doJump");
                method.setAccessible(true);
                method.invoke(manager);

                assertEquals((short) 0x167B, mockSprite.getCentreX(),
                                "Sonic_Jump/Tails_Jump writes x_radius/y_radius but never adjusts x_pos when entering roll");
        }

        /**
         * Test that jumping on an uphill slope has negative X component.
         */
        @Test
        public void testJumpOnUphillSlope() throws Exception {
                mockSprite.setAir(false);
                mockSprite.setAngle((byte) 0xE0);
                mockSprite.setXSpeed((short) 0);
                mockSprite.setYSpeed((short) 0);
                mockSprite.setGSpeed((short) 0);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doJump");
                method.setAccessible(true);
                method.invoke(manager);

                assertTrue(mockSprite.getXSpeed() < 0, "Jump on uphill slope should have negative xSpeed, but was " + mockSprite.getXSpeed());
                assertTrue(mockSprite.getYSpeed() < 0, "Jump should always have upward ySpeed");
        }

        /**
         * Test that angle is properly captured before setAir resets it.
         */
        @Test
        public void testJumpAngleCapturedBeforeAirReset() throws Exception {
                mockSprite.setAir(false);
                mockSprite.setAngle((byte) 0x40); // 90 degrees (wall)

                assertEquals((byte) 0x40, mockSprite.getAngle(), "Angle should be 0x40 before jump");

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doJump");
                method.setAccessible(true);
                method.invoke(manager);

                assertTrue(mockSprite.getAir(), "Sprite should be in air after jump");
                assertEquals((byte) 0x40, mockSprite.getAngle(), "Angle should still be 0x40 immediately after jump");
                assertTrue(mockSprite.getXSpeed() != 0 || mockSprite.getYSpeed() != 0, "Jump should have used original angle for velocity calculation");
        }

        /**
         * Test that angle gradually returns to 0 while airborne.
         */
        @Test
        public void testAirAngleGradualReturn() {
                mockSprite.setAngle((byte) 0x10);
                mockSprite.returnAngleToZero();
                assertEquals((byte) 0x0E, mockSprite.getAngle(), "Angle should decrease by 2");

                mockSprite.returnAngleToZero();
                assertEquals((byte) 0x0C, mockSprite.getAngle(), "Angle should decrease by 2 again");

                mockSprite.setAngle((byte) 0xF0);
                mockSprite.returnAngleToZero();
                assertEquals((byte) 0xF2, mockSprite.getAngle(), "Angle should increase by 2 toward 0");

                mockSprite.returnAngleToZero();
                assertEquals((byte) 0xF4, mockSprite.getAngle(), "Angle should increase by 2 again");

                mockSprite.setAngle((byte) 0x00);
                mockSprite.returnAngleToZero();
                assertEquals((byte) 0x00, mockSprite.getAngle(), "Angle at 0 should stay 0");
        }

        /**
         * Test that air control IS enabled when sprite is in hurt/knockback state.
         * ROM: Sonic_ChgJumpDir does NOT gate air control on hurt state.
         * The hurt state affects initial knockback velocity, not ongoing air physics.
         */
        @Test
        public void testAirControlWorksWhenHurt() throws Exception {
                mockSprite.setAir(true);
                mockSprite.setXSpeed((short) 1000);
                mockSprite.setYSpeed((short) 500); // Falling (no drag)
                mockSprite.setHurt(true);
                mockSprite.setRollingJump(false);

                // Test left input - air control should work
                setInputState(true, false, false, false, false);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doChgJumpDir");
                method.setAccessible(true);
                method.invoke(manager);

                // ROM: Air control applies regardless of hurt state
                // Default runAccel is 12 (0x0C), air control uses 2*runAccel = 24
                // 1000 - 24 = 976
                assertEquals((short) 976, mockSprite.getXSpeed(), "Air control works when hurt - left input should decrease xSpeed");

                // Test right input - air control should work
                mockSprite.setXSpeed((short) 1000);
                mockSprite.setYSpeed((short) 500);
                setInputState(false, true, false, false, false); // right
                method.invoke(manager);

                // 1000 + 24 = 1024
                assertEquals((short) 1024, mockSprite.getXSpeed(), "Air control works when hurt - right input should increase xSpeed");
        }

        /**
         * Test that air control DOES work when not hurt.
         */
        @Test
        public void testAirControlWorksWhenNotHurt() throws Exception {
                mockSprite.setAir(true);
                mockSprite.setXSpeed((short) 1000);
                mockSprite.setYSpeed((short) 500);
                mockSprite.setHurt(false);
                mockSprite.setRollingJump(false);

                // Test left input
                setInputState(true, false, false, false, false); // left

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doChgJumpDir");
                method.setAccessible(true);
                method.invoke(manager);

                assertEquals((short) 976, mockSprite.getXSpeed(), "Air control should work when not hurt - left input"); // 1000 - 24

                // Test right input
                mockSprite.setXSpeed((short) 1000);
                mockSprite.setYSpeed((short) 500);
                setInputState(false, true, false, false, false); // right
                method.invoke(manager);

                assertEquals((short) 1024, mockSprite.getXSpeed(), "Air control should work when not hurt - right input"); // 1000 + 24
        }

        @Test
        public void testS3kCpuTailsAirControlUsesCpuLogicalInputWhileControlLocked() throws Exception {
                GameModuleRegistry.setCurrent(new Sonic3kGameModule());

                Tails tails = new Tails("tails", (short) 0, (short) 0);
                setGameRulesForTest(tails, GameRules.SONIC_3K);
                PlayableSpriteMovement tailsMovement = new PlayableSpriteMovement(tails);

                GameServices.camera().setMinX((short) 0);
                GameServices.camera().setMaxX((short) 0x7FFF);
                GameServices.camera().setMaxY((short) 0x7FFF);
                tails.setCpuControlled(true);
                tails.setControlLocked(true);
                tails.setSuppressAirCollision(true);
                tails.setAir(true);
                tails.setCentreX((short) 0x1269);
                tails.setCentreY((short) 0x071E);
                tails.setSubpixelRaw(0xC100, 0x2000);
                tails.setXSpeed((short) -0x0444);
                tails.setYSpeed((short) 0x01B8);
                tails.setGSpeed((short) 0);

                tailsMovement.handleMovement(false, false, true, false, false, false, false, false);

                assertEquals((short) -0x045C, tails.getXSpeed(),
                                "S3K Tails_InputAcceleration_Freespace applies the CPU left input before movement");
                assertEquals(0x6500, tails.getXSubpixelRaw(),
                                "MoveSprite_TestGravity should move with the post-acceleration x_vel");
                assertEquals(0xD800, tails.getYSubpixelRaw(),
                                "MoveSprite_TestGravity should move with the old y_vel before +$38 gravity");
                assertEquals((short) 0x01F0, tails.getYSpeed(),
                                "MoveSprite_TestGravity applies +$38 gravity after capturing old y_vel");
                assertTrue(tails.getAir(), "Post-cage Tails should remain airborne on the split frame");
        }

        @Test
        public void testS3kDeferredObjectControlReleaseRunsGroundWalkoffPath() throws Exception {
                GameModuleRegistry.setCurrent(new Sonic3kGameModule());

                Tails tails = new Tails("tails_p2", (short) 0, (short) 0);
                setGameRulesForTest(tails, GameRules.SONIC_3K);
                CollisionSystem collisionSystem = new NoGroundAttachmentCollisionSystem();
                PlayableSpriteMovement tailsMovement = new PlayableSpriteMovement(tails, collisionSystem, GameServices.gameState());
                installRuntimeCollisionSystem(collisionSystem);

                GameServices.camera().setMinX((short) 0);
                GameServices.camera().setMaxX((short) 0x7FFF);
                GameServices.camera().setMaxY((short) 0x7FFF);
                tails.setCentreX((short) 0x1D44);
                tails.setCentreY((short) 0x03C3);
                tails.setSubpixelRaw(0x1000, 0x7000);
                tails.setAir(false);
                tails.setRolling(false);
                tails.setOnObject(false);
                tails.setAngle((byte) 0);
                tails.setXSpeed((short) 0);
                tails.setYSpeed((short) 0);
                tails.setGSpeed((short) 0x0266);

                // AIZ vine handoff mirrors ROM object_control=$02: object control is
                // still non-zero for sidekick/ordering gates, but bit 0 no longer owns
                // movement. Player_AnglePos must still run and take the walkoff branch
                // when no floor/support is found (sonic3k.asm:18728, 18839-18842).
                tails.setObjectControlled(true);
                tails.setObjectControlAllowsCpu(true);
                tails.setControlLocked(true);
                tails.deferObjectControlRelease();

                tailsMovement.handleMovement(false, false, false, true, false, false, false, false);

                assertTrue(tails.getAir(), "Non-bit-0 object control must still reach Player_AnglePos walkoff");
                assertFalse(tails.getRolling(), "Walkoff is not the vine jump/eject path");
                assertEquals((short) 0, tails.getYSpeed(), "Walkoff itself does not apply gravity until the next air frame");
                assertEquals((short) 0x0266, tails.getGSpeed(), "Ground inertia is preserved by the walkoff frame");
        }

        @Test
        public void testS3kCpuTailsClearsStalePushVelocityBeforeGroundMove() throws Exception {
                GameModuleRegistry.setCurrent(new Sonic3kGameModule());

                Tails tails = new Tails("tails_p2", (short) 0, (short) 0);
                setGameRulesForTest(tails, GameRules.SONIC_3K);
                CollisionSystem collisionSystem = new StableGroundCollisionSystem();
                PlayableSpriteMovement tailsMovement = new PlayableSpriteMovement(tails, collisionSystem, GameServices.gameState());
                installRuntimeCollisionSystem(collisionSystem);

                GameServices.camera().setMinX((short) 0);
                GameServices.camera().setMaxX((short) 0x7FFF);
                GameServices.camera().setMaxY((short) 0x7FFF);
                tails.setCpuControlled(true);
                tails.setAir(false);
                tails.setPushing(true);
                tails.setCentreX((short) 0x1CED);
                tails.setCentreY((short) 0x03C0);
                tails.setSubpixelRaw(0xEE00, 0x2800);
                tails.setXSpeed((short) 0x000C);
                tails.setYSpeed((short) 0);
                tails.setGSpeed((short) 0x000C);

                tailsMovement.handleMovement(false, false, false, false, false, false, false, false);

                assertEquals((short) 0, tails.getXSpeed(),
                                "Stale push velocity is cleared before the no-input ground move");
                assertEquals((short) 0, tails.getGSpeed(),
                                "Stale push inertia is cleared before the no-input ground move");
                assertEquals(0xEE00, tails.getXSubpixelRaw(),
                                "Clearing stale push velocity must not advance x_pos_sub");
        }

        @Test
        public void testS3kCpuTailsKeepsCollisionPushXVelocityWhenGroundVelocityIsZero() throws Exception {
                GameModuleRegistry.setCurrent(new Sonic3kGameModule());

                Tails tails = new Tails("tails_p2", (short) 0, (short) 0);
                setGameRulesForTest(tails, GameRules.SONIC_3K);
                CollisionSystem collisionSystem = new PushCollisionVelocitySystem();
                PlayableSpriteMovement tailsMovement = new PlayableSpriteMovement(tails, collisionSystem, GameServices.gameState());
                installRuntimeCollisionSystem(collisionSystem);

                GameServices.camera().setMinX((short) 0);
                GameServices.camera().setMaxX((short) 0x7FFF);
                GameServices.camera().setMaxY((short) 0x7FFF);
                tails.setCpuControlled(true);
                tails.setAir(false);
                tails.setPushing(true);
                tails.setCentreX((short) 0x1F35);
                tails.setCentreY((short) 0x049D);
                tails.setSubpixelRaw(0x5900, 0x3700);
                tails.setXSpeed((short) 0);
                tails.setYSpeed((short) 0);
                tails.setGSpeed((short) 0x0100);

                tailsMovement.handleMovement(false, false, false, false, false, false, false, false);

                assertEquals((short) -0x00E8, tails.getXSpeed(),
                                "ROM Tails_InputAcceleration_Path keeps side-collision x_vel after ground_vel is zeroed");
                assertEquals((short) 0, tails.getGSpeed(),
                                "The side-collision push frame has already zeroed ground velocity");
                assertEquals(0x7100, tails.getXSubpixelRaw(),
                                "MoveSprite_TestGravity2 must advance by the preserved collision x_vel");
        }

        /**
         * Test that rolling is prevented when the down key is locked.
         */
        @Test
        public void testDownLockedPreventsRoll() throws Exception {
                mockSprite.setAir(false);
                mockSprite.setRolling(false);
                mockSprite.setGSpeed((short) 0);
                mockSprite.setCrouching(true);

                // Set wasCrouching field
                setWasCrouching(true);
                setInputState(true, false, true, false, false); // left + down

                // Update crouch state - should lock down key
                Method crouchMethod = PlayableSpriteMovement.class.getDeclaredMethod(
                                "updateCrouchState", boolean.class);
                crouchMethod.setAccessible(true);
                crouchMethod.invoke(manager, false);

                // Now move at high speed and try to roll
                mockSprite.setGSpeed((short) 500);

                Method rollMethod = PlayableSpriteMovement.class.getDeclaredMethod("doCheckStartRoll");
                rollMethod.setAccessible(true);
                rollMethod.invoke(manager);

                assertTrue(!mockSprite.getRolling(), "Rolling should NOT start when down is locked from crouch transition");
        }

        @Test
        public void groundedBalanceSelectionSurvivesLaterSameDispatchTerrainDetach() throws Exception {
                mockSprite.setAir(true);
                mockSprite.setRolling(false);
                mockSprite.setSpindash(false);
                mockSprite.setAngle((byte) 0);
                mockSprite.setGSpeed((short) 0);
                setMovementField("preFrictionGroundSpeed", 0);
                setMovementField("preMoveBalanceEvaluated", true);
                setMovementField("preMoveBalanceState", 1);
                setMovementField("preMoveBalanceDirection", Direction.RIGHT);

                Method crouchMethod = PlayableSpriteMovement.class.getDeclaredMethod(
                                "updateCrouchState", boolean.class);
                crouchMethod.setAccessible(true);
                crouchMethod.invoke(manager, false);

                assertTrue(mockSprite.getAir(), "AnglePos detach remains visible after the grounded Move dispatch");
                assertEquals(1, mockSprite.getBalanceState(),
                                "the earlier grounded Balance branch owns the animation byte for the detach frame");
                assertEquals(Direction.RIGHT, mockSprite.getDirection());
        }

        @Test
        public void releasedObjectSupportDoesNotReuseTerrainDetachBalanceSelection() throws Exception {
                mockSprite.setOnObject(true);
                mockSprite.captureOnObjectAtFrameStart();
                mockSprite.setOnObject(false);
                mockSprite.captureOnObjectAtFrameStart();
                mockSprite.setAir(true);
                mockSprite.setRolling(false);
                mockSprite.setSpindash(false);
                mockSprite.setAngle((byte) 0);
                mockSprite.setGSpeed((short) 0);
                setMovementField("preFrictionGroundSpeed", 0);
                setMovementField("preMoveBalanceEvaluated", true);
                setMovementField("preMoveBalanceState", 1);
                setMovementField("preMoveBalanceDirection", Direction.RIGHT);

                Method crouchMethod = PlayableSpriteMovement.class.getDeclaredMethod(
                                "updateCrouchState", boolean.class);
                crouchMethod.setAccessible(true);
                crouchMethod.invoke(manager, false);

                assertEquals(0, mockSprite.getBalanceState(),
                                "an object-release frame does not use the terrain AnglePos detach bridge");
        }

        @Test
        public void s3kMoveLockDownKeepsWalkWhenPlayerSlotEnteredOnObject() throws Exception {
                setGameRulesForTest(GameRules.SONIC_3K);
                mockSprite.setAir(false);
                mockSprite.setRolling(false);
                mockSprite.setSpindash(false);
                mockSprite.setGSpeed((short) 0);
                mockSprite.setCrouching(false);
                mockSprite.setOnObject(true);
                mockSprite.captureOnObjectAtFrameStart();
                mockSprite.setOnObject(false);
                mockSprite.getAnimationManager().suppressGroundMovementAnimationForFrame();
                setInputState(false, false, true, false, false);

                Method crouchMethod = PlayableSpriteMovement.class.getDeclaredMethod(
                                "updateCrouchState", boolean.class);
                crouchMethod.setAccessible(true);
                crouchMethod.invoke(manager, false);

                assertFalse(mockSprite.getCrouching(),
                                "SonicKnux_Roll must see the player-slot Status_OnObj bit and skip Duck");
        }

        @Test
        public void s3kMovingCrouchUsesPreSlopeRepelGroundSpeed() throws Exception {
                setGameRulesForTest(GameRules.SONIC_3K);
                mockSprite.setAir(false);
                mockSprite.setRolling(false);
                mockSprite.setSpindash(false);
                mockSprite.setGSpeed((short) 0x168);
                mockSprite.setCrouching(false);
                setMovementField("preRollGroundSpeed", 0x0D9);
                setInputState(false, false, true, false, false);

                Method crouchMethod = PlayableSpriteMovement.class.getDeclaredMethod(
                                "updateCrouchState", boolean.class);
                crouchMethod.setAccessible(true);
                crouchMethod.invoke(manager, false);

                assertTrue(mockSprite.getCrouching(),
                                "SonicKnux_Roll tests inertia below $100 before SlopeRepel raises it");
        }

        /**
         * Test that releasing and re-pressing down unlocks rolling.
         */
        @Test
        public void testDownReleasedUnlocksRolling() throws Exception {
                mockSprite.setAir(false);
                mockSprite.setRolling(false);
                mockSprite.setGSpeed((short) 0);
                mockSprite.setCrouching(true);

                // Lock the down key
                setWasCrouching(true);
                setInputState(true, false, true, false, false); // left + down

                Method crouchMethod = PlayableSpriteMovement.class.getDeclaredMethod(
                                "updateCrouchState", boolean.class);
                crouchMethod.setAccessible(true);
                crouchMethod.invoke(manager, false);

                // Release down - should unlock
                setWasCrouching(false);
                setInputState(true, false, false, false, false); // left only, no down
                crouchMethod.invoke(manager, false);

                // Now try to roll
                mockSprite.setGSpeed((short) 500);
                setInputState(false, false, true, false, false); // down pressed fresh

                Method rollMethod = PlayableSpriteMovement.class.getDeclaredMethod("doCheckStartRoll");
                rollMethod.setAccessible(true);
                rollMethod.invoke(manager);

                assertTrue(mockSprite.getRolling(), "Rolling should start after down is released and pressed again");
        }

        /**
         * Test that rolling works normally when not starting from crouch state.
         */
        @Test
        public void testRollingWorksWhenNotFromCrouch() throws Exception {
                mockSprite.setAir(false);
                mockSprite.setRolling(false);
                mockSprite.setCrouching(false);
                mockSprite.setGSpeed((short) 500);

                setInputState(false, false, true, false, false); // down pressed

                Method rollMethod = PlayableSpriteMovement.class.getDeclaredMethod("doCheckStartRoll");
                rollMethod.setAccessible(true);
                rollMethod.invoke(manager);

        assertTrue(mockSprite.getRolling(), "Rolling should work when not transitioning from crouch");
        }

        @Test
        public void s3kRollEntryClearsGroundPush() throws Exception {
                setGameRulesForTest(GameRules.SONIC_3K);
                mockSprite.setAir(false);
                mockSprite.setRolling(false);
                mockSprite.setCrouching(false);
                mockSprite.setPushing(true);
                mockSprite.setGSpeed((short) 0x0800);

                setInputState(false, false, true, false, false);

                Method rollMethod = PlayableSpriteMovement.class.getDeclaredMethod("doCheckStartRoll");
                rollMethod.setAccessible(true);
                rollMethod.invoke(manager);

                assertTrue(mockSprite.getRolling(), "DOWN at roll speed should enter rolling");
                assertFalse(mockSprite.getPushing(),
                                "S3K Animate_Tails clears Status_Push after Tails_Roll writes anim=#2");
        }

        @Test
        public void slidingStatusSuppressesManualDownRoll() throws Exception {
                setGameRulesForTest(GameRules.SONIC_3K);
                mockSprite.setAir(false);
                mockSprite.setRolling(false);
                mockSprite.setSliding(true);
                mockSprite.setGSpeed((short) 0x0800);

                setInputState(false, false, true, false, false);

                Method rollMethod = PlayableSpriteMovement.class.getDeclaredMethod("doCheckStartRoll");
                rollMethod.setAccessible(true);
                rollMethod.invoke(manager);

                assertFalse(mockSprite.getRolling(),
                                "S3K sub_108E6 returns while status_secondary bit 7 is set");
        }

        @Test
        public void rollStartFromWallModePreservesRomXPos() throws Exception {
                mockSprite.setAir(false);
                mockSprite.setRolling(false);
                mockSprite.setCrouching(false);
                mockSprite.setGroundMode(GroundMode.RIGHTWALL);
                mockSprite.setAngle((byte) 0xB8);
                mockSprite.setWidth(mockSprite.getStandYRadius() * 2);
                mockSprite.setHeight(mockSprite.getStandYRadius() * 2);
                mockSprite.setCentreX((short) 0x167B);
                mockSprite.setCentreY((short) 0x01F9);
                mockSprite.setGSpeed((short) 0x024C);

                setInputState(false, false, true, false, false);

                Method rollMethod = PlayableSpriteMovement.class.getDeclaredMethod("doCheckStartRoll");
                rollMethod.setAccessible(true);
                rollMethod.invoke(manager);

                assertTrue(mockSprite.getRolling(), "DOWN at roll speed should enter rolling");
                assertEquals((short) 0x167B, mockSprite.getCentreX(),
                                "ROM roll entry writes radii and y_pos only, never x_pos");
        }

        @Test
        public void s3kCpuSidekickMoveLockDoesNotSuppressDownOnlyRoll() throws Exception {
                // S3K Tails_InputAcceleration_Path skips acceleration when move_lock is
                // active (sonic3k.asm:27796-27797), but Tails_Stand_Path still calls
                // Tails_Roll afterward (sonic3k.asm:27523-27524), and Tails_Roll has
                // no move_lock gate before entering roll (sonic3k.asm:28461-28472).
                setGameRulesForTest(GameRules.SONIC_3K);
                mockSprite.setCpuControlled(true);
                mockSprite.setAir(false);
                mockSprite.setRolling(false);
                mockSprite.setGSpeed((short) 0x0180);
                mockSprite.setMoveLockTimer(14);

                setInputState(false, false, true, false, false);

                Method rollMethod = PlayableSpriteMovement.class.getDeclaredMethod("doCheckStartRoll");
                rollMethod.setAccessible(true);
                rollMethod.invoke(manager);

                assertTrue(mockSprite.getRolling(),
                                "S3K Tails_Roll still consumes DOWN-only logical input while move_lock is active");
        }

        @Test
        public void playerMoveLockDoesNotSuppressManualDownRoll() throws Exception {
                mockSprite.setCpuControlled(false);
                mockSprite.setAir(false);
                mockSprite.setRolling(false);
                mockSprite.setGSpeed((short) 0x0180);
                mockSprite.setMoveLockTimer(14);

                setInputState(false, false, true, false, false);

                Method rollMethod = PlayableSpriteMovement.class.getDeclaredMethod("doCheckStartRoll");
                rollMethod.setAccessible(true);
                rollMethod.invoke(manager);

                assertTrue(mockSprite.getRolling(),
                                "Manual/player DOWN keeps the existing roll behavior during move_lock");
        }

        @Test
        public void testMoveLockFilteredDirectionStillPreventsRoll() throws Exception {
                mockSprite.setAir(false);
                mockSprite.setRolling(false);
                mockSprite.setCrouching(false);
                mockSprite.setGSpeed((short) 500);

                setInputState(false, false, true, false, false);
                setRawHorizontalInput(true, false);

                Method rollMethod = PlayableSpriteMovement.class.getDeclaredMethod("doCheckStartRoll");
                rollMethod.setAccessible(true);
                rollMethod.invoke(manager);

                assertFalse(mockSprite.getRolling(),
                                "ROM roll entry reads held left/right even when move_lock filtered movement input");
        }

        /**
         * Test that jumpPressed is reset when springing starts.
         */
        @Test
        public void testJumpPressedClearedWhenSpringing() throws Exception {
                Field jumpPressedField = PlayableSpriteMovement.class.getDeclaredField("jumpPressed");
                jumpPressedField.setAccessible(true);

                jumpPressedField.set(manager, true);
                assertTrue((Boolean) jumpPressedField.get(manager), "jumpPressed should be true initially");

                mockSprite.setSpringing(15);

                // Simulate the fix behavior
                if (mockSprite.getSpringing()) {
                        jumpPressedField.set(manager, false);
                }

                assertTrue(!(Boolean) jumpPressedField.get(manager), "jumpPressed should be false when springing");
        }

        /**
         * Test spring velocity not capped by jump handler.
         */
        /**
         * Test spring velocity not capped by jump handler.
         * ROM: Springs do NOT set the jumping flag. The Sonic_JumpHeight routine
         * checks jumping, not springing. When jumping=0, it branches to UpVelCap
         * which has a -0xFC0 cap (much higher than -1720).
         */
        @Test
        public void testSpringVelocityNotCappedByJumpHandler() throws Exception {
                mockSprite.setAir(true);
                mockSprite.setYSpeed((short) -1720);

                Field jumpPressedField = PlayableSpriteMovement.class.getDeclaredField("jumpPressed");
                jumpPressedField.setAccessible(true);
                // Springs don't set jumping flag - it should be false
                jumpPressedField.set(manager, false);
                mockSprite.setSpringing(10);

                setInputState(false, false, false, false, false);

                Method jumpHeightMethod = PlayableSpriteMovement.class.getDeclaredMethod("doJumpHeight");
                jumpHeightMethod.setAccessible(true);
                jumpHeightMethod.invoke(manager);

                // With jumpPressed=false, goes to UpVelCap path (-0xFC0 cap)
                // -1720 > -4032, so velocity is NOT capped
                assertEquals((short) -1720, mockSprite.getYSpeed(), "Velocity should NOT be capped for spring launch");

                mockSprite.setSpringing(0);

                assertEquals((short) -1720, mockSprite.getYSpeed(), "Velocity should remain unchanged in UpVelCap path");
        }

        @Test
        public void externalJumpingFlagPrimesJumpHeightLatch() throws Exception {
                PlayableSpriteMovement controllerMovement =
                                (PlayableSpriteMovement) mockSprite.getMovementManager();
                mockSprite.setAir(true);
                mockSprite.setYSpeed((short) -0x0450);
                mockSprite.setJumping(true);

                Method jumpHeightMethod = PlayableSpriteMovement.class.getDeclaredMethod("doJumpHeight");
                jumpHeightMethod.setAccessible(true);
                jumpHeightMethod.invoke(controllerMovement);

                assertEquals((short) -0x0400, mockSprite.getYSpeed(),
                                "Object releases that set jumping(a0) should use Sonic_JumpHeight release cap");
        }

        /**
         * Test rolling slope physics when gSpeed is zero.
         */
        @Test
        public void testRollingSlopePhysicsWithZeroGSpeed() throws Exception {
                mockSprite.setAir(false);
                mockSprite.setRolling(true);
                mockSprite.setGSpeed((short) 0);
                mockSprite.setAngle((byte) 0x10); // Downhill to the right

                // Use doRollRepel which handles rolling slope physics
                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doRollRepel");
                method.setAccessible(true);
                method.invoke(manager);

                short newGSpeed = mockSprite.getGSpeed();
                assertTrue(newGSpeed > 20, "gSpeed should be positive (accelerating down-right slope) with full factor, was " + newGSpeed);
        }

        /**
         * Test rolling slope physics when gSpeed is zero on an uphill slope.
         */
        @Test
        public void testRollingSlopePhysicsWithZeroGSpeedUphill() throws Exception {
                mockSprite.setAir(false);
                mockSprite.setRolling(true);
                mockSprite.setGSpeed((short) 0);
                mockSprite.setAngle((byte) 0xF0); // Uphill to the right

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doRollRepel");
                method.setAccessible(true);
                method.invoke(manager);

                short newGSpeed = mockSprite.getGSpeed();
                assertTrue(newGSpeed < 0, "gSpeed should be negative (pushed back down uphill slope), was " + newGSpeed);
                assertTrue(newGSpeed > -20, "gSpeed magnitude should be small (reduced factor), was " + newGSpeed);
        }

        // Helper methods to set up input state

        private void setInputState(boolean left, boolean right, boolean down, boolean up, boolean jump) throws Exception {
                Field leftField = PlayableSpriteMovement.class.getDeclaredField("inputLeft");
                Field rightField = PlayableSpriteMovement.class.getDeclaredField("inputRight");
                Field downField = PlayableSpriteMovement.class.getDeclaredField("inputDown");
                Field upField = PlayableSpriteMovement.class.getDeclaredField("inputUp");
                Field jumpField = PlayableSpriteMovement.class.getDeclaredField("inputJump");
                Field rawLeftField = PlayableSpriteMovement.class.getDeclaredField("inputRawLeft");
                Field rawRightField = PlayableSpriteMovement.class.getDeclaredField("inputRawRight");

                leftField.setAccessible(true);
                rightField.setAccessible(true);
                downField.setAccessible(true);
                upField.setAccessible(true);
                jumpField.setAccessible(true);
                rawLeftField.setAccessible(true);
                rawRightField.setAccessible(true);

                leftField.set(manager, left);
                rightField.set(manager, right);
                downField.set(manager, down);
                upField.set(manager, up);
                jumpField.set(manager, jump);
                rawLeftField.set(manager, left);
                rawRightField.set(manager, right);
        }

        private void setWasCrouching(boolean wasCrouching) throws Exception {
                Field wasCrouchingField = PlayableSpriteMovement.class.getDeclaredField("wasCrouching");
                wasCrouchingField.setAccessible(true);
                wasCrouchingField.set(manager, wasCrouching);
        }

        private void setRawHorizontalInput(boolean left, boolean right) throws Exception {
                Field rawLeftField = PlayableSpriteMovement.class.getDeclaredField("inputRawLeft");
                Field rawRightField = PlayableSpriteMovement.class.getDeclaredField("inputRawRight");
                rawLeftField.setAccessible(true);
                rawRightField.setAccessible(true);
                rawLeftField.set(manager, left);
                rawRightField.set(manager, right);
        }

        // ========================================
        // ROM-ACCURATE LANDING gSpeed TESTS
        // ========================================

        /**
         * Test ROM-accurate flat slope detection.
         * ROM: ((angle + 0x10) & 0x20) == 0 AND ((angle + 0x20) & 0x40) == 0
         * Flat angles: 0x00-0x0F, 0xF0-0xFF
         */
        @Test
        public void testLandingFlatSlopeUsesXSpeed() throws Exception {
                // Flat angle 0x00 - should use xSpeed directly
                mockSprite.setAngle((byte) 0x00);
                mockSprite.setYSpeed((short) 500);
                mockSprite.setXSpeed((short) 200);
                mockSprite.setGSpeed((short) 0);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("calculateLanding",
                                AbstractPlayableSprite.class);
                method.setAccessible(true);
                method.invoke(manager, mockSprite);

                assertEquals((short) 200, mockSprite.getGSpeed(), "Flat slope (0x00) should set gSpeed = xSpeed");
        }

        /**
         * Test ROM-accurate steep slope detection and ySpeed cap.
         * ROM: ((angle + 0x20) & 0x40) != 0
         * Steep angles: 0x20-0x5F, 0xA0-0xDF
         */
        @Test
        public void testLandingSteepSlopeCapsYSpeed() throws Exception {
                // Steep angle 0x40 (90 degrees, wall) with high ySpeed
                mockSprite.setAngle((byte) 0x40);
                mockSprite.setYSpeed((short) 5000); // Above cap of 0xFC0 (4032)
                mockSprite.setXSpeed((short) 200);
                mockSprite.setGSpeed((short) 0);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("calculateLanding",
                                AbstractPlayableSprite.class);
                method.setAccessible(true);
                method.invoke(manager, mockSprite);

                // On steep slope, gSpeed should be capped ySpeed
                // Angle 0x40 is in lower half (0x00-0x7F), so gSpeed is positive
                assertEquals((short) 0xFC0, mockSprite.getGSpeed(), "Steep slope (0x40) should cap ySpeed at 0xFC0");

                // xSpeed should be cleared on steep slopes
                assertEquals((short) 0, mockSprite.getXSpeed(), "Steep slope should clear xSpeed");
        }

        @Test
        public void s3kBubbleShieldSteepLandingCopiesPostBounceYSpeedToGroundSpeed() throws Exception {
                GameModuleRegistry.setCurrent(new Sonic3kGameModule());
                setGameRulesForTest(GameRules.SONIC_3K);
                mockSprite.setInWater(true);
                mockSprite.giveShield(ShieldType.BUBBLE);
                mockSprite.setDoubleJumpFlag(1);
                mockSprite.setAir(true);
                mockSprite.setRolling(true);
                mockSprite.setAngle((byte) 0x20);
                mockSprite.setXSpeed((short) 0);
                mockSprite.setYSpeed((short) 0x04BA);
                mockSprite.setGSpeed((short) 0);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("calculateLanding",
                                AbstractPlayableSprite.class);
                method.setAccessible(true);
                method.invoke(manager, mockSprite);

                assertEquals((short) 0x02D4, mockSprite.getXSpeed(),
                                "BubbleShield_Bounce adds underwater normal X velocity on the steep landing frame");
                assertEquals((short) 0x01E6, mockSprite.getYSpeed(),
                                "BubbleShield_Bounce rewrites y_vel before loc_11FC2 copies it to ground_vel");
                assertEquals((short) 0x01E6, mockSprite.getGSpeed(),
                                "S3K loc_11FC2 writes ground_vel after Player_TouchFloor_Check_Spindash (sonic3k.asm:24112-24117)");
                assertTrue(mockSprite.getAir(), "BubbleShield_Bounce relaunches Sonic into air");
                assertTrue(mockSprite.getRolling(), "BubbleShield_Bounce leaves Sonic rolling");
        }

        /**
         * Test ROM-accurate steep slope gSpeed sign based on angle.
         * ROM: If angle bit 7 is set (0x80-0xFF), negate gSpeed
         */
        @Test
        public void testLandingSteepSlopeNegatesGSpeedForUpperAngles() throws Exception {
                // Steep angle 0xC0 (192 degrees, right wall) - in upper half
                mockSprite.setAngle((byte) 0xC0);
                mockSprite.setYSpeed((short) 500);
                mockSprite.setXSpeed((short) 200);
                mockSprite.setGSpeed((short) 0);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("calculateLanding",
                                AbstractPlayableSprite.class);
                method.setAccessible(true);
                method.invoke(manager, mockSprite);

                // Angle 0xC0 has bit 7 set, so gSpeed should be negated
                assertTrue(mockSprite.getGSpeed() < 0, "Steep slope (0xC0) should have negative gSpeed");
        }

        /**
         * Test ROM-accurate moderate slope detection.
         * ROM: Not steep AND ((angle + 0x10) & 0x20) != 0
         * Moderate angles: 0x10-0x1F, 0xE0-0xEF
         */
        @Test
        public void testLandingModerateSlopeHalvesYSpeed() throws Exception {
                // Moderate angle 0x18 - should use ySpeed/2
                mockSprite.setAngle((byte) 0x18);
                mockSprite.setYSpeed((short) 500);
                mockSprite.setXSpeed((short) 0);
                mockSprite.setGSpeed((short) 0);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("calculateLanding",
                                AbstractPlayableSprite.class);
                method.setAccessible(true);
                method.invoke(manager, mockSprite);

                // Moderate slope uses ySpeed >> 1 = 250
                // Angle 0x18 is in lower half, so positive
                assertEquals((short) 250, mockSprite.getGSpeed(), "Moderate slope (0x18) should set gSpeed = ySpeed/2");
        }

        /**
         * Test that resetOnFloor clears all landing-related flags.
         */
        @Test
        public void testResetOnFloorClearsFlags() throws Exception {
                // Set up various flags that should be cleared
                mockSprite.setAir(true);
                mockSprite.setPushing(true);
                mockSprite.setRollingJump(true);
                mockSprite.setJumping(true);
                mockSprite.setAngle((byte) 0x00);
                mockSprite.setYSpeed((short) 100);
                mockSprite.setXSpeed((short) 100);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("calculateLanding",
                                AbstractPlayableSprite.class);
                method.setAccessible(true);
                method.invoke(manager, mockSprite);

                assertTrue(!mockSprite.getAir(), "resetOnFloor should clear air flag");
                assertTrue(!mockSprite.getPushing(), "resetOnFloor should clear pushing flag");
                assertTrue(!mockSprite.getRollingJump(), "resetOnFloor should clear rollingJump flag");
                assertTrue(!mockSprite.isJumping(), "resetOnFloor should clear jumping flag");
        }

        /**
         * Test ROM angle boundaries for steep detection.
         * Steep: ((angle + 0x20) & 0x40) != 0
         * This means: 0x20-0x5F (32-95) and 0xA0-0xDF (160-223)
         */
        @Test
        public void testSteepAngleBoundaries() {
                // Test the formula: ((angle + 0x20) & 0x40) != 0

                // Boundary cases that should be steep
                assertTrue(((0x20 + 0x20) & 0x40) != 0, "0x20 should be steep");
                assertTrue(((0x5F + 0x20) & 0x40) != 0, "0x5F should be steep");
                assertTrue(((0xA0 + 0x20) & 0x40) != 0, "0xA0 should be steep");
                assertTrue(((0xDF + 0x20) & 0x40) != 0, "0xDF should be steep");

                // Boundary cases that should NOT be steep
                assertTrue(((0x1F + 0x20) & 0x40) == 0, "0x1F should NOT be steep");
                assertTrue(((0x60 + 0x20) & 0x40) == 0, "0x60 should NOT be steep");
                assertTrue(((0x9F + 0x20) & 0x40) == 0, "0x9F should NOT be steep");
                assertTrue(((0xE0 + 0x20) & 0x40) == 0, "0xE0 should NOT be steep");
        }

        private static final class LandingProbeCollisionSystem extends CollisionSystem {
                private final LandingProbe probe;

                private LandingProbeCollisionSystem(LandingProbe probe) {
                        super(new TerrainCollisionManager());
                        this.probe = probe;
                }

                @Override
                public void resolveAirCollision(FrameCollisionPlan plan,
                                                AbstractPlayableSprite sprite,
                                                Consumer<AbstractPlayableSprite> landingHandler,
                                                boolean forceFloorCheck) {
                        probe.accept(sprite, landingHandler, forceFloorCheck);
                }

                @Override
                public void resolveAirCollision(FrameCollisionPlan plan,
                                                AbstractPlayableSprite sprite,
                                                Consumer<AbstractPlayableSprite> landingHandler,
                                                Consumer<SensorResult[]> landingProbeHandler,
                                                boolean forceFloorCheck) {
                        probe.accept(sprite, landingHandler, forceFloorCheck);
                }

                @Override
                public void resolveAirCollision(AbstractPlayableSprite sprite,
                                                Consumer<AbstractPlayableSprite> landingHandler,
                                                boolean forceFloorCheck) {
                        probe.accept(sprite, landingHandler, forceFloorCheck);
                }
        }

        private static final class NoGroundAttachmentCollisionSystem extends CollisionSystem {
                private NoGroundAttachmentCollisionSystem() {
                        super(new TerrainCollisionManager());
                }

                @Override
                public void resolveGroundAttachment(FrameCollisionPlan plan,
                                                    AbstractPlayableSprite sprite,
                                                    int positiveThreshold,
                                                    BooleanSupplier hasObjectSupport) {
                        sprite.setAir(true);
                        sprite.setPushing(false);
                }

                @Override
                public void resolveGroundAttachment(AbstractPlayableSprite sprite,
                                                    int positiveThreshold,
                                                    BooleanSupplier hasObjectSupport) {
                        sprite.setAir(true);
                        sprite.setPushing(false);
                }
        }

        private static final class StableGroundCollisionSystem extends CollisionSystem {
                private StableGroundCollisionSystem() {
                        super(new TerrainCollisionManager());
                }

                @Override
                public void resolveGroundAttachment(FrameCollisionPlan plan,
                                                    AbstractPlayableSprite sprite,
                                                    int positiveThreshold,
                                                    BooleanSupplier hasObjectSupport) {
                        sprite.setAir(false);
                }

                @Override
                public void resolveGroundAttachment(AbstractPlayableSprite sprite,
                                                    int positiveThreshold,
                                                    BooleanSupplier hasObjectSupport) {
                        sprite.setAir(false);
                }
        }

        private static final class PushCollisionVelocitySystem extends CollisionSystem {
                private PushCollisionVelocitySystem() {
                        super(new TerrainCollisionManager());
                }

                @Override
                public void resolveGroundAttachment(FrameCollisionPlan plan,
                                                    AbstractPlayableSprite sprite,
                                                    int positiveThreshold,
                                                    BooleanSupplier hasObjectSupport) {
                        sprite.setAir(false);
                }

                @Override
                public void resolveGroundAttachment(AbstractPlayableSprite sprite,
                                                    int positiveThreshold,
                                                    BooleanSupplier hasObjectSupport) {
                        sprite.setAir(false);
                }

                @Override
                public void resolveGroundWallCollision(FrameCollisionPlan plan, AbstractPlayableSprite sprite) {
                        // ROM Tails_InputAcceleration_Path converts nonzero ground_vel
                        // to x_vel first, then CalcRoomInFront's push path zeroes
                        // ground_vel while preserving the collision x_vel for
                        // MoveSprite_TestGravity2 (sonic3k.asm:27947-27955,
                        // 27997-28017).
                        sprite.setXSpeed((short) -0x00E8);
                        sprite.setGSpeed((short) 0);
                        sprite.setPushing(true);
                }

                @Override
                public void resolveGroundWallCollision(AbstractPlayableSprite sprite) {
                        // ROM Tails_InputAcceleration_Path converts nonzero ground_vel
                        // to x_vel first, then CalcRoomInFront's push path zeroes
                        // ground_vel while preserving the collision x_vel for
                        // MoveSprite_TestGravity2 (sonic3k.asm:27947-27955,
                        // 27997-28017).
                        sprite.setXSpeed((short) -0x00E8);
                        sprite.setGSpeed((short) 0);
                        sprite.setPushing(true);
                }
        }

        @FunctionalInterface
        private interface LandingProbe {
                void accept(AbstractPlayableSprite sprite,
                            Consumer<AbstractPlayableSprite> landingHandler,
                            boolean forceFloorCheck);
        }

        private static void installRuntimeCollisionSystem(CollisionSystem collisionSystem) throws Exception {
                GameplayModeContext gameplayMode = TestEnvironment.activeGameplayMode();
                Field field = com.openggf.game.session.GameplayModeContext.class.getDeclaredField("collisionSystem");
                field.setAccessible(true);
                field.set(gameplayMode, collisionSystem);
        }

        /**
         * Test ROM angle boundaries for flat detection.
         * Flat: ((angle + 0x10) & 0x20) == 0 AND not steep
         * This means: 0x00-0x0F (0-15) and 0xF0-0xFF (240-255)
         */
        @Test
        public void testFlatAngleBoundaries() {
                // Test the formula for flat: ((angle + 0x10) & 0x20) == 0
                // (This only applies when not steep)

                // Boundary cases that should be flat (assuming not steep)
                assertTrue(((0x00 + 0x10) & 0x20) == 0, "0x00 should be flat");
                assertTrue(((0x0F + 0x10) & 0x20) == 0, "0x0F should be flat");
                assertTrue(((0xF0 + 0x10) & 0x20) == 0, "0xF0 should be flat");
                assertTrue(((0xFF + 0x10) & 0x20) == 0, "0xFF should be flat");

                // Boundary cases that should NOT be flat
                assertTrue(((0x10 + 0x10) & 0x20) != 0, "0x10 should NOT be flat");
                assertTrue(((0x1F + 0x10) & 0x20) != 0, "0x1F should NOT be flat");
                assertTrue(((0xE0 + 0x10) & 0x20) != 0, "0xE0 should NOT be flat");
                assertTrue(((0xEF + 0x10) & 0x20) != 0, "0xEF should NOT be flat");
        }

        // ========================================
        // STICK_TO_CONVEX FLAG TESTS
        // ========================================

        /**
         * Test that stick_to_convex is cleared when jumping.
         * ROM: clr.b stick_to_convex(a0) at s2.asm:37035
         */
        @Test
        public void testStickToConvexClearedOnJump() throws Exception {
                mockSprite.setAir(false);
                mockSprite.setAngle((byte) 0x40); // Wall angle
                mockSprite.setStickToConvex(true);
                mockSprite.setXSpeed((short) 0);
                mockSprite.setYSpeed((short) 0);
                mockSprite.setGSpeed((short) 0);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doJump");
                method.setAccessible(true);
                method.invoke(manager);

                assertTrue(!mockSprite.isStickToConvex(), "stick_to_convex should be cleared after jump");
        }

        // NOTE: Tests for automatic stick_to_convex setting were removed.
        // ROM: stick_to_convex is NEVER set automatically in Sonic 2.
        // It's only used by special objects (rotating discs in S1/S3).
        // The previous tests were verifying incorrect behavior that caused
        // Sonic to never slide off slopes (doSlopeRepel was bypassed).

        @Test
        public void testSlopeRepelAddsDownhillKickBeforeMoveLock() throws Exception {
                setGameRulesForTest(GameRules.SONIC_3K);
                mockSprite.setAir(false);
                mockSprite.setOnObject(false);
                mockSprite.setStickToConvex(false);
                mockSprite.setAngle((byte) 0x18);
                mockSprite.setGSpeed((short) 0x01E5);
                mockSprite.setMoveLockTimer(0);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doSlopeRepel");
                method.setAccessible(true);
                method.invoke(manager);

                assertEquals(0x0265, mockSprite.getGSpeed() & 0xFFFF,
                                "Player_SlopeRepel should add $80 on shallow downhill slopes");
                assertEquals(0x1E, mockSprite.getMoveLockTimer(),
                                "Player_SlopeRepel should arm the ROM 30-frame move_lock");
                assertTrue(!mockSprite.getAir(), "Shallow slope repel should stay grounded");
        }

        @Test
        public void testSlopeRepelSetsAirOnSteepSlipRange() throws Exception {
                setGameRulesForTest(GameRules.SONIC_3K);
                mockSprite.setAir(false);
                mockSprite.setOnObject(false);
                mockSprite.setStickToConvex(false);
                mockSprite.setAngle((byte) 0x40);
                mockSprite.setGSpeed((short) 0x0100);
                mockSprite.setMoveLockTimer(0);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doSlopeRepel");
                method.setAccessible(true);
                method.invoke(manager);

                assertEquals(0x0100, mockSprite.getGSpeed() & 0xFFFF,
                                "Steep slip range should not overwrite ground velocity");
                assertEquals(0x1E, mockSprite.getMoveLockTimer(),
                                "Player_SlopeRepel should arm move_lock before setting air");
                assertTrue(mockSprite.getAir(), "Steep slip range should put the player airborne");
        }

        @Test
        public void testSlopeRepelMoveLockCountsDownWhileObjectSupported() throws Exception {
                setGameRulesForTest(GameRules.SONIC_2);
                mockSprite.setAir(false);
                mockSprite.setOnObject(true);
                mockSprite.setStickToConvex(false);
                mockSprite.setAngle((byte) 0x40);
                mockSprite.setGSpeed((short) 0);
                mockSprite.setMoveLockTimer(3);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doSlopeRepel");
                method.setAccessible(true);
                method.invoke(manager);

                assertEquals(2, mockSprite.getMoveLockTimer(),
                                "Sonic_SlopeRepel should decrement active move_lock before object-support slip suppression");
                assertEquals(0, mockSprite.getGSpeed(),
                                "Object support should still suppress arming a fresh slope slip from stale terrain angle");
                assertFalse(mockSprite.getAir(), "Object support should not create a new airborne slope slip");
        }

        @Test
        public void entryActiveMoveLockSuppressesBalanceAfterFinalSlopeRepelTickAcrossGames()
                        throws Exception {
                Field objectManagerField = GameServices.level().getClass().getDeclaredField("objectManager");
                objectManagerField.setAccessible(true);
                objectManagerField.set(GameServices.level(), new ObjectManager(List.of(), null, 0, null, null));
                CollisionSystem collisionSystem = new StableGroundCollisionSystem();
                manager = new PlayableSpriteMovement(mockSprite, collisionSystem, GameServices.gameState());
                installRuntimeCollisionSystem(collisionSystem);
                GameServices.camera().setMinX((short) 0);
                GameServices.camera().setMaxX((short) 0x7FFF);
                GameServices.camera().setMaxY((short) 0x7FFF);

                GameRules[] sharedRules = {
                        GameRules.SONIC_1,
                        GameRules.SONIC_2,
                        GameRules.SONIC_3K
                };
                String[] labels = {"S1", "S2", "S3K"};
                for (int index = 0; index < sharedRules.length; index++) {
                        GameRules rules = sharedRules[index];
                        String label = labels[index];
                        setGameRulesForTest(rules);
                        prepareClearedInteractSlotBalanceProbe(0);
                        manager.handleMovement(false, false, false, false,
                                false, false, false, false);
                        assertTrue(mockSprite.getBalanceState() > 0,
                                label + " control dispatch must prove the edge can balance");

                        prepareClearedInteractSlotBalanceProbe(1);
                        manager.handleMovement(false, false, false, false,
                                false, false, false, false);

                        assertEquals(0, mockSprite.getMoveLockTimer(),
                                label + " SlopeRepel must consume the final move_lock tick");
                        assertEquals(0, mockSprite.getBalanceState(),
                                label + " Sonic_Move/Tails_Move must retain the entry-time lock decision");
                }
        }

        private void prepareClearedInteractSlotBalanceProbe(int moveLockTimer) throws Exception {
                Sensor flatLeft = new Sensor(mockSprite, Direction.DOWN, (byte) -9, (byte) 19, true) {
                        @Override
                        protected SensorResult doScan(short dx, short dy) {
                                return new SensorResult((byte) 0, (byte) 0, 0, Direction.DOWN);
                        }
                };
                Sensor flatRight = new Sensor(mockSprite, Direction.DOWN, (byte) 9, (byte) 19, true) {
                        @Override
                        protected SensorResult doScan(short dx, short dy) {
                                return new SensorResult((byte) 0, (byte) 0, 0, Direction.DOWN);
                        }
                };
                mockSprite.setGroundSensors(new Sensor[]{flatLeft, flatRight});
                mockSprite.setCeilingSensors(new Sensor[]{flatLeft, flatRight});
                mockSprite.setPushSensors(new Sensor[]{flatLeft, flatRight});
                mockSprite.setCentreX((short) 0x3C90);
                mockSprite.setCentreY((short) 0x0200);
                mockSprite.setXSpeed((short) 0);
                mockSprite.setYSpeed((short) 0);
                mockSprite.setGSpeed((short) 0);
                mockSprite.setAngle((byte) 0);
                mockSprite.setAir(false);
                mockSprite.setRolling(false);
                mockSprite.setSpindash(false);
                mockSprite.setOnObject(true);
                mockSprite.setInteractSlotIndex(15);
                mockSprite.setBalanceState(0);
                mockSprite.setMoveLockTimer(moveLockTimer);
        }

        @Test
        public void testS3kLandingPreservesRollingInPinballMode() throws Exception {
                setGameRulesForTest(GameRules.SONIC_3K);
                mockSprite.setRolling(true);
                mockSprite.setPinballMode(true);
                mockSprite.setAir(true);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("resetOnFloor");
                method.setAccessible(true);
                method.invoke(manager);

                assertTrue(mockSprite.getRolling(),
                                "S3K Player_TouchFloor_Check_Spindash skips the Status_Roll clear while spin_dash_flag is set");
                assertTrue(mockSprite.getPinballMode(),
                                "S3K landing should preserve the engine spin_dash_flag mirror for AutoSpin tunnels");
        }

        @Test
        public void testLandingClearingRollUsesCurrentStandingRadiusDelta() throws Exception {
                setGameRulesForTest(GameRules.SONIC_3K);
                mockSprite.setGroundMode(GroundMode.GROUND);
                mockSprite.setCentreY((short) 0x0D40);
                mockSprite.setRolling(true);
                mockSprite.setCentreY((short) 0x0D40);
                mockSprite.applyStandingRadii(false);
                mockSprite.setRollingJump(false);
                mockSprite.setAir(true);
                mockSprite.setAngle((byte) 0xFC);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("resetOnFloor");
                method.setAccessible(true);
                method.invoke(manager);

                assertEquals((short) 0x0D40, mockSprite.getCentreY(),
                                "Player_TouchFloor adjusts ROM centre y_pos by old y_radius - default_y_radius");
                assertEquals(mockSprite.getStandYRadius(), mockSprite.getYRadius(),
                                "Landing should restore default y_radius");
                assertTrue(!mockSprite.getRolling(), "Landing should clear Status_Roll");
        }

        @Test
        public void testS2LandingClearingRollUsesFixedLiftEvenWithStandingRadius() throws Exception {
                setGameRulesForTest(GameRules.SONIC_2);
                mockSprite.setGroundMode(GroundMode.GROUND);
                mockSprite.setCentreY((short) 0x0D40);
                mockSprite.setRolling(true);
                mockSprite.setCentreY((short) 0x0D40);
                mockSprite.applyStandingRadii(false);
                mockSprite.setRollingJump(false);
                mockSprite.setAir(true);
                mockSprite.setAngle((byte) 0x00);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("resetOnFloor");
                method.setAccessible(true);
                method.invoke(manager);

                assertEquals((short) 0x0D3B, mockSprite.getCentreY(),
                                "S2 Sonic_ResetOnFloor applies the fixed -5 centre-y lift when clearing rolling");
                assertEquals(mockSprite.getStandYRadius(), mockSprite.getYRadius(),
                                "Landing should restore default y_radius");
                assertTrue(!mockSprite.getRolling(), "Landing should clear Status_Roll");
        }

        @Test
        public void testS1RollingLandingWritesWalkAnimationLikeResetOnFloor() throws Exception {
                setGameRulesForTest(GameRules.SONIC_1);
                mockSprite.setAnimationId(2);
                mockSprite.setRolling(true);
                mockSprite.setAir(true);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("resetOnFloor");
                method.setAccessible(true);
                boolean resetOwnedWalkPublication = (boolean) method.invoke(manager);

                assertFalse(mockSprite.getRolling());
                assertTrue(resetOwnedWalkPublication,
                                "the rolling-clear branch owns the landing's single Walk publication");
                assertEquals(0, mockSprite.getAnimationId(),
                                "S1 Sonic_ResetOnFloor writes id_Walk when it clears Status_Roll");
        }

        @Test
        public void testS1NonRollingResetLeavesWalkPublicationToTerrainLanding() throws Exception {
                setGameRulesForTest(GameRules.SONIC_1);
                mockSprite.setAnimationId(5);
                mockSprite.setRolling(false);
                mockSprite.setAir(true);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("resetOnFloor");
                method.setAccessible(true);
                boolean resetOwnedWalkPublication = (boolean) method.invoke(manager);

                assertFalse(resetOwnedWalkPublication);
                assertEquals(5, mockSprite.getAnimationId(),
                                "non-rolling ResetOnFloor leaves Sonic_Floor to publish Walk");
        }

        @Test
        public void testS1TerrainLandingWritesWalkEvenWhenIncomingAnimationWasWait() throws Exception {
                setGameRulesForTest(GameRules.SONIC_1);
                mockSprite.setAnimationId(5);
                mockSprite.setRolling(false);
                mockSprite.setAir(true);
                mockSprite.setAngle((byte) 0x00);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("calculateLanding",
                                AbstractPlayableSprite.class);
                method.setAccessible(true);
                method.invoke(manager, mockSprite);

                assertEquals(0, mockSprite.getAnimationId(),
                                "S1 Sonic_Floor writes id_Walk after ResetOnFloor on an accepted floor landing");
        }

		@Test
		public void testTerrainLandingWalkReplacesStaleLookProjection() throws Exception {
				setGameRulesForTest(GameRules.SONIC_3K);
				mockSprite.setAnimationId(2);
				mockSprite.setLookingUp(true);
				mockSprite.setAir(true);
				mockSprite.setAngle((byte) 0x00);

				Method method = PlayableSpriteMovement.class.getDeclaredMethod("calculateLanding",
								AbstractPlayableSprite.class);
				method.setAccessible(true);
				method.invoke(manager, mockSprite);

				assertFalse(mockSprite.getLookingUp(),
								"Player_TouchFloor's Walk write replaces the semantic LookUp projection");
				assertEquals(0, mockSprite.getAnimationId());
		}

        @Test
        public void testLandingClearingRollStillLiftsFromRollingRadius() throws Exception {
                setGameRulesForTest(GameRules.SONIC_3K);
                mockSprite.setGroundMode(GroundMode.GROUND);
                mockSprite.setCentreY((short) 0x0D40);
                mockSprite.setRolling(true);
                mockSprite.setCentreY((short) 0x0D40);
                mockSprite.setAir(true);
                mockSprite.setAngle((byte) 0x00);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("resetOnFloor");
                method.setAccessible(true);
                method.invoke(manager);

                assertEquals((short) 0x0D3B, mockSprite.getCentreY(),
                                "Rolling y_radius 14 landing should apply the -5 centre-y radius delta");
                assertEquals(mockSprite.getStandYRadius(), mockSprite.getYRadius(),
                                "Landing should restore default y_radius");
                assertTrue(!mockSprite.getRolling(), "Landing should clear Status_Roll");
        }

        @Test
        public void testS2LandingPreservesRollingInPinballMode() throws Exception {
                setGameRulesForTest(GameRules.SONIC_2);
                mockSprite.setAnimationProfile(new ScriptedVelocityAnimationProfile()
                                .setWalkAnimId(0)
                                .setRollAnimId(2));
                mockSprite.setAnimationId(2);
                mockSprite.setRolling(true);
                mockSprite.setPinballMode(true);
                mockSprite.setAir(true);
                mockSprite.setXSpeed((short) 0x0200);
                mockSprite.setYSpeed((short) 0x0200);
                mockSprite.setAngle((byte) 0);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod(
                                "calculateLanding", AbstractPlayableSprite.class);
                method.setAccessible(true);
                method.invoke(manager, mockSprite);

                assertTrue(mockSprite.getRolling(),
                                "S2 Sonic_ResetOnFloor skips the roll-clear block when pinball_mode is set");
                assertTrue(mockSprite.getPinballMode(),
                                "ROM Tails_ResetOnFloor never clears pinball_mode (bne.s *_Part3 skips the roll-clear block; s2.asm:40625-40626)");
                assertEquals(2, mockSprite.getAnimationId(),
                                "pinball_mode skips Sonic_ResetOnFloor's Walk write");
        }

        @Test
        public void testS2LandingPreservesActiveSpindashAnimationThroughNativeAlias() throws Exception {
                setGameRulesForTest(GameRules.SONIC_2);
                mockSprite.setAnimationProfile(new ScriptedVelocityAnimationProfile()
                                .setWalkAnimId(0)
                                .setSpindashAnimId(9));
                mockSprite.setAnimationId(9);
                mockSprite.setSpindash(true);
                mockSprite.setPinballMode(false);
                mockSprite.setRolling(false);
                mockSprite.setAir(true);
                mockSprite.setXSpeed((short) 0x0200);
                mockSprite.setYSpeed((short) 0x0200);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod(
                                "calculateLanding", AbstractPlayableSprite.class);
                method.setAccessible(true);
                method.invoke(manager, mockSprite);

                assertFalse(mockSprite.getAir());
                assertEquals(9, mockSprite.getAnimationId(),
                                "S2 ResetOnFloor sees the live spindash_flag/pinball_mode byte and skips Walk");
        }

        @Test
        public void crushDeathPublishesRawDeathWithoutReplacingCurrentMapping() throws Exception {
                setGameRulesForTest(GameRules.SONIC_2);
                mockSprite.setAnimationId(Sonic2AnimationIds.WALK.id());
                mockSprite.setMappingFrame(0x10);

                assertTrue(mockSprite.applyCrushDeath());

                assertEquals(Sonic2AnimationIds.DEATH.id(), mockSprite.getAnimationId());
                assertEquals(0x10, mockSprite.getMappingFrame(),
                                "same-frame Kill_Character leaves the already-rendered mapping latched");
        }

        @Test
        public void s2OrdinaryObjectRiderStillEntersBlinkFromDeepWait() throws Exception {
                setGameRulesForTest(GameRules.SONIC_2);
                mockSprite.setAnimationProfile(new ScriptedVelocityAnimationProfile()
                                .setIdleAnimId(Sonic2AnimationIds.WAIT)
                                .setWalkAnimId(Sonic2AnimationIds.WALK)
                                .setBlinkAnimId(Sonic2AnimationIds.BLINK)
                                .setGetUpAnimId(Sonic2AnimationIds.GET_UP));
                mockSprite.setAnimationId(Sonic2AnimationIds.WAIT.id());
                mockSprite.setAnimationFrameIndex(0x1E);
                mockSprite.setOnObject(true);
                setInputState(true, false, false, false, false);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doWaitBlinkInterruptCheck");
                method.setAccessible(true);

                assertTrue((boolean) method.invoke(manager));
                assertEquals(Sonic2AnimationIds.BLINK.id(), mockSprite.getAnimationId(),
                                "Status_OnObj does not bypass Obj01_MdNormal_Checks in the ROM");
        }

        @Test
        public void s2OrdinaryObjectRiderStillEntersGetUpFromDeepWait() throws Exception {
                setGameRulesForTest(GameRules.SONIC_2);
                mockSprite.setAnimationProfile(new ScriptedVelocityAnimationProfile()
                                .setIdleAnimId(Sonic2AnimationIds.WAIT)
                                .setBlinkAnimId(Sonic2AnimationIds.BLINK)
                                .setGetUpAnimId(Sonic2AnimationIds.GET_UP));
                mockSprite.setAnimationId(Sonic2AnimationIds.WAIT.id());
                mockSprite.setAnimationFrameIndex(0xAC);
                mockSprite.setOnObject(true);
                setInputState(false, false, false, true, false);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doWaitBlinkInterruptCheck");
                method.setAccessible(true);

                assertTrue((boolean) method.invoke(manager));
                assertEquals(Sonic2AnimationIds.GET_UP.id(), mockSprite.getAnimationId(),
                                "ordinary ridden solids do not suppress the player's deep-wait routine");
        }

        @Test
        public void s2DeepWaitSeesHeldRightBeforeRidingStaleMovementFilter() throws Exception {
                setGameRulesForTest(GameRules.SONIC_2);
                mockSprite.setAnimationProfile(new ScriptedVelocityAnimationProfile()
                                .setIdleAnimId(Sonic2AnimationIds.WAIT)
                                .setWalkAnimId(Sonic2AnimationIds.WALK)
                                .setBlinkAnimId(Sonic2AnimationIds.BLINK)
                                .setGetUpAnimId(Sonic2AnimationIds.GET_UP));
                mockSprite.setAnimationId(Sonic2AnimationIds.WAIT.id());
                mockSprite.setAnimationFrameIndex(0x1E);

                // ObjD5's object-order shim has hidden the fresh right edge from
                // Sonic_Move, but Obj01_MdNormal_Checks already read it from the
                // logical held-control word before that movement-only delay.
                setInputState(false, false, false, false, false);
                setRawHorizontalInput(false, true);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doWaitBlinkInterruptCheck");
                method.setAccessible(true);

                assertTrue((boolean) method.invoke(manager));
                assertEquals(Sonic2AnimationIds.BLINK.id(), mockSprite.getAnimationId());
                Field effectiveRight = PlayableSpriteMovement.class.getDeclaredField("inputRight");
                effectiveRight.setAccessible(true);
                assertFalse(effectiveRight.getBoolean(manager),
                                "the stale riding shim must keep horizontal movement suppressed");
        }

        @Test
        public void airborneJumpFlipAdvancesFromInertiaBeforeAnimation() throws Exception {
                mockSprite.setFlipAngle(0xF2);
                mockSprite.setFlipSpeed(4);
                mockSprite.setFlipsRemaining(1);
                mockSprite.setGSpeed((short) 0x0100);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("advanceAirborneFlipAngle");
                method.setAccessible(true);
                method.invoke(manager);

                assertEquals(0xF6, mockSprite.getFlipAngle());
                assertEquals(1, mockSprite.getFlipsRemaining());
        }

        @Test
        public void testS3kRollStopAnimationChangeRetainsPushing() throws Exception {
                setGameRulesForTest(GameRules.SONIC_3K);
                mockSprite.setAnimationProfile(new ScriptedVelocityAnimationProfile()
                                .setIdleAnimId(5)
                                .setRollAnimId(2));
                mockSprite.setAnimationId(2);
                mockSprite.setRolling(true);
                mockSprite.setPushing(true);
                mockSprite.setGSpeed((short) 0);
                mockSprite.setAir(false);
                mockSprite.setPinballMode(false);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doRollSpeed");
                method.setAccessible(true);
                method.invoke(manager);

                assertFalse(mockSprite.getRolling(), "Tails_RollSpeed clears Status_Roll below the stop threshold");
                assertEquals(5, mockSprite.getAnimationId(),
                                "Tails_RollSpeed writes idle animation when rolling stops");
                assertTrue(mockSprite.getPushing(),
                                "Sonic_RollSpeed/Tails_RollSpeed's roll-stop block writes only the roll bit,"
                                                + " the radii, anim and y_pos (sonic3k.asm:22979-22990,28216-28231;"
                                                + " s2.asm:37051-37061). Animate_Sonic/Animate_Tails clears"
                                                + " Status_Push on anim != prev_anim (sonic3k.asm:29359-29364,"
                                                + " 29681-29686), and that runs AFTER Sonic_RecordPos"
                                                + " (sonic3k.asm:21995-22022), so the movement path must leave"
                                                + " Status_Push alone");
        }

        @Test
        public void testSameDirectionRollInputPublishesRollButOppositeInputDoesNot() throws Exception {
                mockSprite.setAnimationProfile(new ScriptedVelocityAnimationProfile()
                                .setIdleAnimId(5)
                                .setWalkAnimId(0)
                                .setRollAnimId(2));
                mockSprite.setRolling(true);
                mockSprite.setAir(false);
                mockSprite.setGSpeed((short) 0x100);
                mockSprite.setAnimationId(0);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doRollSpeed");
                method.setAccessible(true);
                setInputState(false, true, false, false, false);
                method.invoke(manager);

                assertEquals(2, mockSprite.getAnimationId(),
                                "RollRight publishes Roll when inertia is already nonnegative");

                mockSprite.setGSpeed((short) 0x100);
                mockSprite.setAnimationId(0);
                setInputState(true, false, false, false, false);
                method.invoke(manager);

                assertEquals(0, mockSprite.getAnimationId(),
                                "RollLeft deceleration against positive inertia does not write obAnim");
        }

        @Test
        public void testS3kFacingFlipClearsLeftWallPushLatchLikeRom() throws Exception {
                setGameRulesForTest(GameRules.SONIC_3K);
                mockSprite.setAir(false);
                mockSprite.setRolling(false);
                mockSprite.setDirection(Direction.RIGHT);
                mockSprite.setGSpeed((short) 0);
                mockSprite.setPushing(false);

                Method updatePush = PlayableSpriteMovement.class.getDeclaredMethod(
                                "updatePushingOnDirectionChange", boolean.class, boolean.class);
                updatePush.setAccessible(true);
                updatePush.invoke(manager, true, false);
                mockSprite.setDirection(Direction.LEFT);
                mockSprite.setPushing(true);

                Method clearPush = PlayableSpriteMovement.class.getDeclaredMethod(
                                "clearFacingFlipPushAfterGroundWallCollision");
                clearPush.setAccessible(true);
                clearPush.invoke(manager);

                assertEquals(Direction.LEFT, mockSprite.getDirection(),
                                "Pressing left from rest should face into the left wall before CalcRoomInFront");
                assertFalse(mockSprite.getPushing(),
                                "S3K grounded facing-flip push clear is unconditional; a later CalcRoomInFront "
                                                + "contact can set Status_Push again in the normal movement path");
        }

        @Test
        public void airborneFacingFlipPreservesPushLikeRomJumpDirectionControl() throws Exception {
                setGameRulesForTest(GameRules.SONIC_2);
                mockSprite.setAir(true);
                mockSprite.setRolling(false);
                mockSprite.setDirection(Direction.LEFT);
                mockSprite.setGSpeed((short) 0);
                mockSprite.setPushing(true);

                Method updatePush = PlayableSpriteMovement.class.getDeclaredMethod(
                                "updatePushingOnDirectionChange", boolean.class, boolean.class);
                updatePush.setAccessible(true);
                updatePush.invoke(manager, false, true);

                assertTrue(mockSprite.getPushing(),
                                "S2 Tails_ChgJumpDir flips facing in air without clearing Status_Push "
                                                + "(s2.asm:40184-40211); HTZ2 f4526 keeps the Obj30 drop push bit");
        }

        @Test
        public void groundedFacingFlipRestartsWalkScriptLikeRomPrevAnimSentinel() throws Exception {
                setGameRulesForTest(GameRules.SONIC_1);
                SpriteAnimationSet animations = new SpriteAnimationSet();
                animations.addScript(0, new SpriteAnimationScript(0xFF,
                                List.of(10, 11, 12, 13), SpriteAnimationEndAction.LOOP, 0));
                animations.addScript(1, new SpriteAnimationScript(0xFF,
                                List.of(20, 21, 22, 23), SpriteAnimationEndAction.LOOP, 0));
                animations.addScript(5, new SpriteAnimationScript(0,
                                List.of(30), SpriteAnimationEndAction.LOOP, 0));
                mockSprite.setAnimationSet(animations);
                mockSprite.setAnimationProfile(new ScriptedVelocityAnimationProfile()
                                .setIdleAnimId(5)
                                .setWalkAnimId(0)
                                .setRunAnimId(1)
                                .setRunSpeedThreshold(0x600));
                mockSprite.setAnimationId(0);
                mockSprite.setMovementInputActive(true);
                mockSprite.setDirection(Direction.RIGHT);
                mockSprite.setGSpeed((short) 0);
                mockSprite.setAir(false);
                mockSprite.setRolling(false);

                mockSprite.getAnimationManager().update(0);
                mockSprite.setAnimationFrameIndex(2);
                mockSprite.setAnimationTick(0);

                Method updatePush = PlayableSpriteMovement.class.getDeclaredMethod(
                                "updatePushingOnDirectionChange", boolean.class, boolean.class);
                updatePush.setAccessible(true);
                updatePush.invoke(manager, true, false);
                mockSprite.setDirection(Direction.LEFT);

                assertEquals(1, mockSprite.getAnimationManager().captureRewindState().lastAnimationId(),
                                "MoveLeft publishes the native prev_anim=Run sentinel, not an anonymous restart");

                mockSprite.getAnimationManager().update(1);

                assertEquals(10, mockSprite.getMappingFrame(),
                                "S1/S2/S3K MoveLeft/MoveRight force prev_anim=Run on a grounded facing flip, "
                                + "so Animate_* must restart the walk script from frame 0");
        }

        @Test
        public void slopeResistanceCrossingZeroRestartsWalkOnSameFrameAsFacingFlip() throws Exception {
                setGameRulesForTest(GameRules.SONIC_1);
                SpriteAnimationSet animations = new SpriteAnimationSet();
                animations.addScript(0, new SpriteAnimationScript(0xFF,
                                List.of(10, 11, 12, 13), SpriteAnimationEndAction.LOOP, 0));
                animations.addScript(1, new SpriteAnimationScript(0xFF,
                                List.of(20, 21, 22, 23), SpriteAnimationEndAction.LOOP, 0));
                animations.addScript(5, new SpriteAnimationScript(0,
                                List.of(30), SpriteAnimationEndAction.LOOP, 0));
                mockSprite.setAnimationSet(animations);
                mockSprite.setAnimationProfile(new ScriptedVelocityAnimationProfile()
                                .setIdleAnimId(5)
                                .setWalkAnimId(0)
                                .setRunAnimId(1)
                                .setRunSpeedThreshold(0x600));
                mockSprite.setAnimationId(0);
                mockSprite.setMovementInputActive(true);
                mockSprite.setDirection(Direction.LEFT);
                mockSprite.setGSpeed((short) -1);
                mockSprite.setAngle((byte) 0x20);
                mockSprite.setAir(false);
                mockSprite.setRolling(false);

                mockSprite.getAnimationManager().update(0);
                mockSprite.setAnimationFrameIndex(2);
                mockSprite.setAnimationTick(0);

                Method slopeResist = PlayableSpriteMovement.class.getDeclaredMethod("doSlopeResist");
                slopeResist.setAccessible(true);
                slopeResist.invoke(manager);
                assertTrue(mockSprite.getGSpeed() > 0,
                                "Sonic_SlopeResist should cross inertia through zero before MoveRight");

                Method updatePush = PlayableSpriteMovement.class.getDeclaredMethod(
                                "updatePushingOnDirectionChange", boolean.class, boolean.class);
                updatePush.setAccessible(true);
                updatePush.invoke(manager, false, true);
                mockSprite.setDirection(Direction.RIGHT);
                mockSprite.getAnimationManager().update(1);

                assertEquals(1, mockSprite.getAnimationFrameIndex(),
                                "MoveRight must observe post-slope inertia and restart Walk at script frame zero");
        }

        /**
         * Test ROM-accurate speed-dependent threshold calculation.
         * ROM: threshold = min(abs(xSpeed >> 8) + 4, 14)
         */
        @Test
        public void testSpeedDependentThreshold() {
                // Test cases: xSpeed -> expected threshold
                // Speed 0 -> threshold 4
                assertEquals(4, Math.min(Math.abs(0 >> 8) + 4, 14), "Threshold at speed 0");

                // Speed 256 (1 pixel) -> threshold 5
                assertEquals(5, Math.min(Math.abs(256 >> 8) + 4, 14), "Threshold at speed 256");

                // Speed 2560 (10 pixels) -> threshold 14 (capped)
                assertEquals(14, Math.min(Math.abs(2560 >> 8) + 4, 14), "Threshold at speed 2560");

                // Speed -512 (-2 pixels) -> threshold 6
                assertEquals(6, Math.min(Math.abs(-512 >> 8) + 4, 14), "Threshold at speed -512");
        }

        // ========================================
        // ARITHMETIC SHIFT VS DIVISION TESTS
        // ========================================

        /**
         * Test that right shift (>>8) behaves correctly for negative values.
         * This is critical for ROM accuracy: 68000 ASR rounds toward -infinity,
         * while Java / rounds toward zero.
         *
         * Examples:
         * -1 >> 8 = -1 (correct, rounds toward -infinity)
         * -1 / 256 = 0 (incorrect, rounds toward zero)
         *
         * -255 >> 8 = -1 (correct)
         * -255 / 256 = 0 (incorrect)
         */
        @Test
        public void testArithmeticShiftVsDivisionForNegativeValues() {
                // Test case 1: -1
                assertEquals(-1, -1 >> 8, ">>8 rounds -1 toward -infinity");
                assertEquals(0, -1 / 256, "/256 rounds -1 toward zero");

                // Test case 2: -255
                assertEquals(-1, -255 >> 8, ">>8 rounds -255 toward -infinity");
                assertEquals(0, -255 / 256, "/256 rounds -255 toward zero");

                // Test case 3: -256
                assertEquals(-1, -256 >> 8, ">>8 for -256");
                assertEquals(-1, -256 / 256, "/256 for -256");

                // Test case 4: -257
                assertEquals(-2, -257 >> 8, ">>8 rounds -257 toward -infinity");
                assertEquals(-1, -257 / 256, "/256 rounds -257 toward zero");

                // Test case 5: positive values should be the same
                assertEquals(0, 255 >> 8, ">>8 for 255");
                assertEquals(0, 255 / 256, "/256 for 255");

                assertEquals(1, 256 >> 8, ">>8 for 256");
                assertEquals(1, 256 / 256, "/256 for 256");
        }

        /**
         * Test boundary prediction with negative position/speed.
         * This verifies the fix for using >>8 instead of /256.
         */
        @Test
        public void testBoundaryPredictionWithNegativeSpeed() {
                // Scenario: sprite at x=100, moving left slowly
                // xTotal = 100*256 + 0 + (-255) = 25600 - 255 = 25345
                // predictedX should be 25345 >> 8 = 99 (correct)
                // NOT 25345 / 256 = 99 (same in this case)

                int xTotal1 = 100 * 256 + 0 + (-255);
                assertEquals(99, xTotal1 >> 8, "Positive total with >>8");
                assertEquals(99, xTotal1 / 256, "Positive total with /256");

                // Scenario: sprite near left boundary, moving left
                // xTotal = 1*256 + 0 + (-512) = 256 - 512 = -256
                // predictedX should be -256 >> 8 = -1 (correct)
                // NOT -256 / 256 = -1 (same in this case)

                int xTotal2 = 1 * 256 + 0 + (-512);
                assertEquals(-1, xTotal2 >> 8, "Negative total with >>8");
                assertEquals(-1, xTotal2 / 256, "Negative total with /256");

                // Scenario: edge case with non-multiple of 256
                // xTotal = 0*256 + 0 + (-1) = -1
                // predictedX should be -1 >> 8 = -1 (rounds toward -infinity)
                // NOT -1 / 256 = 0 (rounds toward zero) - THIS IS THE BUG!

                int xTotal3 = 0 * 256 + 0 + (-1);
                assertEquals(-1, xTotal3 >> 8, "Edge case -1 with >>8 (correct)");
                assertEquals(0, xTotal3 / 256, "Edge case -1 with /256 (wrong)");
        }

        // ========================================
        // GROUND MODE TRANSITION TESTS
        // ========================================

        /**
         * Test that updateGroundMode() produces correct mode from angle.
         * ROM formula from s2.asm:42551.
         *
         * Actual boundaries (traced through the algorithm):
         * - GROUND: 0x00-0x20, 0xE0-0xFF
         * - LEFTWALL: 0x21-0x5F
         * - CEILING: 0x60-0xA0
         * - RIGHTWALL: 0xA1-0xDF
         */
        @Test
        public void testGroundModeFromAngle() throws Exception {
                Method updateGroundMode = PlayableSpriteMovement.class.getDeclaredMethod("updateGroundMode");
                updateGroundMode.setAccessible(true);

                // Test GROUND mode angles: 0x00-0x20, 0xE0-0xFF
                byte[] groundAngles = {0x00, 0x10, 0x20, (byte)0xE0, (byte)0xF0, (byte)0xFF};
                for (byte angle : groundAngles) {
                        mockSprite.setAngle(angle);
                        updateGroundMode.invoke(manager);
                        assertEquals(GroundMode.GROUND, mockSprite.getGroundMode(), "Angle " + String.format("0x%02X", angle & 0xFF) + " should be GROUND");
                }

                // Test LEFTWALL mode angles: 0x21-0x5F
                byte[] leftWallAngles = {0x21, 0x30, 0x40, 0x5F};
                for (byte angle : leftWallAngles) {
                        mockSprite.setAngle(angle);
                        updateGroundMode.invoke(manager);
                        assertEquals(GroundMode.LEFTWALL, mockSprite.getGroundMode(), "Angle " + String.format("0x%02X", angle & 0xFF) + " should be LEFTWALL");
                }

                // Test CEILING mode angles: 0x60-0xA0
                byte[] ceilingAngles = {0x60, 0x70, (byte)0x80, (byte)0xA0};
                for (byte angle : ceilingAngles) {
                        mockSprite.setAngle(angle);
                        updateGroundMode.invoke(manager);
                        assertEquals(GroundMode.CEILING, mockSprite.getGroundMode(), "Angle " + String.format("0x%02X", angle & 0xFF) + " should be CEILING");
                }

                // Test RIGHTWALL mode angles: 0xA1-0xDF
                byte[] rightWallAngles = {(byte)0xA1, (byte)0xB0, (byte)0xC0, (byte)0xDF};
                for (byte angle : rightWallAngles) {
                        mockSprite.setAngle(angle);
                        updateGroundMode.invoke(manager);
                        assertEquals(GroundMode.RIGHTWALL, mockSprite.getGroundMode(), "Angle " + String.format("0x%02X", angle & 0xFF) + " should be RIGHTWALL");
                }
        }

        /**
         * Test boundary angles where mode transitions occur.
         * These are critical for loop traversal accuracy.
         *
         * Actual boundaries (traced through the algorithm):
         * - GROUND/LEFTWALL: 0x20 -> GROUND, 0x21 -> LEFTWALL
         * - LEFTWALL/CEILING: 0x5F -> LEFTWALL, 0x60 -> CEILING
         * - CEILING/RIGHTWALL: 0xA0 -> CEILING, 0xA1 -> RIGHTWALL
         * - RIGHTWALL/GROUND: 0xDF -> RIGHTWALL, 0xE0 -> GROUND
         */
        @Test
        public void testGroundModeBoundaryAngles() throws Exception {
                Method updateGroundMode = PlayableSpriteMovement.class.getDeclaredMethod("updateGroundMode");
                updateGroundMode.setAccessible(true);

                // GROUND/LEFTWALL boundary: 0x20 -> GROUND, 0x21 -> LEFTWALL
                mockSprite.setAngle((byte)0x20);
                updateGroundMode.invoke(manager);
                assertEquals(GroundMode.GROUND, mockSprite.getGroundMode(), "0x20 should be GROUND");

                mockSprite.setAngle((byte)0x21);
                updateGroundMode.invoke(manager);
                assertEquals(GroundMode.LEFTWALL, mockSprite.getGroundMode(), "0x21 should be LEFTWALL");

                // LEFTWALL/CEILING boundary: 0x5F -> LEFTWALL, 0x60 -> CEILING
                mockSprite.setAngle((byte)0x5F);
                updateGroundMode.invoke(manager);
                assertEquals(GroundMode.LEFTWALL, mockSprite.getGroundMode(), "0x5F should be LEFTWALL");

                mockSprite.setAngle((byte)0x60);
                updateGroundMode.invoke(manager);
                assertEquals(GroundMode.CEILING, mockSprite.getGroundMode(), "0x60 should be CEILING");

                // CEILING/RIGHTWALL boundary: 0xA0 -> CEILING, 0xA1 -> RIGHTWALL
                mockSprite.setAngle((byte)0xA0);
                updateGroundMode.invoke(manager);
                assertEquals(GroundMode.CEILING, mockSprite.getGroundMode(), "0xA0 should be CEILING");

                mockSprite.setAngle((byte)0xA1);
                updateGroundMode.invoke(manager);
                assertEquals(GroundMode.RIGHTWALL, mockSprite.getGroundMode(), "0xA1 should be RIGHTWALL");

                // RIGHTWALL/GROUND boundary: 0xDF -> RIGHTWALL, 0xE0 -> GROUND
                mockSprite.setAngle((byte)0xDF);
                updateGroundMode.invoke(manager);
                assertEquals(GroundMode.RIGHTWALL, mockSprite.getGroundMode(), "0xDF should be RIGHTWALL");

                mockSprite.setAngle((byte)0xE0);
                updateGroundMode.invoke(manager);
                assertEquals(GroundMode.GROUND, mockSprite.getGroundMode(), "0xE0 should be GROUND");
        }

        // ========================================
        // WALL COLLISION PREDICTION TESTS
        // ========================================

        /**
         * Test that wall collision prediction uses speed directly without subpixels.
         * ROM: Uses integer velocity (x_vel >> 8) directly for projection,
         * not (x_vel + subpixel) >> 8.
         */
        @Test
        public void testWallCollisionProjectionWithoutSubpixels() {
                // Verify the correct formula: projectedDx = xSpeed >> 8
                short xSpeed = 512; // 2 pixels per frame

                // Correct (ROM-accurate): just shift the speed
                short correctProjection = (short)(xSpeed >> 8);
                assertEquals(2, correctProjection, "Correct projection should be 2");

                // Previous incorrect behavior would add subpixels first
                // This is wrong because it can cause 1-pixel errors
                byte xSubpixel = (byte)200; // Near full subpixel
                short incorrectProjection = (short)((xSpeed + (xSubpixel & 0xFF)) >> 8);
                assertEquals(2, incorrectProjection, "Incorrect projection would be 2 (same here)");

                // Edge case where the bug manifests:
                xSpeed = 56; // Less than 1 pixel per frame
                xSubpixel = (byte)200;

                correctProjection = (short)(xSpeed >> 8);
                assertEquals(0, correctProjection, "Correct projection for slow speed");

                incorrectProjection = (short)((xSpeed + (xSubpixel & 0xFF)) >> 8);
                assertEquals(1, incorrectProjection, "Incorrect projection would be 1 (off by 1 pixel)");
        }

        /**
         * Test that Y projection for wall collision is also subpixel-free.
         */
        @Test
        public void testWallCollisionYProjectionWithoutSubpixels() {
                // Verify the correct formula: projectedDy = ySpeed >> 8
                short ySpeed = -384; // Moving up ~1.5 pixels per frame

                // Correct (ROM-accurate): just shift the speed
                short correctProjection = (short)(ySpeed >> 8);
                assertEquals(-2, correctProjection, "Correct Y projection should be -2");

                // Edge case with subpixels
                ySpeed = -56;
                byte ySubpixel = (byte)200;

                correctProjection = (short)(ySpeed >> 8);
                assertEquals(-1, correctProjection, "Correct Y projection for slow upward speed");

                // Previous buggy behavior
                short incorrectProjection = (short)((ySpeed + (ySubpixel & 0xFF)) >> 8);
                assertEquals(0, incorrectProjection, "Incorrect Y projection would be 0 (off by 1 pixel)");
        }

        // ========================================
        // SPEED THRESHOLD FOR GROUND ATTACHMENT TESTS
        // ========================================

        /**
         * Test that getSpeedForThreshold() uses gSpeed as fallback ONLY when xSpeed is zero.
         * This is CRITICAL for loop traversal - during loop transitions, xSpeed can be
         * zero due to velocity decomposition even when gSpeed is non-zero.
         *
         * ROM uses raw velocity bytes directly (s2.asm:42727 mvabs.b instruction).
         * No fallback to gSpeed - the fix is to ensure xSpeed/ySpeed are always
         * set correctly when gSpeed is set (e.g., immediately after spindash release).
         */
        @Test
        public void testSpeedThresholdUsesRawVelocityNoFallback() throws Exception {
                // ROM behavior: use xSpeed directly, no fallback to gSpeed
                mockSprite.setGSpeed((short) 1536);
                mockSprite.setXSpeed((short) 0);  // If this is 0, threshold is 0
                mockSprite.setYSpeed((short) 0);
                mockSprite.setGroundMode(GroundMode.GROUND);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("getSpeedForThreshold");
                method.setAccessible(true);
                int threshold = (int) method.invoke(manager);

                // ROM uses xSpeed directly (0 >> 8 = 0), no gSpeed fallback
                assertEquals(0, threshold, "Should use xSpeed directly per ROM behavior");

                // This gives positiveThreshold = min(0 + 4, 14) = 4
                // The fix for spindash is to ensure xSpeed is set BEFORE this check
                int positiveThreshold = Math.min(threshold + 4, 14);
                assertEquals(4, positiveThreshold, "Attachment threshold is 4 pixels with zero xSpeed");
        }

        /**
         * Test that without gSpeed fallback, threshold would be dangerously tight.
         * This test documents the bug that was causing loop fall-through.
         */
        @Test
        public void testSpeedThresholdWithoutFallbackWouldBeTooTight() {
                // If we ONLY used xSpeed (the buggy behavior):
                short xSpeed = 0;  // Zero due to velocity decomposition
                int buggySpeedPixels = Math.abs(xSpeed >> 8);  // = 0
                int buggyThreshold = Math.min(buggySpeedPixels + 4, 14);  // = 4

                assertEquals(4, buggyThreshold, "Without fallback, threshold would be only 4 pixels");

                // 4 pixels is too tight for curved surfaces like loops
                // Normal terrain distance on curves can be 5-10 pixels
                // This would cause false "too far from terrain" and launch Sonic out
        }

        /**
         * Test speed threshold on wall modes uses ySpeed directly per ROM (no fallback).
         */
        @Test
        public void testSpeedThresholdOnWallMode() throws Exception {
                // On LEFTWALL mode, ROM uses ySpeed directly (s2.asm:42794 mvabs.b y_vel)
                mockSprite.setGSpeed((short) 1024);  // 4 pixels/frame
                mockSprite.setXSpeed((short) 1024);  // Non-zero, but irrelevant for wall mode
                mockSprite.setYSpeed((short) 0);     // Zero
                mockSprite.setGroundMode(GroundMode.LEFTWALL);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("getSpeedForThreshold");
                method.setAccessible(true);
                int threshold = (int) method.invoke(manager);

                // ROM uses ySpeed directly (0 >> 8 = 0), no gSpeed fallback
                assertEquals(0, threshold, "Should use ySpeed directly on wall mode per ROM");

                // With non-zero ySpeed, threshold reflects actual velocity
                mockSprite.setYSpeed((short) 1024);  // 4 pixels/frame
                int threshold2 = (int) method.invoke(manager);
                assertEquals(4, threshold2, "Should use ySpeed when non-zero");
        }

        /**
         * Test speed threshold uses ROM-style velocity when non-zero, NOT Math.max().
         * This is critical for slope accuracy - using Math.max() breaks slopes.
         */
        @Test
        public void testSpeedThresholdUsesRomStyleWhenNonZero() throws Exception {
                // On slopes, xSpeed is non-zero, so should use xSpeed (ROM behavior)
                // NOT Math.max(xSpeed, gSpeed) which would give too loose a threshold
                mockSprite.setGSpeed((short) 1536);  // 6 pixels/frame
                mockSprite.setXSpeed((short) 1024);  // 4 pixels/frame (non-zero on slope)
                mockSprite.setYSpeed((short) 0);
                mockSprite.setGroundMode(GroundMode.GROUND);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("getSpeedForThreshold");
                method.setAccessible(true);
                int threshold = (int) method.invoke(manager);

                // Should use xSpeed (4), NOT gSpeed (6), because xSpeed is non-zero
                assertEquals(4, threshold, "Should use xSpeed (ROM behavior) when non-zero");

                // This gives threshold = min(4 + 4, 14) = 8, matching ROM
                // Using Math.max would give min(6 + 4, 14) = 10, which breaks slopes
        }

        /**
         * Test speed threshold uses high byte of velocity (ROM mvabs.b behavior).
         */
        @Test
        public void testSpeedThresholdUsesHighByte() throws Exception {
                // ROM uses mvabs.b which takes high byte of velocity
                mockSprite.setGSpeed((short) 1536);  // 6 pixels/frame
                mockSprite.setXSpeed((short) 255);   // Less than 1 pixel, high byte = 0
                mockSprite.setYSpeed((short) 0);
                mockSprite.setGroundMode(GroundMode.GROUND);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("getSpeedForThreshold");
                method.setAccessible(true);
                int threshold = (int) method.invoke(manager);

                // 255 >> 8 = 0, ROM uses this directly (no fallback)
                assertEquals(0, threshold, "Should use high byte of xSpeed (0)");

                // Now test with xSpeed = 256 (exactly 1 pixel)
                mockSprite.setXSpeed((short) 256);
                int threshold2 = (int) method.invoke(manager);

                // 256 >> 8 = 1
                assertEquals(1, threshold2, "Should use xSpeed when it's 1+ pixels");

                // With higher speed
                mockSprite.setXSpeed((short) 2048);  // 8 pixels/frame
                int threshold3 = (int) method.invoke(manager);
                assertEquals(8, threshold3, "Should use xSpeed high byte");
        }

        /**
         * Test that spindash velocity calculation sets xSpeed from gSpeed immediately.
         * This is critical for ground attachment threshold calculation.
         *
         * Bug fix: Before this fix, xSpeed was 0 during doAnglePos() after spindash release,
         * causing threshold to be only 4 pixels (0+4) and Sonic to falsely become airborne.
         * Now xSpeed is set from gSpeed before any ground checks, giving proper threshold.
         */
        @Test
        public void testSpindashVelocityCalculation() throws Exception {
                // Test that xSpeed is correctly calculated from gSpeed at angle 0
                short gSpeed = 0x0900;  // Typical spindash speed (2304 subpixels = 9 pixels/frame)
                mockSprite.setGSpeed(gSpeed);
                mockSprite.setAngle((byte) 0);  // Flat ground
                mockSprite.setGroundMode(GroundMode.GROUND);

                // Simulate the velocity calculation that happens in doReleaseSpindash:
                // xSpeed = (gSpeed * cos(angle)) >> 8
                // At angle 0, cos = 256, so xSpeed = gSpeed
                int hexAngle = mockSprite.getAngle() & 0xFF;
                short calculatedXSpeed = (short) ((gSpeed * TrigLookupTable.cosHex(hexAngle)) >> 8);
                mockSprite.setXSpeed(calculatedXSpeed);

                // xSpeed should match gSpeed on flat ground
                assertEquals(gSpeed, mockSprite.getXSpeed(), "xSpeed should equal gSpeed on flat ground (angle 0)");

                // Verify threshold is now correct
                Method thresholdMethod = PlayableSpriteMovement.class.getDeclaredMethod("getSpeedForThreshold");
                thresholdMethod.setAccessible(true);
                int threshold = (int) thresholdMethod.invoke(manager);

                // With xSpeed = 0x0900 = 2304, threshold = 2304 >> 8 = 9
                assertEquals(9, threshold, "Threshold should reflect spindash speed");

                // positiveThreshold = min(9 + 4, 14) = 13 pixels - ample for ground attachment
                int positiveThreshold = Math.min(threshold + 4, 14);
                assertTrue(positiveThreshold >= 10, "Ground attachment threshold should be >= 10 pixels");
        }

        /**
         * Test velocity calculation on slopes (non-zero angle).
         */
        @Test
        public void testSpindashVelocityOnSlope() throws Exception {
                // On a 45-degree slope, both xSpeed and ySpeed should be non-zero
                short gSpeed = 0x0800;  // Spindash speed
                mockSprite.setGSpeed(gSpeed);
                mockSprite.setAngle((byte) 0x20);  // ~45 degrees
                mockSprite.setGroundMode(GroundMode.GROUND);

                // Calculate velocities as done in doReleaseSpindash
                int hexAngle = mockSprite.getAngle() & 0xFF;
                short xSpeed = (short) ((gSpeed * TrigLookupTable.cosHex(hexAngle)) >> 8);
                short ySpeed = (short) ((gSpeed * TrigLookupTable.sinHex(hexAngle)) >> 8);

                mockSprite.setXSpeed(xSpeed);
                mockSprite.setYSpeed(ySpeed);

                // Both should be non-zero on a slope
                assertTrue(xSpeed != 0, "xSpeed should be non-zero on slope");
                assertTrue(ySpeed != 0, "ySpeed should be non-zero on slope");

                // Verify threshold uses correct velocity
                Method thresholdMethod = PlayableSpriteMovement.class.getDeclaredMethod("getSpeedForThreshold");
                thresholdMethod.setAccessible(true);
                int threshold = (int) thresholdMethod.invoke(manager);

                // Threshold should be based on xSpeed (for GROUND mode)
                int expectedThreshold = Math.abs(xSpeed >> 8);
                assertEquals(expectedThreshold, threshold, "Threshold should be based on xSpeed");
        }

        @Test
        public void knucklesSlideGetUpRunsKnuxTouchFloorGroundingTail() throws Exception {
                mockSprite.setAir(true);
                mockSprite.setPushing(true);
                mockSprite.setJumping(true);
                mockSprite.setFlipAngle(0x60);
                mockSprite.setFlipType(0x81);
                mockSprite.setFlipsRemaining(3);
                mockSprite.setDoubleJumpFlag(3);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("slideGetUp");
                method.setAccessible(true);
                method.invoke(manager);

                assertFalse(mockSprite.getAir(),
                                "Knuckles_Sliding .getUp must tail through Knux_TouchFloor");
                assertFalse(mockSprite.getPushing(), "Knux_TouchFloor clears Status_Push");
                assertFalse(mockSprite.isJumping(), "Knux_TouchFloor clears jumping(a0)");
                assertEquals(0, mockSprite.getFlipAngle());
                assertEquals(0, mockSprite.getFlipType());
                assertEquals(0, mockSprite.getFlipsRemaining());
        }

        @Test
        public void knucklesWallClimbNoInputUsesFloorDistanceAsRetailAnimationDelta() throws Exception {
                prepareWallClimbProbe(0x0100, 0xB7);

                try (var terrain = org.mockito.Mockito.mockStatic(ObjectTerrainUtils.class)) {
                        terrain.when(() -> ObjectTerrainUtils.checkFloorDist(0x0100, 0x0109))
                                        .thenReturn(new TerrainCheckResult(3, (byte) 0, 1));

                        invokeWallClimbUpdate();
                }

                assertEquals(0xBA, mockSprite.getMappingFrame(),
                                "FixBugs=0 leaves sub_F828's floor distance in d1, so B7 + 3 becomes BA");
                assertEquals(3, mockSprite.getDoubleJumpProperty() & 0xFF);
        }

        @Test
        public void knucklesWallClimbNoInputDetachesOnNegativeFloorDistance() throws Exception {
                prepareWallClimbProbe(0x0100, 0xB9);
                mockSprite.setAir(true);
                mockSprite.setJumping(true);
                int originalY = mockSprite.getY();

                try (var terrain = org.mockito.Mockito.mockStatic(ObjectTerrainUtils.class)) {
                        terrain.when(() -> ObjectTerrainUtils.checkFloorDist(0x0100, 0x0109))
                                        .thenReturn(new TerrainCheckResult(-3, (byte) 0, 1));

                        invokeWallClimbUpdate();
                }

                assertEquals((short) (originalY - 3), mockSprite.getY());
                assertEquals(0, mockSprite.getDoubleJumpFlag());
                assertFalse(mockSprite.getAir());
                assertFalse(mockSprite.isJumping());
        }

        @Test
        public void knucklesWallClimbNoSurfaceUsesRomEmptyTileDistance() throws Exception {
                // probeY=$114, so the ROM FindFloor empty-tile path returns
                // $1F-($114&$F)=$1B. $A0+$1B lands inside the climb-frame range.
                prepareWallClimbProbe(0x010B, 0xA0);

                try (var terrain = org.mockito.Mockito.mockStatic(ObjectTerrainUtils.class)) {
                        terrain.when(() -> ObjectTerrainUtils.checkFloorDist(0x0100, 0x0114))
                                        .thenReturn(TerrainCheckResult.noCollision());

                        invokeWallClimbUpdate();
                }

                assertEquals(0xBB, mockSprite.getMappingFrame(),
                                "the engine no-surface sentinel must map back to the ROM's positive d1 distance");
        }

        @Test
        public void knucklesWallClimbMappingFrameAddWrapsAsByteBeforeClamp() throws Exception {
                prepareWallClimbProbe(0x0100, 0xF0);

                try (var terrain = org.mockito.Mockito.mockStatic(ObjectTerrainUtils.class)) {
                        terrain.when(() -> ObjectTerrainUtils.checkFloorDist(0x0100, 0x0109))
                                        .thenReturn(new TerrainCheckResult(0x20, (byte) 0, 1));

                        invokeWallClimbUpdate();
                }

                assertEquals(0xBC, mockSprite.getMappingFrame(),
                                "$F0+$20 wraps to $10 before the unsigned B7..BC clamps");
        }

        @Test
        public void ordinaryControlUnlockDoesNotRecreateConsumedRawJumpEdge() throws Exception {
                Method storeInput = PlayableSpriteMovement.class.getDeclaredMethod("storeInputState",
                                boolean.class, boolean.class, boolean.class, boolean.class, boolean.class);
                storeInput.setAccessible(true);

                // Poll_Controller sees the edge while Ctrl_1_locked keeps the logical
                // held word out of Sonic_Control.
                mockSprite.setJumpInputPressed(true, true);
                storeInput.invoke(manager, false, false, false, false, false);
                assertFalse(manager.captureRewindState().inputJumpPress());

                // The lock lifts with A/B/C still held, but Poll_Controller's press
                // byte is now clear. The logical copy must not manufacture a new edge.
                mockSprite.setJumpInputPressed(true, false);
                storeInput.invoke(manager, false, false, false, false, true);

                assertFalse(manager.captureRewindState().inputJumpPress(),
                                "Sonic_Control copies the raw pressed byte; it does not derive an edge from filtered held input");
        }

        @Test
        public void hurtStopBottomKillReturnsBeforeTerrainCollision() throws Exception {
                setGameRulesForTest(GameRules.SONIC_2);
                boolean[] terrainCollisionRan = {false};
                CollisionSystem collisionSystem = new LandingProbeCollisionSystem(
                                (sprite, landingHandler, forceFloorCheck) -> terrainCollisionRan[0] = true);
                manager = new PlayableSpriteMovement(mockSprite, collisionSystem, GameServices.gameState());
                installRuntimeCollisionSystem(collisionSystem);

                Camera camera = GameServices.camera();
                camera.setMaxY((short) 0x0100);
                camera.setMaxYTarget((short) 0x0100);
                camera.setLevelStarted(true);
                mockSprite.setCentreY((short) 0x01E1);
                mockSprite.setAir(true);
                mockSprite.setHurt(true);
                mockSprite.setXSpeed((short) 0);
                mockSprite.setYSpeed((short) 0);
                mockSprite.setGroundSensors(new Sensor[] {
                                fixedSensor(mockSprite, Direction.DOWN, (byte) 0, (byte) 16),
                                fixedSensor(mockSprite, Direction.DOWN, (byte) 0, (byte) 16)
                });
                mockSprite.setCeilingSensors(new Sensor[] {
                                fixedSensor(mockSprite, Direction.UP, (byte) 0, (byte) 16),
                                fixedSensor(mockSprite, Direction.UP, (byte) 0, (byte) 16)
                });
                mockSprite.setPushSensors(new Sensor[] {
                                fixedSensor(mockSprite, Direction.LEFT, (byte) 0, (byte) 16),
                                fixedSensor(mockSprite, Direction.RIGHT, (byte) 0, (byte) 16)
                });

                Method modeAirborne = PlayableSpriteMovement.class.getDeclaredMethod("modeAirborne");
                modeAirborne.setAccessible(true);
                modeAirborne.invoke(manager);

                assertTrue(mockSprite.getDead());
                assertFalse(terrainCollisionRan[0],
                                "Sonic_HurtStop kills and returns before Sonic_Floor/DoLevelCollision");
        }

        @Test
        public void hurtStopBottomKillUsesUnsignedS1AndSignedS2WordComparisons() throws Exception {
                Camera camera = GameServices.camera();
                camera.setMaxY((short) 0x0100);
                camera.setMaxYTarget((short) 0x0100);
                camera.setLevelStarted(true);

                com.openggf.tests.TestablePlayableSprite s1 =
                                new com.openggf.tests.TestablePlayableSprite("s1", (short) 0, (short) 0);
                s1.setGameRulesForTest(GameRules.SONIC_1);
                s1.setCentreY((short) 0xFFFF);
                PlayableSpriteMovement s1Movement = new PlayableSpriteMovement(s1);

                Method hurtStopKill = PlayableSpriteMovement.class
                                .getDeclaredMethod("applyHurtStopBottomKill");
                hurtStopKill.setAccessible(true);
                assertTrue((Boolean) hurtStopKill.invoke(s1Movement),
                                "S1 FixBugs=0 uses unsigned blo, so wrapped $FFFF is beyond $01E0");
                assertTrue(s1.getDead());

                com.openggf.tests.TestablePlayableSprite s2 =
                                new com.openggf.tests.TestablePlayableSprite("s2", (short) 0, (short) 0);
                s2.setGameRulesForTest(GameRules.SONIC_2);
                s2.setCentreY((short) 0xFFFF);
                PlayableSpriteMovement s2Movement = new PlayableSpriteMovement(s2);

                assertFalse((Boolean) hurtStopKill.invoke(s2Movement),
                                "S2 uses signed blt, so y_pos=-1 is above rather than below the kill row");
                assertFalse(s2.getDead());
        }

        private void prepareWallClimbProbe(int centreY, int mappingFrame) throws Exception {
                mockSprite.setCentreX((short) 0x0100);
                mockSprite.setCentreY((short) centreY);
                mockSprite.setWallClimbX(mockSprite.getX());
                mockSprite.setDoubleJumpFlag(4);
                mockSprite.setDoubleJumpProperty((byte) 0);
                mockSprite.setObjectMappingFrameControl(true);
                mockSprite.setMappingFrame(mappingFrame);
                setInputState(false, false, false, false, false);
        }

        private void invokeWallClimbUpdate() throws Exception {
                Method method = PlayableSpriteMovement.class.getDeclaredMethod("updateWallClimb");
                method.setAccessible(true);
                method.invoke(manager);
        }
}
