package com.openggf.game.sonic3k.objects;

import com.openggf.game.DamageCause;
import com.openggf.game.sonic1.objects.TestPlayableSprite;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.TestObjectServices;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestSonic3kInvisibleHurtBlockHObjectInstance {

    @Test
    public void subtypeDecodesSolidDimensionsLikeInvisibleBlock() {
        Sonic3kInvisibleHurtBlockHObjectInstance block = new Sonic3kInvisibleHurtBlockHObjectInstance(
                new ObjectSpawn(0x100, 0x180, 0x6A, 0xF1, 0, false, 0));

        SolidObjectParams params = block.getSolidParams();

        assertEquals(0x80 + 0x0B, params.halfWidth());
        assertEquals(0x10, params.airHalfHeight());
        assertEquals(0x11, params.groundHalfHeight());
    }

    @Test
    public void defaultPlacementHurtsOnStandingContact() {
        Sonic3kInvisibleHurtBlockHObjectInstance block = new Sonic3kInvisibleHurtBlockHObjectInstance(
                new ObjectSpawn(0x100, 0x180, 0x6A, 0x11, 0, false, 0));
        RecordingPlayer player = new RecordingPlayer();

        block.onSolidContact(player, new SolidContact(true, false, false, true, false), 7);

        assertTrue(player.hurtOrDeathCalled);
        assertEquals(0x100, player.lastSourceX);
        assertEquals(DamageCause.NORMAL, player.lastCause);
        assertFalse(player.lastHadRings);
    }

    @Test
    public void xFlipHurtsOnSideContactOnly() {
        Sonic3kInvisibleHurtBlockHObjectInstance block = new Sonic3kInvisibleHurtBlockHObjectInstance(
                new ObjectSpawn(0x100, 0x180, 0x6A, 0x11, 0x01, false, 0));
        RecordingPlayer player = new RecordingPlayer();

        block.onSolidContact(player, new SolidContact(false, true, false, false, false, 6, true), 7);

        assertTrue(player.hurtOrDeathCalled);
    }

    @Test
    public void yFlipHurtsOnBottomContactOnly() {
        Sonic3kInvisibleHurtBlockHObjectInstance block = new Sonic3kInvisibleHurtBlockHObjectInstance(
                new ObjectSpawn(0x100, 0x180, 0x6A, 0x11, 0x02, false, 0));
        RecordingPlayer player = new RecordingPlayer();

        block.onSolidContact(player, new SolidContact(false, false, true, false, false), 7);

        assertTrue(player.hurtOrDeathCalled);
    }

    @Test
    public void inactiveFaceDoesNotHurt() {
        Sonic3kInvisibleHurtBlockHObjectInstance block = new Sonic3kInvisibleHurtBlockHObjectInstance(
                new ObjectSpawn(0x100, 0x180, 0x6A, 0x11, 0x01, false, 0));
        RecordingPlayer player = new RecordingPlayer();

        block.onSolidContact(player, new SolidContact(true, false, false, true, false), 7);

        assertFalse(player.hurtOrDeathCalled);
        assertFalse(player.hurtCalled);
    }

    @Test
    public void invulnerablePlayerIsIgnored() {
        Sonic3kInvisibleHurtBlockHObjectInstance block = new Sonic3kInvisibleHurtBlockHObjectInstance(
                new ObjectSpawn(0x100, 0x180, 0x6A, 0x11, 0, false, 0));
        RecordingPlayer player = new RecordingPlayer();
        player.setInvulnerableFrames(60);

        block.onSolidContact(player, new SolidContact(true, false, false, true, false), 7);

        assertFalse(player.hurtOrDeathCalled);
        assertFalse(player.hurtCalled);
    }

    @Test
    public void ringedPlayerUsesOrdinaryHurtCharacterLostRingOrdering() {
        Sonic3kInvisibleHurtBlockHObjectInstance block = new Sonic3kInvisibleHurtBlockHObjectInstance(
                new ObjectSpawn(0x100, 0x180, 0x6A, 0x11, 0, false, 0));
        RecordingServices services = new RecordingServices();
        block.setServices(services);
        RecordingPlayer player = new RecordingPlayer();
        player.setRingCount(42);

        block.onSolidContact(player, new SolidContact(true, false, false, true, false), 0x0C66);

        assertEquals(0, services.immediateLostRingSpawns,
                "sub_1F58C allocates Obj_Bouncing_Ring through HurtCharacter");
        assertEquals(1, services.delayedLostRingSpawns);
        assertFalse(services.deferredOwnerLostRingSpawn,
                "invisible hurt blocks use the same ring-clear timing as other HurtCharacter callers");
        assertTrue(player.hurtOrDeathCalled);
        assertTrue(player.lastHadRings);
    }

    @Test
    public void hurtRewindsPlayerYByCurrentYSpeedBeforeApplyingHurt() {
        Sonic3kInvisibleHurtBlockHObjectInstance block = new Sonic3kInvisibleHurtBlockHObjectInstance(
                new ObjectSpawn(0x100, 0x180, 0x6A, 0x11, 0, false, 0));
        RecordingPlayer player = new RecordingPlayer();
        int initialY = player.getCentreY();
        player.setYSpeed((short) -0x240);

        block.onSolidContact(player, new SolidContact(true, false, false, true, false), 7);

        assertEquals(initialY + 2, player.getCentreY());
        assertEquals(0x4000, player.getYSubpixelRaw());
        assertTrue(player.hurtOrDeathCalled);
    }

    private static final class RecordingPlayer extends TestPlayableSprite {
        private boolean hurtOrDeathCalled;
        private boolean hurtCalled;
        private int lastSourceX;
        private DamageCause lastCause;
        private boolean lastHadRings;
        private int ringCount;

        @Override
        public boolean hasShield() {
            return false;
        }

        @Override
        public int getRingCount() {
            return ringCount;
        }

        @Override
        public void setRingCount(int ringCount) {
            this.ringCount = ringCount;
        }

        @Override
        public boolean applyHurt(int sourceX) {
            hurtCalled = true;
            lastSourceX = sourceX;
            return true;
        }

        @Override
        public boolean applyHurtOrDeath(int sourceX, DamageCause cause, boolean hadRings) {
            hurtOrDeathCalled = true;
            lastSourceX = sourceX;
            lastCause = cause;
            lastHadRings = hadRings;
            return true;
        }
    }

    private static final class RecordingServices extends TestObjectServices {
        private int immediateLostRingSpawns;
        private int delayedLostRingSpawns;
        private int lastDelayedFrame;
        private boolean deferredOwnerLostRingSpawn;

        @Override
        public void spawnLostRings(com.openggf.game.PlayableEntity player, int frameCounter) {
            immediateLostRingSpawns++;
        }

        @Override
        public void spawnLostRingsAfterCurrentFrame(com.openggf.game.PlayableEntity player, int frameCounter) {
            delayedLostRingSpawns++;
            lastDelayedFrame = frameCounter;
        }

        @Override
        public void spawnLostRingsWithDeferredOwner(
                com.openggf.game.PlayableEntity player, int frameCounter) {
            delayedLostRingSpawns++;
            lastDelayedFrame = frameCounter;
            deferredOwnerLostRingSpawn = true;
        }
    }
}
