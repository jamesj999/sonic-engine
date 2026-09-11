package com.openggf.tests;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.CheckpointState;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.render.SpecialRenderEffectContext;
import com.openggf.game.render.SpecialRenderEffectStage;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SidekickCpuController;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kIcz1SnowboardIntroHeadless {
    private static final int ZONE_ICZ = 0x05;
    private static final int ACT_1 = 0;

    private Object oldSkipIntros;
    private Object oldMainCharacter;
    private Object oldSidekickCharacters;

    @BeforeEach
    public void setUpConfig() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        oldMainCharacter = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        oldSidekickCharacters = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);

        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "");
    }

    @AfterEach
    public void restoreConfig() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS,
                oldSkipIntros != null ? oldSkipIntros : false);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE,
                oldMainCharacter != null ? oldMainCharacter : "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                oldSidekickCharacters != null ? oldSidekickCharacters : "");
    }

    @Test
    public void sonicAloneRunsSnowboardIntroAndReleasesControlAfterCrash() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE_ICZ, ACT_1)
                .build();
        AbstractPlayableSprite sonic = fixture.sprite();

        assertTrue(hasSnowboardIntroObject(), "ICZ1 should spawn the Sonic snowboard intro object");
        assertTrue(sonic.isControlLocked(), "Intro should lock Sonic input");
        assertTrue(sonic.isObjectControlled(), "Intro should hold Sonic under object control initially");
        assertTrue(sonic.getAir(), "Intro should start Sonic airborne");
        assertTrue(sonic.getRolling(), "Intro should force Sonic into rolling/snowboard posture");
        renderSnowboardIntroObjects();

        int startX = sonic.getCentreX();
        for (int frame = 0; frame < 45; frame++) {
            fixture.stepFrame(false, false, false, false, false);
        }
        assertTrue(sonic.getCentreX() > startX + 0x10,
                "Sonic should start rolling right after the ROM startup lock expires");
        assertFalse(sonic.isObjectControlled(),
                "Initial object-control hold should be released after the ROM startup lock");

        boolean sawSlopeRegion = false;
        boolean sawCrashHandoff = false;

        for (int frame = 0; frame < 1800; frame++) {
            fixture.stepFrame(false, false, false, false, false);
            if (sonic.getCentreX() >= 0x184 && hasSnowboardIntroObject()) {
                sawSlopeRegion = true;
                renderSnowboardIntroObjects();
            }
            if (sonic.getCentreX() >= 0x3880
                    && !sonic.isObjectControlled()
                    && !hasSnowboardIntroObject()) {
                sawCrashHandoff = true;
                break;
            }
        }

        assertTrue(sonic.getCentreX() > startX + 0x1000,
                "Snowboard intro should carry Sonic far down ICZ1");
        assertTrue(sawSlopeRegion, "Sonic should reach the snowboard slope handoff region");
        assertTrue(sawCrashHandoff, "Sonic should crash off the snowboard and hand control to the ICZ1 event");
        assertFalse(sonic.isObjectControlled(), "The snowboard intro should stop object-controlling Sonic after the crash");
    }

    @Test
    public void checkpointRespawnDoesNotRestartSnowboardIntro() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE_ICZ, ACT_1)
                .build();
        AbstractPlayableSprite sonic = fixture.sprite();
        CheckpointState checkpoint = (CheckpointState) GameServices.level().getCheckpointState();

        checkpoint.saveCheckpoint(7, 0x3A20, 0x0690, false);
        GameServices.level().respawnPlayer();

        assertEquals(0x3A20, sonic.getCentreX(), "respawn should restore the late ICZ1 checkpoint X");
        assertEquals(0x0690, sonic.getCentreY(), "respawn should restore the late ICZ1 checkpoint Y");
        assertFalse(hasSnowboardIntroObject(),
                "ROM SpawnLevelMainSprites returns on nonzero Last_star_post_hit before Obj_LevelIntroICZ1");
        assertFalse(sonic.isControlLocked(), "checkpoint respawn must leave Sonic input unlocked");
        assertFalse(sonic.isObjectControlled(), "checkpoint respawn must leave Sonic out of intro object control");
        assertFalse(sonic.isHidden(), "checkpoint respawn must leave Sonic visible");

        for (int frame = 0; frame < 12; frame++) {
            fixture.stepFrame(false, false, false, true, false);
        }
        assertFalse(sonic.isControlLocked(), "the first live frames must not reapply the snowboard input lock");
        assertFalse(sonic.isObjectControlled(), "the first live frames must not reapply snowboard object control");
        assertFalse(sonic.isHidden(), "the first live frames must keep Sonic visible");
    }

    @Test
    public void sonicAndTailsStartsSnowboardIntroWithTailsDormantMarker() {
        SonicConfigurationService.getInstance()
                .setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");

        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE_ICZ, ACT_1)
                .build();
        AbstractPlayableSprite sonic = fixture.sprite();
        List<AbstractPlayableSprite> sidekicks = GameServices.sprites().getRegisteredSidekicks();

        assertTrue(hasSnowboardIntroObject(),
                "ROM SpawnLevelMainSprites loc_690A spawns Obj_LevelIntroICZ1 for Player_mode < 2");
        assertEquals(0x0800, sonic.getXSpeed() & 0xFFFF);
        assertEquals(0x0280, sonic.getYSpeed() & 0xFFFF);
        assertEquals(0x0800, sonic.getGSpeed() & 0xFFFF);
        assertTrue(sonic.getAir(), "ICZ1 snowboard startup begins airborne");
        assertTrue(sonic.getRolling(), "ICZ1 snowboard startup begins in rolling posture");
        assertFalse(sidekicks.isEmpty(), "Sonic+Tails ICZ1 should keep Player_2 registered");
        AbstractPlayableSprite tails = sidekicks.getFirst();
        assertEquals(0x7F00, tails.getCentreX() & 0xFFFF,
                "ROM loc_13A74 calls sub_13ECA to park CPU Tails off-screen");
        assertEquals(0x0000, tails.getCentreY() & 0xFFFF);
        assertEquals(SidekickCpuController.State.DORMANT_MARKER, tails.getCpuController().getState());
        assertTrue(tails.isObjectControlled(),
                "ROM loc_13A74 writes object_control=$83 for the dormant marker");
        assertTrue(tails.isHidden(),
                "ICZ setup must suppress the registered Player_2 presentation during the snowboard intro");

        for (int frame = 0; frame < 30; frame++) {
            fixture.stepFrame(false, false, false, false, false);
        }
        assertEquals(0x00F2, sonic.getCentreY() & 0xFFFF,
                "ROM Obj_LevelIntroICZ1 clears object_control before the frame-29 SpeedToPos sample");
        assertEquals(0x8000, sonic.getYSubpixelRaw());
        assertEquals(0x02B8, sonic.getYSpeed() & 0xFFFF);

        for (int frame = 30; frame < 118; frame++) {
            fixture.stepFrame(false, false, false, false, false);
        }
        assertEquals(0x0800, sonic.getGSpeed() & 0xFFFF,
                "ROM loc_3943A follows the airborne snowboard overlay without the loc_394A0 ground-speed floor");

        for (int frame = 118; frame < 164; frame++) {
            fixture.stepFrame(false, false, false, false, false);
        }
        assertTrue(sonic.isObjectControlled(),
                "ROM loc_397FA keeps Sonic under object_control=#2 during the snowboard overlay");
        assertFalse(sonic.isTouchResponseSuppressedByObjectControl(),
                "ICZ snowboard object_control=#2 must not suppress Test_Ring_Collisions");
        assertEquals(1, sonic.getRingCount(),
                "Sonic should collect the first ICZ1 snowboard-route ring while object_control=#2 is active");

        for (int frame = 164; frame < 171; frame++) {
            fixture.stepFrame(false, false, false, true, false);
        }
        fixture.stepFrame(false, false, false, true, true);

        assertFalse(sonic.getAir(),
                "ROM loc_3984E queues A/B/C input after Sonic's frame, so the snowboard jump cannot happen early");
        assertFalse(sonic.getRolling(),
                "Frame-171 ICZ snowboard trace remains grounded and unrolled until the queued logical jump is consumed");
        assertEquals(5, sonic.getRingCount(),
                "The low-bit ICZ snowboard object_control state should keep collecting route rings before jump handoff");

        fixture.stepFrame(false, false, false, true, false);

        assertTrue(sonic.getAir(),
                "The queued snowboard jump press should be consumed by normal player physics on the next frame");
        assertTrue(sonic.getRolling(),
                "The queued snowboard jump should use the normal rolling jump transition");

        for (int frame = 173; frame <= 488; frame++) {
            fixture.stepFrame(false, false, false, true, false);
        }

        assertEquals(0x1354, sonic.getCentreX(),
                "ROM loc_395FE should publish object_control=#2 on the final slope-table sample");
        assertEquals(0x0411, sonic.getCentreY(),
                "Frame-488 ICZ snowboard trace should include the first normal movement sample after slope exit");
        assertEquals(0x2B00, sonic.getXSubpixelRaw(),
                "ROM move.w x_pos table writes preserve the existing x_sub accumulator through slope exit");
        assertEquals(0xE000, sonic.getYSubpixelRaw(),
                "ROM move.w y_pos table writes preserve the existing y_sub accumulator through slope exit");
        assertEquals(0x120D, sonic.getXSpeed() & 0xFFFF,
                "Sonic's frame-488 x_vel should reflect the ROM post-slope snowboard speed sample");
        assertEquals(0x076B, sonic.getYSpeed() & 0xFFFF,
                "Sonic's frame-488 y_vel should reflect the ROM post-slope snowboard speed sample");

        boolean sawCrashHandoff = false;
        for (int frame = 489; frame <= 1120; frame++) {
            fixture.stepFrame(false, false, false, true, false);
            if (!hasSnowboardIntroObject()) {
                sawCrashHandoff = true;
                break;
            }
        }

        assertTrue(sawCrashHandoff, "Sonic+Tails ICZ1 should reach the snowboard crash handoff");
        assertEquals(0x7F00, tails.getCentreX() & 0xFFFF,
                "ROM keeps dormant Tails parked until routine 2 performs its own catch-up warp");
        assertEquals(0x0000, tails.getCentreY() & 0xFFFF);
        assertTrue(tails.isObjectControlled(),
                "ROM leaves object_control=$83 intact when Obj_LevelIntroICZ1 writes Tails_CPU_routine=2");
        assertEquals(SidekickCpuController.State.CATCH_UP_FLIGHT, tails.getCpuController().getState(),
                "ROM Obj_LevelIntroICZ1 crash release writes Tails_CPU_routine=2");
        assertFalse(tails.isHidden(),
                "ICZ crash release must restore Tails presentation before catch-up flight");
    }

    @Test
    public void introRecoversIfSonicLandsBeforeReachingSnowboard() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE_ICZ, ACT_1)
                .build();
        AbstractPlayableSprite sonic = fixture.sprite();

        for (int frame = 0; frame < 35; frame++) {
            fixture.stepFrame(false, false, false, false, false);
        }
        sonic.setAir(false);
        sonic.setXSpeed((short) 0);
        sonic.setYSpeed((short) 0);
        sonic.setGSpeed((short) 0);

        for (int frame = 0; frame < 90; frame++) {
            fixture.stepFrame(false, false, false, false, false);
        }

        assertTrue(sonic.getCentreX() >= 0x00C0,
                "ICZ intro should keep Sonic moving into the snowboard even if he lands early");
    }

    @Test
    public void snowboardIntroStillStartsAfterBlockingTitleCard() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE_ICZ, ACT_1)
                .build();
        AbstractPlayableSprite sonic = fixture.sprite();

        assertTrue(hasSnowboardIntroObject(), "ICZ1 should spawn the Sonic snowboard intro object");

        for (int frame = 0; frame < 45; frame++) {
            GameServices.level().updateObjectPositions();
            GameServices.camera().updatePosition(true);
        }

        ((Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider())
                .applyZonePlayerState();

        int startX = sonic.getCentreX();
        for (int frame = 0; frame < 60; frame++) {
            fixture.stepFrame(false, false, false, false, false);
        }

        assertTrue(sonic.getCentreX() > startX + 0x10,
                "ICZ intro should resume player movement after the blocking title card exits");
        assertFalse(sonic.isObjectControlSuppressesMovement(),
                "The title-card exit reapply must not leave object control suppressing movement");
    }

    @Test
    public void bigSnowPileFallsAfterCrashAndRequiresJumpEscape() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE_ICZ, ACT_1)
                .build();
        AbstractPlayableSprite sonic = fixture.sprite();

        boolean sawCrashRelease = false;
        for (int frame = 0; frame < 1900; frame++) {
            fixture.stepFrame(false, false, false, false, false);
            if (sonic.getCentreX() >= 0x3880
                    && !sonic.isObjectControlled()
                    && !hasSnowboardIntroObject()) {
                sawCrashRelease = true;
                break;
            }
        }
        assertTrue(sawCrashRelease, "Sonic should reach the ICZ1 wall crash handoff");

        boolean sawBigSnowPile = false;
        boolean sawLockedOnLandedPile = false;
        for (int frame = 0; frame < 360; frame++) {
            fixture.stepFrame(false, false, false, false, false);
            ObjectInstance pile = findObjectSimpleName("IczBigSnowPileInstance");
            sawBigSnowPile |= pile != null;
            if (pile != null && pile.getY() == 0x070E && sonic.isControlLocked() && !sonic.getAir()) {
                sawLockedOnLandedPile = true;
                break;
            }
        }

        assertTrue(sawBigSnowPile, "ICZ1 background event should spawn Obj_ICZ1BigSnowPile");
        assertTrue(sawLockedOnLandedPile, "Sonic should remain locked while standing under the fallen pile");

        int pileEscapeY = sonic.getCentreY();
        fixture.stepFrame(false, false, false, false, true);

        assertFalse(sonic.isControlLocked(), "Jumping out of the pile should unlock Sonic");
        assertTrue(sonic.getAir(), "Jumping out of the pile should force Sonic airborne");
        assertEquals(0xFA00, sonic.getYSpeed() & 0xFFFF,
                "ROM Obj_ICZ1BigSnowPile writes y_vel=-$600 on the jump release");
        assertEquals(pileEscapeY, sonic.getCentreY(),
                "ROM publishes the pile jump state before the first airborne movement sample");

        for (int frame = 0; frame < 12; frame++) {
            fixture.stepFrame(false, false, false, false, false);
        }
        assertFalse(sonic.isControlLocked(), "The screen event should not relock Sonic after the pile jump release");

        for (int frame = 0; frame < 180 && sonic.getAir(); frame++) {
            fixture.stepFrame(false, false, false, false, false);
        }
        assertFalse(sonic.getAir(), "Sonic should land after jumping out of the pile");
        assertFalse(sonic.isControlLocked(), "Sonic should stay unlocked after landing from the pile jump");
        int landedX = sonic.getCentreX();
        for (int frame = 0; frame < 24; frame++) {
            fixture.stepFrame(false, false, false, true, false);
        }
        assertTrue(sonic.getCentreX() > landedX,
                "Sonic should accept right input on the ground after escaping the pile");
    }

    @Test
    public void bigSnowPileRendersLockOnBackgroundSnowTiles() throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE_ICZ, ACT_1)
                .build();
        AbstractPlayableSprite sonic = fixture.sprite();

        for (int frame = 0; frame < 2260; frame++) {
            fixture.stepFrame(false, false, false, false, false);
            if (hasObjectSimpleName("IczBigSnowPileInstance")
                    && sonic.isControlLocked()
                    && !sonic.getAir()) {
                break;
            }
        }

        ObjectInstance pile = findObjectSimpleName("IczBigSnowPileInstance");
        assertNotNull(pile, "ICZ1 should spawn Obj_ICZ1BigSnowPile");
        List<GLCommand> commands = new ArrayList<>();
        pile.appendRenderCommands(commands);
        Object objectPassTileCount = pile.getClass()
                .getDeclaredMethod("getLastRenderedTileCountForTests")
                .invoke(pile);
        assertEquals(0, ((Number) objectPassTileCount).intValue(),
                "Obj_ICZ1BigSnowPile visual snow should not render in the object/sprite pass");

        GameServices.specialRenderEffectRegistry().dispatch(
                SpecialRenderEffectStage.AFTER_BACKGROUND,
                new SpecialRenderEffectContext(
                        GameServices.camera(),
                        0,
                        GameServices.level(),
                        GameServices.graphics()));
        Object backgroundPassTileCount = pile.getClass()
                .getDeclaredMethod("getLastRenderedTileCountForTests")
                .invoke(pile);
        assertEquals(0, ((Number) backgroundPassTileCount).intValue(),
                "Obj_ICZ1BigSnowPile visual snow should render in front of low-priority foreground tiles");

        GameServices.specialRenderEffectRegistry().dispatch(
                SpecialRenderEffectStage.SPRITE_PRIORITY_MASK,
                new SpecialRenderEffectContext(
                        GameServices.camera(),
                        0,
                        GameServices.level(),
                        GameServices.graphics()));
        Object priorityMaskTileCount = pile.getClass()
                .getDeclaredMethod("getLastRenderedTileCountForTests")
                .invoke(pile);
        assertTrue(((Number) priorityMaskTileCount).intValue() > 0,
                "Obj_ICZ1BigSnowPile should contribute to the sprite priority mask so Sonic appears behind it");

        GameServices.specialRenderEffectRegistry().dispatch(
                SpecialRenderEffectStage.AFTER_FOREGROUND,
                new SpecialRenderEffectContext(
                        GameServices.camera(),
                        0,
                        GameServices.level(),
                        GameServices.graphics()));
        Object tileCount = pile.getClass()
                .getDeclaredMethod("getLastRenderedTileCountForTests")
                .invoke(pile);
        assertTrue(((Number) tileCount).intValue() > 0,
                "Obj_ICZ1BigSnowPile should render non-empty lock-on ICZ background snow tiles");
    }

    private boolean hasSnowboardIntroObject() {
        return hasObjectSimpleName("IczSnowboardIntroInstance");
    }

    private boolean hasObjectSimpleName(String simpleName) {
        return findObjectSimpleName(simpleName) != null;
    }

    private ObjectInstance findObjectSimpleName(String simpleName) {
        return GameServices.level().getObjectManager().getActiveObjects().stream()
                .filter(object -> simpleName.equals(object.getClass().getSimpleName()))
                .findFirst()
                .orElse(null);
    }

    private void renderSnowboardIntroObjects() {
        List<GLCommand> commands = new ArrayList<>();
        for (ObjectInstance object : GameServices.level().getObjectManager().getActiveObjects()) {
            if (object.getClass().getSimpleName().contains("Snowboard")) {
                object.appendRenderCommands(commands);
            }
        }
    }
}
