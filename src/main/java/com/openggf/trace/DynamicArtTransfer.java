package com.openggf.trace;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable comparison-only model and validators for the native
 * {@code dynamic_art_transfer_state_per_frame} contract.
 */
public final class DynamicArtTransfer {

    private static final Set<String> OWNERS = Set.of(
            "sonic", "tails", "tails-tails",
            "ss-sonic", "ss-tails", "ss-tails-tails");
    private static final String SHA256_PREFIX = "sha256:";

    private DynamicArtTransfer() {
    }

    /**
     * Run-wide identity state. Edge ordinals are strictly increasing in
     * publication order and a transfer id is allocated at most once, even
     * after its transfer has completed.
     */
    public static final class LifecycleIdentity {
        private final Set<Long> observedEdgeOrdinals;
        private final Set<Long> submittedTransferIds = new HashSet<>();
        private long previousEdgeOrdinal;

        public LifecycleIdentity() {
            this(new HashSet<>());
        }

        private LifecycleIdentity(Set<Long> observedEdgeOrdinals) {
            this.observedEdgeOrdinals = observedEdgeOrdinals;
            this.previousEdgeOrdinal = -1L;
        }

        private void observeEdgeOrdinal(long edgeOrdinal) {
            if (edgeOrdinal <= previousEdgeOrdinal
                    || !observedEdgeOrdinals.add(edgeOrdinal)) {
                throw new IllegalArgumentException(
                        "edge_ordinal must be strictly increasing: "
                                + edgeOrdinal + " follows "
                                + previousEdgeOrdinal);
            }
            previousEdgeOrdinal = edgeOrdinal;
        }

        private void observeSubmission(long transferId) {
            if (!submittedTransferIds.add(transferId)) {
                throw new IllegalArgumentException(
                        "transfer_id must not be reused: " + transferId);
            }
        }

        private void rememberExistingSubmission(long transferId) {
            submittedTransferIds.add(transferId);
        }
    }

    public record Request(
            int romSourceAddress,
            int sourceTileIndex,
            int ramSourceAddress,
            int vramDestination,
            int byteLength) {
        public Request {
            boolean romBacked = romSourceAddress >= 0
                    && sourceTileIndex >= 0
                    && ramSourceAddress == -1;
            boolean ramBacked = romSourceAddress == -1
                    && sourceTileIndex == -1
                    && ramSourceAddress >= 0;
            if (!romBacked && !ramBacked) {
                throw new IllegalArgumentException(
                        "request must select exactly one source domain");
            }
            if (romSourceAddress > 0xFFFFFF || ramSourceAddress > 0xFFFFFF) {
                throw new IllegalArgumentException("source address exceeds 24-bit domain");
            }
            if (vramDestination < 0 || vramDestination > 0xFFFF) {
                throw new IllegalArgumentException(
                        "vram_destination outside unsigned 16-bit domain");
            }
            if (byteLength <= 0) {
                throw new IllegalArgumentException("byte_length must be positive");
            }
        }

        public boolean romBacked() {
            return romSourceAddress >= 0;
        }

        public boolean ramBacked() {
            return ramSourceAddress >= 0;
        }
    }

    public record Descriptor(
            long transferId,
            String owner,
            int mappingFrame,
            String submissionOrigin,
            List<Request> requests,
            String fingerprint) {
        public Descriptor {
            requireNonnegative(transferId, "transfer_id");
            validateOwner(owner);
            requireNonnegative(mappingFrame, "mapping_frame");
            validateOrigin(submissionOrigin);
            requests = validateRequests(requests, true);
            String computed = DynamicArtTransfer.fingerprint(new DescriptorPayload(
                    transferId, owner, mappingFrame, submissionOrigin, requests));
            if (fingerprint == null) {
                fingerprint = computed;
            } else {
                validateFingerprint(fingerprint, "fingerprint");
                if (!fingerprint.equals(computed)) {
                    throw new IllegalArgumentException(
                            "descriptor fingerprint does not match descriptor");
                }
            }
        }
    }

    private record DescriptorPayload(
            long transferId,
            String owner,
            int mappingFrame,
            String submissionOrigin,
            List<Request> requests) {
    }

