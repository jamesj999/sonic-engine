package com.openggf.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openggf.game.sonic1.specialstage.Sonic1SpecialStageTraceData;
import com.openggf.game.sonic3k.specialstage.S3kSpecialStageTraceData;
import com.openggf.trace.SpecialStageTraceData;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceMetadata;
import com.openggf.trace.TraceRunManifest;
import com.openggf.tests.TraceChaserTestSupport;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/** Runs TraceChaser's immutable v5 pack through OpenGGF's real Java consumers. */
class TestTraceChaserV5ConsumerContract {
    private static final Path PACK = Path.of("src/test/resources/tracechaser/v5");
    private static final Path PROVENANCE = Path.of("src/test/resources/tracechaser/provenance.json");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> EXPECTED_COPIES = Map.of(
            "contracts/v5", "src/test/resources/tracechaser/v5",
            "bizhawk-headless/fixtures/gpgx-audio-capability-v1.json",
                    "src/test/resources/tracechaser/gpgx-audio-capability-v1.json",
            "contracts/audio/normalization-contract-v1.json",
                    "src/test/resources/audio/parity/s1/normalization-contract-v1.json");

    @TempDir Path temporaryDirectory;

    @TestFactory
    Stream<DynamicTest> everyPublishedCaseMatchesJavaConsumerSemantics() throws IOException {
        JsonNode manifest = JSON.readTree(PACK.resolve("manifest.json").toFile());
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode testCase : manifest.path("cases")) {
            tests.add(DynamicTest.dynamicTest(testCase.path("id").asText(),
                    () -> executeCase(testCase)));
        }
        assertEquals(31, tests.size());
        return tests.stream();
    }

    @Test
    void consumerCopyRecordsExactImmutableSource() throws Exception {
        validateProvenance(PROVENANCE, Path.of("."));
    }

    @TestFactory
    Stream<DynamicTest> provenanceRejectsTamperedMissingAndExtraCopies() {
        return Stream.of(
                DynamicTest.dynamicTest("tampered declared copy", () -> {
                    Path root = materializeProvenance("tampered");
                    Files.writeString(root.resolve(
                            "src/test/resources/tracechaser/gpgx-audio-capability-v1.json"), "tampered");
                    assertThrows(AssertionError.class,
                            () -> validateProvenance(root.resolve("provenance.json"), root));
                }),
                DynamicTest.dynamicTest("missing declared copy", () -> {
                    Path root = materializeProvenance("missing");
                    Files.delete(root.resolve(
                            "src/test/resources/audio/parity/s1/normalization-contract-v1.json"));
                    assertThrows(AssertionError.class,
                            () -> validateProvenance(root.resolve("provenance.json"), root));
                }),
                DynamicTest.dynamicTest("extra provenance copy", () -> {
                    Path root = materializeProvenance("extra");
                    Path provenancePath = root.resolve("provenance.json");
                    var provenance = (com.fasterxml.jackson.databind.node.ObjectNode)
                            JSON.readTree(provenancePath.toFile());
                    var extra = JSON.createObjectNode();
                    extra.put("source", "unexpected/source.json");
                    extra.put("destination", "src/test/resources/tracechaser/unexpected.json");
                    extra.put("sha256", "00".repeat(32));
                    ((com.fasterxml.jackson.databind.node.ArrayNode) provenance.path("copies")).add(extra);
                    JSON.writeValue(provenancePath.toFile(), provenance);
                    assertThrows(AssertionError.class,
                            () -> validateProvenance(provenancePath, root));
                }));
    }

    @Test
    @Tag("tracechaser-integration")
    void initializedSourceCopiesMatchThePinnedConsumerCopies() throws Exception {
        Path checkout = TraceChaserTestSupport.requirePinnedCheckout();
        JsonNode provenance = JSON.readTree(PROVENANCE.toFile());
        for (JsonNode copy : provenance.path("copies")) {
            Path source = checkout.resolve(copy.path("source").asText());
            Path destination = Path.of(copy.path("destination").asText());
            assertCopyEquals(source, destination);
        }
    }

    private void validateProvenance(Path provenancePath, Path root) throws Exception {
        JsonNode provenance = JSON.readTree(provenancePath.toFile());
        Path consumerRoot = root.toAbsolutePath().normalize();
        assertEquals("9e51ff79e7a542f3c50d96618a7e24e6fc72397e",
                provenance.path("origin_commit").asText());
        assertEquals("openggf-tracechaser-consumer-copy-v1", provenance.path("format").asText());
        Map<String, String> declared = new LinkedHashMap<>();
        for (JsonNode copy : provenance.path("copies")) {
            String source = copy.path("source").asText();
            String destination = copy.path("destination").asText();
            assertEquals(null, declared.put(source, destination), "duplicate provenance source " + source);
            Path destinationPath = consumerRoot.resolve(destination).normalize();
            assertTrue(destinationPath.startsWith(consumerRoot),
                    "copy destination escapes consumer root: " + destination);
            Path hashed = copy.has("manifest_sha256")
                    ? destinationPath.resolve("manifest.json") : destinationPath;
            assertTrue(Files.isRegularFile(hashed), "missing declared consumer copy: " + destination);
            String field = copy.has("manifest_sha256") ? "manifest_sha256" : "sha256";
            assertEquals(copy.path(field).asText(), sha256(hashed), destination);
        }
        assertEquals(EXPECTED_COPIES, declared, "provenance must declare exactly the reviewed copies");
    }

    private Path materializeProvenance(String name) throws IOException {
        Path root = temporaryDirectory.resolve("provenance-" + name);
        Files.createDirectories(root);
        Files.copy(PROVENANCE, root.resolve("provenance.json"));
        for (String destination : EXPECTED_COPIES.values()) {
            Path source = Path.of(destination);
            Path target = root.resolve(destination);
            if (Files.isDirectory(source)) {
                Files.createDirectories(target);
                Files.copy(source.resolve("manifest.json"), target.resolve("manifest.json"));
            } else {
                Files.createDirectories(target.getParent());
                Files.copy(source, target);
            }
        }
        return root;
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
    }

    private static void assertCopyEquals(Path source, Path destination) throws IOException {
        assertEquals(Files.isDirectory(source), Files.isDirectory(destination), destination.toString());
        if (!Files.isDirectory(source)) {
            assertTrue(java.util.Arrays.equals(Files.readAllBytes(source), Files.readAllBytes(destination)),
                    destination.toString());
            return;
        }
        try (var sourcePaths = Files.walk(source); var destinationPaths = Files.walk(destination)) {
            List<Path> sourceFiles = sourcePaths.filter(Files::isRegularFile)
                    .map(source::relativize).sorted().toList();
            List<Path> destinationFiles = destinationPaths.filter(Files::isRegularFile)
                    .map(destination::relativize).sorted().toList();
            assertEquals(sourceFiles, destinationFiles, destination.toString());
            for (Path relative : sourceFiles) {
                assertTrue(java.util.Arrays.equals(Files.readAllBytes(source.resolve(relative)),
                        Files.readAllBytes(destination.resolve(relative))), relative.toString());
            }
        }
    }

    private void executeCase(JsonNode testCase) throws Exception {
        Path root = materialize(testCase);
        JsonNode expectation = testCase.path("consumer_expectation");
        if ("accept".equals(expectation.path("outcome").asText())) {
            Object parsed = parse(testCase.path("consumer_entry").asText(), root);
            JsonNode semantics = expectation.has("normalized_semantics")
                    ? expectation.path("normalized_semantics") : testCase.path("normalized_semantics");
            assertSemantic(semantics, parsed, testCase.path("id").asText());
            return;
        }
        Throwable thrown = assertThrows(Throwable.class,
                () -> parse(testCase.path("consumer_entry").asText(), root));
        JsonNode diagnostic = expectation.path("diagnostic");
        assertEquals(diagnostic.path("exception_class").asText(), thrown.getClass().getName());
        String expected = diagnostic.path("message").asText();
        if ("contains".equals(diagnostic.path("message_match").asText())) {
            assertTrue(String.valueOf(thrown.getMessage()).contains(expected), thrown::toString);
        } else {
            assertEquals(expected, thrown.getMessage());
        }
    }

    private Object parse(String entry, Path root) throws IOException {
        return switch (entry) {
            case "com.openggf.trace.TraceData.load" -> TraceData.load(root);
            case "com.openggf.trace.TraceRunManifest.load+validate" -> {
                TraceRunManifest manifest = TraceRunManifest.load(root.resolve("run_manifest.json"));
                manifest.validate(root);
                yield manifest;
            }
            case "com.openggf.game.sonic1.specialstage.Sonic1SpecialStageTraceData.load" ->
                    Sonic1SpecialStageTraceData.load(root);
            case "com.openggf.trace.SpecialStageTraceData.load" -> SpecialStageTraceData.load(root);
            case "com.openggf.game.sonic3k.specialstage.S3kSpecialStageTraceData.load" ->
                    S3kSpecialStageTraceData.load(root);
            default -> throw new AssertionError("unknown Java consumer entry: " + entry);
        };
    }

    private Path materialize(JsonNode testCase) throws IOException {
        Path source = PACK.resolve(testCase.path("root").asText());
        JsonNode materialization = testCase.path("materialization");
        if (materialization.isMissingNode()) return source;
        Path targetRoot = temporaryDirectory.resolve(testCase.path("id").asText());
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path target = targetRoot.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else Files.copy(path, target);
            }
        }
        JsonNode payload = JSON.readTree(targetRoot.resolve(materialization.path("source").asText()).toFile());
        Files.write(targetRoot.resolve(materialization.path("target").asText()),
                Base64.getDecoder().decode(payload.path("base64").asText()));
        return targetRoot;
    }

    private void assertSemantic(JsonNode expected, Object actual, String path) throws Exception {
        if (expected.isNull()) {
            assertEquals(null, actual, path);
        } else if (expected.isObject()) {
            for (var fields = expected.fields(); fields.hasNext();) {
                var field = fields.next();
                assertSemantic(field.getValue(), resolve(actual, field.getKey()), path + "." + field.getKey());
            }
        } else if (expected.isArray()) {
            assertTrue(actual instanceof List<?>, path + " must be a list");
            List<?> values = (List<?>) actual;
            assertEquals(expected.size(), values.size(), path);
            for (int index = 0; index < expected.size(); index++) {
                assertSemantic(expected.get(index), values.get(index), path + "[" + index + "]");
            }
        } else if (expected.isBoolean()) {
            assertEquals(expected.booleanValue(), actual, path);
        } else if (expected.isIntegralNumber()) {
            assertTrue(actual instanceof Number, path + " must be numeric, got " + actual);
            assertEquals(expected.longValue(), ((Number) actual).longValue(), path);
        } else {
            String value = actual instanceof Enum<?> enumeration
                    ? enumeration.name().toLowerCase(Locale.ROOT) : String.valueOf(actual);
            assertEquals(expected.asText(), value, path);
        }
    }

    private Object resolve(Object actual, String field) throws Exception {
        assertNotNull(actual, field);
        if (actual instanceof TraceData trace) {
            return switch (field) {
                case "metadata" -> trace.metadata();
                case "frames" -> java.util.stream.IntStream.range(0, trace.frameCount()).mapToObj(trace::getFrame).toList();
                case "aux_events" -> java.util.stream.IntStream.range(0, trace.frameCount())
                        .boxed().flatMap(frame -> trace.getEventsForFrame(frame).stream()).toList();
                case "hardware_work_completed" -> trace.hardwareTimingSchedule().edges();
                default -> throw new AssertionError("unknown TraceData semantic: " + field);
            };
        }
        if (actual instanceof Sonic1SpecialStageTraceData trace) {
            return traceField(field, trace.metadata(), trace.frames(), trace.eventsByFrame(), trace.frameCount());
        }
        if (actual instanceof SpecialStageTraceData trace) {
            List<?> frames = java.util.stream.IntStream.range(0, trace.frameCount()).mapToObj(trace::getFrame).toList();
            return switch (field) {
                case "metadata" -> trace.metadata();
                case "frames" -> frames;
                case "aux_events" -> java.util.stream.IntStream.range(0, trace.frameCount())
                        .boxed().flatMap(frame -> trace.getEventsForFrame(frame).stream()).toList();
                default -> throw new AssertionError("unknown special-stage semantic: " + field);
            };
        }
        if (actual instanceof S3kSpecialStageTraceData trace) {
            return traceField(field, trace.metadata(), trace.frames(), trace.eventsByFrame(), trace.frameCount());
        }
        if (actual instanceof TraceRunManifest manifest) {
            if (field.equals("trace_schema")) return TraceRunManifest.TRACE_SCHEMA;
            if (field.equals("member_order")) return manifest.segments().stream().map(TraceRunManifest.Segment::dir).toList();
        }
        if (actual instanceof TraceFrame frame && field.equals("player")) return frame;
        if (actual instanceof TraceFrame && field.equals("present")) return true;
        if (actual instanceof com.openggf.trace.TraceEvent.StateSnapshot snapshot) {
            if (field.equals("frame")) return snapshot.frame();
            return snapshot.fields().get(field);
        }
        if (actual instanceof TraceMetadata metadata) {
            return switch (field) {
                case "initial_rng_seed" -> metadata.initialRngSeed();
                case "rng_seed_hex" -> metadata.rngSeedHex();
                case "start_x" -> (int) metadata.startX();
                case "start_y" -> (int) metadata.startY();
                case "start_x_hex" -> metadata.startXHex();
                case "start_y_hex" -> metadata.startYHex();
                default -> invoke(actual, field);
            };
        }
        return invoke(actual, field);
    }

    private Object traceField(String field, TraceMetadata metadata, List<?> frames,
            java.util.Map<Integer, ?> events, int frameCount) {
        return switch (field) {
            case "metadata" -> metadata;
            case "frames" -> frames;
            case "aux_events" -> java.util.stream.IntStream.range(0, frameCount)
                    .mapToObj(events::get).filter(java.util.Objects::nonNull)
                    .flatMap(value -> ((List<?>) value).stream()).toList();
            default -> throw new AssertionError("unknown special-stage semantic: " + field);
        };
    }

    private Object invoke(Object actual, String snakeName) throws Exception {
        String camel = snakeName.replaceAll("_([a-z])", "$1");
        StringBuilder converted = new StringBuilder();
        boolean upper = false;
        for (char character : snakeName.toCharArray()) {
            if (character == '_') { upper = true; continue; }
            converted.append(upper ? Character.toUpperCase(character) : character);
            upper = false;
        }
        camel = converted.toString();
        if (camel.equals("status") && (actual instanceof TraceFrame
                || actual instanceof com.openggf.trace.TraceCharacterState)) camel = "statusByte";
        camel = camel.replace("Togo", "ToGo");
        Method method = actual.getClass().getMethod(camel);
        return method.invoke(actual);
    }
}
