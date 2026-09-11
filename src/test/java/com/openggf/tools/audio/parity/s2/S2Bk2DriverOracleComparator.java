package com.openggf.tools.audio.parity.s2;

import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.rewind.SmpsSequencerSnapshot;
import com.openggf.audio.rewind.SmpsTrackSnapshot;
import com.openggf.audio.rewind.SmpsSourceDescriptor;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.game.sonic2.audio.Sonic2SmpsSequencerConfig;
import com.openggf.tests.trace.runs.S2RequestProjectionBk2TestBridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Comparison-only folding of committed production rows into native update intervals. */
final class S2Bk2DriverOracleComparator {
    private S2Bk2DriverOracleComparator() {
    }

    record FoldedTick(int ordinal, SmpsDriverSnapshot snapshot,
            List<S2OracleRawStream.ChipWrite> writes) {
        FoldedTick {
            Objects.requireNonNull(snapshot, "snapshot");
            writes = List.copyOf(writes);
        }
    }

    static S2AudioOracleComparator.Report compareAuthenticatedFixture(
            Path fixture,
            List<S2RequestProjectionBk2TestBridge.ProductionAudioRow> rows) {
        Objects.requireNonNull(fixture, "fixture");
        try {
            if (!S2OracleSchema.PAYLOAD_GZ_SHA256.equals(sha256(fixture))) {
                return invalid("fixture digest changed");
            }
            List<S2OracleRawStream.Frame> frames = new ArrayList<>();
            S2OracleRawStream.scan(fixture, new S2OracleRawStream.Sink() {
                @Override public void header(S2OracleRawStream.Header header) { }
                @Override public void baseline(S2OracleRawStream.Baseline baseline) { }
                @Override public void frame(S2OracleRawStream.Frame frame) {
                    frames.add(frame);
                }
                @Override public void cutoff(int exclusiveEnd) { }
            });
            for (S2OracleRawStream.Frame frame : frames) {
                if (frame.row() >= S2OracleSchema.ANCHOR_ROW
                        && frame.updateMusicCompletions() > 1) {
                    return invalid("reference row carries multiple update completions");
                }
            }
            List<S2AudioOracleComparator.ReferenceTick> reference =
                    S2AudioOracleComparator.buildTicks(
                            frames, S2OracleSchema.ANCHOR_ROW);
            List<FoldedTick> folded = foldRows(reference, rows);
            List<S2OracleEngineCapture.EngineTick> engine = new ArrayList<>();
            for (FoldedTick tick : folded) {
                engine.add(map(tick));
            }
            return S2AudioOracleComparator.compareWithEngine(reference, engine);
        } catch (IOException | RuntimeException failure) {
            return invalid(failure.getClass().getSimpleName() + ": "
                    + failure.getMessage());
        }
    }

