package com.openggf.tests;

import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.Sonic3kPlcArtRegistry;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.IczBreakableWallObjectInstance;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.tools.Sonic3kObjectProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestS3kIczBreakableWallObject {

    // Clear any gameplay session leaked by a prior test in this fork so the registry
    // resolves the S3KL zone set (not a leaked SKL zone). Parallel-suite flake fix.
    @BeforeEach
    void clearLeakedGameplaySession() {
        com.openggf.game.session.SessionManager.clear();
    }

    @Test
    void registryCreatesIczBreakableWallInstance() {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x3200, 0x0700, Sonic3kObjectIds.ICZ_BREAKABLE_WALL, 0, 0, false, 0));

        assertInstanceOf(IczBreakableWallObjectInstance.class, instance);
    }

    @Test
    void objectUsesRomSolidDimensionsAndMappingFrame() {
        IczBreakableWallObjectInstance wall = new IczBreakableWallObjectInstance(
                new ObjectSpawn(0x3200, 0x0700, Sonic3kObjectIds.ICZ_BREAKABLE_WALL, 0, 0, false, 0));

        SolidObjectParams params = wall.getSolidParams();
        assertEquals(0x2B, params.halfWidth());
        assertEquals(0x40, params.airHalfHeight());
        assertEquals(0x70, params.groundHalfHeight());
        assertEquals(6, wall.getMappingFrameForTesting());
        assertEquals(Sonic3kObjectArtKeys.ICZ_WALL_AND_COLUMN, wall.getArtKeyForTesting());
    }

    @Test
    void debrisSpecsMatchChildObjDatAndVelocityIndex() {
        assertEquals("""
                0:31EC,06D0,FF00,FF00
                2:3208,06CC,0100,FF00
                4:31F4,06EC,FE00,FE00
                6:3210,06E0,0200,FE00
                8:320A,06FC,FD00,FE00
                10:31EC,070C,0300,FE00
                12:3208,0718,FE00,FE00
                14:31F0,0728,0000,FE00
                16:320C,0734,FC00,FD00
                """.stripIndent(), formatSpecs(IczBreakableWallObjectInstance.debrisSpecsForTesting(
                0x3200, 0x0700, false)));
    }

    @Test
    void artRegistryReusesIczWallAndColumnLevelArt() {
        Sonic3kPlcArtRegistry.LevelArtEntry entry = Sonic3kPlcArtRegistry.getPlan(0x05, 0).levelArt().stream()
                .filter(candidate -> Sonic3kObjectArtKeys.ICZ_WALL_AND_COLUMN.equals(candidate.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing ICZ wall/column art entry"));

        assertEquals(Sonic3kConstants.MAP_ICZ_WALL_AND_COLUMN_ADDR, entry.mappingAddr());
        assertEquals(1, entry.artTileBase());
        assertEquals(2, entry.palette());
    }

    @Test
    void breakDebrisUsesIczPlatformsIntroLevelArt() {
        // ObjDat3_8A41E: Map_ICZPlatforms + make_art_tile(ArtTile_ICZIntroSprites,2,1).
        Sonic3kPlcArtRegistry.LevelArtEntry entry = Sonic3kPlcArtRegistry.getPlan(0x05, 0).levelArt().stream()
                .filter(candidate -> Sonic3kObjectArtKeys.ICZ_PLATFORMS_INTRO.equals(candidate.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing ICZ intro-sprites break-debris art entry"));

        assertEquals(Sonic3kConstants.MAP_ICZ_PLATFORMS_ADDR, entry.mappingAddr());
        assertEquals(Sonic3kConstants.ARTTILE_ICZ_INTRO_SPRITES, entry.artTileBase());
        assertEquals(2, entry.palette());
    }

    @Test
    void pathFollowPlatformInsideRomTriggerBoxBreaksWall() {
        ObjectManager objectManager = mock(ObjectManager.class);
        ObjectSpawn wallSpawn = new ObjectSpawn(
                0x3200, 0x0700, Sonic3kObjectIds.ICZ_BREAKABLE_WALL, 0, 0, false, 0);
        IczBreakableWallObjectInstance wall = new IczBreakableWallObjectInstance(wallSpawn);
        StubObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        }.withPlayerQuery(new ObjectPlayerQuery(() -> null, List::of));
        wall.setServices(services);
        when(objectManager.getActiveObjects()).thenReturn(List.of(
                new IczPathFollowPlatformTestInstance(0x3200, 0x06F0)));

        wall.update(1, null);

        assertTrue(wall.isDestroyed(), "Wall should delete after spawning debris");
        verify(objectManager, times(9)).addDynamicObjectAfterCurrent(
                org.mockito.ArgumentMatchers.any(AbstractObjectInstance.class));
        verify(objectManager).markRemembered(wallSpawn);
    }

    @Test
    void profileMarksIczBreakableWallImplementedOnlyForS3kl() {
        Sonic3kObjectProfile profile = new Sonic3kObjectProfile();

        assertTrue(profile.getImplementedIds().contains(Sonic3kObjectIds.ICZ_BREAKABLE_WALL));
    }

    private static String formatSpecs(
            java.util.List<IczBreakableWallObjectInstance.IczBreakableWallDebrisSpec> specs) {
        StringBuilder builder = new StringBuilder();
        for (IczBreakableWallObjectInstance.IczBreakableWallDebrisSpec spec : specs) {
            builder.append(spec.subtype())
                    .append(':')
                    .append(hex16(spec.x()))
                    .append(',')
                    .append(hex16(spec.y()))
                    .append(',')
                    .append(hex16(spec.xVel()))
                    .append(',')
                    .append(hex16(spec.yVel()))
                    .append('\n');
        }
        return builder.toString();
    }

    private static String hex16(int value) {
        return "%04X".formatted(value & 0xFFFF);
    }

    private static final class IczPathFollowPlatformTestInstance extends AbstractObjectInstance {
        private IczPathFollowPlatformTestInstance(int x, int y) {
            super(new ObjectSpawn(x, y, 0xB0, 0, 0, false, 0), "ICZPathFollowPlatform");
        }

        @Override
        public void update(int vIntRunCount, com.openggf.game.PlayableEntity player) {
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }
    }
}
