package com.openggf.tools.audio.parity.s3k;

import com.openggf.tools.audio.parity.AudioParityChipWrite;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kDacRunDiagnostics {
    @Test
    void selectedFinalRunIsReportedWithoutInventingATerminatingWrite() {
        List<S3kAudioTick> ticks = List.of(tick(7,
                AudioParityChipWrite.ym2612(0, 0x2B, 0x80),
                AudioParityChipWrite.ym2612(0, 0x2A, 0x11),
                AudioParityChipWrite.ym2612(0, 0x2B, 0),
                AudioParityChipWrite.ym2612(0, 0x2B, 0x80),
                AudioParityChipWrite.ym2612(0, 0x2A, 0xE1)),
                tick(8, AudioParityChipWrite.ym2612(0, 0x2A, 0xE2)));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            DebugS3kDacRuns.print("SENTINEL", ticks, 1, 1, output);
        }
        String text = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("run=1 start=service=7,write=4"), text);
        assertTrue(text.contains("length=2 first=[225, 226] last=[225, 226]"), text);
        assertTrue(text.contains("unterminated=true"), text);
        assertFalse(text.contains("run=0"), text);
        assertFalse(text.contains("value=0"), "EOF must not fabricate a DAC disable");
    }

    @Test
    void emptyWindowReportsNoRun() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            DebugS3kDacRuns.print("SENTINEL", List.of(), 0, 0, output);
        }
        assertTrue(bytes.toString(StandardCharsets.UTF_8).isEmpty());
    }

    private static S3kAudioTick tick(int ordinal, AudioParityChipWrite... writes) {
        return new S3kAudioTick(ordinal, false, List.of(0, 0, 0),
                new S3kAudioTick.GlobalState(null, null, null, null, null, null, null,
                        null, null, null, null, null, null),
                S3kAudioParitySchema.ROLES.stream().map(S3kAudioTrackState::idle).toList(),
                List.of(writes));
    }
}
