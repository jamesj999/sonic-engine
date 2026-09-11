package com.openggf.sprites.managers;

import com.openggf.tests.TestEnvironment;
import com.openggf.game.session.SessionManager;
import com.openggf.game.rules.GameRules;
import com.openggf.physics.Direction;
import com.openggf.sprites.animation.ScriptedVelocityAnimationProfile;
import com.openggf.sprites.animation.SpriteAnimationEndAction;
import com.openggf.sprites.animation.SpriteAnimationScript;
import com.openggf.sprites.animation.SpriteAnimationSet;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.FullReset;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import java.lang.reflect.Field;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(SingletonResetExtension.class)
@FullReset
public class TestPlayableSpriteAnimation {

    @BeforeEach
    public void setUp() {
        TestEnvironment.activeGameplayMode();
    }

    @AfterEach
    public void tearDown() {
        SessionManager.clear();
    }

    @Test
    public void s3kIdleToWalkAnimationChangeClearsGroundPush() {
        TestablePlayableSprite sprite = createSprite(GameRules.SONIC_3K);
        sprite.setAnimationId(5);
        sprite.setMovementInputActive(false);
        sprite.getAnimationManager().update(0);

        sprite.setMovementInputActive(true);
        sprite.setPushing(true);

        sprite.getAnimationManager().update(1);

        assertFalse(sprite.getPushing(),
                "S3K Animate_Tails2P clears Status_Push when MoveRight changes anim from idle to walk");
        assertEquals(0, sprite.getAnimationId(),
                "After the push clear, animation resolution should choose walk instead of push");
    }

    @Test
    public void s2IdleToWalkAnimationChangeClearsGroundPush() {
        TestablePlayableSprite sprite = createSprite(GameRules.SONIC_2);
        sprite.setAnimationId(5);
        sprite.setMovementInputActive(false);
        sprite.getAnimationManager().update(0);

        sprite.setMovementInputActive(true);
        sprite.setPushing(true);

        sprite.getAnimationManager().update(1);

        assertFalse(sprite.getPushing(),
                "S2 Animate_Sonic/Tails clears Status_Push when anim changes from idle to walk");
        assertEquals(0, sprite.getAnimationId(),
                "After the push clear, animation resolution should choose walk instead of push");
    }

    @Test
    public void s2GroundPushSurvivesInitialRomAnimByteCapture() {
        TestablePlayableSprite sprite = createSprite(GameRules.SONIC_2);
        sprite.setAnimationId(0);
        sprite.setMovementInputActive(true);
        sprite.setGSpeed((short) 0);
        sprite.setPushing(true);

        sprite.getAnimationManager().update(759);

        assertTrue(sprite.getPushing(),
                "S2 does not clear Status_Push before prev_anim has a captured ROM anim byte");
        assertEquals(0, sprite.getAnimationId(),
                "S2 keeps raw anim=Walk while the $FF handler selects push mappings");
    }

    @Test
    public void characterProfileCanKeepPushInsideWalkHandler() {
        TestablePlayableSprite sprite = createSprite(GameRules.SONIC_3K);
        ((ScriptedVelocityAnimationProfile) sprite.getAnimationProfile())
                .setPushUsesWalkSpecialHandler(true);
        sprite.setAnimationId(0);
        sprite.setMovementInputActive(true);
        sprite.setPushing(true);

        sprite.getAnimationManager().update(0);

        assertEquals(0, sprite.getAnimationId(),
                "the character's $FF Walk handler selects push mappings without rewriting raw anim");
        assertEquals(0, sprite.getMappingFrame(),
                "the profile-owned handler resolves the configured push script");
    }

    @Test
    public void heldSpindashPreservesObjectPublishedWalkByte() {
        TestablePlayableSprite sprite = createSprite(GameRules.SONIC_2);
        ((ScriptedVelocityAnimationProfile) sprite.getAnimationProfile()).setSpindashAnimId(9);
        sprite.setSpindash(true);
        sprite.setAnimationId(0);

        sprite.getAnimationManager().update(1);

        assertEquals(0, sprite.getAnimationId(),
                "held Down does not rewrite anim; SolidObject_TestClearPush's Walk byte must persist");
    }

    @Test
    public void objectPublishedLandingAnimUpdatesNativePreviousByte() {
        TestablePlayableSprite sprite = createSprite(GameRules.SONIC_2);
        sprite.setObjectControlled(true);
        sprite.setAnimationId(2);
        sprite.getAnimationManager().update(0);

        sprite.setAnimationId(0);
        sprite.getAnimationManager().update(1);

        sprite.setObjectControlled(false);
        sprite.setMovementInputActive(true);
        sprite.setPushing(true);
        sprite.getAnimationManager().update(2);

        assertTrue(sprite.getPushing(),
                "the landing-frame Walk store must become prev_anim before the next push frame");
        assertEquals(0, sprite.getAnimationManager().captureRewindState().lastGroundMovementAnimId());
    }

    @Test
    public void postPlayerLandingWalkChangeClearsFreshPushWhenResolverIsSuppressed() {
        TestablePlayableSprite sprite = createSprite(GameRules.SONIC_2);
        sprite.setObjectControlled(true);
        sprite.setAnimationId(2);
        sprite.getAnimationManager().update(0);

        sprite.setAnimationId(0);
        sprite.setPushing(true);
        sprite.getAnimationManager().update(1);

        assertFalse(sprite.getPushing(),
                "Animate_Tails clears Status_Push when a post-player landing changed raw anim from Roll to Walk");
        assertEquals(0, sprite.getAnimationManager().captureRewindState().lastAnimationId());
    }

