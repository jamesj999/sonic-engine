package com.openggf.editor;

import com.openggf.editor.commands.DeriveBlockFromChunksCommand;
import com.openggf.editor.commands.DeriveChunkFromPatternsCommand;
import com.openggf.editor.commands.PlaceBlockCommand;
import com.openggf.level.Block;
import com.openggf.level.Chunk;
import com.openggf.level.ChunkDesc;
import com.openggf.level.MutableLevel;
import com.openggf.level.PatternDesc;
import com.openggf.game.session.EditorCursorState;

import java.util.Objects;

public final class LevelEditorController {
    private static final int CHUNK_INDEX_MASK = 0x03FF;

    private final EditorHistory history = new EditorHistory();
    private EditorHierarchyDepth depth = EditorHierarchyDepth.WORLD;
    private EditorFocusRegion focusRegion = EditorFocusRegion.WORLD_CANVAS;
    private EditorSelectionState selection = EditorSelectionState.empty();
    private EditorCursorState worldCursor = new EditorCursorState(0, 0);
    private int blockGridSide = 8;
    private int selectedBlockCellX;
    private int selectedBlockCellY;
    private int selectedChunkCellX;
    private int selectedChunkCellY;
    private Integer selectedChunkDescriptorRaw;
    private Integer selectedPatternRaw;
    private int activeLayer;
    private MutableLevel level;

    public void attachLevel(MutableLevel level) {
        this.level = Objects.requireNonNull(level, "level");
        history.clear();
        depth = EditorHierarchyDepth.WORLD;
        focusRegion = EditorFocusRegion.WORLD_CANVAS;
        selection = EditorSelectionState.empty();
        worldCursor = new EditorCursorState(0, 0);
        blockGridSide = level.getChunksPerBlockSide();
        selectedBlockCellX = 0;
        selectedBlockCellY = 0;
        selectedChunkCellX = 0;
        selectedChunkCellY = 0;
        selectedChunkDescriptorRaw = null;
        selectedPatternRaw = null;
        activeLayer = 0;
    }

    public void placeBlock(int layer, int x, int y, int blockIndex) {
        MutableLevel attachedLevel = requireLevel();
        int before = Byte.toUnsignedInt(attachedLevel.getMap().getValue(layer, x, y));
        history.execute(new PlaceBlockCommand(attachedLevel, layer, x, y, before, blockIndex));
    }

    public void executeCommand(EditorCommand command) {
        history.execute(command);
        refreshSelectionFromActiveTarget();
    }

    public MutableLevel currentLevel() {
        return level;
    }

    public void undo() {
        if (history.undo()) {
            refreshSelectionFromActiveTarget();
        }
    }

    public void redo() {
        if (history.redo()) {
            refreshSelectionFromActiveTarget();
        }
    }

    public int activeLayer() {
        return activeLayer;
    }

    public void toggleActiveLayer() {
        MutableLevel attachedLevel = requireLevel();
        int layerCount = attachedLevel.getMap().getLayerCount();
        if (layerCount < 2) {
            activeLayer = 0;
            return;
        }
        activeLayer = activeLayer == 0 ? 1 : 0;
    }

    public boolean hasUndoHistory() {
        return history.hasUndoEntries();
    }

    public void selectBlock(int blockIndex) {
        requireNonNegative(blockIndex, "blockIndex");
        selection = new EditorSelectionState(blockIndex, null);
        selectedChunkDescriptorRaw = null;
        if (depth == EditorHierarchyDepth.CHUNK) {
            depth = EditorHierarchyDepth.BLOCK;
            focusRegion = EditorFocusRegion.BLOCK_PANE;
        }
    }

    public void selectChunk(int chunkIndex) {
        requireNonNegative(chunkIndex, "chunkIndex");
        if (selection.selectedBlock() == null) {
            throw new IllegalStateException("Cannot select a chunk without a selected block");
        }
        selection = new EditorSelectionState(selection.selectedBlock(), chunkIndex);
        selectedChunkDescriptorRaw = unflaggedChunkDescriptorRaw(chunkIndex);
    }

