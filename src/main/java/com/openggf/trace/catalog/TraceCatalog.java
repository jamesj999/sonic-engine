package com.openggf.trace.catalog;

import com.openggf.debug.playback.Bk2Movie;
import com.openggf.debug.playback.Bk2MovieLoader;
import com.openggf.game.save.SelectedTeam;
import com.openggf.trace.TraceFiles;
import com.openggf.trace.TraceMetadata;
import com.openggf.trace.TraceRunManifest;
import com.openggf.trace.replay.runs.TraceRunReplayWalker;
import com.openggf.trace.replay.runs.TraceRunSegmentDescriptor;

import java.io.IOException;
import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Scans a traces root directory (default {@code src/test/resources/traces}) and
 * returns an immutable, sorted list of valid {@link TraceEntry} records.
 *
 * <p>A directory is a valid trace iff it contains {@code metadata.json},
 * {@code physics.csv} (or {@code physics.csv.gz}), a resolvable BK2 movie, and
 * its metadata declares one of the supported games ({@code s1}, {@code s2},
 * {@code s3k}). The BK2 movie is resolved either from a shared, deduplicated
 * copy referenced by {@code metadata.source_bk2} (stored once under
 * {@code <game>/_movies/}) or from a per-directory {@code .bk2} movie
 * placement retained for development fixtures. That placement fallback is
 * not a trace-schema compatibility path: v5 metadata and row files are still
 * required. The {@code synthetic/} subtree is always filtered out.
 */
public final class TraceCatalog {
    private static final Logger LOGGER = Logger.getLogger(TraceCatalog.class.getName());

    @FunctionalInterface
    interface RunMovieLoader {
        Bk2Movie load(Path movie) throws IOException;
    }

    @FunctionalInterface
    interface RunDescriptorPlanner {
        List<TraceRunSegmentDescriptor> plan(
                TraceRunManifest manifest, Path runDir) throws IOException;
    }

    private static final RunDescriptorPlanner DEFAULT_RUN_DESCRIPTOR_PLANNER =
            TraceRunReplayWalker::planDescriptors;
    private static final List<String> VALID_GAME_IDS = List.of("s1", "s2", "s3k");
    private static final Comparator<String> GAME_ORDER =
            Comparator.comparingInt(VALID_GAME_IDS::indexOf);

    private TraceCatalog() {
    }

    /** Result of deeper validation performed when a discovered run is selected. */
    public record RunLaunchValidation(boolean launchable, String diagnostic) {
        private static RunLaunchValidation valid() {
            return new RunLaunchValidation(true, "");
        }

        private static RunLaunchValidation invalid(String diagnostic) {
            return new RunLaunchValidation(false, diagnostic);
        }
    }

    /** Immutable launch metadata that retains no eagerly opened segment payload. */
    public record PreparedDescriptorRunLaunch(
            Bk2Movie movie,
            List<TraceRunSegmentDescriptor> segments) {
        public PreparedDescriptorRunLaunch {
            Objects.requireNonNull(movie, "movie");
            segments = List.copyOf(segments);
        }
    }

    public static List<TraceEntry> scan(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<TraceEntry> entries = new ArrayList<>();
        // Depth 2 reaches `<root>/<game>/<trace-dir>` — the exact level
        // where every valid trace lives. Bounding the walk here avoids
        // multi-minute whole-project scans if TRACE_CATALOG_DIR is
        // misconfigured and resolves to the project root.
        try (Stream<Path> stream = Files.walk(root, 2)) {
            stream.filter(Files::isDirectory)
                    .filter(p -> !isSyntheticSubtree(root, p))
                    .forEach(dir -> tryLoad(dir).ifPresent(entries::add));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not scan " + root, e);
        }
        scanRuns(root, entries);
        entries.sort(Comparator
                .comparing(TraceEntry::gameId, GAME_ORDER)
                .thenComparingInt(TraceEntry::zone)
                .thenComparingInt(TraceEntry::act)
                .thenComparing(e -> e.dir().getFileName().toString()));
        return Collections.unmodifiableList(entries);
    }