    @Test
    public void s2SonicPushWaitsForWalkTimerBeforePublishingPushMapping() {
        TestablePlayableSprite sprite = createSprite(GameRules.SONIC_2);
        ((ScriptedVelocityAnimationProfile) sprite.getAnimationProfile())
                .setWalkRunPublishesFrameBeforeTimerAdvance(true);
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(0, new SpriteAnimationScript(0xFF,
                List.of(0x10, 0x11), SpriteAnimationEndAction.LOOP, 0));
        animations.addScript(1, new SpriteAnimationScript(0xFF,
                List.of(0x20), SpriteAnimationEndAction.LOOP, 0));
        animations.addScript(4, new SpriteAnimationScript(0xFD,
                List.of(0x48), SpriteAnimationEndAction.LOOP, 0));
        sprite.setAnimationSet(animations);
        sprite.setAnimationId(0);
        sprite.getAnimationManager().restoreRewindState(
                new PlayableSpriteAnimation.RewindState(0, 0));
        sprite.setAnimationFrameIndex(1);
        sprite.setAnimationTick(1);
        sprite.setMappingFrame(0x10);
        sprite.setMovementInputActive(true);
        sprite.setPushing(true);

        sprite.getAnimationManager().update(0);

        assertEquals(0, sprite.getAnimationId(), "Status_Push does not replace the raw anim byte");
        assertEquals(0x10, sprite.getMappingFrame(),
                "A live SAnim_WalkRun timer retains the walk mapping on push onset");
        assertEquals(0, sprite.getAnimationTick());

        sprite.getAnimationManager().update(1);

        assertEquals(0, sprite.getAnimationId());
        assertEquals(0x48, sprite.getMappingFrame(),
                "The expired walk timer lets the native handler select SAnim_Push");
    }

    @Test
    public void springControlLockDoesNotOverwriteObjectPublishedWalk() {
        TestablePlayableSprite sprite = createSprite(GameRules.SONIC_2);
        ((ScriptedVelocityAnimationProfile) sprite.getAnimationProfile())
                .setSpringAnimId(0x10);
        SpriteAnimationSet animations = createAnimationSet();
        animations.addScript(0x10, new SpriteAnimationScript(0,
                List.of(0x59), SpriteAnimationEndAction.LOOP, 0));
        sprite.setAnimationSet(animations);
        sprite.setAnimationId(0);
        sprite.getAnimationManager().restoreRewindState(
                new PlayableSpriteAnimation.RewindState(0, 0));
        sprite.setAir(true);
        sprite.setSpringing(48);

        sprite.getAnimationManager().update(0);

        assertEquals(0, sprite.getAnimationId(),
                "The synthetic spring control lock must not replace Obj41's explicit anim byte");
    }

    @Test
    public void groundedSpiralAngleIsRenderedWithoutSyntheticAdvance() {
        TestablePlayableSprite sprite = createSprite(GameRules.SONIC_2);
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(0, new SpriteAnimationScript(0xFF,
                List.of(0x10), SpriteAnimationEndAction.LOOP, 0));
        animations.addScript(1, new SpriteAnimationScript(0xFF,
                List.of(0x20), SpriteAnimationEndAction.LOOP, 0));
        sprite.setAnimationSet(animations);
        sprite.setAnimationId(0);
        sprite.setMovementInputActive(true);
        sprite.setGSpeed((short) 0x0100);
        sprite.setFlipAngle(0xF2);
        sprite.setFlipSpeed(4);
        sprite.setFlipsRemaining(1);

        sprite.getAnimationManager().update(0);

        assertEquals(0xF2, sprite.getFlipAngle(),
                "grounded Obj06 owns flip_angle; Animate_Sonic only renders it");
        assertEquals(0x6A, sprite.getMappingFrame(),
                "SAnim_Tumble applies byte-sized +$0B to the object-published angle");
    }

    @Test
    public void s3kBarberPoleFlipTypeSelectsNativeTumbleSet() {
        TestablePlayableSprite sprite = createSprite(GameRules.SONIC_3K);
        ((ScriptedVelocityAnimationProfile) sprite.getAnimationProfile())
                .setTumbleFrameBase(0x31)
                .setTumbleTypeFrameBases(0, 0x3D, 0x49, 0x49);
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(0, new SpriteAnimationScript(0xFF,
                List.of(7), SpriteAnimationEndAction.LOOP, 0));
        sprite.setAnimationSet(animations);
        sprite.setAnimationId(0);
        sprite.setMovementInputActive(true);
        sprite.setFlipType(2);
        sprite.setFlipAngle(8);

        sprite.getAnimationManager().update(0);

        assertEquals(0x49, sprite.getMappingFrame(),
                "S3K byte_1286E selects the normal barber-pole tumble base");
        assertFalse(sprite.getRenderHFlip());
        assertFalse(sprite.getRenderVFlip());
    }

    @Test
    public void s3kNegativeFlipTypeEntersTumbleBeforeAngleAdvances() {
        TestablePlayableSprite sprite = createSprite(GameRules.SONIC_3K);
        ((ScriptedVelocityAnimationProfile) sprite.getAnimationProfile())
                .setTumbleFrameBase(0x31)
                .setTumbleTypeFrameBases(0, 0x3D, 0x49, 0x49);
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(0, new SpriteAnimationScript(0xFF,
                List.of(0x21), SpriteAnimationEndAction.LOOP, 0));
        sprite.setAnimationSet(animations);
        sprite.setAnimationId(0);
        sprite.setMovementInputActive(true);
        sprite.setFlipType(0x80);
        sprite.setFlipAngle(0);

        sprite.getAnimationManager().update(0);

        assertEquals(0x37, sprite.getMappingFrame(),
                "Signed flip_type selects Anim_Tumble before Obj31 advances flip_angle");
        assertFalse(sprite.getRenderHFlip());
        assertTrue(sprite.getRenderVFlip());
    }

