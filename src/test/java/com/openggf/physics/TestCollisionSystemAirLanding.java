package com.openggf.physics;

import com.openggf.tests.TestEnvironment;
import com.openggf.game.session.SessionManager;
import com.openggf.game.GameModule;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.GroundMode;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.ShieldType;
import com.openggf.sprites.animation.SpriteAnimationEndAction;
import com.openggf.sprites.animation.SpriteAnimationScript;
import com.openggf.sprites.animation.SpriteAnimationSet;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.FullReset;
import com.openggf.tests.SingletonResetExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(SingletonResetExtension.class)
@FullReset
class TestCollisionSystemAirLanding {

    private GameModule previousModule;

    @BeforeEach
    void setUp() {
        previousModule = GameModuleRegistry.getCurrent();
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
        SessionManager.clear();
        TestEnvironment.activeGameplayMode();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        if (previousModule != null) {
            GameModuleRegistry.setCurrent(previousModule);
        } else {
            GameModuleRegistry.reset();
        }
    }

    @Test
    void thresholdedAirLandingIgnoresExactSurfaceContact() throws Exception {
        AbstractPlayableSprite sprite = newTestSprite();
        sprite.setAir(true);
        sprite.setXSpeed((short) 0x0397);
        sprite.setYSpeed((short) 0x04D0);
        sprite.setCentreX((short) 0x0A83);
        sprite.setCentreY((short) 0x0272);

        CollisionSystem collisionSystem = new CollisionSystem(new TerrainCollisionManager());
        AtomicBoolean landed = new AtomicBoolean(false);

        Method method = CollisionSystem.class.getDeclaredMethod(
                "doTerrainCollisionAir",
                AbstractPlayableSprite.class,
                SensorResult[].class,
                Consumer.class);
        method.setAccessible(true);
        method.invoke(collisionSystem, sprite, exactSurfaceContactResults(), landingHandler(landed));

        assertFalse(landed.get(), "Exact floor contact should not count as an air landing");
        assertTrue(sprite.getAir(), "Exact surface contact should leave the sprite airborne");
    }

    @Test
    void thresholdedAirLandingAcceptsNegativeSurfaceContact() throws Exception {
        AbstractPlayableSprite sprite = newTestSprite();
        sprite.setAir(true);
        sprite.setXSpeed((short) 0x0397);
        sprite.setYSpeed((short) 0x04D0);
        sprite.setCentreX((short) 0x0A83);
        sprite.setCentreY((short) 0x0272);

        CollisionSystem collisionSystem = new CollisionSystem(new TerrainCollisionManager());
        AtomicBoolean landed = new AtomicBoolean(false);

        Method method = CollisionSystem.class.getDeclaredMethod(
                "doTerrainCollisionAir",
                AbstractPlayableSprite.class,
                SensorResult[].class,
                Consumer.class);
        method.setAccessible(true);
        method.invoke(collisionSystem, sprite, negativeSurfaceContactResults(), landingHandler(landed));

        assertTrue(landed.get(), "Negative floor distance should count as an air landing");
        assertFalse(sprite.getAir(), "Landing handler should have cleared airborne state");
        assertEquals((byte) 0x08, sprite.getAngle(), "Landing should preserve the slope angle");
    }

    @Test
    void acceptedAirLandingPublishesTheProbePairThatProducedIt() throws Exception {
        AbstractPlayableSprite sprite = newTestSprite();
        sprite.setAir(true);
        sprite.setYSpeed((short) 0x04D0);

        CollisionSystem collisionSystem = new CollisionSystem(new TerrainCollisionManager());
        SensorResult[] landingResults = negativeSurfaceContactResults();
        AtomicReference<SensorResult[]> published = new AtomicReference<>();

        Method method = CollisionSystem.class.getDeclaredMethod(
                "doTerrainCollisionAir",
                AbstractPlayableSprite.class,
                SensorResult[].class,
                Consumer.class,
                Consumer.class);
        method.setAccessible(true);
        method.invoke(collisionSystem, sprite, landingResults,
                landingHandler(new AtomicBoolean()), (Consumer<SensorResult[]>) published::set);

        assertTrue(published.get() == landingResults,
                "Player-tail state must consume the original landing probes, not a post-seat rescan");
    }

