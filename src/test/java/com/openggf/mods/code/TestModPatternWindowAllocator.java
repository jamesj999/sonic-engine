package com.openggf.mods.code;

import com.openggf.game.session.PatternWindowState;
import com.openggf.graphics.PatternAtlas;
import com.openggf.graphics.PatternAtlasRange;
import com.openggf.level.Pattern;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestModPatternWindowAllocator {
    private static final int FIRST_FREE = 0x108000;
    private static final int PRODUCTION_FIRST_FREE = PatternAtlasRange.CONTINUE_SCREEN.endExclusive();

    @Test
    void acceptsOneAndSixteenWindowOwnersInEffectiveOrder() {
        ModPatternWindowAllocator allocator = new ModPatternWindowAllocator(List.of(
                new ModPatternWindowAllocator.Request("owner-z", 1),
                new ModPatternWindowAllocator.Request("owner-a", 16)), FIRST_FREE);

        assertEquals(List.of("owner-z", "owner-a"), allocator.assignments().stream()
                .map(PatternWindowState.Assignment::owner).toList());
        assertEquals(FIRST_FREE,
                allocator.assignment("owner-z").orElseThrow().base());
        assertEquals(FIRST_FREE
                        + ModPatternWindowAllocator.WINDOW_SIZE,
                allocator.assignment("owner-a").orElseThrow().base());
        assertEquals(17, allocator.totalWindows());
    }

    @Test
    void rejectsOutOfContractRequestsAndDuplicateOwners() {
        assertThrows(IllegalArgumentException.class,
                () -> new ModPatternWindowAllocator.Request("owner", 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ModPatternWindowAllocator.Request("owner", 17));
        assertThrows(IllegalArgumentException.class,
                () -> new ModPatternWindowAllocator(List.of(
                        new ModPatternWindowAllocator.Request("owner", 1),
                        new ModPatternWindowAllocator.Request("owner", 1)), FIRST_FREE));
    }

    @Test
    void assignmentsDoNotOverlapAndCanBeReRegisteredAfterAtlasClear() {
        ModPatternWindowAllocator allocator = new ModPatternWindowAllocator(List.of(
                new ModPatternWindowAllocator.Request("first", 2),
                new ModPatternWindowAllocator.Request("second", 3)), PRODUCTION_FIRST_FREE);
        PatternWindowState.Assignment first = allocator.assignment("first").orElseThrow();
        PatternWindowState.Assignment second = allocator.assignment("second").orElseThrow();
        assertEquals(first.endExclusive(), second.base());

        PatternAtlas atlas = new PatternAtlas(256, 256);
        allocator.registerRanges(atlas::registerRange);
        assertNotNull(atlas.cachePatternHeadless(new Pattern(), second.endExclusive() - 1));
        atlas.clearRanges();
        assertThrows(IllegalArgumentException.class,
                () -> atlas.cachePatternHeadless(new Pattern(), second.base()));
        allocator.registerRanges(atlas::registerRange);
        assertNotNull(atlas.cachePatternHeadless(new Pattern(), second.base()));
    }

    @Test
    void honorsSyntheticHigherStartAndRejectsMisalignmentOrAddressOverflow() {
        int syntheticStart = 0x109000;
        ModPatternWindowAllocator allocator = new ModPatternWindowAllocator(List.of(
                new ModPatternWindowAllocator.Request("higher", 1)), syntheticStart);
        assertEquals(syntheticStart, allocator.assignment("higher").orElseThrow().base());

        assertThrows(IllegalArgumentException.class, () -> new ModPatternWindowAllocator(
                List.of(new ModPatternWindowAllocator.Request("misaligned", 1)),
                syntheticStart + 1));
        assertThrows(IllegalArgumentException.class, () -> new ModPatternWindowAllocator(
                List.of(new ModPatternWindowAllocator.Request("overflow", 16)),
                0x7FFFF000));
    }
}