    public Block selectedBlockPreview() {
        Integer selectedBlock = selection.selectedBlock();
        if (selectedBlock == null) {
            return null;
        }
        MutableLevel attachedLevel = requireLevel();
        if (selectedBlock < 0 || selectedBlock >= attachedLevel.getBlockCount()) {
            return null;
        }
        return attachedLevel.getBlock(selectedBlock);
    }

    public Chunk selectedBlockCellPreview() {
        Block block = selectedBlockPreview();
        if (block == null) {
            return null;
        }
        if (selectedBlockCellX < 0 || selectedBlockCellX >= block.getGridSide()
                || selectedBlockCellY < 0 || selectedBlockCellY >= block.getGridSide()) {
            return null;
        }
        int chunkIndex = block.getChunkDesc(selectedBlockCellX, selectedBlockCellY).getChunkIndex();
        MutableLevel attachedLevel = requireLevel();
        if (chunkIndex < 0 || chunkIndex >= attachedLevel.getChunkCount()) {
            return null;
        }
        return attachedLevel.getChunk(chunkIndex);
    }

    public Chunk selectedBlockChunkPreview(int blockCellX, int blockCellY) {
        Block block = selectedBlockPreview();
        if (block == null) {
            return null;
        }
        if (blockCellX < 0 || blockCellY < 0
                || blockCellX >= block.getGridSide()
                || blockCellY >= block.getGridSide()) {
            return null;
        }
        int chunkIndex = block.getChunkDesc(blockCellX, blockCellY).getChunkIndex();
        MutableLevel attachedLevel = requireLevel();
        if (chunkIndex < 0 || chunkIndex >= attachedLevel.getChunkCount()) {
            return null;
        }
        return attachedLevel.getChunk(chunkIndex);
    }

    public Chunk selectedChunkPreview() {
        Integer selectedChunk = selection.selectedChunk();
        if (selectedChunk == null) {
            return null;
        }
        MutableLevel attachedLevel = requireLevel();
        if (selectedChunk < 0 || selectedChunk >= attachedLevel.getChunkCount()) {
            return null;
        }
        return attachedLevel.getChunk(selectedChunk);
    }

    public void descend() {
        if (depth == EditorHierarchyDepth.WORLD && selection.selectedBlock() != null) {
            depth = EditorHierarchyDepth.BLOCK;
            focusRegion = EditorFocusRegion.BLOCK_PANE;
        } else if (depth == EditorHierarchyDepth.BLOCK && selection.selectedChunk() != null) {
            depth = EditorHierarchyDepth.CHUNK;
            focusRegion = EditorFocusRegion.CHUNK_PANE;
        }
    }

    public void ascend() {
        if (depth == EditorHierarchyDepth.CHUNK) {
            depth = EditorHierarchyDepth.BLOCK;
            focusRegion = EditorFocusRegion.BLOCK_PANE;
        } else if (depth == EditorHierarchyDepth.BLOCK) {
            depth = EditorHierarchyDepth.WORLD;
            focusRegion = EditorFocusRegion.WORLD_CANVAS;
        }
    }

    public EditorHierarchyDepth depth() {
        return depth;
    }

    public EditorFocusRegion focusRegion() {
        return focusRegion;
    }

    public void cycleFocusRegion() {
        EditorFocusRegion[] cycle = activeFocusCycle();
        for (int i = 0; i < cycle.length; i++) {
            if (cycle[i] == focusRegion) {
                focusRegion = cycle[(i + 1) % cycle.length];
                return;
            }
        }
        focusRegion = cycle[0];
    }

    public void applyPrimaryAction() {
        if (depth == EditorHierarchyDepth.BLOCK) {
            applyBlockPrimaryAction();
            return;
        }
        if (depth == EditorHierarchyDepth.CHUNK) {
            applyChunkPrimaryAction();
            return;
        }
        applyWorldPrimaryAction();
    }

