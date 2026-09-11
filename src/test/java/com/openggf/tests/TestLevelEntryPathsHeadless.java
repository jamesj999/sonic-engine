package com.openggf.tests;

import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.level.LevelManager;
import com.openggf.level.SeamlessLevelTransitionRequest;
import com.openggf.level.SeamlessLevelTransitionRequest.TransitionType;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.rings.RingManager;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for nonstandard level entry/return paths.
 * <p>
 * {@link TestActTransitionHeadless} and {@link TestActTransitionIntentionalSkips}
 * cover {@code executeActTransition()} directly. This class covers the other
 * level lifecycle entry points that manipulate zone/act counters and route
 * through {@code loadCurrentLevel()} or the seamless transition dispatcher:
 * <ul>
 *   <li>{@code nextAct()} â€” act increment with wrap</li>
 *   <li>{@code nextZone()} â€” zone increment with wrap</li>
 *   <li>{@code advanceToNextLevel()} â€” progression (act then zone)</li>
 *   <li>{@code advanceZoneActOnly()} â€” counter-only advance for special stage entry</li>
 *   <li>{@code consumeSpecialStageReturnLevelReloadRequest()} â€” flag set by above</li>
 *   <li>{@code applySeamlessTransition()} â€” dispatcher routing RELOAD_SAME_LEVEL and
 *       RELOAD_TARGET_LEVEL through executeActTransition</li>
 *   <li>{@code respawnPlayer()} â€” death respawn through loadCurrentLevel(false)</li>
 * </ul>
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestLevelEntryPathsHeadless {
    private static final int ZONE_EHZ = 0;
    private static final int ACT_1 = 0;
    private static final int ACT_2 = 1;
    private static SharedLevel sharedLevel;

    @BeforeAll
    public static void loadLevel() throws Exception {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_2, ZONE_EHZ, ACT_1);
    }

    @AfterAll
    public static void cleanup() {
        if (sharedLevel != null) sharedLevel.dispose();
    }

    private HeadlessTestFixture fixture;

    @BeforeEach
    public void setUp() throws Exception {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .startPosition((short) 96, (short) 655)
                .build();

        GameServices.camera().setFocusedSprite(fixture.sprite());
        GameServices.camera().setFrozen(false);

        // Force zone/act counters to known state. resetPerTest() does NOT
        // reset these, so tests that call nextAct()/nextZone() leave dirty
        // counters for subsequent tests. loadZoneAndAct() sets the counters
        // and routes through loadCurrentLevel(), which the SharedLevel data
        // supports.
        LevelManager lm = GameServices.level();
        if (lm.getCurrentZone() != ZONE_EHZ || lm.getCurrentAct() != ACT_1) {
            lm.loadZoneAndAct(ZONE_EHZ, ACT_1);
        }
    }

    // ========== nextAct() ==========

    @Test
    public void nextActAdvancesActCounter() throws Exception {
        LevelManager lm = GameServices.level();
        assertEquals(ACT_1, lm.getCurrentAct(), "Should start at act 0");

        lm.nextAct();

        assertEquals(ACT_2, lm.getCurrentAct(), "Act should advance to 1 after nextAct()");
    }

    @Test
    public void nextActRebuildsManagers() throws Exception {
        LevelManager lm = GameServices.level();
        ObjectManager beforeOM = lm.getObjectManager();
        RingManager beforeRM = lm.getRingManager();

        lm.nextAct();

        assertNotSame(beforeOM, lm.getObjectManager(), "ObjectManager should be rebuilt after nextAct()");
        assertNotSame(beforeRM, lm.getRingManager(), "RingManager should be rebuilt after nextAct()");
    }

    @Test
    public void nextActWrapsToZeroWhenExhausted() throws Exception {
        LevelManager lm = GameServices.level();

        // EHZ has 2 acts â€” two nextAct() calls should wrap back to 0
        lm.nextAct(); // act 0 â†’ 1
        lm.nextAct(); // act 1 â†’ 0 (wrap)

        assertEquals(ACT_1, lm.getCurrentAct(), "Act should wrap to 0 after exhausting all acts");
    }

    // ========== nextZone() ==========

    @Test
    public void nextZoneAdvancesZoneAndResetsAct() throws Exception {
        LevelManager lm = GameServices.level();

        lm.nextZone();

        assertEquals(ZONE_EHZ + 1, lm.getCurrentZone(), "Zone should advance by 1");
        assertEquals(ACT_1, lm.getCurrentAct(), "Act should reset to 0 on zone change");
    }

    @Test
    public void nextZoneRebuildsManagers() throws Exception {
        LevelManager lm = GameServices.level();
        ObjectManager beforeOM = lm.getObjectManager();

        lm.nextZone();

        assertNotSame(beforeOM, lm.getObjectManager(), "ObjectManager should be rebuilt after nextZone()");
    }

    // ========== advanceToNextLevel() ==========

    @Test
    public void advanceToNextLevelGoesToAct2First() throws Exception {
        LevelManager lm = GameServices.level();

        lm.advanceToNextLevel();

        assertEquals(ZONE_EHZ, lm.getCurrentZone(), "Should still be in same zone");
        assertEquals(ACT_2, lm.getCurrentAct(), "Should advance to act 1");
    }

    @Test
    public void advanceToNextLevelAdvancesZoneWhenActsExhausted() throws Exception {
        LevelManager lm = GameServices.level();

        // EHZ has 2 acts â€” two advanceToNextLevel() calls should move to next zone
        lm.advanceToNextLevel(); // EHZ act 0 â†’ EHZ act 1
        lm.advanceToNextLevel(); // EHZ act 1 â†’ next zone act 0

        assertEquals(ZONE_EHZ + 1, lm.getCurrentZone(), "Should advance to next zone when acts exhausted");
        assertEquals(ACT_1, lm.getCurrentAct(), "Act should reset to 0 on zone advance");
    }

    // ========== advanceZoneActOnly() ==========

    @Test
    public void advanceZoneActOnlyAdvancesCountersWithoutLoading() {
        LevelManager lm = GameServices.level();
        ObjectManager beforeOM = lm.getObjectManager();

        lm.advanceZoneActOnly();

        // Counters advance
        assertEquals(ACT_2, lm.getCurrentAct(), "Act should advance to 1");

        // But managers are NOT rebuilt (no level load happened)
        assertSame(beforeOM, lm.getObjectManager(), "ObjectManager should NOT change â€” no level load occurred");
    }

    @Test
    public void advanceZoneActOnlySetsSpecialStageReturnFlag() {
        LevelManager lm = GameServices.level();
        // Drain any pre-existing flag
        lm.consumeSpecialStageReturnLevelReloadRequest();

        lm.advanceZoneActOnly();

        assertTrue(lm.consumeSpecialStageReturnLevelReloadRequest(), "Flag should be true after advanceZoneActOnly");
    }

    @Test
    public void consumeSpecialStageReturnClearsFlag() {
        LevelManager lm = GameServices.level();
        lm.advanceZoneActOnly();

        // First consume returns true
        assertTrue(lm.consumeSpecialStageReturnLevelReloadRequest());
        // Second consume returns false (consumed)
        assertFalse(lm.consumeSpecialStageReturnLevelReloadRequest(), "Flag should be cleared after consumption");
    }

    @Test
    public void advanceZoneActOnlyClearsCheckpoint() {
        LevelManager lm = GameServices.level();
        assertNotNull(lm.getCheckpointState(), "Checkpoint state should exist");

        lm.advanceZoneActOnly();

        assertFalse(lm.getCheckpointState().isActive(), "Checkpoint should be cleared after advanceZoneActOnly");
    }

    // ========== applySeamlessTransition() dispatcher ==========

    @Test
    public void applySeamlessTransitionReloadTargetRoutesToExecuteActTransition() throws Exception {
        LevelManager lm = GameServices.level();
        ObjectManager beforeOM = lm.getObjectManager();

        SeamlessLevelTransitionRequest request = SeamlessLevelTransitionRequest
                .builder(TransitionType.RELOAD_TARGET_LEVEL)
                .targetZoneAct(ZONE_EHZ, ACT_2)
                .preserveMusic(true)
                .build();

        lm.applySeamlessTransition(request);

        // Verify it routed through executeActTransition (managers rebuilt, zone/act updated)
        assertEquals(ZONE_EHZ, lm.getCurrentZone(), "Zone should be EHZ");
        assertEquals(ACT_2, lm.getCurrentAct(), "Act should be 2");
        assertNotSame(beforeOM, lm.getObjectManager(), "ObjectManager should be rebuilt via executeActTransition");
    }

    @Test
    public void applySeamlessTransitionReloadSameRoutesToExecuteActTransition() throws Exception {
        LevelManager lm = GameServices.level();
        int zoneBefore = lm.getCurrentZone();
        int actBefore = lm.getCurrentAct();
        ObjectManager beforeOM = lm.getObjectManager();

        SeamlessLevelTransitionRequest request = SeamlessLevelTransitionRequest
                .builder(TransitionType.RELOAD_SAME_LEVEL)
                .preserveMusic(true)
                .build();

        lm.applySeamlessTransition(request);

        // RELOAD_SAME_LEVEL builds an adjusted request targeting current zone/act
        assertEquals(zoneBefore, lm.getCurrentZone(), "Zone should remain unchanged");
        assertEquals(actBefore, lm.getCurrentAct(), "Act should remain unchanged");
        assertNotSame(beforeOM, lm.getObjectManager(), "ObjectManager should be rebuilt even for same-level reload");
    }

    @Test
    public void applySeamlessTransitionNullRequestIsNoOp() {
        LevelManager lm = GameServices.level();
        ObjectManager beforeOM = lm.getObjectManager();

        lm.applySeamlessTransition(null);

        assertSame(beforeOM, lm.getObjectManager(), "Null request should be a no-op");
    }

    @Test
    public void headlessRunnerConsumesPendingSeamlessTransitionBeforeFrameTick() {
        LevelManager lm = GameServices.level();
        ObjectManager beforeOM = lm.getObjectManager();

        lm.requestSeamlessTransition(SeamlessLevelTransitionRequest
                .builder(TransitionType.RELOAD_TARGET_LEVEL)
                .targetZoneAct(ZONE_EHZ, ACT_2)
                .preserveMusic(true)
                .build());

        fixture.stepIdleFrames(1);

        assertEquals(ZONE_EHZ, lm.getCurrentZone(), "Zone should remain EHZ after transition");
        assertEquals(ACT_2, lm.getCurrentAct(), "Headless runner should apply pending seamless transition");
        assertNotSame(beforeOM, lm.getObjectManager(),
                "Headless runner should rebuild managers when consuming seamless transitions");
    }

    // ========== respawnPlayer() ==========

    @Test
    public void respawnPlayerRebuildsManagers() throws Exception {
        LevelManager lm = GameServices.level();
        ObjectManager beforeOM = lm.getObjectManager();
        RingManager beforeRM = lm.getRingManager();

        lm.respawnPlayer();

        assertNotSame(beforeOM, lm.getObjectManager(), "ObjectManager should be rebuilt after respawn");
        assertNotSame(beforeRM, lm.getRingManager(), "RingManager should be rebuilt after respawn");
    }

    @Test
    public void respawnPlayerPreservesZoneAndAct() throws Exception {
        LevelManager lm = GameServices.level();
        int zoneBefore = lm.getCurrentZone();
        int actBefore = lm.getCurrentAct();

        lm.respawnPlayer();

        assertEquals(zoneBefore, lm.getCurrentZone(), "Zone should not change on respawn");
        assertEquals(actBefore, lm.getCurrentAct(), "Act should not change on respawn");
    }

    // ========== loadZoneAndAct() ==========

    @Test
    public void loadZoneAndActSetsCountersAndLoads() throws Exception {
        LevelManager lm = GameServices.level();
        ObjectManager beforeOM = lm.getObjectManager();

        lm.loadZoneAndAct(ZONE_EHZ, ACT_2);

        assertEquals(ZONE_EHZ, lm.getCurrentZone(), "Zone should be set to target");
        assertEquals(ACT_2, lm.getCurrentAct(), "Act should be set to target");
        assertNotSame(beforeOM, lm.getObjectManager(), "Managers should be rebuilt after loadZoneAndAct");
    }
}



