package com.openggf.trace.catalog;

import com.openggf.trace.TraceRunManifest;
import com.openggf.tests.trace.TraceV5RunFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestTraceCatalogRunDiscovery {

    @Test
    void discoversGeneratedV5RunWithoutHidingIndividualSegments(@TempDir Path tracesRoot)
            throws IOException {
        Path runs = tracesRoot.resolve("s3k/runs");
        TraceV5RunFixture.writeS3kBonusRun(runs);
        Path movies = tracesRoot.resolve("s3k/_movies");
        TraceV5RunFixture.writeMovie(movies.resolve("synthetic.bk2"));

        List<TraceEntry> entries = TraceCatalog.scan(tracesRoot);

        TraceEntry s3k = entries.stream()
                .filter(TraceEntry::isRun)
                .filter(entry -> "run_aiz_gumball_3seg".equals(
                        entry.runManifest().runId()))
                .findFirst()
                .orElseThrow();

        assertEquals(3, s3k.runManifest().segments().size());
        assertEquals("seg00_aiz", s3k.runManifest().segments().getFirst().dir());
        assertEquals("seg02_aiz", s3k.runManifest().segments().getLast().dir());
        assertEquals(2, s3k.runManifest().transitions().size());
    }

    @Test
    void preparesGeneratedV5RunWithCanonicalizedTiming(@TempDir Path tracesRoot)
            throws Exception {
        TraceV5RunFixture.writeS3kBonusRun(tracesRoot.resolve("s3k/runs"));
        Path movies = tracesRoot.resolve("s3k/_movies");
        TraceV5RunFixture.writeMovie(movies.resolve("synthetic.bk2"));
        TraceEntry run = TraceCatalog.scan(tracesRoot).stream()
                .filter(TraceEntry::isRun)
                .filter(entry -> "run_aiz_gumball_3seg"
                        .equals(entry.runManifest().runId()))
                .findFirst()
                .orElseThrow();

        TraceCatalog.PreparedDescriptorRunLaunch descriptors =
                TraceCatalog.prepareDescriptorRunLaunch(run);

        assertEquals(run.runManifest().segments().size(),
                descriptors.segments().size());
        assertEquals(run.runManifest().segments(), descriptors.segments().stream()
                .map(descriptor -> descriptor.segment()).toList());
    }

    @Test
    void discoversRunManifestAsSingleEntry(@TempDir Path root) throws Exception {
        Path runDir = TraceV5RunFixture.writeS3kBonusRun(root.resolve("s3k/runs"));
        // The manifest's source_bk2 resolves to a generated movie at this standard path.
        Path movies = root.resolve("s3k").resolve("_movies");
        TraceV5RunFixture.writeMovie(movies.resolve("synthetic.bk2"));

        List<TraceEntry> entries = TraceCatalog.scan(root);
        List<TraceEntry> runs = entries.stream().filter(TraceEntry::isRun).toList();
        assertEquals(1, runs.size());
        TraceEntry run = runs.get(0);
        assertEquals("s3k", run.gameId());
        assertEquals(6, run.frameCount()); // 3 segments x 2 frames
        assertEquals(500, run.bk2StartOffset());
        assertTrue(run.displayLabel().contains("run_aiz_gumball_3seg"));
        assertNotNull(run.runManifest());
    }

    @Test
    void sharedMovieWinsWhenRunAlsoContainsLocalCopy(@TempDir Path root) throws Exception {
        Path runDir = copySyntheticRun(root, "s3k");
        Path sharedMovie = root.resolve("s3k/_movies/synthetic.bk2");
        TraceV5RunFixture.writeMovie(sharedMovie);
        TraceV5RunFixture.writeMovie(runDir.resolve("synthetic.bk2"));

        TraceEntry run = TraceCatalog.scan(root).stream()
                .filter(TraceEntry::isRun)
                .findFirst()
                .orElseThrow();

        assertEquals(sharedMovie, run.bk2Path());
    }

    @Test
    void fallsBackToContainedMovieInsideRunDirectory(@TempDir Path root) throws Exception {
        Path runDir = copySyntheticRun(root, "s3k");
        Path localMovie = runDir.resolve("synthetic.bk2");
        TraceV5RunFixture.writeMovie(localMovie);

        TraceEntry run = TraceCatalog.scan(root).stream()
                .filter(TraceEntry::isRun)
                .findFirst()
                .orElseThrow();

        assertEquals(localMovie, run.bk2Path());
    }

    @Test
    void rejectsAbsoluteRunMoviePath(@TempDir Path root) throws Exception {
        Path runDir = copySyntheticRun(root, "s3k");
        Path outsideMovie = root.resolve("outside.bk2").toAbsolutePath();
        TraceV5RunFixture.writeMovie(outsideMovie);
        replaceSourceBk2(runDir, outsideMovie.toString());

        assertTrue(TraceCatalog.scan(root).stream().noneMatch(TraceEntry::isRun));
    }

    @Test
    void rejectsRunMovieParentTraversal(@TempDir Path root) throws Exception {
        Path runDir = copySyntheticRun(root, "s3k");
        Path outsideMovie = runDir.getParent().resolve("outside.bk2");
        TraceV5RunFixture.writeMovie(outsideMovie);
        replaceSourceBk2(runDir, "../outside.bk2");

        assertTrue(TraceCatalog.scan(root).stream().noneMatch(TraceEntry::isRun));
    }

    @Test
    void discoversGeneratedS2RunWithLocalMovie(@TempDir Path tracesRoot) throws Exception {
        Path runDir = TraceV5RunFixture.writeS2SpecialStageRun(tracesRoot.resolve("s2/runs"));
        TraceV5RunFixture.writeMovie(runDir.resolve("synthetic.bk2"));
        TraceRunManifest manifest = TraceRunManifest.load(
                runDir.resolve("run_manifest.json"));
        manifest.validate(runDir);
        assertEquals(runDir.resolve("synthetic.bk2"),
                TraceCatalog.resolveRunBk2(runDir, manifest));

        TraceEntry run = TraceCatalog.scan(tracesRoot).stream()
                .filter(TraceEntry::isRun)
                .filter(entry -> "run_ehz_ss_3seg"
                        .equals(entry.runManifest().runId()))
                .findFirst()
                .orElseThrow();

        assertEquals(runDir.resolve("synthetic.bk2"),
                run.bk2Path());
    }

    @Test
    void syntheticRunSubtreeIsExcludedFromDiscovery(@TempDir Path root) throws Exception {
        // Place an OTHERWISE-VALID run under synthetic/runs/ — without the scanRuns
        // synthetic filter it would be discovered. Assert it is excluded, mirroring
        // the level scan's synthetic exclusion.
        Path runDir = TraceV5RunFixture.writeS3kBonusRun(root.resolve("synthetic/runs"));
        Path movies = root.resolve("synthetic").resolve("_movies");
        TraceV5RunFixture.writeMovie(movies.resolve("synthetic.bk2"));

        List<TraceEntry> entries = TraceCatalog.scan(root);
        assertTrue(entries.stream().noneMatch(TraceEntry::isRun),
                "a run under synthetic/runs/ must be excluded from discovery");
    }

    @Test
    void invalidRunIsSkippedNotFatal(@TempDir Path root) throws Exception {
        Path badRun = root.resolve("s3k").resolve("runs").resolve("broken");
        Files.createDirectories(badRun);
        Files.writeString(badRun.resolve("run_manifest.json"), "{}");
        List<TraceEntry> entries = TraceCatalog.scan(root);
        assertTrue(entries.stream().noneMatch(TraceEntry::isRun));
    }

    private static Path copySyntheticRun(Path root, String game) throws IOException {
        return TraceV5RunFixture.writeS3kBonusRun(root.resolve(game).resolve("runs"));
    }

    private static void replaceSourceBk2(Path runDir, String sourceBk2)
            throws IOException {
        Path manifest = runDir.resolve("run_manifest.json");
        String json = Files.readString(manifest);
        Files.writeString(manifest,
                json.replace("\"source_bk2\": \"synthetic.bk2\"",
                        "\"source_bk2\": \"" + sourceBk2.replace("\\", "\\\\") + "\""));
    }

}
