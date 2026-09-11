package com.openggf.tools.audio.completerun.s2;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Strict streaming reader for the game-owned headless S2 raw staging contract. */
public final class S2CompleteRunReferenceRawAdapter {
    public static final String SCHEMA = "openggf.s2-complete-run-audio-raw.v2";
    private static final String ROM_SHA1 = "8bca5dcef1af3e00098666fd892dc1c2a76333f9";
    private static final String BK2_SHA256 =
            "e850798f882b8c580aad148bc97cb50f260cae1d336dd649fe2f4dfae6796aa5";
    private static final String MANIFEST_SHA256 =
            "ef8f8103c38d70e41cb09cb29751f56815a0401709dc509071aa514d614813a0";
    private static final int MAX_LINE_CHARACTERS = 16 * 1024 * 1024;
    private static final int MAX_EVENTS = 65_536;
    private static final BigInteger MAX_U64 = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                    .maxStringLength(MAX_LINE_CHARACTERS).maxNumberLength(64).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    private S2CompleteRunReferenceRawAdapter() { }

    public interface Sink {
        /** Opens a private transaction; callbacks below must remain invisible until commit. */
        void begin() throws IOException;
        void header(Header value) throws IOException;
        void baseline(RawBoundary value) throws IOException;
        void frame(RawFrame value) throws IOException;
        void cutoff(RawBoundary value) throws IOException;
        /** Atomically makes the staged callbacks visible. */
        void commit() throws IOException;
        /** Discards every staged callback and is safe after any pre-commit failure. */
        void abort() throws IOException;
    }

    public record Header(int firstRow, int exclusiveEnd, int stateStart, int stateExclusiveEnd) { }

    public record RawBoundary(int row, int exclusiveEnd, byte[] driverState,
            int ymPort0Latch, int ymPort1Latch, long nativeArmEpoch, boolean nativeArmed,
            List<RawService> activeServices, List<RawService> pendingDescendants) {
        public RawBoundary {
            driverState = driverState.clone();
            activeServices = List.copyOf(activeServices);
            pendingDescendants = List.copyOf(pendingDescendants);
        }
        @Override public byte[] driverState() { return driverState.clone(); }
    }

    public record RawFrame(int row, boolean lag, byte[] driverState, List<RawEvent> events) {
        public RawFrame {
            driverState = driverState.clone();
            events = List.copyOf(events);
        }
        @Override public byte[] driverState() { return driverState.clone(); }
    }

    public record RawEvent(long ordinal, int serviceToken, int parentToken, long pc,
            int subject, int offset, int kind, int serviceKind, int depth, int sourceCpu,
            int payloadLength, int value, int flags, int reserved, BigInteger payload) { }

    public record RawService(int token, int parentToken, int kind, int depth,
            int currentParentToken, int currentDepth, long beginCoordinate, long endCoordinate,
            int beginRow, long beginNativeOrdinal,
            long beginPc, long endPc, int beginHookToken, int endHookToken, int beginSourceCpu,
            boolean cancelled, boolean complete, List<RawChip> chips,
            List<RawSnapshot> snapshots, List<RawAncestryTransition> ancestryTransitions) {
        public RawService {
            chips = List.copyOf(chips);
            snapshots = List.copyOf(snapshots);
            ancestryTransitions = List.copyOf(ancestryTransitions);
        }
    }

    public record RawChip(long coordinate, long nativeOrdinal, int eventKind, int subject,
            int value, long pc, int sourceCpu, boolean data, int port, int register) { }

