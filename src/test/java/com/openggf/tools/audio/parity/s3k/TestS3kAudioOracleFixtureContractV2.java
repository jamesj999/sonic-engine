package com.openggf.tools.audio.parity.s3k;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openggf.tests.RomTestUtils;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integrity and semantics contract for the v2 S3K sound-driver oracle
 * reference, the stream the oracle compares against.
 *
 * <p>v2 exists because v1 sampled driver RAM out of band after the emulated
 * frame advanced, so a frame whose driver work overran it recorded a truncated
 * prefix of {@code zUpdateMusic}'s track-iteration order. v2 has the observer
 * core capture the window at the driver's own service boundaries instead. The
 * assertions below pin both the fixture's identity and the two properties that
 * distinguish it from v1: rows are services rather than frames, and the
 * title-music load frame is one of the frames that carries no row at all.
 */
class TestS3kAudioOracleFixtureContractV2 {
    private static final Path FIXTURE_DIR = Path.of("src/test/resources/audio/parity/s3k");
    private static final Path REFERENCE = FIXTURE_DIR.resolve("s3k-aiz1-intro-reference-v2.jsonl.gz");
    private static final Path METADATA = FIXTURE_DIR.resolve("s3k-aiz1-intro-metadata-v2.json");

    /** Movie frame at which the title music (request 25h) loads. */
    private static final int TITLE_MUSIC_LOAD_FRAME = 252;

    @Test
    void committedReferenceMatchesItsMetadataSidecar() throws Exception {
        assertTrue(Files.isRegularFile(REFERENCE), "committed v2 S3K oracle reference is required");
        assertTrue(Files.isRegularFile(METADATA), "v2 fixture metadata sidecar is required");
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
        assertEquals(S3kAudioParitySchema.VERSION_V2, streamMetadata.schema());
        assertTrue(streamMetadata.perService(), "v2 is a per-service stream");
        assertEquals(metadata.path("ticks").asInt(), ticks[0]);
        assertEquals(metadata.path("frames").asInt(), streamMetadata.frames());
        assertEquals(metadata.path("rom_sha1").asText(), streamMetadata.romSha1());
        assertEquals(metadata.path("movie").path("sha256").asText(), streamMetadata.movieSha256());
        assertEquals(metadata.path("observer").path("core_zst_sha256").asText(),
                streamMetadata.observerCoreSha256());
    }

    /**
     * The property v1 could not hold. A row is one completed driver service, so
     * ordinals are dense while movie frames are not: 5,400 replayed frames
     * produce 5,263 rows, and the frames that produce none are the ones whose
     * driver work ran past the frame boundary.
     */
    @Test
    void rowsAreServicesSoFramesAreSparseAndStrictlyIncreasing() {
        List<S3kAudioTick> ticks = readAll();
        assertEquals(5_263, ticks.size());
        assertTrue(ticks.size() < 5_400,
                "a per-service stream must have fewer rows than replayed frames");
        for (int index = 0; index < ticks.size(); index++) {
            assertEquals(index, ticks.get(index).ordinal(), "service ordinals must be dense");
            if (index > 0) {
                assertTrue(ticks.get(index).frame() > ticks.get(index - 1).frame(),
                        "each service must complete in a later frame than the one before it");
            }
        }
    }

