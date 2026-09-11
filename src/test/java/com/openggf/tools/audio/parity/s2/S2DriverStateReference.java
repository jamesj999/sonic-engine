package com.openggf.tools.audio.parity.s2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * The committed S2 driver-state v2 reference: one row per completed vertical
 * interrupt, with the driver RAM window the observer core sampled at the
 * driver's own service return. Comparison-only reference data; nothing here
 * hydrates engine state, and the frame field is provenance that no comparison
 * reads.
 */
final class S2DriverStateReference {
    static final String RESOURCE =
            "/audio/parity/s2/s2-driver-state-w10150-12400.reference-v2.jsonl.gz";
    static final String PAYLOAD_SCHEMA = "openggf.s2-driver-state-reference.v2";
    static final String GZIP_SHA256 =
            "7743d513cbcf05306455bf0afa3fbd7ead6fd6197a09ce893e1ac279bd34589a";
    static final int FIRST_ROW = 10_150;
    static final int EXCLUSIVE_END = 12_400;
    static final int SNAPSHOT_START = 0x12FE;
    static final int SNAPSHOT_EXCLUSIVE_END = 0x2000;
    static final int TICKS = 2243;
    static final int ZERO_SERVICE_FRAMES = 7;
    static final int MULTI_SERVICE_FRAMES = 0;

    private static final ObjectMapper JSON = new ObjectMapper();

    private S2DriverStateReference() {
    }

    /** One completed driver service: its writes, and the RAM it committed. */
    record Tick(int index, int frame, List<S2OracleRawStream.ChipWrite> writes,
            byte[] state) {
        Tick {
            writes = List.copyOf(writes);
            state = state.clone();
        }

        @Override
        public byte[] state() {
            return state.clone();
        }
    }

    /** The parsed reference: its ticks and the frame shape it recorded. */
    record Result(List<Tick> ticks, int frames, int zeroServiceFrames,
            int multiServiceFrames, long writeCount, long residualWrites) {
        Result {
            ticks = List.copyOf(ticks);
        }
    }

    static Result read() throws IOException {
        return read(open(), true);
    }

    /**
     * Reads a reference stream. {@code pinned} verifies the committed window
     * and tick count; a perturbed copy under test is read unpinned.
     */
    static Result read(InputStream input, boolean pinned) throws IOException {
        List<Tick> ticks = new ArrayList<>();
        int frames = 0;
        int zeroService = 0;
        int multiService = 0;
        long writeCount = 0;
        long residual = 0;
        boolean terminated = false;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            JsonNode metadata = parse(line, "metadata");
            require(PAYLOAD_SCHEMA.equals(metadata.get("schema").asText()),
                    "reference schema differs");
            require(!metadata.get("production_bound").asBoolean(),
                    "reference must remain unbound");
            require(metadata.get("snapshot_start").asInt() == SNAPSHOT_START
                            && metadata.get("snapshot_exclusive_end").asInt()
                                    == SNAPSHOT_EXCLUSIVE_END,
                    "reference snapshot window differs");
            if (pinned) {
                require(metadata.get("first_row").asInt() == FIRST_ROW
                                && metadata.get("exclusive_end").asInt() == EXCLUSIVE_END,
                        "reference window differs");
            }
            int expectedIndex = 0;
            while ((line = reader.readLine()) != null) {
                JsonNode record = parse(line, "row");
                String kind = record.get("row").asText();
                if ("terminal".equals(kind)) {
                    frames = record.get("frames").asInt();
                    zeroService = record.get("zero_service_frames").asInt();
                    multiService = record.get("multi_service_frames").asInt();
                    writeCount = record.get("write_count").asLong();
                    residual = record.get("residual_write_count").asLong();
                    require(record.get("ticks").asInt() == ticks.size(),
                            "terminal tick count disagrees with the rows");
                    require(reader.readLine() == null, "records follow the terminal row");
                    terminated = true;
                    break;
                }
                require("tick".equals(kind), "unknown reference row: " + kind);
                require(record.get("tick").asInt() == expectedIndex,
                        "reference ticks are not contiguous");
                expectedIndex++;
                ticks.add(new Tick(record.get("tick").asInt(),
                        record.get("frame").asInt(), writes(record.get("writes")),
                        state(record.get("ram").asText())));
            }
        }
        require(terminated, "reference has no terminal row");
        if (pinned) {
            require(ticks.size() == TICKS, "reference tick count differs");
            require(zeroService == ZERO_SERVICE_FRAMES
                            && multiService == MULTI_SERVICE_FRAMES,
                    "reference frame shape differs");
        }
        return new Result(ticks, frames, zeroService, multiService, writeCount,
                residual);
    }

    /**
     * Rebases a reference window onto the full Z80 address space so the shipped
     * decoder reads it at its own offsets. Bytes outside the window are never
     * read: the decoder's fields all lie inside it.
     */
    static byte[] rebase(byte[] window) {
        byte[] full = new byte[S2OracleSchema.STATE_BYTES];
        System.arraycopy(window, 0, full, SNAPSHOT_START, window.length);
        return full;
    }

    static InputStream open() throws IOException {
        return open(RESOURCE);
    }

    /** The same, for another committed driver-state reference of this shape. */
    static InputStream open(String resource) throws IOException {
        InputStream stream = S2DriverStateReference.class.getResourceAsStream(resource);
        if (stream == null) {
            throw new IOException("committed S2 driver-state reference is absent");
        }
        return new GZIPInputStream(stream);
    }

    static String gzipDigest() throws IOException, NoSuchAlgorithmException {
        return gzipDigest(RESOURCE);
    }

    static String gzipDigest(String resource)
            throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream stream =
                     S2DriverStateReference.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("committed S2 driver-state reference is absent");
            }
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = stream.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static List<S2OracleRawStream.ChipWrite> writes(JsonNode values) {
        List<S2OracleRawStream.ChipWrite> writes = new ArrayList<>();
        for (JsonNode value : values) {
            String chip = value.get(0).asText();
            if ("ym".equals(chip)) {
                writes.add(new S2OracleRawStream.ChipWrite(true, value.get(1).asInt(),
                        value.get(2).asInt(), value.get(3).asInt(),
                        S2OracleRawStream.ChipWrite.SERVICE_UPDATE_MUSIC));
            } else if ("psg".equals(chip)) {
                writes.add(new S2OracleRawStream.ChipWrite(false, 0, 0,
                        value.get(1).asInt(),
                        S2OracleRawStream.ChipWrite.SERVICE_UPDATE_MUSIC));
            } else {
                throw new IllegalArgumentException("unknown chip: " + chip);
            }
        }
        return writes;
    }

    private static byte[] state(String hex) {
        int expected = SNAPSHOT_EXCLUSIVE_END - SNAPSHOT_START;
        require(hex.length() == expected * 2, "reference snapshot length differs");
        return HexFormat.of().parseHex(hex);
    }

    private static JsonNode parse(String line, String label) throws IOException {
        if (line == null) {
            throw new IOException("reference ended before its " + label + " row");
        }
        return JSON.readTree(line);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(
                    "S2 driver-state reference invalid: " + message);
        }
    }
}