    private void applyWorldPrimaryAction() {
        if (focusRegion != EditorFocusRegion.WORLD_CANVAS) {
            return;
        }
        Integer selectedBlock = selection.selectedBlock();
        if (selectedBlock == null) {
            return;
        }
        MutableLevel attachedLevel = level;
        if (attachedLevel == null) {
            return;
        }
        WorldMapPosition mapPosition = resolveWorldMapPosition(attachedLevel);
        if (mapPosition == null) {
            return;
        }
        placeBlock(activeLayer, mapPosition.mapX(), mapPosition.mapY(), selectedBlock);
    }

    private void applyBlockPrimaryAction() {
        if (focusRegion != EditorFocusRegion.BLOCK_PANE) {
            return;
        }
        Integer selectedChunk = selection.selectedChunk();
        if (selectedChunk == null) {
            return;
        }
        MutableLevel attachedLevel = level;
        if (attachedLevel == null || !isValidChunkIndex(attachedLevel, selectedChunk)) {
            return;
        }
        WorldMapPosition mapPosition = resolveWorldMapPosition(attachedLevel);
        if (mapPosition == null) {
            return;
        }
        int sourceBlockIndex = Byte.toUnsignedInt(attachedLevel.getMap().getValue(activeLayer, mapPosition.mapX(), mapPosition.mapY()));
        if (!Objects.equals(selection.selectedBlock(), sourceBlockIndex)
                || !isValidBlockIndex(attachedLevel, sourceBlockIndex)
                || !isBlockCellInBounds(attachedLevel.getBlock(sourceBlockIndex), selectedBlockCellX, selectedBlockCellY)) {
            return;
        }
        int derivedBlockIndex = findUnreferencedBlockSlot(attachedLevel, sourceBlockIndex);
        if (derivedBlockIndex < 0) {
            return;
        }
        int[] derivedBlockBeforeState = attachedLevel.getBlock(derivedBlockIndex).saveState();
        int replacementChunkRaw = selectedChunkDescriptorRaw != null
                ? selectedChunkDescriptorRaw
                : unflaggedChunkDescriptorRaw(selectedChunk);
        history.execute(new DeriveBlockFromChunksCommand(
                attachedLevel,
                activeLayer,
                mapPosition.mapX(),
                mapPosition.mapY(),
                sourceBlockIndex,
                derivedBlockIndex,
                derivedBlockBeforeState,
                new ChunkDesc(replacementChunkRaw),
                selectedBlockCellX,
                selectedBlockCellY
        ));
        refreshSelectionFromActiveTarget();
    }

    private void applyChunkPrimaryAction() {
        if (focusRegion != EditorFocusRegion.CHUNK_PANE || selectedPatternRaw == null) {
            return;
        }
        Integer selectedBlock = selection.selectedBlock();
        if (selectedBlock == null) {
            return;
        }
        MutableLevel attachedLevel = level;
        if (attachedLevel == null || !isValidBlockIndex(attachedLevel, selectedBlock)) {
            return;
        }
        WorldMapPosition mapPosition = resolveWorldMapPosition(attachedLevel);
        if (mapPosition == null) {
            return;
        }
        int sourceBlockIndex = Byte.toUnsignedInt(attachedLevel.getMap().getValue(activeLayer, mapPosition.mapX(), mapPosition.mapY()));
        if (sourceBlockIndex != selectedBlock || !isValidBlockIndex(attachedLevel, sourceBlockIndex)) {
            return;
        }
        Block block = attachedLevel.getBlock(sourceBlockIndex);
        if (!isBlockCellInBounds(block, selectedBlockCellX, selectedBlockCellY)) {
            return;
        }
        ChunkDesc sourceChunkDesc = block.getChunkDesc(selectedBlockCellX, selectedBlockCellY);
        int sourceChunkIndex = sourceChunkDesc.getChunkIndex();
        if (!Objects.equals(selection.selectedChunk(), sourceChunkIndex)
                || !isValidChunkIndex(attachedLevel, sourceChunkIndex)) {
            return;
        }
        int derivedBlockIndex = findUnreferencedBlockSlot(attachedLevel, sourceBlockIndex);
        if (derivedBlockIndex < 0) {
            return;
        }
        int derivedChunkIndex = findUnreferencedChunkSlot(attachedLevel, sourceChunkIndex);
        if (derivedChunkIndex < 0) {
            return;
        }
        int[] derivedBlockBeforeState = attachedLevel.getBlock(derivedBlockIndex).saveState();
        int[] derivedChunkBeforeState = attachedLevel.getChunk(derivedChunkIndex).saveState();
        EditorCommand chunkCommand = new DeriveChunkFromPatternsCommand(
                attachedLevel,
                derivedBlockIndex,
                selectedBlockCellX,
                selectedBlockCellY,
                sourceChunkIndex,
                derivedChunkIndex,
                derivedChunkBeforeState,
                new PatternDesc(selectedPatternRaw),
                selectedChunkCellX,
                selectedChunkCellY
        );
        EditorCommand blockCommand = new DeriveBlockFromChunksCommand(
                attachedLevel,
                activeLayer,
                mapPosition.mapX(),
                mapPosition.mapY(),
                sourceBlockIndex,
                derivedBlockIndex,
                derivedBlockBeforeState,
                new ChunkDesc(replaceChunkIndex(sourceChunkDesc.get(), derivedChunkIndex)),
                selectedBlockCellX,
                selectedBlockCellY
        );
        history.execute(new CompositeEditorCommand(chunkCommand, blockCommand));
        refreshSelectionFromActiveTarget();
    }

