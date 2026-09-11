package com.openggf.graphics;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TestWindowIconLoader {

    @Test
    void macosUsesCocoaInsteadOfUnsupportedGlfwWindowIcons() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/openggf/graphics/WindowIconLoader.java"));

        assertTrue(source.contains("applyCocoaApplicationIcon"),
                "macOS must set its application icon through Cocoa");
        assertTrue(source.contains("isMacOs"),
                "window icon routing must explicitly identify macOS");
        int macBranch = source.indexOf("if (isMacOs())");
        int macReturn = source.indexOf("return;", macBranch);
        int glfwCall = source.indexOf("glfwSetWindowIcon(window, icons);");
        assertTrue(macBranch >= 0 && macReturn > macBranch && glfwCall > macReturn,
                "macOS must return before the GLFW call that emits GLFW_FEATURE_UNAVAILABLE");
    }

    @Test
    void macosBundleBuildsAnIcnsFromThePackagedIconWithoutAwt() throws Exception {
        String script = Files.readString(Path.of("src/packaging/assemble-macos-app.sh"));

        assertTrue(script.contains("sips -s format icns"),
                "the app assembler must create the .icns referenced by Info.plist");
        assertTrue(script.contains("openggf-256.png"),
                "the app assembler must derive its icon from the packaged OpenGGF artwork");
        assertTrue(!script.contains("java.awt"),
                "macOS icon packaging must not depend on AWT");
    }
}
