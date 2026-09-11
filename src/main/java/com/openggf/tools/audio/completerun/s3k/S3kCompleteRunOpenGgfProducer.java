package com.openggf.tools.audio.completerun.s3k;

import com.openggf.tools.audio.completerun.CompleteRunAudioOpenGgfProducerPreflight;
import com.openggf.tools.audio.completerun.CompleteRunAudioProducer;

/** Fixed S3K engine producer; Task 7 installs its authenticated runtime identity. */
public final class S3kCompleteRunOpenGgfProducer implements CompleteRunAudioProducer {
    @Override
    public void capture(Request request) throws Exception {
        CompleteRunAudioOpenGgfProducerPreflight.requirePinned(
                request, S3kCompleteRunAudioProfile.ID);
        throw new IllegalStateException(
                "pinned S3K OpenGGF capture startup is not installed");
    }
}