    @Test
    public void s3kRunToWalkAnimationStepKeepsGroundPush() {
        TestablePlayableSprite sprite = createSprite(GameRules.SONIC_3K);
        sprite.setAnimationId(1);
        sprite.setMovementInputActive(true);
        sprite.setGSpeed((short) 0x0700);
        sprite.getAnimationManager().update(0);

        sprite.setPushing(true);
        sprite.setGSpeed((short) 0x0200);

        sprite.getAnimationManager().update(1);

        assertTrue(sprite.getPushing(),
                "S3K keeps Status_Push when only the engine's render-time Run id collapses to the ROM Walk anim byte");
        assertEquals(4, sprite.getAnimationId(),
                "The preserved push bit should continue selecting the push animation");
    }

    @Test
    public void s3kAirborneRollAnimationChangeClearsPush() {
        TestablePlayableSprite sprite = createSprite(GameRules.SONIC_3K);
        sprite.setAnimationId(5);
        sprite.getAnimationManager().update(0);

        sprite.setAir(true);
        sprite.setRolling(true);
        sprite.setJumping(true);
        sprite.setPushing(true);

        sprite.getAnimationManager().update(1);

        assertFalse(sprite.getPushing(),
                "S3K Animate_Tails2P clears Status_Push on anim != prev_anim even while airborne/rolling");
        assertEquals(2, sprite.getAnimationId(),
                "Airborne rolling Tails should keep the roll animation after clearing push");
    }

    @Test
    public void s2AirborneRollAnimationChangeClearsPush() {
        TestablePlayableSprite sprite = createSprite(GameRules.SONIC_2);
        sprite.setAnimationId(5);
        sprite.getAnimationManager().update(0);

        sprite.setAir(true);
        sprite.setRolling(true);
        sprite.setJumping(true);
        sprite.setPushing(true);

        sprite.getAnimationManager().update(1);

        assertFalse(sprite.getPushing(),
                "S2 Animate_Tails clears Status_Push on anim != prev_anim even while airborne/rolling");
        assertEquals(2, sprite.getAnimationId(),
                "Airborne rolling Tails should keep the roll animation after clearing push");
    }

    @Test
    public void s3kActivePushAnimationDoesNotClearEveryFrame() {
        TestablePlayableSprite sprite = createSprite(GameRules.SONIC_3K);
        sprite.setAnimationId(4);
        sprite.setMovementInputActive(true);
        sprite.setPushing(true);
        sprite.setGSpeed((short) 0x0200);

        sprite.getAnimationManager().update(0);

        assertTrue(sprite.getPushing(),
                "An already-displayed push animation with live push contact must remain stable");
        assertEquals(4, sprite.getAnimationId(),
                "Active wall-push contact should continue rendering push instead of flickering to walk");
    }

    @Test
    public void s3kRunToPushDoesNotUseIdleToWalkClear() {
        TestablePlayableSprite sprite = createSprite(GameRules.SONIC_3K);
        sprite.setAnimationId(1);
        sprite.setMovementInputActive(true);
        sprite.setPushing(true);
        sprite.setGSpeed((short) 0x0600);

        sprite.getAnimationManager().update(0);

        assertTrue(sprite.getPushing(),
                "Ground push should remain when the previous animation was not idle");
        assertEquals(4, sprite.getAnimationId(),
                "The existing push script should still render for non-idle previous animations");
    }

    @Test
    public void s1IdleToWalkDoesNotClearPush() {
        TestablePlayableSprite sprite = createSprite(GameRules.SONIC_1);
        sprite.setAnimationId(5);
        sprite.setMovementInputActive(true);
        sprite.setPushing(true);

        sprite.getAnimationManager().update(0);

        assertTrue(sprite.getPushing(),
                "S1 keeps the existing FixBugs-gated behavior for animation-change push clears");
        assertEquals(0, sprite.getAnimationId(),
                "S1 push rendering must preserve the ROM's movement-selected Walk animation byte");
    }

    @Test
    public void s1PushWaitsForWalkAnimationTickBeforeChangingMappingFrame() {
        TestablePlayableSprite sprite = createSprite(GameRules.SONIC_1);
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(0, new SpriteAnimationScript(0xFF,
                List.of(0x08), SpriteAnimationEndAction.LOOP, 0));
        animations.addScript(1, new SpriteAnimationScript(0xFF,
                List.of(0x1E), SpriteAnimationEndAction.LOOP, 0));
        animations.addScript(4, new SpriteAnimationScript(0xFD,
                List.of(0x45), SpriteAnimationEndAction.LOOP, 0));
        sprite.setAnimationSet(animations);
        sprite.setAnimationId(0);
        sprite.getAnimationManager().restoreRewindState(
                new PlayableSpriteAnimation.RewindState(0, 0));
        sprite.setAnimationFrameIndex(1);
        sprite.setAnimationTick(2);
        sprite.setMappingFrame(0x08);
        sprite.setMovementInputActive(true);
        sprite.setGSpeed((short) 0x00BB);
        sprite.setPushing(true);

        sprite.getAnimationManager().update(0);
        assertEquals(0, sprite.getAnimationId());
        assertEquals(0x08, sprite.getMappingFrame());

        sprite.getAnimationManager().update(1);
        assertEquals(0, sprite.getAnimationId());
        assertEquals(0x08, sprite.getMappingFrame());

        sprite.getAnimationManager().update(2);
        assertEquals(0, sprite.getAnimationId());
        assertEquals(0x45, sprite.getMappingFrame());

        sprite.getAnimationManager().update(3);
        assertEquals(0, sprite.getAnimationId());
        assertEquals(0x45, sprite.getMappingFrame(),
                "The push frame must persist while the ROM animation delay remains live");
    }

