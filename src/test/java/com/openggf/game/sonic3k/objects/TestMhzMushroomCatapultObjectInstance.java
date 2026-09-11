package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.MultiPieceSolidProvider;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PlaceholderObjectInstance;
import com.openggf.level.objects.SlopedSolidProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestMhzMushroomCatapultObjectInstance {
    private static final int MHZ_MUSHROOM_CATAPULT = 0x13;
    private static final SolidContact STANDING_CONTACT = new SolidContact(true, false, false, true, false);

    @Test
    void registryRoutesSklSlot13ToMhzMushroomCatapult() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);

        ObjectInstance catapult = registry.create(new ObjectSpawn(
                0x1600, 0x0600, MHZ_MUSHROOM_CATAPULT, 0, 0, false, 0));

        assertFalse(catapult instanceof PlaceholderObjectInstance,
                "SKL slot $13 is Obj_MHZMushroomCatapult and must not remain a placeholder");
        assertInstanceOf(MultiPieceSolidProvider.class, catapult,
                "Obj_MHZMushroomCatapult creates two sloped solid cap pieces");
        assertInstanceOf(SlopedSolidProvider.class, catapult,
                "Both cap pieces call SolidObjectTopSloped2 with byte_3FB50");
        assertEquals(5, catapult.getPriorityBucket(),
                "Obj_MHZMushroomCatapult initializes parent and child priority=$280");
    }

    @Test
    void exposesRomTwoPieceGeometrySlopeAndSubtypeSide() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance rightFacing = registry.create(new ObjectSpawn(
                0x1600, 0x0600, MHZ_MUSHROOM_CATAPULT, 0, 0, false, 0));
        ObjectInstance leftFacing = registry.create(new ObjectSpawn(
                0x1600, 0x0600, MHZ_MUSHROOM_CATAPULT, 1, 0, false, 0));

        MultiPieceSolidProvider rightPieces = assertInstanceOf(MultiPieceSolidProvider.class, rightFacing);
        MultiPieceSolidProvider leftPieces = assertInstanceOf(MultiPieceSolidProvider.class, leftFacing);
        SlopedSolidProvider sloped = assertInstanceOf(SlopedSolidProvider.class, rightFacing);

        assertEquals(2, rightPieces.getPieceCount(),
                "The ROM allocates the parent cap and one large child cap as the playable catapult surfaces");
        assertEquals(0x1600, rightPieces.getPieceX(0));
        assertEquals(0x0600, rightPieces.getPieceY(0));
        assertEquals(0x1640, rightPieces.getPieceX(1),
                "subtype 0 places the child cap at x_pos+$40");
        assertEquals(0x05E8, rightPieces.getPieceY(1),
                "child cap starts at y_pos-$18 while compression $34 is zero");
        assertEquals(0x15C0, leftPieces.getPieceX(1),
                "nonzero subtype places the child cap at x_pos-$40");

        assertArrayEquals(new byte[]{
                0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B,
                0x0B, 0x0C, 0x0C, 0x0C, 0x0C, 0x0C, 0x0C, 0x0C,
                0x0C, 0x0C, 0x0C, 0x0C, 0x0C, 0x0C, 0x0C, 0x0B,
                0x0B, 0x0A, 0x09, 0x08, 0x07, 0x06, 0x05, 0x04
        }, sloped.getSlopeData(), "byte_3FB50 must be used verbatim for cap collision");
        assertEquals(0x20, sloped.getSolidParams().halfWidth(),
                "Obj_MHZMushroomCatapult passes width_pixels ($20) into SolidObjectTopSloped2");
        assertEquals(0, sloped.getSlopeBaseline(),
                "SolidObjectTopSloped2 uses absolute height samples for this cap");
    }

    @Test
    void childCapStandingCompressesSpringUntilBothCapsSwapHeights() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance catapult = registry.create(new ObjectSpawn(
                0x1600, 0x0600, MHZ_MUSHROOM_CATAPULT, 0, 0, false, 0));
        MultiPieceSolidProvider pieces = assertInstanceOf(MultiPieceSolidProvider.class, catapult);

        pieces.onPieceContact(1, null, STANDING_CONTACT, 0);

        catapult.update(0, null);
        assertEquals(0x05F8, pieces.getPieceY(0));
        assertEquals(0x05F0, pieces.getPieceY(1));

        catapult.update(1, null);
        assertEquals(0x05F0, pieces.getPieceY(0));
        assertEquals(0x05F8, pieces.getPieceY(1));

        catapult.update(2, null);
        assertEquals(0x05E8, pieces.getPieceY(0),
                "compression $34 reaches $18 after three +8 steps");
        assertEquals(0x0600, pieces.getPieceY(1));
    }

    @Test
    void fullyRaisedCatapultLaunchesParentCapRiderWithRomVelocity() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance catapult = registry.create(new ObjectSpawn(
                0x1600, 0x0600, MHZ_MUSHROOM_CATAPULT, 0, 0, false, 0));
        MultiPieceSolidProvider pieces = assertInstanceOf(MultiPieceSolidProvider.class, catapult);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x1600, (short) 0x0600);

        pieces.onPieceContact(1, null, STANDING_CONTACT, 0);
        catapult.update(0, null);
        catapult.update(1, null);
        catapult.update(2, null);

        player.setOnObject(true);
        player.setAir(false);
        pieces.onPieceContact(0, player, STANDING_CONTACT, 3);
        catapult.update(3, player);

        assertEquals((short) -0x0D00, player.getYSpeed(),
                "ROM loc_3FA34 launches the opposite cap rider with y_vel=-$D00");
        assertTrue(player.getAir(), "launch must set the player airborne");
        assertFalse(player.isOnObject(), "launch must clear the on-object status bit");
    }

    @Test
    void catapultLaunchAppliesRomSpringStateToRider() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance catapult = registry.create(new ObjectSpawn(
                0x1600, 0x0600, MHZ_MUSHROOM_CATAPULT, 0, 0, false, 0));
        MultiPieceSolidProvider pieces = assertInstanceOf(MultiPieceSolidProvider.class, catapult);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x1600, (short) 0x0600);

        pieces.onPieceContact(1, null, STANDING_CONTACT, 0);
        catapult.update(0, null);
        catapult.update(1, null);
        catapult.update(2, null);

        player.setOnObject(true);
        player.setAir(false);
        player.setSpindash(true);
        player.setAnimationId(Sonic3kAnimationIds.SPINDASH);
        pieces.onPieceContact(0, player, STANDING_CONTACT, 3);
        catapult.update(3, player);

        assertEquals(Sonic3kAnimationIds.SPRING.id(), player.getAnimationId(),
                "loc_3FA66 writes anim=$10 after the catapult bounce");
        assertFalse(player.getSpindash(),
                "loc_3FA66 clears spin_dash_flag after the catapult bounce");
    }

    @Test
    void catapultLaunchForcesRiderBackToNormalRoutine() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance catapult = registry.create(new ObjectSpawn(
                0x1600, 0x0600, MHZ_MUSHROOM_CATAPULT, 0, 0, false, 0));
        MultiPieceSolidProvider pieces = assertInstanceOf(MultiPieceSolidProvider.class, catapult);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x1600, (short) 0x0600);

        pieces.onPieceContact(1, null, STANDING_CONTACT, 0);
        catapult.update(0, null);
        catapult.update(1, null);
        catapult.update(2, null);

        player.setOnObject(true);
        player.setAir(false);
        player.setHurt(true);
        pieces.onPieceContact(0, player, STANDING_CONTACT, 3);
        catapult.update(3, player);

        assertFalse(player.isHurt(),
                "loc_3FA66 writes routine=2 after a catapult launch, leaving the hurt routine");
    }

    @Test
    void centerCapImpactArmsHighVelocityChildCapLaunchWhenRiderRemainsOnOppositeCap() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance catapult = registry.create(new ObjectSpawn(
                0x1600, 0x0600, MHZ_MUSHROOM_CATAPULT, 0, 0, false, 0));
        MultiPieceSolidProvider pieces = assertInstanceOf(MultiPieceSolidProvider.class, catapult);
        TestablePlayableSprite setupRider = new TestablePlayableSprite("sonic", (short) 0x1640, (short) 0x0600);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x1640, (short) 0x0600);

        raiseFromChildCap(catapult, pieces, 0);
        runUntilParentCapReturnsToBaseWithChildRider(catapult, pieces, setupRider, 3);

        raiseFromChildCap(catapult, pieces, 100);
        player.setOnObject(true);
        player.setAir(false);

        boolean launched = false;
        for (int frame = 103; frame < 240; frame++) {
            pieces.onPieceContact(1, player, STANDING_CONTACT, frame);
            catapult.update(frame, player);
            if (player.getYSpeed() == (short) -0x0E80) {
                launched = true;
                break;
            }
        }

        assertTrue(launched,
                "After the small center cap impact sets parent $3A >= $900, child-cap riders launch at -$E80");
        assertTrue(player.getAir(), "high launch must set the player airborne");
        assertFalse(player.isOnObject(), "high launch must clear the on-object status bit");
    }

    @Test
    void emptyLoweringCompletionClearsStoredCenterCapImpactForNextChildLaunch() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance catapult = registry.create(new ObjectSpawn(
                0x1600, 0x0600, MHZ_MUSHROOM_CATAPULT, 0, 0, false, 0));
        MultiPieceSolidProvider pieces = assertInstanceOf(MultiPieceSolidProvider.class, catapult);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x1640, (short) 0x0600);

        raiseFromChildCap(catapult, pieces, 0);
        runUntilParentCapReturnsToBase(catapult, 3);

        raiseFromChildCap(catapult, pieces, 100);
        runUntilParentCapReturnsToBase(catapult, 103);

        raiseFromChildCap(catapult, pieces, 200);
        player.setOnObject(true);
        player.setAir(false);

        boolean launched = false;
        for (int frame = 203; frame < 340; frame++) {
            pieces.onPieceContact(1, player, STANDING_CONTACT, frame);
            catapult.update(frame, player);
            if (player.getYSpeed() != 0) {
                launched = true;
                break;
            }
        }

        assertTrue(launched, "child-cap rider should launch after the third compression cycle");
        assertEquals((short) -0x0D00, player.getYSpeed(),
                "loc_3F9FE clears $3A when no child rider is standing as the catapult returns to rest");
    }

    @Test
    void centerCapPlaysFlipperSfxWhenCompressionReachesMaximum() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        RecordingServices services = new RecordingServices();
        AbstractObjectInstance catapult = (AbstractObjectInstance) registry.create(new ObjectSpawn(
                0x1600, 0x0600, MHZ_MUSHROOM_CATAPULT, 0, 0, false, 0));
        catapult.setServices(services);
        MultiPieceSolidProvider pieces = assertInstanceOf(MultiPieceSolidProvider.class, catapult);

        pieces.onPieceContact(1, null, STANDING_CONTACT, 0);
        catapult.update(0, null);
        catapult.update(1, null);
        catapult.update(2, null);

        assertTrue(services.playedSfx(Sonic3kSfx.FLIPPER.id),
                "loc_3FAC2 plays sfx_Flipper when the small center cap starts bouncing at compression $18");
    }

    @Test
    void fullyRaisedCatapultPlaysLaunchSfxOnceRegardlessOfRiderCount() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        RecordingServices services = new RecordingServices();
        AbstractObjectInstance catapult = (AbstractObjectInstance) registry.create(new ObjectSpawn(
                0x1600, 0x0600, MHZ_MUSHROOM_CATAPULT, 0, 0, false, 0));
        catapult.setServices(services);
        MultiPieceSolidProvider pieces = assertInstanceOf(MultiPieceSolidProvider.class, catapult);
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x1600, (short) 0x0600);
        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0x1600, (short) 0x0600);

        pieces.onPieceContact(1, null, STANDING_CONTACT, 0);
        catapult.update(0, null);
        catapult.update(1, null);
        catapult.update(2, null);

        sonic.setOnObject(true);
        sonic.setAir(false);
        tails.setOnObject(true);
        tails.setAir(false);
        pieces.onPieceContact(0, sonic, STANDING_CONTACT, 3);
        pieces.onPieceContact(0, tails, STANDING_CONTACT, 3);
        catapult.update(3, sonic);

        assertEquals(1, services.sfxCount(Sonic3kSfx.MUSHROOM_BOUNCE.id),
                "sub_3FA5A ends with a single shared 'jmp Play_SFX' per launch call (asm 84351-84352), "
                        + "not once per rider standing on the launching cap");
    }

    @Test
    void mushroomCatapultRendersParentChildAndCenterCapFrames() {
        PatternSpriteRenderer capRenderer = mock(PatternSpriteRenderer.class);
        PatternSpriteRenderer centerRenderer = mock(PatternSpriteRenderer.class);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        LevelManager levelManager = mock(LevelManager.class);
        when(capRenderer.isReady()).thenReturn(true);
        when(centerRenderer.isReady()).thenReturn(true);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MHZ_MUSHROOM_CATAPULT_CAPS))
                .thenReturn(capRenderer);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MHZ_MUSHROOM_CATAPULT_CENTER))
                .thenReturn(centerRenderer);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);
        MhzMushroomCatapultObjectInstance catapult = new MhzMushroomCatapultObjectInstance(new ObjectSpawn(
                0x1600, 0x0600, MHZ_MUSHROOM_CATAPULT, 0, 0, false, 0));
        catapult.setServices(new TestObjectServices().withLevelManager(levelManager));

        catapult.appendRenderCommands(new ArrayList<>());

        verify(capRenderer).drawFrameIndex(0, 0x1600, 0x0600, false, false);
        verify(capRenderer).drawFrameIndex(0, 0x1640, 0x05E8, false, false);
        verify(centerRenderer).drawFrameIndex(1, 0x1600, 0x05EC, false, false);
    }

    private static void raiseFromChildCap(ObjectInstance catapult, MultiPieceSolidProvider pieces, int firstFrame) {
        pieces.onPieceContact(1, null, STANDING_CONTACT, firstFrame);
        catapult.update(firstFrame, null);
        catapult.update(firstFrame + 1, null);
        catapult.update(firstFrame + 2, null);
    }

    private static void runUntilParentCapReturnsToBase(ObjectInstance catapult, int firstFrame) {
        for (int frame = firstFrame; frame < firstFrame + 96; frame++) {
            catapult.update(frame, null);
        }
    }

    private static void runUntilParentCapReturnsToBaseWithChildRider(
            ObjectInstance catapult,
            MultiPieceSolidProvider pieces,
            TestablePlayableSprite rider,
            int firstFrame) {
        for (int frame = firstFrame; frame < firstFrame + 96; frame++) {
            pieces.onPieceContact(1, rider, STANDING_CONTACT, frame);
            catapult.update(frame, rider);
        }
    }

    private static final class RecordingServices extends TestObjectServices {
        private final List<Integer> sfx = new ArrayList<>();

        @Override
        public void playSfx(int soundId) {
            sfx.add(soundId);
        }

        private boolean playedSfx(int soundId) {
            return sfx.contains(soundId);
        }

        private long sfxCount(int soundId) {
            return sfx.stream().filter(id -> id == soundId).count();
        }
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
