package com.openggf.game.sonic3k.objects;

import com.openggf.game.DamageCause;
import com.openggf.game.OscillationManager;
import com.openggf.game.sonic1.objects.TestPlayableSprite;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TestMGZMovingSpikePlatformObjectInstance {

    @BeforeEach
    void resetOscillation() {
        OscillationManager.reset();
    }

    @Test
    void updateReadsGlobalOscillationWithoutAdvancingIt() {
        OscillationManager.update(12);
        byte[] beforeObjectSlot = OscillationManager.snapshotRomFormatBytes();
        MGZMovingSpikePlatformObjectInstance platform =
                new MGZMovingSpikePlatformObjectInstance(
                        new ObjectSpawn(0x2000, 0x0600, 0x56, 0, 0, false, 0));

        platform.update(99, null);

        assertArrayEquals(beforeObjectSlot, OscillationManager.snapshotRomFormatBytes(),
                "Obj_MGZMovingSpikePlatform only reads Oscillating_table; "
                        + "OscillateNumDo runs once at the LevelLoop tail");
        assertEquals(0x0600 + OscillationManager.getByte(0x10), platform.getY(),
                "The platform still applies the current Oscillating_table+$12 byte");
    }

    @Test
    void balanceUsesNativeWidthPixelsRatherThanFullSolidPadding() {
        MGZMovingSpikePlatformObjectInstance platform =
                new MGZMovingSpikePlatformObjectInstance(
                        new ObjectSpawn(0x2000, 0x0600, 0x56, 0, 0, false, 0));

        assertEquals(0x18, platform.getBalanceWidthPixels(),
                "player balance reads the object's $18 width_pixels byte");
        assertEquals(0x23, platform.getSolidParams().halfWidth(),
                "SolidObjectFull independently adds the native $B side padding");
    }

    @Test
    void outOfRangeCheckUsesSavedOriginRatherThanMovingPosition() {
        MGZMovingSpikePlatformObjectInstance platform =
                new MGZMovingSpikePlatformObjectInstance(
                        new ObjectSpawn(0x2000, 0x0600, 0x56, 0, 0, false, 0));

        for (int i = 0; i < 0x30; i++) {
            platform.update(i, null);
        }

        assertEquals(0x2030, platform.getX());
        assertEquals(0x2000, platform.getOutOfRangeReferenceX(),
                "Sprite_OnScreen_Test2 reads the saved $30 origin");
    }

    @Test
    void spikeHurtRewindsFullFixedPointYBeforeHurtCharacter() {
        MGZMovingSpikePlatformObjectInstance platform =
                new MGZMovingSpikePlatformObjectInstance(
                        new ObjectSpawn(0x2108, 0x08C5, 0x56, 0, 0, false, 0));
        RecordingPlayer player = new RecordingPlayer();
        player.setCentreY((short) 0x08A1);
        player.setSubpixelRaw(0, 0x8900);
        player.setYSpeed((short) -0x031D);

        platform.onSolidContact(player,
                new SolidContact(false, true, false, false, false), 9838);

        assertEquals(0x08A4, player.hurtY);
        assertEquals(0xA600, player.hurtYSub,
                "sub_24280 subtracts y_vel<<8 from the complete y_pos longword");
    }

    private static final class RecordingPlayer extends TestPlayableSprite {
        private int hurtY = -1;
        private int hurtYSub = -1;

        @Override
        public boolean applyHurtOrDeath(int sourceX, DamageCause cause, boolean hadRings) {
            hurtY = getCentreY() & 0xFFFF;
            hurtYSub = getYSubpixelRaw();
            return true;
        }
    }
}