    public record SegmentEdge(
            long edgeOrdinal,
            long transferId,
            String phase,
            String owner,
            String submissionOrigin,
            int mappingFrame,
            int logicalFrame,
            int logicalEdgeIndex,
            int publicationFrame,
            boolean terminalForwarded,
            int romCallbackPc,
            List<Request> requests) {
        public SegmentEdge {
            validateEdge(edgeOrdinal, transferId, phase, owner,
                    submissionOrigin, mappingFrame, romCallbackPc, requests);
            requireNonnegative(logicalFrame, "logical_frame");
            requireNonnegative(logicalEdgeIndex, "logical_edge_index");
            requireNonnegative(publicationFrame, "publication_frame");
            if (!"segment".equals(submissionOrigin)
                    && !("completed".equals(phase)
                    && "run_gap".equals(submissionOrigin))) {
                throw new IllegalArgumentException(
                        "segment edge must be segment-owned or complete inherited run-gap work");
            }
            requests = validateRequests(requests, "submitted".equals(phase));
        }

        public Descriptor submissionDescriptor() {
            return new Descriptor(transferId, owner, mappingFrame,
                    submissionOrigin, requests, null);
        }

        /**
         * Exact replay-comparison payload. The ROM callback PC is intentionally
         * absent: loading validates it against the pinned profile, while the
         * engine has no corresponding ROM address to compare.
         */
        public ComparisonEdge comparisonView() {
            return new ComparisonEdge(edgeOrdinal, transferId, phase, owner,
                    submissionOrigin, mappingFrame, logicalFrame,
                    logicalEdgeIndex, publicationFrame, terminalForwarded,
                    requests);
        }
    }

    public record ComparisonEdge(
            long edgeOrdinal,
            long transferId,
            String phase,
            String owner,
            String submissionOrigin,
            int mappingFrame,
            int logicalFrame,
            int logicalEdgeIndex,
            int publicationFrame,
            boolean terminalForwarded,
            List<Request> requests) {
        public ComparisonEdge {
            requests = List.copyOf(requests);
        }
    }

    public record GapEdge(
            long edgeOrdinal,
            long transferId,
            String phase,
            String owner,
            String submissionOrigin,
            int mappingFrame,
            int movieLogicalFrame,
            int gapEdgeIndex,
            int romCallbackPc,
            List<Request> requests) {
        public GapEdge {
            validateEdge(edgeOrdinal, transferId, phase, owner,
                    submissionOrigin, mappingFrame, romCallbackPc, requests);
            requireNonnegative(movieLogicalFrame, "movie_logical_frame");
            requireNonnegative(gapEdgeIndex, "gap_edge_index");
            if ("submitted".equals(phase) && !"run_gap".equals(submissionOrigin)) {
                throw new IllegalArgumentException(
                        "gap submission submission_origin must be run_gap");
            }
            requests = validateRequests(requests, "submitted".equals(phase));
        }

        public Descriptor submissionDescriptor() {
            return new Descriptor(transferId, owner, mappingFrame,
                    submissionOrigin, requests, null);
        }
    }

    public record GapTransition(
            GapEdge dynamicArtGapEdge,
            String beforeLedgerHash,
            List<Descriptor> afterLedgerDescriptors) {
        public GapTransition {
            if (dynamicArtGapEdge == null) {
                throw new IllegalArgumentException("missing dynamic_art_gap_edge");
            }
            validateFingerprint(beforeLedgerHash, "before_ledger_hash");
            afterLedgerDescriptors = List.copyOf(afterLedgerDescriptors);
            Set<Long> ids = new HashSet<>();
            for (Descriptor descriptor : afterLedgerDescriptors) {
                if (descriptor == null || !ids.add(descriptor.transferId())) {
                    throw new IllegalArgumentException(
                            "after_ledger_descriptors has null or duplicate transfer_id");
                }
            }
        }
    }

    public static Descriptor parseDescriptor(JsonNode node) {
        return new Descriptor(requiredLong(node, "transfer_id"),
                requiredText(node, "owner"), requiredInt(node, "mapping_frame"),
                requiredText(node, "submission_origin"),
                parseRequests(node), requiredText(node, "fingerprint"));
    }

