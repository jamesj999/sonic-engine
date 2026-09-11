package com.openggf.tools.audio.parity.s3k;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openggf.tests.RomTestUtils;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

/**
 * Integrity contract for the committed S3K sound-driver oracle reference:
 * the gzip fixture's checksum, its metadata sidecar, the source BK2 identity,
 * and the stream's own terminal digest must all agree.
 */
class TestS3kAudioOracleFixtureContract {
    private static final Path FIXTURE_DIR = Path.of("src/test/resources/audio/parity/s3k");
    private static final Path REFERENCE = FIXTURE_DIR.resolve("s3k-aiz1-intro-reference-v1.jsonl.gz");
    private static final Path METADATA = FIXTURE_DIR.resolve("s3k-aiz1-intro-metadata-v1.json");

    @Test
    void committedReferenceMatchesItsMetadataSidecar() throws Exception {
        assertTrue(Files.isRegularFile(REFERENCE), "committed S3K oracle reference is required");
        assertTrue(Files.isRegularFile(METADATA), "fixture metadata sidecar is required");
        JsonNode metadata = new ObjectMapper().readTree(METADATA.toFile());
        assertEquals("openggf.s3k_audio_oracle_reference_fixture.v1",
                metadata.path("schema").asText());
        assertEquals(metadata.path("reference").asText(), REFERENCE.getFileName().toString());
        assertEquals(metadata.path("reference_gzip_sha256").asText(), sha256(REFERENCE));
        assertEquals(metadata.path("reference_uncompressed_sha256").asText(),
                sha256Uncompressed(REFERENCE));

        Path movie = Path.of(metadata.path("movie").path("path").asText());
        assertTrue(Files.isRegularFile(movie), "source BK2 must remain committed: " + movie);
        assertEquals(metadata.path("movie").path("sha256").asText(), sha256(movie));

        int[] ticks = {0};
        S3kAudioReferenceReader.Metadata streamMetadata =
                S3kAudioReferenceReader.read(REFERENCE, tick -> ticks[0]++);
        assertEquals(metadata.path("ticks").asInt(), ticks[0]);
        assertEquals(metadata.path("rom_sha1").asText(), streamMetadata.romSha1());
        assertEquals(metadata.path("movie").path("sha256").asText(), streamMetadata.movieSha256());
        assertEquals(metadata.path("observer").path("core_zst_sha256").asText(),
                streamMetadata.observerCoreSha256());
    }

    @Test
    void driverProjectionExcludes68kBootstrapWritesButKeepsZ80Writes() {
        List<S3kAudioTick> ticks = new ArrayList<>();
        S3kAudioReferenceReader.read(REFERENCE, ticks::add);

        assertTrue(ticks.get(3).writes().isEmpty(),
                "the 68k PSGInitValues bootstrap is outside the Z80 driver oracle");
        assertEquals(0xff, ticks.get(13).writes().get(0).value(),
                "the first Z80 zStopAllSound write must remain in the projection");
    }

    @Test
    void driverServiceProjectionGroupsBootAtItsRamOwnedCompletionMarker() {
        List<S3kAudioTick> services = new ArrayList<>();
        S3kAudioReferenceReader.readDriverServices(REFERENCE, services::add);

        assertEquals(5_286, services.size());
        S3kAudioTick boot = services.getFirst();
        assertEquals(List.of(0, 0, 0), boot.mailbox(),
                "pre-install mailbox bytes are not a boot-service input");
        assertEquals(85, boot.writes().size());
        assertEquals(0xff, boot.writes().getFirst().value());
        assertEquals(0x82, boot.writes().getFirst().register());
        assertEquals(0x2b, boot.writes().getLast().register());
        assertEquals(0, boot.writes().getLast().value());
        assertEquals(List.of(0xe1, 0, 0), services.get(1).mailbox(),
                "the first ordinary zVInt consumes the request left pending during boot");
        assertTrue(services.get(128).producerInputEvidence().unavailable(),
                "the projector must retain its authenticated suspended-input evidence");
    }

