package com.openggf.tests;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.HtzGroundFireObjectInstance;
import com.openggf.game.sonic2.objects.bosses.HTZBossFlamethrower;
import com.openggf.game.sonic2.objects.bosses.HTZBossLavaBall;
import com.openggf.game.sonic2.objects.bosses.HTZBossSmokeParticle;
import com.openggf.game.sonic2.objects.bosses.LavaBubbleObjectInstance;
import com.openggf.game.sonic2.objects.bosses.Sonic2HTZBossInstance;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

/**
 * Regression tests for HTZ boss child objects and fire/smoke rendering setup.
 */
public class TestHTZBossChildObjects {

    private LevelManager mockLevelManager;

    @BeforeEach
    public void setUp() {
        mockLevelManager = mock(LevelManager.class);
    }

    @AfterEach
    public void tearDown() {
    }

    @Test
    public void lavaBubbleUsesSolRendererAndFireFrames() throws Exception {
        ObjectServices services = services();

        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);

        when(mockLevelManager.getObjectRenderManager()).thenReturn(renderManager);
        when(renderManager.getRenderer(Sonic2ObjectArtKeys.SOL)).thenReturn(renderer);
        when(renderer.isReady()).thenReturn(true);

        LavaBubbleObjectInstance bubble = new LavaBubbleObjectInstance(100, 200);
        bubble.setServices(services);
        assertEquals(Sonic2ObjectIds.LAVA_BUBBLE, bubble.getSpawn().objectId());

        bubble.appendRenderCommands(new ArrayList<>());
        verify(renderManager).getRenderer(Sonic2ObjectArtKeys.SOL);
        verify(renderManager, never()).getRenderer(Sonic2ObjectArtKeys.HTZ_BOSS);
        verify(renderer).drawFrameIndex(eq(3), eq(100), eq(200), eq(false), eq(false));

