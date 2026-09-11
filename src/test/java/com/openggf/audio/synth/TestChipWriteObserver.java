package com.openggf.audio.synth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.synth.nuked.NukedOpn2;
import com.openggf.audio.synth.nuked.NukedOpn2State;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestChipWriteObserver {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void directChipWritesReportResolvedUnsignedValuesExactlyOnce() {
        RecordingObserver observer = new RecordingObserver();
        Ym2612Chip ym = new Ym2612Chip();
        PsgChip psg = new PsgChip();
        ym.setWriteObserver(observer);
        psg.setWriteObserver(observer);

        ym.write(0, 0x1B4, 0x1C7);
        psg.write(0x19F);

        assertEquals(List.of("YM:1:B4:C7", "PSG:9F"), observer.events);
    }

    @Test
    void optedInPhysicalYmCaptureReportsPacedAddressAndDataStrobes() {
        PhysicalRecordingObserver observer = new PhysicalRecordingObserver();
        Ym2612Chip ym = new Ym2612Chip();
        ym.setWriteObserver(observer);
        observer.liveCoreSupplier = () -> ym.captureSnapshot().core();

        ym.write(0, 0x22, 0x08);
        ym.write(1, 0xB4, 0xC0);

        assertEquals(List.of("YM:0:22:08", "YM:1:B4:C0"), observer.events,
                "legacy observation remains at the queued logical boundary");
        assertEquals(List.of(), observer.physical,
                "queued writes are not yet physical bus strobes");

        ym.readStatus();

        assertEquals(List.of(
                "0:0:22:EXTERNAL_BUS", "1:1:08:EXTERNAL_BUS",
                "35:2:B4:EXTERNAL_BUS", "36:3:C0:EXTERNAL_BUS"),
                observer.physical,
                "raw port 0/2 address and port 1/3 data strobes retain bus pacing");
    }

    @Test
    void resetOriginRawYmCaptureReplaysIntoFreshNukedCore() {
        PhysicalRecordingObserver observer = new PhysicalRecordingObserver();
        Ym2612Chip ym = new Ym2612Chip();
        ym.setWriteObserver(observer);
        observer.liveCoreSupplier = () -> ym.captureSnapshot().core();
        ym.reset();
        assertEquals(List.of("YM2612_INTERNAL_CYCLE:0:RESET"),
                observer.boundaries);
        observer.boundaries.clear();
        observer.boundaryEvents.clear();

        ym.write(0, 0x22, 0x08);
        ym.write(1, 0xB4, 0xC0);
        ym.readStatus();

        NukedOpn2 replayed = replayKnownInitialSegment(
                InitialYmConfig.CONSTRUCTOR_RESET_YM2612,
                observer.ymBusEvents, observer.boundaryEvents);
        assertEquals(observer.liveCoreAtLastStrobe, replayed.state(),
                "raw port/cycle capture must reproduce live state at its strobe boundary");
    }

    @Test
    void optedInPhysicalPsgCaptureUsesNativeGeneratorTicks() {
        PhysicalRecordingObserver observer = new PhysicalRecordingObserver();
        PsgChip psg = new PsgChip(PsgChip.TICK_RATE_HZ,
                PsgChip.ChipType.INTEGRATED);
        psg.setWriteObserver(observer);

        psg.renderStereo(new int[1], new int[1], 1);
        psg.write(0x90);

        assertEquals(List.of("PSG:90"), observer.events);
        assertEquals(List.of("1:90"), observer.psgPhysical);
    }

    @Test
    void nonBusChipConfigurationAndAdmissionRestoresMarkBoundaries() {
        PhysicalRecordingObserver observer = new PhysicalRecordingObserver();
        Ym2612Chip ym = new Ym2612Chip();
        PsgChip psg = new PsgChip();
        ym.setWriteObserver(observer);
        psg.setWriteObserver(observer);

        ym.setOutputSampleRate(48_000);
        ym.setChipType(1);
        ym.setDacInterpolate(true);
        psg.setSampleRate(48_000);
        psg.setChipType(PsgChip.ChipType.DISCRETE);
        psg.configure(75, 0xF0);
        PsgChip.SfxAdmissionState admission = psg.captureSfxAdmissionState(1);
        psg.write(0x9F);
        psg.restoreSfxAdmissionState(admission);

        assertEquals(7, observer.boundaries.stream().filter(
                boundary -> boundary.endsWith(":MODEL_MUTATION")).count());
    }

    @Test
    void rawReplayRejectsUnknownConfigurationAndDiscontinuousSegments() {
        List<BusEvent> valid = List.of(new BusEvent(0, 0, 0x22,
                ChipWriteObserver.PhysicalWriteOrigin.EXTERNAL_BUS));
        assertThrows(IllegalArgumentException.class,
                () -> replayKnownInitialSegment(InitialYmConfig.UNKNOWN,
                        valid, List.of()));
        for (ChipWriteObserver.PhysicalTimelineBoundary boundary : List.of(
                ChipWriteObserver.PhysicalTimelineBoundary.MODEL_MUTATION,
                ChipWriteObserver.PhysicalTimelineBoundary.SNAPSHOT_RESTORE,
                ChipWriteObserver.PhysicalTimelineBoundary.TRANSACTION_ROLLBACK,
                ChipWriteObserver.PhysicalTimelineBoundary.RESET)) {
            assertThrows(IllegalArgumentException.class,
                    () -> replayKnownInitialSegment(
                            InitialYmConfig.CONSTRUCTOR_RESET_YM2612, valid,
                            List.of(boundary)));
        }
    }

    @Test
    void rawReplayRejectsUnknownOriginAndNonMonotonicStrobes() {
        assertThrows(IllegalArgumentException.class,
                () -> replayKnownInitialSegment(
                        InitialYmConfig.CONSTRUCTOR_RESET_YM2612,
                        List.of(new BusEvent(0, 0, 0x2A,
                                ChipWriteObserver.PhysicalWriteOrigin.RESTORED_UNKNOWN)),
                        List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> replayKnownInitialSegment(
                        InitialYmConfig.CONSTRUCTOR_RESET_YM2612,
                        List.of(
                                new BusEvent(2, 0, 0x22,
                                        ChipWriteObserver.PhysicalWriteOrigin.EXTERNAL_BUS),
                                new BusEvent(1, 1, 0x08,
                                        ChipWriteObserver.PhysicalWriteOrigin.EXTERNAL_BUS)),
                        List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> replayKnownInitialSegment(
                        InitialYmConfig.CONSTRUCTOR_RESET_YM2612,
                        List.of(new BusEvent(0, 4, 0x22,
                                ChipWriteObserver.PhysicalWriteOrigin.EXTERNAL_BUS)),
                        List.of()));
    }

    @Test
    void physicalDacCaptureIncludesStreamAndSyntheticOriginsWithoutLegacyDuplicates() {
        PhysicalRecordingObserver observer = new PhysicalRecordingObserver();
        Ym2612Chip ym = new Ym2612Chip();
        ym.setWriteObserver(observer);
        ym.setOutputSampleRate(Ym2612Chip.getInternalRate());
        ym.setDacData(new DacData(
                Map.of(1, new byte[] {0, 127, 0, 127}),
                Map.of(0x81, new DacData.DacEntry(1, 8)), 301));
        ym.setDacInterpolate(true);
        ym.playDac(0x81);

        ym.renderStereo(new int[24], new int[24], 24);

        long streamStrobes = observer.physical.stream()
                .filter(event -> event.endsWith(":DAC_STREAM")).count();
        long interpolationStrobes = observer.physical.stream()
                .filter(event -> event.endsWith(":DAC_INTERPOLATION")).count();
        long legacyDacWrites = observer.events.stream()
                .filter(event -> event.startsWith("YM:0:2A:")).count();
        assertTrue(streamStrobes >= 2,
                "a real DAC byte must retain its address and data strobes");
        assertTrue(interpolationStrobes >= 2,
                "synthetic interpolation must carry a distinct provenance");
        assertConsecutiveDacAddressAndData(observer.physical, "DAC_STREAM");
        assertConsecutiveDacAddressAndData(observer.physical,
                "DAC_INTERPOLATION");
        assertEquals(streamStrobes / 2, legacyDacWrites,
                "legacy observers see actual DAC bytes, never synthetic writes");
    }

    @Test
    void removingSynthObserverDisablesBothChipStreams() {
        RecordingObserver observer = new RecordingObserver();
        VirtualSynthesizer synth = new VirtualSynthesizer();
        synth.setChipWriteObserver(observer);
        synth.writeFm(this, 0, 0x22, 0x08);
        synth.writePsg(this, 0x90);
        assertEquals(List.of("YM:0:22:08", "PSG:90"), observer.events);

        observer.events.clear();
        synth.setChipWriteObserver(null);
        synth.writeFm(this, 1, 0xA4, 0x21);
        synth.writePsg(this, 0xBF);

        assertEquals(List.of(), observer.events);
    }

    @Test
    void setInstrumentReportsExistingKeyOffB0AndOperatorExpansionOrder() {
        RecordingObserver observer = new RecordingObserver();
        Ym2612Chip ym = new Ym2612Chip();
        ym.setWriteObserver(observer);

        ym.setInstrument(4, new byte[] {
                0x2D,
                0x01, 0x02, 0x03, 0x04,
                0x05, 0x06, 0x07, 0x08,
                0x09, 0x0A, 0x0B, 0x0C,
                0x0D, 0x0E, 0x0F, 0x10,
                0x11, 0x12, 0x13, 0x14,
                0x15, 0x16, 0x17, 0x18
        });

        assertEquals(List.of(
                "YM:0:28:05", "YM:1:B1:2D",
                "YM:1:31:01", "YM:1:41:15", "YM:1:51:05", "YM:1:61:09", "YM:1:71:0D", "YM:1:81:11", "YM:1:91:00",
                "YM:1:35:03", "YM:1:45:17", "YM:1:55:07", "YM:1:65:0B", "YM:1:75:0F", "YM:1:85:13", "YM:1:95:00",
                "YM:1:39:02", "YM:1:49:16", "YM:1:59:06", "YM:1:69:0A", "YM:1:79:0E", "YM:1:89:12", "YM:1:99:00",
                "YM:1:3D:04", "YM:1:4D:18", "YM:1:5D:08", "YM:1:6D:0C", "YM:1:7D:10", "YM:1:8D:14", "YM:1:9D:00"
        ), observer.events);
    }

    @Test
    void silenceAllReportsEveryYmAndPsgWriteInProductionOrder() {
        RecordingObserver observer = new RecordingObserver();
        VirtualSynthesizer synth = new VirtualSynthesizer();
        synth.setChipWriteObserver(observer);

        synth.silenceAll();

        assertEquals(expectedSilenceWrites(), observer.events);
    }

    @Test
    void constructorObserverSeesTheCompleteInitialSilenceInExactOrder() {
        RecordingObserver observer = new RecordingObserver();

        new VirtualSynthesizer(
                Ym2612Chip.getDefaultOutputRate(), observer);

        assertEquals(202, observer.events.size());
        assertEquals(198, observer.events.stream()
                .filter(event -> event.startsWith("YM:")).count());
        assertEquals(4, observer.events.stream()
                .filter(event -> event.startsWith("PSG:")).count());
        assertEquals(expectedSilenceWrites(), observer.events);
    }

    @Test
    void observationLeavesSnapshotsAndFutureOutputBitExact() {
        VirtualSynthesizer unobserved = new VirtualSynthesizer();
        VirtualSynthesizer observed = new VirtualSynthesizer();
        observed.setChipWriteObserver(new PhysicalRecordingObserver());

        exercise(unobserved);
        exercise(observed);

        assertEquals(
                JSON.valueToTree(unobserved.captureSynthSnapshot()),
                JSON.valueToTree(observed.captureSynthSnapshot()));

        short[] expected = new short[256];
        short[] actual = new short[256];
        unobserved.render(expected);
        observed.render(actual);
        assertArrayEquals(expected, actual);
    }

    private static void exercise(VirtualSynthesizer synth) {
        synth.writeFm(synth, 0, 0x22, 0x0B);
        synth.writeFm(synth, 1, 0x1B4, 0xC7);
        synth.writePsg(synth, 0x84);
        synth.writePsg(synth, 0x12);
        synth.writePsg(synth, 0x92);
    }

    private static List<String> expectedSilenceWrites() {
        List<String> expected = new ArrayList<>();
        expected.addAll(List.of(
                "YM:0:28:00", "YM:0:28:04",
                "YM:0:28:01", "YM:0:28:05",
                "YM:0:28:02", "YM:0:28:06"));
        for (int register = 0x30; register < 0x90; register++) {
            expected.add("YM:0:%02X:FF".formatted(register));
            expected.add("YM:1:%02X:FF".formatted(register));
        }
        expected.addAll(List.of("PSG:9F", "PSG:BF", "PSG:DF", "PSG:FF"));
        return expected;
    }

    private static NukedOpn2 replayKnownInitialSegment(
            InitialYmConfig initial, List<BusEvent> events,
            List<ChipWriteObserver.PhysicalTimelineBoundary> boundaries) {
        if (initial != InitialYmConfig.CONSTRUCTOR_RESET_YM2612) {
            throw new IllegalArgumentException("YM initial configuration is unknown");
        }
        if (!boundaries.isEmpty()) {
            throw new IllegalArgumentException("raw replay crosses a non-bus boundary");
        }
        long previousCycle = -1;
        for (BusEvent event : events) {
            if (event.origin() == ChipWriteObserver.PhysicalWriteOrigin.RESTORED_UNKNOWN) {
                throw new IllegalArgumentException("raw replay has unknown DAC provenance");
            }
            if (event.busPort() < 0 || event.busPort() > 3) {
                throw new IllegalArgumentException("raw replay has an invalid YM bus port");
            }
            if (event.cycle() < previousCycle) {
                throw new IllegalArgumentException("raw replay clocks are not monotonic");
            }
            previousCycle = event.cycle();
        }
        return replay(events);
    }

    private static NukedOpn2 replay(List<BusEvent> events) {
        NukedOpn2 core = new NukedOpn2();
        core.setChipType(NukedOpn2.MODE_YM2612 | NukedOpn2.MODE_READMODE);
        int[] output = new int[2];
        long cycle = 0;
        for (BusEvent event : events) {
            while (cycle < event.cycle) {
                core.clock(output);
                cycle++;
            }
            core.write(event.busPort, event.value);
        }
        return core;
    }

    private static void assertConsecutiveDacAddressAndData(
            List<String> physical, String origin) {
        for (int index = 0; index + 1 < physical.size(); index++) {
            String[] address = physical.get(index).split(":");
            if (!address[1].equals("0") || !address[2].equals("2A")
                    || !address[3].equals(origin)) {
                continue;
            }
            String[] data = physical.get(index + 1).split(":");
            assertEquals(Long.parseLong(address[0]) + 1,
                    Long.parseLong(data[0]));
            assertEquals("1", data[1]);
            assertEquals(origin, data[3]);
            return;
        }
        throw new AssertionError("no DAC address/data pair for " + origin);
    }

    private record BusEvent(long cycle, int busPort, int value,
            ChipWriteObserver.PhysicalWriteOrigin origin) {
    }

    private enum InitialYmConfig {
        CONSTRUCTOR_RESET_YM2612,
        UNKNOWN
    }

    private static class RecordingObserver implements ChipWriteObserver {
        protected final List<String> events = new ArrayList<>();

        @Override
        public void onYm2612Write(int port, int register, int value) {
            events.add("YM:%d:%02X:%02X".formatted(port, register, value));
        }

        @Override
        public void onPsgWrite(int value) {
            events.add("PSG:%02X".formatted(value));
        }
    }

    private static final class PhysicalRecordingObserver extends RecordingObserver {
        private final List<String> physical = new ArrayList<>();
        private final List<String> psgPhysical = new ArrayList<>();
        private final List<BusEvent> ymBusEvents = new ArrayList<>();
        private final List<String> boundaries = new ArrayList<>();
        private final List<PhysicalTimelineBoundary> boundaryEvents = new ArrayList<>();
        private java.util.function.Supplier<NukedOpn2State> liveCoreSupplier;
        private NukedOpn2State liveCoreAtLastStrobe;

        @Override
        public boolean observesPhysicalWrites() {
            return true;
        }

        @Override
        public void onYm2612BusWrite(long cycle, int busPort, int value,
                ChipWriteObserver.PhysicalWriteOrigin origin) {
            physical.add("%d:%d:%02X:%s".formatted(cycle, busPort, value, origin));
            ymBusEvents.add(new BusEvent(cycle, busPort, value, origin));
            if (liveCoreSupplier != null) {
                liveCoreAtLastStrobe = liveCoreSupplier.get();
            }
        }

        @Override
        public void onPsgBusWrite(long tick, int value) {
            psgPhysical.add("%d:%02X".formatted(tick, value));
        }

        @Override
        public void onPhysicalTimelineBoundary(ChipClockDomain domain,
                long clock, PhysicalTimelineBoundary boundary) {
            boundaries.add("%s:%d:%s".formatted(domain, clock, boundary));
            boundaryEvents.add(boundary);
        }
    }
}
