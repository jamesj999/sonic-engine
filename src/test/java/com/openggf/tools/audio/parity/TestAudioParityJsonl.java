package com.openggf.tools.audio.parity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestAudioParityJsonl {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path GOLDEN = Path.of("src", "test", "resources", "audio", "parity", "s1",
            "normalization-contract-v1.json");

    @TempDir
    Path temp;

    @Test
    void javaSchemaReproducesSharedLuaCanonicalBytes() throws Exception {
        // Break caught: Java's gating schema or key ordering drifts from the shared Lua contract.
        JsonNode vector = JSON.readTree(Files.readString(GOLDEN));
        String expected = vector.required("expectedCanonicalJson").textValue();
        AudioParityTick tick = AudioParityJsonl.parseCanonicalPayload(expected, 0);

        assertEquals(expected, AudioParityJsonl.canonicalGatingJson(tick));
    }

    @Test
    void streamsTicksWithoutRetainingRawBusAndWritesDeterministically() throws Exception {
        // Break caught: the JSONL reader buffers the stream/raw bus or output depends on map insertion order.
        AudioParityTick tick = goldenTick();
        AudioParityMetadata metadata = validMetadata();
        Path first = temp.resolve("first.jsonl");
        Path second = temp.resolve("second.jsonl");
        List<AudioParityTick> ticks = List.of(tick, tick.withOrdinal(1), tick.withOrdinal(2));
        AudioParityJsonl.write(first, metadata, ticks.iterator());
        AudioParityJsonl.write(second, metadata, ticks.iterator());
        assertEquals(Files.readString(first), Files.readString(second));

        List<AudioParityTick> observed = new ArrayList<>();
        AudioParityMetadata parsed = AudioParityJsonl.read(first, observed::add);
        assertEquals(metadata, parsed);
        assertEquals(ticks, observed);
        assertTrue(Files.readString(first).lines().allMatch(line -> !line.contains("raw_bus")));
    }

    @Test
    void rejectsUnknownSchemaAndUnknownOrMissingFields() throws Exception {
        // Break caught: incompatible or incomplete streams are silently accepted.
        ObjectNode metadata = metadataJson();
        metadata.put("schema", "openggf.s1_audio_parity_reference.v99");
        assertInvalidStream(metadata, tickJson(goldenTick()), "schema");

        ObjectNode unknown = metadataJson();
        unknown.put("surprise", true);
        assertInvalidStream(unknown, tickJson(goldenTick()), "unknown");

        ObjectNode missing = metadataJson();
        missing.remove("period");
        assertInvalidStream(missing, tickJson(goldenTick()), "period");

        ObjectNode badTick = tickJson(goldenTick());
        badTick.withObject("state").remove("global");
        assertInvalidStream(metadataJson(), badTick, "global");
    }

    @Test
    void validatesNestedLuaReferenceMetadataWithoutAcceptingUnknownFields() throws Exception {
        // Break caught: callback/movie provenance changes shape while the top-level metadata still looks valid.
        ObjectNode valid = referenceMetadataJson();
        Path validStream = temp.resolve("valid-lua-metadata.jsonl");
        AudioParityTick tick = goldenTick();
        Files.writeString(validStream, valid + "\n" + tickJson(tick) + "\n"
                + tickJson(tick.withOrdinal(1)) + "\n" + tickJson(tick.withOrdinal(2)) + "\n");
        assertEquals(3, AudioParityJsonl.read(validStream, ignored -> { }).terminalRecordCount());

        ObjectNode invalid = valid.deepCopy();
        invalid.withObject("callback_contract").put("unreviewed", true);
        assertInvalidStream(invalid, tickJson(goldenTick()), "unknown");
    }

    @Test
    void rejectsDuplicateJsonFields() throws Exception {
        // Break caught: a later duplicate silently overrides a validated identity or tick value.
        Path stream = temp.resolve("duplicates.jsonl");
        String metadata = AudioParityJsonl.metadataTree(validMetadata()).toString();
        String duplicate = tickJson(goldenTick()).toString().replaceFirst("\\{", "{\\\"ordinal\\\":0,");
        Files.writeString(stream, metadata + "\n" + duplicate + "\n");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AudioParityJsonl.read(stream, ignored -> { }));
        assertTrue(error.getMessage().toLowerCase().contains("duplicate"), error::getMessage);
    }

    @Test
    void validatesRecurrenceTupleWithOverflowSafeInclusiveBoundary() {
        // Break caught: malformed or overflowed cycle proof controls the OpenGGF run length.
        assertThrows(IllegalArgumentException.class, () -> AudioParityMetadata.openGgf(
                0, 1, 2, "69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b", "afe05eee"));
        assertThrows(IllegalArgumentException.class, () -> AudioParityMetadata.openGgf(
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                "69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b", "afe05eee"));
        assertThrows(IllegalArgumentException.class, () -> AudioParityMetadata.openGgf(
                2, 17_999, 36_001, "69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b", "afe05eee"));
        assertEquals(36_000, AudioParityMetadata.openGgf(1, 17_999, 36_000,
                "69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b", "afe05eee").terminalRecordCount());
    }

    @Test
    void rejectsTrailingRootJsonAndPrevalidatesWriterBeforePublication() throws Exception {
        // Break caught: a second root value is ignored, or public writer inputs publish unreadable metadata.
        assertThrows(IllegalArgumentException.class,
                () -> AudioParityJsonl.parseTick(tickJson(goldenTick()) + "{}"));
        assertThrows(IllegalArgumentException.class,
                () -> AudioParityJsonl.parseMetadata(metadataJson() + "{}"));

        Path output = temp.resolve("preserved.jsonl");
        Files.writeString(output, "existing-output\n");
        ObjectNode invalidDetails = JSON.createObjectNode();
        invalidDetails.putObject("movie").put("game", "incomplete");
        AudioParityMetadata unreadable = new AudioParityMetadata(AudioParitySchema.VERSION,
                AudioParitySchema.OPENGGF_CAPTURE, 0, 1, 3,
                "69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b", "afe05eee", invalidDetails);
        assertThrows(IllegalArgumentException.class,
                () -> AudioParityJsonl.write(output, unreadable,
                        List.of(goldenTick(), goldenTick().withOrdinal(1), goldenTick().withOrdinal(2)).iterator()));
        assertEquals("existing-output\n", Files.readString(output));

        AudioParityMetadata colliding = new AudioParityMetadata(AudioParitySchema.VERSION,
                AudioParitySchema.OPENGGF_CAPTURE, 0, 1, 3,
                AudioParitySchema.S1_REV01_SHA1, AudioParitySchema.S1_REV01_CRC32,
                JSON.createObjectNode().put("schema", "silently-overwritten"));
        assertThrows(IllegalArgumentException.class,
                () -> AudioParityJsonl.write(output, colliding,
                        List.of(goldenTick(), goldenTick().withOrdinal(1), goldenTick().withOrdinal(2)).iterator()));
        assertEquals("existing-output\n", Files.readString(output));
    }

    @Test
    void rejectsMissingDuplicateAndOutOfOrderOrdinalsWhileStreaming() throws Exception {
        // Break caught: records can be dropped, repeated, or reordered without capture-integrity failure.
        AudioParityTick tick = goldenTick();
        assertInvalidStream(metadataJson(), List.of(tickJson(tick), tickJson(tick)), "ordinal");
        assertInvalidStream(metadataJson(), List.of(tickJson(tick), tickJson(tick.withOrdinal(2))), "ordinal");
        ObjectNode startsAtOne = tickJson(tick.withOrdinal(1));
        assertInvalidStream(metadataJson(), startsAtOne, "ordinal");
    }

    @Test
    void exposesTypedContinuityAndCountFailuresWithoutWeakeningMalformedRejection() throws Exception {
        // Break caught: comparator classification depends on prose or malformed records gain integrity status.
        AudioParityTick tick = goldenTick();
        Path ordinal = writeStream(metadataJson(), List.of(tickJson(tick), tickJson(tick.withOrdinal(2)),
                tickJson(tick.withOrdinal(2))), "typed-ordinal.jsonl");
        AudioParityJsonl.ValidationException ordinalFailure = assertThrows(
                AudioParityJsonl.ValidationException.class,
                () -> AudioParityJsonl.read(ordinal, ignored -> { }));
        assertEquals(AudioParityJsonl.FailureKind.ORDINAL_CONTINUITY, ordinalFailure.kind());
        assertEquals(1, ordinalFailure.expected());
        assertEquals(2, ordinalFailure.observed());

        Path count = writeStream(metadataJson(), List.of(tickJson(tick), tickJson(tick.withOrdinal(1))),
                "typed-count.jsonl");
        AudioParityJsonl.ValidationException countFailure = assertThrows(
                AudioParityJsonl.ValidationException.class,
                () -> AudioParityJsonl.read(count, ignored -> { }));
        assertEquals(AudioParityJsonl.FailureKind.TICK_COUNT, countFailure.kind());
        assertEquals(3, countFailure.expected());
        assertEquals(2, countFailure.observed());

        ObjectNode malformedTick = tickJson(tick);
        malformedTick.put("ordinal_note", "ordinal tick records");
        Path malformed = writeStream(metadataJson(), List.of(malformedTick, tickJson(tick.withOrdinal(1)),
                tickJson(tick.withOrdinal(2))), "typed-malformed.jsonl");
        AudioParityJsonl.ValidationException malformedFailure = assertThrows(
                AudioParityJsonl.ValidationException.class,
                () -> AudioParityJsonl.read(malformed, ignored -> { }));
        assertEquals(AudioParityJsonl.FailureKind.MALFORMED, malformedFailure.kind());
    }

    @Test
    void validatesEveryChipByteAndYmShape() throws Exception {
        // Break caught: malformed decoded writes enter the parity comparison boundary.
        ObjectNode ymValue = tickJson(goldenTick());
        ((ObjectNode) ymValue.withArray("events").get(0)).put("value", 256);
        assertInvalidStream(metadataJson(), ymValue, "value");

        ObjectNode ymPort = tickJson(goldenTick());
        ((ObjectNode) ymPort.withArray("events").get(0)).put("port", 2);
        assertInvalidStream(metadataJson(), ymPort, "port");

        ObjectNode psgRegister = tickJson(goldenTick());
        ((ObjectNode) psgRegister.withArray("events").get(2)).put("register", 7);
        assertInvalidStream(metadataJson(), psgRegister, "unknown");

        ObjectNode psgValue = tickJson(goldenTick());
        ((ObjectNode) psgValue.withArray("events").get(2)).put("value", -1);
        assertInvalidStream(metadataJson(), psgValue, "value");
    }

    @Test
    void validatesFixedRolesAndAllConditionalTrackRanges() throws Exception {
        // Break caught: absent/reordered roles, inactive stale gates, or invalid active bytes pass validation.
        ObjectNode absent = tickJson(goldenTick());
        absent.withObject("state").withArray("tracks").remove(9);
        assertInvalidStream(metadataJson(), absent, "ten");

        ObjectNode wrongRole = tickJson(goldenTick());
        ((ObjectNode) wrongRole.withObject("state").withArray("tracks").get(1)).put("role", "FM2");
        assertInvalidStream(metadataJson(), wrongRole, "role");

        ObjectNode extraInactive = tickJson(goldenTick());
        ((ObjectNode) extraInactive.withObject("state").withArray("tracks").get(0)).put("duration", 0);
        assertInvalidStream(metadataJson(), extraInactive, "inactive");

        ObjectNode badSigned = tickJson(goldenTick());
        ((ObjectNode) badSigned.withObject("state").withArray("tracks").get(1)).put("detune", 128);
        assertInvalidStream(metadataJson(), badSigned, "detune");

        ObjectNode badByte = tickJson(goldenTick());
        ((ObjectNode) badByte.withObject("state").withArray("tracks").get(7)).put("envelopeCursor", 256);
        assertInvalidStream(metadataJson(), badByte, "envelopeCursor");

        ObjectNode missingPsgCursor = tickJson(goldenTick());
        ((ObjectNode) missingPsgCursor.withObject("state").withArray("tracks").get(7))
                .remove("envelopeCursor");
        assertInvalidStream(metadataJson(), missingPsgCursor, "envelopeCursor");

        ObjectNode badLoop = tickJson(goldenTick());
        ((ObjectNode) badLoop.withObject("state").withArray("tracks").get(1))
                .withArray("loopCounters").set(0, JSON.getNodeFactory().numberNode(256));
        assertInvalidStream(metadataJson(), badLoop, "loopCounters");

        ObjectNode badStack = tickJson(goldenTick());
        ((ObjectNode) badStack.withObject("state").withArray("tracks").get(1))
                .withArray("returnStack").set(0, JSON.getNodeFactory().numberNode(-1));
        assertInvalidStream(metadataJson(), badStack, "returnStack");

        ObjectNode badFrequency = tickJson(goldenTick());
        ((ObjectNode) badFrequency.withObject("state").withArray("tracks").get(1))
                .put("baseFrequency", 65536);
        assertInvalidStream(metadataJson(), badFrequency, "baseFrequency");

        ObjectNode badPan = tickJson(goldenTick());
        ((ObjectNode) badPan.withObject("state").withArray("tracks").get(1)).put("pan", 193);
        assertInvalidStream(metadataJson(), badPan, "pan");
    }

    @Test
    void activeDacOmitsTheAliasedSavedDacFrequencyWord() throws Exception {
        ObjectNode withoutFrequency = tickJson(goldenTick());
        ArrayNode tracks = withoutFrequency.withObject("state").withArray("tracks");
        ObjectNode dac = ((ObjectNode) tracks.get(1)).deepCopy();
        dac.put("role", "DAC").put("hardware", "DAC");
        dac.remove("baseFrequency");
        tracks.set(0, dac);

        AudioParityTick parsed = AudioParityJsonl.parseTick(withoutFrequency.toString());
        assertEquals(null, parsed.tracks().get(0).baseFrequency());

        ObjectNode withAliasedWord = withoutFrequency.deepCopy();
        ((ObjectNode) withAliasedWord.withObject("state").withArray("tracks").get(0))
                .put("baseFrequency", 0x8000);
        assertThrows(IllegalArgumentException.class,
                () -> AudioParityJsonl.parseTick(withAliasedWord.toString()));
    }

    @Test
    void activeFadeFieldsAreConditionalAndStrict() throws Exception {
        // Break caught: fade-only fields gate inactive state or disappear during an active fade.
        ObjectNode inactiveExtra = tickJson(goldenTick());
        inactiveExtra.withObject("state").withObject("global").put("fadeDelay", 0);
        assertInvalidStream(metadataJson(), inactiveExtra, "fade");

        ObjectNode activeMissing = tickJson(goldenTick());
        ObjectNode global = activeMissing.withObject("state").withObject("global");
        global.put("fadeActive", true).put("fadeDirection", "out");
        assertInvalidStream(metadataJson(), activeMissing, "fadeDelay");
    }

    @Test
    void diagnosticsDoNotParticipateInGatingEquality() throws Exception {
        // Break caught: host frame/raw state diagnostics turn a semantic match into a mismatch.
        ObjectNode firstJson = tickJson(goldenTick());
        firstJson.set("diagnostic", validDiagnostic(824));
        firstJson.set("raw_bus", validRawBus(159));
        ObjectNode secondJson = tickJson(goldenTick());
        secondJson.set("diagnostic", validDiagnostic(9999));
        secondJson.set("raw_bus", validRawBus(255));

        AudioParityTick first = AudioParityJsonl.parseTick(firstJson.toString());
        AudioParityTick second = AudioParityJsonl.parseTick(secondJson.toString());
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(AudioParityJsonl.canonicalGatingJson(first), AudioParityJsonl.canonicalGatingJson(second));
    }

    @Test
    void streamValidatesDiagnosticAndRawBusShapesWithoutMakingThemGatingData() throws Exception {
        // Break caught: malformed Task 4 diagnostic/raw events pass because the reader blindly skips them.
        ObjectNode badDiagnostic = tickJson(goldenTick());
        badDiagnostic.put("diagnostic", "not-an-object");
        assertInvalidStream(metadataJson(), badDiagnostic, "diagnostic");

        ObjectNode badRaw = tickJson(goldenTick());
        ArrayNode bus = validRawBus(159);
        ((ObjectNode) bus.get(0)).put("value", 256);
        badRaw.set("raw_bus", bus);
        assertInvalidStream(metadataJson(), badRaw, "value");

        ObjectNode unknownRaw = tickJson(goldenTick());
        ArrayNode unknownBus = validRawBus(159);
        ((ObjectNode) unknownBus.get(0)).put("unknown", true);
        unknownRaw.set("raw_bus", unknownBus);
        assertInvalidStream(metadataJson(), unknownRaw, "unknown");

        ObjectNode wrongRole = tickJson(goldenTick());
        ObjectNode diagnostic = validDiagnostic(824);
        ((ObjectNode) diagnostic.withObject("raw_state").withArray("tracks").get(0)).put("role", "FM1");
        wrongRole.set("diagnostic", diagnostic);
        assertInvalidStream(metadataJson(), wrongRole, "role");

        ObjectNode memoryPsgPort = tickJson(goldenTick());
        ArrayNode memoryBus = validRawBus(159);
        ((ObjectNode) memoryBus.get(0)).put("port", 0);
        memoryPsgPort.set("raw_bus", memoryBus);
        assertInvalidStream(metadataJson(), memoryPsgPort, "unknown");

        ObjectNode manifestPsgPort = tickJson(goldenTick());
        ArrayNode manifestBus = validPcManifestRawBus(159);
        ((ObjectNode) manifestBus.get(0)).put("port", 0);
        manifestPsgPort.set("raw_bus", manifestBus);
        assertInvalidStream(metadataJson(), manifestPsgPort, "unknown");
    }

    @Test
    void readCrossValidatesEveryRawBusSourceAgainstCaptureMetadata() throws Exception {
        // Break caught: callback and PC-manifest events can be mixed or mislabeled under trusted provenance.
        AudioParityTick tick = goldenTick();
        ObjectNode reference = referenceMetadataJson();
        ObjectNode wrongSource = tickJson(tick);
        wrongSource.set("raw_bus", validPcManifestRawBus(159));
        assertInvalidStream(reference, List.of(wrongSource, tickJson(tick.withOrdinal(1)),
                tickJson(tick.withOrdinal(2))), "source");

        ObjectNode manifestMetadata = referenceMetadataJson();
        manifestMetadata.putObject("callback_contract").put("manifest_sites", 20).put("source", "pc_manifest");
        ObjectNode memorySource = tickJson(tick);
        memorySource.set("raw_bus", validRawBus(159));
        assertInvalidStream(manifestMetadata, List.of(memorySource, tickJson(tick.withOrdinal(1)),
                tickJson(tick.withOrdinal(2))), "source");

        ObjectNode mixed = tickJson(tick);
        ArrayNode mixedBus = validRawBus(159);
        mixedBus.add(validPcManifestRawBus(159).get(0));
        mixed.set("raw_bus", mixedBus);
        assertInvalidStream(reference, List.of(mixed, tickJson(tick.withOrdinal(1)),
                tickJson(tick.withOrdinal(2))), "source");

        // Standalone parsing intentionally validates shape only because no metadata accompanies the tick.
        AudioParityJsonl.parseTick(mixed.toString());
    }

    @Test
    void pinsReferenceAndOpenGgfCaptureIdentityVariants() throws Exception {
        // Break caught: arbitrary capture names or subtly different ROM/movie/probe provenance enter v1.
        ObjectNode arbitrary = referenceMetadataJson();
        arbitrary.put("capture", "whatever-host-produced");
        assertInvalidStream(arbitrary, tickJson(goldenTick()), "capture");

        ObjectNode wrongRom = referenceMetadataJson();
        wrongRom.withObject("rom").put("crc32", "00000000");
        assertInvalidStream(wrongRom, tickJson(goldenTick()), "REV01");

        ObjectNode wrongMovie = referenceMetadataJson();
        wrongMovie.withObject("movie").put("input_rows", 988);
        assertInvalidStream(wrongMovie, tickJson(goldenTick()), "movie");

        ObjectNode zeroProof = referenceMetadataJson();
        zeroProof.withObject("callback_contract").withObject("proof").put("fm_port0_pairs", 0);
        assertInvalidStream(zeroProof, tickJson(goldenTick()), "positive");

        ObjectNode pcManifest = referenceMetadataJson();
        ObjectNode callback = pcManifest.putObject("callback_contract");
        callback.put("manifest_sites", 20).put("source", "pc_manifest");
        AudioParityJsonl.parseMetadata(pcManifest.toString());

        AudioParityMetadata openGgf = AudioParityMetadata.openGgf(0, 1, 3,
                "69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b", "afe05eee");
        assertEquals(AudioParitySchema.OPENGGF_CAPTURE, openGgf.capture());
        ObjectNode pollutedOpenGgf = (ObjectNode) AudioParityJsonl.metadataTree(openGgf);
        addValidMovie(pollutedOpenGgf);
        assertInvalidStream(pollutedOpenGgf, tickJson(goldenTick()), "OpenGGF");
    }

    @Test
    void rejectsHostPathsAndTimestampsAnywhereInMetadata() throws Exception {
        // Break caught: normalized stream identity varies by host path or capture time.
        assertThrows(IllegalArgumentException.class,
                () -> new AudioParityMetadata(AudioParitySchema.VERSION, AudioParitySchema.REFERENCE_CAPTURE,
                        0, 1, 3, AudioParitySchema.S1_REV01_SHA1, AudioParitySchema.S1_REV01_CRC32,
                        JSON.createObjectNode().put("label", "ROM loaded from /opt/audio-fixtures/sonic.gen")));
        assertThrows(IllegalArgumentException.class,
                () -> new AudioParityMetadata(AudioParitySchema.VERSION, AudioParitySchema.REFERENCE_CAPTURE,
                        0, 1, 3, AudioParitySchema.S1_REV01_SHA1, AudioParitySchema.S1_REV01_CRC32,
                        JSON.createObjectNode().put("label", "captured at 2026-08-09T12:34:56Z")));

        assertThrows(IllegalArgumentException.class,
                () -> new AudioParityMetadata(AudioParitySchema.VERSION, AudioParitySchema.OPENGGF_CAPTURE,
                        0, 1, 3, "69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b", "afe05eee",
                        JSON.createObjectNode().put("label", "from C:\\captures\\audio.jsonl")));
        assertThrows(IllegalArgumentException.class,
                () -> new AudioParityMetadata(AudioParitySchema.VERSION, AudioParitySchema.OPENGGF_CAPTURE,
                        0, 1, 3, AudioParitySchema.S1_REV01_SHA1, AudioParitySchema.S1_REV01_CRC32,
                        JSON.createObjectNode().put("label", "from \\\\server\\share\\audio.jsonl")));
        assertThrows(IllegalArgumentException.class,
                () -> new AudioParityMetadata(AudioParitySchema.VERSION, AudioParitySchema.OPENGGF_CAPTURE,
                        0, 1, 3, AudioParitySchema.S1_REV01_SHA1, AudioParitySchema.S1_REV01_CRC32,
                        JSON.createObjectNode().put("label", "/")));
        AudioParityMetadata ordinary = new AudioParityMetadata(AudioParitySchema.VERSION,
                AudioParitySchema.OPENGGF_CAPTURE, 0, 1, 3, AudioParitySchema.S1_REV01_SHA1,
                AudioParitySchema.S1_REV01_CRC32,
                JSON.createObjectNode().put("label", "pan/AMS/FMS and timeout / fade state"));
        assertEquals(AudioParitySchema.OPENGGF_CAPTURE, ordinary.capture());
    }

    @Test
    void atomicMoveFailurePreservesDestinationAndCleansTemporaryFile() throws Exception {
        // Break caught: unsupported atomic replacement silently falls back and overwrites trusted output.
        Path output = temp.resolve("atomic.jsonl");
        Files.writeString(output, "trusted-existing-output\n");
        AudioParityTick tick = goldenTick();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> AudioParityJsonl.write(output, validMetadata(),
                        List.of(tick, tick.withOrdinal(1), tick.withOrdinal(2)).iterator(),
                        (source, destination) -> {
                            throw new AtomicMoveNotSupportedException(source.toString(), destination.toString(),
                                    "test filesystem refuses atomic replacement");
                        }));
        assertTrue(failure.getMessage().contains("atomically"), failure::getMessage);
        assertEquals("trusted-existing-output\n", Files.readString(output));
        try (var children = Files.list(temp)) {
            assertEquals(List.of(output), children.toList());
        }
    }

    @Test
    void createNewPublicationCannotReplaceAnExistingCapture() throws Exception {
        // Break caught: the CLI's atomic capture publisher replaces evidence from an earlier run.
        Path output = temp.resolve("existing.jsonl");
        Files.writeString(output, "preserve-me\n");
        AudioParityTick tick = goldenTick();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> AudioParityJsonl.writeNew(output, validMetadata(),
                        List.of(tick, tick.withOrdinal(1), tick.withOrdinal(2)).iterator()));

        assertTrue(failure.getMessage().contains("atomically"), failure::getMessage);
        assertEquals("preserve-me\n", Files.readString(output));
        try (var children = Files.list(temp)) {
            assertEquals(List.of(output), children.toList());
        }
    }

    private AudioParityTick goldenTick() throws Exception {
        JsonNode vector = JSON.readTree(Files.readString(GOLDEN));
        return AudioParityJsonl.parseCanonicalPayload(vector.required("expectedCanonicalJson").textValue(), 0);
    }

    private AudioParityMetadata validMetadata() {
        return AudioParityMetadata.openGgf(0, 1, 3,
                "69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b", "afe05eee");
    }

    private ObjectNode metadataJson() {
        return (ObjectNode) AudioParityJsonl.metadataTree(validMetadata());
    }

    private ObjectNode tickJson(AudioParityTick tick) {
        return (ObjectNode) AudioParityJsonl.tickTree(tick);
    }

    private ObjectNode addValidMovie(ObjectNode metadata) {
        ObjectNode movie = metadata.putObject("movie");
        movie.put("archive_sha256", "622ff642d0b0835a4f77bee568f2413f288ead3306a8bc2a93e8d8f77f24ca9c");
        movie.put("core", "Genplus-gx");
        movie.put("emulator", "Version 2.11");
        movie.put("game", "Sonic The Hedgehog (W) (REV01) [!]");
        movie.put("input_rows", 989);
        movie.put("opaque_header_hash", "09DADB5071EB35050067A32462E39C5F");
        return movie;
    }

    private ObjectNode referenceMetadataJson() {
        ObjectNode metadata = metadataJson();
        metadata.put("capture", AudioParitySchema.REFERENCE_CAPTURE);
        metadata.put("launch_update_music_invocations", 514);
        ObjectNode callback = metadata.putObject("callback_contract");
        callback.putArray("arguments").add("address").add("value").add("flags");
        callback.putObject("proof").put("fm_port0_pairs", 2).put("fm_port1_pairs", 1).put("psg_writes", 3);
        callback.put("source", "memory_callback");
        ObjectNode diagnostic = metadata.putObject("diagnostic_fields");
        addStrings(diagnostic.putArray("global"), "priority", "pause", "fade flags", "queues", "sound id",
                "voice selector", "DAC update", "1-up", "speed-up reload", "communication", "ring speaker",
                "push");
        addStrings(diagnostic.putArray("track"), "resting", "note fill", "modulation phase", "raw status",
                "raw voice control");
        ObjectNode gating = metadata.putObject("gating_fields");
        addStrings(gating.putArray("global"), "tempo timeout", "tempo reload", "speed-up", "fade state");
        addStrings(gating.putArray("track"), "active", "role", "hardware", "overridden", "do not attack",
                "modulation enabled", "sequence position", "transpose", "volume", "pan/AMS/FMS",
                "voice/envelope", "duration", "duration reload", "PSG envelope cursor", "base frequency",
                "detune", "live loop counters", "live return stack");
        addValidMovie(metadata);
        return metadata;
    }

    private void addStrings(ArrayNode array, String... values) {
        for (String value : values) {
            array.add(value);
        }
    }

    private ObjectNode validDiagnostic(int frame) {
        ObjectNode diagnostic = JSON.createObjectNode();
        diagnostic.put("emulator_frame", frame).put("game_mode", 4).put("interrupt_mask", 6)
                .put("invocation_open_frame", frame - 1);
        ObjectNode rawState = diagnostic.putObject("raw_state");
        ObjectNode global = rawState.putObject("global");
        for (String field : List.of("communication", "fade_in_flag", "fade_out_counter", "one_up", "pause",
                "priority", "push", "ring_speaker", "sound_id", "speed_up_reload", "updating_dac",
                "voice_selector")) {
            global.put(field, 0);
        }
        global.putArray("queues").add(0).add(0).add(0);
        ArrayNode tracks = rawState.putArray("tracks");
        for (String role : AudioParitySchema.ROLES) {
            ObjectNode track = tracks.addObject();
            for (String field : List.of("ams_fms_pan", "duration_countdown", "duration_reload",
                    "envelope_cursor", "envelope_or_voice", "modulation_delay", "modulation_speed",
                    "modulation_steps", "note_fill_countdown", "note_fill_reload", "status", "voice_control")) {
                track.put(field, 0);
            }
            track.put("data_position", -1).put("modulation_delta", 0).put("modulation_enabled", false)
                    .put("modulation_value", 0).put("overridden", false).put("resting", false)
                    .put("role", role).put("tie_next", false);
        }
        return diagnostic;
    }

    private ArrayNode validRawBus(int psgValue) {
        ArrayNode bus = JSON.createArrayNode();
        bus.addObject().put("address", 0xC00011).put("flags", 8192).put("kind", "psg")
                .put("source", "memory_callback").put("value", psgValue);
        return bus;
    }

    private ArrayNode validPcManifestRawBus(int psgValue) {
        ArrayNode bus = JSON.createArrayNode();
        bus.addObject().put("kind", "psg").put("pc", 0x729BC).put("source", "pc_manifest")
                .put("value", psgValue);
        return bus;
    }

    private void assertInvalidStream(ObjectNode metadata, ObjectNode tick, String message) throws Exception {
        assertInvalidStream(metadata, List.of(tick), message);
    }

    private void assertInvalidStream(ObjectNode metadata, List<ObjectNode> ticks, String message) throws Exception {
        Path stream = writeStream(metadata, ticks, "invalid-" + System.nanoTime() + ".jsonl");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AudioParityJsonl.read(stream, ignored -> { }));
        assertTrue(error.getMessage().toLowerCase().contains(message.toLowerCase()), error::getMessage);
    }

    private Path writeStream(ObjectNode metadata, List<ObjectNode> ticks, String name) throws Exception {
        Path stream = temp.resolve(name);
        StringBuilder content = new StringBuilder(metadata.toString()).append('\n');
        ticks.forEach(tick -> content.append(tick).append('\n'));
        Files.writeString(stream, content, StandardCharsets.UTF_8);
        return stream;
    }
}