    /**
     * Performs selection-time validation that is deliberately deeper than
     * discovery. A failure is returned as a diagnostic so the picker can keep
     * the catalog entry visible instead of silently filtering it out.
     */
    public static RunLaunchValidation validateRunLaunch(TraceEntry entry) {
        return validateRunLaunch(
                entry,
                path -> new Bk2MovieLoader().load(path),
                DEFAULT_RUN_DESCRIPTOR_PLANNER);
    }

    static RunLaunchValidation validateRunLaunch(
            TraceEntry entry,
            RunMovieLoader movieLoader,
            RunDescriptorPlanner descriptorPlanner) {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(movieLoader, "movieLoader");
        Objects.requireNonNull(descriptorPlanner, "descriptorPlanner");
        try {
            validateRunLaunchPayloads(entry, movieLoader, descriptorPlanner);
            return RunLaunchValidation.valid();
        } catch (IOException | RuntimeException e) {
            return RunLaunchValidation.invalid(diagnosticMessage(e));
        }
    }

    private static void validateRunLaunchPayloads(
            TraceEntry entry,
            RunMovieLoader movieLoader,
            RunDescriptorPlanner descriptorPlanner) throws IOException {
        if (!entry.isRun()) {
            throw new IllegalArgumentException("Catalog entry is not a trace run");
        }
        TraceRunManifest manifest = entry.runManifest();
        TraceRunManifest.Segment first = manifest.segments().getFirst();
        if (!"level".equals(first.kind())) {
            throw new IllegalArgumentException(
                    "Visual run segment 0 must be level, got " + first.kind());
        }
        Bk2Movie movie;
        try {
            movie = movieLoader.load(entry.bk2Path());
        } catch (IOException | RuntimeException e) {
            throw new IOException(
                    "Run BK2 parser failed: " + diagnosticMessage(e), e);
        }
        List<TraceRunSegmentDescriptor> descriptors =
                descriptorPlanner.plan(manifest, entry.runDir());
        for (int i = 0; i < manifest.segments().size(); i++) {
            TraceRunManifest.Segment segment = manifest.segments().get(i);
            int end;
            try {
                end = Math.addExact(
                        segment.bk2FrameOffset(), segment.traceFrameCount());
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException(
                        "Segment " + i + " BK2 range overflows", e);
            }
            if (segment.bk2FrameOffset() < 0 || end > movie.getFrameCount()) {
                throw new IllegalArgumentException(
                        "Segment " + i + " BK2 range ["
                                + segment.bk2FrameOffset() + ", " + end
                                + ") exceeds movie row count " + movie.getFrameCount());
            }
            TraceRunSegmentDescriptor descriptor = descriptors.get(i);
            if (!profilesCompatible(segment, descriptor.metadata())) {
                throw new IllegalArgumentException(
                        "Segment " + i + " profile mismatch: manifest='"
                                + segment.traceProfile() + "', metadata='"
                                + descriptor.metadata().traceProfile() + "'");
            }
            if (descriptor.rowCount() != segment.traceFrameCount()) {
                throw new IllegalArgumentException(
                        "Segment " + i + " row count mismatch: manifest="
                                + segment.traceFrameCount() + ", parsed="
                                + descriptor.rowCount());
            }
        }
    }

    /**
     * Parses the selected run's BK2 movie and compact segment descriptors
     * without constructing eager replay payloads.
     */
    public static PreparedDescriptorRunLaunch prepareDescriptorRunLaunch(
            TraceEntry entry) throws IOException {
        return prepareDescriptorRunLaunch(
                entry,
                path -> new Bk2MovieLoader().load(path),
                DEFAULT_RUN_DESCRIPTOR_PLANNER);
    }

