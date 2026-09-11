package com.openggf.tools.audio.parity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the sound-test SFX capture kinds: movie-bounded metadata,
 * the per-tick dispatch sequence, and the comparator's kind pairing.
 */
class TestS1SfxAudioParitySchema {

    @Test
    void sfxMetadataIsMovieBoundedWithoutARecurrenceProof() {
        AudioParityMetadata metadata = AudioParityMetadata.openGgfSfx(1966,
                AudioParitySchema.S1_REV01_SHA1, AudioParitySchema.S1_REV01_CRC32);
        assertEquals(AudioParitySchema.SFX_OPENGGF_CAPTURE, metadata.capture());
        assertEquals(0, metadata.cycleStart());
        assertEquals(0, metadata.period());
        assertEquals(1966, metadata.terminalRecordCount());

        assertThrows(IllegalArgumentException.class, () -> new AudioParityMetadata(
                AudioParitySchema.VERSION, AudioParitySchema.SFX_OPENGGF_CAPTURE, 1, 0, 3,
                AudioParitySchema.S1_REV01_SHA1, AudioParitySchema.S1_REV01_CRC32, null));
        assertThrows(IllegalArgumentException.class, () -> new AudioParityMetadata(
                AudioParitySchema.VERSION, AudioParitySchema.SFX_OPENGGF_CAPTURE, 0, 7, 3,
                AudioParitySchema.S1_REV01_SHA1, AudioParitySchema.S1_REV01_CRC32, null));
    }

    @Test
    void dispatchSequenceRoundTripsThroughTheTickTransport() {
        AudioParityTick tick = tick(0, List.of(0xA0, 0xB5));
        String json = AudioParityJsonl.tickTree(tick).toString();
        AudioParityTick parsed = AudioParityJsonl.parseTick(json);
        assertEquals(List.of(0xA0, 0xB5), parsed.dispatches());
        assertEquals(tick.events(), parsed.events());

        AudioParityTick musicTick = tick(0, null);
        AudioParityTick musicParsed = AudioParityJsonl.parseTick(
                AudioParityJsonl.tickTree(musicTick).toString());
        assertNull(musicParsed.dispatches());

        assertThrows(IllegalArgumentException.class, () -> tick(0, List.of(0x1A0)));
    }

    @Test
    void comparatorPairsSfxKindsAndReportsTheFirstDispatchDivergence() {
        AudioParityMetadata reference = new AudioParityMetadata(AudioParitySchema.VERSION,
                AudioParitySchema.SFX_REFERENCE_CAPTURE, 0, 0, 2,
                AudioParitySchema.S1_REV01_SHA1, AudioParitySchema.S1_REV01_CRC32,
                sfxReferenceDetails());
        AudioParityMetadata openGgf = AudioParityMetadata.openGgfSfx(2,
                AudioParitySchema.S1_REV01_SHA1, AudioParitySchema.S1_REV01_CRC32);

        AudioParityReport match = AudioParityComparator.compare(reference,
                List.of(tick(0, List.of()), tick(1, List.of(0xCC))), openGgf,
                List.of(tick(0, List.of()), tick(1, List.of(0xCC))));
        assertTrue(match.matches(), match.toHumanText());

        AudioParityReport mismatch = AudioParityComparator.compare(reference,
                List.of(tick(0, List.of()), tick(1, List.of(0xCC))), openGgf,
                List.of(tick(0, List.of()), tick(1, List.of(0xA0))));
        assertEquals(AudioParityReport.Kind.DISPATCH_MISMATCH, mismatch.kind());
        assertEquals(1, mismatch.tickOrdinal());
        assertEquals("dispatches", mismatch.field());

        AudioParityReport wrongPair = AudioParityComparator.compare(reference,
                List.of(tick(0, List.of()), tick(1, List.of())),
                AudioParityMetadata.openGgf(0, 1, 3, AudioParitySchema.S1_REV01_SHA1,
                        AudioParitySchema.S1_REV01_CRC32),
                List.of(tick(0, null), tick(1, null)));
        assertEquals(AudioParityReport.Kind.METADATA_MISMATCH, wrongPair.kind());
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode sfxReferenceDetails() {
        var details = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        var callback = details.putObject("callback_contract");
        callback.putArray("arguments").add("address").add("value").add("flags");
        callback.putObject("proof").put("fm_port0_pairs", 1).put("fm_port1_pairs", 1)
                .put("psg_writes", 1);
        callback.put("source", "memory_callback");
        var diagnostic = details.putObject("diagnostic_fields");
        AudioParitySchema.DIAGNOSTIC_GLOBAL_FIELDS.forEach(diagnostic.putArray("global")::add);
        AudioParitySchema.DIAGNOSTIC_TRACK_FIELDS.forEach(diagnostic.putArray("track")::add);
        var gating = details.putObject("gating_fields");
        AudioParitySchema.SFX_GATING_GLOBAL_FIELDS.forEach(gating.putArray("global")::add);
        AudioParitySchema.GATING_TRACK_FIELDS.forEach(gating.putArray("track")::add);
        details.put("launch_update_music_invocations", AudioParitySchema.SFX_BK2_LAUNCH_INVOCATIONS);
        details.putObject("movie")
                .put("archive_sha256", AudioParitySchema.SFX_BK2_SHA256)
                .put("core", AudioParitySchema.BK2_CORE)
                .put("emulator", AudioParitySchema.BK2_EMULATOR)
                .put("game", AudioParitySchema.BK2_GAME)
                .put("input_rows", AudioParitySchema.SFX_BK2_INPUT_ROWS)
                .put("opaque_header_hash", AudioParitySchema.SFX_BK2_OPAQUE_HASH);
        return details;
    }

    private static AudioParityTick tick(int ordinal, List<Integer> dispatches) {
        return new AudioParityTick(ordinal,
                new AudioParityTick.GlobalState(false, "none", null, null, false, 2, 1),
                AudioParitySchema.ROLES.stream().map(AudioParityTrackState::inactive).toList(),
                List.of(), dispatches);
    }
}
