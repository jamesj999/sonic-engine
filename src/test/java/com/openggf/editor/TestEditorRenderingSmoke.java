package com.openggf.editor;

import com.openggf.debug.DebugColor;
import com.openggf.game.session.EditorCursorState;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GLCommandable;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.PixelFontTextRenderer;
import com.openggf.editor.render.EditorOverlayRenderer;
import com.openggf.editor.render.EditorCommandStripRenderer;
import com.openggf.editor.render.EditorLibraryPaneRenderer;
import com.openggf.editor.render.EditorTextRenderer;
import com.openggf.editor.render.EditorToolbarRenderer;
import com.openggf.editor.render.EditorWorldOverlayRenderer;
import com.openggf.editor.render.FocusedEditorPaneRenderer;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.level.AbstractLevel;
import com.openggf.level.Block;
import com.openggf.level.Chunk;
import com.openggf.level.ChunkDesc;
import com.openggf.level.MutableLevel;
import com.openggf.level.PatternDesc;
import com.openggf.level.Pattern;
import com.openggf.level.rings.RingSpriteSheet;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.rings.RingSpawn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestEditorRenderingSmoke {
    private static final GraphicsManager TEST_GRAPHICS = GraphicsManager.getInstance();

    @BeforeEach
    void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        TEST_GRAPHICS.resetState();
    }

    @Test
    void focusedPaneRenderer_buildsWithoutPermanentSidebarAssumptions() {
        InspectableFocusedEditorPaneRenderer renderer = new InspectableFocusedEditorPaneRenderer();

        // With no controller attached the renderer must still emit a defined
        // fallback: the static pane chrome plus a translucent backdrop, and no
        // controller-driven preview placements.
        List<GLCommand> blockChrome = renderer.buildBlockCommands();
        List<GLCommand> chunkChrome = renderer.buildChunkCommands();
        assertFalse(blockChrome.isEmpty(),
                "block pane must emit fallback chrome commands with a null controller");
        assertFalse(chunkChrome.isEmpty(),
                "chunk pane must emit fallback chrome commands with a null controller");

        assertEquals(1, renderer.buildBlockBackdropCommands().size(),
                "block pane must emit exactly one fallback backdrop command");
        assertEquals(1, renderer.buildChunkBackdropCommands().size(),
                "chunk pane must emit exactly one fallback backdrop command");

        assertTrue(renderer.buildBlockPreviewPlacements().isEmpty(),
                "no preview placements without a controller");
        assertTrue(renderer.buildChunkPreviewPlacements().isEmpty(),
                "no preview placements without a controller");

        // The full render path must also remain exception-free.
        assertDoesNotThrow(renderer::renderBlockEditorPane);
        assertDoesNotThrow(renderer::renderChunkEditorPane);
    }

    @Test
    void overlayRenderer_splitsWorldAndScreenSpacePassesByHierarchyDepth() {
        TrackingToolbarRenderer toolbar = new TrackingToolbarRenderer();
        TrackingCommandStripRenderer commandStrip = new TrackingCommandStripRenderer();
        TrackingWorldOverlayRenderer worldOverlay = new TrackingWorldOverlayRenderer();
        TrackingFocusedEditorPaneRenderer focusedPane = new TrackingFocusedEditorPaneRenderer();
        TrackingLibraryPaneRenderer libraryPane = new TrackingLibraryPaneRenderer();
        EditorOverlayRenderer renderer = new EditorOverlayRenderer(toolbar, commandStrip, worldOverlay, focusedPane,
                libraryPane);

        assertDoesNotThrow(renderer::renderWorldSpaceOverlay);
        assertEquals(1, worldOverlay.renderCalls);
        assertEquals(0, toolbar.renderCalls);
        assertEquals(0, commandStrip.renderCalls);
        assertEquals(0, focusedPane.blockPaneCalls);
        assertEquals(0, focusedPane.chunkPaneCalls);
        assertEquals(0, libraryPane.renderCalls);

        assertDoesNotThrow(renderer::renderScreenSpaceOverlay);
        assertEquals(1, worldOverlay.renderCalls);
        assertEquals(1, toolbar.renderCalls);
        assertEquals(1, commandStrip.renderCalls);
        assertEquals(0, focusedPane.blockPaneCalls);
        assertEquals(0, focusedPane.chunkPaneCalls);
        assertEquals(0, libraryPane.renderCalls);

        renderer.setHierarchyDepth(EditorHierarchyDepth.BLOCK);
        renderer.renderWorldSpaceOverlay();
        renderer.renderScreenSpaceOverlay();

        assertEquals(1, worldOverlay.renderCalls);
        assertEquals(2, toolbar.renderCalls);
        assertEquals(2, commandStrip.renderCalls);
        assertEquals(1, focusedPane.blockPaneCalls);
        assertEquals(0, focusedPane.chunkPaneCalls);
        assertEquals(0, libraryPane.renderCalls);

        renderer.setHierarchyDepth(EditorHierarchyDepth.CHUNK);
        renderer.renderWorldSpaceOverlay();
        renderer.renderScreenSpaceOverlay();

        assertEquals(1, worldOverlay.renderCalls);
        assertEquals(3, toolbar.renderCalls);
        assertEquals(3, commandStrip.renderCalls);
        assertEquals(1, focusedPane.blockPaneCalls);
        assertEquals(1, focusedPane.chunkPaneCalls);
        assertEquals(0, libraryPane.renderCalls);
    }

    @Test
    void overlayRenderer_rendersLibraryPaneWhenFocusRegionIsLibrary() {
        LevelEditorController controller = createPreviewController();
        controller.cycleFocusRegion();
        TrackingToolbarRenderer toolbar = new TrackingToolbarRenderer();
        TrackingCommandStripRenderer commandStrip = new TrackingCommandStripRenderer();
        TrackingWorldOverlayRenderer worldOverlay = new TrackingWorldOverlayRenderer();
        TrackingFocusedEditorPaneRenderer focusedPane = new TrackingFocusedEditorPaneRenderer();
        TrackingLibraryPaneRenderer libraryPane = new TrackingLibraryPaneRenderer();
        EditorOverlayRenderer renderer = new EditorOverlayRenderer(controller, toolbar, commandStrip, worldOverlay,
                focusedPane, libraryPane);
        renderer.setHierarchyDepth(EditorHierarchyDepth.WORLD);

        renderer.renderScreenSpaceOverlay();

        assertEquals(1, toolbar.renderCalls);
        assertEquals(1, commandStrip.renderCalls);
        assertEquals(1, libraryPane.renderCalls);
        assertEquals(EditorHierarchyDepth.WORLD, libraryPane.capturedDepth);
    }

    @Test
    void overlayRenderer_rendersFocusedPaneAndLibraryPaneWhenFocusedModeLibraryIsActive() {
        LevelEditorController controller = createPreviewController();
        controller.selectBlock(1);
        controller.descend();
        controller.cycleFocusRegion();
        TrackingToolbarRenderer toolbar = new TrackingToolbarRenderer();
        TrackingCommandStripRenderer commandStrip = new TrackingCommandStripRenderer();
        TrackingWorldOverlayRenderer worldOverlay = new TrackingWorldOverlayRenderer();
        TrackingFocusedEditorPaneRenderer focusedPane = new TrackingFocusedEditorPaneRenderer();
        TrackingLibraryPaneRenderer libraryPane = new TrackingLibraryPaneRenderer();
        EditorOverlayRenderer renderer = new EditorOverlayRenderer(controller, toolbar, commandStrip, worldOverlay,
                focusedPane, libraryPane);

        renderer.renderScreenSpaceOverlay();

        assertEquals(1, focusedPane.blockPaneCalls);
        assertEquals(1, libraryPane.renderCalls);
        assertEquals(EditorHierarchyDepth.BLOCK, libraryPane.capturedDepth);
    }

    @Test
    void toolbarRenderer_buildsVisibleChromeCommands() {
        InspectableToolbarRenderer renderer = new InspectableToolbarRenderer();

        assertFalse(renderer.buildCommands().isEmpty());
    }

    @Test
    void commandStripRenderer_buildsVisibleChromeCommands() {
        InspectableCommandStripRenderer renderer = new InspectableCommandStripRenderer();

        assertFalse(renderer.buildCommands().isEmpty());
    }

    @Test
    void toolbarRenderer_buildsStateLinesFromController() {
        LevelEditorController controller = createPreviewController();
        controller.selectBlock(2);
        controller.selectChunk(4);
        controller.descend();
        InspectableToolbarRenderer renderer = new InspectableToolbarRenderer(controller);

        List<String> lines = renderer.buildLines();

        assertTrue(lines.stream().anyMatch(line -> line.contains("World > Block 2")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("BLOCK_PANE")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Block 2")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Chunk 4")));
    }

    @Test
    void commandStripRenderer_buildsDepthSpecificCommandHints() {
        LevelEditorController controller = createPreviewController();
        InspectableCommandStripRenderer renderer = new InspectableCommandStripRenderer(controller);

        assertTrue(String.join(" ", renderer.buildLines()).contains("Place block"));

        controller.selectBlock(1);
        controller.selectChunk(3);
        controller.descend();
        assertTrue(String.join(" ", renderer.buildLines()).contains("Apply chunk"));

        controller.descend();
        assertTrue(String.join(" ", renderer.buildLines()).contains("Apply pattern"));
    }

    @Test
    void libraryPaneRenderer_buildsVisibleChromeAndContextLines() {
        LevelEditorController controller = createPreviewController();
        controller.selectBlock(2);
        controller.cycleFocusRegion();
        InspectableLibraryPaneRenderer renderer = new InspectableLibraryPaneRenderer(controller);

        assertFalse(renderer.buildCommands().isEmpty());
        assertTrue(String.join(" ", renderer.buildLines()).contains("Block library"));
        assertTrue(String.join(" ", renderer.buildLines()).contains("Block 2"));
    }

    @Test
    void textRenderer_buildsCommandsForRenderableLines() {
        InspectableTextRenderer renderer = new InspectableTextRenderer();

        List<EditorTextRenderer.TextCommand> commands = renderer.buildCommands(List.of("One", "", "Two"),
                8, 12);

        assertEquals(2, commands.size());
        assertEquals("One", commands.get(0).text());
        assertEquals("Two", commands.get(1).text());
        assertEquals(10, commands.get(0).lineHeight());
        assertEquals(12, commands.get(0).y());
        assertEquals(32, commands.get(1).y());
        assertEquals(commands.get(0).y() + commands.get(0).lineHeight() * 2, commands.get(1).y());
    }

    @Test
    void textRenderer_queuesTextBatchInsteadOfRenderingImmediately() throws Exception {
        GraphicsManager.getInstance().resetState();
        InspectableTextRenderer renderer = new InspectableTextRenderer();

        assertEquals(0, graphicsCommandQueueSize());

        renderer.renderLines(List.of("Queued"), 8, 12);

        assertEquals(1, graphicsCommandQueueSize());
    }

    @Test
    void textRenderer_executesQueuedBatchThroughPixelFontBackend() throws Exception {
        GraphicsManager.getInstance().resetState();
        float[] projectionMatrix = {
                1f, 2f, 3f, 4f,
                5f, 6f, 7f, 8f,
                9f, 10f, 11f, 12f,
                13f, 14f, 15f, 16f
        };
        TEST_GRAPHICS.setProjectionMatrixBuffer(projectionMatrix);
        RecordingPixelFontTextRenderer pixelFontTextRenderer = new RecordingPixelFontTextRenderer();
        InspectableTextRenderer renderer = new InspectableTextRenderer(pixelFontTextRenderer);

        renderer.renderLines(List.of("Queued"), 8, 12);

        assertEquals(1, graphicsCommandQueueSize());
        queuedGraphicsCommand().execute(0, 0, 320, 224);

        assertEquals(1, pixelFontTextRenderer.projectionCalls);
        assertSame(projectionMatrix, pixelFontTextRenderer.lastProjection);
        assertEquals(1, pixelFontTextRenderer.calls.size());
        DrawCall call = pixelFontTextRenderer.calls.get(0);
        assertEquals("Queued", call.text());
        assertEquals(8, call.x());
        assertEquals(12, call.y());
        assertEquals(DebugColor.WHITE, call.color());
    }

    @Test
    void toolbarRenderer_textLayoutFitsToolbarChrome() {
        LevelEditorController controller = createPreviewController();
        controller.selectBlock(2);
        controller.selectChunk(4);
        controller.descend();
        InspectableToolbarRenderer renderer = new InspectableToolbarRenderer(controller);

        assertTextCommandsInsideChrome(renderer.buildTextCommands(), 4, 24);
    }

    @Test
    void commandStripRenderer_textLayoutFitsCommandStripChrome() {
        LevelEditorController controller = createPreviewController();
        controller.selectBlock(1);
        controller.selectChunk(3);
        controller.descend();
        InspectableCommandStripRenderer renderer = new InspectableCommandStripRenderer(controller);

        assertTextCommandsInsideChrome(renderer.buildTextCommands(), 198, 220);
    }

    @Test
    void focusedPaneRenderer_buildsVisiblePaneChromeCommands() {
        InspectableFocusedEditorPaneRenderer renderer = new InspectableFocusedEditorPaneRenderer();

        assertFalse(renderer.buildBlockCommands().isEmpty());
        assertFalse(renderer.buildChunkCommands().isEmpty());
    }

    @Test
    void focusedPaneRenderer_buildsTranslucentFadeBackdropCommandsForFocusedPanes() {
        InspectableFocusedEditorPaneRenderer renderer = new InspectableFocusedEditorPaneRenderer();

        List<GLCommand> blockBackdrop = renderer.buildBlockBackdropCommands();
        List<GLCommand> chunkBackdrop = renderer.buildChunkBackdropCommands();

        assertEquals(1, blockBackdrop.size());
        assertEquals(1, chunkBackdrop.size());
        assertFocusedBackdropCommand(blockBackdrop.get(0));
        assertFocusedBackdropCommand(chunkBackdrop.get(0));
    }

    @Test
    void focusedPaneRenderer_blockPreviewPlacementsUseRealSelectedBlockComposition() {
        LevelEditorController controller = createPreviewController();
        InspectableFocusedEditorPaneRenderer renderer = new InspectableFocusedEditorPaneRenderer(controller);

        controller.selectBlock(1);
        List<FocusedEditorPaneRenderer.PreviewPlacement> first = renderer.buildBlockPreviewPlacements();

        assertEquals(16, first.size());
        assertEquals(controller.selectedBlockChunkPreview(0, 0).getPatternDesc(0, 0).get(),
                first.get(0).descriptor().get());
        assertEquals(controller.selectedBlockChunkPreview(0, 0).getPatternDesc(1, 0).get(),
                first.get(1).descriptor().get());
        assertEquals(controller.selectedBlockChunkPreview(0, 0).getPatternDesc(0, 1).get(),
                first.get(2).descriptor().get());
        assertEquals(controller.selectedBlockChunkPreview(0, 0).getPatternDesc(1, 1).get(),
                first.get(3).descriptor().get());
        assertEquals(first.get(0).x() + 8, first.get(1).x());
        assertEquals(first.get(0).y() + 8, first.get(2).y());

        controller.selectBlock(2);
        List<FocusedEditorPaneRenderer.PreviewPlacement> second = renderer.buildBlockPreviewPlacements();

        assertFalse(second.isEmpty());
        assertNotEquals(placementSignature(first), placementSignature(second));
    }

    @Test
    void focusedPaneRenderer_blockPreviewCommandsChangeWithActiveCell() {
        LevelEditorController controller = createPreviewController();
        InspectableFocusedEditorPaneRenderer renderer = new InspectableFocusedEditorPaneRenderer(controller);

        controller.selectBlock(1);
        controller.descend();
        List<GLCommand> first = renderer.buildBlockCommands();

        controller.moveActiveSelection(1, 1);
        List<GLCommand> second = renderer.buildBlockCommands();

        assertNotEquals(commandSignature(first), commandSignature(second));
    }

    @Test
    void focusedPaneRenderer_chunkPreviewPlacementsUseRealSelectedChunkComposition() {
        LevelEditorController controller = createPreviewController();
        InspectableFocusedEditorPaneRenderer renderer = new InspectableFocusedEditorPaneRenderer(controller);

        controller.selectBlock(1);
        controller.selectChunk(3);
        controller.descend();
        controller.descend();
        List<FocusedEditorPaneRenderer.PreviewPlacement> first = renderer.buildChunkPreviewPlacements();

        assertEquals(4, first.size());
        assertEquals(controller.selectedChunkPreview().getPatternDesc(0, 0).get(), first.get(0).descriptor().get());
        assertEquals(controller.selectedChunkPreview().getPatternDesc(1, 0).get(), first.get(1).descriptor().get());
        assertEquals(controller.selectedChunkPreview().getPatternDesc(0, 1).get(), first.get(2).descriptor().get());
        assertEquals(controller.selectedChunkPreview().getPatternDesc(1, 1).get(), first.get(3).descriptor().get());
        assertEquals(first.get(0).x() + 8, first.get(1).x());
        assertEquals(first.get(0).y() + 8, first.get(2).y());

        controller.selectChunk(4);
        List<FocusedEditorPaneRenderer.PreviewPlacement> second = renderer.buildChunkPreviewPlacements();

        assertFalse(second.isEmpty());
        assertNotEquals(placementSignature(first), placementSignature(second));
    }

    @Test
    void focusedPaneRenderer_chunkPreviewCommandsChangeWithActiveChunkCell() {
        LevelEditorController controller = createPreviewController();
        InspectableFocusedEditorPaneRenderer renderer = new InspectableFocusedEditorPaneRenderer(controller);

        controller.selectBlock(1);
        controller.selectChunk(3);
        controller.descend();
        controller.descend();
        List<GLCommand> first = renderer.buildChunkCommands();

        controller.moveActiveSelection(1, 1);
        List<GLCommand> second = renderer.buildChunkCommands();

        assertNotEquals(commandSignature(first), commandSignature(second));
    }

    @Test
    void worldOverlayRenderer_usesCurrentSessionCursorWhenRendering() {
        SessionManager.openGameplaySession(new Sonic2GameModule());
        SessionManager.enterEditorMode(new EditorCursorState(320, 448));
        CapturingWorldOverlayRenderer renderer = new CapturingWorldOverlayRenderer();

        renderer.render();

        assertEquals(SessionManager.getCurrentEditorMode().getCursor(), renderer.capturedCursor);
    }

    @Test
    void worldOverlayRenderer_buildsDifferentCursorCommandsForDifferentPositions() {
        InspectableWorldOverlayRenderer renderer = new InspectableWorldOverlayRenderer();

        List<GLCommand> first = renderer.buildCursorCommands(new EditorCursorState(320, 448));
        List<GLCommand> second = renderer.buildCursorCommands(new EditorCursorState(384, 480));

        assertFalse(first.isEmpty());
        assertFalse(second.isEmpty());
        assertNotEquals(commandSignature(first), commandSignature(second));
    }

    @Test
    void worldOverlayRenderer_buildsGridCommandsAlongsideCursor() {
        InspectableWorldOverlayRenderer renderer = new InspectableWorldOverlayRenderer();

        List<GLCommand> cursorOnly = renderer.buildCursorCommands(new EditorCursorState(64, 64));
        List<GLCommand> worldCommands = renderer.buildWorldCommands(new EditorCursorState(64, 64));

        assertFalse(cursorOnly.isEmpty());
        assertTrue(worldCommands.size() > cursorOnly.size());
        assertTrue(worldCommands.size() - cursorOnly.size() >= 16);
    }

    private static final class TrackingToolbarRenderer extends EditorToolbarRenderer {
        private int renderCalls;

        @Override
        public void render() {
            renderCalls++;
        }
    }

    private static final class TrackingCommandStripRenderer extends EditorCommandStripRenderer {
        private int renderCalls;

        @Override
        public void render() {
            renderCalls++;
        }
    }

    private static final class TrackingWorldOverlayRenderer extends EditorWorldOverlayRenderer {
        private int renderCalls;

        @Override
        public void render() {
            renderCalls++;
        }
    }

    private static final class TrackingLibraryPaneRenderer extends EditorLibraryPaneRenderer {
        private int renderCalls;
        private EditorHierarchyDepth capturedDepth;

        @Override
        public void render(EditorHierarchyDepth depth) {
            renderCalls++;
            capturedDepth = depth;
        }
    }

    private static final class TrackingFocusedEditorPaneRenderer extends FocusedEditorPaneRenderer {
        private int blockPaneCalls;
        private int chunkPaneCalls;

        @Override
        public void renderBlockEditorPane() {
            blockPaneCalls++;
        }

        @Override
        public void renderChunkEditorPane() {
            chunkPaneCalls++;
        }
    }

    private static final class InspectableToolbarRenderer extends EditorToolbarRenderer {
        private InspectableToolbarRenderer() {
            super();
        }

        private InspectableToolbarRenderer(LevelEditorController controller) {
            super(controller, TEST_GRAPHICS);
        }

        private List<GLCommand> buildCommands() {
            List<GLCommand> commands = new ArrayList<>();
            appendCommands(commands);
            return commands;
        }

        private List<String> buildLines() {
            return buildStateLines();
        }

        private List<EditorTextRenderer.TextCommand> buildTextCommands() {
            return buildToolbarTextCommands();
        }
    }

    private static final class InspectableCommandStripRenderer extends EditorCommandStripRenderer {
        private InspectableCommandStripRenderer() {
            super();
        }

        private InspectableCommandStripRenderer(LevelEditorController controller) {
            super(controller, TEST_GRAPHICS);
        }

        private List<GLCommand> buildCommands() {
            List<GLCommand> commands = new ArrayList<>();
            appendCommands(commands);
            return commands;
        }

        private List<String> buildLines() {
            return buildCommandLines();
        }

        private List<EditorTextRenderer.TextCommand> buildTextCommands() {
            return buildCommandTextCommands();
        }
    }

    private static final class InspectableLibraryPaneRenderer extends EditorLibraryPaneRenderer {
        private InspectableLibraryPaneRenderer(LevelEditorController controller) {
            super(controller, TEST_GRAPHICS);
        }

        private List<GLCommand> buildCommands() {
            List<GLCommand> commands = new ArrayList<>();
            appendCommands(commands);
            return commands;
        }

        private List<String> buildLines() {
            return buildLibraryLines(EditorHierarchyDepth.WORLD);
        }
    }

    private static final class InspectableTextRenderer extends EditorTextRenderer {
        private InspectableTextRenderer() {
            super(TEST_GRAPHICS);
        }

        private InspectableTextRenderer(PixelFontTextRenderer textRenderer) {
            super(TEST_GRAPHICS, textRenderer);
        }

        private List<TextCommand> buildCommands(List<String> lines, int x, int y) {
            return buildTextCommands(lines, x, y);
        }
    }

    private static final class RecordingPixelFontTextRenderer extends PixelFontTextRenderer {
        private int projectionCalls;
        private float[] lastProjection;
        private final List<DrawCall> calls = new ArrayList<>();

        @Override
        public void setProjectionMatrix(float[] projectionMatrix) {
            projectionCalls++;
            lastProjection = projectionMatrix;
        }

        @Override
        public void drawShadowedText(String text, int x, int y, DebugColor color) {
            calls.add(new DrawCall(text, x, y, color));
        }
    }

    private record DrawCall(String text, int x, int y, DebugColor color) {}

    private static final class InspectableFocusedEditorPaneRenderer extends FocusedEditorPaneRenderer {
        private InspectableFocusedEditorPaneRenderer() {
            super(null, TEST_GRAPHICS);
        }

        private InspectableFocusedEditorPaneRenderer(LevelEditorController controller) {
            super(controller, TEST_GRAPHICS);
        }

        private List<GLCommand> buildBlockCommands() {
            List<GLCommand> commands = new ArrayList<>();
            appendBlockPaneCommands(commands);
            return commands;
        }

        private List<GLCommand> buildChunkCommands() {
            List<GLCommand> commands = new ArrayList<>();
            appendChunkPaneCommands(commands);
            return commands;
        }

        private List<GLCommand> buildBlockBackdropCommands() {
            List<GLCommand> commands = new ArrayList<>();
            appendBlockBackdropCommands(commands);
            return commands;
        }

        private List<GLCommand> buildChunkBackdropCommands() {
            List<GLCommand> commands = new ArrayList<>();
            appendChunkBackdropCommands(commands);
            return commands;
        }

        @Override
        protected void renderPreviewPlacements(List<PreviewPlacement> placements) {
            // Test-only renderer: the placement model is verified directly in dedicated assertions.
        }

        public List<PreviewPlacement> buildBlockPreviewPlacements() {
            return super.buildBlockPreviewPlacements();
        }

        public List<PreviewPlacement> buildChunkPreviewPlacements() {
            return super.buildChunkPreviewPlacements();
        }
    }

    private static final class InspectableWorldOverlayRenderer extends EditorWorldOverlayRenderer {
        private List<GLCommand> buildCursorCommands(EditorCursorState cursor) {
            List<GLCommand> commands = new ArrayList<>();
            appendCursorCommands(commands, cursor);
            return commands;
        }

        private List<GLCommand> buildWorldCommands(EditorCursorState cursor) {
            List<GLCommand> commands = new ArrayList<>();
            appendWorldCommands(commands, cursor);
            return commands;
        }
    }

    private static final class CapturingWorldOverlayRenderer extends EditorWorldOverlayRenderer {
        private EditorCursorState capturedCursor;

        @Override
        protected void appendCursorCommands(List<GLCommand> commands, EditorCursorState cursor) {
            capturedCursor = cursor;
        }
    }

    private static String commandSignature(List<GLCommand> commands) {
        StringBuilder signature = new StringBuilder();
        for (GLCommand command : commands) {
            signature.append(command.getCommandType())
                    .append(':')
                    .append((int) command.getX1())
                    .append(',')
                    .append((int) command.getY1())
                    .append(';');
        }
        return signature.toString();
    }

    private static String placementSignature(List<FocusedEditorPaneRenderer.PreviewPlacement> placements) {
        StringBuilder signature = new StringBuilder();
        for (FocusedEditorPaneRenderer.PreviewPlacement placement : placements) {
            signature.append(placement.descriptor().get())
                    .append('@')
                    .append(placement.x())
                    .append(',')
                    .append(placement.y())
                    .append(';');
        }
        return signature.toString();
    }

    private static void assertFocusedBackdropCommand(GLCommand command) {
        assertEquals(GLCommand.CommandType.RECTI, command.getCommandType());
        assertEquals(GLCommand.BlendType.ONE_MINUS_SRC_ALPHA, command.getBlendMode());
        assertEquals(1.0f, command.getColour1(), 0.001f);
        assertEquals(1.0f, command.getColour2(), 0.001f);
        assertEquals(1.0f, command.getColour3(), 0.001f);
        assertTrue(command.getAlpha() > 0.0f && command.getAlpha() < 1.0f);
    }

    @SuppressWarnings("unchecked")
    private static int graphicsCommandQueueSize() throws Exception {
        Field commands = GraphicsManager.class.getDeclaredField("commands");
        commands.setAccessible(true);
        return ((List<GLCommandable>) commands.get(GraphicsManager.getInstance())).size();
    }

    @SuppressWarnings("unchecked")
    private static GLCommandable queuedGraphicsCommand() throws Exception {
        Field commands = GraphicsManager.class.getDeclaredField("commands");
        commands.setAccessible(true);
        return ((List<GLCommandable>) commands.get(GraphicsManager.getInstance())).get(0);
    }

    private static void assertTextCommandsInsideChrome(List<EditorTextRenderer.TextCommand> commands,
                                                       int top,
                                                       int bottom) {
        assertFalse(commands.isEmpty());
        for (EditorTextRenderer.TextCommand command : commands) {
            assertTrue(command.y() >= top, "text command starts above chrome: " + command);
            assertTrue(command.y() + command.lineHeight() <= bottom,
                    "text command overflows chrome: " + command);
        }
    }

    private static LevelEditorController createPreviewController() {
        LevelEditorController controller = new LevelEditorController();
        controller.attachLevel(createPreviewLevel());
        return controller;
    }

    private static MutableLevel createPreviewLevel() {
        return MutableLevel.snapshot(new PreviewLevel());
    }

    private static final class PreviewLevel extends AbstractLevel {
        PreviewLevel() {
            super(0);
            patternCount = 1;
            patterns = new Pattern[] { new Pattern() };

            chunkCount = 9;
            chunks = new Chunk[chunkCount];
            for (int i = 0; i < chunkCount; i++) {
                chunks[i] = new Chunk();
                chunks[i].setPatternDesc(0, 0, new PatternDesc(i * 4 + 1));
                chunks[i].setPatternDesc(1, 0, new PatternDesc(i * 4 + 2));
                chunks[i].setPatternDesc(0, 1, new PatternDesc(i * 4 + 3));
                chunks[i].setPatternDesc(1, 1, new PatternDesc(i * 4 + 4));
            }

            blockCount = 3;
            blocks = new Block[blockCount];
            for (int i = 0; i < blockCount; i++) {
                blocks[i] = new Block(2);
                blocks[i].setChunkDesc(0, 0, new ChunkDesc(i));
                blocks[i].setChunkDesc(1, 0, new ChunkDesc((i + 1) % chunkCount));
                blocks[i].setChunkDesc(0, 1, new ChunkDesc((i + 2) % chunkCount));
                blocks[i].setChunkDesc(1, 1, new ChunkDesc((i + 3) % chunkCount));
            }

            solidTileCount = 0;
            solidTiles = new com.openggf.level.SolidTile[0];
            map = new com.openggf.level.Map(1, 2, 2);
            map.setValue(0, 0, 0, (byte) 0);
            map.setValue(0, 1, 0, (byte) 1);
            map.setValue(0, 0, 1, (byte) 2);
            map.setValue(0, 1, 1, (byte) 0);
            palettes = new com.openggf.level.Palette[] {
                    new com.openggf.level.Palette(),
                    new com.openggf.level.Palette(),
                    new com.openggf.level.Palette(),
                    new com.openggf.level.Palette()
            };
            objects = List.of();
            rings = List.of();
            minX = 0;
            maxX = 255;
            minY = 0;
            maxY = 191;
        }

        @Override
        public int getChunksPerBlockSide() {
            return 2;
        }

        @Override
        public int getBlockPixelSize() {
            return 128;
        }

        @Override
        public int getPaletteCount() {
            return palettes.length;
        }

        @Override
        public com.openggf.level.Palette getPalette(int index) {
            return palettes[index];
        }

        @Override
        public int getPatternCount() {
            return patternCount;
        }

        @Override
        public Pattern getPattern(int index) {
            return patterns[index];
        }

        @Override
        public int getChunkCount() {
            return chunkCount;
        }

        @Override
        public Chunk getChunk(int index) {
            return chunks[index];
        }

        @Override
        public int getBlockCount() {
            return blockCount;
        }

        @Override
        public Block getBlock(int index) {
            return blocks[index];
        }

        @Override
        public com.openggf.level.SolidTile getSolidTile(int index) {
            return solidTiles[index];
        }

        @Override
        public com.openggf.level.Map getMap() {
            return map;
        }

        @Override
        public List<ObjectSpawn> getObjects() {
            return objects;
        }

        @Override
        public List<RingSpawn> getRings() {
            return rings;
        }

        @Override
        public RingSpriteSheet getRingSpriteSheet() {
            return null;
        }

        @Override
        public int getMinX() {
            return minX;
        }

        @Override
        public int getMaxX() {
            return maxX;
        }

        @Override
        public int getMinY() {
            return minY;
        }

        @Override
        public int getMaxY() {
            return maxY;
        }

        @Override
        public int getZoneIndex() {
            return 0;
        }
    }
}