    static PreparedDescriptorRunLaunch prepareDescriptorRunLaunch(
            TraceEntry entry,
            RunMovieLoader movieLoader,
            RunDescriptorPlanner descriptorPlanner) throws IOException {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(movieLoader, "movieLoader");
        Objects.requireNonNull(descriptorPlanner, "descriptorPlanner");
        if (!entry.isRun()) {
            throw new IllegalArgumentException("Catalog entry is not a trace run");
        }
        TraceRunManifest manifest = entry.runManifest();
        TraceRunManifest.Segment first = manifest.segments().getFirst();
        if (!"level".equals(first.kind())) {
            throw new IllegalArgumentException(
                    "Visual run segment 0 must be level, got " + first.kind());
        }
        Bk2Movie movie;
        try {
            movie = movieLoader.load(entry.bk2Path());
        } catch (IOException | RuntimeException e) {
            throw new IOException(
                    "Run BK2 parser failed: " + diagnosticMessage(e), e);
        }
        List<TraceRunSegmentDescriptor> descriptors =
                descriptorPlanner.plan(manifest, entry.runDir());
        for (int i = 0; i < manifest.segments().size(); i++) {
            TraceRunManifest.Segment segment = manifest.segments().get(i);
            int end;
            try {
                end = Math.addExact(
                        segment.bk2FrameOffset(), segment.traceFrameCount());
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException(
                        "Segment " + i + " BK2 range overflows", e);
            }
            if (segment.bk2FrameOffset() < 0 || end > movie.getFrameCount()) {
                throw new IllegalArgumentException(
                        "Segment " + i + " BK2 range ["
                                + segment.bk2FrameOffset() + ", " + end
                                + ") exceeds movie row count " + movie.getFrameCount());
            }
            TraceRunSegmentDescriptor descriptor = descriptors.get(i);
            if (!profilesCompatible(segment, descriptor.metadata())) {
                throw new IllegalArgumentException(
                        "Segment " + i + " profile mismatch: manifest='"
                                + segment.traceProfile() + "', metadata='"
                                + descriptor.metadata().traceProfile() + "'");
            }
            if (descriptor.rowCount() != segment.traceFrameCount()) {
                throw new IllegalArgumentException(
                        "Segment " + i + " row count mismatch: manifest="
                                + segment.traceFrameCount() + ", parsed="
                                + descriptor.rowCount());
            }
        }
        return new PreparedDescriptorRunLaunch(movie, descriptors);
    }

    private static boolean profilesCompatible(
            TraceRunManifest.Segment segment, TraceMetadata metadata) {
        if (Objects.equals(segment.traceProfile(), metadata.traceProfile())) {
            return true;
        }
        return "level".equals(segment.kind())
                && "complete_run".equals(segment.traceProfile())
                && metadata.traceProfile() == null;
    }

