package com.openggf.game.sonic3k.objects;

import com.openggf.tests.TestEnvironment;

import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestMgzMinibossSpikeArt {

    private SharedLevel shared;

    @AfterEach
    void tearDown() {
        if (shared != null) {
            shared.dispose();
        }
    }

    @Test
    void mgzSpireRendererLoadsWithFramesInRealLevel() throws Exception {
        shared = SharedLevel.load(SonicGame.SONIC_3K, 2, 0);

        ObjectRenderManager renderManager = GameServices.level().getObjectRenderManager();
        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.MGZ_MINIBOSS_SPIRE);
        ObjectSpriteSheet sheet = renderManager.getSheet(Sonic3kObjectArtKeys.MGZ_MINIBOSS_SPIRE);

        assertTrue(renderManager.isReady(), "Object render manager should be ready in MGZ1");
        assertTrue(renderer != null && renderer.isReady(), "MGZ falling-spike renderer should be cached and ready");
        assertTrue(sheet != null && sheet.getFrameCount() > 0, "MGZ falling-spike sheet should expose at least one frame");
        assertTrue(!sheet.getFrame(0).pieces().isEmpty(), "MGZ falling-spike frame 0 should not be empty");
        assertTrue(sheet.getPatterns().length > 0, "MGZ falling-spike sheet should contain pattern data");
    }

    @Test
    void mgzBossInitMakesExplosionRendererAvailableInRealLevel() throws Exception {
        shared = SharedLevel.load(SonicGame.SONIC_3K, 2, 0);

        MgzMinibossInstance boss = new MgzMinibossInstance(
                new com.openggf.level.objects.ObjectSpawn(0x2D80, 0x0100, Sonic3kObjectIds.MGZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(com.openggf.tests.TestEnvironment.objectServices());
        boss.update(0, (com.openggf.game.PlayableEntity) GameServices.camera().getFocusedSprite());

        ObjectRenderManager renderManager = GameServices.level().getObjectRenderManager();
        PatternSpriteRenderer renderer = renderManager.getBossExplosionRenderer();

        assertTrue(renderer != null && renderer.isReady(),
                "MGZ boss init should ensure the shared boss explosion renderer is available");
    }
}