    @Test
    public void finalAnimationMatchingPrevAnimPreservesCadenceAcrossTransientWrites() {
        TestablePlayableSprite sprite = createSprite(GameRules.SONIC_1);
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(0, new SpriteAnimationScript(0,
                List.of(0x08), SpriteAnimationEndAction.LOOP, 0));
        animations.addScript(15, new SpriteAnimationScript(7,
                List.of(0x3C, 0x3D), SpriteAnimationEndAction.LOOP, 0));
        sprite.setAnimationSet(animations);
        // A terrain landing can write Walk before a later state owner restores
        // Spring in the same player dispatch. ROM Animate compares the final
        // anim byte with prev_anim; the transient write must not restart it.
        sprite.setAnimationId(0);
        sprite.setForcedAnimationId(15);
        sprite.getAnimationManager().restoreRewindState(
                new PlayableSpriteAnimation.RewindState(15, 0));
        sprite.setAnimationFrameIndex(1);
        sprite.setAnimationTick(1);
        sprite.setMappingFrame(0x3C);

        sprite.getAnimationManager().update(0);
        assertEquals(0x3C, sprite.getMappingFrame());
        assertEquals(0, sprite.getAnimationTick());

        sprite.getAnimationManager().update(1);
        assertEquals(0x3D, sprite.getMappingFrame(),
                "final anim == prev_anim continues the existing script instead of restarting it");
    }

    @Test
    public void classicRunFramesKeepRawWalkAnimationIdAcrossGames() {
        for (GameRules rules : List.of(GameRules.SONIC_1, GameRules.SONIC_2, GameRules.SONIC_3K)) {
            TestablePlayableSprite sprite = createSprite(rules);
            sprite.setAnimationProfile(new ScriptedVelocityAnimationProfile()
                    .setIdleAnimId(5)
                    .setWalkAnimId(0)
                    .setRunAnimId(1)
                    .setRunFramesUseWalkAnimationId(true)
                    .setRunSpeedThreshold(0x600));
            SpriteAnimationSet animations = new SpriteAnimationSet();
            animations.addScript(0, new SpriteAnimationScript(0xFF,
                    List.of(0x08), SpriteAnimationEndAction.LOOP, 0));
            animations.addScript(1, new SpriteAnimationScript(0xFF,
                    List.of(0x1E), SpriteAnimationEndAction.LOOP, 0));
            animations.addScript(5, new SpriteAnimationScript(0,
                    List.of(0x00), SpriteAnimationEndAction.LOOP, 0));
            sprite.setAnimationSet(animations);
            sprite.setAnimationId(0);
            sprite.setMovementInputActive(true);
            sprite.setGSpeed((short) 0x600);

            sprite.getAnimationManager().update(0);

            assertEquals(0, sprite.getAnimationId(),
                    "MoveLeft/MoveRight publish Walk in every classic game");
            assertEquals(0x1E, sprite.getMappingFrame(),
                    "Animate_* must still select the speed-gated Run frame script");
        }
    }

    @Test
    public void typedPlayerAnimationRuleClearsPushWithoutFallback() throws Exception {
        GameRules base = GameRules.SONIC_1;
        GameRules typedRules = new GameRules(
                base.playerMovement(),
                base.playerCapability(),
                base.collision(),
                GameRules.SONIC_2.playerAnimation(),
                base.camera(),
                base.ring(),
                base.objectInteraction(),
                base.sidekickCpu(),
                base.powerUp(),
                base.drowningBubble());
        TestablePlayableSprite sprite = createSprite(null);
        setGameRulesForTest(sprite, typedRules);
        sprite.setAnimationId(5);
        sprite.setMovementInputActive(false);
        sprite.getAnimationManager().update(0);

        sprite.setMovementInputActive(true);
        sprite.setPushing(true);

        sprite.getAnimationManager().update(1);

        assertFalse(sprite.getPushing(),
                "Typed PlayerAnimationRules.animationChangeClearsPush should clear push without legacy features");
    }

    @Test
    public void playerAnimationRuleUsesDefaultWhenTypedRulesMissing() throws Exception {
        TestablePlayableSprite sprite = createSprite(GameRules.SONIC_2);
        setGameRulesForTest(sprite, null);
        sprite.setAnimationId(5);
        sprite.setMovementInputActive(false);
        sprite.getAnimationManager().update(0);

        sprite.setMovementInputActive(true);
        sprite.setPushing(true);

        sprite.getAnimationManager().update(1);

        assertTrue(sprite.getPushing(),
                "Missing GameRules should not recreate removed feature-set animation rules");
    }

    @Test
    public void playerAnimationRuleUsesDefaultWhenTypedGroupMissing() throws Exception {
        GameRules base = GameRules.SONIC_1;
        GameRules rulesWithoutAnimationGroup = new GameRules(
                base.playerMovement(),
                base.playerCapability(),
                base.collision(),
                null,
                base.camera(),
                base.ring(),
                base.objectInteraction(),
                base.sidekickCpu(),
                base.powerUp(),
                base.drowningBubble());
        TestablePlayableSprite sprite = createSprite(GameRules.SONIC_2);
        setGameRulesForTest(sprite, rulesWithoutAnimationGroup);
        sprite.setAnimationId(5);
        sprite.setMovementInputActive(false);
        sprite.getAnimationManager().update(0);

        sprite.setMovementInputActive(true);
        sprite.setPushing(true);

        sprite.getAnimationManager().update(1);

        assertTrue(sprite.getPushing(),
                "A null PlayerAnimationRules group should not recreate removed feature-set animation rules");
    }

    @Test
    public void scriptedSwitchDoesNotRunOnFirstDisplayedFrame() {
        TestablePlayableSprite sprite = createSprite(GameRules.SONIC_3K);
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(0, new SpriteAnimationScript(0,
                List.of(7), SpriteAnimationEndAction.LOOP, 0));
        animations.addScript(0x10, new SpriteAnimationScript(0x2F,
                List.of(0x8E), SpriteAnimationEndAction.SWITCH, 0));
        sprite.setAnimationSet(animations);
        sprite.setObjectControlled(true);
        sprite.setAnimationId(0x10);
        sprite.forceAnimationRestart();

        sprite.getAnimationManager().update(0);

        assertEquals(0x10, sprite.getAnimationId(),
                "A one-frame $FD script must hold its frame for its delay before switching.");
        assertEquals(0x8E, sprite.getMappingFrame(),
                "The first update should display the scripted frame, not immediately fall through.");
    }

