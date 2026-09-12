package com.openggf.game.sonic3k.objects;

import com.openggf.game.GameServices;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@RequiresRom(SonicGame.SONIC_3K)
class TestMhzMinibossDefeat {
    @Test
    void freshMhz1BossRegistersItsExplosionRenderer() throws Exception {
        var fixture = HeadlessTestFixture.builder()
                .withSharedLevel(SharedLevel.load(SonicGame.SONIC_3K, 7, 0)).build();
        var level = GameServices.level();
        var manager = level.getObjectManager();
        manager.createDynamicObject(() -> new MhzMinibossInstance(
                new ObjectSpawn(0, 0, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0)));
        manager.update(fixture.camera().getX() & 0xFFFF, fixture.sprite(), List.of(), 0, false);
        assertNotNull(level.getObjectRenderManager().getBossExplosionRenderer(),
                "loc_75220 loads PLC_MHZMiniboss_Explosion before the fight");
        assertTrue(level.getObjectRenderManager().getBossExplosionRenderer().isReady(),
                "late boss initialization must upload explosion patterns as well as register the sheet");
    }
    @Test
    void explosionTrainOutlivesBodyAndRewindsIndependently() throws Exception {
        var fixture = HeadlessTestFixture.builder()
                .withSharedLevel(SharedLevel.load(SonicGame.SONIC_3K, 7, 0)).build();
        var manager = GameServices.level().getObjectManager();
        var boss = manager.createDynamicObject(() -> new MhzMinibossInstance(
                new ObjectSpawn(0, 0, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0)));
        int cameraX = fixture.camera().getX() & 0xFFFF;
        manager.update(cameraX, fixture.sprite(), List.of(), 0, false);
        boss.getState().y = (fixture.camera().getY() & 0xFFFF) + 80;
        boss.getState().yFixed = boss.getState().y << 16;
        boss.getState().hitCount = 1;
        boss.onPlayerAttack(null, null);
        Set<ObjectInstance> explosions = new HashSet<>();
        for (int frame = 0; frame < 64; frame++) {
            manager.update(cameraX, fixture.sprite(), List.of(), frame + 1, false);
            manager.getActiveObjects().stream().filter(S3kBossExplosionChild.class::isInstance)
                    .forEach(explosions::add);
        }
        assertTrue(boss.isDestroyed(), "the body hands off after its native 64-update wait");
        assertEquals(22, explosions.size(), "first burst is immediate, then every three updates");
        assertTrue(manager.getActiveObjects().stream()
                .anyMatch(MhzMinibossExplosionController.class::isInstance),
                "the explosion controller must survive the signpost handoff");

        var registry = new RewindRegistry();
        registry.register(manager.rewindSnapshottable());
        var snapshot = registry.capture();
        for (int frame = 64; frame < 95; frame++) {
            manager.update(cameraX, fixture.sprite(), List.of(), frame + 1, false);
            manager.getActiveObjects().stream().filter(S3kBossExplosionChild.class::isInstance)
                    .forEach(explosions::add);
        }
        assertEquals(31, explosions.size(), "CreateBossExp10 must finish all 31 explosion attempts");
        assertFalse(manager.getActiveObjects().stream()
                .anyMatch(MhzMinibossExplosionController.class::isInstance));
        registry.restore(snapshot);
        assertTrue(manager.getActiveObjects().stream()
                .anyMatch(MhzMinibossExplosionController.class::isInstance),
                "rewind recreates the controller after the boss has already retired");
        for (int frame = 64; frame < 95; frame++) {
            manager.update(cameraX, fixture.sprite(), List.of(), frame + 1, false);
        }
        assertFalse(manager.getActiveObjects().stream()
                .anyMatch(MhzMinibossExplosionController.class::isInstance));
    }

}
