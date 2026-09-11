package com.openggf.tests;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SidekickCpuController;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless regression for the solo-Sonic CNZ1 Tails-carry intro.
 *
 * <p>ROM {@code SpawnLevelMainSprites loc_68D8} (sonic3k.asm:8187-8197) spawns a
 * throwaway {@code Obj_Tails} into the Player_2 slot for solo Sonic
 * (Player_mode==1) at CNZ Act 1, so Tails carries Sonic in and then flies off.
 *
 * <p>This asserts the throwaway carrier survives the production level-load step
 * order. The S3K init profile runs
 * {@code initLevelEvents -> spawnSidekicks -> applyZonePlayerState}
 * (Sonic3kLevelInitProfile), and {@code spawnSidekicks} starts with
 * {@code SpriteManager.removeTemporarySidekicks()}. The carry-in Tails is a
 * temporary sidekick, so it MUST be spawned during {@code applyZonePlayerState}
 * (the ROM {@code SpawnLevelMainSprites} location that runs after sidekick
 * placement) rather than during the earlier {@code initLevelEvents} step, or it
 * is deleted before the first gameplay frame and Sonic drops unassisted.
 *
 * <p>The {@link HeadlessTestFixture} build path reorders these steps (sidekick
 * reposition before level events), which masks the production bug, so this test
 * replays the production triplet explicitly after the fixture is built.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestS3kCnzSoloCarryInHeadless {

    private static final int ZONE_CNZ = 3;
    private static final int ACT_1 = 0;
    private static final int S3K_SIDEKICK_X_OFFSET = -32;
    private static final int S3K_SIDEKICK_Y_OFFSET = 4;

    private static Object oldSkipIntros;
    private static Object oldSidekickCode;
    private static SharedLevel sharedLevel;

    private HeadlessTestFixture fixture;

    @BeforeAll
    static void loadLevel() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        oldSidekickCode = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        // Solo Sonic: no configured sidekick, so PlayerCharacter resolves to
        // SONIC_ALONE and the only Tails on the level is the throwaway carrier.
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "");
        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, ZONE_CNZ, ACT_1);
    }

    @AfterAll
    static void cleanup() {
        if (sharedLevel != null) {
            sharedLevel.dispose();
            sharedLevel = null;
        }
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        if (oldSkipIntros != null) {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, oldSkipIntros);
            oldSkipIntros = null;
        }
        if (oldSidekickCode != null) {
            config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, oldSidekickCode);
            oldSidekickCode = null;
        }
    }

    @BeforeEach
    void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .build();
    }

    /**
     * Replays the production load-step order and verifies the throwaway carry-in
     * Tails is alive afterward. Fails when the carrier is spawned during
     * {@code initLevelEvents} (deleted by the following {@code spawnSidekicks}).
     */
    @Test
    void soloCarrierSurvivesProductionSidekickSpawnStep() {
        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();

        // Production order (Sonic3kLevelInitProfile, post-load assembly):
        manager.initLevel(ZONE_CNZ, ACT_1);                                 // step: initLevelEvents
        GameServices.level().spawnSidekicks(S3K_SIDEKICK_X_OFFSET, S3K_SIDEKICK_Y_OFFSET); // step: spawnSidekicks (removeTemporarySidekicks)
        manager.applyZonePlayerState();                                     // step: initZonePlayerState (ROM loc_68D8)

        List<AbstractPlayableSprite> sidekicks = GameServices.sprites().getRegisteredSidekicks();
        assertEquals(1, sidekicks.size(),
                "Solo Sonic CNZ1 must keep its throwaway carry-in Tails after the production load order");

        SidekickCpuController controller = sidekicks.get(0).getCpuController();
        assertNotNull(controller, "Carry-in Tails must have a CPU controller");
        assertTrue(controller.isTransientCarrySidekick(),
                "The CNZ1 solo carry-in Tails must be flagged as a transient carrier so it flies off after the drop");
    }

    /**
     * End-to-end: after the production load order, stepping frames must engage
     * the carry (Sonic object-controlled and pulled along) instead of dropping.
     */
    @Test
    void soloSonicIsCarriedNotDroppedFromSky() {
        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        manager.initLevel(ZONE_CNZ, ACT_1);
        GameServices.level().spawnSidekicks(S3K_SIDEKICK_X_OFFSET, S3K_SIDEKICK_Y_OFFSET);
        manager.applyZonePlayerState();

        AbstractPlayableSprite sonic = fixture.sprite();

        // Frame 1: ROM loc_13A10 sets Tails_CPU_routine=$C and returns; the 0x0C
        // body that writes x_vel=$100 has not run yet.
        fixture.stepFrame(false, false, false, false, false);
        assertEquals((short) 0x0000, sonic.getXSpeed(),
                "Frame 1: carry INIT just armed the routine, no carry velocity yet");

        // Frame 2: the 0x0C body writes x_vel=$100 and Tails carries Sonic.
        fixture.stepFrame(false, false, false, false, false);
        assertEquals((short) 0x0100, sonic.getXSpeed(),
                "Frame 2: solo Sonic must be carried at the ROM carry velocity");
        assertTrue(sonic.isObjectControlled(),
                "Frame 2: solo Sonic must be object-controlled by the carry-in Tails");
        assertTrue(sonic.getAir(),
                "Frame 2: solo Sonic must be airborne (carried), not grounded/dropping");
    }

    /**
     * After Sonic lands, the throwaway carrier must enter the fly-off/self-delete
     * path (ROM routine $10), never the NORMAL follow AI.
     */
    @Test
    void soloCarrierFliesOffAfterLandingNeverFollowAI() {
        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        manager.initLevel(ZONE_CNZ, ACT_1);
        GameServices.level().spawnSidekicks(S3K_SIDEKICK_X_OFFSET, S3K_SIDEKICK_Y_OFFSET);
        manager.applyZonePlayerState();

        SidekickCpuController controller =
                GameServices.sprites().getRegisteredSidekicks().get(0).getCpuController();

        // Carry grounds Sonic around frame ~107 (see TestS3kCnzCarryHeadless).
        boolean reachedFlyoff = false;
        for (int i = 0; i < 200; i++) {
            fixture.stepFrame(false, false, false, false, false);
            assertNotEquals(SidekickCpuController.State.NORMAL, controller.getState(),
                    "Throwaway carrier must never enter NORMAL follow AI (frame " + i + ")");
            if (controller.getState() == SidekickCpuController.State.CARRY_FLYOFF
                    || controller.isTransientFlyoffDespawned()) {
                reachedFlyoff = true;
                break;
            }
        }
        assertTrue(reachedFlyoff,
                "Throwaway carrier must enter the fly-off path after the carry ends");

        // The flight-paced fly-off must actually leave the screen and self-delete
        // (ROM loc_140AC) — not linger on-screen. Also verify it moves at flight
        // pace, not the old constant 6px/frame teleport-step.
        AbstractPlayableSprite carrier = GameServices.sprites().getRegisteredSidekicks().isEmpty()
                ? null
                : GameServices.sprites().getRegisteredSidekicks().get(0);
        int maxStepPx = 0;
        boolean despawned = controller.isTransientFlyoffDespawned();
        int prevX = carrier != null ? (carrier.getCentreX() & 0xFFFF) : 0;
        for (int i = 0; i < 600 && !despawned; i++) {
            fixture.stepFrame(false, false, false, false, false);
            despawned = controller.isTransientFlyoffDespawned();
            if (carrier != null && !despawned) {
                int x = carrier.getCentreX() & 0xFFFF;
                maxStepPx = Math.max(maxStepPx, Math.abs(x - prevX));
                prevX = x;
            }
        }
        assertTrue(despawned,
                "Fly-off carrier must leave the screen and self-delete (ROM loc_140AC)");
        assertTrue(maxStepPx <= 5,
                "Fly-off must move at flight pace, not the old constant 6px/frame step (saw "
                        + maxStepPx + "px/frame)");
    }

    /**
     * If Sonic jumps off the carry before landing (ROM Tails_Carry_Sonic A/B/C
     * jump-out), the throwaway carrier stays in the carry routine's released
     * cooldown/regrab loop (ROM routine $E / loc_14534) — never the NORMAL follow
     * AI — and flies off once Sonic lands.
     */
    @Test
    void soloCarrierFliesOffWhenSonicJumpsOffMidCarry() {
        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        manager.initLevel(ZONE_CNZ, ACT_1);
        GameServices.level().spawnSidekicks(S3K_SIDEKICK_X_OFFSET, S3K_SIDEKICK_Y_OFFSET);
        manager.applyZonePlayerState();

        SidekickCpuController controller =
                GameServices.sprites().getRegisteredSidekicks().get(0).getCpuController();

        // Engage the carry for a few frames so Sonic is airborne + object-controlled.
        for (int i = 0; i < 5; i++) {
            fixture.stepFrame(false, false, false, false, false);
        }
        assertEquals(SidekickCpuController.State.CARRYING, controller.getState(),
                "Carry must be active before the jump-off");

        // Sonic jumps off mid-air (before landing).
        fixture.stepFrame(false, false, false, false, true);

        // ROM: routine stays $E (released cooldown/regrab); it must NOT become the
        // NORMAL follow AI, and Sonic is no longer object-controlled.
        assertNotEquals(SidekickCpuController.State.NORMAL, controller.getState(),
                "Throwaway carrier must not fall into NORMAL follow AI after a mid-carry jump-off");
        assertEquals(SidekickCpuController.State.CARRYING, controller.getState(),
                "After a mid-air jump-off the carrier stays in the carry routine's "
                        + "released cooldown/regrab loop (ROM routine $E)");

        // Sonic falls and lands -> the carrier transitions to fly-off and self-
        // deletes; it must never enter NORMAL at any point.
        boolean flewOff = false;
        for (int i = 0; i < 400 && !flewOff; i++) {
            fixture.stepFrame(false, false, false, false, false);
            assertNotEquals(SidekickCpuController.State.NORMAL, controller.getState(),
                    "Throwaway carrier must never enter NORMAL follow AI (frame " + i + ")");
            flewOff = controller.getState() == SidekickCpuController.State.CARRY_FLYOFF
                    || controller.isTransientFlyoffDespawned();
        }
        assertTrue(flewOff,
                "Throwaway carrier must fly off after Sonic lands following the jump-off");
    }

    /**
     * The per-frame rewind keyframe capture must not throw while the throwaway
     * carry-in Tails is alive — it must be registered in the rewind identity
     * table like any other sidekick.
     */
    @Test
    void rewindCaptureSucceedsWithSoloCarryInTailsPresent() {
        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        manager.initLevel(ZONE_CNZ, ACT_1);
        GameServices.level().spawnSidekicks(S3K_SIDEKICK_X_OFFSET, S3K_SIDEKICK_Y_OFFSET);
        manager.applyZonePlayerState();

        assertEquals(1, GameServices.sprites().getRegisteredSidekicks().size(),
                "Precondition: the throwaway carry-in Tails is present");

        // Capture a rewind keyframe every frame across the whole carry -> land ->
        // fly-off -> despawn lifecycle (the real engine captures per frame). Hold
        // right + occasional jump so the player/carrier interact with start-area
        // objects (bumpers, monitors, etc.) the way real play does. An object that
        // touched the carrier keeps a reference to it after it flies off and is
        // removed; the keyframe capture must still encode (as a dangling/null
        // reference) rather than throwing.
        for (int i = 0; i < 400; i++) {
            final int frame = i;
            boolean jump = (i % 24) == 0;
            fixture.stepFrame(false, false, false, true, jump);
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                    () -> fixture.gameplayMode().getRewindRegistry().capture(),
                    "Rewind capture must not throw for the throwaway carry-in Tails (frame " + frame + ")");
        }
    }

    /**
     * Rewinding back into the carry intro after the carrier has flown off and
     * been removed must re-create it (so the leader is not left object-controlled
     * with no carrier present).
     */
    @Test
    void rewindRestoreRecreatesDespawnedCarrier() {
        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        manager.initLevel(ZONE_CNZ, ACT_1);
        GameServices.level().spawnSidekicks(S3K_SIDEKICK_X_OFFSET, S3K_SIDEKICK_Y_OFFSET);
        manager.applyZonePlayerState();
        SidekickCpuController controller =
                GameServices.sprites().getRegisteredSidekicks().get(0).getCpuController();
        AbstractPlayableSprite sonic = fixture.sprite();

        // Advance into the carry, capture a keyframe while the carrier is alive.
        for (int i = 0; i < 10; i++) {
            fixture.stepFrame(false, false, false, false, false);
        }
        assertEquals(1, GameServices.sprites().getRegisteredSidekicks().size());
        assertTrue(sonic.isObjectControlled(), "Precondition: Sonic is being carried");
        short carriedY = sonic.getCentreY();
        var keyframe = fixture.gameplayMode().getRewindRegistry().capture();

        // Run right so Sonic lands and the camera leaves the carrier behind -> despawn.
        for (int i = 0; i < 800 && !controller.isTransientFlyoffDespawned(); i++) {
            fixture.stepFrame(false, false, false, true, false);
        }
        assertTrue(controller.isTransientFlyoffDespawned(),
                "Precondition: the carrier flew off and was removed");
        assertEquals(0, GameServices.sprites().getRegisteredSidekicks().size());

        // Restore the keyframe — the carrier must be re-created and Sonic's carried
        // state restored.
        fixture.gameplayMode().getRewindRegistry().restore(keyframe);
        assertEquals(1, GameServices.sprites().getRegisteredSidekicks().size(),
                "Rewinding into the carry must re-create the throwaway carrier");
        AbstractPlayableSprite restoredCarrier = GameServices.sprites().getRegisteredSidekicks().get(0);
        assertTrue(restoredCarrier.getCpuController().isTransientCarrySidekick(),
                "Re-created carrier must still be flagged as the throwaway carrier");
        assertTrue(sonic.isObjectControlled(),
                "Sonic's carried state is restored alongside the re-created carrier");
        assertEquals(carriedY, sonic.getCentreY(),
                "Sonic's position is rolled back to the captured carry frame");
    }
}
