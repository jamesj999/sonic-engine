package com.openggf.game.sonic1.objects;

import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidRoutineProfile;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestSonic1PushBlockObjectInstance {

    @Test
    public void rawWalkPushReleaseFallbackIsScopedToLavaMotion() throws Exception {
        Sonic1PushBlockObjectInstance block = new Sonic1PushBlockObjectInstance(
                new ObjectSpawn(0x100, 0x100, Sonic1ObjectIds.PUSH_BLOCK, 0, 0, false, 0));

        assertFalse(block.preservesNativePushLatchAcrossSkippedSolidCheckpoints(),
                "An ordinary state-0 Solid_ChkCollision has a current checkpoint");

        var inMotion = Sonic1PushBlockObjectInstance.class.getDeclaredField("inMotion");
        inMotion.setAccessible(true);
        inMotion.setBoolean(block, true);

        assertTrue(block.preservesNativePushLatchAcrossSkippedSolidCheckpoints(),
                "PushB_OnLava retains the native motion-mode latch");
    }

    @Test
    public void nativeStandingStateUsesGlobalOnObjectBitBeforeReturningToCollision() throws Exception {
        ProbePushBlock block = new ProbePushBlock();
        block.setServices(new StubObjectServices());
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 0x100);
        player.setCentreY((short) 0x100);
        player.setOnObject(true);

        block.nextCheckpointStanding = true;
        block.ridingThisBlock = true;
        block.update(0, player);

        assertEquals(2, solidState(block),
                "Solid_Landed must promote Obj33's native obSolid state to 2");
        assertEquals(1, block.checkpointCalls);

        block.ridingThisBlock = false;
        block.nextCheckpointStanding = false;
        block.update(1, player);

        assertEquals(2, solidState(block),
                "Obj33 must retain obSolid=2 while another object owns Sonic's global Status_OnObj");
        assertEquals(1, block.checkpointCalls,
                "State 2 must use ExitPlatform/MvSonicOnPtfm without a new Solid_ChkCollision");

        player.setOnObject(false);
        block.ridingThisBlock = false;
        block.nextCheckpointStanding = false;
        block.update(2, player);

        assertEquals(0, solidState(block),
                "State 2 must consume the cleared Status_OnObj and return to state 0");
        assertEquals(1, block.checkpointCalls,
                "ExitPlatform state must return without a same-slot Solid_ChkCollision fallback");
        assertTrue(block.preservesNativePushLatchAcrossSkippedSolidCheckpoints(),
                "The skipped state-2 slot must retain Obj33's push-release owner");

        block.update(3, player);

        assertEquals(2, block.checkpointCalls);
        assertFalse(block.preservesNativePushLatchAcrossSkippedSolidCheckpoints(),
                "The next state-0 checkpoint consumes the retained release owner");
    }

    @Test
    public void rowSubtypeUsesObjectLevelHighPriority() {
        Sonic1PushBlockObjectInstance single = new Sonic1PushBlockObjectInstance(
                new ObjectSpawn(0x100, 0x100, Sonic1ObjectIds.PUSH_BLOCK, 0, 0, false, 0));
        Sonic1PushBlockObjectInstance row = new Sonic1PushBlockObjectInstance(
                new ObjectSpawn(0x100, 0x100, Sonic1ObjectIds.PUSH_BLOCK, 1, 0, false, 0));

        assertFalse(single.isHighPriority());
        assertTrue(row.isHighPriority());
    }

    @Test
    public void pushBlockSolidRoutineKeepsRightEdgeInclusive() {
        Sonic1PushBlockObjectInstance block = new Sonic1PushBlockObjectInstance(
                new ObjectSpawn(0x100, 0x100, Sonic1ObjectIds.PUSH_BLOCK, 0, 0, false, 0));

        SolidRoutineProfile profile = block.getSolidRoutineProfile();

        assertTrue(profile.inclusiveRightEdge(),
                "S1 Solid_ChkEnter uses BHI for the right-edge rejection, so exact edge contact is solid");
    }

    private static int solidState(Sonic1PushBlockObjectInstance block) throws ReflectiveOperationException {
        Field field = Sonic1PushBlockObjectInstance.class.getDeclaredField("solidState");
        field.setAccessible(true);
        return field.getInt(block);
    }

    private static final class ProbePushBlock extends Sonic1PushBlockObjectInstance {
        private int checkpointCalls;
        private boolean nextCheckpointStanding;
        private boolean ridingThisBlock;

        private ProbePushBlock() {
            super(new ObjectSpawn(0x100, 0x100, Sonic1ObjectIds.PUSH_BLOCK, 0, 0, false, 0));
        }

        @Override
        protected SolidCheckpointBatch checkpointAll() {
            checkpointCalls++;
            return new SolidCheckpointBatch(this, Map.of());
        }

        @Override
        protected boolean hasStandingContact(SolidCheckpointBatch batch) {
            return nextCheckpointStanding;
        }

        @Override
        protected boolean isPlayerRidingThisBlock() {
            return ridingThisBlock;
        }
    }
}