    @Test
    void directAirLandingIgnoresExactSurfaceContact() throws Exception {
        AbstractPlayableSprite sprite = newTestSprite();
        sprite.setAir(true);
        sprite.setXSpeed((short) 0x0200);
        sprite.setYSpeed((short) 0x0300);
        sprite.setCentreX((short) 0x0200);
        sprite.setCentreY((short) 0x0100);

        CollisionSystem collisionSystem = new CollisionSystem(new TerrainCollisionManager());
        AtomicBoolean landed = new AtomicBoolean(false);

        Method method = CollisionSystem.class.getDeclaredMethod(
                "doTerrainCollisionAirDirect",
                AbstractPlayableSprite.class,
                SensorResult[].class,
                Consumer.class,
                boolean.class);
        method.setAccessible(true);
        method.invoke(collisionSystem, sprite, exactSurfaceContactResults(), landingHandler(landed), false);

        assertFalse(landed.get(), "Direct air floor checks should not land on exact surface contact");
        assertTrue(sprite.getAir(), "Exact surface contact should leave the sprite airborne");
    }

    @Test
    void staleObjectSupportDoesNotSuppressTerrainWalkOffWhenStatusOnObjectIsClear() {
        AbstractPlayableSprite sprite = newTestSprite();
        sprite.setAir(false);
        sprite.setOnObject(false);
        sprite.setPushing(true);
        sprite.setAngle((byte) 0x08);

        CollisionSystem collisionSystem = new CollisionSystem(new StubTerrainCollisionManager(null, null));
        collisionSystem.resolveGroundAttachment(sprite, 14, () -> true);

        assertTrue(sprite.getAir(), "Stale object-side support must still allow terrain walk-off once Status_OnObj is clear");
        assertFalse(sprite.getPushing(), "Walk-off should clear pushing just like the normal terrain path");
        assertEquals(0, sprite.getAngle() & 0xFF,
                "Player_Angle rounds an empty-floor flagged angle to the current cardinal quadrant");
    }

    @Test
    void groundedRollingWalkOffRestartsUnchangedRollAnimation() {
        AbstractPlayableSprite sprite = newTestSprite();
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(2, new SpriteAnimationScript(0,
                List.of(0x2E, 0x2F, 0x30, 0x31), SpriteAnimationEndAction.LOOP, 0));
        sprite.setAnimationSet(animations);
        sprite.setAnimationId(2);
        sprite.setRolling(true);
        sprite.setAir(false);

        sprite.getAnimationManager().update(0);
        sprite.getAnimationManager().update(1);
        assertEquals(0x2F, sprite.getMappingFrame(), "Fixture should begin partway through Roll2");

        CollisionSystem collisionSystem = new CollisionSystem(new StubTerrainCollisionManager(null, null));
        collisionSystem.resolveGroundAttachment(sprite, 14, () -> false);
        sprite.getAnimationManager().update(2);

        assertTrue(sprite.getAir(), "Missing terrain support should put the player in the air");
        assertEquals(2, sprite.getAnimationId(), "Walk-off should keep the selected rolling animation");
        assertEquals(0x2E, sprite.getMappingFrame(),
                "AnglePos must restart Roll2 by forcing a prev_anim mismatch");
    }

    @Test
    void groundedRunWalkOffPreservesUnchangedRunAnimationCadence() {
        AbstractPlayableSprite sprite = newTestSprite();
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(1, new SpriteAnimationScript(0,
                List.of(0x10, 0x11, 0x12, 0x13), SpriteAnimationEndAction.LOOP, 0));
        sprite.setAnimationSet(animations);
        sprite.setAnimationId(1);
        sprite.setAir(false);

        sprite.getAnimationManager().update(0);
        sprite.getAnimationManager().update(1);
        assertEquals(0x11, sprite.getMappingFrame(), "Fixture should begin partway through Run");

        CollisionSystem collisionSystem = new CollisionSystem(new StubTerrainCollisionManager(null, null));
        collisionSystem.resolveGroundAttachment(sprite, 14, () -> false);
        sprite.getAnimationManager().update(2);

        assertTrue(sprite.getAir(), "Missing terrain support should put the player in the air");
        assertEquals(1, sprite.getAnimationId(), "Walk-off should keep the selected Run animation");
        assertEquals(0x12, sprite.getMappingFrame(),
                "prev_anim=Run must preserve cadence when the selected raw animation is already Run");
    }