    public void performEyedrop() {
        if (depth == EditorHierarchyDepth.CHUNK) {
            performChunkEyedrop();
            return;
        }
        if (depth == EditorHierarchyDepth.BLOCK) {
            performBlockEyedrop();
            return;
        }
        if (focusRegion != EditorFocusRegion.WORLD_CANVAS) {
            return;
        }
        MutableLevel attachedLevel = level;
        if (attachedLevel == null) {
            return;
        }
        WorldMapPosition mapPosition = resolveWorldMapPosition(attachedLevel);
        if (mapPosition == null) {
            return;
        }
        int blockIndex = Byte.toUnsignedInt(attachedLevel.getMap().getValue(activeLayer, mapPosition.mapX(), mapPosition.mapY()));
        selectBlock(blockIndex);
    }

    private void performBlockEyedrop() {
        if (focusRegion != EditorFocusRegion.BLOCK_PANE) {
            return;
        }
        Integer selectedBlock = selection.selectedBlock();
        MutableLevel attachedLevel = level;
        if (selectedBlock == null || attachedLevel == null || !isValidBlockIndex(attachedLevel, selectedBlock)) {
            return;
        }
        Block block = attachedLevel.getBlock(selectedBlock);
        if (!isBlockCellInBounds(block, selectedBlockCellX, selectedBlockCellY)) {
            return;
        }
        ChunkDesc chunkDesc = block.getChunkDesc(selectedBlockCellX, selectedBlockCellY);
        int chunkIndex = chunkDesc.getChunkIndex();
        if (isValidChunkIndex(attachedLevel, chunkIndex)) {
            selection = new EditorSelectionState(selectedBlock, chunkIndex);
            selectedChunkDescriptorRaw = chunkDesc.get();
        }
    }

    private void performChunkEyedrop() {
        if (focusRegion != EditorFocusRegion.CHUNK_PANE) {
            return;
        }
        Integer selectedChunk = selection.selectedChunk();
        MutableLevel attachedLevel = level;
        if (selectedChunk == null || attachedLevel == null || !isValidChunkIndex(attachedLevel, selectedChunk)) {
            return;
        }
        Chunk chunk = attachedLevel.getChunk(selectedChunk);
        selectedPatternRaw = chunk.getPatternDesc(selectedChunkCellX, selectedChunkCellY).get();
    }

    public void setWorldCursor(EditorCursorState cursor) {
        Objects.requireNonNull(cursor, "cursor");
        this.worldCursor = clampWorldCursor(cursor.x(), cursor.y());
    }

    public EditorCursorState worldCursor() {
        return worldCursor;
    }

    public int blockGridSide() {
        return blockGridSide;
    }

