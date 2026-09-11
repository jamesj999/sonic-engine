package com.openggf.tools.audio.timeline;

import static com.openggf.tools.audio.timeline.S1GameplayAudioTimeline.*;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Strict record-at-a-time JSONL transport for the S1 GHZ1 gameplay-audio contract. */
public final class S1GameplayAudioTimelineJsonl {
    private static final JsonFactory FACTORY = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final ObjectMapper JSON = new ObjectMapper(FACTORY);
    private static final int MAX_JSONL_RECORD_CHARS = 128 * 1024;
    private static final Set<String> METADATA_FIELDS = Set.of("type", "schema", "capture", "rom", "bk2",
            "producer", "segment_start_bk2_frame", "segment_end_bk2_frame", "terminal_frame_count",
            "field_inventory");
    private static final Set<String> BASELINE_FIELDS = Set.of("type", "bk2_frame", "active_music_id",
            "diagnostic_tick", "owners");
    private static final Set<String> FRAME_FIELDS = Set.of("type", "bk2_frame", "diagnostic_tick", "requests",
            "admissions", "owners");
    private static final Set<String> REQUEST_FIELDS = Set.of("request_ordinal", "sound_class", "raw_sound_id");
    private static final Set<String> ADMISSION_FIELDS = Set.of("request_ordinal", "sound_class", "sound_id",
            "requested_roles", "arbitration");
    private static final Set<String> ARBITRATION_FIELDS = Set.of("role", "acquired", "displaced_owner", "final_owner");
    private static final Set<String> OWNER_FIELDS = Set.of("owner_class", "sound_id", "request_ordinal");
    private static final Set<String> OWNER_VECTOR_FIELDS = Set.of("fm3", "fm4", "fm5", "psg1", "psg2", "psg3");
    private static final Set<String> TERMINAL_FIELDS = Set.of("type", "frame_count", "request_count",
            "admission_count", "diagnostic_tick_count");

    private S1GameplayAudioTimelineJsonl() {
    }

    /** Opens a bounded reader; records are parsed and validated only as the caller advances it. */
    public static Reader read(Path path) {
        try {
            return read(Files.newInputStream(path));
        } catch (IOException failure) {
            throw invalid("cannot read timeline JSONL: " + failure.getMessage(), failure);
        }
    }

    /** Opens a bounded reader over caller-owned bytes, including digesting streams. */
    public static Reader read(InputStream stream) {
        try {
            BufferedReader input = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            try {
                String line = readLine(input);
                if (line == null || line.isBlank()) {
                    throw invalid("missing metadata JSONL record");
                }
                return new Reader(input, parseMetadata(line));
            } catch (RuntimeException failure) {
                input.close();
                throw failure;
            }
        } catch (IOException failure) {
            throw invalid("cannot read timeline JSONL: " + failure.getMessage(), failure);
        }
    }