    @Test
    public void skidSwitchPublishesWalkEvenWhenBrakingRefreshedStopThisSlot() throws Exception {
        TestablePlayableSprite sprite = createSprite(GameRules.SONIC_3K);
        ((ScriptedVelocityAnimationProfile) sprite.getAnimationProfile()).setSkidAnimId(0x0D);
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(0, new SpriteAnimationScript(0,
                List.of(7), SpriteAnimationEndAction.LOOP, 0));
        animations.addScript(0x0D, new SpriteAnimationScript(0,
                List.of(0x8F), SpriteAnimationEndAction.SWITCH, 0));
        sprite.setAnimationSet(animations);
        sprite.setAnimationId(0x0D);
        sprite.setSkidding(true);
        sprite.forceAnimationRestart();

        sprite.getAnimationManager().update(0);
        Field refreshed = PlayableSpriteMovement.class
                .getDeclaredField("skidAnimationRefreshedThisFrame");
        refreshed.setAccessible(true);
        refreshed.setBoolean(sprite.getMovementManager(), true);
        sprite.getAnimationManager().update(1);

        assertEquals(0, sprite.getAnimationId(),
                "$FD runs after braking and must publish Walk in the same player slot");
        assertTrue(sprite.getSkidding(),
                "continuing braking must preserve the skid condition for the next movement slot");
        assertEquals(0x0D, sprite.getAnimationManager().captureRewindState().lastAnimationId(),
                "$FD must leave the native prev_anim byte on Stop");
        assertEquals(1, sprite.getAnimationFrameIndex(),
                "$FD must retain the Stop script command position");
        assertEquals(0x8F, sprite.getMappingFrame(),
                "$FD changes anim without replacing the mapping already displayed this frame");

        refreshed.setBoolean(sprite.getMovementManager(), false);
        sprite.getAnimationManager().update(2);

        assertEquals(0, sprite.getAnimationId());
        assertFalse(sprite.getSkidding(),
                "the engine skid latch ends once braking no longer refreshes Stop");
    }

    @Test
    public void s2WalkPublishesAdvancedFrameOnTickAfterTimerExpiry() {
        TestablePlayableSprite sprite = createSprite(GameRules.SONIC_2);
        ((ScriptedVelocityAnimationProfile) sprite.getAnimationProfile())
                .setWalkRunPublishesFrameBeforeTimerAdvance(true);
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(0, new SpriteAnimationScript(0xFF,
                List.of(0x0F, 0x10, 0x11), SpriteAnimationEndAction.LOOP, 0));
        animations.addScript(1, new SpriteAnimationScript(0xFF,
                List.of(0x1E), SpriteAnimationEndAction.LOOP, 0));
        sprite.setAnimationSet(animations);
        sprite.setAnimationId(0);
        sprite.setMovementInputActive(true);
        sprite.setGSpeed((short) 0x000C);

        sprite.getAnimationManager().update(0);

        assertEquals(0x0F, sprite.getMappingFrame());
        assertEquals(1, sprite.getAnimationFrameIndex(),
                "SAnim_WalkRun advances anim_frame after the expired timer");
        assertEquals(7, sprite.getAnimationTick());

        sprite.getAnimationManager().update(1);

        assertEquals(0x10, sprite.getMappingFrame(),
                "S2 reads the advanced anim_frame before the live timer returns");
        assertEquals(1, sprite.getAnimationFrameIndex());
        assertEquals(6, sprite.getAnimationTick());
    }

    @Test
    public void s2TailsWalkKeepsTimerFirstMappingCadence() {
        TestablePlayableSprite sprite = createSprite(GameRules.SONIC_2);
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(0, new SpriteAnimationScript(0xFF,
                List.of(0x0E, 0x0F), SpriteAnimationEndAction.LOOP, 0));
        animations.addScript(1, new SpriteAnimationScript(0xFF,
                List.of(0x20), SpriteAnimationEndAction.LOOP, 0));
        sprite.setAnimationSet(animations);
        sprite.setAnimationId(0);
        sprite.setMovementInputActive(true);
        sprite.setGSpeed((short) 0x000C);

        sprite.getAnimationManager().update(0);
        sprite.getAnimationManager().update(1);

        assertEquals(0x0E, sprite.getMappingFrame(),
                "S2 TAnim_WalkRunZoom gates mapping selection on its live timer");
        assertEquals(1, sprite.getAnimationFrameIndex());
        assertEquals(6, sprite.getAnimationTick());
    }

    @Test
    public void s2WalkPublishesCurrentFrameBeforeAdvancingExpiredTimer() {
        TestablePlayableSprite sprite = createSprite(GameRules.SONIC_2);
        ((ScriptedVelocityAnimationProfile) sprite.getAnimationProfile())
                .setWalkRunPublishesFrameBeforeTimerAdvance(true);
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(0, new SpriteAnimationScript(0xFF,
                List.of(0x0F, 0x10, 0x11), SpriteAnimationEndAction.LOOP, 0));
        animations.addScript(1, new SpriteAnimationScript(0xFF,
                List.of(0x1E), SpriteAnimationEndAction.LOOP, 0));
        sprite.setAnimationSet(animations);
        sprite.setAnimationId(0);
        sprite.setMovementInputActive(true);
        sprite.setGSpeed((short) 0x000C);

        sprite.getAnimationManager().update(0);
        sprite.setAnimationFrameIndex(1);
        sprite.setAnimationTick(0);
        sprite.getAnimationManager().update(1);

        assertEquals(0x10, sprite.getMappingFrame(),
                "SAnim_WalkRun publishes the current anim_frame before expiry processing");
        assertEquals(2, sprite.getAnimationFrameIndex(),
                "the expired timer advances anim_frame only after publication");
        assertEquals(7, sprite.getAnimationTick());
    }

