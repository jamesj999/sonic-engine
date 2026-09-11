package com.openggf.game.sonic2.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.solid.ContactKind;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.PostContactState;
import com.openggf.game.solid.PreContactState;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.game.sonic2.ButtonVineTriggerManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidExecutionMode;
import com.openggf.level.objects.TestObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.IdentityHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestButtonObjectInstance {

    @AfterEach
    void resetTriggers() {
        ButtonVineTriggerManager.reset();
    }

    @Test
    void buttonUsesManualCheckpointForSameFrameTriggerWrites() {
        TestButton button = new TestButton(new ObjectSpawn(0x09F0, 0x01C0, 0x47, 0x03, 0, false, 0));
        button.setServices(new TestObjectServices());
        button.setStanding(true);

        assertEquals(SolidExecutionMode.MANUAL_CHECKPOINT, button.solidExecutionMode());

        button.update(1, null);

        assertTrue(ButtonVineTriggerManager.getTrigger(3),
                "Obj47 must write ButtonVine_Trigger from the same-frame SolidObject result");
    }

    @Test
    void buttonKeepsStandardSolidObjectExactRightEdgeContact() {
        ButtonObjectInstance button = new ButtonObjectInstance(
                new ObjectSpawn(0x1710, 0x0200, 0x47, 0, 0, false, 0));

        assertTrue(button.getSolidRoutineProfile().inclusiveRightEdge(),
                "Obj47's BHI gate accepts the exact +$1B edge");
    }

    private static final class TestButton extends ButtonObjectInstance {
        private boolean standing;

        private TestButton(ObjectSpawn spawn) {
            super(spawn);
        }

        void setStanding(boolean standing) {
            this.standing = standing;
        }

        @Override
        protected SolidCheckpointBatch checkpointAll() {
            Map<PlayableEntity, PlayerSolidContactResult> perPlayer = new IdentityHashMap<>();
            perPlayer.put(null, new PlayerSolidContactResult(
                    standing ? ContactKind.TOP : ContactKind.NONE,
                    standing,
                    false,
                    false,
                    false,
                    PreContactState.ZERO,
                    PostContactState.ZERO,
                    0));
            return new SolidCheckpointBatch(this, perPlayer);
        }

        @Override
        public boolean isWithinSolidContactBounds() {
            return true;
        }
    }
}