    public record RawSnapshot(int rangeId, int sourceCpu, long pc, byte[] bytes) {
        public RawSnapshot { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    public record RawAncestryTransition(long coordinate, long nativeOrdinal,
            int previousParentToken, int previousDepth, int currentParentToken,
            int currentDepth, int hookToken, int sourceCpu, long pc) { }

    public static void scan(Path raw, Sink sink) throws IOException {
        scan(raw, sink, true);
    }

    static void scanPrefixForTesting(Path raw, Sink sink) throws IOException {
        scan(raw, sink, false);
    }

    private static void scan(Path raw, Sink sink, boolean requireFull) throws IOException {
        Objects.requireNonNull(raw, "S2 raw staging path");
        Objects.requireNonNull(sink, "S2 raw staging sink");
        boolean transactionStarted = false;
        boolean committed = false;
        try (BufferedReader input = Files.newBufferedReader(raw, StandardCharsets.UTF_8)) {
            Header header = header(object(requiredLine(input), "metadata"));
            RawBoundary baseline = boundary(object(requiredLine(input), "baseline"), true);
            if (baseline.row() != header.firstRow()) {
                throw invalid("baseline row does not match the pinned first row");
            }
            transactionStarted = true;
            sink.begin();
            sink.header(header);
            sink.baseline(baseline);
            FrameValidator frameValidator = new FrameValidator(baseline.activeServices());
            OriginLedger originLedger = new OriginLedger(header.firstRow(), baseline);
            int expected = header.firstRow();
            while (true) {
                String line = requiredLine(input);
                JsonNode value = object(line, "raw record");
                String type = text(value, "type");
                if ("cutoff".equals(type)) {
                    RawBoundary cutoff = boundary(value, false);
                    if (cutoff.exclusiveEnd() != expected) {
                        throw invalid("cutoff does not follow the contiguous raw rows");
                    }
                    if (requireFull && cutoff.exclusiveEnd() != header.exclusiveEnd()) {
                        throw invalid("full S2 raw capture ended before the pinned exclusive end");
                    }
                    if (requireFull && (!cutoff.activeServices().isEmpty()
                            || !cutoff.pendingDescendants().isEmpty())) {
                        throw invalid("full S2 raw cutoff frontier is not empty");
                    }
                    if (boundedLine(input) != null) throw invalid("raw records follow the cutoff");
                    originLedger.validateCutoff(cutoff);
                    sink.cutoff(cutoff);
                    sink.commit();
                    committed = true;
                    return;
                }
                RawFrame frame = frame(value, frameValidator);
                if (frame.row() != expected || expected >= header.exclusiveEnd()) {
                    throw invalid("S2 raw frame rows are not contiguous and in range");
                }
                originLedger.observe(frame);
                sink.frame(frame);
                expected++;
            }
        } catch (IOException | RuntimeException | Error failure) {
            if (transactionStarted && !committed) {
                try { sink.abort(); }
                catch (IOException | RuntimeException | Error abortFailure) {
                    failure.addSuppressed(abortFailure);
                }
            }
            throw failure;
        }
    }

    private static Header header(JsonNode value) {
        exact(value, "type", "schema", "rom_sha1", "bk2_sha256",
                "service_manifest_sha256", "first_row", "exclusive_end",
                "state_start", "state_exclusive_end");
        require(text(value, "type").equals("metadata"), "first raw record is not metadata");
        require(text(value, "schema").equals(SCHEMA), "S2 raw schema is not pinned");
        require(text(value, "rom_sha1").equals(ROM_SHA1), "S2 raw ROM identity changed");
        require(text(value, "bk2_sha256").equals(BK2_SHA256), "S2 raw BK2 identity changed");
        require(text(value, "service_manifest_sha256").equals(MANIFEST_SHA256),
                "S2 raw service-manifest identity changed");
        int first = integer(value, "first_row");
        int end = integer(value, "exclusive_end");
        int stateStart = integer(value, "state_start");
        int stateEnd = integer(value, "state_exclusive_end");
        require(first == 769 && end == 259_590 && stateStart == 0x0000 && stateEnd == 0x2000,
                "S2 raw interval or driver-state range changed");
        return new Header(first, end, stateStart, stateEnd);
    }

    private static RawBoundary boundary(JsonNode value, boolean baseline) {
        if (baseline) {
            exact(value, "type", "row", "state_hex", "ym_port0_latch", "ym_port1_latch",
                    "native_arm_epoch", "native_armed", "active_services", "pending_descendants");
            require(text(value, "type").equals("baseline"), "second raw record is not baseline");
        } else {
            exact(value, "type", "exclusive_end", "state_hex", "ym_port0_latch", "ym_port1_latch",
                    "native_arm_epoch", "native_armed", "active_services", "pending_descendants");
            require(text(value, "type").equals("cutoff"), "raw terminal record is not cutoff");
        }
        int row = baseline ? integer(value, "row") : -1;
        int end = baseline ? -1 : integer(value, "exclusive_end");
        RawBoundary result = new RawBoundary(row, end, state(value), unsignedByte(value, "ym_port0_latch"),
                unsignedByte(value, "ym_port1_latch"), nonNegativeLong(value, "native_arm_epoch"),
                bool(value, "native_armed"), serviceJson(value, "active_services", 8),
                serviceJson(value, "pending_descendants", MAX_EVENTS));
        if (baseline) {
            require(result.ymPort0Latch() == 0x2a && result.ymPort1Latch() == 0xa1,
                    "S2 raw baseline YM latches changed");
            require(result.nativeArmEpoch() == 1 && result.nativeArmed(),
                    "S2 raw baseline native arm proof changed");
            require(result.activeServices().size() == 1
                            && result.activeServices().getFirst().kind() == 4
                            && !result.activeServices().getFirst().complete()
                            && !result.activeServices().getFirst().cancelled()
                            && result.pendingDescendants().isEmpty(),
                    "S2 raw baseline frontier is not the pinned 1/0 kind-4 shape");
        } else {
            require(result.nativeArmed() && result.nativeArmEpoch() >= 1,
                    "S2 raw cutoff lost its native arm proof");
            require(result.activeServices().stream().noneMatch(RawService::complete)
                            && result.pendingDescendants().stream().allMatch(RawService::complete),
                    "S2 raw cutoff active/pending lifecycle partition changed");
        }
        return result;
    }

    private static RawFrame frame(JsonNode value, FrameValidator validator) {
        exact(value, "type", "row", "lag", "state_hex", "events");
        require(text(value, "type").equals("frame"), "unknown S2 raw record type");
        JsonNode source = value.get("events");
        require(source != null && source.isArray() && source.size() <= MAX_EVENTS,
                "S2 raw event list is not bounded");
        List<RawEvent> events = new ArrayList<>(source.size());
        long expectedOrdinal = 0;
        for (JsonNode event : source) {
            exact(event, "ordinal", "service_token", "parent_token", "pc", "subject", "offset",
                    "kind", "service_kind", "depth", "source_cpu", "payload_length", "value",
                    "flags", "reserved", "payload");
            long ordinal = nonNegativeLong(event, "ordinal");
            require(ordinal == expectedOrdinal++, "S2 raw event ordinals are not contiguous");
            BigInteger payload;
            String payloadText = text(event, "payload");
            require(payloadText.matches("0|[1-9][0-9]*"),
                    "S2 raw payload is not canonical unsigned decimal");
            try { payload = new BigInteger(payloadText); }
            catch (NumberFormatException failure) { throw invalid("S2 raw payload is not unsigned decimal", failure); }
            require(payload.signum() >= 0 && payload.compareTo(MAX_U64) <= 0,
                    "S2 raw payload is outside uint64");
            int kind = unsignedByte(event, "kind");
            int sourceCpu = sourceCpu(event, "source_cpu");
            int payloadLength = unsignedByte(event, "payload_length");
            int flags = unsignedByte(event, "flags");
            int reserved = unsignedByte(event, "reserved");
            require(kind >= 1 && kind <= 11, "S2 raw event kind is outside ABI v3");
            require(payloadLength <= 8, "S2 raw payload length is outside ABI v3");
            require(reserved == 0, "S2 raw reserved field is nonzero");
            require(validFlags(kind, flags), "S2 raw event flags are outside ABI v3");
            require(kind == 6 || payload.signum() == 0,
                    "S2 raw non-snapshot event carries payload bytes");
            int valueByte = unsignedByte(event, "value");
            require(kind == 3 || kind == 4 || kind == 10 || valueByte == 0,
                    "S2 raw event kind carries an unexpected value byte");
            require((kind == 6 && payloadLength >= 1)
                            || (kind != 6 && payloadLength == 0),
                    "S2 raw event payload length disagrees with its ABI kind");
            require(payloadLength == 8 || payload.shiftRight(payloadLength * 8).signum() == 0,
                    "S2 raw payload has nonzero bytes outside its declared length");
            long pc = unsignedInt(event, "pc");
            requirePc(sourceCpu, pc, "event");
            RawEvent parsed = new RawEvent(ordinal, unsignedWord(event, "service_token"),
                    unsignedWord(event, "parent_token"), pc,
                    unsignedWord(event, "subject"), unsignedWord(event, "offset"),
                    kind, serviceKind(event, "service_kind"), depth(event, "depth"),
                    sourceCpu, payloadLength, valueByte, flags, reserved, payload);
            validateEventShape(parsed);
            events.add(parsed);
        }
        validator.validate(events);
        return new RawFrame(integer(value, "row"), bool(value, "lag"), state(value), events);
    }

    private static byte[] state(JsonNode value) {
        String hex = text(value, "state_hex");
        require(hex.length() == 16384 && hex.matches("[0-9a-f]+"),
                "S2 raw state is not exact lowercase $0000..$1FFF hex");
        byte[] bytes = new byte[8192];
        for (int i = 0; i < bytes.length; i++)
            bytes[i] = (byte) Integer.parseInt(hex, i * 2, i * 2 + 2, 16);
        return bytes;
    }

    private static List<RawService> serviceJson(JsonNode value, String field, int maximum) {
        JsonNode array = value.get(field);
        require(array != null && array.isArray() && array.size() <= maximum,
                "S2 raw boundary service list is not bounded");
        List<RawService> result = new ArrayList<>(array.size());
        for (JsonNode service : array) {
            require(service.isObject(), "S2 raw boundary service is not an object");
            exact(service, "token", "parent_token", "kind", "depth", "current_parent_token",
                    "current_depth", "begin_coordinate", "end_coordinate", "begin_row",
                    "begin_native_ordinal", "begin_pc", "end_pc",
                    "begin_hook_token", "end_hook_token", "begin_source_cpu", "cancelled", "complete",
                    "chips", "snapshots", "ancestry_transitions");
            int kind = serviceKind(service, "kind");
            int beginHookToken = unsignedWord(service, "begin_hook_token");
            int sourceCpu = integer(service, "begin_source_cpu");
            long beginPc = unsignedInt(service, "begin_pc");
            long endPc = unsignedInt(service, "end_pc");
            boolean resetService = kind == 1;
            if (resetService) {
                require(sourceCpu == 0 && beginPc == 0 && endPc == 0 && beginHookToken == 0,
                        "S2 raw reset service begin-source shape changed");
            } else {
                require(sourceCpu >= 1 && sourceCpu <= 3,
                        "S2 raw begin_source_cpu is outside ABI v3");
                requirePc(sourceCpu, beginPc, "service begin");
                if (endPc != 0) requirePc(sourceCpu, endPc, "service end");
            }
            int parentToken = unsignedWord(service, "parent_token");
            int serviceDepth = depth(service, "depth");
            int currentParentToken = unsignedWord(service, "current_parent_token");
            int currentDepth = depth(service, "current_depth");
            require((serviceDepth == 0) == (parentToken == 0),
                    "S2 raw frontier immutable ancestry is inconsistent");
            require((currentDepth == 0) == (currentParentToken == 0),
                    "S2 raw frontier effective ancestry is inconsistent");
            require(currentParentToken == parentToken && currentDepth == serviceDepth,
                    "S2 raw frontier effective ancestry differs without promotion hooks");
            boolean complete = bool(service, "complete");
            boolean cancelled = bool(service, "cancelled");
            long beginCoordinate = nonNegativeLong(service, "begin_coordinate");
            int beginRow = integer(service, "begin_row");
            long beginNativeOrdinal = nonNegativeLong(service, "begin_native_ordinal");
            require(beginRow >= 0 && beginNativeOrdinal < MAX_EVENTS,
                    "S2 raw frontier service begin origin is outside ABI v4 bounds");
            long endCoordinate = nonNegativeLong(service, "end_coordinate");
            int endHookToken = unsignedWord(service, "end_hook_token");
            require(!resetService || (parentToken == 0 && serviceDepth == 0
                    && currentParentToken == 0 && currentDepth == 0 && complete
                    && endHookToken == 0), "S2 raw reset service lifecycle shape changed");
            require(!complete || endCoordinate >= beginCoordinate,
                    "S2 raw frontier service ends before it begins");
            require(complete || (!cancelled && endCoordinate == 0 && endPc == 0
                    && endHookToken == 0),
                    "S2 raw open frontier service carries completion state");
            List<RawAncestryTransition> transitions = ancestry(service.get("ancestry_transitions"));
            require(transitions.isEmpty(),
                    "S2 raw ancestry transitions require absent promotion hooks");
            RawService parsed = new RawService(nonZeroWord(service, "token"),
                    parentToken, kind, serviceDepth,
                    currentParentToken, currentDepth, beginCoordinate, endCoordinate,
                    beginRow, beginNativeOrdinal,
                    beginPc, endPc, beginHookToken, endHookToken,
                    sourceCpu, cancelled, complete,
                    chips(service.get("chips")), snapshots(service.get("snapshots")),
                    transitions);
            validateFrontierServiceShape(parsed);
            result.add(parsed);
        }
        if (field.equals("active_services")) validateActiveStack(result);
        return List.copyOf(result);
    }

    private static void validateActiveStack(List<RawService> services) {
        for (int i = 0; i < services.size(); i++) {
            RawService service = services.get(i);
            int parent = i == 0 ? 0 : services.get(i - 1).token();
            require(service.parentToken() == parent && service.depth() == i,
                    "S2 raw active services are not one coherent ordered stack");
        }
    }

    private static List<RawChip> chips(JsonNode array) {
        require(array != null && array.isArray() && array.size() <= MAX_EVENTS,
                "S2 raw frontier chip list is not bounded");
        List<RawChip> result = new ArrayList<>(array.size());
        for (JsonNode chip : array) {
            exact(chip, "coordinate", "native_ordinal", "event_kind", "subject", "value", "pc",
                    "source_cpu", "data", "port", "register");
            int kind = unsignedByte(chip, "event_kind");
            int subject = unsignedByte(chip, "subject");
            int source = sourceCpu(chip, "source_cpu");
            long pc = unsignedInt(chip, "pc");
            requirePc(source, pc, "frontier chip");
            boolean data = bool(chip, "data");
            int port = unsignedByte(chip, "port");
            int register = unsignedByte(chip, "register");
            require(kind == 3 || kind == 4, "S2 raw frontier chip kind is not YM/PSG");
            require(kind != 3 || (subject <= 3 && port == (subject < 2 ? 0 : 1)
                    && data == (subject == 1 || subject == 3)), "S2 raw frontier YM shape changed");
            require(kind != 4 || (subject == 0 && data && port == 0 && register == 0),
                    "S2 raw frontier PSG shape changed");
            result.add(new RawChip(nonNegativeLong(chip, "coordinate"),
                    unsignedInt(chip, "native_ordinal"), kind, subject,
                    unsignedByte(chip, "value"), pc, source, data, port, register));
        }
        return List.copyOf(result);
    }

    private static List<RawSnapshot> snapshots(JsonNode array) {
        require(array != null && array.isArray() && array.size() <= MAX_EVENTS,
                "S2 raw frontier snapshot list is not bounded");
        List<RawSnapshot> result = new ArrayList<>(array.size());
        for (JsonNode snapshot : array) {
            exact(snapshot, "range_id", "source_cpu", "pc", "bytes_hex");
            int range = unsignedWord(snapshot, "range_id");
            require(range == 1 || range == 2, "S2 raw frontier snapshot range is unknown");
            int source = sourceCpu(snapshot, "source_cpu");
            long pc = unsignedInt(snapshot, "pc");
            requirePc(source, pc, "frontier snapshot");
            String hex = text(snapshot, "bytes_hex");
            int expected = range == 1 ? 8192 : 1;
            require(hex.length() == expected * 2 && hex.matches("[0-9a-f]+"),
                    "S2 raw frontier snapshot width changed");
            result.add(new RawSnapshot(range, source, pc, hex(hex)));
        }
        return List.copyOf(result);
    }

    private static List<RawAncestryTransition> ancestry(JsonNode array) {
        require(array != null && array.isArray() && array.size() <= 7,
                "S2 raw frontier ancestry list is not bounded");
        List<RawAncestryTransition> result = new ArrayList<>(array.size());
        for (JsonNode transition : array) {
            exact(transition, "coordinate", "native_ordinal", "previous_parent_token",
                    "previous_depth", "current_parent_token", "current_depth", "hook_token",
                    "source_cpu", "pc");
            int source = sourceCpu(transition, "source_cpu");
            long pc = unsignedInt(transition, "pc");
            requirePc(source, pc, "frontier ancestry");
            int previousParent = unsignedWord(transition, "previous_parent_token");
            int previousDepth = depth(transition, "previous_depth");
            int currentParent = unsignedWord(transition, "current_parent_token");
            int currentDepth = depth(transition, "current_depth");
            require((previousParent == 0) == (previousDepth == 0)
                            && (currentParent == 0) == (currentDepth == 0),
                    "S2 raw ancestry transition parent/depth is inconsistent");
            result.add(new RawAncestryTransition(nonNegativeLong(transition, "coordinate"),
                    unsignedInt(transition, "native_ordinal"),
                    previousParent, previousDepth, currentParent, currentDepth,
                    nonZeroWord(transition, "hook_token"),
                    source, pc));
        }
        return List.copyOf(result);
    }

    private static JsonNode object(String line, String label) {
        try {
            JsonNode value = JSON.readTree(line);
            require(value != null && value.isObject(), label + " is not a JSON object");
            return value;
        } catch (IOException failure) {
            throw invalid(label + " is not strict JSON", failure);
        }
    }

    private static void exact(JsonNode value, String... fields) {
        Set<String> actual = new HashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        require(actual.equals(Set.of(fields)), "S2 raw record fields are not exact");
    }

    private static String text(JsonNode value, String field) {
        JsonNode node = value.get(field);
        require(node != null && node.isTextual(), "S2 raw " + field + " is not text");
        return node.textValue();
    }

    private static int integer(JsonNode value, String field) {
        JsonNode node = value.get(field);
        require(node != null && node.isIntegralNumber() && node.canConvertToInt(),
                "S2 raw " + field + " is not canonical int");
        return node.intValue();
    }

    private static boolean bool(JsonNode value, String field) {
        JsonNode node = value.get(field);
        require(node != null && node.isBoolean(), "S2 raw " + field + " is not boolean");
        return node.booleanValue();
    }

    private static long nonNegativeLong(JsonNode value, String field) {
        JsonNode node = value.get(field);
        require(node != null && node.isIntegralNumber() && node.canConvertToLong()
                        && node.longValue() >= 0,
                "S2 raw " + field + " is not a canonical non-negative long");
        return node.longValue();
    }

    private static int unsignedByte(JsonNode value, String field) {
        int result = integer(value, field);
        require(result >= 0 && result <= 0xff, "S2 raw " + field + " is not uint8");
        return result;
    }

    private static int unsignedWord(JsonNode value, String field) {
        int result = integer(value, field);
        require(result >= 0 && result <= 0xffff, "S2 raw " + field + " is not uint16");
        return result;
    }

    private static int nonZeroWord(JsonNode value, String field) {
        int result = unsignedWord(value, field);
        require(result != 0, "S2 raw " + field + " is zero");
        return result;
    }

    private static int sourceCpu(JsonNode value, String field) {
        int result = unsignedByte(value, field);
        require(result >= 1 && result <= 3, "S2 raw " + field + " is outside ABI v3");
        return result;
    }

    private static int depth(JsonNode value, String field) {
        int result = unsignedByte(value, field);
        require(result <= 7, "S2 raw " + field + " exceeds the ABI stack bound");
        return result;
    }

    private static int serviceKind(JsonNode value, String field) {
        int result = unsignedByte(value, field);
        require(result >= 1 && result <= 9,
                "S2 raw " + field + " is outside the reviewed S2 manifest");
        return result;
    }

    private static void validateEventShape(RawEvent event) {
        require((event.parentToken() == 0) == (event.depth() == 0),
                "S2 raw event parent/depth is inconsistent");
        require(event.serviceToken() != 0 && event.serviceKind() >= 1 && event.serviceKind() <= 9,
                "S2 raw event is not owned by a pinned service kind");
        switch (event.kind()) {
            case 1 -> require(event.subject() != 0 && event.offset() == 0
                            && event.payloadLength() == 0 && event.payload().signum() == 0
                            && event.value() == 0 && event.flags() == 0
                            && beginKind(event.subject()) == event.serviceKind()
                            && beginSource(event.subject()) == event.sourceCpu()
                            && beginPc(event.subject()) == event.pc(),
                    "S2 raw service-begin shape changed");
            case 2 -> {
                require(event.offset() == 0 && event.payloadLength() == 0
                                && event.payload().signum() == 0 && event.value() == 0
                                && (event.flags() == 0 || event.flags() == 2),
                        "S2 raw service-end shape changed");
                if (event.flags() == 2) require(event.subject() == 0 && event.sourceCpu() == 3
                                && event.pc() == 0,
                        "S2 raw reset-cancellation shape changed");
                else require(event.subject() != 0
                                && completionKind(event.subject()) == event.serviceKind()
                                && completionSource(event.subject()) == event.sourceCpu()
                                && completionPc(event.subject()) == event.pc(),
                        "S2 raw service completion has no pinned hook token");
            }
            case 3 -> require(event.subject() <= 3 && event.offset() == 0
                            && event.payloadLength() == 0 && event.payload().signum() == 0
                            && event.flags() == 0,
                    "S2 raw YM event shape changed");
            case 4 -> require(event.subject() == 0 && event.offset() == 0
                            && event.payloadLength() == 0 && event.payload().signum() == 0
                            && event.flags() == 0,
                    "S2 raw PSG event shape changed");
            case 5 -> require((event.subject() == 1 || event.subject() == 2)
                            && event.offset() == 0 && event.payloadLength() == 0
                            && event.payload().signum() == 0 && event.value() == 0
                            && event.flags() == 0,
                    "S2 raw snapshot-begin shape changed");
            case 6 -> require((event.subject() == 1 || event.subject() == 2)
                            && event.payloadLength() >= 1 && event.payloadLength() <= 8
                            && event.offset() + event.payloadLength()
                                <= (event.subject() == 1 ? 8192 : 1)
                            && event.value() == 0 && event.flags() == 0,
                    "S2 raw snapshot-chunk shape changed");
            case 7 -> require((event.subject() == 1 || event.subject() == 2)
                            && event.offset() == (event.subject() == 1 ? 8192 : 1)
                            && event.payloadLength() == 0 && event.payload().signum() == 0
                            && event.value() == 0 && event.flags() == 0,
                    "S2 raw snapshot-end shape changed");
            case 8 -> require(event.serviceKind() == 1 && event.sourceCpu() == 3
                            && event.pc() == 0 && event.parentToken() == 0 && event.depth() == 0
                            && event.offset() == 0 && event.payloadLength() == 0
                            && event.payload().signum() == 0 && event.value() == 0
                            && (event.flags() == 0 || event.flags() == 1),
                    "S2 raw reset-begin shape changed");
            case 9 -> require(event.serviceKind() == 1 && event.sourceCpu() == 3
                            && event.pc() == 0 && event.parentToken() == 0 && event.depth() == 0
                            && event.subject() == 0 && event.offset() == 0
                            && event.payloadLength() == 0 && event.payload().signum() == 0
                            && (event.flags() == 0 || event.flags() == 1),
                    "S2 raw reset-end shape changed");
            case 10 -> {
                require(event.value() >= 0 && event.value() <= 3,
                        "S2 raw marker value is outside ABI v3");
                throw invalid("S2 raw marker has no hook in the pinned S2 manifest");
            }
            case 11 -> require(false,
                    "S2 raw promotion has no hook in the pinned S2 manifest");
            default -> throw invalid("S2 raw event kind is outside ABI v3");
        }
    }

    private static void validateFrontierServiceShape(RawService service) {
        if (service.kind() == 1) return;
        require(beginKind(service.beginHookToken()) == service.kind()
                        && beginSource(service.beginHookToken()) == service.beginSourceCpu()
                        && beginPc(service.beginHookToken()) == service.beginPc(),
                "S2 raw frontier begin is not a pinned S2 hook");
        if (service.complete() && service.cancelled()) require(service.endHookToken() == 0
                        && service.endPc() == 0,
                "S2 raw frontier cancellation shape changed");
        else if (service.complete()) require(completionKind(service.endHookToken()) == service.kind()
                            && completionSource(service.endHookToken()) == service.beginSourceCpu()
                            && completionPc(service.endHookToken()) == service.endPc(),
                    "S2 raw frontier completion is not a pinned S2 hook");
    }

    /** Exact generation ledger joining boundary sidecars to observed ABI-v4 begin events. */
    private static final class OriginLedger {
        private final int firstRow;
        private final Map<Integer, Generation> active = new java.util.LinkedHashMap<>();
        private final Map<Origin, Generation> generations = new java.util.LinkedHashMap<>();
        private final List<Generation> pending = new ArrayList<>();
        private int ymPort0Latch;
        private int ymPort1Latch;
        private long nextCoordinate;

        private OriginLedger(int firstRow, RawBoundary baseline) {
            this.firstRow = firstRow;
            ymPort0Latch = baseline.ymPort0Latch();
            ymPort1Latch = baseline.ymPort1Latch();
            Origin prior = null;
            for (RawService service : baseline.activeServices()) {
                Origin origin = Origin.of(service);
                require(origin.row() < firstRow,
                        "S2 raw baseline service origin is not before the comparison boundary");
                require(prior == null || prior.compareTo(origin) < 0,
                        "S2 raw baseline service origins are not in begin order");
                Generation generation = Generation.fromBoundary(service);
                require(active.putIfAbsent(service.token(), generation) == null
                                && generations.putIfAbsent(origin, generation) == null,
                        "S2 raw baseline service generation is duplicated");
                prior = origin;
            }
            require(baseline.pendingDescendants().isEmpty(),
                    "S2 raw baseline pending generation is unsupported");
        }

        private void observe(RawFrame frame) {
            for (RawEvent event : frame.events()) {
                if (event.kind() == 1 || event.kind() == 8) {
                    Origin origin = new Origin(frame.row(), event.ordinal());
                    Generation parent = event.parentToken() == 0
                            ? null : active.get(event.parentToken());
                    Generation generation = new Generation(event.serviceToken(), event.parentToken(),
                            event.serviceKind(), event.depth(), origin,
                            Math.addExact(nextCoordinate, event.ordinal()),
                            Math.toIntExact(event.pc()), event.kind() == 8 ? 0 : event.subject(),
                            event.sourceCpu(), parent == null ? event.serviceToken() : parent.rootToken,
                            false);
                    require(active.putIfAbsent(event.serviceToken(), generation) == null,
                            "S2 raw service token was reused while its prior generation remained active");
                    require(generations.putIfAbsent(origin, generation) == null,
                            "S2 raw service begin origin is duplicated");
                    if (event.kind() == 8) ymPort0Latch = ymPort1Latch = 0;
                } else if (event.kind() == 3 || event.kind() == 4) {
                    Generation generation = requireActive(event.serviceToken());
                    int port = event.kind() == 4 ? 0 : event.subject() >>> 1;
                    int register = event.kind() == 4 ? 0
                            : port == 0 ? ymPort0Latch : ymPort1Latch;
                    generation.chips.add(new RawChip(
                            Math.addExact(nextCoordinate, event.ordinal()), event.ordinal(), event.kind(),
                            event.subject(), event.value(), event.pc(), event.sourceCpu(),
                            event.kind() == 4 || (event.subject() & 1) != 0, port, register));
                    if (event.kind() == 3 && (event.subject() & 1) == 0) {
                        if (port == 0) ymPort0Latch = event.value();
                        else ymPort1Latch = event.value();
                    }
                } else if (event.kind() == 5) {
                    requireActive(event.serviceToken()).beginSnapshot(event);
                } else if (event.kind() == 6) {
                    requireActive(event.serviceToken()).appendSnapshot(event);
                } else if (event.kind() == 7) {
                    requireActive(event.serviceToken()).endSnapshot(event);
                } else if (event.kind() == 2 || event.kind() == 9) {
                    Generation generation = active.remove(event.serviceToken());
                    require(generation != null,
                            "S2 raw service completion has no active generation");
                    generation.complete(Math.addExact(nextCoordinate, event.ordinal()), event);
                    if (active.containsKey(generation.rootToken)) pending.add(generation);
                    else pending.removeIf(value -> value.rootToken == generation.rootToken);
                }
            }
            nextCoordinate = Math.addExact(nextCoordinate, frame.events().size());
        }

        private Generation requireActive(int token) {
            Generation generation = active.get(token);
            require(generation != null, "S2 raw evidence has no active service generation");
            return generation;
        }

        private void validateCutoff(RawBoundary cutoff) {
            require(cutoff.activeServices().size() == active.size(),
                    "S2 raw cutoff active generation count changed");
            int index = 0;
            for (Generation generation : active.values()) {
                require(generation.matches(cutoff.activeServices().get(index++), false),
                        "S2 raw cutoff does not continue the same active generation");
            }
            List<Generation> expectedPending = pending.stream()
                    .sorted(java.util.Comparator.comparing(value -> value.origin))
                    .toList();
            require(cutoff.pendingDescendants().size() == expectedPending.size(),
                    "S2 raw cutoff pending generation count changed");
            for (int pendingIndex = 0; pendingIndex < expectedPending.size(); pendingIndex++) {
                require(expectedPending.get(pendingIndex).matches(
                                cutoff.pendingDescendants().get(pendingIndex), true),
                        "S2 raw cutoff pending service order or identity changed");
            }
        }
    }

    private record Origin(int row, long ordinal) implements Comparable<Origin> {
        private static Origin of(RawService service) {
            return new Origin(service.beginRow(), service.beginNativeOrdinal());
        }
        @Override public int compareTo(Origin other) {
            int rowOrder = Integer.compare(row, other.row);
            return rowOrder != 0 ? rowOrder : Long.compare(ordinal, other.ordinal);
        }
    }

    private static final class Generation {
        private final int token, parentToken, kind, depth, beginPc, beginHookToken,
                beginSourceCpu, rootToken;
        private final Origin origin;
        private final long coordinate;
        private final List<RawChip> chips = new ArrayList<>();
        private final List<RawSnapshot> snapshots = new ArrayList<>();
        private SnapshotLedger snapshot;
        private long endCoordinate;
        private long endPc;
        private int endHookToken;
        private boolean cancelled;
        private boolean complete;

        private Generation(int token, int parentToken, int kind, int depth,
                Origin origin, long coordinate, int beginPc, int beginHookToken,
                int beginSourceCpu, int rootToken, boolean complete) {
            this.token = token;
            this.parentToken = parentToken;
            this.kind = kind;
            this.depth = depth;
            this.origin = origin;
            this.coordinate = coordinate;
            this.beginPc = beginPc;
            this.beginHookToken = beginHookToken;
            this.beginSourceCpu = beginSourceCpu;
            this.rootToken = rootToken;
            this.complete = complete;
        }

        private static Generation fromBoundary(RawService service) {
            Generation generation = new Generation(service.token(), service.parentToken(), service.kind(),
                    service.depth(), Origin.of(service), service.beginCoordinate(),
                    Math.toIntExact(service.beginPc()), service.beginHookToken(),
                    service.beginSourceCpu(), service.token(), service.complete());
            generation.endCoordinate = service.endCoordinate();
            generation.endPc = service.endPc();
            generation.endHookToken = service.endHookToken();
            generation.cancelled = service.cancelled();
            generation.chips.addAll(service.chips());
            generation.snapshots.addAll(service.snapshots());
            return generation;
        }

        private void beginSnapshot(RawEvent event) {
            snapshot = new SnapshotLedger(event.subject(), event.sourceCpu(), event.pc());
        }

        private void appendSnapshot(RawEvent event) {
            for (int index = 0; index < event.payloadLength(); index++) {
                snapshot.bytes.write(event.payload().shiftRight(index * 8).byteValue());
            }
        }

        private void endSnapshot(RawEvent event) {
            snapshots.add(new RawSnapshot(snapshot.rangeId, snapshot.sourceCpu,
                    snapshot.pc, snapshot.bytes.toByteArray()));
            snapshot = null;
        }

        private void complete(long coordinate, RawEvent event) {
            endCoordinate = coordinate;
            endPc = event.pc();
            endHookToken = event.kind() == 9 || event.flags() == 2 ? 0 : event.subject();
            cancelled = event.flags() == 2;
            complete = true;
        }

        private boolean matches(RawService service, boolean requireComplete) {
            return token == service.token() && parentToken == service.parentToken()
                    && kind == service.kind() && depth == service.depth()
                    && origin.equals(Origin.of(service)) && coordinate == service.beginCoordinate()
                    && beginPc == service.beginPc() && beginHookToken == service.beginHookToken()
                    && beginSourceCpu == service.beginSourceCpu()
                    && parentToken == service.currentParentToken()
                    && depth == service.currentDepth()
                    && endCoordinate == service.endCoordinate() && endPc == service.endPc()
                    && endHookToken == service.endHookToken()
                    && cancelled == service.cancelled()
                    && sameChips(chips, service.chips())
                    && sameSnapshots(snapshots, service.snapshots())
                    && service.ancestryTransitions().isEmpty()
                    && (!requireComplete || complete && service.complete());
        }

        private static boolean sameChips(List<RawChip> expected, List<RawChip> actual) {
            return expected.equals(actual);
        }

        private static boolean sameSnapshots(List<RawSnapshot> expected, List<RawSnapshot> actual) {
            if (expected.size() != actual.size()) return false;
            for (int index = 0; index < expected.size(); index++) {
                RawSnapshot left = expected.get(index);
                RawSnapshot right = actual.get(index);
                if (left.rangeId() != right.rangeId() || left.sourceCpu() != right.sourceCpu()
                        || left.pc() != right.pc()
                        || !java.util.Arrays.equals(left.bytes(), right.bytes())) return false;
            }
            return true;
        }
    }

    private static final class SnapshotLedger {
        private final int rangeId, sourceCpu;
        private final long pc;
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        private SnapshotLedger(int rangeId, int sourceCpu, long pc) {
            this.rangeId = rangeId;
            this.sourceCpu = sourceCpu;
            this.pc = pc;
        }
    }

    private static int beginKind(int hook) {
        return switch (hook) {
            case 9 -> 2; case 1, 2, 16, 19 -> 3; case 5, 13, 20 -> 4;
            case 7 -> 5; case 11 -> 6; case 14, 15 -> 7; case 17, 18 -> 8;
            case 21 -> 9;
            default -> -1;
        };
    }

    private static int beginSource(int hook) {
        return hook == 7 || hook == 9 ? 2 : beginKind(hook) > 0 ? 1 : -1;
    }

    private static long beginPc(int hook) {
        return switch (hook) {
            case 11 -> 0; case 1, 2 -> 56; case 21 -> 272; case 5, 13, 20 -> 378;
            case 14, 15 -> 1808; case 17, 18 -> 4744; case 7 -> 638; case 9 -> 966656;
            default -> -1;
        };
    }

    private static int completionKind(int hook) {
        return switch (hook) {
            case 10 -> 2; case 3, 4, 13, 14, 17 -> 3; case 6, 20 -> 4;
            case 8 -> 5; case 12 -> 6; case 15, 16 -> 7; case 18, 19 -> 8;
            case 22, 23 -> 9; default -> -1;
        };
    }

    private static int completionSource(int hook) {
        return hook == 8 || hook == 10 ? 2 : completionKind(hook) > 0 ? 1 : -1;
    }

    private static long completionPc(int hook) {
        return switch (hook) {
            case 3 -> 231; case 4 -> 271; case 22 -> 331; case 12 -> 369;
            case 6 -> 432; case 23 -> 3508; case 13, 20 -> 378;
            case 14, 15 -> 1808; case 16 -> 1842; case 17, 18 -> 4744;
            case 19 -> 4860; case 8 -> 648; case 10 -> 966710; default -> -1;
        };
    }

    private static final int PUSH_BEGIN = 1;
    private static final int POP_END = 2;
    private static final int TAIL_POP_PUSH = 4;
    private static final Map<Integer, List<Integer>> KIND_RANGES = Map.of(
            1, List.of(2), 2, List.of(1), 3, List.of(2),
            4, List.of(2), 5, List.of(2), 6, List.of(2),
            7, List.of(2), 8, List.of(2), 9, List.of(2));
    private static final Map<Integer, Hook> HOOKS = Map.ofEntries(
            hook(11, PUSH_BEGIN, 0, 6, 1, 0),
            hook(1, PUSH_BEGIN, 0, 3, 1, 56),
            hook(2, PUSH_BEGIN, 4, 3, 1, 56),
            hook(3, POP_END, 3, 0, 1, 231, 2),
            hook(4, POP_END, 3, 0, 1, 271, 2),
            hook(21, PUSH_BEGIN, 3, 9, 1, 272),
            hook(22, POP_END, 9, 0, 1, 331, 2),
            hook(12, POP_END, 6, 0, 1, 369, 2),
            hook(5, PUSH_BEGIN, 0, 4, 1, 378),
            hook(13, TAIL_POP_PUSH, 3, 4, 1, 378, 2),
            hook(20, TAIL_POP_PUSH, 4, 4, 1, 378, 2),
            hook(6, POP_END, 4, 0, 1, 432, 2),
            hook(14, TAIL_POP_PUSH, 3, 7, 1, 1808, 2),
            hook(15, TAIL_POP_PUSH, 7, 7, 1, 1808, 2),
            hook(16, TAIL_POP_PUSH, 7, 3, 1, 1842, 2),
            hook(23, POP_END, 9, 0, 1, 3508, 2),
            hook(17, TAIL_POP_PUSH, 3, 8, 1, 4744, 2),
            hook(18, TAIL_POP_PUSH, 8, 8, 1, 4744, 2),
            hook(19, TAIL_POP_PUSH, 8, 3, 1, 4860, 2),
            hook(7, PUSH_BEGIN, 0, 5, 2, 638),
            hook(8, POP_END, 5, 0, 2, 648, 2),
            hook(9, PUSH_BEGIN, 0, 2, 2, 966656),
            hook(10, POP_END, 2, 0, 2, 966710, 1));

    private static Map.Entry<Integer, Hook> hook(int token, int action, int expectedKind,
            int serviceKind, int sourceCpu, long pc, int... ranges) {
        return Map.entry(token, new Hook(token, action, expectedKind, serviceKind,
                sourceCpu, pc, List.of(java.util.Arrays.stream(ranges).boxed().toArray(Integer[]::new))));
    }

    private record Hook(int token, int action, int expectedKind, int serviceKind,
            int sourceCpu, long pc, List<Integer> ranges) { }

    /** Cross-event ABI state that must be complete at every native frame boundary. */
    private static final class FrameValidator {
        private final List<Owner> activeServices = new ArrayList<>();

        private FrameValidator(List<RawService> initialActiveServices) {
            for (RawService service : initialActiveServices) activeServices.add(
                    new Owner(service.token(), service.currentParentToken(),
                            service.kind(), service.currentDepth()));
        }

        private void validate(List<RawEvent> events) {
            RawEvent snapshot = null;
            Owner snapshotOwner = null;
            int snapshotLength = 0;
            int snapshotOffset = 0;
            Owner reset = null;
            int resetPower = 0;
            Hook pendingTail = null;
            for (RawEvent event : events) {
                if (snapshot != null && event.kind() != 6 && event.kind() != 7) {
                    throw invalid("S2 raw snapshot events are not contiguous");
                }
                if (pendingTail != null && (event.kind() != 1
                        || event.subject() != pendingTail.token())) {
                    throw invalid("S2 raw tail completion has no adjacent manifest begin");
                }
                switch (event.kind()) {
                    case 1 -> {
                        require(reset == null, "S2 raw service begins during reset cancellation");
                        Hook hook = requireHook(event.subject());
                        require(hook.action() == PUSH_BEGIN || hook.action() == TAIL_POP_PUSH,
                                "S2 raw service begin does not name a begin hook");
                        if (hook.action() == TAIL_POP_PUSH) {
                            require(pendingTail == hook,
                                    "S2 raw tail begin lacks its adjacent completion");
                        } else {
                            require(pendingTail == null && topKind() == hook.expectedKind(),
                                    "S2 raw begin expected-active-kind changed");
                        }
                        int parent = activeServices.isEmpty() ? 0
                                : activeServices.getLast().token;
                        require(event.parentToken() == parent
                                        && event.depth() == activeServices.size()
                                        && event.serviceKind() == hook.serviceKind()
                                        && event.sourceCpu() == hook.sourceCpu()
                                        && event.pc() == hook.pc(),
                                "S2 raw service begin does not match its manifest hook/parent");
                        activeServices.add(Owner.of(event));
                        pendingTail = null;
                    }
                    case 2 -> {
                        require(!activeServices.isEmpty()
                                        && activeServices.getLast().matches(event),
                                "S2 raw service end does not own the active stack top");
                        Owner owner = activeServices.getLast();
                        if (reset != null) require(event.flags() == 2,
                                "S2 raw non-cancelled service end occurs during reset");
                        if (event.flags() == 2) {
                            require(reset != null, "S2 raw cancellation occurs outside a reset");
                            owner.requireSnapshots(canonicalRanges(owner.kind), event.sourceCpu(), event.pc());
                        } else {
                            Hook hook = requireHook(event.subject());
                            require((hook.action() == POP_END || hook.action() == TAIL_POP_PUSH)
                                            && hook.expectedKind() == owner.kind
                                            && event.sourceCpu() == hook.sourceCpu()
                                            && event.pc() == hook.pc(),
                                    "S2 raw service end does not match its manifest hook");
                            owner.requireSnapshots(hook.ranges(), event.sourceCpu(), event.pc());
                            if (hook.action() == TAIL_POP_PUSH) pendingTail = hook;
                        }
                        activeServices.removeLast();
                    }
                    case 3, 4 -> require(owned(event, activeServices, reset),
                            "S2 raw chip event is not owned by the active service");
                    case 5 -> {
                        snapshotOwner = owner(event, activeServices, reset);
                        require(snapshotOwner != null,
                                "S2 raw snapshot begin is not owned by the active service");
                        require(snapshot == null, "S2 raw snapshot begin overlaps another snapshot");
                        require(canonicalRanges(snapshotOwner.kind).contains(event.subject()),
                                "S2 raw snapshot range is outside its owning service manifest slice");
                        snapshot = event;
                        snapshotLength = event.subject() == 1 ? 8192 : 1;
                        snapshotOffset = 0;
                    }
                    case 6 -> {
                        require(snapshot != null, "S2 raw snapshot chunk has no begin");
                        sameSnapshotOwner(snapshot, event);
                        require(event.offset() == snapshotOffset,
                                "S2 raw snapshot chunks are not contiguous");
                        snapshotOffset += event.payloadLength();
                    }
                    case 7 -> {
                        require(snapshot != null, "S2 raw snapshot end has no begin");
                        sameSnapshotOwner(snapshot, event);
                        require(snapshotOffset == snapshotLength && event.offset() == snapshotLength,
                                "S2 raw snapshot byte count changed");
                        snapshotOwner.snapshots.add(new SnapshotEvidence(event.subject(),
                                event.sourceCpu(), event.pc()));
                        snapshot = null;
                        snapshotOwner = null;
                    }
                    case 8 -> {
                        require(reset == null && event.subject() == activeServices.size(),
                                "S2 raw reset begin active-service count changed");
                        reset = Owner.of(event);
                        resetPower = event.flags();
                    }
                    case 9 -> {
                        require(reset != null && activeServices.isEmpty()
                                        && reset.matches(event)
                                        && event.flags() == resetPower,
                                "S2 raw reset begin/end pairing changed");
                        reset.requireSnapshots(canonicalRanges(1), event.sourceCpu(), event.pc());
                        reset = null;
                    }
                    default -> { }
                }
            }
            require(snapshot == null, "S2 raw snapshot stream ended before END");
            require(reset == null, "S2 raw reset stream ended before reset end");
            require(pendingTail == null, "S2 raw tail completion ended without its begin");
            require(activeServices.stream().allMatch(owner -> owner.snapshots.isEmpty()),
                    "S2 raw manifest snapshot is not adjacent to service completion");
        }

        private int topKind() {
            return activeServices.isEmpty() ? 0 : activeServices.getLast().kind;
        }

        private static void sameSnapshotOwner(RawEvent begin, RawEvent event) {
            require(event.serviceToken() == begin.serviceToken()
                            && event.parentToken() == begin.parentToken()
                            && event.serviceKind() == begin.serviceKind()
                            && event.depth() == begin.depth()
                            && event.subject() == begin.subject()
                            && event.sourceCpu() == begin.sourceCpu()
                            && event.pc() == begin.pc(),
                    "S2 raw snapshot owner/range/source changed mid-stream");
        }

        private static Owner owner(RawEvent event, List<Owner> active, Owner reset) {
            if (reset != null && reset.matches(event)) return reset;
            if (!active.isEmpty() && active.getLast().matches(event)) return active.getLast();
            return null;
        }

        private static boolean owned(RawEvent event, List<Owner> active, Owner reset) {
            return owner(event, active, reset) != null;
        }

        private static final class Owner {
            private final int token;
            private final int parentToken;
            private final int kind;
            private final int depth;
            private final List<SnapshotEvidence> snapshots = new ArrayList<>();

            private Owner(int token, int parentToken, int kind, int depth) {
                this.token = token;
                this.parentToken = parentToken;
                this.kind = kind;
                this.depth = depth;
            }

            private static Owner of(RawEvent event) {
                return new Owner(event.serviceToken(), event.parentToken(),
                        event.serviceKind(), event.depth());
            }

            private boolean matches(RawEvent event) {
                return token == event.serviceToken() && parentToken == event.parentToken()
                        && kind == event.serviceKind() && depth == event.depth();
            }

            private void requireSnapshots(List<Integer> ranges, int sourceCpu, long pc) {
                require(snapshots.size() == ranges.size(),
                        "S2 raw service is missing its exact manifest snapshot slice");
                for (int i = 0; i < ranges.size(); i++) {
                    SnapshotEvidence snapshot = snapshots.get(i);
                    require(snapshot.range() == ranges.get(i)
                                    && snapshot.sourceCpu() == sourceCpu && snapshot.pc() == pc,
                            "S2 raw service snapshot range/source/PC differs from its manifest hook");
                }
                snapshots.clear();
            }
        }

        private record SnapshotEvidence(int range, int sourceCpu, long pc) { }
    }

    private static Hook requireHook(int token) {
        Hook hook = HOOKS.get(token);
        require(hook != null, "S2 raw event hook is absent from the pinned manifest");
        return hook;
    }

    private static List<Integer> canonicalRanges(int serviceKind) {
        List<Integer> ranges = KIND_RANGES.get(serviceKind);
        require(ranges != null, "S2 raw service kind has no canonical manifest range slice");
        return ranges;
    }

    private static boolean validFlags(int kind, int flags) {
        return switch (kind) {
            case 2 -> flags == 0 || flags == 2;
            case 8, 9 -> flags == 0 || flags == 1;
            default -> flags == 0;
        };
    }

    private static void requirePc(int sourceCpu, long pc, String label) {
        require((sourceCpu == 1 && pc <= 0xffffL)
                        || (sourceCpu == 2 && pc <= 0xff_ffffL)
                        || (sourceCpu == 3 && pc == 0),
                "S2 raw " + label + " PC is outside its ABI source CPU");
    }

    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int index = 0; index < result.length; index++)
            result[index] = (byte) Integer.parseInt(value, index * 2, index * 2 + 2, 16);
        return result;
    }

    private static long unsignedInt(JsonNode value, String field) {
        JsonNode node = value.get(field);
        require(node != null && node.isIntegralNumber() && node.canConvertToLong(),
                "S2 raw " + field + " is not uint32");
        long result = node.longValue();
        require(result >= 0 && result <= 0xffff_ffffL, "S2 raw " + field + " is not uint32");
        return result;
    }

    private static String requiredLine(BufferedReader input) throws IOException {
        String line = boundedLine(input);
        if (line == null) throw invalid("S2 raw staging stream ended early");
        return line;
    }

    private static String boundedLine(BufferedReader input) throws IOException {
        StringBuilder line = new StringBuilder(8192);
        int value;
        while ((value = input.read()) >= 0) {
            if (value == '\n') return line.toString();
            if (value == '\r') throw invalid("S2 raw staging requires LF line endings");
            if (line.length() == MAX_LINE_CHARACTERS) throw invalid("S2 raw record exceeds its bound");
            line.append((char) value);
        }
        return line.isEmpty() ? null : line.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw invalid(message);
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private static IllegalArgumentException invalid(String message, Throwable cause) {
        return new IllegalArgumentException(message, cause);
    }
}
