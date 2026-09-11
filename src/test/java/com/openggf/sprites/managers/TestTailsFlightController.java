package com.openggf.sprites.managers;

import com.openggf.audio.GameSound;
import com.openggf.audio.rewind.AudioCommand;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.sprites.animation.ScriptedVelocityAnimationProfile;
import com.openggf.sprites.animation.SpriteAnimationEndAction;
import com.openggf.sprites.animation.SpriteAnimationScript;
import com.openggf.sprites.animation.SpriteAnimationSet;
import com.openggf.sprites.playable.Tails;
import com.openggf.tests.FullReset;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(SingletonResetExtension.class)
@FullReset
class TestTailsFlightController {
    private Tails tails;
    private TailsFlightController flight;

    @BeforeEach
    void setUp() {
        TestEnvironment.configureGameModuleFixture(new Sonic3kGameModule());
        tails = new Tails("tails", (short) 100, (short) 100);
        tails.setCentreY((short) 100);
        flight = tails.getTailsFlightController();
        GameServices.camera().setMinY((short) 0);
        GameServices.audio().commandTimeline().clear();
    }

    @Test
    void constructorRejectsNullSprite() {
        assertThrows(NullPointerException.class, () -> new TailsFlightController(null));
    }

    @Test
    void activationSetsRomStateAndRestoresRollingDimensions() {
        tails.setRollingJump(true);
        tails.setRolling(true);
        tails.setCentreY((short) 100);

        flight.activate();

        assertTrue(flight.isActive());
        assertEquals(1, tails.getDoubleJumpFlag());
        assertEquals(TailsFlightController.FLIGHT_TIME, tails.getDoubleJumpProperty() & 0xFF);
        assertFalse(tails.getRolling());
        assertFalse(tails.getRollingJump());
        assertEquals(tails.getStandYRadius(), tails.getYRadius());
        assertEquals(99, tails.getCentreY(),
                "ROM adds old y_radius-default_y_radius to y_pos");
        assertEquals(0x20, tails.getAnimationId());
    }

    @Test
    void manualFlightAnimationSurvivesTheGenericAirAnimationPass() {
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(0, new SpriteAnimationScript(
                0, List.of(0x01), SpriteAnimationEndAction.LOOP, 0));
        animations.addScript(0x20, new SpriteAnimationScript(
                0x1F, List.of(0xA0), SpriteAnimationEndAction.LOOP, 0));
        tails.setAnimationSet(animations);
        tails.setAnimationProfile(new ScriptedVelocityAnimationProfile()
                .setAirAnimId(0)
                .setWalkAnimId(0)
                .setRunAnimId(0)
                .setRollAnimId(0));
        tails.setAir(true);
        tails.setJumping(true);

        flight.activate();
        tails.getAnimationManager().update(0);

        assertEquals(0x20, tails.getAnimationId(),
                "Tails_Set_Flying_Animation owns the ROM anim byte during active flight");
        assertEquals(0xA0, tails.getMappingFrame(),
                "AniTails20 must render the flying body frame after the normal animation phase");
    }

    @Test
    void manualSwimmingAnimationSurvivesTheGenericAirAnimationPass() {
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(0, new SpriteAnimationScript(
                0, List.of(0x01), SpriteAnimationEndAction.LOOP, 0));
        animations.addScript(0x25, new SpriteAnimationScript(
                7, List.of(0xBD), SpriteAnimationEndAction.LOOP, 0));
        tails.setAnimationSet(animations);
        tails.setAnimationProfile(new ScriptedVelocityAnimationProfile()
                .setAirAnimId(0)
                .setWalkAnimId(0)
                .setRunAnimId(0)
                .setRollAnimId(0));
        tails.setAir(true);
        tails.setJumping(true);
        tails.setInWater(true);

        flight.activate();
        tails.getAnimationManager().update(0);

        assertEquals(0x25, tails.getAnimationId(),
                "the underwater Tails_Set_Flying_Animation branch owns the ROM anim byte");
        assertEquals(0xBD, tails.getMappingFrame(),
                "AniTails25 must render the swimming body frame after the normal animation phase");
    }

