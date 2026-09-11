package com.openggf.trace.timing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.trace.TraceMetadata;
import com.openggf.trace.TraceRunManifest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the immutable publication evidence for the approved S3K hardware-timing
 * fleet. The TSV expectations are reviewed literals from native candidates,
 * never values derived by invoking the recorder during this test.
 */
class TestCommittedHardwareTimingFixtures {

    private static final Path FIXTURE_ROOT = resolveProjectRoot()
            .resolve("src/test/resources/traces/s3k");
    private static final Path PUBLICATION_MANIFEST =
            FIXTURE_ROOT.resolve("hardware-timing-publication.tsv");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CANDIDATE_B_COMPLETE_RUN_OWNER =
            "v642-complete-sonic-tails-candidate-b";
    private static final Map<String, RunOwnership> EXPECTED_RUN_OWNERSHIP =
            Map.of(
                    "runs/s3-knux-multibonus-ss/",
                    new RunOwnership("v637-knuckles-b",
                            "s3-knux-multibonus-ss"),
                    "runs/s3k-knuckles-complete-superemeralds/",
                    new RunOwnership("v638-knuckles-superemeralds",
                            "s3k-knuckles-complete-superemeralds"));
    private static final Map<String, Ownership> EXPECTED_STANDALONE_OWNERSHIP =
            Map.ofEntries(
                    Map.entry("aiz1_to_hcz_fullrun",
                            new Ownership("v638-aiz-hcz-transition",
                                    "aiz1_to_hcz_fullrun")),
                    Map.entry("cnz", new Ownership("v637-standard-cnz", ".")),
                    Map.entry("mgz", new Ownership("v637-standard-mgz", ".")),
                    Map.entry("aiz_completerun",
                            new Ownership(CANDIDATE_B_COMPLETE_RUN_OWNER, "aiz")),
                    Map.entry("hcz_completerun",
                            new Ownership(CANDIDATE_B_COMPLETE_RUN_OWNER, "hcz")),
                    Map.entry("mgz_completerun",
                            new Ownership(CANDIDATE_B_COMPLETE_RUN_OWNER, "mgz")),
                    Map.entry("cnz_completerun",
                            new Ownership(CANDIDATE_B_COMPLETE_RUN_OWNER, "cnz")),
                    Map.entry("icz_completerun",
                            new Ownership(CANDIDATE_B_COMPLETE_RUN_OWNER, "icz")),
                    Map.entry("lbz_completerun",
                            new Ownership(CANDIDATE_B_COMPLETE_RUN_OWNER, "lbz")),
                    Map.entry("mhz_completerun",
                            new Ownership(CANDIDATE_B_COMPLETE_RUN_OWNER, "mhz")),
                    Map.entry("fbz_completerun",
                            new Ownership(CANDIDATE_B_COMPLETE_RUN_OWNER, "fbz")),
                    Map.entry("soz_completerun",
                            new Ownership(CANDIDATE_B_COMPLETE_RUN_OWNER, "soz")),
                    Map.entry("lrz_completerun",
                            new Ownership(CANDIDATE_B_COMPLETE_RUN_OWNER, "lrz")),
                    Map.entry("hpz_completerun",
                            new Ownership(CANDIDATE_B_COMPLETE_RUN_OWNER, "hpz22")),
                    Map.entry("ssz_completerun",
                            new Ownership(CANDIDATE_B_COMPLETE_RUN_OWNER, "hpz")),
                    Map.entry("dez_completerun",
                            new Ownership(CANDIDATE_B_COMPLETE_RUN_OWNER, "ssz")),
                    Map.entry("ddz_completerun",
                            new Ownership(CANDIDATE_B_COMPLETE_RUN_OWNER, "dez23")),
                    Map.entry("bonus_gumball",
                            new Ownership("v637-knuckles-c", "gumball")),
                    Map.entry("bonus_slots",
                            new Ownership("v637-knuckles-c", "slots")),
                    Map.entry("bonus_pachinko",
                            new Ownership("v637-knuckles-c", "pachinko")),
                    Map.entry("special_stage",
                            new Ownership("v637-knuckles-c", "ss")));
    private static final Set<String> EXPECTED_DESTINATIONS = Set.of(
            "aiz1_to_hcz_fullrun",
            "aiz_completerun",
            "bonus_gumball",
            "bonus_pachinko",
            "bonus_slots",
            "cnz",
            "cnz_completerun",
            "ddz_completerun",
            "dez_completerun",
            "fbz_completerun",
            "hcz_completerun",
            "hpz_completerun",
            "icz_completerun",
            "lbz_completerun",
            "lrz_completerun",
            "mgz",
            "mgz_completerun",
            "mhz_completerun",
            "runs/s3-knux-multibonus-ss/aiz",
            "runs/s3-knux-multibonus-ss/aiz_2",
            "runs/s3-knux-multibonus-ss/aiz_3",
            "runs/s3-knux-multibonus-ss/aiz_4",
            "runs/s3-knux-multibonus-ss/aiz_5",
            "runs/s3-knux-multibonus-ss/gumball",
            "runs/s3-knux-multibonus-ss/gumball_2",
            "runs/s3-knux-multibonus-ss/hcz",
            "runs/s3-knux-multibonus-ss/hcz_2",
            "runs/s3-knux-multibonus-ss/hcz_3",
            "runs/s3-knux-multibonus-ss/hcz_4",
            "runs/s3-knux-multibonus-ss/hcz_5",
            "runs/s3-knux-multibonus-ss/hcz_6",
            "runs/s3-knux-multibonus-ss/mgz",
            "runs/s3-knux-multibonus-ss/mgz_2",
            "runs/s3-knux-multibonus-ss/mgz_3",
            "runs/s3-knux-multibonus-ss/pachinko",
            "runs/s3-knux-multibonus-ss/slots",
            "runs/s3-knux-multibonus-ss/slots_2",
            "runs/s3-knux-multibonus-ss/slots_3",
            "runs/s3-knux-multibonus-ss/slots_4",
            "runs/s3-knux-multibonus-ss/slots_5",
            "runs/s3-knux-multibonus-ss/ss",
            "runs/s3-knux-multibonus-ss/ss_2",
            "runs/s3-knux-multibonus-ss/ss_3",
            "runs/s3k-knuckles-complete-superemeralds/aiz",
            "runs/s3k-knuckles-complete-superemeralds/ss",
            "runs/s3k-knuckles-complete-superemeralds/aiz_2",
            "runs/s3k-knuckles-complete-superemeralds/ss_2",
            "runs/s3k-knuckles-complete-superemeralds/aiz_3",
            "runs/s3k-knuckles-complete-superemeralds/pachinko",
            "runs/s3k-knuckles-complete-superemeralds/aiz_4",
            "runs/s3k-knuckles-complete-superemeralds/pachinko_2",
            "runs/s3k-knuckles-complete-superemeralds/aiz_5",
            "runs/s3k-knuckles-complete-superemeralds/hcz",
            "runs/s3k-knuckles-complete-superemeralds/pachinko_3",
            "runs/s3k-knuckles-complete-superemeralds/hcz_2",
            "runs/s3k-knuckles-complete-superemeralds/ss_3",
            "runs/s3k-knuckles-complete-superemeralds/hcz_3",
            "runs/s3k-knuckles-complete-superemeralds/gumball",
            "runs/s3k-knuckles-complete-superemeralds/hcz_4",
            "runs/s3k-knuckles-complete-superemeralds/ss_4",
            "runs/s3k-knuckles-complete-superemeralds/hcz_5",
            "runs/s3k-knuckles-complete-superemeralds/slots",
            "runs/s3k-knuckles-complete-superemeralds/hcz_6",
            "runs/s3k-knuckles-complete-superemeralds/ss_5",
            "runs/s3k-knuckles-complete-superemeralds/hcz_7",
            "runs/s3k-knuckles-complete-superemeralds/mgz",
            "runs/s3k-knuckles-complete-superemeralds/ss_6",
            "runs/s3k-knuckles-complete-superemeralds/mgz_2",
            "runs/s3k-knuckles-complete-superemeralds/cnz",
            "runs/s3k-knuckles-complete-superemeralds/ss_7",
            "runs/s3k-knuckles-complete-superemeralds/cnz_2",
            "runs/s3k-knuckles-complete-superemeralds/icz",
            "runs/s3k-knuckles-complete-superemeralds/lbz",
            "runs/s3k-knuckles-complete-superemeralds/gumball_2",
            "runs/s3k-knuckles-complete-superemeralds/lbz_2",
            "runs/s3k-knuckles-complete-superemeralds/mhz",
            "runs/s3k-knuckles-complete-superemeralds/dez23",
            "runs/s3k-knuckles-complete-superemeralds/ss_8",
            "runs/s3k-knuckles-complete-superemeralds/mhz_2",
            "runs/s3k-knuckles-complete-superemeralds/dez23_2",
            "runs/s3k-knuckles-complete-superemeralds/ss_9",
            "runs/s3k-knuckles-complete-superemeralds/mhz_3",
            "runs/s3k-knuckles-complete-superemeralds/dez23_3",
            "runs/s3k-knuckles-complete-superemeralds/ss_10",
            "runs/s3k-knuckles-complete-superemeralds/mhz_4",
            "runs/s3k-knuckles-complete-superemeralds/dez23_4",
            "runs/s3k-knuckles-complete-superemeralds/ss_11",
            "runs/s3k-knuckles-complete-superemeralds/mhz_5",
            "runs/s3k-knuckles-complete-superemeralds/dez23_5",
            "runs/s3k-knuckles-complete-superemeralds/ss_12",
            "runs/s3k-knuckles-complete-superemeralds/mhz_6",
            "runs/s3k-knuckles-complete-superemeralds/fbz",
            "runs/s3k-knuckles-complete-superemeralds/dez23_6",
            "runs/s3k-knuckles-complete-superemeralds/ss_13",
            "runs/s3k-knuckles-complete-superemeralds/fbz_2",
            "runs/s3k-knuckles-complete-superemeralds/gumball_3",
            "runs/s3k-knuckles-complete-superemeralds/fbz_3",
            "runs/s3k-knuckles-complete-superemeralds/dez23_7",
            "runs/s3k-knuckles-complete-superemeralds/ss_14",
            "runs/s3k-knuckles-complete-superemeralds/fbz_4",
            "runs/s3k-knuckles-complete-superemeralds/soz",
            "runs/s3k-knuckles-complete-superemeralds/pachinko_4",
            "runs/s3k-knuckles-complete-superemeralds/soz_2",
            "runs/s3k-knuckles-complete-superemeralds/lrz",
            "runs/s3k-knuckles-complete-superemeralds/pachinko_5",
            "runs/s3k-knuckles-complete-superemeralds/lrz_2",
            "runs/s3k-knuckles-complete-superemeralds/slots_2",
            "runs/s3k-knuckles-complete-superemeralds/lrz_3",
            "runs/s3k-knuckles-complete-superemeralds/hpz22",
            "runs/s3k-knuckles-complete-superemeralds/hpz",
            "soz_completerun",
            "special_stage",
            "ssz_completerun");
    /**
     * Approved schema-2 fixtures whose represented route reaches a gameplay
     * consumer of {@code Kos_decomp_queue_count}. Each must carry both direct
     * and module ledgers at its ROM-owned boundaries.
     *
     * <p>The multi-bonus run's first AIZ segment begins at camera X 0x1300,
     * after the intro consumer, so it is intentionally absent.</p>
     */
    private static final Map<String, String> SCHEMA_TWO_DIRECT_CONSUMER_FIXTURES =
            Map.ofEntries(
                    Map.entry("aiz1_to_hcz_fullrun", "AIZ intro"),
                    Map.entry("aiz_completerun", "AIZ intro"),
                    Map.entry("hcz_completerun",
                            "HCZ decompression consumer"),
                    Map.entry("mgz_completerun",
                            "MGZ decompression consumer"),
                    Map.entry("cnz_completerun",
                            "CNZ decompression consumer"),
                    Map.entry("icz_completerun",
                            "ICZ1-to-ICZ2 act transition"),
                    Map.entry(
                            "runs/s3k-knuckles-complete-superemeralds/aiz",
                            "AIZ intro"),
                    Map.entry(
                            "runs/s3k-knuckles-complete-superemeralds/icz",
                            "ICZ act/zone transition"));

