package com.openggf.trace.timing;

import com.fasterxml.jackson.databind.JsonNode;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareWorkKind;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Strict field mechanics only; record schemas and stream identity belong to each loader. */
final class StrictTimingFields {
    private StrictTimingFields() {
    }

    static String requireText(Path path, int line, JsonNode node, String field, String exact)
            throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || (exact != null && !exact.equals(value.textValue()))) {
            throw rejected(path, "line " + line + " has invalid " + field);
        }
        return value.textValue();
    }

    static int requireInt(Path path, int line, JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.isInt()) {
            throw rejected(path, "line " + line + " has invalid " + field);
        }
        return value.intValue();
    }

    static long requireOrdinal(Path path, int line, JsonNode node) throws IOException {
        JsonNode value = node.get("ordinal");
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw rejected(path, "line " + line + " has invalid ordinal");
        }
        return value.longValue();
    }

    static HardwareServiceBoundary parseBoundary(Path path, int line, String wireName)
            throws IOException {
        try {
            return HardwareServiceBoundary.fromWireName(wireName);
        } catch (IllegalArgumentException e) {
            throw rejected(path, "line " + line + " has invalid boundary");
        }
    }

    static HardwareWorkKind parseKind(Path path, int line, String wireName) throws IOException {
        try {
            return HardwareWorkKind.fromWireName(wireName);
        } catch (IllegalArgumentException e) {
            throw rejected(path, "line " + line + " has invalid kind");
        }
    }

    static String decodeUtf8(Path path, String fileName) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw rejected(path, fileName + " must be valid UTF-8");
        }
    }

    static IOException rejected(Path path, String reason) {
        return new IOException(path.getFileName() + ": " + reason);
    }

}
