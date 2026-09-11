package com.openggf.editor.persistence;

public record EditorSaveEnvelope(
        int version,
        String gameCode,
        int zone,
        int act,
        String savedAt,
        EditorSavePayload payload,
        String hash) {
}