    @Test
    void approvedFleetMatchesFrozenPublicationEvidence() throws Exception {
        Set<String> observedDestinations = new HashSet<>();
        List<RunManifestExpectation> runManifests = new ArrayList<>();

        for (String line : Files.readAllLines(PUBLICATION_MANIFEST)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split("\t", -1);
            switch (fields[0]) {
                case "FIXTURE" -> {
                    ExpectedFixture expected = ExpectedFixture.parse(fields);
                    assertTrue(observedDestinations.add(expected.directory()),
                            "duplicate publication destination " + expected.directory());
                    assertOwnership(expected);
                    assertFixture(expected);
                }
                case "RUN_MANIFEST" ->
                        runManifests.add(RunManifestExpectation.parse(fields));
                default -> throw new IllegalArgumentException(
                        "Unknown publication record " + fields[0]);
            }
        }

        assertEquals(EXPECTED_DESTINATIONS, observedDestinations);
        assertEquals(2, runManifests.size(),
                "publication must contain each approved run manifest once");
        assertEquals(Set.of(
                        new RunManifestExpectation(
                                "runs/s3-knux-multibonus-ss/run_manifest.json",
                                "v637-knuckles-b", "3.0", 8804,
                                "149c879ccb3e31cbe806b492dbe40cb060c5e4414e7666a0ab5199f0c7228302",
                                25, 22),
                        new RunManifestExpectation(
                                "runs/s3k-knuckles-complete-superemeralds/"
                                        + "run_manifest.json",
                                "v638-knuckles-superemeralds",
                                "3.0", 20804,
                                "b3581067db368a857137b16b41f90cff452782b0cc98188e9982de9c704c3474",
                                67, 48)),
                new HashSet<>(runManifests));
        for (RunManifestExpectation runManifest : runManifests) {
            assertRunManifest(runManifest);
        }
    }

