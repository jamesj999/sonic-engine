package com.openggf;

import com.openggf.game.resources.DynamicArtDiagnosticsProvider;
import com.openggf.game.resources.DynamicArtLifecycleService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the production ordering seam used by visual named-run DPLC audit.
 */
class TestGameLoopTraceRunPostIteration {

    @Test
    void loopDelegatesTraceIterationBracketingToTraceSessionOwner() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/openggf/GameLoop.java"));
        int step = source.indexOf("private void stepInternal()");
        int delegated = source.indexOf(
                "TraceSessionLauncher.runProductionIterationIfActive(", step);

        assertTrue(step >= 0 && delegated > step,
                "GameLoop must delegate trace-specific iteration bracketing "
                        + "to TraceSessionLauncher");
        assertFalse(source.substring(step,
                        source.indexOf("private void stepInternalBody()", step))
                        .contains("traceSession.afterProductionIteration()"),
                "GameLoop must not own trace-specific exception choreography");
    }

    @Test
    void runObservationWrapsEveryHostStepInsteadOfGameplayOnlyTail() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/openggf/GameLoop.java"));
        int publicStep = source.indexOf("public void step()");
        int step = source.indexOf("private void stepInternal()", publicStep);
        int body = source.indexOf("private void stepInternalBody()", step);
        String wrapper = source.substring(publicStep, step);
        String bodySource = source.substring(body,
                source.indexOf("public boolean ownsGameplayFadeLifecycle()", body));

        assertTrue(wrapper.contains(
                "LevelIterationAdmissionController.runTraceObservedStep("),
                "all-mode run observation must execute from the outer step wrapper");
        assertFalse(bodySource.contains(
                "LevelIterationAdmissionController.driveTraceRunSession("),
                "early-returning modes must not rely on the gameplay-only tail");
    }

    @Test
    void runEscapeIsNotRestrictedToLevelMode() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/openggf/LevelIterationAdmissionController.java"));
        assertTrue(source.contains("mode == GameMode.LEVEL || session.isRunSession()"),
                "an active run must own Escape while crossing non-level modes");
    }

    @Test
    void destinationTimingHandoffPrecedesDynamicArtAndComparatorOwners()
            throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/openggf/TraceSessionLauncher.java"));
        int method = source.indexOf(
                "private void applyRunDestinationAdmission(");
        int end = source.indexOf(
                "private void installRunComparator(", method);
        String admission = source.substring(method, end);

        // enterSegment() performs the same source-schedule handoff and also
        // declares the drive's membership of the destination.
        int timing = admission.indexOf("runHardwareTiming.enterSegment(");
        int dynamicArt = admission.indexOf("runDynamicArtSegments.beginSegment()");
        int comparator = admission.indexOf("installRunComparator(");
        assertTrue(timing >= 0 && dynamicArt > timing && comparator > timing,
                "source timing verification/handoff must complete before "
                        + "destination dynamic-art and comparator owners open");
    }

    @Test
    void titleCardReleaseAdmitsRunBeforeLevelFallThroughInput() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/openggf/GameLoop.java"));
        int release = source.indexOf(
                "if (currentGameMode == GameMode.LEVEL) {",
                source.indexOf("private void exitTitleCard()"));
        int admission = source.indexOf(
                "TraceSessionLauncher.admitRunDestinationBeforeProductionIfActive(",
                release);
        int sync = source.indexOf("syncPlaybackInputBridge();", admission);
        assertTrue(release >= 0 && admission > release && sync > admission,
                "title-card release must admit the destination before its "
                        + "same-step playback/input fall-through");
    }

    @Test
    void diagnosticsServiceExposesNoRegistrationOrCapableReference() {
        assertTrue(Arrays.stream(
                        DynamicArtDiagnosticsProvider.class
                                .getDeclaredMethods())
                .allMatch(method -> method.getParameterCount() == 0));
        assertTrue(Arrays.stream(
                        DynamicArtLifecycleService.class.getDeclaredFields())
                .noneMatch(field -> field.getType().getName()
                        .startsWith("java.util.function")));
        assertFalse(Arrays.stream(
                        DynamicArtLifecycleService.class.getDeclaredFields())
                .anyMatch(field -> field.getType().getName()
                        .startsWith("com.openggf.trace")));
        assertEquals(4, DynamicArtDiagnosticsProvider.class
                .getDeclaredMethods().length);
        assertTrue(Arrays.stream(DynamicArtDiagnosticsProvider.class
                        .getDeclaredMethods())
                .anyMatch(method -> method.getName()
                        .equals("gapOpeningSnapshot")));
    }
}
