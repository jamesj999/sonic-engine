package com.openggf.tests;

import com.openggf.version.AppVersion;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class TestAppVersion {

    @Test
    public void testVersionResourceExistsOnClasspath() throws Exception {
        try (InputStream input = AppVersion.class.getResourceAsStream("/version.properties")) {
            assertNotNull(input, "version.properties should be present on the classpath");
        }
    }

    @Test
    public void testResolvedVersionIsNotBlank() {
        assertNotNull(AppVersion.get(), "App version should resolve");
        assertFalse(AppVersion.get().trim().isEmpty(), "App version should not be blank");
    }

    @Test
    public void testMavenBuildDoesNotUseDevFallback() {
        assertNotEquals("dev", AppVersion.get(), "Maven test runs should resolve the filtered project version");
    }

    @Test
    public void testBlankVersionContentFallsBackToDev() throws Exception {
        assertEquals("dev", invokeLoadVersion(""));
    }

    @Test
    public void testMalformedVersionContentFallsBackToDev() throws Exception {
        assertEquals("dev", invokeLoadVersion("app.version=bad\\u12"));
    }

    private static String invokeLoadVersion(String content) throws Exception {
        Method method = AppVersion.class.getDeclaredMethod("loadVersion", InputStream.class);
        method.setAccessible(true);
        try (ByteArrayInputStream input = new ByteArrayInputStream(content.getBytes(StandardCharsets.ISO_8859_1))) {
            return (String) method.invoke(null, input);
        }
    }
}
