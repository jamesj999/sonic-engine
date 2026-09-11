package com.openggf.game.sonic2.specialstage;

import com.openggf.game.SpecialStageProvider;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic2SpecialStageRewindAdapter {
    @Test
    void adapterUsesGenericSpecialStageKeyAndKeepsMissingSnapshotDefault() {
        AtomicBoolean submitted = new AtomicBoolean(false);
        Sonic2SpecialStageRewindAdapter adapter = new Sonic2SpecialStageRewindAdapter(
                new Sonic2SpecialStageManager(), submitted::get, submitted::set);

        assertEquals(SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY, adapter.key());
        assertThrows(IllegalStateException.class, adapter::resetForMissingSnapshot);
    }

    @Test
    void adapterDelegatesCaptureAndRestoreToManager() {
        Sonic2SpecialStageManager manager = new Sonic2SpecialStageManager();
        manager.markCompleted(true);
        AtomicBoolean submitted = new AtomicBoolean(false);
        Sonic2SpecialStageRewindAdapter adapter = new Sonic2SpecialStageRewindAdapter(
                manager, submitted::get, submitted::set);

        Sonic2SpecialStageProviderSnapshot snapshot = adapter.capture();
        manager.markFailed();

        adapter.restore(snapshot);

        assertEquals(Sonic2SpecialStageManager.ResultState.COMPLETED, manager.getResultState());
    }

    @Test
    void adapterRestoresPendingAndCompletedResultsPlcSubmissionState() {
        AtomicBoolean submitted = new AtomicBoolean(false);
        Sonic2SpecialStageRewindAdapter adapter = new Sonic2SpecialStageRewindAdapter(
                new Sonic2SpecialStageManager(), submitted::get, submitted::set);

        Sonic2SpecialStageProviderSnapshot pending = adapter.capture();
        submitted.set(true);
        adapter.restore(pending);
        assertFalse(submitted.get(), "pending snapshot must re-arm the provider retry");

        submitted.set(true);
        Sonic2SpecialStageProviderSnapshot completed = adapter.capture();
        submitted.set(false);
        adapter.restore(completed);
        assertTrue(submitted.get(), "completed snapshot must retain its no-duplicate latch");
    }
}
