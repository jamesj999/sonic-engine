package com.openggf.tools.audio.completerun.s2;

import com.openggf.tools.audio.completerun.CompleteRunAudioOpenGgfProducerPreflight;
import com.openggf.tools.audio.completerun.CompleteRunAudioProducer;

/** Fixed S2 engine producer; Task 7 installs its authenticated runtime identity. */
public final class S2CompleteRunOpenGgfProducer implements CompleteRunAudioProducer {
    @Override
    public void capture(Request request) throws Exception {
        CompleteRunAudioOpenGgfProducerPreflight.requirePinned(
                request, S2CompleteRunAudioProfile.ID);
        throw new IllegalStateException(
                "pinned S2 OpenGGF capture startup is not installed");
    }
}
