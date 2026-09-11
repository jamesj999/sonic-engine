package com.openggf.game.sonic3k;

import com.openggf.game.GameModuleRegistry;
import com.openggf.game.GameServices;
import com.openggf.game.ShieldType;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.Knuckles;
import com.openggf.sprites.playable.Sonic;
import com.openggf.sprites.playable.Tails;
import com.openggf.sprites.managers.PlayableSpriteMovement;
import com.openggf.tests.FullReset;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(SingletonResetExtension.class)
@FullReset
class TestSonic3kSuperTransformationEligibility {

    @BeforeEach
    void setUp() {
        GameModuleRegistry.setCurrent(new Sonic3kGameModule());
        TestEnvironment.activeGameplayMode();
        GameServices.level().resetLevelGamestate(GameModuleRegistry.getCurrent().createLevelState());
    }

    @Test
    void convertedChaosEmeraldsCannotTransformSonicOrKnuckles() {
        collectAllChaosEmeralds();
        GameServices.gameState().setEmeraldsConverted(true);

        assertFalse(eligible(new Sonic("sonic", (short) 0, (short) 0)));
        assertFalse(eligible(new Knuckles("knuckles", (short) 0, (short) 0)));
    }

    @Test
    void allSuperEmeraldsTransformEveryMainCharacter() {
        collectAllChaosEmeralds();
        GameServices.gameState().setEmeraldsConverted(true);
        collectAllSuperEmeralds();

        assertTrue(eligible(new Sonic("sonic", (short) 0, (short) 0)));
        assertTrue(eligible(new Tails("tails", (short) 0, (short) 0)));
        assertTrue(eligible(new Knuckles("knuckles", (short) 0, (short) 0)));
    }

    @Test
    void chaosEmeraldsAloneDoNotTransformTails() {
        collectAllChaosEmeralds();

        assertFalse(eligible(new Tails("tails", (short) 0, (short) 0)));
    }

    @Test
    void cpuTailsNeverTransforms() {
        collectAllChaosEmeralds();
        collectAllSuperEmeralds();
        Tails tails = new Tails("tails", (short) 0, (short) 0);
        tails.setCpuControlled(true);

        assertFalse(eligible(tails));
    }

    @Test
    void sonicElementalShieldBlocksButBasicShieldAllowsTransformation() {
        collectAllChaosEmeralds();
        Sonic elemental = new Sonic("sonic", (short) 0, (short) 0);
        elemental.giveShield(ShieldType.FIRE);
        Sonic basic = new Sonic("sonic", (short) 0, (short) 0);
        basic.giveShield(ShieldType.BASIC);

        assertFalse(eligible(elemental));
        assertTrue(eligible(basic));
    }

    @Test
    void invincibilityBlocksOnlySonic() {
        collectAllChaosEmeralds();
        collectAllSuperEmeralds();
        Sonic sonic = new Sonic("sonic", (short) 0, (short) 0);
        Tails tails = new Tails("tails", (short) 0, (short) 0);
        Knuckles knuckles = new Knuckles("knuckles", (short) 0, (short) 0);
        sonic.setInvincibleFrames(60);
        tails.setInvincibleFrames(60);
        knuckles.setInvincibleFrames(60);

        assertFalse(eligible(sonic));
        assertTrue(eligible(tails));
        assertTrue(eligible(knuckles));
    }

    @Test
    void pausedHudTimerBlocksEveryCharacter() {
        collectAllChaosEmeralds();
        collectAllSuperEmeralds();
        GameServices.level().getLevelGamestate().pauseTimer();

        assertFalse(eligible(new Sonic("sonic", (short) 0, (short) 0)));
        assertFalse(eligible(new Tails("tails", (short) 0, (short) 0)));
        assertFalse(eligible(new Knuckles("knuckles", (short) 0, (short) 0)));
    }

    @Test
    void eligibleMainTailsTransformsBeforeFlight() throws Exception {
        collectAllChaosEmeralds();
        collectAllSuperEmeralds();
        Tails tails = new Tails("tails", (short) 0, (short) 0);

        triggerSecondPress(tails);

        assertTrue(tails.isSuperSonic());
        assertEquals(0x29, tails.getForcedAnimationId());
        assertFalse(tails.getTailsFlightController().isActive());
    }

    @Test
    void chaosOnlyMainTailsFallsBackToFlight() throws Exception {
        collectAllChaosEmeralds();
        Tails tails = new Tails("tails", (short) 0, (short) 0);

        triggerSecondPress(tails);

        assertFalse(tails.isSuperSonic());
        assertTrue(tails.getTailsFlightController().isActive());
    }

    @Test
    void eligibleKnucklesTransformsBeforeGlide() throws Exception {
        collectAllChaosEmeralds();
        Knuckles knuckles = new Knuckles("knuckles", (short) 0, (short) 0);

        triggerSecondPress(knuckles);

        assertTrue(knuckles.isSuperSonic());
        assertTrue(knuckles.getDoubleJumpFlag() == 0,
                "Knuckles must not enter the glide state when transformation consumes the press");
    }

    private boolean eligible(AbstractPlayableSprite player) {
        player.setRingCount(50);
        GameServices.sprites().clearAllSprites();
        GameServices.sprites().addSprite(player, player.getCode());
        player.setSuperStateController(new Sonic3kSuperStateController(player));
        return player.getSuperStateController().activateFromAirAbility();
    }

    private void triggerSecondPress(AbstractPlayableSprite player) throws Exception {
        player.setRingCount(50);
        player.setAir(true);
        player.setJumping(true);
        player.setYSpeed((short) -0x400);
        player.setDoubleJumpFlag(0);
        GameServices.sprites().clearAllSprites();
        GameServices.sprites().addSprite(player, player.getCode());
        player.setSuperStateController(new Sonic3kSuperStateController(player));
        PlayableSpriteMovement movement = (PlayableSpriteMovement) player.getMovementManager();
        setMovementField(movement, "jumpReleasedSinceJump", true);
        setMovementField(movement, "inputJumpPress", true);
        setMovementField(movement, "inputJump", true);
        Method jumpHeight = PlayableSpriteMovement.class.getDeclaredMethod("doJumpHeight");
        jumpHeight.setAccessible(true);
        jumpHeight.invoke(movement);
    }

    private void setMovementField(PlayableSpriteMovement movement, String name, boolean value) throws Exception {
        Field field = PlayableSpriteMovement.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setBoolean(movement, value);
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
}