    @Test
    void reverseGravityPreservesLockedOnWrongRegisterRadiusDelta() {
        GameServices.gameState().setReverseGravityActive(true);
        tails.setRolling(true);
        tails.setCentreY((short) 100);

        flight.activate();

        assertEquals(99, tails.getCentreY(),
                "locked-on negates d0, not the d1 radius delta actually applied");
    }

    @Test
    void nonRollingActivationLeavesCurrentRadiiAndCentreUntouched() {
        tails.applyCustomRadii(8, 12);
        tails.setCentreY((short) 100);

        flight.activate();

        assertEquals(12, tails.getYRadius());
        assertEquals(100, tails.getCentreY());
    }

    @Test
    void timerDecrementsOnlyOnOddRomVisibleFrames() {
        activateReadyAt(0);

        flight.updateVertical(false, false, 2);
        assertEquals(TailsFlightController.FLIGHT_TIME, tails.getDoubleJumpProperty() & 0xFF);

        flight.updateVertical(false, false, 3);
        assertEquals(TailsFlightController.FLIGHT_TIME - 1, tails.getDoubleJumpProperty() & 0xFF);
    }

    @Test
    void readyJumpStartsFlapButCurrentFrameStillAddsEight() {
        activateReadyAt(0);

        flight.updateVertical(true, false, 0);

        assertEquals(2, tails.getDoubleJumpFlag());
        assertEquals(0x08, tails.getYSpeed());
    }

    @Test
    void flapStateSubtractsTwentyWithoutReadyGravity() {
        tails.setDoubleJumpFlag(2);
        tails.setDoubleJumpProperty((byte) 10);
        tails.setYSpeed((short) 0);

        flight.updateVertical(false, false, 0);

        assertEquals(3, tails.getDoubleJumpFlag());
        assertEquals(-0x20, tails.getYSpeed());
    }

    @Test
    void flapResetsBeforeSubtractionWhenAlreadyAboveVelocityThreshold() {
        tails.setDoubleJumpFlag(7);
        tails.setDoubleJumpProperty((byte) 10);
        tails.setYSpeed((short) -0x101);

        flight.updateVertical(false, false, 0);

        assertEquals(1, tails.getDoubleJumpFlag());
        assertEquals(-0x101, tails.getYSpeed());
    }

    @Test
    void flapStateOneFResetsAfterIncrementingToTwenty() {
        tails.setDoubleJumpFlag(0x1F);
        tails.setDoubleJumpProperty((byte) 10);
        tails.setYSpeed((short) 0);

        flight.updateVertical(false, false, 0);

        assertEquals(1, tails.getDoubleJumpFlag());
        assertEquals(-0x20, tails.getYSpeed());
    }

    @Test
    void exhaustedReadyStateCannotStartFlap() {
        tails.setDoubleJumpFlag(1);
        tails.setDoubleJumpProperty((byte) 0);
        tails.setYSpeed((short) 0);

        flight.updateVertical(true, false, 0);

        assertEquals(1, tails.getDoubleJumpFlag());
        assertEquals(0x08, tails.getYSpeed());
        assertEquals(0x24, tails.getAnimationId());
    }

    @Test
    void upwardVelocityClampsWithinTenHexPixelsOfCameraMinimum() {
        GameServices.camera().setMinY((short) 100);
        tails.setCentreY((short) 116);
        tails.setDoubleJumpFlag(2);
        tails.setDoubleJumpProperty((byte) 10);
        tails.setYSpeed((short) 0);

        flight.updateVertical(false, false, 0);

        assertEquals(0, tails.getYSpeed());
    }

    @Test
    void cameraClampComparesWrappedCoordinatesAsUnsignedWords() {
        GameServices.camera().setMinY((short) 0xFFF0);
        tails.setCentreY((short) 0x0001);
        tails.setDoubleJumpFlag(2);
        tails.setDoubleJumpProperty((byte) 10);
        tails.setYSpeed((short) 0);

        flight.updateVertical(false, false, 0);

        assertEquals(0, tails.getYSpeed(),
                "ROM compares unsigned 16-bit camera and player coordinates");
    }

