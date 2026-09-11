package com.openggf;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TestEngineResourceLifetime {
    private static final Path ENGINE_SOURCE = Path.of("src", "main", "java", "com", "openggf", "Engine.java");
    private static final Path OPENAL_SINK_SOURCE = Path.of(
            "src", "main", "java", "com", "openggf", "audio", "output", "OpenAlPcmSink.java");

    @Test
    void runEntersCleanupScopeBeforeStartupInit() throws IOException {
        String runBody = methodBody(Files.readString(ENGINE_SOURCE), "run");

        int tryIndex = runBody.indexOf("try");
        int initIndex = runBody.indexOf("init()");
        int cleanupIndex = runBody.indexOf("cleanup");

        assertTrue(tryIndex >= 0 && initIndex > tryIndex && cleanupIndex > initIndex,
                "Engine.run() must put init() inside the try/finally cleanup scope");
    }

    @Test
    void openAlSinkInitCatchReleasesPartialNativeAllocationsBeforeRethrow() throws IOException {
        String source = Files.readString(OPENAL_SINK_SOURCE);
        assertTrue(source.contains("catch (Throwable failure) {\n"
                        + "            closeDeviceAfterFailure(failure);"),
                "OpenAlPcmSink construction must release partially initialized native resources");
    }

    private static String methodBody(String source, String methodName) {
        int methodIndex = source.indexOf(methodName + "()");
        if (methodIndex < 0) {
            throw new AssertionError("Could not find method " + methodName);
        }
        int openBrace = source.indexOf('{', methodIndex);
        if (openBrace < 0) {
            throw new AssertionError("Could not find method body for " + methodName);
        }
        return balancedBody(source, openBrace);
    }

    private static String balancedBody(String source, int openBrace) {
        int depth = 0;
        for (int i = openBrace; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(openBrace + 1, i);
                }
            }
        }
        throw new AssertionError("Could not find matching brace");
    }
}