    public static SegmentEdge parseSegmentEdge(JsonNode node) {
        for (String forbidden : List.of(
                "movie_logical_frame", "gap_edge_index")) {
            if (node.has(forbidden)) {
                throw new IllegalArgumentException(
                        "segment edge must not contain gap field " + forbidden);
            }
        }
        return new SegmentEdge(requiredLong(node, "edge_ordinal"),
                requiredLong(node, "transfer_id"), requiredText(node, "phase"),
                requiredText(node, "owner"),
                requiredText(node, "submission_origin"),
                requiredInt(node, "mapping_frame"),
                requiredInt(node, "logical_frame"),
                requiredInt(node, "logical_edge_index"),
                requiredInt(node, "publication_frame"),
                requiredBoolean(node, "terminal_forwarded"),
                requiredInt(node, "rom_callback_pc"), parseRequests(node));
    }

    public static GapTransition parseGapTransition(JsonNode node) {
        JsonNode edgeNode = requiredObject(node, "dynamic_art_gap_edge");
        for (String forbidden : List.of(
                "logical_frame", "logical_edge_index",
                "publication_frame", "terminal_forwarded")) {
            if (edgeNode.has(forbidden)) {
                throw new IllegalArgumentException(
                        "gap edge must not contain segment field " + forbidden);
            }
        }
        GapEdge edge = new GapEdge(requiredLong(edgeNode, "edge_ordinal"),
                requiredLong(edgeNode, "transfer_id"),
                requiredText(edgeNode, "phase"),
                requiredText(edgeNode, "owner"),
                requiredText(edgeNode, "submission_origin"),
                requiredInt(edgeNode, "mapping_frame"),
                requiredInt(edgeNode, "movie_logical_frame"),
                requiredInt(edgeNode, "gap_edge_index"),
                requiredInt(edgeNode, "rom_callback_pc"),
                parseRequests(edgeNode));
        List<Descriptor> descriptors = new ArrayList<>();
        for (JsonNode descriptor : requiredArray(node, "after_ledger_descriptors")) {
            descriptors.add(parseDescriptor(descriptor));
        }
        return new GapTransition(edge, requiredText(node, "before_ledger_hash"),
                descriptors);
    }

    public static List<Descriptor> validateSegment(
            List<TraceEvent.DynamicArtTransferState> envelopes,
            StoredPhysicsFrameDomain domain,
            String game,
            LifecycleIdentity identity) {
        return validateSegment(envelopes, domain, game, identity, List.of());
    }

    public static List<Descriptor> validateSegment(
            List<TraceEvent.DynamicArtTransferState> envelopes,
            StoredPhysicsFrameDomain domain,
            String game,
            LifecycleIdentity identity,
            List<Descriptor> openingLedger) {
        if (envelopes.size() != domain.frames().size()) {
            throw new IllegalArgumentException(
                    "incomplete dynamic-art state: expected "
                            + domain.frames().size() + " rows but found "
                            + envelopes.size());
        }
        List<Descriptor> ledger = new ArrayList<>(openingLedger);
        openingLedger.forEach(descriptor ->
                identity.rememberExistingSubmission(descriptor.transferId()));
        int previousLogicalFrame = -1;
        int nextLogicalIndex = 0;
        for (int index = 0; index < envelopes.size(); index++) {
            TraceEvent.DynamicArtTransferState envelope = envelopes.get(index);
            int expectedFrame = domain.frames().get(index);
            if (envelope.frame() != expectedFrame) {
                throw new IllegalArgumentException(
                        "dynamic-art envelope frame mismatch: expected "
                                + expectedFrame + " but found " + envelope.frame());
            }
            for (SegmentEdge edge : envelope.edges()) {
                if (edge.publicationFrame() != envelope.frame()) {
                    throw new IllegalArgumentException(
                            "edge publication_frame must equal envelope frame");
                }
                if (edge.terminalForwarded()
                        && envelope.frame() != domain.lastFrame()) {
                    throw new IllegalArgumentException(
                            "terminal_forwarded edge is not on the final stored row");
                }
                validateCallback(game, edge.phase(), edge.romCallbackPc());
                identity.observeEdgeOrdinal(edge.edgeOrdinal());
                if (edge.logicalFrame() != previousLogicalFrame) {
                    if (edge.logicalFrame() <= previousLogicalFrame) {
                        throw new IllegalArgumentException(
                                "segment logical frames must be strictly increasing");
                    }
                    previousLogicalFrame = edge.logicalFrame();
                    nextLogicalIndex = 0;
                }
                if (edge.logicalEdgeIndex() != nextLogicalIndex) {
                    throw new IllegalArgumentException(
                            "logical_edge_index must be zero-based and contiguous");
                }
                nextLogicalIndex++;
                applyEdge(ledger, edge.phase(), edge.transferId(), edge.owner(),
                        edge.mappingFrame(), edge.submissionOrigin(), edge.requests(),
                        edge.submissionDescriptor(), game, identity);
            }
            validateLedgerIds(ledger, envelope.outstandingTransferIds());
        }
        return List.copyOf(ledger);
    }

