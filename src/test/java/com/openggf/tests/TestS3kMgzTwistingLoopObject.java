package com.openggf.tests;

import com.openggf.game.session.SessionManager;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.EngineContext;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.MGZTwistingLoopObjectInstance;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.game.rules.GameRules;
import com.openggf.sprites.animation.SpriteAnimationSet;
import com.openggf.sprites.animation.SpriteAnimationScript;
import com.openggf.sprites.animation.SpriteAnimationEndAction;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kMgzTwistingLoopObject {
    private static final int LOOP_X = 0x1200;
    private static final int LOOP_Y = 0x0600;

    @BeforeEach
    void setUp() {
        SessionManager.clear();
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        AbstractObjectInstance.updateCameraBounds(0, 0, 1024, 1024, 0);
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void mgzTwistingLoopDirectEntry_advancesDownwardOnFirstActiveFrame() {
        MGZTwistingLoopObjectInstance loop = new MGZTwistingLoopObjectInstance(
                new ObjectSpawn(LOOP_X, LOOP_Y, Sonic3kObjectIds.MGZ_TWISTING_LOOP, 0x10, 0, false, 0));
        TestablePlayableSprite player = createDirectEntryPlayer();

        loop.update(0, player); // capture
        loop.update(1, player); // first active frame

        assertTrue(player.isObjectControlled(), "Loop should still own the player after the first active frame");
        assertTrue(player.isObjectControlAllowsCpu(),
                "MGZ loop uses bits 0-6 object_control, so CPU/touch remain allowed while carried");
        assertFalse(player.isObjectControlSuppressesMovement(),
                "MGZ loop native $42 control leaves bit 0 clear, so normal movement stays active");
        assertFalse(player.isControlLocked(),
                "MGZ loop native $42 control must keep Ctrl_1 logical input unlocked");
        assertFalse(player.isTouchResponseSuppressedByObjectControl(),
                "MGZ loop bits 0-6 should not suppress touch responses");
        assertTrue(player.getCentreY() > LOOP_Y,
                "Captured MGZ loop entry should advance downward on the first active frame");
    }

    @ParameterizedTest
    @CsvSource({"sonic, -1, false", "sonic, 1, true", "tails, -1, false", "tails, 1, true"})
    void spiralOrientationSurvivesPlayerAnimation(String character, int entrySide, boolean hFlip) {
        MGZTwistingLoopObjectInstance loop = new MGZTwistingLoopObjectInstance(
                new ObjectSpawn(LOOP_X, LOOP_Y, Sonic3kObjectIds.MGZ_TWISTING_LOOP, 0x10, 0, false, 0));
        TestablePlayableSprite player = createDirectEntryPlayer(character, LOOP_X + entrySide);
        player.setGameRulesForTest(GameRules.SONIC_3K);
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(0, new SpriteAnimationScript(0xFF, List.of(1, 2),
                SpriteAnimationEndAction.LOOP, 0));
        player.setAnimationSet(animations);

        loop.update(0, player);
        // loc_33D70 sets Y flip on both sides; the preceding branch sets X flip
        // only on right-side entry. loc_10C62/loc_138C8 skip Animate_* while held.
        assertEquals(hFlip, player.getRenderHFlip());
        assertTrue(player.getRenderVFlip(), "Capture must vertically flip the spiral mapping");
        for (int frame = 1; frame <= 24; frame++) {
            int heldMapping = player.getMappingFrame();
            player.getAnimationManager().update(frame);
            assertEquals(heldMapping, player.getMappingFrame());
            assertEquals(hFlip, player.getRenderHFlip(), "Animation must retain the entry-side flip");
            assertTrue(player.getRenderVFlip(), "Animation must retain the spiral's vertical flip");
            loop.update(frame, player);
            assertTrue(player.isObjectMappingFrameControl());
        }
        // The no-art fallback must preserve the same object-owned presentation.
        player.setAnimationSet(null);
        player.getAnimationManager().update(25);
        assertEquals(hFlip, player.getRenderHFlip());
        assertTrue(player.getRenderVFlip());
    }

    @Test
    void mgzTwistingLoopDirectEntry_doesNotReleaseBeforeItsConfiguredThreshold() {
        MGZTwistingLoopObjectInstance loop = new MGZTwistingLoopObjectInstance(
                new ObjectSpawn(LOOP_X, LOOP_Y, Sonic3kObjectIds.MGZ_TWISTING_LOOP, 0x01, 0, false, 0));
        TestablePlayableSprite player = createDirectEntryPlayer();

        loop.update(0, player); // capture
        for (int frame = 1; frame <= 3; frame++) {
            loop.update(frame, player);
        }

        assertTrue(player.isObjectControlled(),
                "MGZ loop should still be carrying an on-foot entry before its configured release threshold");
    }

    @Test
    void mgzTwistingLoopRollingEntry_keepsRollingRadiiUntilLoopReleases() {
        MGZTwistingLoopObjectInstance loop = new MGZTwistingLoopObjectInstance(
                new ObjectSpawn(LOOP_X, LOOP_Y, Sonic3kObjectIds.MGZ_TWISTING_LOOP, 0x01, 0, false, 0));
        TestablePlayableSprite player = createDirectEntryPlayer();
        player.move((short) 0, (short) 0x80); // seed y_sub = $8000 without changing pixel Y
        player.setRolling(true);

        loop.update(0, player); // capture
        loop.update(1, player); // first active frame

        assertTrue(player.getRolling(),
                "MGZ loop should keep rolling state while the object is carrying the player");
        assertEquals(14, player.getYRadius(),
                "MGZ loop should keep using the rolling radius for spiral positioning while captured");
        assertTrue(player.getYSubpixelRaw() != 0,
                "MGZ loop should continue carrying subpixel progress while the player is captured");

        for (int frame = 2; frame <= 24 && player.isObjectControlled(); frame++) {
            loop.update(frame, player);
        }

        assertFalse(player.getRolling(),
                "MGZ loop should restore standing state in the shared release path");
        assertEquals(player.getStandYRadius(), player.getYRadius(),
                "MGZ loop should restore the standing radius in the shared release path");
    }

    @Test
    void mgzTwistingLoopMinimumGroundClamp_keepsLiveProjectedYSpeed() {
        MGZTwistingLoopObjectInstance loop = new MGZTwistingLoopObjectInstance(
                new ObjectSpawn(LOOP_X, LOOP_Y, Sonic3kObjectIds.MGZ_TWISTING_LOOP, 0x10, 0, false, 0));
        TestablePlayableSprite player = createDirectEntryPlayer();
        player.setGSpeed((short) -8);
        player.setYSpeed((short) 0x03FD);

        loop.update(0, player); // capture
        loop.update(1, player); // first active frame

        assertEquals(-0x0400, player.getGSpeed(),
                "MGZ loop should preserve a negative entry direction when clamping to minimum speed");
        assertEquals(0x03FD, player.getYSpeed() & 0xFFFF,
                "The minimum ground-speed clamp must leave the physics-produced y_vel untouched");
    }

    @Test
    void mgzTwistingLoopMaximumGroundClampPublishesMaximumDownwardSpeed() {
        MGZTwistingLoopObjectInstance loop = new MGZTwistingLoopObjectInstance(
                new ObjectSpawn(LOOP_X, LOOP_Y, Sonic3kObjectIds.MGZ_TWISTING_LOOP, 0x10, 0, false, 0));
        TestablePlayableSprite player = createDirectEntryPlayer();
        player.setGSpeed((short) -0x0C01);
        player.setYSpeed((short) 0x03A0);

        loop.update(0, player); // capture
        loop.update(1, player); // first active frame

        assertEquals(-0x0C00, player.getGSpeed(),
                "MGZ loop should retain ground direction when applying its maximum clamp");
        assertEquals(0x0C00, player.getYSpeed() & 0xFFFF,
                "The maximum clamp explicitly publishes positive $C00 y_vel");
    }

    @Test
    void mgzTwistingLoopCapture_keepsEntryWallAngleWhileCarried() {
        MGZTwistingLoopObjectInstance loop = new MGZTwistingLoopObjectInstance(
                new ObjectSpawn(LOOP_X, LOOP_Y, Sonic3kObjectIds.MGZ_TWISTING_LOOP, 0x10, 0, false, 0));
        TestablePlayableSprite player = createDirectEntryPlayer();

        loop.update(0, player); // capture
        loop.update(1, player); // first active frame

        assertTrue((player.getAngle() & 0xFF) == 0x40,
                "MGZ loop should keep the entry wall angle while carrying the player");
    }

    @Test
    void mgzTwistingLoopOnFootRelease_doesNotUseCompensatedHandoff() {
        MGZTwistingLoopObjectInstance loop = new MGZTwistingLoopObjectInstance(
                new ObjectSpawn(LOOP_X, LOOP_Y, Sonic3kObjectIds.MGZ_TWISTING_LOOP, 0x00, 0, false, 0));
        TestablePlayableSprite player = createDirectEntryPlayer();

        loop.update(0, player); // capture
        loop.update(1, player); // immediate threshold release

        assertFalse(player.isObjectControlled(),
                "Plain MGZ direct entries should release immediately at the configured threshold");
        assertFalse(player.isStickToConvex(),
                "Plain MGZ direct entries should not inherit the compensated convex handoff");
        assertFalse(player.getAir(),
                "Plain MGZ direct entries should remain grounded on release");
    }

    @Test
    void mgzTwistingLoopJumpReleaseKeepsBits0To6ControlUntilReleaseFramesEnd() {
        MGZTwistingLoopObjectInstance loop = new MGZTwistingLoopObjectInstance(
                new ObjectSpawn(LOOP_X, LOOP_Y, Sonic3kObjectIds.MGZ_TWISTING_LOOP, 0x10, 0, false, 0));
        TestablePlayableSprite player = createDirectEntryPlayer();

        loop.update(0, player); // capture
        player.setJumpInputPressed(true);
        loop.update(1, player); // jump release starts

        assertTrue(player.isObjectControlled(),
                "Jump release keeps temporary object control while the loop owns launch movement");
        assertTrue(player.isObjectControlAllowsCpu(),
                "Jump release should preserve the MGZ loop bits 0-6 CPU/touch policy");
        assertTrue(player.isObjectControlSuppressesMovement(),
                "Jump release should keep movement suppressed until release frames expire");
        assertFalse(player.isTouchResponseSuppressedByObjectControl(),
                "Bits 0-6 jump release should not suppress touch responses");
        assertFalse(player.isControlLocked(),
                "Jump release should unlock player control even while temporary object control remains");
    }

    @Test
    void mgzTwistingLoopUsesNativeP2QueryWithoutPromotingExtraSidekicks() {
        MGZTwistingLoopObjectInstance loop = new MGZTwistingLoopObjectInstance(
                new ObjectSpawn(LOOP_X, LOOP_Y, Sonic3kObjectIds.MGZ_TWISTING_LOOP, 0x10, 0, false, 0));
        TestablePlayableSprite main = createDirectEntryPlayer();
        TestablePlayableSprite nativeP2 = createDirectEntryPlayer("tails", LOOP_X - 1);
        TestablePlayableSprite extraSidekick = createDirectEntryPlayer("knuckles", LOOP_X + 2);
        loop.setServices(new QueryOnlyPlayerServices(main, List.of(nativeP2, extraSidekick)));

        loop.update(0, main);
        loop.update(1, main);

        assertTrue(nativeP2.isObjectControlled(),
                "MGZ loop player2 slot should use only the first native sidekick from ObjectPlayerQuery");
        assertFalse(extraSidekick.isObjectControlled(),
                "MGZ loop must not promote extra engine sidekicks into the native player2 slot");
    }

    @Test
    void mgzTwistingLoopDoesNotPromoteCpuControlledMainIntoNativeP2Slot() {
        MGZTwistingLoopObjectInstance loop = new MGZTwistingLoopObjectInstance(
                new ObjectSpawn(LOOP_X, LOOP_Y, Sonic3kObjectIds.MGZ_TWISTING_LOOP, 0x10, 0, false, 0));
        TestablePlayableSprite updatePlayer = createDirectEntryPlayer("sonic", LOOP_X + 1);
        TestablePlayableSprite cpuMain = createDirectEntryPlayer("tails-main", LOOP_X - 1);
        TestablePlayableSprite nativeP2 = createDirectEntryPlayer("tails", LOOP_X + 2);
        cpuMain.setCpuControlled(true);
        nativeP2.setCpuControlled(true);
        loop.setServices(new QueryOnlyPlayerServices(cpuMain, List.of(nativeP2)));

        loop.update(0, updatePlayer);

        assertFalse(cpuMain.isObjectControlled(),
                "The query's main player must never be consumed as MGZ's native P2 slot, even if CPU-controlled");
        assertTrue(nativeP2.isObjectControlled(),
                "MGZ loop should skip the queried main and use the first queried sidekick as native P2");
    }

    private static TestablePlayableSprite createDirectEntryPlayer() {
        return createDirectEntryPlayer("sonic", LOOP_X + 1);
    }

    private static TestablePlayableSprite createDirectEntryPlayer(String characterCode, int centreX) {
        TestablePlayableSprite player = new TestablePlayableSprite(characterCode, (short) 0, (short) 0);
        player.setCentreX((short) centreX);
        player.setCentreY((short) LOOP_Y);
        player.setAir(false);
        player.setAngle((byte) 0x40);
        player.setGSpeed((short) 0x0800);
        player.setYSpeed((short) 0x0200);
        player.setXSpeed((short) 0);
        player.setObjectControlled(false);
        player.setControlLocked(false);
        player.setOnObject(false);
        player.setRolling(false);
        player.setJumping(false);
        return player;
    }

    private static final class QueryOnlyPlayerServices extends TestObjectServices {
        private final ObjectPlayerQuery playerQuery;

        private QueryOnlyPlayerServices(TestablePlayableSprite main, List<TestablePlayableSprite> sidekicks) {
            this.playerQuery = new ObjectPlayerQuery(() -> main, () -> sidekicks);
        }

        @Override
        public ObjectPlayerQuery playerQuery() {
            return playerQuery;
        }

        @Override
        public List<com.openggf.game.PlayableEntity> sidekicks() {
            throw new AssertionError("MGZ twisting loop should use ObjectPlayerQuery for native P2 selection");
        }
    }
}
