package com.openggf.game.sonic2.objects.badniks;

import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ExplosionObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.Direction;
import com.openggf.sprites.playable.Sonic;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestAsteronBadnikInstance {

    @Test
    void rendersWithRomHighPriorityBit() {
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderer.isReady()).thenReturn(true);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic2ObjectArtKeys.ASTERON)).thenReturn(renderer);

        AsteronBadnikInstance asteron = new AsteronBadnikInstance(
                new ObjectSpawn(0x0200, 0x0180, Sonic2ObjectIds.ASTERON, 0x00, 0, false, 0));
        asteron.setServices(new StubObjectServices() {
            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }
        });

        asteron.appendRenderCommands(new ArrayList<GLCommand>());

        verify(renderer).drawFrameIndexForcedPriority(0, 0x0200, 0x0180, false, false, -1, true);
    }

    @Test
    void spawnedSpikesRenderWithRomHighPriorityBit() {
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderer.isReady()).thenReturn(true);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic2ObjectArtKeys.ASTERON)).thenReturn(renderer);

        BadnikProjectileInstance spike = new BadnikProjectileInstance(
                new ObjectSpawn(0x0200, 0x0180, Sonic2ObjectIds.ASTERON, 0x00, 0, false, 0),
                BadnikProjectileInstance.ProjectileType.ASTERON_SPIKE,
                0x0220, 0x0190, 0x0300, 0x0000, false, true, 0, 3);
        spike.setServices(new StubObjectServices() {
            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }
        });

        spike.appendRenderCommands(new ArrayList<GLCommand>());

        verify(renderer).drawFrameIndexForcedPriority(3, 0x0220, 0x0190, true, false, -1, true);
    }

    @Test
    void explosionReusesAsteronSlotAndSpikesAreObj98Children() throws Exception {
        ObjectManager objectManager = mock(ObjectManager.class);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        ObjectSpawn spawn = new ObjectSpawn(0x0710, 0x01A0, Sonic2ObjectIds.ASTERON, 0x00, 0, false, 0);
        AsteronBadnikInstance asteron = new AsteronBadnikInstance(spawn);
        asteron.setSlotIndex(25);
        asteron.setServices(new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }
        });

        Method explode = AsteronBadnikInstance.class.getDeclaredMethod("explode");
        explode.setAccessible(true);
        explode.invoke(asteron);

        verify(objectManager).addDynamicObjectAtSlot(
                org.mockito.ArgumentMatchers.argThat(ExplosionObjectInstance.class::isInstance),
                eq(25));

        ArgumentCaptor<BadnikProjectileInstance> projectiles =
                ArgumentCaptor.forClass(BadnikProjectileInstance.class);
        verify(objectManager, times(5)).addDynamicObjectAfterCurrent(projectiles.capture());
        List<BadnikProjectileInstance> spawned = projectiles.getAllValues();
        assertEquals(5, spawned.size());
        for (BadnikProjectileInstance projectile : spawned) {
            assertEquals(Sonic2ObjectIds.PROJECTILE, projectile.getSpawn().objectId() & 0xFF,
                    "Obj_CreateProjectiles writes Obj98 into each spike child slot");
        }
        assertTrue(asteron.isDestroyed());
        assertEquals(-1, asteron.getSlotIndex(),
                "The original Asteron must detach its slot so the Obj27 replacement keeps it");
        verify(objectManager).removeFromActiveSpawns(spawn);
        verify(objectManager, times(5)).addDynamicObjectAfterCurrent(any(BadnikProjectileInstance.class));
    }

    @Test
    void activationUsesMainCharacterCoordinatesWhenCurrentPlayableIsRidingCarrier() throws Exception {
        ObjectSpawn spawn = new ObjectSpawn(0x0710, 0x01A0, Sonic2ObjectIds.ASTERON, 0x00, 0, false, 0);
        AsteronBadnikInstance asteron = new AsteronBadnikInstance(spawn);
        Sonic sonic = sonicAtRomPos(0x06C0, 0x0180);
        Sonic activeCarrierRider = sonicAtRomPos(0x0400, 0x0100);
        asteron.setServices(new StubObjectServices()
                .withPlayerQuery(new ObjectPlayerQuery(() -> sonic, List::of)));

        asteron.update(1, activeCarrierRider);

        assertEquals("ARMED", asteronStateName(asteron),
                "ObjA4 calls Obj_GetOrientationToPlayer, so carrier/riding update context must not replace Sonic");
    }

    @Test
    void activationUsesHorizontallyClosestNativePlayerLikeObjGetOrientationToPlayer() throws Exception {
        ObjectSpawn spawn = new ObjectSpawn(0x0710, 0x01A0, Sonic2ObjectIds.ASTERON, 0x00, 0, false, 0);
        AsteronBadnikInstance asteron = new AsteronBadnikInstance(spawn);
        Sonic sonic = sonicAtRomPos(0x0600, 0x01A0);
        Sonic tails = sonicAtRomPos(0x06C0, 0x0180);
        Sonic activeCarrierRider = sonicAtRomPos(0x0400, 0x0100);
        asteron.setServices(new StubObjectServices()
                .withPlayerQuery(new ObjectPlayerQuery(() -> sonic, () -> List.of(tails))));

        asteron.update(1, activeCarrierRider);

        assertEquals("ARMED", asteronStateName(asteron),
                "Obj_GetOrientationToPlayer compares MainCharacter and Sidekick by horizontal distance");
    }

    @Test
    void activationDoesNotDependOnPlayerFacingDirection() throws Exception {
        for (Direction direction : List.of(Direction.LEFT, Direction.RIGHT)) {
            ObjectSpawn spawn = new ObjectSpawn(0x1120, 0x0710, Sonic2ObjectIds.ASTERON, 0x2E, 0, false, 0);
            AsteronBadnikInstance asteron = new AsteronBadnikInstance(spawn);
            Sonic sonicOnNut = sonicAtRomPos(0x10C1, 0x072D);
            sonicOnNut.setDirection(direction);
            asteron.setServices(new StubObjectServices()
                    .withPlayerQuery(new ObjectPlayerQuery(() -> sonicOnNut, List::of)));

            asteron.update(1, sonicOnNut);

            assertEquals("ARMED", asteronStateName(asteron),
                    "Obj_GetOrientationToPlayer reads position only; facing must not affect Asteron activation");
        }
    }

    @Test
    void idleRangeExcludesPositiveSixtyPixelBoundaryLikeRomBhs() throws Exception {
        ObjectSpawn spawn = new ObjectSpawn(0x1120, 0x0710, Sonic2ObjectIds.ASTERON, 0x2E, 0, false, 0);
        AsteronBadnikInstance asteron = new AsteronBadnikInstance(spawn);
        Sonic sonicOnNut = sonicAtRomPos(0x10C0, 0x072D);
        asteron.setServices(new StubObjectServices()
                .withPlayerQuery(new ObjectPlayerQuery(() -> sonicOnNut, List::of)));

        asteron.update(1, sonicOnNut);

        assertEquals("IDLE", asteronStateName(asteron),
                "ROM loc_389B6 adds $60 then bhs-outs at $C0, so +$60 is outside");
    }

    @Test
    void idleRangeIncludesNegativeSixtyPixelBoundaryLikeRomUnsignedWindow() throws Exception {
        ObjectSpawn spawn = new ObjectSpawn(0x1060, 0x0710, Sonic2ObjectIds.ASTERON, 0x2E, 0, false, 0);
        AsteronBadnikInstance asteron = new AsteronBadnikInstance(spawn);
        Sonic sonicOnNut = sonicAtRomPos(0x10C0, 0x072D);
        asteron.setServices(new StubObjectServices()
                .withPlayerQuery(new ObjectPlayerQuery(() -> sonicOnNut, List::of)));

        asteron.update(1, sonicOnNut);

        assertEquals("ARMED", asteronStateName(asteron),
                "ROM loc_389B6 accepts -$60 because adding $60 yields zero");
    }

    private static String asteronStateName(AsteronBadnikInstance asteron) throws Exception {
        Field state = AsteronBadnikInstance.class.getDeclaredField("state");
        state.setAccessible(true);
        return ((Enum<?>) state.get(asteron)).name();
    }

    private static Sonic sonicAtRomPos(int x, int y) {
        Sonic sonic = new Sonic("sonic", (short) 0, (short) 0);
        sonic.setCentreX((short) x);
        sonic.setCentreY((short) y);
        return sonic;
    }
}
