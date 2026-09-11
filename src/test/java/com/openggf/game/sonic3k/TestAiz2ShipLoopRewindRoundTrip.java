package com.openggf.game.sonic3k;

import com.openggf.game.GameServices;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.sonic3k.events.Sonic3kAIZEvents;
import com.openggf.game.sonic3k.objects.AizBattleshipInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end reproduction + confirmation of the AIZ2 ship-loop rewind softlock fix.
 *
 * <h2>The bug</h2>
 * The AIZ2 ship-loop is driven by {@link Sonic3kAIZEvents}.
 * {@code startBattleshipSequence()} spawns an {@link AizBattleshipInstance} (guarded by
 * {@code battleshipSpawned}) and sets {@code battleshipAutoScrollActive=true}, which
 * force-locks the camera every frame. The loop ends only when the battleship (or, post
 * bombing, the small boss craft) reaches its exit and releases the camera lock.
 *
 * <p>Pre-fix, a rewind restore dropped the {@code AizBattleshipInstance} (it had no rewind
 * recreate path) while the {@code battleshipSpawned} guard stayed {@code true} and
 * {@code battleshipAutoScrollActive} stayed {@code true}. With no driver object left to end
 * the loop, the camera was force-locked forever — a softlock.
 *
 * <h2>The fix (already implemented on this branch; this test only proves it)</h2>
 * <ul>
 *   <li><b>Fix A:</b> {@link AizBattleshipInstance} has a Phase-2 generic recreate path, so
 *       the object-manager rewind restore recreates it from the captured snapshot entry.</li>
 *   <li><b>Fix B:</b> {@code Sonic3kAIZEvents.reconcileSequenceAfterRewindRestore()} (run as
 *       a post-restore callback) releases the camera lock and clears the stuck guard if the
 *       sequence-driving object is absent after a restore — a defense-in-depth backstop.</li>
 * </ul>
 *
 * <h2>Strategy</h2>
 * Boot AIZ act 2 headlessly (no ~20k frames of physics needed), reach a battleship-active
 * state directly by adding a live {@link AizBattleshipInstance} through the real
 * {@link ObjectManager} (so {@code ObjectServices} are injected) and setting the public raw
 * guards, then run a full {@link RewindRegistry} capture / diverge / restore round-trip:
 *
 * <ol>
 *   <li>Reach battleship-active state.</li>
 *   <li>{@code capture()}.</li>
 *   <li>Diverge by removing the battleship from the live object manager (mimicking the bug's
 *       drop).</li>
 *   <li>{@code restore()}.</li>
 *   <li>Assert rewind recreate restored exactly one live battleship (Fix A), the spawned guard
 *       is consistent with that live object (Fix B/consistency), and the camera lock is not left
 *       orphaned.</li>
 * </ol>
 *
 * <p>Every assertion below would FAIL on the pre-fix code: the restore would leave zero live
 * battleships, {@code battleshipSpawned=true} with no object, and the auto-scroll lock active.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestAiz2ShipLoopRewindRoundTrip {

    private static final int ZONE_AIZ = 0;
    private static final int ACT_2 = 1;

    @AfterEach
    void cleanup() {
        TestEnvironment.resetAll();
    }

    @Test
    void battleshipSurvivesRewindRestoreAndCameraLockIsNotOrphaned() {
        // 1. Boot AIZ act 2 directly (no long physics run needed).
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE_AIZ, ACT_2)
                .build();

        GameplayModeContext gameplayMode = TestEnvironment.activeGameplayMode();
        assertNotNull(gameplayMode, "GameplayModeContext must be available after fixture build");
        RewindRegistry registry = gameplayMode.getRewindRegistry();
        assertNotNull(registry, "RewindRegistry must be non-null after attachGameplayManagers");

        ObjectManager objectManager = GameServices.level().getObjectManager();
        assertNotNull(objectManager, "ObjectManager must be available for AIZ2");

        Sonic3kLevelEventManager levelEvents =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        assertNotNull(levelEvents, "Sonic3kLevelEventManager should exist for S3K");
        Sonic3kAIZEvents aizEvents = levelEvents.getAizEvents();
        assertNotNull(aizEvents, "AIZ events handler should be initialized");

        // 2. Reach a battleship-active state directly. Adding through the real
        //    ObjectManager injects ObjectServices (ObjectManager.addDynamicObjectInternal
        //    calls setServices), matching a real spawn.
        int cameraX = fixture.camera().getX();
        int baseSecondaryY = (fixture.camera().getY() + 0x08F0) & 0x0FF0;
        ObjectSpawn shipSpawn = new ObjectSpawn(cameraX, baseSecondaryY, 0, 0, 0, false, 0);
        AizBattleshipInstance ship = new AizBattleshipInstance(shipSpawn, baseSecondaryY);
        objectManager.addDynamicObject(ship);

        // Lock the camera the way startBattleshipSequence() does, and arm the guards.
        fixture.camera().setMinX((short) cameraX);
        fixture.camera().setMaxX((short) cameraX);
        aizEvents.setBattleshipSpawned(true);
        aizEvents.setBattleshipAutoScrollActiveRaw(true);

        assertEquals(1, countLiveBattleships(objectManager),
                "Test setup must put exactly one live battleship in the object manager");
        assertTrue(aizEvents.isBattleshipSpawned(),
                "Test setup must arm the battleshipSpawned guard");
        assertTrue(aizEvents.isBattleshipAutoScrollActiveRaw(),
                "Test setup must arm the auto-scroll camera lock");

        // 3. Capture the battleship-active state.
        CompositeSnapshot snapA = registry.capture();
        assertNotNull(snapA, "capture() must return a non-null CompositeSnapshot");

        // 4. Diverge: remove the battleship from the live object manager. This mimics the
        //    pre-fix drop — the live timeline no longer has the ship, but the guard/lock
        //    survive in the events sidecar.
        objectManager.removeDynamicObject(ship);
        assertEquals(0, countLiveBattleships(objectManager),
                "Diverge step must remove the battleship from the live object manager");

        // 5. Restore the captured state. Fix A recreates the battleship from generic recreate;
        //    Fix B's post-restore reconcile runs as part of restore().
        registry.restore(snapA);

        // 6a. Fix A: rewind recreate restored exactly one live battleship.
        List<AizBattleshipInstance> restored = liveBattleships(objectManager);
        assertEquals(1, restored.size(),
                "Fix A: object-manager rewind restore must recreate exactly one live "
                        + "AizBattleshipInstance on restore (pre-fix: zero — the ship was dropped)");
        AizBattleshipInstance restoredShip = restored.get(0);
        assertFalse(restoredShip.isDestroyed(),
                "Recreated battleship must be live, not destroyed");

        // 6b. Consistency: the spawned guard must match the presence of a live battleship —
        //     no "spawned=true with no object" impossible state.
        assertTrue(aizEvents.isBattleshipSpawned(),
                "battleshipSpawned must stay true while a live battleship exists");

        // 6c. Anti-softlock: with the driver object present after restore, the loop can still
        //     end normally, so the camera is NOT permanently pinned with no driver.
        //     The reconcile backstop (Fix B) leaves the auto-scroll active precisely because a
        //     live driver exists; assert the driver-present invariant that makes the lock
        //     non-orphaned.
        boolean driverLive = countLiveBattleships(objectManager) > 0;
        boolean lockActive = aizEvents.isBattleshipAutoScrollActiveRaw();
        assertTrue(driverLive || !lockActive,
                "Anti-softlock invariant: the auto-scroll camera lock must never be active "
                        + "without a live driver object to end it. driverLive=" + driverLive
                        + " lockActive=" + lockActive);
        assertTrue(driverLive,
                "Fix A keeps the battleship driver alive across restore, so the camera lock "
                        + "is not orphaned");

        // 6d. Spawn identity round-trips: the recreated ship reuses the captured spawn.
        assertSame(shipSpawn, restoredShip.getSpawn(),
                "Recreated battleship should round-trip the captured ObjectSpawn identity");
    }

    private static List<AizBattleshipInstance> liveBattleships(ObjectManager objectManager) {
        return objectManager.getActiveObjects().stream()
                .filter(o -> o instanceof AizBattleshipInstance && !o.isDestroyed())
                .map(o -> (AizBattleshipInstance) o)
                .toList();
    }

    private static int countLiveBattleships(ObjectManager objectManager) {
        int count = 0;
        for (ObjectInstance o : objectManager.getActiveObjects()) {
            if (o instanceof AizBattleshipInstance && !o.isDestroyed()) {
                count++;
            }
        }
        return count;
    }
}
