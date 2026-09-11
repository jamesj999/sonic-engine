package com.openggf.tools.audio.parity;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Fail-closed reader for the atomic override-resume fixture commit object.
 * Individual leaves and legacy game directories never confer authority.
 */
public final class OverrideResumeReferenceBundle {
    public static final String BUNDLE_NAME = "override-resume-first-divergence-v1";
    static final String LIMITATION_CODE =
            "FRESH_AUTHENTICATED_NATIVE_GPGX_AUTHORITY_UNAVAILABLE";
    static final List<String> EXACT_INVENTORY = List.of(
            "s1/s1-override-resume-reference.v1.jsonl.gz",
            "s1/s1-override-resume-metadata.v1.json",
            "s2/s2-override-resume-reference.v1.jsonl.gz",
            "s2/s2-override-resume-metadata.v1.json");
    private static final Set<String> METADATA_FIELDS = Set.of(
            "schema", "game", "raw_sha256", "raw_byte_count",
            "attestation_sha256", "record_count", "logical_byte_count",
            "logical_sha256", "stored_byte_count", "stored_sha256",
            "bundle_relative_root", "bundle_member_inventory",
            "publication_protocol", "namespace_lock_precondition");
    private static final Set<String> REFERENCE_FIELDS = Set.of(
            "schema", "game", "boundary", "pcm");
    private static final Set<String> S1_BOUNDARY_FIELDS = Set.of(
            "type", "request", "admission", "request_frame", "admission_frame",
            "frame", "pc", "service_token", "native_ordinal", "fix_bugs",
            "writes_dac_disable_zero", "writes");
    private static final Set<String> S2_BOUNDARY_FIELDS = Set.of(
            "request", "admission", "request_pc", "pc", "service_token",
            "service_begin_ordinal", "native_ordinal", "frame", "fix_driver_bugs",
            "restores_saved_priority", "restores_psg_noise", "writes");
    private static final Set<String> WRITE_FIELDS = Set.of(
            "native_ordinal", "event_kind", "subject", "value", "pc", "source_cpu",
            "data", "port", "register");
    private static final Set<String> S1_PCM_FIELDS = Set.of(
            "type", "selection", "row", "offset", "sample_rate", "channels", "format",
            "stereo_frames", "byte_count", "pcm_hex", "sha256");
    private static final Set<String> S2_PCM_FIELDS = Set.of(
            "selection", "row", "offset", "sample_rate", "channels", "format",
            "stereo_frames", "byte_count", "pcm_hex", "sha256");
    private static final String NAMESPACE_STABILITY_PRECONDITION =
            "All publishers cooperate through the exclusive fixture-root lock; "
                    + "the authoritative root and ancestors remain namespace-stable and "
                    + "protected from rename and mount mutation. Same-credential rename "
                    + "and mount mutation after validation is unsupported.";
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private static final int MAX_MEMBER_BYTES = 1024 * 1024;

    record GameReference(byte[] logicalBytes, JsonNode reference, JsonNode metadata) {
        GameReference {
            logicalBytes = logicalBytes.clone();
        }

        @Override
        public byte[] logicalBytes() {
            return logicalBytes.clone();
        }
    }

    public static final class ReferenceUnavailableException extends IOException {
        private final String code;

        ReferenceUnavailableException(String message) {
            super(message);
            code = LIMITATION_CODE;
        }

        public String code() {
            return code;
        }
    }

    static final class InvalidBundleException extends IOException {
        InvalidBundleException(String message) {
            super(message);
        }

        InvalidBundleException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final GameReference s1;
    private final GameReference s2;

    private OverrideResumeReferenceBundle(GameReference s1, GameReference s2) {
        this.s1 = s1;
        this.s2 = s2;
    }

