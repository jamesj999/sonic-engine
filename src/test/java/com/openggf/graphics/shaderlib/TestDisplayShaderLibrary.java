package com.openggf.graphics.shaderlib;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestDisplayShaderLibrary {

    @TempDir
    Path tempDir;

    @Test
    public void missingRootReturnsOnlyOff() {
        DisplayShaderLibrary library = DisplayShaderLibrary.scan(tempDir.resolve("missing"));

        assertEquals(1, library.size());
        assertEquals(DisplayShaderPresetRef.OFF, library.at(0));
        assertEquals(List.of("Off"), labels(library));
    }

    @Test
    public void scanSortsPresetsAndUnreferencedGlslAfterOff() throws IOException {
        Path root = tempDir.resolve("display-shaders");
        write(root.resolve("BizHawk/BizScanlines.cgp"), "# preset\n");
        write(root.resolve("Custom/warm.glsl"), "void main() {}\n");
        write(root.resolve("RetroArch/shaders_glsl/scanlines/scanline.glslp"),
                "shader0 = shaders/scanline.glsl\n");
        write(root.resolve("RetroArch/shaders_glsl/scanlines/shaders/scanline.glsl"), "void main() {}\n");

        DisplayShaderLibrary library = DisplayShaderLibrary.scan(root);

        assertEquals(List.of(
                "Off",
                "BizHawk/BizScanlines",
                "Custom/warm",
                "RetroArch/shaders_glsl/scanlines/scanline"), labels(library));
    }

    @Test
    public void rootNamedShadersDoesNotFilterEverything() throws IOException {
        Path root = tempDir.resolve("shaders");
        write(root.resolve("plain.glsl"), "void main() {}\n");

        DisplayShaderLibrary library = DisplayShaderLibrary.scan(root);

        assertEquals(List.of("Off", "plain"), labels(library));
    }

    @Test
    public void rootRelativeImplementationSegmentsHideStandaloneGlsl() throws IOException {
        Path root = tempDir.resolve("display-shaders");
        write(root.resolve("crt/shaders/pass.glsl"), "void main() {}\n");
        write(root.resolve("crt/resources/lut.glsl"), "void main() {}\n");
        write(root.resolve("crt/crt-easymode.glslp"), "shader0 = shaders/pass.glsl\n");

        DisplayShaderLibrary library = DisplayShaderLibrary.scan(root);

        assertEquals(List.of("Off", "crt/crt-easymode"), labels(library));
    }

    @Test
    public void quotedPresetReferencesAndInlineCommentsDoNotAbortScan() throws IOException {
        Path root = tempDir.resolve("display-shaders");
        write(root.resolve("crt/quoted.glslp"),
                "shader0 = \"shaders/quoted-pass.glsl\"\n"
                        + "shader1 = shaders/comment-pass.glsl # still a pass reference\n");
        write(root.resolve("crt/shaders/quoted-pass.glsl"), "void main() {}\n");
        write(root.resolve("crt/shaders/comment-pass.glsl"), "void main() {}\n");

        DisplayShaderLibrary library = DisplayShaderLibrary.scan(root);

        assertEquals(List.of("Off", "crt/quoted"), labels(library));
    }

    @Test
    public void invalidPresetReferenceIsIgnoredWithoutDroppingOtherEntries() throws IOException {
        Path root = tempDir.resolve("display-shaders");
        write(root.resolve("bad/bad-ref.glslp"), "shader0 = \"shaders/bad-pass.glsl\"\"\n");
        write(root.resolve("good/kept.glsl"), "void main() {}\n");

        DisplayShaderLibrary library = DisplayShaderLibrary.scan(root);

        assertEquals(List.of("Off", "bad/bad-ref", "good/kept"), labels(library));
    }

    @Test
    public void cgpCgReferenceHidesPairedGlslSibling() throws IOException {
        Path root = tempDir.resolve("display-shaders");
        write(root.resolve("BizHawk/BizScanlines.cgp"), "shader0 = BizScanlines.cg\n");
        write(root.resolve("BizHawk/BizScanlines.cg"), "void main() {}\n");
        write(root.resolve("BizHawk/BizScanlines.glsl"), "void main() {}\n");
        write(root.resolve("BizHawk/Other.glsl"), "void main() {}\n");

        DisplayShaderLibrary library = DisplayShaderLibrary.scan(root);

        assertEquals(List.of("Off", "BizHawk/BizScanlines", "BizHawk/Other"), labels(library));
    }

    @Test
    public void hiddenDirectoriesAndSlangAreIgnored() throws IOException {
        Path root = tempDir.resolve("display-shaders");
        write(root.resolve(".hidden/secret.glslp"), "shader0 = secret.glsl\n");
        write(root.resolve("visible/ignored.slangp"), "# slang preset\n");
        write(root.resolve("visible/ignored.slang"), "void main() {}\n");
        write(root.resolve("visible/kept.cgp"), "# cgp preset\n");

        DisplayShaderLibrary library = DisplayShaderLibrary.scan(root);

        assertEquals(List.of("Off", "visible/kept"), labels(library));
    }

    @Test
    public void unsupportedExternalTexturePresetsAreNotSelectable() throws IOException {
        Path root = tempDir.resolve("display-shaders");
        write(root.resolve("borders/gameboy.glslp"), """
                shaders = 1
                shader0 = pass.glsl
                textures = "BORDER"
                BORDER = "border.png"
                """);
        write(root.resolve("borders/pass.glsl"), "void main() {}\n");
        write(root.resolve("crt/compatible.glslp"), """
                shaders = 1
                shader0 = pass.glsl
                """);
        write(root.resolve("crt/pass.glsl"), "void main() {}\n");

        DisplayShaderLibrary library = DisplayShaderLibrary.scan(root);

        assertEquals(List.of("Off", "crt/compatible"), labels(library));
    }

    @Test
    public void savedSelectionIndexFallsBackToOffWhenMissing() throws IOException {
        Path root = tempDir.resolve("display-shaders");
        write(root.resolve("Custom/warm.glsl"), "void main() {}\n");

        DisplayShaderLibrary library = DisplayShaderLibrary.scan(root);

        assertEquals(0, library.indexOfRelativePath(null));
        assertEquals(0, library.indexOfRelativePath(""));
        assertEquals(0, library.indexOfRelativePath("OFF"));
        assertEquals(0, library.indexOfRelativePath("missing.glslp"));
    }

    @Test
    public void savedSelectionIndexMatchesNormalizedEquivalentPaths() throws IOException {
        Path root = tempDir.resolve("display-shaders");
        write(root.resolve("Custom/warm.glsl"), "void main() {}\n");

        DisplayShaderLibrary library = DisplayShaderLibrary.scan(root);

        assertEquals(1, library.indexOfRelativePath("./Custom/warm.glsl"));
        assertEquals(1, library.indexOfRelativePath("Custom/../Custom/warm.glsl"));
    }

    @Test
    public void caseInsensitiveSortUsesNaturalPathTieBreaker() {
        DisplayShaderPresetRef upper = new DisplayShaderPresetRef(
                DisplayShaderPresetRef.Kind.GLSL,
                "Shaders/Warm.glsl",
                Path.of("Shaders", "Warm.glsl"));
        DisplayShaderPresetRef lower = new DisplayShaderPresetRef(
                DisplayShaderPresetRef.Kind.GLSL,
                "shaders/warm.glsl",
                Path.of("shaders", "warm.glsl"));

        assertEquals(0, String.CASE_INSENSITIVE_ORDER.compare(upper.relativePath(), lower.relativePath()));
        assertTrue(DisplayShaderLibrary.compareEntriesForScanOrder(upper, lower) < 0);
    }

    private static List<String> labels(DisplayShaderLibrary library) {
        return library.entries().stream()
                .map(DisplayShaderPresetRef::label)
                .toList();
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
