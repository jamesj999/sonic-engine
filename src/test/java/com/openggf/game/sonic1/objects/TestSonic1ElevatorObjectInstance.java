package com.openggf.game.sonic1.objects;

import com.openggf.game.solid.ContactKind;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.PlayerStandingState;
import com.openggf.game.solid.PostContactState;
import com.openggf.game.solid.PreContactState;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestSonic1ElevatorObjectInstance {

    @Test
    void standingCheckpointPromotesWaitingElevatorIntoActionImmediately() throws Exception {
        TestPlayableSprite player = new TestPlayableSprite();
        ProbeElevator elevator =
                new ProbeElevator(new ObjectSpawn(0x1358, 0x0268, 0x59, 0x00, 0, false, 0));
        elevator.setCheckpointBatch(standingBatch(elevator, player));

        elevator.update(0, player);

        assertEquals(4, getPrivateInt(elevator, "routine"),
                "A standing PlatformObject checkpoint should promote the elevator into Elev_Action");
        assertEquals(2, getPrivateInt(elevator, "actionType"),
                "Type 1 should increment to type 2 in the same update once the player stands on it");
    }

    @Test
    void nonStandingCheckpointKeepsWaitingElevatorArmedButIdle() throws Exception {
        TestPlayableSprite player = new TestPlayableSprite();
        ProbeElevator elevator =
                new ProbeElevator(new ObjectSpawn(0x1358, 0x0268, 0x59, 0x00, 0, false, 0));
        elevator.setCheckpointBatch(noContactBatch(elevator, player));

        elevator.update(0, player);

        assertEquals(2, getPrivateInt(elevator, "routine"));
        assertEquals(1, getPrivateInt(elevator, "actionType"));
    }

    private static int getPrivateInt(Object instance, String fieldName) throws Exception {
        Field field = Sonic1ElevatorObjectInstance.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(instance);
    }

    private static SolidCheckpointBatch standingBatch(
            Sonic1ElevatorObjectInstance elevator,
            TestPlayableSprite player) {
        PlayerSolidContactResult result = new PlayerSolidContactResult(
                ContactKind.TOP,
                true,
                false,
                false,
                false,
                PreContactState.ZERO,
                new PostContactState((short) 0, (short) 0, false, true, false),
                0);
        return new SolidCheckpointBatch(elevator, Map.of(player, result));
    }

    private static SolidCheckpointBatch noContactBatch(
            Sonic1ElevatorObjectInstance elevator,
            TestPlayableSprite player) {
        PlayerSolidContactResult result = PlayerSolidContactResult.noContact(
                PlayerStandingState.NONE,
                PreContactState.ZERO,
                PostContactState.ZERO);
        return new SolidCheckpointBatch(elevator, Map.of(player, result));
    }

    private static final class ProbeElevator extends Sonic1ElevatorObjectInstance {
        private SolidCheckpointBatch checkpointBatch = new SolidCheckpointBatch(this, Map.of());

        private ProbeElevator(ObjectSpawn spawn) {
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
}