    public int chunkGridSide() {
        return 2;
    }

    public void moveWorldCursor(int dx, int dy) {
        worldCursor = clampWorldCursor(worldCursor.x() + dx, worldCursor.y() + dy);
    }

    public void moveActiveSelection(int dx, int dy) {
        int gridSide = activeGridSide();
        if (depth == EditorHierarchyDepth.WORLD) {
            moveWorldCursor(dx, dy);
            return;
        }
        if (depth == EditorHierarchyDepth.BLOCK) {
            selectedBlockCellX = clamp(selectedBlockCellX + dx, 0, gridSide - 1);
            selectedBlockCellY = clamp(selectedBlockCellY + dy, 0, gridSide - 1);
            return;
        }
        selectedChunkCellX = clamp(selectedChunkCellX + dx, 0, gridSide - 1);
        selectedChunkCellY = clamp(selectedChunkCellY + dy, 0, gridSide - 1);
    }

    public int selectedBlockCellX() {
        return selectedBlockCellX;
    }

    public int selectedBlockCellY() {
        return selectedBlockCellY;
    }

    public int selectedChunkCellX() {
        return selectedChunkCellX;
    }

    public int selectedChunkCellY() {
        return selectedChunkCellY;
    }

    public EditorSelectionState selection() {
        return selection;
    }

    public Integer selectedBlockIndex() {
        return selection.selectedBlock();
    }

    public String breadcrumb() {
        if (depth == EditorHierarchyDepth.WORLD) {
            return "World";
        }
        if (depth == EditorHierarchyDepth.BLOCK) {
            return "World > Block " + selection.selectedBlock();
        }
        return "World > Block " + selection.selectedBlock() + " > Chunk " + selection.selectedChunk();
    }

