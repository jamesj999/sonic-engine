package com.openggf.editor.render;

import com.openggf.editor.EditorHierarchyDepth;
import com.openggf.editor.LevelEditorController;
import com.openggf.game.GameServices;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GLCommandGroup;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Block;
import com.openggf.level.Chunk;
import com.openggf.level.PatternDesc;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_LINES;

public class EditorLibraryPaneRenderer {
    private static final float CHROME_R = 0.44f;
    private static final float CHROME_G = 0.72f;
    private static final float CHROME_B = 1.0f;

    private final LevelEditorController controller;
    private final GraphicsManager graphicsManager;
    private final EditorTextRenderer textRenderer;

    public EditorLibraryPaneRenderer() {
        this(null, GameServices.graphics());
    }

    public EditorLibraryPaneRenderer(LevelEditorController controller) {
        this(controller, GameServices.graphics());
    }

    public EditorLibraryPaneRenderer(LevelEditorController controller, GraphicsManager graphicsManager) {
        this(controller, graphicsManager, new EditorTextRenderer(graphicsManager));
    }

    public EditorLibraryPaneRenderer(LevelEditorController controller,
                                     GraphicsManager graphicsManager,
                                     EditorTextRenderer textRenderer) {
        this.controller = controller;
        this.graphicsManager = Objects.requireNonNull(graphicsManager, "graphicsManager");
        this.textRenderer = Objects.requireNonNull(textRenderer, "textRenderer");
    }

