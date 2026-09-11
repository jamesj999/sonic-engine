package com.openggf.trace.replay.runs;

import com.openggf.game.profiles.trace.TracePlaybackProfile;
import com.openggf.trace.TraceRunManifest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Executable;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestTraceRunVblankClockAuthorityGuard {

    private static final Path SOURCE = Path.of(
            "src/main/java/com/openggf/trace/replay/runs/TraceRunVblankClock.java");

    @Test
    void sourceCannotDependOnComparisonOrHardwareTimingAuthorities()
            throws Exception {
        String source = Files.readString(SOURCE);

        for (String forbidden : Set.of(
                "TraceData",
                "TraceFrame",
                "SegmentPlan",
                "LiveTraceComparator",
                "FrameComparison",
                "HardwareTiming",
                "DynamicArt",
                "AuxState",
                "aux_state")) {
            assertFalse(source.contains(forbidden),
                    () -> "run VBlank clock must not depend on " + forbidden);
        }
    }

    @Test
    void publicInputsAreLimitedToManifestSegmentsProfileAndObservedCounters() {
        Set<Class<?>> allowed = Set.of(
                int.class,
                TracePlaybackProfile.class,
                TraceRunManifest.Segment.class);

        Arrays.stream(TraceRunVblankClock.class.getDeclaredConstructors())
                .filter(constructor -> Modifier.isPublic(constructor.getModifiers()))
                .forEach(constructor -> assertAllowedParameters(
                        constructor, allowed));
        Arrays.stream(TraceRunVblankClock.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .forEach(method -> assertAllowedParameters(method, allowed));
    }

    private static void assertAllowedParameters(
            Executable executable, Set<Class<?>> allowed) {
        for (Class<?> parameter : executable.getParameterTypes()) {
            assertTrue(allowed.contains(parameter),
                    () -> executable + " exposes forbidden input "
                            + parameter.getName());
        }
    }
}
