package com.openggf.tests;

import com.openggf.game.GameServices;
import com.openggf.level.CarriedTitlePublicationTiming;
import com.openggf.level.LevelManager;
import com.openggf.level.SeamlessLevelTransitionRequest;
import com.openggf.level.SeamlessLevelTransitionRequest.TransitionType;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.rings.RingManager;
import com.openggf.camera.Camera;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.*;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link LevelManager#executeActTransition}.
 * <p>
 * Verifies that act transitions rebuild the ObjectManager and RingManager
 * with the new act's spawn data, rather than just clearing runtime state
 * on the old managers (which would leave stale spawn lists).
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestActTransitionHeadless {
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
    public void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .startPosition((short) 96, (short) 655)
                .build();

        // Ensure camera has a focused sprite â€” executeActTransition calls
        // camera.updatePosition(true) which requires a focused sprite.
        // resetPerTest() creates a fresh Camera singleton with no focus.
        GameServices.camera().setFocusedSprite(fixture.sprite());
        GameServices.camera().setFrozen(false);
    }

    // ========== Manager Rebuild ==========

    @Test
    public void executeActTransitionRebuildsObjectManager() throws Exception {
        LevelManager lm = GameServices.level();
        ObjectManager beforeOM = lm.getObjectManager();
        assertNotNull(beforeOM, "ObjectManager should exist before transition");

        SeamlessLevelTransitionRequest request = SeamlessLevelTransitionRequest
                .builder(TransitionType.RELOAD_TARGET_LEVEL)
                .targetZoneAct(ZONE_EHZ, ACT_2)
                .preserveMusic(true)
                .build();

        lm.executeActTransition(request);

        ObjectManager afterOM = lm.getObjectManager();
        assertNotNull(afterOM, "ObjectManager should exist after transition");
        assertNotSame(beforeOM, afterOM, "ObjectManager should be a new instance after transition");
    }

    @Test
    public void executeActTransitionPreservesGlobalVintClock() throws Exception {
        LevelManager lm = GameServices.level();
        ObjectManager beforeOM = lm.getObjectManager();
        beforeOM.initVblaCounter(0x4567);

        SeamlessLevelTransitionRequest request = SeamlessLevelTransitionRequest
                .builder(TransitionType.RELOAD_TARGET_LEVEL)
                .targetZoneAct(ZONE_EHZ, ACT_2)
                .preserveMusic(true)
                .build();

        lm.executeActTransition(request);

        assertEquals(0x4567, lm.getObjectManager().getVblaCounter(),
                "Load_Level rebuilds object RAM but must not reset global V_int_run_count");
    }

    @Test
    public void executeActTransitionInheritsRingFloorPhaseWithoutChangingItsNumericValue() throws Exception {
        LevelManager lm = GameServices.level();
        lm.initRingFloorCheckCounterPhase(7);

        SeamlessLevelTransitionRequest request = SeamlessLevelTransitionRequest
                .builder(TransitionType.RELOAD_TARGET_LEVEL)
                .targetZoneAct(ZONE_EHZ, ACT_2)
                .preserveMusic(true)
                .build();

        lm.executeActTransition(request);

        assertEquals(7, lm.getObjectManager().getRingFloorCheckCounterPhase(),
                "Load_Level preserves the global V_int-derived floor-check phase");
        assertTrue(lm.getObjectManager().hasInheritedRingCounterPhase(),
                "the rebuilt manager separately records that its phase was inherited");
    }

    @Test
    public void fullLevelReloadPreservesGlobalVintClock() {
        LevelManager lm = GameServices.level();
        ObjectManager beforeOM = lm.getObjectManager();
        beforeOM.initVblaCounter(0x4567);

        lm.loadCurrentLevel();

        assertNotSame(beforeOM, lm.getObjectManager(),
                "A full reload must rebuild dynamic object RAM");
        assertEquals(0x4567, lm.getObjectManager().getVblaCounter(),
                "Global V_int_run_count survives deaths and results-screen level loads");
    }

    @Test
    public void seamlessReloadTicksGlobalVintClockAcrossTransitionOnlyFrame() {
        LevelManager lm = GameServices.level();
        lm.getObjectManager().initVblaCounter(0x4567);

        SeamlessLevelTransitionRequest request = SeamlessLevelTransitionRequest
                .builder(TransitionType.RELOAD_TARGET_LEVEL)
                .targetZoneAct(ZONE_EHZ, ACT_2)
                .preserveMusic(true)
                .build();

        lm.applySeamlessTransition(request);

        assertEquals(0x4568, lm.getObjectManager().getVblaCounter(),
                "The transition-only row still runs V-int even though ObjectManager.update is skipped");
    }

    @Test
    public void executeActTransitionRebuildsRingManager() throws Exception {
        LevelManager lm = GameServices.level();
        RingManager beforeRM = lm.getRingManager();
        assertNotNull(beforeRM, "RingManager should exist before transition");

        SeamlessLevelTransitionRequest request = SeamlessLevelTransitionRequest
                .builder(TransitionType.RELOAD_TARGET_LEVEL)
                .targetZoneAct(ZONE_EHZ, ACT_2)
                .preserveMusic(true)
                .build();

        lm.executeActTransition(request);

        RingManager afterRM = lm.getRingManager();
        assertNotNull(afterRM, "RingManager should exist after transition");
        assertNotSame(beforeRM, afterRM, "RingManager should be a new instance after transition");
    }

    @Test
    public void executeActTransitionCanPreserveEndOfLevelGlobals() throws Exception {
        GameServices.gameState().setEndOfLevelActive(true);
        GameServices.gameState().setEndOfLevelFlag(true);

        SeamlessLevelTransitionRequest request = SeamlessLevelTransitionRequest
                .builder(TransitionType.RELOAD_TARGET_LEVEL)
                .targetZoneAct(ZONE_EHZ, ACT_2)
                .preserveEndOfLevelState(true)
                .build();

        GameServices.level().executeActTransition(request);

        assertTrue(GameServices.gameState().isEndOfLevelActive());
        assertTrue(GameServices.gameState().isEndOfLevelFlag());
    }

    @Test
    public void executeActTransitionSpawnListIsNotStaleReference() throws Exception {
        LevelManager lm = GameServices.level();

        // Capture the Act 1 ObjectManager's spawn list reference
        List<ObjectSpawn> act1SpawnRef = lm.getObjectManager().getAllSpawns();

        SeamlessLevelTransitionRequest request = SeamlessLevelTransitionRequest
                .builder(TransitionType.RELOAD_TARGET_LEVEL)
                .targetZoneAct(ZONE_EHZ, ACT_2)
                .preserveMusic(true)
                .build();

        lm.executeActTransition(request);

        // The new manager's spawn list should NOT be the same List instance
        // as the old manager's. Even if the data happens to match (EHZ shares
        // layout), the reference must come from the rebuilt manager.
        List<ObjectSpawn> act2SpawnRef = lm.getObjectManager().getAllSpawns();
        assertNotSame(act1SpawnRef, act2SpawnRef, "Spawn list reference should be from new manager, not stale");
    }

    @Test
    public void executeActTransitionObjectsMatchLevel() throws Exception {
        LevelManager lm = GameServices.level();

        SeamlessLevelTransitionRequest request = SeamlessLevelTransitionRequest
                .builder(TransitionType.RELOAD_TARGET_LEVEL)
                .targetZoneAct(ZONE_EHZ, ACT_2)
                .preserveMusic(true)
                .build();

        lm.executeActTransition(request);

        // After transition, ObjectManager's spawns should exactly match
        // the level's object list (proving rebuild used new data)
        List<ObjectSpawn> managerSpawns = lm.getObjectManager().getAllSpawns();
        List<ObjectSpawn> levelSpawns = lm.getCurrentLevel().getObjects();
        assertEquals(levelSpawns.size(), managerSpawns.size(), "ObjectManager spawns should match level objects after transition");
        assertEquals(levelSpawns, managerSpawns, "ObjectManager spawns should be identical to level objects");
    }

    // ========== Coordinate Offsets ==========

    @Test
    public void executeActTransitionCompletesWithOffsets() throws Exception {
        // Verifies that executeActTransition handles player and camera offsets
        // without error. Exact offset values aren't asserted because
        // restoreCameraBoundsForCurrentLevel + updatePosition(true) snap
        // final positions to level bounds.
        SeamlessLevelTransitionRequest request = SeamlessLevelTransitionRequest
                .builder(TransitionType.RELOAD_TARGET_LEVEL)
                .targetZoneAct(ZONE_EHZ, ACT_2)
                .playerOffset(100, -50)
                .cameraOffset(50, -25)
                .preserveMusic(true)
                .build();

        GameServices.level().executeActTransition(request);

        assertNotNull(GameServices.level().getCurrentLevel(), "Level should exist after transition with offsets");
    }

    @Test
    public void executeActTransitionOffsetsCarriedPersistentObjects() throws Exception {
        // ROM Offset_ObjectsDuringTransition shifts every surviving object by the
        // same world delta as the players/camera. The CNZ/HCZ/MGZ end signpost is a
        // persistent dynamic object carried across the seamless reload; if it is not
        // offset, it is stranded at its Act 1 world position and culled off-screen
        // (the reported CNZ "signpost disappears after it stops" bug).
        LevelManager lm = GameServices.level();
        CarryStub carried = new CarryStub(0x1000, 0x0800);
        lm.getObjectManager().addDynamicObject(carried);

        SeamlessLevelTransitionRequest request = SeamlessLevelTransitionRequest
                .builder(TransitionType.RELOAD_TARGET_LEVEL)
                .targetZoneAct(ZONE_EHZ, ACT_2)
                .playerOffset(0x100, -0x80)
                .cameraOffset(0x100, -0x80)
                .preserveMusic(true)
                .build();

        lm.executeActTransition(request);

        assertTrue(lm.getObjectManager().getActiveObjects().contains(carried),
                "Persistent dynamic objects must survive the seamless object-manager rebuild");
        assertTrue(carried.offsetCalled,
                "Carried persistent objects must receive the transition world offset "
                        + "(ROM Offset_ObjectsDuringTransition)");
        assertEquals(0x1000 + 0x100, carried.worldX,
                "Carried object X must shift by the transition world delta");
        assertEquals(0x0800 - 0x80, carried.worldY,
                "Carried object Y must shift by the transition world delta");
    }

    @Test
    public void sameLevelReloadCarriesExactTitleTimingWhenWorldOffsetIsZero() {
        LevelManager lm = GameServices.level();
        TimingCarryStub carried = new TimingCarryStub();
        lm.getObjectManager().addDynamicObject(carried);

        SeamlessLevelTransitionRequest request = SeamlessLevelTransitionRequest
                .builder(TransitionType.RELOAD_SAME_LEVEL)
                .preserveMusic(true)
                .showInLevelTitleCard(false)
                .resetLevelGamestateAtInLevelTitleCardDisplay(true)
                .inLevelTitleCardResetAdditionalDispatches(12)
                .inLevelTitleCardResetPhaseOneDispatchOverlap(6)
                .lockPlayerControlForInLevelTitleCard(true)
                .inLevelTitleCardExitAdditionalDispatches(10)
                .inLevelTitleCardExitPhaseOneDispatchOverlap(5)
                .build();

        lm.applySeamlessTransition(request);

        assertTrue(carried.offsetCalled,
                "the semantic carry hook must run even without a geometric offset");
        assertEquals(new CarriedTitlePublicationTiming(
                        true, true, true, 12, 6, true, 10, 5, -1),
                carried.titleTiming,
                "the carried object must receive timing from the exact transition request");
    }

    /** Minimal persistent object that records the seamless-transition offset hook. */
    private static final class CarryStub extends com.openggf.level.objects.AbstractObjectInstance {
        int worldX;
        int worldY;
        boolean offsetCalled;

        CarryStub(int x, int y) {
            super(null, "CarryStub");
            this.worldX = x;
            this.worldY = y;
        }

        @Override public int getX() { return worldX; }
        @Override public int getY() { return worldY; }
        @Override public boolean isPersistent() { return true; }
        @Override public boolean isHighPriority() { return false; }
        @Override public void update(int vIntRunCount, com.openggf.game.PlayableEntity player) { }
        @Override public void appendRenderCommands(java.util.List<com.openggf.graphics.GLCommand> commands) { }

        @Override
        public void onCarriedAcrossSeamlessTransition(int offsetX, int offsetY) {
            offsetCalled = true;
            worldX += offsetX;
            worldY += offsetY;
        }
    }

    private static final class TimingCarryStub
            extends com.openggf.level.objects.AbstractObjectInstance {
        boolean offsetCalled;
        CarriedTitlePublicationTiming titleTiming;

        TimingCarryStub() {
            super(null, "TimingCarryStub");
        }

        @Override public boolean isPersistent() { return true; }
        @Override public boolean isHighPriority() { return false; }
        @Override public void update(int vIntRunCount, com.openggf.game.PlayableEntity player) { }
        @Override public void appendRenderCommands(java.util.List<com.openggf.graphics.GLCommand> commands) { }

        @Override
        public void onCarriedAcrossSeamlessTransition(
                int offsetX,
                int offsetY,
                CarriedTitlePublicationTiming timing) {
            offsetCalled = true;
            titleTiming = timing;
        }
    }

    // ========== Zone/Act State ==========

    @Test
    public void executeActTransitionUpdatesCurrentZoneAndAct() throws Exception {
        LevelManager lm = GameServices.level();

        SeamlessLevelTransitionRequest request = SeamlessLevelTransitionRequest
                .builder(TransitionType.RELOAD_TARGET_LEVEL)
                .targetZoneAct(ZONE_EHZ, ACT_2)
                .preserveMusic(true)
                .build();

        lm.executeActTransition(request);

        assertEquals(ZONE_EHZ, lm.getCurrentZone(), "Zone should be EHZ after transition");
        assertEquals(ACT_2, lm.getCurrentAct(), "Act should be 2 (index 1) after transition");
        assertTrue(lm.consumeActTransitionRewindBoundaryDuringFrame(),
                "a direct in-frame act reload must publish a rewind boundary");
        assertFalse(lm.consumeActTransitionRewindBoundaryDuringFrame(),
                "the in-frame rewind boundary signal must be one-shot");
    }

    @Test
    public void executeActTransitionResetsLevelGamestateByDefault() throws Exception {
        LevelManager lm = GameServices.level();
        lm.getLevelGamestate().setRings(37);
        lm.getLevelGamestate().setTimerFrames(1234);

        SeamlessLevelTransitionRequest request = SeamlessLevelTransitionRequest
                .builder(TransitionType.RELOAD_TARGET_LEVEL)
                .targetZoneAct(ZONE_EHZ, ACT_2)
                .preserveMusic(true)
                .build();

        lm.executeActTransition(request);

        assertEquals(0, lm.getLevelGamestate().getRings());
        assertEquals(0, lm.getLevelGamestate().getTimerFrames());
    }

    @Test
    public void executeActTransitionCanPreserveLevelGamestate() throws Exception {
        LevelManager lm = GameServices.level();
        lm.getLevelGamestate().setRings(37);
        lm.getLevelGamestate().setTimerFrames(1234);

        SeamlessLevelTransitionRequest request = SeamlessLevelTransitionRequest
                .builder(TransitionType.RELOAD_TARGET_LEVEL)
                .targetZoneAct(ZONE_EHZ, ACT_2)
                .preserveMusic(true)
                .preserveLevelGamestate(true)
                .build();

        lm.executeActTransition(request);

        assertEquals(37, lm.getLevelGamestate().getRings());
        assertEquals(1234, lm.getLevelGamestate().getTimerFrames());
    }

    // ========== Manager Windowing Uses Live Camera ==========

    @Test
    public void executeActTransitionWindowsManagersAtLiveCameraX() throws Exception {
        LevelManager lm = GameServices.level();

        // Position the live camera at X=5000, well past the level start.
        // If rebuildManagersForActTransition uses the stale cached camera
        // field (which may hold X=0 after Camera.resetState()), the
        // spawn window would be [âˆ’128, 640] and no objects near X=5000
        // would be active. The correct behavior uses the live camera so
        // the spawn window is [4872, 5640].
        Camera cam = GameServices.camera();
        cam.setX((short) 5000);

        SeamlessLevelTransitionRequest request = SeamlessLevelTransitionRequest
                .builder(TransitionType.RELOAD_TARGET_LEVEL)
                .targetZoneAct(ZONE_EHZ, ACT_2)
                .preserveMusic(true)
                .build();

        lm.executeActTransition(request);

        // After transition, active spawns should include objects near
        // the camera (X=5000), not near the level start (X=0).
        Collection<ObjectSpawn> active = lm.getObjectManager().getActiveSpawns();
        boolean hasSpawnNearCamera = active.stream()
                .anyMatch(s -> s.x() >= 4800 && s.x() <= 5700);
        boolean hasSpawnNearStart = active.stream()
                .anyMatch(s -> s.x() < 1000);

        assertTrue(hasSpawnNearCamera, "Active spawns should include objects near camera X=5000, " +
                "proving windowing used the live camera (found " + active.size() +
                " active spawns)");
        assertFalse(hasSpawnNearStart, "Active spawns should NOT include objects near level start " +
                "when camera is at X=5000");
    }

    // ========== Music Suppression Does Not Leak ==========

    @Test
    public void executeActTransitionDoesNotLatchMusicSuppression() throws Exception {
        LevelManager lm = GameServices.level();
        assertFalse(lm.isSuppressNextMusicChange(),
                "Music suppression should not be latched before the transition");

        SeamlessLevelTransitionRequest request = SeamlessLevelTransitionRequest
                .builder(TransitionType.RELOAD_TARGET_LEVEL)
                .targetZoneAct(ZONE_EHZ, ACT_2)
                .preserveMusic(true)
                .build();

        lm.executeActTransition(request);

        // An in-place act transition never runs the level-init profile, so nothing
        // consumes the suppress flag. Latching it here silenced the next real level
        // load until a respawn or a further load cleared it.
        assertFalse(lm.isSuppressNextMusicChange(),
                "preserveMusic() must not leave music suppression latched for the next level load");
    }

    @Test
    public void levelLoadClearsMusicSuppressionForTheFollowingLoad() throws Exception {
        LevelManager lm = GameServices.level();

        // A caller that suppresses music for one load (bonus/special-stage return,
        // credits demo) must not affect the load after it.
        lm.setSuppressNextMusicChange(true);
        lm.loadZoneAndAct(ZONE_EHZ, ACT_2);

        assertFalse(lm.isSuppressNextMusicChange(),
                "Music suppression is single-use and must be cleared by the level load that consumed it");
    }
}
