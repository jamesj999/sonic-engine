package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SlopedSolidProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestMhzCurledVineObjectInstance {
    private static final int MHZ_CURLED_VINE = 0x09;
    private PatternSpriteRenderer renderer;
    private LevelManager levelManager;

    @BeforeEach
    void setUpRenderer() {
        renderer = mock(PatternSpriteRenderer.class);
        when(renderer.isReady()).thenReturn(true);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MHZ_CURLED_VINE)).thenReturn(renderer);
        levelManager = mock(LevelManager.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);
    }

    @Test
    void registryRoutesSklSlot09ToMhzCurledVineInsteadOfAizTree() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);

        ObjectInstance vine = registry.create(new ObjectSpawn(
                0x2000, 0x0600, MHZ_CURLED_VINE, 0, 0, false, 0));

        assertEquals("MHZCurledVine", vine.getName(),
                "SKL slot $09 is Obj_MHZCurledVine; MHZ must not use the S3KL AIZ1 tree object");
    }

    @Test
    void curledVineReservesItsAllocateObjectAfterCurrentDisplayChildSlot() {
        ObjectSpawn spawn = new ObjectSpawn(
                0x2000, 0x0600, MHZ_CURLED_VINE, 0, 0, false, 0);
        MhzCurledVineObjectInstance vine = new MhzCurledVineObjectInstance(spawn);
        ObjectManager objectManager = mock(ObjectManager.class);
        when(levelManager.getObjectManager()).thenReturn(objectManager);
        vine.setServices(new TestObjectServices().withLevelManager(levelManager));
        vine.setSlotIndex(13);

        vine.update(0, null);
        vine.update(1, null);

        verify(objectManager, times(1)).allocateChildSlotsAfter(spawn, 1, 13);
    }

    @Test
    void curledVineExposesRomTopSolidFootprint() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance vine = registry.create(new ObjectSpawn(
                0x2000, 0x0600, MHZ_CURLED_VINE, 0, 0, false, 0));

        assertEquals("MHZCurledVine", vine.getName(),
                "SKL slot $09 must construct the MHZ curled vine before solidity can be validated");
        AbstractObjectInstance concreteVine = assertInstanceOf(AbstractObjectInstance.class, vine);
        assertEquals(5, vine.getPriorityBucket(),
                "Obj_MHZCurledVine initializes priority=$280, which maps to render bucket 5");
        assertEquals(0x40, concreteVine.getOnScreenHalfWidth(),
                "Obj_MHZCurledVine initializes the display child width_pixels to $40");
        assertEquals(0x30, concreteVine.getOnScreenHalfHeight(),
                "Obj_MHZCurledVine initializes the display child height_pixels to $30");

        SolidObjectProvider solid = assertInstanceOf(SolidObjectProvider.class, vine,
                "Obj_MHZCurledVine calls its top-solid helper after generating the child segment surface");
        assertEquals(0x20, solid.getSolidParams().halfWidth(),
                "The initial byte_3E8F6 range is $40 pixels, exposed as a $20 standable half-width");
        assertTrue(solid.isTopSolidOnly(),
                "Obj_MHZCurledVine only presents a rideable top surface");
        assertTrue(solid.usesCollisionHalfWidthForTopLanding(),
                "The curled vine helper passes its computed standable range directly");
    }

    @Test
    void standingPlayerNearRightEdgeWidensRangeAndUncurlsOneStep() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance vine = registry.create(new ObjectSpawn(
                0x2000, 0x0600, MHZ_CURLED_VINE, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2030, (short) 0x05E0);

        SolidObjectListener listener = assertInstanceOf(SolidObjectListener.class, vine,
                "Obj_MHZCurledVine stores per-player segment indices in $36/$37 while ridden");
        listener.onSolidContact(player, new SolidContact(true, false, false, true, false), 0);
        vine.update(1, player);

        SolidObjectProvider solid = assertInstanceOf(SolidObjectProvider.class, vine);
        assertEquals(0x40, solid.getSolidParams().halfWidth(),
                "Right-edge standing index 7 selects byte_3E8F6[8]=$80, exposed as a $40 half-width");
        assertTrue(vine.traceDebugDetails().contains("curve=$FFF50000"),
                "The curve state moves one $10000 step from $FFF40000 toward byte index 8's $FFFF0000 target");
        assertTrue(vine.traceDebugDetails().contains("range=$80"),
                "The live standable range mirrors byte_3E8F6 for the selected rider index");
    }

    @Test
    void fartherOfTwoStandingRidersControlsSharedRange() {
        MhzCurledVineObjectInstance vine = new MhzCurledVineObjectInstance(new ObjectSpawn(
                0x2000, 0x0600, MHZ_CURLED_VINE, 0, 0, false, 0));
        TestablePlayableSprite player2 = new TestablePlayableSprite("tails_p2", (short) 0x1FC0, (short) 0x05E0);
        TestablePlayableSprite player1 = new TestablePlayableSprite("sonic", (short) 0x2000, (short) 0x05E0);
        SolidObjectListener listener = vine;

        // sub_3E9AC visits P2 ($37) then P1 ($36). Their ROM d0 values are
        // respectively $00 and $40, so loc_3E9FA stores segments 0 and 4.
        listener.onSolidContact(player2, new SolidContact(true, false, false, true, false), 0);
        listener.onSolidContact(player1, new SolidContact(true, false, false, true, false), 0);
        vine.update(1, player1);

        assertEquals(0x30, vine.getSolidParams().halfWidth(),
                "loc_3E8A2 retains the farther P1 segment 4 over P2 segment 0, adds one, "
                        + "and selects byte_3E8F6[5]=$60 (sonic3k.asm:82810-82820)");
    }

    @Test
    void standingPlayerSagsToPerSegmentContourY() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance vine = registry.create(new ObjectSpawn(
                0x2000, 0x0600, MHZ_CURLED_VINE, 0, 0, false, 0));
        MhzCurledVineObjectInstance concreteVine =
                assertInstanceOf(MhzCurledVineObjectInstance.class, vine);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2030, (short) 0x05E0);

        SolidObjectListener listener = assertInstanceOf(SolidObjectListener.class, vine);
        // Frame 0 establishes the ride (ROM standing bit still clear: the fall-through
        // loc_3EA1E/loc_1E45A landing owns Y). Frame 1 is a continued-ride frame with the
        // bit set, so loc_3E9FA (sonic3k.asm:82963-82977) contours Y to the curl surface.
        listener.onSolidContact(player, new SolidContact(true, false, false, true, false), 0);
        listener.onSolidContact(player, new SolidContact(true, false, false, true, false), 1);

        // relativeX = 0x2030 - 0x2000 = 0x30; segmentIndex = (0x30 + 0x40) >> 4 = 7
        // (matches the standing-segment index used by standingPlayerNearRightEdge...).
        int expectedY = concreteVine.segmentY(7) - 8 - player.getYRadius();
        assertNotEquals(0x0600, concreteVine.segmentY(7),
                "Segment 7 must sample a non-zero curl offset from spawn.y for this assertion to be meaningful");
        assertEquals(expectedY, player.getCentreY(),
                "Obj_MHZCurledVine writes y_pos(a1) = segmentY - 8 - y_radius(a1) every standing frame "
                        + "from the generated curl segment table (loc_3E9FA, sonic3k.asm:82963-82977), "
                        + "not a flat surface at spawn.y");
    }

    @Test
    void fallingPlayerLandsOnRaisedPerSegmentCurlSurfaceNotFlatSpawnY() {
        int vineX = 0x2000;
        int vineY = 0x0600;
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance vine = registry.create(new ObjectSpawn(
                vineX, vineY, MHZ_CURLED_VINE, 0, 0, false, 0));
        MhzCurledVineObjectInstance concreteVine =
                assertInstanceOf(MhzCurledVineObjectInstance.class, vine);

        // ROM sub_3E9C6->loc_3EA1E (sonic3k.asm:82942-82986): a FALLING player
        // (y_vel>=0) whose (playerX - vineX + $40) is in [0, rangeWidth) lands via
        // the SEGMENT-height surface $1A(a2,d0*6) - 8, not a flat surface at spawn.y.
        // The engine must expose that per-x curl surface to landing detection, i.e.
        // the curled vine must resolve new landings through the sloped-solid path.
        SlopedSolidProvider sloped = assertInstanceOf(SlopedSolidProvider.class, vine,
                "Obj_MHZCurledVine lands falling players on the generated per-segment curl "
                        + "surface (loc_3EA1E), so its top-solid must be sloped, not flat");
        assertEquals(0, sloped.getSlopeBaseline(),
                "loc_3EA1E reads absolute segment heights ($1A(a2,d0*6)); baseline must be 0");

        SolidObjectParams params = sloped.getSolidParams();
        int halfWidth = params.halfWidth();
        int width2 = halfWidth * 2;
        int anchorX = vineX + params.offsetX();
        int anchorY = vineY + params.offsetY();

        // Player one segment into the ROM window: d0 = playerX - vineX + $40 = $30,
        // segment index d0>>4 = 3 (surface generated ~32px above spawn.y).
        int playerCenterX = vineX - 0x10;
        int relX = playerCenterX - anchorX + halfWidth; // = ROM d0
        assertEquals(0x30, relX, "player must sit one segment into the ROM landing window");
        int sampleX = relX >> 1; // ROM lsr.w #1,d0 before the segment lookup
        int segment = sampleX >> 3; // d0>>4
        assertEquals(3, segment);

        byte[] slopeData = sloped.getSlopeData();
        assertTrue(sampleX < slopeData.length, "slope table must cover the full landing window");
        int surfaceY = anchorY - (slopeData[sampleX] - sloped.getSlopeBaseline());

        assertEquals(concreteVine.segmentY(segment) - 8, surfaceY,
                "The sloped landing surface must be the generated curl segment height minus 8 "
                        + "(loc_3EA1E: $1A(a2,d0*6) then subq.w #8,d0), so a falling player lands "
                        + "on the raised curl instead of falling past a flat surface at spawn.y");
        assertTrue(surfaceY < vineY,
                "Segment " + segment + " is curled above spawn.y; the landing surface a falling "
                        + "player reaches must be raised, not the flat spawn.y the old top-solid used");

        // Outside the ROM window (d0 >= rangeWidth) there is no landing surface, so a
        // player there keeps falling -- loc_3EA1E's `cmp.w d2,d0 / bhs locret_3EA4A`.
        int outsidePlayerX = vineX + 0x10; // d0 = $50 >= rangeWidth $40
        int outsideRelX = outsidePlayerX - anchorX + halfWidth;
        assertTrue(outsideRelX >= width2,
                "playerX past the ROM window's right edge falls through with no curl surface");
    }

    @Test
    void topSolidWindowIsAsymmetricOffsetLeftNotCentredOnSpawnX() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance vine = registry.create(new ObjectSpawn(
                0x2000, 0x0600, MHZ_CURLED_VINE, 0, 0, false, 0));
        SolidObjectProvider solid = assertInstanceOf(SolidObjectProvider.class, vine);
        SolidObjectParams params = solid.getSolidParams();

        int vineX = 0x2000;
        int boundsX = vineX + params.offsetX();
        int halfWidth = params.halfWidth();
        int width2 = halfWidth * 2;

        // ROM sub_3E9C6 (sonic3k.asm:82949-82953) accepts
        // 0 <= (playerX - vineX + $40) < rangeWidth, i.e. window
        // [vineX-$40, vineX-$40+rangeWidth) -- offset $40 LEFT of vineX, not centred.
        int justInsideRomWindowX = vineX - 0x40 + 1;
        int relInside = justInsideRomWindowX - boundsX + halfWidth;
        assertTrue(relInside >= 0 && relInside < width2,
                "playerX = vineX-$40+1 satisfies the ROM window's left edge");

        // The OLD (buggy) centred window's right edge (offsetX=0) is now outside
        // the ROM's asymmetric window once rangeWidth <= $40 (the initial/default case).
        int oldCentredRightEdgeX = vineX + halfWidth - 1;
        int relOld = oldCentredRightEdgeX - boundsX + halfWidth;
        assertFalse(relOld >= 0 && relOld < width2,
                "playerX at the old centred window's right edge must fall outside "
                        + "the ROM's [vineX-$40, vineX-$40+rangeWidth) window");
    }

    @Test
    void curledVineRendersRomDisplayChildSegments() {
        MhzCurledVineObjectInstance vine = new MhzCurledVineObjectInstance(new ObjectSpawn(
                0x2000, 0x0600, MHZ_CURLED_VINE, 0, 0, false, 0));
        vine.setServices(new TestObjectServices().withLevelManager(levelManager));

        vine.appendRenderCommands(new ArrayList<>());

        verify(renderer, times(8)).drawFrameIndex(eq(0), anyInt(), anyInt(), eq(false), eq(false));
        verify(renderer).drawFrameIndex(0, 0x1FC8, 0x0600, false, false);
    }

    @Test
    void hFlippedCurledVineMirrorsDisplayChildSegments() {
        MhzCurledVineObjectInstance vine = new MhzCurledVineObjectInstance(new ObjectSpawn(
                0x2000, 0x0600, MHZ_CURLED_VINE, 0, 1, false, 0));
        vine.setServices(new TestObjectServices().withLevelManager(levelManager));

        vine.appendRenderCommands(new ArrayList<>());

        verify(renderer, times(8)).drawFrameIndex(eq(0), anyInt(), anyInt(), eq(true), eq(false));
        verify(renderer).drawFrameIndex(0, 0x2038, 0x0600, true, false);
        assertTrue(vine.traceDebugDetails().contains("hflip=true"),
                "Spawn render flag bit 0 must drive the MHZ curled vine's display-child horizontal flip");
    }

    @Test
    void riderPressureMovesRenderedSegmentsAsCurveUncurls() {
        MhzCurledVineObjectInstance vine = new MhzCurledVineObjectInstance(new ObjectSpawn(
                0x2000, 0x0600, MHZ_CURLED_VINE, 0, 0, false, 0));
        vine.setServices(new TestObjectServices().withLevelManager(levelManager));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2030, (short) 0x05E0);

        vine.appendRenderCommands(new ArrayList<>());
        List<Integer> initialY = capturedRenderedYPositions();
        clearInvocations(renderer);

        SolidObjectListener listener = assertInstanceOf(SolidObjectListener.class, vine);
        listener.onSolidContact(player, new SolidContact(true, false, false, true, false), 0);
        for (int frame = 1; frame <= 8; frame++) {
            vine.update(frame, player);
        }
        vine.appendRenderCommands(new ArrayList<>());

        assertNotEquals(initialY, capturedRenderedYPositions(),
                "Obj_MHZCurledVine animates by regenerating the eight child sprite positions from its curve state");
    }

    private List<Integer> capturedRenderedYPositions() {
        ArgumentCaptor<Integer> yCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(renderer, times(8)).drawFrameIndex(eq(0), anyInt(), yCaptor.capture(), eq(false), eq(false));
        return yCaptor.getAllValues();
    }

    private static final class ZoneForTestRegistry extends Sonic3kObjectRegistry {
        private final int zoneId;

        private ZoneForTestRegistry(int zoneId) {
            this.zoneId = zoneId;
        }

        @Override
        protected int currentRomZoneId() {
            return zoneId;
        }
    }
}
