package com.openggf.tools.audio.completerun;

import com.openggf.tools.audio.completerun.CompleteRunAudioProducer.Request;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.ProducerKind;
import java.util.Objects;

/** Shared fail-closed binding gate used before any producer input is opened. */
public final class CompleteRunAudioOpenGgfProducerPreflight {
    private CompleteRunAudioOpenGgfProducerPreflight() {
    }

    public static CompleteRunAudioProfile requirePinned(Request request,
            String fixedProfileId) throws Exception {
        Objects.requireNonNull(request, "producer request");
        Objects.requireNonNull(fixedProfileId, "fixed profile ID");
        if (request.producerKind() != ProducerKind.OPENGGF) {
            throw new IllegalArgumentException(
                    "OpenGGF producer requires OPENGGF producer kind");
        }
        if (!fixedProfileId.equals(request.profileId())) {
            throw new IllegalArgumentException(
                    "OpenGGF producer profile does not match its fixed game binding");
        }
        return CompleteRunAudioProducerRegistry.requirePinned(
                fixedProfileId, ProducerKind.OPENGGF);
    }
}
