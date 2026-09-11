package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tools.Sonic3kObjectProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestIczIceCubeObjectInstance {

    // Clear any gameplay session leaked by a prior test in this fork so the registry
    // resolves the S3KL zone set (not a leaked SKL zone). Parallel-suite flake fix.
    @BeforeEach
    void clearLeakedGameplaySession() {
        com.openggf.game.session.SessionManager.clear();
    }

    @Test
    void registryCreatesIczIceCubeForId0xB6() {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x1200, 0x0700, Sonic3kObjectIds.ICZ_ICE_CUBE, 0, 0, false, 0));

        assertInstanceOf(IczIceCubeObjectInstance.class, instance);
    }

    @Test
    void objectExposesRomSolidObjectFullExtents() {
        IczIceCubeObjectInstance cube = new IczIceCubeObjectInstance(
                new ObjectSpawn(0x1200, 0x0700, Sonic3kObjectIds.ICZ_ICE_CUBE, 0, 0, false, 0));

        SolidObjectParams params = cube.getSolidParams();

        assertEquals(0x23, params.halfWidth());
        assertEquals(0x10, params.airHalfHeight());
        assertEquals(0x10, params.groundHalfHeight());
        assertEquals(0x18, cube.getTopLandingHalfWidth(null, params.halfWidth()));
        assertTrue(cube.usesInclusiveRightEdge());
        assertEquals(0x1200, cube.getX());
        assertEquals(0x0700, cube.getY());
        assertEquals(0x2E, cube.getCollisionFlags());
        assertEquals(1, cube.getPriorityBucket());
    }

    @Test
    void standingNonRollPlayerDoesNotShatterCube() {
        ObjectManager objectManager = mock(ObjectManager.class);
        IczIceCubeObjectInstance cube = new IczIceCubeObjectInstance(
                new ObjectSpawn(0x1200, 0x0700, Sonic3kObjectIds.ICZ_ICE_CUBE, 0, 0, false, 0));
        cube.setServices(services(objectManager));
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getAnimationId()).thenReturn(Sonic3kAnimationIds.WALK.id());

        cube.onSolidContact(player, standingContact(), 12);

        assertFalse(cube.isDestroyed());
        verify(objectManager, never()).addDynamicObjectAfterCurrent(org.mockito.ArgumentMatchers.any());
        verify(player, never()).setYSpeed(org.mockito.ArgumentMatchers.anyShort());
    }

    @Test
    void standingRollPlayerShattersCubeLaunchesPlayerAndSpawnsDebris() {
        ObjectManager objectManager = mock(ObjectManager.class);
        when(objectManager.getPreContactAnimationId()).thenReturn(Sonic3kAnimationIds.ROLL.id());
        IczIceCubeObjectInstance cube = new IczIceCubeObjectInstance(
                new ObjectSpawn(0x1200, 0x0700, Sonic3kObjectIds.ICZ_ICE_CUBE, 0, 1, false, 0));
        cube.setServices(services(objectManager));
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getAnimationId()).thenReturn(Sonic3kAnimationIds.ROLL.id());
        when(player.getCentreY()).thenReturn((short) 0x064E);

        cube.onSolidContact(player, standingContact(), 12);

        assertTrue(cube.isDestroyed());
        verify(player).setRolling(true);
        verify(player).setCentreYPreserveSubpixel((short) 0x064E);
        verify(player).setAnimationId(Sonic3kAnimationIds.ROLL);
        verify(player).setYSpeed((short) -0x300);
        verify(player).setAir(true);
        verify(player).setOnObject(false);
        verify(objectManager).clearRidingObject(player);

        ArgumentCaptor<ObjectInstance> captor = ArgumentCaptor.forClass(ObjectInstance.class);
        verify(objectManager, times(12)).addDynamicObjectAfterCurrent(captor.capture());
        assertTrue(captor.getAllValues().stream()
                .allMatch(IczIceCubeObjectInstance.IceCubeDebris.class::isInstance));

        IczIceCubeObjectInstance.IceCubeDebris first =
                (IczIceCubeObjectInstance.IceCubeDebris) captor.getAllValues().get(0);
        assertEquals(0x1200, first.getX());
        assertEquals(0x06F8, first.getY());
        assertEquals(0x12, first.getMappingFrameForTesting());
    }

    @Test
    void shatterUsesAnimationCapturedBeforeSolidLandingClearsRoll() {
        ObjectManager objectManager = mock(ObjectManager.class);
        when(objectManager.getPreContactAnimationId()).thenReturn(Sonic3kAnimationIds.ROLL.id());
        IczIceCubeObjectInstance cube = new IczIceCubeObjectInstance(
                new ObjectSpawn(0x1200, 0x0700, Sonic3kObjectIds.ICZ_ICE_CUBE, 0, 0, false, 0));
        cube.setServices(services(objectManager));
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getAnimationId()).thenReturn(Sonic3kAnimationIds.WALK.id());
        when(player.getCentreY()).thenReturn((short) 0x064F);

        cube.onSolidContact(player, standingContact(), 12);

        assertTrue(cube.isDestroyed());
        verify(player).setYSpeed((short) -0x300);
        verify(player).setAnimationId(Sonic3kAnimationIds.ROLL);
    }

    @Test
    void debrisSpecsMatchChildObjDat8B480AndIndexedVelocities() {
        List<IczIceCubeObjectInstance.IceCubeDebrisSpec> specs =
                IczIceCubeObjectInstance.debrisSpecsForTesting(0x1200, 0x0700, true);

        assertEquals(12, specs.size());
        assertEquals(new IczIceCubeObjectInstance.IceCubeDebrisSpec(
                0, 0x1200, 0x06F8, 0x100, -0x100), specs.get(0));
        assertEquals(new IczIceCubeObjectInstance.IceCubeDebrisSpec(
                2, 0x1200, 0x0708, -0x100, -0x100), specs.get(1));
        assertEquals(new IczIceCubeObjectInstance.IceCubeDebrisSpec(
                4, 0x11F0, 0x06F8, 0x200, -0x200), specs.get(2));
        assertEquals(new IczIceCubeObjectInstance.IceCubeDebrisSpec(
                22, 0x1200, 0x0700, 0x400, -0x300), specs.get(11));
    }

    @Test
    void renderUsesIczPlatformFrameAndPalette() {
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        TestableIczIceCube cube = new TestableIczIceCube(
                new ObjectSpawn(0x1200, 0x0700, Sonic3kObjectIds.ICZ_ICE_CUBE, 0, 1, false, 0),
                renderer);

        cube.appendRenderCommands(new ArrayList<GLCommand>());

        verify(renderer).drawFrameIndex(3, 0x1200, 0x0700, true, false, 2);
    }

    @Test
    void profileMarksIczIceCubeImplementedForS3klOnly() {
        Sonic3kObjectProfile profile = new Sonic3kObjectProfile();

        assertTrue(profile.getImplementedIds().contains(Sonic3kObjectIds.ICZ_ICE_CUBE));
    }

    private static StubObjectServices services(ObjectManager objectManager) {
        return new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        };
    }

    private static SolidContact standingContact() {
        return new SolidContact(true, false, false, true, false);
    }

    private static final class TestableIczIceCube extends IczIceCubeObjectInstance {
        private final PatternSpriteRenderer renderer;

        private TestableIczIceCube(ObjectSpawn spawn, PatternSpriteRenderer renderer) {
            super(spawn);
            this.renderer = renderer;
        }

        @Override
        protected PatternSpriteRenderer getRenderer(String artKey) {
            assertEquals(Sonic3kObjectArtKeys.ICZ_PLATFORMS, artKey);
            return renderer;
        }
    }
}
