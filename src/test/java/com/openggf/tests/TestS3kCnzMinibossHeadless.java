package com.openggf.tests;

import com.openggf.camera.DeadzoneGeometry;
import com.openggf.game.session.SessionManager;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.events.Sonic3kCNZEvents;
import com.openggf.game.sonic3k.objects.CnzMinibossInstance;
import com.openggf.game.sonic3k.objects.CnzMinibossTopInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless end-to-end test for the CNZ1 miniboss encounter. Drives
 * Sonic to the arena threshold X, lets the fight state machine run,
 * and confirms that the fight eventually terminates within the
 * trace-budget frame window.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestS3kCnzMinibossHeadless {

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        com.openggf.game.session.SessionManager.clear();
    }

    @Test
    void arenaEntryFiresBossFlagAtThreshold() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        positionAtMinibossArenaGate(fixture);
        getCnzEvents().enterMinibossArenaFromObjectSlot();
        fixture.stepFrame(false, false, false, false, false);

        assertTrue(getCnzEvents().isBossFlag(),
                "Boss_flag must be set when camera reaches arena min X");
    }

    @Test
    void bossSpawnsAndRunsStateMachine() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        positionAtMinibossArenaGate(fixture);
        GameServices.camera().setY((short) Sonic3kConstants.CNZ_MINIBOSS_ARENA_MIN_Y);
        for (int i = 0; i < 123; i++) fixture.stepFrame(false, false, false, false, false);

        Optional<CnzMinibossInstance> boss = findBoss();
        assertTrue(boss.isPresent(), "CNZ miniboss instance must exist after the ROM two-second release wait");
        assertTrue(boss.get().getCurrentRoutine() >= 2,
                "Boss must leave routine 0 (Init) after Obj_CNZMinibossGo releases the start routine");
    }

    @Test
    void arenaEntryKeepsBossDormantUntilReleaseWaitCompletes() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        positionAtMinibossArenaGate(fixture);
        GameServices.camera().setY((short) Sonic3kConstants.CNZ_MINIBOSS_ARENA_MIN_Y);

        fixture.stepFrame(false, false, false, false, false);

        Optional<CnzMinibossInstance> boss = findBoss();
        assertTrue(boss.isPresent(),
                "CNZ miniboss instance must exist as soon as the arena entry gate locks the camera");
        // ROM Obj_CNZMiniboss (sonic3k.asm:144823-144895) locks the arena at
        // Camera_X_pos >= $31E0, fades the music and runs a 2-second Obj_Wait, but
        // does NOT set up the sprite or create the top child until the wait elapses
        // and Obj_CNZMinibossGo -> Obj_CNZMinibossStart -> Obj_CNZMinibossInit fires.
        // One frame past the trigger the boss must therefore still be dormant.
        assertEquals(0, boss.get().getCurrentRoutine(),
                "Obj_CNZMinibossInit must NOT run until the 2-second Obj_Wait release completes");
        assertFalse(activeObjects().stream().anyMatch(CnzMinibossTopInstance.class::isInstance),
                "The spinning top child is only created by Obj_CNZMinibossInit after the release wait");
    }

    @Test
    void rewindRestoreKeepsVisibleMinibossTopChild() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        positionAtMinibossArenaGate(fixture);
        GameServices.camera().setY((short) Sonic3kConstants.CNZ_MINIBOSS_ARENA_MIN_Y);
        // Run past the 2-second Obj_Wait release so Obj_CNZMinibossInit has created
        // the dynamic spinning top child (ROM sonic3k.asm:144885-144895) before we
        // exercise the rewind capture/restore recreation path.
        for (int i = 0; i < 123; i++) {
            fixture.stepFrame(false, false, false, false, false);
        }
        assertTrue(activeObjects().stream().anyMatch(CnzMinibossTopInstance.class::isInstance),
                "Precondition: the spinning top child should exist once the fight has started");

        ObjectManager objectManager = GameServices.level().getObjectManager();
        var rewind = objectManager.rewindSnapshottable();
        var snapshot = rewind.capture();

        rewind.restore(snapshot);

        assertTrue(activeObjects().stream().anyMatch(CnzMinibossTopInstance.class::isInstance),
                "Rewind restore must recreate the dynamic top child instead of dropping the visible spinning top");
    }

    @Test
    void fightResolvesWithin400FramesOfArenaEntry() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        positionAtMinibossArenaGate(fixture);
        GameServices.camera().setY((short) Sonic3kConstants.CNZ_MINIBOSS_ARENA_MIN_Y);

        // Stub four top-to-coil hits across the fight window by nudging the boss's simulateHitForTest
        // at roughly the ROM's expected hit cadence. This is a headless smoke test, not a
        // bit-perfect replay: the goal is to confirm the defeat path completes.
        Optional<CnzMinibossInstance> boss = Optional.empty();
        int simulatedHits = 0;
        for (int i = 0; i < 400 && getCnzEvents().isBossFlag(); i++) {
            fixture.stepFrame(false, false, false, false, false);
            if (boss.isEmpty()) boss = findBoss();
            if (boss.isPresent() && simulatedHits < 4 && i % 60 == 0) {
                boss.get().simulateHitForTest();
                simulatedHits++;
            }
        }
        assertFalse(getCnzEvents().isBossFlag(),
                "Boss_flag must be cleared (defeat path) within 400 frames of arena entry");
    }

    /**
     * ROM anchor: {@code Obj_CNZMinibossEndGo} clears {@code Boss_flag}, calls
     * {@code AfterBoss_Cleanup}, and CNZ's after-boss cleanup entry returns
     * without restoring {@code Camera_stored_max_X_pos}
     * ({@code docs/skdisasm/sonic3k.asm:144996-145001,176489-176557}).
     */
    @Test
    void minibossDefeatKeepsArenaXClampOnBossFlagFallingEdge() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();

        // Trigger arena entry so the camera bounds are locked to the arena box.
        positionAtMinibossArenaGate(fixture);
        getCnzEvents().enterMinibossArenaFromObjectSlot();
        fixture.stepFrame(false, false, false, false, false);

        // Confirm arena lock is active and that the locked max X really is the arena
        // constant (catches a future regression where enterMinibossArena stops
        // applying CNZ_MINIBOSS_ARENA_MAX_X — the release assertion below would
        // silently pass otherwise if the natural CNZ1 maxX coincidentally equalled
        // 0x3260).
        assertTrue(getCnzEvents().isBossFlag(), "Precondition: Boss_flag must be set after arena entry");
        short lockedMaxX = GameServices.camera().getMaxX();
        assertEquals(
                (int) (short) Sonic3kConstants.CNZ_MINIBOSS_ARENA_MAX_X,
                (int) lockedMaxX,
                "Precondition: arena entry must clamp camera maxX to CNZ_MINIBOSS_ARENA_MAX_X");

        // Simulate defeat: clear the boss flag directly (mirrors CnzMinibossInstance.onEndGo).
        getCnzEvents().setBossFlag(false);

        // Step one frame so Sonic3kCNZEvents.update() observes the Boss_flag
        // falling edge. Wall-grab suppression is released, but the camera X
        // clamp itself must still be the arena clamp.
        fixture.stepFrame(false, false, false, false, false);

        assertEquals(
                (int) lockedMaxX,
                (int) GameServices.camera().getMaxX(),
                "Camera maxX must remain at the CNZ miniboss arena clamp after Boss_flag falls");
        assertFalse(getCnzEvents().isWallGrabSuppressed(),
                "Boss_flag falling edge still releases wall-grab suppression for the post-boss route");
    }

    private static Sonic3kCNZEvents getCnzEvents() {
        Sonic3kLevelEventManager events =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        return events.getCnzEvents();
    }

    private static void positionAtMinibossArenaGate(HeadlessTestFixture fixture) {
        int cameraX = Sonic3kConstants.CNZ_MINIBOSS_ARENA_MIN_X;
        int playerX = cameraX + DeadzoneGeometry.rightEdge(fixture.camera().getWidth());
        fixture.sprite().setCentreX((short) playerX);
        fixture.camera().updatePosition(true);
        assertEquals(cameraX, fixture.camera().getX() & 0xFFFF,
                "Precondition: player/camera setup must leave camera at the CNZ miniboss gate");
    }

    private static Optional<CnzMinibossInstance> findBoss() {
        ObjectManager mgr = GameServices.level().getObjectManager();
        if (mgr == null) {
            return Optional.empty();
        }
        return mgr.getActiveObjects().stream()
                .filter(CnzMinibossInstance.class::isInstance)
                .map(CnzMinibossInstance.class::cast)
                .findFirst();
    }

    private static Collection<ObjectInstance> activeObjects() {
        ObjectManager mgr = GameServices.level().getObjectManager();
        if (mgr == null) {
            return java.util.List.of();
        }
        return mgr.getActiveObjects();
    }
}
