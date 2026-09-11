package com.openggf.tools.timing;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Strict offline publisher for S3K profiled Kosinski service costs. */
public final class S3kLoadTimeProfileGenerator {
    private static final ObjectMapper MAPPER = new ObjectMapper(
            new JsonFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION));
    private static final Set<String> FIELDS = Set.of(
            "measurement_schema", "recorder_version", "fixture",
            "movie_sha256", "rom_sha1", "service_model", "epoch",
            "raw_frame", "sequence_in_frame", "boundary", "kind",
            "ordinal", "fingerprint", "parent_fingerprint",
            "observation_precision", "classified",
            "service_opportunities", "source", "destination", "compressed_length",
            "decompressed_length", "literal_commands", "short_copy_commands",
            "long_copy_commands", "copied_output_length");

    private S3kLoadTimeProfileGenerator() {
    }

    public static Result generate(List<Path> inputs) throws IOException {
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("at least one measurement input is required");
        }
        List<Path> orderedInputs = inputs.stream()
                .map(Path::toAbsolutePath)
                .sorted()
                .toList();
        Map<String, List<Observation>> eligible = new TreeMap<>();
        Map<String, String> featureShapes = new TreeMap<>();
        int total = 0;
        int censored = 0;
        int unclassified = 0;
        for (Path input : orderedInputs) {
            int lineNumber = 0;
            FileOrder order = new FileOrder();
            for (String line : Files.readAllLines(input, StandardCharsets.UTF_8)) {
                lineNumber++;
                if (line.isBlank()) {
                    throw new IllegalArgumentException(
                            input + ":" + lineNumber + ": blank measurement record");
                }
                Observation observation = parse(line, input, lineNumber);
                order.accept(observation, input, lineNumber);
                total++;
                if (!observation.exact()) {
                    censored++;
                    continue;
                }
                if (!observation.classified()
                        || observation.serviceOpportunities() == 0) {
                    unclassified++;
                    continue;
                }
                String previous = featureShapes.putIfAbsent(
                        observation.fingerprint(), observation.identityShape());
                if (previous != null && !previous.equals(observation.identityShape())) {
                    throw new IllegalArgumentException(
                            "feature disagreement for " + observation.fingerprint());
                }
                eligible.computeIfAbsent(
                        observation.fingerprint(), ignored -> new ArrayList<>())
                        .add(observation);
            }
        }
        return new Result(
                orderedInputs, eligible, total, censored, unclassified);
    }

    public static void publish(
            Result result,
            Path manifest,
            Path validation,
            Path publicationTsv) throws IOException {
        EstimatorValidation estimator = result.validateEstimator();
        String manifestContent = result.manifestJson(estimator);
        String validationContent = result.validationMarkdown(estimator);
        write(manifest, manifestContent);
        write(validation, validationContent);
        write(publicationTsv, result.publicationTsv(
                manifest, manifestContent, validation, validationContent));
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            throw new IllegalArgumentException(
                    "usage: <manifest> <validation.md> <publication.tsv> <measurement.jsonl>...");
        }
        List<Path> inputs = new ArrayList<>();
        for (int index = 3; index < args.length; index++) {
            inputs.add(Path.of(args[index]));
        }
        publish(
                generate(inputs),
                Path.of(args[0]),
                Path.of(args[1]),
                Path.of(args[2]));
    }

    private static Observation parse(
            String line, Path input, int lineNumber) throws IOException {
        JsonNode node;
        try (JsonParser parser = MAPPER.getFactory().createParser(line)) {
            node = MAPPER.readTree(parser);
        }
        if (!node.isObject()) {
            throw invalid(input, lineNumber, "record must be an object");
        }
        node.fieldNames().forEachRemaining(name -> {
            if (!FIELDS.contains(name)) {
                throw invalid(input, lineNumber, "unknown field " + name);
            }
        });
        if (integer(node, "measurement_schema") != 1) {
            throw invalid(input, lineNumber, "unsupported measurement schema");
        }
        if (!"load-time-measurement-v1".equals(text(node, "recorder_version"))
                || !"s3k-kos-v1".equals(text(node, "service_model"))) {
            throw invalid(input, lineNumber, "invalid recorder/service model");
        }
        String movieSha256 = text(node, "movie_sha256");
        String romSha1 = text(node, "rom_sha1");
        requireHash(movieSha256, 64, "movie_sha256", input, lineNumber);
        requireHash(romSha1, 40, "rom_sha1", input, lineNumber);
        if (!romSha1.equalsIgnoreCase(
                "CFBF98C36C776677290A872547AC47C53D2761D6")) {
            throw invalid(input, lineNumber, "unexpected ROM SHA-1");
        }
        if (!"KOS_DECOMPRESSION_QUEUE".equals(text(node, "kind"))) {
            throw invalid(input, lineNumber, "parent/non-direct record is forbidden");
        }
        if (!"pre_main_loop".equals(text(node, "boundary"))) {
            throw invalid(input, lineNumber, "invalid completion boundary");
        }
        String precision = text(node, "observation_precision");
        if (!precision.equals("exact_callback")
                && !precision.equals("frame_end_censored")) {
            throw invalid(input, lineNumber, "invalid observation precision");
        }
        String parentFingerprint = optionalText(node, "parent_fingerprint");
        boolean exact = precision.equals("exact_callback");
        if (exact != (parentFingerprint != null)) {
            throw invalid(input, lineNumber, "parent identity disagrees with precision");
        }
        if (parentFingerprint != null) {
            requireFingerprint(parentFingerprint, input, lineNumber);
        }
        long source = unsignedInt(node, "source");
        long destination = unsignedInt(node, "destination");
        int compressedLength = nonnegative(node, "compressed_length");
        int decompressedLength = nonnegative(node, "decompressed_length");
        String fingerprint = text(node, "fingerprint");
        requireFingerprint(fingerprint, input, lineNumber);
        String recomputed = computeFingerprint(
                (int) source,
                compressedLength,
                (int) destination,
                decompressedLength);
        if (!fingerprint.equals(recomputed)) {
            throw invalid(input, lineNumber, "fingerprint disagrees with descriptor");
        }
        return new Observation(
                text(node, "fixture"),
                movieSha256,
                romSha1,
                nonnegative(node, "epoch"),
                nonnegative(node, "raw_frame"),
                nonnegative(node, "sequence_in_frame"),
                nonnegativeLong(node, "ordinal"),
                fingerprint,
                parentFingerprint,
                exact,
                bool(node, "classified"),
                nonnegative(node, "service_opportunities"),
                source,
                destination,
                compressedLength,
                decompressedLength,
                nonnegative(node, "literal_commands"),
                nonnegative(node, "short_copy_commands"),
                nonnegative(node, "long_copy_commands"),
                nonnegative(node, "copied_output_length"));
    }

    private static void requireHash(
            String value, int length, String field, Path input, int line) {
        if (value.length() != length
                || !value.matches("[0-9A-Fa-f]{" + length + "}")) {
            throw invalid(input, line, field + " must be a hex digest");
        }
    }

    private static void requireFingerprint(
            String value, Path input, int line) {
        if (!value.matches("sha256:[0-9a-f]{64}")) {
            throw invalid(input, line, "invalid fingerprint");
        }
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException(field + " must be string or null");
        }
        return value.textValue();
    }

    private static long unsignedInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        long parsed = value.longValue();
        if (parsed < 0 || parsed > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException(field + " must be an unsigned 32-bit integer");
        }
        return parsed;
    }

    private static long nonnegativeLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong() || value.longValue() < 0) {
            throw new IllegalArgumentException(field + " must be nonnegative integer");
        }
        return value.longValue();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return value.textValue();
    }

    private static int integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToInt()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return value.intValue();
    }

    private static int nonnegative(JsonNode node, String field) {
        int value = integer(node, field);
        if (value < 0) {
            throw new IllegalArgumentException(field + " must be nonnegative");
        }
        return value;
    }

    private static boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw new IllegalArgumentException(field + " must be boolean");
        }
        return value.booleanValue();
    }

    private static String computeFingerprint(
            int source, int compressedLength, int destination, int destinationLength) {
        try {
            var output = new java.io.ByteArrayOutputStream();
            writeFingerprintText(output, "KOS_DECOMPRESSION_QUEUE");
            output.write(ByteBuffer.allocate(4).putInt(source).array());
            output.write(ByteBuffer.allocate(4).putInt(compressedLength).array());
            output.write(ByteBuffer.allocate(4).putInt(destination).array());
            output.write(ByteBuffer.allocate(4).putInt(destinationLength).array());
            writeFingerprintText(output, "kosinski");
            output.write(ByteBuffer.allocate(4).putInt(1).array());
            return "sha256:" + java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(output.toByteArray()));
        } catch (IOException | java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void writeFingerprintText(
            java.io.OutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.write(ByteBuffer.allocate(4).putInt(bytes.length).array());
        output.write(bytes);
    }

    private static IllegalArgumentException invalid(
            Path input, int line, String message) {
        return new IllegalArgumentException(input + ":" + line + ": " + message);
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static String sha256(Path path) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(path));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Observation(
            String fixture,
            String movieSha256,
            String romSha1,
            int epoch,
            int rawFrame,
            int sequenceInFrame,
            long ordinal,
            String fingerprint,
            String parentFingerprint,
            boolean exact,
            boolean classified,
            int serviceOpportunities,
            long source,
            long destination,
            int compressedLength,
            int decompressedLength,
            int literalCommands,
            int shortCopyCommands,
            int longCopyCommands,
            int copiedOutputLength) {
        private String identityShape() {
            return source + ":" + compressedLength + ":" + destination
                    + ":" + decompressedLength + ":"
                    + literalCommands + ":" + shortCopyCommands + ":"
                    + longCopyCommands + ":" + copiedOutputLength;
        }
    }

    private static final class FileOrder {
        private String fixture;
        private String movieSha256;
        private int epoch = -1;
        private int rawFrame = -1;
        private int sequence = -1;
        private long ordinal = -1;

        private void accept(
                Observation row, Path input, int line) {
            if (fixture == null) {
                fixture = row.fixture();
                movieSha256 = row.movieSha256();
            } else if (!fixture.equals(row.fixture())
                    || !movieSha256.equals(row.movieSha256())) {
                throw invalid(input, line, "fixture identity changed within file");
            }
            if (row.epoch() < epoch
                    || row.epoch() == epoch && row.rawFrame() < rawFrame) {
                throw invalid(input, line, "nonmonotonic epoch/frame");
            }
            if (row.epoch() != epoch) {
                epoch = row.epoch();
                rawFrame = -1;
                sequence = -1;
                ordinal = -1;
            }
            if (row.rawFrame() != rawFrame) {
                rawFrame = row.rawFrame();
                sequence = -1;
            }
            if (row.sequenceInFrame() != sequence + 1) {
                throw invalid(input, line, "noncanonical within-frame sequence");
            }
            if (row.ordinal() <= ordinal) {
                throw invalid(input, line, "nonmonotonic ordinal");
            }
            sequence = row.sequenceInFrame();
            ordinal = row.ordinal();
        }
    }

    public record Result(
            List<Path> inputs,
            Map<String, List<Observation>> eligible,
            int totalObservations,
            int censoredObservations,
            int unclassifiedObservations) {
        public Result {
            inputs = List.copyOf(inputs);
            Map<String, List<Observation>> copied = new TreeMap<>();
            eligible.forEach((key, value) -> copied.put(key, List.copyOf(value)));
            eligible = Map.copyOf(copied);
        }

        public int uniqueEligibleFingerprints() {
            return eligible.size();
        }

        public Candidate bestCompleteDatasetCandidate() {
            List<Observation> observations = eligible.values().stream()
                    .flatMap(List::stream)
                    .toList();
            return selectCandidate(observations);
        }

        private static Candidate selectCandidate(List<Observation> observations) {
            if (observations.isEmpty()) {
                return null;
            }
            Candidate best = null;
            for (Feature feature : Feature.values()) {
                long distinct = observations.stream()
                        .mapToLong(feature::value)
                        .distinct().count();
                if (distinct < 2) {
                    continue;
                }
                List<Integer> divisors =
                        divisorCandidates(observations, feature);
                for (int intercept = 0; intercept <= 8; intercept++) {
                    for (int divisor : divisors) {
                        int aboveFive = 0;
                        int[] errors = new int[observations.size()];
                        int allowedAboveFive = observations.size()
                                - ((95 * observations.size() + 99) / 100);
                        for (int index = 0; index < observations.size(); index++) {
                            Observation observation = observations.get(index);
                            long value = feature.value(observation);
                            long prediction = Math.max(
                                    0L,
                                    Math.addExact(
                                            intercept,
                                            Math.floorDiv(
                                                    Math.addExact(value, divisor - 1L),
                                                    divisor)));
                            long error = Math.abs(Math.subtractExact(
                                    prediction, observation.serviceOpportunities()));
                            errors[index] = Math.toIntExact(error);
                            if (error > 5 && ++aboveFive > allowedAboveFive) {
                                break;
                            }
                        }
                        if (aboveFive > allowedAboveFive) {
                            continue;
                        }
                        java.util.Arrays.sort(errors);
                        int median = errors[(errors.length - 1) / 2];
                        int p95 = errors[(95 * errors.length + 99) / 100 - 1];
                        if (median > 2 || p95 > 5) {
                            continue;
                        }
                        Candidate candidate = new Candidate(
                                feature, intercept, divisor, median, p95);
                        if (best == null || candidate.compareTo(best) < 0) {
                            best = candidate;
                        }
                    }
                }
            }
            return best;
        }

        /**
         * Returns the first divisor in every interval where at least one
         * {@code ceil(feature/divisor)} prediction changes. Scores are
         * constant inside an interval, and the candidate tie-break selects
         * its smallest divisor, so this is exactly equivalent to 1..65536.
         */
        private static List<Integer> divisorCandidates(
                List<Observation> observations, Feature feature) {
            java.util.TreeSet<Integer> candidates = new java.util.TreeSet<>();
            candidates.add(1);
            for (Observation observation : observations) {
                long value = feature.value(observation);
                int divisor = 1;
                while (divisor <= 65536) {
                    candidates.add(divisor);
                    if (value == 0) {
                        break;
                    }
                    long quotient = (value + divisor - 1L) / divisor;
                    if (quotient <= 1) {
                        break;
                    }
                    long lastSame = (value - 1L) / (quotient - 1L);
                    long next = lastSame + 1L;
                    if (next > 65536) {
                        break;
                    }
                    divisor = Math.toIntExact(next);
                }
            }
            return List.copyOf(candidates);
        }

        public EstimatorValidation validateEstimator() {
            Candidate complete = bestCompleteDatasetCandidate();
            if (complete == null || eligible.size() < 20) {
                return new EstimatorValidation(complete, false, -1, -1, -1, -1);
            }
            List<Integer> fingerprintErrors = new ArrayList<>();
            for (Map.Entry<String, List<Observation>> heldOut : eligible.entrySet()) {
                List<Observation> training = eligible.entrySet().stream()
                        .filter(entry -> !entry.getKey().equals(heldOut.getKey()))
                        .flatMap(entry -> entry.getValue().stream())
                        .toList();
                Candidate candidate = selectCandidate(training);
                if (candidate == null) {
                    return new EstimatorValidation(
                            complete, false, -1, -1, -1, -1);
                }
                addErrors(fingerprintErrors, candidate, heldOut.getValue());
            }
            List<String> families = eligible.values().stream()
                    .flatMap(List::stream)
                    .map(Observation::fixture)
                    .distinct()
                    .sorted()
                    .toList();
            if (families.size() < 3) {
                return new EstimatorValidation(
                        complete, false,
                        median(fingerprintErrors), p95(fingerprintErrors), -1, -1);
            }
            List<Integer> familyErrors = new ArrayList<>();
            List<Observation> all = eligible.values().stream()
                    .flatMap(List::stream)
                    .toList();
            for (String family : families) {
                List<Observation> training = all.stream()
                        .filter(row -> !row.fixture().equals(family))
                        .toList();
                List<Observation> heldOut = all.stream()
                        .filter(row -> row.fixture().equals(family))
                        .toList();
                Candidate candidate = selectCandidate(training);
                if (candidate == null) {
                    return new EstimatorValidation(
                            complete, false,
                            median(fingerprintErrors), p95(fingerprintErrors), -1, -1);
                }
                addErrors(familyErrors, candidate, heldOut);
            }
            int fingerprintMedian = median(fingerprintErrors);
            int fingerprintP95 = p95(fingerprintErrors);
            int familyMedian = median(familyErrors);
            int familyP95 = p95(familyErrors);
            boolean accepted = fingerprintMedian <= 2 && fingerprintP95 <= 5
                    && familyMedian <= 2 && familyP95 <= 5;
            return new EstimatorValidation(
                    complete, accepted,
                    fingerprintMedian, fingerprintP95, familyMedian, familyP95);
        }

        private static void addErrors(
                List<Integer> errors,
                Candidate candidate,
                List<Observation> observations) {
            for (Observation observation : observations) {
                long feature = candidate.feature().value(observation);
                long prediction = Math.max(0L,
                        candidate.intercept()
                                + (feature + candidate.divisor() - 1)
                                / candidate.divisor());
                errors.add(Math.toIntExact(Math.abs(
                        prediction - observation.serviceOpportunities())));
            }
        }

        private static int median(List<Integer> errors) {
            List<Integer> sorted = errors.stream().sorted().toList();
            return sorted.get((sorted.size() - 1) / 2);
        }

        private static int p95(List<Integer> errors) {
            List<Integer> sorted = errors.stream().sorted().toList();
            return sorted.get((95 * sorted.size() + 99) / 100 - 1);
        }

        public String manifestJson() {
            return manifestJson(validateEstimator());
        }

        private String manifestJson(EstimatorValidation estimator) {
            List<String> fixtures = eligible.values().stream()
                    .flatMap(List::stream)
                    .map(Observation::fixture)
                    .distinct()
                    .sorted()
                    .toList();
            Map<String, Integer> indexes = new LinkedHashMap<>();
            for (int index = 0; index < fixtures.size(); index++) {
                indexes.put(fixtures.get(index), index);
            }
            StringBuilder json = new StringBuilder();
            json.append("{\n  \"formatVersion\": 1,\n")
                    .append("  \"profile\": \"s3k\",\n")
                    .append("  \"serviceModel\": \"s3k-kos-v1\",\n")
                    .append("  \"fixtures\": [");
            for (int index = 0; index < fixtures.size(); index++) {
                if (index != 0) json.append(", ");
                appendJson(json, fixtures.get(index));
            }
            json.append("],\n");
            if (estimator.accepted()) {
                int sampleCount = eligible.values().stream()
                        .mapToInt(List::size).sum();
                long familyCount = eligible.values().stream()
                        .flatMap(List::stream).map(Observation::fixture)
                        .distinct().count();
                json.append("  \"estimator\": {\n")
                        .append("    \"kind\": \"kos_decompression_queue\",\n")
                        .append("    \"serviceModel\": \"s3k-kos-v1\",\n")
                        .append("    \"feature\": ");
                appendJson(json, estimator.candidate().feature().wireName());
                json.append(",\n    \"intercept\": ")
                        .append(estimator.candidate().intercept())
                        .append(",\n    \"divisor\": ")
                        .append(estimator.candidate().divisor())
                        .append(",\n    \"validation\": {\n")
                        .append("      \"accepted\": true,\n")
                        .append("      \"sampleCount\": ").append(sampleCount)
                        .append(",\n      \"fingerprintCount\": ")
                        .append(eligible.size())
                        .append(",\n      \"familyCount\": ").append(familyCount)
                        .append(",\n      \"fingerprintMedianError\": ")
                        .append(estimator.fingerprintMedian())
                        .append(",\n      \"fingerprintP95Error\": ")
                        .append(estimator.fingerprintP95())
                        .append(",\n      \"familyMedianError\": ")
                        .append(estimator.familyMedian())
                        .append(",\n      \"familyP95Error\": ")
                        .append(estimator.familyP95())
                        .append("\n    }\n  },\n");
            }
            json.append("  \"entries\": [");
            boolean first = true;
            for (Map.Entry<String, List<Observation>> entry
                    : new TreeMap<>(eligible).entrySet()) {
                if (!first) json.append(',');
                first = false;
                List<Integer> values = entry.getValue().stream()
                        .map(Observation::serviceOpportunities)
                        .sorted()
                        .toList();
                int median = values.get((values.size() - 1) / 2);
                List<Integer> fixtureIndexes = entry.getValue().stream()
                        .map(Observation::fixture)
                        .distinct()
                        .sorted()
                        .map(indexes::get)
                        .toList();
                json.append("\n    {\n")
                        .append("      \"kind\": \"kos_decompression_queue\",\n")
                        .append("      \"submissionFingerprint\": ");
                appendJson(json, entry.getKey());
                json.append(",\n      \"serviceFrames\": ").append(median)
                        .append(",\n      \"eligibleBoundaries\": [\"pre_main_loop\"],\n")
                        .append("      \"sampleCount\": ").append(values.size())
                        .append(",\n      \"minFrames\": ").append(values.getFirst())
                        .append(",\n      \"maxFrames\": ").append(values.getLast())
                        .append(",\n      \"fixtureIndexes\": ")
                        .append(fixtureIndexes).append("\n    }");
            }
            if (!first) json.append('\n');
            return json.append("  ]\n}\n").toString();
        }

        public String validationMarkdown() {
            return validationMarkdown(validateEstimator());
        }

        private String validationMarkdown(EstimatorValidation estimator) {
            int eligibleCount = eligible.values().stream().mapToInt(List::size).sum();
            return """
                    # S3K Load-Time Profile Validation

                    Date: 2026-07-29

                    ## Result

                    """ + "- Raw completed direct observations: " + totalObservations + "\n"
                    + "- Exact, fully classified observations: " + eligibleCount + "\n"
                    + "- Censored top-level diagnostics excluded: " + censoredObservations + "\n"
                    + "- Exact observations excluded for unclassified service: "
                    + unclassifiedObservations + "\n"
                    + "- Unique published fingerprints: " + uniqueEligibleFingerprints() + "\n"
                    + "- Provisional S3K comparison point: 125 unique direct events\n"
                    + "- Difference from S3K lower bound: +"
                    + (uniqueEligibleFingerprints() - 125) + " ("
                    + String.format(java.util.Locale.ROOT, "%.1f",
                    (uniqueEligibleFingerprints() - 125) * 100.0 / 125.0)
                    + "%)\n"
                    + "- Provisional cross-game comparison point: 436 unique direct events\n"
                    + "- Share of cross-game lower bound represented by S3K profile: "
                    + String.format(java.util.Locale.ROOT, "%.1f",
                    uniqueEligibleFingerprints() * 100.0 / 436.0) + "%\n\n"
                    + "Only exact module-child observations with fully classified lifetimes "
                    + "are published. Complete-dataset estimator candidate: "
                    + (estimator.candidate() == null
                    ? "none passed median/p95 gates"
                    : estimator.candidate().toString())
                    + ".\n\nHeld-out fingerprint median/p95: "
                    + estimator.fingerprintMedian() + "/"
                    + estimator.fingerprintP95()
                    + ". Held-out fixture-family median/p95: "
                    + estimator.familyMedian() + "/" + estimator.familyP95()
                    + ". Estimator publication: "
                    + (estimator.accepted() ? "accepted" : "rejected")
                    + ".\n";
        }

        public String publicationTsv() throws IOException {
            EstimatorValidation estimator = validateEstimator();
            return publicationTsv(
                    Path.of("src/main/resources/load-time-profiles/s3k-v1.json"),
                    manifestJson(estimator),
                    Path.of("docs/architecture/audits/2026-07-29-s3k-load-time-profile-validation.md"),
                    validationMarkdown(estimator));
        }

        private String publicationTsv(
                Path manifestPath,
                String manifestContent,
                Path validationPath,
                String validationContent) throws IOException {
            String recorderCommit = System.getProperty(
                    "loadTime.recorderCommit", "WORKTREE");
            Map<String, Path> movies = Map.of(
                    "s3k-complete-sonic-tails.bk2",
                    Path.of("src/test/resources/traces/s3k/_movies/s3k-complete-sonic-tails.bk2"),
                    "s3-knux-multibonus-ss.bk2",
                    Path.of("src/test/resources/traces/s3k/_movies/s3-knux-multibonus-ss.bk2"),
                    "s3-aiz1-2-sonictails.bk2",
                    Path.of("src/test/resources/traces/s3k/aiz1_to_hcz_fullrun/s3-aiz1-2-sonictails.bk2"),
                    "s3k-cnz-sonic-tails.bk2",
                    Path.of("src/test/resources/traces/s3k/cnz/s3k-cnz-sonic-tails.bk2"),
                    "s3k-mgz-sonic-tails.bk2",
                    Path.of("src/test/resources/traces/s3k/mgz/s3k-mgz-sonic-tails.bk2"));
            StringBuilder tsv = new StringBuilder()
                    .append("# rom_sha1\tCFBF98C36C776677290A872547AC47C53D2761D6\n")
                    .append("# measurement_schema\t1\n")
                    .append("# recorder_version\tload-time-measurement-v1\n")
                    .append("# recorder_commit\t").append(recorderCommit).append('\n')
                    .append("# generator\tS3kLoadTimeProfileGenerator-v1\n")
                    .append("# input_set_key\tsha256:")
                    .append(inputSetKey(movies, recorderCommit)).append('\n')
                    .append("# capture_command\tBIZHAWK_HOME=<bizhawk> ")
                    .append("tools/tracechaser/bizhawk-headless/run.sh --mode load-time ")
                    .append("--rom <s3k-rom> --movie <movie-path> ")
                    .append("--output target/load-time-measurements/<fixture>\n")
                    .append("# generation_command\tmvn -q exec:java ")
                    .append("-Dexec.mainClass=com.openggf.tools.timing.S3kLoadTimeProfileGenerator ")
                    .append("-DloadTime.recorderCommit=").append(recorderCommit).append(' ')
                    .append("-Dexec.args=\"src/main/resources/load-time-profiles/s3k-v1.json ")
                    .append("docs/architecture/audits/2026-07-29-s3k-load-time-profile-validation.md ")
                    .append("docs/architecture/audits/2026-07-29-s3k-load-time-publication.tsv ")
                    .append("target/load-time-measurements/aiz/load_time_measurements.jsonl ")
                    .append("target/load-time-measurements/cnz/load_time_measurements.jsonl ")
                    .append("target/load-time-measurements/mgz/load_time_measurements.jsonl ")
                    .append("target/load-time-measurements/knux/load_time_measurements.jsonl ")
                    .append("target/load-time-measurements/complete/load_time_measurements.jsonl\"\n")
                    .append("record\tpath\tdigest_algorithm\tdigest\tbytes\tobservations\n");
            for (Path movie : movies.values().stream().sorted().toList()) {
                appendFileRow(tsv, "movie", movie, "");
            }
            for (Path input : inputs.stream().sorted().toList()) {
                long count;
                try (var lines = Files.lines(input, StandardCharsets.UTF_8)) {
                    count = lines.count();
                }
                tsv.append("measurement\ttarget/load-time-measurements/")
                        .append(input.getParent().getFileName())
                        .append('/').append(input.getFileName())
                        .append("\tsha256\t").append(sha256(input)).append('\t')
                        .append(Files.size(input)).append('\t').append(count)
                        .append('\n');
            }
            appendContentRow(tsv, "output", manifestPath, manifestContent);
            appendContentRow(tsv, "output", validationPath, validationContent);
            return tsv.toString();
        }

        private String inputSetKey(
                Map<String, Path> movies, String recorderCommit) throws IOException {
            StringBuilder identity = new StringBuilder()
                    .append("rom_sha1=CFBF98C36C776677290A872547AC47C53D2761D6\n")
                    .append("measurement_schema=1\n")
                    .append("recorder_version=load-time-measurement-v1\n")
                    .append("recorder_commit=").append(recorderCommit).append('\n');
            for (Path movie : movies.values().stream().sorted().toList()) {
                identity.append("movie=").append(movie).append('\t')
                        .append(sha256(movie)).append('\n');
            }
            return digest(identity.toString().getBytes(StandardCharsets.UTF_8));
        }

        private static void appendFileRow(
                StringBuilder tsv, String record, Path path, String observations)
                throws IOException {
            tsv.append(record).append('\t').append(path).append("\tsha256\t")
                    .append(sha256(path)).append('\t').append(Files.size(path));
            if (!observations.isEmpty()) {
                tsv.append('\t').append(observations);
            }
            tsv.append('\n');
        }

        private static void appendContentRow(
                StringBuilder tsv, String record, Path path, String content) {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            tsv.append(record).append('\t').append(path)
                    .append("\tsha256\t").append(digest(bytes)).append('\t')
                    .append(bytes.length).append('\n');
        }

        private static String digest(byte[] bytes) {
            try {
                return java.util.HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(bytes));
            } catch (java.security.NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
        }

        private static void appendJson(StringBuilder builder, String value) {
            builder.append('"');
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (character == '"' || character == '\\') {
                    builder.append('\\');
                }
                builder.append(character);
            }
            builder.append('"');
        }
    }

    public record Candidate(
            Feature feature,
            int intercept,
            int divisor,
            int medianError,
            int p95Error) implements Comparable<Candidate> {
        @Override
        public int compareTo(Candidate other) {
            return Comparator
                    .comparingInt(Candidate::medianError)
                    .thenComparingInt(Candidate::p95Error)
                    .thenComparingInt(candidate -> candidate.feature().ordinal())
                    .thenComparingInt(Candidate::intercept)
                    .thenComparingInt(Candidate::divisor)
                    .compare(this, other);
        }
    }

    public record EstimatorValidation(
            Candidate candidate,
            boolean accepted,
            int fingerprintMedian,
            int fingerprintP95,
            int familyMedian,
            int familyP95) {
    }

    public enum Feature {
        LITERAL_COMMANDS {
            long value(Observation row) { return row.literalCommands(); }
        },
        SHORT_COPY_COMMANDS {
            long value(Observation row) { return row.shortCopyCommands(); }
        },
        LONG_COPY_COMMANDS {
            long value(Observation row) { return row.longCopyCommands(); }
        },
        COPIED_OUTPUT_LENGTH {
            long value(Observation row) { return row.copiedOutputLength(); }
        },
        COMPRESSED_LENGTH {
            long value(Observation row) { return row.compressedLength(); }
        },
        DECOMPRESSED_LENGTH {
            long value(Observation row) { return row.decompressedLength(); }
        },
        MODULE_COUNT {
            long value(Observation row) { return 1; }
        },
        FINAL_MODULE_SIZE {
            long value(Observation row) { return row.decompressedLength(); }
        },
        COORDINATION_COUNT {
            long value(Observation row) { return 1; }
        };

        abstract long value(Observation row);

        String wireName() {
            String[] words = name().toLowerCase(java.util.Locale.ROOT).split("_");
            StringBuilder result = new StringBuilder(words[0]);
            for (int index = 1; index < words.length; index++) {
                result.append(Character.toUpperCase(words[index].charAt(0)))
                        .append(words[index], 1, words[index].length());
            }
            return result.toString();
        }
    }
}
