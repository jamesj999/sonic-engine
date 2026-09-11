package com.openggf.trace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guard: no uncompressed trace payload may be committed under
 * {@code src/test/resources/traces/}.
 *
 * <p>This is a size gate, not a tidiness one. A recorded payload is enormous
 * next to its gzip — a full S3K complete-run {@code aux_state.jsonl} measures
 * ~254 MB raw against ~12 MB gzipped, about 21x — so an uncompressed one is
 * past GitHub's 100 MB per-file hard limit and simply cannot be pushed. That
 * has bitten this project before, on full runs captured with the extended
 * memory hooks enabled, whose aux streams dwarf the hooks-off captures.
 *
 * <p>The native harness compresses at publication time by default
 * ({@code tools/tracechaser/bizhawk-headless}, {@code --no-compress} to opt out), but that
 * covers one producer. This guard covers the goal instead of the tool: a Lua
 * capture, a stable-retro capture, or a file copied in by hand is caught just
 * the same, because the check is on what the fixture tree contains.
 *
 * <p>The 36 payloads already tracked when the guard landed (16.2 MB, largest
 * 4.0 MB — the S1 {@code credits_*} and {@code *_fullrun} stable-retro
 * fixtures) are grandfathered through {@link #BASELINE}. They are canonical
 * fixtures that tests read by name; recompressing them is a user decision, not
 * a cleanup. Following the idiom of the rewind coverage guards, a new
 * uncompressed payload fails and a baseline entry that has since been
 * compressed is reported for tightening rather than failed.
 *
 * <p>The fixture tree is read-only ground truth, so this also catches a fresh
 * capture written into it: captures belong in scratch until they are
 * deliberately installed.
 */
class TestTraceFixtureCompressionGuard {

    /**
     * Paths resolve against the project root supplied by the surefire system
     * property {@code project.basedir} (falling back to the JVM working
     * directory), so the guard works from any IDE or Maven invocation — the
     * same idiom as the other repo-level guards.
     */
    private static final Path PROJECT_ROOT = resolveProjectRoot();

    private static final Path FIXTURE_ROOT =
            PROJECT_ROOT.resolve("src/test/resources/traces");

    private static final Path BASELINE = PROJECT_ROOT.resolve(
            "src/test/resources/trace-guard/uncompressed-payload-baseline.txt");

    private static Path resolveProjectRoot() {
        String basedir = System.getProperty("project.basedir");
        if (basedir != null && !basedir.isEmpty()) {
            return Paths.get(basedir);
        }
        return Paths.get(System.getProperty("user.dir", "."));
    }

    /**
     * The same two name patterns the repo's commit policy
     * ({@code .githooks/validate-policy.sh}) and the harness compressor use.
     * A {@code .gz} sibling does not match, which is the whole point.
     */
    private static boolean isUncompressedPayload(String fileName) {
        return (fileName.startsWith("aux_state") && fileName.endsWith(".jsonl"))
                || (fileName.startsWith("physics") && fileName.endsWith(".csv"));
    }

    private static Set<String> scanUncompressedPayloads(Path root)
            throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> tree = Files.walk(root)) {
            tree.filter(Files::isRegularFile)
                    .filter(path -> isUncompressedPayload(
                            path.getFileName().toString()))
                    .forEach(path -> found.add(relativize(path)));
        }
        return found;
    }

    /**
     * Repo-relative when the path is under the project root (so the baseline
     * stays portable), absolute otherwise — which is what the temp-tree case
     * below compares against.
     */
    private static String relativize(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path root = PROJECT_ROOT.toAbsolutePath().normalize();
        Path relative = absolute.startsWith(root)
                ? root.relativize(absolute)
                : absolute;
        return relative.toString().replace('\\', '/');
    }

    @Test
    void noUncompressedTracePayloadsBeyondBaseline() throws IOException {
        assertTrue(Files.isDirectory(FIXTURE_ROOT),
                "Trace fixture root is missing: " + FIXTURE_ROOT);

        Set<String> current = scanUncompressedPayloads(FIXTURE_ROOT);
        Set<String> baseline = readBaseline();

        Set<String> additions = new TreeSet<>(current);
        additions.removeAll(baseline);
        assertTrue(additions.isEmpty(),
                "Uncompressed trace payload(s) under " + FIXTURE_ROOT + ":\n  "
                        + String.join("\n  ", additions)
                        + "\nThese cannot be pushed once they pass 100 MB and are"
                        + " ~21x larger than they need to be. Gzip each one"
                        + " (`gzip -9 -n <file>`, or capture with the native"
                        + " harness, which compresses by default) and commit the"
                        + " .gz instead. A capture that is not being installed as"
                        + " a fixture does not belong under " + FIXTURE_ROOT
                        + " at all. Only if an uncompressed fixture is genuinely"
                        + " required, add its path to " + BASELINE
                        + " with the reason in the commit message.");

        // Informational, matching the rewind coverage guards: a baseline entry
        // that has since been compressed (or removed) can be dropped.
        Set<String> stale = new TreeSet<>(baseline);
        stale.removeAll(current);
        if (!stale.isEmpty()) {
            System.out.println(
                    "[trace-compression] baseline entries no longer present"
                            + " (tighten baseline):\n  "
                            + String.join("\n  ", stale));
        }
    }

    /**
     * Proves the guard bites, against a temp tree rather than the read-only
     * fixture tree: a newly added payload of either name is caught at any
     * depth, while the gzips, the sidecar JSON and an unrelated CSV are not.
     * Without this, a guard that silently matched nothing would still report
     * green forever.
     */
    @Test
    void scanCatchesNewUncompressedPayloadsAndIgnoresTheRest(
            @TempDir Path root) throws IOException {
        Path segment = root.resolve("lbz_completerun");
        Files.createDirectories(segment);
        Files.writeString(segment.resolve("aux_state.jsonl"), "{}\n");
        Files.writeString(segment.resolve("physics.csv"), "frame\n");
        Files.writeString(segment.resolve("physics_special_stage.csv"), "f\n");
        Files.writeString(segment.resolve("aux_state.jsonl.gz"), "gz");
        Files.writeString(segment.resolve("physics.csv.gz"), "gz");
        Files.writeString(segment.resolve("metadata.json"), "{}\n");
        Files.writeString(root.resolve("run_manifest.json"), "{}\n");

        Set<String> found = scanUncompressedPayloads(root);

        Set<String> expected = new TreeSet<>(Set.of(
                relativize(segment.resolve("aux_state.jsonl")),
                relativize(segment.resolve("physics.csv")),
                relativize(segment.resolve("physics_special_stage.csv"))));
        assertEquals(expected, found);
    }

    private static Set<String> readBaseline() throws IOException {
        Set<String> entries = new TreeSet<>();
        for (String line : Files.readAllLines(BASELINE)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                entries.add(trimmed);
            }
        }
        return entries;
    }
}
