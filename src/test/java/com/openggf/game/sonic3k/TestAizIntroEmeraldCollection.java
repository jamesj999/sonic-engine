package com.openggf.game.sonic3k;

import com.openggf.game.sonic3k.objects.AizEmeraldScatterInstance;
import com.openggf.game.sonic3k.objects.CutsceneKnucklesAiz1Instance;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test: Knuckles must collect all 7 scattered emeralds during
 * his pacing routine in the AIZ1 intro cutscene.
 *
 * The check happens at routine 10 (laugh), BEFORE Knuckles exits (routine 12).
 * Once he walks offscreen, leftover emeralds are despawned by ObjectManager,
 * hiding any collection failure.
 *
 * Root cause was a ROM mismatch in object slot processing order: the S3K ROM's
 * CreateChild6_Simple places emeralds in later object slots. The emerald init
 * code (loc_67900) reads Player_1.x_pos when that slot is first processed --
 * AFTER the plane's scrollVelocity (sub_45DE4) has already added scrollSpeed
 * (16) to Player_1. Java's constructor ran before scrollVelocity, so emeralds
 * spawned 16px too far left. The fix adds scrollSpeed to the spawn X in
 * routine26Explode to compensate for this ordering difference.
 *
 * <p>Level data is loaded once via {@code @BeforeAll}; sprite, camera, and
 * game state are reset per test via {@link HeadlessTestFixture}.
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestAizIntroEmeraldCollection {
    // AIZ1 start position: centre (64, 1056) -> top-left (54, 1037)
    // (Sonic width=20, height=38; top-left = centre - half-dimension)
    private static final short START_X = (short) 54;
    private static final short START_Y = (short) 1037;

    private static Object oldSkipIntros;
    private static Object oldMainCharacter;
    private static SharedLevel sharedLevel;

    @BeforeAll
    public static void loadLevel() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        oldMainCharacter = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");

        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
    }

    @AfterAll
    public static void cleanup() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS,
                oldSkipIntros != null ? oldSkipIntros : false);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE,
                oldMainCharacter != null ? oldMainCharacter : "sonic");
        if (sharedLevel != null) sharedLevel.dispose();
    }

    private HeadlessTestFixture fixture;

    @BeforeEach
    public void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .startPosition(START_X, START_Y)
                .build();
        // Reset object manager so intro cutscene objects spawn fresh per test.
        GameServices.level().getObjectManager().reset(0);
    }

    @Test
    public void allEmeraldsCollectedBeforeKnucklesExit() {
        ObjectManager objectManager = GameServices.level().getObjectManager();

        // Phase 1: Run until emeralds are spawned.
        boolean emeraldsSpawned = false;
        int guard = 2000;
        while (guard-- > 0) {
            fixture.stepFrame(false, false, false, false, false);
            if (countEmeralds(objectManager) > 0) {
                emeraldsSpawned = true;
                break;
            }
        }
        assertTrue(emeraldsSpawned, "Emerald scatter objects should spawn during AIZ intro");

        int totalEmeralds = countEmeralds(objectManager);
        System.out.println("Emeralds spawned: " + totalEmeralds);
        assertEquals(7, totalEmeralds, "All 7 emeralds should be spawned");

        // Phase 2: Find Knuckles.
        CutsceneKnucklesAiz1Instance knuckles = findKnuckles(objectManager);
        assertNotNull(knuckles, "Knuckles cutscene object should exist when emeralds are present");
        System.out.println("Knuckles found at routine " + knuckles.getRoutine()
                + " pos=(" + knuckles.getX() + ", " + knuckles.getY() + ")");

        // Phase 3: Run until Knuckles reaches routine 10 (laugh).
        // This covers fall (~30 frames), stand (128 frames), pace (84 frames).
        guard = 500;
        while (guard-- > 0 && knuckles.getRoutine() < 10) {
            fixture.stepFrame(false, false, false, false, false);
        }
        assertTrue(knuckles.getRoutine() >= 10, "Knuckles should reach routine 10 (laugh) after pacing");
        assertFalse(knuckles.isDestroyed(), "Knuckles should not be destroyed during laugh phase");

        // Phase 4: Check emerald collection DURING routine 10 (laugh).
        // Pacing is complete but Knuckles hasn't exited yet.
        // Any uncollected emeralds are still alive in the active objects list.
        List<AizEmeraldScatterInstance> uncollected = new ArrayList<>();
        for (ObjectInstance obj : objectManager.getActiveObjects()) {
            if (obj instanceof AizEmeraldScatterInstance emerald && !emerald.isDestroyed()) {
                uncollected.add(emerald);
            }
        }

        // Log diagnostics for any uncollected emeralds.
        if (!uncollected.isEmpty()) {
            System.out.println("=== UNCOLLECTED EMERALDS at Knuckles routine "
                    + knuckles.getRoutine() + " ===");
            System.out.println("Knuckles X=" + knuckles.getX()
                    + " facingLeft=" + knuckles.isFacingLeft());
            for (AizEmeraldScatterInstance e : uncollected) {
                System.out.println("  Emerald mappingFrame=" + e.getMappingFrame()
                        + " X=" + e.getX() + " Y=" + e.getY()
                        + " phase=" + e.getPhase()
                        + " xVel=" + e.getXVel());
            }
        }

        assertEquals(0, uncollected.size(), "All 7 emeralds should be collected by Knuckles before he laughs "
                + "(uncollected: " + uncollected.size() + ")");
    }

    private static int countEmeralds(ObjectManager objectManager) {
        int count = 0;
        for (ObjectInstance obj : objectManager.getActiveObjects()) {
            if (obj instanceof AizEmeraldScatterInstance && !obj.isDestroyed()) {
                count++;
            }
        }
        return count;
    }

    private static CutsceneKnucklesAiz1Instance findKnuckles(ObjectManager objectManager) {
        for (ObjectInstance obj : objectManager.getActiveObjects()) {
            if (obj instanceof CutsceneKnucklesAiz1Instance knux) {
                return knux;
            }
        }
        return null;
    }
}


