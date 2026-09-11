package com.openggf.tools.timing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kLoadTimeProfileGenerator {
    @TempDir
    Path temp;

    @Test
    void publishesLowerMedianFromExactClassifiedChildrenOnly() throws Exception {
        Path input = temp.resolve("measurements.jsonl");
        Files.writeString(input,
                record(0, "exact_callback", true, 7)
                        + record(1, "exact_callback", true, 5)
                        + record(2, "exact_callback", true, 9)
                        + record(3, "frame_end_censored", true, 99)
                        + record(4, "exact_callback", false, 99)
                        + record(5, "exact_callback", true, 0));

        var result = S3kLoadTimeProfileGenerator.generate(List.of(input));

        assertEquals(1, result.uniqueEligibleFingerprints());
        assertTrue(result.manifestJson().contains("\"serviceFrames\": 7"));
        assertTrue(result.manifestJson().contains("\"sampleCount\": 3"));
        assertEquals(1, result.censoredObservations());
        assertEquals(2, result.unclassifiedObservations());
    }

    @Test
    void rejectsUnknownFieldsAndParentRecords() throws Exception {
        Path input = temp.resolve("bad.jsonl");
        Files.writeString(input, record(0, "exact_callback", true, 1)
                .replace("\"ordinal\":0", "\"ordinal\":0,\"extra\":1"));
        assertThrows(IllegalArgumentException.class,
                () -> S3kLoadTimeProfileGenerator.generate(List.of(input)));
    }

    @Test
    void rejectsFingerprintDisagreementAndNonmonotonicOrdinals() throws Exception {
        Path badFingerprint = temp.resolve("bad-fingerprint.jsonl");
        Files.writeString(badFingerprint, record(
                0, "exact_callback", true, 1).replace(
                "sha256:1135", "sha256:2135"));
        assertThrows(IllegalArgumentException.class,
                () -> S3kLoadTimeProfileGenerator.generate(
                        List.of(badFingerprint)));

        Path badOrder = temp.resolve("bad-order.jsonl");
        Files.writeString(badOrder,
                record(1, "exact_callback", true, 1)
                        + record(0, "exact_callback", true, 1));
        assertThrows(IllegalArgumentException.class,
                () -> S3kLoadTimeProfileGenerator.generate(List.of(badOrder)));
    }

    @Test
    void exhaustiveEstimatorAndHeldOutFoldsPublishDeterministically()
            throws Exception {
        List<Path> inputs = new java.util.ArrayList<>();
        for (int family = 0; family < 3; family++) {
            Path input = temp.resolve("family-" + family + ".jsonl");
            StringBuilder rows = new StringBuilder();
            for (int index = 0; index < 7; index++) {
                int global = family * 7 + index;
                int shortCopies = global + 1;
                rows.append(modelRecord(
                        family, index, 0x100 + global, shortCopies,
                        (shortCopies + 3) / 4));
            }
            Files.writeString(input, rows);
            inputs.add(input);
        }
        var result = S3kLoadTimeProfileGenerator.generate(inputs);

        var validation = result.validateEstimator();

        assertTrue(validation.accepted());
        assertEquals(
                S3kLoadTimeProfileGenerator.Feature.SHORT_COPY_COMMANDS,
                validation.candidate().feature());
        assertEquals(4, validation.candidate().divisor());

        Path firstManifest = temp.resolve("first/manifest.json");
        Path firstReport = temp.resolve("first/report.md");
        Path secondManifest = temp.resolve("second/manifest.json");
        Path secondReport = temp.resolve("second/report.md");
        S3kLoadTimeProfileGenerator.publish(
                result, firstManifest, firstReport,
                temp.resolve("first/publication.tsv"));
        S3kLoadTimeProfileGenerator.publish(
                result, secondManifest, secondReport,
                temp.resolve("second/publication.tsv"));
        assertEquals(Files.readString(firstManifest),
                Files.readString(secondManifest));
        assertEquals(Files.readString(firstReport),
                Files.readString(secondReport));
    }

    private static String record(
            int ordinal, String precision, boolean classified, int units) {
        String fingerprint =
                "sha256:1135c7e6b6f9e9dfa4ce84ed5bcce9bc5ad7289a14a1c51ce702b67a0773a451";
        String parent = precision.equals("exact_callback")
                ? "\"" + fingerprint + "\"" : "null";
        return "{\"measurement_schema\":1,"
                + "\"recorder_version\":\"load-time-measurement-v1\","
                + "\"fixture\":\"a.bk2\","
                + "\"movie_sha256\":\"" + "a".repeat(64) + "\","
                + "\"rom_sha1\":\"CFBF98C36C776677290A872547AC47C53D2761D6\","
                + "\"service_model\":\"s3k-kos-v1\",\"epoch\":0,"
                + "\"raw_frame\":" + (ordinal + 1) + ","
                + "\"sequence_in_frame\":0,\"boundary\":\"pre_main_loop\","
                + "\"kind\":\"KOS_DECOMPRESSION_QUEUE\",\"ordinal\":" + ordinal + ","
                + "\"fingerprint\":\"" + fingerprint + "\","
                + "\"parent_fingerprint\":" + parent + ","
                + "\"observation_precision\":\"" + precision + "\","
                + "\"classified\":" + classified + ","
                + "\"service_opportunities\":" + units + ","
                + "\"source\":1,\"destination\":2,\"compressed_length\":8,"
                + "\"decompressed_length\":3,\"literal_commands\":3,"
                + "\"short_copy_commands\":0,\"long_copy_commands\":0,"
                + "\"copied_output_length\":0}\n";
    }

    private static String modelRecord(
            int family, int ordinal, int source, int shortCopies, int units)
            throws Exception {
        String fingerprint = fingerprint(source, 8, 2, 3);
        return "{\"measurement_schema\":1,"
                + "\"recorder_version\":\"load-time-measurement-v1\","
                + "\"fixture\":\"family-" + family + ".bk2\","
                + "\"movie_sha256\":\"" + Integer.toHexString(family + 1)
                .repeat(64) + "\","
                + "\"rom_sha1\":\"CFBF98C36C776677290A872547AC47C53D2761D6\","
                + "\"service_model\":\"s3k-kos-v1\",\"epoch\":0,"
                + "\"raw_frame\":" + (ordinal + 1)
                + ",\"sequence_in_frame\":0,\"boundary\":\"pre_main_loop\","
                + "\"kind\":\"KOS_DECOMPRESSION_QUEUE\",\"ordinal\":" + ordinal
                + ",\"fingerprint\":\"" + fingerprint + "\","
                + "\"parent_fingerprint\":\"" + fingerprint + "\","
                + "\"observation_precision\":\"exact_callback\","
                + "\"classified\":true,\"service_opportunities\":" + units
                + ",\"source\":" + source
                + ",\"destination\":2,\"compressed_length\":8,"
                + "\"decompressed_length\":3,\"literal_commands\":1,"
                + "\"short_copy_commands\":" + shortCopies + ","
                + "\"long_copy_commands\":0,\"copied_output_length\":2}\n";
    }

    private static String fingerprint(
            int source, int compressed, int destination, int decompressed)
            throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        writeText(bytes, "KOS_DECOMPRESSION_QUEUE");
        bytes.write(ByteBuffer.allocate(4).putInt(source).array());
        bytes.write(ByteBuffer.allocate(4).putInt(compressed).array());
        bytes.write(ByteBuffer.allocate(4).putInt(destination).array());
        bytes.write(ByteBuffer.allocate(4).putInt(decompressed).array());
        writeText(bytes, "kosinski");
        bytes.write(ByteBuffer.allocate(4).putInt(1).array());
        return "sha256:" + java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(bytes.toByteArray()));
    }

    private static void writeText(
            java.io.OutputStream output, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.write(ByteBuffer.allocate(4).putInt(bytes.length).array());
        output.write(bytes);
    }
}
