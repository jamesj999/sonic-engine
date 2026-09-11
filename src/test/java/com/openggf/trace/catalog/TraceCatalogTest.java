package com.openggf.trace.catalog;

import com.openggf.trace.StoredPhysicsFrameDomain;
import com.openggf.trace.TraceData;
import com.openggf.tests.trace.TraceV5TestFixture;
import com.openggf.tests.trace.TraceV5RunFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceCatalogTest {

    @Test
    void everyCurrentV5RunManifestHasOneLaunchableCatalogEntry(@TempDir Path root)
            throws Exception {
        Path run = TraceV5RunFixture.writeS3kBonusRun(root.resolve("s3k/runs"));
        TraceV5RunFixture.writeMovie(root.resolve("s3k/_movies/synthetic.bk2"));

        List<TraceEntry> entries = TraceCatalog.scan(root).stream()
                .filter(TraceEntry::isRun)
                .toList();

        assertEquals(1, entries.size());
        assertEquals(run.toAbsolutePath().normalize(),
                entries.getFirst().runDir().toAbsolutePath().normalize());
        TraceCatalog.RunLaunchValidation validation =
                TraceCatalog.validateRunLaunch(entries.getFirst());
        assertTrue(validation.launchable(),
                () -> "current v5 visual run is not launchable: "
                        + validation.diagnostic());
    }

    @Test
    void scanFiltersInvalidDirsAndSortsByGameZoneAct(@TempDir Path tmp) throws Exception {
        writeValidTrace(tmp.resolve("s3k/z_cnz"), "s3k", 11, 0);
        writeValidTrace(tmp.resolve("s1/ghz1"), "s1", 0, 0);
        writeValidTrace(tmp.resolve("s2/ehz1"), "s2", 0, 0);
        writeValidTrace(tmp.resolve("s3k/aiz1"), "s3k", 0, 0);
        Files.createDirectories(tmp.resolve("bogus"));            // missing files
        Files.createDirectories(tmp.resolve("synthetic/v3"));     // filtered by path

        List<TraceEntry> entries = TraceCatalog.scan(tmp);

        assertEquals(List.of("s1", "s2", "s3k", "s3k"),
                entries.stream().map(TraceEntry::gameId).toList());
        assertEquals(List.of(0, 0, 0, 11),
                entries.stream().map(TraceEntry::zone).toList());
    }

    @Test
    void scanSkipsDirWithMultipleBk2Files(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("s1/bad");
        writeValidTrace(dir, "s1", 0, 0);
        Files.writeString(dir.resolve("extra.bk2"), "extra");
        assertTrue(TraceCatalog.scan(tmp).isEmpty());
    }

    @Test
    void scanSkipsSyntheticSubtree(@TempDir Path tmp) throws Exception {
        writeValidTrace(tmp.resolve("synthetic/fake"), "s1", 0, 0);
        assertTrue(TraceCatalog.scan(tmp).isEmpty());
    }

    @Test
    void scanTranslatesS1RomZoneToProgressionIndex(@TempDir Path tmp) throws Exception {
        // Sonic 1 ROM zone IDs differ from the engine's progression
        // order: LZ=ROM 1 but progression 3, MZ=ROM 2 but progression 1.
        writeValidTrace(tmp.resolve("s1/mz1"), "s1", 2, 1); // MZ Act 1
        writeValidTrace(tmp.resolve("s1/lz3"), "s1", 1, 3); // LZ Act 3

        List<TraceEntry> entries = TraceCatalog.scan(tmp);

        // MZ progression=1 sorts before LZ progression=3.
        assertEquals(List.of(1, 3),
                entries.stream().map(TraceEntry::zone).toList());
        // Acts are 1-indexed in metadata → 0-indexed in TraceEntry.
        assertEquals(List.of(0, 2),
                entries.stream().map(TraceEntry::act).toList());
    }

    @Test
    void scanS2AndS3kUseIdentityZoneMapping(@TempDir Path tmp) throws Exception {
        writeValidTrace(tmp.resolve("s2/cpz1"), "s2", 4, 1); // CPZ Act 1
        writeValidTrace(tmp.resolve("s3k/cnz1"), "s3k", 3, 1); // CNZ Act 1

        List<TraceEntry> entries = TraceCatalog.scan(tmp);

        assertEquals(List.of(4, 3),
                entries.stream().map(TraceEntry::zone).toList());
        assertEquals(List.of(0, 0),
                entries.stream().map(TraceEntry::act).toList());
    }

    @Test
    void scanResolvesSharedMovieViaSourceBk2(@TempDir Path tmp) throws Exception {
        // No per-dir .bk2: the movie lives once under <game>/_movies/ and is
        // referenced by metadata.source_bk2 (the deduplicated layout).
        Path dir = tmp.resolve("s1/ghz1_completerun");
        writeTraceWithoutBk2(dir, "s1", 0, 0, "s1-complete-run.bk2");
        Files.createDirectories(tmp.resolve("s1/_movies"));
        Files.writeString(tmp.resolve("s1/_movies/s1-complete-run.bk2"), "shared-movie");

        List<TraceEntry> entries = TraceCatalog.scan(tmp);

        assertEquals(1, entries.size());
        assertEquals(tmp.resolve("s1/_movies/s1-complete-run.bk2"),
                entries.getFirst().bk2Path());
    }

    @Test
    void scanUsesPerDirBk2MoviePlacementWhenSharedMovieMissing(@TempDir Path tmp) throws Exception {
        // source_bk2 declared but the shared movie is absent — the retained
        // per-directory movie placement resolves the otherwise valid v5
        // fixture. This does not make an older trace schema loadable.
        Path dir = tmp.resolve("s1/ghz1");
        writeTraceWithoutBk2(dir, "s1", 0, 0, "s1-complete-run.bk2");
        Files.writeString(dir.resolve("trace.bk2"), "stub");

        List<TraceEntry> entries = TraceCatalog.scan(tmp);

        assertEquals(1, entries.size());
        assertEquals(dir.resolve("trace.bk2"), entries.getFirst().bk2Path());
    }

    @Test
    void scanSkipsWhenSourceBk2DeclaredButNoMovieResolvable(@TempDir Path tmp) throws Exception {
        // source_bk2 set, no shared movie, no per-dir .bk2 → unresolvable, skip.
        writeTraceWithoutBk2(tmp.resolve("s1/ghz1"), "s1", 0, 0, "missing.bk2");
        assertTrue(TraceCatalog.scan(tmp).isEmpty());
    }

    @Test
    void scanAcceptsGzippedPhysicsCsv(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("s3k/cnz1");
        writeValidTrace(dir, "s3k", 3, 1);
        String physics = Files.readString(dir.resolve("physics.csv"));
        Files.delete(dir.resolve("physics.csv"));
        try (var out = Files.newOutputStream(dir.resolve("physics.csv.gz"));
             var gzip = new java.util.zip.GZIPOutputStream(out)) {
            gzip.write(physics.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        List<TraceEntry> entries = TraceCatalog.scan(tmp);

        assertEquals(1, entries.size());
        assertEquals(2, entries.getFirst().frameCount());
    }

    @Test
    void scanDoesNotCountRecorderCsvHeaderAsAFrame(@TempDir Path tmp)
            throws Exception {
        Path dir = tmp.resolve("s1/ghz1");
        writeValidTrace(dir, "s1", 0, 1);
        Files.writeString(dir.resolve("physics.csv"),
                "frame,input,camera_x\n"
                        + "0000,0000,0000\n"
                        + "0001,0000,0000\n");

        List<TraceEntry> entries = TraceCatalog.scan(tmp);

        assertEquals(2, entries.getFirst().frameCount());
    }

    @Test
    void scanPreservesEveryHeaderlessV5CsvRow(@TempDir Path tmp)
            throws Exception {
        Path dir = tmp.resolve("s1/ghz1");
        writeValidTrace(dir, "s1", 0, 1);

        List<TraceEntry> entries = TraceCatalog.scan(tmp);

        assertEquals(2, entries.getFirst().frameCount());
        assertEquals(2, TraceData.load(dir).frameCount());
        assertEquals(List.of(0, 1), StoredPhysicsFrameDomain.scan(
                dir.resolve("physics.csv")).frames());
    }

    @Test
    void malformedEqualOffsetLegacyCohortIsOmittedWithoutAbortingScan(
            @TempDir Path tmp) throws Exception {
        Path first = tmp.resolve("s1/first_completerun");
        Path second = tmp.resolve("s1/second_completerun");
        writeTraceWithoutBk2(first, "s1", 0, 1, "legacy.bk2");
        writeTraceWithoutBk2(second, "s1", 1, 1, "legacy.bk2");
        Files.writeString(first.resolve("physics.csv"), "");
        Files.writeString(second.resolve("physics.csv"), "");
        Files.createDirectories(tmp.resolve("s1/_movies"));
        Files.writeString(tmp.resolve("s1/_movies/legacy.bk2"), "movie");

        List<TraceEntry> entries = assertDoesNotThrow(
                () -> TraceCatalog.scan(tmp));

        assertEquals(2, entries.size());
        assertTrue(entries.stream().noneMatch(TraceEntry::isRun));
    }

    private static void writeValidTrace(Path dir, String game, int zoneId, int act)
            throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("metadata.json"), String.format("""
            {
              "game": "%s",
              "zone": "ZONE",
              "zone_id": %d,
              "act": %d,
              "trace_schema": 5,
              "bk2_frame_offset": 100,
              "pre_trace_osc_frames": 12,
              "main_character": "sonic",
              "sidekicks": []
            }
            """, game, zoneId, act));
        Files.writeString(dir.resolve("physics.csv"),
                TraceV5TestFixture.levelRow(0) + "\n"
                        + TraceV5TestFixture.levelRow(1) + "\n");
        Files.writeString(dir.resolve("trace.bk2"), "stub");
    }

    /**
     * Like {@link #writeValidTrace} but writes no per-dir {@code .bk2} and adds
     * a {@code source_bk2} reference, exercising the shared-movie resolution.
     */
    private static void writeTraceWithoutBk2(
            Path dir, String game, int zoneId, int act, String sourceBk2)
            throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("metadata.json"), String.format("""
            {
              "game": "%s",
              "zone": "ZONE",
              "zone_id": %d,
              "act": %d,
              "trace_schema": 5,
              "bk2_frame_offset": 100,
              "pre_trace_osc_frames": 12,
              "main_character": "sonic",
              "sidekicks": [],
              "source_bk2": "%s"
            }
            """, game, zoneId, act, sourceBk2));
        Files.writeString(dir.resolve("physics.csv"),
                TraceV5TestFixture.levelRow(0) + "\n"
                        + TraceV5TestFixture.levelRow(1) + "\n");
    }
}
