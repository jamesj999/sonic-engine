package com.openggf.game;

import com.openggf.control.InputActionMasks;
import com.openggf.control.LogicalInputSnapshot;
import com.openggf.control.PlayerInputState;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.RecordedInputSnapshots;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestSpecialStageInputMapper {

    @Test
    void mapsHeldAndPressedBitsFromLogicalSnapshot() {
        LogicalInputSnapshot logical = LogicalInputSnapshot.ofPlayers(
                PlayerInputState.of(
                        AbstractPlayableSprite.INPUT_UP | AbstractPlayableSprite.INPUT_LEFT,
                        AbstractPlayableSprite.INPUT_LEFT,
                        InputActionMasks.ACTION_A | InputActionMasks.ACTION_C,
                        InputActionMasks.ACTION_C,
                        true,
                        true),
                PlayerInputState.of(
                        AbstractPlayableSprite.INPUT_DOWN | AbstractPlayableSprite.INPUT_RIGHT,
                        0,
                        InputActionMasks.ACTION_B,
                        0,
                        true,
                        false));

        SpecialStageInputMapper.MappedInput mapped = SpecialStageInputMapper.map(logical);

        assertEquals(0x01 | 0x04 | 0x40 | 0x20 | 0x80, mapped.p1Held());
        assertEquals(0x04 | 0x20 | 0x80, mapped.p1Pressed());
        assertEquals(0x02 | 0x08 | 0x10 | 0x80, mapped.p2Held());
        assertEquals(mapped.p2Held(), mapped.p2Logical());
    }

    @Test
    void replayedRowsProduceSameStartHeldAndPressedSemantics() {
        Bk2FrameInput previous = new Bk2FrameInput(0, 0, 0, false, 0, 0, false, "previous");
        Bk2FrameInput current = new Bk2FrameInput(
                1,
                AbstractPlayableSprite.INPUT_RIGHT,
                InputActionMasks.ACTION_A,
                true,
                0,
                0,
                false,
                "current");

        SpecialStageInputMapper.MappedInput mapped =
                SpecialStageInputMapper.map(RecordedInputSnapshots.fromBk2(current, previous));

        assertEquals(0x08 | 0x40 | 0x80, mapped.p1Held());
        assertEquals(0x08 | 0x40 | 0x80, mapped.p1Pressed());
    }
}