    /** Validates while writing, then atomically publishes a fresh destination with a hard link. */
    public static void writeNew(Path path, Metadata metadata, Iterator<TimelineRecord> records) {
        Path absolute = path.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) {
            throw invalid("timeline output must have a parent directory");
        }
        String metadataJson = canonical(metadataTree(metadata));
        parseMetadata(metadataJson);
        Path temporary = null;
        try {
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, ".s1-gameplay-audio-", ".jsonl.tmp");
            Validator validator = new Validator();
            try (BufferedWriter output = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                output.write(metadataJson);
                output.newLine();
                while (records.hasNext()) {
                    TimelineRecord record = records.next();
                    validator.accept(record);
                    output.write(canonical(recordTree(record)));
                    output.newLine();
                }
                validator.finish();
            }
            // Read our own bytes from disk before publication: serializer and iterator cannot bypass validation.
            try (Reader reader = read(temporary)) {
                while (reader.hasNext()) {
                    reader.next();
                }
            }
            Files.createLink(absolute, temporary);
            Files.delete(temporary);
            temporary = null;
        } catch (IOException failure) {
            throw invalid("cannot publish timeline JSONL atomically: " + failure.getMessage(), failure);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Preserve the primary validation/publication failure.
                }
            }
        }
    }

    public static final class Reader implements AutoCloseable, Iterator<TimelineRecord> {
        private final BufferedReader input;
        private final Metadata metadata;
        private final Validator validator = new Validator();
        private String nextLine;
        private boolean complete;

        private Reader(BufferedReader input, Metadata metadata) {
            this.input = input;
            this.metadata = metadata;
        }

        public Metadata metadata() {
            return metadata;
        }

        @Override
        public boolean hasNext() {
            if (complete) {
                return false;
            }
            if (nextLine != null) {
                return true;
            }
            try {
                nextLine = readLine(input);
            } catch (IOException failure) {
                throw invalid("cannot read timeline JSONL record: " + failure.getMessage(), failure);
            }
            if (nextLine == null) {
                validator.finish();
                complete = true;
                return false;
            }
            if (nextLine.isBlank()) {
                throw invalid("blank timeline JSONL record");
            }
            return true;
        }

        @Override
        public TimelineRecord next() {
            if (!hasNext()) {
                throw new java.util.NoSuchElementException();
            }
            TimelineRecord record = parseRecord(nextLine);
            nextLine = null;
            validator.accept(record);
            if (record instanceof Terminal) {
                try {
                    if (readLine(input) != null) {
                        throw invalid("terminal must be the final JSONL record");
                    }
                } catch (IOException failure) {
                    throw invalid("cannot read terminal tail: " + failure.getMessage(), failure);
                }
                validator.finish();
                complete = true;
            }
            return record;
        }

        @Override
        public void close() {
            try {
                input.close();
            } catch (IOException failure) {
                throw invalid("cannot close timeline JSONL reader: " + failure.getMessage(), failure);
            }
        }
    }

    private static Metadata parseMetadata(String json) {
        ObjectNode root = object(tree(json), "metadata");
        exact(root, METADATA_FIELDS, "metadata");
        require(root, "type", "metadata");
        ObjectNode rom = object(required(root, "rom"), "rom");
        exact(rom, Set.of("sha1", "crc32"), "rom");
        ObjectNode bk2 = object(required(root, "bk2"), "bk2");
        exact(bk2, Set.of("sha256"), "bk2");
        validateInventory(object(required(root, "field_inventory"), "field_inventory"));
        return new Metadata(text(root, "schema"), text(root, "capture"), text(rom, "sha1"),
                text(rom, "crc32"), text(bk2, "sha256"), text(root, "producer"),
                integer(root, "segment_start_bk2_frame"), integer(root, "segment_end_bk2_frame"),
                integer(root, "terminal_frame_count"));
    }

    private static TimelineRecord parseRecord(String json) {
        ObjectNode root = object(tree(json), "timeline record");
        String type = text(root, "type");
        return switch (type) {
            case "baseline" -> {
                exact(root, BASELINE_FIELDS, "baseline");
                yield new Baseline(integer(root, "bk2_frame"), integer(root, "active_music_id"),
                        nullableLong(root, "diagnostic_tick"), ownerVector(object(required(root, "owners"), "owners")));
            }
            case "frame" -> {
                exact(root, FRAME_FIELDS, "frame");
                ArrayNode nodes = array(required(root, "requests"), "requests");
                bounded(nodes, MAX_REQUESTS_PER_FRAME, "requests");
                List<Request> requests = new ArrayList<>(nodes.size());
                nodes.forEach(node -> requests.add(request(object(node, "request"))));
                ArrayNode admissionNodes = array(required(root, "admissions"), "admissions");
                bounded(admissionNodes, MAX_ADMISSIONS_PER_FRAME, "admissions");
                List<Admission> admissions = new ArrayList<>(admissionNodes.size());
                admissionNodes.forEach(node -> admissions.add(admission(object(node, "admission"))));
                yield new Frame(integer(root, "bk2_frame"), nullableLong(root, "diagnostic_tick"), requests,
                        admissions, ownerVector(object(required(root, "owners"), "owners")));
            }
            case "terminal" -> {
                exact(root, TERMINAL_FIELDS, "terminal");
                yield new Terminal(integer(root, "frame_count"), longInteger(root, "request_count"),
                        longInteger(root, "admission_count"),
                        longInteger(root, "diagnostic_tick_count"));
            }
            default -> throw invalid("unknown timeline record type " + type);
        };
    }

    private static Request request(ObjectNode node) {
        exact(node, REQUEST_FIELDS, "request");
        return new Request(longInteger(node, "request_ordinal"), enumValue(SoundClass.class, text(node, "sound_class")),
                integer(node, "raw_sound_id"));
    }

    private static Admission admission(ObjectNode node) {
        exact(node, ADMISSION_FIELDS, "admission");
        ArrayNode requested = array(required(node, "requested_roles"), "requested_roles");
        bounded(requested, MAX_ROLES_PER_REQUEST, "requested_roles");
        List<HardwareRole> roles = new ArrayList<>(requested.size());
        requested.forEach(value -> roles.add(enumValue(HardwareRole.class, text(value, "requested role"))));
        ArrayNode arbitration = array(required(node, "arbitration"), "arbitration");
        bounded(arbitration, MAX_ROLES_PER_REQUEST, "arbitration");
        List<RoleArbitration> decisions = new ArrayList<>(arbitration.size());
        arbitration.forEach(value -> decisions.add(arbitration(object(value, "arbitration"))));
        return new Admission(longInteger(node, "request_ordinal"), enumValue(SoundClass.class, text(node, "sound_class")),
                integer(node, "sound_id"), roles, decisions);
    }

    private static RoleArbitration arbitration(ObjectNode node) {
        exact(node, ARBITRATION_FIELDS, "arbitration");
        return new RoleArbitration(enumValue(HardwareRole.class, text(node, "role")), bool(node, "acquired"),
                owner(object(required(node, "displaced_owner"), "displaced_owner")),
                owner(object(required(node, "final_owner"), "final_owner")));
    }

    private static OwnerVector ownerVector(ObjectNode node) {
        exact(node, OWNER_VECTOR_FIELDS, "owners");
        return new OwnerVector(owner(object(required(node, "fm3"), "owners.fm3")),
                owner(object(required(node, "fm4"), "owners.fm4")), owner(object(required(node, "fm5"), "owners.fm5")),
                owner(object(required(node, "psg1"), "owners.psg1")), owner(object(required(node, "psg2"), "owners.psg2")),
                owner(object(required(node, "psg3"), "owners.psg3")));
    }

    private static OwnerRef owner(ObjectNode node) {
        exact(node, OWNER_FIELDS, "owner");
        return new OwnerRef(enumValue(OwnerClass.class, text(node, "owner_class")), integer(node, "sound_id"),
                longInteger(node, "request_ordinal"));
    }

    private static ObjectNode metadataTree(Metadata metadata) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("type", "metadata").put("schema", metadata.schema()).put("capture", metadata.capture());
        root.putObject("rom").put("sha1", metadata.romSha1()).put("crc32", metadata.romCrc32());
        root.putObject("bk2").put("sha256", metadata.bk2Sha256());
        root.put("producer", metadata.producer()).put("segment_start_bk2_frame", metadata.segmentStartBk2Frame())
                .put("segment_end_bk2_frame", metadata.segmentEndBk2Frame())
                .put("terminal_frame_count", metadata.terminalFrameCount());
        root.set("field_inventory", inventoryTree());
        return root;
    }

    private static ObjectNode inventoryTree() {
        ObjectNode inventory = JsonNodeFactory.instance.objectNode();
        inventory.putArray("record_types").add("baseline").add("frame").add("terminal");
        inventory.putArray("semantic_event_types").add("request").add("admission");
        inventory.putArray("ownership_roles").add("FM3").add("FM4").add("FM5").add("PSG1").add("PSG2").add("PSG3");
        inventory.putArray("sound_classes").add("MUSIC").add("SFX").add("SPECIAL_SFX").add("COMMAND");
        inventory.putArray("owner_classes").add("NONE").add("MUSIC").add("NORMAL_SFX").add("SPECIAL_SFX");
        return inventory;
    }

    private static JsonNode recordTree(TimelineRecord record) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        if (record instanceof Baseline baseline) {
            root.put("type", "baseline").put("bk2_frame", baseline.bk2Frame()).put("active_music_id", baseline.activeMusicId());
            nullable(root, "diagnostic_tick", baseline.diagnosticTick());
            root.set("owners", ownerVectorTree(baseline.owners()));
        } else if (record instanceof Frame frame) {
            root.put("type", "frame").put("bk2_frame", frame.bk2Frame());
            nullable(root, "diagnostic_tick", frame.diagnosticTick());
            ArrayNode requests = root.putArray("requests");
            frame.requests().forEach(request -> requests.add(requestTree(request)));
            ArrayNode admissions = root.putArray("admissions");
            frame.admissions().forEach(admission -> admissions.add(admissionTree(admission)));
            root.set("owners", ownerVectorTree(frame.owners()));
        } else if (record instanceof Terminal terminal) {
            root.put("type", "terminal").put("frame_count", terminal.frameCount())
                    .put("request_count", terminal.requestCount())
                    .put("admission_count", terminal.admissionCount())
                    .put("diagnostic_tick_count", terminal.diagnosticTickCount());
        } else {
            throw invalid("unknown timeline record implementation");
        }
        return root;
    }

    private static ObjectNode requestTree(Request request) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("request_ordinal", request.requestOrdinal()).put("sound_class", request.soundClass().name())
                .put("raw_sound_id", request.rawSoundId());
        return node;
    }

    private static ObjectNode admissionTree(Admission admission) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("request_ordinal", admission.requestOrdinal()).put("sound_class", admission.soundClass().name())
                .put("sound_id", admission.soundId());
        ArrayNode roles = node.putArray("requested_roles");
        admission.requestedRoles().forEach(role -> roles.add(role.name()));
        ArrayNode decisions = node.putArray("arbitration");
        admission.arbitration().forEach(decision -> {
            ObjectNode item = decisions.addObject();
            item.put("role", decision.role().name()).put("acquired", decision.acquired());
            item.set("displaced_owner", ownerTree(decision.displacedOwner()));
            item.set("final_owner", ownerTree(decision.finalOwner()));
        });
        return node;
    }

    private static ObjectNode ownerVectorTree(OwnerVector owners) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        for (HardwareRole role : HardwareRole.values()) {
            node.set(role.name().toLowerCase(), ownerTree(owners.owner(role)));
        }
        return node;
    }

    private static ObjectNode ownerTree(OwnerRef owner) {
        return JsonNodeFactory.instance.objectNode().put("owner_class", owner.ownerClass().name())
                .put("sound_id", owner.soundId()).put("request_ordinal", owner.requestOrdinal());
    }

    private static void validateInventory(ObjectNode inventory) {
        exact(inventory, Set.of("record_types", "semantic_event_types", "ownership_roles", "sound_classes",
                "owner_classes"), "field_inventory");
        if (!strings(array(required(inventory, "record_types"), "record_types")).equals(List.of("baseline", "frame", "terminal"))
                || !strings(array(required(inventory, "semantic_event_types"), "semantic_event_types")).equals(
                        List.of("request", "admission"))
                || !strings(array(required(inventory, "ownership_roles"), "ownership_roles")).equals(
                        List.of("FM3", "FM4", "FM5", "PSG1", "PSG2", "PSG3"))
                || !strings(array(required(inventory, "sound_classes"), "sound_classes")).equals(
                        List.of("MUSIC", "SFX", "SPECIAL_SFX", "COMMAND"))
                || !strings(array(required(inventory, "owner_classes"), "owner_classes")).equals(
                        List.of("NONE", "MUSIC", "NORMAL_SFX", "SPECIAL_SFX"))) {
            throw invalid("field inventory is not v2");
        }
    }

    private static final class Validator {
        private boolean baselineSeen;
        private boolean terminalSeen;
        private int expectedFrame = SEGMENT_START_BK2_FRAME;
        private int frames;
        private long requests;
        private long admissions;
        private long diagnosticTicks;
        private Long lastDiagnosticTick;
        private long lastRequestOrdinal = -1;
        private long lastAdmissionOrdinal = -1;
        private final Map<Long, Request> requestsByOrdinal = new HashMap<>();

        private void accept(TimelineRecord record) {
            if (terminalSeen) {
                throw invalid("record follows terminal");
            }
            if (record instanceof Baseline baseline) {
                if (baselineSeen || frames != 0) {
                    throw invalid("baseline must be first and unique");
                }
                baselineSeen = true;
                tick(baseline.diagnosticTick());
            } else if (record instanceof Frame frame) {
                if (!baselineSeen || frame.bk2Frame() != expectedFrame) {
                    throw invalid("BK2 frame continuity failure at " + expectedFrame);
                }
                expectedFrame++;
                frames++;
                tick(frame.diagnosticTick());
                for (Request request : frame.requests()) {
                    if (request.requestOrdinal() <= lastRequestOrdinal) {
                        throw invalid("request ordinals must be globally increasing");
                    }
                    lastRequestOrdinal = request.requestOrdinal();
                    requestsByOrdinal.put(request.requestOrdinal(), request);
                    requests++;
                }
                for (Admission admission : frame.admissions()) {
                    Request request = requestsByOrdinal.get(admission.requestOrdinal());
                    if (admission.requestOrdinal() <= lastAdmissionOrdinal || request == null
                            || request.soundClass() != admission.soundClass()) {
                        throw invalid("admissions must be globally increasing and match a prior request");
                    }
                    lastAdmissionOrdinal = admission.requestOrdinal();
                    requestsByOrdinal.remove(admission.requestOrdinal());
                    admissions++;
                }
            } else if (record instanceof Terminal terminal) {
                if (!baselineSeen || frames != SEGMENT_FRAME_COUNT || terminal.frameCount() != frames
                        || terminal.requestCount() != requests || terminal.admissionCount() != admissions
                        || terminal.diagnosticTickCount() != diagnosticTicks) {
                    throw invalid("terminal counts do not match the complete timeline");
                }
                terminalSeen = true;
            } else {
                throw invalid("unknown timeline record");
            }
        }

        private void tick(Long tick) {
            if (tick != null) {
                if (lastDiagnosticTick != null && tick <= lastDiagnosticTick) {
                    throw invalid("diagnostic ticks must be strictly monotonic");
                }
                lastDiagnosticTick = tick;
                diagnosticTicks++;
            }
        }

        private void finish() {
            if (!baselineSeen || !terminalSeen) {
                throw invalid("timeline must contain baseline, all GHZ1 frames, and terminal");
            }
        }
    }

    private static JsonNode tree(String json) {
        try (JsonParser parser = FACTORY.createParser(json)) {
            JsonNode root = JSON.readTree(parser);
            if (root == null || parser.nextToken() != null) {
                throw invalid("JSON record must contain exactly one root");
            }
            return root;
        } catch (IOException failure) {
            throw invalid("malformed or duplicate-field JSON: " + failure.getMessage(), failure);
        }
    }

    private static String canonical(JsonNode node) {
        try {
            return JSON.writeValueAsString(sorted(node));
        } catch (IOException failure) {
            throw invalid("cannot serialize canonical timeline JSON", failure);
        }
    }

    private static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            TreeSet<String> fields = new TreeSet<>();
            node.fieldNames().forEachRemaining(fields::add);
            fields.forEach(field -> sorted.set(field, sorted(node.get(field))));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode sorted = JsonNodeFactory.instance.arrayNode();
            node.forEach(value -> sorted.add(sorted(value)));
            return sorted;
        }
        return node.deepCopy();
    }

    private static void exact(ObjectNode node, Set<String> expected, String name) {
        TreeSet<String> actual = new TreeSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(new TreeSet<>(expected))) {
            throw invalid(name + " fields must be exactly " + new TreeSet<>(expected));
        }
    }

    private static ObjectNode object(JsonNode node, String name) {
        if (!(node instanceof ObjectNode object)) {
            throw invalid(name + " must be an object");
        }
        return object;
    }

    private static ArrayNode array(JsonNode node, String name) {
        if (!(node instanceof ArrayNode array)) {
            throw invalid(name + " must be an array");
        }
        return array;
    }

    private static void bounded(ArrayNode values, int maximum, String name) {
        if (values.size() > maximum) {
            throw invalid(name + " exceeds the v2 bounded-record limit of " + maximum);
        }
    }

    /** Reads one bounded physical JSONL record so malformed nesting cannot allocate an unlimited line. */
    private static String readLine(BufferedReader input) throws IOException {
        StringBuilder line = new StringBuilder();
        int character;
        while ((character = input.read()) != -1) {
            if (character == '\n') {
                break;
            }
            if (line.length() == MAX_JSONL_RECORD_CHARS) {
                throw invalid("JSONL record exceeds " + MAX_JSONL_RECORD_CHARS + " characters");
            }
            line.append((char) character);
        }
        if (character == -1 && line.isEmpty()) {
            return null;
        }
        if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
            line.setLength(line.length() - 1);
        }
        return line.toString();
    }

    private static JsonNode required(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw invalid("missing required field " + field);
        }
        return value;
    }

    private static String text(ObjectNode node, String field) {
        return text(required(node, field), field);
    }

    private static String text(JsonNode node, String field) {
        if (!node.isTextual()) {
            throw invalid(field + " must be a string");
        }
        return node.textValue();
    }

    private static int integer(ObjectNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid(field + " must be an integer");
        }
        return value.intValue();
    }

    private static long longInteger(ObjectNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalid(field + " must be an integer");
        }
        return value.longValue();
    }

    private static Long nullableLong(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalid(field + " must be an integer or null");
        }
        return value.longValue();
    }

    private static boolean bool(ObjectNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isBoolean()) {
            throw invalid(field + " must be boolean");
        }
        return value.booleanValue();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException error) {
            throw invalid("unknown " + type.getSimpleName() + " " + value, error);
        }
    }

    private static List<String> strings(ArrayNode values) {
        List<String> result = new ArrayList<>(values.size());
        values.forEach(value -> result.add(text(value, "inventory value")));
        return result;
    }

    private static void require(ObjectNode node, String field, String expected) {
        if (!expected.equals(text(node, field))) {
            throw invalid(field + " must be " + expected);
        }
    }

    private static void nullable(ObjectNode node, String field, Long value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private static IllegalArgumentException invalid(String message, Throwable cause) {
        return new IllegalArgumentException(message, cause);
    }
}
