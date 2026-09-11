package com.openggf.game.resources;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TestNativePlcFadeOwnerCoverage {

    @Test
    void sharedGameplayTransitionsUseTheNativeMarkerHelpers() throws IOException {
        String lifecycle = source("com/openggf/GameLoopPlcLifecycle.java");
        String gameLoop = source("com/openggf/GameLoop.java");
        assertTrue(lifecycle.contains("beginNativeBlockingFade()"));
        assertTrue(lifecycle.contains(".wrapCompletion(callback)"));
        assertTrue(lifecycle.contains("startToBlack("));
        assertTrue(lifecycle.contains("startFromBlack("));
        assertTrue(lifecycle.contains("startToWhite("));
        assertTrue(lifecycle.contains("startFromWhite("));
        assertTrue(gameLoop.contains("GameLoopPlcLifecycle.start"));
    }

    @Test
    void auditedConcurrentSonic1FadesRemainUnmarked() throws IOException {
        String special = source(
                "com/openggf/game/sonic1/specialstage/Sonic1SpecialStageManager.java");
        String credits = source(
                "com/openggf/game/sonic1/credits/Sonic1CreditsManager.java");
        String ending = source(
                "com/openggf/game/sonic1/objects/Sonic1EndingSonicObjectInstance.java");
        String provider = source(
                "com/openggf/game/sonic1/credits/Sonic1EndingProvider.java");

        assertTrue(special.contains(
                "startFadeToWhite(null, Integer.MAX_VALUE)"));
        assertTrue(credits.contains(
                "state = State.DEMO_FADING_OUT;"));
        assertTrue(credits.contains("startFadeToBlack(() ->"));
        assertTrue(ending.contains("startFadeToWhite(() ->"));
        assertTrue(provider.contains(
                "PlcLifecyclePhase.CREDITS_DEMO_FADE"));
    }

    private static String source(String relative) throws IOException {
        return Files.readString(Path.of("src/main/java").resolve(relative));
    }
}
