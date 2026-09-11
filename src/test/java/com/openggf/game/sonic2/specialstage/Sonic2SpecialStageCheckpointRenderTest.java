package com.openggf.game.sonic2.specialstage;

import com.openggf.graphics.GraphicsManager;
import com.openggf.level.PatternDesc;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class Sonic2SpecialStageCheckpointRenderTest {
    @Test
    void checkpointWingsStayFixedWhileHandshakeMoves() {
        Sonic2SpecialStageCheckpoint checkpoint = messageCheckpoint();
        RecordingGraphics graphics = new RecordingGraphics();
        Sonic2SpecialStageRenderer renderer = new Sonic2SpecialStageRenderer(graphics);
        renderer.setIntroPatternBases(0, 0, 0x1000);
        renderer.setCheckpoint(checkpoint);

        renderer.renderCheckpointUI();
        List<Call> firstFrame = List.copyOf(graphics.calls);

        checkpoint.update(false);
        graphics.calls.clear();
        renderer.renderCheckpointUI();
        List<Call> secondFrame = List.copyOf(graphics.calls);

        List<Integer> firstWingY = yCoordinates(firstFrame, 0x1A, 0x39);
        List<Integer> secondWingY = yCoordinates(secondFrame, 0x1A, 0x39);
        List<Integer> firstHandY = yCoordinates(firstFrame, 0x3A, 0x59);
        List<Integer> secondHandY = yCoordinates(secondFrame, 0x3A, 0x59);

        assertEquals(firstWingY, secondWingY,
                "Obj5A wings are an independent peer sprite and must not follow the handshake peer");
        assertNotEquals(firstHandY, secondHandY,
                "Obj5A handshake peer must continue its ROM bobbing movement");
    }

    @Test
    void checkpointRenderRestoresWingPositionAndIsDeterministicAfterRewind() throws Exception {
        Sonic2SpecialStageCheckpoint checkpoint = messageCheckpoint();
        RecordingGraphics graphics = new RecordingGraphics();
        Sonic2SpecialStageRenderer renderer = new Sonic2SpecialStageRenderer(graphics);
        renderer.setIntroPatternBases(0, 0, 0x1000);
        renderer.setCheckpoint(checkpoint);

        Sonic2SpecialStageSnapshot.CheckpointSnapshot snapshot =
                checkpoint.captureRewindSnapshot();

        renderer.renderCheckpointUI();
        List<Call> firstFrame = List.copyOf(graphics.calls);
        graphics.calls.clear();
        renderer.renderCheckpointUI();
        assertEquals(firstFrame, graphics.calls,
                "re-rendering an unchanged checkpoint frame must be deterministic");

        set(checkpoint, "handTargetY", 99);
        set(checkpoint, "handY", 99);
        checkpoint.restoreRewindSnapshot(snapshot);

        graphics.calls.clear();
        renderer.renderCheckpointUI();
        assertEquals(firstFrame, graphics.calls,
                "rewind must restore the separate wings position as well as the hand");
        assertEquals(snapshot.handTargetY(), checkpoint.getWingsY());
        assertEquals(snapshot.handY(), checkpoint.getHandY());
    }

    private static Sonic2SpecialStageCheckpoint messageCheckpoint() {
        Sonic2SpecialStageCheckpoint checkpoint = new Sonic2SpecialStageCheckpoint();
        checkpoint.beginCheckpoint(1, 1, 1, false);
        for (int frame = 0; frame < 20
                && checkpoint.getPhase() == Sonic2SpecialStageCheckpoint.MessagePhase.RAINBOW_RINGS;
                frame++) {
            checkpoint.update(true);
        }
        assertEquals(Sonic2SpecialStageCheckpoint.MessagePhase.MESSAGE_DISPLAY,
                checkpoint.getPhase(), "checkpoint must reach the handshake message phase");
        assertEquals(0x48, checkpoint.getWingsY(),
                "Obj5A_CreateCheckpointWingedHand places both peer sprites at y=$48");
        return checkpoint;
    }

    private static List<Integer> yCoordinates(List<Call> calls, int minOffset, int maxOffset) {
        return calls.stream()
                .filter(call -> {
                    int offset = call.patternId - 0x1000;
                    return offset >= minOffset && offset <= maxOffset;
                })
                .map(Call::y)
                .toList();
    }

    private static void set(Object target, String fieldName, int value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private record Call(int patternId, int x, int y) {
    }

    private static final class RecordingGraphics extends GraphicsManager {
        private final List<Call> calls = new ArrayList<>();

        @Override
        public void beginPatternBatch() {
        }

        @Override
        public void flushPatternBatch() {
        }

        @Override
        public void renderPatternWithId(int patternId, PatternDesc desc, int x, int y) {
            calls.add(new Call(patternId, x, y));
        }
    }
}