    static List<FoldedTick> foldRows(
            List<S2AudioOracleComparator.ReferenceTick> reference,
            List<S2RequestProjectionBk2TestBridge.ProductionAudioRow> rows) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(rows, "rows");
        if (reference.isEmpty()) {
            throw new IllegalArgumentException("reference has no completed updates");
        }
        if (rows.isEmpty() || rows.getFirst().row() != S2OracleSchema.ANCHOR_ROW) {
            throw new IllegalArgumentException(
                    "production rows do not start at the anchor: first="
                            + (rows.isEmpty() ? "absent" : rows.getFirst().row()));
        }
        List<FoldedTick> result = new ArrayList<>(reference.size());
        List<S2OracleRawStream.ChipWrite> pending = new ArrayList<>();
        int referenceIndex = 0;
        int previousRow = S2OracleSchema.ANCHOR_ROW - 1;
        for (S2RequestProjectionBk2TestBridge.ProductionAudioRow row : rows) {
            if (row.row() != previousRow + 1) {
                throw new IllegalArgumentException(
                        "production rows are not exact and contiguous");
            }
            previousRow = row.row();
            pending.addAll(row.writes());
            if (referenceIndex < reference.size()
                    && row.row() == reference.get(referenceIndex).row()) {
                if (!row.completedDriverService() || row.snapshot() == null) {
                    throw new IllegalArgumentException(
                            "completion row has no committed driver service");
                }
                result.add(new FoldedTick(referenceIndex, row.snapshot(), pending));
                pending = new ArrayList<>();
                referenceIndex++;
            } else if (referenceIndex < reference.size()
                    && row.row() > reference.get(referenceIndex).row()) {
                throw new IllegalArgumentException("reference completion row was skipped");
            }
            if (referenceIndex == reference.size()) {
                break;
            }
        }
        if (referenceIndex != reference.size()) {
            throw new IllegalArgumentException(
                    "production rows do not cover every reference completion");
        }
        return List.copyOf(result);
    }

    /** True when a snapshot carries exactly the one EHZ music sequencer the
     * engine-shaped tick is built from. */
    static boolean hasSingleEhzMusicSequencer(SmpsDriverSnapshot snapshot) {
        return snapshot != null && snapshot.sequencers().stream()
                .filter(entry -> !entry.sfx())
                .filter(entry -> entry.source().kind()
                        == SmpsSourceDescriptor.Kind.BASE_MUSIC)
                .filter(entry -> entry.source().id() == Sonic2Music.EMERALD_HILL.id)
                .count() == 1;
    }

    /**
     * The engine's music DAC entry at a completion snapshot: the note its DAC
     * track last played, which is what {@code SmpsSequencer} hands to
     * {@code playDac}, resolved through the ROM's own DAC table. It is the
     * engine's counterpart to {@code zCurDAC}, which {@code zUpdateDAC} rebases
     * by {@code 81h} (s2.sounddriver.asm:505-518), so the two are never
     * compared to each other; each side reads only its own.
     *
     * @return {@code {selector, decoded length}}, the length being -1 when the
     *     note names no sample.
     */
    static int[] dacSampleAndLength(SmpsDriverSnapshot snapshot) {
        for (SmpsDriverSnapshot.SequencerEntry entry : snapshot.sequencers()) {
            if (entry.sfx()
                    || entry.source().kind() != SmpsSourceDescriptor.Kind.BASE_MUSIC
                    || entry.source().id() != Sonic2Music.EMERALD_HILL.id) {
                continue;
            }
            for (SmpsTrackSnapshot track : entry.snapshot().tracks()) {
                if (track.type() != SmpsSequencer.TrackType.DAC) {
                    continue;
                }
                int note = track.note() & 0xff;
                return new int[] { note, decodedLength(entry.dacData(), note) };
            }
        }
        return new int[] { -1, -1 };
    }

    /**
     * The decoded length of the sample a note selects, from {@code zDACLenTbl}
     * as the engine loaded it (s2.sounddriver.asm:513-518, :528-529), or -1.
     */
    static int decodedLength(DacData dacData, int note) {
        if (dacData == null) {
            return -1;
        }
        DacData.DacEntry entry = dacData.mappingForNote(note);
        if (entry == null) {
            return -1;
        }
        DacData.Sample sample = dacData.sample(entry.sampleId());
        return sample == null ? -1 : sample.length();
    }

    /**
     * The same mapping for a v2 driver-update tick, whose scope is one engine
     * driver update rather than one folded reference row.
     */
    static S2OracleEngineCapture.EngineTick mapUpdateTick(int ordinal,
            SmpsDriverSnapshot snapshot,
            List<S2OracleRawStream.ChipWrite> writes) {
        return map(new FoldedTick(ordinal, snapshot, writes));
    }

    private static S2OracleEngineCapture.EngineTick map(FoldedTick tick) {
        List<SmpsDriverSnapshot.SequencerEntry> music = tick.snapshot()
                .sequencers().stream()
                .filter(entry -> !entry.sfx())
                .filter(entry -> entry.source().kind()
                        == SmpsSourceDescriptor.Kind.BASE_MUSIC)
                .filter(entry -> entry.source().id()
                        == Sonic2Music.EMERALD_HILL.id)
                .toList();
        if (music.size() != 1) {
            throw new IllegalArgumentException(
                    "completion snapshot does not contain exactly one EHZ music sequencer");
        }
        SmpsDriverSnapshot.SequencerEntry entry = music.getFirst();
        SmpsSequencerSnapshot snapshot = entry.snapshot();
        int tempo = snapshot.normalTempo() & 0xff;
        if (snapshot.speedShoes()) {
            Integer speedTempo = Sonic2SmpsSequencerConfig.SPEED_UP_TEMPOS.get(
                    entry.smpsData().getId());
            if (speedTempo != null) {
                tempo = speedTempo;
            }
        }
        return new S2OracleEngineCapture.EngineTick(
                tick.ordinal(), tempo, snapshot.tempoAccumulator() & 0xff,
                mapMusicSlots(snapshot, entry.source().z80StartAddress()),
                tick.writes());
    }

    private static List<S2OracleComparison.MappedTrack> mapMusicSlots(
            SmpsSequencerSnapshot snapshot, int z80Start) {
        S2OracleComparison.MappedTrack[] slots =
                new S2OracleComparison.MappedTrack[
                        S2OracleDriverState.MUSIC_SLOTS.size()];
        for (SmpsTrackSnapshot track : snapshot.tracks()) {
            int slot = switch (track.type()) {
                case DAC -> 0;
                case FM -> 1 + track.channelId();
                case PSG -> 7 + track.channelId();
            };
            if (slot < 0 || slot >= slots.length || slots[slot] != null) {
                throw new IllegalArgumentException(
                        "music track does not map to a unique S2 slot");
            }
            slots[slot] = S2OracleComparison.MappedTrack.fromEngine(
                    track, z80Start);
        }
        for (int index = 0; index < slots.length; index++) {
            if (slots[index] == null) {
                slots[index] = S2OracleComparison.MappedTrack.absent();
            }
        }
        return List.of(slots);
    }

    private static S2AudioOracleComparator.Report invalid(String reason) {
        return new S2AudioOracleComparator.Report(
                S2AudioOracleComparator.Kind.INVALID,
                0, 0, -1, -1, null, null, reason);
    }

    private static String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