    public void renderCommands(List<String> lines) {
        List<GLCommand> backdrop = new ArrayList<>();
        for (int[] point : new int[][] {{8, 34}, {312, 34}, {312, 192}, {8, 192}}) {
            backdrop.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1,
                    GLCommand.BlendType.ONE_MINUS_SRC_ALPHA, 0.025f, 0.04f, 0.07f,
                    point[0], point[1], 0, 0));
        }
        graphicsManager.registerCommand(new GLCommandGroup(org.lwjgl.opengl.GL11.GL_TRIANGLE_FAN, backdrop));
        textRenderer.renderLines(lines, 18, 42);
    }

    public void render(EditorHierarchyDepth depth) {
        List<GLCommand> commands = new ArrayList<>();
        appendCommands(commands);
        if (!commands.isEmpty()) {
            graphicsManager.registerCommand(new GLCommandGroup(GL_LINES, commands));
        }
        textRenderer.renderLines(buildLibraryLines(depth), 18, 48);
        renderSelectedPreview();
    }

    protected void appendCommands(List<GLCommand> commands) {
        EditorToolbarRenderer.appendRectOutline(commands, 12, 34, 152, 194, CHROME_R, CHROME_G, CHROME_B);
        EditorToolbarRenderer.appendLine(commands, 12, 60, 152, 60, CHROME_R, CHROME_G, CHROME_B);
        EditorToolbarRenderer.appendLine(commands, 12, 90, 152, 90, CHROME_R, CHROME_G, CHROME_B);
        EditorToolbarRenderer.appendLine(commands, 54, 90, 54, 194, CHROME_R, CHROME_G, CHROME_B);
        EditorToolbarRenderer.appendLine(commands, 96, 90, 96, 194, CHROME_R, CHROME_G, CHROME_B);
        EditorToolbarRenderer.appendLine(commands, 12, 128, 152, 128, CHROME_R, CHROME_G, CHROME_B);
        EditorToolbarRenderer.appendLine(commands, 12, 164, 152, 164, CHROME_R, CHROME_G, CHROME_B);
        if(controller!=null) {
            EditorLibraryBrowserPane.Entry selected=controller.libraryBrowser().selected();
            if(selected!=null&&selected.kind()==EditorLibraryBrowserPane.Kind.OBJECT&&!hasExactObjectArtPreview()) {
                EditorToolbarRenderer.appendRectOutline(commands,62,98,102,138,.7f,.7f,.7f);
                EditorToolbarRenderer.appendLine(commands,62,98,102,138,.7f,.7f,.7f);
                EditorToolbarRenderer.appendLine(commands,102,98,62,138,.7f,.7f,.7f);
            }
        }
    }

    protected List<String> buildLibraryLines(EditorHierarchyDepth depth) {
        if (controller == null) {
            return List.of(libraryTitle(depth), "No controller");
        }

        List<String> lines = new ArrayList<>();
        EditorLibraryBrowserPane browser=controller.libraryBrowser();
        EditorLibraryBrowserPane.Entry selected=browser.selected();
        lines.add(selected==null?libraryTitle(depth):switch(selected.kind()) {
            case BLOCK -> "Block library"; case CHUNK -> "Chunk library"; case OBJECT -> "Object palette";
        });
        lines.add("Focus " + controller.focusRegion());
        lines.add("Filter " + (browser.filter().isEmpty()?"-":browser.filter()));
        lines.add("Filter input "+(controller.isLibraryFilterInputActive()?"ON":"OFF (Insert)"));
        lines.add("Cursor " + browser.cursor() + "/" + browser.visibleEntries().size());
        if(selected!=null)lines.add(selected.kind()==EditorLibraryBrowserPane.Kind.OBJECT
                ? (hasExactObjectArtPreview()?"Preview exact object art":"Preview [generic object]")
                : "Preview loaded tileset #"+selected.index());
        int start=Math.max(0,browser.cursor()-1),end=Math.min(browser.visibleEntries().size(),start+5);
        for(int i=start;i<end;i++)lines.add((i==browser.cursor()?"> ":"  ")+browser.visibleEntries().get(i).label());
        return lines;
    }

    protected List<PreviewPlacement> buildSelectedTilePreview() {
        if(controller==null)return List.of();
        EditorLibraryBrowserPane.Entry entry=controller.libraryBrowser().selected();
        if(entry==null||entry.kind()==EditorLibraryBrowserPane.Kind.OBJECT)return List.of();
        List<PreviewPlacement> placements=new ArrayList<>();
        if(entry.kind()==EditorLibraryBrowserPane.Kind.CHUNK) {
            appendChunk(placements,controller.libraryPreviewChunk(entry.index()),64,108);
        } else {
            Block block=controller.libraryPreviewBlock(entry.index());
            if(block!=null)for(int y=0;y<Math.min(8,block.getGridSide());y++)for(int x=0;x<Math.min(8,block.getGridSide());x++) {
                int chunkIndex=block.getChunkDesc(x,y).getChunkIndex();
                appendChunk(placements,controller.libraryPreviewChunk(chunkIndex),16+x*16,64+y*16);
            }
        }
        return List.copyOf(placements);
    }

    private static void appendChunk(List<PreviewPlacement> placements,Chunk chunk,int originX,int originY) {
        if(chunk==null)return;
        for(int y=0;y<2;y++)for(int x=0;x<2;x++)placements.add(
                new PreviewPlacement(chunk.getPatternDesc(x,y),originX+x*8,originY+y*8));
    }

    protected boolean hasExactObjectArtPreview() {
        if(controller==null)return false;
        EditorLibraryBrowserPane.Entry selected=controller.libraryBrowser().selected();
        return selected!=null&&selected.previewArtKey()!=null
                &&controller.libraryObjectPreviewRenderer(selected.previewArtKey())!=null;
    }

    private void renderSelectedPreview() {
        for(PreviewPlacement placement:buildSelectedTilePreview())graphicsManager.renderPatternWithId(
                placement.descriptor().getPatternIndex(),placement.descriptor(),placement.x(),placement.y());
        if(controller==null)return;
        EditorLibraryBrowserPane.Entry selected=controller.libraryBrowser().selected();
        if(selected!=null&&selected.previewArtKey()!=null) {
            var renderer=controller.libraryObjectPreviewRenderer(selected.previewArtKey());
            if(renderer!=null)renderer.drawFrameIndex(0,82,112);
        }
    }

    protected record PreviewPlacement(PatternDesc descriptor,int x,int y) {}

    private static String libraryTitle(EditorHierarchyDepth depth) {
        return switch (depth) {
            case WORLD -> "Block library";
            case BLOCK -> "Chunk library";
            case CHUNK -> "Pattern library";
        };
    }

    private static String valueOrNone(Integer value) {
        return value == null ? "-" : value.toString();
    }
}