    @Test
    void approvedDirectConsumerInventoryUsesSchemaTwoAndBothLedgers()
            throws Exception {
        assertEquals(Set.of(
                        "aiz1_to_hcz_fullrun",
                        "aiz_completerun",
                        "hcz_completerun",
                        "mgz_completerun",
                        "cnz_completerun",
                        "icz_completerun",
                        "runs/s3k-knuckles-complete-superemeralds/aiz",
                        "runs/s3k-knuckles-complete-superemeralds/icz"),
                SCHEMA_TWO_DIRECT_CONSUMER_FIXTURES.keySet());

        for (Map.Entry<String, String> inventory :
                SCHEMA_TWO_DIRECT_CONSUMER_FIXTURES.entrySet()) {
            String directory = inventory.getKey();
            assertTrue(EXPECTED_DESTINATIONS.contains(directory),
                    inventory.getValue());

            Path fixture = FIXTURE_ROOT.resolve(directory);
            TraceMetadata metadata =
                    TraceMetadata.load(fixture.resolve("metadata.json"));
            assertEquals(5, metadata.traceSchema(), inventory.getValue());

            List<HardwareCompletionEdge> edges =
                    HardwareTimingStreamLoader.load(fixture, metadata).edges();
            assertFalse(edges.isEmpty(), inventory.getValue());
            assertTrue(edges.stream().anyMatch(edge ->
                            edge.kind() == HardwareWorkKind.KOS_DECOMPRESSION_QUEUE
                                    && edge.boundary()
                                    == HardwareServiceBoundary.PRE_MAIN_LOOP),
                    inventory.getValue());
            assertTrue(edges.stream().anyMatch(edge ->
                            edge.kind() == HardwareWorkKind.KOS_MODULE_QUEUE
                                    && (edge.boundary()
                                    == HardwareServiceBoundary.VINT_SERVICE
                                    || edge.boundary()
                                    == HardwareServiceBoundary.POST_OBJECTS)),
                    inventory.getValue());
        }
    }

