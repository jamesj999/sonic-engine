package com.openggf.game.sonic3k;

import com.openggf.game.GameServices;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.sonic3k.objects.AizBattleshipInstance;
import com.openggf.game.sonic3k.objects.AizShipBombInstance;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces and confirms the fix for AIZ2 transient combat/cosmetic children
 * replaying forward across a held rewind.
 *
 * <h2>The bug</h2>
 * Held rewind restores the nearest keyframe and re-simulates forward to the
 * displayed frame each frame. An object reverses cleanly only if it is captured
 * in the keyframe and recreated on restore. The battleship's dropped bombs
 * ({@link AizShipBombInstance}) were intentionally <em>dropped</em> on restore
 * (no rewind codec), so the re-simulation re-emitted them from scratch — the
 * bombs visibly fell forward and re-triggered instead of rewinding.
 *
 * <h2>The fix</h2>
 * {@link AizShipBombInstance} now restores through generic recreate, relinking
 * the live battleship parent during restore. The bomb's mid-flight scalar state
 * ({@code state}, {@code portYOffset}, ...) is captured by the generic field
 * capturer and reapplied after recreate, so the bomb resumes exactly where it
 * was — not at its spawn defaults.
 *
 * <p>Pre-fix, the restore left zero live bombs (dropped), so every assertion in
 * the second half of this test fails.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestAiz2TransientChildRewind {

    private static final int ZONE_AIZ = 0;
    private static final int ACT_2 = 1;

    @AfterEach
    void cleanup() {
        TestEnvironment.resetAll();
    }

    @Test
    void shipBombSurvivesRewindRestoreWithMidFlightState() {
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

        // 2. Put a live battleship in the object manager so the bomb has a parent
        //    to relink to, then add a bomb child the way the battleship does. Adding
        //    through the real ObjectManager injects ObjectServices.
        int cameraX = fixture.camera().getX();
        int baseSecondaryY = (fixture.camera().getY() + 0x08F0) & 0x0FF0;
        ObjectSpawn shipSpawn = new ObjectSpawn(cameraX, baseSecondaryY, 0, 0, 0, false, 0);
        AizBattleshipInstance ship = new AizBattleshipInstance(shipSpawn, baseSecondaryY);
        objectManager.addDynamicObject(ship);

        // The battleship spawns bombs as:
        //   new AizShipBombInstance(new ObjectSpawn(cameraX + screenX, worldY, ...),
        //                           this, bombScriptX, worldY)
        int bombScriptX = 0x60;
        int worldY = fixture.camera().getY() + 0x40;
        ObjectSpawn bombSpawn = new ObjectSpawn(cameraX + 0x80, worldY, 0, 0, 0, false, 0);
        AizShipBombInstance bomb = new AizShipBombInstance(bombSpawn, ship, bombScriptX, worldY);
        objectManager.addDynamicObject(bomb);

        // 3. Advance the bomb a few frames so it is mid-flight (state has left the
        //    spawn default and portYOffset has advanced past its start value).
        for (int i = 0; i < 12; i++) {
            bomb.update(i, fixture.sprite());
        }
        int capturedState = bomb.stateForTest();
        int capturedPortY = bomb.portYOffsetForTest();
        int capturedSecondaryX = bomb.sourceSecondaryXForTest();
        assertTrue(capturedPortY > 0x0A60,
                "Test setup must advance the bomb past its spawn-default portYOffset; got 0x"
                        + Integer.toHexString(capturedPortY));
        assertEquals(bombScriptX, capturedSecondaryX,
                "Bomb must carry its non-spawn sourceSecondaryX differentiator");
        assertEquals(1, countLiveBombs(objectManager),
                "Test setup must put exactly one live bomb in the object manager");

        // 4. Capture the mid-flight state.
        CompositeSnapshot snap = registry.capture();
        assertNotNull(snap, "capture() must return a non-null CompositeSnapshot");

        // 5. Diverge: remove the bomb (mimicking the drop the re-simulation would
        //    otherwise replay forward).
        objectManager.removeDynamicObject(bomb);
        assertEquals(0, countLiveBombs(objectManager),
                "Diverge step must remove the bomb from the live object manager");

        // 6. Restore. Generic recreate must rebuild the bomb (relinked to the
        //    live ship) and the field capturer must reapply its mid-flight scalars.
        registry.restore(snap);

        List<AizShipBombInstance> restored = liveBombs(objectManager);
        assertEquals(1, restored.size(),
                "Generic recreate must restore exactly one live AizShipBombInstance "
                        + "(pre-fix: zero — the bomb was dropped)");
        AizShipBombInstance restoredBomb = restored.get(0);

        // 6a. Mid-flight scalar state was restored, NOT reset to spawn defaults.
        assertEquals(capturedState, restoredBomb.stateForTest(),
                "Restored bomb must resume its captured drop state, not the spawn default");
        assertEquals(capturedPortY, restoredBomb.portYOffsetForTest(),
                "Restored bomb must resume its captured portYOffset, not the spawn default 0x0A60");
        assertEquals(capturedSecondaryX, restoredBomb.sourceSecondaryXForTest(),
                "Restored bomb must keep its non-spawn sourceSecondaryX differentiator");

        // 6b. The bomb relinked to the live battleship recreated earlier in restore.
        //     The battleship's generic recreate path also restores it, so the live ship is a
        //     fresh instance (not the pre-restore `ship`); the bomb must point at
        //     whichever battleship is live now.
        AizBattleshipInstance liveShip = findLiveShip(objectManager);
        assertNotNull(liveShip, "A live battleship should remain after restore");
        assertSame(liveShip, restoredBomb.sourceShipForTest(),
                "Restored bomb must relink to the live battleship parent");

        // 6c. Spawn identity round-trips.
        assertSame(bombSpawn, restoredBomb.getSpawn(),
                "Recreated bomb should round-trip the captured ObjectSpawn identity");
    }

    private static AizBattleshipInstance findLiveShip(ObjectManager objectManager) {
        for (ObjectInstance o : objectManager.getActiveObjects()) {
            if (o instanceof AizBattleshipInstance ship && !o.isDestroyed()) {
                return ship;
            }
        }
        return null;
    }

    private static List<AizShipBombInstance> liveBombs(ObjectManager objectManager) {
        return objectManager.getActiveObjects().stream()
                .filter(o -> o instanceof AizShipBombInstance && !o.isDestroyed())
                .map(o -> (AizShipBombInstance) o)
                .toList();
    }

    private static int countLiveBombs(ObjectManager objectManager) {
        int count = 0;
        for (ObjectInstance o : objectManager.getActiveObjects()) {
            if (o instanceof AizShipBombInstance && !o.isDestroyed()) {
                count++;
            }
        }
        return count;
    }
}