    /**
     * The v1 failure, pinned as a fixture property. Movie frame 252 loads the
     * title music; the driver's work overruns that frame and {@code zVInt}
     * reaches its return in frame 253. v1 sampled at the frame-252 boundary and
     * recorded a partially parsed track set. v2 records no row for frame 252 at
     * all, and the row that does cover the load has every music track parsed.
     */
    @Test
    void theTitleMusicLoadFrameCarriesNoRowAndItsServiceIsFullyParsed() {
        List<S3kAudioTick> ticks = readAll();
        assertTrue(ticks.stream().noneMatch(tick -> tick.frame() == TITLE_MUSIC_LOAD_FRAME),
                "frame " + TITLE_MUSIC_LOAD_FRAME + " is overrun, so it completes no service");

        S3kAudioTick load = ticks.stream()
                .filter(tick -> tick.frame() == TITLE_MUSIC_LOAD_FRAME + 1)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the overrun service must complete in the next frame"));

        // Under v1 these were still at their post-init zZeroFillTrackRAM values
        // because the snapshot cut through zUpdateMusic's track loop.
        for (String role : List.of("MUS_FM1", "MUS_FM2", "MUS_FM3", "MUS_PSG1", "MUS_PSG3")) {
            S3kAudioTrackState track = load.tracks().stream()
                    .filter(state -> state.role().equals(role))
                    .findFirst()
                    .orElseThrow();
            assertTrue(track.playing(), role + " must be playing after the music load");
            assertNotEquals(0x01, track.durationTimeout(),
                    role + " still holds its post-init duration, so the snapshot cut through the track loop");
        }
    }

    /**
     * The boot row is the driver init itself, ending at the last write that
     * routine makes. {@code zStopAllSound} writes YM 2Bh then 27h with
     * interrupts disabled throughout (skdisasm Sound/Z80 Sound Driver.asm:
     * 2506-2520), and {@code zInitAudioDriver} only enables interrupts
     * afterwards (:550). The 2Bh write that follows in the stream is a
     * different one, the first instruction block of {@code zPlayDigitalAudio}
     * (:4258-4262), which the init jumps into and never returns from.
     */
    @Test
    void theBootRowEndsWhereTheDriverInitEnds() {
        S3kAudioTick boot = readAll().getFirst();
        assertEquals(0, boot.ordinal());
        assertEquals(84, boot.writes().size(),
                "the boot row holds zStopAllSound's writes and nothing after them");
        assertEquals(0x82, boot.writes().getFirst().register());
        assertEquals(0xff, boot.writes().getFirst().value());
        assertEquals(0x27, boot.writes().getLast().register(),
                "zFM3NormalMode's 27h write is the last the init makes");
        assertEquals(0, boot.writes().getLast().value());
    }

    /**
     * The per-service stream needs no frame-to-service projection, so
     * {@code readDriverServices} must hand back exactly what {@code read} does.
     */
    @Test
    void serviceProjectionIsAPassThroughForAPerServiceStream() {
        List<S3kAudioTick> direct = readAll();
        List<S3kAudioTick> projected = new ArrayList<>();
        S3kAudioReferenceReader.readDriverServices(REFERENCE, projected::add);
        assertEquals(direct, projected);
    }

    /**
     * The live oracle comparison, so v2 is the stream CI actually compares
     * against, and the current frontier is pinned rather than only logged.
     *
     * <p>{@code zStopAllSound} writes 2Bh then 27h with interrupts disabled
     * (skdisasm Sound/Z80 Sound Driver.asm:2506-2520), so 27h is the last write
     * the init makes. The 2Bh that follows is the first instruction block of
     * {@code zPlayDigitalAudio} (:4256-4260), which the init jumps into at :551
     * and never returns from, so it opens the window the first {@code zVInt}
     * return closes. This pins that placement on both sides of the boundary.
     */
    @Test
    void theDacLoopEntryWriteOpensTheServiceAfterTheDriverInit() {
        File rom = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(rom != null && rom.isFile(), "S3K locked-on ROM unavailable");
        List<S3kAudioTick> services = readAll().subList(0, 8);

        S3kOpenGgfAudioCapture.CaptureResult engine =
                S3kOpenGgfAudioCapture.capture(rom.toPath(), services, null);
        S3kAudioParityComparator.Report report =
                S3kAudioParityComparator.compare(services, engine.ticks());

        assertEquals(84, services.getFirst().writes().size(),
                "the reference boot row ends where the driver init ends");
        assertEquals(84, engine.ticks().getFirst().writes().size(),
                "the engine's init must end at zStopAllSound's 27h");
        assertEquals(0x27, engine.ticks().getFirst().writes().getLast().register());
        assertEquals(0x2b, engine.ticks().get(1).writes().getFirst().register(),
                "the DAC loop's entry write opens the next service");
        assertEquals(0, engine.ticks().get(1).writes().getFirst().value());
        assertEquals(services.get(1).writes().getFirst(),
                engine.ticks().get(1).writes().getFirst());
        assertNotEquals(S3kAudioParityComparator.Report.Kind.EVENT_EXTRA,
                report.kind(), "the boot service must no longer carry an extra write");
    }

