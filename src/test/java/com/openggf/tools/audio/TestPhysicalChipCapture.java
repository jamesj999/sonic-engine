package com.openggf.tools.audio;

import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.audio.synth.VirtualSynthesizer;
import com.openggf.audio.synth.Ym2612Chip;
import com.openggf.audio.synth.nuked.NukedOpn2;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openggf.version.BuildIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestPhysicalChipCapture {
    @TempDir
    Path directory;

    @Test
    void accurateOracleCaptureRejectsFastSnapshots() {
        PhysicalChipCapture capture = new PhysicalChipCapture(100);
        var fast = new com.openggf.audio.synth.FastYm2612Chip(
                new com.openggf.audio.synth.fast.FastYm2612Dsp());
        assertThrows(IllegalStateException.class,
                () -> capture.beginYmReplaySegment(fast.captureSnapshot()));
    }

    @Test
    void boundedCapturePreservesNativeDomainsAndReportsOverflowWithoutThrowing() throws Exception {
        PhysicalChipCapture capture = new PhysicalChipCapture(2);

        capture.onYm2612BusWrite(7, 0, 0x22,
                ChipWriteObserver.PhysicalWriteOrigin.EXTERNAL_BUS);
        capture.onPsgBusWrite(11, 0x90);
        capture.onPhysicalTimelineBoundary(
                ChipWriteObserver.ChipClockDomain.YM2612_INTERNAL_CYCLE, 8,
                ChipWriteObserver.PhysicalTimelineBoundary.MODEL_MUTATION);

        assertEquals(2, capture.size());
        assertTrue(capture.overflowed());
        assertEquals(1, capture.dropped());
        Path output = directory.resolve("capture.txt");
        capture.write(output, "s1", "sfx", 0xA0, 44_100,
                "/rom/s1.gen", "012345", new BuildIdentity("test", "abc", false));
        String text = Files.readString(output);
        assertTrue(text.contains("\"ym_ticks_per_second\":"));
        assertTrue(text.contains("\"rom_sha1\":\"012345\""));
        assertTrue(text.contains("\"ym_core_mode\":3"));
        assertTrue(text.contains("\"rendered_output_frames\":null"),
                "an unfinished diagnostic must not imply a replay duration");
        assertTrue(text.contains("\"terminal_ym_cycle\":null"),
                "a capture without an observed endpoint is not replayable");
        assertEquals(3, text.lines().count(),
                "JSONL has one header and one line per retained event");
        assertTrue(text.contains("\"psg_ticks_per_second\":"));
        assertTrue(text.contains("\"overflow\":true,\"dropped\":1"));
        assertTrue(text.contains("\"type\":\"ym\",\"ordinal\":0,\"cycle\":7"));
        assertTrue(text.contains("\"type\":\"psg\",\"ordinal\":1,\"tick\":11"));
    }

    @Test
    void exportPropagatesWriterFailures() {
        PhysicalChipCapture capture = new PhysicalChipCapture(1);

        assertThrows(java.io.IOException.class, () -> capture.write(directory,
                "s1", "sfx", 0xA0, 44_100, "/rom/s1.gen", "012345",
                new BuildIdentity("test", "abc", false)));
    }

    @Test
    void exportEscapesControlCharactersInProvenance() throws Exception {
        PhysicalChipCapture capture = new PhysicalChipCapture(1);
        Path output = directory.resolve("escaped.jsonl");

        capture.write(output, "s1", "sfx", 0xA0, 44_100,
                "/rom/line\n\t.gen", "012345",
                new BuildIdentity("test", "abc", false));

        String header = Files.readAllLines(output).getFirst();
        assertTrue(header.contains("/rom/line\\n\\t.gen"));
    }

    @Test
    void nativeEndpointIncludesQueuedFramesAndReproducesLiveCore() throws Exception {
        PhysicalChipCapture capture = new PhysicalChipCapture(100);
        VirtualSynthesizer synth = new VirtualSynthesizer(Ym2612Chip.getInternalRate(),
                capture, VirtualSynthesizer.Initialization.DEFERRED);
        capture.beginYmReplaySegment(synth.captureSynthSnapshot().ym());
        synth.writeFm(this, 0, 0x22, 8);
        synth.writeFm(this, 0, 0xB4, 0xC0);
        synth.writeFm(this, 0, 0xA4, 0x22);
        synth.writeFm(this, 0, 0xA0, 0x69);
        synth.render(new short[2]);
        Ym2612Chip.Snapshot live = (Ym2612Chip.Snapshot) synth.captureSynthSnapshot().ym();
        capture.finish(1, live);

        Path output = directory.resolve("endpoint.jsonl");
        capture.write(output, "s3k", "music", 1, Ym2612Chip.getInternalRate(),
                "/rom/s3k.gen", "012345", new BuildIdentity("test", "abc", false));
        ObjectMapper json = new ObjectMapper();
        var lines = Files.readAllLines(output);
        JsonNode header = json.readTree(lines.getFirst());
        assertEquals(1, header.get("rendered_output_frames").longValue());
        long terminal = header.get("terminal_ym_cycle").longValue();
        assertTrue(terminal > 24, "paced queued writes advance beyond the requested frame");
        assertTrue(header.get("ym_replay_start_ordinal").longValue() > 0,
                "setup boundaries remain in the diagnostic before the verified segment");

        NukedOpn2 replay = new NukedOpn2();
        replay.setChipType(NukedOpn2.MODE_YM2612 | NukedOpn2.MODE_READMODE);
        int[] pins = new int[2];
        long cycle = 0;
        for (String line : lines.subList(1, lines.size())) {
            JsonNode event = json.readTree(line);
            if (!event.get("type").asText().equals("ym")) {
                continue;
            }
            long at = event.get("cycle").longValue();
            assertTrue(at < terminal);
            while (cycle < at) {
                replay.clock(pins);
                cycle++;
            }
            replay.write(event.get("bus_port").intValue(), event.get("value").intValue());
        }
        while (cycle < terminal) {
            replay.clock(pins);
            cycle++;
        }
        assertEquals(live.core(), replay.state(),
                "the endpoint must include partial bus-drain clocks, not only returned PCM");
    }

    @Test
    void nonBusMutationCannotClaimAReplayEndpoint() throws Exception {
        PhysicalChipCapture capture = new PhysicalChipCapture(100);
        VirtualSynthesizer synth = new VirtualSynthesizer(Ym2612Chip.getInternalRate(),
                capture, VirtualSynthesizer.Initialization.DEFERRED);
        capture.beginYmReplaySegment(synth.captureSynthSnapshot().ym());
        synth.setFmMute(0, true);
        synth.render(new short[2]);
        capture.finish(1, synth.captureSynthSnapshot().ym());
        Path output = directory.resolve("mutation.jsonl");
        capture.write(output, "s3k", "music", 1, Ym2612Chip.getInternalRate(),
                "/rom/s3k.gen", "012345", new BuildIdentity("test", "abc", false));
        JsonNode header = new ObjectMapper().readTree(Files.readAllLines(output).getFirst());
        assertTrue(header.get("terminal_ym_cycle").isNull());
    }

    @Test
    void alreadyAdvancedCoreCannotBeRelabelledResetOrigin() throws Exception {
        PhysicalChipCapture capture = new PhysicalChipCapture(100);
        VirtualSynthesizer synth = new VirtualSynthesizer(Ym2612Chip.getInternalRate(),
                capture, VirtualSynthesizer.Initialization.DEFERRED);
        synth.render(new short[2]);
        capture.beginYmReplaySegment(synth.captureSynthSnapshot().ym());
        capture.finish(1, synth.captureSynthSnapshot().ym());
        Path output = directory.resolve("late.jsonl");
        capture.write(output, "s3k", "music", 1, Ym2612Chip.getInternalRate(),
                "/rom/s3k.gen", "012345", new BuildIdentity("test", "abc", false));
        JsonNode header = new ObjectMapper().readTree(Files.readAllLines(output).getFirst());
        assertTrue(header.get("ym_replay_start_ordinal").isNull());
        assertTrue(header.get("terminal_ym_cycle").isNull());
        assertThrows(IllegalArgumentException.class,
                () -> capture.finish(-1, synth.captureSynthSnapshot().ym()));
    }

    @Test
    void queuedNonBusMutationCannotHideInsideTheSetupPrefix() throws Exception {
        PhysicalChipCapture capture = new PhysicalChipCapture(100);
        VirtualSynthesizer synth = new VirtualSynthesizer(Ym2612Chip.getInternalRate(),
                capture, VirtualSynthesizer.Initialization.DEFERRED);
        synth.writeFm(this, 0, 0x28, 0xF0);
        synth.forceSilenceChannel(0);
        capture.beginYmReplaySegment(synth.captureSynthSnapshot().ym());
        synth.render(new short[2]);
        capture.finish(1, synth.captureSynthSnapshot().ym());
        Path output = directory.resolve("queued-mutation.jsonl");
        capture.write(output, "s3k", "music", 1, Ym2612Chip.getInternalRate(),
                "/rom/s3k.gen", "012345", new BuildIdentity("test", "abc", false));
        JsonNode header = new ObjectMapper().readTree(Files.readAllLines(output).getFirst());
        assertTrue(header.get("ym_replay_start_ordinal").isNull(),
                "a deferred non-bus mutation must not be hidden by a still-reset core");
        assertTrue(header.get("terminal_ym_cycle").isNull());
    }
}
