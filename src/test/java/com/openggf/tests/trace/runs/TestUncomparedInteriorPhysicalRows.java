package com.openggf.tests.trace.runs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestUncomparedInteriorPhysicalRows {
    private static final Pattern MEASURED_TIMING = Pattern.compile(
            "(?<![0-9_])(?:10_?162|172)(?![0-9_])"
                    + "|s2-sonic-tails-complete-emeralds");

    @Test
    void representedAndInterstitialRowsFormOneContinuousPhysicalWalk() {
        List<AbstractRunChainTest.UncomparedInteriorPhysicalRow> rows =
                AbstractRunChainTest.uncomparedInteriorPhysicalRows(
                        40, 3, 47,
                        new boolean[]{false, true, false, true});

        assertEquals(List.of(40, 41, 42, 43, 44, 45, 46),
                rows.stream()
                        .map(AbstractRunChainTest.UncomparedInteriorPhysicalRow::movieRow)
                        .toList());
        assertTrue(rows.get(2).representedSpecialRow());
        assertFalse(rows.get(3).representedSpecialRow());
        assertEquals(43, rows.get(3).movieRow(),
                "the first gap row is the special segment's exclusive end");
        assertTrue(rows.get(4).lagGapRow(),
                "the census selects the loop for its row only");
    }

    @Test
    void productionLevelEntryTimingContainsNoMeasuredRowOrRouteIdentity()
            throws IOException {
        for (Path source : List.of(
                Path.of("src/main/java/com/openggf/GameLoop.java"),
                Path.of("src/main/java/com/openggf/level/LevelManager.java"),
                Path.of("src/main/java/com/openggf/game/LevelInitProfile.java"),
                Path.of("src/main/java/com/openggf/game/sonic2/"
                        + "Sonic2LevelInitProfile.java"))) {
            assertFalse(MEASURED_TIMING.matcher(Files.readString(source)).find(),
                    source::toString);
        }
    }
}