    public static List<Descriptor> validateGaps(
            List<GapTransition> transitions,
            List<Descriptor> openingLedger,
            String game,
            LifecycleIdentity identity) {
        List<Descriptor> ledger = new ArrayList<>(openingLedger);
        openingLedger.forEach(descriptor ->
                identity.rememberExistingSubmission(descriptor.transferId()));
        int previousMovieFrame = -1;
        int nextGapIndex = 0;
        for (GapTransition transition : transitions) {
            GapEdge edge = transition.dynamicArtGapEdge();
            validateCallback(game, edge.phase(), edge.romCallbackPc());
            identity.observeEdgeOrdinal(edge.edgeOrdinal());
            if (edge.movieLogicalFrame() != previousMovieFrame) {
                if (edge.movieLogicalFrame() <= previousMovieFrame) {
                    throw new IllegalStateException(
                            "gap movie frames must be strictly increasing");
                }
                previousMovieFrame = edge.movieLogicalFrame();
                nextGapIndex = 0;
            }
            if (edge.gapEdgeIndex() != nextGapIndex) {
                throw new IllegalStateException(
                        "gap_edge_index must be zero-based and contiguous");
            }
            nextGapIndex++;
            if (!ledgerHash(ledger).equals(transition.beforeLedgerHash())) {
                throw new IllegalStateException(
                        "before_ledger_hash does not match pending ledger");
            }
            applyEdge(ledger, edge.phase(), edge.transferId(), edge.owner(),
                    edge.mappingFrame(), edge.submissionOrigin(), edge.requests(),
                    edge.submissionDescriptor(), game, identity);
            if (!descriptorsMatch(ledger, transition.afterLedgerDescriptors())) {
                throw new IllegalStateException(
                        "after_ledger_descriptors does not match pending ledger");
            }
        }
        return List.copyOf(ledger);
    }

    public static String fingerprint(Descriptor descriptor) {
        return fingerprint(new DescriptorPayload(descriptor.transferId(),
                descriptor.owner(), descriptor.mappingFrame(),
                descriptor.submissionOrigin(), descriptor.requests()));
    }

