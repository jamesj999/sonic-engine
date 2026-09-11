package com.openggf.tools.audio.parity;

import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.session.LegacyCompatibilitySmpsPhysicalPolicy;
import com.openggf.audio.session.OwnedSmpsAudioStream;
import com.openggf.audio.session.SmpsPhysicalDevice;
import com.openggf.audio.rewind.SmpsSequencerSnapshot;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.data.Rom;
import com.openggf.game.sonic1.audio.Sonic1Music;
import com.openggf.game.sonic1.audio.Sonic1SmpsConstants;
import com.openggf.game.sonic1.audio.Sonic1SmpsSequencerConfig;
import com.openggf.game.sonic1.audio.smps.Sonic1SmpsLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.zip.CRC32;

/** ROM-backed test host that records OpenGGF's S1 GHZ SMPS invocation contract. */
public final class S1OpenGgfAudioCapture {
    private static final double SAMPLE_RATE = 44_100.0;
    private static final int NTSC_SAMPLES = 735;

    private S1OpenGgfAudioCapture() {
    }

    public record SongContract(S1AudioStateNormalizer.GhzAssetRange assetRange,
            Set<Integer> f7LoopIndices) {
        public SongContract {
            Objects.requireNonNull(assetRange, "assetRange");
            f7LoopIndices = Set.copyOf(Objects.requireNonNull(f7LoopIndices, "f7LoopIndices"));
        }
    }

    public record CaptureResult(int recordCount, long advancedSamples,
            List<Double> postTickSampleCounters, SongContract songContract) {
        public CaptureResult {
            postTickSampleCounters = List.copyOf(postTickSampleCounters);
            Objects.requireNonNull(songContract, "songContract");
        }
    }

    public static CaptureResult capture(Path reference, Path romPath, Path output) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(romPath, "romPath");
        Objects.requireNonNull(output, "output");
        AudioParityMetadata referenceMetadata = readReferenceMetadata(reference);
        if (!AudioParitySchema.REFERENCE_CAPTURE.equals(referenceMetadata.capture())) {
            throw new IllegalArgumentException("reference stream is not a BizHawk S1 capture");
        }

