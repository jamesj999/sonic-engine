package com.openggf.tests;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.Sonic3kLevelTriggerManager;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.MGZDashTriggerObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for {@code MGZDashTriggerObjectInstance} (Object 0x59).
 *
 * <p>The bug being guarded: a player charging a spindash flush against the
 * side of a dash trigger should arm it. Earlier revisions only fired the
 * trigger when the player was standing on top, because activation went
 * through {@code SolidContact.standing/pushing} which never fires while the
 * player is stationary in the duck/spindash position. ROM's {@code sub_1DD0E}
 * is a per-frame proximity probe -- this test reproduces that case.
 *
 * <p>Test coordinates: in the user's reproduction, Sonic stood at top-left
 * X=4833, Y=1450 facing left next to a dash trigger in MGZ Act 1. The trigger
 * is located by scanning the active object list rather than hard-coding ROM
 * coordinates so the test stays robust to placement changes.
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kMgzDashTriggerAdjacentSpindash {

    private static SharedLevel sharedLevel;
    private static Object oldSkipIntros;
    private static Object oldMainCharacter;
    private static Object oldSidekickCharacter;

    private HeadlessTestFixture fixture;
    private Sonic sprite;

    @BeforeAll
    public static void loadLevel() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        oldMainCharacter = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        oldSidekickCharacter = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "");
        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, Sonic3kZoneIds.ZONE_MGZ, 0);
    }

    @AfterAll
    public static void cleanup() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS,
                oldSkipIntros != null ? oldSkipIntros : false);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE,
                oldMainCharacter != null ? oldMainCharacter : "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                oldSidekickCharacter != null ? oldSidekickCharacter : "tails");
        if (sharedLevel != null) {
            sharedLevel.dispose();
            sharedLevel = null;
        }
    }

    @BeforeEach
    public void setUp() {
        fixture = HeadlessTestFixture.builder().withSharedLevel(sharedLevel).build();
        sprite = (Sonic) fixture.sprite();
        Sonic3kLevelTriggerManager.reset();
    }

    @Test
    public void spindashAdjacentToDashTrigger_armsTheTriggerArray() {
        // 1. Object spawns are camera-windowed. Teleport near the user's
        // reproduction spot (top-left X=4833, Y=1450 -> centre ~4842,1469)
        // so the dash trigger enters the active object set.
        teleportNear(4842, 1469);
        ObjectInstance trigger = findFirstDashTrigger();
        assertNotNull(trigger,
                "MGZ1 should contain at least one MGZDashTrigger spawn (object 0x59)");

        int triggerIndex = trigger.getSpawn().subtype() & 0x0F;
        Sonic3kLevelTriggerManager.clearAll(triggerIndex);

        // 2. Position Sonic to the LEFT of the trigger, flush against its
        // side. Half-widths: trigger 27px, Sonic 9px standing -> centres
        // 36px apart (no SolidObject overlap, just touching).
        sprite.setCentreX((short) (trigger.getX() - (27 + 9)));
        sprite.setCentreY((short) trigger.getY());
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0);
        sprite.setGSpeed((short) 0);
        sprite.setAir(false);

        // 3. Charge a spindash via input: hold DOWN one frame (enters duck),
        // then DOWN+JUMP one frame (charges spindash). After this the player
        // sprite reports getAnimationId() == SPINDASH.
        fixture.stepFrame(false, true, false, false, false);
        fixture.stepFrame(false, true, false, false, true);

        assertEquals(Sonic3kAnimationIds.SPINDASH.id(), sprite.getAnimationId(),
                "Sanity: Sonic should be in SPINDASH animation after down+jump charge");
        assertTrue(Sonic3kLevelTriggerManager.testAny(triggerIndex),
                "Spindashing flush against the dash trigger should arm trigger slot "
                        + triggerIndex + " within the charge frame. spriteCx="
                        + sprite.getCentreX() + " triggerX=" + trigger.getX()
                        + " anim=" + sprite.getAnimationId());
    }

    @Test
    public void spindashOnTopOfDashTrigger_alsoArmsTheTrigger() {
        teleportNear(4842, 1469);
        ObjectInstance trigger = findFirstDashTrigger();
        assertNotNull(trigger, "MGZ1 should contain a dash trigger");
        int triggerIndex = trigger.getSpawn().subtype() & 0x0F;
        Sonic3kLevelTriggerManager.clearAll(triggerIndex);

        // Stand the player directly above the trigger so they're riding it.
        sprite.setCentreX((short) trigger.getX());
        sprite.setCentreY((short) (trigger.getY() - (16 + 19)));
        sprite.setAir(false);
        fixture.stepIdleFrames(1);
        sprite.setAnimationId(Sonic3kAnimationIds.SPINDASH.id());
        sprite.setSpindash(true);
        ((MGZDashTriggerObjectInstance) trigger).update(fixture.frameCount() + 1, sprite);

        assertTrue(Sonic3kLevelTriggerManager.testAny(triggerIndex),
                "A player already in spindash state on top of the dash trigger should arm trigger slot "
                        + triggerIndex + ". anim=" + sprite.getAnimationId());
    }

    private void teleportNear(int centreX, int centreY) {
        sprite.setCentreX((short) centreX);
        sprite.setCentreY((short) centreY);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0);
        sprite.setGSpeed((short) 0);
        sprite.setAir(false);

        Camera camera = fixture.camera();
        camera.updatePosition(true);
        // Force the object placement window to repopulate at the new camera X
        // so far-away spawns (the dash trigger lives offscreen at level start)
        // become active.
        GameServices.level().getObjectManager().reset(camera.getX());
        // One idle frame lets the placement loop finish wiring everything.
        fixture.stepIdleFrames(1);
    }

    private ObjectInstance findFirstDashTrigger() {
        for (ObjectInstance obj : GameServices.level().getObjectManager().getActiveObjects()) {
            if (obj.getSpawn() != null
                    && obj.getSpawn().objectId() == Sonic3kObjectIds.MGZ_DASH_TRIGGER) {
                return obj;
            }
        }
        return null;
    }
}
