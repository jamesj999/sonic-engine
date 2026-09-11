package com.openggf.sprites.playable;

import com.openggf.tests.TestEnvironment;
import com.openggf.game.session.EngineServices;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.GameServices;
import com.openggf.game.InstaShieldHandle;
import com.openggf.game.PlayableEntity;
import com.openggf.game.PowerUpObject;
import com.openggf.game.PowerUpSpawner;
import com.openggf.game.ShieldType;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.level.objects.PerObjectRewindSnapshot;
import com.openggf.level.objects.PerObjectRewindSnapshot.PlayerRewindExtra;
import com.openggf.physics.Direction;
import com.openggf.sprites.managers.PlayableSpriteMovement;
import com.openggf.sprites.managers.PlayableSpriteAnimation;
import com.openggf.sprites.managers.SpindashDustController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused unit test for {@link AbstractPlayableSprite#captureRewindState()} /
 * {@link AbstractPlayableSprite#restoreRewindState(PerObjectRewindSnapshot)}.
 *
 * <p>Verifies that every field in {@link PlayerRewindExtra} round-trips through
 * a capture → reset → restore cycle. Private fields on
 * {@code AbstractPlayableSprite} are verified indirectly through the snapshot
 * record fields (and then confirmed via a second capture after restore).
 */
class TestAbstractPlayableSpriteRewindCapture {

    @BeforeEach
    void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
        TestEnvironment.activeGameplayMode();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        GameModuleRegistry.reset();
    }

    // -------------------------------------------------------------------------
    // Round-trip test: mutate every captured field, capture, reset, restore,
    // assert restored values match original sentinels.
    // -------------------------------------------------------------------------

    @Test
    void roundTripCoversFullPlayerSurface() {
        Sonic sonic = new Sonic("sonic", (short) 100, (short) 200);

        // ---- Mutate AbstractSprite base fields (use public API) ----
        sonic.setX((short) 0x1234);
        sonic.setY((short) 0x5678);
        sonic.setSubpixelRaw(0xABCD, 0xEF01);
        sonic.setWidth(24);
        sonic.setHeight(40);

        // ---- Mutate AbstractPlayableSprite protected fields ----
        sonic.gSpeed = (short) 0x123;
        sonic.xSpeed = (short) 0x456;
        sonic.ySpeed = (short) 0xFF80;
        sonic.jump = (short) 0x42;
        sonic.angle = (byte) 0x40;
        // statusTertiary is private — mutated via sentinel; verified via snapshot
        sonic.loopLowPlane = true;
        sonic.topSolidBit = (byte) 0x0E;
        sonic.lrbSolidBit = (byte) 0x0F;
        sonic.prePhysicsAir = true;
        sonic.prePhysicsAngle = (byte) 0x80;
        sonic.prePhysicsGSpeed = (short) 0x111;
        sonic.prePhysicsXSpeed = (short) 0x222;
        sonic.prePhysicsYSpeed = (short) 0x333;
        sonic.prePhysicsCentreX = (short) 0x444;
        sonic.prePhysicsCentreY = (short) 0x555;
        sonic.air = true;
        sonic.rolling = true;
        sonic.jumping = true;
        sonic.rollingJump = true;
        sonic.pinballMode = true;
        sonic.pinballSpeedLock = true;
        sonic.preserveRollingOnNextLanding = true;
        sonic.preserveRollingOnNextRollStop = true;
        sonic.objectPreservedRollBoostFollowup = true;
        sonic.objectPreservedRollWallProbe = true;
        sonic.objectPreservedRollVelocityCarry = true;
        sonic.tunnelMode = true;
        sonic.onObject = true;
        sonic.latchedSolidObjectId = 0xBEEF;
        sonic.slopeRepelJustSlipped = true;
        sonic.stickToConvex = true;
        sonic.sliding = true;
        sonic.pushing = true;
        sonic.skidding = true;
        sonic.skidDustTimer = 3;
        sonic.wallClimbX = (short) 0x99;
        sonic.rightWallPenetrationTimer = 7;
        sonic.balanceState = 2;
        sonic.springing = true;
        sonic.springingFrames = 5;
        sonic.dead = true;
        sonic.drowningDeath = true;
        sonic.drownPreDeathTimer = 60;
        sonic.hurt = true;
        sonic.captureOnObjectAtFrameStart();
        sonic.deathCountdown = 30;
        sonic.invulnerableFrames = 128;
        sonic.invincibleFrames = 64;
        sonic.spindash = true;
        sonic.spindashCounter = (short) 0x400;
        sonic.crouching = true;
        sonic.lookingUp = true;
        sonic.lookDelayCounter = (short) 0x78;
        sonic.doubleJumpFlag = 1;
        sonic.doubleJumpProperty = (byte) 0x80;
        sonic.shield = true;
        sonic.speedShoes = true;
        sonic.superSonic = true;
        sonic.forceInputRight = true;
        sonic.forcedInputMask = 0x10;
        sonic.forcedJumpPress = true;
        sonic.suppressNextJumpPress = true;
        sonic.deferredObjectControlRelease = true;
        sonic.controlLocked = true;
        sonic.moveLockTimer = 35;
        sonic.objectControlled = true;
        sonic.objectControlAllowsCpu = true;
        sonic.objectControlSuppressesMovement = true;
        sonic.objectControlReleasedFrame = 999;
        sonic.suppressAirCollision = true;
        sonic.suppressGroundWallCollision = true;
        sonic.forceFloorCheck = true;
        sonic.suppressNextObjectMoveAndFall();
        sonic.hidden = true;
        sonic.setNativeSlotPresent(false);
        sonic.setRenderFlagOnScreen(false);
        sonic.controller.restoreSpringHandoff(true, 0x300, 0x200);
        sonic.jumpInputPressed = true;
        sonic.jumpInputJustPressed = true;
        sonic.jumpInputPressedPreviousFrame = true;
        sonic.upInputPressed = true;
        sonic.downInputPressed = true;
        sonic.leftInputPressed = true;
        sonic.rightInputPressed = true;
        sonic.movementInputActive = true;
        sonic.inWater = true;
        sonic.waterPhysicsActive = true;
        sonic.wasInWater = true;
        sonic.waterSkimActive = true;
        sonic.preventTailsRespawn = true;
        sonic.setMappingFrame(17);
        sonic.setAnimationId(8);
        sonic.setForcedAnimationId(11);
        sonic.setAnimationFrameIndex(3);
        sonic.setAnimationTick(6);
        sonic.setFlipType(0x84);

        // ---- Capture ----
        PerObjectRewindSnapshot snap1 = sonic.captureRewindState();
        assertNotNull(snap1, "captureRewindState must return non-null");
        assertNotNull(snap1.playerExtra(), "PlayerRewindExtra must be populated");

        PlayerRewindExtra e1 = snap1.playerExtra();

        // ---- Verify sentinel values appear in the snapshot ----
        assertEquals(0x1234, e1.xPixel(), "xPixel mismatch in snapshot");
        assertEquals(0x5678, e1.yPixel(), "yPixel mismatch in snapshot");
        assertEquals((short) 0xABCD, e1.xSubpixel(), "xSubpixel mismatch");
        assertEquals((short) 0xEF01, e1.ySubpixel(), "ySubpixel mismatch");
        assertEquals(24, e1.width(), "width mismatch");
        assertEquals(40, e1.height(), "height mismatch");
        assertEquals((short) 0x123, e1.gSpeed(), "gSpeed mismatch");
        assertEquals((short) 0x456, e1.xSpeed(), "xSpeed mismatch");
        assertEquals((short) 0xFF80, e1.ySpeed(), "ySpeed mismatch");
        assertEquals((short) 0x42, e1.jump(), "jump mismatch");
        assertEquals((byte) 0x40, e1.angle(), "angle mismatch");
        assertTrue(e1.loopLowPlane(), "loopLowPlane mismatch");
        assertEquals((byte) 0x0E, e1.topSolidBit(), "topSolidBit mismatch");
        assertEquals((byte) 0x0F, e1.lrbSolidBit(), "lrbSolidBit mismatch");
        assertTrue(e1.prePhysicsAir(), "prePhysicsAir mismatch");
        assertEquals((byte) 0x80, e1.prePhysicsAngle(), "prePhysicsAngle mismatch");
        assertEquals((short) 0x111, e1.prePhysicsGSpeed(), "prePhysicsGSpeed mismatch");
        assertEquals((short) 0x222, e1.prePhysicsXSpeed(), "prePhysicsXSpeed mismatch");
        assertEquals((short) 0x333, e1.prePhysicsYSpeed(), "prePhysicsYSpeed mismatch");
        assertEquals((short) 0x444, e1.prePhysicsCentreX(), "prePhysicsCentreX mismatch");
        assertEquals((short) 0x555, e1.prePhysicsCentreY(), "prePhysicsCentreY mismatch");
        assertTrue(e1.air(), "air mismatch");
        assertTrue(e1.rolling(), "rolling mismatch");
        assertTrue(e1.jumping(), "jumping mismatch");
        assertTrue(e1.rollingJump(), "rollingJump mismatch");
        assertTrue(e1.pinballMode(), "pinballMode mismatch");
        assertTrue(e1.pinballSpeedLock(), "pinballSpeedLock mismatch");
        assertTrue(e1.preserveRollingOnNextLanding(), "preserveRollingOnNextLanding mismatch");
        assertTrue(e1.preserveRollingOnNextRollStop(), "preserveRollingOnNextRollStop mismatch");
        assertTrue(e1.objectPreservedRollBoostFollowup(), "objectPreservedRollBoostFollowup mismatch");
        assertTrue(e1.objectPreservedRollWallProbe(), "objectPreservedRollWallProbe mismatch");
        assertTrue(e1.objectPreservedRollVelocityCarry(), "objectPreservedRollVelocityCarry mismatch");
        assertTrue(e1.tunnelMode(), "tunnelMode mismatch");
        assertTrue(e1.onObject(), "onObject mismatch");
        assertTrue(e1.hurtAtFrameStart(), "hurtAtFrameStart mismatch");
        assertFalse(e1.hurtRecoveryCompletedThisFrame(), "hurtRecoveryCompletedThisFrame mismatch");
        assertEquals(0xBEEF, e1.latchedSolidObjectId(), "latchedSolidObjectId mismatch");
        assertTrue(e1.slopeRepelJustSlipped(), "slopeRepelJustSlipped mismatch");
        assertTrue(e1.stickToConvex(), "stickToConvex mismatch");
        assertTrue(e1.sliding(), "sliding mismatch");
        assertTrue(e1.pushing(), "pushing mismatch");
        assertTrue(e1.skidding(), "skidding mismatch");
        assertEquals(3, e1.skidDustTimer(), "skidDustTimer mismatch");
        assertEquals((short) 0x99, e1.wallClimbX(), "wallClimbX mismatch");
        assertEquals(7, e1.rightWallPenetrationTimer(), "rightWallPenetrationTimer mismatch");
        assertEquals(2, e1.balanceState(), "balanceState mismatch");
        assertTrue(e1.springing(), "springing mismatch");
        assertEquals(5, e1.springingFrames(), "springingFrames mismatch");
        assertTrue(e1.dead(), "dead mismatch");
        assertTrue(e1.drowningDeath(), "drowningDeath mismatch");
        assertEquals(60, e1.drownPreDeathTimer(), "drownPreDeathTimer mismatch");
        assertTrue(e1.hurt(), "hurt mismatch");
        assertEquals(30, e1.deathCountdown(), "deathCountdown mismatch");
        assertEquals(128, e1.invulnerableFrames(), "invulnerableFrames mismatch");
        assertEquals(64, e1.invincibleFrames(), "invincibleFrames mismatch");
        assertTrue(e1.spindash(), "spindash mismatch");
        assertEquals((short) 0x400, e1.spindashCounter(), "spindashCounter mismatch");
        assertTrue(e1.crouching(), "crouching mismatch");
        assertTrue(e1.lookingUp(), "lookingUp mismatch");
        assertEquals((short) 0x78, e1.lookDelayCounter(), "lookDelayCounter mismatch");
        assertEquals(1, e1.doubleJumpFlag(), "doubleJumpFlag mismatch");
        assertEquals((byte) 0x80, e1.doubleJumpProperty(), "doubleJumpProperty mismatch");
        assertTrue(e1.shield(), "shield mismatch");
        assertTrue(e1.speedShoes(), "speedShoes mismatch");
        assertTrue(e1.superSonic(), "superSonic mismatch");
        assertTrue(e1.forceInputRight(), "forceInputRight mismatch");
        assertEquals(0x10, e1.forcedInputMask(), "forcedInputMask mismatch");
        assertTrue(e1.forcedJumpPress(), "forcedJumpPress mismatch");
        assertTrue(e1.suppressNextJumpPress(), "suppressNextJumpPress mismatch");
        assertTrue(e1.deferredObjectControlRelease(), "deferredObjectControlRelease mismatch");
        assertTrue(e1.controlLocked(), "controlLocked mismatch");
        assertEquals(35, e1.moveLockTimer(), "moveLockTimer mismatch");
        assertTrue(e1.objectControlled(), "objectControlled mismatch");
        assertTrue(e1.objectControlAllowsCpu(), "objectControlAllowsCpu mismatch");
        assertTrue(e1.objectControlSuppressesMovement(), "objectControlSuppressesMovement mismatch");
        assertEquals(999, e1.objectControlReleasedFrame(), "objectControlReleasedFrame mismatch");
        assertTrue(e1.suppressAirCollision(), "suppressAirCollision mismatch");
        assertTrue(e1.suppressGroundWallCollision(), "suppressGroundWallCollision mismatch");
        assertTrue(e1.forceFloorCheck(), "forceFloorCheck mismatch");
        assertEquals(0x3, e1.suppressedObjectMoveAndFallAxes(), "suppressedObjectMoveAndFallAxes mismatch");
        assertTrue(e1.hidden(), "hidden mismatch");
        assertFalse(e1.nativeSlotPresent(), "nativeSlotPresent mismatch");
        assertFalse(e1.renderFlagOnScreen(), "renderFlagOnScreen mismatch");
        assertTrue(e1.renderFlagOnScreenValid(), "renderFlagOnScreenValid mismatch");
        assertTrue(e1.mgzTopPlatformSpringHandoffPending(), "mgzTopPlatformSpringHandoffPending mismatch");
        assertEquals(0x300, e1.mgzTopPlatformSpringHandoffXVel(), "mgzTopPlatformSpringHandoffXVel mismatch");
        assertEquals(0x200, e1.mgzTopPlatformSpringHandoffYVel(), "mgzTopPlatformSpringHandoffYVel mismatch");
        assertTrue(e1.jumpInputPressed(), "jumpInputPressed mismatch");
        assertTrue(e1.jumpInputJustPressed(), "jumpInputJustPressed mismatch");
        assertTrue(e1.jumpInputPressedPreviousFrame(), "jumpInputPressedPreviousFrame mismatch");
        assertTrue(e1.upInputPressed(), "upInputPressed mismatch");
        assertTrue(e1.downInputPressed(), "downInputPressed mismatch");
        assertTrue(e1.leftInputPressed(), "leftInputPressed mismatch");
        assertTrue(e1.rightInputPressed(), "rightInputPressed mismatch");
        assertTrue(e1.movementInputActive(), "movementInputActive mismatch");
        assertTrue(e1.inWater(), "inWater mismatch");
        assertTrue(e1.waterPhysicsActive(), "waterPhysicsActive mismatch");
        assertTrue(e1.wasInWater(), "wasInWater mismatch");
        assertTrue(e1.waterSkimActive(), "waterSkimActive mismatch");
        assertTrue(e1.preventTailsRespawn(), "preventTailsRespawn mismatch");
        assertEquals((byte) 0x84, e1.flipType(), "flipType mismatch");

        // ---- Reset all mutated fields to defaults ----
        sonic.setX((short) 0);
        sonic.setY((short) 0);
        sonic.setSubpixelRaw(0, 0);
        sonic.setWidth(20);
        sonic.setHeight(19); // runHeight default
        sonic.gSpeed = 0;
        sonic.xSpeed = 0;
        sonic.ySpeed = 0;
        sonic.jump = 0;
        sonic.angle = 0;
        sonic.loopLowPlane = false;
        sonic.topSolidBit = 0x0C;
        sonic.lrbSolidBit = 0x0D;
        sonic.prePhysicsAir = false;
        sonic.prePhysicsAngle = 0;
        sonic.prePhysicsGSpeed = 0;
        sonic.prePhysicsXSpeed = 0;
        sonic.prePhysicsYSpeed = 0;
        sonic.air = false;
        sonic.rolling = false;
        sonic.jumping = false;
        sonic.rollingJump = false;
        sonic.pinballMode = false;
        sonic.pinballSpeedLock = false;
        sonic.preserveRollingOnNextLanding = false;
        sonic.preserveRollingOnNextRollStop = false;
        sonic.objectPreservedRollBoostFollowup = false;
        sonic.objectPreservedRollWallProbe = false;
        sonic.objectPreservedRollVelocityCarry = false;
        sonic.tunnelMode = false;
        sonic.onObject = false;
        sonic.latchedSolidObjectId = 0;
        sonic.slopeRepelJustSlipped = false;
        sonic.stickToConvex = false;
        sonic.sliding = false;
        sonic.pushing = false;
        sonic.skidding = false;
        sonic.skidDustTimer = 0;
        sonic.wallClimbX = 0;
        sonic.rightWallPenetrationTimer = 0;
        sonic.balanceState = 0;
        sonic.springing = false;
        sonic.springingFrames = 0;
        sonic.dead = false;
        sonic.drowningDeath = false;
        sonic.drownPreDeathTimer = 0;
        sonic.hurt = false;
        sonic.deathCountdown = 0;
        sonic.invulnerableFrames = 0;
        sonic.invincibleFrames = 0;
        sonic.spindash = false;
        sonic.spindashCounter = 0;
        sonic.crouching = false;
        sonic.lookingUp = false;
        sonic.lookDelayCounter = 0;
        sonic.doubleJumpFlag = 0;
        sonic.doubleJumpProperty = 0;
        sonic.shield = false;
        sonic.speedShoes = false;
        sonic.superSonic = false;
        sonic.forceInputRight = false;
        sonic.forcedInputMask = 0;
        sonic.forcedJumpPress = false;
        sonic.suppressNextJumpPress = false;
        sonic.deferredObjectControlRelease = false;
        sonic.controlLocked = false;
        sonic.moveLockTimer = 0;
        sonic.objectControlled = false;
        sonic.objectControlAllowsCpu = false;
        sonic.objectControlSuppressesMovement = false;
        sonic.objectControlReleasedFrame = Integer.MIN_VALUE;
        sonic.suppressAirCollision = false;
        sonic.suppressGroundWallCollision = false;
        sonic.forceFloorCheck = false;
        sonic.hidden = false;
        sonic.setNativeSlotPresent(true);
        sonic.setRenderFlagOnScreen(true);
        sonic.controller.clearSpringHandoff();
        sonic.jumpInputPressed = false;
        sonic.jumpInputJustPressed = false;
        sonic.jumpInputPressedPreviousFrame = false;
        sonic.upInputPressed = false;
        sonic.downInputPressed = false;
        sonic.leftInputPressed = false;
        sonic.rightInputPressed = false;
        sonic.movementInputActive = false;
        sonic.inWater = false;
        sonic.waterPhysicsActive = false;
        sonic.wasInWater = false;
        sonic.waterSkimActive = false;
        sonic.preventTailsRespawn = false;
        sonic.setMappingFrame(0);
        sonic.setAnimationId(0);
        sonic.setForcedAnimationId(-1);
        sonic.setAnimationFrameIndex(0);
        sonic.setAnimationTick(0);
        sonic.setFlipType(0);

        // ---- Restore from snap1 ----
        sonic.restoreRewindState(snap1);

        // ---- Verify restored values via a second capture ----
        // This approach also validates private fields (statusTertiary, historyPos,
        // cpuControlled, etc.) because their defaults are preserved through reset
        // and the restored snapshot must equal the original.
        PerObjectRewindSnapshot snap2 = sonic.captureRewindState();
        assertNotNull(snap2.playerExtra(), "snap2 must have playerExtra after restore");
        PlayerRewindExtra e2 = snap2.playerExtra();

        // Verify all fields match the original sentinels
        assertEquals(e1.xPixel(), e2.xPixel(), "xPixel not restored");
        assertEquals(e1.yPixel(), e2.yPixel(), "yPixel not restored");
        assertEquals(e1.xSubpixel(), e2.xSubpixel(), "xSubpixel not restored");
        assertEquals(e1.ySubpixel(), e2.ySubpixel(), "ySubpixel not restored");
        assertEquals(e1.width(), e2.width(), "width not restored");
        assertEquals(e1.height(), e2.height(), "height not restored");
        assertEquals(e1.gSpeed(), e2.gSpeed(), "gSpeed not restored");
        assertEquals(e1.xSpeed(), e2.xSpeed(), "xSpeed not restored");
        assertEquals(e1.ySpeed(), e2.ySpeed(), "ySpeed not restored");
        assertEquals(e1.jump(), e2.jump(), "jump not restored");
        assertEquals(e1.angle(), e2.angle(), "angle not restored");
        assertEquals(e1.statusTertiary(), e2.statusTertiary(), "statusTertiary not restored");
        assertEquals(e1.loopLowPlane(), e2.loopLowPlane(), "loopLowPlane not restored");
        assertEquals(e1.topSolidBit(), e2.topSolidBit(), "topSolidBit not restored");
        assertEquals(e1.lrbSolidBit(), e2.lrbSolidBit(), "lrbSolidBit not restored");
        assertEquals(e1.prePhysicsAir(), e2.prePhysicsAir(), "prePhysicsAir not restored");
        assertEquals(e1.prePhysicsAngle(), e2.prePhysicsAngle(), "prePhysicsAngle not restored");
        assertEquals(e1.prePhysicsGSpeed(), e2.prePhysicsGSpeed(), "prePhysicsGSpeed not restored");
        assertEquals(e1.prePhysicsXSpeed(), e2.prePhysicsXSpeed(), "prePhysicsXSpeed not restored");
        assertEquals(e1.prePhysicsYSpeed(), e2.prePhysicsYSpeed(), "prePhysicsYSpeed not restored");
        assertEquals(e1.prePhysicsCentreX(), e2.prePhysicsCentreX(), "prePhysicsCentreX not restored");
        assertEquals(e1.prePhysicsCentreY(), e2.prePhysicsCentreY(), "prePhysicsCentreY not restored");
        assertEquals(e1.air(), e2.air(), "air not restored");
        assertEquals(e1.rolling(), e2.rolling(), "rolling not restored");
        assertEquals(e1.jumping(), e2.jumping(), "jumping not restored");
        assertEquals(e1.rollingJump(), e2.rollingJump(), "rollingJump not restored");
        assertEquals(e1.pinballMode(), e2.pinballMode(), "pinballMode not restored");
        assertEquals(e1.pinballSpeedLock(), e2.pinballSpeedLock(), "pinballSpeedLock not restored");
        assertEquals(e1.preserveRollingOnNextLanding(), e2.preserveRollingOnNextLanding(), "preserveRollingOnNextLanding not restored");
        assertEquals(e1.preserveRollingOnNextRollStop(), e2.preserveRollingOnNextRollStop(), "preserveRollingOnNextRollStop not restored");
        assertEquals(e1.objectPreservedRollBoostFollowup(), e2.objectPreservedRollBoostFollowup(), "objectPreservedRollBoostFollowup not restored");
        assertEquals(e1.objectPreservedRollWallProbe(), e2.objectPreservedRollWallProbe(), "objectPreservedRollWallProbe not restored");
        assertEquals(e1.objectPreservedRollVelocityCarry(), e2.objectPreservedRollVelocityCarry(), "objectPreservedRollVelocityCarry not restored");
        assertEquals(e1.tunnelMode(), e2.tunnelMode(), "tunnelMode not restored");
        assertEquals(e1.onObject(), e2.onObject(), "onObject not restored");
        assertEquals(e1.onObjectAtFrameStart(), e2.onObjectAtFrameStart(), "onObjectAtFrameStart not restored");
        assertEquals(e1.onObjectAtPreviousFrameStart(), e2.onObjectAtPreviousFrameStart(),
                "onObjectAtPreviousFrameStart not restored");
        assertEquals(e1.pushingAtFrameStart(), e2.pushingAtFrameStart(), "pushingAtFrameStart not restored");
        assertEquals(e1.hurtAtFrameStart(), e2.hurtAtFrameStart(), "hurtAtFrameStart not restored");
        assertEquals(e1.hurtRecoveryCompletedThisFrame(), e2.hurtRecoveryCompletedThisFrame(),
                "hurtRecoveryCompletedThisFrame not restored");
        assertEquals(e1.latchedSolidObjectId(), e2.latchedSolidObjectId(), "latchedSolidObjectId not restored");
        assertEquals(e1.slopeRepelJustSlipped(), e2.slopeRepelJustSlipped(), "slopeRepelJustSlipped not restored");
        assertEquals(e1.stickToConvex(), e2.stickToConvex(), "stickToConvex not restored");
        assertEquals(e1.sliding(), e2.sliding(), "sliding not restored");
        assertEquals(e1.pushing(), e2.pushing(), "pushing not restored");
        assertEquals(e1.skidding(), e2.skidding(), "skidding not restored");
        assertEquals(e1.skidDustTimer(), e2.skidDustTimer(), "skidDustTimer not restored");
        assertEquals(e1.wallClimbX(), e2.wallClimbX(), "wallClimbX not restored");
        assertEquals(e1.rightWallPenetrationTimer(), e2.rightWallPenetrationTimer(), "rightWallPenetrationTimer not restored");
        assertEquals(e1.balanceState(), e2.balanceState(), "balanceState not restored");
        assertEquals(e1.springing(), e2.springing(), "springing not restored");
        assertEquals(e1.springingFrames(), e2.springingFrames(), "springingFrames not restored");
        assertEquals(e1.dead(), e2.dead(), "dead not restored");
        assertEquals(e1.drowningDeath(), e2.drowningDeath(), "drowningDeath not restored");
        assertEquals(e1.drownPreDeathTimer(), e2.drownPreDeathTimer(), "drownPreDeathTimer not restored");
        assertEquals(e1.hurt(), e2.hurt(), "hurt not restored");
        assertEquals(e1.deathCountdown(), e2.deathCountdown(), "deathCountdown not restored");
        assertEquals(e1.invulnerableFrames(), e2.invulnerableFrames(), "invulnerableFrames not restored");
        assertEquals(e1.invincibleFrames(), e2.invincibleFrames(), "invincibleFrames not restored");
        assertEquals(e1.spindash(), e2.spindash(), "spindash not restored");
        assertEquals(e1.spindashCounter(), e2.spindashCounter(), "spindashCounter not restored");
        assertEquals(e1.crouching(), e2.crouching(), "crouching not restored");
        assertEquals(e1.lookingUp(), e2.lookingUp(), "lookingUp not restored");
        assertEquals(e1.lookDelayCounter(), e2.lookDelayCounter(), "lookDelayCounter not restored");
        assertEquals(e1.doubleJumpFlag(), e2.doubleJumpFlag(), "doubleJumpFlag not restored");
        assertEquals(e1.doubleJumpProperty(), e2.doubleJumpProperty(), "doubleJumpProperty not restored");
        assertEquals(e1.shield(), e2.shield(), "shield not restored");
        assertEquals(e1.instaShieldRegistered(), e2.instaShieldRegistered(), "instaShieldRegistered not restored");
        assertEquals(e1.speedShoes(), e2.speedShoes(), "speedShoes not restored");
        assertEquals(e1.superSonic(), e2.superSonic(), "superSonic not restored");
        assertEquals(e1.forceInputRight(), e2.forceInputRight(), "forceInputRight not restored");
        assertEquals(e1.forcedInputMask(), e2.forcedInputMask(), "forcedInputMask not restored");
        assertEquals(e1.forcedJumpPress(), e2.forcedJumpPress(), "forcedJumpPress not restored");
        assertEquals(e1.suppressNextJumpPress(), e2.suppressNextJumpPress(), "suppressNextJumpPress not restored");
        assertEquals(e1.deferredObjectControlRelease(), e2.deferredObjectControlRelease(), "deferredObjectControlRelease not restored");
        assertEquals(e1.controlLocked(), e2.controlLocked(), "controlLocked not restored");
        assertEquals(e1.hasQueuedControlLockedState(), e2.hasQueuedControlLockedState(), "hasQueuedControlLockedState not restored");
        assertEquals(e1.queuedControlLocked(), e2.queuedControlLocked(), "queuedControlLocked not restored");
        assertEquals(e1.hasQueuedForceInputRightState(), e2.hasQueuedForceInputRightState(), "hasQueuedForceInputRightState not restored");
        assertEquals(e1.queuedForceInputRight(), e2.queuedForceInputRight(), "queuedForceInputRight not restored");
        assertEquals(e1.moveLockTimer(), e2.moveLockTimer(), "moveLockTimer not restored");
        assertEquals(e1.objectControlled(), e2.objectControlled(), "objectControlled not restored");
        assertEquals(e1.objectControlAllowsCpu(), e2.objectControlAllowsCpu(), "objectControlAllowsCpu not restored");
        assertEquals(e1.objectControlSuppressesMovement(), e2.objectControlSuppressesMovement(), "objectControlSuppressesMovement not restored");
        assertEquals(e1.objectControlReleasedFrame(), e2.objectControlReleasedFrame(), "objectControlReleasedFrame not restored");
        assertEquals(e1.suppressAirCollision(), e2.suppressAirCollision(), "suppressAirCollision not restored");
        assertEquals(e1.suppressGroundWallCollision(), e2.suppressGroundWallCollision(), "suppressGroundWallCollision not restored");
        assertEquals(e1.forceFloorCheck(), e2.forceFloorCheck(), "forceFloorCheck not restored");
        assertEquals(e1.suppressedObjectMoveAndFallAxes(), e2.suppressedObjectMoveAndFallAxes(), "suppressedObjectMoveAndFallAxes not restored");
        assertEquals(e1.hidden(), e2.hidden(), "hidden not restored");
        assertEquals(e1.nativeSlotPresent(), e2.nativeSlotPresent(), "nativeSlotPresent not restored");
        assertEquals(e1.renderFlagOnScreen(), e2.renderFlagOnScreen(), "renderFlagOnScreen not restored");
        assertEquals(e1.renderFlagOnScreenValid(), e2.renderFlagOnScreenValid(), "renderFlagOnScreenValid not restored");
        assertEquals(e1.mgzTopPlatformSpringHandoffPending(), e2.mgzTopPlatformSpringHandoffPending(), "mgzTopPlatformSpringHandoffPending not restored");
        assertEquals(e1.mgzTopPlatformSpringHandoffXVel(), e2.mgzTopPlatformSpringHandoffXVel(), "mgzTopPlatformSpringHandoffXVel not restored");
        assertEquals(e1.mgzTopPlatformSpringHandoffYVel(), e2.mgzTopPlatformSpringHandoffYVel(), "mgzTopPlatformSpringHandoffYVel not restored");
        assertEquals(e1.jumpInputPressed(), e2.jumpInputPressed(), "jumpInputPressed not restored");
        assertEquals(e1.jumpInputJustPressed(), e2.jumpInputJustPressed(), "jumpInputJustPressed not restored");
        assertEquals(e1.jumpInputPressedPreviousFrame(), e2.jumpInputPressedPreviousFrame(), "jumpInputPressedPreviousFrame not restored");
        assertEquals(e1.upInputPressed(), e2.upInputPressed(), "upInputPressed not restored");
        assertEquals(e1.downInputPressed(), e2.downInputPressed(), "downInputPressed not restored");
        assertEquals(e1.leftInputPressed(), e2.leftInputPressed(), "leftInputPressed not restored");
        assertEquals(e1.rightInputPressed(), e2.rightInputPressed(), "rightInputPressed not restored");
        assertEquals(e1.movementInputActive(), e2.movementInputActive(), "movementInputActive not restored");
        assertEquals(e1.logicalInputState(), e2.logicalInputState(), "logicalInputState not restored");
        assertEquals(e1.logicalJumpPressState(), e2.logicalJumpPressState(), "logicalJumpPressState not restored");
        assertEquals(e1.cpuControlled(), e2.cpuControlled(), "cpuControlled not restored");
        assertEquals(e1.historyPos(), e2.historyPos(), "historyPos not restored");
        assertEquals(e1.followerHistoryRecordedThisTick(), e2.followerHistoryRecordedThisTick(), "followerHistoryRecordedThisTick not restored");
        assertEquals(e1.spiralActiveFrame(), e2.spiralActiveFrame(), "spiralActiveFrame not restored");
        assertEquals(e1.flipAngle(), e2.flipAngle(), "flipAngle not restored");
        assertEquals(e1.flipType(), e2.flipType(), "flipType not restored");
        assertEquals(e1.flipSpeed(), e2.flipSpeed(), "flipSpeed not restored");
        assertEquals(e1.flipsRemaining(), e2.flipsRemaining(), "flipsRemaining not restored");
        assertEquals(e1.flipTurned(), e2.flipTurned(), "flipTurned not restored");
        assertEquals(e1.inWater(), e2.inWater(), "inWater not restored");
        assertEquals(e1.waterPhysicsActive(), e2.waterPhysicsActive(), "waterPhysicsActive not restored");
        assertEquals(e1.wasInWater(), e2.wasInWater(), "wasInWater not restored");
        assertEquals(e1.waterSkimActive(), e2.waterSkimActive(), "waterSkimActive not restored");
        assertEquals(e1.preventTailsRespawn(), e2.preventTailsRespawn(), "preventTailsRespawn not restored");
        assertEquals(e1.badnikChainCounter(), e2.badnikChainCounter(), "badnikChainCounter not restored");
        assertEquals(e1.bubbleAnimId(), e2.bubbleAnimId(), "bubbleAnimId not restored");
        assertEquals(e1.initPhysicsActive(), e2.initPhysicsActive(), "initPhysicsActive not restored");
        assertEquals(e1.objectMappingFrameControl(), e2.objectMappingFrameControl(), "objectMappingFrameControl not restored");
        assertEquals(e1.mappingFrame(), e2.mappingFrame(), "mappingFrame not restored");
        assertEquals(e1.animationId(), e2.animationId(), "animationId not restored");
        assertEquals(e1.forcedAnimationId(), e2.forcedAnimationId(), "forcedAnimationId not restored");
        assertEquals(e1.animationFrameIndex(), e2.animationFrameIndex(), "animationFrameIndex not restored");
        assertEquals(e1.animationTick(), e2.animationTick(), "animationTick not restored");
    }

    // -------------------------------------------------------------------------
    // Additional test: protected field values are directly visible after restore
    // -------------------------------------------------------------------------

    @Test
    void protectedFieldsAreDirectlyVerifiableAfterRestore() {
        Sonic sonic = new Sonic("sonic", (short) 0, (short) 0);

        sonic.gSpeed = (short) 0x300;
        sonic.xSpeed = (short) 0x100;
        sonic.ySpeed = (short) 0x200;
        sonic.air = true;
        sonic.rolling = true;
        sonic.angle = (byte) 0x20;

        PerObjectRewindSnapshot snap = sonic.captureRewindState();

        sonic.gSpeed = 0;
        sonic.xSpeed = 0;
        sonic.ySpeed = 0;
        sonic.air = false;
        sonic.rolling = false;
        sonic.angle = 0;

        sonic.restoreRewindState(snap);

        assertEquals((short) 0x300, sonic.gSpeed, "gSpeed not restored directly");
        assertEquals((short) 0x100, sonic.xSpeed, "xSpeed not restored directly");
        assertEquals((short) 0x200, sonic.ySpeed, "ySpeed not restored directly");
        assertTrue(sonic.air, "air not restored directly");
        assertTrue(sonic.rolling, "rolling not restored directly");
        assertEquals((byte) 0x20, sonic.angle, "angle not restored directly");
    }

    @Test
    void restoreRewindStateRestoresPresentedRenderFlip() {
        Sonic sonic = new Sonic("sonic", (short) 0, (short) 0);
        sonic.setDirection(Direction.LEFT);
        sonic.setRenderFlips(true, false);

        PerObjectRewindSnapshot snap = sonic.captureRewindState();

        sonic.setDirection(Direction.RIGHT);
        sonic.setRenderFlips(false, false);

        sonic.restoreRewindState(snap);

        assertEquals(Direction.LEFT, sonic.getDirection(), "gameplay facing direction not restored");
        assertTrue(sonic.getRenderHFlip(), "render h-flip must match the restored rewind frame");
        assertFalse(sonic.getRenderVFlip(), "render v-flip must match the restored rewind frame");
    }

    @Test
    void compactCaptureCanOmitFollowHistoryWhenNoSidekickDependsOnIt() {
        Sonic sonic = new Sonic("sonic", (short) 0, (short) 0);

        PlayerRewindExtra compact = sonic.captureRewindState(false).playerExtra();
        PlayerRewindExtra full = sonic.captureRewindState(true).playerExtra();

        assertNull(compact.xHistory());
        assertNull(compact.yHistory());
        assertNull(compact.inputHistory());
        assertNull(compact.jumpPressHistory());
        assertNull(compact.statusHistory());
        assertNotNull(full.xHistory());
        assertNotNull(full.yHistory());
        assertNotNull(full.inputHistory());
        assertNotNull(full.jumpPressHistory());
        assertNotNull(full.statusHistory());
    }

    @Test
    void roundTripRestoresSpindashControllerState() throws Exception {
        Sonic sonic = new Sonic("sonic", (short) 100, (short) 200);
        PlayableSpriteMovement movement = (PlayableSpriteMovement) sonic.getMovementManager();
        SpindashDustController dust = new SpindashDustController(sonic, null);
        sonic.setSpindashDustController(dust);

        setBooleanField(movement, "jumpPressed", true);
        setBooleanField(movement, "jumpPrevious", true);
        setBooleanField(movement, "jumpReleasedSinceJump", true);
        setBooleanField(movement, "testKeyPressed", true);
        setBooleanField(movement, "inputDown", true);
        setBooleanField(movement, "inputJump", true);
        setBooleanField(movement, "inputJumpPress", true);
        setBooleanField(movement, "facingFlipForcesPushClearAfterGroundWall", true);
        setBooleanField(movement, "wasCrouching", true);
        setIntField(dust, "frameIndex", 4);
        setIntField(dust, "frameTick", 1);
        setIntField(dust, "currentFrame", 0x0E);
        setBooleanField(dust, "activeLastTick", true);

        PerObjectRewindSnapshot snapshot = sonic.captureRewindState();

        movement.resetTransientState();
        setIntField(dust, "frameIndex", 0);
        setIntField(dust, "frameTick", 0);
        setIntField(dust, "currentFrame", 0x0A);
        setBooleanField(dust, "activeLastTick", false);

        sonic.restoreRewindState(snapshot);

        assertTrue(getBooleanField(movement, "jumpPressed"), "jumpPressed latch not restored");
        assertTrue(getBooleanField(movement, "jumpPrevious"), "jumpPrevious latch not restored");
        assertTrue(getBooleanField(movement, "jumpReleasedSinceJump"), "jumpReleasedSinceJump latch not restored");
        assertTrue(getBooleanField(movement, "testKeyPressed"), "testKeyPressed latch not restored");
        assertTrue(getBooleanField(movement, "inputDown"), "inputDown not restored");
        assertTrue(getBooleanField(movement, "inputJump"), "inputJump not restored");
        assertTrue(getBooleanField(movement, "inputJumpPress"), "inputJumpPress not restored");
        assertTrue(getBooleanField(movement, "facingFlipForcesPushClearAfterGroundWall"),
                "facingFlipForcesPushClearAfterGroundWall not restored");
        assertTrue(getBooleanField(movement, "wasCrouching"), "wasCrouching not restored");
        assertEquals(4, getIntField(dust, "frameIndex"), "dust frameIndex not restored");
        assertEquals(1, getIntField(dust, "frameTick"), "dust frameTick not restored");
        assertEquals(0x0E, getIntField(dust, "currentFrame"), "dust currentFrame not restored");
        assertTrue(getBooleanField(dust, "activeLastTick"), "dust activeLastTick not restored");
    }

    @Test
    void roundTripRestoresDrowningControllerState() throws Exception {
        Sonic sonic = new Sonic("sonic", (short) 100, (short) 200);
        DrowningController drowning = sonic.getDrowningController();
        assertNotNull(drowning, "playable sprite must have a DrowningController");

        setIntField(drowning, "remainingAir", 11);
        setIntField(drowning, "frameTimer", 37);
        setBooleanField(drowning, "drowningMusicStarted", true);
        setIntField(drowning, "bubbleFlags", 0xC1);
        setIntField(drowning, "bubblesRemainingInBurst", 1);
        setIntField(drowning, "nextBubbleTimer", 9);
        setIntField(drowning, "numberBubbleTimer", 3);
        setIntField(drowning, "numberBubbleFrequency", 2);

        PerObjectRewindSnapshot snapshot = sonic.captureRewindState();

        // Mutate away from the captured sentinels (simulating frames advancing
        // past the rewind target before the seek/restore is applied).
        setIntField(drowning, "remainingAir", 30);
        setIntField(drowning, "frameTimer", 60);
        setBooleanField(drowning, "drowningMusicStarted", false);
        setIntField(drowning, "bubbleFlags", 0);
        setIntField(drowning, "bubblesRemainingInBurst", -1);
        setIntField(drowning, "nextBubbleTimer", 0);
        setIntField(drowning, "numberBubbleTimer", 0);
        setIntField(drowning, "numberBubbleFrequency", 1);

        sonic.restoreRewindState(snapshot);

        assertEquals(11, getIntField(drowning, "remainingAir"), "remainingAir (breath timer) not restored");
        assertEquals(37, getIntField(drowning, "frameTimer"), "per-second frame timer not restored");
        assertTrue(getBooleanField(drowning, "drowningMusicStarted"),
                "drowning music audio-cue phase not restored");
        assertEquals(0xC1, getIntField(drowning, "bubbleFlags"), "bubbleFlags not restored");
        assertEquals(1, getIntField(drowning, "bubblesRemainingInBurst"), "bubblesRemainingInBurst not restored");
        assertEquals(9, getIntField(drowning, "nextBubbleTimer"), "nextBubbleTimer not restored");
        assertEquals(3, getIntField(drowning, "numberBubbleTimer"), "numberBubbleTimer (countdown) not restored");
        assertEquals(2, getIntField(drowning, "numberBubbleFrequency"), "numberBubbleFrequency not restored");
    }

    @Test
    void roundTripRestoresShieldTypeAndRebindsShieldObject() {
        Sonic sonic = new Sonic("sonic", (short) 100, (short) 200);
        RecordingPowerUpSpawner spawner = new RecordingPowerUpSpawner();
        sonic.setPowerUpSpawner(spawner);
        sonic.setShieldState(true, ShieldType.FIRE);

        PerObjectRewindSnapshot snapshot = sonic.captureRewindState();
        sonic.setShieldState(false, null);

        sonic.restoreRewindState(snapshot);
        sonic.refreshPowerUpObjectsAfterRewindRestore();

        assertTrue(sonic.hasShield());
        assertEquals(ShieldType.FIRE, sonic.getShieldType());
        assertSame(spawner.lastShield, sonic.getShieldObject());
        assertEquals(ShieldType.FIRE, spawner.lastShieldType);
        assertFalse(spawner.lastShield.isDestroyed());
    }

    @Test
    void playerExtraSolelyOwnsExactCarryContextAndExistingFlightState() {
        Tails tails = new Tails("tails_p2", (short) 0x180, (short) 0x240);
        tails.setCpuControlled(true);
        tails.setCpuController(new SidekickCpuController(tails,
                new Sonic("sonic", (short) 0x100, (short) 0x200)));

        TailsCarryController.CarryContext[] contexts = {
                TailsCarryController.CarryContext.MANUAL,
                TailsCarryController.CarryContext.CNZ,
                TailsCarryController.CarryContext.MGZ_BOSS
        };
        for (int i = 0; i < contexts.length; i++) {
            short latchX = (short) (0x1100 + i);
            short latchY = (short) (-0x2200 - i);
            int cooldown = 0x12 + i;
            TailsCarryController.Snapshot expectedCarry = new TailsCarryController.Snapshot(
                    latchX, latchY, true, true, cooldown, contexts[i]);
            tails.getTailsCarryController().restore(expectedCarry);
            tails.setDoubleJumpFlag(2 + i);
            tails.setDoubleJumpProperty((byte) (i == 1 ? 0 : 0x31 + i));
            tails.setInWater(i == 2);

            PerObjectRewindSnapshot snapshot = tails.captureRewindState();
            PlayerRewindExtra extra = snapshot.playerExtra();
            assertEquals(expectedCarry, extra.controllerState().tailsCarryState());

            tails.getTailsCarryController().clearState();
            tails.setDoubleJumpFlag(0);
            tails.setDoubleJumpProperty((byte) 0x7F);
            tails.setInWater(false);
            tails.restoreRewindState(snapshot);

            assertEquals(expectedCarry, tails.getTailsCarryController().capture());
            assertEquals(2 + i, tails.getDoubleJumpFlag(),
                    "flight active/flap state remains owned by the existing player field");
            assertEquals(i == 1 ? 0 : 0x31 + i, tails.getDoubleJumpProperty() & 0xFF,
                    "flight exhaustion/timer remains owned by the existing player field");
            assertEquals(i == 2, tails.isInWater(),
                    "swimming remains owned by the existing player field");
        }

        assertNoRawParticipantReference(PlayerRewindExtra.class);
    }

    @Test
    void aggregateControllerStateRoundTripsWithoutRawPlayerReferences() {
        Sonic sonic = new Sonic("sonic", (short) 0x180, (short) 0x240);
        PlayableSpriteMovement.RewindState movement = new PlayableSpriteMovement.RewindState(
                true, false, true, true,
                true, false, true, false, true, true, true, false,
                true, true, 7, 8, 9, true,
                true, 3, Direction.LEFT, 0x22, 0x33);
        PlayableSpriteAnimation.RewindState animation =
                new PlayableSpriteAnimation.RewindState(0x12, 0x34);
        DrowningController.RewindState drowning = new DrowningController.RewindState(
                17, 23, true, 5, 4, 3, 2, 1);
        TailsCarryController.Snapshot carry = new TailsCarryController.Snapshot(
                (short) 0x1234, (short) -0x2345, true, true, 19,
                TailsCarryController.CarryContext.CNZ);
        sonic.setSpindashDustController(new SpindashDustController(sonic, null));
        SpindashDustController.RewindState spindash =
                new SpindashDustController.RewindState(2, 3, 4, true);
        PlayableSpriteController.RewindState expected = new PlayableSpriteController.RewindState(
                movement, spindash, animation, drowning, carry, null, null);
        sonic.controller.restoreRewindState(expected);

        PerObjectRewindSnapshot snapshot = sonic.captureRewindState();
        assertEquals(expected, snapshot.playerExtra().controllerState());

        sonic.controller.restoreRewindState(null);
        sonic.restoreRewindState(snapshot);

        assertEquals(expected, sonic.captureRewindState().playerExtra().controllerState());
        assertNoRawParticipantReference(PlayableSpriteController.RewindState.class);
    }

    @Test
    void carryRestorePreservesPendingStateUntilMainParticipantReturns() {
        Tails tails = new Tails("tails_p2", (short) 0x180, (short) 0x240);
        tails.setCpuControlled(true);
        tails.setCpuController(new SidekickCpuController(tails, null));
        TailsCarryController.Snapshot expected = new TailsCarryController.Snapshot(
                (short) 0x1357, (short) -0x2468, true, true, 0x21,
                TailsCarryController.CarryContext.CNZ);
        tails.getTailsCarryController().restore(expected);
        PerObjectRewindSnapshot snapshot = tails.captureRewindState();

        GameServices.sprites().clearAllSprites();
        GameServices.sprites().addSprite(tails, "tails");
        tails.getTailsCarryController().clearState();
        tails.restoreRewindState(snapshot);
        tails.getTailsCarryController().updateAfterTailsCollision(0);
        assertEquals(expected, tails.getTailsCarryController().capture(),
                "missing main participant must not erase a just-restored pending carry");

        Sonic restoredMain = new Sonic("sonic", (short) 0x100, (short) 0x200);
        restoredMain.setObjectControlled(true);
        restoredMain.setAir(true);
        restoredMain.setXSpeed(expected.latchX());
        restoredMain.setYSpeed(expected.latchY());
        GameServices.sprites().addSprite(restoredMain, "sonic");
        tails.getTailsCarryController().updateAfterTailsCollision(0);

        TailsCarryController.Snapshot resolved = tails.getTailsCarryController().capture();
        assertTrue(resolved.carrying());
        assertFalse(resolved.parentagePending(),
                "the registry participant is lazily resolved on a later update");
        assertEquals(TailsCarryController.CarryContext.CNZ, resolved.context());
    }

    @Test
    void jumpReleaseCooldownRoundTripsThroughPlayerExtra() {
        Tails tails = new Tails("tails_p2", (short) 0, (short) 0);
        TailsCarryController.Snapshot released = new TailsCarryController.Snapshot(
                (short) 0x321, (short) -0x123, false, false, 0x12,
                TailsCarryController.CarryContext.NONE);
        tails.getTailsCarryController().restore(released);

        PerObjectRewindSnapshot snapshot = tails.captureRewindState();
        tails.getTailsCarryController().clearState();
        tails.restoreRewindState(snapshot);

        assertEquals(released, tails.getTailsCarryController().capture());
    }

    private static void assertNoRawParticipantReference(Class<?> recordType) {
        assertTrue(recordType.isRecord());
        for (RecordComponent component : recordType.getRecordComponents()) {
            assertFalse(AbstractPlayableSprite.class.isAssignableFrom(component.getType()),
                    "rewind records must resolve participants through the sprite registry: "
                            + component.getName());
            if (component.getType().isRecord()) {
                assertNoRawParticipantReference(component.getType());
            }
        }
    }

    private static void setBooleanField(Object target, String name, boolean value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }

    private static boolean getBooleanField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static void setIntField(Object target, String name, int value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static int getIntField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static final class RecordingPowerUpSpawner implements PowerUpSpawner {
        private RecordingPowerUpObject lastShield;
        private ShieldType lastShieldType;

        @Override
        public PowerUpObject spawnShield(PlayableEntity player, ShieldType type) {
            lastShieldType = type;
            lastShield = new RecordingPowerUpObject();
            return lastShield;
        }

        @Override
        public PowerUpObject spawnInvincibilityStars(PlayableEntity player) {
            return new RecordingPowerUpObject();
        }

        @Override
        public InstaShieldHandle createInstaShield(PlayableEntity player) {
            return null;
        }

        @Override
        public void registerObject(PowerUpObject obj) {
        }

        @Override
        public void spawnSplash(PlayableEntity player) {
        }
    }

    private static final class RecordingPowerUpObject implements PowerUpObject {
        private boolean destroyed;

        @Override
        public void destroy() {
            destroyed = true;
        }

        @Override
        public boolean isDestroyed() {
            return destroyed;
        }

        @Override
        public void setVisible(boolean visible) {
        }
    }
}
