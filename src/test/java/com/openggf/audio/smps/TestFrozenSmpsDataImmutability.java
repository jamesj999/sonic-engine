package com.openggf.audio.smps;

import com.openggf.audio.AudioManager;
import com.openggf.audio.presentation.AudioPresentationCommand;
import com.openggf.audio.presentation.AudioPresentationSourceFactory;
import com.openggf.audio.presentation.DecodedPcmCache;
import com.openggf.audio.rewind.AudioSourceDescriptor;
import com.openggf.audio.session.SmpsSessionTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestFrozenSmpsDataImmutability {
    private static final DacData EMPTY_DAC =
            new DacData(Map.of(), Map.of(), 288);

    @AfterEach
    void tearDown() {
        AudioManager.getInstance().resetState();
    }

    @Test
    void frozenProgramOwnsLoaderArraysAndDefendsEveryPublicArrayAccessor() {
        MutableSmpsData source = MutableSmpsData.complete();
        AudioPresentationSourceFactory factory = factory();
        AudioPresentationCommand.MusicVoiceEntry entry = factory.musicSmps(
                "base", 0x91, 1, source, EMPTY_DAC,
                new SmpsSequencerConfig.Builder().build(),
                AudioSourceDescriptor.baseMusic(0x91), 32);

        source.mutateOwnedInputs();
        AbstractSmpsData frozen = program(factory, entry);

        mutatePublicCopies(frozen);
        assertOriginalValues(frozen);
        assertThrows(UnsupportedOperationException.class,
                () -> frozen.setId(0x44));
        assertThrows(UnsupportedOperationException.class,
                () -> frozen.setPalSpeedupDisabled(false));
        assertEquals(0x91, frozen.getId());
        assertTrue(frozen.isPalSpeedupDisabled());
    }

    @Test
    void legacyIndexedReadsAllocateEachVoiceAndEnvelopeOnlyOnce() {
        CountingAllocatingSmpsData source =
                CountingAllocatingSmpsData.withTracks();

        SmpsSequencer sequencer = new SmpsSequencer(
                source, EMPTY_DAC, AudioManager.getInstance(),
                indexedConfig());

        assertEquals(1, source.voiceReads);
        assertEquals(1, source.psgEnvelopeReads);
        assertEquals(1, source.modEnvelopeReads);
        assertEquals(3, source.allocations);
        assertEquals(2, sequencer.getTracks().size());
        SmpsSequencer.Track fm = sequencer.getTracks().get(0);
        SmpsSequencer.Track psg = sequencer.getTracks().get(1);
        assertEquals(1, fm.pos);
        assertEquals(11, fm.keyOffset);
        assertEquals(12, fm.volumeOffset);
        assertArrayEquals(CountingAllocatingSmpsData.VOICE, fm.voiceData);
        assertEquals(8, psg.pos);
        assertEquals(13, psg.keyOffset);
        assertEquals(14, psg.volumeOffset);
        assertArrayEquals(CountingAllocatingSmpsData.PSG_ENVELOPE,
                psg.envData);
        assertArrayEquals(CountingAllocatingSmpsData.MOD_ENVELOPE,
                psg.modEnvData);
        assertSame(source.lastVoice, fm.voiceData);
        assertSame(source.lastPsgEnvelope, psg.envData);
        assertSame(source.lastModEnvelope, psg.modEnvData);
        assertEquals(0xF2,
                sequencer.programView().dataByteAt(fm.pos) & 0xFF);
    }

    @Test
    void fallbackVoiceUsesOneBoundedReadFromEachLegacyProgram() {
        CountingAllocatingSmpsData primary =
                CountingAllocatingSmpsData.withoutVoice();
        CountingAllocatingSmpsData fallback =
                CountingAllocatingSmpsData.withFallbackVoice();
        SmpsSequencer sequencer = new SmpsSequencer(
                primary, EMPTY_DAC, AudioManager.getInstance(),
                new SmpsSequencerConfig.Builder().build());
        sequencer.setFallbackVoiceData(fallback);
        SmpsSequencer.Track track = new SmpsSequencer.Track(
                0, SmpsSequencer.TrackType.FM, 0);

        sequencer.loadVoice(track, 7);

        assertEquals(1, primary.voiceReads);
        assertEquals(1, fallback.voiceReads);
        assertEquals(1, fallback.allocations);
        assertArrayEquals(CountingAllocatingSmpsData.VOICE,
                track.voiceData);
        assertSame(fallback.lastVoice, track.voiceData);
    }

    @Test
    void coordFlagContextExposesOnlyTheScalarIndexedProgramView()
            throws Exception {
        Class<?> view = assertDoesNotThrow(() -> Class.forName(
                "com.openggf.audio.smps.SmpsProgramView"));
        Set<String> expectedMethods = Set.of(
                "dataLength", "dataByteAt",
                "fmPointerCount", "fmPointerAt", "fmKeyOffsetAt",
                "fmVolumeOffsetAt", "psgPointerCount", "psgPointerAt",
                "psgKeyOffsetAt", "psgVolumeOffsetAt",
                "psgModEnvelopeAt", "psgInstrumentCount",
                "psgInstrumentAt",
                "voiceLength", "voiceByteAt",
                "psgEnvelopeLength", "psgEnvelopeByteAt",
                "modEnvelopeLength", "modEnvelopeByteAt");
        Set<String> actualMethods = Set.of(view.getDeclaredMethods()).stream()
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertEquals(expectedMethods, actualMethods);
        for (Method method : view.getDeclaredMethods()) {
            assertFalse(method.getReturnType().isArray(), method::toString);
            assertFalse(Collection.class.isAssignableFrom(
                    method.getReturnType()), method::toString);
        }
        assertEquals(view,
                CoordFlagContext.class.getMethod("programView")
                        .getReturnType());
        for (Method method : CoordFlagContext.class.getMethods()) {
            assertFalse(method.getReturnType().isArray(), method::toString);
        }
    }

    private static AudioPresentationSourceFactory factory() {
        return new AudioPresentationSourceFactory(
                () -> true,
                new SmpsCoordFlagHandlerOwner(
                        new SmpsCoordFlagRuntimeState()),
                new AudioPresentationSourceFactory.Settings(
                        48_000, SmpsSequencer.Region.NTSC,
                        false, false, false, 1,
                        AudioManager.getInstance(),
                        new DecodedPcmCache(), ignored -> null),
                SmpsSessionTestSupport.installed(48_000));
    }

    private static SmpsSequencerConfig indexedConfig() {
        return new SmpsSequencerConfig.Builder()
                .fmChannelOrder(new int[] {0})
                .psgChannelOrder(new int[] {0x80})
                .build();
    }

    private static AbstractSmpsData program(
            AudioPresentationSourceFactory factory,
            AudioPresentationCommand.MusicVoiceEntry entry) {
        return ((AudioPresentationCommand.SmpsVoiceDescriptor)
                entry.voiceDescriptor()).activation().incomingMusic().smpsData();
    }

    private static void mutatePublicCopies(AbstractSmpsData frozen) {
        frozen.getData()[0] = 0x55;
        frozen.getFmPointers()[0] = 0x55;
        frozen.getFmKeyOffsets()[0] = 0x55;
        frozen.getFmVolumeOffsets()[0] = 0x55;
        frozen.getPsgPointers()[0] = 0x55;
        frozen.getPsgKeyOffsets()[0] = 0x55;
        frozen.getPsgVolumeOffsets()[0] = 0x55;
        frozen.getPsgModEnvs()[0] = 0x55;
        frozen.getPsgInstruments()[0] = 0x55;
        frozen.getVoice(7)[0] = 0x55;
        frozen.getPsgEnvelope(8)[0] = 0x55;
        frozen.getModEnvelope(9)[0] = 0x55;
    }

    private static void assertOriginalValues(AbstractSmpsData frozen) {
        assertArrayEquals(MutableSmpsData.PROGRAM, frozen.getData());
        assertArrayEquals(new int[] {1}, frozen.getFmPointers());
        assertArrayEquals(new int[] {11}, frozen.getFmKeyOffsets());
        assertArrayEquals(new int[] {12}, frozen.getFmVolumeOffsets());
        assertArrayEquals(new int[] {8}, frozen.getPsgPointers());
        assertArrayEquals(new int[] {13}, frozen.getPsgKeyOffsets());
        assertArrayEquals(new int[] {14}, frozen.getPsgVolumeOffsets());
        assertArrayEquals(new int[] {15}, frozen.getPsgModEnvs());
        assertArrayEquals(new int[] {16}, frozen.getPsgInstruments());
        assertArrayEquals(new byte[] {17, 18}, frozen.getVoice(7));
        assertArrayEquals(new byte[] {19, 20}, frozen.getPsgEnvelope(8));
        assertArrayEquals(new byte[] {21, 22}, frozen.getModEnvelope(9));

        SmpsProgramView view = frozen;
        assertEquals(MutableSmpsData.PROGRAM.length, view.dataLength());
        assertEquals(0xF2, view.dataByteAt(1) & 0xFF);
        assertEquals(1, view.fmPointerCount());
        assertEquals(1, view.fmPointerAt(0));
        assertEquals(11, view.fmKeyOffsetAt(0));
        assertEquals(12, view.fmVolumeOffsetAt(0));
        assertEquals(1, view.psgPointerCount());
        assertEquals(8, view.psgPointerAt(0));
        assertEquals(13, view.psgKeyOffsetAt(0));
        assertEquals(14, view.psgVolumeOffsetAt(0));
        assertEquals(15, view.psgModEnvelopeAt(0));
        assertEquals(16, view.psgInstrumentAt(0));
        assertEquals(2, view.voiceLength(7));
        assertEquals(17, view.voiceByteAt(7, 0));
        assertEquals(2, view.psgEnvelopeLength(8));
        assertEquals(19, view.psgEnvelopeByteAt(8, 0));
        assertEquals(2, view.modEnvelopeLength(9));
        assertEquals(21, view.modEnvelopeByteAt(9, 0));
    }

    private static final class MutableSmpsData extends AbstractSmpsData {
        private static final byte[] PROGRAM = {
                1, (byte) 0xF2, 0, 0, 0, 0, 0, 0,
                (byte) 0xF2, 0, 0, 0, 0, 0, 0, 0
        };
        private final byte[] voice;
        private final byte[] psgEnvelope;
        private final byte[] modEnvelope;

        private MutableSmpsData(
                byte[] data,
                int[] fmPointers,
                int[] fmKeyOffsets,
                int[] fmVolumeOffsets,
                int[] psgPointers,
                int[] psgKeyOffsets,
                int[] psgVolumeOffsets,
                int[] psgModEnvs,
                int[] psgInstruments,
                byte[] voice,
                byte[] psgEnvelope,
                byte[] modEnvelope) {
            super(data, 0);
            this.fmPointers = fmPointers;
            this.fmKeyOffsets = fmKeyOffsets;
            this.fmVolumeOffsets = fmVolumeOffsets;
            this.psgPointers = psgPointers;
            this.psgKeyOffsets = psgKeyOffsets;
            this.psgVolumeOffsets = psgVolumeOffsets;
            this.psgModEnvs = psgModEnvs;
            this.psgInstruments = psgInstruments;
            this.voice = voice;
            this.psgEnvelope = psgEnvelope;
            this.modEnvelope = modEnvelope;
            setId(0x91);
            setPalSpeedupDisabled(true);
        }

        static MutableSmpsData complete() {
            return new MutableSmpsData(
                    PROGRAM.clone(),
                    new int[] {1}, new int[] {11}, new int[] {12},
                    new int[] {8}, new int[] {13}, new int[] {14},
                    new int[] {15}, new int[] {16},
                    new byte[] {17, 18}, new byte[] {19, 20},
                    new byte[] {21, 22});
        }

        void mutateOwnedInputs() {
            data[0] = 0x66;
            fmPointers[0] = 0x66;
            fmKeyOffsets[0] = 0x66;
            fmVolumeOffsets[0] = 0x66;
            psgPointers[0] = 0x66;
            psgKeyOffsets[0] = 0x66;
            psgVolumeOffsets[0] = 0x66;
            psgModEnvs[0] = 0x66;
            psgInstruments[0] = 0x66;
            voice[0] = 0x66;
            psgEnvelope[0] = 0x66;
            modEnvelope[0] = 0x66;
            setId(0x66);
            setPalSpeedupDisabled(false);
        }

        @Override
        protected void parseHeader() {
        }

        @Override
        public byte[] getVoice(int voiceId) {
            return voiceId == 7 ? voice : null;
        }

        @Override
        public byte[] getPsgEnvelope(int id) {
            return id == 8 ? psgEnvelope : null;
        }

        @Override
        public byte[] getModEnvelope(int id) {
            return id == 9 ? modEnvelope : null;
        }

        @Override
        public int read16(int offset) {
            return ((data[offset] & 0xFF) << 8)
                    | (data[offset + 1] & 0xFF);
        }

        @Override
        public int getBaseNoteOffset() {
            return 0;
        }
    }

    private static final class CountingAllocatingSmpsData
            extends AbstractSmpsData {
        private static final byte[] VOICE = {
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
                11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
                21, 22, 23, 24, 25
        };
        private static final byte[] PSG_ENVELOPE = {3, 4, (byte) 0x81};
        private static final byte[] MOD_ENVELOPE = {5, 6, (byte) 0x81};

        private final int voiceId;
        private int voiceReads;
        private int psgEnvelopeReads;
        private int modEnvelopeReads;
        private int allocations;
        private byte[] lastVoice;
        private byte[] lastPsgEnvelope;
        private byte[] lastModEnvelope;

        private CountingAllocatingSmpsData(
                int voiceId, boolean tracks) {
            super(new byte[] {
                    0, (byte) 0xF2, 0, 0, 0, 0, 0, 0,
                    (byte) 0xF2, 0, 0, 0, 0, 0, 0, 0
            }, 0);
            this.voiceId = voiceId;
            if (tracks) {
                fmPointers = new int[] {1};
                fmKeyOffsets = new int[] {11};
                fmVolumeOffsets = new int[] {12};
                psgPointers = new int[] {8};
                psgKeyOffsets = new int[] {13};
                psgVolumeOffsets = new int[] {14};
                psgModEnvs = new int[] {9};
                psgInstruments = new int[] {8};
            }
        }

        static CountingAllocatingSmpsData withTracks() {
            return new CountingAllocatingSmpsData(0, true);
        }

        static CountingAllocatingSmpsData withoutVoice() {
            return new CountingAllocatingSmpsData(-1, false);
        }

        static CountingAllocatingSmpsData withFallbackVoice() {
            return new CountingAllocatingSmpsData(7, false);
        }

        @Override protected void parseHeader() { }

        @Override
        public byte[] getVoice(int requestedVoiceId) {
            voiceReads++;
            if (requestedVoiceId != voiceId) {
                return null;
            }
            allocations++;
            lastVoice = VOICE.clone();
            return lastVoice;
        }

        @Override
        public byte[] getPsgEnvelope(int id) {
            psgEnvelopeReads++;
            if (id != 8) {
                return null;
            }
            allocations++;
            lastPsgEnvelope = PSG_ENVELOPE.clone();
            return lastPsgEnvelope;
        }

        @Override
        public byte[] getModEnvelope(int id) {
            modEnvelopeReads++;
            if (id != 9) {
                return null;
            }
            allocations++;
            lastModEnvelope = MOD_ENVELOPE.clone();
            return lastModEnvelope;
        }

        @Override public int read16(int offset) { return 0; }
        @Override public int getBaseNoteOffset() { return 0; }
    }
}