    @Test
    public void s3kRollKeepsTimerFirstCadenceWhenWalkPublishesFirst() {
        TestablePlayableSprite sprite = createSprite(GameRules.SONIC_3K);
        ((ScriptedVelocityAnimationProfile) sprite.getAnimationProfile())
                .setWalkRunPublishesFrameBeforeTimerAdvance(true)
                .setRollAnimId(2)
                .setRoll2AnimId(3)
                .setRunSpeedThreshold(0x600);
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(2, new SpriteAnimationScript(0xFE,
                List.of(0x96, 0x97, 0x96, 0x98, 0x96, 0x99, 0x96, 0x9A),
                SpriteAnimationEndAction.LOOP, 0));
        sprite.setAnimationSet(animations);
        sprite.setAnimationId(2);
        sprite.getAnimationManager().restoreRewindState(
                new PlayableSpriteAnimation.RewindState(2, 2));
        sprite.setAnimationFrameIndex(7);
        sprite.setAnimationTick(1);
        sprite.setMappingFrame(0x96);
        sprite.setAir(true);
        sprite.setRolling(true);
        sprite.setJumping(true);
        sprite.setGSpeed((short) 0x218);

        sprite.getAnimationManager().update(0);

        assertEquals(0x96, sprite.getMappingFrame(),
                "loc_12A2A returns before publishing Roll while its timer remains live");
        assertEquals(0, sprite.getAnimationTick());

        sprite.getAnimationManager().update(1);

        assertEquals(0x9A, sprite.getMappingFrame(),
                "the expired Roll timer publishes the current native anim_frame");
        assertEquals(1, sprite.getAnimationTick());
    }

    @Test
    public void s3kNegativeFlipTypeDoesNotLetWalkTumbleOwnershipSuppressRollScript() {
        TestablePlayableSprite sprite = createSprite(GameRules.SONIC_3K);
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(2, new SpriteAnimationScript(0xFE,
                List.of(0x96, 0x97), SpriteAnimationEndAction.LOOP, 0));
        sprite.setAnimationSet(animations);
        sprite.setAnimationId(2);
        sprite.getAnimationManager().restoreRewindState(
                new PlayableSpriteAnimation.RewindState(0, 0));
        sprite.setAir(true);
        sprite.setRolling(true);
        sprite.setFlipType(0x80);
        sprite.setFlipAngle(0x20);
        sprite.setObjectMappingFrameControl(true);
        sprite.setMappingFrame(0x33);

        sprite.getAnimationManager().update(0);

        assertEquals(0x96, sprite.getMappingFrame(),
                "AniSonic02 timing $FE follows loc_12A2A instead of the $FF Anim_Tumble branch");
    }

    @Test
    public void s2TailsRunUsesNativeThreeFrameSlopeStride() {
        TestablePlayableSprite sprite = createSprite(GameRules.SONIC_2);
        ScriptedVelocityAnimationProfile profile =
                (ScriptedVelocityAnimationProfile) sprite.getAnimationProfile();
        profile.setRunFramesUseWalkAnimationId(true)
                .setAnglePreAdjust(true)
                .setWalkSlopeFrameStride(4)
                .setRunSlopeFrameStride(3)
                .setHighSpeedWalkRunAnimId(0x1F)
                .setHighSpeedWalkRunThreshold(0x700)
                .setHighSpeedSlopeFrameStride(3);
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(0, new SpriteAnimationScript(0xFF,
                List.of(0x0E), SpriteAnimationEndAction.LOOP, 0));
        animations.addScript(1, new SpriteAnimationScript(0xFF,
                List.of(0x2E), SpriteAnimationEndAction.LOOP, 0));
        animations.addScript(0x1F, new SpriteAnimationScript(0xFF,
                List.of(0x32, 0x33), SpriteAnimationEndAction.LOOP, 0));
        sprite.setAnimationSet(animations);
        sprite.setAnimationId(0);
        sprite.setMovementInputActive(true);
        sprite.setGSpeed((short) 0x600);
        sprite.setAngle((byte) 0xE8);
        sprite.setDirection(Direction.RIGHT);

        sprite.getAnimationManager().update(0);

        assertEquals(0x34, sprite.getMappingFrame(),
                "TAnim_WalkRunZoom adds angle bucket 2 * run stride 3 to frame $2E");
        assertEquals(0, sprite.getAnimationId(),
                "the internal run script must not replace the native Walk animation byte");
    }

    @Test
    public void s2TailsSelectsPrivateHaulAssTierWithoutChangingAnimationId() {
        TestablePlayableSprite sprite = createSprite(GameRules.SONIC_2);
        ScriptedVelocityAnimationProfile profile =
                (ScriptedVelocityAnimationProfile) sprite.getAnimationProfile();
        profile.setRunFramesUseWalkAnimationId(true)
                .setAnglePreAdjust(true)
                .setWalkSlopeFrameStride(4)
                .setRunSlopeFrameStride(3)
                .setHighSpeedWalkRunAnimId(0x1F)
                .setHighSpeedWalkRunThreshold(0x700)
                .setHighSpeedSlopeFrameStride(3);
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(0, new SpriteAnimationScript(0xFF,
                List.of(0x0E), SpriteAnimationEndAction.LOOP, 0));
        animations.addScript(1, new SpriteAnimationScript(0xFF,
                List.of(0x2E), SpriteAnimationEndAction.LOOP, 0));
        animations.addScript(0x1F, new SpriteAnimationScript(0xFF,
                List.of(0x32, 0x33), SpriteAnimationEndAction.LOOP, 0));
        sprite.setAnimationSet(animations);
        sprite.setAnimationId(0);
        sprite.setMovementInputActive(true);
        sprite.setGSpeed((short) 0x940);
        sprite.setAngle((byte) 0xD8);
        sprite.setDirection(Direction.RIGHT);

        sprite.getAnimationManager().update(0);

        assertEquals(0x38, sprite.getMappingFrame(),
                "TailsAni_HaulAss frame $32 uses the retained three-frame slope stride");
        assertEquals(0, sprite.getAnimationId(),
                "TailsAni_HaulAss is a private pointer inside raw Walk animation");
    }

