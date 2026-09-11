package com.openggf.debug;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TestProfileSnapshot {

    @Test
    void getSectionsSortedByName_returnsAlphabeticalCachedView() {
        ProfileSnapshot snapshot = new ProfileSnapshot();
        Map<String, Long> rollingSums = new LinkedHashMap<>();
        rollingSums.put("physics", 2_000_000L);
        rollingSums.put("audio", 1_000_000L);
        rollingSums.put("render", 3_000_000L);

        snapshot.populate(rollingSums, 1, new float[]{16.67f}, 0, 1, 6_000_000L);

        List<SectionStats> first = snapshot.getSectionsSortedByName();
        List<SectionStats> second = snapshot.getSectionsSortedByName();

        assertEquals(List.of("audio", "physics", "render"),
                first.stream().map(SectionStats::name).toList());
        assertSame(first, second, "alphabetical section ordering should be cached within the snapshot");
    }
}
