package com.openggf.tools.audio.parity.s2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

/**
 * Strict streaming reader for {@code openggf.s2-oracle-audio-raw.v1}: the
 * windowed S2 driver-oracle capture (metadata, baseline, one frame per driver
 * invocation, cutoff). Chip bus events are folded into decoded YM/PSG writes
 * using the YM address latches: raw event kind 3 carries the YM bus address in
 * {@code subject} (0/2 = port0/port1 address latch, 1/3 = port0/port1 data)
 * and kind 4 is a PSG data byte — the exact
 * {@code gpgx_audio_trace_fm_write(address & 3, data)} contract of observer
 * patch 0001. The initial latches come from the baseline record.
 */
public final class S2OracleRawStream {
    private static final ObjectMapper JSON = new ObjectMapper();

    private S2OracleRawStream() {
    }

    /** One decoded chip data write in execution order, with its owning service kind. */
    public record ChipWrite(boolean ym, int port, int register, int value, int serviceKind) {
        /** Service kinds from the pinned S2 observer manifest. */
        public static final int SERVICE_VINT = 3;
        public static final int SERVICE_DPCM_ITERATION = 4;
        public static final int SERVICE_DAC_DISPATCH = 6;
        public static final int SERVICE_SEGA_PCM = 7;
        public static final int SERVICE_SAXMAN = 8;
        public static final int SERVICE_UPDATE_MUSIC = 9;

        /**
         * True for writes from the nested zUpdateMusic service, as opposed to
         * its parent VInt, the DAC sample loop, drum dispatch, or SEGA PCM.
         */
        public boolean updateMusicOwned() {
            return serviceKind == SERVICE_UPDATE_MUSIC;
        }
    }

    public record Baseline(int row, byte[] state, int ymPort0Latch, int ymPort1Latch) {
        public Baseline {
            state = state.clone();
        }

        @Override
        public byte[] state() {
            return state.clone();
        }
    }

    public record Frame(int row, boolean lag, byte[] state, List<ChipWrite> writes,
            int updateMusicCompletions) {
        public Frame {
            state = state.clone();
            writes = List.copyOf(writes);
        }

        @Override
        public byte[] state() {
            return state.clone();
        }
    }

    public record Header(int firstRow, int exclusiveEnd) {
    }

    /** Streaming consumer; called in file order. */
    public interface Sink {
        void header(Header header);

        void baseline(Baseline baseline);

        void frame(Frame frame);

        void cutoff(int exclusiveEnd);
    }

    /** Reads and validates the gzip JSONL fixture, folding chip events per frame. */
    public static void scan(Path gzPayload, Sink sink) throws IOException {
        Objects.requireNonNull(gzPayload, "payload");
        Objects.requireNonNull(sink, "sink");
        try (InputStream raw = Files.newInputStream(gzPayload);
                BufferedReader input = new BufferedReader(new InputStreamReader(
                        new GZIPInputStream(raw), StandardCharsets.UTF_8))) {
            JsonNode metadata = parse(requiredLine(input), "metadata");
            require(S2OracleSchema.PAYLOAD_SCHEMA.equals(text(metadata, "schema")),
                    "payload schema is not " + S2OracleSchema.PAYLOAD_SCHEMA);
            require(S2OracleSchema.S2_REV01_SHA1.equals(text(metadata, "rom_sha1")),
                    "payload ROM identity changed");
            require(S2OracleSchema.BK2_SHA256.equals(text(metadata, "bk2_sha256")),
                    "payload BK2 identity changed");
            require(S2OracleSchema.SERVICE_MANIFEST_SHA256
                            .equals(text(metadata, "service_manifest_sha256")),
                    "payload service-manifest identity changed");
            int firstRow = integer(metadata, "first_row");
            int end = integer(metadata, "exclusive_end");
            require(firstRow >= 0 && end > firstRow, "payload window is not a valid interval");
            require(integer(metadata, "state_start") == 0
                            && integer(metadata, "state_exclusive_end") == S2OracleSchema.STATE_BYTES,
                    "payload driver-state range changed");
            sink.header(new Header(firstRow, end));

            JsonNode baseline = parse(requiredLine(input), "baseline");
            require("baseline".equals(text(baseline, "type")), "second record is not baseline");
            require(integer(baseline, "row") == firstRow, "baseline row disagrees with the window");
            int latch0 = integer(baseline, "ym_port0_latch");
            int latch1 = integer(baseline, "ym_port1_latch");
            sink.baseline(new Baseline(firstRow, state(baseline), latch0, latch1));

            int[] latches = { latch0, latch1 };
            int expected = firstRow;
            while (true) {
                JsonNode record = parse(requiredLine(input), "frame or cutoff");
                String type = text(record, "type");
                if ("cutoff".equals(type)) {
                    int cutoffEnd = integer(record, "exclusive_end");
                    require(cutoffEnd == expected, "cutoff does not follow the contiguous rows");
                    require(cutoffEnd == end, "cutoff disagrees with the declared window end");
                    require(input.readLine() == null, "records follow the cutoff");
                    sink.cutoff(cutoffEnd);
                    return;
                }
                require("frame".equals(type), "unknown record type: " + type);
                int row = integer(record, "row");
                require(row == expected && row < end, "frame rows are not contiguous and in range");
                JsonNode events = record.get("events");
                sink.frame(new Frame(row, bool(record, "lag"), state(record),
                        foldWrites(events, latches), countUpdateMusicCompletions(events)));
                expected++;
            }
        }
    }

