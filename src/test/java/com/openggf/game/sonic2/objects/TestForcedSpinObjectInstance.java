package com.openggf.game.sonic2.objects;

import com.openggf.game.GroundMode;
import com.openggf.game.rules.GameRules;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.sprites.animation.ScriptedVelocityAnimationProfile;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SidekickCpuController;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestForcedSpinObjectInstance {

    @Test
    void autorollPreservesXPosWhenTriggeredOnWallMode() throws Exception {
        ForcedSpinObjectInstance trigger = new ForcedSpinObjectInstance(
                new ObjectSpawn(0x1C60, 0x0438, Sonic2ObjectIds.FORCED_SPIN, 0, 0, false, 0),
                "ForcedSpin");
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x1C6C, (short) 0x0431);
        player.setGroundMode(GroundMode.RIGHTWALL);

        short centreX = player.getCentreX();
        short centreY = player.getCentreY();

        Method enablePinballMode = ForcedSpinObjectInstance.class
                .getDeclaredMethod("enablePinballMode", AbstractPlayableSprite.class);
        enablePinballMode.setAccessible(true);
        enablePinballMode.invoke(trigger, player);

        assertEquals(centreX, player.getCentreX(),
                "Obj84 loc_212C4 sets rolling radii and adds 5 to y_pos, but never changes x_pos");
        assertEquals((short) (centreY + 5), player.getCentreY());
        assertTrue(player.getRolling());
        assertTrue(player.getPinballMode());
        assertTrue(player.getSpindash(),
                "S2 Obj84 writes pinball_mode, which aliases the spindash flag byte");
    }

    @Test
    void forcedSpinEntryKeepsRollAnimationWhenPinballModeSharesSpindashByte() {
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        player.setGameRulesForTest(GameRules.SONIC_2);
        ScriptedVelocityAnimationProfile profile = s2AnimationProfile();
        player.setAnimationProfile(profile);
        player.setAnimationId(Sonic2AnimationIds.WAIT.id());
        player.setCentreX((short) 0x0100);
        player.setCentreY((short) 0x0120);

        ForcedSpinObjectInstance trigger = new ForcedSpinObjectInstance(
                new ObjectSpawn(0x0100, 0x0120, Sonic2ObjectIds.FORCED_SPIN, 0, 0, false, 0x0120),
                "ForcedSpin");
        trigger.setServices(new QueryOnlyPlayerServices(player, List.of()));

        trigger.update(0, player);

        assertTrue(player.getPinballMode(), "Obj84 should enable pinball mode for forced tunnel rolling");
        assertTrue(player.getRolling(), "Obj84 should put the player into rolling state");
        assertEquals(Sonic2AnimationIds.ROLL.id(), player.getAnimationId(),
                "HTZ/CNZ forced-spin tunnels use normal rolling animation, not spindash");
        assertTrue(player.getSpindash(),
                "S2 Obj84 still mirrors pinball_mode into the ROM spindash byte for movement/sidekick logic");

        Integer resolvedAnimation = profile.resolveAnimationId(player, 1, 32);

        assertEquals(Sonic2AnimationIds.ROLL.id(), resolvedAnimation,
                "Obj84's mirrored spindash byte must not make the animation resolver choose AniIDSonAni_Spindash");
    }

    @Test
    void genuineChargingSpindashKeepsItsExplicitAnimationOwner() {
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        player.setGameRulesForTest(GameRules.SONIC_2);
        ScriptedVelocityAnimationProfile profile = s2AnimationProfile();
        player.setAnimationProfile(profile);
        player.setAnimationId(Sonic2AnimationIds.SPINDASH.id());
        player.setSpindash(true);
        player.setPinballMode(false);
        player.setRolling(false);

        Integer resolvedAnimation = profile.resolveAnimationId(player, 1, 32);

        assertNull(resolvedAnimation,
                "a genuine charging spindash preserves the animation byte written by CheckSpindash/UpdateSpindash");
        assertEquals(Sonic2AnimationIds.SPINDASH.id(), player.getAnimationId());
    }

    @Test
    void disableClearsPinballSpindashAliasWithoutTouchingCounter() throws Exception {
        ForcedSpinObjectInstance trigger = new ForcedSpinObjectInstance(
                new ObjectSpawn(0x0100, 0x0100, Sonic2ObjectIds.FORCED_SPIN, 0, 0, false, 0),
                "ForcedSpin");
        TestablePlayableSprite player = new TestablePlayableSprite("tails_p2", (short) 0x0100, (short) 0x0100);
        player.setPinballMode(true);
        player.setSpindash(true);
        player.setSpindashCounter((short) 0x0400);

        Method disablePinballMode = ForcedSpinObjectInstance.class
                .getDeclaredMethod("disablePinballMode", AbstractPlayableSprite.class);
        disablePinballMode.setAccessible(true);
        disablePinballMode.invoke(trigger, player);

        assertFalse(player.getPinballMode());
        assertFalse(player.getSpindash());
        assertEquals((short) 0x0400, player.getSpindashCounter(),
                "Obj84 only clears pinball_mode/spindash_flag; spindash_counter is a separate word");
    }

    @Test
    void nativeP2QuerySidekickCanTriggerForcedSpinWhenRawSidekickListIsEmpty() {
        ForcedSpinObjectInstance trigger = new ForcedSpinObjectInstance(
                new ObjectSpawn(0x0100, 0x0100, Sonic2ObjectIds.FORCED_SPIN, 0, 0, false, 0),
                "ForcedSpin");
        TestablePlayableSprite main = new TestablePlayableSprite("sonic", (short) 0x0080, (short) 0x0100);
        TestablePlayableSprite sidekick = new TestablePlayableSprite("tails", (short) 0x00F0, (short) 0x0100);
        trigger.setServices(new QueryOnlyPlayerServices(main, List.of(sidekick)));

        trigger.update(0, main);
        sidekick.setCentreX((short) 0x0100);
        trigger.update(1, main);

        assertTrue(sidekick.getPinballMode(),
                "Obj84 has only native P1/P2 crossing flags, so P2 must come from ObjectPlayerQuery NATIVE_P1_P2");
        assertTrue(sidekick.getSpindash(),
                "Obj84's pinball_mode byte is the same flag Tails_UpdateSpindash later consumes");
        assertTrue(sidekick.getRolling());
    }

    @Test
    void nativeP2FlightAutoRecoverySkipsHorizontalForcedSpinUntilNormalCpuRoutine() {
        ForcedSpinObjectInstance trigger = new ForcedSpinObjectInstance(
                new ObjectSpawn(0x0100, 0x0100, Sonic2ObjectIds.FORCED_SPIN, 0, 0, false, 0),
                "ForcedSpin");
        TestablePlayableSprite main = new TestablePlayableSprite("sonic", (short) 0x0080, (short) 0x0100);
        TestablePlayableSprite sidekick = new TestablePlayableSprite("tails", (short) 0x00F0, (short) 0x0100);
        sidekick.setCpuControlled(true);
        SidekickCpuController controller = new SidekickCpuController(sidekick, main);
        controller.setInitialState(SidekickCpuController.State.FLIGHT_AUTO_RECOVERY);
        trigger.setServices(new QueryOnlyPlayerServices(main, List.of(sidekick)));

        trigger.update(0, main);
        sidekick.setCentreX((short) 0x0100);
        trigger.update(1, main);

        assertFalse(sidekick.getPinballMode(),
                "ROM Obj84 skips native P2 while Tails_CPU_routine is $04");
        assertFalse(sidekick.getRolling());

        controller.setInitialState(SidekickCpuController.State.NORMAL);
        trigger.update(2, main);

        assertTrue(sidekick.getPinballMode(),
                "Once Tails leaves routine $04, Obj84 should consume the still-pending crossing");
        assertTrue(sidekick.getRolling());
    }

    private static ScriptedVelocityAnimationProfile s2AnimationProfile() {
        return new ScriptedVelocityAnimationProfile()
                .setIdleAnimId(Sonic2AnimationIds.WAIT)
                .setWalkAnimId(Sonic2AnimationIds.WALK)
                .setRunAnimId(Sonic2AnimationIds.RUN)
                .setRollAnimId(Sonic2AnimationIds.ROLL)
                .setRoll2AnimId(Sonic2AnimationIds.ROLL2)
                .setPushAnimId(Sonic2AnimationIds.PUSH)
                .setDuckAnimId(Sonic2AnimationIds.DUCK)
                .setLookUpAnimId(Sonic2AnimationIds.LOOK_UP)
                .setSpindashAnimId(Sonic2AnimationIds.SPINDASH)
                .setSpringAnimId(Sonic2AnimationIds.SPRING)
                .setDeathAnimId(Sonic2AnimationIds.DEATH)
                .setDrownAnimId(Sonic2AnimationIds.DROWN)
                .setHurtAnimId(Sonic2AnimationIds.HURT)
                .setSkidAnimId(Sonic2AnimationIds.SKID)
                .setSlideAnimId(Sonic2AnimationIds.SLIDE)
                .setAirAnimId(Sonic2AnimationIds.WALK)
                .setBalanceAnimId(Sonic2AnimationIds.BALANCE)
                .setBalance2AnimId(Sonic2AnimationIds.BALANCE2)
                .setBalance3AnimId(Sonic2AnimationIds.BALANCE3)
                .setBalance4AnimId(Sonic2AnimationIds.BALANCE4)
                .setWalkSpeedThreshold(0x40)
                .setRunSpeedThreshold(0x600)
                .setFallbackFrame(0)
                .setAnglePreAdjust(true)
                .setCompactSuperRunSlope(true);
    }

    private static final class QueryOnlyPlayerServices extends TestObjectServices {
        private final PlayableEntity main;
        private final List<? extends PlayableEntity> queriedSidekicks;

        private QueryOnlyPlayerServices(PlayableEntity main, List<? extends PlayableEntity> queriedSidekicks) {
            this.main = main;
            this.queriedSidekicks = List.copyOf(queriedSidekicks);
        }

        @Override
        public ObjectPlayerQuery playerQuery() {
            return new ObjectPlayerQuery(() -> main, () -> queriedSidekicks);
        }

        @Override
        public List<PlayableEntity> sidekicks() {
            return List.of();
        }
    }
}
