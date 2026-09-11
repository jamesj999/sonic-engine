package com.openggf.tools.audio.parity;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.game.sonic1.audio.Sonic1SmpsSequencerConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS1OpenGgfAudioCapture {
    private static final Path REFERENCE = Path.of("target", "audio-parity",
            "s1-reviewfix2-full-1.jsonl");

    @TempDir
    Path temp;

    @Test
    void s1ReleaseProfileLeavesTheCompletedSfxNoteOffAuthoritative() {
        assertEquals(SmpsSequencerConfig.PsgSfxTakeoverMode.S1_PSG3_SILENCE_PAIR,
                Sonic1SmpsSequencerConfig.CONFIG.getPsgSfxTakeoverMode());
        assertEquals(SmpsSequencerConfig.SfxTrackWalkMode.CHANNEL_RAM_ORDER,
                Sonic1SmpsSequencerConfig.CONFIG.getSfxTrackWalkMode());
        assertEquals(SmpsSequencerConfig.FmVolumeVoiceBankMode.S1_SPECIAL_POINTER_BUG,
                Sonic1SmpsSequencerConfig.CONFIG.getFmVolumeVoiceBankMode());
        assertEquals(SmpsSequencerConfig.FmSfxReleaseMode.ROM_VOICE_RESTORE,
                Sonic1SmpsSequencerConfig.CONFIG.getFmSfxReleaseMode());
        assertEquals(SmpsSequencerConfig.PsgSfxReleaseMode.ROM_REST_RESTORE,
                Sonic1SmpsSequencerConfig.CONFIG.getPsgSfxReleaseMode());
    }

    @Test
    void realGhzCaptureStartsAfterPowerOnAndUsesOneNtscServicePerRecord() throws Exception {
        Path rom = requiredRom();
        Path shortReference = shortReference(3);
        Path output = temp.resolve("openggf.jsonl");

        S1OpenGgfAudioCapture.CaptureResult result =
                S1OpenGgfAudioCapture.capture(shortReference, rom, output);

        List<AudioParityTick> ticks = new ArrayList<>();
        AudioParityMetadata metadata = AudioParityJsonl.read(output, ticks::add);
        assertEquals(AudioParitySchema.OPENGGF_CAPTURE, metadata.capture());
        assertEquals(3, metadata.terminalRecordCount());
        assertEquals(3, result.recordCount());
        assertEquals(2L * 735L, result.advancedSamples());
        assertEquals(List.of(0.0, 0.0, 0.0), result.postTickSampleCounters());

        // The reference epoch begins in S1 InitMusicPlayback, not at chip power-on or
        // Java object construction. Preserve the shipped driver's descending key-off,
        // interleaved-port TL silence, then PSG silence ordering exactly.
        assertEquals(s1GhzMusicLoadWrites(), ticks.get(0).events().subList(0, 49));
        assertFalse(ticks.get(0).events().isEmpty());
        assertFalse(ticks.get(1).events().isEmpty());
        assertEquals(2, ticks.get(0).global().tempoTimeout(),
                "tick zero must snapshot after the one S1 priming tempo service");
        assertEquals(1, ticks.get(1).global().tempoTimeout(),
                "the next snapshot must follow exactly one later service");
    }

    private static List<AudioParityChipWrite> s1GhzMusicLoadWrites() {
        List<AudioParityChipWrite> writes = new ArrayList<>();
        for (int channel = 2; channel >= 0; channel--) {
            writes.add(AudioParityChipWrite.ym2612(0, 0x28, channel));
            writes.add(AudioParityChipWrite.ym2612(0, 0x28, channel + 4));
        }
        for (int channel = 0; channel < 3; channel++) {
            for (int operator = 0; operator < 4; operator++) {
                int register = 0x40 + channel + operator * 4;
                writes.add(AudioParityChipWrite.ym2612(0, register, 0x7f));
                writes.add(AudioParityChipWrite.ym2612(1, register, 0x7f));
            }
        }
        writes.add(AudioParityChipWrite.psg(0x9f));
        writes.add(AudioParityChipWrite.psg(0xbf));
        writes.add(AudioParityChipWrite.psg(0xdf));
        writes.add(AudioParityChipWrite.psg(0xff));
        // GHZ defines DAC + five FM tracks. The shipped FixBugs=0 loader silences
        // the absent FM6, then note-offs six FM slots (the cleared absent slot aliases
        // FM1's zero channel byte), followed by the three declared PSG slots.
        writes.add(AudioParityChipWrite.ym2612(0, 0x28, 6));
        for (int register : List.of(0x42, 0x4a, 0x46, 0x4e)) {
            writes.add(AudioParityChipWrite.ym2612(1, register, 0x7f));
        }
        writes.add(AudioParityChipWrite.ym2612(1, 0xb6, 0xc0));
        for (int channel : List.of(0, 1, 2, 4, 5, 0)) {
            writes.add(AudioParityChipWrite.ym2612(0, 0x28, channel));
        }
        writes.add(AudioParityChipWrite.psg(0x9f));
        writes.add(AudioParityChipWrite.psg(0xbf));
        writes.add(AudioParityChipWrite.psg(0xdf));
        return List.copyOf(writes);
    }

    @Test
    void referenceTerminalCountControlsTheRunAndOutputIsDeterministic() throws Exception {
        Path rom = requiredRom();
        Path shortReference = shortReference(3);
        Path first = temp.resolve("first.jsonl");
        Path second = temp.resolve("second.jsonl");

        S1OpenGgfAudioCapture.capture(shortReference, rom, first);
        S1OpenGgfAudioCapture.capture(shortReference, rom, second);

        assertEquals(Files.readAllBytes(first).length, Files.readAllBytes(second).length);
        assertEquals(sha256(first), sha256(second));
        assertEquals(4, Files.readAllLines(first).size());
    }

    @Test
    void missingOrWrongRomFailsBeforePublishingOutput() throws Exception {
        Path shortReference = shortReference(3);
        Path output = temp.resolve("preserved.jsonl");
        Files.writeString(output, "preserve-me\n");

        assertThrows(IllegalArgumentException.class, () -> S1OpenGgfAudioCapture.capture(
                shortReference, temp.resolve("missing.gen"), output));
        assertEquals("preserve-me\n", Files.readString(output));

        Path wrong = temp.resolve("wrong.gen");
        Files.writeString(wrong, "not a Sonic 1 ROM");
        assertThrows(IllegalArgumentException.class,
                () -> S1OpenGgfAudioCapture.capture(shortReference, wrong, output));
        assertEquals("preserve-me\n", Files.readString(output));
    }

    @Test
    void derivesGhzBoundsAndLoopIndicesFromTheRomBackedSong() {
        S1OpenGgfAudioCapture.SongContract contract =
                S1OpenGgfAudioCapture.inspectGhz(requiredRom());

        assertEquals(0x745dcL, contract.assetRange().romBase());
        assertEquals(0x74d44L, contract.assetRange().romEndExclusive());
        assertEquals(List.of(0, 1), contract.f7LoopIndices().stream().sorted().toList());
    }

    @Test
    void capturesTheCompleteReferenceControlledInterval() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(REFERENCE), "local deterministic BizHawk reference required");
        AudioParityMetadata referenceMetadata = metadata(REFERENCE);
        Path first = temp.resolve("full-openggf-1.jsonl");
        Path second = temp.resolve("full-openggf-2.jsonl");

        S1OpenGgfAudioCapture.CaptureResult result =
                S1OpenGgfAudioCapture.capture(REFERENCE, requiredRom(), first);
        S1OpenGgfAudioCapture.capture(REFERENCE, requiredRom(), second);

        assertEquals(14_690, referenceMetadata.terminalRecordCount());
        assertEquals(referenceMetadata.terminalRecordCount(), result.recordCount());
        try (var lines = Files.lines(first)) {
            assertEquals(referenceMetadata.terminalRecordCount() + 1L, lines.count());
        }
        assertEquals(sha256(first), sha256(second));
    }

    private Path shortReference(int terminalCount) throws Exception {
        ObjectNode root = (ObjectNode) AudioParityJsonl.metadataTree(AudioParityMetadata.openGgf(
                0, 1, terminalCount, AudioParitySchema.S1_REV01_SHA1,
                AudioParitySchema.S1_REV01_CRC32));
        root.put("capture", AudioParitySchema.REFERENCE_CAPTURE);
        root.put("cycle_start", 0);
        root.put("period", 1);
        root.put("terminal_record_count", terminalCount);
        root.put("launch_update_music_invocations", 514);
        ObjectNode callback = root.putObject("callback_contract");
        callback.putArray("arguments").add("address").add("value").add("flags");
        callback.putObject("proof")
                .put("fm_port0_pairs", 26_143)
                .put("fm_port1_pairs", 4_363)
                .put("psg_writes", 23_530);
        callback.put("source", "memory_callback");
        ObjectNode diagnostic = root.putObject("diagnostic_fields");
        addStrings(diagnostic.putArray("global"), AudioParitySchema.DIAGNOSTIC_GLOBAL_FIELDS);
        addStrings(diagnostic.putArray("track"), AudioParitySchema.DIAGNOSTIC_TRACK_FIELDS);
        ObjectNode gating = root.putObject("gating_fields");
        addStrings(gating.putArray("global"), AudioParitySchema.GATING_GLOBAL_FIELDS);
        addStrings(gating.putArray("track"), AudioParitySchema.GATING_TRACK_FIELDS);
        ObjectNode movie = root.putObject("movie");
        movie.put("archive_sha256", AudioParitySchema.BK2_SHA256);
        movie.put("core", AudioParitySchema.BK2_CORE);
        movie.put("emulator", AudioParitySchema.BK2_EMULATOR);
        movie.put("game", AudioParitySchema.BK2_GAME);
        movie.put("input_rows", AudioParitySchema.BK2_INPUT_ROWS);
        movie.put("opaque_header_hash", AudioParitySchema.BK2_OPAQUE_HASH);
        Path result = temp.resolve("reference-" + terminalCount + ".jsonl");
        Files.writeString(result, root + "\n", StandardCharsets.UTF_8);
        return result;
    }

    private static void addStrings(ArrayNode target, List<String> values) {
        values.forEach(target::add);
    }

    private static AudioParityMetadata metadata(Path source) throws Exception {
        try (var reader = Files.newBufferedReader(source)) {
            return AudioParityJsonl.parseMetadata(reader.readLine());
        }
    }

    private static Path requiredRom() {
        String configured = System.getProperty("sonic1.rom.path");
        Assumptions.assumeTrue(configured != null && !configured.isBlank(),
                "-Dsonic1.rom.path is required");
        return Path.of(configured);
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
