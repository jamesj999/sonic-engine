package com.openggf.tools.audio.parity.s3k;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Comparison-only reader for the source-observed Sonic 3&amp;K music-mailbox
 * requests extracted from two agreeing reference captures.
 *
 * <p>Each entry is a byte a capture read out of Z80 RAM {@code $1C0A} at the
 * {@code Play_Music} bus-release instruction, while the Z80 was still stopped.
 * The sidecar exists because the v1 oracle stream samples the mailbox
 * <em>before</em> each invocation, which cannot see a request that is written
 * and consumed inside one frame. It supplies a driver <em>input</em> the old
 * capture was blind to; it carries no compared value, no engine state, and no
 * authority over what the comparison decides.</p>
 *
 * <p>This reader validates and nothing more. It has no writer, and it never
 * synthesises, defaults, or infers a request: a row absent from the sidecar
 * stays absent.</p>
 */
public final class S3kRequestObservationSidecar {
    public static final String SCHEMA = "openggf.s3k-preconsumption-request-observations.v1";

    private final String romSha1;
    private final String movieSha256;
    private final int firstRow;
    private final int exclusiveEnd;
    private final String captureSha256;
    private final Map<Integer, Integer> observations;

    private S3kRequestObservationSidecar(String romSha1, String movieSha256, int firstRow,
            int exclusiveEnd, String captureSha256, Map<Integer, Integer> observations) {
        this.romSha1 = romSha1;
        this.movieSha256 = movieSha256;
        this.firstRow = firstRow;
        this.exclusiveEnd = exclusiveEnd;
        this.captureSha256 = captureSha256;
        this.observations = Collections.unmodifiableMap(new LinkedHashMap<>(observations));
    }

    /** The committed observations for the S3K oracle's power-on window. */
    public static final Path COMMITTED = Path.of(
            "src/test/resources/audio/parity/s3k/s3k-aiz1-intro-requests-v1.json");

    /** An empty sidecar: every row is unobserved, which is the pre-existing behaviour. */
    public static S3kRequestObservationSidecar absent() {
        return new S3kRequestObservationSidecar(null, null, 0, 0, null, Map.of());
    }

    public static S3kRequestObservationSidecar read(Path path) {
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(Files.readString(path));
        } catch (IOException error) {
            throw new IllegalArgumentException(
                    "cannot read S3K request observations: " + error.getMessage(), error);
        }
        if (!SCHEMA.equals(root.path("schema").asText())) {
            throw new IllegalArgumentException(
                    "unexpected S3K request observation schema: " + root.path("schema").asText());
        }
        if (root.path("production_bound").asBoolean(true)) {
            throw new IllegalArgumentException(
                    "the S3K request observation sidecar must remain unbound");
        }
        String capture = requiredText(root, "capture_sha256");
        if (!capture.equals(requiredText(root, "duplicate_capture_sha256"))) {
            throw new IllegalArgumentException(
                    "the two S3K request captures disagree");
        }
        int firstRow = requiredInt(root, "first_row");
        int exclusiveEnd = requiredInt(root, "exclusive_end");
        if (firstRow < 0 || exclusiveEnd <= firstRow) {
            throw new IllegalArgumentException("the S3K request window is not a range");
        }
        JsonNode rows = root.path("observations");
        if (!rows.isArray() || rows.isEmpty()) {
            throw new IllegalArgumentException("the S3K request sidecar observed nothing");
        }
        Map<Integer, Integer> observations = new LinkedHashMap<>();
        int previous = -1;
        for (JsonNode row : rows) {
            int index = requiredInt(row, "row");
            int request = requiredInt(row, "request");
            if (index <= previous) {
                throw new IllegalArgumentException(
                        "S3K request observations are not strictly ascending at row " + index);
            }
            if (index < firstRow || index >= exclusiveEnd) {
                throw new IllegalArgumentException(
                        "S3K request observation is outside its declared window: " + index);
            }
            if (request < 1 || request > 0xFF) {
                throw new IllegalArgumentException(
                        "S3K request observation is not a nonzero byte at row " + index);
            }
            previous = index;
            observations.put(index, request);
        }
        return new S3kRequestObservationSidecar(requiredText(root, "rom_sha1"),
                requiredText(root, "bk2_sha256"), firstRow, exclusiveEnd, capture, observations);
    }

    /** The source-observed request written on this movie row, if one was observed. */
    public Optional<Integer> requestAt(int movieRow) {
        return Optional.ofNullable(observations.get(movieRow));
    }

    /** The observed requests, keyed by movie row, in ascending row order. */
    public Map<Integer, Integer> observations() {
        return observations;
    }

    public int size() {
        return observations.size();
    }

    public String romSha1() {
        return romSha1;
    }

    public String movieSha256() {
        return movieSha256;
    }

    public String captureSha256() {
        return captureSha256;
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isEmpty()) {
            throw new IllegalArgumentException("missing S3K request sidecar field: " + field);
        }
        return value.asText();
    }

    private static int requiredInt(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber()) {
            throw new IllegalArgumentException("missing S3K request sidecar field: " + field);
        }
        return value.asInt();
    }
}
