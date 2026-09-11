package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.debug.DebugOverlayManager;
import com.openggf.game.DamageCause;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PerObjectRewindSnapshot;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.SpawnCoordinateRewindRecreatable;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.TouchResponseTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestHczHarmfulExplosionObjectInstance {

    @Test
    void collisionEndsAtMappingFrameThreeAndAnimationDeletesAtFrameFive() {
        HczHarmfulExplosionObjectInstance explosion =
                new HczHarmfulExplosionObjectInstance(0x2345, 0x0678);

        assertEquals(0x8B, explosion.getCollisionFlags());
        assertFalse(explosion.requiresRenderFlagForTouch());

        explosion.update(0, null); // HCZEndBossExplosion_Init setup dispatch
        for (int frame = 1; frame <= 24; frame++) {
            explosion.update(frame, null);
        }
        assertEquals(0, explosion.getCollisionFlags(),
                "HCZEndBossExplosion_Main adds collision only while mapping_frame < 3");
        assertFalse(explosion.isDestroyed());

        for (int frame = 25; frame <= 40; frame++) {
            explosion.update(frame, null);
        }
        assertTrue(explosion.isDestroyed());
    }

    @Test
    void genericRewindRestoresAnimationStateAndRecreatesAtCapturedCoordinates() {
        HczHarmfulExplosionObjectInstance explosion =
                new HczHarmfulExplosionObjectInstance(0x2345, 0x0678);
        assertInstanceOf(SpawnCoordinateRewindRecreatable.class, explosion);

        explosion.update(0, null);
        for (int frame = 1; frame <= 8; frame++) {
            explosion.update(frame, null);
        }
        PerObjectRewindSnapshot snapshot = explosion.captureRewindState();

        for (int frame = 9; frame <= 24; frame++) {
            explosion.update(frame, null);
        }
        assertEquals(0, explosion.getCollisionFlags());
        explosion.restoreRewindState(snapshot);
        assertEquals(0x8B, explosion.getCollisionFlags());

        ObjectSpawn capturedSpawn = new ObjectSpawn(0x3456, 0x0789, 0, 0, 0, false, 0);
        AbstractObjectInstance recreated = explosion.recreateForRewind(
                new RewindRecreateContext(capturedSpawn, snapshot, null));
        assertInstanceOf(HczHarmfulExplosionObjectInstance.class, recreated);
        assertEquals(0x3456, recreated.getX());
        assertEquals(0x0789, recreated.getY());
    }

    @Test
    void activeExplosionDamagesAnOverlappingVulnerablePlayerThroughTouchResponse() {
        TouchResponseTable table = mock(TouchResponseTable.class);
        // Touch_Sizes index $0B is 8x8; ObjData's $0C/$0C are render dimensions.
        when(table.getWidthRadius(0x0B)).thenReturn(0x08);
        when(table.getHeightRadius(0x0B)).thenReturn(0x08);
        DebugOverlayManager debugOverlay = mock(DebugOverlayManager.class);
        when(debugOverlay.isEnabled(any())).thenReturn(false);
        Camera camera = mock(Camera.class);
        when(camera.getX()).thenReturn((short) 0);
        when(camera.getY()).thenReturn((short) 0);
        when(camera.getWidth()).thenReturn((short) 320);
        when(camera.getHeight()).thenReturn((short) 224);
        when(camera.isVerticalWrapEnabled()).thenReturn(false);
        TestObjectServices services = new TestObjectServices()
                .withCamera(camera)
                .withDebugOverlay(debugOverlay);
        ObjectManager manager = new ObjectManager(List.of(), inertRegistry(), 0, null,
                table, null, camera, services);
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getCentreX()).thenReturn((short) 0x00A0);
        when(player.getCentreY()).thenReturn((short) 0x0064);
        when(player.getYRadius()).thenReturn((short) 0x13);
        when(player.getRingCount()).thenReturn(0);
        when(player.getInvulnerable()).thenReturn(false);
        when(player.getDead()).thenReturn(false);
        HczHarmfulExplosionObjectInstance explosion =
                new HczHarmfulExplosionObjectInstance(0x00A0, 0x0064);
        manager.addDynamicObject(explosion);

        manager.update(0, player, List.of(), 1);

        verify(player).applyHurtOrDeath(0x00A0, DamageCause.NORMAL, false);
    }

    private static ObjectRegistry inertRegistry() {
        return new ObjectRegistry() {
            @Override
            public ObjectInstance create(ObjectSpawn spawn) {
                return null;
            }

            @Override
            public void reportCoverage(List<ObjectSpawn> spawns) {
            }

            @Override
            public String getPrimaryName(int objectId) {
                return "Test";
            }
        };
    }
}
