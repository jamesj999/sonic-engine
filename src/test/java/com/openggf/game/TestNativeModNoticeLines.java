package com.openggf.game;

import com.openggf.GameLoop;
import com.openggf.mods.NativeUnsupportedMods;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.function.ToIntFunction;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestNativeModNoticeLines {
    private static final ToIntFunction<String> SIX_PER_CHAR = value -> value.length() * 6;

    @Test
    void longNoticeSentenceWrapsWithinTheBodyWidth() {
        var lines = NativeModNoticeScreen.wrapLines(
                List.of("These mods are not supported on OpenGGF native builds:"),
                SIX_PER_CHAR, 120, 12);

        assertEquals(List.of(
                "These mods are not",
                "supported on OpenGGF",
                "native builds:"), lines);
        assertTrue(lines.stream().allMatch(line -> SIX_PER_CHAR.applyAsInt(line) <= 120));
    }

    @Test
    void oversizedSingleWordIsSplitToFit() {
        var lines = NativeModNoticeScreen.wrapLines(
                List.of("abcdefghij"), SIX_PER_CHAR, 24, 12);

        assertEquals(List.of("abcd", "efgh", "ij"), lines);
        assertTrue(lines.stream().allMatch(line -> SIX_PER_CHAR.applyAsInt(line) <= 24));
    }

    @Test
    void wrappedNoticePreservesLinesBeyondThePageBudget() {
        var lines = NativeModNoticeScreen.wrapLines(
                List.of("one", "two", "three", "four"), SIX_PER_CHAR, 120, 3);

        assertEquals(List.of("one", "two", "three", "four"), lines);
    }

    @Test
    void shortNoticeLinesRemainUnchanged() {
        var lines = NativeModNoticeScreen.wrapLines(
                List.of("Short header", "Short mod"), SIX_PER_CHAR, 120, 12);

        assertEquals(List.of("Short header", "Short mod"), lines);
    }

    @Test
    void bootNoticeWiringDoesNotExpandThePublicModApi() throws NoSuchMethodException {
        var supplierSetter = GameLoop.class.getDeclaredMethod(
                "setNativeModNoticeScreenSupplier", java.util.function.Supplier.class);
        var exitSetter = GameLoop.class.getDeclaredMethod(
                "setNativeModNoticeExitHandler", Runnable.class);

        assertTrue(!Modifier.isPublic(supplierSetter.getModifiers()));
        assertTrue(!Modifier.isPublic(exitSetter.getModifiers()));
    }

    @Test
    void screenBudgetProducesTruncation() {
        var names = IntStream.range(0, NativeModNoticeScreen.MAX_VISIBLE_MOD_LINES + 3)
                .mapToObj(i -> "mod" + i).toList();

        var lines = NativeUnsupportedMods.noticeLines(
                names, NativeModNoticeScreen.MAX_VISIBLE_MOD_LINES);

        assertEquals(NativeModNoticeScreen.MAX_VISIBLE_MOD_LINES + 1, lines.size());
        assertTrue(lines.getLast().endsWith(" more"));
    }
}
