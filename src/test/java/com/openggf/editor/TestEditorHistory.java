package com.openggf.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestEditorHistory {

    @Test
    void history_callsCommandHooksInUndoRedoOrder() {
        StringBuilder log = new StringBuilder();
        EditorHistory history = new EditorHistory();

        history.execute(new EditorCommand() {
            @Override
            public void apply() {
                log.append("A");
            }

            @Override
            public void undo() {
                log.append("U");
            }
        });

        assertTrue(history.undo());
        assertTrue(history.redo());

        assertEquals("AUA", log.toString());
    }

    @Test
    void history_undoRedoReturnFalseWhenNoCommandRuns() {
        EditorHistory history = new EditorHistory();

        assertFalse(history.undo());
        assertFalse(history.redo());
    }

    @Test
    void history_undoRetainsCommandWhenUndoThrows() {
        StringBuilder log = new StringBuilder();
        EditorHistory history = new EditorHistory();

        history.execute(new EditorCommand() {
            private int undoCalls;

            @Override
            public void apply() {
                log.append("A");
            }

            @Override
            public void undo() {
                undoCalls++;
                log.append(undoCalls == 1 ? "U" : "u");
                if (undoCalls == 1) {
                    throw new IllegalStateException("boom");
                }
            }
        });

        assertThrows(IllegalStateException.class, history::undo);

        history.undo();
        history.redo();

        assertEquals("AUuA", log.toString());
    }

    @Test
    void history_redoRetainsCommandWhenRedoThrows() {
        StringBuilder log = new StringBuilder();
        EditorHistory history = new EditorHistory();

        history.execute(new EditorCommand() {
            private int applyCalls;

            @Override
            public void apply() {
                applyCalls++;
                log.append(applyCalls == 1 ? "A" : applyCalls == 2 ? "R" : "r");
                if (applyCalls == 2) {
                    throw new IllegalStateException("boom");
                }
            }

            @Override
            public void undo() {
                log.append("U");
            }
        });

        history.undo();
        assertThrows(IllegalStateException.class, history::redo);

        history.redo();

        assertEquals("AURr", log.toString());
    }
}


