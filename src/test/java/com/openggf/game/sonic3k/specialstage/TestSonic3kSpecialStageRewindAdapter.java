package com.openggf.game.sonic3k.specialstage;

import com.openggf.game.SpecialStageProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestSonic3kSpecialStageRewindAdapter {
    @Test
    void adapterUsesGenericSpecialStageKeyAndKeepsMissingSnapshotDefault() {
        Sonic3kSpecialStageRewindAdapter adapter =
                new Sonic3kSpecialStageRewindAdapter(new Sonic3kSpecialStageManager());

        assertEquals(SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY, adapter.key());
        assertThrows(IllegalStateException.class, adapter::resetForMissingSnapshot);
    }

    @Test
    void captureAndRestoreFailBeforeManagerIsInitialized() {
        Sonic3kSpecialStageRewindAdapter adapter =
                new Sonic3kSpecialStageRewindAdapter(new Sonic3kSpecialStageManager());
        Sonic3kSpecialStageSnapshot emptySnapshot = Sonic3kSpecialStageSnapshot.uninitializedForTest();

        assertThrows(IllegalStateException.class, adapter::capture);
        assertThrows(IllegalStateException.class, () -> adapter.restore(emptySnapshot));
    }
}
