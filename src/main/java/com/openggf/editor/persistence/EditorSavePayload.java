package com.openggf.editor.persistence;

import java.util.List;

public record EditorSavePayload(
        List<BlockState> blocks,
        List<ChunkState> chunks,
        List<MapCell> mapCells) {

    public EditorSavePayload {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        mapCells = mapCells == null ? List.of() : List.copyOf(mapCells);
    }

    public record BlockState(int index, int[] state) {
    }

    public record ChunkState(int index, int[] state) {
    }

    public record MapCell(int layer, int x, int y, int blockIndex) {
    }
}
