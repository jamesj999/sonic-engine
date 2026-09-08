package com.openggf.tools.audio.parity.s1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Strict, diagnostic-only projection of a sealed S1 native restore boundary. */
public final class S1RestoreNativeDiagnostic {
    public static final String RAW_SHA256 =
            "798c2197005c88abf99173629815220fe4d574274d9fa774be76fdeb37d57122";
    public static final String ATTESTATION_SHA256 =
            "1796640fcb106bd50587f9e45af7643bf0cce790ac04754abfa0c2e97c5a064b";
    public static final String ROM_SHA1 =
            "69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b";
    public static final String BK2_SHA256 =
            "f2e817936d07b2b1f2b80d61451f174189509a2817da2b2349ce0e19b8a5567b";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long RAW_BYTES = 25_700_433_659L;

    private S1RestoreNativeDiagnostic() { }

    public record Request(int row, long id, int soundId) { }
    public record Dispatch(int row, long requestId, int soundId) { }
    public record Write(long ordinal, int eventKind, String chip, int port,
            int register, int value, boolean data) { }
    public record Boundary(int requestFrame, int admissionFrame, int frame,
            long serviceToken, long nativeOrdinal, List<Write> writes) {
        public Boundary {
            writes = List.copyOf(writes);
        }
    }
    public record Capture(int firstRow, int exclusiveEnd,
            List<Request> requests, List<Dispatch> dispatches,
            Boundary boundary) {
        public Capture {
            requests = List.copyOf(requests);
            dispatches = List.copyOf(dispatches);
            Objects.requireNonNull(boundary, "boundary");
        }
    }

    public static Capture read(Path raw, Path attestation) throws IOException {
        requireDigest(raw, RAW_SHA256, RAW_BYTES, "raw");
        requireDigest(attestation, ATTESTATION_SHA256, -1, "attestation");
        return readVerified(raw, attestation);
    }

