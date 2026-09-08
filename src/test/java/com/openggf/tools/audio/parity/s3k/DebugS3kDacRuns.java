package com.openggf.tools.audio.parity.s3k;

import com.openggf.tools.audio.parity.AudioParityChipWrite;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Explicit, comparison-only diagnostic; never changes replay state. */
@EnabledIfSystemProperty(named = "s3k.dac.probe", matches = "true")
class DebugS3kDacRuns {
    @Test
    void annotateRuns() {
        List<S3kAudioTick> reference = new ArrayList<>();
        S3kAudioReferenceReader.readDriverServices(Path.of(
                "src/test/resources/audio/parity/s3k/s3k-aiz1-intro-reference-v2.jsonl.gz"),
                S3kRequestObservationSidecar.read(S3kRequestObservationSidecar.COMMITTED),
                reference::add);
        List<S3kAudioTick> engine = S3kOpenGgfAudioCapture.capture(
                Path.of(System.getProperty("s3k.rom.path")), reference, null).ticks();
        System.out.println(S3kAudioParityComparator.compare(reference, engine).toMachineText());
        System.out.println(S3kAudioParityComparator.compareDacStream(reference, engine).toMachineText());
        System.out.println("RUN_COUNTS reference=" + S3kAudioParityComparator.dacRuns(reference).size()
                + " engine=" + S3kAudioParityComparator.dacRuns(engine).size());
        print("REFERENCE", reference);
        print("ENGINE", engine);
        int differences = 0;
        boolean trackDifference = false;
        for (int i = 0; i < reference.size(); i++) {
            var r = reference.get(i);
            var e = engine.get(i);
            var rt = r.tracks().getFirst();
            var et = e.tracks().getFirst();
            if (!trackDifference && rt.playing() && et.playing()
                    && (!java.util.Objects.equals(rt.frequency(), et.frequency())
                    || !java.util.Objects.equals(rt.durationTimeout(), et.durationTimeout()))) {
                trackDifference = true;
                System.out.println("FIRST_DAC_TRACK_DIFFERENCE " + i + " mail=" + r.mailbox()
                        + " ref=" + rt + " engine=" + et + " refglobal=" + r.global() + " engglobal=" + e.global());
                for (int k = Math.max(0, i - 2); k <= i; k++) {
                    System.out.println("CONTEXT " + k + " refglobal=" + reference.get(k).global()
                            + " engglobal=" + engine.get(k).global() + " mail=" + reference.get(k).mailbox());
                }
            }
            var rc = r.writes().stream().filter(w -> "ym2612".equals(w.chip())
                    && w.port() == 0 && w.register() == 0x2B && w.value() != 0).count();
            var ec = e.writes().stream().filter(w -> "ym2612".equals(w.chip())
                    && w.port() == 0 && w.register() == 0x2B && w.value() != 0).count();
            if (rc != ec && differences++ < 16) System.out.println("ENABLE_DIFFERENCE " + i + " ref=" + rc + " engine=" + ec);
        }
    }

    private static void print(String source, List<S3kAudioTick> ticks) {
        print(source, ticks, Integer.getInteger("s3k.dac.probe.firstRun", 335),
                Integer.getInteger("s3k.dac.probe.lastRun", 341), System.out);
    }

    static void print(String source, List<S3kAudioTick> ticks,
            int fromRun, int toRun, PrintStream output) {
        int run = 0;
        List<Integer> bytes = new ArrayList<>();
        String start = "none";
        Integer precedingNote = null;
        Integer runNote = null;
        for (S3kAudioTick tick : ticks) {
            for (int index = 0; index < tick.writes().size(); index++) {
                AudioParityChipWrite write = tick.writes().get(index);
                if (!"ym2612".equals(write.chip()) || write.port() != 0) continue;
                String position = "service=" + tick.ordinal() + ",write=" + index;
                if (write.register() == 0x2A) {
                    if (bytes.isEmpty()) {
                        start = position;
                        runNote = precedingNote;
                    }
                    bytes.add(write.value());
                } else if (write.register() == 0x2B) {
                    if (run >= fromRun && run <= toRun) {
                        output.println(source + " boundary " + position + " value=" + write.value());
                    }
                    if (!bytes.isEmpty()) {
                        if (run >= fromRun && run <= toRun) {
                            output.println(source + " run=" + run + " start=" + start
                                    + " precedingServiceDacNote=" + runNote
                                    + " length=" + bytes.size() + " first="
                                    + bytes.subList(0, Math.min(12, bytes.size())) + " last="
                                    + bytes.subList(Math.max(0, bytes.size() - 12), bytes.size()));
                        }
                        run++;
                        bytes.clear();
                    }
                }
            }
            precedingNote = tick.tracks().getFirst().frequency();
        }
        if (!bytes.isEmpty() && run >= fromRun && run <= toRun) {
            output.println(source + " run=" + run + " start=" + start
                    + " precedingServiceDacNote=" + runNote
                    + " length=" + bytes.size() + " first="
                    + bytes.subList(0, Math.min(12, bytes.size())) + " last="
                    + bytes.subList(Math.max(0, bytes.size() - 12), bytes.size())
                    + " unterminated=true");
        }
    }
}