    @Test
    void staleStatusOnObjectDoesNotSuppressTerrainWalkOffWhenObjectSupportIsGone() {
        AbstractPlayableSprite sprite = newTestSprite();
        sprite.setAir(false);
        sprite.setOnObject(true);
        sprite.setPushing(true);
        sprite.setRolling(false);
        sprite.setYSpeed((short) 0);
        sprite.setGSpeed((short) 0x025A);

        CollisionSystem collisionSystem = new CollisionSystem(new StubTerrainCollisionManager(null, null));
        collisionSystem.resolveGroundAttachment(sprite, 14, () -> false);

        assertTrue(sprite.getAir(), "Stale Status_OnObj must not suppress Player_AnglePos walk-off");
        assertFalse(sprite.isOnObject(), "Stale object support should be cleared before terrain walk-off");
        assertFalse(sprite.getPushing(), "Player_AnglePos walk-off clears Status_Push");
        assertFalse(sprite.getRolling(), "Terrain walk-off should not become a vine/jump release");
        assertEquals((short) 0, sprite.getYSpeed(), "Walk-off preserves y_vel until gravity runs next frame");
        assertEquals((short) 0x025A, sprite.getGSpeed(), "Walk-off preserves the ground-speed path");
    }

    @Test
    void supportedOnObjectStillSkipsTerrainAttachment() {
        AbstractPlayableSprite sprite = newTestSprite();
        sprite.setAir(false);
        sprite.setOnObject(true);

        StubTerrainCollisionManager terrain = new StubTerrainCollisionManager(null, null);
        CollisionSystem collisionSystem = new CollisionSystem(terrain);
        collisionSystem.resolveGroundAttachment(sprite, 14, () -> true);

        assertFalse(sprite.getAir(), "Supported object riders should not be detached by terrain probes");
        assertEquals(0, terrain.probeCount, "Supported object riders should skip terrain attachment probes");
    }

    @Test
    void wallCeilingLandingRollResetPreservesCentreX() throws Exception {
        Sonic sprite = new Sonic("sonic", (short) 0, (short) 0);
        sprite.setAir(true);
        sprite.setGroundMode(GroundMode.RIGHTWALL);
        sprite.setRolling(true);
        sprite.setAnimationId(0x10);
        sprite.setCentreXPreserveSubpixel((short) 0x18C2);
        sprite.setCentreY((short) 0x0967);

        CollisionSystem collisionSystem = new CollisionSystem(new TerrainCollisionManager());
        Method method = CollisionSystem.class.getDeclaredMethod(
                "resetWallCeilingLandingState",
                AbstractPlayableSprite.class,
                int.class);
        method.setAccessible(true);
        method.invoke(collisionSystem, sprite, 0xA8);

        assertEquals(0x18C2, sprite.getCentreX() & 0xFFFF,
                "S3K Player_TouchFloor clears roll and adjusts y_pos, not x_pos, on wall landings");
        assertFalse(sprite.getRolling(), "Wall landing should still clear rolling");
        assertFalse(sprite.getAir(), "Wall landing should clear airborne state");
        assertEquals(0, sprite.getAnimationId(),
                "Sonic_ResetOnFloor publishes Walk on an angled ceiling/wall landing");
    }

    @Test
    void s3kObjectControlledAngledLandingPublishesWalkUnlessSpindashing() throws Exception {
        GameModuleRegistry.setCurrent(new Sonic3kGameModule());
        Sonic sprite = new Sonic("sonic", (short) 0, (short) 0);
        sprite.setAir(true);
        sprite.setObjectControlled(true);
        sprite.setAnimationId(2);

        CollisionSystem collisionSystem = new CollisionSystem(new TerrainCollisionManager());
        Method method = CollisionSystem.class.getDeclaredMethod(
                "resetWallCeilingLandingState",
                AbstractPlayableSprite.class,
                int.class);
        method.setAccessible(true);
        method.invoke(collisionSystem, sprite, 0x58);

        assertEquals(0, sprite.getAnimationId(),
                "S3K Player_TouchFloor_Check_Spindash owns Walk before the object-control cleanup gate");

        Sonic spindashingSprite = new Sonic("sonic", (short) 0, (short) 0);
        spindashingSprite.setAir(true);
        spindashingSprite.setObjectControlled(true);
        spindashingSprite.setAnimationId(2);
        spindashingSprite.setSpindash(true);
        method.invoke(collisionSystem, spindashingSprite, 0x58);

        assertEquals(2, spindashingSprite.getAnimationId(),
                "A live S3K spin_dash_flag skips the landing Walk publication");
    }