    static Capture readVerified(Path raw, Path attestation) throws IOException {
        JsonNode seal = JSON.readTree(attestation.toFile());
        requireText(seal, "schema",
                "openggf.override-resume-first-divergence-attestation.v1");
        requireText(seal, "raw_sha256", RAW_SHA256);
        requireText(seal, "status", "ok");
        requireInt(seal, "fault_count", 0);
        requireInt(seal, "overflow_count", 0);

        List<Request> requests = new ArrayList<>();
        List<Dispatch> dispatches = new ArrayList<>();
        Boundary boundary = null;
        int firstRow = -1;
        int exclusiveEnd = -1;
        try (BufferedReader lines = Files.newBufferedReader(raw,
                StandardCharsets.UTF_8)) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (line.startsWith("{\"type\":\"metadata\"")) {
                    JsonNode node = JSON.readTree(line);
                    requireText(node, "schema",
                            "openggf.s1-complete-run-audio-raw.v1");
                    requireText(node, "rom_sha1", ROM_SHA1.toUpperCase());
                    requireText(node, "bk2_sha256", BK2_SHA256);
                    requireInt(node, "native_abi", 5);
                    firstRow = node.path("first_row").intValue();
                    exclusiveEnd = node.path("exclusive_end").intValue();
                } else if (line.startsWith("{\"type\":\"request\"")) {
                    JsonNode node = JSON.readTree(line);
                    requests.add(new Request(node.path("row").intValue(),
                            node.path("request_id").longValue(),
                            node.path("sound_id").intValue()));
                } else if (line.startsWith("{\"type\":\"dispatch\"")) {
                    JsonNode node = JSON.readTree(line);
                    dispatches.add(new Dispatch(node.path("row").intValue(),
                            node.path("request_id").longValue(),
                            node.path("sound_id").intValue()));
                } else if (line.contains("\"type\":\"override_resume\"")) {
                    if (boundary != null) {
                        throw new IOException("multiple native restore boundaries");
                    }
                    boundary = parseBoundary(JSON.readTree(line));
                }
            }
        }
        if (firstRow < 0 || exclusiveEnd <= firstRow || boundary == null) {
            throw new IOException("native diagnostic is incomplete");
        }
        requireCausalHistory(requests, dispatches, boundary);
        return new Capture(firstRow, exclusiveEnd, requests, dispatches, boundary);
    }

    private static Boundary parseBoundary(JsonNode node) throws IOException {
        requireText(node, "request", "cfFadeInToPrevious");
        requireText(node, "admission", "native_restore_entry");
        requireInt(node, "fix_bugs", 0);
        if (node.path("writes_dac_disable_zero").booleanValue()) {
            throw new IOException("FixBugs=0 restore unexpectedly wrote DAC disable");
        }
        List<Write> writes = new ArrayList<>();
        int[] latch = {-1, -1};
        long previous = -1;
        for (JsonNode write : node.path("writes")) {
            long ordinal = write.path("native_ordinal").longValue();
            int port = write.path("port").intValue();
            int register = write.path("register").intValue();
            int value = write.path("value").intValue();
            boolean data = write.path("data").booleanValue();
            int eventKind = write.path("event_kind").intValue();
            if (ordinal <= previous || value < 0 || value > 255) {
                throw new IOException("malformed native write ordering/value");
            }
            String chip;
            if (eventKind == 3) {
                chip = "YM2612";
                if (port < 0 || port > 1) {
                    throw new IOException("native YM write has invalid port");
                }
                if (data) {
                    if (latch[port] < 0 || register != latch[port]) {
                        throw new IOException("native data write has no matching address");
                    }
                    latch[port] = -1;
                } else {
                    if (latch[port] >= 0) {
                        throw new IOException("native YM address was replaced before data");
                    }
                    latch[port] = value;
                }
            } else if (eventKind == 4) {
                chip = "PSG";
            } else {
                throw new IOException("unexpected native restore write kind");
            }
            writes.add(new Write(ordinal, eventKind, chip, port, register,
                    value, data));
            previous = ordinal;
        }
        if (latch[0] >= 0 || latch[1] >= 0) {
            throw new IOException("native YM address is missing data");
        }
        if (writes.isEmpty()) {
            throw new IOException("native restore has no writes");
        }
        Write last = writes.getLast();
        if (last.eventKind() != 4 || last.value() != 0xff) {
            throw new IOException("pinned native restore is missing its observed terminal PSG key-off");
        }
        return new Boundary(node.path("request_frame").intValue(),
                node.path("admission_frame").intValue(),
                node.path("frame").intValue(),
                node.path("service_token").longValue(),
                node.path("native_ordinal").longValue(), writes);
    }

    private static void requireCausalHistory(List<Request> requests,
            List<Dispatch> dispatches, Boundary boundary) throws IOException {
        Request request = requests.stream()
                .filter(value -> value.row() == boundary.requestFrame()
                        && value.soundId() == 0x88)
                .findFirst().orElseThrow(() ->
                        new IOException("restore request identity is unmatched"));
        boolean dispatched = dispatches.stream().anyMatch(value ->
                value.requestId() == request.id()
                        && value.row() == boundary.admissionFrame()
                        && value.soundId() == request.soundId());
        if (!dispatched) {
            throw new IOException("restore admission identity is unmatched");
        }
    }

    private static void requireDigest(Path file, String expected,
            long expectedBytes, String label) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException(label + " is not a regular file");
        }
        if (expectedBytes >= 0 && Files.size(file) != expectedBytes) {
            throw new IOException(label + " byte count mismatch");
        }
        String actual;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] bytes = new byte[1024 * 1024];
                for (int count; (count = input.read(bytes)) >= 0;) {
                    digest.update(bytes, 0, count);
                }
            }
            actual = HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        if (!expected.equals(actual)) {
            throw new IOException(label + " SHA-256 mismatch");
        }
    }

    private static void requireText(JsonNode node, String field,
            String expected) throws IOException {
        if (!expected.equals(node.path(field).textValue())) {
            throw new IOException(field + " mismatch");
        }
    }

    private static void requireInt(JsonNode node, String field,
            int expected) throws IOException {
        if (!node.path(field).isInt() || node.path(field).intValue() != expected) {
            throw new IOException(field + " mismatch");
        }
    }
}