    public static OverrideResumeReferenceBundle open(Path parityRoot) throws IOException {
        if (parityRoot == null || !parityRoot.isAbsolute()) {
            throw new InvalidBundleException("parity root must be absolute");
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parityRoot)) {
            if (!(stream instanceof SecureDirectoryStream<Path> root)) {
                throw new InvalidBundleException(
                        "platform does not provide secure directory traversal");
            }
            BasicFileAttributes attributes;
            try {
                attributes = attributes(root, Path.of(BUNDLE_NAME));
            } catch (NoSuchFileException missing) {
                throw new ReferenceUnavailableException(
                        LIMITATION_CODE + ": atomic reference bundle is absent");
            }
            if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
                throw new InvalidBundleException("bundle commit object is not a real directory");
            }
            try (SecureDirectoryStream<Path> bundle = root.newDirectoryStream(
                    Path.of(BUNDLE_NAME), LinkOption.NOFOLLOW_LINKS)) {
                requireInventory(bundle, Set.of("s1", "s2"), "bundle");
                GameReference s1 = readGame(bundle, "s1");
                GameReference s2 = readGame(bundle, "s2");
                return new OverrideResumeReferenceBundle(s1, s2);
            }
        } catch (ReferenceUnavailableException | InvalidBundleException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new InvalidBundleException("bundle commit validation failed", failure);
        }
    }

    GameReference s1() {
        return s1;
    }

    GameReference s2() {
        return s2;
    }

    List<String> memberInventory() {
        return EXACT_INVENTORY;
    }

    private static GameReference readGame(SecureDirectoryStream<Path> bundle,
            String game) throws IOException {
        BasicFileAttributes attributes = attributes(bundle, Path.of(game));
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
            throw new InvalidBundleException(game + " member is not a real directory");
        }
        try (SecureDirectoryStream<Path> directory = bundle.newDirectoryStream(
                Path.of(game), LinkOption.NOFOLLOW_LINKS)) {
            String referenceName = game + "-override-resume-reference.v1.jsonl.gz";
            String metadataName = game + "-override-resume-metadata.v1.json";
            requireInventory(directory, Set.of(referenceName, metadataName), game);
            byte[] stored = readRegular(directory, Path.of(referenceName));
            byte[] metadataBytes = readRegular(directory, Path.of(metadataName));
            byte[] logical = gunzip(stored);
            JsonNode reference = parseSingleLfRecord(logical, game + " reference");
            JsonNode metadata = parseSingleLfRecord(metadataBytes, game + " metadata");
            validate(game, stored, logical, reference, metadata);
            return new GameReference(logical, reference, metadata);
        }
    }

    private static void validate(String game, byte[] stored, byte[] logical,
            JsonNode reference, JsonNode metadata) throws IOException {
        requireExactFields(reference, REFERENCE_FIELDS, game + " reference");
        requireText(reference, "schema",
                "openggf.override-resume-first-divergence-reference.v1");
        requireText(reference, "game", game);
        JsonNode boundary = requireObject(reference, "boundary");
        JsonNode pcm = requireObject(reference, "pcm");
        validateBoundary(game, boundary);
        validatePcm(game, boundary, pcm);

        requireExactFields(metadata, METADATA_FIELDS, game + " metadata");
        requireText(metadata, "schema",
                "openggf.override-resume-first-divergence-metadata.v1");
        requireText(metadata, "game", game);
        requireText(metadata, "bundle_relative_root",
                "src/test/resources/audio/parity/" + BUNDLE_NAME);
        requireText(metadata, "publication_protocol",
                "linux-atomic-bundle-rename-noreplace-v1");
        requireText(metadata, "namespace_lock_precondition",
                NAMESPACE_STABILITY_PRECONDITION);
        requirePositiveLong(metadata, "raw_byte_count");
        List<String> inventory = new ArrayList<>();
        JsonNode inventoryNode = metadata.get("bundle_member_inventory");
        if (inventoryNode == null || !inventoryNode.isArray()) {
            throw new InvalidBundleException("metadata bundle inventory is not an array");
        }
        for (JsonNode member : inventoryNode) {
            if (!member.isTextual()) {
                throw new InvalidBundleException("metadata bundle inventory is not textual");
            }
            inventory.add(member.textValue());
        }
        if (!inventory.equals(EXACT_INVENTORY)) {
            throw new InvalidBundleException("metadata bundle inventory is not exact");
        }
        if (requireInt(metadata, "record_count") != 1
                || requirePositiveLong(metadata, "logical_byte_count") != logical.length
                || requirePositiveLong(metadata, "stored_byte_count") != stored.length
                || !requireDigest(metadata, "logical_sha256").equals(digest(logical))
                || !requireDigest(metadata, "stored_sha256").equals(digest(stored))) {
            throw new InvalidBundleException(game + " count or digest metadata disagrees");
        }
        requireDigestPair(metadata.path("raw_sha256"), "raw_sha256");
        requireDigestPair(metadata.path("attestation_sha256"), "attestation_sha256");
    }

    private static void validateBoundary(String game, JsonNode boundary)
            throws InvalidBundleException {
        requireExactFields(boundary, game.equals("s1")
                ? S1_BOUNDARY_FIELDS : S2_BOUNDARY_FIELDS, game + " boundary");
        requireText(boundary, "request", "cfFadeInToPrevious");
        requireNonNegativeInt(boundary, "frame");
        JsonNode writes = boundary.get("writes");
        if (writes == null || !writes.isArray() || writes.isEmpty()) {
            throw new InvalidBundleException(game + " resumed service owns no writes");
        }
        long previous = -1;
        for (JsonNode write : writes) {
            requireExactFields(write, WRITE_FIELDS, game + " write");
            long ordinal = requirePositiveLong(write, "native_ordinal");
            if (ordinal <= previous) {
                throw new InvalidBundleException(game + " chip writes are unordered");
            }
            previous = ordinal;
            requireNonNegativeInt(write, "event_kind");
            requireNonNegativeInt(write, "subject");
            requireRange(write, "value", 0, 255);
            requireNonNegativeInt(write, "pc");
            requireNonNegativeInt(write, "source_cpu");
            requireBoolean(write, "data");
            requireRange(write, "port", 0, 1);
            requireRange(write, "register", 0, 255);
        }
        if (game.equals("s1")) {
            requireText(boundary, "type", "override_resume");
            requireText(boundary, "admission", "native_restore_entry");
            requireNonNegativeInt(boundary, "request_frame");
            requireNonNegativeInt(boundary, "admission_frame");
            requireEqualInt(boundary, "pc", 0x72B14);
            requirePositiveLong(boundary, "service_token");
            requirePositiveLong(boundary, "native_ordinal");
            requireEqualInt(boundary, "fix_bugs", 0);
            if (requireBoolean(boundary, "writes_dac_disable_zero")) {
                throw new InvalidBundleException("S1 FixBugs=0 invented YM $2B=$00");
            }
            return;
        }
        requireText(boundary, "admission", "native_service_completion");
        requireEqualInt(boundary, "request_pc", 0x0D35);
        requireEqualInt(boundary, "pc", 0x0DB4);
        requirePositiveLong(boundary, "service_token");
        requirePositiveLong(boundary, "service_begin_ordinal");
        requirePositiveLong(boundary, "native_ordinal");
        requireEqualInt(boundary, "fix_driver_bugs", 0);
        if (!requireBoolean(boundary, "restores_saved_priority")
                || requireBoolean(boundary, "restores_psg_noise")) {
            throw new InvalidBundleException("S2 FixDriverBugs=0 restore semantics changed");
        }
    }

    private static void validatePcm(String game, JsonNode boundary, JsonNode pcm)
            throws IOException {
        requireExactFields(pcm, game.equals("s1") ? S1_PCM_FIELDS : S2_PCM_FIELDS,
                game + " PCM");
        if (game.equals("s1")) {
            requireText(pcm, "type", "native_pcm_packet");
        }
        String selection = requireString(pcm, "selection");
        int offset = requireInt(pcm, "offset");
        if (!(selection.equals("service_frame") && offset == 0)
                && !(selection.equals("following_row") && offset == 1)) {
            throw new InvalidBundleException("PCM packet timing is outside the exact eligible rows");
        }
        int row = requireNonNegativeInt(pcm, "row");
        int expectedRow;
        try {
            expectedRow = Math.addExact(requireInt(boundary, "frame"), offset);
        } catch (ArithmeticException failure) {
            throw new InvalidBundleException("PCM row overflows", failure);
        }
        if (row != expectedRow) {
            throw new InvalidBundleException("PCM row identity changed");
        }
        requireEqualInt(pcm, "sample_rate", 44100);
        requireEqualInt(pcm, "channels", 2);
        requireText(pcm, "format", "s16le-interleaved-stereo");
        int frames = requirePositiveInt(pcm, "stereo_frames");
        int byteCount = requireInt(pcm, "byte_count");
        String hex = requireString(pcm, "pcm_hex");
        byte[] pcmBytes = parseLowerHex(hex, "PCM bytes");
        int expectedBytes;
        try {
            expectedBytes = Math.multiplyExact(frames, 4);
        } catch (ArithmeticException failure) {
            throw new InvalidBundleException("PCM frame count overflows", failure);
        }
        if (byteCount != expectedBytes || pcmBytes.length != byteCount) {
            throw new InvalidBundleException("PCM packet byte/frame inventory is inconsistent");
        }
        if (!requireDigest(pcm, "sha256").equals(digest(pcmBytes))) {
            throw new InvalidBundleException("PCM digest identity changed");
        }
    }

    private static void requireDigestPair(JsonNode value, String label)
            throws InvalidBundleException {
        if (!value.isArray() || value.size() != 2) {
            throw new InvalidBundleException(label + " is not an exact pair");
        }
        for (JsonNode digest : value) {
            if (!digest.isTextual() || !digest.asText().matches("[0-9a-f]{64}")) {
                throw new InvalidBundleException(label + " contains an invalid digest");
            }
        }
    }

    private static String requireDigest(JsonNode object, String field)
            throws InvalidBundleException {
        String value = requireString(object, field);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new InvalidBundleException(field + " is not a lowercase SHA-256 digest");
        }
        return value;
    }

    private static byte[] parseLowerHex(String value, String label)
            throws InvalidBundleException {
        if (!value.matches("(?:[0-9a-f]{2})+")) {
            throw new InvalidBundleException(label + " is not nonempty even lowercase hex");
        }
        return HexFormat.of().parseHex(value);
    }

    private static JsonNode requireObject(JsonNode object, String field)
            throws InvalidBundleException {
        JsonNode value = object.get(field);
        if (value == null || !value.isObject()) {
            throw new InvalidBundleException(field + " is not an object");
        }
        return value;
    }

    private static String requireString(JsonNode object, String field)
            throws InvalidBundleException {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.textValue().isEmpty()) {
            throw new InvalidBundleException(field + " is not a nonempty string");
        }
        return value.textValue();
    }

    private static int requireInt(JsonNode object, String field)
            throws InvalidBundleException {
        JsonNode value = object.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new InvalidBundleException(field + " is not an exact integer");
        }
        return value.intValue();
    }

    private static int requireNonNegativeInt(JsonNode object, String field)
            throws InvalidBundleException {
        int value = requireInt(object, field);
        if (value < 0) {
            throw new InvalidBundleException(field + " must be nonnegative");
        }
        return value;
    }

    private static int requirePositiveInt(JsonNode object, String field)
            throws InvalidBundleException {
        int value = requireInt(object, field);
        if (value < 1) {
            throw new InvalidBundleException(field + " must be positive");
        }
        return value;
    }

    private static long requirePositiveLong(JsonNode object, String field)
            throws InvalidBundleException {
        JsonNode value = object.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < 1) {
            throw new InvalidBundleException(field + " must be a positive exact integer");
        }
        return value.longValue();
    }

    private static void requireEqualInt(JsonNode object, String field, int expected)
            throws InvalidBundleException {
        if (requireInt(object, field) != expected) {
            throw new InvalidBundleException(field + " identity changed");
        }
    }

    private static void requireRange(JsonNode object, String field, int minimum, int maximum)
            throws InvalidBundleException {
        int value = requireInt(object, field);
        if (value < minimum || value > maximum) {
            throw new InvalidBundleException(field + " is out of range");
        }
    }

    private static boolean requireBoolean(JsonNode object, String field)
            throws InvalidBundleException {
        JsonNode value = object.get(field);
        if (value == null || !value.isBoolean()) {
            throw new InvalidBundleException(field + " is not a boolean");
        }
        return value.booleanValue();
    }

    private static void requireText(JsonNode object, String field, String expected)
            throws InvalidBundleException {
        if (!requireString(object, field).equals(expected)) {
            throw new InvalidBundleException(field + " is invalid");
        }
    }

    private static void requireExactFields(JsonNode object, Set<String> expected,
            String label) throws InvalidBundleException {
        if (!object.isObject()) {
            throw new InvalidBundleException(label + " is not an object");
        }
        Set<String> actual = new LinkedHashSet<>();
        object.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw new InvalidBundleException(label + " schema inventory is not exact");
        }
    }

    private static void requireInventory(SecureDirectoryStream<Path> directory,
            Set<String> expected, String label) throws IOException {
        Set<String> actual = new LinkedHashSet<>();
        for (Path entry : directory) {
            actual.add(entry.getFileName().toString());
        }
        if (!actual.equals(expected)) {
            throw new InvalidBundleException(label + " inventory is not exact: " + actual);
        }
    }

    private static BasicFileAttributes attributes(SecureDirectoryStream<Path> directory,
            Path name) throws IOException {
        BasicFileAttributeView view = directory.getFileAttributeView(name,
                BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        return view.readAttributes();
    }

    private static byte[] readRegular(SecureDirectoryStream<Path> directory,
            Path name) throws IOException {
        BasicFileAttributes attributes = attributes(directory, name);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                || attributes.size() <= 0 || attributes.size() > MAX_MEMBER_BYTES) {
            throw new InvalidBundleException(name + " is not a bounded regular member");
        }
        Set<OpenOption> options = Set.of(StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS);
        try (SeekableByteChannel channel = directory.newByteChannel(name, options)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (channel.read(buffer) >= 0) {
                buffer.flip();
                output.write(buffer.array(), 0, buffer.remaining());
                buffer.clear();
                if (output.size() > MAX_MEMBER_BYTES) {
                    throw new InvalidBundleException(name + " exceeds the size bound");
                }
            }
            return output.toByteArray();
        }
    }

    private static byte[] gunzip(byte[] stored) throws IOException {
        if (stored.length < 18 || stored[0] != 0x1f || stored[1] != (byte) 0x8b) {
            throw new InvalidBundleException("reference is not gzip");
        }
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(stored));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            gzip.transferTo(output);
            if (output.size() > MAX_MEMBER_BYTES) {
                throw new InvalidBundleException("logical reference exceeds size bound");
            }
            return output.toByteArray();
        }
    }

    private static JsonNode parseSingleLfRecord(byte[] bytes, String label)
            throws IOException {
        if (bytes.length == 0 || bytes[bytes.length - 1] != '\n') {
            throw new InvalidBundleException(label + " is not LF-terminated");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (Exception failure) {
            throw new InvalidBundleException(label + " is not strict UTF-8", failure);
        }
        if (text.indexOf('\r') >= 0 || text.indexOf('\n') != text.length() - 1) {
            throw new InvalidBundleException(label + " is not one LF record");
        }
        try {
            return JSON.readTree(text.substring(0, text.length() - 1));
        } catch (IOException failure) {
            throw new InvalidBundleException(label + " is not valid JSON", failure);
        }
    }

    private static String digest(byte[] value) throws IOException {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception failure) {
            throw new IOException("SHA-256 unavailable", failure);
        }
    }
}
