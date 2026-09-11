package com.openggf.audio.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Strict loader for independently transcribed physical-write fixtures. */
public final class ExactWriteProgramFixture {
    private static final String SCHEMA =
            "openggf.smps_exact_write_program.v1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PINNED_COMMIT =
            "044fa46725c71187399e13f5ddb70e11d32dc024";
    private static final String PINNED_PATH =
            "Sound/Z80 Sound Driver.asm";
    private static final Set<String> ROOT_FIELDS =
            Set.of("schema", "source", "writes");
    private static final Set<String> SOURCE_FIELDS = Set.of(
            "submodule_commit", "path", "routine", "fix_sndbugs");
    private static final Set<String> WRITE_FIELDS = Set.of(
            "chip", "port", "register", "value");

    private ExactWriteProgramFixture() {
    }

    public static List<SmpsChipWrite> load(String resource) {
        try (InputStream input = ExactWriteProgramFixture.class
                .getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalArgumentException(
                        "missing exact-write fixture " + resource);
            }
            return parse(input);
        } catch (IOException failure) {
            throw new IllegalArgumentException(
                    "cannot read exact-write fixture " + resource,
                    failure);
        }
    }

    static List<SmpsChipWrite> parse(InputStream input) throws IOException {
        JsonNode root = JSON.readTree(input);
        requireObjectWithFields(root, ROOT_FIELDS, "root");
        if (!SCHEMA.equals(root.path("schema").asText())) {
            throw new IllegalArgumentException(
                    "unsupported exact-write fixture schema");
        }
        JsonNode source = root.path("source");
        requireObjectWithFields(source, SOURCE_FIELDS, "source");
        if (!PINNED_COMMIT.equals(
                    source.path("submodule_commit").asText())
                || !PINNED_PATH.equals(source.path("path").asText())
                || !"zStopAllSound".equals(
                        source.path("routine").asText())
                || !source.path("fix_sndbugs").canConvertToInt()
                || source.path("fix_sndbugs").asInt() != 0) {
            throw new IllegalArgumentException(
                    "invalid exact-write fixture source attribution");
        }
        JsonNode writes = root.path("writes");
        if (!writes.isArray()) {
            throw new IllegalArgumentException(
                    "exact-write fixture writes must be an array");
        }
        List<SmpsChipWrite> result = new ArrayList<>(writes.size());
        for (int index = 0; index < writes.size(); index++) {
            JsonNode write = writes.get(index);
            requireObjectWithFields(write, WRITE_FIELDS,
                    "writes[" + index + "]");
            String chip = write.path("chip").asText();
            int port = exactByte(write, "port", index);
            int register = exactByte(write, "register", index);
            int value = exactByte(write, "value", index);
            switch (chip) {
                case "YM2612" -> {
                    if (port > 1) {
                        throw new IllegalArgumentException(
                                "YM2612 port outside 0..1 at write " + index);
                    }
                    result.add(new SmpsChipWrite.Ym2612(
                            port, register, value));
                }
                case "PSG" -> {
                    if (port != 0 || register != 0) {
                        throw new IllegalArgumentException(
                                "PSG tuples require zero port/register at write "
                                        + index);
                    }
                    result.add(new SmpsChipWrite.Psg(value));
                }
                default -> throw new IllegalArgumentException(
                        "unknown write kind " + chip + " at write " + index);
            }
        }
        return List.copyOf(result);
    }

    private static int exactByte(
            JsonNode write, String field, int index) {
        JsonNode value = write.path(field);
        if (!value.isIntegralNumber()
                || !value.canConvertToInt()
                || value.asInt() < 0 || value.asInt() > 0xFF) {
            throw new IllegalArgumentException(field
                    + " outside byte range at write " + index);
        }
        return value.asInt();
    }

    private static void requireObjectWithFields(
            JsonNode node, Set<String> expected, String location) {
        if (!node.isObject()) {
            throw new IllegalArgumentException(location
                    + " must be an object");
        }
        Set<String> actual = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(location
                    + " fields must be exactly " + expected);
        }
    }
}
