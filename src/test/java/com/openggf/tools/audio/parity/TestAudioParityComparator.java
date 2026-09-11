package com.openggf.tools.audio.parity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestAudioParityComparator {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path GOLDEN = Path.of("src", "test", "resources", "audio", "parity", "s1",
            "normalization-contract-v1.json");

    @TempDir
    Path temp;

    private AudioParityTick baseTick;
    private AudioParityMetadata referenceMetadata;
    private AudioParityMetadata openGgfMetadata;

    @BeforeEach
    void setUp() throws Exception {
        JsonNode vector = JSON.readTree(Files.readString(GOLDEN));
        baseTick = AudioParityJsonl.parseCanonicalPayload(
                vector.required("expectedCanonicalJson").textValue(), 0);
        referenceMetadata = metadata(AudioParitySchema.REFERENCE_CAPTURE, 0, 1, 3);
        openGgfMetadata = metadata(AudioParitySchema.OPENGGF_CAPTURE, 0, 1, 3);
    }

    @Test
    void matchingCapturesProduceDeterministicHumanAndCompactJsonReports() throws Exception {
        // Break caught: a valid pair is rejected or report serialization depends on incidental ordering.
        AudioParityReport report = compare(ticks(baseTick), ticks(baseTick));

        assertTrue(report.matches());
        assertEquals(AudioParityReport.Kind.MATCH, report.kind());
        assertTrue(report.toHumanText().contains("MATCH (3 ticks)"));
        assertTrue(report.toHumanText().contains("production song startup is not validated"));
        JsonNode summary = JSON.readTree(report.toJsonSummary());
        assertFalse(summary.required("productionStartupValidated").booleanValue());
        assertEquals("driver-after-harness-initialization", summary.required("coverage").textValue());
        assertEquals(report.toJsonSummary(), report.toJsonSummary());
    }

    @Test
    void validatesCaptureKindsAndSharedMetadataBeforeTicks() {
        // Break caught: reference/OpenGGF provenance is equated or cycle identity is ignored.
        AudioParityReport wrongKind = AudioParityComparator.compare(openGgfMetadata, ticks(baseTick),
                openGgfMetadata, ticks(baseTick));
        assertEquals(AudioParityReport.Kind.METADATA_MISMATCH, wrongKind.kind());
        assertEquals("capture", wrongKind.field());

        AudioParityMetadata differentCycle = metadata(AudioParitySchema.OPENGGF_CAPTURE, 1, 1, 4);
        AudioParityReport cycle = AudioParityComparator.compare(referenceMetadata, ticks(baseTick),
                differentCycle, fourTicks(baseTick));
        assertEquals(AudioParityReport.Kind.METADATA_MISMATCH, cycle.kind());
        assertEquals("cycle_start", cycle.field());
    }

    @Test
    void malformedSchemaAndRomIdentityAreCaptureFailures() throws Exception {
        // Break caught: incompatible schema or ROM identity reaches semantic comparison.
        Path reference = writeReferenceStream();
        Path open = temp.resolve("open.jsonl");
        AudioParityJsonl.write(open, openGgfMetadata, ticks(baseTick).iterator());

        String valid = Files.readString(reference);
        Files.writeString(reference, valid.replace(AudioParitySchema.VERSION,
                "openggf.s1_audio_parity_reference.v99"));
        AudioParityReport schema = AudioParityComparator.compare(reference, open);
        assertEquals(AudioParityReport.Kind.CAPTURE_FAILURE, schema.kind());
        assertTrue(schema.referenceValue().contains("schema"));

        Files.writeString(reference, valid.replace(AudioParitySchema.S1_REV01_CRC32, "00000000"));
        AudioParityReport identity = AudioParityComparator.compare(reference, open);
        assertEquals(AudioParityReport.Kind.CAPTURE_FAILURE, identity.kind());
        assertTrue(identity.referenceValue().contains("REV01"));
    }

    @Test
    void comparesValidatedJsonlStreamsWithoutEquatingTheirCaptureKinds() throws Exception {
        // Break caught: the bounded-memory path rejects the intentional reference/OpenGGF provenance split.
        Path reference = writeReferenceStream();
        Path open = temp.resolve("open-valid.jsonl");
        AudioParityJsonl.write(open, openGgfMetadata, ticks(baseTick).iterator());

        AudioParityReport report = AudioParityComparator.compare(reference, open);
        assertTrue(report.matches());
        assertEquals(3, report.ticksCompared());
    }

    @Test
    void pathComparisonChecksMetadataBeforeTickIntegrity() throws Exception {
        // Break caught: an ordinal error from an incompatible stream hides the higher-priority metadata gate.
        Path reference = writeReferenceStream();
        List<String> lines = Files.readAllLines(reference);
        lines.set(2, lines.get(2).replace("\"ordinal\":1", "\"ordinal\":2"));
        Files.write(reference, lines);
        Path open = temp.resolve("different-cycle.jsonl");
        AudioParityJsonl.write(open, metadata(AudioParitySchema.OPENGGF_CAPTURE, 1, 1, 4),
                fourTicks(baseTick).iterator());

        AudioParityReport report = AudioParityComparator.compare(reference, open);
        assertEquals(AudioParityReport.Kind.METADATA_MISMATCH, report.kind());
        assertEquals("cycle_start", report.field());
    }

    @Test
    void pathIntegrityReportsStructuredSideExpectedAndObservedValues() throws Exception {
        // Break caught: message parsing guesses the failure kind or reports equal cross-side counts.
        Path open = temp.resolve("open-integrity.jsonl");
        AudioParityJsonl.write(open, openGgfMetadata, ticks(baseTick).iterator());

        Path ordinalReference = writeReferenceStream();
        List<String> ordinalLines = Files.readAllLines(ordinalReference);
        ordinalLines.set(2, ordinalLines.get(2).replace("\"ordinal\":1", "\"ordinal\":2"));
        Files.write(ordinalReference, ordinalLines);
        AudioParityReport ordinal = AudioParityComparator.compare(ordinalReference, open);
        assertEquals(AudioParityReport.Kind.ORDINAL_MISMATCH, ordinal.kind());
        assertEquals(AudioParityReport.Side.REFERENCE, ordinal.side());
        assertEquals(1, ordinal.tickOrdinal());
        assertEquals("1", ordinal.expectedValue());
        assertEquals("2", ordinal.observedValue());
        assertEquals("2", ordinal.referenceValue());
        assertNull(ordinal.openGgfValue());
        assertTrue(ordinal.toHumanText().contains("side: reference\nexpected: 1\nobserved: 2"));
        assertTrue(ordinal.toJsonSummary().contains(
                "\"side\":\"reference\",\"expected\":\"1\",\"observed\":\"2\""));

        Path countReference = writeReferenceStream();
        List<String> countLines = Files.readAllLines(countReference);
        Files.write(countReference, countLines.subList(0, countLines.size() - 1));
        AudioParityReport count = AudioParityComparator.compare(countReference, open);
        assertEquals(AudioParityReport.Kind.TICK_COUNT_MISMATCH, count.kind());
        assertEquals(AudioParityReport.Side.REFERENCE, count.side());
        assertEquals("3", count.expectedValue());
        assertEquals("2", count.observedValue());
        assertEquals("2", count.referenceValue());
        assertNull(count.openGgfValue());
    }

    @Test
    void malformedTickNeverMasqueradesAsOrdinalOrCountIntegrity() throws Exception {
        // Break caught: exception-message words accidentally classify an unknown/type error as continuity.
        Path reference = writeReferenceStream();
        List<String> lines = Files.readAllLines(reference);
        lines.set(1, lines.get(1).replaceFirst("\\{", "{\"ordinal_note\":\"tick records\","));
        Files.write(reference, lines);
        Path open = temp.resolve("open-malformed.jsonl");
        AudioParityJsonl.write(open, openGgfMetadata, ticks(baseTick).iterator());

        AudioParityReport report = AudioParityComparator.compare(reference, open);
        assertEquals(AudioParityReport.Kind.CAPTURE_FAILURE, report.kind());
        assertEquals(AudioParityReport.Side.REFERENCE, report.side());
    }

    @Test
    void tickCountAndOrdinalIntegrityPrecedeSemanticComparison() {
        // Break caught: the comparator aligns around dropped ticks or reports their state as the root cause.
        AudioParityReport count = AudioParityComparator.compare(referenceMetadata, ticks(baseTick),
                openGgfMetadata, List.of(baseTick, baseTick.withOrdinal(1)));
        assertEquals(AudioParityReport.Kind.TICK_COUNT_MISMATCH, count.kind());
        assertEquals(AudioParityReport.Side.OPENGGF, count.side());
        assertEquals("3", count.expectedValue());
        assertEquals("2", count.observedValue());
        assertNull(count.referenceValue());
        assertEquals("2", count.openGgfValue());

        List<AudioParityTick> wrongOrdinal = new ArrayList<>(ticks(baseTick));
        wrongOrdinal.set(1, baseTick.withOrdinal(2));
        AudioParityReport ordinal = AudioParityComparator.compare(referenceMetadata, ticks(baseTick),
                openGgfMetadata, wrongOrdinal);
        assertEquals(AudioParityReport.Kind.ORDINAL_MISMATCH, ordinal.kind());
        assertEquals(AudioParityReport.Side.OPENGGF, ordinal.side());
        assertEquals(1, ordinal.tickOrdinal());
        assertEquals("1", ordinal.expectedValue());
        assertEquals("2", ordinal.observedValue());
        assertNull(ordinal.referenceValue());
        assertEquals("2", ordinal.openGgfValue());
    }

    @Test
    void rejectsAtomicReplacementBetweenValidationAndComparison() throws Exception {
        // Break caught: a valid replacement can turn a validated mismatch into an unvalidated false MATCH.
        Path reference = writeReferenceStream();
        Path open = temp.resolve("open-before.jsonl");
        AudioParityTick changed = new AudioParityTick(0,
                new AudioParityTick.GlobalState(false, "none", null, null, false,
                        baseTick.global().tempoReload() + 1, baseTick.global().tempoTimeout()),
                baseTick.tracks(), baseTick.events());
        AudioParityJsonl.write(open, openGgfMetadata, ticks(changed).iterator());
        Path replacement = temp.resolve("open-replacement.jsonl");
        AudioParityJsonl.write(replacement, openGgfMetadata, ticks(baseTick).iterator());

        AudioParityReport report = AudioParityComparator.compare(reference, open,
                () -> Files.move(replacement, open, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING));
        assertEquals(AudioParityReport.Kind.CAPTURE_FAILURE, report.kind());
        assertEquals(AudioParityReport.Side.OPENGGF, report.side());
        assertEquals("source_changed", report.field());
    }

    @Test
    void reportsFirstGlobalStateDifferenceBeforeWritesAtTheSameOrdinal() {
        // Break caught: register output masks an earlier logical-state divergence.
        AudioParityTick changed = new AudioParityTick(0,
                new AudioParityTick.GlobalState(baseTick.global().fadeActive(),
                        baseTick.global().fadeDirection(), baseTick.global().fadeDelay(),
                        baseTick.global().fadeSteps(), baseTick.global().speedUp(),
                        baseTick.global().tempoReload(), baseTick.global().tempoTimeout() + 1),
                baseTick.tracks(), mutateYmValue(baseTick.events(), 0));
        AudioParityReport report = compare(ticks(baseTick), ticks(changed));

        assertEquals(AudioParityReport.Kind.GLOBAL_STATE_MISMATCH, report.kind());
        assertEquals(0, report.tickOrdinal());
        assertEquals("GLOBAL", report.role());
        assertEquals("tempo_timeout", report.field());
        assertEquals(Integer.toString(baseTick.global().tempoTimeout()), report.referenceValue());
    }

    @Test
    void reportsFirstFixedRoleFieldDifference() {
        // Break caught: fixed slot state is compared wholesale without naming its semantic field and role.
        List<AudioParityTrackState> tracks = new ArrayList<>(baseTick.tracks());
        AudioParityTrackState fm1 = tracks.get(1);
        tracks.set(1, withVolume(fm1, fm1.volume() + 1));
        AudioParityTick changed = new AudioParityTick(0, baseTick.global(), tracks, baseTick.events());

        AudioParityReport report = compare(ticks(baseTick), ticks(changed));
        assertEquals(AudioParityReport.Kind.TRACK_STATE_MISMATCH, report.kind());
        assertEquals("FM1", report.role());
        assertEquals("volume", report.field());
    }

    @Test
    void classifiesYmValueDifferenceWithoutRealignment() {
        // Break caught: same-position YM mutations are misreported as missing/extra transactions.
        AudioParityTick changed = withEvents(baseTick, mutateYmValue(baseTick.events(), 0));
        AudioParityReport report = compare(ticks(baseTick), ticks(changed));

        assertEquals(AudioParityReport.Kind.EVENT_VALUE_DIFFERENT, report.kind());
        assertEquals(0, report.eventIndex());
        assertEquals(baseTick.events().get(0).toString(), report.referenceValue());
        assertTrue(report.stateContext().referenceTracks().stream().allMatch(AudioParityTrackState::active));
        assertTrue(report.stateContext().referenceTracks().stream()
                .anyMatch(track -> track.role().equals("FM1")));
        assertEquals(report.stateContext().referenceTracks(), report.stateContext().openGgfTracks());
        assertTrue(report.toHumanText().contains("active track context"));
        assertTrue(report.toJsonSummary().contains("\"stateContext\""));
    }

    @Test
    void classifiesOnlyProvenAdjacentSwapAsReordered() {
        // Break caught: the comparator silently realigns or calls a proven adjacent swap two value changes.
        List<AudioParityChipWrite> swapped = new ArrayList<>(baseTick.events());
        AudioParityChipWrite first = swapped.get(0);
        swapped.set(0, swapped.get(1));
        swapped.set(1, first);

        AudioParityReport report = compare(ticks(baseTick), ticks(withEvents(baseTick, swapped)));
        assertEquals(AudioParityReport.Kind.EVENT_REORDERED, report.kind());
        assertEquals(0, report.eventIndex());
    }

    @Test
    void classifiesSingleMissingAndExtraWritesWithoutRealignment() {
        // Break caught: insertion/deletion is hidden by an edit-distance alignment.
        List<AudioParityChipWrite> missing = new ArrayList<>(baseTick.events());
        missing.remove(1);
        AudioParityReport missingReport = compare(ticks(baseTick), ticks(withEvents(baseTick, missing)));
        assertEquals(AudioParityReport.Kind.EVENT_MISSING, missingReport.kind());
        assertEquals(1, missingReport.eventIndex());
        assertEquals("<missing>", missingReport.openGgfValue());

        List<AudioParityChipWrite> extra = new ArrayList<>(baseTick.events());
        extra.add(1, AudioParityChipWrite.psg(0x9f));
        AudioParityReport extraReport = compare(ticks(baseTick), ticks(withEvents(baseTick, extra)));
        assertEquals(AudioParityReport.Kind.EVENT_EXTRA, extraReport.kind());
        assertEquals(1, extraReport.eventIndex());
        assertEquals("<missing>", extraReport.referenceValue());
    }

    @Test
    void boundsTransactionContextToEightOnEachSideForBothCaptures() {
        // Break caught: a first mismatch report retains or prints an unbounded music transaction stream.
        List<AudioParityChipWrite> referenceEvents = events(25);
        List<AudioParityChipWrite> changedEvents = new ArrayList<>(referenceEvents);
        AudioParityChipWrite original = changedEvents.get(12);
        changedEvents.set(12, AudioParityChipWrite.ym2612(original.port(), original.register(),
                (original.value() + 1) & 0xff));

        AudioParityReport report = compare(ticks(withEvents(baseTick, referenceEvents)),
                ticks(withEvents(baseTick, changedEvents)));
        AudioParityReport.EventContext context = report.eventContext();
        assertEquals(8, context.referenceBefore().size());
        assertEquals(8, context.referenceAfter().size());
        assertEquals(8, context.openGgfBefore().size());
        assertEquals(8, context.openGgfAfter().size());
        assertEquals(4, context.referenceBefore().get(0).index());
        assertEquals(20, context.referenceAfter().get(7).index());
    }

    @Test
    void returnsOnlyTheEarliestMismatchAcrossTicks() {
        // Break caught: later differences overwrite or accumulate after the first divergence.
        List<AudioParityTick> actual = ticks(baseTick);
        AudioParityTick first = actual.get(0);
        actual.set(0, new AudioParityTick(0,
                new AudioParityTick.GlobalState(false, "none", null, null, false,
                        first.global().tempoReload() + 1, first.global().tempoTimeout()),
                first.tracks(), first.events()));
        actual.set(1, withEvents(actual.get(1), mutateYmValue(actual.get(1).events(), 0)));

        AudioParityReport report = compare(ticks(baseTick), actual);
        assertEquals(AudioParityReport.Kind.GLOBAL_STATE_MISMATCH, report.kind());
        assertEquals(0, report.tickOrdinal());
        assertNull(report.eventContext());
        assertFalse(report.toHumanText().contains("tick 1"));
    }

    private AudioParityReport compare(List<AudioParityTick> reference, List<AudioParityTick> openGgf) {
        return AudioParityComparator.compare(referenceMetadata, reference, openGgfMetadata, openGgf);
    }

    private List<AudioParityTick> ticks(AudioParityTick tickZero) {
        return new ArrayList<>(List.of(tickZero.withOrdinal(0), tickZero.withOrdinal(1), tickZero.withOrdinal(2)));
    }

    private List<AudioParityTick> fourTicks(AudioParityTick tickZero) {
        return List.of(tickZero.withOrdinal(0), tickZero.withOrdinal(1), tickZero.withOrdinal(2),
                tickZero.withOrdinal(3));
    }

    private AudioParityMetadata metadata(String capture, int cycleStart, int period, int count) {
        return new AudioParityMetadata(AudioParitySchema.VERSION, capture, cycleStart, period, count,
                AudioParitySchema.S1_REV01_SHA1, AudioParitySchema.S1_REV01_CRC32, null);
    }

    private AudioParityTick withEvents(AudioParityTick tick, List<AudioParityChipWrite> events) {
        return new AudioParityTick(tick.ordinal(), tick.global(), tick.tracks(), events);
    }

    private List<AudioParityChipWrite> mutateYmValue(List<AudioParityChipWrite> source, int index) {
        List<AudioParityChipWrite> changed = new ArrayList<>(source);
        AudioParityChipWrite original = changed.get(index);
        changed.set(index, AudioParityChipWrite.ym2612(original.port(), original.register(),
                (original.value() + 1) & 0xff));
        return changed;
    }

    private AudioParityTrackState withVolume(AudioParityTrackState track, int volume) {
        return new AudioParityTrackState(track.role(), track.hardware(), track.active(), track.baseFrequency(),
                track.detune(), track.doNotAttack(), track.duration(), track.durationReload(),
                track.envelopeCursor(), track.loopCounters(), track.modulationEnabled(), track.overridden(),
                track.pan(), track.ams(), track.fms(), track.returnStack(), track.sequencePosition(),
                track.transpose(), track.voiceOrEnvelope(), volume);
    }

    private List<AudioParityChipWrite> events(int count) {
        List<AudioParityChipWrite> events = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            events.add(AudioParityChipWrite.ym2612(index & 1, 0x30 + index, 0x40 + index));
        }
        return events;
    }

    private Path writeReferenceStream() throws Exception {
        Path path = temp.resolve("reference.jsonl");
        String metadata = "{\"callback_contract\":{\"arguments\":[\"address\",\"value\",\"flags\"],"
                + "\"proof\":{\"fm_port0_pairs\":2,\"fm_port1_pairs\":1,\"psg_writes\":3},"
                + "\"source\":\"memory_callback\"},"
                + "\"capture\":\"" + AudioParitySchema.REFERENCE_CAPTURE + "\","
                + "\"cycle_start\":0,"
                + "\"diagnostic_fields\":{\"global\":[\"priority\",\"pause\",\"fade flags\",\"queues\","
                + "\"sound id\",\"voice selector\",\"DAC update\",\"1-up\",\"speed-up reload\","
                + "\"communication\",\"ring speaker\",\"push\"],\"track\":[\"resting\",\"note fill\","
                + "\"modulation phase\",\"raw status\",\"raw voice control\"]},"
                + "\"gating_fields\":{\"global\":[\"tempo timeout\",\"tempo reload\",\"speed-up\","
                + "\"fade state\"],\"track\":[\"active\",\"role\",\"hardware\",\"overridden\","
                + "\"do not attack\",\"modulation enabled\",\"sequence position\",\"transpose\",\"volume\","
                + "\"pan/AMS/FMS\",\"voice/envelope\",\"duration\",\"duration reload\","
                + "\"PSG envelope cursor\",\"base frequency\",\"detune\",\"live loop counters\","
                + "\"live return stack\"]},\"launch_update_music_invocations\":514,"
                + "\"movie\":{\"archive_sha256\":\"" + AudioParitySchema.BK2_SHA256 + "\","
                + "\"core\":\"Genplus-gx\",\"emulator\":\"Version 2.11\","
                + "\"game\":\"Sonic The Hedgehog (W) (REV01) [!]\",\"input_rows\":989,"
                + "\"opaque_header_hash\":\"09DADB5071EB35050067A32462E39C5F\"},"
                + "\"period\":1,\"rom\":{\"crc32\":\"" + AudioParitySchema.S1_REV01_CRC32
                + "\",\"sha1\":\"" + AudioParitySchema.S1_REV01_SHA1 + "\"},\"schema\":\""
                + AudioParitySchema.VERSION + "\",\"terminal_record_count\":3,\"type\":\"capture_metadata\"}";
        StringBuilder stream = new StringBuilder(metadata).append('\n');
        for (AudioParityTick tick : ticks(baseTick)) {
            stream.append(AudioParityJsonl.tickTree(tick)).append('\n');
        }
        Files.writeString(path, stream);
        return path;
    }
}