    @Test
    public void s3kTailsHighSpeedTierUsesSingleFrameSlopeStride() {
        TestablePlayableSprite sprite = createSprite(GameRules.SONIC_3K);
        ScriptedVelocityAnimationProfile profile =
                (ScriptedVelocityAnimationProfile) sprite.getAnimationProfile();
        profile.setAnglePreAdjust(true)
                .setWalkRunPublishesFrameBeforeTimerAdvance(true)
                .setWalkSlopeFrameStride(4)
                .setRunSlopeFrameStride(2)
                .setHighSpeedWalkRunAnimId(0x1F)
                .setHighSpeedWalkRunThreshold(0x700)
                .setHighSpeedSlopeFrameStride(1);
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(0, new SpriteAnimationScript(0xFF,
                List.of(0x0E), SpriteAnimationEndAction.LOOP, 0));
        animations.addScript(1, new SpriteAnimationScript(0xFF,
                List.of(0x2E), SpriteAnimationEndAction.LOOP, 0));
        animations.addScript(0x1F, new SpriteAnimationScript(0xFF,
                List.of(0xC3, 0xC4), SpriteAnimationEndAction.LOOP, 0));
        sprite.setAnimationSet(animations);
        sprite.setAnimationId(0);
        sprite.setMovementInputActive(true);
        sprite.setGSpeed((short) 0x940);
        sprite.setAngle((byte) 0xE8);
        sprite.setDirection(Direction.RIGHT);

        sprite.getAnimationManager().update(0);

        assertEquals(0xC5, sprite.getMappingFrame(),
                "S3K AniTails1F adds angle bucket 2 * high-speed stride 1");
    }

    @Test
    public void s2TailsSlidingSpeedReachesPrivateHighSpeedTier() {
        TestablePlayableSprite sprite = createSprite(GameRules.SONIC_2);
        ScriptedVelocityAnimationProfile profile =
                (ScriptedVelocityAnimationProfile) sprite.getAnimationProfile();
        profile.setAnglePreAdjust(true)
                .setDoubleWalkRunAnimationSpeedWhenSliding(true)
                .setHighSpeedWalkRunAnimId(0x1F)
                .setHighSpeedWalkRunThreshold(0x700)
                .setHighSpeedSlopeFrameStride(3);
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(0, new SpriteAnimationScript(0xFF,
                List.of(0x0E), SpriteAnimationEndAction.LOOP, 0));
        animations.addScript(1, new SpriteAnimationScript(0xFF,
                List.of(0x2E), SpriteAnimationEndAction.LOOP, 0));
        animations.addScript(0x1F, new SpriteAnimationScript(0xFF,
                List.of(0x32), SpriteAnimationEndAction.LOOP, 0));
        sprite.setAnimationSet(animations);
        sprite.setAnimationId(0);
        sprite.setMovementInputActive(true);
        sprite.setSliding(true);
        sprite.setGSpeed((short) 0x380);
        sprite.setAngle((byte) 0);

        sprite.getAnimationManager().update(0);

        assertEquals(0x32, sprite.getMappingFrame(),
                "status_secondary.sliding doubles $380 to the native $700 tier threshold");
    }

    @Test
    public void s3kSlowSteepSlopeTurnaroundRefreshesOrientationBeforeWalkTickExpires() {
        TestablePlayableSprite sprite = createSprite(GameRules.SONIC_3K);
        ((ScriptedVelocityAnimationProfile) sprite.getAnimationProfile())
                .setWalkRunPublishesFrameBeforeTimerAdvance(true);
        SpriteAnimationSet animations = new SpriteAnimationSet();
        SpriteAnimationScript walk = new SpriteAnimationScript(0xFF,
                List.of(10, 11, 12, 13), SpriteAnimationEndAction.LOOP, 0);
        animations.addScript(0, walk);
        animations.addScript(1, walk);
        sprite.setAnimationSet(animations);
        sprite.setMovementInputActive(true);
        sprite.setGSpeed((short) 0x0100);
        sprite.setAngle((byte) 0x60);
        sprite.setDirection(Direction.LEFT);

        sprite.getAnimationManager().update(0);

        assertEquals(22, sprite.getMappingFrame(),
                "Left-facing steep slope should display the matching high-angle walk set");
        assertFalse(sprite.getRenderVFlip(),
                "The first steep slope orientation should not be vertically flipped");

        sprite.setDirection(Direction.RIGHT);
        sprite.getAnimationManager().update(1);

        assertEquals(15, sprite.getMappingFrame(),
                "S3K republishes the advanced frame from the newly mirrored slope bank");
        assertTrue(sprite.getRenderVFlip(),
                "S3K refreshes walk/run mapping orientation before its timer gate");
    }

    @Test
    public void walkDelayHoldsMappingAcrossRunThresholdChange() {
        TestablePlayableSprite sprite = createSprite(GameRules.SONIC_1);
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(0, new SpriteAnimationScript(0xFF,
                List.of(0x08), SpriteAnimationEndAction.LOOP, 0));
        animations.addScript(1, new SpriteAnimationScript(0xFF,
                List.of(0x1E), SpriteAnimationEndAction.LOOP, 0));
        sprite.setAnimationSet(animations);
        sprite.setAnimationId(0);
        sprite.getAnimationManager().restoreRewindState(
                new PlayableSpriteAnimation.RewindState(0, 0));
        sprite.setAnimationFrameIndex(1);
        sprite.setAnimationTick(1);
        sprite.setMappingFrame(0x08);
        sprite.setMovementInputActive(true);
        sprite.setGSpeed((short) 0x1000);
        sprite.setForcedAnimationId(0);

        sprite.getAnimationManager().update(0);

        assertEquals(0x08, sprite.getMappingFrame(),
                "Crossing into run speed must not replace the latched walk frame before obTimeFrame expires");
    }

