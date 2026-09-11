package com.openggf.game.rewind;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.MhzSwingBarHorizontalObjectInstance;
import com.openggf.game.sonic3k.objects.MhzSwingBarVerticalObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live-rewind reproduction for the MHZ swing bars (SKL objects $0B / $0C):
 * grab the bar so the player hangs, capture a keyframe through the real
 * {@code GameplayModeContext} rewind registry, keep playing, then restore.
 *
 * <p>Before the fix the bar's per-player hang map ({@code hangingPlayers} /
 * {@code hangStates} on the horizontal bar, {@code playerStates} on the
 * vertical bar) was captured on no rewind path: with only final
 * {@code IdentityHashMap} fields and no {@code CAPTURED} policy the class had an
 * empty compact captured-field list, dropping it onto the generic scalar path
 * which cannot capture identity-keyed collections. A mid-hang restore recreated
 * the bar with an empty grab map while the player came back object-controlled,
 * leaving it frozen with the bar no longer knowing it held the player — the same
 * failure family as the MGZ spinning top.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestS3kMhzSwingBarLiveRewind {

    private static SharedLevel sharedLevel;
    private static Object oldSkipIntros;
    private static Object oldMainCharacter;
    private static Object oldSidekickCharacter;

    private HeadlessTestFixture fixture;
    private AbstractPlayableSprite sprite;

    @BeforeAll
    static void loadLevel() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        oldMainCharacter = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        oldSidekickCharacter = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "");
        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, Sonic3kZoneIds.ZONE_MHZ, 0);
    }

    @AfterAll
    static void cleanup() {
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
    void setUp() {
        fixture = HeadlessTestFixture.builder().withSharedLevel(sharedLevel).build();
        sprite = fixture.sprite();
        fixture.camera().updatePosition(true);
        GameServices.level().postCameraObjectPlacementSync();
        GameServices.level().getObjectManager().reset(fixture.camera().getX());
        fixture.stepIdleFrames(1);
    }

    @Test
    void verticalSwingBarHangStateSurvivesRewindRestore() {
        ObjectManager objectManager = GameServices.level().getObjectManager();
        int barX = sprite.getCentreX() - 0x18;
        int barY = sprite.getCentreY();

        MhzSwingBarVerticalObjectInstance bar = objectManager.createDynamicObject(
                () -> new MhzSwingBarVerticalObjectInstance(new ObjectSpawn(
                        barX, barY, 0x0C, 0, 0, false, 0)));

        // Approach the bar from the left edge of the right-side grab window with
        // enough x-speed to latch (ROM MIN_GRAB_SPEED = $400), grounded.
        sprite.setXSpeed((short) 0x0400);
        sprite.setYSpeed((short) 0);
        sprite.setRolling(false);
        sprite.setAir(false);
        bar.update(0, sprite);

        assertTrue(sprite.isObjectControlled(), "Player must be hanging (object-controlled) before capture");
        assertTrue(bar.isPlayerHanging(sprite), "Bar must hold the player's hang state before capture");

        RewindRegistry registry = fixture.gameplayMode().getRewindRegistry();
        assertNotNull(registry, "GameplayModeContext rewind registry must exist");
        CompositeSnapshot midHang = registry.capture();

        // Keep playing forward: the hang climb advances the phase.
        for (int frame = 1; frame <= 10; frame++) {
            bar.update(frame, sprite);
        }
        // Force the SpawnRewindRecreatable rebuild path on restore.
        objectManager.createDynamicObject(() -> new MhzSwingBarVerticalObjectInstance(new ObjectSpawn(
                barX + 0x400, barY + 0x400, 0x0C, 0, 0, false, 1)));

        registry.restore(midHang);

        MhzSwingBarVerticalObjectInstance restored = liveVerticalBar(objectManager);
        assertNotNull(restored, "restore must keep exactly one vertical swing bar");
        assertNotSame(bar, restored, "restore must recreate the vertical swing bar");
        assertTrue(sprite.isObjectControlled(), "restored player must still be object-controlled");
        assertTrue(restored.isPlayerHanging(sprite),
                "restored vertical swing bar must still hold the player's hang state across rewind");

        // Resume: the recreated bar must keep driving the hang, then release cleanly on jump.
        restored.update(11, sprite);
        assertTrue(sprite.isObjectControlled(), "recreated bar must keep the player hanging on resume");
        sprite.setJumpInputPressed(true);
        restored.update(12, sprite);
        assertTrue(sprite.getAir() && !sprite.isObjectControlled(),
                "jump release after rewind must free the player cleanly (not leave it frozen)");
    }

    @Test
    void horizontalSwingBarHangStateSurvivesRewindRestore() {
        ObjectManager objectManager = GameServices.level().getObjectManager();
        int barX = sprite.getCentreX();
        int barY = sprite.getCentreY() - 0x20;

        MhzSwingBarHorizontalObjectInstance bar = objectManager.createDynamicObject(
                () -> new MhzSwingBarHorizontalObjectInstance(new ObjectSpawn(
                        barX, barY, 0x0B, 0, 0, false, 0)));

        // Fall into the grab window below the bar.
        sprite.setYSpeed((short) 0x0300);
        sprite.setRolling(false);
        sprite.setAir(true);
        bar.update(0, sprite);

        assertTrue(sprite.isObjectControlled(), "Player must be hanging (object-controlled) before capture");
        assertTrue(bar.isPlayerHanging(sprite), "Bar must hold the player's hang state before capture");

        RewindRegistry registry = fixture.gameplayMode().getRewindRegistry();
        assertNotNull(registry, "GameplayModeContext rewind registry must exist");
        CompositeSnapshot midHang = registry.capture();

        for (int frame = 1; frame <= 6; frame++) {
            bar.update(frame, sprite);
        }
        objectManager.createDynamicObject(() -> new MhzSwingBarHorizontalObjectInstance(new ObjectSpawn(
                barX + 0x400, barY + 0x400, 0x0B, 0, 0, false, 1)));

        registry.restore(midHang);

        MhzSwingBarHorizontalObjectInstance restored = liveHorizontalBar(objectManager);
        assertNotNull(restored, "restore must keep exactly one horizontal swing bar");
        assertNotSame(bar, restored, "restore must recreate the horizontal swing bar");
        assertTrue(sprite.isObjectControlled(), "restored player must still be object-controlled");
        assertTrue(restored.isPlayerHanging(sprite),
                "restored horizontal swing bar must still hold the player's hang state across rewind");

        restored.update(7, sprite);
        assertTrue(sprite.isObjectControlled(), "recreated bar must keep the player hanging on resume");
        sprite.setJumpInputPressed(true);
        restored.update(8, sprite);
        assertTrue(sprite.getAir() && !sprite.isObjectControlled(),
                "jump release after rewind must free the player cleanly (not leave it frozen)");
    }

    private static MhzSwingBarVerticalObjectInstance liveVerticalBar(ObjectManager objectManager) {
        for (ObjectInstance obj : objectManager.getActiveObjects()) {
            if (obj instanceof MhzSwingBarVerticalObjectInstance bar && !bar.isDestroyed()) {
                return bar;
            }
        }
        return null;
    }

    private static MhzSwingBarHorizontalObjectInstance liveHorizontalBar(ObjectManager objectManager) {
        for (ObjectInstance obj : objectManager.getActiveObjects()) {
            if (obj instanceof MhzSwingBarHorizontalObjectInstance bar && !bar.isDestroyed()) {
                return bar;
            }
        }
        return null;
    }
}
