package com.openggf.editor;

import com.openggf.editor.commands.DeriveBlockFromChunksCommand;
import com.openggf.editor.commands.DeriveChunkFromPatternsCommand;
import com.openggf.editor.commands.PlaceBlockCommand;
import com.openggf.editor.commands.DeleteObjectSpawnCommand;
import com.openggf.editor.commands.DeleteRingSpawnCommand;
import com.openggf.editor.commands.CycleCellCollisionModeCommand;
import com.openggf.editor.commands.MoveObjectSpawnCommand;
import com.openggf.editor.commands.MoveRingSpawnCommand;
import com.openggf.editor.commands.PlaceObjectSpawnCommand;
import com.openggf.editor.commands.PlaceRingSpawnCommand;
import com.openggf.editor.commands.SetChunkSolidTileIndexCommand;
import com.openggf.editor.persistence.EditorSaveManager;
import com.openggf.editor.persistence.FullLevelExporter;
import com.openggf.editor.render.EditorLibraryBrowserPane;
import com.openggf.level.Block;
import com.openggf.level.Chunk;
import com.openggf.level.ChunkDesc;
import com.openggf.level.MutableLevel;
import com.openggf.level.PatternDesc;
import com.openggf.level.objects.ObjectPlacementEncoding;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.rings.RingSpawn;
import com.openggf.game.session.EditorCursorState;
import com.openggf.game.ObjectArtProvider;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.Objects;

@com.openggf.game.ModApi
public final class LevelEditorController {
    private final EditorCommandPalette commandPalette = new EditorCommandPalette(this::toggleLibraryFilterInput);

