package com.openggf.game.sonic2.specialstage;

import com.openggf.graphics.GraphicsManager;
import com.openggf.level.PatternDesc;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Sonic2SpecialStageHudLayoutTest {
    private static final int VIEWPORT_X = 32;
    private static final int HUD_BASE = 0x1000;
    private static final int MESSAGES_BASE = 0x2000;
    private static final int TAILS_TEXT_BASE = 0x3000;

    @Test
    void sonicOnlyUsesObj5eSonicLayoutAndObj87Digits() {
        RecordingGraphics graphics = render(new Sonic2SpecialStageRenderer.RingHudState(true, false, 12, 0, 12));

        assertDraw(graphics, HUD_BASE, VIEWPORT_X + 0xD4 - 0x60, 0x10);
        assertDigitXs(graphics, List.of(VIEWPORT_X + 0x9C, VIEWPORT_X + 0xA4));
    }

    @Test
    void tailsOnlyUsesOverseasObj5eFrameTwoAndObj87Position() {
        RecordingGraphics graphics = render(new Sonic2SpecialStageRenderer.RingHudState(false, true, 0, 4, 4));

        assertDraw(graphics, TAILS_TEXT_BASE, VIEWPORT_X + 0x38 + 0x38, 0x10);
        assertDigitXs(graphics, List.of(VIEWPORT_X + 0x9C));
    }

    @Test
    void overseasTailsFrameUsesDedicatedUnflippedPatternsInSourceOrder() {
        RecordingGraphics graphics = render(new Sonic2SpecialStageRenderer.RingHudState(false, true, 0, 4, 4));

        List<Call> tailsName = graphics.calls.stream()
                .filter(call -> call.patternId >= TAILS_TEXT_BASE
                        && call.patternId < TAILS_TEXT_BASE + 5)
                .toList();
        assertEquals(5, tailsName.size());
        assertEquals(List.of(0, 1, 2, 3, 4), tailsName.stream()
                .map(call -> call.patternId - TAILS_TEXT_BASE).toList());
        assertTrue(tailsName.stream().noneMatch(Call::hFlip));
    }

    @Test
    void teamUsesSonicTailsTotalLayoutAndIndependentCounts() {
        RecordingGraphics graphics = render(new Sonic2SpecialStageRenderer.RingHudState(true, true, 12, 4, 16));

        assertDraw(graphics, HUD_BASE, VIEWPORT_X + 0x80 - 0x60, 0x10);
        assertDraw(graphics, TAILS_TEXT_BASE, VIEWPORT_X + 0x80 + 0x38, 0x10);
        assertDraw(graphics, HUD_BASE + 0x26, VIEWPORT_X + 0x80 - 0x14, 0x10);
        assertDigitXs(graphics, List.of(
                VIEWPORT_X + 0x48, VIEWPORT_X + 0x50,
                VIEWPORT_X + 0xE0,
                VIEWPORT_X + 0x7C, VIEWPORT_X + 0x84));
        assertTopDigit(graphics, 1, VIEWPORT_X + 0x48);
        assertTopDigit(graphics, 2, VIEWPORT_X + 0x50);
        assertTopDigit(graphics, 4, VIEWPORT_X + 0xE0);
        assertTopDigit(graphics, 1, VIEWPORT_X + 0x7C);
        assertTopDigit(graphics, 6, VIEWPORT_X + 0x84);
    }

    @Test
    void totalDigitsAreCenteredAndSuppressLeadingZeroes() {
        assertTotalDigitXs(7, List.of(0x80));
        assertTotalDigitXs(16, List.of(0x7C, 0x84));
        assertTotalDigitXs(123, List.of(0x78, 0x80, 0x88));
    }

    private static void assertTotalDigitXs(int total, List<Integer> nativeXs) {
        RecordingGraphics graphics = render(new Sonic2SpecialStageRenderer.RingHudState(true, true, 0, 0, total));
        List<Integer> expected = nativeXs.stream().map(x -> x + VIEWPORT_X).toList();
        List<Integer> actual = graphics.calls.stream()
                .filter(call -> call.y == 0x18 && call.patternId >= HUD_BASE + 0x12
                        && call.patternId < HUD_BASE + 0x26)
                .map(Call::x).toList();
        assertEquals(expected, actual.subList(actual.size() - expected.size(), actual.size()));
    }

    private static RecordingGraphics render(Sonic2SpecialStageRenderer.RingHudState state) {
        RecordingGraphics graphics = new RecordingGraphics();
        Sonic2SpecialStageRenderer renderer = new Sonic2SpecialStageRenderer(graphics);
        renderer.setIntroPatternBases(HUD_BASE, 0, MESSAGES_BASE);
        renderer.setTailsTextPatternBase(TAILS_TEXT_BASE);
        renderer.renderRingCounter(state);
        return graphics;
    }

    private static void assertTopDigit(RecordingGraphics graphics, int digit, int x) {
        assertDraw(graphics, HUD_BASE + 0x12 + digit * 2, x, 0x18);
    }

    private static void assertDigitXs(RecordingGraphics graphics, List<Integer> expected) {
        List<Integer> actual = graphics.calls.stream()
                .filter(call -> call.y == 0x18 && call.patternId >= HUD_BASE + 0x12
                        && call.patternId < HUD_BASE + 0x26)
                .map(Call::x).toList();
        assertTrue(actual.containsAll(expected), () -> "missing digit Xs; calls=" + actual);
    }

    private static void assertDraw(RecordingGraphics graphics, int patternId, int x, int y) {
        assertTrue(graphics.calls.stream().anyMatch(call -> call.patternId == patternId
                        && call.x == x && call.y == y),
                () -> "missing draw " + new Call(patternId, x, y));
    }

    private record Call(int patternId, int x, int y, boolean hFlip) {
        private Call(int patternId, int x, int y) {
            this(patternId, x, y, false);
        }
    }

    private static final class RecordingGraphics extends GraphicsManager {
        private final List<Call> calls = new ArrayList<>();

        @Override public void beginPatternBatch() { }
        @Override public void flushPatternBatch() { }

        @Override
        public void renderPatternWithId(int patternId, PatternDesc desc, int x, int y) {
            calls.add(new Call(patternId, x, y, desc.getHFlip()));
        }
    }
}