    @Test
    void s3kRollingAngledLandingPublishesWalkUnlessSpindashing() throws Exception {
        GameModuleRegistry.setCurrent(new Sonic3kGameModule());
        Sonic sprite = new Sonic("sonic", (short) 0, (short) 0);
        sprite.setAir(true);
        sprite.setGroundMode(GroundMode.RIGHTWALL);
        sprite.setRolling(true);
        sprite.setAnimationId(2);

        CollisionSystem collisionSystem = new CollisionSystem(new TerrainCollisionManager());
        Method method = CollisionSystem.class.getDeclaredMethod(
                "resetWallCeilingLandingState",
                AbstractPlayableSprite.class,
                int.class);
        method.setAccessible(true);
        method.invoke(collisionSystem, sprite, 0xA8);

        assertFalse(sprite.getRolling(), "Player_TouchFloor clears Status_Roll");
        assertEquals(0, sprite.getAnimationId(),
                "Player_TouchFloor_Check_Spindash and the rolling Player_TouchFloor path both publish Walk");
    }

    @Test
    void angledCeilingLandingRunsBubbleShieldBounceBeforeGroundSpeedSample() throws Exception {
        GameModuleRegistry.setCurrent(new Sonic3kGameModule());
        SessionManager.clear();
        TestEnvironment.activeGameplayMode();
        Sonic sprite = new Sonic("sonic", (short) 0x143C, (short) 0x05BA);
        sprite.setAir(true);
        sprite.setRolling(true);
        sprite.setDoubleJumpFlag(1);
        sprite.giveShield(ShieldType.BUBBLE);
        sprite.setXSpeed((short) 0x028B);
        sprite.setYSpeed((short) -0x03B8);

        CollisionSystem collisionSystem = new CollisionSystem(new TerrainCollisionManager());
        Method method = CollisionSystem.class.getDeclaredMethod(
                "doCeilingCollision",
                AbstractPlayableSprite.class,
                SensorResult[].class);
        method.setAccessible(true);
        method.invoke(collisionSystem, sprite, new SensorResult[] {
                new SensorResult((byte) 0xB8, (byte) -1, 14, Direction.UP),
                new SensorResult((byte) 0xFF, (byte) 2, 14, Direction.UP)
        });

        assertTrue(sprite.getAir(), "BubbleShield_Bounce must re-arm Status_InAir");
        assertTrue(sprite.getRolling(), "BubbleShield_Bounce must restore rolling radii/state");
        assertEquals(2, sprite.getAnimationId(),
                "BubbleShield_Bounce must overwrite Player_TouchFloor's Walk publication with Roll");
        assertEquals(0, sprite.getDoubleJumpFlag(),
                "Player_TouchFloor clears double_jump_flag after BubbleShield_Bounce returns");
        assertEquals((short) -0x04D0, sprite.getXSpeed());
        assertEquals((short) -0x0249, sprite.getYSpeed());
        assertEquals((short) 0x0249, sprite.getGSpeed(),
                "The angled-ceiling tail samples the post-bounce y_vel");
    }

    private static AbstractPlayableSprite newTestSprite() {
        return new AbstractPlayableSprite("sonic", (short) 0, (short) 0) {
            @Override
            protected void defineSpeeds() {
            }

            @Override
            protected void createSensorLines() {
            }

            @Override
            public void draw() {
            }
        };
    }

    private static SensorResult[] exactSurfaceContactResults() {
        return new SensorResult[] {
                new SensorResult((byte) 0x08, (byte) 0x00, 14, Direction.DOWN),
                new SensorResult((byte) 0xFF, (byte) 0x02, 164, Direction.DOWN)
        };
    }

    private static SensorResult[] negativeSurfaceContactResults() {
        return new SensorResult[] {
                new SensorResult((byte) 0x08, (byte) 0xFF, 14, Direction.DOWN),
                new SensorResult((byte) 0xFF, (byte) 0x02, 164, Direction.DOWN)
        };
    }

    private static Consumer<AbstractPlayableSprite> landingHandler(AtomicBoolean landed) {
        return sprite -> {
            landed.set(true);
            sprite.setAir(false);
        };
    }

    private static final class StubTerrainCollisionManager extends TerrainCollisionManager {
        private final SensorResult[] results;
        private int probeCount;

        private StubTerrainCollisionManager(SensorResult left, SensorResult right) {
            results = new SensorResult[] {left, right};
        }

        @Override
        public SensorResult[] getSensorResult(Sensor[] sensors) {
            probeCount++;
            return results;
        }
    }
}