    private static void assertOwnership(ExpectedFixture expected) {
        Ownership ownership = EXPECTED_STANDALONE_OWNERSHIP.get(
                expected.directory());
        if (ownership == null) {
            RunOwnership run = expectedRunOwnership(expected.directory());
            if (run != null) {
                ownership = new Ownership(
                        run.owner(),
                        Path.of(expected.directory()).getFileName().toString());
            }
        }
        assertEquals(ownership,
                new Ownership(expected.owner(), expected.sourceSegment()),
                expected.directory());
    }

    private static void assertFixture(ExpectedFixture expected) throws Exception {
        Path fixture = FIXTURE_ROOT.resolve(expected.directory());
        Path metadataPath = fixture.resolve("metadata.json");
        JsonNode metadataJson = MAPPER.readTree(metadataPath.toFile());

        assertEquals(expected.traceSchema(), metadataJson.path("trace_schema").intValue(),
                expected.directory());
        // `recorder` / `recorder_version` are deliberately not asserted: hard rule 4
        // makes them opaque provenance that selects no replay behaviour, so pinning
        // their values gates nothing and only goes red the next time a fixture is
        // re-recorded. `trace_schema` above is the field that does select behaviour.
        assertEquals(expected.frameCount(),
                metadataJson.path("trace_frame_count").intValue(),
                expected.directory());
        RunOwnership run = expectedRunOwnership(expected.directory());
        if (run != null) {
            assertEquals(run.runId(), metadataJson.path("run_id").textValue(),
                    expected.directory());
        } else if ("v637-knuckles-c".equals(expected.owner())) {
            assertEquals("s3k-multibonus",
                    metadataJson.path("run_id").textValue(), expected.directory());
        } else {
            assertFalse(metadataJson.has("run_id"), expected.directory());
        }
        assertFalse(Files.exists(fixture.resolve("run_manifest.json")),
                expected.directory());

        assertFile(fixture, expected.metadata());
        assertFile(fixture, expected.physics());
        assertFile(fixture, expected.aux());
        assertFile(fixture, expected.timing());

        TraceMetadata metadata = TraceMetadata.load(metadataPath);
        List<HardwareCompletionEdge> edges =
                HardwareTimingStreamLoader.load(fixture, metadata).edges();
        assertEquals(expected.eventCount(), edges.size(), expected.directory());
        assertFalse(edges.isEmpty(), expected.directory());
        assertEdge(edges.getFirst(), expected.firstEdge(), expected.directory());
        assertEdge(edges.getLast(), expected.lastEdge(), expected.directory());
        assertTrue(edges.stream().allMatch(edge -> {
            if (edge.rawFrame() < 0
                    || edge.rawFrame() >= metadata.traceFrameCount()) {
                return false;
            }
            if (edge.kind() == HardwareWorkKind.KOS_DECOMPRESSION_QUEUE) {
                return expected.hardwareSchema() == 2
                        && edge.boundary()
                        == HardwareServiceBoundary.PRE_MAIN_LOOP;
            }
            return edge.kind() == HardwareWorkKind.KOS_MODULE_QUEUE
                    && (edge.boundary() == HardwareServiceBoundary.VINT_SERVICE
                    || edge.boundary() == HardwareServiceBoundary.POST_OBJECTS);
        }), expected.directory());
        assertEquals(expected.preMainLoopEventCount(),
                edges.stream().filter(edge ->
                        edge.boundary() == HardwareServiceBoundary.PRE_MAIN_LOOP).count(),
                expected.directory());
        assertEquals(expected.vintServiceEventCount(),
                edges.stream().filter(edge ->
                        edge.boundary() == HardwareServiceBoundary.VINT_SERVICE).count(),
                expected.directory());
        assertEquals(expected.postObjectsEventCount(),
                edges.stream().filter(edge ->
                        edge.boundary() == HardwareServiceBoundary.POST_OBJECTS).count(),
                expected.directory());
    }