    @Test
    void airAnimationsUseNativeTwentyThroughTwentyFourAndPriorCarryState() {
        assertAnimation(false, false, 1, 0x20);
        assertAnimation(false, false, -1, 0x21);
        assertAnimation(false, true, 1, 0x22);
        assertAnimation(false, true, -1, 0x23);
        assertAnimation(false, true, 1, 0, 0x24);
    }

    @Test
    void swimAnimationsUseNativeTwentyFiveThroughTwentyEight() {
        assertAnimation(true, false, 1, 0x25);
        assertAnimation(true, false, -1, 0x26);
        assertAnimation(true, true, 1, 0x27);
        assertAnimation(true, true, -1, 0x27);
        assertAnimation(true, true, 1, 0, 0x28);
    }

    @Test
    void airSoundUsesOnScreenSixteenFrameCadenceAndTiredVariant() {
        tails.setCentreX((short) 100);
        tails.setCentreY((short) 100);
        activateReadyAt(0);

        flight.updateVertical(false, false, 7);
        assertEquals(0, GameServices.audio().commandTimeline().entryCount());

        flight.updateVertical(false, false, 8);
        assertLastSound(GameSound.TAILS_FLYING);

        GameServices.audio().commandTimeline().clear();
        tails.setDoubleJumpProperty((byte) 0);
        flight.updateVertical(false, false, 8);
        assertLastSound(GameSound.TAILS_FLY_TIRED);

        GameServices.audio().commandTimeline().clear();
        tails.setCentreX((short) 1000);
        flight.updateVertical(false, false, 8);
        assertEquals(0, GameServices.audio().commandTimeline().entryCount());
    }

    @Test
    void swimmingIsSilent() {
        tails.setInWater(true);
        activateReadyAt(0);

        flight.updateVertical(false, false, 8);

        assertEquals(0, GameServices.audio().commandTimeline().entryCount());
    }

    @Test
    void underwaterCarryBlocksNewFlapWithoutClearingTimer() {
        tails.setInWater(true);
        activateReadyAt(0);

        flight.updateVertical(true, true, 0);

        assertEquals(1, tails.getDoubleJumpFlag());
        assertEquals(TailsFlightController.FLIGHT_TIME, tails.getDoubleJumpProperty() & 0xFF);
        assertEquals(0x08, tails.getYSpeed());
    }

    @Test
    void clearRemovesFlightState() {
        flight.activate();

        flight.clear();

        assertFalse(flight.isActive());
        assertEquals(0, tails.getDoubleJumpFlag());
        assertEquals(0, tails.getDoubleJumpProperty());
    }

    private void activateReadyAt(int ySpeed) {
        tails.setDoubleJumpFlag(1);
        tails.setDoubleJumpProperty((byte) TailsFlightController.FLIGHT_TIME);
        tails.setYSpeed((short) ySpeed);
    }

    private void assertAnimation(boolean underwater, boolean carrying, int ySpeed, int expected) {
        assertAnimation(underwater, carrying, ySpeed, 1, expected);
    }

    private void assertAnimation(boolean underwater, boolean carrying, int ySpeed,
                                 int timer, int expected) {
        tails.setInWater(underwater);
        tails.setDoubleJumpFlag(1);
        tails.setDoubleJumpProperty((byte) timer);
        tails.setYSpeed((short) (ySpeed - 8));

        flight.updateVertical(false, carrying, 0);

        assertEquals(expected, tails.getAnimationId());
    }

    private void assertLastSound(GameSound expected) {
        var entries = GameServices.audio().commandTimeline().entries();
        assertEquals(1, entries.size());
        AudioCommand.PlaySfx command = (AudioCommand.PlaySfx) entries.get(0).command();
        assertEquals(expected.name(), command.sfxName());
    }
}
