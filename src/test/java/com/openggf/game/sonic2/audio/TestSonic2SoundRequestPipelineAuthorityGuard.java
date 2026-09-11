package com.openggf.game.sonic2.audio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;

class TestSonic2SoundRequestPipelineAuthorityGuard {
    private static final Path PIPELINE = Path.of(
            "src/main/java/com/openggf/game/sonic2/audio/Sonic2SoundRequestPipeline.java");
    private static final Pattern FORBIDDEN_OWNER = Pattern.compile(
            "com\\.openggf\\.(?:tools\\.audio|trace|audio\\.(?:driver|session|presentation))"
                    + "|\\b(?:SmpsDriver|SmpsDriverSession|AudioManager|HardwareTiming|TraceData|CompleteRun"
                    + "|Sonic2AudioProfile|LevelMutationSurface|ZoneLayoutMutationPipeline"
                    + "|RequestReference|RequestComparator)\\b|java\\.lang\\.reflect|\\b(?:HashMap|Map|getInstance)\\b");

    @Test
    void pureGameOwnedPipelineCannotReachReferenceTraceOrRuntimeAudioOwners() {
        assertFalse(FORBIDDEN_OWNER.matcher(read(PIPELINE)).find());
    }

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
