package com.openggf.tools.audio.completerun.s3k;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.ProducerKind;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.UnavailableProducerBinding;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

class TestS3kCompleteRunAudioFixture {
    private static final Path MOVIE_DIRECTORY = Path.of(
            "src/test/resources/traces/s3k/runs/s3k-knuckles-complete-superemeralds");
    private static final Path RUN_LOCAL_MOVIE =
            MOVIE_DIRECTORY.resolve("s3k-knuckles-complete-superemeralds.bk2");
    private static final Path LEGACY_MOVIE = Path.of(
            "src/test/resources/traces/s3k/_movies/s3k-knuckles-complete-superemeralds.bk2");

    @Test
    void manifestLocalMovieIsAnIndependentAuthenticatedCompleteRunFixture() throws Exception {
        assertTrue(Files.isRegularFile(RUN_LOCAL_MOVIE, LinkOption.NOFOLLOW_LINKS),
                "manifest-local S3K complete-run BK2 is required");
        assertFalse(Files.isSymbolicLink(RUN_LOCAL_MOVIE));
        assertFalse(Files.isSameFile(LEGACY_MOVIE, RUN_LOCAL_MOVIE),
                "manifest-local fixture must not be a hard link to _movies");
        assertEquals("aa892856df22b7bb1fe5accb48db10b90dc26845d1dccee90352da30349f53cc",
                sha256(RUN_LOCAL_MOVIE));

        try (ZipFile movie = new ZipFile(RUN_LOCAL_MOVIE.toFile())) {
            long rows = new java.io.BufferedReader(new java.io.InputStreamReader(
                    movie.getInputStream(movie.getEntry("Input Log.txt")),
                    java.nio.charset.StandardCharsets.UTF_8)).lines()
                    .filter(line -> line.startsWith("|")).count();
            assertEquals(434_417, rows);
        }
    }

    @Test
    void referenceBindingNamesOnlyTheRemainingIdentityActivationBlocker() {
        UnavailableProducerBinding unavailable = (UnavailableProducerBinding)
                S3kCompleteRunAudioProfile.profile().producerBindings().get(ProducerKind.REFERENCE);

        assertEquals("exact reference runtime, observer, and capability identities are not installed and active",
                unavailable.reason());
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path)));
    }
}