    @Test
    void engineUsesProductionStopsThroughTheUnrepresentedPcmExitFrontier() {
        File rom = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(rom != null && rom.isFile(), "S3K locked-on ROM unavailable");
        List<S3kAudioTick> services = new ArrayList<>();
        S3kAudioReferenceReader.readDriverServices(REFERENCE, services::add);

        S3kOpenGgfAudioCapture.CaptureResult engine =
                S3kOpenGgfAudioCapture.capture(
                        rom.toPath(), services.subList(0, 260), null);

        S3kAudioParityComparator.Report report =
                S3kAudioParityComparator.compare(
                        services.subList(0, 260), engine.ticks());
        assertEquals(260, engine.ticks().size());
        assertEquals(84, engine.ticks().get(49).writes().size(),
                "FF must use the exact production stop before PCM transport");
        assertEquals(0xff,
                engine.ticks().get(49).writes().getFirst().value());
        assertTrue(engine.ticks().get(138).writes().size() >= 84,
                "the first music request must begin with the production stop");
        assertEquals(services.get(138).writes().subList(0, 84),
                engine.ticks().get(138).writes().subList(0, 84));

        // v1's frame-granular projection swept the whole boot frame, so its
        // boot row still physically carries zPlayDigitalAudio's entry 2Bh
        // (D:4256-4260) as an 85th write, where zInitAudioDriver's own last
        // write is zStopAllSound's 27h (D:2513-2519,550-551) and the engine
        // closes the init service before it. That write is the sample-end
        // disable, which now belongs to the DAC byte stream, so tick 0's
        // service streams agree and the retired stream's first divergence is
        // its next sampling artefact: v1's row for the SEGA PCM start omits
        // the 2Bh = 80h enable the engine emits there. The enable is
        // deliberately still compared per tick, so every run start stays
        // pinned. v2 remains the live comparison.
        assertEquals(S3kAudioParityComparator.Report.Kind.EVENT_EXTRA,
                report.kind());
        assertEquals(50, report.tick());
        assertEquals("decoded_write", report.field());
        assertEquals(0x80, engine.ticks().get(50).writes().getFirst().value());
        assertEquals(85, services.getFirst().writes().size(),
                "the retired v1 boot row absorbed the DAC loop's entry write");
        assertEquals(0x2b, services.getFirst().writes().get(84).register());
    }

    @Test
    void engineHostModelsE3WithTheSourceOwnedPsgSilenceProgram() {
        File rom = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(rom != null && rom.isFile(),
                "S3K locked-on ROM unavailable");
        List<S3kAudioTick> services = new ArrayList<>();
        S3kAudioReferenceReader.readDriverServices(REFERENCE, services::add);
        S3kAudioTick template = services.get(1);
        S3kAudioTick e3 = new S3kAudioTick(
                template.ordinal(), template.lag(), List.of(0xE3, 0, 0),
                template.global(), template.tracks(), List.of());

        S3kOpenGgfAudioCapture.CaptureResult engine =
                S3kOpenGgfAudioCapture.capture(
                        rom.toPath(), List.of(services.getFirst(), e3), null);

        // Tick 1 opens with zPlayDigitalAudio's entry DAC disable, which the
        // init reaches by jp and therefore does not own (D:550-551,4256-4260),
        // followed by zPSGSilenceAll's four writes.
        assertEquals(List.of(0x00, 0x9F, 0xBF, 0xDF, 0xFF),
                engine.ticks().get(1).writes().stream()
                        .map(write -> write.value()).toList());
        assertEquals(0x2b, engine.ticks().get(1).writes().getFirst().register());
        assertTrue(engine.unsupportedRequests().isEmpty());
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static String sha256Uncompressed(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new GZIPInputStream(Files.newInputStream(path))) {
            byte[] chunk = new byte[64 * 1024];
            int count;
            while ((count = input.read(chunk)) >= 0) {
                digest.update(chunk, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
