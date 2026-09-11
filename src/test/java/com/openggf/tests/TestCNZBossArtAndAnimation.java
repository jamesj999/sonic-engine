package com.openggf.tests;

import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.bosses.CNZBossElectricBall;
import com.openggf.game.sonic2.objects.bosses.Sonic2CNZBossInstance;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.render.PatternSpriteRenderer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for Obj51's unusual mapping table.
 */
public class TestCNZBossArtAndAnimation {

    @Test
    public void bossRenderUsesRomMappingFrameIdsWithNullFrameZeroPreserved() {
        LevelManager levelManager = mock(LevelManager.class);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);
        when(renderManager.getRenderer(Sonic2ObjectArtKeys.CNZ_BOSS)).thenReturn(renderer);
        when(renderer.isReady()).thenReturn(true);

        TestObjectServices services = new TestObjectServices().withLevelManager(levelManager);
        Sonic2CNZBossInstance boss = new Sonic2CNZBossInstance(
                new ObjectSpawn(0, 0, Sonic2ObjectIds.CNZ_BOSS, 0, 0, false, 0));
        boss.setServices(services);

        boss.appendRenderCommands(new ArrayList<>());

        var order = inOrder(renderer);
        order.verify(renderer).drawFrameIndex(5, 0x2A46, 0x654, false, false);
        order.verify(renderer).drawFrameIndex(1, 0x2A46, 0x654, false, false);
        order.verify(renderer).drawFrameIndex(6, 0x2A46, 0x654, false, false);
        order.verify(renderer).drawFrameIndex(2, 0x2A46, 0x654, false, false);
    }

    @Test
    public void electricBallUsesObj51ProjectileMappingFrames() {
        LevelManager levelManager = mock(LevelManager.class);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);
        when(renderManager.getRenderer(Sonic2ObjectArtKeys.CNZ_BOSS)).thenReturn(renderer);
        when(renderer.isReady()).thenReturn(true);

        TestObjectServices services = new TestObjectServices().withLevelManager(levelManager);
        Sonic2CNZBossInstance boss = new Sonic2CNZBossInstance(
                new ObjectSpawn(0, 0, Sonic2ObjectIds.CNZ_BOSS, 0, 0, false, 0));
        boss.setServices(services);
        CNZBossElectricBall ball = new CNZBossElectricBall(
                new ObjectSpawn(0x2A46, 0x654, Sonic2ObjectIds.CNZ_BOSS, 4, 0, false, 0), boss);
        ball.setServices(services);

        assertEquals(0x2A46, ball.getX(),
                "Obj51's allocation snapshot owns the attached ball's native x_pos");
        assertEquals(0x654, ball.getY(),
                "Obj51 init owns the attached ball's native y_pos");
        ball.appendRenderCommands(new ArrayList<>());

        inOrder(renderer).verify(renderer).drawFrameIndex(0x12, 0x2A46, 0x654, false, false);
    }

}