    private static void assertRunManifest(RunManifestExpectation expected)
            throws Exception {
        Path path = FIXTURE_ROOT.resolve(expected.path());
        assertFile(path, expected.hash(), expected.bytes());
        TraceRunManifest manifest = TraceRunManifest.load(path);
        String prefix = expected.path().substring(
                0, expected.path().length() - "run_manifest.json".length());
        RunOwnership ownership = EXPECTED_RUN_OWNERSHIP.get(prefix);
        assertEquals(expected.owner(), ownership.owner(), expected.path());
        assertEquals(ownership.runId(), manifest.runId(), expected.path());
        assertEquals(expected.segmentCount(), manifest.segments().size());
        assertEquals(expected.transitionCount(), manifest.transitions().size());
    }

    private static RunOwnership expectedRunOwnership(String directory) {
        return EXPECTED_RUN_OWNERSHIP.entrySet().stream()
                .filter(entry -> directory.startsWith(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private static void assertEdge(HardwareCompletionEdge actual,
            EdgeExpectation expected, String fixture) {
        assertEquals(expected.rawFrame(), actual.rawFrame(), fixture);
        assertEquals(expected.ordinal(), actual.ordinal(), fixture);
        assertEquals(expected.boundary(), actual.boundary(), fixture);
        assertEquals(expected.kind(), actual.kind(), fixture);
        assertEquals(expected.fingerprint(), actual.submissionFingerprint(), fixture);
    }

    private static void assertFile(Path directory, FileExpectation expected)
            throws IOException, NoSuchAlgorithmException {
        assertFile(directory.resolve(expected.name()), expected.hash(), expected.bytes());
    }

    private static void assertFile(Path path, String expectedHash, long expectedBytes)
            throws IOException, NoSuchAlgorithmException {
        byte[] content = Files.readAllBytes(path);
        assertEquals(expectedBytes, content.length, path.toString());
        assertEquals(expectedHash,
                HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(content)),
                path.toString());
    }

    private static Path resolveProjectRoot() {
        String basedir = System.getProperty("project.basedir");
        return basedir == null || basedir.isEmpty()
                ? Paths.get(System.getProperty("user.dir", "."))
                : Paths.get(basedir);
    }

    private record ExpectedFixture(
            String directory,
            String owner,
            String sourceSegment,
            int traceSchema,
            int hardwareSchema,
            String recorderVersion,
            int frameCount,
            FileExpectation metadata,
            FileExpectation physics,
            FileExpectation aux,
            FileExpectation timing,
            int eventCount,
            int postObjectsEventCount,
            int vintServiceEventCount,
            EdgeExpectation firstEdge,
            EdgeExpectation lastEdge,
            int preMainLoopEventCount) {

        private static ExpectedFixture parse(String[] fields) {
            if (fields.length != 17 && fields.length != 18) {
                throw new IllegalArgumentException(
                        "Expected 17 or 18 fields, got " + fields.length);
            }
            return new ExpectedFixture(
                    fields[1], fields[2], fields[3],
                    Integer.parseInt(fields[4]), Integer.parseInt(fields[5]),
                    fields[6], Integer.parseInt(fields[7]),
                    FileExpectation.parse(fields[8]),
                    FileExpectation.parse(fields[9]),
                    FileExpectation.parse(fields[10]),
                    FileExpectation.parse(fields[11]),
                    Integer.parseInt(fields[12]), Integer.parseInt(fields[13]),
                    Integer.parseInt(fields[14]),
                    EdgeExpectation.parse(fields[15]),
                    EdgeExpectation.parse(fields[16]),
                    fields.length == 18 ? Integer.parseInt(fields[17]) : 0);
        }
    }

    private record FileExpectation(String name, long bytes, String hash) {
        private static FileExpectation parse(String field) {
            String[] parts = field.split(":", 3);
            assertFieldCount(parts, 3);
            return new FileExpectation(
                    parts[0], Long.parseLong(parts[1]), parts[2]);
        }
    }

    private record Ownership(String owner, String sourceSegment) {
    }

    private record RunOwnership(String owner, String runId) {
    }

    private record EdgeExpectation(
            int rawFrame,
            long ordinal,
            HardwareServiceBoundary boundary,
            HardwareWorkKind kind,
            String fingerprint) {

        private static EdgeExpectation parse(String field) {
            String[] parts = field.split(":", 5);
            assertFieldCount(parts, 5);
            return new EdgeExpectation(
                    Integer.parseInt(parts[0]), Long.parseLong(parts[1]),
                    HardwareServiceBoundary.fromWireName(parts[2]),
                    HardwareWorkKind.fromWireName(parts[3]), parts[4]);
        }
    }

    private record RunManifestExpectation(
            String path,
            String owner,
            String legacyRecorderVersion,
            long bytes,
            String hash,
            int segmentCount,
            int transitionCount) {

        private static RunManifestExpectation parse(String[] fields) {
            assertFieldCount(fields, 8);
            return new RunManifestExpectation(
                    fields[1], fields[2], fields[3], Long.parseLong(fields[4]),
                    fields[5], Integer.parseInt(fields[6]),
                    Integer.parseInt(fields[7]));
        }
    }

    private static void assertFieldCount(String[] fields, int expected) {
        if (fields.length != expected) {
            throw new IllegalArgumentException(
                    "Expected " + expected + " fields, got " + fields.length);
        }
    }
}
