package com.openggf.game.mutation;

import com.openggf.game.rewind.snapshot.MutationPipelineSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestMutationPipelineRewindSnapshot {

    @Test
    void emptyPipelineRoundTrips() {
        ZoneLayoutMutationPipeline pipeline = new ZoneLayoutMutationPipeline();
        MutationPipelineSnapshot snap = pipeline.capture();
        pipeline.restore(snap);
        assertTrue(pipeline.isEmpty());
    }

    @Test
    void queuedIntentsRoundTrip() {
        ZoneLayoutMutationPipeline pipeline = new ZoneLayoutMutationPipeline();
        LayoutMutationIntent intent1 = ctx -> MutationEffects.NONE;
        LayoutMutationIntent intent2 = ctx -> MutationEffects.NONE;
        pipeline.queue(intent1);
        pipeline.queue(intent2);
        MutationPipelineSnapshot snap = pipeline.capture();
        pipeline.clear();
        assertTrue(pipeline.isEmpty());
        pipeline.restore(snap);
        assertFalse(pipeline.isEmpty());
        assertEquals(2, snap.queued().size());
    }

    @Test
    void keyIsMutationPipeline() {
        assertEquals("mutation-pipeline", new ZoneLayoutMutationPipeline().key());
    }
}
