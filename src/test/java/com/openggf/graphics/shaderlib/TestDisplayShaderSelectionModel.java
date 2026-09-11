package com.openggf.graphics.shaderlib;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

public class TestDisplayShaderSelectionModel {

    @TempDir
    Path tempDir;

    @Test
    public void emptyQueryReturnsRootOffAndImmediateFolders() throws IOException {
        DisplayShaderSelectionModel model = new DisplayShaderSelectionModel(libraryWith(
                "scanlines/zebra.glslp",
                "crt/crt-easymode.glslp",
                "BizHawk/BizScanlines.cgp"));

        List<DisplayShaderSelectionModel.SelectionItem> items = model.filter("");

        assertEquals(List.of(
                "Off",
                "BizHawk/",
                "crt/",
                "scanlines/"), displayPaths(items));
        assertEquals(List.of(
                "System",
                "BizHawk",
                "crt",
                "scanlines"), categories(items));
    }

    @Test
    public void constructionFromListPreservesEmptyFilterOrder() {
        DisplayShaderPresetRef scanline = preset("scanlines/zebra.glslp");
        DisplayShaderPresetRef crt = preset("crt/crt-easymode.glslp");
        DisplayShaderSelectionModel model = new DisplayShaderSelectionModel(List.of(
                DisplayShaderPresetRef.OFF,
                scanline,
                crt));

        List<DisplayShaderSelectionModel.SelectionItem> items = model.filter("");

        assertEquals(List.of(
                "Off",
                "crt/",
                "scanlines/"), displayPaths(items));
        assertSame(null, items.get(1).ref());
        assertSame(null, items.get(2).ref());
    }

    @Test
    public void textQueryMatchesRootRelativePathCaseInsensitively() throws IOException {
        DisplayShaderSelectionModel model = new DisplayShaderSelectionModel(libraryWith(
                "libretro-glsl/crt/crt-easymode.glslp",
                "scanlines/zebra.glslp"));

        List<DisplayShaderSelectionModel.SelectionItem> items = model.filter("EASYMODE");

        assertEquals(List.of("Off"), displayPaths(items));
    }

    @Test
    public void categoryQueryMatchesPathSegment() throws IOException {
        DisplayShaderSelectionModel model = new DisplayShaderSelectionModel(libraryWith(
                "RetroArch/shaders_glsl/scanlines/scanline.glslp",
                "crt/crt-easymode.glslp"));

        List<DisplayShaderSelectionModel.SelectionItem> items = model.filter("scanlines");

        assertEquals(List.of("Off"), displayPaths(items));
    }

    @Test
    public void categoryListExcludesImplementationSegmentsShadersAndResources() throws IOException {
        DisplayShaderSelectionModel model = new DisplayShaderSelectionModel(libraryWith(
                "libretro-glsl/shaders/crt/crt-pass.glslp",
                "libretro-glsl/resources/crt/crt-lut.glslp",
                "RetroArch/shaders_glsl/scanlines/scanline.glslp"));

        assertEquals(List.of("crt", "scanlines"), model.categories());
    }

    @Test
    public void categoryListIncludesRealSystemFolder() throws IOException {
        DisplayShaderSelectionModel model = new DisplayShaderSelectionModel(libraryWith(
                "System/foo.glsl",
                "crt/crt-easymode.glslp"));

        assertEquals(List.of("crt", "System"), model.categories());
    }

    @Test
    public void selectByCurrentVisibleIndexReturnsTheUnderlyingPresetRef() throws IOException {
        DisplayShaderLibrary library = libraryWith(
                "crt/crt-easymode.glslp",
                "scanlines/zebra.glslp");
        DisplayShaderSelectionModel model = new DisplayShaderSelectionModel(library);
        model.enterFolder("scanlines");
        model.filter("zebra");

        DisplayShaderPresetRef selected = model.select(1);

        assertSame(library.at(2), selected);
        assertEquals("scanlines/zebra.glslp", selected.relativePath());
    }

    @Test
    public void selectUsesTheMostRecentFilterResult() throws IOException {
        DisplayShaderLibrary library = libraryWith(
                "crt/crt-easymode.glslp",
                "scanlines/zebra.glslp");
        DisplayShaderSelectionModel model = new DisplayShaderSelectionModel(library);
        model.enterFolder("scanlines");
        model.filter("zebra");
        model.enterParentFolder();
        model.enterFolder("crt");
        model.filter("crt");

        DisplayShaderPresetRef selected = model.select(1);

        assertSame(library.at(1), selected);
        assertEquals("crt/crt-easymode.glslp", selected.relativePath());
    }

    @Test
    public void enteringFolderShowsParentFoldersAndBasenameOnlyShaders() throws IOException {
        DisplayShaderLibrary library = libraryWith(
                "libretro-glsl/crt/crt-easymode.glslp",
                "libretro-glsl/crt/crtglow_gauss.glslp",
                "libretro-glsl/scanlines/zebra.glslp");
        DisplayShaderSelectionModel model = new DisplayShaderSelectionModel(library);

        model.enterFolder("libretro-glsl");
        model.enterFolder("crt");
        List<DisplayShaderSelectionModel.SelectionItem> items = model.filter("");

        assertEquals("libretro-glsl/crt", model.currentFolder());
        assertEquals(List.of("..", "crt-easymode.glslp", "crtglow_gauss.glslp"), displayPaths(items));
        assertSame(library.at(1), items.get(1).ref());
    }

    @Test
    public void currentFolderContainingSelectionOpensToThatFolder() throws IOException {
        DisplayShaderLibrary library = libraryWith(
                "libretro-glsl/crt/crt-easymode.glslp",
                "libretro-glsl/scanlines/zebra.glslp");
        DisplayShaderSelectionModel model = new DisplayShaderSelectionModel(library);

        model.showFolderFor(library.at(1));

        assertEquals("libretro-glsl/crt", model.currentFolder());
        assertEquals(List.of("..", "crt-easymode.glslp"), displayPaths(model.filter("")));
    }

    private DisplayShaderLibrary libraryWith(String... paths) throws IOException {
        Path root = tempDir.resolve("display-shaders");
        for (String path : paths) {
            write(root.resolve(path), "# shader preset\n");
        }
        return DisplayShaderLibrary.scan(root);
    }

    private static DisplayShaderPresetRef preset(String relativePath) {
        return new DisplayShaderPresetRef(
                DisplayShaderPresetRef.Kind.GLSLP,
                relativePath,
                Path.of("display-shaders").resolve(relativePath));
    }

    private static List<String> displayPaths(List<DisplayShaderSelectionModel.SelectionItem> items) {
        return items.stream()
                .map(DisplayShaderSelectionModel.SelectionItem::displayPath)
                .toList();
    }

    private static List<String> categories(List<DisplayShaderSelectionModel.SelectionItem> items) {
        return items.stream()
                .map(DisplayShaderSelectionModel.SelectionItem::category)
                .toList();
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
