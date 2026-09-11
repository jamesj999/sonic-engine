package com.openggf.tools.audio.parity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestOverrideResumeReferenceBundle {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NAMESPACE_PRECONDITION =
            "All publishers cooperate through the exclusive fixture-root lock; "
                    + "the authoritative root and ancestors remain namespace-stable and "
                    + "protected from rename and mount mutation. Same-credential rename "
                    + "and mount mutation after validation is unsupported.";

    @TempDir
    Path temp;

    @Test
    void opensTheExactFourMemberCommitObject() throws Exception {
        Path parity = validBundle();
        OverrideResumeReferenceBundle bundle = OverrideResumeReferenceBundle.open(parity);
        assertEquals("s1", bundle.s1().reference().path("game").asText());
        assertEquals("s2", bundle.s2().metadata().path("game").asText());
        assertEquals(4, bundle.memberInventory().size());
    }

    @Test
    void absentBundlePreservesTheAuthenticatedAuthorityLimitation() throws Exception {
        Path parity = Files.createDirectories(temp.resolve("absent"));
        OverrideResumeReferenceBundle.ReferenceUnavailableException failure = assertThrows(
                OverrideResumeReferenceBundle.ReferenceUnavailableException.class,
                () -> OverrideResumeReferenceBundle.open(parity));
        assertEquals("FRESH_AUTHENTICATED_NATIVE_GPGX_AUTHORITY_UNAVAILABLE", failure.code());
    }

    @Test
    void rejectsMissingExtraSymlinkedAndHashMismatchedMembers() throws Exception {
        Path parity = validBundle();
        Path bundle = parity.resolve(OverrideResumeReferenceBundle.BUNDLE_NAME);
        Path s1 = bundle.resolve("s1");
        Path metadata = s1.resolve("s1-override-resume-metadata.v1.json");
        Path reference = s1.resolve("s1-override-resume-reference.v1.jsonl.gz");

        Files.delete(metadata);
        assertInvalid(parity);
        writeGame(bundle, "s1");
        Files.writeString(bundle.resolve("extra"), "extra\n");
        assertInvalid(parity);
        Files.delete(bundle.resolve("extra"));

        Path outside = temp.resolve("outside.gz");
        Files.copy(reference, outside);
        Files.delete(reference);
        Files.createSymbolicLink(reference, outside);
        assertInvalid(parity);
        Files.delete(reference);
        Files.copy(outside, reference);

        byte[] changed = Files.readAllBytes(reference);
        changed[changed.length - 1] ^= 1;
        Files.write(reference, changed);
        assertInvalid(parity);
    }

    @Test
    void rejectsSchemaInventoryAndLogicalDigestMismatches() throws Exception {
        Path parity = validBundle();
        Path bundle = parity.resolve(OverrideResumeReferenceBundle.BUNDLE_NAME);
        mutateMetadata(bundle, "s2", value -> value.put("schema", "wrong"));
        assertInvalid(parity);
        writeGame(bundle, "s2");
        mutateMetadata(bundle, "s2", value -> value.putArray("bundle_member_inventory")
                .add("wrong"));
        assertInvalid(parity);
        writeGame(bundle, "s2");
        mutateMetadata(bundle, "s2", value -> value.put("logical_sha256", "0".repeat(64)));
        assertInvalid(parity);
    }

    @Test
    void rejectsWrongPreconditionAndRawByteCount() throws Exception {
        Path parity = validBundle();
        Path bundle = parity.resolve(OverrideResumeReferenceBundle.BUNDLE_NAME);
        mutateMetadata(bundle, "s1", value -> value.put(
                "namespace_lock_precondition", "cooperative namespace-stable root"));
        assertInvalid(parity);
        writeGame(bundle, "s1");
        mutateMetadata(bundle, "s1", value -> value.put("raw_byte_count", 0));
        assertInvalid(parity);
        writeGame(bundle, "s1");
        mutateMetadata(bundle, "s1", value -> value.put("raw_byte_count", "1"));
        assertInvalid(parity);
    }

    @Test
    void rejectsDuplicateJsonKeys() throws Exception {
        Path parity = validBundle();
        Path metadata = parity.resolve(OverrideResumeReferenceBundle.BUNDLE_NAME)
                .resolve("s1/s1-override-resume-metadata.v1.json");
        String text = Files.readString(metadata, StandardCharsets.UTF_8);
        Files.writeString(metadata, text.replaceFirst(
                "\\\"game\\\":\\\"s1\\\"", "\\\"game\\\":\\\"s1\\\",\\\"game\\\":\\\"s1\\\""),
                StandardCharsets.UTF_8);
        assertInvalid(parity);
    }

    @Test
    void rejectsIncompleteAndCrossGameNestedShapes() throws Exception {
        Path parity = validBundle();
        Path bundle = parity.resolve(OverrideResumeReferenceBundle.BUNDLE_NAME);
        mutateReference(bundle, "s1", value -> ((ObjectNode) value.get("boundary"))
                .remove("request_frame"));
        assertInvalid(parity);
        writeGame(bundle, "s1");
        mutateReference(bundle, "s1", value -> ((ObjectNode) value.get("pcm"))
                .remove("type"));
        assertInvalid(parity);
        writeGame(bundle, "s2");
        mutateReference(bundle, "s2", value -> ((ObjectNode) value.get("pcm"))
                .put("type", "native_pcm_packet"));
        assertInvalid(parity);
        writeGame(bundle, "s2");
        mutateReference(bundle, "s2", value -> ((ObjectNode) value.get("boundary"))
                .put("type", "override_resume"));
        assertInvalid(parity);
    }

    @Test
    void rejectsCoercedNestedNumericTypesAndRanges() throws Exception {
        Path parity = validBundle();
        Path bundle = parity.resolve(OverrideResumeReferenceBundle.BUNDLE_NAME);
        mutateReference(bundle, "s1", value -> ((ObjectNode) value.get("boundary"))
                .put("native_ordinal", "30"));
        assertInvalid(parity);
        writeGame(bundle, "s1");
        mutateReference(bundle, "s1", value -> ((ObjectNode) ((ArrayNode)
                value.path("boundary").path("writes")).get(0)).put("event_kind", 1.0));
        assertInvalid(parity);
        writeGame(bundle, "s1");
        mutateReference(bundle, "s1", value -> ((ObjectNode) value.get("pcm"))
                .put("sample_rate", -1));
        assertInvalid(parity);
    }

    @Test
    void rejectsWriteOrderPcmInventoryAndDigestRelationshipMismatches() throws Exception {
        Path parity = validBundle();
        Path bundle = parity.resolve(OverrideResumeReferenceBundle.BUNDLE_NAME);
        mutateReference(bundle, "s1", value -> ((ObjectNode) ((ArrayNode)
                value.path("boundary").path("writes")).get(1)).put("native_ordinal", 31));
        assertInvalid(parity);
        writeGame(bundle, "s1");
        mutateReference(bundle, "s1", value -> ((ObjectNode) value.get("pcm"))
                .put("row", 3911));
        assertInvalid(parity);
        writeGame(bundle, "s1");
        mutateReference(bundle, "s1", value -> ((ObjectNode) value.get("pcm"))
                .put("byte_count", 8));
        assertInvalid(parity);
        writeGame(bundle, "s1");
        mutateReference(bundle, "s1", value -> ((ObjectNode) value.get("pcm"))
                .put("sha256", "0".repeat(64)));
        assertInvalid(parity);
    }

    private static void assertInvalid(Path parity) {
        assertThrows(OverrideResumeReferenceBundle.InvalidBundleException.class,
                () -> OverrideResumeReferenceBundle.open(parity));
    }

    private Path validBundle() throws Exception {
        Path parity = Files.createDirectories(temp.resolve("parity"));
        Path bundle = Files.createDirectories(parity.resolve(
                OverrideResumeReferenceBundle.BUNDLE_NAME));
        writeGame(bundle, "s1");
        writeGame(bundle, "s2");
        return parity;
    }

    private static void writeGame(Path bundle, String game) throws Exception {
        Path directory = Files.createDirectories(bundle.resolve(game));
        byte[] logical = jsonLine(validReference(game));
        byte[] stored = gzip(logical);
        Files.write(directory.resolve(game + "-override-resume-reference.v1.jsonl.gz"), stored);
        Files.write(directory.resolve(game + "-override-resume-metadata.v1.json"),
                jsonLine(validMetadata(game, logical, stored)));
    }

    private static ObjectNode validReference(String game) {
        ObjectNode reference = JSON.createObjectNode();
        reference.put("schema", "openggf.override-resume-first-divergence-reference.v1");
        reference.put("game", game);
        reference.set("boundary", game.equals("s1") ? s1Boundary() : s2Boundary());
        reference.set("pcm", pcm(game));
        return reference;
    }

    private static ObjectNode s1Boundary() {
        ObjectNode value = JSON.createObjectNode();
        value.put("type", "override_resume");
        value.put("request", "cfFadeInToPrevious");
        value.put("admission", "native_restore_entry");
        value.put("request_frame", 3698);
        value.put("admission_frame", 3699);
        value.put("frame", 3910);
        value.put("pc", 0x72B14);
        value.put("service_token", 9);
        value.put("native_ordinal", 30);
        value.put("fix_bugs", 0);
        value.put("writes_dac_disable_zero", false);
        value.set("writes", writes(31));
        return value;
    }

    private static ObjectNode s2Boundary() {
        ObjectNode value = JSON.createObjectNode();
        value.put("request", "cfFadeInToPrevious");
        value.put("admission", "native_service_completion");
        value.put("request_pc", 0x0D35);
        value.put("pc", 0x0DB4);
        value.put("service_token", 7);
        value.put("service_begin_ordinal", 10);
        value.put("native_ordinal", 42);
        value.put("frame", 4000);
        value.put("fix_driver_bugs", 0);
        value.put("restores_saved_priority", true);
        value.put("restores_psg_noise", false);
        value.set("writes", writes(40));
        return value;
    }

    private static ArrayNode writes(int firstOrdinal) {
        ArrayNode writes = JSON.createArrayNode();
        writes.add(write(firstOrdinal, 0x28));
        writes.add(write(firstOrdinal + 1, 0x2A));
        return writes;
    }

    private static ObjectNode write(int ordinal, int register) {
        ObjectNode value = JSON.createObjectNode();
        value.put("native_ordinal", ordinal);
        value.put("event_kind", 3);
        value.put("subject", 1);
        value.put("value", 0x7F);
        value.put("pc", 0x72B3E);
        value.put("source_cpu", 2);
        value.put("data", true);
        value.put("port", 0);
        value.put("register", register);
        return value;
    }

    private static ObjectNode pcm(String game) {
        ObjectNode value = JSON.createObjectNode();
        if (game.equals("s1")) value.put("type", "native_pcm_packet");
        value.put("selection", "service_frame");
        value.put("row", game.equals("s1") ? 3910 : 4000);
        value.put("offset", 0);
        value.put("sample_rate", 44100);
        value.put("channels", 2);
        value.put("format", "s16le-interleaved-stereo");
        value.put("stereo_frames", 1);
        value.put("byte_count", 4);
        value.put("pcm_hex", "0100ffff");
        value.put("sha256", "16b8cb1fe734fbc60c6763c94c9e4cc55840ae966e7e508ba82f539d82702511");
        return value;
    }

    private static ObjectNode validMetadata(String game, byte[] logical, byte[] stored)
            throws Exception {
        ObjectNode metadata = JSON.createObjectNode();
        metadata.put("schema", "openggf.override-resume-first-divergence-metadata.v1");
        metadata.put("game", game);
        metadata.putArray("raw_sha256").add("1".repeat(64)).add("1".repeat(64));
        metadata.put("raw_byte_count", 1);
        metadata.putArray("attestation_sha256").add("2".repeat(64)).add("2".repeat(64));
        metadata.put("record_count", 1);
        metadata.put("logical_byte_count", logical.length);
        metadata.put("logical_sha256", digest(logical));
        metadata.put("stored_byte_count", stored.length);
        metadata.put("stored_sha256", digest(stored));
        metadata.put("bundle_relative_root", "src/test/resources/audio/parity/"
                + OverrideResumeReferenceBundle.BUNDLE_NAME);
        ArrayNode inventory = metadata.putArray("bundle_member_inventory");
        OverrideResumeReferenceBundle.EXACT_INVENTORY.forEach(inventory::add);
        metadata.put("publication_protocol", "linux-atomic-bundle-rename-noreplace-v1");
        metadata.put("namespace_lock_precondition", NAMESPACE_PRECONDITION);
        return metadata;
    }

    private static void mutateReference(Path bundle, String game,
            Consumer<ObjectNode> mutation) throws Exception {
        Path directory = bundle.resolve(game);
        Path referencePath = directory.resolve(game + "-override-resume-reference.v1.jsonl.gz");
        Path metadataPath = directory.resolve(game + "-override-resume-metadata.v1.json");
        ObjectNode reference = (ObjectNode) JSON.readTree(gunzip(Files.readAllBytes(referencePath)));
        mutation.accept(reference);
        byte[] logical = jsonLine(reference);
        byte[] stored = gzip(logical);
        Files.write(referencePath, stored);
        ObjectNode metadata = (ObjectNode) JSON.readTree(Files.readAllBytes(metadataPath));
        metadata.put("logical_byte_count", logical.length);
        metadata.put("logical_sha256", digest(logical));
        metadata.put("stored_byte_count", stored.length);
        metadata.put("stored_sha256", digest(stored));
        Files.write(metadataPath, jsonLine(metadata));
    }

    private static void mutateMetadata(Path bundle, String game,
            Consumer<ObjectNode> mutation) throws Exception {
        Path metadataPath = bundle.resolve(game).resolve(
                game + "-override-resume-metadata.v1.json");
        ObjectNode metadata = (ObjectNode) JSON.readTree(Files.readAllBytes(metadataPath));
        mutation.accept(metadata);
        Files.write(metadataPath, jsonLine(metadata));
    }

    private static byte[] jsonLine(JsonNode value) throws Exception {
        return (JSON.writeValueAsString(value) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] gzip(byte[] logical) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(logical);
        }
        return output.toByteArray();
    }

    private static byte[] gunzip(byte[] stored) throws Exception {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(stored));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            gzip.transferTo(output);
            return output.toByteArray();
        }
    }

    private static String digest(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
