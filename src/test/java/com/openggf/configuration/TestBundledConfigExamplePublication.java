package com.openggf.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A player's {@code config.yaml} holds the values they changed and never
 * regains the comments, new keys, or worked examples the bundled template
 * gains later. {@code config.yaml.example} is refreshed beside it every run so
 * the current documented template is always there to read or copy from.
 */
class TestBundledConfigExamplePublication {

    @Test
    void everyRunRefreshesTheExampleBesideTheUsersConfig(@TempDir Path configDir)
            throws IOException {
        SonicConfigurationService.createStandalone(configDir);

        Path example = configDir.resolve("config.yaml.example");
        assertTrue(Files.exists(example), "config.yaml.example should be published");
        assertEquals(bundled(), Files.readString(example),
                "the example must be the bundled template verbatim, comments and all");
    }

    @Test
    void theExampleCarriesTheCommentsThatMakeItWorthPublishing(@TempDir Path configDir)
            throws IOException {
        SonicConfigurationService.createStandalone(configDir);

        String example = Files.readString(configDir.resolve("config.yaml.example"));
        assertTrue(example.contains("ADVANCED: full ffmpeg argument lists"),
                "the ffmpeg guidance should survive publication");
        assertTrue(example.contains("TRACE CAPTURE ONLY"),
                "the capture.scale note should survive publication");
    }

    @Test
    void aStaleExampleIsReplacedRatherThanLeftBehind(@TempDir Path configDir)
            throws IOException {
        Path example = configDir.resolve("config.yaml.example");
        Files.writeString(example, "stale: true\n");

        SonicConfigurationService.createStandalone(configDir);

        assertEquals(bundled(), Files.readString(example));
    }

    /**
     * The player's settings are theirs. Replacing their values with the
     * template's to give them comments would be a bad trade.
     *
     * <p>Asserting on the file's bytes would be wrong: the service already
     * rewrites {@code config.yaml} on load to persist migrations and newly
     * defaulted keys, which predates this publication step. What must hold is
     * that the values the player chose survive, and that the template did not
     * land on top of them.
     */
    @Test
    void thePlayersOwnValuesSurvivePublication(@TempDir Path configDir)
            throws IOException {
        Files.writeString(configDir.resolve("config.yaml"),
                "display:\n  fps: 50\n");

        SonicConfigurationService config =
                SonicConfigurationService.createStandalone(configDir);

        assertEquals(50, config.getInt(SonicConfiguration.FPS),
                "the player's chosen value must not be replaced by the template's");
        assertEquals(bundled(), Files.readString(configDir.resolve("config.yaml.example")));
        assertTrue(!Files.readString(configDir.resolve("config.yaml"))
                        .contains("ADVANCED: full ffmpeg argument lists"),
                "the template must be published beside the player's config, not into it");
    }

    private static String bundled() throws IOException {
        try (InputStream is = SonicConfigurationService.class
                .getResourceAsStream("/config.yaml")) {
            return new String(is.readAllBytes());
        }
    }

}