        for (int i = 0; i < 8; i++) {
            bubble.update(i, null);
        }
        bubble.appendRenderCommands(new ArrayList<>());
        verify(renderer).drawFrameIndex(eq(4), eq(100), eq(200), eq(false), eq(false));
    }

    @Test
    public void htzSmokeSpawnSubtypeAndPriorityMatchRom() {
        HTZBossSmokeParticle smoke = new HTZBossSmokeParticle(10, 20);
        assertEquals(0x08, smoke.getSpawn().subtype());
        assertEquals(1, smoke.getPriorityBucket());
    }

    @Test
    public void flamethrowerUsesHtzChildTileBaseOffset() throws Exception {
        ObjectServices services = services();

        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);

        when(mockLevelManager.getObjectRenderManager()).thenReturn(renderManager);
        when(renderManager.getRenderer(Sonic2ObjectArtKeys.HTZ_BOSS)).thenReturn(renderer);
        when(renderer.isReady()).thenReturn(true);

        Sonic2HTZBossInstance parent = new Sonic2HTZBossInstance(
                new ObjectSpawn(0x3040, 0x0580, Sonic2ObjectIds.HTZ_BOSS, 0, 0, false, 0));
        parent.setServices(services);
        HTZBossFlamethrower flamethrower = new HTZBossFlamethrower(parent, 0x3040, 0x0564, false);
        flamethrower.setServices(services);

        flamethrower.appendRenderCommands(new ArrayList<>());
        verify(renderer).drawPatternIndex(eq(0xC1), eq(0x2FCC), eq(0x0560), eq(0));
    }

    @Test
    public void lavaBallUsesHtzChildTileBaseOffsetBeforeLanding() throws Exception {
        ObjectServices services = services();

        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);

        when(mockLevelManager.getObjectRenderManager()).thenReturn(renderManager);
        when(renderManager.getRenderer(Sonic2ObjectArtKeys.HTZ_BOSS)).thenReturn(renderer);
        when(renderer.isReady()).thenReturn(true);

        Sonic2HTZBossInstance parent = new Sonic2HTZBossInstance(
                new ObjectSpawn(0x3040, 0x0580, Sonic2ObjectIds.HTZ_BOSS, 0, 0, false, 0));
        parent.setServices(services);
        HTZBossLavaBall lavaBall = new HTZBossLavaBall(parent, 0x3040, 0x0580, true, false);
        lavaBall.setServices(services);

        lavaBall.appendRenderCommands(new ArrayList<>());
        verify(renderer).drawPatternIndex(eq(0xC3), eq(0x3038), eq(0x0578), eq(0));
        verify(renderer).drawPatternIndex(eq(0xC5), eq(0x3040), eq(0x0578), eq(0));
        verify(renderer).drawPatternIndex(eq(0xC4), eq(0x3038), eq(0x0580), eq(0));
        verify(renderer).drawPatternIndex(eq(0xC6), eq(0x3040), eq(0x0580), eq(0));
    }

    @Test
    public void lavaBallInitialPairSpawnerMatchesRomRoutineZeroCadence() {
        ObjectManager objectManager = mock(ObjectManager.class);
        when(mockLevelManager.getObjectManager()).thenReturn(objectManager);
        ObjectServices services = services();

        Sonic2HTZBossInstance parent = new Sonic2HTZBossInstance(
                new ObjectSpawn(0x2F40, 0x0538, Sonic2ObjectIds.HTZ_BOSS, 0, 0, false, 0));
        parent.setServices(services);
        parent.getState().lastUpdatedVIntRunCount = 7;

        HTZBossLavaBall firstBall =
                HTZBossLavaBall.createInitialPairSpawner(parent, 0x2F40, 0x0538);
        firstBall.setServices(services);
        assertEquals(0, firstBall.getCollisionFlags());

        firstBall.update(7, null);

        assertEquals(0x8B, firstBall.getCollisionFlags());
        assertEquals(0x2F40, firstBall.getX());
        assertEquals(0x0538, firstBall.getY());

        ArgumentCaptor<ObjectInstance> childCaptor = ArgumentCaptor.forClass(ObjectInstance.class);
        verify(objectManager).addDynamicObject(childCaptor.capture());
        assertTrue(childCaptor.getValue() instanceof HTZBossLavaBall);
        HTZBossLavaBall secondBall = (HTZBossLavaBall) childCaptor.getValue();
        assertEquals(0x8B, secondBall.getCollisionFlags());

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(anyInt(), anyInt(), anyInt()))
                    .thenReturn(TerrainCheckResult.noCollision());
            secondBall.update(7, null);
        }

        assertEquals(0x2F41, secondBall.getX());
        assertEquals(0x0532, secondBall.getY());
    }

    @Test
    public void lavaBallTransformsToGroundFireWhenLandingOnSurface() throws Exception {
        ObjectServices services = services();

        ObjectManager objectManager = mock(ObjectManager.class);
        when(mockLevelManager.getObjectManager()).thenReturn(objectManager);

        Sonic2HTZBossInstance parent = new Sonic2HTZBossInstance(
                new ObjectSpawn(0x3040, 0x0580, Sonic2ObjectIds.HTZ_BOSS, 0, 0, false, 0));
        parent.setServices(services);
        HTZBossLavaBall lavaBall = new HTZBossLavaBall(parent, 0x3040, 0x0580, true, false);
        lavaBall.setServices(services);
        parent.getState().lastUpdatedVIntRunCount = 1;

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(anyInt(), anyInt(), anyInt()))
                    .thenReturn(new TerrainCheckResult(-2, (byte) 0, 0));
            lavaBall.update(1, null);
        }

        assertTrue(lavaBall.isDestroyed());
        // ROM: lava ball transforms to Obj20 routine $A (fire trail spawner)
        verify(objectManager).addDynamicObject(argThat(obj -> obj instanceof HtzGroundFireObjectInstance));
    }

    @Test
    public void htzBossHazardsAreTouchResponseProviders() {
        ObjectServices services = services();
        Sonic2HTZBossInstance parent = new Sonic2HTZBossInstance(
                new ObjectSpawn(0x3040, 0x0580, Sonic2ObjectIds.HTZ_BOSS, 0, 0, false, 0));
        parent.setServices(services);

        HTZBossFlamethrower flamethrower = new HTZBossFlamethrower(parent, 0x3040, 0x0564, false);
        flamethrower.setServices(services);
        HTZBossLavaBall lavaBall = new HTZBossLavaBall(parent, 0x3040, 0x0580, true, false);
        lavaBall.setServices(services);
        LavaBubbleObjectInstance bubble = new LavaBubbleObjectInstance(0x3040, 0x0580);
        bubble.setServices(services);

        assertTrue(flamethrower instanceof TouchResponseProvider);
        assertTrue(lavaBall instanceof TouchResponseProvider);
        assertTrue(bubble instanceof TouchResponseProvider);

        assertEquals(0, flamethrower.getCollisionProperty());
        assertEquals(0, lavaBall.getCollisionProperty());
        assertEquals(0, bubble.getCollisionProperty());
    }

    private TestObjectServices services() {
        return new TestObjectServices().withLevelManager(mockLevelManager);
    }
}

