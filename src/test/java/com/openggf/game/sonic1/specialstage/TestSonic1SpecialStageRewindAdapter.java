package com.openggf.game.sonic1.specialstage;

import com.openggf.game.SpecialStageProvider;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic1SpecialStageRewindAdapter {
    @Test
    void adapterRestoresProviderOwnedResultsPlcSubmissionState() {
        AtomicBoolean submitted = new AtomicBoolean(false);
        Sonic1SpecialStageRewindAdapter adapter = new Sonic1SpecialStageRewindAdapter(
                new Sonic1SpecialStageManager(), submitted::get, submitted::set);

        assertEquals(SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY, adapter.key());
        Sonic1SpecialStageProviderSnapshot pending = adapter.capture();
        submitted.set(true);
        adapter.restore(pending);

        assertFalse(submitted.get(), "restoring a pending results snapshot must re-arm its PLC retry");
        assertThrows(IllegalStateException.class, adapter::resetForMissingSnapshot);
    }

    @Test
    void adapterRestoresCompletedResultsPlcSubmissionState() {
        AtomicBoolean submitted = new AtomicBoolean(true);
        Sonic1SpecialStageRewindAdapter adapter = new Sonic1SpecialStageRewindAdapter(
                new Sonic1SpecialStageManager(), submitted::get, submitted::set);

        Sonic1SpecialStageProviderSnapshot completed = adapter.capture();
        submitted.set(false);
        adapter.restore(completed);

        assertTrue(submitted.get(), "restoring a completed results snapshot must retain its no-duplicate latch");
    }
}
