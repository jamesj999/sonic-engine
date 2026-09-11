package com.openggf.game.sonic1.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.game.solid.ContactKind;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.level.objects.ObjectPlayerQuery;
import org.junit.jupiter.api.Test;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestSonic1SpikeObjectInstance {

    @Test
    public void movingSpikesKeepSolidLatchOnLiveInstance() {
        Sonic1SpikeObjectInstance spikes = new Sonic1SpikeObjectInstance(
                new ObjectSpawn(0x100, 0x100, Sonic1ObjectIds.SPIKES, 1, 0, false, 0));

        assertTrue(spikes.usesInstanceSolidStateLatchKey(),
                "Obj36 status bits belong to its live SST while coordinates retract");
    }

    @Test
    public void spikesHurtDuringInvulnerabilityFrames() {
        Sonic1SpikeObjectInstance spikes = new Sonic1SpikeObjectInstance(
                new ObjectSpawn(160, 112, 0x36, 0x00, 0, false, 0));
        SpikeTestPlayableSprite player = new SpikeTestPlayableSprite();
        player.setCentreX((short) 160);
        player.setCentreY((short) 80);
        player.setInvulnerableFrames(120);
        player.setHurt(false);
        player.setInvincibleFrames(0);

        spikes.onSolidContact(player, new SolidContact(true, false, false, false, false), 1);

        assertTrue(player.hurtOrDeathIgnoringIFramesCalled);
        assertEquals(160, player.lastSourceX);
        assertTrue(player.lastSpikeHit);
        assertFalse(player.lastHadRings);
    }

    @Test
    public void spikesDoNotHurtWhenInvincibilityIsActive() {
        Sonic1SpikeObjectInstance spikes = new Sonic1SpikeObjectInstance(
                new ObjectSpawn(160, 112, 0x36, 0x00, 0, false, 0));
        SpikeTestPlayableSprite player = new SpikeTestPlayableSprite();
        player.setCentreX((short) 160);
        player.setCentreY((short) 80);
        player.setInvincibleFrames(600);

        spikes.onSolidContact(player, new SolidContact(true, false, false, false, false), 1);

        assertFalse(player.hurtOrDeathIgnoringIFramesCalled);
        assertFalse(player.hurtIgnoringIFramesCalled);
    }

    @Test
    public void spikesDisableStickyContactBuffer() {
        Sonic1SpikeObjectInstance spikes = new Sonic1SpikeObjectInstance(
                new ObjectSpawn(160, 112, 0x36, 0x01, 0, false, 0));
        assertFalse(spikes.usesStickyContactBuffer());
        assertTrue(spikes.getSolidRoutineProfile().inclusiveRightEdge());
        assertTrue(spikes.usesInstanceSolidStateLatchKey(),
                "Moving Obj36 variants retain SolidObject bits in their live SST");
    }

    @Test
    public void spikesDoNotHurtWhenAlreadyHurt() {
        // Sideways spike (subtype 0x10) â€” ROM check: cmpi.b #4,obRoutine(a0) / bhs.s loc_CF20
        Sonic1SpikeObjectInstance spikes = new Sonic1SpikeObjectInstance(
                new ObjectSpawn(160, 112, 0x36, 0x10, 0, false, 0));
        SpikeTestPlayableSprite player = new SpikeTestPlayableSprite();
        player.setCentreX((short) 160);
        player.setCentreY((short) 112);
        player.setHurt(true);

        spikes.onSolidContact(player, new SolidContact(false, true, false, false, false), 1);

        assertFalse(player.hurtOrDeathIgnoringIFramesCalled);
        assertFalse(player.hurtIgnoringIFramesCalled);
    }

    @Test
    public void spikesDoNotHurtWhenDead() {
        // Sideways spike (subtype 0x10) â€” ROM check: cmpi.b #4,obRoutine(a0) / bhs.s loc_CF20
        Sonic1SpikeObjectInstance spikes = new Sonic1SpikeObjectInstance(
                new ObjectSpawn(160, 112, 0x36, 0x10, 0, false, 0));
        SpikeTestPlayableSprite player = new SpikeTestPlayableSprite();
        player.setCentreX((short) 160);
        player.setCentreY((short) 112);
        player.setDead(true);

        spikes.onSolidContact(player, new SolidContact(false, true, false, false, false), 1);

        assertFalse(player.hurtOrDeathIgnoringIFramesCalled);
        assertFalse(player.hurtIgnoringIFramesCalled);
    }

    @Test
    public void ceilingSpikeSetsRomKnockbackVelocity() {
        // Upright spike (subtype 0x00) hit from below (touchBottom = ceiling contact).
        // ROM: HurtSonic always sets ySpeed = -$400 (upward), regardless of contact direction.
        Sonic1SpikeObjectInstance spikes = new Sonic1SpikeObjectInstance(
                new ObjectSpawn(160, 112, 0x36, 0x00, 0, false, 0));
        SpikeTestPlayableSprite player = new SpikeTestPlayableSprite();
        player.setCentreX((short) 160);
        player.setCentreY((short) 130); // below spike
        player.setYSpeed((short) -0x400); // jumping upward

        // touchBottom = true: Sonic hits the bottom of the spike from below (ceiling contact)
        spikes.onSolidContact(player, new SolidContact(false, false, true, false, false), 1);

        assertTrue(player.hurtOrDeathIgnoringIFramesCalled, "Spike should have hurt player");
        assertTrue(player.getYSpeed() < 0, "ySpeed should be negative (upward) matching ROM HurtSonic, was: " + player.getYSpeed());
    }

    @Test
    public void floorSpikeSetsRomKnockbackVelocity() {
        // Upright spike (subtype 0x00) hit from above (standing contact).
        // ROM: HurtSonic always sets ySpeed = -$400 (upward).
        Sonic1SpikeObjectInstance spikes = new Sonic1SpikeObjectInstance(
                new ObjectSpawn(160, 112, 0x36, 0x00, 0, false, 0));
        SpikeTestPlayableSprite player = new SpikeTestPlayableSprite();
        player.setCentreX((short) 160);
        player.setCentreY((short) 80); // above spike
        player.setYSpeed((short) 0); // descending onto spike

        // standing = true: Sonic landed on top of the spike
        spikes.onSolidContact(player, new SolidContact(true, false, false, false, false), 1);

        assertTrue(player.hurtOrDeathIgnoringIFramesCalled, "Spike should have hurt player");
        assertTrue(player.getYSpeed() < 0, "ySpeed should be negative (upward) matching ROM HurtSonic, was: " + player.getYSpeed());
    }

    @Test
    public void uprightSpikesIgnoreStandingContactOutsideRomLandingWindow() {
        Sonic1SpikeObjectInstance spikes = new Sonic1SpikeObjectInstance(
                new ObjectSpawn(160, 112, 0x36, 0x00, 0, false, 0));
        SpikeTestPlayableSprite player = new SpikeTestPlayableSprite();
        player.setCentreX((short) 160);
        // relY = 20 (outside Solid_Landed threshold < 16)
        player.setCentreY((short) 93);

        spikes.onSolidContact(player, new SolidContact(true, false, false, false, false), 1);

        assertFalse(player.hurtOrDeathIgnoringIFramesCalled);
        assertFalse(player.hurtIgnoringIFramesCalled);
    }

    @Test
    public void updateAppliesCheckpointContactToPlayersFromObjectPlayerQuery() {
        SpikeTestPlayableSprite main = new SpikeTestPlayableSprite();
        SpikeTestPlayableSprite sidekick = new SpikeTestPlayableSprite();
        sidekick.setCpuControlled(true);
        sidekick.setCentreX((short) 160);
        sidekick.setCentreY((short) 112);

        TestableSonic1SpikeObjectInstance spikes = new TestableSonic1SpikeObjectInstance(
                new ObjectSpawn(160, 112, 0x36, 0x10, 0, false, 0));
        spikes.setCheckpointBatch(new SolidCheckpointBatch(spikes, Map.of(
                main, noContact(),
                sidekick, sideContact()
        )));
        spikes.setServices(new com.openggf.level.objects.TestObjectServices() {
            private final ObjectPlayerQuery playerQuery = new ObjectPlayerQuery(
                    () -> main,
                    () -> List.of(sidekick));

            @Override
            public ObjectPlayerQuery playerQuery() {
                return playerQuery;
            }
        });

        spikes.update(1, main);

        assertTrue(sidekick.hurtIgnoringIFramesCalled);
        assertEquals(160, sidekick.lastSourceX);
        assertTrue(sidekick.lastSpikeHit);
    }

    private static PlayerSolidContactResult noContact() {
        return new PlayerSolidContactResult(ContactKind.NONE, false, false, false, false,
                null, null, 0);
    }

    private static PlayerSolidContactResult sideContact() {
        return new PlayerSolidContactResult(ContactKind.SIDE, false, false, true, false,
                null, null, 0);
    }

    private static final class TestableSonic1SpikeObjectInstance extends Sonic1SpikeObjectInstance {
        private SolidCheckpointBatch checkpointBatch;

        private TestableSonic1SpikeObjectInstance(ObjectSpawn spawn) {
            super(spawn);
        }

        private void setCheckpointBatch(SolidCheckpointBatch checkpointBatch) {
            this.checkpointBatch = checkpointBatch;
        }

        @Override
        protected SolidCheckpointBatch checkpointAll() {
            return checkpointBatch;
        }
    }

    private static final class SpikeTestPlayableSprite extends TestPlayableSprite {
        private boolean hurtOrDeathIgnoringIFramesCalled;
        private boolean hurtIgnoringIFramesCalled;
        private int lastSourceX;
        private boolean lastSpikeHit;
        private boolean lastHadRings;

        @Override
        public boolean applyHurtIgnoringIFrames(int sourceX, boolean spikeHit) {
            hurtIgnoringIFramesCalled = true;
            lastSourceX = sourceX;
            lastSpikeHit = spikeHit;
            return true;
        }

        @Override
        public boolean applyHurtOrDeathIgnoringIFrames(int sourceX, boolean spikeHit, boolean hadRings) {
            hurtOrDeathIgnoringIFramesCalled = true;
            lastSourceX = sourceX;
            lastSpikeHit = spikeHit;
            lastHadRings = hadRings;
            // Simulate real applyHurt behavior: set hurt flag and knockback velocity
            setHurt(true);
            int dir = (getCentreX() >= sourceX) ? 1 : -1;
            setXSpeed((short) (0x200 * dir));
            setYSpeed((short) -0x400);
            return true;
        }
    }
}
