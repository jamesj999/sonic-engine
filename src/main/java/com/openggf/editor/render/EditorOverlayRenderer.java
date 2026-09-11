package com.openggf.editor.render;

import com.openggf.editor.EditorFocusRegion;
import com.openggf.editor.EditorHierarchyDepth;
import com.openggf.editor.LevelEditorController;
import com.openggf.game.GameServices;
import com.openggf.graphics.GraphicsManager;

import java.util.Objects;

public final class EditorOverlayRenderer {
    private final LevelEditorController controller;
    private final EditorToolbarRenderer toolbar;
    private final EditorCommandStripRenderer commandStrip;
    private final EditorWorldOverlayRenderer worldOverlay;
    private final FocusedEditorPaneRenderer focusedPane;
    private final EditorLibraryPaneRenderer libraryPane;
    private EditorHierarchyDepth hierarchyDepth = EditorHierarchyDepth.WORLD;

    public EditorOverlayRenderer() {
        this(null);
    }

    public EditorOverlayRenderer(LevelEditorController controller) {
        this(controller, GameServices.graphics());
    }

    public EditorOverlayRenderer(LevelEditorController controller, GraphicsManager graphicsManager) {
        this(controller, new EditorToolbarRenderer(controller, graphicsManager),
                new EditorCommandStripRenderer(controller, graphicsManager),
                new EditorWorldOverlayRenderer(graphicsManager),
                new FocusedEditorPaneRenderer(controller, graphicsManager),
                new EditorLibraryPaneRenderer(controller, graphicsManager));
    }

    public EditorOverlayRenderer(EditorToolbarRenderer toolbar,
                                 EditorCommandStripRenderer commandStrip,
                                 EditorWorldOverlayRenderer worldOverlay,
                                 FocusedEditorPaneRenderer focusedPane,
                                 EditorLibraryPaneRenderer libraryPane) {
        this(null, toolbar, commandStrip, worldOverlay, focusedPane, libraryPane);
    }

    public EditorOverlayRenderer(LevelEditorController controller,
                                 EditorToolbarRenderer toolbar,
                                 EditorCommandStripRenderer commandStrip,
                                 EditorWorldOverlayRenderer worldOverlay,
                                 FocusedEditorPaneRenderer focusedPane,
                                 EditorLibraryPaneRenderer libraryPane) {
        this.controller = controller;
        this.toolbar = Objects.requireNonNull(toolbar, "toolbar");
        this.commandStrip = Objects.requireNonNull(commandStrip, "commandStrip");
        this.worldOverlay = Objects.requireNonNull(worldOverlay, "worldOverlay");
        this.focusedPane = Objects.requireNonNull(focusedPane, "focusedPane");
        this.libraryPane = Objects.requireNonNull(libraryPane, "libraryPane");
    }

    public void setHierarchyDepth(EditorHierarchyDepth hierarchyDepth) {
        this.hierarchyDepth = Objects.requireNonNull(hierarchyDepth, "hierarchyDepth");
    }

    public void render() {
        renderWorldSpaceOverlay();
        renderScreenSpaceOverlay();
    }

    public void renderWorldSpaceOverlay() {
        if (activeDepth() == EditorHierarchyDepth.WORLD) {
            worldOverlay.render();
        }
    }

    public void renderScreenSpaceOverlay() {
        switch (activeDepth()) {
            case WORLD -> renderWorldPlacementUi();
            case BLOCK -> renderFocusedBlockEdit();
            case CHUNK -> renderFocusedChunkEdit();
        }
    }

    public void renderWorldPlacementUi() {
        toolbar.render();
        renderLibraryPaneIfFocused(EditorHierarchyDepth.WORLD);
        commandStrip.render();
    }

    public void renderFocusedBlockEdit() {
        toolbar.render();
        focusedPane.renderBlockEditorPane();
        renderLibraryPaneIfFocused(EditorHierarchyDepth.BLOCK);
        commandStrip.render();
    }

    public void renderFocusedChunkEdit() {
        toolbar.render();
        focusedPane.renderChunkEditorPane();
        renderLibraryPaneIfFocused(EditorHierarchyDepth.CHUNK);
        commandStrip.render();
    }

    private void renderLibraryPaneIfFocused(EditorHierarchyDepth depth) {
        if (controller != null && isLibraryPaneFocus(depth, controller.focusRegion())) {
            libraryPane.render(depth);
        }
    }

    private static boolean isLibraryPaneFocus(EditorHierarchyDepth depth, EditorFocusRegion focusRegion) {
        return switch (depth) {
            case WORLD -> focusRegion == EditorFocusRegion.BLOCK_PANE;
            case BLOCK -> focusRegion == EditorFocusRegion.CHUNK_PANE;
            case CHUNK -> focusRegion == EditorFocusRegion.PATTERN_PANE;
        };
    }

    private EditorHierarchyDepth activeDepth() {
        return controller == null ? hierarchyDepth : controller.depth();
    }
}