        verifyRomIdentity(romPath);
        try (Rom rom = openRom(romPath)) {
            Sonic1SmpsLoader loader = new Sonic1SmpsLoader(rom);
            AbstractSmpsData song = Objects.requireNonNull(loader.loadMusic(Sonic1Music.GHZ.id),
                    "S1 GHZ music is absent from the verified ROM");
            DacData dacData = Objects.requireNonNull(loader.loadDacData(),
                    "S1 DAC data is absent from the verified ROM");
            SongContract contract = inspectGhz(rom, song);

            AudioParityMetadata outputMetadata = AudioParityMetadata.openGgf(
                    referenceMetadata.cycleStart(), referenceMetadata.period(),
                    referenceMetadata.terminalRecordCount(), referenceMetadata.romSha1(),
                    referenceMetadata.romCrc32());
            try (CaptureIterator ticks = new CaptureIterator(
                    song, dacData, contract,
                    referenceMetadata.terminalRecordCount())) {
                AudioParityJsonl.writeNew(output, outputMetadata, ticks);
                return ticks.result();
            }
        }
    }

    public static SongContract inspectGhz(Path romPath) {
        Objects.requireNonNull(romPath, "romPath");
        verifyRomIdentity(romPath);
        try (Rom rom = openRom(romPath)) {
            AbstractSmpsData song = Objects.requireNonNull(
                    new Sonic1SmpsLoader(rom).loadMusic(Sonic1Music.GHZ.id),
                    "S1 GHZ music is absent from the verified ROM");
            return inspectGhz(rom, song);
        }
    }

    static SongContract inspectGhz(Rom rom, AbstractSmpsData song) {
        return inspectSong(rom, song, Sonic1Music.GHZ.id);
    }

    /**
     * Derives a song's ROM asset range and reachable loop indices, the two
     * pieces of song identity the state normalizer needs.
     *
     * <p>The range is the song's own pointer-table entry through that entry
     * plus the loaded blob's length, exactly as
     * {@code Sonic1SmpsLoader.calculateMusicDataSize} sized the blob. Every
     * music id is derived the same way, so a per-song window carries no
     * hard-coded address of its own.
     */
    static SongContract inspectSong(Rom rom, AbstractSmpsData song, int musicId) {
        if (musicId < Sonic1Music.ID_BASE || musicId > Sonic1Music.ID_MAX) {
            throw new IllegalArgumentException(
                    "music id is outside the S1 pointer table: 0x" + Integer.toHexString(musicId));
        }
        try {
            int index = musicId - Sonic1Music.ID_BASE;
            long base = Integer.toUnsignedLong(rom.read32BitAddr(
                    Sonic1SmpsConstants.MUSIC_PTR_TABLE_ADDR + index * 4L));
            long end = base + song.getData().length;
            return new SongContract(new S1AudioStateNormalizer.GhzAssetRange(base, end),
                    parseReachableF7LoopIndices(song));
        } catch (IOException error) {
            throw new IllegalArgumentException("cannot derive the ROM asset range for music 0x"
                    + Integer.toHexString(musicId), error);
        }
    }

    static AudioParityMetadata readReferenceMetadata(Path reference) {
        try (BufferedReader input = AudioParityJsonl.openReader(reference)) {
            String line = input.readLine();
            if (line == null) {
                throw new IllegalArgumentException("reference stream has no metadata");
            }
            return AudioParityJsonl.parseMetadata(line);
        } catch (IOException error) {
            throw new IllegalArgumentException("cannot read reference metadata", error);
        }
    }

    static Rom openRom(Path path) {
        Rom rom = new Rom();
        if (!rom.open(path.toString())) {
            throw new IllegalArgumentException("cannot open verified S1 ROM");
        }
        return rom;
    }

    static void verifyRomIdentity(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("S1 ROM does not exist or is not a regular file");
        }
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            CRC32 crc32 = new CRC32();
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                sha1.update(buffer, 0, count);
                crc32.update(buffer, 0, count);
            }
            String actualSha1 = HexFormat.of().formatHex(sha1.digest());
            String actualCrc32 = "%08x".formatted(crc32.getValue());
            if (!AudioParitySchema.S1_REV01_SHA1.equals(actualSha1)
                    || !AudioParitySchema.S1_REV01_CRC32.equals(actualCrc32)) {
                throw new IllegalArgumentException("audio capture requires the pinned S1 World REV01 ROM");
            }
        } catch (IOException | NoSuchAlgorithmException error) {
            throw new IllegalArgumentException("cannot verify S1 ROM identity", error);
        }
    }

    /**
     * Walks reachable S1 track bytecode, following jump/call/return edges and both
     * loop outcomes. This deliberately parses only the loaded song buffer, never a
     * disassembly or a captured fixture.
     */
    private static Set<Integer> parseReachableF7LoopIndices(AbstractSmpsData song) {
        byte[] data = song.getData();
        ArrayDeque<ParseState> pending = new ArrayDeque<>();
        for (int pointer : song.getFmPointers()) {
            pending.add(new ParseState(pointer, new int[0]));
        }
        for (int pointer : song.getPsgPointers()) {
            pending.add(new ParseState(pointer, new int[0]));
        }
        Set<ParseState> visited = new HashSet<>();
        Set<Integer> indices = new LinkedHashSet<>();
        while (!pending.isEmpty()) {
            ParseState state = pending.removeFirst();
            if (state.position < 0 || state.position >= data.length || !visited.add(state)) {
                continue;
            }
            int pos = state.position;
            int command = data[pos] & 0xff;
            if (command < 0xe0) {
                int next = pos + 1;
                if (command >= 0x80 && next < data.length && (data[next] & 0xff) < 0x80) {
                    next++;
                }
                pending.add(new ParseState(next, state.stack));
                continue;
            }
            if (command == 0xf2) {
                continue;
            }
            if (command == 0xe3) {
                if (state.stack.length > 0) {
                    int[] stack = Arrays.copyOf(state.stack, state.stack.length - 1);
                    pending.add(new ParseState(state.stack[state.stack.length - 1], stack));
                }
                continue;
            }
            if (command == 0xf6 && pos + 2 < data.length) {
                pending.add(new ParseState(relativeTarget(data, pos + 1), state.stack));
                continue;
            }
            if (command == 0xf8 && pos + 2 < data.length) {
                int[] stack = Arrays.copyOf(state.stack, state.stack.length + 1);
                stack[stack.length - 1] = pos + 3;
                pending.add(new ParseState(relativeTarget(data, pos + 1), stack));
                continue;
            }
            if (command == 0xf7 && pos + 4 < data.length) {
                indices.add(data[pos + 1] & 0xff);
                pending.add(new ParseState(relativeTarget(data, pos + 3), state.stack));
                pending.add(new ParseState(pos + 5, state.stack));
                continue;
            }
            pending.add(new ParseState(pos + 1 + parameterLength(command), state.stack));
        }
        return Set.copyOf(indices);
    }

    private static int relativeTarget(byte[] data, int pointerOffset) {
        int raw = ((data[pointerOffset] & 0xff) << 8) | (data[pointerOffset + 1] & 0xff);
        return pointerOffset + 1 + (short) raw;
    }

    private static int parameterLength(int command) {
        return switch (command) {
            case 0xe0, 0xe1, 0xe2, 0xe5, 0xe6, 0xe8, 0xe9, 0xea, 0xeb, 0xec,
                    0xef, 0xf3, 0xf5 -> 1;
            case 0xfd -> 2;
            case 0xf0 -> 4;
            default -> 0; // Includes S1's zero-parameter ED and EE commands.
        };
    }

    private record ParseState(int position, int[] stack) {
        private ParseState {
            stack = stack.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ParseState that && position == that.position
                    && Arrays.equals(stack, that.stack);
        }

        @Override
        public int hashCode() {
            return 31 * position + Arrays.hashCode(stack);
        }
    }

    static final class CaptureIterator implements Iterator<AudioParityTick>,
            ChipWriteObserver, AutoCloseable {
        private final OwnedSmpsAudioStream stream;
        private final SmpsDriver driver;
        private final SmpsSequencer sequencer;
        private final SongContract contract;
        private final int terminalCount;
        private final List<AudioParityChipWrite> writes = new ArrayList<>();
        private final List<Double> sampleCounters = new ArrayList<>();
        private int ordinal;
        private long advancedSamples;

        private CaptureIterator(AbstractSmpsData song, DacData dacData, SongContract contract,
                int terminalCount) {
            this.contract = contract;
            this.terminalCount = terminalCount;
            stream = new OwnedSmpsAudioStream(
                    "s1-parity", 0,
                    new SmpsPhysicalDevice.Settings(
                            SAMPLE_RATE, false),
                    LegacyCompatibilitySmpsPhysicalPolicy.INSTANCE,
                    ChipWriteObserver.NONE);
            driver = stream.logicalDriver();
            sequencer = new SmpsSequencer(song, dacData, driver, () -> { },
                    Sonic1SmpsSequencerConfig.CONFIG);
            sequencer.setSampleRate(SAMPLE_RATE);
            driver.addSequencer(sequencer, false);
            // The BizHawk epoch begins at S1 InitMusicPlayback. Chip power-on and
            // Java construction precede it; the shipped FixBugs=0 path then performs
            // this exact music-load silence sequence before the first track update.
            stream.setChipWriteObserver(this);
            initializeS1MusicPlayback(driver, song);
        }

        @Override
        public void close() {
            stream.close();
        }

        static void initializeS1MusicPlayback(SmpsDriver driver, AbstractSmpsData song) {
            // Capture-only setup for a window with no preceding SFX. This is
            // not production song-start validation: Sound_PlayBGM's closing
            // note-offs consult SFX ownership (:912-959), and zero FM count
            // bypasses FM6 disposition (:826). A shared production transition
            // must preserve those branches; do not promote this program as-is.
            for (int channel = 2; channel >= 0; channel--) {
                driver.writeFm(driver, 0, 0x28, channel);
                driver.writeFm(driver, 0, 0x28, channel + 4);
            }
            for (int channel = 0; channel < 3; channel++) {
                for (int operator = 0; operator < 4; operator++) {
                    int register = 0x40 + channel + operator * 4;
                    driver.writeFm(driver, 0, register, 0x7f);
                    driver.writeFm(driver, 1, register, 0x7f);
                }
            }
            driver.writePsg(driver, 0x9f);
            driver.writePsg(driver, 0xbf);
            driver.writePsg(driver, 0xdf);
            driver.writePsg(driver, 0xff);

            int declaredFmDacTracks = song.getFmPointers().length;
            int[] fmOrder = Sonic1SmpsSequencerConfig.FM_CHANNEL_ORDER;
            if (declaredFmDacTracks < fmOrder.length) {
                driver.writeFm(driver, 0, 0x28, 6);
                for (int register : new int[] { 0x42, 0x4a, 0x46, 0x4e }) {
                    driver.writeFm(driver, 1, register, 0x7f);
                }
                driver.writeFm(driver, 1, 0xb6, 0xc0);
            } else {
                driver.writeFm(driver, 0, 0x2b, 0);
            }

            // FixBugs=0 clears undefined track slots instead of assigning their
            // hardware bytes. FMNoteOff therefore aliases any absent FM slot to FM1.
            for (int musicFmSlot = 1; musicFmSlot < fmOrder.length; musicFmSlot++) {
                int channel = musicFmSlot < declaredFmDacTracks ? fmOrder[musicFmSlot] : 0;
                driver.writeFm(driver, 0, 0x28, channel);
            }
            int[] psgOrder = Sonic1SmpsSequencerConfig.PSG_CHANNEL_ORDER;
            int declaredPsgTracks = song.getPsgPointers().length;
            for (int slot = 0; slot < psgOrder.length; slot++) {
                int channel = slot < declaredPsgTracks ? psgOrder[slot] : 0;
                driver.writePsg(driver, channel | 0x1f);
            }
        }

        @Override
        public boolean hasNext() {
            return ordinal < terminalCount;
        }

        @Override
        public AudioParityTick next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            if (ordinal == 0) {
                sequencer.advanceSamples(0);
            } else {
                sequencer.advanceBatch(NTSC_SAMPLES);
                advancedSamples += NTSC_SAMPLES;
            }
            SmpsSequencerSnapshot snapshot = sequencer.captureSnapshot();
            sampleCounters.add(snapshot.sampleCounter());
            S1AudioStateNormalizer.NormalizedState state = S1AudioStateNormalizer.normalize(
                    snapshot, contract.assetRange(), contract.f7LoopIndices());
            AudioParityTick tick = new AudioParityTick(ordinal, state.global(), state.tracks(),
                    List.copyOf(writes));
            writes.clear();
            ordinal++;
            return tick;
        }

        @Override
        public void onYm2612Write(int port, int register, int value) {
            writes.add(AudioParityChipWrite.ym2612(port, register, value));
        }

        @Override
        public void onPsgWrite(int value) {
            writes.add(AudioParityChipWrite.psg(value));
        }

        private CaptureResult result() {
            if (ordinal != terminalCount) {
                throw new IllegalStateException("capture iterator did not reach the reference terminal count");
            }
            return new CaptureResult(ordinal, advancedSamples, sampleCounters, contract);
        }
    }
}
