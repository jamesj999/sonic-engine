package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PlaceholderObjectInstance;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestUpdraftObjectInstance {
    private static final int UPDRAFT = 0x14;

    @AfterEach
    void resetCameraBounds() {
        AbstractObjectInstance.resetCameraBoundsForTests();
    }

    @Test
    void registryRoutesSklSlot14ToUpdraftInMhz() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);

        ObjectInstance updraft = registry.create(new ObjectSpawn(
                0x1800, 0x0600, UPDRAFT, 0, 0, false, 0));

        assertFalse(updraft instanceof PlaceholderObjectInstance,
                "SKL slot $14 is Obj_Updraft in MHZ and must not remain a placeholder");
        assertEquals("Updraft", updraft.getName());
    }

    @Test
    void playerInsideNormalAirflowIsLiftedAndReceivesRomState() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance updraft = registry.create(new ObjectSpawn(
                0x1800, 0x0600, UPDRAFT, 0, 0, false, 0));
        TestablePlayableSprite player = playerAt(0x1800, 0x0600);
        player.setRollingJump(true);
        player.setJumping(true);
        player.setDoubleJumpFlag(5);
        player.setYSpeed((short) 0x0300);
        player.setGSpeed((short) 0x0200);

        updraft.update(15, player);

        assertEquals(0x05FF, player.getCentreY() & 0xFFFF,
                "Obj_Updraft uses the ROM word lift curve; at d1=$40 it moves the player up one pixel");
        assertTrue(player.getAir(), "Obj_Updraft sets Status_InAir");
        assertFalse(player.getRollingJump(), "Obj_Updraft clears Status_RollJump");
        assertEquals(0, player.getYSpeed(), "Obj_Updraft zeroes y_vel");
        assertEquals(1, player.getGSpeed(), "Obj_Updraft writes ground_vel=1");
        assertEquals(0, player.getDoubleJumpFlag(), "Obj_Updraft cancels double-jump/shield action state");
        assertFalse(player.isJumping(), "Obj_Updraft clears jumping");
        assertEquals(1, player.getFlipAngle(), "positive subtype starts the flip sequence when flip_angle is clear");
        assertEquals(0, player.getAnimationId());
        assertEquals(0x7F, player.getFlipsRemaining());
        assertEquals(8, player.getFlipSpeed());
    }

    @Test
    void negativeSubtypeUsesAlternateUpdraftAnimationWithoutStartingFlip() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance updraft = registry.create(new ObjectSpawn(
                0x1800, 0x0600, UPDRAFT, 0x80, 0, false, 0));
        TestablePlayableSprite player = playerAt(0x1800, 0x0600);

        updraft.update(0, player);

        assertEquals(1, player.getGSpeed());
        assertEquals(0x0F, player.getAnimationId(),
                "subtype bit 7 follows the ROM alternate player animation path");
        assertEquals(0, player.getFlipAngle(),
                "negative subtype does not seed flip_angle/flips_remaining/flip_speed");
    }

    @Test
    void objectControlledParachuteIsLiftedWithCarriedPlayerOnRomSpecialPath() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance parachute = registry.create(new ObjectSpawn(
                0x1800, 0x0500, 0x12, 0, 0, false, 0));
        ObjectInstance updraft = registry.create(new ObjectSpawn(
                0x1800, 0x0525, UPDRAFT, 8, 0, false, 0));
        TestablePlayableSprite player = playerAt(0x1800, 0x0525);
        player.setYSpeed((short) 0x0200);

        parachute.update(0, player);
        ObjectManager objectManager = mock(ObjectManager.class);
        when(objectManager.getActiveObjects()).thenReturn(List.of(parachute, updraft));
        ((AbstractObjectInstance) updraft).setServices(new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public ObjectPlayerQuery playerQuery() {
                return new ObjectPlayerQuery(() -> player, List::of);
            }
        });

        updraft.update(1, player);

        assertEquals(0x0524, player.getCentreY() & 0xFFFF,
                "Obj_Updraft loc_3FC76 must lift an object-controlled player carried by loc_3F51C/loc_3F572");
        assertEquals(0x04FF, parachute.getY(),
                "loc_3FC8A adds the same lift delta to the parachute object's y_pos once via d2");
    }

    @Test
    void windQuietSfxUsesVIntLowByteIntervalWithoutFrameOffset() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        AbstractObjectInstance updraft = (AbstractObjectInstance) registry.create(new ObjectSpawn(
                0x1800, 0x0600, UPDRAFT, 0, 0, false, 0));
        RecordingServices services = new RecordingServices();
        updraft.setServices(services);
        TestablePlayableSprite player = playerAt(0x1800, 0x0600);

        updraft.update(0, player);
        updraft.update(13, player);
        updraft.update(16, player);

        assertEquals(2, services.windQuietCount,
                "Obj_Updraft masks (V_int_run_count+3).b, the low byte of the counter, so frames 0 and 16 play wind");
    }

    @Test
    void updraftDeletesRespawnablyWhenOutsideRomRangeWindow() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance updraft = registry.create(new ObjectSpawn(
                0x1800, 0x0600, UPDRAFT, 0, 0, false, 0));
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);

        updraft.update(0, null);

        assertTrue(updraft.isDestroyed(),
                "Obj_Updraft ends with Delete_Sprite_If_Not_In_Range when its spawn is outside the ROM X window");
        assertTrue(updraft.isDestroyedRespawnable(),
                "Delete_Sprite_If_Not_In_Range clears the respawn latch so the updraft can respawn later");
    }

    @Test
    void playerOutsideHorizontalWindowIsUnchanged() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance updraft = registry.create(new ObjectSpawn(
                0x1800, 0x0600, UPDRAFT, 0, 0, false, 0));
        TestablePlayableSprite player = playerAt(0x1880, 0x0600);
        player.setYSpeed((short) 0x0300);

        updraft.update(0, player);

        assertEquals(0x0600, player.getCentreY() & 0xFFFF);
        assertEquals(0x0300, player.getYSpeed());
        assertFalse(player.getAir());
    }

    @Test
    void horizontalAirflowWindowUsesWrappedWordDistance() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance updraft = registry.create(new ObjectSpawn(
                0x0020, 0x0600, UPDRAFT, 0, 0, false, 0));
        TestablePlayableSprite player = playerAt(0xFFE0, 0x0600);

        updraft.update(0, player);

        assertEquals(0x05FF, player.getCentreY() & 0xFFFF,
                "sub_3FBC4 does sub.w x_pos(a0),d0 then addi.w #$40; "
                        + "$FFE0-$0020+$40 wraps to $0000 and remains inside the $80 airflow window");
        assertTrue(player.getAir());
    }

    private static TestablePlayableSprite playerAt(int x, int y) {
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) x, (short) y);
        player.setAir(false);
        return player;
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

    private static final class RecordingServices extends StubObjectServices {
        private int windQuietCount;

        @Override
        public void playSfx(int soundId) {
            if (soundId == Sonic3kSfx.WIND_QUIET.id) {
                windQuietCount++;
            }
        }

        @Override
        public ObjectPlayerQuery playerQuery() {
            return new ObjectPlayerQuery(() -> null, List::of);
        }
    }
}