    private static void requireNonNegative(int index, String name) {
        if (index < 0) {
            throw new IllegalArgumentException(name + " must be >= 0");
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private EditorCursorState clampWorldCursor(int x, int y) {
        if (level == null) {
            return new EditorCursorState(x, y);
        }
        int minX = level.getMinX();
        int maxX = level.getMaxX();
        int minY = level.getMinY();
        int maxY = level.getMaxY();
        return new EditorCursorState(clamp(x, minX, maxX), clamp(y, minY, maxY));
    }

    private WorldMapPosition resolveWorldMapPosition(MutableLevel attachedLevel) {
        int blockPixelSize = attachedLevel.getBlockPixelSize();
        if (blockPixelSize <= 0) {
            return null;
        }
        int mapX = worldCursor.x();
        int mapY = worldCursor.y();
        int mapWidth = attachedLevel.getMap().getWidth();
        int mapHeight = attachedLevel.getMap().getHeight();
        if (mapWidth <= 0 || mapHeight <= 0) {
            return null;
        }
        mapX = clamp(mapX / blockPixelSize, 0, mapWidth - 1);
        mapY = clamp(mapY / blockPixelSize, 0, mapHeight - 1);
        return new WorldMapPosition(mapX, mapY);
    }

    private int activeGridSide() {
        if (depth == EditorHierarchyDepth.BLOCK) {
            return blockGridSide;
        }
        if (depth == EditorHierarchyDepth.CHUNK) {
            return chunkGridSide();
        }
        return 1;
    }

    private EditorFocusRegion[] activeFocusCycle() {
        return switch (depth) {
            case WORLD -> new EditorFocusRegion[] {
                    EditorFocusRegion.WORLD_CANVAS,
                    EditorFocusRegion.BLOCK_PANE,
                    EditorFocusRegion.COMMAND_STRIP,
                    EditorFocusRegion.TOOLBAR
            };
            case BLOCK -> new EditorFocusRegion[] {
                    EditorFocusRegion.BLOCK_PANE,
                    EditorFocusRegion.CHUNK_PANE,
                    EditorFocusRegion.COMMAND_STRIP,
                    EditorFocusRegion.TOOLBAR
            };
            case CHUNK -> new EditorFocusRegion[] {
                    EditorFocusRegion.CHUNK_PANE,
                    EditorFocusRegion.PATTERN_PANE,
                    EditorFocusRegion.COMMAND_STRIP,
                    EditorFocusRegion.TOOLBAR
            };
        };
    }

    private static boolean isBlockCellInBounds(Block block, int x, int y) {
        return x >= 0 && y >= 0 && x < block.getGridSide() && y < block.getGridSide();
    }

    private static boolean isValidBlockIndex(MutableLevel level, int blockIndex) {
        return blockIndex >= 0 && blockIndex < level.getBlockCount();
    }

    private static boolean isValidChunkIndex(MutableLevel level, int chunkIndex) {
        return chunkIndex >= 0 && chunkIndex < level.getChunkCount();
    }

    private static int unflaggedChunkDescriptorRaw(int chunkIndex) {
        return chunkIndex & CHUNK_INDEX_MASK;
    }

    private static int replaceChunkIndex(int descriptorRaw, int chunkIndex) {
        return (descriptorRaw & ~CHUNK_INDEX_MASK) | unflaggedChunkDescriptorRaw(chunkIndex);
    }

    private static int findUnreferencedBlockSlot(MutableLevel level, int sourceBlockIndex) {
        for (int blockIndex = 0; blockIndex < level.getBlockCount(); blockIndex++) {
            if (blockIndex != sourceBlockIndex && !level.isBlockReferencedInMap(blockIndex)) {
                return blockIndex;
            }
        }
        return -1;
    }

    private static int findUnreferencedChunkSlot(MutableLevel level, int sourceChunkIndex) {
        for (int chunkIndex = 0; chunkIndex < level.getChunkCount(); chunkIndex++) {
            if (chunkIndex != sourceChunkIndex && !level.isChunkReferencedInBlocks(chunkIndex)) {
                return chunkIndex;
            }
        }
        return -1;
    }

    private void refreshSelectionFromActiveTarget() {
        if (depth == EditorHierarchyDepth.WORLD || level == null) {
            return;
        }
        WorldMapPosition mapPosition = resolveWorldMapPosition(level);
        if (mapPosition == null) {
            return;
        }
        int blockIndex = Byte.toUnsignedInt(level.getMap().getValue(activeLayer, mapPosition.mapX(), mapPosition.mapY()));
        if (!isValidBlockIndex(level, blockIndex)) {
            return;
        }
        Integer chunkIndex = null;
        Integer chunkDescriptorRaw = null;
        Block block = level.getBlock(blockIndex);
        if (isBlockCellInBounds(block, selectedBlockCellX, selectedBlockCellY)) {
            ChunkDesc activeChunkDesc = block.getChunkDesc(selectedBlockCellX, selectedBlockCellY);
            int activeChunkIndex = activeChunkDesc.getChunkIndex();
            if (isValidChunkIndex(level, activeChunkIndex)) {
                chunkIndex = activeChunkIndex;
                chunkDescriptorRaw = activeChunkDesc.get();
                if (depth == EditorHierarchyDepth.CHUNK) {
                    selectedPatternRaw = level.getChunk(activeChunkIndex)
                            .getPatternDesc(selectedChunkCellX, selectedChunkCellY)
                            .get();
                }
            }
        }
        selection = new EditorSelectionState(blockIndex, chunkIndex);
        selectedChunkDescriptorRaw = chunkDescriptorRaw;
    }

    private static final class CompositeEditorCommand implements EditorCommand {
        private final EditorCommand first;
        private final EditorCommand second;

        private CompositeEditorCommand(EditorCommand first, EditorCommand second) {
            this.first = Objects.requireNonNull(first, "first");
            this.second = Objects.requireNonNull(second, "second");
        }

        @Override
        public void apply() {
            first.apply();
            second.apply();
        }

        @Override
        public void undo() {
            second.undo();
            first.undo();
        }
    }

    private MutableLevel requireLevel() {
        if (level == null) {
            throw new IllegalStateException("No MutableLevel is attached to the editor controller");
        }
        return level;
    }

    private record WorldMapPosition(int mapX, int mapY) {
    }
}
