package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic1.objects.TestPlayableSprite;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestCnzBarberPoleObjectInstance {

    @Test
    void exposesRomCodePointerForS3kTailsCpuInteract() {
        CnzBarberPoleObjectInstance pole = new CnzBarberPoleObjectInstance(
                new ObjectSpawn(0x0F70, 0x0810, Sonic3kObjectIds.CNZ_BARBER_POLE, 0, 0, false, 0));

        assertTrue(pole instanceof RomObjectCodePointerProvider);
        assertEquals(0x0003, ((RomObjectCodePointerProvider) pole).romObjectCodePointerHighWord(),
                "S3K sub_13EFC stores word 0 of loc_33376/loc_335A8 into Tails_CPU_interact");
    }

    @Test
    void unloadUsesLatchedLifecycleDestructionPolicy() {
        CnzBarberPoleObjectInstance pole = new CnzBarberPoleObjectInstance(
                new ObjectSpawn(0x2870, 0x0190, Sonic3kObjectIds.CNZ_BARBER_POLE, 0, 0, false, 0));

        pole.onUnload();

        assertTrue(pole.isDestroyed());
    }

    @Test
    void usesNativeCoarseBackDeletionWindow() {
        CnzBarberPoleObjectInstance pole = new CnzBarberPoleObjectInstance(
                new ObjectSpawn(0x0EF0, 0x0A10, Sonic3kObjectIds.CNZ_BARBER_POLE, 0, 0, false, 0));

        assertFalse(pole.isCustomOutOfRange(0x0F7F));
        assertTrue(pole.isCustomOutOfRange(0x0F80),
                "the unsigned coarse subtraction must delete as soon as the camera's coarse-back passes the pole");
    }

    @Test
    void normalRelatchUsesRomUnsignedWordCompareForInnerTrackFlag() {
        CnzBarberPoleObjectInstance pole = new CnzBarberPoleObjectInstance(
                new ObjectSpawn(0x0F70, 0x0810, Sonic3kObjectIds.CNZ_BARBER_POLE, 0, 0, false, 0));
        pole.setServices(new TestObjectServices());

        TestPlayableSprite tails = new TestPlayableSprite();
        tails.setHeight(30);
        tails.applyCustomRadii(9, 15);
        tails.setCentreX((short) 0x0F4B);
        tails.setCentreY((short) 0x07B2);
        tails.setSubpixelRaw(0x8000, 0x8600);
        tails.setOnObject(true);
        tails.setLatchedSolidObject(Sonic3kObjectIds.CNZ_BARBER_POLE, pole);
        tails.setAir(false);

        pole.update(0x0638, tails);
        tails.setXSpeed((short) 0x0687);
        tails.setGSpeed((short) 0x093C);
        pole.update(0x0639, tails);

        assertTrue(tails.isOnObject());
        assertFalse(tails.getAir());
        assertEquals(0x0F4E, tails.getCentreX() & 0xFFFF);
        assertEquals(0x07B9, tails.getCentreY() & 0xFFFF);
        assertEquals(0x0E, tails.getFlipAngle() & 0xFF);
        assertEquals(2, tails.getFlipType(),
                "loc_334A4 selects the normal-pole tumble mapping set");
    }

    @Test
    void normalPoleTogglesPlayerPriorityAcrossFrontAndBackHalves() {
        CnzBarberPoleObjectInstance pole = new CnzBarberPoleObjectInstance(
                new ObjectSpawn(0x0F70, 0x0810, Sonic3kObjectIds.CNZ_BARBER_POLE, 0, 0, false, 0));
        pole.setServices(new TestObjectServices());

        TestPlayableSprite sonic = new TestPlayableSprite();
        sonic.setHeight(30);
        sonic.applyCustomRadii(9, 15);
        sonic.setCentreX((short) 0x0F4B);
        sonic.setCentreY((short) 0x07B2);
        sonic.setSubpixelRaw(0x8000, 0x0000);
        sonic.setOnObject(true);
        sonic.setLatchedSolidObject(Sonic3kObjectIds.CNZ_BARBER_POLE, pole);
        sonic.setAir(false);

        pole.update(0, sonic);
        sonic.setXSpeed((short) 0x0800);
        sonic.setGSpeed((short) 0x0800);

        pole.update(1, sonic);
        assertTrue(sonic.isHighPriority(),
                "loc_33542 sets high_priority while the normal-pole curve byte is below $34");

        for (int frame = 2; frame <= 10; frame++) {
            pole.update(frame, sonic);
        }

        assertTrue(sonic.isOnObject());
        assertFalse(sonic.isHighPriority(),
                "loc_33542 clears high_priority once Sonic moves onto the rear half of the pole");
    }

    @Test
    void crossingPoleOfOppositeOrientationDoesNotStealRider() {
        // ROM loc_33472 (sonic3k.asm:69439) checks cmpi.l #loc_33376,(a3): the
        // normal-pole re-latch path only fires when interact(a1) is itself a
        // normal pole. At a CNZ2 X crossing the other pole is mirrored
        // (routine loc_335A8), so the normal pole must not steal the rider --
        // Sonic passes through the crossing pole instead of being blocked.
        CnzBarberPoleObjectInstance normalPole = new CnzBarberPoleObjectInstance(
                new ObjectSpawn(0x0F70, 0x0810, Sonic3kObjectIds.CNZ_BARBER_POLE, 0, 0, false, 0));
        normalPole.setServices(new TestObjectServices());
        CnzBarberPoleObjectInstance mirroredPole = new CnzBarberPoleObjectInstance(
                new ObjectSpawn(0x0F70, 0x0810, Sonic3kObjectIds.CNZ_BARBER_POLE, 1, 0, false, 0));

        TestPlayableSprite sonic = new TestPlayableSprite();
        sonic.setHeight(30);
        sonic.applyCustomRadii(9, 15);
        sonic.setCentreX((short) 0x0F4B);
        sonic.setCentreY((short) 0x07B2);
        sonic.setSubpixelRaw(0x8000, 0x8600);
        sonic.setOnObject(true);
        sonic.setLatchedSolidObject(Sonic3kObjectIds.CNZ_BARBER_POLE, mirroredPole);
        sonic.setAir(false);

        normalPole.update(0x0638, sonic);

        assertEquals(mirroredPole, sonic.getLatchedSolidObjectInstance(),
                "the crossing mirrored pole's rider must not be re-latched by the normal pole");
    }

    @Test
    void debugPlacementModeBlocksBarberPoleLatch() {
        // ROM sub_33392 / sub_335C4 return at tst.w (Debug_placement_mode).w
        // (sonic3k.asm:69385-69386, 69597-69598): poles never grab a player in
        // debug movement mode.
        CnzBarberPoleObjectInstance pole = new CnzBarberPoleObjectInstance(
                new ObjectSpawn(0x0100, 0x0100, Sonic3kObjectIds.CNZ_BARBER_POLE, 0, 0, false, 0));
        pole.setServices(new TestObjectServices());

        TestPlayableSprite sonic = new TestPlayableSprite();
        sonic.setCentreX((short) 0x0100);
        sonic.setCentreY((short) 0x00C7);
        sonic.setDebugMode(true);

        pole.update(0, sonic);

        assertFalse(sonic.isOnObject(), "debug placement mode must not latch the player to the pole");
        assertNotEquals(Sonic3kObjectIds.CNZ_BARBER_POLE, sonic.getLatchedSolidObjectId());
    }

    @Test
    void queryOnlySidekickParticipatesInBarberPoleLatch() {
        CnzBarberPoleObjectInstance pole = new CnzBarberPoleObjectInstance(
                new ObjectSpawn(0x0100, 0x0100, Sonic3kObjectIds.CNZ_BARBER_POLE, 0, 0, false, 0));
        TestPlayableSprite main = new TestPlayableSprite();
        main.setCentreX((short) 0);
        main.setCentreY((short) 0);
        TestPlayableSprite sidekick = new TestPlayableSprite();
        sidekick.setCentreX((short) 0x0100);
        sidekick.setCentreY((short) 0x00C9);
        pole.setServices(new QueryOnlyPlayerServices(main, List.of(sidekick)));

        pole.update(0, main);

        assertTrue(sidekick.isOnObject());
        assertFalse(sidekick.getAir());
        assertEquals(Sonic3kObjectIds.CNZ_BARBER_POLE, sidekick.getLatchedSolidObjectId());
    }

    @Test
    void airborneRollingLatchRunsNativeTouchFloorBeforeRide() {
        TestEnvironment.resetAll();
        CnzBarberPoleObjectInstance pole = new CnzBarberPoleObjectInstance(
                new ObjectSpawn(0x0100, 0x0100, Sonic3kObjectIds.CNZ_BARBER_POLE, 0, 0, false, 0));
        pole.setServices(new TestObjectServices());

        TestPlayableSprite sonic = new TestPlayableSprite();
        sonic.setCentreX((short) 0x0100);
        sonic.setRolling(true);
        sonic.setCentreY((short) 0x00CA);
        sonic.setAir(true);
        sonic.setYSpeed((short) 0x0200);

        pole.update(0, sonic);

        assertTrue(sonic.isOnObject());
        assertFalse(sonic.getAir());
        assertFalse(sonic.getRolling(), "sub_337D8 calls Player_TouchFloor on an airborne latch");
        assertEquals(0x00C5, sonic.getCentreY() & 0xFFFF,
                "Player_TouchFloor subtracts the rolling-to-standing radius delta from y_pos");
    }

    @Test
    void stalePoleRiderStateDoesNotOverwriteCurrentInteractPole() {
        CnzBarberPoleObjectInstance oldPole = new CnzBarberPoleObjectInstance(
                new ObjectSpawn(0x0100, 0x0100, Sonic3kObjectIds.CNZ_BARBER_POLE, 0, 0, false, 0));
        CnzBarberPoleObjectInstance currentPole = new CnzBarberPoleObjectInstance(
                new ObjectSpawn(0x0180, 0x0100, Sonic3kObjectIds.CNZ_BARBER_POLE, 0, 0, false, 0));
        oldPole.setServices(new TestObjectServices());

        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 0x0100);
        player.setCentreY((short) 0x00C9);
        oldPole.update(0, player);
        assertEquals(oldPole, player.getLatchedSolidObjectInstance());

        player.setLatchedSolidObject(Sonic3kObjectIds.CNZ_BARBER_POLE, currentPole);
        player.setCentreX((short) 0x0200);
        player.setCentreY((short) 0x0200);
        player.setXSpeed((short) 0x0400);
        player.setGSpeed((short) 0x0400);

        oldPole.update(1, player);

        assertEquals(0x0200, player.getCentreX() & 0xFFFF);
        assertEquals(0x0200, player.getCentreY() & 0xFFFF);
        assertEquals(currentPole, player.getLatchedSolidObjectInstance());
    }

    @Test
    void mirroredTrackWrapsAsPackedRomLongWithoutReleasingRider() throws Exception {
        CnzBarberPoleObjectInstance pole = new CnzBarberPoleObjectInstance(
                new ObjectSpawn(0x3290, 0x0810, Sonic3kObjectIds.CNZ_BARBER_POLE, 1, 0, false, 0));
        pole.setServices(new TestObjectServices());
        TestPlayableSprite sonic = new TestPlayableSprite();
        sonic.setCentreX((short) 0x3290);
        sonic.setCentreY((short) 0x07D5);
        pole.update(0, sonic);
        assertTrue(sonic.isOnObject());

        var ridersField = CnzBarberPoleObjectInstance.class.getDeclaredField("riders");
        ridersField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<AbstractPlayableSprite, Object> riders =
                (Map<AbstractPlayableSprite, Object>) ridersField.get(pole);
        Object state = riders.get(sonic);
        var trackField = state.getClass().getDeclaredField("trackFixed");
        trackField.setAccessible(true);
        trackField.setLong(state, 0xFFFF_5300L);
        var innerField = state.getClass().getDeclaredField("innerTrack");
        innerField.setAccessible(true);
        innerField.setBoolean(state, true);
        sonic.setXSpeed((short) 0x0100);
        sonic.setGSpeed((short) 0x0200);

        pole.update(1, sonic);

        assertTrue(sonic.isOnObject(),
                "loc_33700 add.l wraps the track from $FFFF to $0000 before the $A0 range check");
        assertEquals(0x0000_1300L, trackField.getLong(state));
    }

    @Test
    void playerQueryFailureIsNotSwallowed() {
        CnzBarberPoleObjectInstance pole = new CnzBarberPoleObjectInstance(
                new ObjectSpawn(0x0100, 0x0100, Sonic3kObjectIds.CNZ_BARBER_POLE, 0, 0, false, 0));
        pole.setServices(new ThrowingPlayerQueryServices());

        assertThrows(IllegalStateException.class, () -> pole.update(0, new TestPlayableSprite()));
    }

    private static final class QueryOnlyPlayerServices extends TestObjectServices {
        private final PlayableEntity main;
        private final List<? extends PlayableEntity> queriedSidekicks;

        private QueryOnlyPlayerServices(PlayableEntity main, List<? extends PlayableEntity> queriedSidekicks) {
            this.main = main;
            this.queriedSidekicks = List.copyOf(queriedSidekicks);
        }

        @Override
        public ObjectPlayerQuery playerQuery() {
            return new ObjectPlayerQuery(() -> main, () -> queriedSidekicks);
        }

        @Override
        public List<PlayableEntity> sidekicks() {
            return List.of();
        }
    }

    private static final class ThrowingPlayerQueryServices extends TestObjectServices {
        @Override
        public ObjectPlayerQuery playerQuery() {
            throw new IllegalStateException("query unavailable");
        }
    }
}
