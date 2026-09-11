package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.Sonic3kPlcArtRegistry;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class TestIczSegmentColumnObjectInstance {

    @BeforeEach
    public void resetSharedTestState() {
        // Clear any gameplay session leaked by a prior test in this fork. The registry
        // derives the zone set from GameServices.levelOrNull(); a leaked SKL-zone level
        // makes the ICZ id resolve to a PlaceholderObjectInstance. Clearing it restores
        // currentRomZoneId()==-1 -> S3KL default. (Parallel-suite flake fix.)
        com.openggf.game.session.SessionManager.clear();
        com.openggf.level.objects.AbstractObjectInstance.resetCameraBoundsForTests();
    }

    @Test
    public void registryCreatesIczSegmentColumnForId0xB3InS3klZoneSet() {
        Sonic3kObjectRegistry registry = new Sonic3kObjectRegistry();
        ObjectSpawn spawn = new ObjectSpawn(0x1200, 0x0580, Sonic3kObjectIds.ICZ_SEGMENT_COLUMN, 0, 0, false, 0);

        ObjectInstance instance = registry.create(spawn);

        assertTrue(instance instanceof IczSegmentColumnObjectInstance);
    }

    @Test
    public void primaryNameFor0xB3MatchesS3klPointerTable() {
        Sonic3kObjectRegistry registry = new Sonic3kObjectRegistry();
        assertEquals("ICZSegmentColumn", registry.getPrimaryName(Sonic3kObjectIds.ICZ_SEGMENT_COLUMN));
    }

    @Test
    public void subtypeZeroBuildsThreeRomStackSegments() {
        IczSegmentColumnObjectInstance column = new IczSegmentColumnObjectInstance(
                new ObjectSpawn(0x1200, 0x0580, Sonic3kObjectIds.ICZ_SEGMENT_COLUMN, 0, 0, false, 0));

        List<IczSegmentColumnObjectInstance.SegmentSpec> specs = column.segmentSpecsForTesting();

        assertEquals(3, specs.size());
        assertEquals(new IczSegmentColumnObjectInstance.SegmentSpec(0, 0x1200, 0x0580, 0x0A), specs.get(0));
        assertEquals(new IczSegmentColumnObjectInstance.SegmentSpec(2, 0x1200, 0x0560, 0x0A), specs.get(1));
        assertEquals(new IczSegmentColumnObjectInstance.SegmentSpec(4, 0x1200, 0x0540, 0x0A), specs.get(2));
    }

    @Test
    public void nonzeroSubtypeBuildsFourSegmentsWithTopCapFrame() {
        IczSegmentColumnObjectInstance column = new IczSegmentColumnObjectInstance(
                new ObjectSpawn(0x1200, 0x0580, Sonic3kObjectIds.ICZ_SEGMENT_COLUMN, 2, 0, false, 0));

        List<IczSegmentColumnObjectInstance.SegmentSpec> specs = column.segmentSpecsForTesting();

        assertEquals(4, specs.size());
        assertEquals(new IczSegmentColumnObjectInstance.SegmentSpec(0, 0x1200, 0x0580, 0x0A), specs.get(0));
        assertEquals(new IczSegmentColumnObjectInstance.SegmentSpec(2, 0x1200, 0x0560, 0x0A), specs.get(1));
        assertEquals(new IczSegmentColumnObjectInstance.SegmentSpec(4, 0x1200, 0x0540, 0x0A), specs.get(2));
        assertEquals(new IczSegmentColumnObjectInstance.SegmentSpec(6, 0x1200, 0x0520, 0x03), specs.get(3));
    }

    @Test
    public void segmentUsesSolidObjectFullExtentsFromDisassembly() {
        IczSegmentColumnObjectInstance.Segment segment =
                IczSegmentColumnObjectInstance.Segment.forTesting(0x1200, 0x0580, 0, null);

        SolidObjectParams params = segment.getSolidParams();

        assertEquals(0x2B, params.halfWidth());
        assertEquals(0x10, params.airHalfHeight());
        assertEquals(0x10, params.groundHalfHeight());
        assertEquals(0x20, segment.getTopLandingHalfWidth(null, params.halfWidth()));
        assertEquals(5, segment.getPriorityBucket());
    }

    @Test
    public void segmentExposesRomCodePointerWordForS3kTailsCpuInteract() {
        IczSegmentColumnObjectInstance.Segment segment =
                IczSegmentColumnObjectInstance.Segment.forTesting(0x1200, 0x0580, 0, null);

        assertTrue(segment instanceof RomObjectCodePointerProvider);
        assertEquals(0x0008, ((RomObjectCodePointerProvider) segment).romObjectCodePointerHighWord(),
                "Obj_ICZSegmentColumn child segments install loc_8ABB0 in word 0; "
                        + "S3K Tails_CPU_interact stores the pointer high word "
                        + "(docs/skdisasm/sonic3k.asm:188708-188731,26816-26843)");
    }

    @Test
    public void iczPlanIncludesLevelArtEntryForWallAndColumnMappings() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(0x05, 0);

        Sonic3kPlcArtRegistry.LevelArtEntry entry = plan.levelArt().stream()
                .filter(e -> Sonic3kObjectArtKeys.ICZ_WALL_AND_COLUMN.equals(e.key()))
                .findFirst()
                .orElse(null);

        assertNotNull(entry);
        assertEquals(Sonic3kConstants.MAP_ICZ_WALL_AND_COLUMN_ADDR, entry.mappingAddr());
        assertEquals(1, entry.artTileBase());
        assertEquals(2, entry.palette());
    }

    @Test
    public void iczPlanIncludesLevelArtEntryForSegmentColumnDebrisMappings() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(0x05, 0);

        Sonic3kPlcArtRegistry.LevelArtEntry entry = plan.levelArt().stream()
                .filter(e -> Sonic3kObjectArtKeys.ICZ_PLATFORMS.equals(e.key()))
                .findFirst()
                .orElse(null);

        assertNotNull(entry);
        assertEquals(Sonic3kConstants.MAP_ICZ_PLATFORMS_ADDR, entry.mappingAddr());
        assertEquals(Sonic3kConstants.ARTTILE_ICZ_MISC1, entry.artTileBase());
        assertEquals(2, entry.palette());
    }

    @Test
    public void breakDebrisSpecsMatchChildObjDat8AAEAAndIndexedVelocities() {
        List<IczSegmentColumnObjectInstance.BreakDebrisSpec> specs =
                IczSegmentColumnObjectInstance.breakDebrisSpecsForTesting(0x1200, 0x0580);

        assertEquals(12, specs.size());
        assertEquals(new IczSegmentColumnObjectInstance.BreakDebrisSpec(
                0, 0x11F4, 0x0578, 0x200, -0x200, 0x0C), specs.get(0));
        assertEquals(new IczSegmentColumnObjectInstance.BreakDebrisSpec(
                2, 0x11FC, 0x0578, -0x300, -0x200, 0x0C), specs.get(1));
        assertEquals(new IczSegmentColumnObjectInstance.BreakDebrisSpec(
                8, 0x11F4, 0x0580, 0, -0x200, 0x0C), specs.get(4));
        assertEquals(new IczSegmentColumnObjectInstance.BreakDebrisSpec(
                22, 0x120C, 0x0588, 0x200, -0x200, 0x0C), specs.get(11));
    }

    @Test
    public void breakDebrisSpawnCreatesTwelveVisualChildrenAfterCurrent() {
        ObjectManager objectManager = mock(ObjectManager.class);
        IczSegmentColumnObjectInstance.Segment segment =
                IczSegmentColumnObjectInstance.Segment.forTesting(0x1200, 0x0580, 0, null);
        segment.setServices(new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        });

        segment.spawnBreakDebrisForTesting();

        ArgumentCaptor<ObjectInstance> captor = ArgumentCaptor.forClass(ObjectInstance.class);
        verify(objectManager, times(12)).addDynamicObjectAfterCurrent(captor.capture());
        List<ObjectInstance> debris = captor.getAllValues();
        assertTrue(debris.stream().allMatch(IczSegmentColumnObjectInstance.BreakDebris.class::isInstance));

        IczSegmentColumnObjectInstance.BreakDebris first =
                (IczSegmentColumnObjectInstance.BreakDebris) debris.get(0);
        assertEquals(0x11F4, first.getX());
        assertEquals(0x0578, first.getY());
        assertEquals(0x0C, first.getMappingFrameForTesting());
    }
}
