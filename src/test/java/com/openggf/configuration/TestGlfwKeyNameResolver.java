package com.openggf.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

class TestGlfwKeyNameResolver {

    @Test
    void nativeImageMetadataRetainsGlfwKeyFieldsForSymbolicConfiguration() throws IOException {
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "META-INF/native-image/com.openggf/OpenGGF/reflect-config.json")) {
            assertNotNull(input, "Native-image reflection metadata must be packaged");
            String metadata = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(metadata.contains("\"name\": \"org.lwjgl.glfw.GLFW\""));
            assertTrue(metadata.contains("\"allDeclaredFields\": true"));
        }
    }

    // --- resolve() tests ---

    @ParameterizedTest
    @CsvSource({
        "Q,          81",   // GLFW_KEY_Q
        "q,          81",   // case insensitive
        "D,          68",   // GLFW_KEY_D
        "SPACE,      32",   // GLFW_KEY_SPACE
        "space,      32",   // case insensitive named key
        "ENTER,      257",  // GLFW_KEY_ENTER
        "TAB,        258",  // GLFW_KEY_TAB
        "F9,         298",  // GLFW_KEY_F9
        "F12,        301",  // GLFW_KEY_F12
        "LEFT_SHIFT, 340",  // GLFW_KEY_LEFT_SHIFT
        "LEFT,       263",  // GLFW_KEY_LEFT (arrow)
        "RIGHT,      262",  // GLFW_KEY_RIGHT (arrow)
        "PAGE_UP,    266",  // GLFW_KEY_PAGE_UP
        "DELETE,     261",  // GLFW_KEY_DELETE
    })
    void resolve_keyNames(String name, int expectedCode) {
        OptionalInt result = GlfwKeyNameResolver.resolve(name);
        assertTrue(result.isPresent(), "Expected '" + name + "' to resolve");
        assertEquals(expectedCode, result.getAsInt());
    }

    @ParameterizedTest
    @CsvSource({
        "GLFW_KEY_Q,          81",
        "GLFW_KEY_SPACE,      32",
        "glfw_key_enter,      257",
    })
    void resolve_stripsGlfwKeyPrefix(String name, int expectedCode) {
        OptionalInt result = GlfwKeyNameResolver.resolve(name);
        assertTrue(result.isPresent(), "Expected '" + name + "' to resolve");
        assertEquals(expectedCode, result.getAsInt());
    }

    @ParameterizedTest
    @CsvSource({
        "KEY_Q,          81",
        "KEY_SPACE,      32",
        "key_f9,         298",
    })
    void resolve_stripsKeyPrefix(String name, int expectedCode) {
        OptionalInt result = GlfwKeyNameResolver.resolve(name);
        assertTrue(result.isPresent(), "Expected '" + name + "' to resolve");
        assertEquals(expectedCode, result.getAsInt());
    }

    @Test
    void resolve_invalidName_returnsEmpty() {
        assertTrue(GlfwKeyNameResolver.resolve("banana").isEmpty());
        assertTrue(GlfwKeyNameResolver.resolve("ZZZZ").isEmpty());
        assertTrue(GlfwKeyNameResolver.resolve("").isEmpty());
    }

    @Test
    void resolve_null_returnsEmpty() {
        assertTrue(GlfwKeyNameResolver.resolve(null).isEmpty());
    }

    @Test
    void resolve_unknown_returnsEmpty() {
        // GLFW_KEY_UNKNOWN (-1) is filtered out to avoid collision with the "unbound" sentinel
        assertTrue(GlfwKeyNameResolver.resolve("UNKNOWN").isEmpty());
    }

    @Test
    void resolve_hashAlias_returnsWorld1() {
        OptionalInt result = GlfwKeyNameResolver.resolve("#");
        assertTrue(result.isPresent(), "Expected '#' to resolve to the UK hash key alias");
        assertEquals(GLFW_KEY_WORLD_1, result.getAsInt());
    }

    // --- nameOf() tests ---

    @Test
    void nameOf_letter() {
        assertEquals("Q", GlfwKeyNameResolver.nameOf(GLFW_KEY_Q));
    }

    @Test
    void nameOf_namedKey() {
        assertEquals("SPACE", GlfwKeyNameResolver.nameOf(GLFW_KEY_SPACE));
        assertEquals("ENTER", GlfwKeyNameResolver.nameOf(GLFW_KEY_ENTER));
    }

    @Test
    void nameOf_functionKey() {
        assertEquals("F9", GlfwKeyNameResolver.nameOf(GLFW_KEY_F9));
    }

    @Test
    void nameOf_modifierKey() {
        assertEquals("LEFT_SHIFT", GlfwKeyNameResolver.nameOf(GLFW_KEY_LEFT_SHIFT));
    }

    @Test
    void nameOf_unknownCode_returnsNumericString() {
        assertEquals("99999", GlfwKeyNameResolver.nameOf(99999));
    }
}