    private static String fingerprint(DescriptorPayload descriptor) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeBytes("ODAT");
                output.writeByte(1);
                output.writeLong(descriptor.transferId());
                writeUtf8(output, descriptor.owner());
                output.writeInt(descriptor.mappingFrame());
                output.writeByte("segment".equals(descriptor.submissionOrigin()) ? 0 : 1);
                output.writeInt(descriptor.requests().size());
                for (Request request : descriptor.requests()) {
                    output.writeInt(request.romSourceAddress());
                    output.writeInt(request.sourceTileIndex());
                    output.writeInt(request.ramSourceAddress());
                    output.writeInt(request.vramDestination());
                    output.writeInt(request.byteLength());
                }
            }
            return sha256(bytes.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("unexpected in-memory fingerprint error", e);
        }
    }

    public static String ledgerHash(List<Descriptor> descriptors) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeBytes("ODAL");
                output.writeByte(1);
                output.writeInt(descriptors.size());
                for (Descriptor descriptor : descriptors) {
                    writeUtf8(output, descriptor.fingerprint());
                }
            }
            return sha256(bytes.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("unexpected in-memory ledger hash error", e);
        }
    }

    private static void applyEdge(List<Descriptor> ledger, String phase,
            long transferId, String owner, int mappingFrame, String origin,
            List<Request> requests, Descriptor submission, String game,
            LifecycleIdentity identity) {
        if ("submitted".equals(phase)) {
            identity.observeSubmission(transferId);
            if (ledger.stream().anyMatch(value -> value.transferId() == transferId)) {
                throw new IllegalArgumentException(
                        "duplicate transfer_id " + transferId);
            }
            if ("s1".equals(game)
                    && requests.stream().anyMatch(request -> !request.romBacked())) {
                throw new IllegalArgumentException(
                        "S1 submissions must be ROM-backed");
            }
            ledger.add(submission);
            return;
        }
        for (int index = 0; index < ledger.size(); index++) {
            Descriptor descriptor = ledger.get(index);
            if (descriptor.transferId() != transferId) {
                continue;
            }
            if (!descriptor.owner().equals(owner)
                    || descriptor.mappingFrame() != mappingFrame
                    || !descriptor.submissionOrigin().equals(origin)) {
                throw new IllegalArgumentException(
                        "completion does not match submission descriptor");
            }
            if ("s1".equals(game)) {
                Request request = requests.getFirst();
                if (requests.size() != 1
                        || !request.ramBacked()
                        || request.ramSourceAddress() != 0xC800
                        || request.vramDestination() != 0xF000
                        || request.byteLength() != 0x2E0) {
                    throw new IllegalArgumentException(
                            "S1 staging-buffer completion does not match profile");
                }
            } else if (!descriptor.requests().equals(requests)) {
                throw new IllegalArgumentException(
                        "completion requests do not match submitted batch");
            }
            ledger.remove(index);
            return;
        }
        throw new IllegalArgumentException(
                "completion without submission " + transferId);
    }

    private static boolean descriptorsMatch(
            List<Descriptor> expected, List<Descriptor> actual) {
        if (expected.size() != actual.size()) {
            return false;
        }
        for (int index = 0; index < expected.size(); index++) {
            if (expected.get(index).transferId() != actual.get(index).transferId()
                    || !expected.get(index).fingerprint()
                    .equals(actual.get(index).fingerprint())) {
                return false;
            }
        }
        return true;
    }

    private static void validateLedgerIds(
            List<Descriptor> ledger, List<Long> ids) {
        if (ledger.size() != ids.size()) {
            throw new IllegalArgumentException(
                    "outstanding_transfer_ids does not match pending ledger");
        }
        for (int index = 0; index < ledger.size(); index++) {
            if (ledger.get(index).transferId() != ids.get(index)) {
                throw new IllegalArgumentException(
                        "outstanding_transfer_ids does not match pending ledger");
            }
        }
    }

    private static void validateEdge(long edgeOrdinal, long transferId,
            String phase, String owner, String origin, int mappingFrame,
            int callbackPc, List<Request> requests) {
        requireNonnegative(edgeOrdinal, "edge_ordinal");
        requireNonnegative(transferId, "transfer_id");
        if (!"submitted".equals(phase) && !"completed".equals(phase)) {
            throw new IllegalArgumentException("unknown lifecycle phase");
        }
        validateOwner(owner);
        validateOrigin(origin);
        requireNonnegative(mappingFrame, "mapping_frame");
        if (callbackPc < 0 || callbackPc > 0xFFFFFF) {
            throw new IllegalArgumentException(
                    "rom_callback_pc outside 24-bit ROM domain");
        }
        validateRequests(requests, "submitted".equals(phase));
    }

    /**
     * Games for which a dynamic-art capability profile exists.
     *
     * <p>{@link #validateCallback} pins a ROM-callback PC set per game and
     * rejects any other game outright, so a trace can only legitimately carry
     * the capability for a game listed here. There is no S3K dynamic-art
     * observer on the recording side either -- {@code tools/tracechaser/bizhawk-headless}
     * ships {@code S1DynamicArtObserver} and {@code S2DynamicArtObserver} and no
     * S3K equivalent -- so an S3K trace can neither be produced with the
     * capability nor parsed with it.
     *
     * <p>This is the single source of truth for that support matrix. Anything
     * that REQUIRES the capability must consult it, or it demands something the
     * parser refuses.
     */
    public static boolean supportsCapability(String game) {
        return "s1".equals(game) || "s2".equals(game);
    }

    private static void validateCallback(String game, String phase, int pc) {
        Set<Integer> allowed = switch (game) {
            case "s1" -> "submitted".equals(phase)
                    ? Set.of(0x0D20, 0x0E34, 0x0F24, 0x1030)
                    : Set.of(0x0D50, 0x0E64, 0x0F54, 0x1060);
            case "s2" -> "submitted".equals(phase)
                    ? Set.of(0x14AA, 0x1B89A, 0x1D1FE, 0x33B3E, 0x34B1A)
                    : Set.of(0x14AC);
            default -> throw new IllegalArgumentException(
                    "dynamic-art capability unsupported for game " + game);
        };
        if (!allowed.contains(pc)) {
            throw new IllegalArgumentException(
                    "rom_callback_pc is not permitted by the pinned " + game + " profile");
        }
    }

    private static List<Request> validateRequests(
            List<Request> values, boolean submission) {
        List<Request> requests = List.copyOf(values);
        if (requests.isEmpty()) {
            throw new IllegalArgumentException("requests must not be empty");
        }
        if (submission) {
            boolean romBacked = requests.getFirst().romBacked();
            if (requests.stream().anyMatch(value -> value.romBacked() != romBacked)) {
                throw new IllegalArgumentException(
                        "submission requests must use one source domain");
            }
        } else {
            boolean ramBacked = requests.getFirst().ramBacked();
            if (requests.stream().anyMatch(value -> value.ramBacked() != ramBacked)) {
                throw new IllegalArgumentException(
                        "completion requests must use one source domain");
            }
        }
        return requests;
    }

    private static void validateOwner(String owner) {
        if (!OWNERS.contains(owner)) {
            throw new IllegalArgumentException("unknown dynamic-art owner");
        }
    }

    private static void validateOrigin(String origin) {
        if (!"segment".equals(origin) && !"run_gap".equals(origin)) {
            throw new IllegalArgumentException("unknown submission_origin");
        }
    }

    private static List<Request> parseRequests(JsonNode node) {
        List<Request> requests = new ArrayList<>();
        for (JsonNode request : requiredArray(node, "requests")) {
            requests.add(new Request(
                    requiredInt(request, "rom_source_address"),
                    requiredInt(request, "source_tile_index"),
                    requiredInt(request, "ram_source_address"),
                    requiredInt(request, "vram_destination"),
                    requiredInt(request, "byte_length")));
        }
        return requests;
    }

    static JsonNode requiredArray(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException(
                    "missing or invalid array field: " + field);
        }
        return value;
    }

    private static JsonNode requiredObject(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(
                    "missing or invalid object field: " + field);
        }
        return value;
    }

    static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(
                    "missing or invalid text field: " + field);
        }
        return value.asText();
    }

    static boolean requiredBoolean(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw new IllegalArgumentException(
                    "missing or invalid boolean field: " + field);
        }
        return value.asBoolean();
    }

    static int requiredInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(
                    "missing or invalid integer field: " + field);
        }
        return value.asInt();
    }

    static long requiredLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException(
                    "missing or invalid integer field: " + field);
        }
        return value.asLong();
    }

    static void validateFingerprint(String value, String field) {
        if (value == null
                || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    field + " must be a lowercase sha256 fingerprint");
        }
    }

    private static void requireNonnegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must be nonnegative");
        }
    }

    private static void writeUtf8(DataOutputStream output, String value)
            throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static String sha256(byte[] payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
            return SHA256_PREFIX + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