    /**
     * The frame field is provenance, never an input. It exists so a divergence
     * can name the movie frame a service completed in; it must not reach the
     * engine host or the comparator, because a per-row movie frame steering
     * engine behaviour would make the reference an input rather than a
     * comparison. Proven by perturbation rather than by inspection: rewriting
     * every frame to a value that cannot be a real one must leave both the
     * engine capture and the comparison bit-for-bit unchanged.
     */
    @Test
    void theFrameFieldNeverReachesTheEngineHostOrTheComparator() {
        File rom = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(rom != null && rom.isFile(), "S3K locked-on ROM unavailable");
        List<S3kAudioTick> honest = readAll().subList(0, 8);
        List<S3kAudioTick> perturbed = new ArrayList<>();
        for (S3kAudioTick tick : honest) {
            perturbed.add(new S3kAudioTick(tick.ordinal(), tick.frame() + 9_000, tick.lag(),
                    tick.mailbox(), tick.global(), tick.tracks(), tick.writes(),
                    tick.producerInputEvidence()));
        }

        S3kOpenGgfAudioCapture.CaptureResult fromHonest =
                S3kOpenGgfAudioCapture.capture(rom.toPath(), honest, null);
        S3kOpenGgfAudioCapture.CaptureResult fromPerturbed =
                S3kOpenGgfAudioCapture.capture(rom.toPath(), perturbed, null);

        assertEquals(honest.size(), fromHonest.ticks().size(),
                "the engine host runs exactly one service per reference tick");
        assertEquals(fromHonest.ticks(), fromPerturbed.ticks(),
                "the engine capture must not depend on the reference's movie frames");
        assertEquals(
                S3kAudioParityComparator.compare(honest, fromHonest.ticks()),
                S3kAudioParityComparator.compare(perturbed, fromPerturbed.ticks()),
                "the comparison must not depend on the reference's movie frames");
    }

    /**
     * The window's frame shape, so a later reader knows what to expect of it.
     * 137 frames complete no service: 14 before the 68k has loaded the driver,
     * and 123 the driver's work runs past. No frame completes two, because
     * 0084h is reached once per interrupt and this movie is NTSC, so the PAL
     * double-update never runs.
     */
    @Test
    void theWindowsFrameShapeMatchesItsMetadata() throws Exception {
        JsonNode shape = new ObjectMapper().readTree(METADATA.toFile()).path("frame_shape");
        List<S3kAudioTick> ticks = readAll();
        int frames = shape.path("frames").asInt();

        int[] perFrame = new int[frames];
        ticks.forEach(tick -> perFrame[tick.frame()]++);
        int zero = 0;
        int doubled = 0;
        for (int count : perFrame) {
            if (count == 0) zero++;
            if (count > 1) doubled++;
        }
        assertEquals(shape.path("ticks").asInt(), ticks.size());
        assertEquals(shape.path("zero_service_frames").asInt(), zero);
        assertEquals(shape.path("double_service_frames").asInt(), doubled);
        assertEquals(shape.path("zero_service_pre_install").asInt(), ticks.getFirst().frame(),
                "every frame before the first service is a pre-install frame");
    }

    private static List<S3kAudioTick> readAll() {
        List<S3kAudioTick> ticks = new ArrayList<>();
        S3kAudioReferenceReader.read(REFERENCE, ticks::add);
        return ticks;
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
    }

    private static String sha256Uncompressed(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream raw = Files.newInputStream(path);
                InputStream stream = new GZIPInputStream(raw)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