    EditorCommandPalette commandPalette() { return commandPalette; }
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
    private EditorSpawnEditMode spawnEditMode = EditorSpawnEditMode.TILES;
    private EditorStockObjectPalette objectPalette;
    private EditorSpawnFactory spawnFactory;
    private ObjectSpawn selectedObjectSpawn;
    private RingSpawn selectedRingSpawn;
    private ObjectSpawn selectedRingBackingObject;
    private boolean collisionOverlayEnabled;
    private EditorCollisionPath collisionPath = EditorCollisionPath.PRIMARY;
    private EditorSaveManager.ApplyResult persistenceStatus = EditorSaveManager.ApplyResult.NONE;
    private java.nio.file.Path lastExportDirectory;
    private final EditorLibraryBrowserPane libraryBrowser = new EditorLibraryBrowserPane();
    private ObjectArtProvider objectArtProvider;
    private boolean libraryFilterInputActive;

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
        spawnEditMode = EditorSpawnEditMode.TILES;
        objectPalette = null;
        spawnFactory = null;
        objectArtProvider = null;
        selectedObjectSpawn = null;
        selectedRingSpawn = null;
        selectedRingBackingObject = null;
        collisionOverlayEnabled = false;
        collisionPath = EditorCollisionPath.PRIMARY;
        libraryBrowser.setFilter("");
        libraryFilterInputActive=false;
        refreshLibraryBrowser();
    }

    public void configureSpawnEditing(ObjectRegistry registry, ObjectPlacementEncoding encoding) {
        configureSpawnEditing(registry, encoding, null);
    }

    public void configureSpawnEditing(ObjectRegistry registry, ObjectPlacementEncoding encoding,
                                      ObjectArtProvider objectArtProvider) {
        MutableLevel attachedLevel = requireLevel();
        objectPalette = new EditorStockObjectPalette(registry);
        spawnFactory = new EditorSpawnFactory(encoding, new EditorPlacementIdAllocator(attachedLevel));
        this.objectArtProvider = objectArtProvider;
        refreshLibraryBrowser();
    }

    public void setSpawnEditMode(EditorSpawnEditMode mode) {
        libraryFilterInputActive=false;
        spawnEditMode = Objects.requireNonNull(mode, "mode");
        selectedObjectSpawn = null;
        selectedRingSpawn = null;
        selectedRingBackingObject = null;
        refreshLibraryBrowser();
    }

    public EditorSpawnEditMode spawnEditMode() { return spawnEditMode; }
    public boolean isSpawnEditing() { return spawnEditMode != EditorSpawnEditMode.TILES; }
    public EditorStockObjectPalette objectPalette() {
        if (objectPalette == null) throw new IllegalStateException("Spawn editing is not configured");
        return objectPalette;
    }

    public void cycleSpawnEditMode() {
        setSpawnEditMode(switch (spawnEditMode) {
            case TILES -> EditorSpawnEditMode.OBJECTS;
            case OBJECTS -> EditorSpawnEditMode.RINGS;
            case RINGS -> EditorSpawnEditMode.TILES;
        });
    }

    public void placeObjectSpawnAtCursor() {
        requireSpawnMode(EditorSpawnEditMode.OBJECTS);
        if (!objectPalette().selectedIsKeyed()
                && !requireSpawnFactory().canCreateObject(objectPalette().selectedObjectId())) {
            return;
        }
        ObjectSpawn spawn = objectPalette().selectedIsKeyed()
                ? requireSpawnFactory().createKeyedObjectSpawn(worldCursor.x(), worldCursor.y(),
                        objectPalette().selectedObjectKey(), objectPalette().selectedSubtype(), 0, true)
                : requireSpawnFactory().createObjectSpawn(worldCursor.x(), worldCursor.y(),
                        objectPalette().selectedObjectId(), objectPalette().selectedSubtype(), 0, true);
        history.execute(new PlaceObjectSpawnCommand(requireLevel(), spawn));
        selectedObjectSpawn = spawn;
    }

    public void placeRingSpawnAtCursor() {
        requireSpawnMode(EditorSpawnEditMode.RINGS);
        RingSpawn spawn = requireSpawnFactory().createRingSpawn(worldCursor.x(), worldCursor.y());
        ObjectSpawn backingObject = requireSpawnFactory().createRingBackingObject(spawn);
        history.execute(new PlaceRingSpawnCommand(requireLevel(), spawn, backingObject));
        selectedRingSpawn = spawn;
        selectedRingBackingObject = backingObject;
    }

    public void placeSpawnAtCursor() {
        if (spawnEditMode == EditorSpawnEditMode.OBJECTS) placeObjectSpawnAtCursor();
        else if (spawnEditMode == EditorSpawnEditMode.RINGS) placeRingSpawnAtCursor();
    }

    public void deleteSpawnAtCursor() {
        MutableLevel attachedLevel = requireLevel();
        if (spawnEditMode == EditorSpawnEditMode.OBJECTS) {
            ObjectSpawn spawn = findObjectAtCursor();
            if (spawn != null) {
                history.execute(new DeleteObjectSpawnCommand(attachedLevel, spawn));
                if (selectedObjectSpawn != null && selectedObjectSpawn.layoutIndex() == spawn.layoutIndex()) selectedObjectSpawn = null;
            }
        } else if (spawnEditMode == EditorSpawnEditMode.RINGS) {
            RingSpawn spawn = findRingAtCursor();
            if (spawn != null) {
                ObjectSpawn backingObject = attachedLevel.ringBackingObject(spawn);
                history.execute(new DeleteRingSpawnCommand(attachedLevel, spawn, backingObject));
                if (selectedRingSpawn != null && selectedRingSpawn.placementId() == spawn.placementId()) selectedRingSpawn = null;
                selectedRingBackingObject = null;
            }
        }
    }

    public void moveSelectedSpawn(int x, int y) {
        MutableLevel attachedLevel = requireLevel();
        if (spawnEditMode == EditorSpawnEditMode.OBJECTS && selectedObjectSpawn != null) {
            ObjectSpawn moved = requireSpawnFactory().moveObjectSpawn(selectedObjectSpawn, x, y);
            history.execute(new MoveObjectSpawnCommand(attachedLevel, selectedObjectSpawn, moved));
            selectedObjectSpawn = moved;
        } else if (spawnEditMode == EditorSpawnEditMode.RINGS && selectedRingSpawn != null) {
            RingSpawn moved = requireSpawnFactory().moveRingSpawn(selectedRingSpawn, x, y);
            int dx = x - selectedRingSpawn.x();
            int dy = y - selectedRingSpawn.y();
            ObjectSpawn movedBackingObject = selectedRingBackingObject == null ? null
                    : requireSpawnFactory().moveRingBackingObject(selectedRingBackingObject,
                            selectedRingBackingObject.x() + dx,
                            selectedRingBackingObject.y() + dy);
            history.execute(new MoveRingSpawnCommand(attachedLevel, selectedRingSpawn, moved,
                    selectedRingBackingObject, movedBackingObject));
            selectedRingSpawn = moved;
            selectedRingBackingObject = movedBackingObject;
        }
    }

    public void eyedropSpawnAtCursor() {
        if (spawnEditMode == EditorSpawnEditMode.OBJECTS) {
            selectedObjectSpawn = findObjectAtCursor();
            if (selectedObjectSpawn != null) objectPalette().eyedrop(selectedObjectSpawn);
        } else if (spawnEditMode == EditorSpawnEditMode.RINGS) {
            selectedRingSpawn = findRingAtCursor();
            selectedRingBackingObject = selectedRingSpawn == null ? null : requireLevel().ringBackingObject(selectedRingSpawn);
        }
    }

    private ObjectSpawn findObjectAtCursor() {
        if (level == null) return null;
        return level.getObjects().stream()
                .filter(s -> s.x() == worldCursor.x() && s.y() == worldCursor.y())
                .filter(s -> !level.ringObjectPlacementMapping().containsKey(s))
                .findFirst().orElse(null);
    }

    private RingSpawn findRingAtCursor() {
        if (level == null) return null;
        return level.getRings().stream()
                .filter(s -> s.x() == worldCursor.x() && s.y() == worldCursor.y())
                .findFirst().orElse(null);
    }

    private EditorSpawnFactory requireSpawnFactory() {
        if (spawnFactory == null) throw new IllegalStateException("Spawn editing is not configured");
        return spawnFactory;
    }

    private void requireSpawnMode(EditorSpawnEditMode expected) {
        if (spawnEditMode != expected) throw new IllegalStateException("Editor is not in " + expected + " mode");
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

    public EditorSaveManager.ApplyResult persistenceStatus() {
        return persistenceStatus;
    }

    public void setPersistenceStatus(EditorSaveManager.ApplyResult persistenceStatus) {
        this.persistenceStatus = Objects.requireNonNull(persistenceStatus, "persistenceStatus");
    }

    public FullLevelExporter.ExportResult exportLevel(FullLevelExporter exporter,
                                                       FullLevelExporter.ExportRequest request)
            throws java.io.IOException {
        if (level == null) throw new IllegalStateException("No mutable level is attached");
        FullLevelExporter.ExportResult result = exporter.export(level, request);
        lastExportDirectory = result.directory();
        return result;
    }

    public java.nio.file.Path lastExportDirectory() { return lastExportDirectory; }

    public boolean isCollisionOverlayEnabled() {
        return collisionOverlayEnabled;
    }

    public void toggleCollisionOverlay() {
        collisionOverlayEnabled = !collisionOverlayEnabled;
    }

    public EditorCollisionPath collisionPath() {
        return collisionPath;
    }

    public void toggleCollisionPath() {
        collisionPath = collisionPath.other();
    }

    public void cycleSelectedCellCollisionMode() {
        MutableLevel attachedLevel = level;
        Integer blockIndex = selection.selectedBlock();
        if (attachedLevel == null || blockIndex == null || depth != EditorHierarchyDepth.BLOCK
                || !isValidBlockIndex(attachedLevel, blockIndex)) {
            return;
        }
        Block block = attachedLevel.getBlock(blockIndex);
        int cellIndex = selectedBlockCellY * block.getGridSide() + selectedBlockCellX;
        executeCommand(new CycleCellCollisionModeCommand(
                attachedLevel, blockIndex, cellIndex, collisionPath));
    }

    public void setSelectedChunkSolidTileIndex(int newIndex) {
        MutableLevel attachedLevel = level;
        Integer chunkIndex = selection.selectedChunk();
        if (attachedLevel == null || chunkIndex == null || !isCollisionIndexEditingDepth()
                || !isValidChunkIndex(attachedLevel, chunkIndex)) {
            return;
        }
        Chunk chunk = attachedLevel.getChunk(chunkIndex);
        int current = collisionPath == EditorCollisionPath.PRIMARY
                ? chunk.getSolidTileIndex() : chunk.getSolidTileAltIndex();
        if (current == newIndex) {
            return;
        }
        executeCommand(new SetChunkSolidTileIndexCommand(
                attachedLevel, chunkIndex, collisionPath, newIndex));
    }

    public void adjustSelectedChunkSolidTileIndex(int delta) {
        MutableLevel attachedLevel = level;
        Integer chunkIndex = selection.selectedChunk();
        if (attachedLevel == null || chunkIndex == null || !isCollisionIndexEditingDepth()
                || !isValidChunkIndex(attachedLevel, chunkIndex)) {
            return;
        }
        Chunk chunk = attachedLevel.getChunk(chunkIndex);
        int current = collisionPath == EditorCollisionPath.PRIMARY
                ? chunk.getSolidTileIndex() : chunk.getSolidTileAltIndex();
        int maxIndex = Math.max(0, attachedLevel.getSolidTileCount() - 1);
        setSelectedChunkSolidTileIndex(clamp(current + delta, 0, maxIndex));
    }

    private boolean isCollisionIndexEditingDepth() {
        return depth == EditorHierarchyDepth.BLOCK || depth == EditorHierarchyDepth.CHUNK;
    }

    public void undo() {
        if (history.undo()) {
            refreshSelectionFromActiveTarget();
            reconcileSpawnSelection();
        }
    }

    public void redo() {
        if (history.redo()) {
            refreshSelectionFromActiveTarget();
            reconcileSpawnSelection();
        }
    }

    private void reconcileSpawnSelection() {
        if (level == null) {
            selectedObjectSpawn = null;
            selectedRingSpawn = null;
            selectedRingBackingObject = null;
            return;
        }
        if (selectedObjectSpawn != null) {
            int placementId = selectedObjectSpawn.layoutIndex();
            selectedObjectSpawn = level.getObjects().stream()
                    .filter(spawn -> spawn.layoutIndex() == placementId)
                    .findFirst().orElse(null);
        }
        if (selectedRingSpawn != null) {
            int placementId = selectedRingSpawn.placementId();
            selectedRingSpawn = level.getRings().stream()
                    .filter(spawn -> spawn.placementId() == placementId)
                    .findFirst().orElse(null);
        }
        selectedRingBackingObject = selectedRingSpawn == null
                ? null : level.ringBackingObject(selectedRingSpawn);
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
        libraryFilterInputActive=false;
        if (depth == EditorHierarchyDepth.WORLD && selection.selectedBlock() != null) {
            depth = EditorHierarchyDepth.BLOCK;
            focusRegion = EditorFocusRegion.BLOCK_PANE;
        } else if (depth == EditorHierarchyDepth.BLOCK && selection.selectedChunk() != null) {
            depth = EditorHierarchyDepth.CHUNK;
            focusRegion = EditorFocusRegion.CHUNK_PANE;
        }
        refreshLibraryBrowser();
    }

    public void ascend() {
        libraryFilterInputActive=false;
        if (depth == EditorHierarchyDepth.CHUNK) {
            depth = EditorHierarchyDepth.BLOCK;
            focusRegion = EditorFocusRegion.BLOCK_PANE;
        } else if (depth == EditorHierarchyDepth.BLOCK) {
            depth = EditorHierarchyDepth.WORLD;
            focusRegion = EditorFocusRegion.WORLD_CANVAS;
        }
        refreshLibraryBrowser();
    }

    public EditorHierarchyDepth depth() {
        return depth;
    }

    public EditorFocusRegion focusRegion() {
        return focusRegion;
    }

    public void cycleFocusRegion() {
        libraryFilterInputActive=false;
        EditorFocusRegion[] cycle = activeFocusCycle();
        for (int i = 0; i < cycle.length; i++) {
            if (cycle[i] == focusRegion) {
                focusRegion = cycle[(i + 1) % cycle.length];
                refreshLibraryBrowser();
                return;
            }
        }
        focusRegion = cycle[0];
        refreshLibraryBrowser();
    }

    public void applyPrimaryAction() {
        if (isLibraryBrowserFocused()) {
            selectLibraryEntry();
            return;
        }
        if (isSpawnEditing()) {
            placeSpawnAtCursor();
            return;
        }
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
        if (isSpawnEditing()) {
            eyedropSpawnAtCursor();
            return;
        }
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

    public EditorLibraryBrowserPane libraryBrowser() { return libraryBrowser; }

    public Block libraryPreviewBlock(int index) {
        return level!=null&&index>=0&&index<level.getBlockCount()?level.getBlock(index):null;
    }

    public Chunk libraryPreviewChunk(int index) {
        return level!=null&&index>=0&&index<level.getChunkCount()?level.getChunk(index):null;
    }

    /** Exact namespaced lookup only; callers render a generic placeholder when no sheet exists. */
    public PatternSpriteRenderer libraryObjectPreviewRenderer(String previewArtKey) {
        return objectArtProvider==null||previewArtKey==null?null:objectArtProvider.getRenderer(previewArtKey);
    }

    public void setLibraryFilter(String filter) { libraryBrowser.setFilter(filter); }

    /** Text-input seam for UI hosts that receive character/codepoint callbacks. */
    public void appendLibraryFilterText(String text) {
        Objects.requireNonNull(text, "text");
        libraryBrowser.setFilter(libraryBrowser.filter() + text);
    }

    public void backspaceLibraryFilter() {
        String current=libraryBrowser.filter();
        if(!current.isEmpty())libraryBrowser.setFilter(current.substring(0,current.length()-1));
    }

    public boolean isLibraryFilterInputActive(){return libraryFilterInputActive&&isLibraryBrowserFocused();}
    public void toggleLibraryFilterInput(){
        if(isLibraryBrowserFocused())libraryFilterInputActive=!libraryFilterInputActive;
        else libraryFilterInputActive=false;
    }
    public void endLibraryFilterInput(){libraryFilterInputActive=false;}

    public void browseLibrary(int delta) { libraryBrowser.move(delta); }

    public void selectLibraryEntry() {
        EditorLibraryBrowserPane.Entry entry=libraryBrowser.selected();
        if(entry==null)return;
        switch(entry.kind()) {
            case BLOCK -> selectBlock(entry.index());
            case CHUNK -> selectChunk(entry.index());
            case OBJECT -> {
                if(entry.objectKey()!=null)objectPalette().setObjectKey(entry.objectKey());
                else objectPalette().setObjectId(entry.stockObjectId());
            }
        }
    }

    public boolean isLibraryBrowserFocused() {
        if(spawnEditMode==EditorSpawnEditMode.OBJECTS && focusRegion==EditorFocusRegion.SPAWN_PALETTE)return true;
        return (depth==EditorHierarchyDepth.WORLD && focusRegion==EditorFocusRegion.BLOCK_PANE)
                || (depth==EditorHierarchyDepth.BLOCK && focusRegion==EditorFocusRegion.CHUNK_PANE);
    }

    private void refreshLibraryBrowser() {
        if(level==null)return;
        String retainedFilter=libraryBrowser.filter();
        if(focusRegion==EditorFocusRegion.SPAWN_PALETTE&&objectPalette!=null)libraryBrowser.showObjects(objectPalette);
        else if(depth==EditorHierarchyDepth.WORLD)libraryBrowser.showBlocks(level);
        else if(depth==EditorHierarchyDepth.BLOCK&&focusRegion==EditorFocusRegion.CHUNK_PANE)libraryBrowser.showChunks(level);
        libraryBrowser.setFilter(retainedFilter);
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
        if (depth == EditorHierarchyDepth.WORLD && isSpawnEditing()) {
            return new EditorFocusRegion[] {
                    EditorFocusRegion.WORLD_CANVAS,
                    EditorFocusRegion.SPAWN_PALETTE,
                    EditorFocusRegion.COMMAND_STRIP,
                    EditorFocusRegion.TOOLBAR
            };
        }
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