    private static String diagnosticMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message;
    }

    private static boolean isSyntheticSubtree(Path root, Path dir) {
        Path rel = root.relativize(dir);
        return rel.getNameCount() > 0
                && "synthetic".equals(rel.getName(0).toString());
    }

    /**
     * Discovers multi-segment trace runs under {@code <root>/<game>/runs/*}
     * and appends one {@link TraceEntry} per valid run. A run that fails to
     * load, fails structural validation, or can't resolve its shared BK2
     * movie is skipped rather than failing the whole scan.
     */
    private static void scanRuns(Path root, List<TraceEntry> entries) {
        try (Stream<Path> gameDirs = Files.list(root)) {
            // Defense-in-depth: exclude a `synthetic/` game dir from run discovery,
            // mirroring the level scan's synthetic filter (:56). Synthetic fixtures
            // currently live under `synthetic/` (not `synthetic/runs/`) so this is
            // a no-op today, but it keeps run discovery aligned if that ever changes.
            for (Path gameDir : gameDirs.filter(Files::isDirectory)
                    .filter(gd -> !isSyntheticSubtree(root, gd)).toList()) {
                Path runsDir = gameDir.resolve("runs");
                if (!Files.isDirectory(runsDir)) {
                    continue;
                }
                try (Stream<Path> runDirs = Files.list(runsDir)) {
                    for (Path runDir : runDirs.filter(Files::isDirectory).toList()) {
                        tryLoadRun(runDir).ifPresent(entries::add);
                    }
                } catch (IOException e) {
                    LOGGER.log(Level.FINE, "Could not list runs under " + runsDir, e);
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Could not list game dirs under " + root, e);
        }
    }

    private static Optional<TraceEntry> tryLoadRun(Path runDir) {
        Path manifestPath = runDir.resolve("run_manifest.json");
        if (!Files.isRegularFile(manifestPath)) {
            return Optional.empty();
        }
        try {
            TraceRunManifest manifest = TraceRunManifest.load(manifestPath);
            manifest.validate(runDir);
            Path bk2 = resolveRunBk2(runDir, manifest);
            if (bk2 == null) {
                return Optional.empty();
            }
            return Optional.of(TraceEntry.forRun(runDir, manifest, bk2));
        } catch (IOException | RuntimeException e) {
            // TraceRunManifest.validate throws IllegalStateException; a
            // malformed manifest should be skipped, never crash the picker.
            LOGGER.log(Level.FINE, "Could not load run at " + runDir, e);
            return Optional.empty();
        }
    }

    /**
     * Resolves a run movie from the shared {@code <game>/_movies/} directory,
     * then from a contained path beneath {@code runDir}. Absolute paths and
     * parent traversal are rejected before either location is consulted.
     */
    public static Path resolveRunBk2(Path runDir, TraceRunManifest manifest) {
        if (manifest.sourceBk2() == null || manifest.sourceBk2().isBlank()) {
            return null;
        }
        Path source;
        try {
            source = Path.of(manifest.sourceBk2());
        } catch (RuntimeException e) {
            return null;
        }
        if (source.isAbsolute() || containsParentTraversal(source)) {
            return null;
        }
        Path runsDir = runDir.getParent();
        Path gameDir = runsDir != null ? runsDir.getParent() : null;
        if (gameDir == null) {
            return null;
        }
        Path sharedRoot = gameDir.resolve("_movies").normalize();
        Path shared = sharedRoot.resolve(source).normalize();
        if (shared.startsWith(sharedRoot) && Files.isRegularFile(shared)) {
            return shared;
        }
        Path localRoot = runDir.normalize();
        Path local = localRoot.resolve(source).normalize();
        return local.startsWith(localRoot) && Files.isRegularFile(local)
                ? local
                : null;
    }

    private static boolean containsParentTraversal(Path path) {
        for (Path component : path) {
            if ("..".equals(component.toString())) {
                return true;
            }
        }
        return false;
    }

    private static Optional<TraceEntry> tryLoad(Path dir) {
        Path metaPath = dir.resolve("metadata.json");
        Path physicsPath = TraceFiles.resolve(dir, "physics.csv");
        if (!Files.isRegularFile(metaPath) || physicsPath == null) {
            return Optional.empty();
        }
        TraceMetadata meta;
        int frameCount;
        try {
            meta = TraceMetadata.load(metaPath);
            frameCount = countCsvRows(physicsPath);
        } catch (IOException | IllegalArgumentException e) {
            LOGGER.log(Level.FINE, "Could not load trace at " + dir, e);
            return Optional.empty();
        }
        if (meta.game() == null || !VALID_GAME_IDS.contains(meta.game())) {
            return Optional.empty();
        }
        // Resolve the BK2 movie: prefer a shared, deduplicated movie referenced
        // by metadata.source_bk2 (stored once under <game>/_movies/), else use
        // the retained per-directory .bk2 movie placement. This is a movie
        // location fallback, not support for an older trace schema. Mirrors
        // AbstractTraceReplayTest so shared-movie traces (e.g. the S1
        // complete-run suite) appear in trace test mode, not just JUnit.
        Path bk2 = resolveBk2(dir, meta);
        if (bk2 == null) {
            return Optional.empty();
        }
        String main = meta.recordedMainCharacter();
        SelectedTeam team = new SelectedTeam(
                main == null ? "sonic" : main,
                meta.recordedSidekicks());
        int romZoneId = meta.zoneId() != null ? meta.zoneId() : 0;
        int engineZone = romZoneToProgressionIndex(meta.game(), romZoneId);
        // metadata "act" is 1-indexed (Act 1 == 1). Engine's
        // LevelManager.loadZoneAndAct expects 0-indexed acts.
        int engineAct = Math.max(0, meta.act() - 1);
        return Optional.of(new TraceEntry(
                dir,
                meta.game(),
                engineZone,
                engineAct,
                frameCount,
                meta.bk2FrameOffset(),
                meta.preTraceOscillationFrames(),
                team,
                bk2,
                meta));
    }

    /**
     * Resolve a trace directory's BK2 movie. A shared movie referenced by
     * {@code metadata.source_bk2} (looked up under the sibling
     * {@code <game>/_movies/} directory) wins; otherwise exactly one
     * per-directory {@code .bk2} movie is accepted. The latter is only a
     * placement fallback for v5 fixtures, not schema compatibility. Returns
     * {@code null} when no movie can be resolved (the trace is then skipped).
     */
    private static Path resolveBk2(Path dir, TraceMetadata meta) {
        if (meta.sourceBk2() != null && !meta.sourceBk2().isBlank()) {
            Path parent = dir.getParent();
            if (parent != null) {
                Path shared = parent.resolve("_movies").resolve(meta.sourceBk2());
                if (Files.isRegularFile(shared)) {
                    return shared;
                }
            }
            LOGGER.log(Level.FINE,
                    "source_bk2={0} declared but no shared movie found for {1}",
                    new Object[] {meta.sourceBk2(), dir});
        }
        List<Path> bk2s;
        try (Stream<Path> s = Files.list(dir)) {
            bk2s = s.filter(p -> p.getFileName().toString().endsWith(".bk2")).toList();
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "list() failed for " + dir, e);
            return null;
        }
        return bk2s.size() == 1 ? bk2s.get(0) : null;
    }

    /**
     * Convert a metadata ROM zone id into the engine's progression
     * zone index. For S2 and S3K these coincide, but S1 stores zones
     * in ROM order (GHZ=0, LZ=1, MZ=2, SLZ=3, SYZ=4, SBZ=5, FZ=6)
     * while the engine's {@code Sonic1ZoneRegistry} uses play order
     * (GHZ=0, MZ=1, SYZ=2, LZ=3, SLZ=4, SBZ=5, FZ=6).
     */
    static int romZoneToProgressionIndex(String gameId, int romZoneId) {
        if ("s1".equals(gameId)) {
            return switch (romZoneId) {
                case 0 -> 0;  // GHZ
                case 1 -> 3;  // LZ
                case 2 -> 1;  // MZ
                case 3 -> 4;  // SLZ
                case 4 -> 2;  // SYZ
                case 5 -> 5;  // SBZ
                case 6 -> 6;  // FZ
                default -> romZoneId;
            };
        }
        // S2 and S3K already use progression ordering in their
        // recorded zone_id.
        return romZoneId;
    }

    private static int countCsvRows(Path physicsCsv) throws IOException {
        try (BufferedReader reader = TraceFiles.openReader(physicsCsv)) {
            int count = 0;
            boolean firstDataLine = true;
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    if (firstDataLine && TraceFiles.isCsvHeader(line)) {
                        firstDataLine = false;
                        continue;
                    }
                    firstDataLine = false;
                    count++;
                }
            }
            return count;
        }
    }

}