    @Test
    public void retainedDeletedNativeSlotDoesNotDispatchAnimationScript() {
        TestablePlayableSprite sprite = createSprite(GameRules.SONIC_1);
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(0x1C, new SpriteAnimationScript(
                0, List.of(0), SpriteAnimationEndAction.SWITCH, 0));
        sprite.setAnimationSet(animations);
        sprite.setAnimationId(0x1C);
        sprite.setForcedAnimationId(0x1C);
        sprite.setNativeSlotPresent(false);
        sprite.setAnimationFrameIndex(1);

        sprite.getAnimationManager().update(0);

        assertEquals(0x1C, sprite.getAnimationId(),
                "an absent native player SST cannot execute its $FD switch to Walk");
    }

    @Test
    public void hiddenObjectControlledNativeSlotStillDispatchesAnimationScript() {
        TestablePlayableSprite sprite = createSprite(GameRules.SONIC_3K);
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(0x1C, new SpriteAnimationScript(
                0, List.of(0), SpriteAnimationEndAction.SWITCH, 0));
        sprite.setAnimationSet(animations);
        sprite.setAnimationId(0x1C);
        sprite.setForcedAnimationId(0x1C);
        sprite.setHidden(true);
        sprite.setObjectControlled(true);
        sprite.setObjectControlAllowsCpu(false);
        sprite.setObjectControlSuppressesMovement(true);
        sprite.getAnimationManager().update(0);
        sprite.setAnimationFrameIndex(1);

        sprite.getAnimationManager().update(1);

        assertEquals(0, sprite.getAnimationId(),
                "visibility and object control must not imply that a native SST slot was deleted");
    }

    @Test
    public void s1WalkAtAngle18UsesNativeFlippedFourthSlopeBank() {
        TestablePlayableSprite sprite = createSprite(GameRules.SONIC_1);
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(0, new SpriteAnimationScript(0xFF,
                List.of(0x08, 0x09, 0x0A, 0x0B, 0x06, 0x07),
                SpriteAnimationEndAction.LOOP, 0));
        animations.addScript(1, new SpriteAnimationScript(0xFF,
                List.of(0x1E, 0x1F, 0x20, 0x21),
                SpriteAnimationEndAction.LOOP, 0));
        sprite.setAnimationSet(animations);
        sprite.setAnimationId(0);
        sprite.getAnimationManager().restoreRewindState(
                new PlayableSpriteAnimation.RewindState(0, 0));
        sprite.setAnimationFrameIndex(2);
        sprite.setAnimationTick(0);
        sprite.setMovementInputActive(true);
        sprite.setGSpeed((short) 0x0100);
        sprite.setAngle((byte) 0x18);
        sprite.setDirection(Direction.RIGHT);

        sprite.getAnimationManager().update(0);

        assertEquals(0, sprite.getAnimationId(),
                "S1 retains raw id_Walk while its special handler selects slope mappings");
        assertEquals(3, sprite.getAnimationFrameIndex(),
                "Sonic_Animate increments obAniFrame after loading script frame $0A");
        assertEquals(0x1C, sprite.getMappingFrame(),
                "not($18)+$10 quantizes to octant 6; Walk adds 6*3 to frame $0A");
        assertTrue(sprite.getRenderHFlip(),
                "The negative transformed angle sets the native X flip");
        assertTrue(sprite.getRenderVFlip(),
                "The negative transformed angle sets the native Y flip");

        sprite.getAnimationManager().update(1);

        assertEquals(0x1C, sprite.getMappingFrame(),
                "S1 must retain the slope mapping while obTimeFrame remains non-negative");
        assertTrue(sprite.getRenderHFlip(),
                "The delayed S1 walk frame must retain its paired native X flip");
        assertTrue(sprite.getRenderVFlip(),
                "The delayed S1 walk frame must retain its paired native Y flip");
    }

    private static TestablePlayableSprite createSprite(GameRules featureSet) {
        TestablePlayableSprite sprite = new TestablePlayableSprite("tails", (short) 0, (short) 0);
        sprite.setGameRulesForTest(featureSet);
        ScriptedVelocityAnimationProfile profile = new ScriptedVelocityAnimationProfile()
                .setIdleAnimId(5)
                .setWalkAnimId(0)
                .setRunAnimId(1)
                .setRollAnimId(2)
                .setPushAnimId(4)
                .setAirAnimId(0)
                .setRunSpeedThreshold(0x600);
        if (featureSet == GameRules.SONIC_1 || featureSet == GameRules.SONIC_2) {
            profile.setPushUsesWalkSpecialHandler(true);
        }
        sprite.setAnimationProfile(profile);
        sprite.setAnimationSet(createAnimationSet());
        sprite.setAir(false);
        sprite.setRolling(false);
        sprite.setGSpeed((short) 0);
        return sprite;
    }

    private static void setGameRulesForTest(TestablePlayableSprite sprite, GameRules rules) throws Exception {
        Field field = AbstractPlayableSprite.class.getDeclaredField("gameRules");
        field.setAccessible(true);
        field.set(sprite, rules);
    }

    private static SpriteAnimationSet createAnimationSet() {
        SpriteAnimationSet set = new SpriteAnimationSet();
        SpriteAnimationScript script = new SpriteAnimationScript(0, List.of(0), SpriteAnimationEndAction.LOOP, 0);
        set.addScript(0, script);
        set.addScript(1, script);
        set.addScript(2, script);
        set.addScript(4, script);
        set.addScript(5, script);
        return set;
    }
}
