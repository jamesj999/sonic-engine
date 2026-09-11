package com.openggf.editor;

public record EditorSelectionState(
        Integer selectedBlock,
        Integer selectedChunk
) {
    public static EditorSelectionState empty() {
        return new EditorSelectionState(null, null);
    }
}
