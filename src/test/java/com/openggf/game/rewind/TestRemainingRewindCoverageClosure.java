package com.openggf.game.rewind;

import com.openggf.game.sonic1.objects.Sonic1TryAgainEggmanObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1TryAgainEmeraldsObjectInstance;
import com.openggf.game.sonic2.objects.SuperSonicStarsObjectInstance;
import com.openggf.level.objects.RewindRecreatable;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRemainingRewindCoverageClosure {
    private static final List<Class<?>> FINAL_COVERAGE_GAP_CLASSES = List.of(
            Sonic1TryAgainEggmanObjectInstance.class,
            Sonic1TryAgainEmeraldsObjectInstance.class,
            SuperSonicStarsObjectInstance.class
    );

    @Test
    void finalCoverageGapClassesUseGenericRecreate() {
        for (Class<?> type : FINAL_COVERAGE_GAP_CLASSES) {
            assertTrue(RewindRecreatable.class.isAssignableFrom(type),
                    type.getName() + " must opt into generic rewind recreation");
        }
    }

    @Test
    void coverageBaselineHasNoRemainingRecreateGaps() throws Exception {
        String baseline = Files.readString(Path.of("src/test/resources/rewind/coverage-baseline.txt"));
        for (Class<?> type : FINAL_COVERAGE_GAP_CLASSES) {
            assertFalse(baseline.contains(type.getName() + "#recreate"),
                    type.getName() + " should no longer need a recreate baseline entry");
        }
        assertTrue(baseline.isBlank(), "rewind coverage baseline should be empty after final recreate gaps close");
    }
}
