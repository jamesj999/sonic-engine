package com.openggf.tests;

import org.junit.jupiter.api.Test;
import com.openggf.game.rules.GameRules;
import com.openggf.physics.Direction;
import com.openggf.sprites.animation.ScriptedVelocityAnimationProfile;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class TestScriptedVelocityAnimationProfile {

    @Test
    void touchSuppressionAloneStillPublishesNormalMovementAnimation() {
        ScriptedVelocityAnimationProfile profile = createProfile();
        TestSprite sprite = new TestSprite();
        ObjectControlState.engineScriptedTouchSuppressedMovementActive().applyTo(sprite);
        sprite.setGSpeed((short) 0);

        assertEquals(profile.getIdleAnimId(), profile.resolveAnimationId(sprite, 0, 32).intValue(),
                "native object_control bit 7 does not suppress the normal movement animation path");
    }

    @Test
    void movementSuppressionPreservesObjectPublishedAnimation() {
        ScriptedVelocityAnimationProfile profile = createProfile();
        TestSprite sprite = new TestSprite();
        ObjectControlState.nativeBit7FullControl().applyTo(sprite);
        sprite.setAnimationId(profile.getSpringAnimId());

        assertNull(profile.resolveAnimationId(sprite, 0, 32),
                "object_control bit 0 leaves animation ownership with the controlling object");
    }

    @Test
    public void resolvesSlideAnimationWhenSlidingOnGround() {
        ScriptedVelocityAnimationProfile profile = createProfile();
        TestSprite sprite = new TestSprite();
        sprite.setSliding(true);
        sprite.setAir(false);
        sprite.setAnimationId(13);
        sprite.setGSpeed((short) 0x0800); // would normally choose run

        Integer animId = profile.resolveAnimationId(sprite, 0, 32);

        assertNull(animId, "The slide routine's already-published byte remains authoritative");
    }

    @Test
    void groundedSlidePreservesObjectPublishedWalk() {
        ScriptedVelocityAnimationProfile profile = createProfile();
        TestSprite sprite = new TestSprite();
        sprite.setSliding(true);
        sprite.setAir(false);
        sprite.setAnimationId(profile.getWalkAnimId());

        assertNull(profile.resolveAnimationId(sprite, 0, 32),
                "OilSlides may publish Walk while its sliding status bit remains set");
    }

    @Test
    void airborneTumblePreservesObjectPublishedFloatAnimation() {
        ScriptedVelocityAnimationProfile profile = createProfile();
        TestSprite sprite = new TestSprite();
        sprite.setAir(true);
        sprite.setRolling(true);
        sprite.setFlipAngle(1);
        sprite.setAnimationId(15);

        assertNull(profile.resolveAnimationId(sprite, 0, 32),
                "A non-zero flip angle keeps the animation byte written by the launching object");
    }

    @Test
    public void keepsHurtAnimationPriorityOverSlide() {
        ScriptedVelocityAnimationProfile profile = createProfile();
        TestSprite sprite = new TestSprite();
        sprite.setSliding(true);
        sprite.setAir(false);
        sprite.setHurt(true);
        // HurtCharacter's common tail already performed its one-shot
        // `move.b #$1A,anim(a0)` (docs/skdisasm/sonic3k.asm:21321), so the hurt
        // byte is live when the profile runs.
        sprite.setAnimationId(profile.getHurtAnimId());

        Integer animId = profile.resolveAnimationId(sprite, 0, 32);

        assertEquals(11, animId.intValue());
    }

    @Test
    public void hurtDoesNotReclaimAnAnimationByteAnotherOwnerHasTaken() {
        // The hurt animation is written once by HurtCharacter's tail; the hurt
        // ROUTINE that runs afterwards -- S3K loc_1569C, S1 Sonic_Hurt, S2
        // Obj01_Hurt -- never rewrites anim. So an owner that stores the byte
        // during the hurt keeps it, and the solid push-release tail is exactly
        // such an owner: it stores Walk and exempts only Roll (S1/S2) or Roll
        // and Spindash (S3K), never Hurt.
        ScriptedVelocityAnimationProfile profile = createProfile();
        TestSprite sprite = new TestSprite();
        sprite.setSliding(true);
        sprite.setAir(false);
        sprite.setHurt(true);
        sprite.setAnimationId(profile.getWalkAnimId());

        assertNull(profile.resolveAnimationId(sprite, 0, 32),
                "a still-hurt player does not re-derive the hurt byte over a later owner's store");
    }

    @Test
    public void usesRollAnimationWhenSlidingAndAirborne() {
        // ROM: when airborne (e.g. jumping off water slide), the jump/roll mode
        // overwrites obAnim with id_Roll. Slide animation only applies on ground.
        ScriptedVelocityAnimationProfile profile = createProfile();
        TestSprite sprite = new TestSprite();
        sprite.setSliding(true);
        sprite.setRolling(true);
        sprite.setAir(true);
        sprite.setGSpeed((short) 0x0800);

        Integer animId = profile.resolveAnimationId(sprite, 0, 32);

        assertEquals(3, animId.intValue()); // rollAnimId, not slideAnimId
    }

    @Test
    public void s3kAirborneSlidePreservesTerrainPublishedAnimationWhileRolling() {
        ScriptedVelocityAnimationProfile profile = createProfile()
                .setAirborneSlidePreservesPublishedAnimation(true);
        TestSprite sprite = new TestSprite();
        sprite.setAnimationId(profile.getSlideAnimId());
        sprite.setSliding(true);
        sprite.setRolling(true);
        sprite.setAir(true);

        assertNull(profile.resolveAnimationId(sprite, 0, 32));
    }

    @Test
    public void preservesSlideAnimationWhenTerrainDetachSetsAirAfterSlideDispatch() {
        ScriptedVelocityAnimationProfile profile = createProfile();
        TestSprite sprite = new TestSprite();
        sprite.setAnimationId(13);
        sprite.setSliding(true);
        sprite.setRolling(false);
        sprite.setJumping(false);
        sprite.setAir(true);

        Integer animId = profile.resolveAnimationId(sprite, 0, 32);

        assertNull(animId,
                "AnglePos terrain detach does not overwrite LZWaterSlides' animation byte");
    }

    @Test
    public void rollingJumpPreservesSlideWrittenBeforeJumpDispatch() {
        ScriptedVelocityAnimationProfile profile = createProfile();
        TestSprite sprite = new TestSprite();
        sprite.setAnimationId(profile.getSlideAnimId());
        sprite.setSliding(true);
        sprite.setRolling(true);
        sprite.setRollingJump(true);
        sprite.setJumping(true);
        sprite.setAir(true);

        Integer animId = profile.resolveAnimationId(sprite, 0, 32);

        assertNull(animId,
                "Sonic_Jump .rolljump sets only the Roll-Jump bit and leaves Slide active");
    }

    @Test
    public void preservesAnimationWhenFinalMoveLockTickExpiresAfterMoveDispatch() {
        ScriptedVelocityAnimationProfile profile = createProfile();
        TestSprite sprite = new TestSprite();
        sprite.setAnimationId(1);
        sprite.setSkidding(true);
        sprite.setMoveLockTimer(0);
        sprite.getAnimationManager().suppressGroundMovementAnimationForFrame();

        Integer animId = profile.resolveAnimationId(sprite, 0, 32);

        assertNull(animId,
                "Sonic_Move skipped animation writes while locktime was non-zero at dispatch");
    }

    @Test
    void s3kRollCheckPublishesDuckWhileMoveLockIsActive() {
        ScriptedVelocityAnimationProfile profile = createProfile();
        TestSprite sprite = new TestSprite();
        sprite.useGameRules(GameRules.SONIC_3K);
        sprite.setAnimationId(profile.getWalkAnimId());
        sprite.setMoveLockTimer(22);
        sprite.setCrouching(true);
        sprite.getAnimationManager().suppressGroundMovementAnimationForFrame();

        Integer animId = profile.resolveAnimationId(sprite, 0, 32);

        assertEquals(profile.getDuckAnimId(), animId.intValue(),
                "SonicKnux_Roll runs after the move_lock-gated Sonic_Move routine");
    }

    @Test
    void s2MoveLockStillPreservesThePublishedAnimation() {
        ScriptedVelocityAnimationProfile profile = createProfile();
        TestSprite sprite = new TestSprite();
        sprite.useGameRules(GameRules.SONIC_2);
        sprite.setAnimationId(profile.getWalkAnimId());
        sprite.setMoveLockTimer(22);
        sprite.setCrouching(true);
        sprite.getAnimationManager().suppressGroundMovementAnimationForFrame();

        assertNull(profile.resolveAnimationId(sprite, 0, 32),
                "S2 has no post-Move low-speed crouch write that bypasses move_lock");
    }

    @Test
    void s3kCpuCrouchStateDoesNotSubstituteForCtrl2LogicalDuckWrite() {
        ScriptedVelocityAnimationProfile profile = createProfile();
        TestSprite sprite = new TestSprite();
        sprite.useGameRules(GameRules.SONIC_3K);
        sprite.setCpuControlled(true);
        sprite.setAnimationId(profile.getWalkAnimId());
        sprite.setMoveLockTimer(22);
        sprite.setCrouching(true);

        assertNull(profile.resolveAnimationId(sprite, 0, 32),
                "CPU movement state is not the native Ctrl_2_logical byte consumed by Tails_Roll");
    }

    @Test
    void s3kStationaryDuckReleaseKeepsTheMoveRoutineWaitWrite() {
        ScriptedVelocityAnimationProfile profile = createProfile()
                .setDuckReleasePublishesWalk(true);
        TestSprite sprite = new TestSprite();
        sprite.setAnimationId(profile.getDuckAnimId());
        sprite.getAnimationManager().captureGroundMovementAnimSpeed((short) 0);

        assertEquals(profile.getIdleAnimId(),
                profile.resolveAnimationId(sprite, 0, 32).intValue(),
                "Move writes Wait before the later roll check compares the animation byte");
    }

    @Test
    void s3kCoastingDuckReleasePublishesWalkAfterMoveLeavesDuckUntouched() {
        ScriptedVelocityAnimationProfile profile = createProfile()
                .setDuckReleasePublishesWalk(true);
        TestSprite sprite = new TestSprite();
        sprite.setAnimationId(profile.getDuckAnimId());
        sprite.setGSpeed((short) 0x80);
        sprite.getAnimationManager().captureGroundMovementAnimSpeed((short) 0x80);

        assertEquals(profile.getWalkAnimId(),
                profile.resolveAnimationId(sprite, 0, 32).intValue(),
                "the post-Move roll check replaces a surviving Duck byte while coasting");
    }

    @Test
    public void preservesObjectAnimationForAirborneExternalReleaseWithRollingStatus() {
        ScriptedVelocityAnimationProfile profile = createProfile();
        TestSprite sprite = new TestSprite();
        sprite.setAir(true);
        sprite.setRolling(true);
        sprite.setJumping(true);
        sprite.setRollingJump(false);
        sprite.setSliding(false);
        sprite.setAnimationId(20);

        Integer animId = profile.resolveAnimationId(sprite, 0, 32);

        assertNull(animId,
                "Obj80 moving-vine release leaves AniIDSonAni_Hang2 active even when Status_Roll remains set");
    }

    @Test
    void groundedRollPreservesAnimationWrittenByLaterObjectDispatch() {
        ScriptedVelocityAnimationProfile profile = createProfile();
        TestSprite sprite = new TestSprite();
        sprite.setRolling(true);
        sprite.setAir(false);
        sprite.setAnimationId(profile.getWalkAnimId());

        Integer animId = profile.resolveAnimationId(sprite, 0, 32);

        assertNull(animId,
                "MdRoll does not overwrite a SolidObject push-release animation write");
    }

    @Test
    void zeroInertiaChoosesWaitEvenWhileOppositeDirectionRemainsHeld() {
        ScriptedVelocityAnimationProfile profile = createProfile();
        TestSprite sprite = new TestSprite();
        sprite.setMovementInputActive(true);
        sprite.getAnimationManager().captureGroundMovementAnimSpeed((short) 0);

        Integer animId = profile.resolveAnimationId(sprite, 0, 32);

        assertEquals(0, animId.intValue(),
                "the enclosing ROM move routine writes WAIT after opposite-direction deceleration reaches zero");
    }

    @Test
    void releasedDirectionPreservesPublishedStopUntilItsScriptSwitches() {
        ScriptedVelocityAnimationProfile profile = createProfile();
        TestSprite sprite = new TestSprite();
        sprite.setAnimationId(profile.getSkidAnimId());
        sprite.setSkidding(false);
        sprite.setMovementInputActive(false);
        sprite.getAnimationManager().captureGroundMovementAnimSpeed((short) -0x0700);

        Integer animId = profile.resolveAnimationId(sprite, 0, 32);

        assertNull(animId,
                "no-input Sonic_Move reaches ResetScr without replacing the existing Stop byte");
    }

    @Test
    void coastingWithoutDirectionPreservesExplicitAnimationOwner() {
        ScriptedVelocityAnimationProfile profile = createProfile();
        TestSprite sprite = new TestSprite();
        sprite.setAnimationId(profile.getSlideAnimId());
        sprite.setMovementInputActive(false);
        sprite.getAnimationManager().captureGroundMovementAnimSpeed((short) 0x0700);

        assertNull(profile.resolveAnimationId(sprite, 0, 32),
                "no-input Sonic_Move does not replace an explicit animation while inertia remains non-zero");
    }

    @Test
    void releasedDuckRemainsPublishedOnProfilesWithoutEarlyClear() {
        ScriptedVelocityAnimationProfile profile = createProfile();
        TestSprite sprite = new TestSprite();
        sprite.setAnimationId(profile.getDuckAnimId());
        sprite.setCrouching(false);
        sprite.setMovementInputActive(false);
        sprite.setGSpeed((short) 0x0100);

        assertNull(profile.resolveAnimationId(sprite, 0, 32),
                "the shared no-input tail preserves Duck unless the character profile opts into an early clear");
    }

    @Test
    void s3kReleasedDuckPublishesWalkBeforeNoInputTail() {
        ScriptedVelocityAnimationProfile profile = createProfile()
                .setDuckReleasePublishesWalk(true);
        TestSprite sprite = new TestSprite();
        sprite.setAnimationId(profile.getDuckAnimId());
        sprite.setCrouching(false);
        sprite.setMovementInputActive(false);
        sprite.setGSpeed((short) 0x0100);

        assertEquals(profile.getWalkAnimId(), profile.resolveAnimationId(sprite, 0, 32).intValue());
    }

    @Test
    void effectiveSameDirectionPublishesWalkDespiteOppositeRawInput() {
        ScriptedVelocityAnimationProfile profile = createProfile();
        TestSprite sprite = new TestSprite();
        sprite.setAnimationId(profile.getSkidAnimId());
        sprite.setMovementInputActive(true);
        sprite.setDirectionalInputPressed(false, false, false, true);
        sprite.setDirection(Direction.RIGHT);
        sprite.getAnimationManager().captureGroundMovementAnimSpeed((short) 0x0080);

        assertEquals(1, profile.resolveAnimationId(sprite, 1, 32).intValue(),
                "animation resolution follows the effective movement path, not raw input hidden by a forced direction");
    }

    @Test
    void releasedDirectionPreservesPublishedSpringWhileCoasting() {
        ScriptedVelocityAnimationProfile profile = createProfile();
        TestSprite sprite = new TestSprite();
        sprite.setAnimationId(profile.getSpringAnimId());
        sprite.setMovementInputActive(false);
        sprite.getAnimationManager().captureGroundMovementAnimSpeed((short) 0x0230);

        Integer animId = profile.resolveAnimationId(sprite, 0, 32);

        assertNull(animId,
                "the friction-only tail does not replace an object-published Spring byte with Walk");
    }

    @Test
    void groundMovementWaitSurvivesAnglePosDetachFrame() {
        ScriptedVelocityAnimationProfile profile = createProfile();
        TestSprite sprite = new TestSprite();
        sprite.setAnimationId(1);
        sprite.getAnimationManager().captureGroundMovementAnimSpeed((short) 0);
        sprite.setAir(true);

        Integer animId = profile.resolveAnimationId(sprite, 0, 32);

        assertEquals(0, animId.intValue(),
                "Move writes Wait before AnglePos sets Status_InAir on a ground-to-air detach frame");
    }

    @Test
    void lateGroundedDuckWriteSurvivesAnglePosDetachFrame() {
        ScriptedVelocityAnimationProfile profile = createProfile();
        TestSprite sprite = new TestSprite();
        sprite.getAnimationManager().captureGroundMovementAnimSpeed((short) 0x24);
        sprite.setAnimationId(profile.getDuckAnimId());
        sprite.setAir(true);

        assertNull(profile.resolveAnimationId(sprite, 0, 32),
                "Tails_Roll's post-Move Duck write remains authoritative when AnglePos detaches Tails");
    }

    @Test
    void hurtLandingPublishesWalkForRecoveryFrameBeforeReturningToWait() {
        ScriptedVelocityAnimationProfile profile = createProfile();
        TestSprite sprite = new TestSprite();
        sprite.setHurt(true);
        sprite.setAirRaw(true);
        sprite.captureOnObjectAtFrameStart();
        sprite.setHurt(false);
        sprite.setAirRaw(false);

        assertEquals(1, profile.resolveAnimationId(sprite, 0, 32).intValue(),
                "ROM hurt-stop writes Walk before running animation with zero inertia");

        sprite.captureOnObjectAtFrameStart();

        assertEquals(0, profile.resolveAnimationId(sprite, 1, 32).intValue(),
                "the following normal-control frame may select Wait at zero inertia");
    }

    @Test
    void ordinaryAirRoutineLandingPreservesItsIncomingAnimationForThatFrame() {
        TestSprite sprite = new TestSprite();
        ScriptedVelocityAnimationProfile profile = createProfile();
        sprite.setAirRaw(true);
        sprite.setAnimationId(profile.getWalkAnimId());
        sprite.captureOnObjectAtFrameStart();

        sprite.setAirRaw(false);

        assertNull(profile.resolveGroundMovementAnimId(sprite),
                "an airborne routine that lands does not run grounded animation selection in the same frame");
    }

    @Test
    public void s3kBalanceUsesSingleFacingSetForAwayStates() {
        ScriptedVelocityAnimationProfile profile = createProfile()
                .setBalanceAnimId(20)
                .setBalance2AnimId(21)
                .setBalance3AnimId(22)
                .setBalance4AnimId(23);
        TestSprite sprite = new TestSprite();
        sprite.useGameRules(GameRules.SONIC_3K);
        sprite.setBalanceState(4);

        Integer animId = profile.resolveAnimationId(sprite, 0, 32);

        assertEquals(21, animId.intValue(),
                "S3K dummies out the S2 away-facing object/terrain balance animations and uses Balance2");
    }

    private static ScriptedVelocityAnimationProfile createProfile() {
        return new ScriptedVelocityAnimationProfile()
                .setIdleAnimId(0)
                .setWalkAnimId(1)
                .setRunAnimId(2)
                .setRollAnimId(3)
                .setRoll2AnimId(4)
                .setPushAnimId(5)
                .setDuckAnimId(6)
                .setLookUpAnimId(7)
                .setSpindashAnimId(8)
                .setSpringAnimId(9)
                .setDeathAnimId(10)
                .setHurtAnimId(11)
                .setSkidAnimId(12)
                .setSlideAnimId(13)
                .setAirAnimId(14)
                .setWalkSpeedThreshold(0x40)
                .setRunSpeedThreshold(0x600)
                .setFallbackFrame(0)
                .setAnglePreAdjust(true);
    }

    private static class TestSprite extends AbstractPlayableSprite {
        TestSprite() {
            super("test", (short) 0, (short) 0);
        }

        @Override
        public void draw() {
        }

        @Override
        protected void defineSpeeds() {
            runAccel = 12;
            runDecel = 128;
            friction = 12;
            max = 1536;
            jump = 1664;
        }

        @Override
        protected void createSensorLines() {
        }

        void useGameRules(GameRules fs) {
            super.setGameRulesForTest(fs);
        }

        void setAirRaw(boolean air) {
            this.air = air;
        }
    }
}