    /**
     * Counts completed UpdateMusic services (manifest kind 9). The completion
     * marker, rather than the begin marker, identifies the frame whose RAM
     * image contains the post-service state: a long update can cross a video
     * frame boundary after its begin event.
     */
    private static int countUpdateMusicCompletions(JsonNode events) {
        require(events != null && events.isArray(), "frame events are absent");
        int completions = 0;
        for (JsonNode event : events) {
            if (integer(event, "kind") == 2
                    && integer(event, "service_kind") == ChipWrite.SERVICE_UPDATE_MUSIC) {
                completions++;
            }
        }
        return completions;
    }

    private static List<ChipWrite> foldWrites(JsonNode events, int[] latches) {
        require(events != null && events.isArray(), "frame events are absent");
        List<ChipWrite> writes = new ArrayList<>();
        for (JsonNode event : events) {
            int kind = integer(event, "kind");
            if (kind == 3) {
                int subject = integer(event, "subject");
                int value = integer(event, "value");
                require(subject >= 0 && subject <= 3, "YM bus event subject is not an address");
                switch (subject) {
                    case 0 -> latches[0] = value;
                    case 2 -> latches[1] = value;
                    case 1 -> writes.add(new ChipWrite(true, 0, latches[0], value,
                            integer(event, "service_kind")));
                    default -> writes.add(new ChipWrite(true, 1, latches[1], value,
                            integer(event, "service_kind")));
                }
            } else if (kind == 4) {
                writes.add(new ChipWrite(false, 0, 0, integer(event, "value"),
                        integer(event, "service_kind")));
            }
        }
        return writes;
    }

    private static byte[] state(JsonNode record) {
        String hex = text(record, "state_hex");
        require(hex.length() == S2OracleSchema.STATE_BYTES * 2,
                "driver state is not exactly $0000..$1FFF");
        return HexFormat.of().parseHex(hex);
    }

    private static JsonNode parse(String line, String label) throws IOException {
        JsonNode value = JSON.readTree(line);
        require(value != null && value.isObject(), label + " record is not an object");
        return value;
    }

    private static String requiredLine(BufferedReader input) throws IOException {
        String line = input.readLine();
        require(line != null, "payload ended early");
        return line;
    }

    private static String text(JsonNode value, String name) {
        JsonNode node = value.get(name);
        require(node != null && node.isTextual(), "missing string: " + name);
        return node.asText();
    }

    private static int integer(JsonNode value, String name) {
        JsonNode node = value.get(name);
        require(node != null && node.canConvertToInt(), "missing integer: " + name);
        return node.asInt();
    }

    private static boolean bool(JsonNode value, String name) {
        JsonNode node = value.get(name);
        require(node != null && node.isBoolean(), "missing boolean: " + name);
        return node.asBoolean();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException("S2 oracle payload invalid: " + message);
        }
    }
}
