package com.openggf.game.timing;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** Session-owned exact profiled-load lookup with warn-once immediate fallback. */
public final class ProfiledLoadTimeManifest implements LoadTimeProfile {
    private static final ObjectMapper MAPPER = new ObjectMapper(
            new JsonFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION));

    private final String serviceModel;
    private final Map<Key, LoadTimeDecision> decisions;
    private final Estimator estimator;
    private final Consumer<String> warningSink;
    private final Set<Key> warnedMissing = new HashSet<>();

    private ProfiledLoadTimeManifest(
            String serviceModel,
            Map<Key, LoadTimeDecision> decisions,
            Estimator estimator,
            Consumer<String> warningSink) {
        this.serviceModel = serviceModel;
        this.decisions = Map.copyOf(decisions);
        this.estimator = estimator;
        this.warningSink = warningSink;
    }

    public static ProfiledLoadTimeManifest load(
            InputStream input, Consumer<String> warningSink) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(warningSink, "warningSink");
        JsonNode root;
        try (JsonParser parser = MAPPER.getFactory().createParser(input)) {
            root = MAPPER.readTree(parser);
        }
        requireOnly(root, Set.of(
                "formatVersion", "profile", "serviceModel",
                "fixtures", "estimator", "entries"), "manifest");
        if (requiredInt(root, "formatVersion") != 1) {
            throw new IllegalArgumentException("unsupported load-time manifest version");
        }
        requiredText(root, "profile");
        String serviceModel = requiredText(root, "serviceModel");
        Estimator estimator = parseEstimator(root.get("estimator"));
        JsonNode fixtures = requiredArray(root, "fixtures");
        for (JsonNode fixture : fixtures) {
            if (!fixture.isTextual()) {
                throw new IllegalArgumentException("fixture must be a string");
            }
        }

        Map<Key, LoadTimeDecision> decisions = new HashMap<>();
        for (JsonNode entry : requiredArray(root, "entries")) {
            requireOnly(entry, Set.of(
                    "kind", "submissionFingerprint", "serviceFrames",
                    "eligibleBoundaries", "sampleCount", "minFrames",
                    "maxFrames", "fixtureIndexes"), "manifest entry");
            HardwareWorkKind kind = HardwareWorkKind.fromWireName(
                    requiredText(entry, "kind"));
            String fingerprint = requiredText(entry, "submissionFingerprint");
            int frames = requiredInt(entry, "serviceFrames");
            int sampleCount = requiredInt(entry, "sampleCount");
            int minFrames = requiredInt(entry, "minFrames");
            int maxFrames = requiredInt(entry, "maxFrames");
            if (sampleCount <= 0 || minFrames < 0
                    || maxFrames < minFrames
                    || frames < minFrames || frames > maxFrames) {
                throw new IllegalArgumentException("invalid load-time entry statistics");
            }
            EnumSet<HardwareServiceBoundary> boundaries =
                    EnumSet.noneOf(HardwareServiceBoundary.class);
            for (JsonNode boundary : requiredArray(entry, "eligibleBoundaries")) {
                boundaries.add(HardwareServiceBoundary.fromWireName(boundary.asText()));
            }
            for (JsonNode fixtureIndex : requiredArray(entry, "fixtureIndexes")) {
                if (!fixtureIndex.canConvertToInt()
                        || fixtureIndex.intValue() < 0
                        || fixtureIndex.intValue() >= fixtures.size()) {
                    throw new IllegalArgumentException("invalid fixture index");
                }
            }
            Key key = new Key(kind, fingerprint);
            LoadTimeDecision previous = decisions.put(
                    key,
                    new LoadTimeDecision(
                            frames,
                            boundaries,
                            LoadTimeDecisionSource.MEASURED,
                            serviceModel));
            if (previous != null) {
                throw new IllegalArgumentException(
                        "duplicate load-time manifest key: " + key);
            }
        }
        return new ProfiledLoadTimeManifest(
                serviceModel, decisions, estimator, warningSink);
    }

    @Override
    public LoadTimeDecision assign(
            HardwareWorkSubmission submission, HardwareWorkHandle handle) {
        Objects.requireNonNull(handle, "handle");
        Key key = new Key(handle.kind(), handle.submissionFingerprint());
        LoadTimeDecision decision = decisions.get(key);
        if (decision != null) {
            return decision;
        }
        if (estimator != null
                && submission != null
                && submission.kind() == HardwareWorkKind.KOS_DECOMPRESSION_QUEUE) {
            if (warnedMissing.add(key)) {
                warningSink.accept(
                        "No measured " + serviceModel + " load-time profile for "
                                + handle.kind() + " "
                                + handle.submissionFingerprint()
                                + "; using deterministic estimate");
            }
            return new LoadTimeDecision(
                    estimator.predict(submission.features()),
                    EnumSet.of(HardwareServiceBoundary.PRE_MAIN_LOOP),
                    LoadTimeDecisionSource.ESTIMATED,
                    serviceModel);
        }
        if (warnedMissing.add(key)) {
            warningSink.accept(
                    "No " + serviceModel + " load-time profile for "
                            + handle.kind() + " " + handle.submissionFingerprint()
                            + "; using immediate readiness");
        }
        return LoadTimeProfile.IMMEDIATE.assign(submission, handle);
    }

    private static String requiredText(JsonNode parent, String name) {
        JsonNode node = parent.get(name);
        if (node == null || !node.isTextual() || node.textValue().isBlank()) {
            throw new IllegalArgumentException(name + " must be a nonblank string");
        }
        return node.textValue();
    }

    private static int requiredInt(JsonNode parent, String name) {
        JsonNode node = parent.get(name);
        if (node == null || !node.canConvertToInt()) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        return node.intValue();
    }

    private static JsonNode requiredArray(JsonNode parent, String name) {
        JsonNode node = parent.get(name);
        if (node == null || !node.isArray()) {
            throw new IllegalArgumentException(name + " must be an array");
        }
        return node;
    }

    private static Estimator parseEstimator(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("estimator must be an object");
        }
        requireOnly(
                node, Set.of(
                        "kind", "serviceModel", "feature", "intercept",
                        "divisor", "validation"), "estimator");
        if (!"kos_decompression_queue".equals(requiredText(node, "kind"))
                || !"s3k-kos-v1".equals(requiredText(node, "serviceModel"))) {
            throw new IllegalArgumentException("estimator eligibility mismatch");
        }
        EstimatorFeature feature;
        try {
            feature = EstimatorFeature.fromWireName(
                    requiredText(node, "feature"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "unsupported estimator feature", exception);
        }
        int intercept = requiredInt(node, "intercept");
        int divisor = requiredInt(node, "divisor");
        if (intercept < 0 || intercept > 8
                || divisor < 1 || divisor > 65536) {
            throw new IllegalArgumentException("invalid estimator coefficients");
        }
        JsonNode validation = node.get("validation");
        requireOnly(validation, Set.of(
                "accepted", "sampleCount", "fingerprintCount", "familyCount",
                "fingerprintMedianError", "fingerprintP95Error",
                "familyMedianError", "familyP95Error"), "estimator validation");
        JsonNode accepted = validation.get("accepted");
        if (accepted == null || !accepted.isBoolean() || !accepted.booleanValue()
                || requiredInt(validation, "sampleCount") < 20
                || requiredInt(validation, "fingerprintCount") < 20
                || requiredInt(validation, "familyCount") < 3
                || requiredInt(validation, "fingerprintMedianError") > 2
                || requiredInt(validation, "fingerprintP95Error") > 5
                || requiredInt(validation, "familyMedianError") > 2
                || requiredInt(validation, "familyP95Error") > 5) {
            throw new IllegalArgumentException(
                    "estimator validation does not meet publication gates");
        }
        return new Estimator(feature, intercept, divisor);
    }

    private static void requireOnly(
            JsonNode object, Set<String> fields, String description) {
        if (object == null || !object.isObject()) {
            throw new IllegalArgumentException(description + " must be an object");
        }
        object.fieldNames().forEachRemaining(field -> {
            if (!fields.contains(field)) {
                throw new IllegalArgumentException(
                        "unknown " + description + " field: " + field);
            }
        });
    }

    private record Key(HardwareWorkKind kind, String fingerprint) {
        private Key {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(fingerprint, "fingerprint");
        }
    }

    private record Estimator(
            EstimatorFeature feature, int intercept, int divisor) {
        private int predict(HardwareWorkFeatures features) {
            long value = feature.value(features);
            long prediction = Math.max(
                    0L, intercept + (value + divisor - 1L) / divisor);
            return Math.toIntExact(prediction);
        }
    }

    private enum EstimatorFeature {
        LITERAL_COMMANDS {
            long value(HardwareWorkFeatures f) { return f.literalCommands(); }
        },
        SHORT_COPY_COMMANDS {
            long value(HardwareWorkFeatures f) { return f.shortCopyCommands(); }
        },
        LONG_COPY_COMMANDS {
            long value(HardwareWorkFeatures f) { return f.longCopyCommands(); }
        },
        COPIED_OUTPUT_LENGTH {
            long value(HardwareWorkFeatures f) { return f.copiedOutputLength(); }
        },
        COMPRESSED_LENGTH {
            long value(HardwareWorkFeatures f) { return f.compressedLength(); }
        },
        DECOMPRESSED_LENGTH {
            long value(HardwareWorkFeatures f) { return f.decompressedLength(); }
        },
        MODULE_COUNT {
            long value(HardwareWorkFeatures f) { return f.moduleCount(); }
        },
        FINAL_MODULE_SIZE {
            long value(HardwareWorkFeatures f) { return f.finalModuleSize(); }
        },
        COORDINATION_COUNT {
            long value(HardwareWorkFeatures f) { return f.coordinationCount(); }
        };

        abstract long value(HardwareWorkFeatures features);

        static EstimatorFeature fromWireName(String value) {
            String enumName = value.replaceAll(
                    "([a-z0-9])([A-Z])", "$1_$2")
                    .toUpperCase(java.util.Locale.ROOT);
            return valueOf(enumName);
        }
    }
}
